package com.jaac.avoqado_tpv.features.remote_command.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Lock Screen Overlay - Full-Screen Security Blocker
 *
 * **WHY**: When admin sends a LOCK command from dashboard, this overlay
 * completely blocks the terminal. NO user interactions are possible.
 * The terminal can ONLY be unlocked via remote UNLOCK command.
 *
 * **Use Cases**:
 * - Stolen device: Admin locks immediately from dashboard
 * - Employee termination: Lock terminal until collected
 * - Security breach: Temporary lockdown during investigation
 * - After-hours: Prevent unauthorized use
 *
 * **Design**:
 * - Full-screen overlay with zIndex 100 (above everything)
 * - Dark background with error-colored lock icon
 * - Shows reason and custom message from admin
 * - NO dismiss button - only remote unlock
 *
 * **Pattern**: In-composition overlay (NOT ModalBottomSheet)
 * per CLAUDE.md performance guidelines for 1GB RAM devices.
 *
 * @param visible Whether the overlay is shown
 * @param lockReason Why the terminal was locked (from admin)
 * @param lockMessage Custom message from admin (e.g., "Contact support at...")
 * @param lockedBy Who locked the terminal (admin name)
 *
 * @see LockScreenManager State management for lock
 * @see CommandExecutor.executeLock Where lock is applied
 */
@Composable
fun LockScreenOverlay(
    visible: Boolean,
    lockReason: String?,
    lockMessage: String?,
    lockedBy: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .zIndex(100f),  // Above everything
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                // Lock Icon
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Terminal bloqueada",
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Title
                Text(
                    text = "Terminal Bloqueada",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reason (if provided)
                if (!lockReason.isNullOrBlank()) {
                    Text(
                        text = lockReason,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Custom message (if provided)
                if (!lockMessage.isNullOrBlank()) {
                    Text(
                        text = lockMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Locked by (if provided)
                if (!lockedBy.isNullOrBlank()) {
                    Text(
                        text = "Bloqueado por: $lockedBy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Contact admin message
                Text(
                    text = "Contacte al administrador para desbloquear",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 600)
@Composable
private fun LockScreenOverlayPreview() {
    MaterialTheme {
        LockScreenOverlay(
            visible = true,
            lockReason = "Seguridad: Dispositivo reportado como perdido",
            lockMessage = "Por favor, contacte al soporte técnico al 800-123-4567 para verificar su identidad.",
            lockedBy = "Admin Principal"
        )
    }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 600)
@Composable
private fun LockScreenOverlayMinimalPreview() {
    MaterialTheme {
        LockScreenOverlay(
            visible = true,
            lockReason = null,
            lockMessage = null,
            lockedBy = null
        )
    }
}
