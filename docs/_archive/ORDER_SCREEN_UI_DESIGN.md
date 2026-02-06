# 📱 OrderScreen UI Design - Avoqado TPV

> **Based on:** Toast POS, Square POS, Clover, Lightspeed, TouchBistro analysis
> **Date:** 2025-01-15
> **Status:** ✅ Ready for Implementation

---

## 🎯 Design Principles

1. **Split-Screen Layout** - Industry standard (products left, order right)
2. **Touch-First** - All targets 80dp+ (responsive sizing)
3. **Real-Time Sync** - Socket.IO for multi-terminal coordination
4. **Dark Theme** - Consistent with Avoqado Web Dashboard
5. **Responsive** - Works on PAX A80 (small), A920 (medium), Sunmi T2s (large)
6. **Zero Friction** - No confirmations for reversible actions

---

## 📐 Screen Architecture

### Layout Structure (Landscape-First)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ AvoqadoTopBar: "Orden #ORD-1234567890"                       [⚙] [←]   │
│ Subtitle: "Mesa 5 · 2 Personas · Juan Pérez"                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                    │                                    │
│  PRODUCT SELECTION (65%)           │  ORDER SUMMARY (35%)               │
│                                    │                                    │
│  ┌──────────────────────────────┐ │  ┌──────────────────────────────┐ │
│  │ [Todas][Bebidas][Comidas]... │ │  │ ORDER HEADER                 │ │
│  └──────────────────────────────┘ │  │ Mesa 5 · 2 Personas          │ │
│                                    │  │ Mesero: Juan Pérez           │ │
│  ┌────────┐ ┌────────┐ ┌────────┐ │  │ Estado: PENDIENTE            │ │
│  │ 🍕     │ │ 🥤     │ │ 🥗     │ │  │ ──────────────────────────   │ │
│  │        │ │        │ │        │ │  └──────────────────────────────┘ │
│  │ Pizza  │ │ Coca   │ │ Ensala │ │                                    │
│  │ Marghe │ │ Cola   │ │ César  │ │  ITEMS LIST                        │
│  │        │ │        │ │        │ │  ┌──────────────────────────────┐ │
│  │ $180   │ │ $35    │ │ $120   │ │  │ 2x Pizza Margherita          │ │
│  └────────┘ └────────┘ └────────┘ │  │    $180.00 c/u    $360.00 [X]│ │
│                                    │  ├──────────────────────────────┤ │
│  ┌────────┐ ┌────────┐ ┌────────┐ │  │ 1x Coca-Cola                 │ │
│  │ 🍔     │ │ 🍺     │ │ 🌮     │ │  │    $35.00 c/u     $35.00  [X]│ │
│  │        │ │        │ │        │ │  ├──────────────────────────────┤ │
│  │ Hambur │ │ Cervez │ │ Tacos  │ │  │ 1x Ensalada César            │ │
│  │ guesa  │ │ Corona │ │ Pastor │ │  │    $120.00 c/u   $120.00  [X]│ │
│  │        │ │        │ │        │ │  └──────────────────────────────┘ │
│  │ $150   │ │ $50    │ │ $95    │ │                                    │
│  └────────┘ └────────┘ └────────┘ │  TOTALS                            │
│                                    │  ┌──────────────────────────────┐ │
│  ... more products (scrollable)    │  │ Subtotal           $515.00   │ │
│                                    │  │ IVA (16%)          $82.40    │ │
│                                    │  │ ──────────────────────────   │ │
│                                    │  │ TOTAL              $597.40   │ │
│                                    │  └──────────────────────────────┘ │
│                                    │                                    │
│                                    │  ACTION BUTTONS                    │
│                                    │  ┌──────────────────────────────┐ │
│                                    │  │ [+ Agregar Items]            │ │
│                                    │  │ [💳 Tomar Pago]              │ │
│                                    │  └──────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### Responsive Breakpoints

| Device | Width | Layout |
|--------|-------|--------|
| PAX A80 | 1024x600 dp | Products: 2 cols, Summary: Stack below |
| PAX A920 | 1280x720 dp | Products: 3 cols, Summary: 35% right |
| Sunmi T2s | 1280x800 dp | Products: 3 cols, Summary: 30% right |

