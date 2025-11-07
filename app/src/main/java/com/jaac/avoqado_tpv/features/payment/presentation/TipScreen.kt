package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTipSelector
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tip screen - Collect tip amount before payment
 *
 * **Flow:**
 * 1. User selects tip percentage (10%, 15%, 20%) or custom amount
 * 2. User can "Continuar" (with tip) or "Sin propina" (skip tip)
 * 3. Proceeds to merchant selection
 *
 * **Design:**
 * Quick tip selection with clear total display
 */
@Composable
fun TipScreen(
    subtotal: String,
    selectedTipPercentage: Int?,
    customTipAmount: String?,
    onTipPercentageSelected: (Int) -> Unit,
    onCustomTipSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onSkipTip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTipAmount = when {
        customTipAmount != null -> customTipAmount
        selectedTipPercentage != null -> calculateTipAmount(subtotal, selectedTipPercentage)
        else -> "0"
    }

    val totalAmount = calculateTotal(subtotal, currentTipAmount)

    ResponsiveScaffold(
        modifier = modifier,
        scrollable = false,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        AvoqadoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.paddingScreen)
        ) {
            Column(
                modifier = Modifier.padding(sizes.paddingCard),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Agregar propina",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(sizes.spacingMedium))

                // Tip selector
                AvoqadoTipSelector(
                    subtotal = subtotal,
                    selectedTipPercentage = selectedTipPercentage,
                    customTipAmount = customTipAmount,
                    onTipPercentageSelected = onTipPercentageSelected,
                    onCustomTipSelected = onCustomTipSelected
                )

                Spacer(modifier = Modifier.height(sizes.spacingMedium))

                // Total display
                if (currentTipAmount.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) ?: 0 > 0) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Propina: $$currentTipAmount",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(sizes.spacingXSmall))
                        Text(
                            text = "Total: $$totalAmount",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
                ) {
                    // Skip tip button (always enabled)
                    AvoqadoSecondaryButton(
                        text = "Sin propina",
                        onClick = onSkipTip,
                        modifier = Modifier.weight(1f)
                    )

                    // Continue button (always enabled - can continue with or without tip)
                    AvoqadoButton(
                        text = "Continuar",
                        onClick = onContinue,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Helper text
                Spacer(modifier = Modifier.height(sizes.spacingSmall))
                Text(
                    text = "La propina es opcional",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Calculate tip amount based on percentage
 */
private fun calculateTipAmount(subtotal: String, percentage: Int): String {
    val subtotalDecimal = subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val tip = subtotalDecimal.multiply(BigDecimal(percentage))
        .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
    return tip.toString()
}

/**
 * Calculate total amount (subtotal + tip)
 */
private fun calculateTotal(subtotal: String, tipAmount: String): String {
    val subtotalDecimal = subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val tipDecimal = tipAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO
    return subtotalDecimal.add(tipDecimal).toString()
}

@Preview(showBackground = true)
@Composable
private fun TipScreenPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // No tip selected
            TipScreen(
                subtotal = "100.00",
                selectedTipPercentage = null,
                customTipAmount = null,
                onTipPercentageSelected = {},
                onCustomTipSelected = {},
                onContinue = {},
                onSkipTip = {},
                modifier = Modifier.weight(1f)
            )

            // 15% tip selected
            TipScreen(
                subtotal = "200.00",
                selectedTipPercentage = 15,
                customTipAmount = null,
                onTipPercentageSelected = {},
                onCustomTipSelected = {},
                onContinue = {},
                onSkipTip = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}
