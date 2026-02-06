# 📱 OrderScreen UI Design - PORTRAIT (PAX A910S)

> **Device:** PAX A910S (720x1280 px, 5.5" Portrait)
> **Pattern:** Bottom Sheet (Square/Toast Mobile Standard)
> **Date:** 2025-01-15
> **Status:** ✅ Ready for Implementation

---

## 🎯 Design Principles (Portrait)

1. **Bottom Sheet Pattern** - Industry standard for mobile POS (Square, Toast, Clover mobile)
2. **Products Fullscreen** - Maximum visibility for product browsing
3. **Order in Bottom Sheet** - Pull up to see full order, collapse to see summary
4. **Touch-First** - All targets 48dp+ (finger-friendly)
5. **Single Hand Operation** - FAB and buttons in thumb zone
6. **Dark Theme** - Avoqado OKLCH palette

---

## 📐 Screen Architecture (Portrait)

### Layout Structure (720x1280 dp)

```
┌─────────────────────────────────┐  ← 720dp wide
│ AvoqadoTopBar                   │
│ "Orden #ORD-1234567890"    [←] │
│ Mesa 5 · 2 Personas             │
├─────────────────────────────────┤
│                                 │
│  PRODUCT GRID (Fullscreen)      │
│                                 │
│  [Todas][Bebidas][Comidas]...   │
│  ────────────────────────────   │
│                                 │
│  ┌───────┐ ┌───────┐            │  ← 2 columns (portrait)
│  │  🍕   │ │  🥤   │            │
│  │       │ │       │            │
│  │ Pizza │ │ Coca  │            │
│  │ Marg  │ │ Cola  │            │
│  │       │ │       │            │
│  │ $180  │ │ $35   │            │
│  └───────┘ └───────┘            │
│                                 │
│  ┌───────┐ ┌───────┐            │
│  │  🥗   │ │  🍔   │            │
│  │       │ │       │            │
│  │ Ensala│ │ Hambur│            │
│  │ César │ │ guesa │            │
│  │       │ │       │            │
│  │ $120  │ │ $150  │            │
│  └───────┘ └───────┘            │
│                                 │
│  ┌───────┐ ┌───────┐            │
│  │  🍺   │ │  🌮   │            │
│  │ Cervez│ │ Tacos │            │
│  │ Corona│ │ Pastor│            │
│  │ $50   │ │ $95   │            │
│  └───────┘ └───────┘            │
│                                 │
│  ... (scrollable)               │
│                                 │
├═════════════════════════════════┤  ← Bottom Sheet Handle
│ ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔ │  ← Drag handle (swipe up)
│                                 │
│ ORDER SUMMARY (Collapsed)       │  ← 120dp height (collapsed)
│                                 │
│ 3 items                 $515.00 │
│                                 │
│ [Ver Orden Completa]            │
└─────────────────────────────────┘

WHEN EXPANDED (swipe up or tap "Ver Orden"):

┌─────────────────────────────────┐
│ ▼ Orden #ORD-1234567890    [X] │  ← Collapse button
├─────────────────────────────────┤
│                                 │
│ ORDER ITEMS (Scrollable)        │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ 2x Pizza Margherita         │ │
│ │    $180.00 c/u    $360.00 [X]│ │
│ ├─────────────────────────────┤ │
│ │ 1x Coca-Cola                │ │
│ │    $35.00 c/u     $35.00  [X]│ │
│ ├─────────────────────────────┤ │
│ │ 1x Ensalada César           │ │
│ │    $120.00 c/u   $120.00  [X]│ │
│ └─────────────────────────────┘ │
│                                 │
│ TOTALS                          │
│ ┌─────────────────────────────┐ │
│ │ Subtotal           $515.00  │ │
│ │ IVA (16%)          $82.40   │ │
│ │ ───────────────────────────│ │
│ │ TOTAL              $597.40  │ │
│ └─────────────────────────────┘ │
│                                 │
│ ACTION BUTTONS                  │
│ ┌─────────────────────────────┐ │
│ │ [💳 Tomar Pago]             │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
                                   ↑ 1280dp total height
```

---

## 🎨 Bottom Sheet Behavior

### States

| State | Height | Content | Trigger |
|-------|--------|---------|---------|
| **Collapsed** | 120dp | Item count + Total + "Ver Orden" button | Default state |
| **Peek** | 300dp | Last 2 items + Total + Actions | Swipe up (partial) |
| **Expanded** | 80% screen | All items + Totals + Actions | Swipe up (full) or tap "Ver Orden" |
| **Hidden** | 0dp | None (products fullscreen) | Swipe down completely |

### Gestures

- **Swipe Up** → Expand bottom sheet
- **Swipe Down** → Collapse or hide bottom sheet
- **Tap Handle** → Toggle between collapsed/expanded
- **Tap Background (when expanded)** → Collapse to previous state

### Visual Feedback

```kotlin
// Material 3 Bottom Sheet with scrim
ModalBottomSheet(
    onDismissRequest = { showBottomSheet = false },
    sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false  // Allow peek state
    ),
    scrimColor = Color.Black.copy(alpha = 0.32f),  // Dim background when expanded
    dragHandle = {
        // Custom drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
) {
    OrderSummaryContent(order)
}
```

---

## 📱 Component Specifications (Portrait)

### 1. ProductGridScreen (Main Screen)

**File:** `OrderDetailScreen.kt`

**Structure:**
```kotlin
@Composable
fun OrderDetailScreen(
    orderId: String,
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (String, BigDecimal) -> Unit,
    viewModel: OrderDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showOrderSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Orden #${order.orderNumber}",
                subtitle = "${order.tableName} · ${order.covers} Personas",
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Product Grid (Fullscreen)
            ProductGridPanel(
                categories = state.categories,
                products = state.products,
                onProductTapped = { product ->
                    viewModel.showProductModal(product)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Bottom Sheet (Order Summary)
            if (state is OrderDetailState.Success) {
                OrderBottomSheet(
                    order = (state as OrderDetailState.Success).order,
                    onItemClick = { item -> viewModel.editItem(item) },
                    onItemRemove = { item -> viewModel.removeItem(item) },
                    onPayment = { order ->
                        onNavigateToPayment(order.id, order.total)
                    }
                )
            }
        }
    }
}
```

**Visual Specs:**
- Top bar height: 120dp (double height for title + subtitle)
- Product grid: 2 columns (portrait)
- Grid padding: 16dp
- Item spacing: 12dp

---

### 2. ProductGridPanel (Portrait Layout)

**File:** `components/ProductGridPanel.kt`

**Structure:**
```kotlin
@Composable
fun ProductGridPanel(
    categories: List<ProductCategory>,
    products: List<Product>,
    onProductTapped: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current
    var selectedCategory by remember { mutableStateOf<ProductCategory?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Category Tabs (Horizontal Scroll)
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(selectedCategory),
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                text = { Text("Todas") }
            )

            categories.forEach { category ->
                Tab(
                    selected = category == selectedCategory,
                    onClick = { selectedCategory = category },
                    text = { Text(category.name) }
                )
            }
        }

        // Product Grid (2 columns for portrait)
        val filteredProducts = if (selectedCategory == null) {
            products
        } else {
            products.filter { it.categoryId == selectedCategory.id }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),  // ← PORTRAIT: 2 columns
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sizes.paddingScreen,
                end = sizes.paddingScreen,
                top = sizes.spacingSmall,
                bottom = 180.dp  // ← Space for bottom sheet (collapsed height + buffer)
            ),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium),
            horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
        ) {
            items(filteredProducts, key = { it.id }) { product ->
                ProductCard(
                    product = product,
                    onClick = { onProductTapped(product) }
                )
            }
        }
    }
}
```

**Visual Specs:**
- Columns: **2 (portrait)**
- Card size: 340dp width × 340dp height (square, responsive)
- Bottom padding: 180dp (space for bottom sheet)
- Grid spacing: spacingMedium (16-32dp)

---

### 3. OrderBottomSheet (Collapsible Order Summary)

**File:** `components/OrderBottomSheet.kt`

**Structure:**
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderBottomSheet(
    order: Order,
    onItemClick: (OrderItem) -> Unit,
    onItemRemove: (OrderItem) -> Unit,
    onPayment: (Order) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    val scope = rememberCoroutineScope()

    // Collapsed State (Always visible at bottom)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .align(Alignment.BottomCenter),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    scope.launch {
                        sheetState.expand()
                    }
                }
                .padding(sizes.spacingMedium),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }

            // Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${order.items.size} ${if (order.items.size == 1) "item" else "items"}",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "$${order.total}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // View Order Button
            OutlinedButton(
                onClick = {
                    scope.launch {
                        sheetState.expand()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Ver Orden Completa")
                Spacer(modifier = Modifier.width(sizes.spacingSmall))
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
            }
        }
    }

    // Expanded State (Modal Bottom Sheet)
    if (sheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch {
                    sheetState.hide()
                }
            },
            sheetState = sheetState,
            scrimColor = Color.Black.copy(alpha = 0.32f)
        ) {
            OrderSummaryExpandedContent(
                order = order,
                onItemClick = onItemClick,
                onItemRemove = onItemRemove,
                onPayment = onPayment
            )
        }
    }
}
```

**Visual Specs:**
- Collapsed height: 120dp
- Expanded height: 80% of screen (1024dp)
- Drag handle: 32dp width × 4dp height
- Corner radius: 16dp (top only)
- Elevation: 8dp
- Scrim opacity: 32%

---

### 4. OrderSummaryExpandedContent

**File:** `components/OrderSummaryExpandedContent.kt`

**Structure:**
```kotlin
@Composable
fun OrderSummaryExpandedContent(
    order: Order,
    onItemClick: (OrderItem) -> Unit,
    onItemRemove: (OrderItem) -> Unit,
    onPayment: (Order) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(sizes.paddingScreen)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Orden #${order.orderNumber}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = { /* Close sheet */ }
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Cerrar")
            }
        }

        Spacer(modifier = Modifier.height(sizes.spacingMedium))

        // Order Info
        OrderHeaderCard(order = order)

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Section Title
        Text(
            text = "ITEMS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = sizes.spacingSmall)
        )

        // Items List (Scrollable)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
        ) {
            items(order.items, key = { it.id }) { item ->
                OrderItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onRemove = { onItemRemove(item) }
                )
            }
        }

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Totals
        OrderTotalsCard(
            subtotal = order.subtotal,
            tax = order.taxAmount,
            total = order.total
        )

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Payment Button
        Button(
            onClick = { onPayment(order) },
            modifier = Modifier.fillMaxWidth(),
            enabled = order.items.isNotEmpty()
        ) {
            Icon(Icons.Default.Payment, contentDescription = null)
            Spacer(modifier = Modifier.width(sizes.spacingSmall))
            Text("Tomar Pago")
        }
    }
}
```

**Visual Specs:**
- Padding: paddingScreen (16-24dp)
- Header: titleLarge (22sp), bold
- Section spacing: spacingLarge (24-48dp)
- Button height: 48dp
- Items scrollable: weight(1f)

---

### 5. ProductCard (Portrait Optimized)

**File:** `components/ProductCard.kt`

**Structure:**
```kotlin
@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)  // Square card
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(sizes.spacingSmall),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Product Emoji (Larger for portrait)
            Text(
                text = product.emoji,
                fontSize = 56.sp,  // ← Larger for better visibility
                modifier = Modifier.padding(bottom = sizes.spacingSmall)
            )

            // Product Name
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyLarge,  // ← Slightly larger
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = sizes.spacingSmall)
            )

            // Product Price
            Text(
                text = "$${product.price}",
                style = MaterialTheme.typography.titleMedium,  // ← More prominent
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
```

**Visual Specs (Portrait Optimized):**
- Card size: ~340dp × 340dp (responsive, 2 per row)
- Emoji size: **56sp** (larger for visibility)
- Product name: bodyLarge (16sp), bold, max 2 lines
- Price: titleMedium (16sp), bold, primary color
- Padding: spacingSmall (8-16dp)
- Elevation: 2dp → 6dp on press

---

## 🎬 User Flow (Portrait)

### Flow 1: Browse Products → Add to Order

```
1. User sees product grid (2 columns)
   - Bottom sheet shows: "3 items | $515.00"
   ↓
2. User scrolls down to find "Pizza Margherita"
   ↓
3. User taps Pizza card
   ↓
4. ProductSelectionModal appears (fullscreen dialog)
   - Emoji: 🍕 (64sp)
   - Quantity: [−] [1] [+]
   - Notes: Text field
   - Confirm: "Agregar $180.00"
   ↓
5. User increases quantity to 2
   - Confirm updates: "Agregar $360.00"
   ↓
6. User taps "Agregar $360.00"
   ↓
7. Modal closes → Item added
   - Bottom sheet updates: "4 items | $875.00"
   - Brief toast: "Pizza Margherita agregada"
```

### Flow 2: View Full Order (Expand Bottom Sheet)

```
1. User sees collapsed bottom sheet: "3 items | $515.00"
   ↓
2. User swipes up on bottom sheet handle
   OR taps "Ver Orden Completa"
   ↓
3. Bottom sheet expands to 80% of screen
   - Product grid dims (scrim overlay)
   - Shows all items in scrollable list
   - Shows totals card
   - Shows "Tomar Pago" button
   ↓
4. User reviews items
   ↓
5. User swipes down or taps [↓] button
   ↓
6. Bottom sheet collapses back to 120dp
   - Product grid becomes interactive again
```

### Flow 3: Remove Item from Order

```
1. User expands bottom sheet
   ↓
2. User sees order items:
   - "2x Pizza Margherita $360.00 [X]"
   - "1x Coca-Cola $35.00 [X]"
   ↓
3. User taps [X] on Coca-Cola
   ↓
4. Item removed immediately (optimistic update)
   - Item disappears from list
   - Totals update
   - Snackbar: "Coca-Cola eliminada. [DESHACER]" (3s)
   ↓
5. Bottom sheet count updates: "1 item | $360.00"
```

---

## 📏 Responsive Breakpoints (Portrait)

| Device | Width | Columns | Card Size | Bottom Sheet |
|--------|-------|---------|-----------|--------------|
| **PAX A910S** | 720dp | 2 | 340×340dp | 120dp collapsed |
| **Phone (Small)** | 360dp | 2 | 160×160dp | 100dp collapsed |
| **Phone (Large)** | 480dp | 2 | 220×220dp | 120dp collapsed |

**Adaptive Behavior:**
```kotlin
val columns = when {
    width < 400.dp -> 2  // Small phones
    width < 600.dp -> 2  // Normal phones (including PAX A910S)
    width < 840.dp -> 3  // Large phones / small tablets
    else -> 4            // Tablets
}

val bottomSheetCollapsedHeight = when {
    width < 400.dp -> 100.dp
    width < 600.dp -> 120.dp
    else -> 140.dp
}
```

---

## 🎨 Color Palette (Same as Before)

```kotlin
// Dark Theme (Avoqado OKLCH)
val background = Color(0xFF1C1C1C)
val foreground = Color(0xFFFAFAFA)
val surface = Color(0xFF2A2A2A)
val surfaceVariant = Color(0xFF333333)
val primary = Color(0xFFE8E8E8)
val error = Color(0xFFEB5757)
```

---

## ⚡ Performance Optimizations

### 1. Lazy Loading with Keys

```kotlin
LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    key = { product -> product.id }  // ← CRITICAL for recomposition
) {
    items(products, key = { it.id }) { product ->
        ProductCard(product, onClick)
    }
}
```

### 2. Bottom Sheet State Management

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
val sheetState = rememberModalBottomSheetState(
    skipPartiallyExpanded = false  // Allow peek state
)

// Memoize expensive calculations
val itemCount = remember(order.items) { order.items.size }
val totalPrice = remember(order.total) { order.total }
```

