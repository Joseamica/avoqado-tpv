package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionState

/**
 * Connection Banner
 *
 * Discrete banner that shows backend connectivity status.
 * Follows Square POS and Toast POS patterns for offline-first operation.
 *
 * **Design Principles:**
 * - Discrete and non-intrusive (thin bar at top)
 * - Warning color (orange, NOT error red)
 * - Doesn't block operations (user can continue working)
 * - Auto-hides when connected
 * - Smooth animations (slide in/out)
 * - User can manually retry (no dismiss - stays until reconnected)
 *
 * **States:**
 * - Connected: Banner hidden
 * - DisconnectedNoInternet: Orange banner "Sin conexión a internet" + Reintentar button
 * - DisconnectedServerDown: Orange banner "Sin conexión al servidor" + Reintentar button
 * - Reconnecting: Orange banner "Reconectando..."
 * - Reconnected: Green banner briefly "Conectado"
 *
 * @param state Current connection state
 * @param onRetry Callback when user taps "Reintentar" button
 * @param modifier Modifier for customization
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Only show banner when NOT connected and NOT checking
    val showBanner = state !is ConnectionState.Connected &&
                     state !is ConnectionState.Checking &&
                     state !is ConnectionState.Dismissed

    AnimatedVisibility(
        visible = showBanner,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        when (state) {
            is ConnectionState.DisconnectedNoInternet -> DisconnectedBanner(
                message = "Sin conexión a internet",
                onRetry = onRetry
            )
            is ConnectionState.DisconnectedServerDown -> DisconnectedBanner(
                message = "Sin conexión al servidor",
                onRetry = onRetry
            )
            is ConnectionState.Reconnecting -> ReconnectingBanner()
            is ConnectionState.Reconnected -> ReconnectedBanner()
            else -> { /* No banner */ }
        }
    }
}

/**
 * Disconnected Banner - Thin elegant bar (Slack/WhatsApp pattern)
 *
 * Height: ~36dp (non-intrusive)
 * Pattern: Icon + Short text + Retry button
 * Note: No dismiss button - banner stays until connection is restored
 */
@Composable
private fun DisconnectedBanner(
    message: String = "Sin conexión a internet",
    onRetry: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.avoqadoColors.offlineOrange)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Icon + Short message
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }

        // Right: Retry button only
        TextButton(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Reintentar",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Reconnecting Banner - Thin bar with sync icon
 */
@Composable
private fun ReconnectingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.avoqadoColors.offlineOrange)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = "Reconectando...",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Reconnected Banner - Thin green success bar (brief)
 */
@Composable
private fun ReconnectedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.avoqadoColors.statusSuccess)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = "Conectado",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun ConnectionBannerDisconnectedPreview() {
    AvoqadoTheme {
        ConnectionBanner(
            state = ConnectionState.DisconnectedNoInternet,
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun ConnectionBannerReconnectingPreview() {
    AvoqadoTheme {
        ConnectionBanner(state = ConnectionState.Reconnecting)
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun ConnectionBannerReconnectedPreview() {
    AvoqadoTheme {
        ConnectionBanner(state = ConnectionState.Reconnected)
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun ConnectionBannerConnectedPreview() {
    AvoqadoTheme {
        // Should be hidden
        ConnectionBanner(state = ConnectionState.Connected)
    }
}
