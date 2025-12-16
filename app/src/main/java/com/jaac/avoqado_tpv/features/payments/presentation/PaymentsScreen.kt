package com.jaac.avoqado_tpv.features.payments.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentStatus
import com.jaac.avoqado_tpv.features.payments.domain.models.StaffSummary
import com.jaac.avoqado_tpv.features.payments.presentation.components.PaymentDetailBottomSheet
import java.math.BigDecimal
import java.time.Instant

/**
 * Payments Screen
 *
 * Displays payment history with pagination and filtering.
 *
 * Pattern: Toast POS + Square Terminal
 * - Paginated list with "Load More" button
 * - Date range filter
 * - Payment method filter
 * - Pull-to-refresh support
 */
@Composable
fun PaymentsScreen(
    modifier: Modifier = Modifier,
    viewModel: PaymentsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onNavigateToRefund: (Payment) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filterDateRange by viewModel.filterDateRange.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    // Print mode states
    val isPrintMode by viewModel.isPrintMode.collectAsStateWithLifecycle()
    val selectedPaymentsForPrint by viewModel.selectedPaymentsForPrint.collectAsStateWithLifecycle()
    val showPrintDialog by viewModel.showPrintDialog.collectAsStateWithLifecycle()

    // Payment detail states (for bottom sheet + refund)
    val showPaymentDetailSheet by viewModel.showPaymentDetailSheet.collectAsStateWithLifecycle()
    val selectedPaymentForDetail by viewModel.selectedPaymentForDetail.collectAsStateWithLifecycle()
    val canProcessRefund by viewModel.canProcessRefund.collectAsStateWithLifecycle()
    val paymentForRefund by viewModel.paymentForRefund.collectAsStateWithLifecycle()

    // Handle refund navigation
    LaunchedEffect(paymentForRefund) {
        paymentForRefund?.let { payment ->
            onNavigateToRefund(payment)
            viewModel.clearRefundNavigation()
        }
    }

    var showFilterDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Historial de Pagos",
                titleStyle = MaterialTheme.typography.titleMedium,
                onNavigationClick = onBack,
                actions = {
                    // Print mode toggle
                    if (isPrintMode) {
                        TextButton(onClick = { viewModel.togglePrintMode(false) }) {
                            Text("Cancelar")
                        }
                    } else {
                        IconButton(onClick = { viewModel.togglePrintMode(true) }) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = "Seleccionar para imprimir",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Filter button (only when not in print mode)
                    if (!isPrintMode) {
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filtros",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        // FAB for printing when payments selected
        floatingActionButton = {
            if (isPrintMode && selectedPaymentsForPrint.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.showPrintDialog() },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null
                        )
                        Text("${selectedPaymentsForPrint.size}")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when (val currentState = state) {
            is PaymentsState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    AvoqadoLoadingOverlay(message = "Cargando pagos...")
                }
            }

            is PaymentsState.Success -> {
                PaymentsContent(
                    modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
                    payments = currentState.payments,
                    hasMore = currentState.hasMore,
                    currentPage = currentState.currentPage,
                    totalPages = currentState.totalPages,
                    totalCount = currentState.totalCount,
                    dateRangeLabel = filterDateRange.label,
                    isRefreshing = isRefreshing,
                    isPrintMode = isPrintMode,
                    selectedPaymentIds = selectedPaymentsForPrint,
                    onPaymentClick = { payment ->
                        if (isPrintMode) {
                            viewModel.togglePaymentSelection(payment)
                        } else {
                            // Show payment detail bottom sheet
                            viewModel.showPaymentDetail(payment)
                        }
                    },
                    onLoadMore = { viewModel.loadMore() },
                    onRefresh = { viewModel.refresh() }
                )
            }

            is PaymentsState.LoadingMore -> {
                PaymentsContent(
                    modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
                    payments = currentState.payments,
                    hasMore = true,
                    currentPage = currentState.currentPage,
                    totalPages = currentState.totalPages,
                    totalCount = currentState.totalCount,
                    dateRangeLabel = filterDateRange.label,
                    isLoadingMore = true,
                    isRefreshing = isRefreshing,
                    isPrintMode = isPrintMode,
                    selectedPaymentIds = selectedPaymentsForPrint,
                    onPaymentClick = { payment ->
                        if (isPrintMode) {
                            viewModel.togglePaymentSelection(payment)
                        } else {
                            // Show payment detail bottom sheet
                            viewModel.showPaymentDetail(payment)
                        }
                    },
                    onLoadMore = {},
                    onRefresh = { viewModel.refresh() }
                )
            }

            is PaymentsState.Error -> {
                ErrorContent(
                    modifier = Modifier.padding(paddingValues),
                    message = currentState.message,
                    onRetry = { viewModel.loadPayments() }
                )
            }
        }

        // Filter Dialog
        if (showFilterDialog) {
            FilterDialog(
                currentDateRange = filterDateRange,
                onDateRangeSelected = { filter ->
                    viewModel.setDateRangeFilter(filter)
                    showFilterDialog = false
                },
                onDismiss = { showFilterDialog = false }
            )
        }

        // Print Dialog
        if (showPrintDialog) {
            PaymentPrintDialog(
                selectedCount = selectedPaymentsForPrint.size,
                onConfirm = { printMode ->
                    viewModel.printSelectedPayments(printMode)
                },
                onDismiss = { viewModel.dismissPrintDialog() }
            )
        }

        // Payment Detail Bottom Sheet (for refund initiation)
        if (showPaymentDetailSheet && selectedPaymentForDetail != null) {
            PaymentDetailBottomSheet(
                payment = selectedPaymentForDetail!!,
                canProcessRefund = canProcessRefund,
                onDismiss = { viewModel.dismissPaymentDetail() },
                onRefundClick = { payment ->
                    viewModel.initiateRefund(payment)
                }
            )
        }
    }
}

