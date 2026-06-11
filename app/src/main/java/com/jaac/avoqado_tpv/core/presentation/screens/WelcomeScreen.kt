package com.jaac.avoqado_tpv.core.presentation.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.domain.events.VenueStatusEvent
import com.jaac.avoqado_tpv.core.presentation.components.ActionButton
import com.jaac.avoqado_tpv.core.presentation.components.StaticActionButtonGrid
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoDialog
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.SalesGoalsPager
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.SettingsBottomSheet
import com.jaac.avoqado_tpv.core.presentation.components.ShiftStatusBanner
import com.jaac.avoqado_tpv.core.presentation.components.VenueStatusBanner
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.permissions.di.PermissionsEntryPoint
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanFeatureCatalog
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import com.jaac.avoqado_tpv.features.plan.domain.model.allowsFeature
import com.jaac.avoqado_tpv.features.remote_command.presentation.LockScreenOverlay
import com.jaac.avoqado_tpv.features.remote_command.presentation.MaintenanceOverlay
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.jaac.avoqado_tpv.features.shift.presentation.CachedShiftInfo
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleSalesGoal
import com.jaac.avoqado_tpv.core.presentation.components.TimeclockStatusCard
import com.jaac.avoqado_tpv.features.messaging.presentation.TpvMessageUiModel
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal

/**
 * Home Screen (Welcome Screen)
 *
 * Main dashboard after successful login.
 * Shows personalized greeting and action button grid for quick access to features.
 *
 * Features:
 * - Personalized greeting: "Hola, [Staff Name]"
 * - Shift status banner (tap to navigate to Shifts screen)
 * - 3-column grid of action buttons (enabled and future placeholders)
 * - Settings modal for user management (logout, config, help)
 *
 * @param modifier Modifier for customization
 * @param onStartPaymentWithAmount Navigate to payment with amount (opens rating screen)
 * @param onNavigateToShifts Navigate to Shifts screen
 * @param onLogout Logout callback
 * @param viewModel HomeViewModel for staff info and logout logic
 * @param shiftViewModel ShiftViewModel for shift status
 */
