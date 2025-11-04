package com.jaac.avoqado_tpv.features.authentication.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * PIN Indicator Component
 *
 * Visual indicator showing how many PIN digits have been entered.
 * Follows Square POS and Toast POS pattern of filled/unfilled circles.
 *
 * **Design:**
 * - Filled circles for entered digits
 * - Outline circles for remaining digits
 * - Animates as user types (implicit animation via state change)
 * - Standard: 4 digits (Square/Toast POS standard)
 *
 * **Usage:**
 * ```kotlin
 * PinIndicator(
 *     pinLength = 3,    // User has entered 3 digits
 *     maxLength = 4     // Standard 4-digit PIN
 * )
 * ```
 *
 * @param pinLength Current number of digits entered (0-maxLength)
 * @param maxLength Maximum PIN length (typically 4)
 * @param modifier Modifier for the indicator container
 */
@Composable
fun PinIndicator(
    pinLength: Int,
    maxLength: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(maxLength) { index ->
            PinDot(isFilled = index < pinLength)
        }
    }
}

/**
 * Single PIN dot indicator
 *
 * Filled circle when digit is entered, outline circle when empty.
 */
@Composable
private fun PinDot(
    isFilled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(
                color = if (isFilled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            )
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun PinIndicatorEmptyPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinIndicator(pinLength = 0, maxLength = 4)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinIndicatorPartialPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinIndicator(pinLength = 2, maxLength = 4)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinIndicatorFullPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinIndicator(pinLength = 4, maxLength = 4)
        }
    }
}
