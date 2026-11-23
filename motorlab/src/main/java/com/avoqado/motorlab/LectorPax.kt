package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Cvm
import com.avoqado.cobropropio.dominio.Entrada
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.ResultadoKernel
import com.pax.jemv.clcommon.KernType
import com.pax.jemv.clcommon.RetCode
import com.paxsz.module.emv.process.enums.CvmResultEnum
import com.paxsz.module.emv.process.enums.TransResultEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Lo que el adaptador le pide a la PAX (bloque 4-L · L-c). La de verdad es [PaxDeLaTerminal]; las pruebas JVM usan una de
 * mentira, porque `AppManager`, `ClssProcess` y `DeviceManager` cargan librerías nativas. Todo corre en el hilo del lector,
 * salvo [pararSondeo].
 */
internal interface Pax {
    /** `AppManager.init` (la primera vez), `EmvTransParam` de VENTA con este importe y `ClssProcess.preTransProcess`. 0 = bien. */
    fun preparar(importeCentavos: Long): Int

    /** `polling(PICC, esperaMs)`: bloquea hasta que llega una tarjeta, vence o alguien llama [pararSondeo]. */
    fun sondear(esperaMs: Long): Sondeo

    /** `stopPolling`: despierta a [sondear] desde otro hilo; también es el primer paso del cierre. */
    fun pararSondeo()

    /** `setIDevice(DeviceImplNeptune)`, el oyente sin contacto y `startTransProcess`. */
    fun leerConElKernel(): Kernel

    /** `getTlv` del kernel: sus bytes, o null si no lo devolvió. */
    fun tag(tag: Int): ByteArray?

    /** El último paso del cierre: el lector sin contacto interno. */
    fun cerrarPicc()
}

internal enum class Sondeo { TARJETA, VENCIO, PARADO, OTRO }

/** Lo que dijo el kernel, sin traducir. [tipo] es `KernType.kernType` (null si no eligió kernel). */
internal class Kernel(val tipo: Int?, val codigo: Int, val resultado: TransResultEnum?, val cvm: CvmResultEnum?)

/**
 * Por qué terminó una lectura. `DETENIDA` = `detener` de ese folio o el motor dejó de esperar; `KERNEL` = el kernel regresó un
 * código ≠ 0; `SONDEO` = el sondeo terminó sin tarjeta y sin que nadie lo parara; `CIERRE_PENDIENTE` = el cierre que falló en
 * el intento anterior volvió a fallar, y este intento no preparó ni sondeó.
 */
internal enum class Causa { LEIDA, SIN_BANDA, KERNEL, VENCIDA, DETENIDA, SIN_RED, SIN_FOLIO, NO_ES_VENTA, PREPARACION, SONDEO, CIERRE_PENDIENTE, ERROR }

/**
 * Cómo quedó el lector tras un intento (tres desenlaces, no dos): `NO_SE_ABRIO` = este intento no abrió el lector;
 * `ACREDITADO` = se cerró y cada paso salió bien («lector cerrado»); `SIN_ACREDITAR` = algún paso falló, y el siguiente
 * intento lo reintenta antes de preparar.
 */
internal enum class Cierre { NO_SE_ABRIO, ACREDITADO, SIN_ACREDITAR }

/** Lo único que el laboratorio puede enseñar de una lectura: nunca la banda, el PAN, el criptograma ni el valor de un tag. */
internal data class Informe(
    val folio: AttemptId?,
    val causa: Causa,
    /** El de la preparación que falló o el del kernel. */
    val codigo: Int?,
    val marca: Marca?,
    val resultado: ResultadoKernel?,
    val cvm: Cvm?,
    /** El 9F02 que puso el kernel, en centavos: así se ve que es el importe real. */
    val importeDelKernel: Long?,
    /** Qué tags devolvió el kernel: sólo los números. */
    val tagsPresentes: Set<Int>,
    val cierre: Cierre,
    /** Los pasos del cierre que fallaron (vacío salvo con `SIN_ACREDITAR`). */
    val fallasDelCierre: List<String>,
    /** Sólo la clase de un error de la PAX: su mensaje puede traer datos de la tarjeta. */
    val error: String?,
)

