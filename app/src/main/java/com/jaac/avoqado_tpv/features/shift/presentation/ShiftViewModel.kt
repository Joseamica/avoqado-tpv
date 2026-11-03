package com.jaac.avoqado_tpv.features.shift.presentation

import com.jaac.avoqado_tpv.core.util.VenueTimeZone
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
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.PaginaPagosDelCierre
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

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
    private val permissionsRepository: PermissionsRepository,
    /** 💸 La cola durable de REEMBOLSOS: lo que impide cerrar la caja (Fase 2, Task 9). */
    private val refundQueueRepository: RefundQueueRepository
) : ViewModel() {

    /**
     * Zona del NEGOCIO para pintar horas (nunca el reloj del aparato: una PAX configurada en otra
     * zona mostraría la hora del cajón corrida — regla del workspace, y el founder lo notó el 28-ago).
     */
    val zonaDelNegocio: java.time.ZoneId
        get() = VenueTimeZone.get(secureStorage)


    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    // El executor de Room también lo lee al confirmar «Revisado».
    private val solicitudesDeCierre = AtomicLong(0)
    // Cambia por gesto/entrada UI; un reload del pulso propio no lo invalida.
    private val gestosDePantalla = AtomicLong(0)
    private data class ContextoCierre(val version: Long, val venueId: String, val staffId: String?, val token: String?,
        val shiftId: String, val localSessionId: String?, val uiEpoch: Long)
    private var contextoCierre: ContextoCierre? = null

    private val _state = MutableStateFlow<ShiftState>(ShiftState.Idle)
    val state: StateFlow<ShiftState> = _state.asStateFlow()

    private val _localCaja = MutableStateFlow<CajaLocalPresentation?>(null)
    val localCaja: StateFlow<CajaLocalPresentation?> = _localCaja.asStateFlow()
    private var acknowledgedLocalSessionId: String? = null
    private val _localHistory = MutableStateFlow<com.jaac.avoqado_tpv.features.shift.data.repository.LocalShiftPage?>(null)
    val localHistory = _localHistory.asStateFlow()
    private val _localReview = MutableStateFlow<LocalCajaReview?>(null)
    val localReview = _localReview.asStateFlow()

    // Pull-to-refresh state
    /** M-4: al cerrar se le pregunta a Avoqado hasta 5 s (D3); mientras tanto la pantalla dice que está cerrando. */
    private val _cerrandoCaja = MutableStateFlow(false)
    val cerrandoCaja: StateFlow<Boolean> = _cerrandoCaja.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Initial loading state (true until first data load completes)
    // Used to show loading overlay on WelcomeScreen during post-login sync
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    // Offline state (Square/Toast prevention pattern)
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    // «Sin conexión» tiene DOS causas y el ConnectivityObserver sólo ve una: el aparato sin red, o
    // con WiFi pero el servidor sin contestar (N86, 23-sep-2026: arrancar sin servidor dejaba «Sin
    // turno de caja» con la caja abierta y guardada). `isOffline` es la suma; cada fuente escribe
    // sólo la suya, así un «WiFi disponible» no borra un servidor que sigue callado.
    private var sinRed = false
    private var servidorSinContestar = false
    private fun publicarSinConexion() {
        _isOffline.value = sinRed || servidorSinContestar
    }

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
        observeShiftChanges()
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
                sinRed = status == NetworkStatus.Unavailable
                publicarSinConexion()

                when (status) {
                    NetworkStatus.Unavailable -> {
                        Timber.w("⚠️ [ShiftViewModel] Network lost - loading cached shift state")
                        loadCachedShiftInfo()
                    }
                    NetworkStatus.Available -> {
                        if (wasOffline && !_isOffline.value) {
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
     * misleading "Sin caja abierta".
     */
    private fun loadCachedShiftInfo() {
        viewModelScope.launch {
            val venueId = secureStorage.getVenueId() ?: return@launch
            cargarUltimoTurnoConocido(venueId)
        }
    }

    private suspend fun cargarUltimoTurnoConocido(venueId: String) {
        val staff = secureStorage.getStaffId(); val token = secureStorage.getToken()
        val cached = cachedShiftDao.getCachedShift(venueId)
        if (secureStorage.getVenueId() != venueId || secureStorage.getStaffId() != staff || secureStorage.getToken() != token) return

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

    /** El servidor contestó: su palabra manda y el «último turno conocido» deja de mostrarse. */
    private fun alContestarElServidor() {
        servidorSinContestar = false
        publicarSinConexion()
        if (!_isOffline.value) _cachedShiftInfo.value = null
    }

    /**
     * El servidor NO contestó (no es lo mismo que contestar «sin turno»): se dice «sin conexión» y se
     * muestra el último turno que ESTE aparato confirmó, si lo hay. Un error que sí es respuesta del
     * servidor (4xx) no entra aquí: ahí no se sabe menos, se sabe otra cosa.
     */
    private suspend fun alNoContestarElServidor(venueId: String, error: ApiException) {
        if (!error.servidorNoContesto) return
        val staff = secureStorage.getStaffId(); val token = secureStorage.getToken()
        cargarUltimoTurnoConocido(venueId)
        if (secureStorage.getVenueId() != venueId || secureStorage.getStaffId() != staff || secureStorage.getToken() != token) return
        servidorSinContestar = true
        publicarSinConexion()
        Timber.w("📦 [ShiftViewModel] Servidor sin contestar — último turno conocido: abierto=${_cachedShiftInfo.value?.isOpen}")
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
     * - Fixing "Sin caja abierta" bug when shift was opened during offline mode
     * - Syncing shift closures made by other terminals
     * - Updating shift totals (sales, cash drawer)
     */
    /**
     * 🔔 Otra pantalla abrió o cerró el turno (QA Nexgo N86, 7-sep-2026). Cada pantalla tiene su
     * propio `ShiftViewModel` —el de Inicio vive en el back-stack de Home— y el de «Turnos de caja»
     * abría la caja sin que Inicio se enterara: seguía en «Sin turno de caja» y bloqueaba Cobrar
     * hasta reiniciar la app. El repositorio avisa; aquí se recarga del servidor. La instancia que
     * hizo el cambio también recibe el aviso: `loadCurrentShift` no parpadea si ya tiene turno.
     */
    private fun observeShiftChanges() {
        viewModelScope.launch {
            shiftRepository.shiftChanges.collect {
                Timber.i("🔄 [ShiftViewModel] El turno cambió desde otra pantalla — recargando")
                loadCurrentShift()
            }
        }
    }

    private fun listenToConnectionRestored() {
        viewModelScope.launch {
            connectionEventManager.connectionRestoredEvents.collect { event ->
                Timber.i("🔄 [ShiftViewModel] Connection restored - syncing shift data")
                Timber.d("   Reconnected after ${event.attemptsBeforeReconnection} attempts at ${event.timestamp}")
                // Este evento proviene del latido HTTP, a diferencia de un Success local de Room.
                alContestarElServidor()
                // M-4: una recarga aquí invalidaría el cierre en curso, y su mensaje de bloqueo ya no se mostraría. El cierre siempre
                // termina publicando un resultado, así que no se pierde nada.
                if (!_cerrandoCaja.value) loadCurrentShift()
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
    private fun historyOf(state: ShiftState): List<Shift> = when (state) {
        is ShiftState.ShiftActive -> state.shiftHistory
        is ShiftState.ShiftClosed -> state.shiftHistory
        is ShiftState.NoActiveShift -> state.shiftHistory
        else -> emptyList()
    }

    private fun publishShift(shift: Shift?, history: List<Shift>) {
        _state.value = when {
            shift == null -> ShiftState.NoActiveShift(history)
            shift.status == com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.CLOSED -> {
                val row = _localCaja.value?.session?.takeIf { it.localSessionId == shift.localShiftSessionId }
                if (acknowledgedLocalSessionId == row?.localSessionId && row != null) ShiftState.NoActiveShift(history)
                else ShiftState.ShiftClosed(shift, history, row?.cashReconciliationAction?.let {
                    runCatching { CashReconciliationAction.valueOf(it) }.getOrNull()
                })
            }
            else -> ShiftState.ShiftActive(shift, history)
        }
        _isInitialLoading.value = false
    }

    fun loadCurrentShift() { loadCaja(refreshing = false) }
    fun refresh() { loadCaja(refreshing = true) }

    /** Room primero; cada suspensión conserva venue, actor, token y dueño de esta lectura. */
    private fun loadCaja(refreshing: Boolean) {
        val solicitud = solicitudesDeCierre.incrementAndGet()
        val epoch = gestosDePantalla.get()
        val venueId = secureStorage.getVenueId()
        val staffId = secureStorage.getStaffId()
        val token = secureStorage.getToken()
        fun vigente() = solicitudesDeCierre.get() == solicitud && gestosDePantalla.get() == epoch &&
            secureStorage.getVenueId() == venueId && secureStorage.getStaffId() == staffId && secureStorage.getToken() == token
        viewModelScope.launch {
            if (hasUnacknowledgedCashReconciliationResult() &&
                (_state.value as? ShiftState.ShiftClosed)?.shift?.financialTotalsKnown != false) return@launch
            if (venueId == null) {
                _state.value = ShiftState.Error("No se encontró información del local")
                _isInitialLoading.value = false
                return@launch
            }
            _isRefreshing.value = refreshing
            val priorHistory = historyOf(_state.value)
            if (_state.value == ShiftState.Idle || _state.value is ShiftState.Error) _state.value = ShiftState.Loading
            try {
                val bootstrap = readCajaBootstrap(shiftRepository, venueId, ::vigente)
                if (!vigente()) return@launch
                val local = bootstrap.local
                val legacyFallback = bootstrap.shift.takeIf { local == null }
                _localCaja.value = local?.let { CajaLocalPresentation(it.row, it.summary) }
                if (local != null) publishShift(local.shift, priorHistory)
                else if (legacyFallback != null) publishShift(legacyFallback, priorHistory)
                val result = shiftRepository.getCurrentShift(venueId)
                if (!vigente()) return@launch
                when (result) {
                    is Result.Success -> {
                        val remote = result.data
                        // Revalidar la fila DESPUÉS de HTTP antes de mostrar sus métricas.
                        val fresh = shiftRepository.leerCajaLocal(venueId)
                        if (!vigente()) return@launch
                        _localCaja.value = fresh?.let { CajaLocalPresentation(it.row, it.summary) }
                        if (remote?.financialTotalsKnown != false) alContestarElServidor()
                        if (fresh != null) {
                            val row = fresh.row
                            val sameAccreditedOpen = remote != null && remote.hasKnownFinancialTotals() &&
                                remote.status == com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.OPEN &&
                                remote.venueId == venueId && remote.localShiftSessionId == row.localSessionId &&
                                row.serverShiftId != null && remote.id == row.serverShiftId && row.localStatus == "OPEN" &&
                                row.openingStatus == "CONFIRMED" && row.closeOperationId == null && row.closeStatus == "NONE"
                            publishShift(if (sameAccreditedOpen) remote else fresh.shift, priorHistory)
                        } else {
                            if (remote?.financialTotalsKnown != false) {
                                cacheShiftState(remote, venueId)
                                if (!vigente()) return@launch
                            }
                            publishShift(remote, priorHistory)
                        }
                    }
                    is Result.Error -> {
                        alNoContestarElServidor(venueId, result.exception)
                        if (!vigente()) return@launch
                        if (local == null && legacyFallback == null) _state.value = ShiftState.Error(
                            translateError(result.exception), canOpenLocally = result.exception.servidorNoContesto)
                        _isInitialLoading.value = false
                    }
                }
                // El historial remoto no retrasa la caja ni sus hechos locales.
                _isRefreshing.value = false
                val visible = _state.value
                val history = shiftRepository.getShiftHistory(venueId, limit = 10)
                if (!vigente() || _state.value !== visible) return@launch
                if (history is Result.Success) _state.value = when (visible) {
                    is ShiftState.ShiftActive -> visible.copy(shiftHistory = history.data)
                    is ShiftState.ShiftClosed -> visible.copy(shiftHistory = history.data)
                    is ShiftState.NoActiveShift -> visible.copy(shiftHistory = history.data)
                    else -> visible
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (vigente()) {
                    _state.value = ShiftState.Error("No se pudo leer la caja guardada. Vuelve a intentar; no se confirma ningún cambio.")
                    _isInitialLoading.value = false
                }
                Timber.e(error, "No se pudo consultar la caja local")
            } finally { if (vigente()) _isRefreshing.value = false }
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
        gestosDePantalla.incrementAndGet()
        solicitudesDeCierre.incrementAndGet()
        viewModelScope.launch {
            if (!_canOpenShift.value) {
                _state.value = ShiftState.Error("No tienes permiso para abrir la caja.\n\nContacta a tu administrador.")
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

            val actor = secureStorage.getStaffId()
            val token = secureStorage.getToken()
            val epoch = gestosDePantalla.get()
            Timber.i("Guardando apertura local")

            when (val result = shiftRepository.openShift(venueId, staffId, startingCash)) {
                is Result.Success -> {
                    if (secureStorage.getVenueId() != venueId || secureStorage.getStaffId() != actor ||
                        secureStorage.getToken() != token || gestosDePantalla.get() != epoch) return@launch
                    acknowledgedLocalSessionId = null
                    publishShift(result.data, emptyList())
                    loadCurrentShift()
                }
                is Result.Error -> {
                    if (secureStorage.getVenueId() != venueId || secureStorage.getStaffId() != actor ||
                        secureStorage.getToken() != token || gestosDePantalla.get() != epoch) return@launch
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
                _state.value = ShiftState.Error("No tienes permiso para cerrar la caja.\n\nContacta a tu administrador.")
                return@launch
            }

            val currentState = _state.value
            if (currentState !is ShiftState.ShiftActive) {
                Timber.w("⚠️ Cannot close shift: No active shift")
                return@launch
            }

            val version = solicitudesDeCierre.incrementAndGet()
            _state.value = ShiftState.Loading
            _cerrandoCaja.value = true
            coroutineContext[Job]?.invokeOnCompletion { _cerrandoCaja.value = false }

            val venueId = secureStorage.getVenueId()
            if (venueId == null) {
                Timber.e("❌ No venueId found")
                _state.value = ShiftState.Error("No se encontró información del local")
                return@launch
            }

            val contexto = ContextoCierre(version, venueId, secureStorage.getStaffId(), secureStorage.getToken(),
                currentState.shift.id, currentState.shift.localShiftSessionId, gestosDePantalla.incrementAndGet())
            contextoCierre = contexto

            // B2 separa GUARDAR del POST: el repositorio conserva su cerca SDK/SQL.
            // Las pantallas legacy aún sin identidad local conservan C7 hasta importar su caja.
            if (currentState.shift.localShiftSessionId == null && currentState.shift.financialTotalsKnown != false) {
            // 💸 BARRERA (Fase 2, Task 9): una devolución que el SDK YA aprobó y que aún no quedó
            // registrada en el servidor —o que el servidor rechazó y nadie ha visto— impide cerrar.
            // Cerrar antes firmaría un corte que cuadra DE MÁS por dinero que sí salió del cajón:
            // el mismo defecto que el retiro sin red que inventaba un faltante de $50 en Android.
            val bloqueantes = try {
                refundQueueRepository.blockingForVenue(venueId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ante la duda se BLOQUEA, no se cierra a ciegas: no poder leer la cola es no saber
                // si hay dinero sin anotar. El cajero vuelve a intentar.
                Timber.e(e, "💸 [Shift] No se pudo leer la cola de reembolsos antes de cerrar")
                if (!puedePublicar(contexto, ShiftState.Loading)) return@launch
                _state.value = ShiftState.Error(
                    "No se pudo verificar si hay devoluciones sin registrar.\n\nVuelve a intentar cerrar la caja."
                )
                return@launch
            }
            if (!puedePublicar(contexto, ShiftState.Loading)) return@launch
            if (bloqueantes.isNotEmpty()) {
                Timber.w("💸 [Shift] Cierre BLOQUEADO: ${bloqueantes.size} devolución(es) sin registrar o sin reconocer")
                _state.value = ShiftState.CloseBlockedByRefunds(
                    shift = currentState.shift,
                    shiftHistory = currentState.shiftHistory,
                    refunds = bloqueantes,
                )
                return@launch
            }

            val pagos = try {
                shiftRepository.pagosQueBloqueanCierre(venueId, 0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (puedePublicar(contexto, ShiftState.Loading)) {
                    _state.value = ShiftState.CloseBlockedByPayments(currentState.shift, currentState.shiftHistory, venueId,
                        error = "No se pudo verificar si hay ventas sin registrar o sin revisar. Vuelve a intentar.")
                }
                return@launch
            }
            if (!puedePublicar(contexto, ShiftState.Loading)) return@launch
            if (pagos.total > 0) {
                _state.value = ShiftState.CloseBlockedByPayments(currentState.shift, currentState.shiftHistory, venueId, pagos)
                return@launch
            }

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
                    if (!resultadoPerteneceAlGesto(contexto, closedShift)) return@launch
                    if (closedShift.financialTotalsKnown == false) {
                        // El recibo local no firma ventas/esperado/diferencia de compatibilidad.
                        val snapshot = try { shiftRepository.leerCajaLocal(venueId) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (resultadoPerteneceAlGesto(contexto, closedShift)) _state.value = ShiftState.Error(
                                "El cierre quedó guardado; no se pudo leer su resumen. Reabre Caja para comprobarlo.")
                            return@launch
                        }
                        if (!resultadoPerteneceAlGesto(contexto, closedShift)) return@launch
                        _localCaja.value = snapshot?.takeIf { it.row.localSessionId == closedShift.localShiftSessionId }
                            ?.let { CajaLocalPresentation(it.row, it.summary) }
                        _state.value = ShiftState.ShiftClosed(closedShift, currentState.shiftHistory, reconciliationAction)
                        _isInitialLoading.value = false
                        return@launch
                    }
                    Timber.i("Cierre remoto acreditado: ${closedShift.id}")

                    // Load updated history (now includes the just-closed shift)
                    val historyResult = shiftRepository.getShiftHistory(venueId, limit = 10)
                    val shiftHistory = when (historyResult) {
                        is Result.Success -> historyResult.data
                        is Result.Error -> emptyList()
                    }

                    if (!resultadoPerteneceAlGesto(contexto, closedShift)) return@launch
                    // Show closed shift briefly with updated history
                    val publicado = ShiftState.ShiftClosed(
                        shift = closedShift,
                        shiftHistory = shiftHistory,
                        reconciliationAction = reconciliationAction
                    )
                    _state.value = publicado

                    // Preserve the legacy behavior exactly. A reconciliation attempt remains until
                    // explicit acknowledgment so the cashier can read the result.
                    if (reconciliationAction == null) {
                        kotlinx.coroutines.delay(2000)
                        if (_state.value === publicado && resultadoPerteneceAlGesto(contexto, closedShift)) loadCurrentShift()
                    }
                }
                is Result.Error -> {
                    if (!puedePublicar(contexto, ShiftState.Loading)) return@launch
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
    /**
     * 💸 Salida de emergencia de la barrera (Fase 2, Task 9): el cajero vio el aviso de una
     * devolución que el servidor RECHAZÓ de forma definitiva y libera el cierre.
     *
     * 🔴 Sólo aplica a filas `permanent`. Una devolución PENDIENTE no se puede «reconocer»: se va
     * a registrar sola al volver la red, y dejar que alguien la quite de la barrera cerraría el
     * corte con dinero sin anotar — justo lo que la barrera existe para impedir. Exige el mismo
     * permiso que cerrar la caja. La fila queda en la cola para conciliarse a mano.
     */
    fun acknowledgeRefund(idempotencyKey: String) {
        viewModelScope.launch {
            if (!_canCloseShift.value) {
                _state.value = ShiftState.Error("No tienes permiso para cerrar la caja.\n\nContacta a tu administrador.")
                return@launch
            }
            val current = _state.value as? ShiftState.CloseBlockedByRefunds ?: return@launch
            val fila = current.refunds.firstOrNull { it.idempotencyKey == idempotencyKey } ?: return@launch
            if (!fila.permanent) {
                Timber.w("💸 [Shift] Se intentó reconocer una devolución PENDIENTE — no se permite | key=$idempotencyKey")
                return@launch
            }
            val venueId = secureStorage.getVenueId() ?: return@launch
            try {
                // 🔴 Queda QUIÉN y CUÁNDO en la fila (auditoría de Codex F9): liberar el cierre con dinero
                // devuelto sin registro exige evidencia durable del responsable. Cero filas afectadas =
                // no se liberó nada, y se dice.
                val quien = secureStorage.getStaffId()
                val afectadas = refundQueueRepository.acknowledge(idempotencyKey, quien)
                if (afectadas == 0) {
                    Timber.e("💸 [Shift] acknowledge no afectó filas | key=$idempotencyKey")
                    _state.value = ShiftState.Error("No se pudo marcar la devolución como vista.\n\nVuelve a intentarlo.")
                    return@launch
                }
                Timber.i("💸 [Shift] Devolución rechazada reconocida | key=$idempotencyKey | staff=$quien")
                val restantes = refundQueueRepository.blockingForVenue(venueId)
                _state.value = if (restantes.isEmpty()) {
                    ShiftState.ShiftActive(shift = current.shift, shiftHistory = current.shiftHistory)
                } else {
                    current.copy(refunds = restantes)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "💸 [Shift] No se pudo reconocer la devolución | key=$idempotencyKey")
                _state.value = ShiftState.Error("No se pudo marcar la devolución como vista.\n\nVuelve a intentarlo.")
            }
        }
    }

    /** Sólo publicación UI: el commit/intención de A permanecen aunque ya se esté mostrando B. */
    private fun resultadoPerteneceAlGesto(contexto: ContextoCierre, resultado: Shift): Boolean {
        if (contextoCierre !== contexto || gestosDePantalla.get() != contexto.uiEpoch || !mismaIdentidad(contexto)) return false
        fun mismaCaja(shift: Shift): Boolean = if (contexto.localSessionId != null)
            shift.localShiftSessionId == contexto.localSessionId else shift.id == contexto.shiftId
        if (!mismaCaja(resultado)) return false
        return when (val visible = _state.value) {
            ShiftState.Loading, is ShiftState.NoActiveShift -> true
            is ShiftState.ShiftActive -> mismaCaja(visible.shift)
            is ShiftState.ShiftClosed -> mismaCaja(visible.shift)
            else -> false
        }
    }

    private fun mismaPantalla(contexto: ContextoCierre, esperado: ShiftState): Boolean =
        solicitudesDeCierre.get() == contexto.version && _state.value === esperado &&
            (esperado !is ShiftState.CloseBlockedByPayments || esperado.shift.id == contexto.shiftId)

    private fun mismaIdentidad(contexto: ContextoCierre): Boolean =
        secureStorage.getVenueId() == contexto.venueId && secureStorage.getStaffId() == contexto.staffId &&
            secureStorage.getToken() == contexto.token

    private fun puedePublicar(contexto: ContextoCierre, esperado: ShiftState): Boolean {
        if (!mismaPantalla(contexto, esperado)) return false
        if (!mismaIdentidad(contexto)) {
            _state.value = ShiftState.Error("La sesión cambió. Vuelve a abrir Caja para revisar las ventas.")
            return false
        }
        return true
    }

    fun onShiftScreenEntered() {
        gestosDePantalla.incrementAndGet()
        _localReview.value = null
        _localHistory.value = null
        refreshSettings()
        refreshShiftPermissions()
        loadCurrentShift()
    }

    fun onShiftScreenLeft() {
        gestosDePantalla.incrementAndGet()
        solicitudesDeCierre.incrementAndGet()
        _localReview.value = null
    }

    /** Un gesto explícito de adopción invalida el cierre UI viejo incluso en la misma pareja real. */
    fun adoptRemoteCaja() {
        val source = _localCaja.value ?: return
        val venue = secureStorage.getVenueId() ?: return
        val staff = secureStorage.getStaffId()
        val token = secureStorage.getToken()
        val epoch = gestosDePantalla.incrementAndGet()
        solicitudesDeCierre.incrementAndGet()
        viewModelScope.launch {
            if (!permissionsRepository.hasPermission("tpv-shifts:create")) return@launch
            if (gestosDePantalla.get() != epoch || secureStorage.getVenueId() != venue ||
                secureStorage.getStaffId() != staff || secureStorage.getToken() != token) return@launch
            when (val result = shiftRepository.adoptarCajaRemotaParaNuevosGestos(venue, source.session.localSessionId)) {
                is Result.Success -> {
                    if (gestosDePantalla.get() != epoch || secureStorage.getVenueId() != venue ||
                        secureStorage.getStaffId() != staff || secureStorage.getToken() != token) return@launch
                    contextoCierre = null
                    _localReview.value = null
                    acknowledgedLocalSessionId = null
                    _state.value = ShiftState.Loading
                    loadCurrentShift()
                }
                is Result.Error -> if (gestosDePantalla.get() == epoch && secureStorage.getVenueId() == venue &&
                    secureStorage.getStaffId() == staff && secureStorage.getToken() == token)
                    _state.value = ShiftState.Error(translateError(result.exception))
            }
        }
    }

    fun loadLocalHistory(offset: Int = 0) {
        val venue = secureStorage.getVenueId() ?: return
        val staff = secureStorage.getStaffId(); val token = secureStorage.getToken(); val epoch = gestosDePantalla.get()
        fun vigente() = gestosDePantalla.get() == epoch && secureStorage.getVenueId() == venue &&
            secureStorage.getStaffId() == staff && secureStorage.getToken() == token
        viewModelScope.launch {
            try {
                if (!permissionsRepository.hasPermission("shifts:read") || !vigente()) return@launch
                val page = shiftRepository.sesionesLocales(venue, offset)
                if (vigente()) _localHistory.value = page
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { Timber.w(error, "No se pudo leer el historial local de Caja") }
        }
    }

    fun reviewLocalCaja(sessionId: String, offset: Int = 0) {
        if (_localReview.value?.let { it.busy && it.sessionId == sessionId } == true) return
        val venue = secureStorage.getVenueId() ?: return
        val staff = secureStorage.getStaffId(); val token = secureStorage.getToken(); val epoch = gestosDePantalla.get()
        val owner = LocalCajaReview(sessionId, busy = true)
        _localReview.value = owner
        fun vigente() = _localReview.value === owner && gestosDePantalla.get() == epoch &&
            secureStorage.getVenueId() == venue && secureStorage.getStaffId() == staff && secureStorage.getToken() == token
        viewModelScope.launch {
            try {
                if (!permissionsRepository.hasPermission("shifts:read")) {
                    if (vigente()) _localReview.value = owner.copy(busy = false, error = "No tienes permiso para consultar esta caja.")
                    return@launch
                }
                if (!vigente()) return@launch
                val page = shiftRepository.obligacionesDelCierreLocal(venue, sessionId, offset)
                val cash = shiftRepository.efectivoRevisableDelCierre(venue, page)
                if (vigente()) _localReview.value = owner.copy(page = page, cashEligible = cash, busy = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { if (vigente()) _localReview.value = owner.copy(busy = false,
                  error = "No se pudo leer la lista. El cierre sigue esperando sus registros.") }
        }
    }

    fun acknowledgeLocalCash(id: Long) {
        val current = _localReview.value ?: return
        if (current.busy || id !in current.cashEligible) return
        val venue = secureStorage.getVenueId() ?: return
        val staff = secureStorage.getStaffId() ?: return
        val token = secureStorage.getToken(); val epoch = gestosDePantalla.get()
        val owner = current.copy(busy = true, error = null); _localReview.value = owner
        fun vigente() = _localReview.value === owner && gestosDePantalla.get() == epoch &&
            secureStorage.getVenueId() == venue && secureStorage.getStaffId() == staff && secureStorage.getToken() == token
        viewModelScope.launch {
            try {
                if (!permissionsRepository.hasPermission("tpv-shifts:close")) {
                    if (vigente()) _localReview.value = current.copy(error = "No tienes permiso para revisar el efectivo de esta caja.")
                    return@launch
                }
                if (!vigente()) return@launch
                val changed = shiftRepository.reconocerPagoEnCierreLocal(current.sessionId, id, venue, staff, token) {
                    vigente() && PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, "tpv-shifts:close")
                }
                if (!vigente()) return@launch
                if (changed == 1) {
                    _localReview.value = current
                    reviewLocalCaja(current.sessionId, current.page?.offset ?: 0)
                }
                else _localReview.value = current.copy(error = "No se pudo marcar el efectivo como revisado. Actualiza y vuelve a intentar.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { if (vigente()) _localReview.value = current.copy(error = "No se pudo guardar la revisión del efectivo.") }
        }
    }

    /** Consulta la K elegida de Room; nunca inicia otro QR ni busca una operación por monto. */
    fun queryLocalCrypto(key: String) {
        val current = _localReview.value ?: return
        if (current.busy || current.page?.rows?.none { it.owner == "CRYPTO" && it.ownerId == key } != false) return
        val venue = secureStorage.getVenueId() ?: return
        val staff = secureStorage.getStaffId(); val token = secureStorage.getToken(); val epoch = gestosDePantalla.get()
        val owner = current.copy(busy = true, error = null); _localReview.value = owner
        fun vigente() = _localReview.value === owner && gestosDePantalla.get() == epoch &&
            secureStorage.getVenueId() == venue && secureStorage.getStaffId() == staff && secureStorage.getToken() == token
        viewModelScope.launch {
            try {
                if (!permissionsRepository.hasPermission("payments:create")) {
                    if (vigente()) _localReview.value = current.copy(error = "No tienes permiso para consultar esta operación.")
                    return@launch
                }
                if (!vigente()) return@launch
                val result = shiftRepository.consultarCriptoDurable(venue, key)
                if (vigente()) _localReview.value = current.copy(error = if (result.operation?.financialRecorded == true &&
                    result.operation?.financialStatus == "COMPLETED") "Registro confirmado para esta operación. Actualiza la lista."
                    else "Operación consultada; el registro financiero todavía no está confirmado. No vuelvas a cobrarla.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { if (vigente()) _localReview.value = current.copy(error = "No se pudo confirmar esta operación. Su clave se conserva; vuelve a consultar.") }
        }
    }

    fun dismissLocalReview() { _localReview.value = null }

    fun loadPaymentPage(offset: Int) {
        val actual = _state.value as? ShiftState.CloseBlockedByPayments ?: return
        if (actual.busy) return
        val contexto = contextoCierre ?: return
        viewModelScope.launch { cargarPaginaDePagos(contexto, actual, offset) }
    }

    fun onPaymentRetryFailed() {
        val actual = _state.value as? ShiftState.CloseBlockedByPayments ?: return
        _state.value = actual.copy(error = "No se pudo iniciar la subida. La venta sigue guardada; vuelve a intentar.")
    }

    private suspend fun cargarPaginaDePagos(contexto: ContextoCierre, anterior: ShiftState.CloseBlockedByPayments, offset: Int) {
        if (!puedePublicar(contexto, anterior)) return
        // StateFlow conserva la instancia anterior al asignar una copia igual. La guarda
        // por identidad debe usar esa misma instancia si «Revisado» ya puso busy=true.
        val cargando = if (anterior.busy && anterior.error == null) anterior else anterior.copy(busy = true, error = null)
        _state.value = cargando
        try {
            val pagina = shiftRepository.pagosQueBloqueanCierre(contexto.venueId, offset)
            if (!puedePublicar(contexto, cargando)) return
            _state.value = if (pagina.total == 0) ShiftState.ShiftActive(anterior.shift, anterior.shiftHistory)
                else anterior.copy(page = pagina, busy = false, error = null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (puedePublicar(contexto, cargando)) {
                _state.value = anterior.copy(busy = false, error = "No se pudo actualizar la lista. La caja sigue esperando. Vuelve a intentar.")
            }
        }
    }

    fun acknowledgePayment(id: Long) {
        val actual = _state.value as? ShiftState.CloseBlockedByPayments ?: return
        val contexto = contextoCierre ?: return
        if (actual.busy) return
        val pagina = actual.page ?: return
        val pago = pagina.pagos.firstOrNull { it.id == id } ?: return
        if (pago.syncStatus in listOf("PENDING", "SYNCING") || (pago.syncStatus != "FAILED" && !pago.requiereRevision)) return
        viewModelScope.launch {
            if (!puedePublicar(contexto, actual)) return@launch
            val quien = contexto.staffId
            if (quien.isNullOrBlank() || !_canCloseShift.value) {
                _state.value = actual.copy(error = "Inicia sesión con permiso para cerrar la caja antes de marcar Revisado.")
                return@launch
            }
            val revisando = actual.copy(busy = true, error = null)
            _state.value = revisando
            try {
                val filas = shiftRepository.reconocerPago(id, contexto.venueId, quien, contexto.token) {
                    mismaPantalla(contexto, revisando) && mismaIdentidad(contexto) && _canCloseShift.value
                }
                if (!puedePublicar(contexto, revisando)) return@launch
                if (filas != 1) {
                    _state.value = actual.copy(error = "No se pudo marcar la venta como Revisado. Actualiza la lista y vuelve a intentar.")
                    return@launch
                }
                cargarPaginaDePagos(contexto, revisando, pagina.offset)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (puedePublicar(contexto, revisando)) {
                    _state.value = actual.copy(error = "No se pudo marcar la venta como Revisado. Vuelve a intentar.")
                }
            }
        }
    }

    fun acknowledgeClosedShift() {
        if (_state.value is ShiftState.ShiftClosed) {
            acknowledgedLocalSessionId = (_state.value as ShiftState.ShiftClosed).shift.localShiftSessionId
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
                400 -> "Ya existe una caja abierta.\n\nCierra la caja actual antes de abrir una nueva."
                403 -> "No tienes permiso para esta acción.\n\nContacta a tu administrador."
                404 -> "No se encontró la caja abierta.\n\nEs posible que ya haya sido cerrada."
                409 -> "La caja se está cerrando en otra terminal.\n\nEspera unos segundos y actualiza."
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
     * 💸 El cierre está BLOQUEADO por devoluciones que el SDK ya aprobó y que no están registradas
     * en el servidor (o que el servidor rechazó y nadie ha reconocido). Fase 2, Task 9.
     *
     * @param refunds lo que bloquea, en el orden en que se encoló
     */
    data class CloseBlockedByRefunds(
        val shift: Shift,
        val shiftHistory: List<Shift> = emptyList(),
        val refunds: List<QueuedRefund>,
    ) : ShiftState()

    data class CloseBlockedByPayments(
        val shift: Shift,
        val shiftHistory: List<Shift>,
        val venueId: String,
        val page: PaginaPagosDelCierre? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : ShiftState()

    /**
     * Error state with user-friendly message
     *
     * @param message Translated error message for user display
     */
    data class Error(val message: String, val canOpenLocally: Boolean = false) : ShiftState()
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

/**
 * ¿Hay turno para vender? Lo confirma el servidor; y SIN conexión, el último turno ABIERTO que este
 * aparato confirmó (se guarda en cada consulta buena y se borra cuando el servidor dice «sin turno»).
 * Con conexión la caché nunca sustituye al servidor, y sin nada guardado no se inventa un turno.
 */
fun turnoPermiteVender(turno: Shift?, sinConexion: Boolean, ultimoConocido: CachedShiftInfo?): Boolean =
    if (turno != null) turno.status == com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.OPEN
    else sinConexion && ultimoConocido?.isOpen == true
