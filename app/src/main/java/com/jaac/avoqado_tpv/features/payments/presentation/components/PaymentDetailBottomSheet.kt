package com.jaac.avoqado_tpv.features.payments.presentation.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentStatus
import com.jaac.avoqado_tpv.features.payments.domain.models.StaffSummary
import com.jaac.avoqado_tpv.features.payment.domain.model.OrderNumberFormatter
import java.math.BigDecimal
import java.time.Instant

/**
 * Payment Detail Bottom Sheet
 *
 * Shows full payment details with option to initiate refund.
 *
 * **Square POS Pattern:**
 * - Entry point from PaymentsScreen (tap payment card)
 * - Shows all payment info in a clean layout
 * - Refund button only visible if:
 *   1. User role is authorized (SUPERADMIN, OWNER, ADMIN)
 *   2. Payment is refundable (completed, card payment, not already refunded)
 *
 * **Multi-Merchant Critical:**
 * When initiating a refund, the payment's merchantAccountId is passed
 * to ensure the refund goes to the correct merchant account.
 *
 * @param payment The payment to display
 * @param canProcessRefund True if current user's role can process refunds
 * @param onDismiss Called when bottom sheet is dismissed
 * @param onRefundClick Called when refund button is clicked (passes payment ID)
 * @param onViewReceipt Called when "View Receipt" is clicked (future feature)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDetailBottomSheet(
    payment: Payment,
    canProcessRefund: Boolean,
    canRefundOnCurrentDevice: Boolean = payment.isRefundable(),
    nonRefundableReasonOverride: String? = null,
    onDismiss: () -> Unit,
    onRefundClick: (Payment) -> Unit,
    onSendReceiptByEmail: ((Payment, String) -> Unit)? = null,  // payment, email
    onViewReceipt: ((Payment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Email input state
    var showEmailDialog by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // ═══════════════════════════════════════════════════════════════
            // HEADER
            // ═══════════════════════════════════════════════════════════════
            Text(
                text = "Detalle del Pago",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════════
            // AMOUNT SECTION
            // ═══════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = payment.formatTotalAmount(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (payment.tipAmount > BigDecimal.ZERO) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            Text(
                                text = "Monto base: ${payment.formatAmount()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = " + ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Propina: ${payment.formatTipAmount()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    // Payment Method Badge
                    Spacer(modifier = Modifier.height(8.dp))
                    PaymentMethodBadge(method = payment.method)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════════════
            // DETAILS SECTION
            // ═══════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Order Info
                    if (payment.orderNumber != null) {
                        DetailRow(
                            icon = Icons.Filled.Receipt,
                            label = "Orden",
                            value = OrderNumberFormatter.display(payment.orderNumber) ?: payment.orderNumber ?: "N/A"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Source (Table or Fast Payment)
                    DetailRow(
                        icon = if (payment.tableName != null) Icons.Default.Person else Icons.Default.Money,
                        label = "Origen",
                        value = payment.getSourceLabel()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Processed By
                    if (payment.processedBy != null) {
                        DetailRow(
                            icon = Icons.Default.Person,
                            label = "Procesado por",
                            value = payment.processedBy.getFullName()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Timestamp
                    DetailRow(
                        icon = Icons.Default.CreditCard,
                        label = "Fecha",
                        value = payment.formatTimestamp()
                    )

                    // Status
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Estado",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PaymentStatusBadge(status = payment.status, isRefund = payment.isRefund)
                    }

                    // Refund Status (if partially refunded)
                    if (payment.refundedAmount != null && payment.refundedAmount > BigDecimal.ZERO) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Reembolsado",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "$${payment.refundedAmount.setScale(2)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!payment.isFullyRefunded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Disponible para reembolso: ${payment.formatRefundableAmount()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════════
            // ACTIONS
            // ═══════════════════════════════════════════════════════════════

            // Refund Button (only if authorized AND payment is refundable)
            val isRefundable = canRefundOnCurrentDevice
            val nonRefundableReason = nonRefundableReasonOverride ?: payment.getNonRefundableReason()

            if (canProcessRefund) {
                if (isRefundable) {
                    // Refund button
                    Button(
                        onClick = { onRefundClick(payment) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            text = "Procesar Reembolso",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Show why refund is not available
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = nonRefundableReason ?: "Este pago no puede ser reembolsado",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // User doesn't have refund permission
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Solo administradores pueden procesar reembolsos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Send Receipt by Email Button
            if (onSendReceiptByEmail != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showEmailDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enviar Recibo por Correo")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Close Button
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cerrar")
            }
        }
    }

    // Email Input Dialog
    if (showEmailDialog) {
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = { Text("Enviar Recibo") },
            text = {
                Column {
                    Text(
                        text = "Ingresa el correo electrónico donde deseas enviar el recibo:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Correo electrónico") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (emailInput.isNotBlank() && emailInput.contains("@")) {
                            onSendReceiptByEmail?.invoke(payment, emailInput.trim())
                            showEmailDialog = false
                            emailInput = ""
                        }
                    },
                    enabled = emailInput.isNotBlank() && emailInput.contains("@")
                ) {
                    Text("Enviar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showEmailDialog = false
                    emailInput = ""
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Detail Row Component
 */
