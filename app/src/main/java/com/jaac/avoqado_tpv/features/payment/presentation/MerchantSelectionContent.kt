package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment

/**
 * Merchant selection screen - Final step before payment processing
 *
 * **Flow:**
 * 1. Shows payment summary (total, tip, rating)
 * 2. User selects merchant account
 * 3. User clicks "Procesar Pago" to start payment
 *
 * **Design:**
 * Simple merchant button grid with payment summary
 */
@Composable
fun MerchantSelectionContent(
    modifier: Modifier = Modifier,
    totalAmount: String,
    tipAmount: String,
    rating: Int?,
    merchants: List<MerchantAccount>,
    currentMerchant: MerchantAccount?,
    merchantSwitchingLoading: Boolean,
    onSelectMerchant: (MerchantAccount) -> Unit,
    onStartPayment: () -> Unit,
    onStartCashPayment: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,

) {
    Box(modifier = modifier.fillMaxSize()) {
        ResponsiveScaffold(
                scrollable = false,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
            val sizes = LocalResponsiveSizes.current

            // 3-zone layout: Header (reserve space) → Content (centered) → Footer (single button)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // HEADER ZONE: Reserved space for title (handled by AvoqadoTopBar)
                // No content needed here - title is in navigation bar

                // Center content vertically
                Spacer(modifier = Modifier.weight(1f))

                // CONTENT ZONE: Main content (centered)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val isSingleMerchant = merchants.size <= 1

                    if (isSingleMerchant) {
                        // ✅ SINGLE MERCHANT: Modern fintech-style payment card
                        // 2025 UI trend: Single visual unit, inline details, minimal labels

                        val hasTip = (tipAmount.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ZERO) ?: 0) > 0

                        // Payment Card Container - subtle background groups everything
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(horizontal = 32.dp, vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Hero amount - the star of the show
                                Text(
                                    text = "$$totalAmount",
                                    style = MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                // Currency + inline details (modern single-line approach)
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = "MXN",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // Inline tip (if exists) with dot separator
                                    if (hasTip) {
                                        Text(
                                            text = "  ·  ",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = "+$$tipAmount propina",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                // Rating stars (if exists) - clean, no label
                                rating?.let {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "⭐".repeat(it),
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            }
                        }
                    } else {
                        // ✅ MULTIPLE MERCHANTS: Layout compacto + selector de cuenta

                        // Payment summary
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Total
                            Text(
                                text = "Total a cobrar:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$$totalAmount MXN",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // Tip (if provided)
                            if ((tipAmount.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ZERO) ?: 0) > 0) {
                                Spacer(modifier = Modifier.height(sizes.spacingXSmall))
                                Text(
                                    text = "Incluye propina: $$tipAmount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Rating (if provided)
                            rating?.let {
                                Spacer(modifier = Modifier.height(sizes.spacingXSmall))
                                Text(
                                    text = "Calificación: ${"⭐".repeat(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(sizes.spacingMedium))

                        // Merchant selection (solo cuando hay 2+ merchants)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Seleccionar Cuenta",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(sizes.spacingSmall))

                            // Current merchant
                            Text(
                                text = "Activa: ${currentMerchant?.shortName() ?: "Default"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(sizes.spacingSmall))

                            // Merchant buttons (show short names for better fit)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
                            ) {
                                merchants.forEach { merchant ->
                                    if (merchant == currentMerchant) {
                                        AvoqadoButton(
                                            text = merchant.shortName(),
                                            onClick = { onSelectMerchant(merchant) },
                                            enabled = !merchantSwitchingLoading,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        AvoqadoSecondaryButton(
                                            text = merchant.shortName(),
                                            onClick = { onSelectMerchant(merchant) },
                                            enabled = !merchantSwitchingLoading,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Center content vertically
                Spacer(modifier = Modifier.weight(1f))

                // FOOTER ZONE: Payment method segmented buttons (2025 UI style)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Label
                    Text(
                        text = "Método de pago",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Segmented button group
                    val buttonShape = RoundedCornerShape(12.dp)
                    val cardEnabled = !merchantSwitchingLoading && currentMerchant != null
                    val cashEnabled = !merchantSwitchingLoading

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(buttonShape)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = buttonShape
                            )
                    ) {
                        // Card payment button (left)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if (cardEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable(enabled = cardEnabled) { onStartPayment() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tarjeta 💳",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (cardEnabled) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        )

                        // Cash payment button (right)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if (cashEnabled) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable(enabled = cashEnabled) { onStartCashPayment() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Efectivo 💵",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (cashEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                }
            }
        }

        // Loading overlay during merchant switch
        if (merchantSwitchingLoading) {
            AvoqadoLoadingOverlay(
                message = "Cambiando cuenta..."
            )
        }
    }
}

/**
 * Get short merchant name for button display
 * Example: "Cuenta Blumon A (Sandbox)" → "Cuenta A"
 */
private fun MerchantAccount.shortName(): String {
    return when {
        displayName.contains("Account A") || displayName.contains("Cuenta") && displayName.contains("A") -> "Cuenta A"
        displayName.contains("Account B") || displayName.contains("Cuenta") && displayName.contains("B") -> "Cuenta B"
        displayName.contains("C") -> "Cuenta C"
        displayName.contains("D") -> "Cuenta D"
        else -> displayName.take(8) // Fallback: first 8 chars
    }
}

// ==================== PREVIEWS ====================

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, name = "Single Merchant")
@Composable
private fun MerchantSelectionSingleMerchantPreview() {
    val singleMerchant = MerchantAccount(
        id = "1",
        serialNumber = "2841548417",
        posId = "5729",
        displayName = "mindform",
        environment = MerchantEnvironment.PRODUCTION
    )
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        MerchantSelectionContent(
            totalAmount = "115.00",
            tipAmount = "15.00",
            rating = 5,  // 5 stars = excellent rating
            merchants = listOf(singleMerchant),
            currentMerchant = singleMerchant,
            merchantSwitchingLoading = false,
            onSelectMerchant = {},
            onStartPayment = {},
            onStartCashPayment = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, name = "Single Merchant - No Tip/Rating")
@Composable
private fun MerchantSelectionSingleNoExtrasPreview() {
    val singleMerchant = MerchantAccount(
        id = "1",
        serialNumber = "2841548417",
        posId = "5729",
        displayName = "mindform",
        environment = MerchantEnvironment.PRODUCTION
    )
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        MerchantSelectionContent(
            totalAmount = "250.00",
            tipAmount = "0",
            rating = null,
            merchants = listOf(singleMerchant),
            currentMerchant = singleMerchant,
            merchantSwitchingLoading = false,
            onSelectMerchant = {},
            onStartPayment = {},
            onStartCashPayment = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, name = "Multiple Merchants")
@Composable
private fun MerchantSelectionMultipleMerchantsPreview() {
    val merchantA = MerchantAccount(
        id = "1",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Account A",
        environment = MerchantEnvironment.SANDBOX
    )
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        MerchantSelectionContent(
            totalAmount = "115.00",
            tipAmount = "15.00",
            rating = 4,
            merchants = listOf(
                merchantA,
                MerchantAccount(
                    id = "2",
                    serialNumber = "2841548418",
                    posId = "378",
                    displayName = "Account B",
                    environment = MerchantEnvironment.SANDBOX
                )
            ),
            currentMerchant = merchantA,
            merchantSwitchingLoading = false,
            onSelectMerchant = {},
            onStartPayment = {},
            onStartCashPayment = {}
        )
    }
}