/**
 * Payments Content
 *
 * Displays list of payments with pagination.
 */
@Composable
private fun PaymentsContent(
    modifier: Modifier = Modifier,
    payments: List<Payment>,
    hasMore: Boolean,
    currentPage: Int,
    totalPages: Int,
    totalCount: Int,
    dateRangeLabel: String,
    isLoadingMore: Boolean = false,
    isRefreshing: Boolean = false,
    isPrintMode: Boolean = false,
    selectedPaymentIds: Set<String> = emptySet(),
    onPaymentClick: (Payment) -> Unit = {},
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit
) {
    // FIX: Use payments.size as fallback when totalCount is 0 but payments exist
    val displayCount = if (totalCount > 0) totalCount else payments.size

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Summary Header - Full width banner (no margins, before ResponsiveScaffold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Title and count in one line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = dateRangeLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$displayCount pagos",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Show selection count when in print mode
                if (isPrintMode && selectedPaymentIds.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${selectedPaymentIds.size} seleccionados",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Print mode hint
        if (isPrintMode) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "Toca los pagos para seleccionarlos (máx. 20)",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Content with ResponsiveScaffold (for LocalResponsiveSizes)
        ResponsiveScaffold(
            modifier = Modifier.weight(1f),
            scrollable = false
        ) {
            val sizes = LocalResponsiveSizes.current

            // Payment List with Pull-to-Refresh
            AvoqadoPullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (payments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(sizes.paddingScreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No hay pagos en este período",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(sizes.paddingScreen),
                        verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
                    ) {
                        items(
                            items = payments,
                            key = { payment -> payment.id }
                        ) { payment ->
                            PaymentCard(
                                payment = payment,
                                isPrintMode = isPrintMode,
                                isSelected = payment.id in selectedPaymentIds,
                                onClick = { onPaymentClick(payment) }
                            )
                        }

                        // Load More Button
                        if (hasMore) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator()
                                    } else {
                                        Button(
                                            onClick = onLoadMore,
                                            modifier = Modifier.padding(vertical = sizes.spacingMedium)
                                        ) {
                                            Text("Cargar más ($currentPage/$totalPages)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Payment Card
 *
 * Displays individual payment information.
 * Supports print mode with checkbox selection.
 */
@Composable
private fun PaymentCard(
    payment: Payment,
    modifier: Modifier = Modifier,
    isPrintMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {}
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isPrintMode) {
                    Modifier.toggleable(
                        value = isSelected,
                        onValueChange = { onClick() }
                    )
                } else {
                    Modifier.clickable { onClick() }
                }
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(sizes.paddingScreen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox (only in print mode)
            if (isPrintMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Header Row (Amount + Method/Refund Badge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = payment.formatTotalAmount(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        // Red for refunds, primary for normal payments
                        color = if (payment.isRefund)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Refund badge (if refund)
                        if (payment.isRefund) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "Reembolso",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        // Payment method badge
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = when (payment.method) {
                                PaymentMethod.CASH -> MaterialTheme.colorScheme.secondaryContainer
                                PaymentMethod.CARD -> MaterialTheme.colorScheme.primaryContainer
                                PaymentMethod.VOUCHER -> MaterialTheme.colorScheme.tertiaryContainer
                                PaymentMethod.OTHER -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = payment.getMethodLabel(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(sizes.spacingSmall))

                // Source (Table or Fast Payment)
                if (payment.orderNumber != null || payment.tableName != null) {
                    Row {
                        Text(
                            text = "Orden: ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = payment.orderNumber ?: "N/A",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (payment.tableName != null) {
                            Text(
                                text = " • ${payment.getSourceLabel()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = payment.getSourceLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Staff Member
                if (payment.processedBy != null) {
                    Text(
                        text = "Procesado por: ${payment.processedBy.getFullName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Timestamp
                Text(
                    text = payment.formatTimestamp(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Tip (if present)
                if (payment.tipAmount > BigDecimal.ZERO) {
                    Spacer(modifier = Modifier.height(sizes.spacingSmall))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(sizes.spacingSmall))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Propina",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = payment.formatTipAmount(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Error Content
 *
 * Displays error message with retry button.
 */
@Composable
private fun ErrorContent(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}

/**
 * Filter Dialog
 *
 * Simple date range filter dialog.
 */
@Composable
private fun FilterDialog(
    currentDateRange: DateRangeFilter,
    onDateRangeSelected: (DateRangeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtrar por período") },
        text = {
            Column {
                DateRangeFilter.entries.forEach { filter ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentDateRange == filter,
                            onClick = { onDateRangeSelected(filter) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(filter.label)
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

/**
 * Payment Print Dialog
 *
 * Dialog for selecting print options.
 * Options: Individual (one receipt per payment) or Summary (all in one receipt)
 */
@Composable
private fun PaymentPrintDialog(
    selectedCount: Int,
    onConfirm: (PaymentPrintMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(PaymentPrintMode.SUMMARY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Imprimir pagos") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Selection count
                Text(
                    text = "$selectedCount pago${if (selectedCount > 1) "s" else ""} seleccionado${if (selectedCount > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Print mode options
                Text(
                    text = "Modo de impresión",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Individual option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = selectedMode == PaymentPrintMode.INDIVIDUAL,
                            onValueChange = { selectedMode = PaymentPrintMode.INDIVIDUAL }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == PaymentPrintMode.INDIVIDUAL,
                        onClick = { selectedMode = PaymentPrintMode.INDIVIDUAL }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Individual",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Un recibo por pago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Summary option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = selectedMode == PaymentPrintMode.SUMMARY,
                            onValueChange = { selectedMode = PaymentPrintMode.SUMMARY }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == PaymentPrintMode.SUMMARY,
                        onClick = { selectedMode = PaymentPrintMode.SUMMARY }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Resumen",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Todos los pagos en un recibo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Warning for many individual prints
                if (selectedMode == PaymentPrintMode.INDIVIDUAL && selectedCount > 10) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "Se imprimirán $selectedCount recibos. Considera usar modo Resumen.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedMode) }
            ) {
                Text("Imprimir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// Previews

@Preview(showBackground = true)
@Composable
private fun PaymentCardPreview() {
    AvoqadoTheme {
        PaymentCard(
            payment = Payment(
                id = "payment-1",
                orderId = "order-1",
                orderNumber = "ORD-001",
                venueId = "venue-1",
                amount = BigDecimal("125.50"),
                tipAmount = BigDecimal("24.50"),
                totalAmount = BigDecimal("150.00"),
                method = PaymentMethod.CARD,
                processedBy = StaffSummary("staff-1", "Juan", "Pérez"),
                createdAt = Instant.now(),
                status = PaymentStatus.COMPLETED,
                tableName = "5"
            )
        )
    }
}
