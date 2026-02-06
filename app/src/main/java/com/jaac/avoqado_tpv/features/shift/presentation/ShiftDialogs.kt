package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import java.math.BigDecimal

/**
 * Open Shift Dialog
 *
 * Modal dialog for opening a new shift with starting cash amount.
 * Follows Toast/Square POS pattern for shift initialization.
 *
 * **Features:**
 * - Numeric input for starting cash
 * - Input validation (non-negative numbers)
 * - Suggested quick amounts (0, 500, 1000, 2000)
 * - Cancel and confirm actions
 *
 * **Usage:**
 * ```kotlin
 * if (showOpenDialog) {
 *     OpenShiftDialog(
 *         onDismiss = { showOpenDialog = false },
 *         onConfirm = { startingCash ->
 *             viewModel.openShift(startingCash)
 *             showOpenDialog = false
 *         }
 *     )
 * }
 * ```
 *
 * @param onDismiss Callback when dialog is dismissed (cancel)
 * @param onConfirm Callback when shift is confirmed with starting cash amount
 */
@Composable
fun OpenShiftDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var startingCashText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Abrir Turno",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                Text(
                    text = "Ingresa la cantidad de efectivo inicial en caja",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Starting cash input
                OutlinedTextField(
                    value = startingCashText,
                    onValueChange = {
                        startingCashText = it
                        isError = it.isNotEmpty() && (it.toDoubleOrNull() == null || it.toDouble() < 0)
                    },
                    label = { Text("Efectivo Inicial") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AttachMoney,
                            contentDescription = null
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isError) {
                    Text(
                        text = "Ingresa un monto válido (número positivo)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick amount buttons
                Text(
                    text = "Montos sugeridos:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Primera fila: $0 y $500
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { startingCashText = "0" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "$0", fontSize = 14.sp)
                        }
                        OutlinedButton(
                            onClick = { startingCashText = "500" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "$500", fontSize = 14.sp)
                        }
                    }

                    // Segunda fila: $1000 y $2000
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { startingCashText = "1000" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "$1000", fontSize = 14.sp)
                        }
                        OutlinedButton(
                            onClick = { startingCashText = "2000" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "$2000", fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons - ⭐ Stacked vertically (world-class pattern)
                // Primary action first, secondary below
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AvoqadoButton(
                        text = "Abrir Turno",
                        onClick = {
                            val amount = startingCashText.toDoubleOrNull()
                            if (amount != null && amount >= 0) {
                                onConfirm(amount)
                            }
                        },
                        enabled = !isError && startingCashText.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()  // ⭐ Full width
                    )

                    AvoqadoSecondaryButton(
                        text = "Cancelar",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()  // ⭐ Full width
                    )
                }
            }
        }
    }
}

/**
 * Close Shift Dialog
 *
 * Modal dialog for closing an active shift with automatic calculation summary.
 * Shows calculated metrics before confirming.
 *
 * **Features:**
 * - Displays shift summary (sales, products, duration)
 * - Shows payment breakdown (cash, card, voucher, other)
 * - Confirmation before closing
 * - No manual input (backend auto-calculates)
 *
 * **Usage:**
 * ```kotlin
 * if (showCloseDialog) {
 *     CloseShiftDialog(
 *         shift = currentShift,
 *         onDismiss = { showCloseDialog = false },
 *         onConfirm = {
 *             viewModel.closeShift()
 *             showCloseDialog = false
 *         }
 *     )
 * }
 * ```
 *
 * @param shift Active shift to close
 * @param onDismiss Callback when dialog is dismissed (cancel)
 * @param onConfirm Callback when close is confirmed
 */
@Composable
fun CloseShiftDialog(
    shift: Shift,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Cerrar Turno",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Staff name
                Text(
                    text = shift.staffName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Shift summary
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Total sales
                    SummaryRow(label = "Ventas Totales", value = "$${shift.totalSales}")

                    // Products sold
                    SummaryRow(label = "Productos Vendidos", value = "${shift.totalProductsSold}")

                    // Orders
                    SummaryRow(label = "Órdenes", value = "${shift.totalOrders}")

                    // Duration
                    val duration = shift.durationMinutes?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                    } ?: "Calculando..."
                    SummaryRow(label = "Duración", value = duration)

                    Spacer(modifier = Modifier.height(8.dp))

                    // Payment breakdown header
                    Text(
                        text = "Desglose de Pagos:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Cash
                    SummaryRow(label = "  Efectivo", value = "$${shift.totalCashPayments}")

                    // Card
                    SummaryRow(label = "  Tarjeta", value = "$${shift.totalCardPayments}")

                    // Voucher
                    if (shift.totalVoucherPayments > BigDecimal.ZERO) {
                        SummaryRow(label = "  Vales/Wallet", value = "$${shift.totalVoucherPayments}")
                    }

                    // Other
                    if (shift.totalOtherPayments > BigDecimal.ZERO) {
                        SummaryRow(label = "  Otros", value = "$${shift.totalOtherPayments}")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Warning message
                Text(
                    text = "¿Estás seguro de cerrar este turno?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons - ⭐ Stacked vertically (world-class pattern)
                // Destructive action first, cancel below
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ⭐ Custom error button (red) for destructive action
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()  // ⭐ Full width
                            .height(48.dp),
                        shape = RoundedCornerShape(28.dp),  // ⭐ Fully rounded like Avoqado buttons
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.avoqadoColors.statusError,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Cerrar Turno",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    AvoqadoSecondaryButton(
                        text = "Cancelar",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()  // ⭐ Full width
                    )
                }
            }
        }
    }
}

/**
 * Summary Row Component
 *
 * Reusable row for displaying label-value pairs in dialogs.
 */
@Composable
private fun SummaryRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun OpenShiftDialogPreview() {
    AvoqadoTheme {
        OpenShiftDialog(
            onDismiss = {},
            onConfirm = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CloseShiftDialogPreview() {
    AvoqadoTheme {
        CloseShiftDialog(
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
            onDismiss = {},
            onConfirm = {}
        )
    }
}