@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    onStartPaymentWithAmount: (String) -> Unit = {},  // ✅ Keep modal for first-time flow
    onNavigateToShifts: () -> Unit = {},
    onNavigateToOrdering: () -> Unit = {},
    onNavigateToCheckout: () -> Unit = {},  // 🆕 Unified Checkout (Cobrar) — additive rollout
    onNavigateToReports: () -> Unit = {},
    onNavigateToPayments: () -> Unit = {},  // ⭐ NEW: Navigate to Payments screen
    onNavigateToSupport: () -> Unit = {},  // ⭐ NEW: Navigate to Support screen
    onNavigateToSettings: () -> Unit = {},  // ⚙️ Navigate to Settings screen
    onNavigateToSelfUpdate: () -> Unit = {},  // 🔄 Navigate to Self-Update screen
    onNavigateToSuperAdmin: () -> Unit = {},
    onNavigateToSerializedSale: () -> Unit = {},  // 📱 Telecom: Vender flow (barcode → price → payment)
    onNavigateToInventoryRegister: () -> Unit = {},  // 📦 Telecom: Alta de productos flow
    onNavigateToMySales: () -> Unit = {},  // 📊 Telecom: My sales history
    onNavigateToMisSims: () -> Unit = {},  // 📱 Telecom: SIM custody inbox
    onNavigateToTimeclock: () -> Unit = {},  // ⏱ Navigate to TimeclockScreen from WelcomeScreen
    onNavigateToTimeclockForClockOut: () -> Unit = {},  // ⏱ Navigate to TimeclockScreen for clock-out
    onNavigateToMessages: () -> Unit = {},  // 📨 Navigate to Messages screen
    onNavigateToTrainings: () -> Unit = {},  // 🎓 Navigate to Trainings screen
    onNavigateToPendingVerifications: () -> Unit = {},  // 📸 Navigate to Pending Verifications
    onRefreshConnection: () -> Unit = {},
    onLogout: () -> Unit = {},
    isDarkMode: Boolean = false,
    onDarkModeToggle: () -> Unit = {},
    messageHistory: List<TpvMessageUiModel> = emptyList(),
    isLoadingMessageHistory: Boolean = false,
    onMessageClick: (TpvMessageUiModel) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    shiftViewModel: com.jaac.avoqado_tpv.features.shift.presentation.ShiftViewModel = hiltViewModel()
) {
    // Staff info from ViewModel
    val staffName by viewModel.staffName.collectAsStateWithLifecycle()
    val clockInTime by viewModel.clockInTime.collectAsStateWithLifecycle()

    // Shift state from ShiftViewModel
    val shiftState by shiftViewModel.state.collectAsStateWithLifecycle()
    val isShiftSystemEnabled by shiftViewModel.isShiftSystemEnabled.collectAsStateWithLifecycle()
    val currentShift = when (val state = shiftState) {
        is com.jaac.avoqado_tpv.features.shift.presentation.ShiftState.ShiftActive -> state.shift
        else -> null
    }

    // Offline state for shift status banner (Square/Toast prevention pattern)
    val isOffline by shiftViewModel.isOffline.collectAsStateWithLifecycle()
    val cachedShiftInfo by shiftViewModel.cachedShiftInfo.collectAsStateWithLifecycle()

    // Initial loading state (shows overlay during post-login sync)
    val isInitialLoading by shiftViewModel.isInitialLoading.collectAsStateWithLifecycle()

    // 📸 Pending verifications count for non-blocking proof-of-sale
    val pendingVerificationsCount by viewModel.pendingVerificationsCount.collectAsStateWithLifecycle()

    // ═══════════════════════════════════════════════════════════════════════════
    // REMOTE COMMAND STATE (Lock & Maintenance)
    // ═══════════════════════════════════════════════════════════════════════════
    val isLocked by viewModel.lockScreenManager.isLocked.collectAsStateWithLifecycle()
    val lockReason by viewModel.lockScreenManager.lockReason.collectAsStateWithLifecycle()
    val lockMessage by viewModel.lockScreenManager.lockMessage.collectAsStateWithLifecycle()
    val lockedBy by viewModel.lockScreenManager.lockedBy.collectAsStateWithLifecycle()

    val isInMaintenance by viewModel.maintenanceManager.isInMaintenance.collectAsStateWithLifecycle()
    val maintenanceReason by viewModel.maintenanceManager.maintenanceReason.collectAsStateWithLifecycle()
    val maintenanceInitiatedBy by viewModel.maintenanceManager.initiatedBy.collectAsStateWithLifecycle()

    // ═══════════════════════════════════════════════════════════════════════════
    // BLUMON SDK INITIALIZATION STATE
    // ═══════════════════════════════════════════════════════════════════════════
    val isBlumonInitializing by viewModel.isBlumonInitializing.collectAsStateWithLifecycle()
    val isBlumonReady by viewModel.isBlumonReady.collectAsStateWithLifecycle()
    val blumonInitError by viewModel.blumonInitError.collectAsStateWithLifecycle()
    val blumonInitElapsedSeconds by viewModel.blumonInitElapsedSeconds.collectAsStateWithLifecycle()

    // ═══════════════════════════════════════════════════════════════════════════
    // VENUE STATUS (for mid-session detection)
    // ═══════════════════════════════════════════════════════════════════════════
    val venueStatus by viewModel.venueStatus.collectAsStateWithLifecycle()

    // State for venue status dialogs
    var showVenueSuspendedDialog by remember { mutableStateOf(false) }
    var showVenueClosedDialog by remember { mutableStateOf(false) }

    // Sales goals from HomeViewModel (for progress bar display)
    val salesGoals by viewModel.salesGoals.collectAsStateWithLifecycle()

    // ⏱ Attendance state from HomeViewModel (for timeclock card + button gating)
    val currentTimeEntry by viewModel.currentTimeEntry.collectAsStateWithLifecycle()
    val requireClockInToLogin by viewModel.requireClockInToLogin.collectAsStateWithLifecycle()
    val isAttendanceLoading by viewModel.isAttendanceLoading.collectAsStateWithLifecycle()

    // Pull-to-refresh state (combined from both ViewModels)
    val homeIsRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val shiftIsRefreshing by shiftViewModel.isRefreshing.collectAsStateWithLifecycle()
    val isRefreshing = homeIsRefreshing || shiftIsRefreshing

    // [PERF] Composition tracking
    LaunchedEffect(Unit) {
        timber.log.Timber.d("[PERF] WelcomeScreen COMPOSED at ${android.os.SystemClock.elapsedRealtime()}ms")
    }

    // Observe venue status events
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.venueStatusEvents.collect { event ->
            when (event) {
                is VenueStatusEvent.VenueSuspended -> {
                    showVenueSuspendedDialog = true
                }
                is VenueStatusEvent.VenueClosed -> {
                    showVenueClosedDialog = true
                }
                is VenueStatusEvent.VenueActivated -> {
                    // Could show a success toast/snackbar
                }
                is VenueStatusEvent.StatusChanged -> {
                    // Status changed but still operational - no action needed
                }
            }
        }
    }

    // Refresh sales goals + attendance when WelcomeScreen resumes
    // (e.g., after completing a payment or returning from TimeclockScreen).
    // Skip first resume since HomeViewModel.init{} already fetches on startup.
    // NOTE: refreshPendingVerifications() runs on EVERY resume (including first)
    // because the composable may be re-created on navigation back, resetting isFirstResume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var isFirstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Always refresh pending verifications (lightweight, no delay needed)
                viewModel.refreshPendingVerifications()
                if (isFirstResume) {
                    isFirstResume = false
                } else {
                    viewModel.refreshSalesGoal()
                    viewModel.refreshAttendance()
                    shiftViewModel.loadCurrentShift()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Main content
    WelcomeScreenContent(
        modifier = modifier,
        staffName = staffName,
        clockInTime = clockInTime,
        currentShift = currentShift,
        isShiftSystemEnabled = isShiftSystemEnabled, // Pass setting
        isOffline = isOffline,
        cachedShiftInfo = cachedShiftInfo,
        venueStatus = venueStatus,  // 📊 Pass venue status for banner
        salesGoals = salesGoals,  // 🎯 Pass sales goals for progress pager
        isRefreshing = isRefreshing,
        onRefresh = {
            viewModel.refreshDashboard()
            shiftViewModel.refresh()
            onRefreshConnection()
        },
        onStartPaymentWithAmount = onStartPaymentWithAmount,  // ✅ Modal flow for first-time
        onNavigateToShifts = onNavigateToShifts,
        onNavigateToOrdering = onNavigateToOrdering,
        onNavigateToCheckout = onNavigateToCheckout,  // 🆕 Pass Checkout navigation
        onNavigateToReports = onNavigateToReports,
        onNavigateToPayments = onNavigateToPayments,  // ⭐ NEW: Pass payments navigation
        onNavigateToSupport = onNavigateToSupport,  // ⭐ NEW: Pass support navigation
        onNavigateToSettings = onNavigateToSettings,  // ⚙️ Pass settings navigation
        onNavigateToSelfUpdate = onNavigateToSelfUpdate,  // 🔄 Pass self-update navigation
        onNavigateToSuperAdmin = onNavigateToSuperAdmin,
        onNavigateToSerializedSale = onNavigateToSerializedSale,  // 📱 Telecom: Vender
        onNavigateToInventoryRegister = onNavigateToInventoryRegister,  // 📦 Telecom: Alta
        onNavigateToMySales = onNavigateToMySales,  // 📊 Telecom: Mis Ventas
        onNavigateToMisSims = onNavigateToMisSims,  // 📱 Telecom: Mis SIMs
        currentTimeEntry = currentTimeEntry,
        requireClockInToLogin = requireClockInToLogin,
        isAttendanceLoading = isAttendanceLoading,
        onNavigateToTimeclock = onNavigateToTimeclock,
        onNavigateToTimeclockForClockOut = onNavigateToTimeclockForClockOut,
        onNavigateToMessages = onNavigateToMessages,
        onNavigateToTrainings = onNavigateToTrainings,
        pendingVerificationsCount = pendingVerificationsCount,
        onNavigateToPendingVerifications = onNavigateToPendingVerifications,
        messageHistory = messageHistory,
        isLoadingMessageHistory = isLoadingMessageHistory,
        onMessageClick = onMessageClick,
        onLogout = {
            viewModel.logout()
            onLogout()
        },
        isDarkMode = isDarkMode,
        onDarkModeToggle = onDarkModeToggle
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // BLUMON SDK INITIALIZATION OVERLAY
    // Shows loader while SDK initializes, blocks user from proceeding to payment
    // Progressive warnings: 0-15s normal, 15-30s slow hint, 30s+ warning + cancel
    // ═══════════════════════════════════════════════════════════════════════════
    val shouldBlockForBlumon = isBlumonInitializing ||
        blumonInitError != null ||
        (BuildConfig.ENABLE_PAX_SDK && !isBlumonReady)

    if (shouldBlockForBlumon) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* Block clicks while SDK is not ready */ },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(0.85f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isBlumonInitializing || blumonInitError == null) {
                        // Loading state with progressive warnings
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Preparando sistema de pagos...",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )

                        // Progressive message based on elapsed time
                        val subtitleText = when {
                            blumonInitElapsedSeconds >= 30 ->
                                "Esto está tomando más de lo normal. Verifica tu conexión a internet."
                            blumonInitElapsedSeconds >= 15 ->
                                "Esto puede tardar un poco en conexiones lentas"
                            else ->
                                "Espere un momento mientras se configura el terminal"
                        }
                        val subtitleColor = if (blumonInitElapsedSeconds >= 30)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant

                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtitleColor,
                            textAlign = TextAlign.Center
                        )

                        // Cancel button after 30s
                        if (blumonInitElapsedSeconds >= 30) {
                            OutlinedButton(
                                onClick = { viewModel.cancelBlumonInit() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Cancelar")
                            }
                        }
                    } else if (blumonInitError != null) {
                        // Error state
                        Text(
                            text = "Error de Inicialización",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = blumonInitError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { viewModel.retryBlumonInit() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reintentar")
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIAL SYNC LOADING OVERLAY
    // Shows during post-login sync to prevent user from seeing intermediate states
    // ═══════════════════════════════════════════════════════════════════════════
    if (isInitialLoading) {
        AvoqadoLoadingOverlay(message = "Sincronizando terminal...")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTE: Lock and Maintenance overlays moved to MainActivity (root level)
    // This ensures they cover ALL screens, not just WelcomeScreen
    // See: MainActivity.kt setContent {} block
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // VENUE STATUS DIALOGS
    // Shown when venue becomes suspended or closed mid-session
    // ═══════════════════════════════════════════════════════════════════════════

    // Venue Suspended Dialog
    if (showVenueSuspendedDialog) {
        AvoqadoDialog(
            title = "Establecimiento Suspendido",
            message = "Este establecimiento ha sido suspendido.\n\n" +
                "No es posible continuar operando hasta que se resuelva la situación.\n\n" +
                "Contacta al administrador para más información.",
            icon = Icons.Default.Warning,
            confirmText = "Entendido",
            onDismiss = { },  // Cannot dismiss
            onConfirm = {
                showVenueSuspendedDialog = false
                viewModel.forceLogoutDueToVenueStatus()
                onLogout()
            }
        )
    }

    // Venue Closed Dialog
    if (showVenueClosedDialog) {
        AvoqadoDialog(
            title = "Establecimiento Cerrado",
            message = "Este establecimiento ha sido cerrado permanentemente.\n\n" +
                "No es posible continuar operando.\n\n" +
                "Contacta al administrador para más información.",
            icon = Icons.Default.Block,
            confirmText = "Entendido",
            onDismiss = { },  // Cannot dismiss
            onConfirm = {
                showVenueClosedDialog = false
                viewModel.forceLogoutDueToVenueStatus()
                onLogout()
            }
        )
    }
}

/**
 * Welcome Screen Content
 *
 * Stateless UI component that can be previewed without ViewModel.
 *
 * @param modifier Modifier for customization
 * @param staffName Staff member's name for greeting
 * @param clockInTime Clock-in time or null if not clocked in
 * @param currentShift Current active shift (null if no shift open)
 * @param isOffline Whether device is offline
 * @param cachedShiftInfo Cached shift info for offline display
 * @param onStartPaymentWithAmount Navigate to payment with amount
 * @param onNavigateToShifts Navigate to Shifts screen
 * @param onLogout Logout callback
 */
@Composable
private fun WelcomeScreenContent(
    modifier: Modifier = Modifier,
    staffName: String,
    clockInTime: String?,
    currentShift: Shift?,
    isShiftSystemEnabled: Boolean = true, // Default true
    isOffline: Boolean = false,
    cachedShiftInfo: CachedShiftInfo? = null,
    venueStatus: VenueStatus = VenueStatus.ACTIVE,  // 📊 Venue status for banner
    salesGoals: List<ModuleSalesGoal> = emptyList(),  // 🎯 Sales goals from backend
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onStartPaymentWithAmount: (String) -> Unit,  // ✅ Modal flow (first-time)
    onNavigateToShifts: () -> Unit,
    onNavigateToOrdering: () -> Unit,
    onNavigateToCheckout: () -> Unit = {},  // 🆕 Unified Checkout (Cobrar)
    onNavigateToReports: () -> Unit,
    onNavigateToPayments: () -> Unit,  // ⭐ NEW: Navigate to Payments screen
    onNavigateToSupport: () -> Unit,  // ⭐ NEW: Navigate to Support screen
    onNavigateToSettings: () -> Unit,  // ⚙️ Navigate to Settings screen
    onNavigateToSelfUpdate: () -> Unit = {},  // 🔄 Navigate to Self-Update screen
    onNavigateToSuperAdmin: () -> Unit,
    onNavigateToSerializedSale: () -> Unit = {},  // 📱 Telecom: Vender flow
    onNavigateToInventoryRegister: () -> Unit = {},  // 📦 Telecom: Alta de productos
    onNavigateToMySales: () -> Unit = {},  // 📊 Telecom: My sales history
    onNavigateToMisSims: () -> Unit = {},  // 📱 Telecom: SIM custody inbox
    currentTimeEntry: TimeEntry? = null,  // ⏱ Current attendance entry
    requireClockInToLogin: Boolean = false,  // ⏱ Whether clock-in is required
    isAttendanceLoading: Boolean = false,  // ⏱ Loading state for attendance
    onNavigateToTimeclock: () -> Unit = {},  // ⏱ Navigate to TimeclockScreen for clock-in
    onNavigateToTimeclockForClockOut: () -> Unit = {},  // ⏱ Navigate for clock-out
    onNavigateToMessages: () -> Unit = {},  // 📨 Navigate to Messages screen
    onNavigateToTrainings: () -> Unit = {},  // 🎓 Navigate to Trainings screen
    pendingVerificationsCount: Int = 0,  // 📸 Pending proof-of-sale verifications
    onNavigateToPendingVerifications: () -> Unit = {},  // 📸 Navigate to pending verifications
    messageHistory: List<TpvMessageUiModel> = emptyList(),  // 📨 Message inbox history
    isLoadingMessageHistory: Boolean = false,  // 📨 Loading state for history
    onMessageClick: (TpvMessageUiModel) -> Unit = {},  // 📨 Open message dialog
    onLogout: () -> Unit,
    isDarkMode: Boolean = false,
    onDarkModeToggle: () -> Unit = {},
) {
    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    // 🔐 Get current user's role from SecureStorage for authorization checks
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    val currentUserRole = remember { secureStorage.getRole() }

    // 🔐 Get permissions repository, kiosk mode manager, and modules repository via EntryPoint
    val hiltEntryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PermissionsEntryPoint::class.java
        )
    }
    val permissionsRepository = remember { hiltEntryPoint.permissionsRepository() }
    val kioskModeManager = remember { hiltEntryPoint.kioskModeManager() }
    val modulesRepository = remember { hiltEntryPoint.modulesRepository() }
    val tpvSettingsRepository = remember { hiltEntryPoint.tpvSettingsRepository() }
    val planManager = remember { hiltEntryPoint.planManager() }

    // 👑 Plan-tier gating (fail open — null plan info ⇒ nothing is locked).
    // SERIALIZED_INVENTORY product registration ("Alta de Productos") requires
    // Plan Premium. Exempt venues (grandfathered legacy like PlayTelecom,
    // demo) are never gated — allowsFeature() returns true for them.
    val planInfo by planManager.planInfo.collectAsStateWithLifecycle()
    val serializedInventoryPlanLocked =
        !planInfo.allowsFeature(PlanFeatureCatalog.SERIALIZED_INVENTORY)

    // 📱 Check for simplified order flow (telecom/serialized inventory mode)
    // Use StateFlow so changes (e.g., on logout) trigger recomposition
    val currentModules by modulesRepository.modules.collectAsStateWithLifecycle()
    val serializedInventoryModule = currentModules
        .find { it.moduleCode == ModulesRepository.MODULE_SERIALIZED_INVENTORY }
    val serializedInventoryConfig = serializedInventoryModule?.config
    val isSimplifiedMode = serializedInventoryConfig?.ui?.simplifiedOrderFlow == true
    // Plan §3.2 — "Mis SIMs" tile only for venues with SERIALIZED_INVENTORY enabled.
    val isSerializedInventoryMode = serializedInventoryModule?.active == true

    // 📦 Check if user has serialized inventory permissions
    var hasInventoryRegisterPermission by remember { mutableStateOf(false) }
    var hasInventorySellPermission by remember { mutableStateOf(false) }
    // Distinct from `:sell` — the Mis SIMs inbox is about accepting/rejecting
    // custody, not about selling. Gate with the canonical custody permission
    // so non-promoter roles that happen to have :sell don't see the tile.
    var hasSimCustodyAcceptPermission by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = permissionsRepository.getPermissions(forceRefresh = false)
        val permissions = result.getOrNull()
        hasInventoryRegisterPermission = permissions?.contains("serialized-inventory:create") ?: false
        hasInventorySellPermission = permissions?.contains("serialized-inventory:sell") ?: false
        hasSimCustodyAcceptPermission = permissions?.contains("tpv-sim-custody:accept") ?: false
    }

    // 🥝 Kiosk mode state
    val isKioskModeEnabled by kioskModeManager.isKioskMode.collectAsStateWithLifecycle()

    // ⚙️ TPV Settings (for kioskModeAvailable check)
    val tpvSettings by tpvSettingsRepository.settings.collectAsStateWithLifecycle()

    // 🔐 Check if user has permission to access Settings (and Kiosk Mode)
    var hasSettingsAccess by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = permissionsRepository.getPermissions(forceRefresh = true) // Force refresh to ensure fresh permissions
        val permissions = result.getOrNull()
        hasSettingsAccess = permissions?.contains("tpv-terminal:settings") ?: false
        timber.log.Timber.i("🔐 [Settings] User role: $currentUserRole, hasSettingsAccess: $hasSettingsAccess, permissions count: ${permissions?.size ?: 0}")
        if (!hasSettingsAccess && permissions != null) {
            timber.log.Timber.w("🔐 [Settings] Missing tpv-terminal:settings. TPV permissions: ${permissions.filter { it.startsWith("tpv") }}")
        }
    }

    // Modal states
    var showAmountBottomSheet by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }

    // ══════════════════════════════════════════════════════════════════════
    // ACTION BUTTONS CONFIGURATION
    // ══════════════════════════════════════════════════════════════════════

    // ⭐ Check if shift is open for payment processing (Square/Toast pattern)
    val hasOpenShift = currentShift?.status == ShiftStatus.OPEN
    val canOperate = hasOpenShift || !isShiftSystemEnabled // Unlock if disabled

    // ⏱ Attendance gating: require clock-in before operational buttons
    val isClockedIn = currentTimeEntry?.status == TimeEntryStatus.CLOCKED_IN
    val canWork = isClockedIn || !requireClockInToLogin

    // 📱 Get module labels for simplified mode
    val moduleLabels = serializedInventoryConfig?.labels

    // Build action buttons based on mode (simplified for telecom vs normal)
    val actionButtons = if (isSimplifiedMode) {
        // ════════════════════════════════════════════════════════════════════
        // 📱 SIMPLIFIED MODE (Telecom/Serialized Inventory)
        // Only shows: "Vender" + "Alta de Productos" (if permission) + Soporte
        // ════════════════════════════════════════════════════════════════════
        timber.log.Timber.d("📱 WelcomeScreen: Simplified mode enabled, hasInventoryPermission=$hasInventoryRegisterPermission")
        mutableListOf<ActionButton>().apply {
            // Primary action: Vender (barcode → price → payment)
            add(
                ActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Vender",
                    enabled = canWork,
                    badge = if (!canWork) "Registra tu entrada" else null,
                    onClick = onNavigateToSerializedSale
                )
            )

            // Secondary action: Alta de Productos (inventory registration)
            // Only shown if user has serialized-inventory:register permission.
            // 👑 Plan gate: SERIALIZED_INVENTORY requires Plan Premium — the
            // tile stays VISIBLE as a teaser (disabled + "Plan Premium" badge,
            // same pattern as other disabled tiles). Exempt venues
            // (PlayTelecom/demo) are never locked. Fail open: no plan info ⇒
            // not locked, behaves as today.
            if (hasInventoryRegisterPermission) {
                add(
                    ActionButton(
                        icon = Icons.Default.Inventory2,
                        label = moduleLabels?.register ?: "Alta de Productos",
                        enabled = canWork && !serializedInventoryPlanLocked,
                        badge = when {
                            serializedInventoryPlanLocked ->
                                "Plan ${PlanTier.PREMIUM.displayName}"
                            !canWork -> "Registra tu entrada"
                            else -> null
                        },
                        onClick = onNavigateToInventoryRegister
                    )
                )
            }

            // 📊 Mis Ventas — promoter's sales history
            // Shown if user has serialized-inventory:sell permission
            if (hasInventorySellPermission) {
                add(
                    ActionButton(
                        icon = Icons.Default.Receipt,
                        label = "Mis Ventas",
                        enabled = true,
                        onClick = onNavigateToMySales
                    )
                )
            }

            // 📱 Mis SIMs — promoter's SIM custody inbox (plan §3)
            // Asana explicitly states: "En la TPV, el PROMOTOR hace click en
            // una nueva sección de Mis SIMs". The tile is WAITER-only.
            // Gated by:
            //   1. SERIALIZED_INVENTORY module enabled for the venue
            //   2. Staff role is WAITER (the Promotor in PlayTelecom)
            //   3. `tpv-sim-custody:accept` permission (defense in depth)
            //
            // Non-WAITER staff (MANAGER Supervisor, CASHIER, etc.) never see
            // the tile even if they have the permission — they operate from
            // the Dashboard web, not TPV. Edge case: a Supervisor that must
            // temporarily sell can still accept via the deep-link from
            // "Vender SIM" when the backend returns SIM_NOT_ACCEPTED.
            if (
                isSerializedInventoryMode &&
                currentUserRole == StaffRole.WAITER &&
                hasSimCustodyAcceptPermission
            ) {
                add(
                    ActionButton(
                        icon = Icons.Default.SimCard,
                        label = "Mis SIMs",
                        enabled = true,
                        onClick = onNavigateToMisSims
                    )
                )
            }

            // 📨 Mensajes — navigates to full Messages screen (controlled by tpvSettings)
            if (tpvSettings.showMessages) {
                val pendingMessageCount = messageHistory.count {
                    it.deliveryStatus == "PENDING" || it.deliveryStatus == "DELIVERED"
                }
                add(
                    ActionButton(
                        icon = Icons.Default.Email,
                        label = "Mensajes",
                        enabled = true,
                        badge = if (pendingMessageCount > 0) "$pendingMessageCount" else null,
                        onClick = onNavigateToMessages
                    )
                )
            }

            // 🎓 Entrenamientos — step-by-step training modules (controlled by tpvSettings)
            if (tpvSettings.showTrainings) {
                add(
                    ActionButton(
                        icon = Icons.Default.School,
                        label = "Entrenamientos",
                        enabled = true,
                        onClick = onNavigateToTrainings
                    )
                )
            }

            // Support always available
            add(
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Help,
                    label = "Soporte",
                    enabled = true,
                    onClick = onNavigateToSupport
                )
            )

            // 🔐 SuperAdmin button — always visible for SUPERADMIN role, regardless of mode
            if (currentUserRole == StaffRole.SUPERADMIN) {
                add(
                    ActionButton(
                        icon = Icons.Default.AdminPanelSettings,
                        label = "SuperAdmin",
                        enabled = true,
                        onClick = onNavigateToSuperAdmin
                    )
                )
            }
        }
    } else {
        // ════════════════════════════════════════════════════════════════════
        // 📦 NORMAL MODE (Restaurant/Retail)
        // Full feature set: Pago rápido, Órdenes, Reportes, Turnos, Pagos, Soporte
        // Visibility of "Pago rápido" and "Órdenes" controlled by tpvSettings
        // ════════════════════════════════════════════════════════════════════
        val allButtons = mutableListOf<ActionButton>()

        // ✅ "Pago rápido" - controlled by tpvSettings.showQuickPayment
        if (tpvSettings.showQuickPayment) {
            val quickPayEnabled = canOperate && canWork
            val quickPayBadge = when {
                !canWork -> "Registra tu entrada"
                !canOperate -> "Abre el turno primero"
                else -> null
            }
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.CreditCard,
                    label = "Pago rápido",
                    enabled = quickPayEnabled,
                    badge = quickPayBadge,
                    onClick = { showAmountBottomSheet = true }
                )
            )
        }

        // ✅ "Órdenes" - controlled by tpvSettings.showOrderManagement
        if (tpvSettings.showOrderManagement) {
            val ordersEnabled = canOperate && canWork
            val ordersBadge = when {
                !canWork -> "Registra tu entrada"
                !canOperate -> "Abre el turno primero"
                else -> null
            }
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.Restaurant,
                    label = "Órdenes",
                    enabled = ordersEnabled,
                    badge = ordersBadge,
                    onClick = onNavigateToOrdering
                )
            )
        }

        // 🆕 "Cobrar" — unified Checkout (Phase 2 of refactor/home/tpv).
        // Runs in parallel with "Pago rápido" + "Órdenes" during validation.
        // Once validated in production, those two get removed in a separate ola.
        if (tpvSettings.showCheckout) {
            val checkoutEnabled = canOperate && canWork
            val checkoutBadge = when {
                !canWork -> "Registra tu entrada"
                !canOperate -> "Abre el turno primero"
                else -> null
            }
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.PointOfSale,
                    label = "Cobrar",
                    enabled = checkoutEnabled,
                    badge = checkoutBadge,
                    onClick = onNavigateToCheckout
                )
            )
        }

        // Conditionally visible buttons (controlled by tpvSettings)
        if (tpvSettings.showReports) {
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.BarChart,
                    label = "Reportes",
                    enabled = true,
                    onClick = onNavigateToReports
                )
            )
        }

        // Turnos — always visible (controlled separately by isShiftSystemEnabled)
        allButtons.add(
            ActionButton(
                icon = Icons.Default.Schedule,
                label = "Turnos",
                enabled = true,
                onClick = onNavigateToShifts
            )
        )

        if (tpvSettings.showPayments) {
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.Receipt,
                    label = "Pagos",
                    enabled = true,
                    onClick = onNavigateToPayments
                )
            )
        }

        if (tpvSettings.showSupport) {
            allButtons.add(
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Help,
                    label = "Soporte",
                    enabled = true,
                    onClick = onNavigateToSupport
                )
            )
        }

        // 📨 Mensajes — navigates to full Messages screen (controlled by tpvSettings)
        if (tpvSettings.showMessages) {
            val pendingMessageCount = messageHistory.count {
                it.deliveryStatus == "PENDING" || it.deliveryStatus == "DELIVERED"
            }
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.Email,
                    label = "Mensajes",
                    enabled = true,
                    badge = if (pendingMessageCount > 0) "$pendingMessageCount" else null,
                    onClick = onNavigateToMessages
                )
            )
        }

        // 🎓 Entrenamientos — step-by-step training modules (controlled by tpvSettings)
        if (tpvSettings.showTrainings) {
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.School,
                    label = "Entrenamientos",
                    enabled = true,
                    onClick = onNavigateToTrainings
                )
            )
        }

        // 🔐 AUTHORIZATION-BASED FILTERING (normal mode only)
        // Add SuperAdmin button ONLY if user has SUPERADMIN role
        if (currentUserRole == StaffRole.SUPERADMIN) {
            allButtons.add(
                ActionButton(
                    icon = Icons.Default.AdminPanelSettings,
                    label = "SuperAdmin",
                    enabled = true,
                    onClick = onNavigateToSuperAdmin
                )
            )
        }

        // Filter out "Turnos" button if shift system is disabled
        if (!isShiftSystemEnabled) {
            allButtons.removeAll { it.label == "Turnos" }
        }

        allButtons
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI
    // ══════════════════════════════════════════════════════════════════════

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Hola, $staffName",
                onSettingsClick = { showSettingsModal = true }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Explicitly use constraints from scope to avoid "unused scope" error                  │
            val sizes = ResponsiveSizes.calculate(this.maxHeight, this.maxWidth)
            CompositionLocalProvider(LocalResponsiveSizes provides sizes) {
                AvoqadoPullToRefresh(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // verticalScroll makes the entire content area scrollable,
                    // which allows pullToRefresh to detect drag gestures anywhere
                    // (not just on the button grid). For 6-8 buttons, non-lazy
                    // rendering via StaticActionButtonGrid has zero perf impact.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {

                        // Shift status banner (with offline state support) - FullWidth
                        // Hidden in simplified mode when module config disables shifts
                        val moduleEnableShifts = serializedInventoryConfig?.ui?.enableShifts ?: true
                        val shouldShowShiftBanner = isShiftSystemEnabled && moduleEnableShifts && !isSimplifiedMode
                        if (shouldShowShiftBanner) {
                            ShiftStatusBanner(
                                shift = currentShift,
                                isOffline = isOffline,
                                cachedInfo = cachedShiftInfo,
                                onClick = onNavigateToShifts,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // ═══════════════════════════════════════════════════════════════
                        // TIMECLOCK STATUS CARD
                        // Shows attendance status: clock-in/out, elapsed time
                        // Visible when requireClockInToLogin is enabled OR user has active entry
                        // ═══════════════════════════════════════════════════════════════
                        if (requireClockInToLogin || currentTimeEntry != null) {
                            TimeclockStatusCard(
                                currentEntry = currentTimeEntry,
                                isLoading = isAttendanceLoading,
                                onClockIn = onNavigateToTimeclock,
                                onClockOut = onNavigateToTimeclockForClockOut
                            )
                        }

                        // ═══════════════════════════════════════════════════════════════
                        // PENDING VERIFICATIONS CARD
                        // Shows count of sales missing proof-of-sale photos
                        // Visible only when SERIALIZED_INVENTORY mode has pending items
                        // ═══════════════════════════════════════════════════════════════
                        if (pendingVerificationsCount > 0) {
                            PendingVerificationsCard(
                                count = pendingVerificationsCount,
                                onClick = onNavigateToPendingVerifications
                            )
                        }

                        // ═══════════════════════════════════════════════════════════════
                        // SALES GOALS PAGER
                        // Shows progress toward sales goals in a horizontal pager.
                        // Uses salesGoals from HomeViewModel (fetched from backend).
                        // Falls back to module config if no backend goals (legacy support).
                        // ═══════════════════════════════════════════════════════════════
                        val effectiveGoals = salesGoals.ifEmpty {
                            listOfNotNull(serializedInventoryConfig?.salesGoal)
                        }
                        if (tpvSettings.showGoals && effectiveGoals.isNotEmpty()) {
                            SalesGoalsPager(
                                salesGoals = effectiveGoals,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        // Action button grid (non-lazy, compatible with verticalScroll)
                        StaticActionButtonGrid(
                            buttons = actionButtons,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // VENUE STATUS BANNER - Floating overlay (top, above all content)
            // ═══════════════════════════════════════════════════════════════
            VenueStatusBanner(
                status = venueStatus,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODALS
    // ══════════════════════════════════════════════════════════════════════

    // Amount input overlay (first-time payment flow)
    // ⚡ Performance: Uses in-composition overlay instead of Dialog (72% faster on 1GB RAM)
    com.jaac.avoqado_tpv.core.presentation.components.AmountInputBottomSheet(
        visible = showAmountBottomSheet,
        onDismiss = { showAmountBottomSheet = false },
        onConfirm = { amount ->
            showAmountBottomSheet = false
            onStartPaymentWithAmount(amount)
        }
    )

    // Settings bottom sheet (new user management modal)
    if (showSettingsModal) {
        SettingsBottomSheet(
            onDismiss = { showSettingsModal = false },
            onLogout = {
                showSettingsModal = false
                onLogout()
            },
            onRestartApp = {
                showSettingsModal = false
                showRestartConfirmDialog = true  // Show confirmation dialog
            },
            // 🔐 Only show Settings option if user has permission
            onSettings = if (hasSettingsAccess) {
                {
                    showSettingsModal = false
                    onNavigateToSettings()
                }
            } else null,
            onHelp = null, // Disabled - future feature
            // 🥝 Kiosk Mode Toggle - same permission as Settings
            // kioskModeAvailable: controlled from dashboard TpvSettings (shows/hides the toggle)
            // isKioskModeEnabled: local state (whether kiosk mode is currently active)
            kioskModeAvailable = tpvSettings.kioskModeEnabled,
            isKioskModeEnabled = isKioskModeEnabled,
            onKioskModeToggle = if (hasSettingsAccess) {
                {
                    showSettingsModal = false
                    val venueId = secureStorage.getVenueId()
                    if (venueId != null) {
                        if (isKioskModeEnabled) {
                            // Exit kiosk mode
                            kioskModeManager.exitKioskMode()
                        } else {
                            // Enter kiosk mode
                            kioskModeManager.enterKioskMode(venueId)
                        }
                    }
                }
            } else null,
            isDarkMode = isDarkMode,
            onDarkModeToggle = { onDarkModeToggle() },
            onCheckForUpdates = {
                showSettingsModal = false
                onNavigateToSelfUpdate()
            }
        )
    }

    // Restart app confirmation dialog
    if (showRestartConfirmDialog) {
        val context = LocalContext.current

        AvoqadoDialog(
            title = "Reiniciar Aplicación",
            message = "¿Estás seguro de que deseas reiniciar la aplicación?\n\n" +
                    "La app se cerrará y volverá a abrir desde el inicio.",
            icon = Icons.Outlined.RestartAlt,
            confirmText = "Reiniciar",
            dismissText = "Cancelar",
            onDismiss = { showRestartConfirmDialog = false },
            onConfirm = {
                showRestartConfirmDialog = false
                restartApp(context)
            }
        )
    }

}

/**
 * Restart the app completely (Toast/Square pattern)
 *
 * This kills the current process and restarts the app from scratch.
 * - Clears all in-memory state (ViewModels, Singletons, caches)
 * - Preserves persistent storage (Room DB, SecureStorage)
 * - User will go through Splash → Login (if token expired) → Home
 *
 * @param context Android context to get package manager
 */
private fun restartApp(context: Context) {
    val packageManager = context.packageManager
    val intent = packageManager.getLaunchIntentForPackage(context.packageName)
    val componentName = intent?.component

    val mainIntent = Intent.makeRestartActivityTask(componentName)
    context.startActivity(mainIntent)

    // Kill the current process
    Runtime.getRuntime().exit(0)
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "PAX A80 (Portrait)")
@Composable
private fun WelcomeScreenPreview() {
    AvoqadoTheme {
        WelcomeScreenContent(
            staffName = "Juan Pérez",
            clockInTime = null,
            currentShift = null,
            isRefreshing = false,
            onRefresh = {},
            onStartPaymentWithAmount = {},
            onNavigateToShifts = {},
            onNavigateToOrdering = {},
            onNavigateToReports = {},
            onNavigateToPayments = {},
            onNavigateToSupport = {},
            onNavigateToSettings = {},
            onNavigateToSuperAdmin = {},
            onLogout = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 (Portrait)")
@Composable
private fun WelcomeScreenPreviewLarge() {
    AvoqadoTheme {
        WelcomeScreenContent(
            staffName = "María González",
            clockInTime = "Desde las 09:00",
            currentShift = null,
            isRefreshing = false,
            onRefresh = {},
            onStartPaymentWithAmount = {},
            onNavigateToShifts = {},
            onNavigateToOrdering = {},
            onNavigateToReports = {},
            onNavigateToPayments = {},
            onNavigateToSupport = {},
            onNavigateToSettings = {},
            onNavigateToSuperAdmin = {},
            onLogout = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "With Active Shift")
@Composable
private fun WelcomeScreenWithActiveShiftPreview() {
    AvoqadoTheme {
        WelcomeScreenContent(
            staffName = "Juan Pérez",
            clockInTime = null,
            isRefreshing = false,
            onRefresh = {},
            currentShift = Shift(
                id = "shift-123",
                venueId = "venue-1",
                staffId = "staff-1",
                staffName = "Juan Pérez",
                startTime = "2025-01-15T14:30:00Z",
                endTime = null,
                status = ShiftStatus.OPEN,
                startingCash = BigDecimal("500.00"),
                endingCash = null,
                totalSales = BigDecimal("1250.50"),
                totalTips = BigDecimal("125.00"),
                totalOrders = 15,
                totalCashPayments = BigDecimal("600.00"),
                totalCardPayments = BigDecimal("650.50"),
                totalVoucherPayments = BigDecimal("0.00"),
                totalOtherPayments = BigDecimal("0.00"),
                totalProductsSold = 23,
                durationMinutes = 150
            ),
            onStartPaymentWithAmount = {},
            onNavigateToShifts = {},
            onNavigateToOrdering = {},
            onNavigateToReports = {},
            onNavigateToPayments = {},
            onNavigateToSupport = {},
            onNavigateToSettings = {},
            onNavigateToSuperAdmin = {},
            onLogout = {}
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PENDING VERIFICATIONS CARD
// Shows count of sales with missing proof-of-sale photos (SERIALIZED_INVENTORY)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PendingVerificationsCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.avoqadoColors.statusInfo.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.avoqadoColors.statusInfo.copy(alpha = 0.2f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.avoqadoColors.statusInfo
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "En revisión por Administración",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$count venta${if (count != 1) "s" else ""} en revisión",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Ver ventas en revisión",
                tint = MaterialTheme.avoqadoColors.statusInfo,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
