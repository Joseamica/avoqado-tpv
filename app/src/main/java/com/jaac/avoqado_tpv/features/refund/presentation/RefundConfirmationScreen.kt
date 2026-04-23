package com.jaac.avoqado_tpv.features.refund.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Refund Confirmation Screen
 *
 * Square POS Pattern:
 * User reviews refund details before processing. This is a critical checkpoint
 * to prevent accidental refunds and ensure proper reason documentation.
 *
 * **Multi-Merchant Critical:**
 * ⚠️ The refund MUST use the same merchantAccountId from the original payment.
 * This is validated in the caller (PaymentsScreen/PaymentDetailBottomSheet).
 *
 * **Flow:**
 * PaymentsScreen → PaymentDetailBottomSheet → RefundConfirmationScreen → PaymentScreen (TransType.REFUND)
 *
 * @param paymentId Original payment ID
 * @param orderNumber Order number (if applicable)
 * @param originalAmount Original payment total
 * @param originalTipAmount Original tip amount
 * @param paymentMethod Payment method (CARD, CASH, etc.)
 * @param createdAt Payment timestamp
 * @param merchantAccountId Merchant account (CRITICAL for multi-merchant)
 * @param blumonSerialNumber Terminal serial (required for SDK)
 * @param refundedAmount Amount already refunded (for partial refund tracking)
 * @param onNavigateBack Called when user cancels/goes back
 * @param onConfirmRefund Called when user confirms refund (amount, reason)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefundConfirmationScreen(
    paymentId: String,
    orderNumber: String?,
    originalAmount: BigDecimal,
    originalTipAmount: BigDecimal,
    paymentMethod: String,
    createdAt: Instant,
    merchantAccountId: String,
    blumonSerialNumber: String,
    refundedAmount: BigDecimal = BigDecimal.ZERO,
    onNavigateBack: () -> Unit,
    onConfirmRefund: (
        amount: BigDecimal,
        reason: RefundReason,
        tipRefundCents: Int?,
    ) -> Unit,
) {
    // Calculate refundable amount
    val maxRefundable = (originalAmount - refundedAmount).coerceAtLeast(BigDecimal.ZERO)
    val hasPartialRefund = refundedAmount > BigDecimal.ZERO

    // State
    var refundAmountText by remember { mutableStateOf(maxRefundable.setScale(2, RoundingMode.HALF_UP).toString()) }
    var selectedReason by remember { mutableStateOf(RefundReason.CUSTOMER_REQUEST) }
    var isPartialRefund by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    // Default ON → backend default (proportional split between sale and tip).
    // OFF → we send tipRefundCents=0 so the staff tip ledger stays intact.
    // Only surfaced when the original payment has a tip.
    var includeTip by remember { mutableStateOf(true) }

    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Parse and validate amount
    val refundAmount = remember(refundAmountText) {
        try {
            BigDecimal(refundAmountText.ifBlank { "0" }).setScale(2, RoundingMode.HALF_UP)
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    val isValidAmount = refundAmount > BigDecimal.ZERO && refundAmount <= maxRefundable

    // Currency formatter
    val currencyFormatter = remember {
        NumberFormat.getCurrencyInstance(Locale("es", "MX")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }
    }

    // Date formatter
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
            .withZone(ZoneId.of("America/Mexico_City"))
    }

    Scaffold(
        topBar = {
            com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar(
                title = "Confirmar Reembolso",
                onNavigationClick = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ═══════════════════════════════════════════════════════════════
            // WARNING BANNER
            // ═══════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Esta acción no se puede deshacer. El dinero será devuelto al cliente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // ORIGINAL PAYMENT INFO
            // ═══════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pago Original",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Amount
                    InfoRow(
                        label = "Monto Total",
                        value = currencyFormatter.format(originalAmount)
                    )

                    if (originalTipAmount > BigDecimal.ZERO) {
                        InfoRow(
                            label = "Propina incluida",
                            value = currencyFormatter.format(originalTipAmount)
                        )
                    }

                    // Order number
                    if (!orderNumber.isNullOrBlank()) {
                        InfoRow(label = "Orden", value = orderNumber)
                    }

                    // Payment method
                    InfoRow(label = "Método", value = paymentMethod)

                    // Date
                    InfoRow(label = "Fecha", value = dateFormatter.format(createdAt))

                    // Partial refund status
                    if (hasPartialRefund) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow(
                            label = "Ya reembolsado",
                            value = currencyFormatter.format(refundedAmount),
                            valueColor = MaterialTheme.colorScheme.error
                        )
                        InfoRow(
                            label = "Disponible para reembolso",
                            value = currencyFormatter.format(maxRefundable),
                            valueColor = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // REFUND AMOUNT
            // ═══════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Monto a Reembolsar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Partial refund toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reembolso parcial",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = isPartialRefund,
                            onCheckedChange = { checked ->
                                isPartialRefund = checked
                                if (!checked) {
                                    // Reset to full refundable amount
                                    refundAmountText = maxRefundable.setScale(2, RoundingMode.HALF_UP).toString()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isPartialRefund) {
                        // Amount input for partial refund
                        OutlinedTextField(
                            value = refundAmountText,
                            onValueChange = { newValue ->
                                // Only allow valid decimal input
                                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                                    refundAmountText = newValue
                                }
                            },
                            label = { Text("Monto") },
                            prefix = { Text("$") },
                            supportingText = {
                                Text("Máximo: ${currencyFormatter.format(maxRefundable)}")
                            },
                            isError = !isValidAmount && refundAmountText.isNotBlank(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Full refund display
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = currencyFormatter.format(maxRefundable),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp)
                            )
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // TIP INCLUDE TOGGLE — only when the original payment had a tip.
            // Does NOT change what Blumon SDK returns to the cardholder;
            // only affects internal sale/tip booking.
            // ═══════════════════════════════════════════════════════════════
            if (originalTipAmount > BigDecimal.ZERO) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeTip,
                            onCheckedChange = { includeTip = it },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Incluir propina en el reembolso",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (includeTip) {
                                    "Se divide proporcionalmente entre venta y propina del pago original."
                                } else {
                                    "Solo se reembolsa el producto; la propina del mesero queda intacta."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = currencyFormatter.format(originalTipAmount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ═══════════════════════════════════════════════════════════════
            // REFUND REASON
            // ═══════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .selectableGroup()
                ) {
                    Text(
                        text = "Motivo del Reembolso",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    RefundReason.values().forEach { reason ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedReason == reason,
                                    onClick = { selectedReason = reason },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedReason == reason,
                                onClick = null // handled by selectable
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = reason.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = reason.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Error message
            if (showError) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ═══════════════════════════════════════════════════════════════
            // ACTION BUTTONS
            // ═══════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Confirm button
                Button(
                    onClick = {
                        // Validate
                        if (!isValidAmount) {
                            showError = true
                            errorMessage = "El monto de reembolso debe ser mayor a $0 y menor o igual a ${currencyFormatter.format(maxRefundable)}"
                            return@Button
                        }

                        showError = false
                        // Tip-split override: null when original had no tip OR user
                        // left checkbox ON (proportional default). Send 0 when the
                        // user unchecks it so the refund books 100% against sale.
                        val tipOverrideCents: Int? = if (originalTipAmount > BigDecimal.ZERO && !includeTip) 0 else null
                        onConfirmRefund(refundAmount, selectedReason, tipOverrideCents)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    enabled = isValidAmount
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Procesar Reembolso ${currencyFormatter.format(refundAmount)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Cancel button
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Info Row Component
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PREVIEWS
// ═══════════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun RefundConfirmationScreenPreview() {
    AvoqadoTheme {
        RefundConfirmationScreen(
            paymentId = "payment-123",
            orderNumber = "ORD-001",
            originalAmount = BigDecimal("150.00"),
            originalTipAmount = BigDecimal("25.00"),
            paymentMethod = "Tarjeta",
            createdAt = Instant.now(),
            merchantAccountId = "merchant-abc",
            blumonSerialNumber = "PAX123456",
            onNavigateBack = {},
            onConfirmRefund = { _, _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RefundConfirmationScreenPartialPreview() {
    AvoqadoTheme {
        RefundConfirmationScreen(
            paymentId = "payment-456",
            orderNumber = null,
            originalAmount = BigDecimal("200.00"),
            originalTipAmount = BigDecimal.ZERO,
            paymentMethod = "Tarjeta",
            createdAt = Instant.now(),
            merchantAccountId = "merchant-xyz",
            blumonSerialNumber = "PAX789012",
            refundedAmount = BigDecimal("50.00"),
            onNavigateBack = {},
            onConfirmRefund = { _, _, _ -> }
        )
    }
}
