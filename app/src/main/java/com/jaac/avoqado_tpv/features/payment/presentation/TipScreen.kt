package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.TipInputBottomSheet
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
 * Clean, full-screen layout without cards (inspired by AvoqadoPOS)
 * Quick percentage buttons + custom amount modal with $/% toggle
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
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentTipAmount = when {
        customTipAmount != null -> customTipAmount
        selectedTipPercentage != null -> calculateTipAmount(subtotal, selectedTipPercentage)
        else -> "0"
    }

    var showCustomTipModal by remember { mutableStateOf(false) }

    ResponsiveScaffold(
        modifier = modifier,
        scrollable = false,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        // 3-zone layout: Header (reserve space) → Content (centered) → Footer (buttons)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = sizes.paddingScreen),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // HEADER ZONE: Reserved space for title (handled by AvoqadoTopBar)
            // No content needed here - title is in navigation bar

            // Center content vertically
            Spacer(modifier = Modifier.weight(1f))

            // CONTENT ZONE: Main content (centered)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Quick tip percentage buttons (10%, 15%, 20%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
                ) {
                    listOf(10, 15, 20).forEach { percentage ->
                        val isSelected = selectedTipPercentage == percentage && customTipAmount == null
                        val tipAmount = calculateTipAmount(subtotal, percentage)

                        TipPercentageCard(
                            percentage = percentage,
                            amount = tipAmount,
                            isSelected = isSelected,
                            onClick = { onTipPercentageSelected(percentage) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Custom amount button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .shadow(
                            elevation = if (customTipAmount != null) 4.dp else 1.dp,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { showCustomTipModal = true }
                        .background(
                            color = if (customTipAmount != null)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = if (customTipAmount != null) 2.dp else 1.dp,
                            color = if (customTipAmount != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (customTipAmount != null) {
                            "Monto personalizado: $$customTipAmount"
                        } else {
                            "Monto personalizado"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (customTipAmount != null) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Center content vertically
            Spacer(modifier = Modifier.weight(1f))

            // FOOTER ZONE: Action buttons (fixed at bottom)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
                ) {
                    // Skip tip button
                    AvoqadoSecondaryButton(
                        text = "Sin propina",
                        onClick = onSkipTip,
                        modifier = Modifier.weight(1f)
                    )

                    // Continue button
                    AvoqadoButton(
                        text = "Continuar",
                        onClick = onContinue,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(sizes.spacingSmall))
            }
        }

        // Custom tip modal with keyboard + $/% toggle
        if (showCustomTipModal) {
            TipInputBottomSheet(
                subtotal = subtotal,
                onDismiss = { showCustomTipModal = false },
                onConfirm = { amount ->
                    onCustomTipSelected(amount)
                    showCustomTipModal = false
                }
            )
        }
    }
}

/**
 * TipPercentageCard - Card for quick percentage selection
 *
 * Design similar to AvoqadoPOS RatingOption
 */
@Composable
private fun TipPercentageCard(
    percentage: Int,
    amount: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(110.dp)
            .shadow(
                elevation = if (isSelected) 4.dp else 1.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .background(
                color = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Percentage
            Text(
                text = "$percentage%",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Amount
            Text(
                text = "$$amount",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

@Preview(name = "No Tip Selected", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreenNoSelectionPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "100.00",
            selectedTipPercentage = null,
            customTipAmount = null,
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}

@Preview(name = "15% Tip Selected", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreen15PercentPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "200.00",
            selectedTipPercentage = 15,
            customTipAmount = null,
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}

@Preview(name = "Custom Tip", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreenCustomPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "150.00",
            selectedTipPercentage = null,
            customTipAmount = "25.00",
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}
