package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
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
    totalAmount: String,
    tipAmount: String,
    rating: Int?,
    merchants: List<MerchantAccount>,
    currentMerchant: MerchantAccount?,
    merchantSwitchingLoading: Boolean,
    onSelectMerchant: (MerchantAccount) -> Unit,
    onStartPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        ResponsiveScaffold(
            scrollable = false,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val sizes = LocalResponsiveSizes.current

            AvoqadoCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(sizes.paddingScreen)
            ) {
                Column(
                    modifier = Modifier.padding(sizes.paddingCard),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Payment summary
                    Text(
                        text = "Resumen de pago",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(sizes.spacingSmall))

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
                    if (tipAmount.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ZERO) ?: 0 > 0) {
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

                    Spacer(modifier = Modifier.height(sizes.spacingLarge))

                    // Merchant selection
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

                    Spacer(modifier = Modifier.height(sizes.spacingLarge))

                    // Process payment button
                    AvoqadoButton(
                        text = "Procesar Pago",
                        onClick = onStartPayment,
                        enabled = !merchantSwitchingLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Helper text
                    Spacer(modifier = Modifier.height(sizes.spacingSmall))
                    Text(
                        text = "Seleccione la cuenta antes de procesar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
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

@Preview(showBackground = true)
@Composable
private fun MerchantSelectionContentPreview() {
    MaterialTheme {
        MerchantSelectionContent(
            totalAmount = "115.00",
            tipAmount = "15.00",
            rating = 4,
            merchants = listOf(
                MerchantAccount(
                    id = "1",
                    serialNumber = "2841548417",
                    posId = "376",
                    displayName = "Account A",
                    environment = MerchantEnvironment.SANDBOX
                ),
                MerchantAccount(
                    id = "2",
                    serialNumber = "2841548418",
                    posId = "378",
                    displayName = "Account B",
                    environment = MerchantEnvironment.SANDBOX
                )
            ),
            currentMerchant = null,
            merchantSwitchingLoading = false,
            onSelectMerchant = {},
            onStartPayment = {}
        )
    }
}
