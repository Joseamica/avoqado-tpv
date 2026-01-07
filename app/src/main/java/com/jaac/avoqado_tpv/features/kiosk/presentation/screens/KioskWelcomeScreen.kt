package com.jaac.avoqado_tpv.features.kiosk.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminAuth
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminBottomSheet
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminPinDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskExitPinDialog
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * EntryPoint for accessing SecureStorage in KioskWelcomeScreen
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskWelcomeEntryPoint {
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
}

/**
 * Kiosk Welcome Screen - "Touch to Start"
 *
 * Customer-facing welcome screen with venue branding.
 *
 * **v2 Admin Mode**:
 * - Gear icon in top-right for admin access (PIN protected)
 * - Admin can configure kiosk without exiting
 * - Hidden exit gesture (5 taps on logo) still works as emergency fallback
 *
 * Pattern: McDonald's/Chili's kiosk welcome screens
 *
 * @param onStart Callback when customer taps to start ordering
 * @param onExitKiosk Callback when staff successfully exits kiosk mode
 */
@Composable
fun KioskWelcomeScreen(
    onStart: () -> Unit,
    onExitKiosk: () -> Unit
) {
    val context = LocalContext.current

    // Get venue logo from SecureStorage
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskWelcomeEntryPoint::class.java
        )
    }
    val secureStorage = remember { entryPoint.secureStorage() }
    val venueLogo = remember { secureStorage.getVenueLogo() }
    val venueName = remember { secureStorage.getVenueName() }

    // Secret exit gesture state (5 taps in 3 seconds) - emergency fallback
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Admin mode state
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var showAdminBottomSheet by remember { mutableStateOf(false) }
    var adminAuth by remember { mutableStateOf<KioskAdminAuth?>(null) }

    Timber.d("🥝 [KIOSK-WELCOME] Composing KioskWelcomeScreen (venueName: $venueName, hasLogo: ${!venueLogo.isNullOrEmpty()})")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                Timber.i("🥝 [KIOSK-WELCOME] Screen tapped - starting order flow")
                onStart()
            },
        contentAlignment = Alignment.Center
    ) {
        // Admin gear icon - top right (subtle, not prominent)
        IconButton(
            onClick = {
                Timber.i("🥝 [KIOSK-WELCOME] Admin gear icon tapped")
                showAdminPinDialog = true
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Administrador",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Venue logo with secret exit gesture (emergency fallback)
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime > 3000) {
                            tapCount = 1
                            Timber.d("🥝 [KIOSK-WELCOME] Logo tap reset (timeout) - count: 1")
                        } else {
                            tapCount++
                            Timber.d("🥝 [KIOSK-WELCOME] Logo tap - count: $tapCount")
                        }
                        lastTapTime = now

                        if (tapCount >= 5) {
                            Timber.i("🥝 [KIOSK-WELCOME] Secret exit gesture detected! (5 taps in 3 seconds)")
                            tapCount = 0
                            showExitDialog = true
                        }
                    }
            ) {
                if (!venueLogo.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(venueLogo)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Logo de $venueName",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Placeholder when no logo
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.large
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = venueName?.take(2)?.uppercase() ?: "AV",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Welcome text
            Text(
                text = "Toca para comenzar",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Touch to start",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            // Venue name at bottom
            if (!venueName.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(64.dp))
                Text(
                    text = venueName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // Admin PIN Dialog
    if (showAdminPinDialog) {
        Timber.d("🥝 [KIOSK-WELCOME] Showing Admin PIN Dialog")
        KioskAdminPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-WELCOME] Admin PIN dialog dismissed")
                showAdminPinDialog = false
            },
            onAuthSuccess = { auth ->
                Timber.i("🥝 [KIOSK-WELCOME] Admin authenticated: ${auth.staffName} (role=${auth.role}, staffId=${auth.staffId})")
                showAdminPinDialog = false
                adminAuth = auth
                showAdminBottomSheet = true
            }
        )
    }

    // Admin Bottom Sheet
    if (showAdminBottomSheet && adminAuth != null) {
        Timber.d("🥝 [KIOSK-WELCOME] Showing Admin Bottom Sheet for: ${adminAuth!!.staffName}")
        KioskAdminBottomSheet(
            adminAuth = adminAuth!!,
            onDismiss = {
                Timber.d("🥝 [KIOSK-WELCOME] Admin bottom sheet dismissed")
                showAdminBottomSheet = false
                adminAuth = null
            },
            onExitKiosk = {
                Timber.i("🥝 [KIOSK-WELCOME] Exit kiosk from admin bottom sheet - calling onExitKiosk()")
                showAdminBottomSheet = false
                adminAuth = null
                onExitKiosk()
            }
        )
    }

    // Legacy Exit PIN Dialog (emergency fallback via 5-tap gesture)
    if (showExitDialog) {
        Timber.d("🥝 [KIOSK-WELCOME] Showing legacy Exit PIN Dialog (5-tap gesture)")
        KioskExitPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-WELCOME] Exit dialog dismissed")
                showExitDialog = false
            },
            onExitSuccess = {
                Timber.i("🥝 [KIOSK-WELCOME] Exit PIN validated - calling onExitKiosk()")
                showExitDialog = false
                onExitKiosk()
            }
        )
    }
}