/**
 * El lector sin contacto de la PAX para el motor (bloque 4-L · L-c). Prepara con el importe REAL y sondea SÓLO el lector sin
 * contacto. El kernel lee, y el adaptador entrega los tags CRUDOS: la admisión es del motor. Un solo hilo es dueño de la PAX.
 *
 * `leer` no recibe el folio (`LectorDeTarjeta`): quien cobra llama [siguienteFolio] antes de cada `cobrar`, y ese folio es el
 * ÚNICO dueño del lector hasta que su `leer` cierra (o [soltarFolio], si el motor no llegó a leer). Fases bajo [candado]:
 * `APUNTADO → PREPARANDO → SONDEANDO → CERRANDO → CERRADO`. El informe sale DESPUÉS de cerrar el lector.
 *
 * [detener] y la cancelación de `leer` (el motor dejó de esperar) marcan al dueño. El adaptador no INICIA el sondeo de un folio
 * marcado; si ya decidió iniciarlo, ORDENA pararlo (`stopPolling` sólo con el sondeo activo y con el candado tomado, para que
 * nunca le caiga al sondeo de otro folio). Al volver del sondeo, si ya está marcado no se llama al kernel; una marca que cae
 * después de esa revisión deja correr el kernel. La decisión final va después del cierre y la marca manda sobre toda salida
 * anterior al sondeo: un folio detenido nunca entrega una `Lectura` y sale `Cancelada`. Que la PAX obedezca una parada que cae
 * justo cuando empieza a sondear se mide en el aparato.
 */
internal class LectorPax(private val pax: Pax, private val red: Red) : LectorDeTarjeta {

    private enum class Fase { APUNTADO, PREPARANDO, SONDEANDO, CERRANDO, CERRADO }

    private class Turno(val folio: AttemptId) {
        var fase = Fase.APUNTADO
        var detenido = false
    }

    private val candado = Any()

    /** El dueño del lector, bajo [candado]: desde [siguienteFolio] hasta que su `leer` cerró, o hasta [soltarFolio]. */
    private var turno: Turno? = null

    /** Un cierre que falló y que el siguiente intento reintenta antes de preparar. Sólo en el hilo del lector. */
    private var cierrePendiente = false

    private val hilo = Executors.newSingleThreadExecutor { Thread(it, "motorlab-lector").apply { isDaemon = true } }
    private val ultimo = MutableStateFlow<Informe?>(null)

    /** El informe de la última lectura (o del último intento sin folio). Se compara su `folio`. */
    val informes: StateFlow<Informe?> = ultimo.asStateFlow()

    /**
     * Apunta el folio de la siguiente `leer` y lo hace DUEÑO del lector. Si otro folio sigue siendo dueño (esperando su `leer`
     * o leyendo), se rechaza (`false`) y nada cambia: el laboratorio cobra uno a la vez (pre-plan §5). Quien cobra lo llama DENTRO
     * de la corrutina dueña del intento, justo antes de `cobrar`.
     */
    fun siguienteFolio(folio: AttemptId): Boolean = synchronized(candado) {
        if (turno != null) return false
        turno = Turno(folio)
        true
    }

    /**
     * El motor terminó ese folio sin llamar a `leer` (no salió antes de pedir la tarjeta), o el intento abortó antes de
     * arrancar el motor: se suelta su reserva. Sólo un dueño que todavía no empieza a leer; uno que lee se suelta solo al cerrar.
     * `false` = no había nada que soltar. Que una vista deje de escuchar el `Flow` NO es razón para llamarlo: el motor puede
     * seguir vivo y llamar a `leer` después.
     */
    fun soltarFolio(folio: AttemptId): Boolean = synchronized(candado) {
        val t = turno
        if (t == null || t.folio != folio || t.fase != Fase.APUNTADO) return false
        turno = null
        true
    }

    /** Sin esperar: si ese folio es el dueño y no sondea todavía, ya no sondeará; si sondea, ordena pararlo; si no, nada. */
    fun detener(folio: AttemptId) {
        synchronized(candado) { turno?.takeIf { it.folio == folio }?.let { parar(it) } }
    }

    /** Bajo [candado]: marca al dueño, y ordena `stopPolling` sólo si sondea. */
    private fun parar(t: Turno) {
        t.detenido = true
        if (t.fase == Fase.SONDEANDO) runCatching { pax.pararSondeo() }
    }

    override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura {
        val t = synchronized(candado) { turno?.takeIf { it.fase == Fase.APUNTADO }?.also { it.fase = Fase.PREPARANDO } }
        if (t == null) {
            ultimo.value = Hoja().apply { causa = Causa.SIN_FOLIO }.informe(null)
            return ResultadoDeLectura.Cancelada
        }
        return suspendCancellableCoroutine { c ->
            // El motor deja de esperar (su reloj de lectura o el fin de su `fondo`): igual que detener ese folio.
            c.invokeOnCancellation { synchronized(candado) { parar(t) } }
            hilo.execute { c.resume(correr(t, operacion, importeCentavos, espera)) }
        }
    }

