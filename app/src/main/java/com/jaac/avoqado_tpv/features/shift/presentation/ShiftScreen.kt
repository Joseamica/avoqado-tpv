package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationOutcome
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shift Screen
 *
 * Full-screen shift management interface (Turnos).
 * Follows Toast/Square POS pattern with current shift card + context-aware action button.
 *
 * **Layout:**
 * ```
 * ┌─────────────────────────────────────┐
 * │ TURNOS                        [←]   │  ← Top bar
 * ├─────────────────────────────────────┤
 * │ ┌─────────────────────────────────┐ │
 * │ │ TURNO ACTUAL                    │ │
 * │ │ 🟢 Abierto - Juan Pérez         │ │  ← Current shift card
 * │ │ Inicio: 14:30                   │ │
 * │ │ Ventas: $1,250.00               │ │
 * │ │                                 │ │
 * │ │ [Cerrar Turno] ← RED BUTTON     │ │  ← Context-aware button
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * │ (Future: HISTORIAL DE TURNOS)       │  ← Phase 2
 * └─────────────────────────────────────┘
 * ```
 *
 * **Usage:**
 * ```kotlin
 * ShiftScreen(
 *     onNavigateBack = { navController.popBackStack() }
 * )
 * ```
 *
 * @param onNavigateBack Callback to navigate back to previous screen
 * @param viewModel ShiftViewModel (injected by Hilt)
 */
@Composable
fun ShiftScreen(
    onNavigateBack: () -> Unit,
    viewModel: ShiftViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val canOpenShift by viewModel.canOpenShift.collectAsStateWithLifecycle()
    val canCloseShift by viewModel.canCloseShift.collectAsStateWithLifecycle()
    val isCashReconciliationEnabled by viewModel.isCashReconciliationEnabled.collectAsStateWithLifecycle()

    // Dialog states
    var showOpenDialog by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    var selectedShift by remember { mutableStateOf<Shift?>(null) }
    val requiresReconciliationAcknowledgement =
        requiresCashReconciliationAcknowledgement(state)

    // A counted/skipped result is deliberately persistent: neither the system back action nor
    // the top-bar arrow may bypass the explicit "Listo" acknowledgment. Legacy closes retain
    // their existing navigation behavior.
    BackHandler(enabled = requiresReconciliationAcknowledgement) { }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Turnos",
                onNavigationClick = if (requiresReconciliationAcknowledgement) {
                    null
                } else {
                    onNavigateBack
                }
            )
        }
    ) { paddingValues ->
        ResponsiveScaffold(
            scrollable = false,  // Pull-to-refresh handles scrolling
            modifier = Modifier.padding(paddingValues)
        ) {
            AvoqadoPullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                enabled = isShiftPullToRefreshEnabled(state)
            ) {
                when (val currentState = state) {
                is ShiftState.Loading -> {
                    AvoqadoLoadingOverlay(message = "Cargando turno...")
                }

                is ShiftState.ShiftActive -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        ActiveShiftContent(
                            shift = currentState.shift,
                            onCloseShift = { showCloseDialog = true },
                            canCloseShift = canCloseShift
                        )

                        if (currentState.shiftHistory.isNotEmpty()) {
                            ShiftHistoryList(
                                shifts = currentState.shiftHistory,
                                onShiftClick = { shift -> selectedShift = shift }
                            )
                        }
                    }
                }

                is ShiftState.NoActiveShift -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        NoActiveShiftContent(
                            onOpenShift = { showOpenDialog = true },
                            canOpenShift = canOpenShift
                        )

                        if (currentState.shiftHistory.isNotEmpty()) {
                            ShiftHistoryList(
                                shifts = currentState.shiftHistory,
                                onShiftClick = { shift -> selectedShift = shift }
                            )
                        }
                    }
                }

                is ShiftState.ShiftClosed -> {
                    ShiftClosedContent(
                        shift = currentState.shift,
                        reconciliationAction = currentState.reconciliationAction,
                        onDone = viewModel::acknowledgeClosedShift
                    )
                }

                is ShiftState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.retry() }
                    )
                }

                is ShiftState.Idle -> {
                    // Initial state - loading will happen automatically
                }
                }
            }
        }

        // Dialogs
        if (showOpenDialog) {
            OpenShiftDialog(
                onDismiss = { showOpenDialog = false },
                onConfirm = { startingCash ->
                    viewModel.openShift(startingCash)
                    showOpenDialog = false
                }
            )
        }

        if (showCloseDialog && state is ShiftState.ShiftActive) {
            CloseShiftDialog(
                shift = (state as ShiftState.ShiftActive).shift,
                onDismiss = { showCloseDialog = false },
                onConfirm = {
                    viewModel.closeShift()
                    showCloseDialog = false
                },
                cashReconciliationEnabled = isCashReconciliationEnabled,
                onCounted = { countedCash ->
                    viewModel.closeShift(
                        reconciliationAction = CashReconciliationAction.COUNTED,
                        countedCash = countedCash
                    )
                    showCloseDialog = false
                },
                onSkipped = {
                    viewModel.closeShift(reconciliationAction = CashReconciliationAction.SKIPPED)
                    showCloseDialog = false
                }
            )
        }

        // Shift detail dialog
        selectedShift?.let { shift ->
            ShiftDetailDialog(
                shift = shift,
                onDismiss = { selectedShift = null }
            )
        }
    }
}

