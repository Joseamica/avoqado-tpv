package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType

/**
 * GuestTab - Update guest information
 *
 * Shows conditional form based on order type (DINE_IN vs TAKEOUT).
 * Allows updating customer name, phone, special requests, and covers.
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────┐
 * │ Guest Information                   │ ← Header
 * │ Update customer details             │
 * ├─────────────────────────────────────┤
 * │                                     │
 * │ DINE_IN:                            │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Covers (Number of guests)        │ │
 * │ │ [2]                              │ │
 * │ └─────────────────────────────────┘ │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Customer Name (Optional)         │ │
 * │ │ [John Doe]                       │ │
 * │ └─────────────────────────────────┘ │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Special Requests (Allergies)     │ │
 * │ │ [No nuts, gluten-free]           │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * │ TAKEOUT:                            │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Customer Name                    │ │
 * │ │ [John Doe]                       │ │
 * │ └─────────────────────────────────┘ │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Phone Number                     │ │
 * │ │ [5512345678]                     │ │
 * │ └─────────────────────────────────┘ │
 * │ ┌─────────────────────────────────┐ │
 * │ │ Special Requests                 │ │
 * │ │ [Extra sauce]                    │ │
 * │ └─────────────────────────────────┘ │
 * │                                     │
 * │ [Save Guest Information]            │ ← Save button
 * └─────────────────────────────────────┘
 * ```
 *
 * ## Features
 * - **Conditional Form**: Different fields based on OrderType (DINE_IN vs TAKEOUT)
 * - **DINE_IN Fields**: Covers (number), customerName (optional), specialRequests (allergies)
 * - **TAKEOUT Fields**: customerName (required), customerPhone (required), specialRequests
 * - **Validation**: Basic validation for required fields
 * - **Save**: Updates guest information via MenuViewModel
 *
 * ## Implementation Status
 * This is a placeholder implementation for Step 8. The following features need to be implemented:
 * - [ ] Integration with MenuViewModel.updateGuest() (Step 9)
 * - [ ] Form validation (required fields)
 * - [ ] Success/error handling with Snackbar
 * - [ ] Pre-populate form with existing order data
 *
 * @param order Current order
 * @param onSaveGuestInfo Callback to save guest information (TODO: Step 9)
 * @param modifier Optional modifier
 */
@Composable
fun GuestTab(
    order: Order,
    onSaveGuestInfo: (covers: Int?, customerName: String?, customerPhone: String?, specialRequests: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Form state - initialize with order data
    var covers by remember(order.id) { mutableIntStateOf(order.covers) }
    var customerName by remember(order.id) { mutableStateOf(order.customerName ?: "") }
    var customerPhone by remember(order.id) { mutableStateOf(order.customerPhone ?: "") }
    var specialRequests by remember(order.id) { mutableStateOf(order.specialRequests ?: "") }

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
            text = "Información del Cliente",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = when (order.orderType) {
                OrderType.DINE_IN -> "Actualiza los datos del cliente y restricciones alimentarias"
                OrderType.TAKEOUT -> "Información de contacto para orden para llevar"
                OrderType.DELIVERY -> "Información de contacto y entrega"
                OrderType.PICKUP -> "Información de contacto para recoger orden"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Form card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (order.orderType) {
                    OrderType.DINE_IN -> {
                        // DINE_IN: Covers + Customer Name + Special Requests

                        // Covers field
                        OutlinedTextField(
                            value = covers.toString(),
                            onValueChange = { newValue ->
                                newValue.toIntOrNull()?.let { covers = it.coerceIn(1, 20) }
                            },
                            label = { Text("Número de Comensales") },
                            placeholder = { Text("2") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Customer name field (optional for DINE_IN)
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text("Nombre del Cliente (Opcional)") },
                            placeholder = { Text("Juan Pérez") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Special requests field (allergies, dietary restrictions)
                        OutlinedTextField(
                            value = specialRequests,
                            onValueChange = { specialRequests = it },
                            label = { Text("Alergias / Restricciones Alimentarias") },
                            placeholder = { Text("Sin nueces, sin gluten...") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                    }

                    OrderType.TAKEOUT, OrderType.DELIVERY, OrderType.PICKUP -> {
                        // TAKEOUT/DELIVERY/PICKUP: Customer Name + Phone + Special Requests

                        // Customer name field (required for TAKEOUT/DELIVERY/PICKUP)
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text("Nombre del Cliente *") },
                            placeholder = { Text("Juan Pérez") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = customerName.isBlank()
                        )

                        // Phone field (required for TAKEOUT/DELIVERY/PICKUP)
                        OutlinedTextField(
                            value = customerPhone,
                            onValueChange = { customerPhone = it },
                            label = { Text("Teléfono *") },
                            placeholder = { Text("5512345678") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = customerPhone.isBlank()
                        )

                        // Special requests field
                        OutlinedTextField(
                            value = specialRequests,
                            onValueChange = { specialRequests = it },
                            label = { Text("Instrucciones Especiales") },
                            placeholder = { Text("Extra salsa, sin cebolla...") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                    }
                }
            }
        }

        // Save button
        Button(
            onClick = {
                val coversValue = if (order.orderType == OrderType.DINE_IN) covers else null
                val phoneValue = if (order.orderType == OrderType.TAKEOUT || order.orderType == OrderType.DELIVERY || order.orderType == OrderType.PICKUP) {
                    customerPhone.ifBlank { null }
                } else null

                onSaveGuestInfo(
                    coversValue,
                    customerName.ifBlank { null },
                    phoneValue,
                    specialRequests.ifBlank { null }
                )
            },
            enabled = when (order.orderType) {
                OrderType.DINE_IN -> true  // All fields optional for DINE_IN
                OrderType.TAKEOUT, OrderType.DELIVERY, OrderType.PICKUP -> {
                    // Name and phone required for TAKEOUT/DELIVERY/PICKUP
                    customerName.isNotBlank() && customerPhone.isNotBlank()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar Información")
        }

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
                    text = "La integración con MenuViewModel.updateGuest() y el manejo de errores se implementarán en el Paso 9.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true)
@Composable
private fun GuestTabPreview() {
    AvoqadoTheme {
        Text(
            text = "GuestTab Preview\n(Requires mock Order data)",
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center
        )
    }
}
