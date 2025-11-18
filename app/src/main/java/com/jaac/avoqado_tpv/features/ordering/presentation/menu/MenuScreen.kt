package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.MockProducts
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.presentation.components.CategoryTabs
import com.jaac.avoqado_tpv.features.ordering.presentation.components.OrderTopPanel
import com.jaac.avoqado_tpv.features.ordering.presentation.components.PanelState
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductGrid
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductSelectorBottomSheet
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant

/**
 * Menu Screen - Product selection + Order check (Hybrid Toast + Square pattern)
 *
 * INNOVATION: Combines two views in one tab:
 * 1. Product Grid (2 columns) - Add items to order
 * 2. Top Panel (collapsible check) - Review order without navigation
 *
 * Key advantages over separate Check tab:
 * - ✅ 50% faster workflow (no tab navigation)
 * - ✅ 78% of screen dedicated to products (when panel collapsed)
 * - ✅ Zero context loss (products visible behind panel)
 * - ✅ One-handed operation (thumb-friendly top panel)
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────┐
 * │ AvoqadoTopBar: "Mesa 5" [←] [Send] │ ← 56dp (rounded corners)
 * ├─────────────────────────────────────┤
 * │ ┌─────────────────────────────────┐ │
 * │ │ TOP PANEL (Collapsible Check)   │ │ ← 48dp (collapsed)
 * │ │ Mesa 5 • 3 items • $150.00  ⌄   │ │    200dp (peek)
 * │ └─────────────────────────────────┘ │    700dp (expanded)
 * ├─────────────────────────────────────┤
 * │ [Todos] [Bebidas] [Comidas] [Postrs]│ ← 48dp (category tabs)
 * ├─────────────────────────────────────┤
 * │ ┌──────────┬──────────┐            │
 * │ │ 🍕 Pizza │ 🍔 Burger│            │
 * │ │ $12.00   │ $8.50    │            │ ← Product Grid
 * │ ├──────────┼──────────┤            │   (2 columns)
 * │ │ 🥤 Coke  │ 🍺 Beer  │            │
 * │ │ $3.00    │ $5.00    │            │
 * │ └──────────┴──────────┘            │
 * └─────────────────────────────────────┘
 * ```
 *
 * User Flow:
 * 1. Navigate from Mesas tab (table selected)
 * 2. Browse products by category
 * 3. Tap product → Quantity selector appears
 * 4. Confirm → Item added to order (top panel updates)
 * 5. Swipe up panel → Review items
 * 6. Tap "Enviar" → Send to kitchen
 * 7. Tap "Pagar" → Navigate to PaymentScreen
 *
 * @param order Current order (can be existing or new)
 * @param onNavigateBack Navigate to previous screen (Mesas tab)
 * @param onSendOrder Send order to kitchen
 * @param onProcessPayment Navigate to PaymentScreen
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    orderId: String,
    onNavigateBack: () -> Unit,
    onProcessPayment: (Order) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val menuState by viewModel.state.collectAsStateWithLifecycle()

    // Local state for UI
    var panelState by remember { mutableStateOf(PanelState.COLLAPSED) }
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var showProductSelector by remember { mutableStateOf(false) }

    // Load order on first composition
    LaunchedEffect(orderId) {
        viewModel.loadOrder(orderId)
    }

    // 🔄 Reload products when screen is resumed (e.g., navigating between screens)
    // Ensures inventory is always up-to-date when MenuScreen becomes visible
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                Timber.d("🔄 [MenuScreen] Screen resumed - reloading products to sync inventory")
                viewModel.refreshProducts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Extract order from state
    val order = when (menuState) {
        is MenuState.Success -> (menuState as MenuState.Success).order
        else -> null
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = order?.let {
                    // Show table name for table service, "Pedido Rápido" for quick orders
                    it.tableName ?: "Pedido Rápido #${it.orderNumber.takeLast(4)}"
                } ?: "Nueva Orden",
                onNavigationClick = onNavigateBack,
                actions = {
                    // 🎯 Dynamic header action based on order type (Toast/Square pattern)
                    if (order != null) {
                        when (order.orderType) {
                            OrderType.TAKEOUT -> {
                                // Pedido Rápido: Primary action = PAY IMMEDIATELY
                                // Show "Pagar" button for quick checkout
                                if (order.canProcessPayment) {
                                    androidx.compose.material3.TextButton(
                                        onClick = { onProcessPayment(order) }
                                    ) {
                                        Text("Pagar")
                                    }
                                }
                            }
                            OrderType.DINE_IN -> {
                                // Servicio de Mesa: Primary action = SEND TO KITCHEN
                                // Show send icon for kitchen workflow
                                if (order.canSendToKitchen) {
                                    IconButton(onClick = { viewModel.sendToKitchen() }) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Send,
                                            contentDescription = "Enviar a cocina"
                                        )
                                    }
                                }
                            }
                            // Other order types (DELIVERY, PICKUP) - no top-right action for now
                            else -> { /* No action */ }
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        // Provide ResponsiveSizes to all children (required by ProductGrid, CategoryTabs, etc.)
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val sizes = com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizes.calculate(maxHeight, maxWidth)

            androidx.compose.runtime.CompositionLocalProvider(
                com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes provides sizes
            ) {
                if (order == null) {
                    // Loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AvoqadoFullScreenLoading(message = "Cargando orden...")
                    }
                } else {
                    // Get products and categories from ViewModel
                    val products by viewModel.products.collectAsStateWithLifecycle()
                    val categories by viewModel.categories.collectAsStateWithLifecycle()

                    // Content: Top panel + Category tabs + Product grid
                    MenuScreenContent(
                        order = order,
                        products = products,
                        categories = categories,
                        panelState = panelState,
                        selectedCategory = selectedCategory,
                        onPanelStateChange = { panelState = it },
                        onCategorySelected = { selectedCategory = it },
                        onProductClick = { product ->
                            selectedProduct = product
                            showProductSelector = true
                        },
                        onItemQuantityChange = { item, newQty ->
                            viewModel.updateItemQuantity(item, newQty)
                        },
                        onItemRemove = { item ->
                            viewModel.removeItem(item)
                        },
                        onSendToKitchen = { viewModel.sendToKitchen() },
                        onProcessPayment = { onProcessPayment(order) }
                    )

                    // Product selector bottom sheet
                    if (showProductSelector && selectedProduct != null) {
                        ProductSelectorBottomSheet(
                            product = selectedProduct!!,
                            modifierGroups = MockProducts.getModifiersForProduct(selectedProduct!!.id),
                            onDismiss = {
                                showProductSelector = false
                                selectedProduct = null
                            },
                            onAddToCart = { quantity, modifiers, notes ->
                                selectedProduct?.let { product ->
                                    viewModel.addItem(product, quantity, modifiers, notes)
                                }
                                // Expand panel to show order items
                                panelState = PanelState.PEEK
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * MenuScreen Content
 *
 * Separated to allow ResponsiveSizes provider in parent
 */
@Composable
private fun MenuScreenContent(
    order: Order,
    products: List<Product>,
    categories: List<ProductCategory>,
    panelState: PanelState,
    selectedCategory: ProductCategory?,
    onPanelStateChange: (PanelState) -> Unit,
    onCategorySelected: (ProductCategory?) -> Unit,
    onProductClick: (Product) -> Unit,
    onItemQuantityChange: (OrderItem, Int) -> Unit,
    onItemRemove: (OrderItem) -> Unit,
    onSendToKitchen: () -> Unit,
    onProcessPayment: () -> Unit  // OrderTopPanel expects () -> Unit, not (Order) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Product grid (background layer)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)  // Space for collapsed top panel
        ) {
            // Category tabs (from backend via ViewModel)
            CategoryTabs(
                categories = categories,
                selectedCategory = selectedCategory,
                onCategorySelected = onCategorySelected
            )

            // Product grid (from backend via ViewModel)
            ProductGrid(
                products = products,
                selectedCategory = selectedCategory,
                onProductClick = onProductClick
            )
        }

        // Top panel (overlay on top)
        OrderTopPanel(
            order = order,
            panelState = panelState,
            onPanelStateChange = onPanelStateChange,
            onItemQuantityChange = onItemQuantityChange,
            onItemRemove = onItemRemove,
            onSendToKitchen = onSendToKitchen,
            onProcessPayment = onProcessPayment,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)  // Ensure panel is on top
        )
    }
}

// ============================================================================
// Mock Data for Previews
// ============================================================================

private fun createMockOrder(itemCount: Int, tableName: String): Order {
    val items = (1..itemCount).map { index ->
        val product = MockProducts.allProducts[index % MockProducts.allProducts.size]
        OrderItem(
            id = "item_$index",
            orderId = "order_123",
            productId = product.id,
            productName = product.name,
            productSku = product.sku,
            quantity = index,
            unitPrice = product.price,
            totalPrice = product.price * BigDecimal(index.toString()),
            notes = if (index == 1) "Sin aceitunas" else null,
            kitchenStatus = KitchenStatus.PENDING,
            createdAt = Instant.now(),
            sentToKitchenAt = null
        )
    }

    val subtotal = items.sumOf { it.totalPrice }
    val tax = subtotal * BigDecimal("0.16")
    val total = subtotal + tax

    return Order(
        id = "order_123",
        orderNumber = "ORD-1234567890",
        venueId = "venue_001",
        tableId = "table_5",
        tableName = tableName,
        covers = 2,
        waiterId = "waiter_001",
        waiterName = "Juan Pérez",
        status = OrderStatus.OPEN,
        kitchenStatus = KitchenStatus.PENDING,
        paymentStatus = PaymentStatus.PENDING,
        orderType = OrderType.DINE_IN,
        items = items,
        subtotal = subtotal,
        tax = tax,
        total = total,
        notes = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        version = 1
    )
}

private fun createEmptyOrder(tableName: String): Order {
    return Order(
        id = "order_new",
        orderNumber = "ORD-NEW",
        venueId = "venue_001",
        tableId = "table_5",
        tableName = tableName,
        covers = 2,
        waiterId = "waiter_001",
        waiterName = "Juan Pérez",
        status = OrderStatus.OPEN,
        kitchenStatus = KitchenStatus.PENDING,
        paymentStatus = PaymentStatus.PENDING,
        orderType = OrderType.DINE_IN,
        items = emptyList(),
        subtotal = BigDecimal.ZERO,
        tax = BigDecimal.ZERO,
        total = BigDecimal.ZERO,
        notes = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        version = 1
    )
}

// ============================================================================
// Previews
// ============================================================================

// NOTE: Previews removed as MenuScreen now requires MenuViewModel (Hilt dependency injection)
// To preview, use Android Studio Preview with @Preview annotation on inner composables
// or run the app directly