@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Payment Method Badge
 */
@Composable
private fun PaymentMethodBadge(method: PaymentMethod) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when (method) {
            PaymentMethod.CASH -> MaterialTheme.colorScheme.secondaryContainer
            PaymentMethod.CARD -> MaterialTheme.colorScheme.primaryContainer
            PaymentMethod.VOUCHER -> MaterialTheme.colorScheme.tertiaryContainer
            PaymentMethod.OTHER -> MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (method) {
                    PaymentMethod.CASH -> Icons.Default.Money
                    PaymentMethod.CARD -> Icons.Default.CreditCard
                    else -> Icons.Default.CreditCard
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = method.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Payment Status Badge
 *
 * @param status Payment status (COMPLETED, PENDING, FAILED)
 * @param isRefund If true, shows "Reembolso" instead of status
 */
@Composable
private fun PaymentStatusBadge(status: PaymentStatus, isRefund: Boolean = false) {
    val (containerColor, textColor, label) = if (isRefund) {
        Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Reembolso"
        )
    } else {
        when (status) {
            PaymentStatus.COMPLETED -> Triple(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                "Completado"
            )
            PaymentStatus.PENDING -> Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
                "Pendiente"
            )
            PaymentStatus.FAILED -> Triple(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                "Fallido"
            )
        }
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PREVIEWS
// ═══════════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun PaymentDetailBottomSheetPreview() {
    AvoqadoTheme {
        PaymentDetailBottomSheet(
            payment = Payment(
                id = "payment-1",
                orderId = "order-1",
                orderNumber = "ORD-001",
                venueId = "venue-1",
                amount = BigDecimal("125.50"),
                tipAmount = BigDecimal("24.50"),
                totalAmount = BigDecimal("150.00"),
                method = PaymentMethod.CARD,
                processedBy = StaffSummary("staff-1", "Juan", "Perez"),
                createdAt = Instant.now(),
                status = PaymentStatus.COMPLETED,
                tableName = "5",
                merchantAccountId = "merchant-123",
                blumonSerialNumber = "PAX123456"
            ),
            canProcessRefund = true,
            onDismiss = {},
            onRefundClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PaymentDetailBottomSheetCashPreview() {
    AvoqadoTheme {
        PaymentDetailBottomSheet(
            payment = Payment(
                id = "payment-2",
                orderId = null,
                orderNumber = null,
                venueId = "venue-1",
                amount = BigDecimal("50.00"),
                tipAmount = BigDecimal.ZERO,
                totalAmount = BigDecimal("50.00"),
                method = PaymentMethod.CASH,
                processedBy = StaffSummary("staff-1", "Maria", "Lopez"),
                createdAt = Instant.now(),
                status = PaymentStatus.COMPLETED,
                tableName = null,
                merchantAccountId = null, // Cash payment
                blumonSerialNumber = null
            ),
            canProcessRefund = true,
            onDismiss = {},
            onRefundClick = {}
        )
    }
}
