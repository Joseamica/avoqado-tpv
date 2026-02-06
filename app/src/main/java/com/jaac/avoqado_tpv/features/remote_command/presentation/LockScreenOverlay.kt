package com.jaac.avoqado_tpv.features.remote_command.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.84f)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .zIndex(100f),  // Above everything
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(24.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Terminal bloqueada",
                            modifier = Modifier
                                .size(92.dp)
                                .padding(24.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Terminal bloqueada",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!lockReason.isNullOrBlank()) {
                        Text(
                            text = lockReason,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (!lockMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = lockMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    if (!lockedBy.isNullOrBlank()) {
                        Text(
                            text = "Bloqueado por: $lockedBy",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "Desbloqueo remoto únicamente",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = "Contacte al administrador para desbloquear el terminal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
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
