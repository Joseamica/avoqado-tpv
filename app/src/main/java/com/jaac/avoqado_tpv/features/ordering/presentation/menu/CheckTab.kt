package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import java.text.NumberFormat
import java.util.Locale

/**
 * CheckTab - Order review and item management
 *
 * Dedicated tab for viewing order details and managing items.
 * Shows OrderTopPanel content in expanded, permanent state (not overlay).
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────┐
 * │ Order Summary                       │ ← Header with totals
 * │ 3 items • $150.00                   │
 * ├─────────────────────────────────────┤
 * │                                     │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Alitas Buffalo x2                │ │ ← Item row
 * │ │ $120.00                          │ │
 * │ │ [+] [2] [-] [🗑️]                 │ │ ← Quantity controls
 * │ │   • BBQ Sauce                    │ │ ← Modifiers
 * │ │   • Ranch                        │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Coca-Cola x1                     │ │
 * │ │ $30.00                           │ │
 * │ │ [+] [1] [-] [🗑️]                 │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * ├─────────────────────────────────────┤
 * │ Subtotal: $150.00                   │
 * │ Tax: $12.00                         │
 * │ Total: $162.00                      │
 * ├─────────────────────────────────────┤
 * │ [Send to Kitchen]  [Process Payment]│ ← Action buttons
 * └─────────────────────────────────────┘
 * ```
 *
 * ## Features
 * - **Item List**: All order items with modifiers and notes
 * - **Quantity Controls**: +/- buttons to adjust item quantity
 * - **Remove Item**: Trash icon to remove item from order
 * - **Totals**: Subtotal, tax, and total displayed at bottom
 * - **Actions**: Send to Kitchen and Process Payment buttons
 *
 * @param order Current order with items
 * @param onItemQuantityChange Callback when item quantity is changed
 * @param onItemRemove Callback when item is removed
 * @param onSendToKitchen Callback to send order to kitchen
 * @param onProcessPayment Callback to process payment
 * @param modifier Optional modifier
 */
@Composable
fun CheckTab(
    order: Order,
    onItemQuantityChange: (OrderItem, Int) -> Unit,
    onItemRemove: (OrderItem) -> Unit,
    onSendToKitchen: () -> Unit,
    onProcessPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with order summary
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = "Resumen de Orden",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${order.items.size} items • ${currencyFormatter.format(order.total)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Empty state
        if (order.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No hay items en esta orden.\n\nAgrega productos desde el tab Menú.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Item list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = order.items,
                    key = { it.id }
                ) { item ->
                    OrderItemCard(
                        item = item,
                        onQuantityChange = { newQty ->
                            onItemQuantityChange(item, newQty)
                        },
                        onRemove = {
                            onItemRemove(item)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Totals section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    TotalRow(label = "Subtotal:", amount = order.subtotal, isBold = false)
                    Spacer(modifier = Modifier.height(4.dp))
                    TotalRow(label = "IVA:", amount = order.tax, isBold = false)
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    TotalRow(label = "Total:", amount = order.total, isBold = true)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Send to Kitchen button
                Button(
                    onClick = onSendToKitchen,
                    modifier = Modifier.weight(1f),
                    enabled = order.canSendToKitchen
                ) {
                    Text("Enviar a Cocina")
                }

                // Process Payment button
                Button(
                    onClick = onProcessPayment,
                    modifier = Modifier.weight(1f),
                    enabled = order.canProcessPayment
                ) {
                    Text("Procesar Pago")
                }
            }
        }
    }
}

/**
 * Order Item Card
 *
 * Shows a single order item with quantity controls, modifiers, and remove button.
 */
@Composable
private fun OrderItemCard(
    item: OrderItem,
    onQuantityChange: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Product name and price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.productName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = currencyFormatter.format(item.totalPrice),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quantity controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Decrease quantity
                    IconButton(
                        onClick = {
                            if (item.quantity > 1) {
                                onQuantityChange(item.quantity - 1)
                            }
                        },
                        enabled = item.quantity > 1
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Disminuir cantidad"
                        )
                    }

                    // Quantity display
                    Text(
                        text = item.quantity.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    // Increase quantity
                    IconButton(
                        onClick = {
                            onQuantityChange(item.quantity + 1)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Aumentar cantidad"
                        )
                    }
                }

                // Remove item button
                IconButton(
                    onClick = onRemove
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Eliminar item",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Modifiers (if any)
            if (item.modifiers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item.modifiers.forEach { modifier ->
                        Text(
                            text = "• ${modifier.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Notes (if any)
            if (!item.notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Nota: ${item.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }
}

/**
 * Total Row
 *
 * Shows a label and amount row for the totals section.
 */
@Composable
private fun TotalRow(
    label: String,
    amount: java.math.BigDecimal,
    isBold: Boolean,
    modifier: Modifier = Modifier
) {
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = currencyFormatter.format(amount),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true)
@Composable
private fun CheckTabPreview() {
    AvoqadoTheme {
        // Preview requires mock data - simplified for now
        Text("CheckTab Preview\n(Requires mock Order data)")
    }
}
