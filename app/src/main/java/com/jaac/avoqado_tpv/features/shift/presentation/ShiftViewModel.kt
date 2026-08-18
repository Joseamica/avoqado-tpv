package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Shift ViewModel
 *
 * Manages shift state and business logic for shift management screens.
 * Follows Toast/Square POS pattern with automatic calculations.
 *
 * **Responsibilities:**
 * - Load current active shift
 * - Open new shifts
 * - Close existing shifts
 * - Provide UI state for shift status banner and full screen
 *
 * **State Management:**
 * - Uses StateFlow for reactive UI updates
 * - Immutable state objects
 * - Single source of truth
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun ShiftScreen(viewModel: ShiftViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *
 *     when (val currentState = state) {
 *         is ShiftState.Idle -> { /* Show empty state */ }
 *         is ShiftState.Loading -> { /* Show loading */ }
 *         is ShiftState.ShiftActive -> { /* Show active shift */ }
 *         is ShiftState.NoActiveShift -> { /* Show open shift button */ }
 *         is ShiftState.Error -> { /* Show error message */ }
 *     }
 * }
 * ```
 */
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftRepository: ShiftRepository,
    private val secureStorage: SecureStorage,
    private val connectionEventManager: ConnectionEventManager,
    private val cachedShiftDao: CachedShiftDao,
    private val connectivityObserver: ConnectivityObserver,
    private val permissionsRepository: PermissionsRepository
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ShiftState>(ShiftState.Idle)
    val state: StateFlow<ShiftState> = _state.asStateFlow()

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Initial loading state (true until first data load completes)
    // Used to show loading overlay on WelcomeScreen during post-login sync
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    // Offline state (Square/Toast prevention pattern)
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    // Cached shift info for offline display
    private val _cachedShiftInfo = MutableStateFlow<CachedShiftInfo?>(null)
    val cachedShiftInfo: StateFlow<CachedShiftInfo?> = _cachedShiftInfo.asStateFlow()

    // Shift system enabled state (Settings)
    private val _isShiftSystemEnabled = MutableStateFlow(true)
    val isShiftSystemEnabled: StateFlow<Boolean> = _isShiftSystemEnabled.asStateFlow()

    // Effective server-owned capability (PRO entitlement AND explicit venue opt-in).
    private val _isCashReconciliationEnabled = MutableStateFlow(false)
    val isCashReconciliationEnabled: StateFlow<Boolean> = _isCashReconciliationEnabled.asStateFlow()

    // Permission states
    private val _canOpenShift = MutableStateFlow(true)
    val canOpenShift: StateFlow<Boolean> = _canOpenShift.asStateFlow()

    private val _canCloseShift = MutableStateFlow(true)
    val canCloseShift: StateFlow<Boolean> = _canCloseShift.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        timber.log.Timber.d("[PERF] ShiftVM.init START at ${System.currentTimeMillis()}ms")
        refreshSettings()
        refreshShiftPermissions()
        loadCurrentShift()
        listenToConnectionRestored()
        observeConnectivity()
        timber.log.Timber.d("[PERF] ShiftVM.init ALL LAUNCHED")
    }

    /**
     * Refresh settings from SecureStorage
     */
    fun refreshSettings() {
        _isShiftSystemEnabled.value = secureStorage.isShiftSystemEnabled()
        _isCashReconciliationEnabled.value = secureStorage.isCashReconciliationEnabled()
    }

    /**
     * 🌐 Observe Network Connectivity Changes
     *
     * Monitors network status to update offline state and load cached data
     * when connection is lost.
     *
     * **Pattern (Square/Toast POS - Prevention):**
     * - Connection lost → Show cached shift state with "Último estado conocido"
     * - Connection restored → ConnectionEventManager handles auto-sync
     * - Shift operations blocked when offline
     */
    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { status ->
                val wasOffline = _isOffline.value
                _isOffline.value = status == NetworkStatus.Unavailable

                when (status) {
                    NetworkStatus.Unavailable -> {
                        Timber.w("⚠️ [ShiftViewModel] Network lost - loading cached shift state")
                        loadCachedShiftInfo()
                    }
                    NetworkStatus.Available -> {
                        if (wasOffline) {
                            Timber.i("✅ [ShiftViewModel] Network restored - clearing cached info")
                            _cachedShiftInfo.value = null
                            // Note: ConnectionEventManager handles auto-sync via listenToConnectionRestored()
                        }
                    }
                }
            }
        }
    }

    /**
     * 📦 Load Cached Shift Info
     *
     * Loads last known shift state from Room database when offline.
     * Used to display "Último estado conocido (hace X min)" instead of
     * misleading "Sin turno activo".
     */
    private fun loadCachedShiftInfo() {
        viewModelScope.launch {
            val venueId = secureStorage.getVenueId() ?: return@launch
            val cached = cachedShiftDao.getCachedShift(venueId)

            if (cached != null) {
                _cachedShiftInfo.value = CachedShiftInfo(
                    isOpen = cached.isOpen(),
                    staffName = cached.staffName,
                    cachedMinutesAgo = cached.minutesSinceCached()
                )
                Timber.d("📦 [ShiftViewModel] Loaded cached shift: isOpen=${cached.isOpen()}, cached ${cached.minutesSinceCached()}min ago")
            } else {
                Timber.d("📦 [ShiftViewModel] No cached shift data available")
                _cachedShiftInfo.value = null
            }
        }
    }

    /**
     * 💾 Cache Shift State
     *
     * Saves current shift state to Room database for offline access.
     * Called after every successful network fetch.
     *
     * @param shift Shift to cache (null clears cache for "no active shift")
     * @param venueId Current venue ID
     */
    private suspend fun cacheShiftState(shift: Shift?, venueId: String) {
        if (shift != null) {
            cachedShiftDao.cacheShift(CachedShiftEntity.fromDomain(shift, venueId))
            Timber.d("💾 [ShiftViewModel] Cached shift state: ${shift.staffName}, status=${shift.status}")
        } else {
            // No active shift - clear cache
            cachedShiftDao.clearCache(venueId)
            Timber.d("💾 [ShiftViewModel] Cleared shift cache (no active shift)")
        }
    }

    /**
     * 🔄 Listen to Connection Restored Events
     *
     * When backend connection is restored after being offline, automatically
     * reload shift data to sync with server state.
     *
     * **Pattern (Toast POS / Square POS):**
     * - Backend goes down → App works offline with cached data
     * - Backend comes back → Heartbeat succeeds → Trigger "reconciliation sync"
     * - Reload: Shift status, orders, config, etc.
     *
     * **Critical for:**
     * - Fixing "Sin turno activo" bug when shift was opened during offline mode
     * - Syncing shift closures made by other terminals
     * - Updating shift totals (sales, cash drawer)
     */
    private fun listenToConnectionRestored() {
        viewModelScope.launch {
            connectionEventManager.connectionRestoredEvents.collect { event ->
                Timber.i("🔄 [ShiftViewModel] Connection restored - syncing shift data")
                Timber.d("   Reconnected after ${event.attemptsBeforeReconnection} attempts at ${event.timestamp}")
                loadCurrentShift()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Load current active shift and shift history
     *
     * Called on ViewModel initialization and after open/close operations.
     * Updates state with current shift (or no shift) + shift history.
     *
     * **Pattern (Square/Toast POS):**
     * - Always load history to show in bottom section
     * - History shows last 10 closed shifts
     * - Helps staff review past shifts quickly
     *
     * **Anti-flash fix:**
     * - Only sets Loading state if current state is Idle or Error
     * - If already ShiftActive, keeps the shift visible while reloading
     * - Prevents "flash of no shift" when navigating back to WelcomeScreen
     */
    fun loadCurrentShift() {
        viewModelScope.launch {
            if (hasUnacknowledgedCashReconciliationResult()) {
                Timber.d("⏸️ Keeping cash reconciliation result visible until acknowledgment")
                return@launch
            }

            // ✅ Anti-flash fix: Only show Loading if we don't already have a shift
            // This prevents the "flash of inactive shift" when navigating back to WelcomeScreen
            val currentState = _state.value
            if (currentState !is ShiftState.ShiftActive) {
                _state.value = ShiftState.Loading
            }

            val venueId = secureStorage.getVenueId()
            if (venueId == null) {
                Timber.e("❌ No venueId found in storage")
                _state.value = ShiftState.Error("No se encontró información del local")
                return@launch
            }

            // Load current shift
            val currentShiftResult = shiftRepository.getCurrentShift(venueId)

            // Load shift history (last 10 closed shifts)
            val historyResult = shiftRepository.getShiftHistory(venueId, limit = 10)

            // Extract history (default to empty list on error)
            val shiftHistory = when (historyResult) {
                is Result.Success -> historyResult.data
                is Result.Error -> {
                    Timber.w("⚠️ Failed to load shift history, continuing without it")
                    emptyList()
                }
            }

            // A close may have completed while this reload was in flight. Never let stale reload
            // data replace the counted/skipped result before the cashier acknowledges it.
            if (hasUnacknowledgedCashReconciliationResult()) return@launch

            // Update state based on current shift result
            when (currentShiftResult) {
                is Result.Success -> {
                    val shift = currentShiftResult.data
                    // 💾 Cache shift state for offline access
                    cacheShiftState(shift, venueId)

                    if (shift != null) {
                        Timber.i("✅ Active shift loaded: ${shift.id}")
                        _state.value = ShiftState.ShiftActive(shift, shiftHistory)
                    } else {
                        Timber.i("ℹ️ No active shift")
                        _state.value = ShiftState.NoActiveShift(shiftHistory)
                    }
                }
                is Result.Error -> {
                    val errorMessage = translateError(currentShiftResult.exception)
                    Timber.e("❌ Failed to load shift: $errorMessage")
                    _state.value = ShiftState.Error(errorMessage)
                }
            }

            // ✅ Mark initial loading complete (hides loading overlay on WelcomeScreen)
            if (_isInitialLoading.value) {
                _isInitialLoading.value = false
                Timber.d("✅ [ShiftViewModel] Initial loading complete")
            }
        }
    }

    /**
     * Refresh shift data (Pull-to-refresh)
     *
     * Reloads shift data without showing full-screen loading.
     */
    fun refresh() {
        viewModelScope.launch {
            if (hasUnacknowledgedCashReconciliationResult()) return@launch

            _isRefreshing.value = true
            try {
                val venueId = secureStorage.getVenueId() ?: return@launch

                // Load current shift
                val currentShiftResult = shiftRepository.getCurrentShift(venueId)
                val historyResult = shiftRepository.getShiftHistory(venueId, limit = 10)

                val shiftHistory = when (historyResult) {
                    is Result.Success -> historyResult.data
                    is Result.Error -> emptyList()
                }

                // Preserve a result that arrived while the refresh requests were in flight.
                if (hasUnacknowledgedCashReconciliationResult()) return@launch

                when (currentShiftResult) {
                    is Result.Success -> {
                        val shift = currentShiftResult.data
                        _state.value = if (shift != null) {
                            ShiftState.ShiftActive(shift, shiftHistory)
                        } else {
                            ShiftState.NoActiveShift(shiftHistory)
                        }
                    }
                    is Result.Error -> {
                        Timber.e("❌ Refresh failed: ${currentShiftResult.exception}")
                    }
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Open a new shift
     *
     * Creates a new shift with starting cash amount.
     * Backend sets status = OPEN and begins tracking.
     *
     * @param startingCash Initial cash amount in drawer
     */
    fun openShift(startingCash: Double) {
        viewModelScope.launch {
            if (!_canOpenShift.value) {
                _state.value = ShiftState.Error("No tienes permiso para abrir turnos.\n\nContacta a tu administrador.")
                return@launch
            }

            _state.value = ShiftState.Loading

            val venueId = secureStorage.getVenueId()
            val staffId = secureStorage.getStaffId()

            if (venueId == null || staffId == null) {
                Timber.e("❌ Missing venueId or staffId")
                _state.value = ShiftState.Error("Información de sesión incompleta")
                return@launch
            }

            Timber.i("🟢 Opening shift with starting cash: $$startingCash")

            when (val result = shiftRepository.openShift(venueId, staffId, startingCash)) {
                is Result.Success -> {
                    val shift = result.data
                    Timber.i("✅ Shift opened successfully: ${shift.id}")
                    // Reload to get fresh state with history
                    loadCurrentShift()
                }
                is Result.Error -> {
                    val errorMessage = translateError(result.exception)
                    Timber.e("❌ Failed to open shift: $errorMessage")
                    _state.value = ShiftState.Error(errorMessage)
                }
            }
        }
    }

    /**
     * Close the current shift
     *
     * Closes the active shift with automatic calculations:
     * - Payment breakdown (cash, card, voucher, other)
     * - Products sold count
     * - Inventory consumed (FIFO batches)
     * - Total sales, tips, orders
     */
    fun closeShift(
        reconciliationAction: CashReconciliationAction? = null,
        countedCash: BigDecimal? = null
    ) {
        viewModelScope.launch {
            if (!_canCloseShift.value) {
                _state.value = ShiftState.Error("No tienes permiso para cerrar turnos.\n\nContacta a tu administrador.")
                return@launch
            }

            val currentState = _state.value
            if (currentState !is ShiftState.ShiftActive) {
                Timber.w("⚠️ Cannot close shift: No active shift")
                return@launch
            }

            _state.value = ShiftState.Loading

            val venueId = secureStorage.getVenueId()
            if (venueId == null) {
                Timber.e("❌ No venueId found")
                _state.value = ShiftState.Error("No se encontró información del local")
                return@launch
            }

            val shiftId = currentState.shift.id
            Timber.i("🔴 Closing shift: $shiftId, reconciliation=${reconciliationAction?.name ?: "LEGACY"}")

            when (
                val result = shiftRepository.closeShift(
                    venueId = venueId,
                    shiftId = shiftId,
                    action = reconciliationAction,
                    countedCash = countedCash
                )
            ) {
                is Result.Success -> {
                    val closedShift = result.data
                    Timber.i("✅ Shift closed. Sales: $${closedShift.totalSales}, Products: ${closedShift.totalProductsSold}")

                    // Load updated history (now includes the just-closed shift)
                    val historyResult = shiftRepository.getShiftHistory(venueId, limit = 10)
                    val shiftHistory = when (historyResult) {
                        is Result.Success -> historyResult.data
                        is Result.Error -> emptyList()
                    }

                    // Show closed shift briefly with updated history
                    _state.value = ShiftState.ShiftClosed(
                        shift = closedShift,
                        shiftHistory = shiftHistory,
                        reconciliationAction = reconciliationAction
                    )

                    // Preserve the legacy behavior exactly. A reconciliation attempt remains until
                    // explicit acknowledgment so the cashier can read the result.
                    if (reconciliationAction == null) {
                        kotlinx.coroutines.delay(2000)
                        loadCurrentShift()
                    }
                }
                is Result.Error -> {
                    val errorMessage = translateError(result.exception)
                    Timber.e("❌ Failed to close shift: $errorMessage")
                    _state.value = ShiftState.Error(errorMessage)
                }
            }
        }
    }

    /**
     * Retry after error
     *
     * Reloads current shift state after network error.
     */
    fun retry() {
        loadCurrentShift()
    }

    /** Acknowledge a COUNTED/SKIPPED result and return to the no-active-shift screen. */
    fun acknowledgeClosedShift() {
        if (_state.value is ShiftState.ShiftClosed) {
            // Clear the guarded result first so the authoritative reload is allowed to proceed.
            _state.value = ShiftState.Loading
            loadCurrentShift()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun hasUnacknowledgedCashReconciliationResult(): Boolean =
        requiresCashReconciliationAcknowledgement(_state.value)

    /**
     * Translate API errors to user-friendly messages
     *
     * Uses ApiException type matching instead of fragile string matching.
     */
    private fun translateError(exception: ApiException): String {
        return when (exception) {
            is ApiException.HttpError -> when (exception.code) {
                400 -> "Ya existe un turno abierto.\n\nCierra el turno actual antes de abrir uno nuevo."
                403 -> "No tienes permiso para esta acción.\n\nContacta a tu administrador."
                404 -> "No se encontró el turno.\n\nEs posible que ya haya sido cerrado."
                409 -> "El turno se está cerrando en otra terminal.\n\nEspera unos segundos y actualiza."
                in 500..599 -> "Error en el servidor.\n\nIntenta nuevamente."
                else -> exception.userMessage
            }
            is ApiException.PermissionDenied -> "No tienes permiso para esta acción.\n\nContacta a tu administrador."
            is ApiException.NetworkError -> "No se pudo conectar al servidor.\n\nVerifica tu conexión a internet."
            else -> exception.userMessage
        }
    }

    /**
     * Permisos del turno DE LA TERMINAL — `tpv-shifts:*`, no `shifts:*`.
     *
     * `shifts:create` / `shifts:close` son el back-office (corregir turnos AJENOS) y
     * se quedan en MANAGER+. Operar el turno de esta caja es `tpv-shifts:create` /
     * `tpv-shifts:close`, que el CASHIER sí tiene desde 2026-08-16.
     *
     * 🔴 Estos nombres se espejan por nombre EXACTO con
     * `avoqado-server/src/lib/permissions.ts`; un desajuste falla MUDO (el botón se
     * esconde y nadie ve un error). Antes se pedían los nombres viejos y funcionaba
     * sólo por el alias bidireccional del server
     * (`'tpv-shifts:create': ['tpv-shifts:create', 'shifts:create', ...]`), pensado
     * para los APK ya instalados en la calle — no para el código nuevo.
     */
    private fun refreshShiftPermissions() {
        viewModelScope.launch {
            val canOpen = permissionsRepository.hasPermission("tpv-shifts:create")
            _canOpenShift.value = canOpen
            val canClose = permissionsRepository.hasPermission("tpv-shifts:close")
            _canCloseShift.value = canClose
            Timber.d("🔐 Shift permissions: open=$canOpen, close=$canClose")
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// STATE DEFINITIONS
// ══════════════════════════════════════════════════════════════════════

/**
 * Shift UI State
 *
 * Sealed class representing all possible UI states for shift management.
 * Follows Material Design state management patterns.
 */
sealed class ShiftState {
    /**
     * Initial state before first load
     */
    data object Idle : ShiftState()

    /**
     * Loading state (opening, closing, or fetching shift)
     */
    data object Loading : ShiftState()

    /**
     * Active shift state (shift is currently open)
     *
     * @param shift Current active shift with live metrics
     * @param shiftHistory List of recent closed shifts (for history display)
     */
    data class ShiftActive(
        val shift: Shift,
        val shiftHistory: List<Shift> = emptyList()
    ) : ShiftState()

    /**
     * No active shift state (ready to open new shift)
     *
     * @param shiftHistory List of recent closed shifts (for history display)
     */
    data class NoActiveShift(
        val shiftHistory: List<Shift> = emptyList()
    ) : ShiftState()

    /**
     * Shift closed state (temporary state showing close summary)
     *
     * @param shift Closed shift with final calculations
     * @param shiftHistory List of recent closed shifts (for history display)
     */
    data class ShiftClosed(
        val shift: Shift,
        val shiftHistory: List<Shift> = emptyList(),
        val reconciliationAction: CashReconciliationAction? = null
    ) : ShiftState()

    /**
     * Error state with user-friendly message
     *
     * @param message Translated error message for user display
     */
    data class Error(val message: String) : ShiftState()
}

// ══════════════════════════════════════════════════════════════════════
// CACHED SHIFT INFO (For offline display)
// ══════════════════════════════════════════════════════════════════════

/**
 * Cached Shift Info
 *
 * Lightweight data class for offline shift display.
 * Contains only the information needed for "Último estado conocido" UI.
 *
 * **Usage in ShiftStatusBanner:**
 * ```
 * ┌───────────────────────────────────┐
 * │ ☁️ Turno abierto                 │
 * │    Último estado conocido (5 min)│
 * │    [Cerrar turno] ← disabled     │
 * └───────────────────────────────────┘
 * ```
 *
 * @property isOpen Whether the cached shift was open
 * @property staffName Staff member who opened the shift
 * @property cachedMinutesAgo Minutes since the data was cached
 */
data class CachedShiftInfo(
    val isOpen: Boolean,
    val staffName: String,
    val cachedMinutesAgo: Int
)