### 3. Scrim Performance

```kotlin
// Use hardware-accelerated alpha blending
scrimColor = Color.Black.copy(alpha = 0.32f)  // Efficient
// NOT: scrimColor = Color(0x52000000)  // Less efficient
```

---

## ✅ Implementation Checklist

### Week 1: Core Layout (Portrait)
- [ ] Create OrderDetailScreen with product grid (2 columns)
- [ ] Implement ProductGridPanel with category tabs
- [ ] Create ProductCard (portrait optimized, 56sp emoji)
- [ ] Test grid scrolling with 15 mock products
- [ ] Test on PAX A910S emulator (720x1280)

### Week 2: Bottom Sheet
- [ ] Implement OrderBottomSheet (collapsed state)
- [ ] Add drag handle and swipe gesture
- [ ] Implement ModalBottomSheet (expanded state)
- [ ] Add OrderSummaryExpandedContent
- [ ] Test expand/collapse transitions

### Week 3: Order Management
- [ ] Implement OrderItemCard in bottom sheet
- [ ] Add item removal with Undo snackbar
- [ ] Add ProductSelectionModal (fullscreen)
- [ ] Implement optimistic updates
- [ ] Test multi-item scenarios

### Week 4: Polish
- [ ] Add loading states (AvoqadoLoadingOverlay)
- [ ] Implement error handling
- [ ] Add empty state (no items)
- [ ] Performance testing (60 FPS scrolling)
- [ ] Accessibility (TalkBack)

---

## 🔑 Key Differences from Landscape Design

| Aspect | Landscape (Wrong) | Portrait (Correct) |
|--------|-------------------|-------------------|
| **Layout** | Split-screen (65/35) | Fullscreen grid + Bottom sheet |
| **Columns** | 3-4 columns | **2 columns** |
| **Order Visibility** | Always visible (right panel) | Collapsible bottom sheet |
| **Emoji Size** | 48sp | **56sp** (more prominent) |
| **Card Size** | 120×120dp | **340×340dp** (larger touch target) |
| **Navigation** | Side-by-side | **Layered (z-axis)** |
| **Interaction** | Two-hand (both panels) | **One-hand (bottom sheet)** |

---

**Last Updated:** 2025-01-15
**Device:** PAX A910S (720x1280 Portrait)
**Status:** ✅ Ready for Domain Model Design
**Next Step:** Design domain models based on PORTRAIT UI requirements