**Small Screen Adaptation (PAX A80):**
```
┌─────────────────────────────────────┐
│ AvoqadoTopBar                       │
├─────────────────────────────────────┤
│ PRODUCT SELECTION (Full Width)      │
│ [Todas][Bebidas][Comidas]           │
│ ┌────────┐ ┌────────┐               │
│ │ 🍕     │ │ 🥤     │               │
│ │ Pizza  │ │ Coca   │               │
│ │ $180   │ │ $35    │               │
│ └────────┘ └────────┘               │
│ ┌────────┐ ┌────────┐               │
│ │ 🍔     │ │ 🍺     │               │
│ │ Hambur │ │ Cervez │               │
│ │ $150   │ │ $50    │               │
│ └────────┘ └────────┘               │
├─────────────────────────────────────┤
│ ORDER SUMMARY (Bottom)              │
│ 2x Pizza $360.00 [X]                │
│ 1x Coca  $35.00  [X]                │
│ ──────────────────                  │
│ TOTAL: $395.00                      │
│ [+ Items] [💳 Pago]                 │
└─────────────────────────────────────┘
```

---

## 🎨 Component Specifications

### 1. ProductSelectionPanel

**File:** `OrderDetailScreen.kt` (inline) or `components/ProductSelectionPanel.kt`

**Structure:**
```kotlin
@Composable
fun ProductSelectionPanel(
    categories: List<ProductCategory>,
    products: List<Product>,
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory?) -> Unit,
    onProductTapped: (Product) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Category Tabs
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(selectedCategory),
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            // "Todas" tab
            Tab(
                selected = selectedCategory == null,
                onClick = { onCategorySelected(null) },
                text = { Text("Todas") }
            )

            // Category tabs
            categories.forEach { category ->
                Tab(
                    selected = category == selectedCategory,
                    onClick = { onCategorySelected(category) },
                    text = { Text(category.name) }
                )
            }
        }

        // Product Grid
        val sizes = LocalResponsiveSizes.current
        val columns = when {
            sizes.isSmall -> 2
            sizes.isMedium -> 3
            else -> 4
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(sizes.paddingScreen),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium),
            horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
        ) {
            val filteredProducts = if (selectedCategory == null) {
                products
            } else {
                products.filter { it.categoryId == selectedCategory.id }
            }

            items(filteredProducts) { product ->
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
- Tab height: 48dp
- Tab text: 14sp, bold when selected
- Selected tab: Primary color background
- Unselected tab: Surface color
- Tab indicator: 3dp thick, primary color

---

### 2. ProductCard

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
            .aspectRatio(1f)  // Square cards
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(sizes.spacingSmall),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Product Emoji/Icon
            Text(
                text = product.emoji,
                fontSize = 48.sp,
                modifier = Modifier.padding(bottom = sizes.spacingSmall)
            )

            // Product Name
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = sizes.spacingSmall)
            )

            // Product Price
            Text(
                text = "$${product.price}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
```

**Visual Specs:**
- Card aspect ratio: 1:1 (square)
- Card elevation: 2dp → 4dp on press
- Emoji size: 48sp
- Product name: 14sp, bold, max 2 lines
- Price: 12sp, primary color, semibold
- Padding: ResponsiveScaffold spacingSmall (8-16dp)
- Touch ripple: Enabled

---

### 3. OrderSummaryPanel

**File:** `components/OrderSummaryPanel.kt`

**Structure:**
```kotlin
@Composable
fun OrderSummaryPanel(
    order: Order,
    onItemClick: (OrderItem) -> Unit,
    onItemRemove: (OrderItem) -> Unit,
    onSendToKitchen: () -> Unit,
    onPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(sizes.paddingScreen)
    ) {
        // Order Header
        OrderHeaderCard(order = order)

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Section Title
        Text(
            text = "ITEMS DE LA ORDEN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = sizes.spacingMedium)
        )

        // Items List
        if (order.items.isEmpty()) {
            EmptyOrderState(onAddItems = { /* Show product modal */ })
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                items(order.items) { item ->
                    OrderItemCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onRemove = { onItemRemove(item) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Totals Card
        OrderTotalsCard(
            subtotal = order.subtotal,
            tax = order.taxAmount,
            total = order.total
        )

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Action Buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
        ) {
            // Send to Kitchen (if not sent yet)
            if (order.kitchenStatus == KitchenStatus.PENDING) {
                OutlinedButton(
                    onClick = onSendToKitchen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Restaurant, contentDescription = null)
                    Spacer(modifier = Modifier.width(sizes.spacingSmall))
                    Text("Enviar a Cocina")
                }
            }

            // Payment Button
            Button(
                onClick = onPayment,
                modifier = Modifier.fillMaxWidth(),
                enabled = order.items.isNotEmpty()
            ) {
                Icon(Icons.Default.Payment, contentDescription = null)
                Spacer(modifier = Modifier.width(sizes.spacingSmall))
                Text("Tomar Pago")
            }
        }
    }
}
```

