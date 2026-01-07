package com.jaac.avoqado_tpv.features.kiosk.presentation.screens

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.features.kiosk.domain.model.UnavailableReason
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * EntryPoint for accessing SecureStorage in KioskUnavailableScreen
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskUnavailableEntryPoint {
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
}

/**
 * Kiosk Unavailable Screen
 *
 * Attractive branded screen shown when kiosk cannot accept orders.
 * Reasons include:
 * - Shift closed (most common)
 * - Auth expired
 * - Network error
 *
 * Features:
 * - Venue branding (logo, colors)
 * - Bilingual messages (ES/EN)
 * - Admin access via gear icon (to fix the issue)
 *
 * Pattern: McDonald's "System Temporarily Unavailable" screens
 *
 * @param reason Why the kiosk is unavailable
 * @param onAdminClick Callback when admin gear icon is tapped
 */
@Composable
fun KioskUnavailableScreen(
    reason: UnavailableReason,
    onAdminClick: () -> Unit
) {
    val context = LocalContext.current

    // Get venue info from SecureStorage
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskUnavailableEntryPoint::class.java
        )
    }
    val secureStorage = remember { entryPoint.secureStorage() }
    val venueLogo = remember { secureStorage.getVenueLogo() }
    val venueName = remember { secureStorage.getVenueName() }

    // Get messages based on reason
    val (titleEs, titleEn, subtitleEs, subtitleEn) = remember(reason) {
        getUnavailableMessages(reason)
    }

    Timber.d("🥝 [KIOSK-UNAVAIL] Composing UnavailableScreen (reason: $reason, venueName: $venueName)")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface // Use light background for kiosk
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Admin gear icon - top right
            IconButton(
                onClick = {
                    Timber.i("🥝 [KIOSK-UNAVAIL] Admin gear icon tapped")
                    onAdminClick()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Administrador",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Main content - centered
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Venue logo
                Box(
                    modifier = Modifier.size(120.dp),
                    contentAlignment = Alignment.Center
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
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.large
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = venueName?.take(2)?.uppercase() ?: "AV",
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Main title - Spanish
                Text(
                    text = titleEs,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Main title - English
                Text(
                    text = titleEn,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Subtitle - Spanish
                Text(
                    text = subtitleEs,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Subtitle - English
                Text(
                    text = subtitleEn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )

                // Venue name at bottom
                if (!venueName.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(64.dp))
                    Text(
                        text = venueName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Get localized messages based on unavailability reason
 */
private fun getUnavailableMessages(reason: UnavailableReason): UnavailableMessages {
    return when (reason) {
        UnavailableReason.SHIFT_CLOSED -> UnavailableMessages(
            titleEs = "Este kiosko no esta\ndisponible",
            titleEn = "This kiosk is not available",
            subtitleEs = "Por favor, ordena en caja",
            subtitleEn = "Please order at the counter"
        )

        UnavailableReason.AUTH_EXPIRED -> UnavailableMessages(
            titleEs = "Sesion expirada",
            titleEn = "Session expired",
            subtitleEs = "Un administrador debe iniciar sesion",
            subtitleEn = "An administrator must sign in"
        )

        UnavailableReason.NETWORK_ERROR -> UnavailableMessages(
            titleEs = "Sin conexion",
            titleEn = "No connection",
            subtitleEs = "Verifica tu conexion a internet",
            subtitleEn = "Please check your internet connection"
        )

        UnavailableReason.DISABLED -> UnavailableMessages(
            titleEs = "Kiosko deshabilitado",
            titleEn = "Kiosk disabled",
            subtitleEs = "Contacta a un administrador",
            subtitleEn = "Contact an administrator"
        )
    }
}

/**
 * Container for unavailable screen messages
 */
private data class UnavailableMessages(
    val titleEs: String,
    val titleEn: String,
    val subtitleEs: String,
    val subtitleEn: String
)
