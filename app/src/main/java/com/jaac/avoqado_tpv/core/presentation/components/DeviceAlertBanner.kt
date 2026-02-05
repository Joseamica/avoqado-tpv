package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.viewmodels.AlertColor
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import com.jaac.avoqado_tpv.core.presentation.viewmodels.getAlertColor

/**
 * Device Alert Banner
 *
 * Displays device health alerts following Square/Toast/Shopify POS patterns.
 *
 * **Design Pattern:**
 * - Single banner showing the most critical alert
 * - Badge showing count of additional alerts
 * - Expandable to show all alerts
 * - Color-coded by severity (red/orange/yellow)
 *
 * **Usage:**
 * ```kotlin
 * val alerts by deviceHealthViewModel.activeAlerts.collectAsStateWithLifecycle()
 * val isExpanded by deviceHealthViewModel.isExpanded.collectAsStateWithLifecycle()
 *
 * DeviceAlertBanner(
 *     alerts = alerts,
 *     isExpanded = isExpanded,
 *     onToggleExpand = { deviceHealthViewModel.toggleExpanded() },
 *     onDismiss = { alert -> deviceHealthViewModel.dismissAlert(alert) }
 * )
 * ```
 */
@Composable
fun DeviceAlertBanner(
    alerts: List<DeviceAlert>,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onDismiss: (DeviceAlert) -> Unit = {},
    onRetry: () -> Unit = {}, // For connection alerts (NoInternet, ServerDown)
    modifier: Modifier = Modifier
) {
    // Don't show if no alerts
    if (alerts.isEmpty()) return

    val topAlert = alerts.first()
    val additionalCount = alerts.size - 1

    AnimatedVisibility(
        visible = alerts.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Column {
            // Main banner (always visible when there are alerts)
            AlertBannerRow(
                alert = topAlert,
                additionalCount = additionalCount,
                isExpanded = isExpanded,
                onToggleExpand = onToggleExpand,
                onDismiss = if (topAlert.priority > 2) {{ onDismiss(topAlert) }} else null,
                onRetry = if (topAlert is DeviceAlert.NoInternet || topAlert is DeviceAlert.ServerDown) onRetry else null
            )

            // Expanded list of additional alerts
            AnimatedVisibility(
                visible = isExpanded && additionalCount > 0,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    alerts.drop(1).forEach { alert ->
                        AlertBannerRow(
                            alert = alert,
                            additionalCount = 0,
                            isExpanded = false,
                            onToggleExpand = {},
                            onDismiss = if (alert.priority > 2) {{ onDismiss(alert) }} else null,
                            onRetry = if (alert is DeviceAlert.NoInternet || alert is DeviceAlert.ServerDown) onRetry else null,
                            isSecondary = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single alert banner row
 */
@Composable
private fun AlertBannerRow(
    alert: DeviceAlert,
    additionalCount: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDismiss: (() -> Unit)?,
    onRetry: (() -> Unit)? = null, // For connection alerts
    isSecondary: Boolean = false
) {
    val backgroundColor = when (alert.getAlertColor()) {
        AlertColor.CRITICAL -> Color(0xFFB71C1C)  // Dark red
        AlertColor.WARNING -> Color(0xFFE65100)   // Dark orange
        AlertColor.CAUTION -> Color(0xFFF57F17)   // Dark yellow
    }

    val icon = when (alert) {
        is DeviceAlert.NoInternet -> Icons.Default.WifiOff
        is DeviceAlert.BatteryCritical -> Icons.Default.BatteryAlert
        is DeviceAlert.ServerDown -> Icons.Default.CloudOff
        is DeviceAlert.BatteryLow -> Icons.Default.Battery2Bar
        is DeviceAlert.StorageLow -> Icons.Default.Storage
        is DeviceAlert.WeakWifi -> Icons.Default.SignalWifiStatusbarConnectedNoInternet4
        is DeviceAlert.MemoryLow -> Icons.Default.Memory
    }

    val message = when (alert) {
        is DeviceAlert.NoInternet -> alert.message
        is DeviceAlert.BatteryCritical -> alert.message
        is DeviceAlert.ServerDown -> alert.message
        is DeviceAlert.BatteryLow -> alert.message
        is DeviceAlert.StorageLow -> alert.message
        is DeviceAlert.WeakWifi -> alert.message
        is DeviceAlert.MemoryLow -> alert.message
    }

    val description = when (alert) {
        is DeviceAlert.NoInternet -> alert.description
        is DeviceAlert.BatteryCritical -> alert.description
        is DeviceAlert.ServerDown -> alert.description
        is DeviceAlert.BatteryLow -> alert.description
        is DeviceAlert.StorageLow -> alert.description
        is DeviceAlert.WeakWifi -> alert.description
        is DeviceAlert.MemoryLow -> alert.description
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSecondary) backgroundColor.copy(alpha = 0.85f) else backgroundColor)
            .clickable(enabled = additionalCount > 0) { onToggleExpand() }
            .padding(horizontal = 12.dp, vertical = if (isSecondary) 4.dp else 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Icon + Message
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (isSecondary) 14.dp else 16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = message,
                    style = if (isSecondary) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                if (!isSecondary) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Right: Badge + Expand/Dismiss buttons
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Additional alerts badge
            if (additionalCount > 0 && !isSecondary) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "+$additionalCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Colapsar" else "Expandir",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Retry button (for connection alerts)
            if (onRetry != null) {
                Surface(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reintentar",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Reintentar",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Dismiss button (only for non-critical alerts)
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Descartar",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerCriticalPreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(DeviceAlert.BatteryCritical(8))
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerWarningPreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(DeviceAlert.BatteryLow(15))
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerCautionPreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(DeviceAlert.WeakWifi(1))
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerMultipleCollapsedPreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(
                DeviceAlert.BatteryCritical(8),
                DeviceAlert.StorageLow(0.5f),
                DeviceAlert.WeakWifi(1)
            ),
            isExpanded = false
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerMultipleExpandedPreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(
                DeviceAlert.BatteryCritical(8),
                DeviceAlert.StorageLow(0.5f),
                DeviceAlert.WeakWifi(1)
            ),
            isExpanded = true
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerStoragePreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(DeviceAlert.StorageLow(0.3f))
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun DeviceAlertBannerMemoryPreview() {
    AvoqadoTheme {
        DeviceAlertBanner(
            alerts = listOf(DeviceAlert.MemoryLow(50))
        )
    }
}
