package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoRatingInput
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Review screen - Collect user feedback before payment
 *
 * **Flow:**
 * 1. User taps stars to rate (optional)
 * 2. User can "Continuar" (if rated) or "Saltar" (skip review)
 * 3. Proceeds to tip screen
 *
 * **Design:**
 * Clean, modern layout without card wrapper
 * Simple and fast - only overall review (1-5 stars)
 * No detailed categories for now (Food, Service, etc.)
 */
@Composable
fun ReviewScreen(
    currentReview: Int,
    amount: String,
    onReviewChange: (Int) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    ResponsiveScaffold(
        modifier = modifier,
        scrollable = false,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sizes.paddingScreen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(sizes.spacingLarge)
        ) {
            // Title
            Text(
                text = "¿Cómo fue tu experiencia?",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(sizes.spacingMedium))

            // Rating input (stars)
            AvoqadoRatingInput(
                rating = currentReview,
                onRatingChange = onReviewChange,
                label = null
            )

            Spacer(modifier = Modifier.height(sizes.spacingLarge))

            // Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                // Skip button
                AvoqadoSecondaryButton(
                    text = "Saltar",
                    onClick = onSkip,
                    modifier = Modifier.weight(1f)
                )

                // Continue button
                AvoqadoButton(
                    text = "Continuar",
                    onClick = onContinue,
                    enabled = currentReview > 0,
                    modifier = Modifier.weight(1f)
                )
            }

            // Subtle helper text
            Text(
                text = if (currentReview == 0) {
                    "La calificación es opcional"
                } else {
                    "Gracias por calificar"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(name = "No Review", showBackground = true, device = "spec:width=800dp,height=1280dp,dpi=160")
@Composable
private fun ReviewScreenNoReviewPreview() {
    AvoqadoTheme {
        ReviewScreen(
            currentReview = 0,
            amount = "100.00",
            onReviewChange = {},
            onContinue = {},
            onSkip = {}
        )
    }
}

@Preview(name = "With Review", showBackground = true, device = "spec:width=800dp,height=1280dp,dpi=160")
@Composable
private fun ReviewScreenWithReviewPreview() {
    AvoqadoTheme {
        ReviewScreen(
            currentReview = 4,
            amount = "150.50",
            onReviewChange = {},
            onContinue = {},
            onSkip = {}
        )
    }
}
