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
    private var trabajo: Job? = null
    private var negocioActual: String? = null

    /** Última respuesta de la checada por persona, para el instante sin red (ahí el latido no se puede mandar de todos modos). */
    private var ultimaEntrada: Pair<String, Boolean>? = null

    /** Arranca el bucle en [scope] (el de la actividad). Idempotente: si ya corre, no hace nada. */
    fun iniciar(scope: CoroutineScope) {
        if (trabajo?.isActive == true) return
        trabajo = scope.launch {
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
        val nuevos = if (seccion == SeccionDeFolios.POR_PAGAR) pagina.porPagar else pagina.porEntregar
        // Un folio sólo vive en UNA sección: si ahora llegó a ésta, se quita de las extras de la otra.
        val ids = nuevos.mapTo(HashSet()) { it.orderId }
        when (seccion) {
            SeccionDeFolios.POR_PAGAR -> {
                extrasPagar = extrasPagar.sumarSinRepetir(nuevos)
                avanzadaPagar = true
                extrasEntregar = extrasEntregar.filter { it.orderId !in ids }
            }
            SeccionDeFolios.POR_ENTREGAR -> {
                extrasEntregar = extrasEntregar.sumarSinRepetir(nuevos)
                avanzadaEntregar = true
                extrasPagar = extrasPagar.filter { it.orderId !in ids }
            }
        }
        cursorExtra = cursorExtra - seccion + pagina.siguientes.filterKey(seccion)
        _estado.value = componer(_estado.value.copy(errorAlCargarMas = false), pagina1Pagar, pagina1Entregar, cursoresPagina1)
        true
    }

    /** Lo que trajo la página 1 de la última vuelta buena (las extras viven aparte, en [extrasPagar] y [extrasEntregar]). */
    private var pagina1Pagar: List<Folio> = emptyList()
    private var pagina1Entregar: List<Folio> = emptyList()
    private var cursoresPagina1: Map<SeccionDeFolios, String> = emptyMap()
    private var extrasPagar: List<Folio> = emptyList()
    private var extrasEntregar: List<Folio> = emptyList()
    private var cursorExtra: Map<SeccionDeFolios, String> = emptyMap()
    private var huellaPagar: List<String>? = null
    private var huellaEntregar: List<String>? = null
    /** La sección ya pidió páginas más allá de la 1 (aunque no haya traído nada): su cursor es [cursorExtra], no el de la página 1. */
    private var avanzadaPagar = false
    private var avanzadaEntregar = false

    private fun olvidarListas() {
        pagina1Pagar = emptyList(); pagina1Entregar = emptyList(); cursoresPagina1 = emptyMap()
        extrasPagar = emptyList(); extrasEntregar = emptyList(); cursorExtra = emptyMap()
        huellaPagar = null; huellaEntregar = null
        avanzadaPagar = false; avanzadaEntregar = false
    }

    private fun huella(l: List<Folio>) = l.map { "${it.orderId}|${it.falta}|${it.estado}|${it.estadoDePago}" }

    /** Página 1 + extras, sin repetir un `orderId` ni dentro de una sección ni entre las dos (manda la página 1). */
    private fun componer(base: EstadoDeFolios, p1Pagar: List<Folio>, p1Entregar: List<Folio>, cursores: Map<SeccionDeFolios, String>): EstadoDeFolios {
        val enPagina1 = (p1Pagar + p1Entregar).mapTo(HashSet()) { it.orderId }
        val xPagar = extrasPagar.filter { it.orderId !in enPagina1 }
        val idsPagar = xPagar.mapTo(HashSet()) { it.orderId }
        val xEntregar = extrasEntregar.filter { it.orderId !in enPagina1 && it.orderId !in idsPagar }
        // El cursor de una sección con extras es el de la última página cargada; sin extras, el de la página 1.
        val cur = buildMap {
            for (sec in SeccionDeFolios.values()) {
                val tieneExtras = if (sec == SeccionDeFolios.POR_PAGAR) avanzadaPagar else avanzadaEntregar
                val c = if (tieneExtras) cursorExtra[sec] else cursores[sec]
                if (c != null) put(sec, c)
            }
        }
        return base.copy(porPagar = p1Pagar + xPagar, porEntregar = p1Entregar + xEntregar, nextCursors = cur)
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
        // Las extras (lo cargado más allá de la página 1) se conservan mientras la página 1 de SU sección no cambie
        // (ids, falta, estado o pago); si cambió, esa sección vuelve a la página 1 y se descartan.
        // El conteo de la sección entra a la huella: un folio de las extras que se cobra o se va no mueve la página 1 pero sí el conteo.
        val hp = huella(pagina.porPagar) + "n=${pagina.conteo.porPagar}"; val he = huella(pagina.porEntregar) + "n=${pagina.conteo.porEntregar}"
        if (hp != huellaPagar) { extrasPagar = emptyList(); avanzadaPagar = false; cursorExtra = cursorExtra - SeccionDeFolios.POR_PAGAR }
        if (he != huellaEntregar) { extrasEntregar = emptyList(); avanzadaEntregar = false; cursorExtra = cursorExtra - SeccionDeFolios.POR_ENTREGAR }
        huellaPagar = hp; huellaEntregar = he
        pagina1Pagar = pagina.porPagar; pagina1Entregar = pagina.porEntregar; cursoresPagina1 = pagina.siguientes
        _estado.value = componer(pagina.aEstado(ahora).copy(errorAlCargarMas = _estado.value.errorAlCargarMas), pagina.porPagar, pagina.porEntregar, pagina.siguientes)
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

    private fun Map<SeccionDeFolios, String>.filterKey(k: SeccionDeFolios) = filterKeys { it == k }

    private fun List<Folio>.sumarSinRepetir(mas: List<Folio>): List<Folio> {
        val vistos = mapTo(HashSet()) { it.orderId }
        return this + mas.filter { vistos.add(it.orderId) }
    }

    companion object {
        const val ESPERA_TRAS_FALLO_MS = 30_000L
        const val ESPERA_SIN_SESION_MS = 10_000L
    }
}
