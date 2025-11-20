package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
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
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderTab
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderTabRow
import com.jaac.avoqado_tpv.features.ordering.presentation.components.CategoryTabs
import com.jaac.avoqado_tpv.features.ordering.presentation.components.OrderTopPanel
import com.jaac.avoqado_tpv.features.ordering.presentation.components.PanelState
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductGrid
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductSelectorBottomSheet
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant

/**
 * Menu Screen - 4-Tab Order Management (Square POS Pattern)
 *
 * **REFACTORED:** Now uses 4-tab bottom navigation for specialized workflows:
 * 1. **Menu Tab** - Product browsing and quick-add
 * 2. **Check Tab** - Order review and item management
 * 3. **Actions Tab** - Comp, void, and discount operations
 * 4. **Guest Tab** - Customer information management
 *
 * This replaces the previous hybrid overlay pattern with dedicated
 * spaces for each operation, following Square POS design principles.
 *
 * Layout (Square POS Pattern):
 * ```
 * ┌─────────────────────────────────────┐
 * │ AvoqadoTopBar: "Mesa 5" [←] [Send] │ ← 56dp
 * ├─────────────────────────────────────┤
 * │ [Menú] [Cuenta] [Acciones] [Cliente]│ ← 48dp (top tabs - text only)
 * ├─────────────────────────────────────┤
 * │                                     │
 * │   [Tab-specific content here]       │ ← Dynamic content
 * │                                     │   based on currentTab
 * │   (Maximum vertical space!)         │
 * │                                     │
 * └─────────────────────────────────────┘
 * ```
 *
 * User Flow:
 * 1. Navigate from Mesas tab (table selected)
 * 2. **Menu tab**: Browse & add products to order
 * 3. **Check tab**: Review items, adjust quantities
 * 4. **Actions tab**: Apply discounts, comp items
 * 5. **Guest tab**: Update customer information
 * 6. Tap "Pagar" → Navigate to PaymentScreen
 *
 * @param orderId Order ID to load
 * @param onNavigateBack Navigate to previous screen (Mesas tab)
 * @param onProcessPayment Navigate to PaymentScreen with order
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
    val isLoadingProducts by viewModel.isLoadingProducts.collectAsStateWithLifecycle()

    // Tab state (local to MenuScreen - pure UI concern)
    var currentTab by remember { mutableStateOf(OrderTab.MENU) }

    // Local state for UI (Menu tab specific)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top tabs (Square POS pattern - text only, no icons)
            OrderTabRow(
                currentTab = currentTab,
                onTabSelected = { newTab -> currentTab = newTab },
                orderItemCount = order?.items?.size ?: 0
            )

            // Provide ResponsiveSizes to all children (required by ProductGrid, CategoryTabs, etc.)
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
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
                    // Route to tab-specific content based on currentTab
                    // Tab-specific content (full screen - no overlay)
                    when (currentTab) {
                            OrderTab.MENU -> {
                                // Get products and categories from ViewModel
                                val filteredProducts by viewModel.filteredProducts.collectAsStateWithLifecycle()
                                val categories by viewModel.categories.collectAsStateWithLifecycle()
                                val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

                                // Menu tab: Product browsing and adding
                                MenuTab(
                                    order = order,
                                    products = filteredProducts,
                                    categories = categories,
                                    searchQuery = searchQuery,
                                    selectedCategory = selectedCategory,
                                    selectedProduct = selectedProduct,
                                    showProductSelector = showProductSelector,
                                    onCategorySelected = { selectedCategory = it },
                                    onSearchQueryChange = { query -> viewModel.setSearchQuery(query) },
                                    onClearSearch = { viewModel.clearSearch() },
                                    onProductClick = { product ->
                                        // Check if order can accept items
                                        if (!order.canAddItems) {
                                            Timber.w("⚠️ Cannot add items - order status: ${order.status}")
                                            return@MenuTab
                                        }

                                        // Conditional modal based on modifiers from backend
                                        if (!product.hasModifiers) {
                                            // Quick-add: Product has no modifiers, add directly with quantity 1
                                            Timber.d("🚀 Quick-add: ${product.name} (no modifiers)")
                                            viewModel.addItem(
                                                product = product,
                                                quantity = 1,
                                                modifiers = emptyList(),
                                                notes = ""
                                            )
                                            // Expand panel to show the added item
                                            panelState = PanelState.PEEK
                                        } else {
                                            // Show modal for products with modifiers
                                            Timber.d("⚙️ Opening modal: ${product.name} (${product.modifierGroups.size} modifier groups)")
                                            selectedProduct = product
                                            showProductSelector = true
                                        }
                                    },
                                    onProductSelectorDismiss = {
                                        showProductSelector = false
                                        selectedProduct = null
                                    },
                                    onProductSelectorConfirm = { product, quantity, modifiers, notes ->
                                        viewModel.addItem(product, quantity, modifiers, notes)
                                        // Expand panel to show order items
                                        panelState = PanelState.PEEK
                                        // Reset state
                                        showProductSelector = false
                                        selectedProduct = null
                                    }
                                )
                            }

                        OrderTab.CHECK -> {
                            // Check tab: Order review and item management
                            CheckTab(
                                order = order,
                                onItemQuantityChange = { item, newQty ->
                                    viewModel.updateItemQuantity(item, newQty)
                                },
                                onItemRemove = { item ->
                                    viewModel.removeItem(item)
                                },
                                onSendToKitchen = { viewModel.sendToKitchen() },
                                onProcessPayment = { onProcessPayment(order) }
                            )
                        }

                        OrderTab.ACTIONS -> {
                            // Actions tab: Order-level operations (comp, void, discount)
                            ActionsTab(
                                order = order,
                                onCompItems = {
                                    // TODO Step 9: Implement comp dialog and ViewModel integration
                                    Timber.d("🎁 Comp Items clicked - Dialog pending")
                                },
                                onVoidItems = {
                                    // TODO Step 9: Implement void dialog and ViewModel integration
                                    Timber.d("🗑️ Void Items clicked - Dialog pending")
                                },
                                onApplyDiscount = {
                                    // TODO Step 9: Implement discount dialog and ViewModel integration
                                    Timber.d("💰 Apply Discount clicked - Dialog pending")
                                }
                            )
                        }

                        OrderTab.GUEST -> {
                            // Guest tab: Update customer information
                            GuestTab(
                                order = order,
                                onSaveGuestInfo = { covers, name, phone, requests ->
                                    viewModel.updateGuest(
                                        covers = covers,
                                        customerName = name,
                                        customerPhone = phone,
                                        specialRequests = requests
                                    )
                                }
                            )
                        }
                    }
                }

                // Show loading overlay while products are loading
                // This appears during "Loaded X products" and "Extracted X categories" operations
                if (isLoadingProducts) {
                    AvoqadoLoadingOverlay(
                        message = "Cargando productos y categorías...",
                        modifier = Modifier.zIndex(2f)  // Above everything else
                    )
                }
            }
        }
        }  // Close Column
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

