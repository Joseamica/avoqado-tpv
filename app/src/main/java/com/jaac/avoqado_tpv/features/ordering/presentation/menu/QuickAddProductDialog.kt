package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory

/**
 * Quick Add Product Dialog
 *
 * Mostrado cuando se escanea un barcode que no existe en el catálogo.
 * Permite crear el producto en tiempo real sin salir de MenuScreen.
 *
 * **Square POS Pattern:**
 * - Formulario minimalista (solo campos esenciales)
 * - Barcode pre-filled y readonly (no editable)
 * - Validación en tiempo real
 * - Botón "Crear y Agregar" (acción atómica)
 *
 * **Flujo:**
 * ```
 * Usuario escanea: "7501234567890"
 *   ↓
 * Backend: 404 Not Found
 *   ↓
 * Muestra este diálogo con barcode="7501234567890"
 *   ↓
 * Usuario ingresa: nombre="iPhone 15", precio="999.00"
 *   ↓
 * Submit → POST /venues/{venueId}/products/quick-add
 *   ↓
 * Producto creado → Cache local → Agregar a orden
 * ```
 *
 * @param barcode Código de barras escaneado (readonly, informativo)
 * @param categories Lista de categorías disponibles para dropdown
 * @param onConfirm Callback con datos del nuevo producto (name, price, categoryId, trackInventory)
 * @param onDismiss Callback para cancelar
 * @param modifier Modifier for customization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddProductDialog(
    barcode: String,
    categories: List<ProductCategory>,
    onConfirm: (name: String, price: Double, categoryId: String?, trackInventory: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var trackInventory by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // ✅ Validación: nombre no vacío + precio válido
    val isValid = name.isNotBlank() && priceText.toDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Agregar Producto Nuevo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ✅ Barcode (readonly, informativo)
                OutlinedTextField(
                    value = barcode,
                    onValueChange = {},
                    label = { Text("Código de Barras") },
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // ✅ Nombre del producto
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del Producto *") },
                    placeholder = { Text("Ej: iPhone 15 Pro Max 256GB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ✅ Precio
                OutlinedTextField(
                    value = priceText,
                    onValueChange = {
                        // Solo permitir dígitos y punto decimal
                        priceText = it.filter { char -> char.isDigit() || char == '.' }
                    },
                    label = { Text("Precio *") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix = { Text("$") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = priceText.isNotBlank() && priceText.toDoubleOrNull() == null
                )

                // ✅ Categoría (dropdown)
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = categories.find { it.id == selectedCategoryId }?.name ?: "Sin categoría",
                        onValueChange = {},
                        label = { Text("Categoría") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        // Opción "Sin categoría"
                        DropdownMenuItem(
                            text = { Text("Sin categoría") },
                            onClick = {
                                selectedCategoryId = null
                                expanded = false
                            }
                        )

                        // Categorías disponibles
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // ✅ Track Inventory checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = trackInventory,
                        onCheckedChange = { trackInventory = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Rastrear inventario",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val price = priceText.toDoubleOrNull() ?: 0.0
                    onConfirm(name, price, selectedCategoryId, trackInventory)
                },
                enabled = isValid
            ) {
                Text("Crear y Agregar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
