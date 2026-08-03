package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.presentation.components.ScreenProfile
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * Canvas ⇄ lista — el toggle vive EN la pantalla (requisito explícito del
 * founder, no un ajuste en Settings).
 */
enum class TableViewMode { CANVAS, LIST }

/**
 * Vista por default según el tamaño real de pantalla — un canvas de 20 mesas
 * en 480×480 (NEXGO) es inoperable, así que las pantallas compactas arrancan
 * en LISTA; solo la pantalla grande (A920, [ScreenProfile.RegularPortrait])
 * arranca en CANVAS.
 *
 * [ScreenProfile.CompactPortrait] (PAX A80, <600dp de alto) comparte el
 * default de [ScreenProfile.CompactSquare] (NEXGO) por la misma razón — es
 * chica para el mismo propósito — aunque el plan (Task 6, Step 1) solo pone
 * los dos extremos bajo test explícito.
 */
fun defaultViewMode(profile: ScreenProfile): TableViewMode = when (profile) {
    ScreenProfile.CompactSquare, ScreenProfile.CompactPortrait -> TableViewMode.LIST
    ScreenProfile.RegularPortrait -> TableViewMode.CANVAS
}

/**
 * Estado visual de una mesa — lo que el mesero lee en un segundo caminando
 * por el salón. Deliberadamente solo 4 valores (no el status crudo del
 * server) para que canvas y lista pinten SIEMPRE el mismo color/etiqueta por
 * el mismo criterio.
 */
enum class TableDisplayStatus { FREE, OCCUPIED, NEEDS_ATTENTION, RESERVED }

/**
 * Mapea el `status` crudo del server (Prisma `TableStatus`:
 * AVAILABLE/OCCUPIED/RESERVED/CLEANING) al estado visual. Un status
 * desconocido — el server agregó uno nuevo que esta TPV todavía no conoce —
 * SIEMPRE cae en [TableDisplayStatus.NEEDS_ATTENTION], nunca se esconde
 * como si la mesa estuviera libre.
 */
fun tableDisplayStatus(rawStatus: String): TableDisplayStatus = when (rawStatus) {
    "AVAILABLE" -> TableDisplayStatus.FREE
    "OCCUPIED" -> TableDisplayStatus.OCCUPIED
    "RESERVED" -> TableDisplayStatus.RESERVED
    "CLEANING" -> TableDisplayStatus.NEEDS_ATTENTION
    else -> TableDisplayStatus.NEEDS_ATTENTION
}

/**
 * El dinero manda primero — una mesa con cuenta abierta jamás se pinta libre,
 * sin importar qué diga `status` ([DiningTable.hasOpenCheck], fix
 * `avoqado-android`/`avoqado-ios` 2026-07-28). RESERVED sigue siendo la única
 * ocupación legítima sin cuenta. Solo al final se usa la clasificación cruda
 * de `status` — y ahí se corrige el drift INVERSO: `status = OCCUPIED` sin
 * cuenta viva se ve LIBRE, nunca "ocupada fantasma" (CLEANING/desconocido
 * siguen cayendo en [TableDisplayStatus.NEEDS_ATTENTION] como antes).
 */
fun DiningTable.displayStatus(): TableDisplayStatus = when {
    hasOpenCheck -> TableDisplayStatus.OCCUPIED
    isReserved -> TableDisplayStatus.RESERVED
    else -> tableDisplayStatus(status).let { raw ->
        if (raw == TableDisplayStatus.OCCUPIED) TableDisplayStatus.FREE else raw
    }
}

/** Agrupación de mesas por área, para la lista y el filtro. */
data class AreaSummary(
    val id: String?,
    val name: String,
    val tableCount: Int,
)

