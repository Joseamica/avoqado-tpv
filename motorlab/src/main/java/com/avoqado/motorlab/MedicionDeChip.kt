package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import com.pax.jemv.clcommon.OnlineResult
import com.pax.jemv.clcommon.RetCode
import com.paxsz.module.emv.process.contact.CandidateAID
import com.paxsz.module.emv.process.contact.IEmvTransProcessListener
import com.paxsz.module.emv.process.entity.IssuerRspData
import com.paxsz.module.emv.process.enums.TransResultEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Medición de chip (rumbo A del 26-nov, `docs/audit/2026-11-21-sdk-propio-fase-3/motor-chip-parada.md`): cómo se porta el kernel
// de CONTACTO de la PAX antes de diseñar el chip del motor. Sin dinero y sin red: nada sale a Blumon y la segunda generación se
// cierra con una respuesta inventada. La pantalla sólo enseña tiempos y códigos (9F34 y 95 en hex): nunca la banda, el PAN, un
// criptograma, el ARPC ni un script. No toca el motor.

internal enum class ModoDeSondeo(val texto: String) { CHIP("sólo chip"), CHIP_Y_ANTENA("chip + antena") }

internal enum class ModoDeCierre(val texto: String) {
    BANCO_NO_CONTESTO("banco no contestó"),
    APROBADO_DE_MENTIRA("aprobado con datos de mentira"),
}

/** Qué lector ganó el sondeo. */
internal enum class Gano { CHIP, ANTENA, VENCIO, PARADO, OTRO }

/**
 * Lo nativo de la medición; lo de verdad es [PaxDeLaTerminal]. Todo corre en el hilo del kernel de la medición, salvo
 * [cancelarNip] y [pararSondeo], que llegan de «Cancelar medición».
 */
internal interface ChipDePrueba {
    /** `getIcc().detect(0)`. Si lanza, NO es «retirada». */
    fun tarjetaPuesta(): Boolean

    /** `EmvProcess.preTransProcess` con la lista de AID de contacto. 0 = bien. */
    fun prepararContacto(importeCentavos: Long): Int

    /** `ClssProcess.preTransProcess`, la misma preparación de la lectura seca. */
    fun prepararAntena(importeCentavos: Long): Int

    /** `polling(ICC, …)` o `polling(ICC_PICC, …)`. */
    fun sondearChip(conAntena: Boolean, esperaMs: Long): Gano

    /** `setIDevice`, el oyente y `startTransProcess(false)`: hasta la primera generación (pedir en línea). */
    fun leerChip(oyente: IEmvTransProcessListener): Kernel

    /** `EmvProcess.getTlv`. La medición sólo pide 9F34 y 95 (lo vigila `PrivacidadTest`). */
    fun tagDelChip(tag: Int): ByteArray?

    /** `completeTransProcess`: por dentro, `EMVCompleteTrans` (la segunda generación). */
    fun completar(respuesta: IssuerRspData): Kernel

    fun pararSondeo()
    fun cerrarIcc()
    fun cerrarPicc()

    /** `getPed(EPedType.INTERNAL).cancelInput()`. */
    fun cancelarNip()
}

/** ARPC inventado y obvio (el del plan del motor): nunca uno de una tarjeta. */
internal const val ARPC_DE_MENTIRA = "0A1B2C3D4E5F6A7B"

/** Autorización (89) inventada. */
internal const val AUTORIZACION_DE_MENTIRA = "LAB001"

private const val IMPORTE_DE_MEDICION = 2_550L // el de la lectura seca; no cobra
private const val REAPERTURA_MS = 500L
private const val CADA_RETIRO_MS = 200L