/**
 * Active Shift Content
 *
 * Displays current active shift with close button.
 */
@Composable
private fun ActiveShiftContent(
    shift: Shift,
    onCloseShift: () -> Unit,
    canCloseShift: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section title
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = "TURNO ACTUAL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        // Current shift card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Status row
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Turno abierto",
                        tint = MaterialTheme.colorScheme.tertiary,  // ✅ Verde Avoqado del theme
                        modifier = Modifier.size(24.dp)
                    )

                    Text(
                        text = "  Abierto - ${shift.staffName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Shift details
                ShiftDetailRow(label = "Inicio", value = formatTime(shift.startTime))
                ShiftDetailRow(label = "Duración", value = formatDuration(shift.durationMinutes))
                ShiftDetailRow(label = "Ventas", value = "$${shift.totalSales}", highlight = true)
                ShiftDetailRow(label = "Productos", value = "${shift.totalProductsSold}")
                ShiftDetailRow(label = "Órdenes", value = "${shift.totalOrders}")
                ShiftDetailRow(label = "Propinas", value = "$${shift.totalTips}")

                Spacer(modifier = Modifier.height(20.dp))

                // Close shift button
                Button(
                    onClick = onCloseShift,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canCloseShift,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error  // ✅ Rojo del theme
                    )
                ) {
                    Text("Cerrar Turno", style = MaterialTheme.typography.bodyLarge)
                }

                if (!canCloseShift) {
                    Text(
                        text = "No tienes permiso para cerrar turnos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * No Active Shift Content
 *
 * Displays empty state with open shift button.
 */
@Composable
private fun NoActiveShiftContent(
    onOpenShift: () -> Unit,
    canOpenShift: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Sin turno activo",
                    tint = MaterialTheme.colorScheme.error,  // ✅ Rojo del theme
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Sin Turno Activo",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Abre un turno para comenzar a registrar ventas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onOpenShift,
                    enabled = canOpenShift,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary  // ✅ Verde Avoqado
                    )
                ) {
                    Text("Abrir Turno", style = MaterialTheme.typography.bodyLarge)
                }

                if (!canOpenShift) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No tienes permiso para abrir turnos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

internal fun requiresCashReconciliationAcknowledgement(state: ShiftState): Boolean =
    state is ShiftState.ShiftClosed && state.reconciliationAction != null

internal fun isShiftPullToRefreshEnabled(state: ShiftState): Boolean =
    state !is ShiftState.Loading && !requiresCashReconciliationAcknowledgement(state)

/**
 * Shift Closed Content
 *
 * Shows closed shift summary (temporary state).
 */
@Composable
private fun ShiftClosedContent(
    shift: Shift,
    reconciliationAction: CashReconciliationAction? = null,
    onDone: () -> Unit = {}
) {
    if (reconciliationAction != null) {
        CashReconciliationClosedContent(
            shift = shift,
            action = reconciliationAction,
            onDone = onDone
        )
        return
    }

    LegacyShiftClosedContent(shift)
}

/** Existing two-second close confirmation, retained unchanged for venues without the feature. */
@Composable
private fun LegacyShiftClosedContent(shift: Shift) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer  // ✅ Verde claro del theme
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Turno cerrado",
                    tint = MaterialTheme.colorScheme.tertiary,  // ✅ Verde Avoqado
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Turno Cerrado Exitosamente",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Ventas: $${shift.totalSales}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary  // ✅ Verde Avoqado
                )

                Text(
                    text = "Productos: ${shift.totalProductsSold}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** Persistent result for an explicit COUNTED or SKIPPED close attempt. */
@Composable
private fun CashReconciliationClosedContent(
    shift: Shift,
    action: CashReconciliationAction,
    onDone: () -> Unit
) {
    val result = shift.reconciliation
    val difference = result?.cashDifference
    val countedCash = result?.cashDeclared ?: shift.cashDeclared
    val wasApplied = result?.outcome == CashReconciliationOutcome.APPLIED

    val title: String
    val description: String
    val statusColor: Color
    val showDifference: Boolean

    when {
        action == CashReconciliationAction.SKIPPED -> {
            title = "Turno cerrado sin conteo"
            description = if (result?.outcome == CashReconciliationOutcome.SKIPPED) {
                "El turno quedó cerrado y no se registró un resultado de caja."
            } else {
                "El turno quedó cerrado; no se recibió un resultado de conciliación."
            }
            statusColor = MaterialTheme.avoqadoColors.statusWarning
            showDifference = false
        }

        wasApplied && difference != null && difference.compareTo(BigDecimal.ZERO) == 0 -> {
            title = "Caja cuadrada"
            description = "El efectivo contado coincide con el efectivo esperado."
            statusColor = MaterialTheme.avoqadoColors.statusSuccess
            showDifference = true
        }

        wasApplied && difference != null && difference.signum() < 0 -> {
            title = "Faltante"
            description = "La caja quedó por debajo del efectivo esperado."
            statusColor = MaterialTheme.avoqadoColors.statusError
            showDifference = true
        }

        wasApplied && difference != null -> {
            title = "Sobrante"
            description = "La caja quedó por encima del efectivo esperado."
            statusColor = MaterialTheme.avoqadoColors.statusWarning
            showDifference = true
        }

        else -> {
            title = "Turno cerrado"
            description = "La conciliación no está disponible. Revisa el turno en el historial."
            statusColor = MaterialTheme.avoqadoColors.statusWarning
            showDifference = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (title == "Caja cuadrada") {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Error
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(56.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (action == CashReconciliationAction.COUNTED && countedCash != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                        ShiftDetailRow(
                            label = "Efectivo contado",
                            value = CurrencyFormatter.format(countedCash)
                        )
                    }

                    if (showDifference && difference != null) {
                        ShiftDetailRow(
                            label = "Diferencia",
                            value = CurrencyFormatter.format(difference.abs()),
                            highlight = true,
                            valueColor = statusColor
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("Listo", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Error Content
 *
 * Shows error message with retry button.
 */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    // 🩹 Center when short, scroll when a long error message pushes the button off-screen.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val minContentHeight = maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minContentHeight)
                .padding(32.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}

/**
 * Shift History List
 *
 * Displays list of closed shifts (Square/Toast POS pattern).
 * Scrollable list to view multiple shifts.
 */
@Composable
private fun ShiftHistoryList(
    shifts: List<Shift>,
    onShiftClick: (Shift) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section title
        Text(
            text = "HISTORIAL DE TURNOS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        // Scrollable history cards with max height
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),  // Max height to make it scrollable
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shifts) { shift ->
                ShiftHistoryCard(
                    shift = shift,
                    onClick = { onShiftClick(shift) }
                )
            }
        }
    }
}

/**
 * Shift History Card
 *
 * Individual card for a closed shift in the history list.
 */
@Composable
private fun ShiftHistoryCard(
    shift: Shift,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row: Date and staff
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDate(shift.startTime),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = shift.staffName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Duration
                Column {
                    Text(
                        text = "Duración",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatDurationForHistory(shift.durationMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Sales
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Ventas",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "$${shift.totalSales}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.avoqadoColors.statusSuccess
                    )
                }

                // Products
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Productos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${shift.totalProductsSold}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            shift.cashDifference?.let { difference ->
                val differenceColor = when (difference.signum()) {
                    -1 -> MaterialTheme.avoqadoColors.statusError
                    0 -> MaterialTheme.avoqadoColors.statusSuccess
                    else -> MaterialTheme.avoqadoColors.statusWarning
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cashDifferenceLabel(difference),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = differenceColor
                    )
                    Text(
                        text = CurrencyFormatter.format(difference.abs()),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = differenceColor
                    )
                }
            }
        }
    }
}

private fun cashDifferenceLabel(difference: BigDecimal): String = when (difference.signum()) {
    -1 -> "Faltante"
    0 -> "Caja cuadrada"
    else -> "Sobrante"
}

/**
 * Shift Detail Row
 *
 * Reusable row for displaying shift information.
 */
@Composable
private fun ShiftDetailRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
            ),
            color = valueColor
                ?: if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Format time from ISO 8601 to HH:mm
 */
private fun formatTime(isoTime: String, zoneId: ZoneId = ZoneId.of("America/Mexico_City")): String {
    return try {
        val instant = Instant.parse(isoTime)
        val localTime = instant.atZone(zoneId).toLocalTime()
        DateTimeFormatter.ofPattern("HH:mm").format(localTime)
    } catch (e: Exception) {
        "N/A"
    }
}

/**
 * Format duration in minutes to readable string
 *
 * Used for active shifts - shows "Iniciando..." for shifts just started.
 */
private fun formatDuration(minutes: Int?): String {
    if (minutes == null || minutes == 0) return "Iniciando..."

    val hours = minutes / 60
    val mins = minutes % 60

    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

/**
 * Format duration for shift history (closed shifts)
 *
 * Unlike active shifts, closed shifts should NEVER show "Iniciando...".
 * If duration is invalid/missing, show "N/A" instead.
 */
private fun formatDurationForHistory(minutes: Int?): String {
    // For closed shifts, null/0 duration = data quality issue, not "starting"
    if (minutes == null || minutes <= 0) return "N/A"

    val hours = minutes / 60
    val mins = minutes % 60

    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

/**
 * Format date from ISO 8601 to "dd MMM, HH:mm"
 */
private fun formatDate(isoTime: String, zoneId: ZoneId = ZoneId.of("America/Mexico_City")): String {
    return try {
        val instant = Instant.parse(isoTime)
        val dateTime = instant.atZone(zoneId)
        DateTimeFormatter.ofPattern("dd MMM, HH:mm").format(dateTime)
    } catch (e: Exception) {
        "N/A"
    }
}

/**
 * Shift Detail Dialog
 *
 * Shows complete information for a selected shift from history.
 * Displays payment breakdown, products sold, and duration.
 */
@Composable
private fun ShiftDetailDialog(
    shift: Shift,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Detalles del Turno",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = formatDate(shift.startTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Staff info
                ShiftDetailRow(label = "Personal", value = shift.staffName)

                // Duration
                ShiftDetailRow(
                    label = "Duración",
                    value = formatDurationForHistory(shift.durationMinutes)
                )

                // Divider between Duration and VENTAS
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )

                // Sales summary
                Text(
                    text = "VENTAS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                ShiftDetailRow(
                    label = "Total Ventas",
                    value = "$${shift.totalSales}",
                    highlight = true
                )

                // Divider between Total Ventas and Productos Vendidos
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )

                ShiftDetailRow(label = "Productos Vendidos", value = "${shift.totalProductsSold}")
                ShiftDetailRow(label = "Órdenes", value = "${shift.totalOrders}")
                ShiftDetailRow(label = "Propinas", value = "$${shift.totalTips}")

                // Divider between Propinas and MÉTODOS DE PAGO
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )

                // Payment breakdown
                Text(
                    text = "MÉTODOS DE PAGO",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                ShiftDetailRow(label = "Efectivo", value = "$${shift.totalCashPayments}")
                ShiftDetailRow(label = "Tarjeta", value = "$${shift.totalCardPayments}")
                ShiftDetailRow(label = "Vales", value = "$${shift.totalVoucherPayments}")
                ShiftDetailRow(label = "Otros", value = "$${shift.totalOtherPayments}")

                // Divider before EFECTIVO EN CAJA
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )

                // Cash drawer
                Text(
                    text = "EFECTIVO EN CAJA",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                ShiftDetailRow(label = "Inicial", value = "$${shift.startingCash}")
                ShiftDetailRow(
                    label = "Final",
                    value = shift.endingCash?.let { "$${it}" } ?: "N/A"
                )

                if (shift.cashDeclared != null || shift.cashDifference != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )

                    Text(
                        text = "CONCILIACIÓN DE CAJA",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    shift.cashDeclared?.let { cashDeclared ->
                        ShiftDetailRow(
                            label = "Efectivo contado",
                            value = CurrencyFormatter.format(cashDeclared)
                        )
                    }

                    shift.cashDifference?.let { difference ->
                        val differenceColor = when (difference.signum()) {
                            -1 -> MaterialTheme.avoqadoColors.statusError
                            0 -> MaterialTheme.avoqadoColors.statusSuccess
                            else -> MaterialTheme.avoqadoColors.statusWarning
                        }
                        ShiftDetailRow(
                            label = cashDifferenceLabel(difference),
                            value = CurrencyFormatter.format(difference.abs()),
                            highlight = true,
                            valueColor = differenceColor
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun ShiftScreenActivePreview() {
    AvoqadoTheme {
        ActiveShiftContent(
            shift = Shift(
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
            onCloseShift = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShiftScreenNoActivePreview() {
    AvoqadoTheme {
        NoActiveShiftContent(
            onOpenShift = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ShiftDetailDialogPreview() {
    AvoqadoTheme {
        ShiftDetailDialog(
            shift = Shift(
                id = "shift-456",
                venueId = "venue-1",
                staffId = "staff-1",
                staffName = "María González",
                startTime = "2025-01-15T09:00:00Z",
                endTime = "2025-01-15T17:30:00Z",
                status = ShiftStatus.CLOSED,
                startingCash = BigDecimal("1000.00"),
                endingCash = BigDecimal("1450.00"),
                totalSales = BigDecimal("2850.75"),
                totalTips = BigDecimal("285.00"),
                totalOrders = 28,
                totalCashPayments = BigDecimal("1200.00"),
                totalCardPayments = BigDecimal("1450.75"),
                totalVoucherPayments = BigDecimal("150.00"),
                totalOtherPayments = BigDecimal("50.00"),
                totalProductsSold = 45,
                durationMinutes = 510  // 8h 30m
            ),
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "PAX A910S — caja cuadrada")
@Composable
private fun CashReconciliationResultPreview() {
    AvoqadoTheme {
        ShiftClosedContent(
            shift = Shift(
                id = "shift-result",
                venueId = "venue-1",
                staffId = "staff-1",
                staffName = "María González",
                startTime = "2025-01-15T09:00:00Z",
                endTime = "2025-01-15T17:30:00Z",
                status = ShiftStatus.CLOSED,
                startingCash = BigDecimal("1000.00"),
                endingCash = BigDecimal("4200.00"),
                totalSales = BigDecimal("3900.00"),
                totalTips = BigDecimal("250.00"),
                totalOrders = 28,
                totalCashPayments = BigDecimal("3200.00"),
                totalCardPayments = BigDecimal("700.00"),
                totalVoucherPayments = BigDecimal.ZERO,
                totalOtherPayments = BigDecimal.ZERO,
                totalProductsSold = 45,
                durationMinutes = 510,
                cashDeclared = BigDecimal("4200.00"),
                cashDifference = BigDecimal("0.00"),
                reconciliation = com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationResult(
                    outcome = CashReconciliationOutcome.APPLIED,
                    cashDeclared = BigDecimal("4200.00"),
                    cashDifference = BigDecimal("0.00")
                )
            ),
            reconciliationAction = CashReconciliationAction.COUNTED,
            onDone = {}
        )
    }
}
