package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * OrderCard - Card for displaying order summary in list
 *
 * Shows:
 * - Order number + time elapsed
 * - Table/Order type (icon + text)
 * - Item count
 * - Total amount
 * - Status badge (color-coded)
 *
 * Used in OrderListScreen
 *
 * @param order Order to display
 * @param onClick Callback when card is tapped
 * @param modifier Modifier for customization
 */
@Composable
fun OrderCard(
    order: Order,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when (order.status) {
        OrderStatus.DRAFT -> MaterialTheme.colorScheme.outline
        OrderStatus.OPEN -> MaterialTheme.colorScheme.primary
        OrderStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
        OrderStatus.READY -> MaterialTheme.colorScheme.secondary
        OrderStatus.SERVED -> MaterialTheme.colorScheme.tertiaryContainer
        OrderStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant
        OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error
    }

    val statusText = when (order.status) {
        OrderStatus.DRAFT -> "Borrador"
        OrderStatus.OPEN -> "Abierta"
        OrderStatus.IN_PROGRESS -> "En Cocina"
        OrderStatus.READY -> "Lista"
        OrderStatus.SERVED -> "Servida"
        OrderStatus.COMPLETED -> "Completada"
        OrderStatus.CANCELLED -> "Cancelada"
    }

    // Calculate elapsed time
    val elapsedMinutes = Duration.between(order.createdAt, Instant.now()).toMinutes()
    val timeText = when {
        elapsedMinutes < 1 -> "Recién creada"
        elapsedMinutes < 60 -> "${elapsedMinutes}m"
        else -> "${elapsedMinutes / 60}h ${elapsedMinutes % 60}m"
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Order number + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = order.orderNumber,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• $timeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status badge
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body: Table/Type + Items + Total
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Table/Type icon + text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (order.orderType == OrderType.DINE_IN) {
                            Icons.Default.RestaurantMenu
                        } else {
                            Icons.Default.ShoppingBag
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = order.tableName ?: "Para llevar",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${order.itemCount} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Right: Total
                Text(
                    text = "$${order.total}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun OrderCardOpenPreview() {
    AvoqadoTheme {
        OrderCard(
            order = Order(
                id = "order_1",
                orderNumber = "ORD-001234",
                venueId = "venue_1",
                tableId = "table_5",
                tableName = "Mesa 5",
                covers = 2,
                waiterId = "waiter_1",
                waiterName = "Juan",
                status = OrderStatus.OPEN,
                kitchenStatus = KitchenStatus.PENDING,
                paymentStatus = PaymentStatus.PENDING,
                orderType = OrderType.DINE_IN,
                items = emptyList(),
                subtotal = BigDecimal("150.00"),
                tax = BigDecimal("24.00"),
                total = BigDecimal("174.00"),
                notes = null,
                createdAt = Instant.now().minusSeconds(300), // 5 minutes ago
                updatedAt = Instant.now(),
                version = 1
            ),
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderCardInProgressPreview() {
    AvoqadoTheme {
        OrderCard(
            order = Order(
                id = "order_2",
                orderNumber = "ORD-001235",
                venueId = "venue_1",
                tableId = "table_3",
                tableName = "Mesa 3",
                covers = 4,
                waiterId = "waiter_1",
                waiterName = "Juan",
                status = OrderStatus.IN_PROGRESS,
                kitchenStatus = KitchenStatus.PREPARING,
                paymentStatus = PaymentStatus.PENDING,
                orderType = OrderType.DINE_IN,
                items = emptyList(),
                subtotal = BigDecimal("320.50"),
                tax = BigDecimal("51.28"),
                total = BigDecimal("371.78"),
                notes = null,
                createdAt = Instant.now().minusSeconds(900), // 15 minutes ago
                updatedAt = Instant.now(),
                version = 1
            ),
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderCardTakeoutPreview() {
    AvoqadoTheme {
        OrderCard(
            order = Order(
                id = "order_quick_3",
                orderNumber = "ORD-001236",
                venueId = "venue_1",
                tableId = null,
                tableName = null,
                covers = 1,
                waiterId = "waiter_1",
                waiterName = "Juan",
                status = OrderStatus.OPEN,
                kitchenStatus = KitchenStatus.PENDING,
                paymentStatus = PaymentStatus.PENDING,
                orderType = OrderType.TAKEOUT,
                items = emptyList(),
                subtotal = BigDecimal("85.00"),
                tax = BigDecimal("13.60"),
                total = BigDecimal("98.60"),
                notes = null,
                createdAt = Instant.now().minusSeconds(60), // 1 minute ago
                updatedAt = Instant.now(),
                version = 1
            ),
            onClick = {}
        )
    }
}
