package com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import com.jaac.avoqado_tpv.features.plan.presentation.PlanUpsellCard

/**
 * Manual order-level discount. Two modes:
 *  - **Porcentaje**: 0-100 of the gross subtotal
 *  - **Monto fijo**: any value up to the subtotal
 *
 * Reason is optional but recommended for audit. The amount is stored locally
 * on the cart as `manualDiscountCents` and travels to the backend in the
 * `discount` field (no Discount entity backing it).
 *
 * If a predefined discount is currently applied, this view replaces it on
 * apply (UI shows a banner explaining the swap).
 *
 * @param planLocked plan-tier gate (PROMOTIONS requires Plan Pro). When true
 *   the MANUAL ad-hoc discount creation form is replaced by a visible teaser.
 *   Predefined discounts / coupons APPLICATION (CouponSubView, order discount
 *   pickers) is NEVER gated. Default false → fail open, behaves as today.
 */
@Composable
fun ManualDiscountSubView(
    cartState: CartState,
    onApply: (amountCents: Int, reason: String?) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    planLocked: Boolean = false,
) {
    var mode by remember { mutableStateOf(DiscountMode.PERCENT) }
    var input by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        SubViewHeader(title = "Descuento", onBack = onBack)

        // Plan gate — visible teaser instead of the ad-hoc discount form.
        if (planLocked) {
            PlanUpsellCard(
                featureTitle = "Descuento manual",
                tier = PlanTier.PRO,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        if (cartState.items.isEmpty()) {
            EmptyState(
                title = "El carrito está vacío",
                subtitle = "Agrega items antes de aplicar un descuento.",
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Current state banner
            cartState.manualDiscountCents.takeIf { it > 0 }?.let { current ->
                CurrentDiscountBanner(
                    amountText = "−$${String.format("%.2f", current / 100.0)}",
                    reason = cartState.manualDiscountReason,
                    onClear = onClear,
                )
            }
            if (cartState.orderDiscount != null) {
                Text(
                    text = "Descuento actual: ${cartState.orderDiscount.name} (${cartState.orderDiscount.formattedValue}). Si aplicas uno manual, este será reemplazado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Mode selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(label = "Porcentaje", selected = mode == DiscountMode.PERCENT, modifier = Modifier.weight(1f)) {
                    mode = DiscountMode.PERCENT
                    input = ""
                }
                ModeChip(label = "Monto fijo", selected = mode == DiscountMode.AMOUNT, modifier = Modifier.weight(1f)) {
                    mode = DiscountMode.AMOUNT
                    input = ""
                }
            }

            // Input
            OutlinedTextField(
                value = input,
                onValueChange = { raw ->
                    // Filter to digits + at most one decimal for AMOUNT, integer-only for PERCENT
                    input = when (mode) {
                        DiscountMode.PERCENT -> raw.filter { it.isDigit() }.take(3)
                        DiscountMode.AMOUNT -> raw.filter { it.isDigit() || it == '.' }
                            .let { value ->
                                // Allow only one dot
                                if (value.count { it == '.' } > 1) value.dropLast(1) else value
                            }
                    }
                },
                label = {
                    Text(if (mode == DiscountMode.PERCENT) "Porcentaje (0-100)" else "Monto en pesos")
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (mode == DiscountMode.PERCENT) KeyboardType.Number else KeyboardType.Decimal,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Reason
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(120) },
                label = { Text("Motivo (opcional)") },
                placeholder = { Text("Ej. Cliente frecuente, gerente autorizó…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Computed preview
            val computedCents = computeManualDiscountCents(mode, input, cartState.subtotalCents)
            if (computedCents > 0) {
                Text(
                    text = "Descuento: −$${String.format("%.2f", computedCents / 100.0)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE61F6B),
                )
            }

            // Action buttons
            Button(
                onClick = {
                    if (computedCents > 0) {
                        onApply(computedCents, reason.trim().takeIf { it.isNotEmpty() })
                        onBack()
                    }
                },
                enabled = computedCents > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (cartState.manualDiscountCents > 0) "Reemplazar descuento" else "Aplicar")
            }

            if (cartState.manualDiscountCents > 0 || cartState.orderDiscount != null) {
                OutlinedButton(
                    onClick = {
                        onClear()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Quitar descuento")
                }
            }
        }
    }
}

private enum class DiscountMode { PERCENT, AMOUNT }

private fun computeManualDiscountCents(mode: DiscountMode, input: String, subtotalCents: Int): Int {
    if (subtotalCents <= 0 || input.isBlank()) return 0
    return when (mode) {
        DiscountMode.PERCENT -> {
            val pct = input.toIntOrNull() ?: return 0
            val clamped = pct.coerceIn(0, 100)
            (subtotalCents * clamped / 100).coerceAtMost(subtotalCents)
        }
        DiscountMode.AMOUNT -> {
            val pesos = input.toDoubleOrNull() ?: return 0
            (pesos * 100).toInt().coerceIn(0, subtotalCents)
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
private fun CurrentDiscountBanner(amountText: String, reason: String?, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFE61F6B).copy(alpha = 0.1f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Descuento manual aplicado",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE61F6B),
            )
            Text(
                text = amountText + (reason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onClear) { Text("Quitar") }
    }
}
