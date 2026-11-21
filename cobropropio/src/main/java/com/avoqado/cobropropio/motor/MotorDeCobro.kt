package com.avoqado.cobropropio.motor

import com.avoqado.cobropropio.admision.Admision
import com.avoqado.cobropropio.admision.MotivoNoAdmitida
import com.avoqado.cobropropio.admision.TablaDeAdmision
import com.avoqado.cobropropio.blumon.ArmadoDelMensaje
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.avoqado.cobropropio.blumon.DesenlaceDeCancelacion
import com.avoqado.cobropropio.blumon.DesenlaceDeVenta
import com.avoqado.cobropropio.blumon.MaterialDukpt
import com.avoqado.cobropropio.blumon.PuertoBlumon
import com.avoqado.cobropropio.blumon.SesionBlumon
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.EventoDeCobro.NoSalioAlBanco
import com.avoqado.cobropropio.dominio.EventoDeCobro.PorConfirmar
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.Solicitud
import com.avoqado.cobropropio.dominio.SolicitudDeCancelacion
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.libreta.DatosDelEnvio
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReverso
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.ResultadoDeAnotarCancelacion
import com.avoqado.cobropropio.libreta.ResultadoDeEnviarCancelacion
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.ReversoDelServidor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lo que contesta [MotorDeCobro.cortar] (regla C). Sólo `CORTADO` promete: el veto ya está en la libreta y ese folio NUNCA
 * sale, ni tras reiniciar ni si se vuelve a pedir.
 * - `YA_SALIO`: el intento ya pasó su punto de no retorno; su desenlace va a la fila y a `resolucionesTardias`.
 * - `SIN_CONFIRMAR`: el veto no se guardó a tiempo (un intento en curso quedó frenado en RAM); la app sigue consultando.
 * - `SIN_INTENTO_VIVO`: aquí no hay nada que cortar (fila en otro estado, o ya terminó); la app consulta el folio.
 */
enum class ResultadoDelCorte { CORTADO, YA_SALIO, SIN_CONFIRMAR, SIN_INTENTO_VIVO }

/**
 * El motor de la venta: cobra, espera, pide permiso al SERVIDOR, reversa y suelta a la cajera a los 30 s, EXACTAMENTE según la
 * tabla de decisiones (secciones A-H, rediseño v3 y parada 2). La cancelación con la tarjeta (T9) entra por la MISMA puerta,
 * el mismo `Registro`, el mismo corte y las mismas salidas; sólo cambia lo de en medio, y nunca se reversa. Sólo AFIRMA con evidencia concluyente; ante cualquier duda
 * después de salir al banco, `PorConfirmar`. Toda emisión terminal sale de la fila DESPUÉS de su commit.
 *
 * La ejecución de cada intento vive en [fondo] desde que su id se registra; el colector sólo observa. Las llamadas de la
 * libreta bloquean: corren siempre en [io]. El camino del reverso está en `CaminoDelReverso.kt`.
 *
 * r2 familia A: el motor vive TODO el proceso y es el único dueño de su libreta. No hay «retirar el motor».
 * - [fondo] tiene que vivir todo el proceso. Si igual se cancela, ningún intento se queda sin final: su `Registro` emite lo
 *   que diga la fila antes de quedar como lápida, y la fila la cierra [recuperar], que nunca salta una viva.
 * - La IPEK de la sesión vive en RAM mientras la sesión sirve, porque cada venta la necesita; se borra al renovar las llaves
 *   y cuando [fondo] termina (R2-5): entonces ya no entra ningún cobro nuevo.
 */
