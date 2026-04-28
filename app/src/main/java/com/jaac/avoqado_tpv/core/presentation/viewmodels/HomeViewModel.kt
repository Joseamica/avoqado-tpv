package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.session.SessionEvent
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.domain.events.VenueStatusEvent
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandPriority
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntities
import com.jaac.avoqado_tpv.core.data.local.mappers.toCategoryEntities
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleSalesGoal
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalPeriod
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalSource
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * HomeViewModel
 *
 * Handles home screen actions including logout.
 * Also handles Socket.IO events for system alerts, admin commands, and hardware status.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val socketManager: SocketManager,
    private val secureStorage: SecureStorage,
    // 🔧 Blumon SDK Initialization - Triggered after login for early payment readiness
    private val initializationManager: InitializationManager,
    // 🏪 Get merchants from backend to use correct serial for SDK init
    private val getMerchantsUseCase: GetMerchantsUseCase,
    // 🎮 Remote Command System
    private val commandExecutor: CommandExecutor,
    // 📡 HTTP ACK for commands received via Socket.IO
    private val heartbeatRepository: HeartbeatRepository,
    val lockScreenManager: LockScreenManager,
    val maintenanceManager: MaintenanceManager,
    // 📦 Product Cache Warm-up (Performance Optimization)
    private val productRepository: ProductRepository,
    private val productDao: ProductDao,
    private val productCategoryDao: ProductCategoryDao,
    // 🎯 Sales Goal API
    private val apiService: ApiService,
    // 🔄 Connection restored events for auto-retry
    private val connectionEventManager: ConnectionEventManager,
    private val terminalConfigRepository: TerminalConfigRepository,
    private val merchantRepository: MerchantRepository,
    private val deviceInfoManager: DeviceInfoManager,
    // 📡 Socket.IO Payment Bridge - Forward socket payment requests to BLE pipeline
    private val bluetoothPaymentService: com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentService,
    // 📦 Update Check Manager - Check for app updates after login
    private val updateCheckManager: UpdateCheckManager,
    // 🔄 Session Manager - Observe token refresh for Socket.IO reconnection
    private val sessionManager: SessionManager,
    // 💳 Payment Queue - Reset failed payments on reconnection
    private val paymentQueueRepository: PaymentQueueRepository,
    private val paymentQueueStateManager: PaymentQueueStateManager,
    // 📊 Observability - Production error tracking (Crashlytics + Remote + File)
    private val observabilityManager: com.jaac.avoqado_tpv.core.observability.ObservabilityManager,
    // 🔄 Order Sync - Cleanup on logout to prevent memory leaks (PAX A80 1GB)
    private val orderSyncCoordinator: OrderSyncCoordinator,
    // ⏱ Attendance state for WelcomeScreen timeclock card
    private val timeEntryRepository: TimeEntryRepository,
    private val tpvSettingsRepository: TpvSettingsRepository,
    // 📸 Pending verifications count for WelcomeScreen card
    private val saleVerificationRepository: com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════════
    // BLUMON SDK INITIALIZATION STATE
    // Used to block UI until SDK is ready for payments
    // ═══════════════════════════════════════════════════════════════════════════

    // True while SDK is initializing (shows loader)
    private val _isBlumonInitializing = MutableStateFlow(true)
    val isBlumonInitializing: StateFlow<Boolean> = _isBlumonInitializing.asStateFlow()

    // True when SDK is ready for payments
    private val _isBlumonReady = MutableStateFlow(false)
    val isBlumonReady: StateFlow<Boolean> = _isBlumonReady.asStateFlow()

    // Error message if initialization failed (null if no error)
    private val _blumonInitError = MutableStateFlow<String?>(null)
    val blumonInitError: StateFlow<String?> = _blumonInitError.asStateFlow()

    // Elapsed seconds since Blumon init started (for progressive warnings)
    private val _blumonInitElapsedSeconds = MutableStateFlow(0)
    val blumonInitElapsedSeconds: StateFlow<Int> = _blumonInitElapsedSeconds.asStateFlow()

    // Job reference for Blumon init (so it can be cancelled)
    private var blumonInitJob: Job? = null

    private companion object {
        const val BLUMON_INIT_MAX_ATTEMPTS = 4
        const val BLUMON_INIT_SETTLE_DELAY_MS = 2_000L
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // USER PROFILE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    // Staff name for personalized greeting
    private val _staffName = MutableStateFlow("Usuario")
    val staffName: StateFlow<String> = _staffName.asStateFlow()

    // Clock-in time (placeholder for future clock-in feature)
    private val _clockInTime = MutableStateFlow<String?>(null)
    val clockInTime: StateFlow<String?> = _clockInTime.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // SYSTEM ALERTS & ADMIN COMMANDS
    // ═══════════════════════════════════════════════════════════════════════════

    // System alerts for UI (info, warning, error, critical)
    private val _systemAlerts = MutableSharedFlow<SocketEvent.SystemAlert>(replay = 0, extraBufferCapacity = 10)
    val systemAlerts: SharedFlow<SocketEvent.SystemAlert> = _systemAlerts.asSharedFlow()

    // Admin commands from dashboard (maintenance mode, reload, disable, shutdown)
    private val _adminCommands = MutableSharedFlow<SocketEvent.TPVCommand>(replay = 0, extraBufferCapacity = 5)
    val adminCommands: SharedFlow<SocketEvent.TPVCommand> = _adminCommands.asSharedFlow()

    // Inventory alerts (low stock, out of stock)
    private val _inventoryAlerts = MutableSharedFlow<SocketEvent>(replay = 0, extraBufferCapacity = 10)
    val inventoryAlerts: SharedFlow<SocketEvent> = _inventoryAlerts.asSharedFlow()

    // Hardware status updates (printer, card reader)
    private val _hardwareStatus = MutableSharedFlow<SocketEvent>(replay = 0, extraBufferCapacity = 10)
    val hardwareStatus: SharedFlow<SocketEvent> = _hardwareStatus.asSharedFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // VENUE STATUS (for mid-session detection)
    // ═══════════════════════════════════════════════════════════════════════════

    // Current venue status from SecureStorage
    private val _venueStatus = MutableStateFlow(secureStorage.getVenueStatus())
    val venueStatus: StateFlow<VenueStatus> = _venueStatus.asStateFlow()

    // Venue status change events (for UI reactions like forced logout)
    private val _venueStatusEvents = MutableSharedFlow<VenueStatusEvent>(replay = 0, extraBufferCapacity = 5)
    val venueStatusEvents: SharedFlow<VenueStatusEvent> = _venueStatusEvents.asSharedFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // SALES GOAL (staff performance tracking)
    // ═══════════════════════════════════════════════════════════════════════════

    // All effective sales goals for the logged-in staff (resolved via venue > org hierarchy)
    private val _salesGoals = MutableStateFlow<List<ModuleSalesGoal>>(emptyList())
    val salesGoals: StateFlow<List<ModuleSalesGoal>> = _salesGoals.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // PENDING VERIFICATIONS (non-blocking proof-of-sale)
    // ═══════════════════════════════════════════════════════════════════════════

    private val _pendingVerificationsCount = MutableStateFlow(0)
    val pendingVerificationsCount: StateFlow<Int> = _pendingVerificationsCount.asStateFlow()

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTENDANCE STATE (for WelcomeScreen timeclock card + button gating)
    // ═══════════════════════════════════════════════════════════════════════════

    private val _currentTimeEntry = MutableStateFlow<TimeEntry?>(null)
    val currentTimeEntry: StateFlow<TimeEntry?> = _currentTimeEntry.asStateFlow()

    private val _requireClockInToLogin = MutableStateFlow(false)
    val requireClockInToLogin: StateFlow<Boolean> = _requireClockInToLogin.asStateFlow()

    private val _isAttendanceLoading = MutableStateFlow(true)
    val isAttendanceLoading: StateFlow<Boolean> = _isAttendanceLoading.asStateFlow()

    private val initStartTime = System.currentTimeMillis()

    init {
        Timber.d("[PERF] HomeVM.init START at ${initStartTime}ms")

        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL (immediate) — needed for UI and real-time
        // ═══════════════════════════════════════════════════════════════════
        initializeObservability()
        loadStaffInfo()
        connectSocketIfNeeded()
        collectSocketEvents()
        observeTokenRefresh()

        // ═══════════════════════════════════════════════════════════════════
        // DEFERRED — staggered to avoid thread pool congestion on 1GB RAM
        // Each function has an internal delay before doing real work.
        // UnconfinedTestDispatcher skips delays automatically in tests.
        // ═══════════════════════════════════════════════════════════════════
        fetchAttendanceState()                // immediate — needed for button gating
        listenForConnectionRestored()       // delay(1_000)
        if (BuildConfig.ENABLE_PAX_SDK) {
            initializeBlumonSDK()           // delay(2_000) — blocks UI until ready (PAX only)
        } else {
            // Nexgo/AngelPay builds do not have Pax native libs.
            _isBlumonInitializing.value = false
            _isBlumonReady.value = true
            _blumonInitError.value = null
            Timber.i("🔶 [HomeViewModel] Skipping Blumon init on non-PAX build")
        }
        fetchSalesGoal()                    // delay(2_500)
        warmUpProductCache()                // delay(3_000)
        checkForUpdates()                   // delay(4_000)
        loadInitialQueueCounts()            // delay(3_000)
        fetchPendingVerificationsCount()    // delay(3_000)

        Timber.d("[PERF] HomeVM.init ALL LAUNCHED in ${System.currentTimeMillis() - initStartTime}ms")
    }

    /**
     * 📦 Warm up product cache in background
     *
     * Fetches products and categories from backend and saves to Room DB.
     * This ensures that when user clicks "Quick Order" or opens a table,
     * the menu loads INSTANTLY from cache (0ms latency).
     *
     * Solves: "Skipped 40+ frames" delay on Quick Order click.
     */
    private fun warmUpProductCache(skipDelay: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Stagger: wait for critical coroutines + Blumon SDK to settle
                if (!skipDelay) delay(3_000)

                val start = System.currentTimeMillis()
                Timber.d("[PERF] HomeVM.warmUpProductCache START")
                val venueId = secureStorage.getVenueId() ?: return@launch
                val cachedAt = System.currentTimeMillis()

                // Fetch products
                productRepository.getProducts(venueId).fold(
                    onSuccess = { products ->
                        productDao.upsertProducts(products.toEntities(venueId, cachedAt))
                        Timber.i("✅ [HomeViewModel] Cached ${products.size} products")
                    },
                    onFailure = { e ->
                        Timber.w(e, "⚠️ [HomeViewModel] Failed to cache products")
                    }
                )

                // Fetch categories
                productRepository.getCategories(venueId).fold(
                    onSuccess = { categories ->
                        productCategoryDao.upsertCategories(categories.toCategoryEntities(venueId, cachedAt))
                        Timber.i("✅ [HomeViewModel] Cached ${categories.size} categories")
                    },
                    onFailure = { e ->
                        Timber.w(e, "⚠️ [HomeViewModel] Failed to cache categories")
                    }
                )

                Timber.d("[PERF] HomeVM.warmUpProductCache DONE (${System.currentTimeMillis() - start}ms)")
            } catch (e: Exception) {
                Timber.e(e, "⚠️ [HomeViewModel] Error warming up cache")
            }
        }
    }

    /**
     * 👤 Load Staff Information
     *
     * Loads the current logged-in staff member's name for personalized greeting.
     * Clock-in time is a placeholder for future implementation.
     */
    /**
     * 📊 Initialize Observability System
     *
     * Sets up Crashlytics custom keys (venueId, terminalId, userId) so that
     * non-fatal errors in Firebase Console can be filtered by terminal.
     * Also activates RemoteLogger (Socket.IO) and FileLogger (offline).
     */
    private fun initializeObservability() {
        val venueId = secureStorage.getVenueId() ?: return
        val userId = secureStorage.getStaffId() ?: "unknown"
        val terminalId = deviceInfoManager.getSerialNumber()
        observabilityManager.initialize(
            venueId = venueId,
            terminalId = terminalId,
            userId = userId
        )
    }

    private fun loadStaffInfo() {
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            Timber.d("[PERF] HomeVM.loadStaffInfo START")
            val name = authRepository.getStaffName() ?: "Usuario"
            _staffName.value = name
            Timber.d("[PERF] HomeVM.loadStaffInfo DONE (${System.currentTimeMillis() - start}ms)")

            // Clock-in time - placeholder for now
            // TODO: Implement clock-in feature and load actual clock-in time
            _clockInTime.value = null
        }
    }

    /**
     * ⏱ Fetch Attendance State
     *
     * Loads the current time entry and requireClockInToLogin setting
     * for the logged-in staff member. Used by WelcomeScreen to:
     * - Show TimeclockStatusCard with real-time status
     * - Gate action buttons when clock-in is required
     */
    private fun fetchAttendanceState() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isAttendanceLoading.value = true
                val venueId = secureStorage.getVenueId() ?: return@launch
                val staffId = secureStorage.getStaffId() ?: return@launch

                // Load requireClockInToLogin setting
                val settings = tpvSettingsRepository.getCurrentSettings()
                _requireClockInToLogin.value = settings.requireClockInToLogin

                // Load active time entry
                val result = timeEntryRepository.findActiveEntryForStaff(venueId, staffId)
                result.onSuccess { entry ->
                    _currentTimeEntry.value = entry
                    // Populate clockInTime for backward compat
                    if (entry != null) {
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        _clockInTime.value = "Desde las ${entry.clockInTime.format(formatter)}"
                    } else {
                        _clockInTime.value = null
                    }
                    Timber.d("⏱ [Attendance] Active entry: ${entry?.status ?: "none"}")
                }.onFailure { error ->
                    Timber.w(error, "⚠️ [Attendance] Failed to fetch active entry")
                    _currentTimeEntry.value = null
                    _clockInTime.value = null
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [Attendance] Error fetching attendance state")
            } finally {
                _isAttendanceLoading.value = false
            }
        }
    }

    /**
     * 🔄 Refresh Attendance State
     *
     * Public method to re-fetch attendance status after returning
     * from TimeclockScreen (clock-in/out).
     */
    fun refreshAttendance() {
        fetchAttendanceState()
    }

    /**
     * 🎯 Fetch Sales Goals
     *
     * Fetches all effective sales goals from the backend using the plural endpoint.
     * Falls back to the singular endpoint for backward compatibility with older backends.
     *
     * **Resolution hierarchy (handled by backend):**
     * 1. Venue-level goals (if configured) → source: VENUE
     * 2. Organization-level goals (fallback) → source: ORGANIZATION
     * 3. Empty list (no goals configured)
     */
    private fun fetchSalesGoal(skipDelay: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!skipDelay) delay(2_500) // Stagger: sales goal is non-critical for initial render
                val start = System.currentTimeMillis()
                Timber.d("[PERF] HomeVM.fetchSalesGoal START")

                // Try plural endpoint first (new backend)
                try {
                    val multiResponse = apiService.getSalesGoals()
                    if (multiResponse.isSuccessful) {
                        val goals = multiResponse.body()?.salesGoals?.map { dto ->
                            mapDtoToGoal(dto)
                        } ?: emptyList()
                        _salesGoals.value = goals
                        Timber.i("✅ [HomeViewModel] Sales goals loaded: ${goals.size} goals")
                        goals.forEach { g ->
                            Timber.d("  → ${g.currentSales}/${g.goal} (${g.period}, source=${g.source})")
                        }
                        Timber.d("[PERF] HomeVM.fetchSalesGoal DONE (${System.currentTimeMillis() - start}ms)")
                        return@launch
                    }
                } catch (e: Exception) {
                    Timber.d("ℹ️ [HomeViewModel] Plural endpoint not available, falling back to singular")
                }

                // Fallback to singular endpoint (old backend — backward compat)
                val response = apiService.getSalesGoal()
                if (response.isSuccessful) {
                    val goalDto = response.body()?.salesGoal
                    if (goalDto != null) {
                        _salesGoals.value = listOf(mapDtoToGoal(goalDto))
                        Timber.i("✅ [HomeViewModel] Sales goal loaded (singular fallback): ${goalDto.currentSales}/${goalDto.goal}")
                    } else {
                        _salesGoals.value = emptyList()
                        Timber.d("ℹ️ [HomeViewModel] No sales goal configured for this staff/venue")
                    }
                } else {
                    Timber.w("⚠️ [HomeViewModel] Failed to fetch sales goal: ${response.code()}")
                    _salesGoals.value = emptyList()
                }
                Timber.d("[PERF] HomeVM.fetchSalesGoal DONE (${System.currentTimeMillis() - start}ms)")
            } catch (e: Exception) {
                Timber.e(e, "❌ [HomeViewModel] Error fetching sales goals")
                _salesGoals.value = emptyList()
            }
        }
    }

    /**
     * Map a SalesGoalDto to domain model ModuleSalesGoal
     */
    private fun mapDtoToGoal(dto: com.jaac.avoqado_tpv.core.data.network.SalesGoalDto): ModuleSalesGoal {
        return ModuleSalesGoal(
            goal = dto.goal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            goalType = when (dto.goalType?.uppercase()) {
                "QUANTITY" -> SalesGoalType.QUANTITY
                else -> SalesGoalType.AMOUNT
            },
            period = when (dto.period.uppercase()) {
                "WEEKLY" -> SalesGoalPeriod.WEEKLY
                "MONTHLY" -> SalesGoalPeriod.MONTHLY
                else -> SalesGoalPeriod.DAILY
            },
            currentSales = dto.currentSales.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            staffId = dto.staffId,
            source = when (dto.source?.lowercase()) {
                "organization" -> SalesGoalSource.ORGANIZATION
                else -> SalesGoalSource.VENUE
            }
        )
    }

    /**
     * 🔄 Refresh Sales Goals
     *
     * Public method to manually refresh the sales goals.
     * Called after a payment is completed to update progress.
     */
    fun refreshSalesGoal() {
        fetchSalesGoal()
    }

    /**
     * 📸 Fetch pending verifications count (deferred, non-critical)
     */
    private fun fetchPendingVerificationsCount() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(3_000) // Deferred startup
            saleVerificationRepository.getPendingVerifications()
                .onSuccess { verifications ->
                    _pendingVerificationsCount.value = verifications.size
                    Timber.d("📸 Pending verifications count: ${verifications.size}")
                }
                .onFailure { e ->
                    Timber.e(e, "📸 Failed to fetch pending verifications count")
                }
        }
    }

    /**
     * 📸 Refresh pending verifications count (called after returning from upload screen)
     */
    fun refreshPendingVerifications() {
        viewModelScope.launch(Dispatchers.IO) {
            saleVerificationRepository.getPendingVerifications()
                .onSuccess { _pendingVerificationsCount.value = it.size }
        }
    }

    /**
     * 🔄 Refresh Dashboard (Pull-to-Refresh)
     *
     * Public method to manually refresh all dashboard data.
     * Called from WelcomeScreen pull-to-refresh gesture.
     * Refreshes sales goal and staff info without init delays.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                fetchSalesGoal(skipDelay = true)
                loadStaffInfo()
                fetchAttendanceState()
                refreshPendingVerifications()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 📦 Check for App Updates
     *
     * Called on app startup after login to check if a newer version is available.
     * If an update is available:
     * - BANNER mode: Shows update alert in DeviceAlertBanner
     * - FORCE mode: DeviceAlertBanner + shows blocking modal
     */
    private fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(4_000) // Stagger: lowest priority, check after everything else settled
                val start = System.currentTimeMillis()
                Timber.d("[PERF] HomeVM.checkForUpdates START")
                val update = updateCheckManager.checkForUpdates()
                Timber.d("[PERF] HomeVM.checkForUpdates DONE (${System.currentTimeMillis() - start}ms)")
                if (update != null) {
                    Timber.i("✅ [HomeViewModel] Update available: ${update.versionName} (mode=${update.updateMode})")
                } else {
                    Timber.d("ℹ️ [HomeViewModel] App is up to date")
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [HomeViewModel] Error checking for updates")
            }
        }
    }

    /**
     * 🔄 Observe Token Refresh Events
     *
     * When TokenAuthenticator refreshes the JWT, Socket.IO may still be using
     * the old expired token in memory, causing infinite reconnection loops.
     * This observer reconnects Socket.IO with the fresh token from SecureStorage.
     */
    private fun observeTokenRefresh() {
        viewModelScope.launch {
            sessionManager.sessionEvents.collect { event ->
                if (event is SessionEvent.TokenRefreshed) {
                    Timber.d("🔄 [HomeViewModel] Token refreshed - reconnecting socket with fresh token")
                    socketManager.reconnectWithFreshToken()
                }
            }
        }
    }

    /**
     * 🔌 Connect Socket.IO if session was restored
     *
     * When the app restarts with a valid session, LoginViewModel is bypassed,
     * so we need to connect to Socket.IO from HomeViewModel.
     *
     * This ensures:
     * - Real-time events work after app restart
     * - TPV commands are received from dashboard
     * - System alerts are delivered
     *
     * Pattern: Check if already connected, if not, connect using stored credentials.
     */
    private fun connectSocketIfNeeded() {
        viewModelScope.launch {
            try {
                val start = System.currentTimeMillis()
                Timber.d("[PERF] HomeVM.connectSocket START")
                // Check if socket is already connected (e.g., from LoginViewModel)
                if (socketManager.isConnected()) {
                    Timber.d("[PERF] HomeVM.connectSocket DONE (${System.currentTimeMillis() - start}ms) - already connected")
                    return@launch
                }

                // Get stored credentials
                val jwtToken = secureStorage.getToken()
                val venueId = secureStorage.getVenueId()

                if (jwtToken == null || venueId == null) {
                    Timber.w("⚠️ [Socket.IO] No credentials found - cannot connect")
                    return@launch
                }

                // Use BLUMON_ENV to determine URL (matches NetworkModule logic)
                val socketUrl = if (BuildConfig.BLUMON_ENV == "PROD") {
                    BuildConfig.SOCKET_URL  // Production: https://api.avoqado.io
                } else {
                    BuildConfig.SOCKET_URL_DEV  // Sandbox: ngrok URL
                }

                Timber.d("[PERF] HomeVM.connectSocket connecting (${System.currentTimeMillis() - start}ms since start)")
                Timber.d("🔌 [Socket.IO] URL: $socketUrl")
                Timber.d("🔌 [Socket.IO] Venue ID: $venueId")

                // Connect with JWT authentication + terminalId for direct socket routing
                socketManager.connect(
                    url = socketUrl,
                    token = jwtToken,
                    terminalId = deviceInfoManager.getSerialNumber()
                )

                // Wait for connection and join venue room
                viewModelScope.launch {
                    socketManager.isConnected.collect { connected ->
                        if (connected) {
                            Timber.i("✅ [Socket.IO] Connected on session restore")
                            socketManager.joinVenueRoom(venueId)
                            Timber.i("✅ [Socket.IO] Joined venue room: $venueId")
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Socket.IO] Connection failed on session restore")
                // Don't block home screen on socket failure - app can work without real-time events
            }
        }
    }

    /**
     * 🔧 Initialize Blumon SDK after successful login
     *
     * **BLOCKS UI** until SDK is ready - shows loader to prevent user from going to payment
     * before SDK is initialized.
     *
     * **Why in HomeViewModel (not LoginViewModel)?**
     * - LoginViewModel gets destroyed when navigating to HomeScreen
     * - HomeViewModel persists throughout the logged-in session
     * - No risk of coroutine cancellation due to navigation
     *
     * **Flow:**
     * 1. Set isBlumonInitializing = true (shows loader)
     * 2. Wait 2 seconds for other operations to settle
     * 3. Fetch merchants from backend (to get real serial numbers)
     * 4. Use first merchant's serial for TerminalConfig
     * 5. Initialize SDK with correct serial
     * 6. Set isBlumonReady = true (hides loader, enables payment)
     *
     * Benefits:
     * - User cannot proceed to payment until SDK is ready
     * - No "NO AUTORIZADO" errors from uninitialized SDK
     * - Clear feedback to user that system is preparing
     * - OAuth + DUKPT keys downloaded in advance
     */
    private fun initializeBlumonSDK() {
        // Reset elapsed timer
        _blumonInitElapsedSeconds.value = 0

        blumonInitJob?.cancel()
        blumonInitJob = viewModelScope.launch(Dispatchers.IO) {
            val timerJob = launch {
                while (true) {
                    delay(1_000)
                    _blumonInitElapsedSeconds.value++
                }
            }

            try {
                val start = System.currentTimeMillis()
                Timber.d("[PERF] HomeVM.initBlumonSDK START")

                // 🔄 Start initialization - show loader
                _isBlumonInitializing.value = true
                _isBlumonReady.value = false
                _blumonInitError.value = null

                var attempt = 1
                while (attempt <= BLUMON_INIT_MAX_ATTEMPTS) {
                    if (attempt == 1) {
                        // ⏳ Wait for startup operations to settle on first attempt
                        delay(BLUMON_INIT_SETTLE_DELAY_MS)
                    }

                    Timber.i("🔧 [Blumon] SDK init attempt $attempt/$BLUMON_INIT_MAX_ATTEMPTS")

                    // Step 1: Fetch merchants from backend to get real serial numbers
                    val merchants = getMerchantsUseCase().firstOrNull()
                    val defaultMerchant = if (merchants.isNullOrEmpty()) {
                        Timber.w("⚠️ [Blumon] No merchants found - SDK init will use default serial")
                        null
                    } else {
                        // Step 2: Use first merchant's serial for TerminalConfig
                        val merchant = merchants.first()
                        Timber.i("🏪 [Blumon] Using merchant for SDK init: ${merchant.displayName} (${merchant.serialNumber})")
                        TerminalConfig.updateSerial(merchant.serialNumber)
                        merchant
                    }

                    // Step 3: Initialize SDK with correct serial AND posId
                    // CRITICAL: Pass posId to handle app restart after merchant switch
                    // Without this, SDK database has stale posId → "NO AUTORIZADO" on first payment
                    val result = initializationManager.ensureInitialized(defaultMerchantPosId = defaultMerchant?.posId)
                    if (result.isSuccess) {
                        Timber.d("[PERF] HomeVM.initBlumonSDK DONE (${System.currentTimeMillis() - start}ms)")
                        _isBlumonInitializing.value = false
                        _isBlumonReady.value = true
                        _blumonInitError.value = null
                        // 🛡️ CRASHLYTICS: Track Blumon SDK init success
                        try { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCustomKey("blumon_sdk_status", "READY") } catch (_: Exception) {}
                        return@launch
                    }

                    val error = result.exceptionOrNull() ?: IllegalStateException("Blumon init failed without exception")
                    val canRetry = isNetworkRelatedInitError(error) && attempt < BLUMON_INIT_MAX_ATTEMPTS
                    if (canRetry) {
                        val retryDelayMs = getRetryDelayMs(attempt)
                        Timber.w(
                            error,
                            "⚠️ [Blumon] SDK init attempt $attempt failed (network). Retrying in ${retryDelayMs}ms..."
                        )
                        delay(retryDelayMs)
                        attempt++
                        continue
                    }

                    Timber.e(error, "❌ [Blumon] SDK initialization failed")
                    _isBlumonInitializing.value = false
                    _isBlumonReady.value = false
                    _blumonInitError.value = getBlumonInitUserMessage(error)
                    // 🛡️ CRASHLYTICS: Track Blumon SDK init failure with reason
                    try {
                        com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                            setCustomKey("blumon_sdk_status", "FAILED")
                            setCustomKey("blumon_sdk_error", error.message?.take(100) ?: "unknown")
                        }
                    } catch (_: Exception) {}
                    return@launch
                }
            } catch (e: CancellationException) {
                // Coroutine was cancelled (user tapped Cancel) — don't overwrite error state
                Timber.d("[Blumon] SDK init coroutine cancelled")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "❌ [Blumon] Unexpected error during SDK initialization")
                _isBlumonInitializing.value = false
                _isBlumonReady.value = false
                _blumonInitError.value = getBlumonInitUserMessage(e)
            } finally {
                timerJob.cancel()
            }
        }
    }

    private fun isNetworkRelatedInitError(error: Throwable): Boolean {
        return error is java.net.UnknownHostException ||
            error.cause is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error.cause is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error.cause is java.net.SocketTimeoutException ||
            error.message?.contains("NetworkConnectionFailure", ignoreCase = true) == true ||
            error.message?.contains("UnknownHostException", ignoreCase = true) == true
    }

    private fun getRetryDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 2_000L
            2 -> 5_000L
            3 -> 10_000L
            else -> 15_000L
        }
    }

    private fun getBlumonInitUserMessage(error: Throwable): String {
        return if (isNetworkRelatedInitError(error)) {
            "Sin conexión a internet. Verifica tu conexión WiFi e intenta de nuevo."
        } else {
            "Error al inicializar sistema de pagos. Intente reiniciar."
        }
    }

    /**
     * 🔄 Retry Blumon SDK initialization
     * Called from UI when user taps "Reintentar" after init failure
     */
    fun retryBlumonInit() {
        if (!BuildConfig.ENABLE_PAX_SDK) {
            _isBlumonInitializing.value = false
            _isBlumonReady.value = true
            _blumonInitError.value = null
            Timber.i("🔶 [HomeViewModel] Ignoring Blumon retry on non-PAX build")
            return
        }
        Timber.i("🔧 [Blumon] Retrying SDK initialization...")
        initializeBlumonSDK()
    }

    /**
     * ❌ Cancel Blumon SDK initialization
     * Called from UI when user taps "Cancelar" after progressive warning (30s+)
     * Sets error state so user can retry later via "Reintentar" button.
     */
    fun cancelBlumonInit() {
        Timber.w("🚫 [Blumon] SDK initialization cancelled by user after ${_blumonInitElapsedSeconds.value}s")
        blumonInitJob?.cancel()
        blumonInitJob = null
        _isBlumonInitializing.value = false
        _isBlumonReady.value = false
        _blumonInitError.value = "Inicialización cancelada. Puedes reintentar cuando la conexión mejore."
    }

    /**
     * 🔄 Listen for connection restored events
     *
     * When connection is restored:
     * 1. Re-fetch merchants if still using fallback accounts
     * 2. Re-check for app updates (in case server was down on initial check)
     * 3. Reset failed payments + trigger immediate sync
     */
    private fun listenForConnectionRestored() {
        viewModelScope.launch {
            delay(1_000) // Stagger: wait for critical coroutines to settle
            connectionEventManager.connectionRestoredEvents.collect { event ->
                Timber.i("🔄 [HomeViewModel] Connection restored (after ${event.attemptsBeforeReconnection} attempts)")

                // Re-fetch merchants if still using fallback
                if (merchantRepository.isUsingFallback()) {
                    Timber.i("🔄 [HomeViewModel] Merchants are fallback - re-fetching from backend...")
                    retryFetchMerchantsAndReinitSDK()
                } else if (!_isBlumonReady.value && !_isBlumonInitializing.value) {
                    Timber.i("🔄 [HomeViewModel] SDK not ready after reconnection - retrying init...")
                    initializeBlumonSDK()
                }

                // Re-fetch data that may have failed during init due to no connection
                fetchAttendanceState()
                fetchSalesGoal(skipDelay = true)
                warmUpProductCache(skipDelay = true)

                // Re-check for updates (in case initial check failed due to no connection)
                // Only if there's no pending update already
                if (updateCheckManager.pendingUpdate.value == null) {
                    Timber.i("📦 [HomeViewModel] Re-checking for app updates after connection restored...")
                    checkForUpdates()
                }

                // Reset failed payments (periodic worker will retry them on next run)
                launch(Dispatchers.IO) {
                    try {
                        val resetCount = paymentQueueRepository.resetAllFailed()
                        if (resetCount > 0) {
                            Timber.i("💳 [HomeViewModel] Reset $resetCount failed payments after reconnection")
                        }
                        refreshQueueCounts()
                    } catch (e: Exception) {
                        Timber.e(e, "❌ [HomeViewModel] Error resetting failed payments")
                    }
                }
            }
        }
    }

    /**
     * Re-fetch merchants from backend and re-initialize Blumon SDK
     */
    private fun retryFetchMerchantsAndReinitSDK() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = merchantRepository.refreshMerchants()
                result.onSuccess {
                    Timber.i("✅ [HomeViewModel] Merchants refreshed from backend")
                    // Re-init SDK with real merchants if it was initialized with fallback
                    Timber.i("🔧 [HomeViewModel] Re-initializing Blumon SDK with real merchants...")
                    initializeBlumonSDK()
                }.onFailure { error ->
                    Timber.w(error, "⚠️ [HomeViewModel] Still can't fetch merchants from backend")
                    if (!_isBlumonReady.value && !_isBlumonInitializing.value) {
                        Timber.i("🔁 [HomeViewModel] Retrying SDK init with current merchant cache")
                        initializeBlumonSDK()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [HomeViewModel] Error re-fetching merchants")
            }
        }
    }

    /**
     * 🔌 Collect Socket.IO Real-time Events
     *
     * Listen to system-wide events:
     * - SystemAlert: Critical alerts from server (info, warning, error, critical)
     * - TPVCommand: Admin commands from dashboard (maintenance mode, reload, disable, shutdown, restart)
     * - InventoryLowStock/OutOfStock: Stock alerts for venue
     * - PrinterStatus/CardReaderStatus/PeripheralError: Hardware status updates
     *
     * Pattern: Centralized event handling for global app state
     */
    private fun collectSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    // ═══════════════════════════════════════════════════════════
                    // SYSTEM ALERTS
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.SystemAlert -> {
                        Timber.i("🚨 [Socket] System Alert [${event.level}]: ${event.title} - ${event.message}")
                        _systemAlerts.tryEmit(event)

                        // Log based on severity
                        when (event.level) {
                            "critical" -> Timber.e("🔴 [CRITICAL] ${event.title}: ${event.message}")
                            "error" -> Timber.e("❌ [ERROR] ${event.title}: ${event.message}")
                            "warning" -> Timber.w("⚠️ [WARNING] ${event.title}: ${event.message}")
                            "info" -> Timber.i("ℹ️ [INFO] ${event.title}: ${event.message}")
                        }
                    }

                    // ═══════════════════════════════════════════════════════════
                    // TPV ADMIN COMMANDS (from dashboard)
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.TPVCommand -> {
                        Timber.w("⚙️ [Socket] TPV Command received: ${event.commandType} (requested by: ${event.requestedBy})")
                        _adminCommands.tryEmit(event)

                        // Execute command via CommandExecutor
                        executeRemoteCommand(event)
                    }

                    // TPV COMMAND CANCELLED (command cancelled before execution)
                    is SocketEvent.TPVCommandCancelled -> {
                        Timber.w("🚫 [Socket] Command cancelled: ${event.commandId} by ${event.cancelledBy}")
                        // Command was cancelled - no action needed, just log
                    }

                    // ═══════════════════════════════════════════════════════════
                    // INVENTORY ALERTS
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.InventoryLowStock -> {
                        Timber.w("📦 [Socket] Low stock alert: ${event.rawMaterialName} (${event.currentStock} ${event.unit})")
                        _inventoryAlerts.tryEmit(event)
                    }

                    is SocketEvent.InventoryOutOfStock -> {
                        Timber.e("🚫 [Socket] OUT OF STOCK: ${event.rawMaterialName}")
                        _inventoryAlerts.tryEmit(event)
                    }

                    is SocketEvent.InventoryUpdated -> {
                        Timber.d("📦 [Socket] Inventory updated: ${event.rawMaterialName} (${event.currentStock} ${event.unit})")
                        _inventoryAlerts.tryEmit(event)
                    }

                    // ═══════════════════════════════════════════════════════════
                    // HARDWARE STATUS
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.PrinterStatus -> {
                        Timber.d("🖨️ [Socket] Printer status: ${event.status} (${event.printerType})")
                        _hardwareStatus.tryEmit(event)

                        if (event.status in listOf("OFFLINE", "PAPER_OUT", "ERROR")) {
                            Timber.e("❌ [Printer] ${event.status}: ${event.errorMessage ?: "No details"}")
                        }
                    }

                    is SocketEvent.CardReaderStatus -> {
                        Timber.d("💳 [Socket] Card reader status: ${event.status} (${event.readerType})")
                        _hardwareStatus.tryEmit(event)

                        if (event.status in listOf("ERROR", "DISCONNECTED")) {
                            Timber.e("❌ [Card Reader] ${event.status}: ${event.errorMessage ?: "No details"}")
                        }
                    }

                    is SocketEvent.PeripheralError -> {
                        Timber.e("⚠️ [Socket] Peripheral error: ${event.peripheralType} - ${event.errorMessage} (severity: ${event.severity})")
                        _hardwareStatus.tryEmit(event)
                    }

                    // ═══════════════════════════════════════════════════════════
                    // TERMINAL PAYMENT REQUEST (iOS → Backend → Socket.IO → TPV)
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.TerminalPaymentRequest -> {
                        Timber.i("💳 [Socket] Terminal payment request: ${event.requestId} | amount=${event.amountCents} cents")
                        val request = com.jaac.avoqado_tpv.core.bluetooth.BlePaymentRequest(
                            amountCents = event.amountCents,
                            tipCents = event.tipCents,
                            rating = event.rating,
                            skipReview = event.skipReview,
                            orderId = event.orderId,
                            processedByStaffId = event.processedByStaffId,
                            source = com.jaac.avoqado_tpv.core.bluetooth.PaymentSource.SOCKET,
                            socketRequestId = event.requestId
                        )
                        bluetoothPaymentService.submitSocketPaymentRequest(request)
                    }

                    is SocketEvent.TerminalPaymentCancel -> {
                        Timber.i("🚫 [Socket] Terminal payment cancel: requestId=${event.requestId} reason=${event.reason}")
                        // Cancel only if the requestId matches the current payment (idempotency)
                        bluetoothPaymentService.cancelSocketPaymentRequest(event.requestId)
                    }

                    // Other events handled by other ViewModels (PaymentViewModel, OrderViewModel, etc.)
                    else -> {
                        // Ignore events not relevant to HomeViewModel
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REMOTE COMMAND EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 🎮 Execute Remote Command
     *
     * Converts Socket.IO event to TpvCommand and executes via CommandExecutor.
     * Handles full lifecycle: ACK → STARTED → EXECUTE → RESULT
     *
     * @param event The TPVCommand socket event received from server
     */
    private fun executeRemoteCommand(event: SocketEvent.TPVCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Parse command type
                val commandType = TpvCommandType.fromString(event.commandType)
                if (commandType == null) {
                    Timber.e("❌ [Command] Unknown command type: ${event.commandType}")
                    return@launch
                }

                // Convert SocketEvent to TpvCommand
                val command = TpvCommand(
                    commandId = event.commandId,
                    correlationId = event.correlationId,
                    type = commandType,
                    payload = event.payload,
                    requiresPin = event.requiresPin,
                    priority = TpvCommandPriority.fromString(event.priority),
                    expiresAt = try {
                        Instant.parse(event.expiresAt)
                    } catch (e: Exception) {
                        Instant.now().plusSeconds(3600) // Default 1 hour if parse fails
                    },
                    requestedBy = event.requestedBy,
                    requestedByName = event.requestedByName
                )

                // Execute via CommandExecutor
                val result = commandExecutor.execute(command)

                Timber.i("✅ [Command] Executed ${command.type.name}: ${result.status.name} - ${result.message}")

                // **CRITICAL FIX (2025-12-01):**
                // Send HTTP ACK to server so it can update command status and sync terminal state.
                // Without this, commands received via Socket.IO would execute locally but server
                // would never know the result, causing dashboard/TPV state desync.
                val terminalId = secureStorage.getSerialNumber() ?: run {
                    Timber.e("❌ [Command] Cannot send ACK - no terminal serial number")
                    return@launch
                }

                val ackResult = heartbeatRepository.sendCommandAck(command.commandId, terminalId, result)
                ackResult.onSuccess {
                    Timber.i("✅ [Command] ACK sent for ${command.type.name}")
                }.onError { exception ->
                    Timber.w("⚠️ [Command] ACK failed for ${command.commandId}: ${exception.message}")
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Command] Failed to execute command: ${event.commandType}")
            }
        }
    }

    /**
     * 🛠️ Exit Maintenance Mode (Local Action)
     *
     * Called when staff clicks "Exit Maintenance" button on overlay.
     * Staff can exit maintenance mode locally without admin intervention.
     */
    fun exitMaintenance() {
        Timber.i("🛠️ [Maintenance] Staff exiting maintenance mode locally")
        maintenanceManager.exitMaintenance()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAYMENT QUEUE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Load initial payment queue counts for DeviceAlertBanner.
     * Deferred to avoid slowing down critical init path.
     */
    private fun loadInitialQueueCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(3_000) // Stagger: non-critical, after Blumon SDK init
            try {
                val pending = paymentQueueRepository.getPendingCount()
                val failed = paymentQueueRepository.getFailedCount()
                paymentQueueStateManager.refreshCounts(pending, failed)
                if (pending > 0 || failed > 0) {
                    Timber.i("💳 [HomeViewModel] Payment queue: pending=$pending, failed=$failed")
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [HomeViewModel] Error loading queue counts")
            }
        }
    }

    /**
     * Refresh payment queue counts (called after sync events)
     */
    private suspend fun refreshQueueCounts() {
        try {
            val pending = paymentQueueRepository.getPendingCount()
            val failed = paymentQueueRepository.getFailedCount()
            paymentQueueStateManager.refreshCounts(pending, failed)
            Timber.d("💳 [HomeViewModel] Queue counts refreshed: pending=$pending, failed=$failed")
        } catch (e: Exception) {
            Timber.e(e, "❌ [HomeViewModel] Error refreshing queue counts")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VENUE STATUS CHANGE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 📊 Check for Venue Status Changes
     *
     * Called after token refresh to detect if venue status changed mid-session.
     * Emits appropriate VenueStatusEvent based on the change.
     *
     * @param newStatus The new venue status from server response
     */
    fun checkVenueStatusChange(newStatus: VenueStatus) {
        viewModelScope.launch {
            val oldStatus = secureStorage.getVenueStatus()

            if (oldStatus != newStatus) {
                Timber.w("📊 [VenueStatus] Status changed: $oldStatus → $newStatus")

                // Update storage and state
                secureStorage.saveVenueStatus(newStatus)
                _venueStatus.value = newStatus

                // Emit appropriate event based on new status
                when {
                    // Venue suspended - force logout
                    newStatus == VenueStatus.SUSPENDED || newStatus == VenueStatus.ADMIN_SUSPENDED -> {
                        Timber.e("🚫 [VenueStatus] Venue suspended - forcing logout")
                        _venueStatusEvents.emit(VenueStatusEvent.VenueSuspended)
                    }

                    // Venue closed - force logout
                    newStatus == VenueStatus.CLOSED -> {
                        Timber.e("🚫 [VenueStatus] Venue closed - forcing logout")
                        _venueStatusEvents.emit(VenueStatusEvent.VenueClosed)
                    }

                    // Venue activated - show success
                    newStatus == VenueStatus.ACTIVE && oldStatus != VenueStatus.ACTIVE -> {
                        Timber.i("✅ [VenueStatus] Venue activated!")
                        _venueStatusEvents.emit(VenueStatusEvent.VenueActivated)
                    }

                    // Other status changes - just notify
                    else -> {
                        Timber.i("📊 [VenueStatus] Status changed: $oldStatus → $newStatus")
                        _venueStatusEvents.emit(VenueStatusEvent.StatusChanged(oldStatus, newStatus))
                    }
                }
            }
        }
    }

    /**
     * Force logout due to venue status change
     *
     * Called when venue becomes SUSPENDED or CLOSED mid-session.
     */
    fun forceLogoutDueToVenueStatus() {
        Timber.w("🚫 [VenueStatus] Force logout triggered")
        logout()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGOUT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logout user
     *
     * Clears session, stops heartbeat, and disconnects Socket.IO.
     */
    fun logout() {
        Timber.d("🚪 User initiated logout")

        // 🧹 Cancel pending syncs and release maps before disconnecting
        orderSyncCoordinator.cleanup()

        // 🔌 Disconnect Socket.IO
        socketManager.disconnect()
        Timber.d("🔌 Socket.IO disconnected")

        // Clear session from SecureStorage
        authRepository.logout()

        Timber.d("✅ Logout complete")
    }
}
