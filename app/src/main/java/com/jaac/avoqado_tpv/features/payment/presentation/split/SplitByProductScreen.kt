package com.jaac.avoqado_tpv.features.payment.presentation.split

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payment.presentation.split.components.SelectableItemRow
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Extension function to format OrderItem display name with quantity
 */
private fun com.jaac.avoqado_tpv.features.ordering.domain.OrderItem.displayName(): String =
    if (quantity > 1) "$productName (${quantity}x)" else productName

/**
 * Split By Product Screen
 *
 * Allows user to select specific products from an order to pay.
 * Selected products are passed to PaymentScreen with PERPRODUCT split type.
 *
 * Layout (Dark Theme):
 * ```
 * ┌─────────────────────────────────────────────────────┐
 * │  ←  Queda por pagar: $777.00                        │  TopBar
 * ├─────────────────────────────────────────────────────┤
 * │                                                     │  background
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │  ○  Hamburguesa Doble           $150.00     │   │  Available
 * │  └─────────────────────────────────────────────┘   │
 * │                                                     │
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │  ●  Papas Fritas                $70.00      │   │  Selected
 * │  └─────────────────────────────────────────────┘   │
 * │                                                     │
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │  ✓  Refresco                    Pagado      │   │  Paid (disabled)
 * │  └─────────────────────────────────────────────┘   │
 * │                                                     │
 * ├─────────────────────────────────────────────────────┤
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │       Pagar 1 producto • $70.00             │   │  Action button
 * │  └─────────────────────────────────────────────┘   │
 * └─────────────────────────────────────────────────────┘
 * ```
 *
 * @param onNavigateBack Go back to previous screen
 * @param onProceedToPayment Navigate to PaymentScreen with selected products
 * @param viewModel ViewModel injected by Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitByProductScreen(
    onNavigateBack: () -> Unit,
    onProceedToPayment: (amount: BigDecimal, productIds: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SplitByProductViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Queda por pagar: ${currencyFormatter.format(uiState.remainingAmount)}",
                onNavigationClick = onNavigateBack
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                uiState.isLoading -> {
                    AvoqadoFullScreenLoading(message = "Cargando productos...")
                }

                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.error ?: "Error desconocido",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                uiState.order != null -> {
                    SplitByProductContent(
                        availableItems = uiState.availableItems,
                        paidItems = uiState.paidItems,
                        selectedProductIds = uiState.selectedProductIds,
                        selectedAmount = uiState.selectedAmount,
                        selectedCount = uiState.selectedCount,
                        canProceed = uiState.canProceed,
                        onToggleProduct = viewModel::toggleProduct,
                        onProceedToPayment = {
                            onProceedToPayment(
                                viewModel.getSelectedAmount(),
                                viewModel.getSelectedProductIds()
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Content composable for SplitByProductScreen
 */
@Composable
private fun SplitByProductContent(
    availableItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>,
    paidItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>,
    selectedProductIds: Set<String>,
    selectedAmount: BigDecimal,
    selectedCount: Int,
    canProceed: Boolean,
    onToggleProduct: (String) -> Unit,
    onProceedToPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Product list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Available items (can be selected)
            if (availableItems.isNotEmpty()) {
                item {
                    Text(
                        text = "Selecciona los productos a pagar",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(
                    items = availableItems,
                    key = { item -> "available_${item.id}" }
                ) { item ->
                    SelectableItemRow(
                        label = item.displayName(),
                        trailingText = currencyFormatter.format(item.totalPrice),
                        isSelected = item.id in selectedProductIds,
                        isPaid = false,
                        onTap = { onToggleProduct(item.id) }
                    )
                }
            }

            // Paid items (disabled)
            if (paidItems.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Ya pagados",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(
                    items = paidItems,
                    key = { item -> "paid_${item.id}" }
                ) { item ->
                    SelectableItemRow(
                        label = item.displayName(),
                        trailingText = "Pagado",
                        isSelected = false,
                        isPaid = true,
                        onTap = { }
                    )
                }
            }
        }

        // Bottom action button
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Button(
                onClick = onProceedToPayment,
                enabled = canProceed,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                val buttonText = if (selectedCount > 0) {
                    val productLabel = if (selectedCount == 1) "producto" else "productos"
                    "Pagar $selectedCount $productLabel • ${currencyFormatter.format(selectedAmount)}"
                } else {
                    "Selecciona productos"
                }
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
private fun SplitByProductContentPreview() {
    AvoqadoTheme {
        val mockItems = listOf(
            com.jaac.avoqado_tpv.features.ordering.domain.OrderItem(
                id = "item_1",
                orderId = "order_1",
                productId = "prod_1",
                productName = "Hamburguesa Doble",
                productSku = "HAM-001",
                quantity = 1,
                unitPrice = BigDecimal("150.00"),
                totalPrice = BigDecimal("150.00"),
                modifiers = emptyList(),
                notes = null,
                kitchenStatus = com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus.PENDING,
                createdAt = java.time.Instant.now(),
                sentToKitchenAt = null
            ),
            com.jaac.avoqado_tpv.features.ordering.domain.OrderItem(
                id = "item_2",
                orderId = "order_1",
                productId = "prod_2",
                productName = "Papas Fritas",
                productSku = "PAP-001",
                quantity = 1,
                unitPrice = BigDecimal("70.00"),
                totalPrice = BigDecimal("70.00"),
                modifiers = emptyList(),
                notes = null,
                kitchenStatus = com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus.PENDING,
                createdAt = java.time.Instant.now(),
                sentToKitchenAt = null
            )
        )

        val mockPaidItems = listOf(
            com.jaac.avoqado_tpv.features.ordering.domain.OrderItem(
                id = "item_3",
                orderId = "order_1",
                productId = "prod_3",
                productName = "Refresco",
                productSku = "REF-001",
                quantity = 1,
                unitPrice = BigDecimal("35.00"),
                totalPrice = BigDecimal("35.00"),
                modifiers = emptyList(),
                notes = null,
                kitchenStatus = com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus.SERVED,
                createdAt = java.time.Instant.now(),
                sentToKitchenAt = null
            )
        )

        SplitByProductContent(
            availableItems = mockItems,
            paidItems = mockPaidItems,
            selectedProductIds = setOf("item_2"),
            selectedAmount = BigDecimal("70.00"),
            selectedCount = 1,
            canProceed = true,
            onToggleProduct = {},
            onProceedToPayment = {}
        )
    }
}
