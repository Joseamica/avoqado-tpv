package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Unpaid Takeout Orders Warning Banner
 *
 * Shows discrete warning when there are unpaid or partially paid TAKEOUT orders.
 * Follows ConnectionBanner pattern for consistency.
 *
 * **Design Pattern:**
 * - Icon (Warning) + Text ("Hay X órdenes rápidas sin pagar")
 * - Color: Warning/Amber (attention)
 * - Height: ~48dp (slightly taller to accommodate text)
 * - Animation: Slide down when shown, slide up when hidden
 * - Tap: Navigate to OrderListScreen with UNPAID_TAKEOUT filter
 *
 * **Usage:**
 * ```kotlin
 * UnpaidTakeoutBanner(
 *     count = 3,
 *     onClick = { navController.navigate("orderList?filter=UNPAID_TAKEOUT") }
 * )
 * ```
 *
 * @param count Number of unpaid TAKEOUT orders
 * @param onClick Callback when user taps banner (navigate to order list)
 * @param modifier Modifier for customization
 */
@Composable
fun UnpaidTakeoutBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show if there are unpaid orders
    AnimatedVisibility(
        visible = count > 0,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Warning Icon
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                // Warning Text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (count == 1) {
                            stringResource(R.string.unpaid_takeout_banner_singular)
                        } else {
                            stringResource(R.string.unpaid_takeout_banner_plural, count)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.unpaid_takeout_banner_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }

                // Chevron Right (visual hint)
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, name = "Banner with 1 order")
@Composable
private fun UnpaidTakeoutBannerPreviewSingular() {
    AvoqadoTheme {
        UnpaidTakeoutBanner(
            count = 1,
            onClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, name = "Banner with 3 orders")
@Composable
private fun UnpaidTakeoutBannerPreviewPlural() {
    AvoqadoTheme {
        UnpaidTakeoutBanner(
            count = 3,
            onClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, name = "Banner hidden (count = 0)")
@Composable
private fun UnpaidTakeoutBannerPreviewHidden() {
    AvoqadoTheme {
        UnpaidTakeoutBanner(
            count = 0,
            onClick = {}
        )
    }
}
