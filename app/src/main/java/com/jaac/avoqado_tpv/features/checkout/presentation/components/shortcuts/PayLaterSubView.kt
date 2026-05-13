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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import kotlinx.coroutines.launch

/**
 * Pay-later flow: bind cart items to a customer, leave order PENDING.
 *
 * Operator picks a customer (required), reviews the cart total, and confirms.
 * Result is an Order with `paymentStatus = PENDING` linked to the customer,
 * which appears in the dashboard's "Cuentas por Cobrar" view and in the
 * operator's pay-later list. No PaymentScreen navigation — cart clears and
 * the operator goes back to the Checkout main view.
 *
 * **Known drift**: `settleOrder` on dashboard doesn't currently trigger
 * inventory deduction. Tracked as a separate follow-up; operations accepts
 * the temporary drift to unblock the feature.
 */
@Composable
fun PayLaterSubView(
    cartState: CartState,
    selectedCustomer: Customer?,
    onSelectCustomer: () -> Unit,
    onConfirm: suspend () -> Result<Unit>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        SubViewHeader(title = "Pagar después", onBack = onBack)

        if (cartState.isEmpty) {
            EmptyState(
                title = "El carrito está vacío",
                subtitle = "Agrega items antes de marcar como Pagar después.",
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Customer selector — required
            CustomerPickerRow(
                customer = selectedCustomer,
                onTap = onSelectCustomer,
            )

            // Items summary card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${cartState.itemCount} artículo${if (cartState.itemCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cartState.items.take(4).forEach { item ->
                    Text(
                        text = "${item.quantity}× ${item.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (cartState.items.size > 4) {
                    Text(
                        text = "+ ${cartState.items.size - 4} más…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Total a cobrar después",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = cartState.totalDisplay,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Hint
            Text(
                text = "La orden quedará pendiente de pago y aparecerá en \"Cuentas por Cobrar\" del dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Error inline
            error?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Confirm button
            Button(
                onClick = {
                    if (selectedCustomer == null) {
                        error = "Selecciona un cliente para continuar"
                        return@Button
                    }
                    isSubmitting = true
                    error = null
                    scope.launch {
                        onConfirm().fold(
                            onSuccess = {
                                isSubmitting = false
                                // Parent navigates back + clears the cart on success.
                            },
                            onFailure = { e ->
                                isSubmitting = false
                                error = e.message ?: "No se pudo crear la orden"
                            },
                        )
                    }
                },
                enabled = selectedCustomer != null && !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSubmitting) "Guardando…" else "Confirmar Pagar después")
            }
        }
    }
}

@Composable
private fun CustomerPickerRow(customer: Customer?, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (customer != null) Color(0xFF00897B).copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                .padding(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = if (customer != null) Color(0xFF00897B) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (customer != null) "Cliente" else "Selecciona cliente (requerido)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = customer?.let {
                    listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { it.phone ?: "Sin nombre" }
                } ?: "Cobranza pendiente sin asignar",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (customer != null) "Cambiar" else "Elegir",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