    /** Lo que se va sabiendo de un intento; sólo en el hilo del lector. Nunca guarda valores de tags. */
    private class Hoja {
        var causa = Causa.ERROR
        var codigo: Int? = null
        var marca: Marca? = null
        var resultado: ResultadoKernel? = null
        var cvm: Cvm? = null
        var importeDelKernel: Long? = null
        var tagsPresentes: Set<Int> = emptySet()
        var sondeo = false
        var cierre = Cierre.NO_SE_ABRIO
        var fallasDelCierre: List<String> = emptyList()
        var error: String? = null

        fun informe(folio: AttemptId?) =
            Informe(folio, causa, codigo, marca, resultado, cvm, importeDelKernel, tagsPresentes, cierre, fallasDelCierre, error)
    }

    /** En el hilo del lector. Nunca lanza: pase lo que pase, el lector se cierra, el informe sale y el dueño se suelta. */
    private fun correr(t: Turno, operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura {
        val h = Hoja()
        val leida = try {
            intentar(t, operacion, importeCentavos, espera, h)
        } catch (e: Throwable) {
            // También un `UnsatisfiedLinkError` de las librerías de PAX. Sólo la clase: el mensaje puede traer la tarjeta.
            h.causa = Causa.ERROR
            h.error = e.javaClass.simpleName
            ResultadoDeLectura.Cortada(SIN_CODIGO)
        }
        synchronized(candado) { t.fase = Fase.CERRANDO }
        if (h.sondeo) cerrar(h)
        return synchronized(candado) {
            t.fase = Fase.CERRADO
            // La decisión final va DESPUÉS del cierre y bajo el candado que suelta al dueño: un folio detenido nunca entrega una
            // lectura, aunque el kernel haya alcanzado a leer o el detener haya llegado mientras se cerraba. Y la marca manda sobre
            // toda salida anterior al sondeo (sin red, `preparar` fallida, cierre pendiente): `Cancelada`, no `Cortada`; el
            // informe conserva su código y su cierre.
            val resultado = if (t.detenido && (leida is ResultadoDeLectura.Leida || !h.sondeo)) {
                h.causa = Causa.DETENIDA
                ResultadoDeLectura.Cancelada
            } else {
                leida
            }
            ultimo.value = h.informe(t.folio)
            if (turno === t) turno = null // el dueño se suelta con su cierre ya resuelto
            resultado
        }
    }

    private fun intentar(t: Turno, operacion: Operacion, importeCentavos: Long, espera: Long, h: Hoja): ResultadoDeLectura {
        fun sale(causa: Causa, r: ResultadoDeLectura) = r.also { h.causa = causa }
        // El laboratorio no cancela con tarjeta (pre-plan §10): una cancelación no toca la PAX.
        if (operacion != Operacion.VENTA) return sale(Causa.NO_ES_VENTA, ResultadoDeLectura.Cortada(SIN_CODIGO))
        // El cierre que falló en el intento anterior se reintenta antes de preparar; si vuelve a fallar, no se prepara ni se sondea.
        if (cierrePendiente) {
            cerrar(h)
            if (h.cierre == Cierre.SIN_ACREDITAR) return sale(Causa.CIERRE_PENDIENTE, ResultadoDeLectura.Cortada(SIN_CODIGO))
        }
        val preparado = pax.preparar(importeCentavos)
        if (preparado != RetCode.EMV_OK) {
            h.codigo = preparado
            return sale(Causa.PREPARACION, ResultadoDeLectura.Cortada(preparado))
        }
        // Otra vez justo antes de sondear: el motor la miró antes de la sesión, que puede tardar.
        if (!red.hayRed()) return sale(Causa.SIN_RED, ResultadoDeLectura.Cortada(SIN_CODIGO))
        val sondea = synchronized(candado) {
            if (!t.detenido) t.fase = Fase.SONDEANDO
            !t.detenido
        }
        if (!sondea) return sale(Causa.DETENIDA, ResultadoDeLectura.Cancelada)
        h.sondeo = true
        val sondeo = pax.sondear(espera)
        // Un detener que llegó mientras sondeaba (aunque su stopPolling se haya perdido en la frontera) manda: no se llama al
        // kernel. Uno que llegue después de esta revisión lo deja correr, y lo frena la decisión final de `correr`.
        if (synchronized(candado) { t.detenido }) return sale(Causa.DETENIDA, ResultadoDeLectura.Cancelada)
        when (sondeo) {
            Sondeo.TARJETA -> Unit
            Sondeo.VENCIO -> return sale(Causa.VENCIDA, ResultadoDeLectura.Vencida)
            Sondeo.PARADO, Sondeo.OTRO -> return sale(Causa.SONDEO, ResultadoDeLectura.Cortada(SIN_CODIGO))
        }
        val k = pax.leerConElKernel()
        h.codigo = k.codigo
        // Con código ≠ 0, `ClssProcess` regresa antes de crear el kernel de ESTA tarjeta y `getTlv` le preguntaría al de la
        // anterior (`ClssProcess.java:115-119`, `:206-211`): no se leen tags. Toda venta medida trae 0 (`Perfiles.kt`).
        if (k.codigo != RetCode.EMV_OK) return sale(Causa.KERNEL, ResultadoDeLectura.Cortada(k.codigo))
        h.marca = marcaDe(k.tipo)
        h.resultado = resultadoDe(k.resultado)
        h.cvm = cvmDe(k.cvm)
        val tags = leerTags(pax)
        h.tagsPresentes = tags.keys
        h.importeDelKernel = tags[0x9F02]?.toLongOrNull()
        if (0x57 !in tags) return sale(Causa.SIN_BANDA, ResultadoDeLectura.Cortada(k.codigo))
        val lectura = Lectura(h.marca, Entrada.SIN_CONTACTO, h.resultado!!, k.codigo, h.cvm!!, tags)
        return sale(Causa.LEIDA, ResultadoDeLectura.Leida(lectura))
    }

    /**
     * Cada paso con su resultado; se acredita sólo si todos salieron bien (no se copia el `cerrarLectores()` del laboratorio
     * viejo, que se tragaba los errores). Sólo se sondea el lector sin contacto: chip y banda nunca se abren. Uno que falla
     * queda pendiente para el siguiente intento.
     */
    private fun cerrar(h: Hoja) {
        h.fallasDelCierre = listOfNotNull(
            "stopPolling".takeIf { runCatching { pax.pararSondeo() }.isFailure },
            "picc.close".takeIf { runCatching { pax.cerrarPicc() }.isFailure },
        )
        h.cierre = if (h.fallasDelCierre.isEmpty()) Cierre.ACREDITADO else Cierre.SIN_ACREDITAR
        cierrePendiente = h.fallasDelCierre.isNotEmpty()
    }
}

/** El código de `Cortada` cuando la PAX no dio uno (sin red, sin venta, sondeo raro, cierre pendiente, error). Ningún `RetCode` lo usa. */
internal const val SIN_CODIGO = Int.MIN_VALUE

/**
 * Los tags que se le piden al kernel: los de los dos armados del motor (`ArmadoDelMensaje.TAGS_VISA_MC` y `TAGS_AMEX`, sin el
 * 9F1E, que sale de la identidad) más la banda 57 y el país 5F28, que mira la admisión.
 */
internal val TAGS: List<Int> = listOf(
    0x57, 0x5F28, 0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82, 0x9F33, 0x9F34, 0x9A, 0x5F2A, 0x9F02,
    0x9F03, 0x9F35, 0x5F34, 0x9F10, 0x84, 0x9F09, 0x9F1A, 0x95, 0x9F6E, 0x50, 0x9F71, 0x9F67,
)

/** Crudos: sólo los que el kernel devolvió con algo, en hex mayúsculas (el formato de la admisión y del armado). */
internal fun leerTags(pax: Pax): Map<Int, String> = TAGS.mapNotNull { tag ->
    pax.tag(tag)?.takeIf { it.isNotEmpty() }?.let { valor -> tag to valor.joinToString("") { "%02X".format(it) } }
}.toMap()

internal fun marcaDe(tipo: Int?): Marca? = when (tipo) {
    KernType.KERNTYPE_VIS -> Marca.VISA
    KernType.KERNTYPE_MC -> Marca.MASTERCARD
    KernType.KERNTYPE_AE -> Marca.AMEX
    else -> null
}

internal fun resultadoDe(r: TransResultEnum?): ResultadoKernel = when (r) {
    TransResultEnum.RESULT_REQ_ONLINE -> ResultadoKernel.PIDE_BANCO
    TransResultEnum.RESULT_OFFLINE_APPROVED -> ResultadoKernel.APROBADA_SIN_BANCO
    TransResultEnum.RESULT_OFFLINE_DENIED -> ResultadoKernel.NEGADA_SIN_BANCO
    else -> ResultadoKernel.OTRO
}

internal fun cvmDe(c: CvmResultEnum?): Cvm = when (c) {
    CvmResultEnum.CVM_NO_CVM -> Cvm.SIN_CVM
    CvmResultEnum.CVM_CONSUMER_DEVICE -> Cvm.DISPOSITIVO
    CvmResultEnum.CVM_SIG -> Cvm.FIRMA
    CvmResultEnum.CVM_OFFLINE_PIN, CvmResultEnum.CVM_ONLINE_PIN, CvmResultEnum.CVM_ONLINE_PIN_SIG -> Cvm.PIN
    null -> Cvm.OTRO
}
