package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.jaac.avoqado_tpv.R
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
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderTab
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderTabRow
import com.jaac.avoqado_tpv.features.ordering.presentation.components.ProductSelectorBottomSheet
import com.jaac.avoqado_tpv.core.presentation.components.AmountInputBottomSheet
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.payment.presentation.split.SplitOptionsOverlay
import timber.log.Timber
import java.math.BigDecimal

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
 * │                                     │   based on a given `MenuScreen` file, I will change the hardcoded title "Pedido Rápido" to use the string resource `R.string.ordering_quick_order_title` and apply a smaller text style, `MaterialTheme.typography.titleMedium`, to the `titleStyle` parameter to make the font smaller as requested by the user. I'll also add the necessary imports for `stringResource` and the `R` class.
 * │                                     │
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
    onProcessPaymentWithAmount: (Order, BigDecimal, SplitType) -> Unit = { order, _, _ -> onProcessPayment(order) },
    onNavigateToSplitByProduct: (String) -> Unit = {},
    onNavigateToSplitByPerson: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val menuState by viewModel.state.collectAsStateWithLifecycle()
    val isLoadingProducts by viewModel.isLoadingProducts.collectAsStateWithLifecycle()

    // 🎟️ Discount & Coupon state
    val availableDiscounts by viewModel.availableDiscounts.collectAsStateWithLifecycle()
    val appliedDiscounts by viewModel.appliedDiscounts.collectAsStateWithLifecycle()
    val isLoadingDiscounts by viewModel.isLoadingDiscounts.collectAsStateWithLifecycle()
    val couponValidationState by viewModel.couponValidationState.collectAsStateWithLifecycle()

    // 💳 Payment preparation state (force sync before navigating to payment)
    val isPreparingPayment by viewModel.isPreparingPayment.collectAsStateWithLifecycle()

    // Tab state (local to MenuScreen - pure UI concern)
    var currentTab by remember { mutableStateOf(OrderTab.MENU) }

    // Local state for UI (Menu tab specific)
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var showProductSelector by remember { mutableStateOf(false) }

    // Split payment overlay state
    var showSplitOptions by remember { mutableStateOf(false) }

    // Custom amount modal state (for CUSTOMAMOUNT split type)
    var showCustomAmountModal by remember { mutableStateOf(false) }

    // Comp dialog state (Void dialog moved to ActionsTab)
    var showCompDialog by remember { mutableStateOf(false) }

    // Load order on first composition
    LaunchedEffect(orderId) {
        viewModel.loadOrder(orderId)
    }

    // 🚀 Performance Optimization: Deferred Rendering
    // Show skeletal UI immediately, then load heavy content (Product Grid) after navigation settles.
    // Solves 750ms UI freeze ("Skipped 40 frames") on navigation transition.
    var isHeavyContentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Wait 300ms for navigation animation to complete before rendering heavy grids
        kotlinx.coroutines.delay(300)
        isHeavyContentReady = true
    }

    // Extract order and syncError from state (simplified smart cast)
    val successState = menuState as? MenuState.Success
    val order = successState?.order
    val syncError = successState?.syncError

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
                val titleText = order?.let {
                    // Show table name for table service, "Pedido Rápido" for quick orders
                    it.tableName ?: "${stringResource(R.string.ordering_quick_order_title)} #${it.orderNumber.takeLast(4)}"
                } ?: "Nueva Orden"
                AvoqadoTopBar(
                    title = titleText,
                    titleStyle = MaterialTheme.typography.titleMedium,
                    flatBottom = true,
                    onNavigationClick = {
                        // ⭐ Bug fix: Force sync before navigating back to ensure items are saved
                        // Prevents race condition with 5-second debounce
                        viewModel.syncBeforeNavigate { _ -> onNavigateBack() }
                    },
                    actions = {
                        // 🎯 Dynamic header action based on order type (Toast/Square pattern)
                        if (order != null) {
                            when (order.orderType) {
                                OrderType.TAKEOUT -> {
                                    // Pedido Rápido: Primary action = PAY IMMEDIATELY
                                    // Show "Pagar" button for quick checkout
                                    if (order.canProcessPayment) {
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                // ⚠️ CRITICAL: Force sync and fetch recalculated discounts
                                                // before navigating to payment (prevents charging wrong amounts)
                                                viewModel.onPaymentRequested(
                                                    onReady = { preparedOrder -> onProcessPayment(preparedOrder) },
                                                    onError = { error -> /* TODO: show toast */ }
                                                )
                                            }
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
        // Calculate ResponsiveSizes early for use in all children (including error banner)
        val configuration = LocalConfiguration.current
        val sizes = remember(configuration.screenHeightDp, configuration.screenWidthDp) {
            ResponsiveSizes.calculate(
                configuration.screenHeightDp.dp,
                configuration.screenWidthDp.dp
            )
        }

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

            // Sync error/conflict banner (dismissible warning)
            syncError?.let { errorMessage ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sizes.paddingScreen, vertical = sizes.spacingSmall),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(sizes.spacingSmall),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(sizes.spacingSmall))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearSyncError() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Main content area with ResponsiveSizes provided to all children
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                CompositionLocalProvider(
                    LocalResponsiveSizes provides sizes
                ) {
                    if (order == null || !isHeavyContentReady) {
                        // 🚀 Loading state (or Deferred Rendering Placeholder)
                        // If order is loaded but content deferred, show lightweight spinner
                        // This allows navigation animation to finish smoothly
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                    ) {
                        // Use lightweight loading indicator (not full screen) during deferred phase
                        if (order != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            AvoqadoFullScreenLoading(message = "Cargando orden...")
                        }
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
                                onProcessPayment = {
                                    // ⚠️ CRITICAL: Force sync and fetch recalculated discounts
                                    viewModel.onPaymentRequested(
                                        onReady = { preparedOrder -> onProcessPayment(preparedOrder) },
                                        onError = { error -> Timber.e("❌ Payment prep failed: $error") }
                                    )
                                },
                                onSplitPayment = { showSplitOptions = true },
                                onPrintComanda = { viewModel.printFullComanda() },
                                onPrintItem = { item -> viewModel.printSingleItem(item) }  // 🖨️ Print single item
                            )
                        }

                        OrderTab.ACTIONS -> {
                            // Actions tab: Order-level operations (comp, void, discount)
                            ActionsTab(
                                order = order,
                                availableDiscounts = availableDiscounts,
                                appliedDiscounts = appliedDiscounts,
                                isLoadingDiscounts = isLoadingDiscounts,
                                couponValidationState = couponValidationState,
                                onApplyPredefinedDiscount = { discountId, itemIds, reason ->
                                    viewModel.applyPredefinedDiscount(
                                        discountId = discountId,
                                        itemIds = itemIds,
                                        reason = reason
                                    )
                                },
                                onApplyManualDiscount = { type, value, reason, itemIds ->
                                    viewModel.applyManualDiscount(
                                        type = type,
                                        value = value,
                                        reason = reason,
                                        itemIds = itemIds
                                    )
                                },
                                onRemoveDiscount = { orderDiscountId ->
                                    viewModel.removeDiscount(orderDiscountId)
                                },
                                onValidateCoupon = { code ->
                                    viewModel.validateCoupon(code)
                                },
                                onApplyCoupon = { code ->
                                    viewModel.applyCoupon(code)
                                },
                                onCompItems = {
                                    showCompDialog = true
                                },
                                onVoidItems = { itemIds, reason ->
                                    viewModel.voidItems(itemIds, reason)
                                }
                            )
                        }

                        OrderTab.GUEST -> {
                            // Guest tab: Customer search modal (Toast/Square pattern)
                            // NEW: Multi-customer support - customers accumulate in list
                            val customerSearchState by viewModel.customerSearchState.collectAsStateWithLifecycle()
                            val selectedCustomer by viewModel.selectedCustomer.collectAsStateWithLifecycle()
                            val recentCustomers by viewModel.recentCustomers.collectAsStateWithLifecycle()
                            val isLoadingRecentCustomers by viewModel.isLoadingRecentCustomers.collectAsStateWithLifecycle()

                            // Multi-customer state
                            val orderCustomers by viewModel.orderCustomers.collectAsStateWithLifecycle()
                            val isAddingCustomer by viewModel.isAddingCustomer.collectAsStateWithLifecycle()

                            GuestTab(
                                order = order,
                                // Multi-customer support (NEW)
                                orderCustomers = orderCustomers,
                                isAddingCustomer = isAddingCustomer,
                                // 🎁 Loyalty program status (Toast/Square pattern)
                                loyaltyActive = viewModel.loyaltyActive,
                                // Search & selection
                                searchState = customerSearchState,
                                recentCustomers = recentCustomers,
                                isLoadingRecentCustomers = isLoadingRecentCustomers,
                                onSearchCustomer = { query -> viewModel.searchCustomers(query) },
                                onSelectCustomer = { customer -> viewModel.addCustomerToOrder(customer) },
                                onRemoveCustomer = { customerId -> viewModel.removeCustomerFromOrder(customerId) },
                                onCreateAndAddCustomer = { firstName, phone, email ->
                                    viewModel.createAndAddCustomerToOrder(firstName, phone, email)
                                },
                                onLoadRecentCustomers = { viewModel.loadRecentCustomers() },
                                onSaveGuestInfo = { covers, name, phone, requests ->
                                    viewModel.updateGuest(
                                        covers = covers,
                                        customerName = name,
                                        customerPhone = phone,
                                        specialRequests = requests
                                    )
                                },
                                // Deprecated (backward compat)
                                selectedCustomer = selectedCustomer,
                                onClearCustomer = { viewModel.clearSelectedCustomer() }
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

                // 💳 Payment preparation overlay
                // Shows while syncing order and fetching recalculated discounts
                // Prevents navigating to PaymentScreen with wrong amounts
                if (isPreparingPayment) {
                    AvoqadoLoadingOverlay(
                        message = "Preparando pago...",
                        modifier = Modifier.zIndex(3f)  // Above loading overlay
                    )
                }

                // Split options overlay (shows when user taps "Dividir" button)
                if (order != null) {
                    SplitOptionsOverlay(
                        visible = showSplitOptions,
                        hasPartialPayment = order.hasRemainingBalance,
                        paidAmount = order.paidAmount,
                        remainingBalance = order.remainingBalance,
                        lastSplitType = order.lastSplitType,  // ⭐ Restricts incompatible split options
                        onDismiss = { showSplitOptions = false },
                        onProductsSplit = {
                            // ⭐ FIX: Force sync before navigation to ensure backend has all items
                            showSplitOptions = false
                            Timber.d("📦 Split by products selected - syncing before navigation | orderId=${order.id}")
                            viewModel.syncBeforeNavigate { syncedOrderId ->
                                Timber.d("📦 Sync complete, navigating to SplitByProduct | orderId=$syncedOrderId")
                                onNavigateToSplitByProduct(syncedOrderId)
                            }
                        },
                        onPersonsSplit = {
                            // ⭐ FIX: Force sync before navigation to ensure backend has all items
                            showSplitOptions = false
                            Timber.d("👥 Split by persons selected - syncing before navigation | orderId=${order.id}")
                            viewModel.syncBeforeNavigate { syncedOrderId ->
                                Timber.d("👥 Sync complete, navigating to SplitByPerson | orderId=$syncedOrderId")
                                onNavigateToSplitByPerson(syncedOrderId)
                            }
                        },
                        onCustomAmount = {
                            // Close split options overlay and show custom amount modal
                            showSplitOptions = false
                            showCustomAmountModal = true
                            Timber.d("💰 Custom amount selected - showing amount input modal")
                        },
                        onFullPayment = {
                            // Navigate to payment with FULLPAYMENT
                            Timber.d("💳 Full payment selected")
                            // ⚠️ CRITICAL: Force sync and fetch recalculated discounts
                            viewModel.onPaymentRequested(
                                onReady = { preparedOrder -> onProcessPayment(preparedOrder) },
                                onError = { error -> Timber.e("❌ Payment prep failed: $error") }
                            )
                        }
                    )
                }

                // Custom amount modal (for CUSTOMAMOUNT split type)
                if (order != null && showCustomAmountModal) {
                    AmountInputBottomSheet(
                        visible = true,
                        onDismiss = { showCustomAmountModal = false },
                        onConfirm = { customAmountString ->
                            showCustomAmountModal = false

                            val customAmount = customAmountString.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            val remainingBalance = order.remainingBalance  // ✅ FIX: Use actual remaining balance for split payments

                            // Validate: amount <= remaining balance
                            if (customAmount > remainingBalance) {
                                Timber.w("⚠️ Custom amount $customAmount exceeds remaining $remainingBalance")
                                // TODO: Show error toast/snackbar
                                return@AmountInputBottomSheet
                            }

                            if (customAmount > BigDecimal.ZERO) {
                                Timber.d("💰 Processing custom amount: $customAmount with CUSTOMAMOUNT split")
                                // ⚠️ CRITICAL: Force sync before custom amount payment too
                                viewModel.onPaymentRequested(
                                    onReady = { preparedOrder ->
                                        onProcessPaymentWithAmount(preparedOrder, customAmount, SplitType.CUSTOMAMOUNT)
                                    },
                                    onError = { error -> Timber.e("❌ Payment prep failed: $error") }
                                )
                            }
                        }
                    )
                }

                // 🎁 Comp Items Dialog - Select items to comp (100% discount)
                if (order != null && showCompDialog) {
                    ItemSelectionDialog(
                        title = "Comp Items (Cortesía)",
                        subtitle = "Selecciona los items a regalar como cortesía",
                        items = order.items,
                        onDismiss = { showCompDialog = false },
                        onConfirm = { selectedItemIds, reason ->
                            showCompDialog = false
                            if (selectedItemIds.isNotEmpty()) {
                                // Apply 100% discount to selected items
                                viewModel.applyManualDiscount(
                                    type = DiscountType.PERCENTAGE,
                                    value = 100.0,
                                    reason = reason ?: "Cortesía",
                                    itemIds = selectedItemIds
                                )
                            }
                        }
                    )
                }

                // ✨ BARCODE QUICK ADD: Scanner screen (Square POS "Scan & Go" pattern)
                if (successState != null && successState.showBarcodeScanner) {
                    BarcodeQuickAddScreen(
                        onBarcodeScanned = { barcode, format ->
                            viewModel.onBarcodeScanned(barcode, format)
                        },
                        onDismiss = {
                            viewModel.closeBarcodeScanner()
                        },
                        isProcessing = successState.barcodeProcessing,
                        lastScannedProduct = successState.lastScannedProductName,
                        totalScannedCount = successState.totalScannedCount,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // ✨ BARCODE QUICK ADD: Create product dialog when barcode not found
                if (successState != null && successState.showQuickAddDialog && successState.scannedBarcodeForQuickAdd != null) {
                    val categories by viewModel.categories.collectAsStateWithLifecycle()
                    QuickAddProductDialog(
                        barcode = successState.scannedBarcodeForQuickAdd,
                        categories = categories,
                        onConfirm = { name, price, categoryId, trackInventory ->
                            viewModel.createQuickAddProduct(name, price, categoryId, trackInventory)
                        },
                        onDismiss = {
                            viewModel.dismissQuickAddDialog()
                        }
                    )
                }
            }
        }
        }  // Close Column
        }  // Close Scaffold
    }  // Close else block (conditional rendering)
}
// ============================================================================
// Item Selection Dialog (Comp/Void)
// ============================================================================

/**
 * Dialog for selecting order items (used for Comp and Void operations)
 *
 * @param title Dialog title
 * @param subtitle Dialog subtitle/description
 * @param items List of order items to select from
 * @param onDismiss Called when dialog is dismissed
 * @param onConfirm Called with selected item IDs and optional reason
 */
@Composable
private fun ItemSelectionDialog(
    title: String,
    subtitle: String,
    items: List<OrderItem>,
    onDismiss: () -> Unit,
    onConfirm: (selectedItemIds: List<String>, reason: String?) -> Unit
) {
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Item list with checkboxes
                if (items.isEmpty()) {
                    Text(
                        text = "No hay items en la orden",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedItems = if (selectedItems.contains(item.id)) {
                                            selectedItems - item.id
                                        } else {
                                            selectedItems + item.id
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Checkbox(
                                    checked = selectedItems.contains(item.id),
                                    onCheckedChange = { checked ->
                                        selectedItems = if (checked) {
                                            selectedItems + item.id
                                        } else {
                                            selectedItems - item.id
                                        }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.quantity}x ${item.productName}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (item.modifiers.isNotEmpty()) {
                                        Text(
                                            text = item.formattedModifiers,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = item.formattedTotalPrice,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Reason input field
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Razón (opcional)") },
                    placeholder = { Text("Ej: Cliente frecuente, queja justificada...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedItems.toList(), reason.ifBlank { null }) },
                enabled = selectedItems.isNotEmpty()
            ) {
                Text("Confirmar (${selectedItems.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// Previews
// ============================================================================

// NOTE: Previews removed as MenuScreen now requires MenuViewModel (Hilt dependency injection)
// To preview, use Android Studio Preview with @Preview annotation on inner composables
// or run the app directly
