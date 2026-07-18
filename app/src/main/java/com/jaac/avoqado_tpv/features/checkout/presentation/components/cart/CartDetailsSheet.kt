package com.jaac.avoqado_tpv.features.checkout.presentation.components.cart

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState

/**
 * Expanded cart contents shown in a `ModalBottomSheet`, modeled after Square's
 * iOS checkout drawer.
 *
 * Layout (top → bottom):
 * 1. Top row: [X close] [⋮ kebab]
 * 2. Customer header: "Agregar cliente" / customer name (tappable, large)
 *    + "X artículos" subtitle (small, gray)
 * 3. "En tienda" items card — list with stepper/delete + "Agregar impuesto"
 *    link
 * 4. Bottom row: [Guardar carrito] [Cobrar $X.XX]
 *
 * The kebab menu carries deferred actions: Agregar nota, Aplicar IVA,
 * Limpiar carrito. Putting these here keeps the keypad surface clean
 * (the `+ Nota` button no longer steals vertical space).
 */
@Composable
fun CartDetailsSheet(
    cartState: CartState,
    customerName: String?,
    onCustomerTap: () -> Unit,
    onClose: () -> Unit,
    onClearCart: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onIncrementQuantity: (String) -> Unit,
    onDecrementQuantity: (String) -> Unit,
    onAddNote: () -> Unit,
    onApplyTax: (() -> Unit)?,
    onCharge: () -> Unit,
    isChargeInFlight: Boolean = false,
    onPayLater: (() -> Unit)? = null,
    isSubmittingPayLater: Boolean = false,
    /**
     * Optional referral capture content (Plan 5A). When non-null, the
     * composable is rendered between the items card and the totals
     * breakdown. Kept as a slot so previews and tests can opt out.
     */
    referralSection: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = showMenu) {
        showMenu = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 16.dp),
    ) {
        // 1. Top row — close + kebab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Cerrar")
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (cartState.orderNote.isNullOrBlank()) "Agregar nota" else "Editar nota",
                            )
                        },
                        onClick = {
                            showMenu = false
                            onAddNote()
                        },
                    )
                    if (onApplyTax != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (cartState.orderTaxPercent == null) {
                                        "Agregar impuesto"
                                    } else {
                                        "Cambiar impuesto (${cartState.orderTaxPercent}%)"
                                    },
                                )
                            },
                            onClick = {
                                showMenu = false
                                onApplyTax()
                            },
                        )
                    }
                    if (onPayLater != null) {
                        DropdownMenuItem(
                            text = { Text(if (isSubmittingPayLater) "Guardando…" else "Pagar después") },
                            enabled = !isSubmittingPayLater,
                            onClick = {
                                showMenu = false
                                onPayLater()
                            },
                        )
                    }
                    if (!cartState.isEmpty) {
                        DropdownMenuItem(
                            text = { Text("Limpiar carrito") },
                            onClick = {
                                showMenu = false
                                onClearCart()
                            },
                        )
                    }
                }
            }
        }

        // 2 + 3. Scrollable middle (customer header + items card). Footer
        // below stays pinned so the operator can always reach Cobrar.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Customer header — large, tappable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCustomerTap)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = customerName ?: "Agregar cliente",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${cartState.itemCount} ${if (cartState.itemCount == 1) "artículo" else "artículos"}",
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = TextDecoration.Underline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (cartState.isEmpty) {
                EmptyState()
            } else {
                ItemsCard(
                    cartState = cartState,
                    onRemoveItem = onRemoveItem,
                    onIncrementQuantity = onIncrementQuantity,
                    onDecrementQuantity = onDecrementQuantity,
                    onApplyTax = onApplyTax,
                )

                if (referralSection != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    referralSection()
                }

                Spacer(modifier = Modifier.height(12.dp))

                TotalsBreakdown(cartState = cartState)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // 4. Footer — full-width Cobrar button. Pay-later moved to the
        //    kebab menu so the primary CTA stays unambiguous.
        Button(
            onClick = onCharge,
            enabled = !cartState.isEmpty && !isChargeInFlight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(
                text = if (cartState.isEmpty) "Cobrar" else "Cobrar ${cartState.totalDisplay}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ItemsCard(
    cartState: CartState,
    onRemoveItem: (String) -> Unit,
    onIncrementQuantity: (String) -> Unit,
    onDecrementQuantity: (String) -> Unit,
    onApplyTax: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        // Section header
        Text(
            text = "En tienda",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // Items list — items are typically <10, so a plain Column inside the
        // already-scrollable parent is fine and avoids LazyColumn-in-Column
        // height-constraint issues.
        cartState.items.forEachIndexed { index, item ->
            key(item.id) {
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
                CartItemRow(
                    item = item,
                    onIncrement = { onIncrementQuantity(item.id) },
                    onDecrement = { onDecrementQuantity(item.id) },
                    onDelete = { onRemoveItem(item.id) },
                )
            }
        }

        // Note row (if set)
        if (!cartState.orderNote.isNullOrBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = "Nota: ${cartState.orderNote}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (onApplyTax != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = if (cartState.orderTaxPercent != null) {
                    "Impuesto (${cartState.orderTaxPercent}%) — ${cartState.taxDisplay}"
                } else {
                    "Agregar impuesto"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onApplyTax)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Subtotal/Descuento/Total breakdown shown above the Cobrar button. Hides the
 * discount row when there's nothing applied so empty rows don't add noise.
 */
@Composable
private fun TotalsBreakdown(cartState: CartState) {
    val hasDiscount = cartState.discountCents > 0
    val hasTax = cartState.taxCents > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TotalsRow(label = "Subtotal", amount = cartState.subtotalDisplay)
        if (hasDiscount) {
            // Pick a friendly label: cortesía (100%) vs descuento parcial
            val isFullyComped = cartState.discountCents >= cartState.subtotalCents
            val label = when {
                isFullyComped -> "Cortesía"
                cartState.orderDiscount != null -> "Descuento (${cartState.orderDiscount.name})"
                cartState.manualDiscountReason != null -> "Descuento (${cartState.manualDiscountReason})"
                else -> "Descuento"
            }
            TotalsRow(label = label, amount = "−${cartState.discountDisplay}", emphasize = true)
        }
        if (hasTax) {
            TotalsRow(label = "Impuesto", amount = cartState.taxDisplay)
        }
        Spacer(modifier = Modifier.height(2.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Total",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = cartState.totalDisplay,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TotalsRow(label: String, amount: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasize) Color(0xFFE61F6B) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasize) Color(0xFFE61F6B) else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CartItemRow(
    item: CartItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon/avatar — product photo if available, else initial badge
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!item.imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = item.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface,
                // Nombre largo → se desliza solo hacia la izquierda (marquee).
                modifier = Modifier.basicMarquee(),
            )
            val subtitle = item.modifiersSummary ?: item.itemNote
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        if (item.type is CartItemType.ProductItem) {
            StepperButton(icon = Icons.Filled.Remove, contentDescription = "Quitar uno", onClick = onDecrement)
            Text(
                text = item.quantity.toString(),
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(16.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StepperButton(icon = Icons.Filled.Add, contentDescription = "Agregar uno", onClick = onIncrement)
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = "$${String.format("%.2f", item.totalPriceCents / 100.0)}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.width(6.dp))

        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Eliminar",
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onDelete),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Carrito vacío",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────

@Preview(name = "Details - Many items + customer", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun CartDetailsManyItemsPreview() {
    AvoqadoTheme {
        CartDetailsSheet(
            cartState = CartState(
                items = listOf(
                    CartItem(
                        type = CartItemType.ProductItem("p-1"),
                        name = "Corte de cabello",
                        subtitle = "Normal, 45 min",
                        unitPriceCents = 25000,
                    ),
                    CartItem(
                        type = CartItemType.CustomAmount,
                        name = "Otro importe",
                        unitPriceCents = 5880,
                    ),
                ),
            ),
            customerName = null,
            onCustomerTap = {},
            onClose = {},
            onClearCart = {},
            onRemoveItem = {},
            onIncrementQuantity = {},
            onDecrementQuantity = {},
            onAddNote = {},
            onApplyTax = {},
            onCharge = {},
            onPayLater = {},
        )
    }
}

@Preview(name = "Details - With customer + note + tax", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun CartDetailsWithCustomerPreview() {
    AvoqadoTheme {
        CartDetailsSheet(
            cartState = CartState(
                items = listOf(
                    CartItem(
                        type = CartItemType.ProductItem("p-1"),
                        name = "Corte de cabello",
                        subtitle = "Normal, 45 min",
                        unitPriceCents = 25000,
                    ),
                ),
                orderNote = "Cliente prefiere tijeras",
                orderTaxPercent = 16,
            ),
            customerName = "Ana López",
            onCustomerTap = {},
            onClose = {},
            onClearCart = {},
            onRemoveItem = {},
            onIncrementQuantity = {},
            onDecrementQuantity = {},
            onAddNote = {},
            onApplyTax = {},
            onCharge = {},
        )
    }
}

@Preview(name = "Details - Empty", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun CartDetailsEmptyPreview() {
    AvoqadoTheme {
        CartDetailsSheet(
            cartState = CartState(),
            customerName = null,
            onCustomerTap = {},
            onClose = {},
            onClearCart = {},
            onRemoveItem = {},
            onIncrementQuantity = {},
            onDecrementQuantity = {},
            onAddNote = {},
            onApplyTax = {},
            onCharge = {},
        )
    }
}
