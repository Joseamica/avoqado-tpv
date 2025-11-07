package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoRatingInput
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes

/**
 * Rating screen - Collect user feedback before payment
 *
 * **Flow:**
 * 1. User taps stars to rate (optional)
 * 2. User can "Continuar" (if rated) or "Saltar" (skip rating)
 * 3. Proceeds to tip screen
 *
 * **Design:**
 * Simple and fast - only overall rating (1-5 stars)
 * No detailed categories for now (Food, Service, etc.)
 */
@Composable
fun RatingScreen(
    currentRating: Int,
    onRatingChange: (Int) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    text = "¿Cómo fue tu experiencia?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(sizes.spacingMedium))

                // Rating input
                AvoqadoRatingInput(
                    rating = currentRating,
                    onRatingChange = onRatingChange,
                    label = null  // Title above already explains
                )

                Spacer(modifier = Modifier.height(sizes.spacingLarge))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
                ) {
                    // Skip button (always enabled)
                    AvoqadoSecondaryButton(
                        text = "Saltar",
                        onClick = onSkip,
                        modifier = Modifier.weight(1f)
                    )

                    // Continue button (enabled if rated)
                    AvoqadoButton(
                        text = "Continuar",
                        onClick = onContinue,
                        enabled = currentRating > 0,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Helper text
                Spacer(modifier = Modifier.height(sizes.spacingSmall))
                Text(
                    text = if (currentRating == 0) {
                        "Califica o salta"
                    } else {
                        "Gracias por calificar"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RatingScreenPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // No rating yet
            RatingScreen(
                currentRating = 0,
                onRatingChange = {},
                onContinue = {},
                onSkip = {},
                modifier = Modifier.weight(1f)
            )

            // With rating
            RatingScreen(
                currentRating = 4,
                onRatingChange = {},
                onContinue = {},
                onSkip = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}