/** Lo que el kernel recibe como «respuesta del banco», sin red. Sin script: con el banco que aprueba, 8A = «00» y 91 = ARPC + 8A, como el SDK. */
internal fun respuestaDeMentira(c: ModoDeCierre): IssuerRspData = IssuerRspData().apply {
    when (c) {
        ModoDeCierre.BANCO_NO_CONTESTO -> onlineResult = OnlineResult.ONLINE_FAILED.toByte()
        ModoDeCierre.APROBADO_DE_MENTIRA -> {
            onlineResult = OnlineResult.ONLINE_APPROVE.toByte()
            respCode = "00".toByteArray(Charsets.US_ASCII)
            authCode = AUTORIZACION_DE_MENTIRA.toByteArray(Charsets.US_ASCII)
            authData = (ARPC_DE_MENTIRA + "3030").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}

internal fun hex(b: ByteArray?): String = b?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02X".format(it) } ?: "—"

internal enum class Paso(val texto: String) {
    AL_EMPEZAR("Tarjeta puesta al empezar (icc.detect)"),
    PREPARAR_CHIP("Preparar el kernel de contacto"),
    PREPARAR_ANTENA("Preparar el kernel sin contacto"),
    SONDEO("Sondeo (tope 60 s)"),
    PRIMERA("Lectura y primera generación"),
    CODIGOS("Códigos"),
    SEGUNDA("Segunda generación (EMVCompleteTrans)"),
    RETIRO("Retiro de la tarjeta (tope 60 s)"),
    CIERRE("Cierre del lector"),
    REAPERTURA("Segundo sondeo inmediato (0.5 s)"),
}

/** Una llamada nativa medida, en ms desde el toque. [abandonada] = la medición dejó de esperarla en el tope. */
internal class Llamada(val paso: Paso, val desde: Long, val aviso: Long) {
    var hasta: Long? = null
    var resultado: String? = null
    var abandonada = false
}

/** El registro de una medición: sólo tiempos (ms desde el toque) y códigos. Se lee y se escribe bajo su propio candado. */
internal class Medicion(val numero: Int, val sondeo: ModoDeSondeo, val cierre: ModoDeCierre, private val t0: Long, val tope: Long) {
    val llamadas = mutableListOf<Llamada>()

    /** Cuándo el kernel confirmó la tarjeta (`showConfirmCard`, después de leer los datos de la aplicación). */
    var msLectura: Long? = null
    val nip = mutableListOf<String>()

    @Volatile var cancelada = false
    var msCancelar: Long? = null
    var cancelar: String? = null
    var fin: Long? = null

    @Volatile var lectorSuelto = false

    fun ms(ahora: Long) = ahora - t0
}

internal enum class Ocupacion { A_TIEMPO, TARDE, EN_CURSO, SIGUE_OCUPADA, NO_REGRESO }

/** ¿Siguió ocupando el hilo? `TARDE` = regresó, pero después de [aviso] (también la que regresó ya abandonada). */
internal fun ocupacion(dura: Long, regreso: Boolean, abandonada: Boolean, aviso: Long): Ocupacion = when {
    regreso -> if (dura >= aviso) Ocupacion.TARDE else Ocupacion.A_TIEMPO
    abandonada -> Ocupacion.NO_REGRESO
    dura >= aviso -> Ocupacion.SIGUE_OCUPADA
    else -> Ocupacion.EN_CURSO
}

/** Lo que enseña la pantalla (y lee `uiautomator`). [ahora] es del mismo reloj que el toque. */
internal fun textoDeLaMedicion(m: Medicion, ahora: Long): String = synchronized(m) {
    val t = m.ms(ahora)
    buildString {
        appendLine("Medición n.º ${m.numero} · ${m.sondeo.texto} · cierre: ${m.cierre.texto} · sin dinero, sin red")
        for (l in m.llamadas) {
            appendLine("${l.paso.texto}: ${l.resultado ?: "…"} · ${tiempo(l, t, m.tope)}")
            if (l.paso == Paso.PRIMERA) {
                val insercion = m.llamadas.firstOrNull { it.paso == Paso.SONDEO }?.hasta
                appendLine("  inserción → lectura: ${entre(insercion, m.msLectura)} · primera generación: ${entre(m.msLectura, l.hasta)}")
                appendLine("Oyente de NIP: " + m.nip.ifEmpty { listOf("no se llamó") }.joinToString("; "))
            }
        }
        m.msCancelar?.let { c ->
            val viva = m.llamadas.lastOrNull { it.desde <= c } // van una tras otra: la última que empezó es la que corría
            appendLine(
                "Cancelar: a los $c ms · ${m.cancelar ?: "…"}" + when {
                    viva == null || (viva.hasta ?: c) < c -> " · no había llamada en curso"
                    viva.hasta == null -> " · «${viva.paso.texto}» sigue sin regresar"
                    else -> " · «${viva.paso.texto}» regresó ${viva.hasta!! - c} ms después"
                },
            )
        }
        append(
            when {
                m.fin == null -> "En curso ($t ms)"
                m.lectorSuelto -> "Terminada a los ${m.fin} ms · lector suelto"
                m.llamadas.any { it.abandonada && it.hasta == null } ->
                    "Se dejó de esperar a los ${m.fin} ms: el lector sigue apartado hasta que regrese la llamada"
                else -> "Soltando el lector (${m.fin} ms)"
            },
        )
    }
}

private fun entre(a: Long?, b: Long?): String = if (a != null && b != null) "${b - a} ms" else "—"

private fun tiempo(l: Llamada, t: Long, tope: Long): String {
    val dura = (l.hasta ?: t) - l.desde
    return when (ocupacion(dura, l.hasta != null, l.abandonada, l.aviso)) {
        Ocupacion.A_TIEMPO -> "$dura ms"
        Ocupacion.TARDE -> "$dura ms · SIGUIÓ OCUPADA más de ${l.aviso / 1000} s" +
            if (l.abandonada) " y regresó después del tope de ${tope / 1000} s (ALTO)" else ""
        Ocupacion.EN_CURSO -> "en curso ($dura ms)"
        Ocupacion.SIGUE_OCUPADA -> "SIGUE OCUPADA ($dura ms, más de ${l.aviso / 1000} s)"
        Ocupacion.NO_REGRESO -> "NO REGRESÓ en ${tope / 1000} s (ALTO)"
    }
}

private fun textoDelKernel(k: Kernel) = "código ${k.codigo} · ${k.resultado ?: "—"} · CVM ${k.cvm ?: "—"}"

private fun falla(t: Throwable) = "falló (${t.javaClass.simpleName})" // sólo la clase: el mensaje puede traer la tarjeta

/** El oyente del kernel de contacto: anota cuándo confirmó la tarjeta y cada llamada del NIP, y salta el NIP (como `bypassPin`). */
internal class OyenteDeMedicion(private val m: Medicion, private val ahora: () -> Long) : IEmvTransProcessListener {
    override fun onWaitAppSelect(isFirstSelect: Boolean, candList: MutableList<CandidateAID>?): Int = 0 // la primera

    override fun showConfirmCard(): Int {
        synchronized(m) { if (m.msLectura == null) m.msLectura = m.ms(ahora()) }
        return RetCode.EMV_OK
    }

    /** Nunca mira [pinData] (en modo PCI la app no ve dígitos): sólo si es en línea y los intentos que quedan. */
    override fun onCardHolderPwd(bOnlinePin: Boolean, leftTimes: Int, pinData: ByteArray?): Int {
        synchronized(m) { m.nip += (if (bOnlinePin) "en línea" else "fuera de línea") + " (intentos $leftTimes) a los ${m.ms(ahora())} ms" }
        return RetCode.EMV_NO_PASSWORD
    }
}

/**
 * «Medir chip (sin dinero)»: una medición a la vez, dueña del lector como un cobro (el folio de [LectorPax]: mientras mide,
 * «Cobrar» y la lectura seca se rechazan, y al revés). Cada llamada nativa corre en UN hilo del kernel; la medición la espera
 * hasta [aviso] (la pantalla dice «sigue ocupada») y hasta [tope] (dice «no regresó» y ya no toca el lector). El folio se suelta
 * en ese mismo hilo, así que una llamada que no regresa deja el lector apartado hasta que regrese, y entonces se ve cuándo.
 */
internal class MedidorDeChip(
    private val chip: ChipDePrueba,
    private val lector: LectorPax,
    private val ahora: () -> Long,
    private val aviso: Long = 10_000,
    private val tope: Long = 120_000,
    private val esperaTarjeta: Long = 60_000,
    private val esperaRetiro: Long = 60_000,
) {
    private val kernel = Executors.newSingleThreadExecutor { Thread(it, "motorlab-medicion").apply { isDaemon = true } }
    private val corriendo = AtomicBoolean(false)
    private val _texto = MutableStateFlow("Medir chip: sin tocar")
    val texto: StateFlow<String> = _texto.asStateFlow()

    @Volatile private var actual: Medicion? = null
    @Volatile private var avisoDeLaPantalla: String? = null
    @Volatile private var medidas = 0

    fun medir(sondeo: ModoDeSondeo, cierre: ModoDeCierre) {
        if (!corriendo.compareAndSet(false, true)) return avisar("no se acepta «Medir»: hay una medición en curso")
        thread(isDaemon = true, name = "motorlab-medidor") {
            try {
                correr(sondeo, cierre)
            } finally {
                corriendo.set(false)
            }
        }
    }

    /** `cancelInput` y `stopPolling` desde otro hilo, para medir qué destraba al kernel. Vale mientras el lector siga apartado. */
    fun cancelar() {
        val m = actual?.takeIf { !it.lectorSuelto } ?: return avisar("no hay medición que cancelar")
        val ya = synchronized(m) { (m.msCancelar != null).also { if (!it) m.msCancelar = m.ms(ahora()) } }
        if (ya) return avisar("ya se pidió cancelar")
        m.cancelada = true
        publicar()
        thread(isDaemon = true, name = "motorlab-cancelar") {
            val nip = intento { chip.cancelarNip() }
            val sondeo = intento { chip.pararSondeo() }
            synchronized(m) { m.cancelar = "cancelInput $nip · stopPolling $sondeo" }
            publicar()
        }
    }

    private fun correr(sondeo: ModoDeSondeo, cierre: ModoDeCierre) {
        val folio = AttemptId.nuevo()
        if (!lector.siguienteFolio(folio)) {
            return avisar("no se acepta «Medir»: el lector tiene otro dueño (un cobro, una lectura seca o una llamada que no ha regresado)")
        }
        avisoDeLaPantalla = null
        val m = Medicion(++medidas, sondeo, cierre, ahora(), tope)
        actual = m
        try {
            pasos(m)
        } finally {
            corriendo.set(false) // antes de que la pantalla diga que terminó: un «Medir» que llegue después ya no choca con ésta
            synchronized(m) { m.fin = m.ms(ahora()) }
            kernel.execute {
                lector.soltarFolio(folio)
                m.lectorSuelto = true
                publicar()
            }
            publicar()
        }
    }

    /** En orden; `null` de [llamada] = no regresó en el tope: ya no se toca el lector (ni se cierra). */
    private fun pasos(m: Medicion) {
        llamada(m, Paso.AL_EMPEZAR, { if (it) "sí" else "no" }) { chip.tarjetaPuesta() } ?: return
        val contacto = llamada(m, Paso.PREPARAR_CHIP, { "código $it" }) { chip.prepararContacto(IMPORTE_DE_MEDICION) } ?: return
        val conAntena = m.sondeo == ModoDeSondeo.CHIP_Y_ANTENA
        if (conAntena) llamada(m, Paso.PREPARAR_ANTENA, { "código $it" }) { chip.prepararAntena(IMPORTE_DE_MEDICION) } ?: return
        if (contacto.getOrNull() == RetCode.EMV_OK && !m.cancelada && !leer(m, conAntena)) return
        llamada(m, Paso.CIERRE, { it }) { cerrar(conAntena) } ?: return
        llamada(m, Paso.REAPERTURA, { it }) {
            val abrio = runCatching { chip.sondearChip(false, REAPERTURA_MS) }.fold({ "abrió · $it" }) { "no abrió (${it.javaClass.simpleName})" }
            "$abrio · cierre: ${cerrar(false)}"
        }
    }

    /** `false` = una llamada no regresó. */
    private fun leer(m: Medicion, conAntena: Boolean): Boolean {
        val gano = llamada(m, Paso.SONDEO, { it.name }, esperaTarjeta + aviso) { chip.sondearChip(conAntena, esperaTarjeta) } ?: return false
        if (gano.getOrNull() != Gano.CHIP) return true
        val primera = llamada(m, Paso.PRIMERA, ::textoDelKernel) { chip.leerChip(OyenteDeMedicion(m, ahora)) } ?: return false
        val k = primera.getOrNull()
        if (k != null && k.codigo == RetCode.EMV_OK) {
            llamada(m, Paso.CODIGOS, { it }) { "9F34 ${hex(chip.tagDelChip(0x9F34))} · 95 ${hex(chip.tagDelChip(0x95))}" } ?: return false
            if (k.resultado == TransResultEnum.RESULT_REQ_ONLINE && !m.cancelada) {
                llamada(m, Paso.SEGUNDA, ::textoDelKernel) { chip.completar(respuestaDeMentira(m.cierre)) } ?: return false
            }
        }
        return llamada(m, Paso.RETIRO, { it }, esperaRetiro + aviso) { esperarRetiro(m) } != null
    }

    private fun esperarRetiro(m: Medicion): String {
        val limite = ahora() + esperaRetiro
        while (chip.tarjetaPuesta()) { // si `detect` lanza, el paso sale «falló»: un error del lector NO es «retirada»
            if (m.cancelada) return "dejó de esperar: se tocó Cancelar"
            if (ahora() >= limite) return "no la retiraron en ${esperaRetiro / 1000} s"
            Thread.sleep(CADA_RETIRO_MS)
        }
        return "retirada"
    }

    /** Cada paso con su resultado; nunca se traga un error (el laboratorio viejo lo hacía). */
    private fun cerrar(conAntena: Boolean): String {
        val fallas = listOfNotNull(
            falloDe("stopPolling") { chip.pararSondeo() },
            falloDe("icc.close") { chip.cerrarIcc() },
            if (conAntena) falloDe("picc.close") { chip.cerrarPicc() } else null,
        )
        return if (fallas.isEmpty()) "todo bien" else "falló: " + fallas.joinToString()
    }

    private fun falloDe(que: String, b: () -> Unit): String? = runCatching(b).exceptionOrNull()?.let { "$que (${it.javaClass.simpleName})" }

    private fun intento(b: () -> Unit): String = runCatching(b).fold({ "bien" }, ::falla)

    /** Corre [b] en el hilo del kernel y lo espera hasta [avisoMs] y luego hasta [tope]. `null` = no regresó (queda abandonada). */
    private fun <T> llamada(m: Medicion, paso: Paso, describir: (T) -> String, avisoMs: Long = aviso, b: () -> T): Result<T>? {
        val l = Llamada(paso, m.ms(ahora()), avisoMs)
        synchronized(m) { m.llamadas += l }
        val f = kernel.submit(
            Callable {
                runCatching(b).also { r ->
                    synchronized(m) {
                        l.resultado = r.fold(describir, ::falla)
                        l.hasta = m.ms(ahora())
                    }
                }
            },
        )
        publicar()
        val r = esperar(f, avisoMs) ?: run {
            publicar() // «sigue ocupada»
            esperar(f, (tope - avisoMs).coerceAtLeast(0))
        }
        if (r == null) synchronized(m) { l.abandonada = true }
        publicar()
        return r
    }

    private fun <T> esperar(f: Future<T>, ms: Long): T? = try {
        f.get(ms, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    private fun avisar(texto: String) {
        avisoDeLaPantalla = texto
        publicar()
    }

    @Synchronized private fun publicar() {
        val m = actual
        _texto.value = listOfNotNull(avisoDeLaPantalla?.let { "Aviso: $it" }, m?.let { textoDeLaMedicion(it, ahora()) } ?: "Medir chip: sin tocar")
            .joinToString("\n")
    }
}
