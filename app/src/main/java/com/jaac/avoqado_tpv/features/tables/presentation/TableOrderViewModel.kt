package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * `TableOrderScreen` (Plan C, Task 7) — lo ya enviado a cocina (agrupado por
 * ronda) vs lo pendiente de enviar. Puerto RECORTADO de
 * `avoqado-android/tables/presentation/TableOrderViewModel.kt` (`sendRound`
 * :403, write-ahead offline :539-576) — sin cursos/tiempos (menú por
 * horario), sin comandas (la TPV no tiene `PrintConfigRepository`/
 * `ComandaPrinter` — infraestructura que este repo no tiene todavía, no un
 * recorte silencioso), sin lealtad/descuentos/cargos por servicio (Task 8,
 * `TableCheckoutScreen`). El alcance real de esta tarea: ver el cheque, armar
 * y enviar una ronda, ofline-first, respetando propiedad de mesa.
 *
 * **Provisional-aware a propósito** (hallazgo documentado en el reporte de la
 * Task 5): [TablesRepository.addItems] SIEMPRE construye la URL con
 * `orderId` real — nunca se le debe llamar mientras
 * [TableSession.Active.isProvisional] sea true (la orden aún no existe en el
 * server). Mientras la sesión sea provisional, [sendRound] rutea por
 * [TableSession.buildAddItemsPayload] + [SyncOutbox.enqueue] directo, nunca
 * por el repositorio.
 */
