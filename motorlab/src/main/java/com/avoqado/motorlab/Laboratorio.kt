package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.SaludDeLaLibreta
import com.avoqado.cobropropio.motor.ResultadoDelCorte
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import kotlin.coroutines.suspendCoroutine

/** Lo que el laboratorio usa del motor: el de verdad es `MotorDeCobro` (en `AppDelLaboratorio.kt`). */
internal interface Motor {
    fun cobrar(s: SolicitudDeVenta): Flow<EventoDeCobro>
    suspend fun cortar(s: SolicitudDeVenta): ResultadoDelCorte
    suspend fun recuperar(): List<AttemptId>
    val resolucionesTardias: SharedFlow<Pair<AttemptId, EventoDeCobro>>
}

/** Lo que el laboratorio lee de la libreta, siempre fuera del carril. `buscar` lanza con una fila ilegible. */
internal interface Filas {
    fun buscar(folio: AttemptId): IntentoEntity?
    fun salud(): SaludDeLaLibreta
}

internal sealed interface EstadoDelMotor {
    /** Al abrir, hasta que `arrancar()` leyó el archivo de la identidad: todavía no se sabe si falta (C). */
    data object Comprobando : EstadoDelMotor
    data object SinIdentidad : EstadoDelMotor
    data object Armando : EstadoDelMotor
    data object Armado : EstadoDelMotor
    data class Fallo(val clase: String) : EstadoDelMotor
}

/** El trabajo en curso; `null` = ninguno. Uno a la vez. */
internal sealed interface Trabajo {
    data class Cobro(val folio: AttemptId) : Trabajo
    data object LecturaSeca : Trabajo
}

/** Las órdenes de la pantalla; todas entran por [Laboratorio.orden]. `Guardar` no es `data`: su `toString` no lleva la identidad. */
internal sealed interface Orden {
    class Guardar(val serie: String, val posId: String, val serieFisica: String) : Orden
    data class Cobrar(val centavos: Long) : Orden
    data object Cancelar : Orden
    data object LecturaSeca : Orden
    data class Aviso(val texto: String) : Orden
}

/** Lo que publica el carril. [guardadaEsLaDelMotor]: null sin motor. [lecturaSeca]: el resultado de la última. */
internal data class Situacion(
    val motor: EstadoDelMotor = EstadoDelMotor.SinIdentidad,
    val trabajo: Trabajo? = null,
    val motoresArmados: Int = 0,
    val identidadGuardada: Boolean = false,
    val guardadaEsLaDelMotor: Boolean? = null,
    val lecturaSeca: String? = null,
    val avisos: List<String> = emptyList(),
)

/** null = «Cobrar» se acepta; si no, por qué no. */
internal fun porQueNoCobra(s: Situacion): String? = when {
    s.trabajo is Trabajo.Cobro -> "hay un cobro en curso"
    s.trabajo is Trabajo.LecturaSeca -> "hay una lectura seca en curso"
    else -> when (val m = s.motor) {
        EstadoDelMotor.Comprobando -> "comprobando la identidad guardada"
        EstadoDelMotor.SinIdentidad -> "falta la identidad"
        EstadoDelMotor.Armando -> "armando el motor"
        is EstadoDelMotor.Fallo -> "no se armó el motor: ${m.clase}; cierra y abre la app"
        EstadoDelMotor.Armado -> null
    }
}

/** La cabeza de la pantalla: nunca la identidad, sólo si está guardada y si es la del motor. */
internal fun textoDeLaSituacion(s: Situacion): String = buildString {
    val motor = when (val m = s.motor) {
        EstadoDelMotor.Comprobando -> "COMPROBANDO"
        EstadoDelMotor.SinIdentidad -> "SIN_IDENTIDAD"
        EstadoDelMotor.Armando -> "ARMANDO"
        EstadoDelMotor.Armado -> "ARMADO"
        is EstadoDelMotor.Fallo -> "FALLO (${m.clase})"
    }
    appendLine("Motor: $motor · motores armados: ${s.motoresArmados}")
    appendLine(
        "Identidad guardada: " + when {
            s.motor == EstadoDelMotor.Comprobando -> "comprobando" // todavía no se lee el archivo: ni «sí» ni «no»
            s.identidadGuardada -> "sí"
            else -> "no"
        } + when (s.guardadaEsLaDelMotor) {
            null -> ""
            true -> " (la del motor)"
            false -> " (distinta de la del motor: se usará al cerrar y abrir la app)"
        },
    )
    appendLine("Cobrar: " + (porQueNoCobra(s)?.let { "apagado: $it" } ?: "listo"))
    s.avisos.forEach { appendLine("· $it") }
}

