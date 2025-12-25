package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChevronRight
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
 * Pay Later Orders Info Banner
 *
 * Shows informational banner when there are pay-later orders (cuentas por cobrar).
 * Differentiated from UnpaidTakeoutBanner by using blue/info color instead of red/warning.
 *
 * **Business Logic:**
 * - Pay-later orders: ANY order type + HAS customer linked + PENDING/PARTIAL payment
 * - UNPAID_TAKEOUT: ONLY TAKEOUT + NO customer (anonymous) + PENDING/PARTIAL payment
 * - These are mutually exclusive categories
 *
 * **Design Pattern:**
 * - Icon (AccountCircle) + Text ("Hay X cuentas por cobrar")
 * - Color: Info/Primary (blue - tracking/management)
 * - Height: ~48dp (slightly taller to accommodate text)
 * - Animation: Slide down when shown, slide up when hidden
 * - Tap: Navigate to OrderListScreen with PAY_LATER filter
 *
 * **Visual Hierarchy:**
 * 1. UNPAID_TAKEOUT (red/warning) - higher priority (anonymous debt, high risk)
 * 2. PAY_LATER (blue/info) - lower priority (identified customer, can track)
 *
 * **Usage:**
 * ```kotlin
 * PayLaterBanner(
 *     count = 3,
 *     onClick = { navController.navigate("orderList?filter=PAY_LATER") }
 * )
 * ```
 *
 * @param count Number of pay-later orders (orders with linked customers)
 * @param onClick Callback when user taps banner (navigate to order list)
 * @param modifier Modifier for customization
 */
@Composable
fun PayLaterBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show if there are pay-later orders
    AnimatedVisibility(
        visible = count > 0,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Account Icon (identified customer)
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                // Info Text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (count == 1) {
                            stringResource(R.string.pay_later_banner_singular)
                        } else {
                            stringResource(R.string.pay_later_banner_plural, count)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.pay_later_banner_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                // Chevron Right (visual hint)
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
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
private fun PayLaterBannerPreviewSingular() {
    AvoqadoTheme {
        PayLaterBanner(
            count = 1,
            onClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, name = "Banner with 3 orders")
@Composable
private fun PayLaterBannerPreviewPlural() {
    AvoqadoTheme {
        PayLaterBanner(
            count = 3,
            onClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, name = "Banner hidden (count = 0)")
@Composable
private fun PayLaterBannerPreviewHidden() {
    AvoqadoTheme {
        PayLaterBanner(
            count = 0,
            onClick = {}
        )
    }
}
