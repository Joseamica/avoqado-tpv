package com.jaac.avoqado_tpv.features.payment.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.rememberQrBitmapPainter
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Crypto Payment QR Screen
 *
 * Displays a QR code for the customer to scan and pay with cryptocurrency.
 * Shows a countdown timer and auto-calls onTimeout when expired.
 *
 * **Flow:**
 * 1. TPV generates QR from B4Bit payment URL
 * 2. Customer scans QR with their crypto wallet app
 * 3. Customer pays via B4Bit gateway (selects crypto, confirms)
 * 4. B4Bit webhook → Backend → Socket.IO → PaymentViewModel
 * 5. PaymentViewModel receives confirmation → transitions to Success
 *
 * **Design:**
 * - Dark background (matches app theme)
 * - Large QR code centered
 * - Payment amount prominently displayed
 * - Countdown timer with visual progress
 * - Cancel button always available
 *
 * @param paymentUrl URL to encode as QR (customer scans this)
 * @param totalAmount Formatted total amount to display (e.g., "55.00")
 * @param expiresInSeconds Seconds until payment expires
 * @param onCancel Called when user taps Cancel button
 * @param onTimeout Called when countdown reaches zero
 */
@Composable
fun CryptoPaymentQrScreen(
    paymentUrl: String,
    totalAmount: String,
    expiresInSeconds: Int,
    onCancel: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var secondsRemaining by remember { mutableIntStateOf(expiresInSeconds) }
    val totalSeconds = remember { expiresInSeconds }

    // Progress animation (1.0 = full, 0.0 = expired)
    val progress by animateFloatAsState(
        targetValue = secondsRemaining.toFloat() / totalSeconds.toFloat(),
        animationSpec = tween(durationMillis = 500),
        label = "countdown_progress"
    )

    // Countdown timer
    LaunchedEffect(Unit) {
        Timber.i("🪙 [CRYPTO-QR] Starting countdown: ${expiresInSeconds}s")
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
            if (secondsRemaining <= 30 && secondsRemaining % 10 == 0) {
                Timber.d("🪙 [CRYPTO-QR] Countdown: $secondsRemaining seconds remaining")
            }
        }
        Timber.w("🪙 [CRYPTO-QR] Payment expired - calling onTimeout")
        onTimeout()
    }

    // Format time as MM:SS
    val formattedTime = remember(secondsRemaining) {
        val minutes = secondsRemaining / 60
        val seconds = secondsRemaining % 60
        String.format("%02d:%02d", minutes, seconds)
    }

    // Color based on urgency
    val timerColor = when {
        secondsRemaining <= 30 -> MaterialTheme.colorScheme.error
        secondsRemaining <= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: Cancel button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(
                    onClick = {
                        Timber.i("🪙 [CRYPTO-QR] User cancelled crypto payment")
                        onCancel()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancelar",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Center section: QR code + amount
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // Header text
                Text(
                    text = "Escanea para pagar con cripto",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Abre tu wallet y escanea el código QR",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // QR Code container
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(
                            width = 4.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberQrBitmapPainter(
                            content = paymentUrl,
                            size = 200.dp,
                            padding = 0.dp
                        ),
                        contentDescription = "Código QR para pago crypto",
                        modifier = Modifier.size(200.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Payment amount
                Text(
                    text = "Total a pagar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$$totalAmount MXN",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Countdown timer
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Expira en",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = timerColor
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .width(200.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = timerColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Bottom section: Info text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Criptomonedas aceptadas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "BTC • ETH • USDT • USDC • SOL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Simplified loading screen while generating crypto QR
 */
@Composable
fun CryptoPaymentLoadingScreen(
    totalAmount: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Generando código QR...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$$totalAmount MXN",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ==================== PREVIEWS ====================

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, widthDp = 360, heightDp = 640)
@Composable
private fun CryptoPaymentQrScreenPreview() {
    MaterialTheme {
        CryptoPaymentQrScreen(
            paymentUrl = "https://pay.b4bit.com/order/abc123",
            totalAmount = "55.00",
            expiresInSeconds = 300,
            onCancel = {},
            onTimeout = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, widthDp = 360, heightDp = 640)
@Composable
private fun CryptoPaymentQrScreenLowTimePreview() {
    MaterialTheme {
        CryptoPaymentQrScreen(
            paymentUrl = "https://pay.b4bit.com/order/abc123",
            totalAmount = "1,250.00",
            expiresInSeconds = 25,
            onCancel = {},
            onTimeout = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, widthDp = 360, heightDp = 640)
@Composable
private fun CryptoPaymentLoadingScreenPreview() {
    MaterialTheme {
        CryptoPaymentLoadingScreen(
            totalAmount = "55.00"
        )
    }
}
