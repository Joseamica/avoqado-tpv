package com.jaac.avoqado_tpv.features.onlineorders.data

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result as AppResult
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.presentation.CachedShiftInfo
import com.jaac.avoqado_tpv.features.shift.presentation.turnoPermiteVender
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El vigía de «Pedidos por pagar»: vive con la actividad ([iniciar]) y, mientras haya sesión guardada, le pregunta al SERVIDOR por los
 * folios cada `pollSeconds` (30 con el recuadro, 600 sin él). Ninguna pantalla lo arranca ni lo para: así también corre cuando
 * `SplashScreen` restaura la sesión sin pasar por el login.
 *
 * **Latido.** Sólo manda `x-online-orders-to-pay: 1` si la terminal PUEDE COBRAR ahora, con la misma condición completa que habilita
 * «Cobrar» en `WelcomeScreen`: `(turnoPermiteVender(…) || !sistemaDeTurnos) && (entradaRegistrada || !checadaObligatoria)`. Se calcula
 * en cada vuelta contra los repositorios (nunca contra lo que una pantalla recuerde): cerrar la caja sin volver al inicio quita la
 * cabecera en la vuelta siguiente.
 *
 * **Sin red** se conserva la última lista y [EstadoDeFolios.sinConexion] es true. Un 4xx es una respuesta, no un apagón.
 *
 * Constructor primario `internal` (con reloj) para las pruebas; el que usa Hilt es el secundario (Dagger rechaza los defaults).
 */
