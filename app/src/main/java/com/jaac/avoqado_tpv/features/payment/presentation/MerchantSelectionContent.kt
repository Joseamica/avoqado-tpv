package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
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
    subtotalAmount: String,
    totalAmount: String,
    tipAmount: String,
    rating: Int?,
    merchants: List<MerchantAccount>,
    currentMerchant: MerchantAccount?,
    merchantSwitchingLoading: Boolean,
    merchantSwitchMessage: String? = null,
    onSelectMerchant: (MerchantAccount) -> Unit,
    onStartPayment: () -> Unit,
    onStartCashPayment: () -> Unit,
    onStartCryptoPayment: () -> Unit = {},  // 🪙 Crypto payment callback (B4Bit)
    onNavigateBack: (() -> Unit)? = null,
    showCashOption: Boolean = true,  // 🥝 Show/hide cash button
    showCryptoOption: Boolean = false,  // 🪙 Show/hide crypto button (B4Bit)
    hideAccountSelector: Boolean = false,  // 🥝 KIOSK: Hide merchant list when default is pre-configured
) {
    // 💵 State for cash payment confirmation dialog
    var showCashConfirmationDialog by remember { mutableStateOf(false) }

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
                    // 🥝 KIOSK: When hideAccountSelector=true, show simplified UI (no merchant list)
                    // This is used in kiosk mode when admin has pre-configured a default merchant
                    val showSimplifiedView = merchants.size <= 1 || hideAccountSelector

                    if (showSimplifiedView) {
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
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (hasTip) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Monto: $$subtotalAmount",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = "Propina: $$tipAmount",
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
                        // ✅ MULTIPLE MERCHANTS: Modern fintech card selector
                        val hasTip = (tipAmount.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ZERO) ?: 0) > 0

                        // Payment amount (same style as single merchant)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$$totalAmount",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (hasTip) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Monto: $$subtotalAmount",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = "Propina: $$tipAmount",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            rating?.let {
                                Text(
                                    text = "⭐".repeat(it),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Merchant selector cards
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Cuenta de cobro",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            merchants.forEach { merchant ->
                                val isSelected = merchant == currentMerchant
                                MerchantCard(
                                    merchant = merchant,
                                    isSelected = isSelected,
                                    enabled = !merchantSwitchingLoading,
                                    onClick = { onSelectMerchant(merchant) }
                                )
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
                    val enabledMethodBackground = MaterialTheme.colorScheme.surfaceVariant
                    val disabledMethodBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

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
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Card payment button (left or full width if no cash option)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (cardEnabled) enabledMethodBackground
                                    else disabledMethodBackground
                                )
                                .clickable(enabled = cardEnabled) { onStartPayment() },
                            contentAlignment = Alignment.Center
                        ) {
                            val cardTextColor = if (cardEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreditCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = cardTextColor.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Tarjeta",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = cardTextColor
                                )
                            }
                        }

                        // 🥝 Only show cash button if showCashOption is true
                        // Kiosk mode = card only (self-service doesn't handle cash)
                        if (showCashOption) {
                            // Cash payment button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (cashEnabled) enabledMethodBackground
                                        else disabledMethodBackground
                                    )
                                    .clickable(enabled = cashEnabled) {
                                        showCashConfirmationDialog = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val cashTextColor = if (cashEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AttachMoney,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = cashTextColor.copy(alpha = 0.8f)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "Efectivo",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = cashTextColor
                                    )
                                }
                            }
                        }

                        // 🪙 Only show crypto button if showCryptoOption is true
                        // B4Bit crypto payment integration
                        if (showCryptoOption) {
                            // Crypto payment button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (!merchantSwitchingLoading) enabledMethodBackground
                                        else disabledMethodBackground
                                    )
                                    .clickable(enabled = !merchantSwitchingLoading) {
                                        onStartCryptoPayment()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val cryptoTextColor = if (!merchantSwitchingLoading) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CurrencyBitcoin,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = cryptoTextColor.copy(alpha = 0.8f)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "Cripto",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = cryptoTextColor
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                }
            }
        }

        // Loading overlay during merchant switch or fallback refresh
        if (merchantSwitchingLoading) {
            AvoqadoLoadingOverlay(
                message = merchantSwitchMessage ?: "Cambiando cuenta..."
            )
        }

        // 💵 Cash payment confirmation dialog
        if (showCashConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showCashConfirmationDialog = false },
                title = {
                    Text(
                        text = "Confirmar pago en efectivo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "¿Confirmas que el cliente pagará $$totalAmount en efectivo?",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Este pago será registrado inmediatamente.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCashConfirmationDialog = false
                            onStartCashPayment()
                        }
                    ) {
                        Text("Confirmar")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showCashConfirmationDialog = false }
                    ) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

