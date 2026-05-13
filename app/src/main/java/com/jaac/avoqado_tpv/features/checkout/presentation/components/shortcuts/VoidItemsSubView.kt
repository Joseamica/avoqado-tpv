package com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState

/**
 * **Cart-level removal (pre-order)** — NOT the legacy void-items flow.
 *
 * The Cobrar cart is purely in-memory; the order is created on the backend
 * only at "Cobrar" time. So removing an item here is a local `CartState`
 * mutation with no backend call, no audit trail, no reason required. This
 * is intentional and correct for the V1 single-shot endpoint.
 *
 * The legacy void-items flow (`OrderRepository.voidItems`) is a different
 * thing: it operates on an order that already exists in the backend,
 * requires `staffId + reason + version`, creates an `OrderAction{type:'VOID'}`
 * audit row, and recalculates Order totals server-side. That flow lives in
 * the existing `MenuScreen → ActionsTab` for legacy Nueva Orden / table
 * service paths and is untouched by Cobrar V1.
 *
 * Tap an item → confirmation → `removeItem`. Mirrors swipe-to-delete in
 * `CartDetailsSheet`; exposed here as a dedicated subview for one-handed
 * operation on the PAX terminal.
 */
@Composable
fun VoidItemsSubView(
    cartState: CartState,
    onRemove: (itemId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<CartItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        SubViewHeader(title = "Eliminar del carrito", onBack = onBack)

        if (cartState.items.isEmpty()) {
            EmptyState(
                title = "El carrito está vacío",
                subtitle = "No hay items que eliminar.",
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = cartState.items, key = { it.id }) { item ->
                VoidRow(item = item, onTap = { pending = item })
            }
        }
    }

    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Eliminar del carrito") },
            text = {
                Text(
                    "¿Eliminar \"${item.quantity}× ${item.name}\" del carrito? La orden aún no se ha creado, así que esta acción solo limpia el carrito local.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(item.id)
                    pending = null
                }) { Text("Eliminar", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun VoidRow(item: CartItem, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            tint = Color(0xFFE53935),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.quantity}× ${item.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.modifiersSummary?.let { mods ->
                Text(
                    text = mods,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$${String.format("%.2f", item.totalPriceCents / 100.0)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
