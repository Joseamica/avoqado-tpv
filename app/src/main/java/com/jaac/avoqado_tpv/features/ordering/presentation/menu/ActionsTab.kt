package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * ActionsTab - Order-level operations
 *
 * Provides buttons for comp, void, and discount operations.
 * Each action opens a confirmation dialog with relevant inputs.
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────┐
 * │ Actions                             │ ← Header
 * │ Perform order-level operations      │
 * ├─────────────────────────────────────┤
 * │                                     │
 * │ ┌─────────────────────────────────┐ │
 * │ │ 🎁 Comp Items                    │ │ ← Comp button
 * │ │ Complimentary items or entire    │ │
 * │ │ order (service recovery)         │ │
 * │ │                                  │ │
 * │ │ [Comp Items]                     │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * │ ┌─────────────────────────────────┐ │
 * │ │ 🗑️ Void Items                    │ │ ← Void button
 * │ │ Cancel items with audit trail   │ │
 * │ │ (customer changed mind, etc.)    │ │
 * │ │                                  │ │
 * │ │ [Void Items]                     │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * │ ┌─────────────────────────────────┐ │
 * │ │ 💰 Apply Discount                │ │ ← Discount button
 * │ │ Percentage or fixed discount     │ │
 * │ │ (promotions, loyalty)            │ │
 * │ │                                  │ │
 * │ │ [Apply Discount]                 │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * └─────────────────────────────────────┘
 * ```
 *
 * ## Features
 * - **Comp Items**: Complimentary items or entire order for service recovery
 * - **Void Items**: Cancel items with audit trail (customer changed mind, out of stock)
 * - **Apply Discount**: Apply percentage or fixed discount to order
 *
 * ## Implementation Status
 * This is a placeholder implementation for Step 7. The following features need to be implemented:
 * - [ ] Comp dialog with item selection and reason
 * - [ ] Void dialog with item selection, reason, and version control
 * - [ ] Discount dialog with type (percentage/fixed), value, reason, and item selection
 * - [ ] Integration with MenuViewModel functions (to be added in Step 9)
 * - [ ] Success/error handling with Snackbar
 * - [ ] Manager authorization for sensitive operations
 *
 * @param order Current order
 * @param onCompItems Callback to comp items (TODO: Step 9)
 * @param onVoidItems Callback to void items (TODO: Step 9)
 * @param onApplyDiscount Callback to apply discount (TODO: Step 9)
 * @param modifier Optional modifier
 */
@Composable
fun ActionsTab(
    order: Order,
    onCompItems: () -> Unit,  // TODO: Step 9 - Add proper parameters
    onVoidItems: () -> Unit,  // TODO: Step 9 - Add proper parameters
    onApplyDiscount: () -> Unit,  // TODO: Step 9 - Add proper parameters
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Acciones",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Realiza operaciones sobre la orden",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Comp Items Card
        ActionCard(
            title = "Comp Items",
            description = "Cortesía de items o toda la orden (recuperación de servicio, satisfacción del cliente)",
            icon = Icons.Default.Favorite,
            buttonText = "Comp Items",
            buttonEnabled = order.items.isNotEmpty(),
            onButtonClick = onCompItems
        )

        // Void Items Card
        ActionCard(
            title = "Void Items",
            description = "Anular items con registro de auditoría (cliente cambió de opinión, producto sin stock)",
            icon = Icons.Default.Delete,
            buttonText = "Void Items",
            buttonEnabled = order.items.isNotEmpty(),
            onButtonClick = onVoidItems
        )

        // Apply Discount Card
        ActionCard(
            title = "Aplicar Descuento",
            description = "Descuento porcentual o fijo (promociones, lealtad, manager discretion)",
            icon = Icons.Default.ShoppingCart,
            buttonText = "Aplicar Descuento",
            buttonEnabled = order.items.isNotEmpty(),
            onButtonClick = onApplyDiscount
        )

        // Note about implementation status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "⚠️ En desarrollo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Los diálogos de confirmación y la integración con el backend se implementarán en el Paso 9 (Update MenuViewModel).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Action Card
 *
 * Card displaying an action with icon, title, description, and button.
 */
@Composable
private fun ActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonText: String,
    buttonEnabled: Boolean,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon + Title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Description
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Action button
            Button(
                onClick = onButtonClick,
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonText)
            }
        }
    }
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true)
@Composable
private fun ActionsTabPreview() {
    AvoqadoTheme {
        Text(
            text = "ActionsTab Preview\n(Requires mock Order data)",
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center
        )
    }
}
