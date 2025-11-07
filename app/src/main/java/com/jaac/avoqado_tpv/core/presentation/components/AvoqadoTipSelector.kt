package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tip selector component
 *
 * Displays quick tip percentage buttons (10%, 15%, 20%) and custom amount option.
 * Calculates tip amount based on subtotal.
 *
 * @param subtotal Base amount for tip calculation
 * @param selectedTipPercentage Currently selected percentage (10, 15, or 20)
 * @param customTipAmount Custom tip amount if selected
 * @param onTipPercentageSelected Callback when percentage button tapped
 * @param onCustomTipSelected Callback when custom amount entered
 * @param modifier Optional modifier
 */
@Composable
fun AvoqadoTipSelector(
    subtotal: String,
    selectedTipPercentage: Int?,
    customTipAmount: String?,
    onTipPercentageSelected: (Int) -> Unit,
    onCustomTipSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val subtotalDecimal = subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Subtotal: $$subtotal MXN",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Quick tip buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf(10, 15, 20).forEach { percentage ->
                val isSelected = selectedTipPercentage == percentage && customTipAmount == null
                val tipAmount = calculateTipAmount(subtotalDecimal, percentage)

                OutlinedButton(
                    onClick = { onTipPercentageSelected(percentage) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$percentage%")
                        Text(
                            text = "$$tipAmount",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom amount button
        var showCustomDialog by remember { mutableStateOf(false) }

        OutlinedButton(
            onClick = { showCustomDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (customTipAmount != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Text(
                text = if (customTipAmount != null) {
                    "Monto personalizado: $$customTipAmount"
                } else {
                    "Monto personalizado"
                }
            )
        }

        // Custom amount dialog
        if (showCustomDialog) {
            var customAmount by remember { mutableStateOf(customTipAmount ?: "") }

            AlertDialog(
                onDismissRequest = { showCustomDialog = false },
                title = { Text("Propina personalizada") },
                text = {
                    AvoqadoTextField(
                        value = customAmount,
                        onValueChange = { customAmount = it },
                        label = "Monto (MXN)",
                        placeholder = "0.00"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onCustomTipSelected(customAmount)
                            showCustomDialog = false
                        }
                    ) {
                        Text("Aceptar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

/**
 * Calculate tip amount based on percentage
 */
private fun calculateTipAmount(subtotal: BigDecimal, percentage: Int): String {
    val tip = subtotal.multiply(BigDecimal(percentage))
        .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
    return tip.toString()
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTipSelectorPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // No selection
            AvoqadoTipSelector(
                subtotal = "100.00",
                selectedTipPercentage = null,
                customTipAmount = null,
                onTipPercentageSelected = {},
                onCustomTipSelected = {}
            )

            // 15% selected
            AvoqadoTipSelector(
                subtotal = "200.00",
                selectedTipPercentage = 15,
                customTipAmount = null,
                onTipPercentageSelected = {},
                onCustomTipSelected = {}
            )

            // Custom amount
            AvoqadoTipSelector(
                subtotal = "150.00",
                selectedTipPercentage = null,
                customTipAmount = "25.00",
                onTipPercentageSelected = {},
                onCustomTipSelected = {}
            )
        }
    }
}
