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

    /** Agrega la página siguiente de [seccion]; no hace nada si ya no hay más. */
    suspend fun cargarMas(seccion: SeccionDeFolios) {
        candado.withLock {
            val venueId = negocioConSesion() ?: return
            val cursor = _estado.value.nextCursors[seccion] ?: return
            val pagina = folios.cargarMas(venueId, seccion, cursor).getOrElse {
                Timber.w(it, "[Folios] no se pudo cargar más de $seccion")
                return
            }
            _estado.value = _estado.value.let { e ->
                val cursores = e.nextCursors - seccion + pagina.siguientes.filterKey(seccion)
                when (seccion) {
                    SeccionDeFolios.POR_PAGAR -> e.copy(porPagar = e.porPagar.sumarSinRepetir(pagina.porPagar), nextCursors = cursores)
                    SeccionDeFolios.POR_ENTREGAR -> e.copy(porEntregar = e.porEntregar.sumarSinRepetir(pagina.porEntregar), nextCursors = cursores)
                }
            }
        }
    }

    /** Una vuelta; regresa cuánto esperar antes de la siguiente. */
    private suspend fun vuelta(): Long = candado.withLock {
        val venueId = negocioConSesion()
        if (venueId == null) {
            // Sin sesión: no consulta, y no se queda lo del negocio anterior en pantalla.
            negocioActual = null
            ultimaEntrada = null
            if (_estado.value != EstadoDeFolios()) _estado.value = EstadoDeFolios()
            return@withLock ESPERA_SIN_SESION_MS
        }
        if (negocioActual != venueId) {
            _estado.value = EstadoDeFolios()
            negocioActual = venueId
        }
        val pagina = folios.listar(venueId, puedeCobrar(venueId)).getOrElse { error ->
            if (!esRespuesta(error)) _estado.value = _estado.value.copy(sinConexion = true)
            Timber.w(error, "[Folios] sin respuesta del servidor")
            return@withLock ESPERA_TRAS_FALLO_MS
        }
        val ahora = reloj()
        _estado.value = pagina.aEstado(ahora)
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

    // ponytail: la vuelta periódica trae sólo la primera página de cada sección; lo cargado con «Cargar más» se pierde en ella.
    // Si hacen falta cientos de folios vivos, conservar el cursor que ya se había paginado.
    private fun List<Folio>.sumarSinRepetir(mas: List<Folio>): List<Folio> {
        val vistos = mapTo(HashSet()) { it.orderId }
        return this + mas.filter { vistos.add(it.orderId) }
    }

    companion object {
        const val ESPERA_TRAS_FALLO_MS = 30_000L
        const val ESPERA_SIN_SESION_MS = 10_000L
    }
}
