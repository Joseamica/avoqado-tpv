package com.jaac.avoqado_tpv.core.presentation.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.domain.events.VenueStatusEvent
import com.jaac.avoqado_tpv.core.presentation.components.ActionButton
import com.jaac.avoqado_tpv.core.presentation.components.ActionButtonGrid
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoDialog
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
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
import com.jaac.avoqado_tpv.features.permissions.di.PermissionsEntryPoint
import com.jaac.avoqado_tpv.features.remote_command.presentation.LockScreenOverlay
import com.jaac.avoqado_tpv.features.remote_command.presentation.MaintenanceOverlay
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.jaac.avoqado_tpv.features.shift.presentation.CachedShiftInfo
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
    onNavigateToReports: () -> Unit = {},
    onNavigateToPayments: () -> Unit = {},  // ⭐ NEW: Navigate to Payments screen
    onNavigateToSupport: () -> Unit = {},  // ⭐ NEW: Navigate to Support screen
    onNavigateToSettings: () -> Unit = {},  // ⚙️ Navigate to Settings screen
    onNavigateToSuperAdmin: () -> Unit = {},
    onNavigateToSerializedSale: () -> Unit = {},  // 📱 Telecom: Vender flow (barcode → price → payment)
    onNavigateToInventoryRegister: () -> Unit = {},  // 📦 Telecom: Alta de productos flow
    onLogout: () -> Unit = {},
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
    // VENUE STATUS (for mid-session detection)
    // ═══════════════════════════════════════════════════════════════════════════
    val venueStatus by viewModel.venueStatus.collectAsStateWithLifecycle()

    // State for venue status dialogs
    var showVenueSuspendedDialog by remember { mutableStateOf(false) }
    var showVenueClosedDialog by remember { mutableStateOf(false) }

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

    // ⭐ FIX: Reload shift status whenever WelcomeScreen becomes visible
    // This ensures shift status is updated when returning from ShiftScreen
    androidx.compose.runtime.LaunchedEffect(Unit) {
        shiftViewModel.loadCurrentShift()
        shiftViewModel.refreshSettings() // Refresh settings too
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
        onStartPaymentWithAmount = onStartPaymentWithAmount,  // ✅ Modal flow for first-time
        onNavigateToShifts = onNavigateToShifts,
        onNavigateToOrdering = onNavigateToOrdering,
        onNavigateToReports = onNavigateToReports,
        onNavigateToPayments = onNavigateToPayments,  // ⭐ NEW: Pass payments navigation
        onNavigateToSupport = onNavigateToSupport,  // ⭐ NEW: Pass support navigation
        onNavigateToSettings = onNavigateToSettings,  // ⚙️ Pass settings navigation
        onNavigateToSuperAdmin = onNavigateToSuperAdmin,
        onNavigateToSerializedSale = onNavigateToSerializedSale,  // 📱 Telecom: Vender
        onNavigateToInventoryRegister = onNavigateToInventoryRegister,  // 📦 Telecom: Alta
        onLogout = {
            viewModel.logout()
            onLogout()
        }
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIAL SYNC LOADING OVERLAY
    // Shows during post-login sync to prevent user from seeing intermediate states
    // ═══════════════════════════════════════════════════════════════════════════
    if (isInitialLoading) {
        AvoqadoLoadingOverlay(message = "Sincronizando terminal...")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REMOTE COMMAND OVERLAYS
    // Full-screen overlays for Lock and Maintenance modes
    // These have high zIndex and block all user interactions when active
    // ═══════════════════════════════════════════════════════════════════════════

    // Maintenance Overlay (zIndex 90) - Staff can exit locally
    MaintenanceOverlay(
        visible = isInMaintenance,
        maintenanceReason = maintenanceReason,
        initiatedBy = maintenanceInitiatedBy,
        onExitMaintenance = { viewModel.exitMaintenance() }
    )

    // Lock Screen Overlay (zIndex 100) - Highest priority, blocks everything
    // Must be unlocked remotely by admin - no local exit option
    LockScreenOverlay(
        visible = isLocked,
        lockReason = lockReason,
        lockMessage = lockMessage,
        lockedBy = lockedBy
    )

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
    onStartPaymentWithAmount: (String) -> Unit,  // ✅ Modal flow (first-time)
    onNavigateToShifts: () -> Unit,
    onNavigateToOrdering: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToPayments: () -> Unit,  // ⭐ NEW: Navigate to Payments screen
    onNavigateToSupport: () -> Unit,  // ⭐ NEW: Navigate to Support screen
    onNavigateToSettings: () -> Unit,  // ⚙️ Navigate to Settings screen
    onNavigateToSuperAdmin: () -> Unit,
    onNavigateToSerializedSale: () -> Unit = {},  // 📱 Telecom: Vender flow
    onNavigateToInventoryRegister: () -> Unit = {},  // 📦 Telecom: Alta de productos
    onLogout: () -> Unit
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

    // 📱 Check for simplified order flow (telecom/serialized inventory mode)
    // Use StateFlow so changes (e.g., on logout) trigger recomposition
    val currentModules by modulesRepository.modules.collectAsStateWithLifecycle()
    val serializedInventoryConfig = currentModules
        .find { it.moduleCode == ModulesRepository.MODULE_SERIALIZED_INVENTORY }
        ?.config
    val isSimplifiedMode = serializedInventoryConfig?.ui?.simplifiedOrderFlow == true

    // 📦 Check if user has serialized inventory permission (for "Alta de Productos" button)
    var hasInventoryRegisterPermission by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = permissionsRepository.getPermissions(forceRefresh = false)
        val permissions = result.getOrNull()
        hasInventoryRegisterPermission = permissions?.contains("serialized-inventory:register") ?: false
    }

    // 🥝 Kiosk mode state
    val isKioskModeEnabled by kioskModeManager.isKioskMode.collectAsStateWithLifecycle()

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
                    enabled = true,
                    onClick = onNavigateToSerializedSale
                )
            )

            // Secondary action: Alta de Productos (inventory registration)
            // Only shown if user has serialized-inventory:register permission
            if (hasInventoryRegisterPermission) {
                add(
                    ActionButton(
                        icon = Icons.Default.Inventory2,
                        label = moduleLabels?.register ?: "Alta de Productos",
                        enabled = true,
                        onClick = onNavigateToInventoryRegister
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
        }
    } else {
        // ════════════════════════════════════════════════════════════════════
        // 📦 NORMAL MODE (Restaurant/Retail)
        // Full feature set: Pago rápido, Órdenes, Reportes, Turnos, Pagos, Soporte
        // ════════════════════════════════════════════════════════════════════
        val allButtons = listOf(
            // ✅ ENABLED FEATURES
            ActionButton(
                icon = Icons.Default.CreditCard,
                label = "Pago rápido",
                enabled = canOperate,  // ⭐ Only enabled when shift is open (or disabled)
                badge = if (!canOperate) "Abre el turno primero" else null,  // ⭐ Show hint when disabled
                onClick = { showAmountBottomSheet = true }  // ✅ Open modal (first-time flow)
            ),
            ActionButton(
                icon = Icons.Default.Restaurant,
                label = "Órdenes",
                enabled = canOperate,  // ⭐ Only enabled when shift is open (or disabled)
                badge = if (!canOperate) "Abre el turno primero" else null,
                onClick = onNavigateToOrdering
            ),
            ActionButton(
                icon = Icons.Default.BarChart,
                label = "Reportes",
                enabled = true,
                onClick = onNavigateToReports
            ),
            ActionButton(
                icon = Icons.Default.Schedule,
                label = "Turnos",
                enabled = true,
                onClick = onNavigateToShifts
            ),
            ActionButton(
                icon = Icons.Default.Receipt,
                label = "Pagos",
                enabled = true,  // ⭐ ENABLED: Payment history is now implemented
                onClick = onNavigateToPayments  // ⭐ Navigate to Payments screen
            ),
            ActionButton(
                icon = Icons.AutoMirrored.Filled.Help,
                label = "Soporte",
                enabled = true,
                onClick = onNavigateToSupport
            )
        )

        // 🔐 AUTHORIZATION-BASED FILTERING (normal mode only)
        allButtons.toMutableList().apply {
            // Add SuperAdmin button ONLY if user has SUPERADMIN role
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

            // Filter out "Turnos" button if shift system is disabled
            if (!isShiftSystemEnabled) {
                removeAll { it.label == "Turnos" }
            }
        }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // ═══════════════════════════════════════════════════════════════
                    // VENUE STATUS BANNER (shows only for non-ACTIVE statuses) - FullWidth
                    // ═══════════════════════════════════════════════════════════════
                    VenueStatusBanner(status = venueStatus)

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

                        // Content with horizontal padding
                        Column(
                            modifier = Modifier
                                .fillMaxSize(), // ⭐ Removed horizontal padding to let Grid handle it (edge-to-edge feel)
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Action button grid (LazyVerticalGrid handles its own scrolling)
                            ActionButtonGrid(
                                buttons = actionButtons,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                }
            }
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
            } else null
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