/**
 * Modern merchant card selector
 *
 * Design: Clean card with merchant name and checkmark indicator
 * - Selected: Primary border + checkmark + subtle background
 * - Unselected: Outline border + no checkmark
 */
@Composable
private fun MerchantCard(
    merchant: MerchantAccount,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) selectedColor.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) selectedColor else unselectedColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Merchant info
        Column {
            Text(
                text = merchant.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "SN: ${merchant.serialNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Selection indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(selectedColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            // Empty circle placeholder
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.5.dp,
                        color = unselectedColor,
                        shape = CircleShape
                    )
            )
        }
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
            subtotalAmount = "100.00",
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
            subtotalAmount = "250.00",
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, name = "With Crypto Option (3 buttons)")
@Composable
private fun MerchantSelectionWithCryptoPreview() {
    val singleMerchant = MerchantAccount(
        id = "1",
        serialNumber = "2841548417",
        posId = "5729",
        displayName = "mindform",
        environment = MerchantEnvironment.PRODUCTION
    )
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        MerchantSelectionContent(
            subtotalAmount = "100.00",
            totalAmount = "115.00",
            tipAmount = "15.00",
            rating = 4,
            merchants = listOf(singleMerchant),
            currentMerchant = singleMerchant,
            merchantSwitchingLoading = false,
            onSelectMerchant = {},
            onStartPayment = {},
            onStartCashPayment = {},
            onStartCryptoPayment = {},
            showCryptoOption = true  // 🪙 Enable crypto button
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, name = "Multiple Merchants (2)")
@Composable
private fun MerchantSelectionTwoMerchantsPreview() {
    val merchantA = MerchantAccount(
        id = "1",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Mindform Principal",
        environment = MerchantEnvironment.PRODUCTION
    )
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        MerchantSelectionContent(
            subtotalAmount = "100.00",
            totalAmount = "115.00",
            tipAmount = "15.00",
            rating = 4,
            merchants = listOf(
                merchantA,
                MerchantAccount(
                    id = "2",
                    serialNumber = "2841548418",
                    posId = "378",
                    displayName = "Mindform Secundaria",
                    environment = MerchantEnvironment.PRODUCTION
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C, name = "Multiple Merchants (3)")
@Composable
private fun MerchantSelectionThreeMerchantsPreview() {
    val merchantB = MerchantAccount(
        id = "2",
        serialNumber = "A28415",
        posId = "378",
        displayName = "a A28415",
        environment = MerchantEnvironment.PRODUCTION
    )
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        MerchantSelectionContent(
            subtotalAmount = "250.00",
            totalAmount = "250.00",
            tipAmount = "0",
            rating = null,
            merchants = listOf(
                MerchantAccount(
                    id = "1",
                    serialNumber = "2841548417",
                    posId = "376",
                    displayName = "Cuenta Principal",
                    environment = MerchantEnvironment.PRODUCTION
                ),
                merchantB,
                MerchantAccount(
                    id = "3",
                    serialNumber = "B94821",
                    posId = "380",
                    displayName = "Cuenta Tercera",
                    environment = MerchantEnvironment.PRODUCTION
                )
            ),
            currentMerchant = merchantB,
            merchantSwitchingLoading = false,
            onSelectMerchant = {},
            onStartPayment = {},
            onStartCashPayment = {}
        )
    }
}
