package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.TipInputBottomSheet
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tip screen - Collect tip amount before payment
 *
 * **Flow:**
 * 1. User selects tip percentage → Header updates with new total, must press "Continuar" to advance
 * 2. User can select custom amount (opens modal) → Header updates, must press "Continuar"
 * 3. User can "Sin propina" to skip tip (text link above Continuar button)
 *
 * **Design:**
 * Clean, full-screen layout without cards (inspired by AvoqadoPOS)
 * 3 percentage buttons + custom amount modal with $/% toggle
 * "Sin propina" as text link above "Continuar" button
 * "Continuar" button required to advance (allows user to review selection)
 *
 * **Dynamic suggestions:**
 * Always shows exactly 3 percentage options:
 * - Takes first 2 from tipSuggestions (e.g., 15%, 18%)
 * - Third option is defaultTipPercentage from TPV settings (e.g., 25%)
 * Example: tipSuggestions=[15,18,20], defaultTipPercentage=25 → shows [15, 18, 25]
 *
 * **Callbacks:**
 * - onTipSelectionChanged: Called when user taps a percentage (updates state for header)
 * - onCustomTipChanged: Called when user confirms custom tip in modal (updates state for header)
 * - onTipPercentageSelected: Called when user presses "Continuar" with percentage selected (advances)
 * - onCustomTipSelected: Called when user presses "Continuar" with custom tip (advances)
 * - onSkipTip: Called when user presses "Sin propina" text (advances)
 */
@Composable
fun TipScreen(
    subtotal: String,
    selectedTipPercentage: Int?,
    customTipAmount: String?,
    tipSuggestions: List<Int> = listOf(10, 15, 20),
    defaultTipPercentage: Int? = null,
    onTipSelectionChanged: ((Int) -> Unit)? = null,
    onCustomTipChanged: ((String) -> Unit)? = null,
    onTipPercentageSelected: (Int) -> Unit,
    onCustomTipSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onSkipTip: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 🎯 Calculate dynamic tip suggestions
    // Always show exactly 3 options, sorted ascending, with default always included
    // Examples:
    // - suggestions=[15,18,20], default=25 → [15, 18, 25] (removes 20)
    // - suggestions=[15,18,20], default=10 → [10, 15, 18] (removes 20)
    // - suggestions=[15,18,20], default=18 → [15, 18, 20] (18 already exists)
    val displaySuggestions = remember(tipSuggestions, defaultTipPercentage) {
        val baseSuggestions = tipSuggestions.ifEmpty { listOf(15, 18, 20) }
        val defaultValue = defaultTipPercentage ?: baseSuggestions.lastOrNull() ?: 20

        // Combine all options, dedupe, and sort ascending
        val allOptions = (baseSuggestions + defaultValue).distinct().sorted()

        if (allOptions.size <= 3) {
            allOptions
        } else {
            // More than 3 options - pick 3 that always include the default
            val defaultIndex = allOptions.indexOf(defaultValue)

            when {
                // Default is at the start - take default + next 2
                defaultIndex == 0 -> allOptions.take(3)
                // Default is at the end - take last 3
                defaultIndex == allOptions.lastIndex -> allOptions.takeLast(3)
                // Default is in the middle - take one before, default, one after
                else -> listOf(
                    allOptions[defaultIndex - 1],
                    defaultValue,
                    allOptions[defaultIndex + 1]
                )
            }
        }
    }

    // 🎯 Use ViewModel state directly (no internal state needed)
    // ViewModel is updated via onTipSelectionChanged/onCustomTipChanged callbacks
    // Determine if "Continuar" should be enabled
    val hasSelection = selectedTipPercentage != null || customTipAmount != null

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
                // Quick tip percentage buttons (dynamic from settings)
                // 🎯 No auto-advance: Selection updates ViewModel, user must press "Continuar"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
                ) {
                    displaySuggestions.forEach { percentage ->
                        val isSelected = selectedTipPercentage == percentage && customTipAmount == null
                        val tipAmount = calculateTipAmount(subtotal, percentage)

                        TipPercentageCard(
                            percentage = percentage,
                            amount = tipAmount,
                            isSelected = isSelected,
                            onClick = {
                                // 🎯 Notify ViewModel to update state & header
                                onTipSelectionChanged?.invoke(percentage)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Custom amount button
                val isCustomSelected = customTipAmount != null
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .shadow(
                            elevation = if (isCustomSelected) 4.dp else 1.dp,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { showCustomTipModal = true }
                        .background(
                            color = if (isCustomSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = if (isCustomSelected) 2.dp else 1.dp,
                            color = if (isCustomSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCustomSelected) {
                            "Monto personalizado: $$customTipAmount"
                        } else {
                            "Monto personalizado"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal,
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
                // "Sin propina" text link - positioned above Continuar button
                Text(
                    text = "Sin propina",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onSkipTip() }
                        .padding(vertical = sizes.spacingSmall)
                )

                Spacer(modifier = Modifier.height(sizes.spacingSmall))

                // "Continuar" button - only enabled when something is selected
                AvoqadoButton(
                    text = "Continuar",
                    onClick = {
                        when {
                            customTipAmount != null -> onCustomTipSelected(customTipAmount)
                            selectedTipPercentage != null -> onTipPercentageSelected(selectedTipPercentage)
                        }
                    },
                    enabled = hasSelection,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(sizes.spacingSmall))
            }
        }

        // Custom tip modal with keyboard + $/% toggle
        // 🎯 Updates ViewModel state, user must press "Continuar" to advance
        if (showCustomTipModal) {
            TipInputBottomSheet(
                subtotal = subtotal,
                onDismiss = { showCustomTipModal = false },
                onConfirm = { amount ->
                    showCustomTipModal = false
                    // 🎯 Notify ViewModel to update state & header
                    onCustomTipChanged?.invoke(amount)
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

@Preview(name = "Default 25% (high) → [15,18,25]", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreenDefaultHighPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "100.00",
            selectedTipPercentage = null,
            customTipAmount = null,
            tipSuggestions = listOf(15, 18, 20),
            defaultTipPercentage = 25, // Shows [15, 18, 25] - removes 20
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}

@Preview(name = "Default 10% (low) → [10,15,18]", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreenDefaultLowPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "100.00",
            selectedTipPercentage = null,
            customTipAmount = null,
            tipSuggestions = listOf(15, 18, 20),
            defaultTipPercentage = 10, // Shows [10, 15, 18] - removes 20
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}

@Preview(name = "Default 18% (exists) → [15,18,20]", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreenDefaultExistsPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "100.00",
            selectedTipPercentage = 18,
            customTipAmount = null,
            tipSuggestions = listOf(15, 18, 20),
            defaultTipPercentage = 18, // Shows [15, 18, 20] - 18 already exists
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}

@Preview(name = "Custom Tip Selected", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipScreenCustomPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipScreen(
            subtotal = "150.00",
            selectedTipPercentage = null,
            customTipAmount = "25.00",
            tipSuggestions = listOf(15, 18, 20),
            defaultTipPercentage = 25,
            onTipPercentageSelected = {},
            onCustomTipSelected = {},
            onContinue = {},
            onSkipTip = {}
        )
    }
}
