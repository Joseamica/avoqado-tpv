package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.presentation.viewmodels.AlertColor
import com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionRetryState
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import com.jaac.avoqado_tpv.core.presentation.viewmodels.getAlertColor
import com.jaac.avoqado_tpv.core.util.NetworkType

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
    onRetry: (DeviceAlert) -> Unit = {}, // Recibe la alerta tocada: el mismo botón sirve a conexión y a pagos
    onUpdate: () -> Unit = {}, // For update alerts (UpdateAvailable)
    /**
     * Ciclo del reintento de conexion. Idle por defecto, para que cualquier otro
     * llamador (y los @Preview) sigan viendo el banner de siempre.
     */
    retryState: ConnectionRetryState = ConnectionRetryState.Idle,
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
                onRetry = if (topAlert.isRetryable) {{ onRetry(topAlert) }} else null,
                onUpdate = if (topAlert is DeviceAlert.UpdateAvailable) onUpdate else null,
                retryState = retryState
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
                            onRetry = if (alert.isRetryable) {{ onRetry(alert) }} else null,
                            onUpdate = if (alert is DeviceAlert.UpdateAvailable) onUpdate else null,
                            isSecondary = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mensaje y descripción del banner. El reemplazo de «Reintentar» sólo aplica a NoInternet/ServerDown.
 *
 * 🔴 Mientras hay un reintento en curso, el banner CUENTA cómo va: antes el único aviso era un Toast que
 * anunciaba el intento y jamás decía cómo terminó. Acotado a las alertas de CONEXIÓN: `onRetry` también
 * existe en el banner de pagos pendientes, y sin el filtro «No se pudo conectar» quedaba pegado sobre un
 * banner que hablaba de dinero. WifiSinSalida tampoco lo lleva: su texto ya dice qué hacer.
 */
internal fun textoDelBanner(alert: DeviceAlert, retryState: ConnectionRetryState): Pair<String, String> {
    val base = when (alert) {
        is DeviceAlert.UpdateAvailable -> alert.message to alert.description
        is DeviceAlert.NoInternet -> alert.message to alert.description
        is DeviceAlert.BatteryCritical -> alert.message to alert.description
        is DeviceAlert.ServerDown -> alert.message to alert.description
        is DeviceAlert.WifiSinSalida -> alert.message to alert.description
        is DeviceAlert.SlowConnection -> alert.message to alert.description
        is DeviceAlert.PendingPayments -> alert.message to alert.description
        is DeviceAlert.BatteryLow -> alert.message to alert.description
        is DeviceAlert.StorageLow -> alert.message to alert.description
        is DeviceAlert.WeakWifi -> alert.message to alert.description
        is DeviceAlert.MemoryLow -> alert.message to alert.description
    }
    val esDeConexion = alert is DeviceAlert.NoInternet || alert is DeviceAlert.ServerDown
    return when {
        retryState is ConnectionRetryState.Retrying && esDeConexion -> "Reconectando..." to "Verificando la conexion con el servidor"
        // Decir explícitamente que los cobros NO se pierden: es verdad, la cola los guarda.
        retryState is ConnectionRetryState.Failed && esDeConexion -> "No se pudo conectar" to "Los cobros se guardan y se envian al reconectar"
        else -> base
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
    onUpdate: (() -> Unit)? = null, // For update alerts
    isSecondary: Boolean = false,
    retryState: ConnectionRetryState = ConnectionRetryState.Idle
) {
    val avoqadoColors = MaterialTheme.avoqadoColors
    val backgroundColor = when (alert.getAlertColor()) {
        AlertColor.UPDATE -> avoqadoColors.statusInfo
        AlertColor.CRITICAL -> avoqadoColors.statusCritical
        AlertColor.WARNING -> avoqadoColors.offlineOrange
        AlertColor.CAUTION -> avoqadoColors.statusWarning
    }

    val icon = when (alert) {
        is DeviceAlert.UpdateAvailable -> Icons.Default.SystemUpdate
        is DeviceAlert.NoInternet -> Icons.Default.WifiOff
        is DeviceAlert.BatteryCritical -> Icons.Default.BatteryAlert
        is DeviceAlert.ServerDown -> Icons.Default.CloudOff
        is DeviceAlert.WifiSinSalida -> Icons.Default.SignalWifiStatusbarConnectedNoInternet4
        is DeviceAlert.SlowConnection -> Icons.Default.Speed
        is DeviceAlert.PendingPayments -> Icons.Default.Sync
        is DeviceAlert.BatteryLow -> Icons.Default.Battery2Bar
        is DeviceAlert.StorageLow -> Icons.Default.Storage
        is DeviceAlert.WeakWifi -> if (alert.networkType == NetworkType.CELLULAR) Icons.Default.SignalCellularAlt else Icons.Default.SignalWifiStatusbarConnectedNoInternet4
        is DeviceAlert.MemoryLow -> Icons.Default.Memory
    }

    // Spinner del botón: sólo en las alertas de CONEXIÓN (ver textoDelBanner).
    val isRetrying = retryState is ConnectionRetryState.Retrying &&
        (alert is DeviceAlert.NoInternet || alert is DeviceAlert.ServerDown)
    val (message, description) = textoDelBanner(alert, retryState)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSecondary) backgroundColor.copy(alpha = 0.85f) else backgroundColor)
            .then(if (!isSecondary) Modifier.statusBarsPadding() else Modifier)
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
                    // enabled=false mientras sondea: sin esto, cada toque arranca otro
                    // ciclo y el ultimo gana — el mismo doble-toque que ya nos costo un bug.
                    enabled = !isRetrying,
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (isRetrying) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reintentar",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isRetrying) "Reconectando" else "Reintentar",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Update button (for update alerts)
            if (onUpdate != null) {
                Surface(
                    onClick = onUpdate,
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Actualizar",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Actualizar",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
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

// PAX A910S (360x640 dp): el texto largo del WiFi sin salida tiene que caber sin botón de reintento.
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun DeviceAlertBannerWifiSinSalidaPreview() {
    AvoqadoTheme {
        Column {
            DeviceAlertBanner(alerts = listOf(DeviceAlert.WifiSinSalida(reiniciando = false)))
            Spacer(modifier = Modifier.height(8.dp))
            DeviceAlertBanner(alerts = listOf(DeviceAlert.WifiSinSalida(reiniciando = true)))
        }
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