@Singleton
class VigiaDeFolios internal constructor(
    private val folios: FoliosRepository,
    private val shifts: ShiftRepository,
    private val asistencia: TimeEntryRepository,
    private val ajustes: TpvSettingsRepository,
    private val storage: SecureStorage,
    private val reloj: () -> Long,
) {
    @Inject
    constructor(
        folios: FoliosRepository,
        shifts: ShiftRepository,
        asistencia: TimeEntryRepository,
        ajustes: TpvSettingsRepository,
        storage: SecureStorage,
    ) : this(folios, shifts, asistencia, ajustes, storage, System::currentTimeMillis)

    private val _estado = MutableStateFlow(EstadoDeFolios())
    val estado: StateFlow<EstadoDeFolios> = _estado.asStateFlow()

    private val candado = Mutex()
    private val despertar = Channel<Unit>(Channel.CONFLATED)
    /** R79 #6: pide releer las páginas extra. Conflated: con una relectura en vuelo, varios sondeos dejan UNA pendiente. */
    private val releer = Channel<Unit>(Channel.CONFLATED)
    private var trabajo: Job? = null
    /** El alcance de [iniciar]; [soltarExtras] corre ahí (no tiene uno propio). */
    private var alcance: CoroutineScope? = null
    private var negocioActual: String? = null

    /** Última respuesta de la checada por persona, para el instante sin red (ahí el latido no se puede mandar de todos modos). */
    private var ultimaEntrada: Pair<String, Boolean>? = null

    /** Arranca el bucle en [scope] (el de la actividad). Idempotente: si ya corre, no hace nada. */
    fun iniciar(scope: CoroutineScope) {
        if (trabajo?.isActive == true) return
        alcance = scope
        trabajo = scope.launch {
            // R79 #6: las páginas extra se releen en su propio bucle, así la vuelta de la página 1 (el latido) nunca espera a una
            // relectura lenta. Un solo consumidor ⇒ nunca corren dos; la que está en vuelo termina y se aplica, y la pendiente corre después.
            // ponytail: si cada relectura tarda más que el sondeo, se encadenan sin pausa (una sola en vuelo, acotada a lo que la
            // cajera cargó); se afloja con un respiro entre relecturas si llega a pesar.
            launch {
                for (pedido in releer) {
                    try {
                        SeccionDeFolios.values().forEach { releerSeccion(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "[Folios] la relectura de las páginas extra falló")
                    }
                }
            }
            while (isActive) {
                val espera = try {
                    vuelta()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "[Folios] la vuelta falló")
                    ESPERA_TRAS_FALLO_MS
                }
                // Dormir hasta la hora o hasta que alguien pida sondear ya (conflated: varias peticiones = una).
                withTimeoutOrNull(espera) { despertar.receive() }
            }
        }
    }

    /**
     * R92 M3: la pantalla de la lista se fue. Se sueltan las páginas extra (y dejan de releerse cada vuelta); la página 1 y su
     * número siguen para el recuadro del inicio.
     */
    fun soltarExtras() {
        alcance?.launch {
            candado.withLock {
                extras = emptyMap(); cursorExtra = emptyMap()
                _estado.value = componer(_estado.value)
            }
        }
    }

    /** Pregunta ahora mismo (p. ej. al volver al inicio o tras cobrar) y reinicia la espera. */
    fun sondearYa() {
        despertar.trySend(Unit)
    }

    /**
     * Agrega la página siguiente de [seccion]. Regresa true si la cargó (o si ya no había más) y false si falló: además
     * [EstadoDeFolios.errorAlCargarMas] queda en true hasta el siguiente intento, para el «No se pudieron cargar más».
     */
    suspend fun cargarMas(seccion: SeccionDeFolios): Boolean = candado.withLock {
        val venueId = negocioConSesion() ?: return@withLock false
        val cursor = _estado.value.nextCursors[seccion] ?: return@withLock true
        val pagina = folios.cargarMas(venueId, seccion, cursor).getOrElse {
            Timber.w(it, "[Folios] no se pudo cargar más de $seccion")
            _estado.value = _estado.value.copy(errorAlCargarMas = true)
            return@withLock false
        }
        agregarPagina(seccion, pagina.de(seccion), pagina.siguientes[seccion])
        _estado.value = componer(_estado.value.copy(errorAlCargarMas = false))
        true
    }

    /** Lo que trajo la página 1 de la última vuelta buena (las páginas extra viven aparte, en [extras]). */
    private var pagina1Pagar: List<Folio> = emptyList()
    private var pagina1Entregar: List<Folio> = emptyList()
    private var cursoresPagina1: Map<SeccionDeFolios, String> = emptyMap()
    /** Una página de «Cargar más». [id] no cambia al releerla: así una relectura sabe cuáles son SUYAS (R92 M1). */
    private class PaginaExtra(val id: Long, val folios: List<Folio>)

    /** Las páginas que la cajera cargó con «Cargar más», en orden, por sección (una página vacía también cuenta). */
    private var extras: Map<SeccionDeFolios, List<PaginaExtra>> = emptyMap()
    private var ultimoIdDePagina = 0L
    /** Cursor tras la última página extra de cada sección; sin llave = no hay más. */
    private var cursorExtra: Map<SeccionDeFolios, String> = emptyMap()

    private fun olvidarListas() {
        pagina1Pagar = emptyList(); pagina1Entregar = emptyList(); cursoresPagina1 = emptyMap()
        extras = emptyMap(); cursorExtra = emptyMap()
    }

    /** «Cargar más»: agrega la página al final con su cursor. */
    private fun agregarPagina(seccion: SeccionDeFolios, items: List<Folio>, siguiente: String?) {
        extras = extras + (seccion to extras[seccion].orEmpty() + PaginaExtra(++ultimoIdDePagina, items))
        cursorExtra = if (siguiente == null) cursorExtra - seccion else cursorExtra + (seccion to siguiente)
        quitarDeLaOtra(seccion, items)
    }

    /**
     * Relectura: pone [items] en la página [id]. Sin [siguiente] ya no hay más: se sueltan [despues] (las que ESTA relectura iba a
     * leer después), nunca una página que la cajera agregó mientras tanto (R92 M1). El cursor sólo cambia si la página es la última.
     * false si la página ya no está (otra vuelta, la pantalla o la sesión la soltó): una relectura vieja no la revive.
     */
    private fun reemplazarPagina(seccion: SeccionDeFolios, id: Long, items: List<Folio>, siguiente: String?, despues: Set<Long>): Boolean {
        val paginas = extras[seccion].orEmpty()
        if (paginas.none { it.id == id }) return false
        val nuevas = paginas
            .map { if (it.id == id) PaginaExtra(id, items) else it }
            .filterNot { siguiente == null && it.id in despues }
        extras = extras + (seccion to nuevas)
        if (nuevas.last().id == id) cursorExtra = if (siguiente == null) cursorExtra - seccion else cursorExtra + (seccion to siguiente)
        quitarDeLaOtra(seccion, items)
        return true
    }

    /** Un folio sólo vive en UNA sección: lo que llegó a [seccion] sale de las páginas extra de la otra (la lectura más nueva manda). */
    private fun quitarDeLaOtra(seccion: SeccionDeFolios, items: List<Folio>) {
        val ids = items.mapTo(HashSet()) { it.orderId }
        val otra = SeccionDeFolios.values().first { it != seccion }
        extras[otra]?.let { pgs -> extras = extras + (otra to pgs.map { p -> PaginaExtra(p.id, p.folios.filter { it.orderId !in ids }) }) }
    }

    /**
     * Página 1 + extras, sin repetir un `orderId` (Dc1-m1): ni entre páginas que se recorrieron, ni dentro de una sección, ni entre las
     * dos; manda la página 1. El conteo NO sale de aquí: viene tal cual del SERVIDOR en [base].
     */
    private fun componer(base: EstadoDeFolios): EstadoDeFolios {
        val vistos = (pagina1Pagar + pagina1Entregar).mapTo(HashSet()) { it.orderId }
        val xPagar = extras[SeccionDeFolios.POR_PAGAR].orEmpty().flatMap { it.folios }.filter { vistos.add(it.orderId) }
        val xEntregar = extras[SeccionDeFolios.POR_ENTREGAR].orEmpty().flatMap { it.folios }.filter { vistos.add(it.orderId) }
        // El cursor de una sección con páginas extra es el de la última que se cargó; sin ellas, el de la página 1.
        val cur = buildMap {
            for (sec in SeccionDeFolios.values()) {
                val c = if (extras[sec].isNullOrEmpty()) cursoresPagina1[sec] else cursorExtra[sec]
                if (c != null) put(sec, c)
            }
        }
        return base.copy(porPagar = pagina1Pagar + xPagar, porEntregar = pagina1Entregar + xEntregar, nextCursors = cur)
    }

    /**
     * R79 #6: relee, en orden, las páginas extra de [seccion] que había al empezar (nunca más) desde el cursor de la página 1 de la
     * última vuelta. Cada página se aplica al llegar. Si una ya no trae siguiente, las de después que iba a leer se sueltan (sin
     * camino a ellas: nunca se quedan tarjetas viejas). Si una lectura falla se queda lo que se veía y el siguiente sondeo lo intenta
     * otra vez.
     */
    private suspend fun releerSeccion(seccion: SeccionDeFolios) {
        val (venueId, ids, inicio) = candado.withLock { Triple(negocioActual, extras[seccion].orEmpty().map { it.id }, cursoresPagina1[seccion]) }
        if (venueId == null || ids.isEmpty()) return
        var cursor = inicio
        var i = 0
        while (cursor != null && i < ids.size) {
            val pagina = folios.cargarMas(venueId, seccion, cursor).getOrElse {
                Timber.w(it, "[Folios] no se pudo releer $seccion")
                return
            }
            val siguiente = pagina.siguientes[seccion]
            val despues = ids.drop(i + 1).toSet()
            val sigue = candado.withLock {
                (negocioActual == venueId && reemplazarPagina(seccion, ids[i], pagina.de(seccion), siguiente, despues))
                    .also { if (it) _estado.value = componer(_estado.value) }
            }
            if (!sigue) return
            cursor = siguiente
            i++
        }
    }

    /** Una vuelta; regresa cuánto esperar antes de la siguiente. */
    private suspend fun vuelta(): Long = candado.withLock {
        val venueId = negocioConSesion()
        if (venueId == null) {
            // Sin sesión: no consulta, y no se queda lo del negocio anterior en pantalla.
            negocioActual = null
            ultimaEntrada = null
            olvidarListas()
            if (_estado.value != EstadoDeFolios()) _estado.value = EstadoDeFolios()
            return@withLock ESPERA_SIN_SESION_MS
        }
        if (negocioActual != venueId) {
            _estado.value = EstadoDeFolios()
            olvidarListas()
            negocioActual = venueId
        }
        val pagina = folios.listar(venueId, puedeCobrar(venueId)).getOrElse { error ->
            // Red caída o 5xx = «sin conexión»; un 4xx (401/403/404) sí es respuesta: la lista ya no es fiable y se dice «desactualizado».
            _estado.value = if (esRespuesta(error)) _estado.value.copy(desactualizado = true) else _estado.value.copy(sinConexion = true)
            Timber.w(error, "[Folios] sin respuesta del servidor")
            return@withLock ESPERA_TRAS_FALLO_MS
        }
        val ahora = reloj()
        pagina1Pagar = pagina.porPagar; pagina1Entregar = pagina.porEntregar; cursoresPagina1 = pagina.siguientes
        // R79 #6: las páginas extra NO regresan a la página 1; se releen después de esta vuelta (ver [releerSeccion]). Una sección
        // cuya página 1 ya no trae cursor no tiene camino a ellas: se sueltan YA (nunca quedan tarjetas viejas, p. ej. un «Listo»).
        for (sec in SeccionDeFolios.values()) if (sec !in pagina.siguientes) { extras = extras - sec; cursorExtra = cursorExtra - sec }
        _estado.value = componer(pagina.aEstado(ahora).copy(errorAlCargarMas = _estado.value.errorAlCargarMas))
        if (extras.values.any { it.isNotEmpty() }) releer.trySend(Unit)
        segundosDeEspera(pagina) * 1000L
    }

    private fun negocioConSesion(): String? {
        if (storage.getToken().isNullOrBlank()) return null
        return storage.getVenueId()?.takeIf { it.isNotBlank() }
    }

    private fun segundosDeEspera(p: PaginaDeFolios): Long =
        (p.pollSeconds ?: if (p.showTile) 30 else 600).coerceIn(5, 3600).toLong()

    /** `(turnoPermiteVender || !sistemaDeTurnos) && (entradaRegistrada || !checadaObligatoria)`, igual que «Cobrar». */
    private suspend fun puedeCobrar(venueId: String): Boolean {
        val turnoOk = !storage.isShiftSystemEnabled() || hayTurnoParaVender(venueId)
        if (!turnoOk) return false
        return !ajustes.getCurrentSettings().requireClockInToLogin || entradaRegistrada(venueId)
    }

    private suspend fun hayTurnoParaVender(venueId: String): Boolean =
        when (val r = shifts.getCurrentShift(venueId)) {
            is AppResult.Success -> turnoPermiteVender(r.data, sinConexion = false, ultimoConocido = null)
            is AppResult.Error -> {
                // El servidor no contestó: vale el último turno abierto que ESTE aparato confirmó (como «Cobrar»).
                val sinServidor = r.exception.servidorNoContesto
                val guardado = if (sinServidor) shifts.getCachedOpenShift(venueId) else null
                turnoPermiteVender(null, sinServidor, guardado?.let { CachedShiftInfo(true, it.staffName, 0) })
            }
        }

    private suspend fun entradaRegistrada(venueId: String): Boolean {
        val staffId = storage.getStaffId() ?: return false
        val llave = "$venueId/$staffId"
        val r = asistencia.findActiveEntryForStaff(venueId, staffId)
        r.onSuccess { entry ->
            val ok = entry?.status == TimeEntryStatus.CLOCKED_IN
            ultimaEntrada = llave to ok
            return ok
        }
        val sinServidor = (r.exceptionOrNull() as? ApiException)?.servidorNoContesto == true || r.exceptionOrNull() is IOException
        return sinServidor && ultimaEntrada?.takeIf { it.first == llave }?.second == true
    }

    /** Un 4xx es una respuesta del servidor (no hay "sin conexión"); lo demás (red, 5xx, 502-504) sí. */
    private fun esRespuesta(error: Throwable): Boolean =
        error is BackendHttpException && error.statusCode in 400..499

    private fun PaginaDeFolios.aEstado(ahora: Long) = EstadoDeFolios(
        showTile = showTile,
        counts = conteo,
        porPagar = porPagar,
        porEntregar = porEntregar,
        nextCursors = siguientes,
        sinConexion = false,
        actualizadoEnMs = ahora,
        desfaseDelServidorMs = horaDelServidor?.let { it.toEpochMilli() - ahora } ?: 0L,
    )

    private fun PaginaDeFolios.de(seccion: SeccionDeFolios) = if (seccion == SeccionDeFolios.POR_PAGAR) porPagar else porEntregar

    companion object {
        const val ESPERA_TRAS_FALLO_MS = 30_000L
        const val ESPERA_SIN_SESION_MS = 10_000L
    }
}
