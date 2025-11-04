package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Loading Indicator
 *
 * Standard loading indicator with consistent styling
 *
 * @param modifier Modifier for customization
 * @param size Size of the loading indicator
 * @param strokeWidth Width of the progress indicator stroke
 */
@Composable
fun AvoqadoLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 4.dp
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = strokeWidth
    )
}

/**
 * Avoqado Full Screen Loading
 *
 * Loading indicator that fills the entire screen with optional message
 *
 * @param modifier Modifier for customization
 * @param message Optional loading message
 */
@Composable
fun AvoqadoFullScreenLoading(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Spacing.Space4)
        ) {
            AvoqadoLoadingIndicator()

            if (message != null) {
                Spacer(Modifier.height(Spacing.Space4))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Avoqado Loading Overlay (Square/Toast Pattern)
 *
 * Fullscreen loading overlay with dark scrim background.
 * Used during critical operations (login, payment, activation).
 *
 * **Pattern:** Square POS, Toast POS, Stripe Terminal
 * - Semi-transparent dark background (scrim)
 * - Elevated card with loading spinner
 * - Centered message
 * - Blocks all user interaction
 *
 * **Usage:**
 * ```kotlin
 * Box(modifier = Modifier.fillMaxSize()) {
 *     // Your content
 *     MyScreen()
 *
 *     // Show loading overlay when needed
 *     if (state is State.Loading) {
 *         AvoqadoLoadingOverlay(message = "Autenticando...")
 *     }
 * }
 * ```
 *
 * @param message Loading message to display
 * @param modifier Modifier for customization
 */
@Composable
fun AvoqadoLoadingOverlay(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Avoqado Inline Loading
 *
 * Small loading indicator for inline use (buttons, lists, etc.)
 *
 * @param modifier Modifier for customization
 */
@Composable
fun AvoqadoInlineLoading(
    modifier: Modifier = Modifier
) {
    AvoqadoLoadingIndicator(
        modifier = modifier,
        size = 20.dp,
        strokeWidth = 2.dp
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoLoadingIndicatorPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space4),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Standard loading
            AvoqadoLoadingIndicator()

            // Small loading
            AvoqadoLoadingIndicator(
                size = 32.dp,
                strokeWidth = 3.dp
            )

            // Inline loading
            AvoqadoInlineLoading()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoFullScreenLoadingPreview() {
    AvoqadoTheme {
        AvoqadoFullScreenLoading()
    }
}

@Preview(showBackground = true, name = "Full Screen with Message")
@Composable
private fun FullScreenLoadingWithMessagePreview() {
    AvoqadoTheme {
        AvoqadoFullScreenLoading(
            message = "Processing payment..."
        )
    }
}

@Preview(showBackground = true, name = "Loading Overlay")
@Composable
private fun AvoqadoLoadingOverlayPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text("Screen content behind overlay")
            }

            // Loading overlay on top
            AvoqadoLoadingOverlay(message = "Autenticando...")
        }
    }
}

@Preview(showBackground = true, name = "Loading States")
@Composable
private fun LoadingStatesPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space5)
        ) {
            // Small inline
            Text(
                text = "Inline Loading",
                style = MaterialTheme.typography.titleMedium
            )
            AvoqadoInlineLoading()

            // Standard
            Text(
                text = "Standard Loading",
                style = MaterialTheme.typography.titleMedium
            )
            AvoqadoLoadingIndicator()

            // Large
            Text(
                text = "Large Loading",
                style = MaterialTheme.typography.titleMedium
            )
            AvoqadoLoadingIndicator(
                size = 64.dp,
                strokeWidth = 5.dp
            )
        }
    }
}
