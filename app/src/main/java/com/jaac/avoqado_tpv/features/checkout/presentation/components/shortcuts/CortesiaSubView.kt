package com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState

/**
 * Lists every cart item. Tap an unmarked item → reason dialog → applies
 * cortesía. Tap a marked item → confirmation prompt → removes cortesía.
 *
 * Cortesía toggle is mutually exclusive with `itemDiscountId` (CartItem.init
 * enforces this); for items with an itemDiscountId set, the cortesía action
 * is disabled with an explanation.
 */
@Composable
fun CortesiaSubView(
    cartState: CartState,
    onApply: (itemId: String, reason: String) -> Unit,
    onRemove: (itemId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingApplyItem by remember { mutableStateOf<CartItem?>(null) }
    var pendingRemoveItem by remember { mutableStateOf<CartItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        SubViewHeader(title = "Cortesía", onBack = onBack)

        if (cartState.items.isEmpty()) {
            EmptyState(
                title = "El carrito está vacío",
                subtitle = "Agrega productos antes de marcar cortesías.",
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = cartState.items, key = { it.id }) { item ->
                CortesiaRow(
                    item = item,
                    onTap = {
                        when {
                            item.isCortesia -> pendingRemoveItem = item
                            item.itemDiscountId != null -> {
                                // Mutex enforced by CartItem.init — guard here too.
                            }
                            else -> pendingApplyItem = item
                        }
                    },
                )
            }
        }
    }

    pendingApplyItem?.let { item ->
        ReasonDialog(
            item = item,
            onDismiss = { pendingApplyItem = null },
            onConfirm = { reason ->
                onApply(item.id, reason)
                pendingApplyItem = null
            },
        )
    }

    pendingRemoveItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoveItem = null },
            title = { Text("Quitar cortesía") },
            text = { Text("¿Quitar la cortesía de \"${item.name}\"? El item vuelve a su precio normal.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(item.id)
                    pendingRemoveItem = null
                }) { Text("Quitar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveItem = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun CortesiaRow(item: CartItem, onTap: () -> Unit) {
    val hasItemDiscount = item.itemDiscountId != null && !item.isCortesia
    val accent = if (item.isCortesia) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (item.isCortesia) Color(0xFF4CAF50).copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(enabled = !hasItemDiscount, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.isCortesia) Icons.Filled.CheckCircle else Icons.Filled.CardGiftcard,
            contentDescription = null,
            tint = accent,
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
            when {
                item.isCortesia -> Text(
                    text = "Cortesía: ${item.cortesiaReason ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50),
                )
                hasItemDiscount -> Text(
                    text = "Tiene descuento aplicado — no se puede marcar como cortesía",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$${String.format("%.2f", item.unitPriceCents / 100.0)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = if (item.isCortesia) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}

@Composable
private fun ReasonDialog(
    item: CartItem,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val canConfirm = reason.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Marcar cortesía") },
        text = {
            Column {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Esta cortesía queda registrada en auditoría con el motivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Motivo (requerido)") },
                    placeholder = { Text("Ej. Cliente VIP, error de cocina…") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(reason.trim()) }) {
                Text("Aplicar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
internal fun SubViewHeader(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Regresar",
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