@HiltViewModel
class TableOrderViewModel @Inject constructor(
    private val repository: TablesRepository,
    val tableSession: TableSession,
    val pendingCart: PendingRoundCart,
    private val tableSyncCoordinator: TableSyncCoordinator,
    private val syncOutbox: SyncOutbox,
    private val deviceInfoManager: DeviceInfoManager,
) : ViewModel() {

    private val _check = MutableStateFlow<OrderDetail?>(null)
    val check: StateFlow<OrderDetail?> = _check.asStateFlow()

    private val _isLoadingCheck = MutableStateFlow(false)
    val isLoadingCheck: StateFlow<Boolean> = _isLoadingCheck.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** Líneas en el carrito, SIN enviar todavía — ver [PendingRoundCart]. */
    val pendingLines: StateFlow<List<PendingRoundCart.Line>> = pendingCart.lines

    /**
     * Rondas "enviadas" SIN red — ya salieron del carrito, esperan el ack del
     * replay del outbox. `TableOrderScreen` las pinta con la etiqueta "Por
     * sincronizar", NUNCA como error (offline es estado normal — ver
     * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3).
     */
    private val _queuedLines = MutableStateFlow<List<PendingRoundCart.Line>>(emptyList())
    val queuedLines: StateFlow<List<PendingRoundCart.Line>> = _queuedLines.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Propiedad de mesa ("Solo el propietario puede modificar sus mesas") —
     * el cheque es de OTRO mesero, el switch del venue está encendido, y yo no
     * tengo `canManageAllTables` → la pantalla se pinta read-only. El server
     * refuerza la regla en el reducer de replay (`assertOwnership` en
     * `sync.mobile.service.ts`) de todos modos — esto es UX, no el candado real.
     *
     * `check?.servedBy?.id == null` (mesa recién abierta / sesión provisional
     * sin cheque cargado todavía) siempre da `false` aquí — [TableOwnership.isLockedForMe]
     * exige `ownerId != null` — nunca bloquea antes de saber quién es el dueño.
     */
    val readOnly: StateFlow<Boolean> = combine(_check, repository.ownership) { check, ownership ->
        ownership.isLockedForMe(check?.servedBy?.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Nombre del dueño para el banner "Mesa de {mesero} — solo lectura". */
    val lockOwnerName: StateFlow<String?> = combine(_check, repository.ownership) { check, ownership ->
        if (!ownership.isLockedForMe(check?.servedBy?.id)) return@combine null
        val served = check?.servedBy ?: return@combine null
        "${served.firstName} ${served.lastName}".trim().ifBlank { null }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val venueId: String? get() = deviceInfoManager.getVenueId()

    init {
        tableSession.current()?.let { pendingCart.ensureOrder(it.orderId) }
        loadCheck()
    }

    /** Carga (o recarga) el cheque desde el server. Sesión provisional = nada que cargar todavía. */
    fun loadCheck() {
        val session = tableSession.current() ?: return
        pendingCart.ensureOrder(session.orderId)
        if (session.isProvisional) {
            _check.value = null
            return
        }
        val vId = venueId ?: return
        viewModelScope.launch {
            _isLoadingCheck.value = true
            // Best-effort: drena el outbox antes de leer, para que el GET ya
            // vea las rondas que se mandaron sin red. Nunca lanza salvo
            // CancellationException — se blinda igual (mismo patrón que
            // TablesViewModel.loadTables).
            try {
                tableSyncCoordinator.replay(vId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [TableOrder] replay() del outbox falló antes de cargar la cuenta")
            }

            repository.getOrder(vId, session.orderId).fold(
                onSuccess = { detail ->
                    _check.value = detail
                    tableSession.updateVersion(detail.version)
                    // El replay pudo haber confirmado rondas que estaban en
                    // "Por sincronizar" — el cheque fresco ya las incluye.
                    _queuedLines.value = emptyList()
                },
                onFailure = { e ->
                    // 🔴 Sin red NO es un error — es el estado normal de un
                    // apagón. Avisar aquí hacía que el mesero viera "no se
                    // pudo cargar la cuenta" cada vez que entraba a una mesa
                    // con la cuenta perfectamente bien.
                    if (!isNetworkError(e)) {
                        _error.value = "No se pudo actualizar la cuenta"
                    }
                },
            )
            _isLoadingCheck.value = false
        }
    }

    private fun isNetworkError(e: Throwable): Boolean = when (e) {
        is IOException -> true
        is BackendHttpException -> e.statusCode in setOf(502, 503, 504, 408, 429)
        else -> false
    }

    /**
     * "Enviar ronda" — manda TODO el carrito en un solo `ADD_ITEMS` atómico.
     * Ver KDoc de la clase para el ruteo provisional-aware.
     */
    fun sendRound() {
        val session = tableSession.current()
        if (session == null) {
            _error.value = "No hay mesa activa"
            return
        }
        if (readOnly.value) {
            _error.value = "Mesa de ${lockOwnerName.value ?: "otro mesero"} — solo lectura"
            return
        }
        if (_isSending.value) return
        val lines = pendingCart.lines.value
        if (lines.isEmpty()) {
            _error.value = "Agrega productos para enviar"
            return
        }
        val vId = venueId
        if (vId == null) {
            _error.value = "No hay venue configurado en este terminal"
            return
        }
        val items = pendingCart.toAddItemsRequests()

        _isSending.value = true
        viewModelScope.launch {
            if (session.isProvisional) {
                // La orden de esta mesa AÚN no existe en el server (se abrió
                // offline) — jamás se intenta el PATCH online, que necesita un
                // orderId real. Ver TableSession.buildAddItemsPayload.
                val payload = tableSession.buildAddItemsPayload(items)
                syncOutbox.enqueue(vId, SyncIntentTypes.ADD_ITEMS, payload)
                _queuedLines.value = _queuedLines.value + lines
                pendingCart.clear()
                _isSending.value = false
                _notice.value = "Sin conexión — ronda guardada; se sincronizará sola"
                return@launch
            }

            repository.addItems(vId, session.orderId, items, session.version).fold(
                onSuccess = { detail ->
                    tableSession.updateVersion(detail.version)
                    val queuedOffline = TablesRepository.wasQueuedOffline(detail)
                    if (queuedOffline) {
                        // Ronda encolada por TablesRepository (sin red) — se ve
                        // como enviada, marcada "Por sincronizar", NUNCA como error.
                        _queuedLines.value = _queuedLines.value + lines
                        _notice.value = "Sin conexión — ronda guardada; se sincronizará sola"
                    } else {
                        _notice.value = "Ronda enviada a cocina"
                    }
                    pendingCart.clear()
                    _isSending.value = false
                    if (!queuedOffline) loadCheck()
                },
                onFailure = { e ->
                    _isSending.value = false
                    if (e is BackendHttpException && e.statusCode == 409) {
                        // VERSION_CONFLICT — otro dispositivo cambió la cuenta.
                        // El carrito NO se pierde: el mesero puede reintentar
                        // tras ver la cuenta actualizada.
                        _notice.value = "La cuenta cambió en otro dispositivo — se actualizó, revisa antes de reenviar"
                        loadCheck()
                    } else {
                        _error.value = (e as? BackendHttpException)?.message ?: "No se pudo enviar la ronda"
                    }
                },
            )
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    fun exitToFloor() {
        pendingCart.clear()
        tableSession.clear()
    }
}

/** Una ronda ya enviada — todas las líneas que comparten el mismo `sentToKitchenAt`. */
data class KitchenRound(val sentAt: String?, val items: List<OrderDetailItem>)

/**
 * Agrupa `check.items` por ronda (`sentToKitchenAt`, compartido por todas las
 * filas de un mismo "Enviar" — ver KDoc de [OrderDetailItem.sentToKitchenAt]),
 * más reciente primero. Un `null` (línea sin timestamp — no debería pasar en
 * datos nuevos, pero un server viejo o una migración podría no tenerlo) cae en
 * su propio grupo al final, nunca se pierde silenciosamente.
 */
fun OrderDetail.roundsSentToKitchen(): List<KitchenRound> = items
    .groupBy { it.sentToKitchenAt }
    .map { (sentAt, lines) -> KitchenRound(sentAt, lines) }
    .sortedWith(compareByDescending { it.sentAt ?: "" })