data class TablesUiState(
    val isLoading: Boolean = true,
    val tables: List<DiningTable> = emptyList(),
    val floorElements: List<FloorElement> = emptyList(),
    val selectedAreaId: String? = null,
    /**
     * true = se está mostrando la última copia buena porque el refresco más
     * reciente falló (sin red, o el server no respondió). Nunca es un error
     * duro mientras haya algo que mostrar — ver
     * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3.
     */
    val isOffline: Boolean = false,
    /** Solo se llena cuando NO hay nada que mostrar (arranque en frío sin red/backend). */
    val errorMessage: String? = null,
    /** Mesa de la sesión activa de ESTE dispositivo ([TableSession]), si hay una. */
    val activeSessionTableId: String? = null,
    /**
     * Spinner de pull-to-refresh — SOLO para el gesto manual del mesero
     * ([TablesViewModel.refresh]), nunca para los refrescos silenciosos que
     * dispara el socket ([listenToSocketEvents]). Distinto de [isLoading]:
     * ese es el loader de PANTALLA COMPLETA del primer arranque en frío
     * (sin nada que mostrar todavía); este es el indicador chico de "ya
     * había datos, el mesero pidió refrescar".
     */
    val isRefreshing: Boolean = false,
    /**
     * Cuántas acciones offline el server rechazó de forma permanente
     * (`REJECTED`, Plan C Task 9) y siguen sin que un mesero/gerente las
     * descarte. Maneja el badge de [QuarantineBadgeButton] en la topbar —
     * `0` = badge oculto. Se refresca en cada [loadTables] (junto con el
     * plano), NUNCA se resetea a 0 por un fallo transitorio del refresco
     * (ver [refreshQuarantineCount]) para que un mesero no pierda de vista
     * un rechazo real solo porque el conteo no pudo actualizarse.
     */
    val quarantineCount: Int = 0,
) {
    val areas: List<AreaSummary>
        get() = tables
            .groupBy { it.areaId to (it.areaName?.takeIf { name -> name.isNotBlank() } ?: "Sin área") }
            .map { (key, tablesInArea) -> AreaSummary(id = key.first, name = key.second, tableCount = tablesInArea.size) }
            .sortedBy { it.name }

    fun tablesForSelectedArea(): List<DiningTable> =
        if (selectedAreaId == null) tables else tables.filter { it.areaId == selectedAreaId }
}

/**
 * `TablesScreen` (Plan C, Task 6) — plano en vivo con toggle canvas/lista.
 * Consume [TablesRepository] (Task 4, lecturas del plano) y [TableSession]
 * (Task 5, para resaltar "tu mesa") + [TableSyncCoordinator] (Task 5, para
 * drenar el outbox antes de refrescar).
 *
 * Offline-first de LECTURA: a diferencia de [TablesRepository.addItems]
 * (Task 4), un GET nunca se encola — no hay nada que reproducir. Lo que sí
 * aplica aquí es la otra mitad de la regla de
 * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3 ("offline es
 * estado normal, no error"): si el refresco falla pero YA había un plano
 * cargado, se sigue mostrando ese plano ([TablesUiState.isOffline] = true,
 * `errorMessage` = null) — nunca se tapa una pantalla que funcionaba con un
 * error. Solo un arranque en frío sin nada cacheado (ni siquiera en memoria)
 * produce [TablesUiState.errorMessage].
 *
 * Este caché es EN MEMORIA (el `StateFlow` sobrevive rotación porque el
 * ViewModel sobrevive rotación) — no hay una tabla Room de mesas/plano en
 * este módulo todavía (Task 3 solo construyó el outbox). Decisión de scope
 * de esta tarea: agregar una tabla Room de solo-lectura para el plano es
 * trabajo de una tarea futura si el arranque-en-frío-sin-red-nunca resulta
 * insuficiente en campo.
 */
