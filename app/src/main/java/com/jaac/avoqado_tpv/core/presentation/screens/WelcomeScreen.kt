package com.jaac.avoqado_tpv.core.presentation.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.ActionButton
import com.jaac.avoqado_tpv.core.presentation.components.ActionButtonGrid
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.SettingsBottomSheet
import com.jaac.avoqado_tpv.core.presentation.components.ShiftStatusBanner
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
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
    onNavigateToSuperAdmin: () -> Unit = {},
    onLogout: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    shiftViewModel: com.jaac.avoqado_tpv.features.shift.presentation.ShiftViewModel = hiltViewModel()
) {
    // Staff info from ViewModel
    val staffName by viewModel.staffName.collectAsStateWithLifecycle()
    val clockInTime by viewModel.clockInTime.collectAsStateWithLifecycle()

    // Shift state from ShiftViewModel
    val shiftState by shiftViewModel.state.collectAsStateWithLifecycle()
    val currentShift = when (val state = shiftState) {
        is com.jaac.avoqado_tpv.features.shift.presentation.ShiftState.ShiftActive -> state.shift
        else -> null
    }

    // ⭐ FIX: Reload shift status whenever WelcomeScreen becomes visible
    // This ensures shift status is updated when returning from ShiftScreen
    androidx.compose.runtime.LaunchedEffect(Unit) {
        shiftViewModel.loadCurrentShift()
    }

    WelcomeScreenContent(
        modifier = modifier,
        staffName = staffName,
        clockInTime = clockInTime,
        currentShift = currentShift,
        onStartPaymentWithAmount = onStartPaymentWithAmount,  // ✅ Modal flow for first-time
        onNavigateToShifts = onNavigateToShifts,
        onNavigateToOrdering = onNavigateToOrdering,
        onNavigateToReports = onNavigateToReports,
        onNavigateToSuperAdmin = onNavigateToSuperAdmin,
        onLogout = {
            viewModel.logout()
            onLogout()
        }
    )
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
    onStartPaymentWithAmount: (String) -> Unit,  // ✅ Modal flow (first-time)
    onNavigateToShifts: () -> Unit,
    onNavigateToOrdering: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToSuperAdmin: () -> Unit,
    onLogout: () -> Unit
) {
    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    // Modal states
    var showAmountBottomSheet by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }

    // ══════════════════════════════════════════════════════════════════════
    // ACTION BUTTONS CONFIGURATION
    // ══════════════════════════════════════════════════════════════════════

    // ⭐ Check if shift is open for payment processing (Square/Toast pattern)
    val hasOpenShift = currentShift?.status == ShiftStatus.OPEN

    val actionButtons = listOf(
        // ✅ ENABLED FEATURES
        ActionButton(
            icon = Icons.Default.CreditCard,
            label = "Pago rápido",
            enabled = hasOpenShift,  // ⭐ Only enabled when shift is open
            badge = if (!hasOpenShift) "Abre el turno primero" else null,  // ⭐ Show hint when disabled
            onClick = { showAmountBottomSheet = true }  // ✅ Open modal (first-time flow)
        ),
        ActionButton(
            icon = Icons.Default.Assessment,
            label = "Resumen",
            enabled = false,
            badge = "Próximamente",
            onClick = { /* TODO: Navigate to daily summary */ }
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
            enabled = false,
            badge = "Próximamente",
            onClick = { /* TODO: Navigate to payment history */ }
        ),

        // ⏳ FUTURE FEATURES
        ActionButton(
            icon = Icons.Default.Restaurant,
            label = "Órdenes",
            enabled = hasOpenShift,  // ⭐ Only enabled when shift is open
            badge = if (!hasOpenShift) "Abre el turno primero" else null,
            onClick = onNavigateToOrdering
        ),
        // ActionButton(
        //     icon = Icons.Default.History,
        //     label = "Historial",
        //     enabled = false,
        //     badge = "Próximamente",
        //     onClick = { /* TODO: Navigate to transaction history */ }
        // ),
        ActionButton(
            icon = Icons.Default.BarChart,
            label = "Reportes",
            enabled = true,
            onClick = onNavigateToReports
        ),
        ActionButton(
            icon = Icons.AutoMirrored.Filled.Help,
            label = "Soporte",
            enabled = false,
            badge = "Próximamente",
            onClick = { /* TODO: Navigate to help/support */ }
        ),

        // 🔧 SUPERADMIN TOOLS
        ActionButton(
            icon = Icons.Default.AdminPanelSettings,
            label = "SuperAdmin",
            enabled = true,
            onClick = onNavigateToSuperAdmin
        )
    )

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
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false,  // LazyVerticalGrid handles scrolling internally
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top spacing
            Spacer(modifier = Modifier.height(16.dp))

            // Shift status banner
            ShiftStatusBanner(
                shift = currentShift,
                onClick = onNavigateToShifts
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action button grid (LazyVerticalGrid handles its own scrolling)
            ActionButtonGrid(
                buttons = actionButtons,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODALS
    // ══════════════════════════════════════════════════════════════════════

    // Amount input bottom sheet (first-time payment flow)
    if (showAmountBottomSheet) {
        com.jaac.avoqado_tpv.core.presentation.components.AmountInputBottomSheet(
            onDismiss = { showAmountBottomSheet = false },
            onConfirm = { amount ->
                showAmountBottomSheet = false
                onStartPaymentWithAmount(amount)
            }
        )
    }

    // Settings bottom sheet (new user management modal)
    if (showSettingsModal) {
        SettingsBottomSheet(
            onDismiss = { showSettingsModal = false },
            onLogout = {
                showSettingsModal = false
                onLogout()
            },
            onSettings = null, // Disabled - future feature
            onHelp = null // Disabled - future feature
        )
    }
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
            onNavigateToSuperAdmin = {},
            onLogout = {}
        )
    }
}