class MotorDeCobro(
    private val identidad: Identidad,
    private val lector: LectorDeTarjeta,
    private val red: Red,
    internal val blumon: PuertoBlumon,
    internal val servidor: ReversoDelServidor,
    internal val libreta: Libreta,
    internal val cola: ColaDeResultados,
    private val huella: HuellaHmac,
    internal val catalogo: CatalogoDeRechazos,
    internal val relojes: Relojes,
    internal val fondo: CoroutineScope,
    internal val ahora: () -> Long,
    internal val relojDePared: () -> Instant,
    /** Sólo las pruebas lo cambian, para que el tiempo virtual espere a la libreta. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Sólo las pruebas lo usan: corre en `recuperar` justo antes de esperar a Room (R7-3). */
    internal var antesDeRecuperar: suspend () -> Unit = {}

    /** Sólo las pruebas lo usan: corre justo antes del punto de no retorno del envío (R4-2). */
    internal var antesDelPuntoSinRetorno: suspend () -> Unit = {}

    /** Sólo las pruebas lo usan: corren alrededor de cada lectura de la fila en H (la de la admisión, con su tope). */
    internal var antesDeBuscar: suspend (AttemptId) -> Unit = {}
    internal var despuesDeBuscar: suspend (AttemptId) -> Unit = {}

    /** Sólo las pruebas lo usan: corre en [cortar], dentro de la escritura con tope, antes del veto. */
    internal var antesDeVetar: suspend (AttemptId) -> Unit = {}

    private val armado = ArmadoDelMensaje(identidad)

    init {
        // Lo ÚLTIMO que puede tronar al construir (revisión de la tarea 6): va después de lo que valida (el `require` de
        // [armado]) y antes del aviso de [fondo]; un constructor que truena deja el dueño de la libreta como estaba.
        libreta.tomarDueno()
    }

    /** Regla M: cada intento que este proceso corrió, vivo o lápida ([Registro.retirado]). `internal` sólo para las pruebas. */
    internal val intentos = ConcurrentHashMap<AttemptId, Registro>()

    /** El monitor de admisión: hoja del grafo de candados (sólo memoria: sin Room, sin `launch`, sin publicar). */
    private val monitorDeAdmision = Any()

    /** v3.1: ninguna lápida se borra mientras una admisión mira; sube ANTES de la primera mirada de [cobrar]. */
    private val admisionesEnVuelo = AtomicInteger(0)

    /** Las lápidas en orden de retiro, bajo [monitorDeAdmision]. */
    private val lapidas = ArrayDeque<Registro>()

    /**
     * ponytail: tope blando de lápidas (unos KB cada una); la más vieja se contesta desde su fila, que para entonces ya no
     * cambia. Si un día hacen falta más, que venzan por tiempo. Sólo las pruebas lo bajan.
     */
    internal var topeDeLapidas = 512

    private val lectorOcupado = Mutex()

    // La sesión de Blumon: una por motor y una sola renovación en vuelo (el candado). Las LLAVES nunca se renuevan con una
    // reserva viva; el TOKEN sí, porque no toca la base DUKPT ni el contador.
    private val candadoDeSesion = Mutex()
    @Volatile private var sesion: SesionBlumon? = null
    @Volatile private var renovarLlaves = false
    @Volatile private var tokenVencido = false
    /** [fondo] terminó: ninguna sesión nueva se instala (R2-5). */
    @Volatile private var cerrado = false

    init {
        // R2-5: con `fondo` terminado (cancelado y sus hijos ya cerrados) ya nadie usa la sesión: se borra su material y no se
        // instala otra. Corre después del último hijo, así que ninguna venta está copiando la IPEK. Va DESPUÉS de declarar
        // `sesion` y `cerrado`: con `fondo` ya terminado, el aviso corre aquí mismo.
        fondo.coroutineContext[Job]?.invokeOnCompletion {
            cerrado = true
            sesion?.material?.close()
            sesion = null
        }
    }
    private var reservas = 0

    /** Sólo las pruebas lo leen (r2 R2-7). */
    internal val reservasDeSesion: Int get() = reservas

    private val tardias = MutableSharedFlow<Pair<AttemptId, EventoDeCobro>>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Todos los finales de cada intento, haya o no alguien escuchando; la fila es la verdad; quien consume deduplica por id, y
     * un `PorConfirmar` nunca reemplaza a un definitivo.
     */
    val resolucionesTardias: SharedFlow<Pair<AttemptId, EventoDeCobro>> = tardias.asSharedFlow()

    /**
     * Lo que vive de un intento en este proceso. `historia` termina en un solo evento terminal: `actual`, el último según su
     * precedencia (R1-7): un definitivo reemplaza a `PorConfirmar`, y `PorConfirmar` nunca reemplaza a un definitivo. Sólo
     * existe para un intento que de verdad corre; al terminar no se borra: queda como lápida (regla M).
     *
     * r7 (familia de los candados):
     * - C1: el corte y el punto de no retorno son UNA máquina CAS sin candado ([paso]: `LIBRE`, `ENVIANDO`, `CORTADO`).
     * - C3: bajo [candado] sólo se DECIDE (la historia nueva y si se publica tarde) y se encola; la publicación (el
     *   `StateFlow`, que con `Main.immediate` reanuda a quien escucha en la misma pila, y [resolucionesTardias]) sale DESPUÉS de
     *   soltarlo, por un drenador serial del propio Registro, en el MISMO orden en que se decidió (R1-7 se conserva).
     */
    internal inner class Registro(val solicitud: Solicitud) {
        val id get() = solicitud.id

        /** El de la venta; el de una cancelación sale de la fila de su original al anotarla (0 mientras no se sabe). */
        @Volatile var importeCentavos: Long = (solicitud as? SolicitudDeVenta)?.importeCentavos ?: 0

        /** Sólo en los caminos de la venta (el reverso): una cancelación nunca llega ahí. */
        val venta: SolicitudDeVenta get() = solicitud as SolicitudDeVenta
        val historia = MutableStateFlow<List<EventoDeCobro>>(emptyList())
        val reservada = AtomicBoolean(false)
        @Volatile var enviado = false
        /** La venta contestó 401/403: su reverso pide token nuevo desde la primera vuelta (el congelado ya se probó malo). */
        @Volatile var ventaConTokenMalo = false

        /** Regla N (v3.1): `true` SÓLO cuando su PROPIO `INSERT` de `anotar` quedó confirmado. */
        @Volatile var anotado = false

        /** v3.1: perdió el `INSERT` contra una fila que ya existía: contesta desde esa fila, no publica nada y no deja lápida. */
        @Volatile var ajeno = false

        /** Regla M: terminó y quedó como lápida (después de su última escritura). */
        @Volatile var retirado = false

        /** R10-1: hay un refresco de la lápida en duda en vuelo. */
        val refrescando = AtomicBoolean(false)

        /** C1: `LIBRE` → `ENVIANDO` (el motor pasa el punto) o `LIBRE` → `CORTADO` (el corte). Nunca vuelve. */
        private val paso = AtomicInteger(LIBRE)

        private val candado = Any()
        /** La historia DECIDIDA (bajo [candado]); [historia] es lo ya publicado, en el mismo orden. */
        private var decidida: List<EventoDeCobro> = emptyList()
        private val porPublicar = ArrayDeque<Pair<List<EventoDeCobro>, EventoDeCobro?>>()
        private val drenando = AtomicBoolean(false)
        /** El intento terminó sin final por una cancelación (R6-1): ya se soltó a la cajera desde la memoria. */
        private var soltadoEnEmergencia = false

        val terminado: Boolean get() = synchronized(candado) { decidida.lastOrNull()?.esTerminal == true }

        /** El final vigente, o `null` si todavía no hay. */
        val actual: EventoDeCobro? get() = synchronized(candado) { decidida.lastOrNull()?.takeIf { it.esTerminal } }

        /** ¿Se pidió el corte? */
        fun cortado(): Boolean = paso.get() == CORTADO

        /** ¿Ya pasó el punto de no retorno? */
        fun pasoElPunto(): Boolean = paso.get() == ENVIANDO

        /** El motor, justo antes de `marcarEnviado`: `false` si ya se pidió el corte. Que una escucha se vaya no cuenta (S1). */
        fun pasarElPuntoSinRetorno(): Boolean = paso.compareAndSet(LIBRE, ENVIANDO)

        /** El corte: `true` si queda puesto (ahora o antes); `false` si la venta ya pasó su punto (llegó tarde). Sin candado. */
        fun pedirCortar(): Boolean = paso.compareAndSet(LIBRE, CORTADO) || paso.get() == CORTADO

        /**
         * El ÚNICO embudo de un folio de este proceso (r2 familia D). Un `PorConfirmar` después de un terminal es una lectura
         * vieja y nunca se publica. El mismo terminal que ya salió no se repite.
         */
        fun emitir(e: EventoDeCobro) {
            synchronized(candado) { decidir(e) }
            publicar()
        }

        /**
         * R6-1 y regla N: sin tocar Room, el final que es verdad desde la memoria. Pasado el punto, `PorConfirmar`. Si no, se
         * cierra el punto (nunca más saldrá) y: con su fila anotada, `NoSalioAlBanco(INTERRUMPIDO)`; sin fila, `PorConfirmar`,
         * porque sin fila no se afirma nada del folio. `true` si terminó así (ahora o antes); `false` si ya tenía su final.
         */
        fun soltarDesdeMemoria(): Boolean {
            val solto = synchronized(candado) {
                if (soltadoEnEmergencia) return true
                if (decidida.lastOrNull()?.esTerminal == true) return false
                soltadoEnEmergencia = true
                val noSale = pedirCortar()
                decidir(if (noSale && anotado) NoSalioAlBanco(MotivoNoSalio.INTERRUMPIDO, null) else PorConfirmar(importeCentavos))
                true
            }
            publicar()
            return solto
        }

        /** Sólo bajo [candado]: decide y encola; nunca publica. */
        private fun decidir(e: EventoDeCobro) {
            val h = decidida
            val tarde = h.lastOrNull()?.esTerminal == true
            val nueva = when {
                !tarde -> h + e
                // Quien pregunte tarde (adjuntar, observar) ve el final vigente, no el primer «por confirmar».
                e.esTerminal && e !is PorConfirmar && h.last() is PorConfirmar -> h.dropLast(1) + e
                else -> h
            }
            // S3: todo final aceptado se publica, haya o no quien escuche; nunca el de un `Registro` ajeno, y nunca
            // `CONTRATO_DISTINTO`, que contesta a quien preguntó y no es un desenlace del folio (R10-2).
            val tardia = e.takeIf {
                !ajeno && it.esTerminal && it != CONTRATO_DISTINTO && (!tarde || (it !is PorConfirmar && it != h.last()))
            }
            if (nueva !== h || tardia != null) {
                decidida = nueva
                porPublicar.addLast(nueva to tardia)
            }
        }

        /**
         * Sin candado: publica lo encolado en orden. Un solo hilo drena a la vez; quien encola mientras otro drena se lo deja
         * (y si la publicación reanuda a alguien que vuelve a emitir en la misma pila, también: no hay reentrada).
         */
        private fun publicar() {
            while (drenando.compareAndSet(false, true)) {
                while (true) {
                    val (h, tardia) = synchronized(candado) { porPublicar.removeFirstOrNull() } ?: break
                    historia.value = h
                    tardia?.let { publicarTardia(id, it) }
                }
                drenando.set(false)
                if (synchronized(candado) { porPublicar.isEmpty() }) break
            }
        }
    }

    /** Lo armado antes de salir, sólo en RAM: la sesión, el cuerpo exacto (para su reverso) y lo que se coteja. */
    internal class Salida(
        val sesion: SesionBlumon,
        val cuerpo: CuerpoDeBlumon,
        val esperado: Clasificador.Esperado,
        val datos: DatosDelEnvio,
        /** Sólo la cancelación: el folio de Blumon de la venta que se cancela. */
        val folioOriginal: Long? = null,
    ) {
        val congelado = CuerpoCongelado(cuerpo, sesion.token)
        override fun toString() = "Salida(oculta)"
    }

    // ───────────── H. La entrada única: la memoria primero (regla M) ─────────────

    private sealed interface Entrada
    private class Adjuntar(val r: Registro) : Entrada
    private class Arrancar(val r: Registro) : Entrada
    private class Contestar(val e: EventoDeCobro) : Entrada

    /**
     * H, regla M. Un folio que este proceso corrió (vivo o lápida) se contesta desde su `Registro`, sin tocar Room. Uno que
     * no conoce se lee con tope ([conTope], sin candado) y se decide bajo [monitorDeAdmision] SÓLO con memoria: si mientras
     * tanto apareció en [intentos], se adjunta (también si la lectura venció); si la lectura venció o tronó, `PorConfirmar` y
     * nada se registra; con fila, se contesta desde la fila y nada se registra; sin fila, nace su `Registro`.
     *
     * Ninguna lectura vieja se usa: las filas de un folio sólo las escribe su intento, que entra a [intentos] antes de anotar
     * y no sale de ahí mientras alguien admite (v3.1). Un «sin fila» viejo contra el veto de [cortar] lo arbitra `anotar`.
     */
    fun cobrar(s: SolicitudDeVenta): Flow<EventoDeCobro> = entrar(s)

    /**
     * T9: la cancelación con la tarjeta de una venta aprobada. Las mismas reglas que [cobrar] (N, C, M y la cajera), con su
     * orden: la fila primero ([Libreta.anotarCancelacion]), una lectura nueva y su admisión, las huellas, el preflight de
     * Blumon, el contador, el punto de no retorno con [Libreta.marcarCancelacionEnviada] (original, venue, ámbito y huellas en
     * el mismo CAS) y la cancelación. Nunca se reversa: sin respuesta, `NO_SE` + `PorConfirmar`.
     */
    fun cancelar(s: SolicitudDeCancelacion): Flow<EventoDeCobro> = entrar(s)

    private fun entrar(s: Solicitud): Flow<EventoDeCobro> = flow {
        admisionesEnVuelo.incrementAndGet()
        val entrada = try { admitir(s) } finally { admisionesEnVuelo.decrementAndGet() }
        when (entrada) {
            is Adjuntar -> emitAll(adjuntar(entrada.r, s))
            is Contestar -> emit(entrada.e)
            is Arrancar -> emitAll(arrancar(entrada.r)) // sin ningún punto de suspensión desde el monitor
        }
    }

    private suspend fun admitir(s: Solicitud): Entrada {
        intentos[s.id]?.let { return Adjuntar(it) }
        val leida = conTope { antesDeBuscar(s.id); enLibreta { libreta.buscar(s.id) }.also { despuesDeBuscar(s.id) } }
        synchronized(monitorDeAdmision) {
            intentos[s.id]?.let { return Adjuntar(it) }
            if (leida == null || leida.isFailure) return Contestar(PorConfirmar(importeDe(s)))
            leida.getOrNull()?.let { return Contestar(contestarDesdeLaFila(it, s)) }
            return Arrancar(Registro(s).also { intentos[s.id] = it })
        }
    }

    private fun arrancar(r: Registro): Flow<EventoDeCobro> {
        // ATOMIC: aunque `fondo` ya esté cancelado, el cuerpo arranca y su `finally` deja el final (r2 R2-2).
        @OptIn(DelicateCoroutinesApi::class)
        val trabajo = fondo.launch(start = CoroutineStart.ATOMIC) {
            try {
                correr(r)
            } finally {
                withContext(NonCancellable) { cerrarSinFinal(r) }
                retirar(r)
            }
        }
        // R6-1: si se cancela `fondo`, la cajera sale en el acto, desde la memoria y sin esperar a Room (ni a una
        // transacción que tenga ocupado al motor); la fila se lee después, en el `finally`.
        @OptIn(InternalCoroutinesApi::class)
        trabajo.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { causa ->
            if (causa != null) r.soltarDesdeMemoria()
        }
        return observar(r)
    }

    /**
     * Regla M: un intento que terminó queda como lápida, con su historia y su final vigente; uno [Registro.ajeno] se borra
     * sin lápida. Al pasar [topeDeLapidas] se borra la más vieja, nunca con una admisión en vuelo (v3.1: el tope es blando).
     */
    private fun retirar(r: Registro) {
        if (r.ajeno) {
            intentos.remove(r.id, r)
            return
        }
        synchronized(monitorDeAdmision) {
            r.retirado = true
            lapidas.addLast(r)
            while (lapidas.size > topeDeLapidas && admisionesEnVuelo.get() == 0) {
                val vieja = lapidas.removeFirst()
                intentos.remove(vieja.id, vieja)
            }
        }
    }

    /**
     * Al arrancar: cierra lo que dejó un proceso muerto (sin inventar respuestas de Blumon) y entrega los reportes pendientes.
     * Un `reversed` que llega aquí se publica tarde. Regresa los ids que cambiaron o se entregaron.
     */
    suspend fun recuperar(): List<AttemptId> {
        // También las filas de ESTE proceso sin `Registro` vivo (r1 R1-5 c). Sin `puerta` (r7 C4, R7-3): la foto de los vivos
        // se toma DENTRO de la transacción (R2-1). Quien se registra antes de esa foto sale en ella; quien se registra después
        // todavía no anotó su fila (anotar es una escritura, que espera a que esta transacción termine).
        antesDeRecuperar()
        val cerrados = enLibreta { libreta.recuperarAlArrancar(::idsVivos) }
        val entregados = cola.entregarPendientes().keys
        for (id in entregados) {
            if (enLibreta { libreta.buscar(id) }?.estado != EstadoDelIntento.NO_SE_COBRO) continue
            // Un solo embudo (r2 familia D): si el folio está en memoria (vivo o lápida), por su `Registro`; sin él, directo.
            val r = intentos[id]
            if (r != null) r.emitir(EventoDeCobro.NoSeCobro) else publicarTardia(id, EventoDeCobro.NoSeCobro)
        }
        return (cerrados + entregados).distinct()
    }

    /**
     * La foto de los intentos VIVOS: sin lápidas, para que `recuperar()` cierre la huérfana de una que ya terminó (R1-5 c).
     * `ArrayList` copia con `toArray`, que aguanta un borrado a la mitad; `toList()` no (r2 R2-6).
     */
    internal fun idsVivos(): List<AttemptId> = ArrayList(intentos.values).filterNot { it.retirado }.map { it.id }

    /**
     * Un `Registro` nunca queda como lápida sin evento terminal (r2 R2-2). Si terminó sin final (`fondo` cancelado), quien
     * mira ya recibió lo de la memoria ([Registro.soltarDesdeMemoria], R6-1); aquí, después y en `NonCancellable`, si no salió
     * se escribe el veto (regla N: con fila o sin ella) y lo que diga la fila sale por el embudo (sólo un definitivo
     * reemplaza). Si el veto no entra porque la fila ya era de otro, el `Registro` es [Registro.ajeno] (v3.1).
     */
    private suspend fun cerrarSinFinal(r: Registro) {
        if (!r.soltarDesdeMemoria()) return
        val f = intentar {
            enLibreta {
                if (!r.pasoElPunto() && !libreta.vetarPorCorte(nuevaFila(r.solicitud)) && !r.anotado) r.ajeno = true
                libreta.buscar(r.id)
            }
        } ?: return
        r.emitir(contestarDesdeLaFila(f, r.solicitud))
    }

    /**
     * Regla M: un folio de este proceso se contesta desde su `Registro`; con otro contrato, `CONTRATO_DISTINTO` sólo a quien
     * preguntó. Un vivo entrega su historia (pinta el paso actual); una lápida, SÓLO su final vigente y al instante (r10: nadie
     * espera a Room por un final que ya está en memoria). Si ese final es `PorConfirmar`, se refresca de la fila en `fondo`.
     */
    private fun adjuntar(r: Registro, s: Solicitud): Flow<EventoDeCobro> = flow {
        if (r.solicitud != s) return@flow emit(CONTRATO_DISTINTO)
        val final = r.actual?.takeIf { r.retirado } ?: return@flow emitAll(observar(r))
        if (final is PorConfirmar) refrescarDesdeLaFila(r)
        emit(final)
    }

    /**
     * R10-1: una lápida en duda lee su fila en `fondo`, sin que nadie la espere, por si un acuse ya guardado la resolvió; el
     * definitivo sale por el `Registro` (a `resolucionesTardias` y como `actual` para quien reabra). Uno en vuelo por lápida.
     */
    private fun refrescarDesdeLaFila(r: Registro) {
        if (!r.refrescando.compareAndSet(false, true)) return
        fondo.launch {
            try {
                intentar { antesDeBuscar(r.id); enLibreta { libreta.buscar(r.id) } }
                    ?.let { contestarDesdeLaFila(it, r.solicitud) }?.takeIf { it !is PorConfirmar }?.let(r::emitir)
            } finally {
                r.refrescando.set(false)
            }
        }
    }

    /** Sólo lectura: la historia del `Registro` hasta su terminal. Que quien escucha se vaya no cambia NADA del intento (S1). */
    private fun observar(r: Registro): Flow<EventoDeCobro> = flow {
        var vistos = 0
        emitAll(
            r.historia.transformWhile { h ->
                for (i in vistos until h.size) emit(h[i])
                vistos = h.size
                h.lastOrNull()?.esTerminal != true
            },
        )
    }

    /**
     * Regla C: el corte es explícito y durable. `CORTADO` sólo con el veto ya escrito en la libreta ([ResultadoDelCorte]).
     * Recibe la solicitud para poder escribir el veto aunque el folio no tenga `Registro`: un corte que llega mientras el
     * folio se admite lo arbitra `anotar` (insertar-si-no-existe), y el intento termina con `NoSalioAlBanco`.
     */
    suspend fun cortar(s: Solicitud): ResultadoDelCorte {
        val r = intentos[s.id]
        if (r != null) {
            if (r.retirado) return ResultadoDelCorte.SIN_INTENTO_VIVO // una lápida: ese folio ya terminó
            if (!r.pedirCortar()) return ResultadoDelCorte.YA_SALIO
        }
        val vetado = conTope { antesDeVetar(s.id); enLibreta { libreta.vetarPorCorte(nuevaFila(r?.solicitud ?: s)) } }
            ?.getOrNull() ?: return ResultadoDelCorte.SIN_CONFIRMAR
        if (vetado) return ResultadoDelCorte.CORTADO
        // v3.1: la fila ya estaba en otro estado; si mientras tanto un intento de este folio pasó su punto, ya salió.
        return if (intentos[s.id]?.pasoElPunto() == true) ResultadoDelCorte.YA_SALIO else ResultadoDelCorte.SIN_INTENTO_VIVO
    }

    /**
     * Una lectura o escritura de la libreta con tope [Relojes.esperaLibreta] que la espera de quien llama no retiene: corre en
     * [fondo] y deja su resultado en un `Deferred`, con el fallo encapsulado (nunca llega al `Job` de [fondo], v2-3). `null` =
     * no contestó a tiempo, o [fondo] ya no corre; lo que llega después no admite ni contesta nada. La cancelación de quien
     * espera se propaga.
     */
    private suspend fun <T> conTope(bloque: suspend () -> T): Result<T>? {
        if (!fondo.isActive) return null
        val listo = CompletableDeferred<Result<T>>()
        fondo.launch { listo.complete(runCatching { bloque() }) }
        return withTimeoutOrNull(relojes.esperaLibreta) { listo.await() }
    }

    /** Una venta: su importe y su origen. Una cancelación: su original (el importe lo pone la original, no quien pide). */
    private fun mismoContrato(f: IntentoEntity, s: Solicitud) =
        f.venueId == s.venueId && f.ambito() == identidad.ambito() && when (s) {
            is SolicitudDeVenta -> f.operacion == Operacion.VENTA && f.importeCentavos == s.importeCentavos &&
                f.vinculado == (s.origen == Origen.VINCULADO_AL_POS)
            is SolicitudDeCancelacion -> f.operacion == Operacion.CANCELACION && f.operacionOriginal == s.original.valor
        }

    /** H desde la fila (R8-1): otro contrato ⇒ `CONTRATO_DISTINTO`; un `ANOTADO` de ESTE proceso sin `Registro` ⇒ duda (v3.1). */
    private fun contestarDesdeLaFila(f: IntentoEntity, s: Solicitud): EventoDeCobro = when {
        !mismoContrato(f, s) -> CONTRATO_DISTINTO
        f.estado == EstadoDelIntento.ANOTADO && f.procesoId == libreta.procesoId -> PorConfirmar(f.importeCentavos)
        else -> desdeLaFila(f)
    }

    private fun importeDe(s: Solicitud) = (s as? SolicitudDeVenta)?.importeCentavos ?: 0

    // ───────────── Antes de salir ─────────────

    private suspend fun correr(r: Registro) {
        try {
            val salida = antesDeSalir(r) ?: return
            antesDelPuntoSinRetorno()
            // R4-2: el punto de no retorno vive en memoria (un CAS). Un corte que ganó antes gana siempre, con su veto durable.
            if (!r.pasarElPuntoSinRetorno()) {
                enLibreta { libreta.vetarPorCorte(nuevaFila(r.solicitud)) }
                return emitirFila(r)
            }
            val t0 = ahora()
            if (r.solicitud is SolicitudDeVenta) {
                if (!enLibreta { libreta.marcarEnviado(r.id, t0, salida.datos) }) return emitirFila(r) // ganó el corte
            } else {
                // T9: preflight válido no es cancelación autorizada: este CAS vuelve a revisar original, venue, ámbito y huellas.
                val enviada = enLibreta { libreta.marcarCancelacionEnviada(r.id, t0, salida.datos) }
                if (enviada != ResultadoDeEnviarCancelacion.Enviada) {
                    // r1 C: con su motivo verdadero; si la propia fila ya no aplica (corte, otro estado), manda la fila (H).
                    val motivo = motivoDe(enviada) ?: return run {
                        val f = enLibreta { libreta.buscar(r.id) }
                        r.emitir(f?.let { contestarDesdeLaFila(it, r.solicitud) } ?: PorConfirmar(r.importeCentavos))
                    }
                    noSalio(r, motivo)
                    return
                }
            }
            r.enviado = true
            if (r.solicitud is SolicitudDeVenta) despuesDeSalir(r, salida, t0) else despuesDeCancelar(r, salida, t0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nunca escapa de fondo. R6-1: la cajera sale PRIMERO desde la memoria, sin esperar a Room. Después, lo de antes de
            // salir se cierra como NO_SALIO; lo de después, como una huérfana (NO_SE, el reverso abierto NO_CONCLUYENTE y
            // PUDO_ENTRAR si una vuelta pudo salir), en una transacción (r1 R1-5 a), y lo de la fila sale tarde. Si eso
            // también falla, la fila queda para `recuperar()`.
            r.soltarDesdeMemoria()
            val f = intentar {
                enLibreta {
                    if (r.enviado) {
                        libreta.cerrarHuerfana(r.id)
                    } else {
                        libreta.transicionar(r.id, EstadoDelIntento.ANOTADO) {
                            it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.INTERRUMPIDO)
                        } ?: libreta.buscar(r.id)
                    }
                }
            }
            f?.let { r.emitir(contestarDesdeLaFila(it, r.solicitud)) }
        } finally {
            soltarReserva(r)
        }
    }

    /**
     * Pasos 2 a 10 (r4 R4-03), de la venta y de la cancelación. `null` = terminó sin salir, con su terminal ya emitido. La
     * cancelación agrega el preflight de Blumon después de la admisión y antes de gastar contador (T9, paso 4).
     */
    private suspend fun antesDeSalir(r: Registro): Salida? {
        val s = r.solicitud
        val operacion = if (s is SolicitudDeCancelacion) Operacion.CANCELACION else Operacion.VENTA
        if (s is SolicitudDeVenta) r.emitir(EventoDeCobro.Iniciado(s.id, identidad, operacion, s.importeCentavos))
        val limiteDeSesion = ahora() + relojes.esperaSesion // la espera de la sesión cuenta desde aquí, candado incluido
        reservarSesion(r, limiteDeSesion)
        // Regla N: la fila va PRIMERO; todo «no salió» de este folio, también sin red, sale de ella. Si la fila ya existía
        // (v3.1), este `Registro` no es su dueño: contesta desde la fila, con el contrato de la fila, y no deja lápida.
        val folioOriginal = when (s) {
            is SolicitudDeVenta -> {
                if (!enLibreta { libreta.anotar(nuevaFila(s)) }) {
                    r.ajeno = true
                    antesDeBuscar(s.id)
                    val f = enLibreta { libreta.buscar(s.id) }
                    return null.also { r.emitir(f?.let { contestarDesdeLaFila(it, s) } ?: PorConfirmar(s.importeCentavos)) }
                }
                null
            }
            is SolicitudDeCancelacion -> anotarLaCancelacion(r, s) ?: return null
        }
        r.anotado = true
        if (!red.hayRed()) return noSalio(r, MotivoNoSalio.SIN_RED)
        if (r.cortado()) return noSalio(r, MotivoNoSalio.INTERRUMPIDO)
        val sesion = sesionPara(r, limiteDeSesion) ?: return noSalio(r, MotivoNoSalio.SIN_LLAVES)
        if (!lectorOcupado.tryLock()) return noSalio(r, MotivoNoSalio.TERMINAL_OCUPADA)
        try {
            // T9, paso 4 (r1 A): ¿Blumon deja cancelar esa venta? ANTES de pedir la tarjeta (no usa nada de ella) y de gastar
            // contador. Un «sí» no autoriza: lo decide el CAS de envío. Sin respuesta (r1 B) es «sin conexión», no un «no».
            if (folioOriginal != null) {
                val pre = conBuzon(relojes.esperaRespuesta) { espera, alLeer -> blumon.validarCancelacion(sesion, folioOriginal, espera, alLeer) }
                when (Clasificador.preflightDeCancelacion(pre, identidad.ambiente)) {
                    true -> if (r.cortado()) return noSalio(r, MotivoNoSalio.INTERRUMPIDO)
                    false -> return noSalio(r, MotivoNoSalio.CANCELACION_NO_PERMITIDA)
                    // Ronda 2 · Q: no salió ⇒ INTERRUMPIDO (como la venta y la cancelación); sin respuesta o transporte dudoso ⇒ SIN_RED.
                    null -> return noSalio(r, if (pre == Respuesta.NoEnviada) MotivoNoSalio.INTERRUMPIDO else MotivoNoSalio.SIN_RED)
                }
            }
            r.emitir(EventoDeCobro.PideTarjeta)
            val leida = withTimeoutOrNull(relojes.esperaLectura) {
                lector.leer(operacion, r.importeCentavos, relojes.esperaLectura)
            } ?: ResultadoDeLectura.Vencida
            val lectura = when (leida) {
                is ResultadoDeLectura.Leida -> leida.lectura
                is ResultadoDeLectura.Cortada -> return noSalio(r, MotivoNoSalio.LECTURA_CORTADA)
                ResultadoDeLectura.Cancelada -> return noSalio(r, MotivoNoSalio.CANCELADA_EN_LECTOR)
                ResultadoDeLectura.Vencida -> return noSalio(r, MotivoNoSalio.LECTURA_VENCIDA)
            }
            if (r.cortado()) return noSalio(r, MotivoNoSalio.INTERRUMPIDO)
            val combinacion = when (val a = TablaDeAdmision.evaluar(lectura, operacion, r.importeCentavos)) {
                is Admision.Admitida -> a.combinacion
                is Admision.NoAdmitida -> return noSalio(r, MotivoNoSalio.NO_ADMITIDA, a.motivo)
            }
            // Amex no gasta contador; las demás lo reservan durable ANTES de cifrar.
            var contador: Long? = null
            val material = if (armado.necesitaDukpt(lectura)) {
                contador = enLibreta { libreta.reservarContador(sesion.ksnBaseCanonica.hexABytes(), sesion.ultimoContadorDeBlumon.toLong()) }
                    ?: return noSalio(r, MotivoNoSalio.CONTADOR_AGOTADO).also { pedirLlavesNuevas() }
                val n = contador.toInt()
                // Un préstamo: la copia es sólo para armar ESTE cuerpo. Si el material ya se cerró, no sale.
                sesion.material.usar { ipek, ksn -> MaterialDukpt(ipek.copyOf(), ksn.copyOf(), n) }
                    ?: return noSalio(r, MotivoNoSalio.SIN_LLAVES)
            } else {
                null
            }
            val cuerpo = try {
                if (folioOriginal != null) armado.cancelacion(s.id, r.importeCentavos, lectura, material)
                else armado.venta(s.id, r.importeCentavos, lectura, material)
            } finally {
                material?.ipek?.fill(0)
                material?.ksnBase?.fill(0)
            }
            val pan = lectura.pan()
            val datos = DatosDelEnvio(
                ksnBase = material?.let { sesion.ksnBaseCanonica }, contadorUsado = contador,
                marca = combinacion.marca, medio = combinacion.medio, bin = lectura.bin(), ultimos4 = lectura.ultimos4(),
                paisEmisor = lectura.paisEmisor(), huellaTarjeta = pan?.let(huella::de),
                huellaLectura = lectura.huellaDeLectura()?.let(huella::de),
            )
            val vigente = this.sesion?.takeIf { it.material === sesion.material } ?: sesion
            return Salida(vigente, cuerpo, Clasificador.Esperado(s.id, r.importeCentavos, pan, identidad.ambiente), datos, folioOriginal)
        } finally {
            lectorOcupado.unlock() // paso 10: la terminal queda libre para otra venta
        }
    }

    private suspend fun noSalio(r: Registro, motivo: MotivoNoSalio, admision: MotivoNoAdmitida? = null): Salida? {
        val f = enLibreta {
            libreta.transicionar(r.id, EstadoDelIntento.ANOTADO) {
                it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = motivo)
            } ?: libreta.buscar(r.id)
        }
        // Regla N: el «no salió» sólo con su fila; si la fila dice otra cosa, manda la fila.
        val propio = f != null && f.estado == EstadoDelIntento.NO_SALIO && f.motivoNoSalio == motivo
        r.emitir(if (propio) NoSalioAlBanco(motivo, admision) else f?.let(::desdeLaFila) ?: PorConfirmar(r.importeCentavos))
        return null
    }

    /**
     * T9, regla N: la fila de ESTA cancelación, en la misma transacción que la decide. Si no se admite, queda `NO_SALIO` con
     * su motivo y así se contesta; si el folio ya tenía fila, se contesta desde ella y este `Registro` es ajeno. Regresa el
     * folio de Blumon de la venta que se cancela, o `null` con el terminal ya emitido.
     */
    private suspend fun anotarLaCancelacion(r: Registro, s: SolicitudDeCancelacion): Long? {
        val anotada = enLibreta { libreta.anotarCancelacion(nuevaFila(s), identidad.ambito()) }
        val (f, o) = enLibreta { libreta.buscar(s.id) to libreta.buscar(s.original) }
        // Ola final, 4ª parte · 1b (r25 A1): el importe sólo sale de la fila si es de ESTE contrato; la de otro (`FolioUsado`) no
        // se le enseña a quien preguntó: 0 = «todavía no se sabe».
        r.importeCentavos = f?.takeIf { mismoContrato(it, s) }?.importeCentavos ?: 0
        r.emitir(EventoDeCobro.Iniciado(s.id, identidad, Operacion.CANCELACION, r.importeCentavos))
        if (anotada == ResultadoDeAnotarCancelacion.Anotada) {
            r.anotado = true
            return o?.operationNumber ?: null.also { noSalio(r, MotivoNoSalio.SIN_VENTA_APROBADA) } // la libreta ya lo revisó
        }
        if (anotada == ResultadoDeAnotarCancelacion.FolioUsado) r.ajeno = true else r.anotado = true
        r.emitir(f?.let { contestarDesdeLaFila(it, s) } ?: PorConfirmar(r.importeCentavos))
        return null
    }

    /** `null`: la propia fila ya no aplica (no está, no es cancelación, ya no está `ANOTADO` o la cortaron): contesta la fila. */
    private fun motivoDe(e: ResultadoDeEnviarCancelacion): MotivoNoSalio? = when (e) {
        ResultadoDeEnviarCancelacion.OtraTarjeta -> MotivoNoSalio.OTRA_TARJETA
        ResultadoDeEnviarCancelacion.LecturaRepetida -> MotivoNoSalio.LECTURA_REPETIDA
        ResultadoDeEnviarCancelacion.OtroAmbito -> MotivoNoSalio.OTRO_AMBITO
        ResultadoDeEnviarCancelacion.SinVentaAprobada -> MotivoNoSalio.SIN_VENTA_APROBADA
        ResultadoDeEnviarCancelacion.YaExiste -> MotivoNoSalio.CANCELACION_YA_EXISTE
        ResultadoDeEnviarCancelacion.YaNoAplica, ResultadoDeEnviarCancelacion.Enviada -> null
    }

    /** De una cancelación, el importe, el origen y `vinculado` los pone la libreta desde la original ([Libreta.anotarCancelacion]). */
    private fun nuevaFila(s: Solicitud): IntentoEntity {
        val origen = (s as? SolicitudDeVenta)?.origen ?: Origen.VINCULADO_AL_POS
        return IntentoEntity(
            attemptId = s.id.valor, operacion = if (s is SolicitudDeVenta) Operacion.VENTA else Operacion.CANCELACION,
            venueId = s.venueId, importeCentavos = importeDe(s), vinculado = origen == Origen.VINCULADO_AL_POS,
            posId = identidad.posId, serieBlumon = identidad.serieBlumon, ambiente = identidad.ambiente,
            versionSdk = identidad.versionSdk, origen = origen, operacionOriginal = (s as? SolicitudDeCancelacion)?.original?.valor,
        )
    }

    // ───────────── La sesión (Global Constraints, r4 R4-05) ─────────────

    /** Paso 4: si hay una sesión usable, el intento la reserva ANTES de anotar, bajo el mismo candado que decide renovarla. */
    private suspend fun reservarSesion(r: Registro, limite: Long) {
        withTimeoutOrNull(limite - ahora()) {
            candadoDeSesion.withLock {
                if (sesion != null && !renovarLlaves) {
                    reservas++
                    r.reservada.set(true)
                }
            }
        }
    }

    /**
     * Paso 5: la reservada, la usable, o una nueva. Renovar las LLAVES exige cero reservas vivas y que la libreta no tenga en
     * este proceso ningún intento que todavía pueda necesitar reverso; si no se puede, `null` ⇒ `SIN_LLAVES`. Un token
     * vencido (401/403) sólo renueva el token, sin esperar reservas; si ni eso se puede, tampoco sale (iría con un token
     * que ya se probó malo).
     */
    private suspend fun sesionPara(r: Registro, limite: Long): SesionBlumon? =
        withTimeoutOrNull(limite - ahora()) { candadoDeSesion.withLock { sesionConCandado(r, limite) } }

    private suspend fun sesionConCandado(r: Registro, limite: Long): SesionBlumon? {
        if (cerrado) return null
        if (!r.reservada.get()) {
            if (sesion == null || renovarLlaves) {
                val ocupada = reservas > 0 || enLibreta {
                    libreta.hayIntentosQuePuedenNecesitarReverso(libreta.procesoId, ahora(), relojes.finDelVeto)
                }
                if (ocupada) return null
                // Sin `withTimeout` entre el nacimiento del material y este dueño (R3-4): el plazo es el del transporte, y del
                // regreso de `iniciarSesion` a la instalación no hay ningún punto de suspensión que un reloj pueda cortar.
                val espera = minOf(relojes.esperaSesion, limite - ahora()).takeIf { it > 0 } ?: return null
                val nueva = intentar { blumon.iniciarSesion(espera.milliseconds) } ?: return null
                val vieja = sesion
                sesion = nueva
                vieja?.material?.takeIf { it !== nueva.material }?.close()
                renovarLlaves = false
                tokenVencido = false
            }
            reservas++
            r.reservada.set(true)
        }
        if (tokenVencido && renovarTokenConCandado(sesion?.token, esperaDelToken(limite - ahora())) == null) return null
        return sesion
    }

    /** Una renovación del token nunca tiene el candado más que una espera de sesión ni más que una vuelta del reverso. */
    private fun esperaDelToken(presupuesto: Long) = minOf(relojes.esperaSesion, relojes.esperaReverso, presupuesto)

    /** Un 401/403 de Blumon: la siguiente venta (o la siguiente vuelta del reverso) renueva sólo el token. Sin candado. */
    internal fun tokenVencido() {
        tokenVencido = true
    }

    /** El contador agotado (o, en las pruebas, otra causa): la siguiente venta pide llaves nuevas cuando ya se pueda. */
    internal fun pedirLlavesNuevas() {
        renovarLlaves = true
    }

    /**
     * Un token nuevo para el reverso, con el candado de la sesión y dentro de [presupuesto] ms. Si otra venta ya lo renovó
     * (el de la sesión no es [viejo]), se usa ése sin pedir otro. `null` si no se pudo a tiempo.
     */
    @JvmSynthetic internal suspend fun tokenNuevo(viejo: String, presupuesto: Long): String? {
        val espera = esperaDelToken(presupuesto)
        return withTimeoutOrNull(espera) { candadoDeSesion.withLock { renovarTokenConCandado(viejo, espera) } }
    }

    /** Sólo con el candado tomado. Cambia el token de la sesión sin tocar sus llaves (los mismos arreglos de IPEK y base). */
    private suspend fun renovarTokenConCandado(viejo: String?, espera: Long): String? {
        val actual = sesion
        if (actual != null && actual.token != viejo && !tokenVencido) return actual.token
        val nuevo = withTimeoutOrNull(espera) { intentar { blumon.renovarToken(espera.milliseconds) } }?.valor ?: return null
        if (actual != null) sesion = actual.conToken(nuevo) // el MISMO material: ni se copia ni se cierra (v4-8)
        tokenVencido = false
        return nuevo
    }

    /** En un desenlace sin envío, al pasar t0 + techo o al terminar el camino del reverso. Idempotente. */
    internal suspend fun soltarReserva(r: Registro) {
        if (r.reservada.compareAndSet(true, false)) withContext(NonCancellable) { candadoDeSesion.withLock { reservas-- } }
    }

    // ───────────── A y G. Después de salir ─────────────

    private suspend fun despuesDeSalir(r: Registro, salida: Salida, t0: Long) {
        r.emitir(EventoDeCobro.Autorizando)
        val cajera = fondo.launch { delay(t0 + relojes.peorCasoCajera - ahora()); soltarALaCajera(r) }
        val techo = fondo.launch { delay(t0 + relojes.finDelVeto - ahora()); soltarReserva(r) } // r2 familia E
        try {
            val venta = fondo.async {
                intentar {
                    blumon.cobrar(salida.sesion, salida.cuerpo, relojes.esperaRespuesta.milliseconds, t0 + relojes.esperaRespuesta)
                } ?: Respuesta.SinRespuesta("EXCEPCION")
            }
            val respuesta = withTimeoutOrNull(t0 + relojes.esperaRespuesta - ahora()) { venta.await() }
            if (respuesta == null) { // A: sin respuesta a los 15 s
                r.emitir(EventoDeCobro.RevisandoConElBanco)
                fondo.launch { guardarRespuestaTardia(r.id, venta.await(), salida.esperado) }
                return caminoDelReverso(r, salida, t0)
            }
            if (Clasificador.pideLlavesNuevas(respuesta)) pedirLlavesNuevas() // T8 arreglos 1 (D)
            if (respuesta is Respuesta.Http && respuesta.codigo in TOKEN_VENCIDO) {
                r.ventaConTokenMalo = true
                tokenVencido()
            }
            when (val d = Clasificador.venta(respuesta, salida.esperado, catalogo)) {
                is DesenlaceDeVenta.Aprobada -> {
                    enLibreta { libreta.guardarAprobacion(r.id, d.operationNumber, d.autorizacion) }
                    emitirFila(r)
                }
                is DesenlaceDeVenta.Rechazada -> {
                    enLibreta { libreta.guardarRechazo(r.id, d.codigo, d.leyenda) }
                    emitirFila(r)
                }
                DesenlaceDeVenta.NoEnviada -> {
                    enLibreta {
                        libreta.transicionar(r.id, EstadoDelIntento.ENVIADO) {
                            it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.INTERRUMPIDO)
                        }
                    }
                    emitirFila(r)
                }
                DesenlaceDeVenta.NoSe -> caminoDelReverso(r, salida, t0)
                is DesenlaceDeVenta.DebeReversar -> {
                    // La evidencia del parcial se guarda ANTES de reversar, y nunca promueve el estado.
                    enLibreta {
                        libreta.transicionar(r.id, EstadoDelIntento.ENVIADO) {
                            it.copy(parcialOperacion = d.operationNumber, parcialImporteCentavos = d.importeAutorizadoCentavos)
                        }
                    }
                    caminoDelReverso(r, salida, t0)
                }
            }
        } finally {
            cajera.cancel()
            techo.cancel()
        }
    }

    /**
     * Después del arbitraje una respuesta de la venta sólo se guarda como evidencia (la libreta lo decide por CAS): el
     * aprobado, el rechazo y el parcial, éste con su folio e importe ya cotejados (r2 R2-3, familia C). `NoSe` no trae nada.
     */
    private suspend fun guardarRespuestaTardia(id: AttemptId, r: Respuesta, esperado: Clasificador.Esperado) {
        if (Clasificador.pideLlavesNuevas(r)) pedirLlavesNuevas()
        when (val d = Clasificador.venta(r, esperado, catalogo)) {
            is DesenlaceDeVenta.Aprobada -> intentar { enLibreta { libreta.guardarAprobacion(id, d.operationNumber, d.autorizacion) } }
            is DesenlaceDeVenta.Rechazada -> intentar { enLibreta { libreta.guardarRechazo(id, d.codigo, d.leyenda) } }
            is DesenlaceDeVenta.DebeReversar ->
                intentar { enLibreta { libreta.guardarParcialTardio(id, d.operationNumber, d.importeAutorizadoCentavos) } }
            DesenlaceDeVenta.NoSe, DesenlaceDeVenta.NoEnviada -> Unit
        }
    }

    /**
     * T9: la cancelación ya salió. Un solo reloj (la ventana de la cajera) y nada se tira ([conBuzon]); la cajera sale a los
     * `peorCasoCajera` desde la memoria ([soltarALaCajera], que sin reverso no toca la fila). El desenlace va a la fila por CAS
     * desde `ENVIADO`. Nunca se reversa: `NoSe` ⇒ `NO_SE` + `PorConfirmar`.
     */
    private suspend fun despuesDeCancelar(r: Registro, salida: Salida, t0: Long) {
        r.emitir(EventoDeCobro.Autorizando)
        val cajera = fondo.launch { delay(t0 + relojes.peorCasoCajera - ahora()); soltarALaCajera(r) }
        try {
            val folio = requireNotNull(salida.folioOriginal)
            val respuesta = conBuzon(relojes.peorCasoCajera) { espera, alLeer -> blumon.cancelar(salida.sesion, folio, salida.cuerpo, espera, alLeer) }
            if (Clasificador.pideLlavesNuevas(respuesta)) pedirLlavesNuevas()
            if (respuesta is Respuesta.Http && respuesta.codigo in TOKEN_VENCIDO) tokenVencido()
            val cambio: (IntentoEntity) -> IntentoEntity = when (val d = Clasificador.cancelacion(respuesta, salida.esperado, catalogo)) {
                is DesenlaceDeCancelacion.Cancelada -> { f -> f.copy(estado = EstadoDelIntento.CANCELADA, operationNumber = d.operationNumber) }
                is DesenlaceDeCancelacion.Rechazada -> { f -> f.copy(estado = EstadoDelIntento.RECHAZADO, codigo = d.codigo, leyenda = d.leyenda) }
                DesenlaceDeCancelacion.NoEnviada -> { f -> f.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.INTERRUMPIDO) }
                DesenlaceDeCancelacion.NoSe -> { f -> f.copy(estado = EstadoDelIntento.NO_SE) }
            }
            enLibreta { libreta.transicionar(r.id, EstadoDelIntento.ENVIADO, cambio) }
            emitirFila(r)
        } finally {
            cajera.cancel()
        }
    }

    /**
     * T9, regla 5 (la de `unaVuelta`): la llamada recibe [espera] entera y deja en un buzón lo que el transporte lee ANTES de
     * reanudar; si el reloj de afuera (el mismo, nunca más corto) vence después, se usa lo del buzón.
     */
    private suspend fun conBuzon(espera: Long, llamada: suspend (Duration, (Respuesta) -> Unit) -> Respuesta): Respuesta {
        val buzon = AtomicReference<Respuesta?>(null)
        val r = withTimeoutOrNull(espera) { intentar { llamada(espera.milliseconds) { buzon.compareAndSet(null, it) } } ?: Respuesta.SinRespuesta("EXCEPCION") }
        return buzon.get() ?: r ?: Respuesta.SinRespuesta("TIEMPO")
    }

    /**
     * G con la vara A (R4-1): a los `peorCasoCajera` desde t0 la cajera recibe `PorConfirmar` PRIMERO, sin tocar Room; la
     * fila pasa a NO_SE después, en [fondo], sin que nadie la espere. Si el proceso muere entre los dos, `recuperar` lleva el
     * ENVIADO a NO_SE, y H reconstruye un ENVIADO como `PorConfirmar`.
     */
    private fun soltarALaCajera(r: Registro) {
        if (r.terminado) return
        r.emitir(PorConfirmar(r.importeCentavos))
        // Sin compromiso todavía (reverso NINGUNO) no se toca: un NO_SE antes del arbitraje dejaría la venta sin reverso.
        fondo.launch { intentar { enLibreta { ponerNoSe(r.id, soloConReverso = true) } } }
    }

    // ───────────── Ayudas ─────────────

    /** ENVIADO ⇒ NO_SE por CAS; si la fila ya cambió, se queda como esté. */
    internal fun ponerNoSe(id: AttemptId, soloConReverso: Boolean = false) {
        libreta.transicionar(id, EstadoDelIntento.ENVIADO) {
            if (soloConReverso && it.reverso == EstadoDelReverso.NINGUNO) it
            else it.copy(estado = EstadoDelIntento.NO_SE)
        }
    }

    internal suspend fun emitirFila(r: Registro) {
        val f = enLibreta { libreta.buscar(r.id) }
        r.emitir(f?.let(::desdeLaFila) ?: PorConfirmar(r.importeCentavos))
    }

    internal fun publicarTardia(id: AttemptId, e: EventoDeCobro) {
        tardias.tryEmit(id to e)
    }

    internal suspend fun <T> enLibreta(bloque: () -> T): T = withContext(io) { bloque() }

    /** Lo que falla se vuelve `null`; la cancelación siempre sigue su camino. */
    internal suspend fun <T> intentar(bloque: suspend () -> T): T? = try {
        bloque()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal companion object {
        private const val LIBRE = 0
        private const val ENVIANDO = 1
        private const val CORTADO = 2
        /** R24-2: un evento propio, nunca un «no salió»: el folio pudo salir y hasta aprobarse. */
        val CONTRATO_DISTINTO: EventoDeCobro = EventoDeCobro.ContratoDistinto
        internal val TOKEN_VENCIDO = setOf(401, 403)
        private const val LEYENDA_GENERICA = "RECHAZADA"

        /** H: la fila manda. ENVIADO y NO_SE ⇒ PorConfirmar; ANOTADO ⇒ NO_SALIO(INTERRUMPIDO). */
        fun desdeLaFila(f: IntentoEntity): EventoDeCobro = when (f.estado) {
            EstadoDelIntento.APROBADO -> EventoDeCobro.Aprobado(
                f.operationNumber, f.autorizacion, f.paymentId, f.marca, f.medio, f.bin, f.ultimos4, f.paisEmisor,
            )
            EstadoDelIntento.RECHAZADO -> EventoDeCobro.Rechazado(f.codigo ?: Clasificador.DESCONOCIDO, f.leyenda ?: LEYENDA_GENERICA)
            EstadoDelIntento.NO_SALIO -> NoSalioAlBanco(f.motivoNoSalio ?: MotivoNoSalio.INTERRUMPIDO, null)
            EstadoDelIntento.ANOTADO -> NoSalioAlBanco(MotivoNoSalio.INTERRUMPIDO, null)
            EstadoDelIntento.NO_SE_COBRO -> EventoDeCobro.NoSeCobro
            EstadoDelIntento.CANCELADA -> f.operationNumber?.let(EventoDeCobro::Cancelada) ?: PorConfirmar(f.importeCentavos)
            EstadoDelIntento.NO_SE, EstadoDelIntento.ENVIADO -> PorConfirmar(f.importeCentavos)
        }
    }
}