**Visual Specs:**
- Background: Surface color
- Padding: ResponsiveScaffold paddingScreen (16-24dp)
- Section spacing: spacingLarge (24-48dp)
- Button height: 48dp
- Button spacing: spacingMedium (16-32dp)

---

### 4. OrderHeaderCard

**File:** `components/OrderHeaderCard.kt`

**Structure:**
```kotlin
@Composable
fun OrderHeaderCard(
    order: Order,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(sizes.spacingMedium)
        ) {
            // Table and Covers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = order.tableName ?: "Sin Mesa",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                order.covers?.let { covers ->
                    Text(
                        text = "$covers ${if (covers == 1) "Persona" else "Personas"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(sizes.spacingSmall))

            // Server Name
            order.servedBy?.let { server ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(sizes.spacingSmall))
                    Text(
                        text = "Mesero: ${server.firstName} ${server.lastName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(sizes.spacingSmall))

            // Order Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (order.kitchenStatus) {
                    KitchenStatus.PENDING -> Color(0xFFFFC107) // Yellow
                    KitchenStatus.PREPARING -> Color(0xFF2196F3) // Blue
                    KitchenStatus.READY -> Color(0xFF4CAF50) // Green
                    KitchenStatus.SERVED -> Color(0xFF9E9E9E) // Gray
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(sizes.spacingSmall))
                Text(
                    text = "Estado: ${order.kitchenStatus.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}
```

**Visual Specs:**
- Card background: surfaceVariant
- Padding: spacingMedium (16-32dp)
- Title: titleMedium (16sp), bold
- Body text: bodySmall (12sp), 80% opacity
- Status dot: 8dp circle
- Icon size: 16dp
- Spacing: spacingSmall (8-16dp)

---

### 5. OrderItemCard

**File:** `components/OrderItemCard.kt`

**Structure:**
```kotlin
@Composable
fun OrderItemCard(
    item: OrderItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Quantity + Product Name + Notes
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Quantity + Name
                Text(
                    text = "${item.quantity}x ${item.productName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Unit Price
                Text(
                    text = "$${item.unitPrice} c/u",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Notes (if any)
                item.notes?.let { notes ->
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(sizes.spacingMedium))

            // Center: Item Total
            Text(
                text = "$${item.total}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(sizes.spacingMedium))

            // Right: Remove Button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Eliminar",
                    tint = Color(0xFFEB5757) // Error red
                )
            }
        }
    }
}
```