@HiltViewModel
class TablesViewModel @Inject constructor(
    private val tablesRepository: TablesRepository,
    private val tableSyncCoordinator: TableSyncCoordinator,
    private val tableSession: TableSession,
    private val deviceInfoManager: DeviceInfoManager,
    private val socketManager: SocketManager,
    private val syncOutbox: SyncOutbox,
) : ViewModel() {

    private val _viewMode = MutableStateFlow(TableViewMode.LIST)
    val viewMode: StateFlow<TableViewMode> = _viewMode.asStateFlow()

    /** Una vez que el usuario toca el toggle a mano, el resize/rotate ya no le pisa la elección. */
    private var userOverrodeViewMode = false

    private val _uiState = MutableStateFlow(TablesUiState())
    val uiState: StateFlow<TablesUiState> = _uiState.asStateFlow()

    private var socketReloadJob: Job? = null
    private var didLoadOnce = false

    init {
        viewModelScope.launch {
            tableSession.active.collect { active ->
                _uiState.update { it.copy(activeSessionTableId = active?.tableId) }
            }
        }
        listenToSocketEvents()
    }

    /**
     * Llamado una vez desde `TablesScreen` con el [ScreenProfile] real del
     * dispositivo — decide el modo de vista inicial y dispara la primera
     * carga. Segura de llamar en cada recomposición: el default de vista solo
     * se aplica mientras el usuario no haya tocado el toggle, y la carga solo
     * se dispara una vez por instancia de ViewModel.
     */
    fun initialize(screenProfile: ScreenProfile) {
        if (!userOverrodeViewMode) {
            _viewMode.value = defaultViewMode(screenProfile)
        }
        if (didLoadOnce) return
        didLoadOnce = true
        loadTables(silent = false)
    }

    fun setViewMode(mode: TableViewMode) {
        userOverrodeViewMode = true
        _viewMode.value = mode
    }

    fun setAreaFilter(areaId: String?) {
        _uiState.update { it.copy(selectedAreaId = areaId) }
    }

    /** Pull-to-refresh / botón de reintentar. */
    fun refresh() {
        loadTables(silent = false)
    }

    private fun listenToSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                val affectsTables = event is SocketEvent.TableStatusChange ||
                    event is SocketEvent.OrderCreated ||
                    event is SocketEvent.OrderUpdated ||
                    event is SocketEvent.OrderStatusChanged ||
                    event is SocketEvent.OrderDeleted
                if (!affectsTables) return@collect

                // Debounce: varios eventos pueden llegar en ráfaga (una ronda
                // completa dispara order_updated por línea) — un solo
                // refresco por ráfaga, no uno por evento.
                socketReloadJob?.cancel()
                socketReloadJob = viewModelScope.launch {
                    delay(SOCKET_RELOAD_DEBOUNCE_MS)
                    Timber.d("🔄 [Tables] Refresco por socket tras %s", event::class.simpleName)
                    loadTables(silent = true)
                }
            }
        }
    }

    private fun loadTables(silent: Boolean) {
        val venueId = deviceInfoManager.getVenueId()
        if (venueId == null) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = "No hay venue configurado en este terminal.")
            }
            return
        }

        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isLoading = it.tables.isEmpty(), isRefreshing = it.tables.isNotEmpty()) }
            }

            // Best-effort: drena el outbox antes de refrescar para que el GET
            // que sigue ya vea las mutaciones hechas sin red. replay() nunca
            // lanza salvo CancellationException — ver KDoc de
            // TableSyncCoordinator.replay — pero se blinda igual: un refresco
            // de plano nunca debe morir por un fallo del drenaje del outbox.
            try {
                tableSyncCoordinator.replay(venueId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [Tables] replay() del outbox falló antes del refresco — se sigue de todos modos")
            }

            // El replay que acaba de correr es el momento más probable para
            // que aparezca un rechazo NUEVO — refrescar el badge aquí, no
            // solo al abrir la pantalla.
            refreshQuarantineCount(venueId)

            val tablesResult = tablesRepository.getTables(venueId)
            val elementsResult = tablesRepository.getFloorElements(venueId)

            tablesResult.fold(
                onSuccess = { tables ->
                    Timber.i("✅ [Tables] %d mesas cargadas (venue=%s)", tables.size, venueId)
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            isRefreshing = false,
                            tables = tables,
                            // Los floor elements son decoración del canvas — si fallan,
                            // no tumban la carga de mesas; se conserva lo que ya había.
                            floorElements = elementsResult.getOrElse { current.floorElements },
                            isOffline = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { current ->
                        if (current.tables.isNotEmpty()) {
                            Timber.w(error, "⚠️ [Tables] Refresco falló — mostrando la última copia buena")
                            current.copy(isLoading = false, isRefreshing = false, isOffline = true)
                        } else {
                            Timber.e(error, "❌ [Tables] Sin plano en memoria y el refresco falló")
                            current.copy(
                                isLoading = false,
                                isRefreshing = false,
                                isOffline = false,
                                errorMessage = if (error is IOException) {
                                    "Sin conexión. Verifica tu red e intenta de nuevo."
                                } else {
                                    "No se pudo cargar el plano de mesas."
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * Refresca [TablesUiState.quarantineCount] desde
     * [SyncOutbox.rejectedIntents] (Plan C, Task 9). `CancellationException`
     * se repropaga; cualquier otro fallo se traga y deja el conteo anterior
     * intacto — un refresco fallido del badge nunca debe hacerlo desaparecer
     * de golpe (eso sería esconder un rechazo real, exactamente lo que este
     * badge existe para prevenir).
     */
    private suspend fun refreshQuarantineCount(venueId: String) {
        val count = try {
            syncOutbox.rejectedIntents(venueId).size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [Tables] No se pudo refrescar el conteo de cuarentena (venue=%s)", venueId)
            return
        }
        _uiState.update { it.copy(quarantineCount = count) }
    }

    // MARK: - Abrir/ver cuenta (Plan C, Task 7) — enciende el CTA de TableSheet

    private val _isOpeningTable = MutableStateFlow(false)
    val isOpeningTable: StateFlow<Boolean> = _isOpeningTable.asStateFlow()

    private val _openTableError = MutableStateFlow<String?>(null)
    val openTableError: StateFlow<String?> = _openTableError.asStateFlow()

    fun clearOpenTableError() {
        _openTableError.value = null
    }

    /**
     * "Abrir mesa y ordenar" — abre (o reusa) la orden de [table] y arranca la
     * sesión activa; [onReady] navega a `TableOrderScreen`. Ver KDoc de
     * [TablesRepository.openTable] para el manejo offline (sesión provisional
     * con UUID local, promovida por [TableSyncCoordinator] al llegar el ack).
     */
    fun openTableAndOrder(table: DiningTable, covers: Int, onReady: () -> Unit) {
        val venueId = deviceInfoManager.getVenueId() ?: return
        if (_isOpeningTable.value) return
        viewModelScope.launch {
            _isOpeningTable.value = true
            _openTableError.value = null
            tablesRepository.openTable(venueId, table.id, covers).fold(
                onSuccess = { outcome ->
                    if (outcome.isProvisional) {
                        tableSession.open(
                            tableId = table.id,
                            localOrderId = outcome.orderId,
                            tableNumber = table.number,
                            areaName = table.areaName,
                        )
                    } else {
                        tableSession.start(
                            TableSession.Active(
                                tableId = table.id,
                                tableNumber = table.number,
                                areaName = table.areaName,
                                orderId = outcome.orderId,
                                orderNumber = outcome.orderNumber,
                                version = outcome.version,
                            ),
                        )
                    }
                    _isOpeningTable.value = false
                    onReady()
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    Timber.w(e, "❌ [Tables] No se pudo abrir la mesa %s", table.id)
                    _isOpeningTable.value = false
                    _openTableError.value = (e as? BackendHttpException)?.message ?: "No se pudo abrir la mesa"
                },
            )
        }
    }

    /**
     * "Ver cuenta" — mesa ocupada con dinero vivo. Lee `table.primaryCheck`,
     * NUNCA `table.currentOrder` directo: una mesa con el puntero
     * desincronizado (p.ej. tras cobrar una parte de un cheque dividido, el
     * server nulea/deja atrás `currentOrder` — `table.tpv.service.ts:143`) se
     * ve ocupada igual y tiene que poder abrirse (ver KDoc de
     * `DiningTable.primaryCheck`). No hace ninguna llamada de red: arranca la
     * sesión con lo que YA trae el plano y navega; `TableOrderScreen` recarga
     * el detalle completo al entrar.
     */
    fun viewExistingCheck(table: DiningTable, onReady: () -> Unit) {
        val order = table.primaryCheck ?: return
        tableSession.start(
            TableSession.Active(
                tableId = table.id,
                tableNumber = table.number,
                areaName = table.areaName,
                orderId = order.id,
                orderNumber = order.orderNumber,
                version = order.version,
                total = order.total,
            ),
        )
        onReady()
    }

    private companion object {
        const val SOCKET_RELOAD_DEBOUNCE_MS = 800L
    }
}
