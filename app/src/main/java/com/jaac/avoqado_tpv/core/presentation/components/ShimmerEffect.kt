package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Professional shimmer loading effect component (Instagram/Facebook/Square pattern).
 *
 * **Use cases:**
 * - Loading QR codes while waiting for backend response
 * - Loading images, cards, or content placeholders
 * - Indicating async data fetching
 *
 * **UX Philosophy:**
 * Shimmer animations provide better perceived performance than static spinners.
 * Users feel content is "actively loading" rather than "stuck waiting".
 *
 * **Design:**
 * - Smooth gradient animation sweeping left-to-right
 * - Uses theme colors (adapts to dark/light mode)
 * - Configurable size and corner radius
 * - 1.3s animation cycle (optimal balance between smooth and energetic)
 *
 * **Usage:**
 * ```kotlin
 * if (isLoading) {
 *     ShimmerBox(
 *         modifier = Modifier.size(180.dp),
 *         cornerRadius = 24.dp
 *     )
 * } else {
 *     Image(painter = actualContent, ...)
 * }
 * ```
 *
 * @param modifier Size and positioning (use .size() for dimensions)
 * @param cornerRadius Border radius for rounded corners (default: 12.dp)
 * @param baseColor Base shimmer color (default: theme surface variant)
 * @param highlightColor Shimmer highlight color (default: lighter variant)
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    baseColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    highlightColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
) {
    // Infinite shimmer animation (repeatable)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1300, // Smooth, professional speed
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    // Shimmer gradient brush (diagonal sweep)
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            baseColor,
            highlightColor,
            baseColor
        ),
        start = Offset(translateAnim - 500f, translateAnim - 500f),
        end = Offset(translateAnim, translateAnim)
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush)
    )
}

/**
 * Shimmer placeholder for QR codes specifically.
 * Pre-configured with QR-appropriate dimensions and styling.
 *
 * **Usage:**
 * ```kotlin
 * if (receipt == null) {
 *     QrShimmerPlaceholder()
 * } else {
 *     Image(painter = rememberQrBitmapPainter(receipt.receiptUrl), ...)
 * }
 * ```
 */
@Composable
fun QrShimmerPlaceholder(
    modifier: Modifier = Modifier
) {
    ShimmerBox(
        modifier = modifier.size(180.dp),
        cornerRadius = 24.dp
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// PREVIEWS
// ═══════════════════════════════════════════════════════════════════════════

@Preview(name = "Shimmer Box - Dark Theme", showBackground = true)
@Composable
private fun ShimmerBoxPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.size(200.dp)) {
            ShimmerBox(
                modifier = Modifier.size(180.dp),
                cornerRadius = 24.dp
            )
        }
    }
}

@Preview(name = "QR Shimmer Placeholder - Dark Theme", showBackground = true)
@Composable
private fun QrShimmerPlaceholderPreview() {
    AvoqadoTheme {
        QrShimmerPlaceholder()
    }
}
