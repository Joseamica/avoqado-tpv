package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionState

/**
 * Connection Banner
 *
 * Discrete banner that shows backend connectivity status.
 * Follows Square POS and Toast POS patterns for offline-first operation.
 *
 * **Design Principles:**
 * - Discrete and non-intrusive (appears at bottom, not top)
 * - Warning color (yellow/orange, NOT error red)
 * - Doesn't block operations (user can continue working)
 * - Auto-hides when connected
 * - Smooth animations (slide in/out from bottom)
 * - User can dismiss or manually retry
 *
 * **States:**
 * - Connected: Banner hidden
 * - Disconnected: Yellow banner "Trabajando sin conexión" + Suprimir/Reintentar buttons
 * - Reconnecting: Yellow banner with spinner "Reconectando..."
 * - Reconnected: Green banner briefly "Conectado al servidor"
 * - Dismissed: Banner hidden (user dismissed it)
 *
 * @param state Current connection state
 * @param onDismiss Callback when user taps "Suprimir" button
 * @param onRetry Callback when user taps "Reintentar" button
 * @param modifier Modifier for customization
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onDismiss: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Only show banner when NOT connected and NOT dismissed
    val showBanner = state !is ConnectionState.Connected &&
                     state !is ConnectionState.Checking &&
                     state !is ConnectionState.Dismissed

    AnimatedVisibility(
        visible = showBanner,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),  // ⭐ From bottom (positive offset)
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),  // ⭐ To bottom (positive offset)
        modifier = modifier
    ) {
        when (state) {
            is ConnectionState.Disconnected -> DisconnectedBanner(
                onDismiss = onDismiss,
                onRetry = onRetry
            )
            is ConnectionState.Reconnecting -> ReconnectingBanner()
            is ConnectionState.Reconnected -> ReconnectedBanner()
            else -> { /* No banner */ }
        }
    }
}

/**
 * Disconnected Banner - Yellow warning with action buttons
 */
@Composable
private fun DisconnectedBanner(
    onDismiss: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFA500)) // Orange/Yellow (warning)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Icon + Message
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "Trabajando sin conexión - Las ventas se guardarán localmente",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }

        // Right: Action Buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Reintentar button
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Reintentar",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Suprimir button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Suprimir",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Reconnecting Banner - Yellow with spinner
 */
@Composable
private fun ReconnectingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFA500)) // Orange/Yellow (warning)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "Reconectando al servidor...",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Reconnected Banner - Green success (brief)
 */
@Composable
private fun ReconnectedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF4CAF50)) // Green (success)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "Conectado al servidor",
            style = MaterialTheme.typography.bodyMedium,
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
            state = ConnectionState.Disconnected,
            onDismiss = {},
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
