package com.jaac.avoqado_tpv.features.payment.presentation.split

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Split Options Overlay
 *
 * Bottom sheet style overlay for selecting split payment mode.
 * Uses in-composition overlay pattern (NOT ModalBottomSheet) for PAX A80 1GB RAM performance.
 *
 * **Options:**
 * - Por Productos: Pay for specific items (PERPRODUCT)
 * - Por Personas: Split equally among N people (EQUALPARTS)
 * - Monto Personalizado: Enter custom amount (CUSTOMAMOUNT)
 * - Pago Completo: Pay full remaining balance (FULLPAYMENT)
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────┐
 * │        (scrim - click to close)    │
 * │                                     │
 * ├─────────────────────────────────────┤
 * │  Dividir Cuenta                     │
 * ├─────────────────────────────────────┤
 * │  [cart] Por Productos           >   │
 * │  [groups] Por Personas          >   │
 * │  [edit] Monto Personalizado     >   │
 * │  [payment] Pago Completo        >   │
 * └─────────────────────────────────────┘
 * ```
 *
 * @param visible Whether overlay is shown
 * @param hasPartialPayment Whether there's already a partial payment on this order
 * @param paidAmount Amount already paid (for display in banner)
 * @param remainingBalance Amount remaining to pay (for display in subtitles)
 * @param lastSplitType Previous split type used (restricts incompatible options)
 * @param onDismiss Close overlay
 * @param onProductsSplit Navigate to PERPRODUCT screen
 * @param onPersonsSplit Navigate to EQUALPARTS screen
 * @param onCustomAmount Navigate to CUSTOMAMOUNT flow
 * @param onFullPayment Proceed to FULLPAYMENT
 */
@Composable
fun SplitOptionsOverlay(
    visible: Boolean,
    hasPartialPayment: Boolean = false,
    paidAmount: BigDecimal = BigDecimal.ZERO,
    remainingBalance: BigDecimal = BigDecimal.ZERO,
    lastSplitType: SplitType? = null,  // ⭐ Restricts incompatible split options
    onDismiss: () -> Unit,
    onProductsSplit: () -> Unit,
    onPersonsSplit: () -> Unit,
    onCustomAmount: () -> Unit,
    onFullPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Currency formatter for MXN
    val currencyFormatter = remember {
        NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    }

    // ⭐ Determine which options to show based on lastSplitType
    // Prevents incompatible split combinations (EQUALPARTS ↔ PERPRODUCT)
    val showProductsSplit = lastSplitType != SplitType.EQUALPARTS
    val showPersonsSplit = lastSplitType != SplitType.PERPRODUCT
    // CUSTOMAMOUNT and FULLPAYMENT are always available

    // Handle back button press
    BackHandler(enabled = visible) {
        onDismiss()
    }

    // Don't render at all when not visible
    if (!visible) return

    // Full-screen overlay container
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(50f),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Scrim (semi-transparent background - tap to dismiss)
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDismiss() }
            )
        }

        // Bottom sheet content
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    // Header
                    Text(
                        text = "Dividir Cuenta",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Partial Payment Banner (when applicable)
                    if (hasPartialPayment) {
                        PartialPaymentBanner(
                            paidAmount = paidAmount,
                            remainingBalance = remainingBalance,
                            currencyFormatter = currencyFormatter,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Option: Por Productos (hidden if lastSplitType = EQUALPARTS)
                    if (showProductsSplit) {
                        SplitOptionRow(
                            icon = Icons.Default.ShoppingCart,
                            title = "Por Productos",
                            subtitle = if (hasPartialPayment) {
                                "Seleccionar del restante (${currencyFormatter.format(remainingBalance)})"
                            } else {
                                "Selecciona qué productos pagar"
                            },
                            onClick = {
                                onDismiss()
                                onProductsSplit()
                            }
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Option: Por Personas (hidden if lastSplitType = PERPRODUCT)
                    if (showPersonsSplit) {
                        SplitOptionRow(
                            icon = Icons.Default.Groups,
                            title = "Por Personas",
                            subtitle = if (hasPartialPayment) {
                                "Dividir ${currencyFormatter.format(remainingBalance)} entre personas"
                            } else {
                                "Divide el total entre N personas"
                            },
                            onClick = {
                                onDismiss()
                                onPersonsSplit()
                            }
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Option: Monto Personalizado
                    SplitOptionRow(
                        icon = Icons.Default.Edit,
                        title = "Monto Personalizado",
                        subtitle = if (hasPartialPayment) {
                            "Pagar hasta ${currencyFormatter.format(remainingBalance)}"
                        } else {
                            "Ingresa un monto específico"
                        },
                        onClick = {
                            onDismiss()
                            onCustomAmount()
                        }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Option: Pago Completo
                    SplitOptionRow(
                        icon = Icons.Default.Payment,
                        title = if (hasPartialPayment) "Pagar Restante" else "Pago Completo",
                        subtitle = if (hasPartialPayment) {
                            "Pagar ${currencyFormatter.format(remainingBalance)} restante"
                        } else {
                            "Pagar todo el saldo"
                        },
                        onClick = {
                            onDismiss()
                            onFullPayment()
                        }
                    )

                    // Bottom padding for navigation bar
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Split Option Row
 *
 * Individual option card with icon, title, subtitle, and chevron.
 */
@Composable
private fun SplitOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Chevron
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Seleccionar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Partial Payment Banner
 *
 * Displays paid amount and remaining balance when there's a partial payment.
 * Uses warning/info styling to draw attention.
 */
@Composable
private fun PartialPaymentBanner(
    paidAmount: BigDecimal,
    remainingBalance: BigDecimal,
    currencyFormatter: NumberFormat,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Paid amount
            Column {
                Text(
                    text = "Pagado",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = currencyFormatter.format(paidAmount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f))
            )

            // Remaining amount (emphasized)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Por pagar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = currencyFormatter.format(remainingBalance),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun SplitOptionsOverlayPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background content simulation
            Text(
                text = "CheckTab content...",
                modifier = Modifier.padding(16.dp)
            )

            // Overlay
            SplitOptionsOverlay(
                visible = true,
                onDismiss = {},
                onProductsSplit = {},
                onPersonsSplit = {},
                onCustomAmount = {},
                onFullPayment = {}
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun SplitOptionsOverlayWithPartialPaymentPreview() {
    AvoqadoTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background content simulation
            Text(
                text = "CheckTab content...",
                modifier = Modifier.padding(16.dp)
            )

            // Overlay with partial payment
            SplitOptionsOverlay(
                visible = true,
                hasPartialPayment = true,
                paidAmount = BigDecimal("259.25"),
                remainingBalance = BigDecimal("259.25"),
                onDismiss = {},
                onProductsSplit = {},
                onPersonsSplit = {},
                onCustomAmount = {},
                onFullPayment = {}
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 400, widthDp = 400)
@Composable
private fun SplitOptionRowPreview() {
    AvoqadoTheme {
        Column {
            SplitOptionRow(
                icon = Icons.Default.ShoppingCart,
                title = "Por Productos",
                subtitle = "Selecciona qué productos pagar",
                onClick = {}
            )
            HorizontalDivider()
            SplitOptionRow(
                icon = Icons.Default.Groups,
                title = "Por Personas",
                subtitle = "Divide el total entre N personas",
                onClick = {}
            )
        }
    }
}