/**
 * El laboratorio (bloque 4-L · L-d1, Ruling 6): dueño del ÚNICO motor del proceso y del trabajo en curso, en UN carril. Toda
 * orden entra por [orden] y se acepta o se rechaza en [carril] (en la app, el hilo de la pantalla), el único que cambia el
 * estado; lo lento va a [alcance] y su resultado regresa al carril. Los folios los arma UN pintor, en orden ([folios]).
 * [alcance] vive todo el proceso y nunca se cancela: el `finally` de cada intento cuenta con él para terminar su COBRO.
 */
internal class Laboratorio(
    private val historial: HistorialLocal,
    private val lector: LectorPax,
    private val archivo: ArchivoDeIdentidad,
    private val fabrica: (Identidad) -> Pair<Motor, Filas>,
    private val lecturaSeca: suspend () -> String,
    private val alcance: CoroutineScope,
    private val carril: Executor,
    private val ahora: () -> Long,
) {
    /** Bajo el carril: [arrancado], [cancelado], [terminado] y [corte]. */
    private class Intento(val s: SolicitudDeVenta) {
        var arrancado = false
        var cancelado = false
        var terminado = false
        var corte: Job? = null
    }

    // ── Sólo el carril toca esto ──
    private var estadoDelMotor: EstadoDelMotor = EstadoDelMotor.Comprobando // hasta que `arrancar()` lea el archivo (C)
    private var trabajo: Trabajo? = null
    private var intento: Intento? = null
    private var motor: Motor? = null
    private var activa: IdentidadTecleada? = null
    private var guardada: IdentidadTecleada? = null
    private var tecleada = false
    private var escribiendo = false
    private var porEscribir: IdentidadTecleada? = null
    private var armados = 0
    private var lecturas = 0
    private var ultimaLectura: String? = null
    private val avisos = ArrayDeque<String>()

    @Volatile private var filas: Filas? = null // la lee el pintor de folios

    private val _situacion = MutableStateFlow(Situacion(motor = estadoDelMotor))
    val situacion: StateFlow<Situacion> = _situacion.asStateFlow()
    private val cambios = MutableStateFlow(0L)
    private val _folios = MutableStateFlow("")

    /** Cada folio con su fila y sus notas, y la salud de la libreta: lo arma UN pintor fuera del carril. */
    val folios: StateFlow<String> = _folios.asStateFlow()

    init {
        alcance.launch { combine(situacion, cambios) { _, _ -> }.collect { runCatching { pintarFolios() }.onSuccess { _folios.value = it } } }
        alcance.launch { lector.informes.collect { i -> i?.folio?.let { nota(it, textoDelInforme(i)) } } } // tal cual (Ruling 8)
    }

    /**
     * Al abrir: la identidad guardada; si hay, arma (salvo que ya se haya tecleado otra, que gana). Hasta que el archivo se lee,
     * el motor está COMPROBANDO; al regresar al carril pasa a SIN_IDENTIDAD si nadie lo armó mientras tanto (C).
     */
    fun arrancar() {
        alcance.launch {
            val t = archivo.leer()
            enCarril {
                if (estadoDelMotor == EstadoDelMotor.Comprobando) estadoDelMotor = EstadoDelMotor.SinIdentidad
                if (!tecleada) guardada = t
                if (!tecleada && t != null) armar(t) else publicar()
            }
        }
    }

    /** EL punto de entrada de toda orden: se acepta, o se rechaza con un aviso, en el carril y en el orden en que llegó. */
    fun orden(o: Orden) = enCarril { aceptar(o) }

    /**
     * Que el pintor vuelva a leer los folios y la libreta, fuera del carril: el motor puede cambiar una fila sin ningún evento
     * (un reverso tras «por confirmar»). No toca el estado del carril ni retrasa ninguna orden (F).
     */
    fun releer() = cambios.update { it + 1 }

    // ───────────── En el carril ─────────────

    private fun aceptar(o: Orden) {
        when (o) {
            is Orden.Aviso -> aviso(o.texto)
            is Orden.Guardar -> guardar(o)
            is Orden.Cobrar -> if (estadoDelMotor == EstadoDelMotor.Armado && trabajo == null) cobrar(o.centavos) else rechazo("Cobrar")
            Orden.Cancelar -> cancelar()
            Orden.LecturaSeca -> if (trabajo == null) leerEnSeco() else rechazo("Lectura seca")
        }
    }

    private fun rechazo(que: String) = aviso("no se acepta «$que»: ${porQueNoCobra(_situacion.value) ?: "ahora no"}")

    /**
     * Siempre se acepta y sólo escribe. Si el motor no tiene identidad (SIN_IDENTIDAD o COMPROBANDO), arma con ella cuando el
     * archivo ya la tiene ([escribir]); con el motor armando, armado o en FALLO, sólo guarda (la activa es la del proceso).
     */
    private fun guardar(o: Orden.Guardar) {
        val t = try {
            IdentidadTecleada.de(o.serie, o.posId, o.serieFisica)
        } catch (_: IllegalArgumentException) {
            return aviso("identidad sin forma: las series llevan de 4 a 32 letras o números y el posId de 1 a 9 números")
        }
        tecleada = true
        escribir(t)
    }

    private fun sinIdentidad() = estadoDelMotor == EstadoDelMotor.SinIdentidad || estadoDelMotor == EstadoDelMotor.Comprobando

    /**
     * Un archivo a la vez; si llega otra mientras se escribe, se escribe después: queda la última aceptada. Un motor sin
     * identidad arma SÓLO con una identidad ya escrita y sólo con la última aceptada: si hay otra en fila, arma ésa cuando se
     * escriba (D). Si la escritura falla, el aviso, y el motor sigue sin identidad.
     */
    private fun escribir(t: IdentidadTecleada) {
        if (escribiendo) {
            porEscribir = t
            return
        }
        escribiendo = true
        alcance.launch {
            val error = runCatching { archivo.guardar(t) }.exceptionOrNull()
            enCarril {
                escribiendo = false
                val siguiente = porEscribir
                porEscribir = null
                if (error != null) {
                    aviso("la identidad no se pudo guardar: ${error.javaClass.simpleName}")
                } else {
                    guardada = t
                    if (siguiente == null && sinIdentidad()) armar(t)
                    aviso(if (activa == null || t == activa) "identidad guardada" else "identidad guardada: se usará al cerrar y abrir la app")
                }
                siguiente?.let { escribir(it) }
            }
        }
    }

    /**
     * ARMANDO, con una identidad que ya está en el archivo (la leída al abrir o la recién escrita): crea el ÚNICO motor y corre
     * `recuperar()` UNA vez; si cualquiera lanza, FALLO y no se reintenta (Ruling 7).
     */
    private fun armar(t: IdentidadTecleada) {
        activa = t
        estadoDelMotor = EstadoDelMotor.Armando
        publicar()
        alcance.launch {
            intentar {
                val (m, f) = fabrica(t.identidad())
                filas = f
                delCarril { motor = m }
                m to m.recuperar()
            }.onSuccess { (m, cambiados) ->
                // El motor publica aquí TODOS los finales de cada intento, también el primero, que ya llegó por su `Flow`; haya o no
                // quien escuche. No son «tardíos»: van a su folio como «final publicado», sin deduplicar (un `NoSeCobro` después de
                // un `PorConfirmar` del mismo folio también se enseña) (B).
                alcance.launch { m.resolucionesTardias.collect { (id, e) -> nota(id, "final publicado: ${textoDelEvento(e)}") } }
                enCarril {
                    armados++
                    estadoDelMotor = EstadoDelMotor.Armado
                    aviso("recuperar (al abrir): " + if (cambiados.isEmpty()) "sin cambios" else cambiados.joinToString { "…" + it.valor.takeLast(4) })
                }
            }.onFailure {
                enCarril {
                    estadoDelMotor = EstadoDelMotor.Fallo(it.javaClass.simpleName)
                    aviso("no se armó el motor: ${it.javaClass.simpleName}; cierra y abre la app")
                }
            }
        }
    }

    /** El intento existe desde aquí: un «Cancelar» que llegue antes del folio se recuerda en él. */
    private fun cobrar(centavos: Long) {
        val m = motor ?: return
        val i = Intento(SolicitudDeVenta(AttemptId.nuevo(), VENUE, centavos, Origen.LOCAL_SIN_SOLICITUD))
        intento = i
        trabajo = Trabajo.Cobro(i.s.id)
        publicar()
        alcance.launch { duenoDelIntento(m, i) }
    }

    private fun cancelar() {
        val i = intento
        if (trabajo !is Trabajo.Cobro || i == null || i.terminado) return aviso("no hay cobro que cancelar")
        if (i.cancelado) return aviso("ya se pidió cancelar")
        i.cancelado = true
        if (!i.arrancado) return aviso("cancelado antes de arrancar el motor")
        val m = motor ?: return
        i.corte = alcance.launch { // el corte es parte del intento: el COBRO no termina hasta que regresa
            lector.detener(i.s.id) // primero y fuera del carril (nota (d) de L-c)
            nota(i.s.id, "corte: " + intentar { m.cortar(i.s).toString() }.getOrElse { "tronó: ${it.javaClass.simpleName}" })
        }
    }

    /** La lectura seca de L-c, por el carril (Codex r3 #2). */
    private fun leerEnSeco() {
        val n = ++lecturas
        trabajo = Trabajo.LecturaSeca
        ultimaLectura = "Lectura n.º $n\nLeyendo… (acerca la tarjeta)"
        publicar()
        alcance.launch {
            val texto = intentar { lecturaSeca() }.getOrElse { "la lectura seca tronó: ${it.javaClass.simpleName}" }
            enCarril {
                ultimaLectura = "Lectura n.º $n\n$texto"
                trabajo = null
                publicar()
            }
        }
    }

    private fun aviso(texto: String) {
        avisos.addLast("${ahora()} $texto")
        while (avisos.size > 8) avisos.removeFirst()
        publicar()
    }

    private fun publicar() {
        _situacion.value = Situacion(
            estadoDelMotor, trabajo, armados, guardada != null, motor?.let { guardada == activa }, ultimaLectura, avisos.toList(),
        )
    }

    // ───────────── Fuera del carril ─────────────

    /** La corrutina del intento (Ruling 8): lector, folio, y sólo entonces, si nadie canceló, el motor. */
    private suspend fun duenoDelIntento(m: Motor, i: Intento) {
        val s = i.s
        var apuntado = false
        try {
            if (!lector.siguienteFolio(s.id)) return enCarril { aviso("el lector sigue ocupado: toca otra vez") }
            apuntado = true
            try {
                historial.anotarFolio(s.id, s.importeCentavos) // ANTES de cobrar, entero y sincronizado
            } catch (e: Exception) {
                return enCarril { aviso("no se pudo guardar el folio: ${e.javaClass.simpleName}") }
            }
            // La frontera de «Cancelar» es UNA decisión del carril (E): antes, el motor nunca se llama; después, `cobrar` ya se
            // llamó (sólo arma el `Flow` frío, `MotorDeCobro.cobrar`) y «Cancelar» va con `detener` + `cortar`.
            val flujo = delCarril {
                if (i.cancelado) {
                    null
                } else {
                    i.arrancado = true
                    m.cobrar(s)
                }
            } ?: return nota(s.id, "cancelado antes de arrancar el motor")
            flujo.collect { nota(s.id, textoDelEvento(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            nota(s.id, "el intento tronó: ${e.javaClass.simpleName}")
        } finally {
            if (apuntado) lector.soltarFolio(s.id) // y no se mira más: el informe de L-c dice cómo quedó el lector
            delCarril { i.terminado = true; i.corte }?.join()
            enCarril {
                intento = null
                trabajo = null
                publicar()
            }
        }
    }

    private fun pintarFolios(): String {
        val f = filas
        return buildString {
            val salud = f?.let { runCatching { it.salud() }.getOrNull() }
            appendLine("Libreta: " + (salud?.let { "reportes pendientes ${it.reportesPendientes} · filas ilegibles ${it.filasIlegibles}" } ?: "sin leer"))
            for (h in historial.leer().takeLast(15).asReversed()) {
                appendLine("— ${h.folio.valor} ${h.importeCentavos?.let(::pesos) ?: "importe ilegible"}: ${textoDeLaFila(leerFila(f, h.folio))}")
                h.notas.forEach { (ms, t) -> appendLine("   $ms $t") }
            }
        }
    }

    private fun leerFila(f: Filas?, id: AttemptId): LecturaDeFila {
        f ?: return LecturaDeFila.SinLibreta
        return try {
            f.buscar(id)?.let { LecturaDeFila.Fila(it) } ?: LecturaDeFila.SinFila
        } catch (e: Exception) {
            LecturaDeFila.Ilegible(e.javaClass.simpleName)
        }
    }

    private fun nota(folio: AttemptId, texto: String) {
        historial.nota(folio, ahora(), texto)
        cambios.update { it + 1 }
    }

    private fun enCarril(b: () -> Unit) = carril.execute(b)

    /** Lo de [b] en el carril; si lanza (p. ej. un `cobrar` que truena al llamarlo), lo recibe quien espera, no el carril. */
    private suspend fun <T> delCarril(b: () -> T): T = suspendCoroutine { c -> carril.execute { c.resumeWith(runCatching(b)) } }
}

/** Como `runCatching`, pero la cancelación no se traga. */
private inline fun <T> intentar(b: () -> T): Result<T> = runCatching(b).onFailure { if (it is CancellationException) throw it }
