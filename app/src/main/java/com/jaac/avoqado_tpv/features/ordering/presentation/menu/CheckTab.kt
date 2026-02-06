package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.ordering.domain.*
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@Composable
fun CheckTab(
    order: Order,
    onItemQuantityChange: (OrderItem, Int) -> Unit,
    onItemRemove: (OrderItem) -> Unit,
    onSendToKitchen: () -> Unit,
    onProcessPayment: () -> Unit,
    onSplitPayment: () -> Unit,
    onPrintComanda: () -> Unit,
    onPrintItem: (OrderItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    val displayItems = remember(order.items) { order.items.distinctBy { it.id } }
    val calculatedTotal = displayItems.sumOf { it.totalPrice }
    val lineCount = displayItems.size
    val unitCount = displayItems.sumOf { it.quantity }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ---------------------------------------------------------
        // 1. SCROLLABLE CONTENT (List)
        // ---------------------------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (displayItems.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp) // Hairline separator style
                ) {
                    // Header Summary (Inside scroll to maximize space)
                    item {
                        OrderSummaryHeader(
                            lineCount = lineCount,
                            unitCount = unitCount,
                            total = calculatedTotal,
                            onPrint = onPrintComanda,
                            hasItems = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }

                    items(
                        items = displayItems,
                        key = { it.id }
                    ) { item ->
                        SwipeableOrderItem(
                            item = item,
                            hasOrderDiscount = order.discountAmount > BigDecimal.ZERO,
                            discountRate = if (order.subtotal > BigDecimal.ZERO) order.discountAmount.divide(order.subtotal, 4, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO,
                            onQuantityChange = { newQty -> onItemQuantityChange(item, newQty) },
                            onRemove = { onItemRemove(item) },
                            onPrintItem = { onPrintItem(item) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp), // Inset divider
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------
        // 2. STICKY FOOTER (Totals + Actions) - Compact for PAX A910S
        // ---------------------------------------------------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 8.dp, // Reduced elevation
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp) // Tighter padding
            ) {
                // Partial Payment Warning
                if (order.hasRemainingBalance && order.paidAmount > BigDecimal.ZERO) {
                    PartialPaymentBanner(
                        paid = order.paidAmount,
                        remaining = order.remainingBalance,
                        formatter = currencyFormatter
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Totals - Compact Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Left side: Subtotal/Discount info (small)
                    Column {
                        if (order.discountAmount > BigDecimal.ZERO) {
                            Text(
                                text = "Desc: -${currencyFormatter.format(order.discountAmount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = if (order.hasRemainingBalance) "POR PAGAR" else "TOTAL",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    // Right side: Amount (Large)
                    Text(
                        text = currencyFormatter.format(if (order.hasRemainingBalance) order.remainingBalance else order.total),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons Row - Compact height
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Kitchen Button (DINE_IN only)
                    if (order.orderType == OrderType.DINE_IN) {
                        ActionButton(
                            text = "Cocina",
                            icon = Icons.Outlined.Timer,
                            onClick = onSendToKitchen,
                            enabled = order.canSendToKitchen,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                            compact = true
                        )
                    }

                    // Split Button
                    OutlinedButton(
                        onClick = onSplitPayment,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp), // Compact height
                        shape = RoundedCornerShape(8.dp),
                        enabled = order.items.isNotEmpty() && order.canProcessPayment,
                        contentPadding = PaddingValues(0.dp) // Reduce padding
                    ) {
                        Text("Dividir", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }

                    // Pay Button (Primary)
                    Button(
                        onClick = onProcessPayment,
                        modifier = Modifier
                            .weight(1.5f)
                            .height(42.dp), // Compact height
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = order.canProcessPayment
                    ) {
                        Text("Pagar", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------
// COMPONENT: SWIPEABLE ITEM (Compact)
// ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableOrderItem(
    item: OrderItem,
    hasOrderDiscount: Boolean,
    discountRate: BigDecimal,
    onQuantityChange: (Int) -> Unit,
    onRemove: () -> Unit,
    onPrintItem: () -> Unit
) {
    // Only allow swipe to dismiss if item NOT sent to kitchen yet
    val canDelete = !item.wasSentToKitchen
    
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canDelete, // Lock swipe if sent
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 
                    MaterialTheme.colorScheme.error 
                else MaterialTheme.colorScheme.surfaceVariant, 
                label = "color"
            )
            
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }
    ) {
        // Main Item Content
        Surface(
            color = MaterialTheme.colorScheme.surface,
            onClick = {} // Capture clicks
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 12.dp), // Slightly more vertical padding for centering
                verticalAlignment = Alignment.CenterVertically // Center align everything vertically
            ) {
                // 1. Quantity Stepper (Horizontal & Compact)
                HorizontalQuantityStepper(
                    quantity = item.quantity,
                    onIncrease = { onQuantityChange(item.quantity + 1) },
                    onDecrease = {
                        if (item.quantity > 1) onQuantityChange(item.quantity - 1)
                    },
                    isLocked = item.wasSentToKitchen
                )

                Spacer(modifier = Modifier.width(12.dp))

                // 2. Details
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.productName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            lineHeight = 20.sp // Tighter line height
                        )
                        // Simple Icon Status (No text)
                        KitchenStatusIcon(item.wasSentToKitchen, onPrintItem)
                    }

                    // Modifiers & Notes
                    if (item.modifiers.isNotEmpty() || !item.notes.isNullOrBlank()) {
                        item.modifiers.take(3).forEach { modifier ->
                            Text(
                                text = "• ${modifier.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (item.modifiers.size > 3) {
                            Text(
                                text = "+${item.modifiers.size - 3} más...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 3. Price (with optional strikethrough for discount)
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (hasOrderDiscount) {
                        // Calculate visual discounted price (approximate)
                        val discountAmount = item.totalPrice.multiply(discountRate)
                        val discountedPrice = item.totalPrice.subtract(discountAmount)
                        
                        Text(
                            text = NumberFormat.getCurrencyInstance(Locale("es", "MX")).format(item.totalPrice),
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = TextDecoration.LineThrough
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = NumberFormat.getCurrencyInstance(Locale("es", "MX")).format(discountedPrice),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = NumberFormat.getCurrencyInstance(Locale("es", "MX")).format(item.totalPrice),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------
// HELPER COMPONENTS
// ---------------------------------------------------------

@Composable
private fun HorizontalQuantityStepper(
    quantity: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    isLocked: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                shape = RoundedCornerShape(50) // Pill shape
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(50)
            )
            .height(32.dp) // Fixed compact height
    ) {
        if (!isLocked) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onDecrease() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "-",
                    modifier = Modifier.size(16.dp),
                    tint = if (quantity > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        
        Text(
            text = quantity.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        if (!isLocked) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onIncrease() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "+",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun KitchenStatusIcon(isSent: Boolean, onPrint: () -> Unit) {
    // Icon-only indicator to save space
    Icon(
        imageVector = if (isSent) Icons.Default.CheckCircle else Icons.Default.Print,
        contentDescription = if (isSent) "Enviado" else "Pendiente",
        modifier = Modifier
            .size(16.dp)
            .clickable { onPrint() },
        tint = if (isSent) MaterialTheme.avoqadoColors.statusSuccess else MaterialTheme.avoqadoColors.offlineOrange
    )
}

@Composable
private fun OrderSummaryHeader(lineCount: Int, unitCount: Int, total: BigDecimal, onPrint: () -> Unit, hasItems: Boolean) {
    val formatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Orden ($lineCount líneas · $unitCount unidades)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (hasItems) {
            TextButton(
                onClick = onPrint,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Imprimir", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: BigDecimal, isDiscount: Boolean = false, formatter: NumberFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isDiscount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = (if (isDiscount) "- " else "") + formatter.format(amount),
            style = MaterialTheme.typography.bodySmall,
            color = if (isDiscount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LargeTotalRow(label: String, amount: BigDecimal, formatter: NumberFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = formatter.format(amount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PartialPaymentBanner(paid: BigDecimal, remaining: BigDecimal, formatter: NumberFormat) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Pagado: ${formatter.format(paid)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                "Resta: ${formatter.format(remaining)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (compact) 42.dp else 56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = textColor
        ),
        contentPadding = PaddingValues(0.dp) // Reduce padding
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add, // Placeholder icon
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .padding(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ordering_empty_state_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.ordering_empty_state_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CheckTabPreview() {
    AvoqadoTheme {
        val mockOrder = Order(
            id = "order_123",
            orderNumber = "ORD-001",
            venueId = "venue_1",
            tableId = "table_1",
            tableName = "Mesa 5",
            covers = 2,
            waiterId = "waiter_1",
            waiterName = "Juan Pérez",
            customerName = null,
            customerPhone = null,
            specialRequests = null,
            status = OrderStatus.OPEN,
            kitchenStatus = KitchenStatus.PENDING,
            paymentStatus = PaymentStatus.PENDING,
            orderType = OrderType.DINE_IN,
            items = listOf(
                OrderItem(
                    id = "item_1",
                    orderId = "order_123",
                    productId = "prod_1",
                    productName = "Hamburguesa de Pollo",
                    productSku = "HAM-001",
                    quantity = 1,
                    unitPrice = java.math.BigDecimal("139.00"),
                    totalPrice = java.math.BigDecimal("139.00"),
                    modifiers = listOf(
                        ProductModifier(
                            id = "mod_1",
                            name = "Tocino",
                            priceAdjustment = java.math.BigDecimal("0.00"),
                            type = com.jaac.avoqado_tpv.features.ordering.domain.ModifierType.MULTIPLE_CHOICE,
                            required = false,
                            displayOrder = 0
                        )
                    ),
                    notes = null,
                    kitchenStatus = KitchenStatus.PENDING,
                    createdAt = java.time.Instant.now(),
                    sentToKitchenAt = null
                ),
                OrderItem(
                    id = "item_2",
                    orderId = "order_123",
                    productId = "prod_2",
                    productName = "Coca-Cola 600ml (enviado)",
                    productSku = "REF-001",
                    quantity = 2,
                    unitPrice = java.math.BigDecimal("30.00"),
                    totalPrice = java.math.BigDecimal("60.00"),
                    modifiers = emptyList(),
                    notes = "Sin hielo",
                    kitchenStatus = KitchenStatus.PREPARING,
                    createdAt = java.time.Instant.now(),
                    sentToKitchenAt = java.time.Instant.now()
                ),
                OrderItem(
                    id = "item_3",
                    orderId = "order_123",
                    productId = "prod_3",
                    productName = "Tacos al Pastor (3 pzas)",
                    productSku = "TAC-001",
                    quantity = 1,
                    unitPrice = java.math.BigDecimal("110.00"),
                    totalPrice = java.math.BigDecimal("110.00"),
                    modifiers = listOf(
                        ProductModifier(
                            id = "mod_2",
                            name = "Salsa Extra",
                            priceAdjustment = java.math.BigDecimal("5.00"),
                            type = com.jaac.avoqado_tpv.features.ordering.domain.ModifierType.MULTIPLE_CHOICE,
                            required = false,
                            displayOrder = 0
                        )
                    ),
                    notes = null,
                    kitchenStatus = KitchenStatus.PENDING,
                    createdAt = java.time.Instant.now(),
                    sentToKitchenAt = null
                )
            ),
            subtotal = java.math.BigDecimal("309.00"),
            discountAmount = java.math.BigDecimal("20.00"),
            tax = java.math.BigDecimal.ZERO,
            total = java.math.BigDecimal("289.00"),
            notes = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
            version = 1
        )

        CheckTab(
            order = mockOrder,
            onItemQuantityChange = { _: OrderItem, _: Int -> },
            onItemRemove = { _: OrderItem -> },
            onSendToKitchen = { },
            onProcessPayment = { },
            onSplitPayment = { },
            onPrintComanda = { },
            onPrintItem = { _: OrderItem -> }
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CheckTabEmptyPreview() {
    AvoqadoTheme {
        val mockOrder = Order(
            id = "order_empty",
            orderNumber = "ORD-002",
            venueId = "venue_1",
            tableId = "table_2",
            tableName = "Mesa 7",
            covers = 1,
            waiterId = "waiter_1",
            waiterName = "Juan Pérez",
            customerName = null,
            customerPhone = null,
            specialRequests = null,
            status = OrderStatus.OPEN,
            kitchenStatus = KitchenStatus.PENDING,
            paymentStatus = PaymentStatus.PENDING,
            orderType = OrderType.DINE_IN,
            items = emptyList(),
            subtotal = BigDecimal.ZERO,
            discountAmount = BigDecimal.ZERO,
            tax = BigDecimal.ZERO,
            total = BigDecimal.ZERO,
            notes = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
            version = 1
        )

        CheckTab(
            order = mockOrder,
            onItemQuantityChange = { _: OrderItem, _: Int -> },
            onItemRemove = { _: OrderItem -> },
            onSendToKitchen = { },
            onProcessPayment = { },
            onSplitPayment = { },
            onPrintComanda = { },
            onPrintItem = { _: OrderItem -> }
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CheckTabPartialPaymentPreview() {
    AvoqadoTheme {
        val mockOrder = Order(
            id = "order_partial",
            orderNumber = "ORD-003",
            venueId = "venue_1",
            tableId = null,
            tableName = null,
            covers = 1,
            waiterId = "waiter_1",
            waiterName = "Juan Pérez",
            customerName = null,
            customerPhone = null,
            specialRequests = null,
            status = OrderStatus.OPEN,
            kitchenStatus = KitchenStatus.PREPARING,
            paymentStatus = PaymentStatus.PARTIAL,
            orderType = OrderType.TAKEOUT,
            items = listOf(
                OrderItem(
                    id = "item_1",
                    orderId = "order_partial",
                    productId = "prod_1",
                    productName = "Taco",
                    productSku = "TACO-001",
                    quantity = 1,
                    unitPrice = BigDecimal("25.00"),
                    totalPrice = BigDecimal("25.00"),
                    modifiers = emptyList(),
                    notes = null,
                    kitchenStatus = KitchenStatus.PREPARING,
                    createdAt = java.time.Instant.now(),
                    sentToKitchenAt = java.time.Instant.now()
                )
            ),
            subtotal = BigDecimal("100.00"),
            discountAmount = BigDecimal.ZERO,
            tax = BigDecimal.ZERO,
            total = BigDecimal("100.00"),
            paidAmount = BigDecimal("50.00"), // Partially paid
            remainingBalance = BigDecimal("50.00"),
            notes = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
            version = 1
        )

        CheckTab(
            order = mockOrder,
            onItemQuantityChange = { _: OrderItem, _: Int -> },
            onItemRemove = { _: OrderItem -> },
            onSendToKitchen = { },
            onProcessPayment = { },
            onSplitPayment = { },
            onPrintComanda = { },
            onPrintItem = { _: OrderItem -> }
        )
    }
}