**Visual Specs:**
- Card border: 1dp, outline 20% opacity
- Padding: spacingMedium (16-32dp)
- Product name: bodyMedium (14sp), semibold
- Unit price: bodySmall (12sp), 60% opacity
- Notes: bodySmall (12sp), italic, 70% opacity
- Total: bodyLarge (16sp), bold, primary color
- Delete button: 32dp, error red (#EB5757)
- Touch ripple: Enabled for entire card

---

### 6. OrderTotalsCard

**File:** `components/OrderTotalsCard.kt`

**Structure:**
```kotlin
@Composable
fun OrderTotalsCard(
    subtotal: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(sizes.spacingMedium)
        ) {
            // Subtotal
            TotalRow(
                label = "Subtotal",
                amount = subtotal,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(sizes.spacingSmall))

            // Tax
            TotalRow(
                label = "IVA (16%)",
                amount = tax,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(sizes.spacingSmall))

            // Divider
            HorizontalDivider(
                modifier = Modifier.padding(vertical = sizes.spacingSmall),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )

            // Total
            TotalRow(
                label = "TOTAL",
                amount = total,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    amount: BigDecimal,
    style: TextStyle,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = style,
            fontWeight = fontWeight,
            color = color
        )
        Text(
            text = "$${amount}",
            style = style,
            fontWeight = fontWeight,
            color = color
        )
    }
}
```

**Visual Specs:**
- Card background: surfaceVariant
- Padding: spacingMedium (16-32dp)
- Subtotal/Tax: bodyMedium (14sp)
- Total: titleLarge (22sp), bold, primary color
- Divider: 1dp, outline 30% opacity
- Row spacing: spacingSmall (8-16dp)

---

### 7. ProductSelectionModal

**File:** `components/ProductSelectionModal.kt`

**Structure:**
```kotlin
@Composable
fun ProductSelectionModal(
    product: Product,
    initialQuantity: Int = 1,
    initialNotes: String = "",
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, notes: String) -> Unit
) {
    val sizes = LocalResponsiveSizes.current
    var quantity by remember { mutableStateOf(initialQuantity) }
    var notes by remember { mutableStateOf(initialNotes) }

    // CRITICAL: Initialize keyboard controllers INSIDE AlertDialog scope
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Close keyboard when tapping outside text field
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Product Emoji
                Text(
                    text = product.emoji,
                    fontSize = 64.sp,
                    modifier = Modifier.padding(vertical = sizes.spacingMedium)
                )

                // Quantity Selector
                Text(
                    text = "Cantidad",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = sizes.spacingSmall)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
                ) {
                    // Decrease Button
                    IconButton(
                        onClick = { if (quantity > 1) quantity-- },
                        enabled = quantity > 1
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Disminuir")
                    }

                    // Quantity Display
                    Text(
                        text = quantity.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 48.dp),
                        textAlign = TextAlign.Center
                    )

                    // Increase Button
                    IconButton(
                        onClick = { quantity++ }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Aumentar")
                    }
                }

                Spacer(modifier = Modifier.height(sizes.spacingLarge))

                // Special Instructions
                Text(
                    text = "Instrucciones Especiales",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = sizes.spacingSmall)
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Prevent tap from propagating to parent Column
                            detectTapGestures { /* consume tap */ }
                        },
                    placeholder = { Text("Ej: Sin cebolla, término medio") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    )
                )
            }
        },
        confirmButton = {
            val totalPrice = product.price.multiply(BigDecimal(quantity))
            Button(
                onClick = {
                    onConfirm(quantity, notes)
                    onDismiss()
                }
            ) {
                Text("Agregar $${totalPrice}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
```

**Visual Specs:**
- Modal width: 60% of screen (centered)
- Emoji size: 64sp
- Quantity display: headlineMedium (32sp), bold
- Buttons: 48dp height
- Text field: Single line, Done action
- Spacing: ResponsiveScaffold tokens
- Keyboard: Auto-dismiss on Done or tap outside

---

## 🎬 User Flow Examples

### Flow 1: Add Item to Order

```
1. User navigates from TableServiceScreen → OrderDetailScreen
   ↓
2. OrderDetailScreen loads order via getOrder()
   - Shows AvoqadoLoadingOverlay("Cargando orden...")
   ↓
3. Order loaded → Shows split-screen UI
   - Left: Product grid (15 mock products)
   - Right: Order summary (existing items)
   ↓
4. User taps "Pizza Margherita" product card
   ↓
5. ProductSelectionModal appears (centered)
   - Product emoji: 🍕
   - Quantity: [−] [1] [+]
   - Notes: Empty text field
   - Confirm: "Agregar $180.00"
   ↓
6. User adjusts quantity to 2
   - Confirm button updates: "Agregar $360.00"
   ↓
7. User adds note: "Sin aceitunas"
   ↓
8. User taps "Agregar $360.00"
   ↓
9. Modal closes → ViewModel.addItems() called
   - Shows AvoqadoLoadingOverlay("Agregando items...")
   ↓
10. Backend PATCH /orders/{orderId}/items
    - Request: { items: [{ productId, quantity: 2, notes }], version: 3 }
    - Response: Updated order with version: 4
    ↓
11. Order refreshed → New item appears in right panel
    - "2x Pizza Margherita $360.00 [X]"
    - Subtotal/Total updated
    ↓
12. Socket.IO emits ORDER_UPDATED event
    ↓
13. Other terminals receive event → Auto-refresh order
```

### Flow 2: Remove Item from Order

```
1. User viewing order with 3 items
   ↓
2. User taps [X] button on "1x Coca-Cola $35.00"
   ↓
3. Item removed immediately (optimistic update)
   - Item disappears from list
   - Totals updated
   - Snackbar appears: "Coca-Cola eliminada. [DESHACER]"
   ↓
4. Backend DELETE /orders/{orderId}/items/{itemId}
   ↓
5. If success: No further action
   If error: Revert item back + show error message
   ↓
6. Snackbar auto-dismisses after 3 seconds
```

### Flow 3: Multi-Terminal Sync

```
Terminal A (Server Maria)         Terminal B (Server Juan)
─────────────────────             ─────────────────────
Viewing Orden #123                Viewing Orden #123
Items: Pizza x1                   Items: Pizza x1

User adds "Coca x2"
↓
ViewModel.addItems()
↓
Backend PATCH /orders/123/items
↓
Socket.IO emits ORDER_UPDATED
                                  ← Socket event received
                                  ← ViewModel.refreshOrder()
                                  ← Order updated

Items: Pizza x1, Coca x2          Items: Pizza x1, Coca x2 ✅
                                  Toast: "Orden actualizada por Maria"
```

---

## 🎨 Color Palette

```kotlin
// Dark Theme (Avoqado OKLCH)
val background = Color(0xFF1C1C1C)     // Deep charcoal
val foreground = Color(0xFFFAFAFA)     // Soft white
val surface = Color(0xFF2A2A2A)        // Cards & elevated
val surfaceVariant = Color(0xFF333333) // Header cards
val primary = Color(0xFFE8E8E8)        // Accents & buttons
val error = Color(0xFFEB5757)          // Delete button

// Functional Colors
val green = Color(0xFF4CAF50)          // Available/Success
val yellow = Color(0xFFFFC107)         // Reserved/Pending
val blue = Color(0xFF2196F3)           // Preparing
val gray = Color(0xFF9E9E9E)           // Served/Disabled
```

---

## 📏 Spacing System

```kotlin
// From ResponsiveSizes.kt
spacingSmall:  8dp / 12dp / 16dp   (small / medium / large)
spacingMedium: 16dp / 24dp / 32dp
spacingLarge:  24dp / 32dp / 48dp
paddingScreen: 16dp / 20dp / 24dp
```

---

## ⚡ Performance Optimizations

### 1. Product Grid Lazy Loading

```kotlin
LazyVerticalGrid(
    columns = GridCells.Fixed(columns),
    // ✅ CRITICAL: Use keys for efficient recomposition
    key = { product -> product.id }
) {
    items(products, key = { it.id }) { product ->
        ProductCard(product, onClick)
    }
}
```

### 2. Order Items Lazy Loading

```kotlin
LazyColumn(
    key = { item -> item.id }
) {
    items(order.items, key = { it.id }) { item ->
        OrderItemCard(item, onClick, onRemove)
    }
}
```

### 3. Image Loading (Future)

```kotlin
// When switching from emojis to real images
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(product.imageUrl)
        .crossfade(true)
        .memoryCacheKey(product.id)
        .diskCacheKey(product.id)
        .build(),
    contentDescription = product.name,
    modifier = Modifier.size(64.dp)
)
```

---

## 🧪 Preview Examples

```kotlin
@Preview(showBackground = true, device = "spec:width=1024dp,height=600dp")
@Composable
private fun OrderDetailScreenPreview_PAX_A80() {
    AvoqadoTheme {
        OrderDetailScreen(
            orderId = "ord_preview",
            onNavigateBack = {},
            onNavigateToPayment = { _, _ -> }
        )
    }
}

@Preview(showBackground = true, device = "spec:width=1280dp,height=720dp")
@Composable
private fun OrderDetailScreenPreview_PAX_A920() {
    AvoqadoTheme {
        OrderDetailScreen(
            orderId = "ord_preview",
            onNavigateBack = {},
            onNavigateToPayment = { _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProductCardPreview() {
    AvoqadoTheme {
        ProductCard(
            product = Product(
                id = "prod_1",
                name = "Pizza Margherita",
                sku = "COM-001",
                price = BigDecimal("180.00"),
                categoryId = "cat_comidas",
                categoryName = "Comidas",
                description = null,
                emoji = "🍕",
                available = true
            ),
            onClick = {}
        )
    }
}
```

---

## ✅ Implementation Checklist

### Week 1: Core Layout
- [ ] Create OrderDetailScreen.kt with split-screen layout
- [ ] Implement ProductSelectionPanel with category tabs
- [ ] Implement ProductCard with responsive sizing
- [ ] Implement OrderSummaryPanel with header + items + totals
- [ ] Test responsive behavior on PAX A80, A920, Sunmi T2s

### Week 2: Product Selection
- [ ] Create ProductSelectionModal with quantity selector
- [ ] Add special instructions text field
- [ ] Implement keyboard dismissal (Dialog scope fix)
- [ ] Add product filtering by category
- [ ] Test modal on all device sizes

### Week 3: Order Management
- [ ] Implement OrderItemCard with edit/remove
- [ ] Add optimistic updates for add/remove
- [ ] Implement Undo snackbar for item removal
- [ ] Add loading states for all async operations
- [ ] Test multi-item scenarios

### Week 4: Real-Time Sync
- [ ] Add Socket.IO events to SocketEvent.kt
- [ ] Implement event listeners in ViewModel
- [ ] Test multi-terminal sync with 2 devices
- [ ] Add sync status indicator in top bar
- [ ] Test conflict resolution (version mismatch)

### Week 5: Polish
- [ ] Add empty state for orders without items
- [ ] Implement error handling with user-friendly messages
- [ ] Add all previews for components
- [ ] Performance testing (recomposition counts)
- [ ] Accessibility testing (TalkBack)

---

**Last Updated:** 2025-01-15
**Status:** ✅ Ready for Domain Model Design
**Next Step:** Design domain models based on UI requirements
