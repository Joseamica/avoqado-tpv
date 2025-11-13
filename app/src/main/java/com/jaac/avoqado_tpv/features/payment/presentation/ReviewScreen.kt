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
 * 1. User taps stars to rate → Automatically proceeds to tip screen
 * 2. User can "Saltar" to skip review
 *
 * **Design:**
 * Clean, modern layout without card wrapper
 * Simple and fast - only overall review (1-5 stars)
 * No detailed categories for now (Food, Service, etc.)
 * No "Continuar" button - selection is automatic (reduces clicks)
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
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Rating input (stars)
                // ⭐ Auto-advance: When user taps a star, save rating and proceed to tip screen
                AvoqadoRatingInput(
                    rating = currentReview,
                    onRatingChange = { rating ->
                        // ⭐ FIX: Only call onReviewChange - it now handles proceed automatically
                        // PaymentViewModel.selectRatingAndProceed saves rating and advances in one step
                        onReviewChange(rating)
                    },
                    label = null
                )
            }

            // Center content vertically
            Spacer(modifier = Modifier.weight(1f))

            // FOOTER ZONE: Action buttons (fixed at bottom)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Only "Saltar" button - rating selection is automatic
                AvoqadoSecondaryButton(
                    text = "Saltar",
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(sizes.spacingSmall))
            }
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
