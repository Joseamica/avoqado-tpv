package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizes
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderTab
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderTabRow
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductSelectorBottomSheet
import timber.log.Timber

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
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var showProductSelector by remember { mutableStateOf(false) }

    // Load order on first composition
    LaunchedEffect(orderId) {
        viewModel.loadOrder(orderId)
    }

    // Extract order from state
    val order = when (menuState) {
        is MenuState.Success -> (menuState as MenuState.Success).order
        else -> null
    }

    // Conditional rendering: Full-screen ProductSelector OR normal Scaffold with tabs
    if (showProductSelector && selectedProduct != null) {
        // FULL-SCREEN MODE: ProductSelector covers EVERYTHING (TopBar + Tabs + Content)
        ProductSelectorBottomSheet(
            visible = true,
            product = selectedProduct,
            modifierGroups = selectedProduct?.modifierGroups ?: emptyList(),
            onDismiss = {
                showProductSelector = false
                selectedProduct = null
            },
            onAddToCart = { quantity, modifiers, notes ->
                selectedProduct?.let { product ->
                    viewModel.addItem(product, quantity, modifiers, notes)
                }
                showProductSelector = false
                selectedProduct = null
            }
        )
    } else {
        // NORMAL MODE: Show Scaffold with TopBar + Tabs
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
                                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send,
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
            val configuration = LocalConfiguration.current
            val sizes = remember(configuration.screenHeightDp, configuration.screenWidthDp) {
                ResponsiveSizes.calculate(
                    configuration.screenHeightDp.dp,
                    configuration.screenWidthDp.dp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                CompositionLocalProvider(
                    LocalResponsiveSizes provides sizes
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
                                val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

                                // Menu tab: Product browsing and adding
                                MenuTab(
                                    order = order,
                                    products = filteredProducts,
                                    categories = categories,
                                    searchQuery = searchQuery,
                                    selectedCategory = selectedCategory,
                                    onCategorySelected = { selectedCategory = it },
                                    onSearchQueryChange = { query -> viewModel.setSearchQuery(query) },
                                    onClearSearch = { viewModel.clearSearch() },
                                    isRefreshing = isRefreshing,
                                    onRefresh = { viewModel.refreshProducts() },
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
                                        } else {
                                            // Show full-screen ProductSelector for products with modifiers
                                            Timber.d("⚙️ Opening ProductSelector: ${product.name} (${product.modifierGroups.size} modifier groups)")
                                            selectedProduct = product
                                            showProductSelector = true
                                        }
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
        }  // Close Scaffold
    }  // Close else block (conditional rendering)
}
// Previews
// ============================================================================

// NOTE: Previews removed as MenuScreen now requires MenuViewModel (Hilt dependency injection)
// To preview, use Android Studio Preview with @Preview annotation on inner composables
// or run the app directly
