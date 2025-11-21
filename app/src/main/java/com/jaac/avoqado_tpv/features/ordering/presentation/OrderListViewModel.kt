package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * OrderListViewModel - Manages order list state and filtering
 *
 * Responsibilities:
 * - Load all orders for venue
 * - Filter orders by status
 * - Handle real-time order updates (TODO: Socket.IO integration)
 * - Provide filtered list to UI
 *
 * State management: StateFlow for reactive UI updates
 */
@HiltViewModel
class OrderListViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val deviceInfoManager: DeviceInfoManager
    // TODO: Inject OrderRepository when backend is ready
) : ViewModel() {

    private val _allOrders = MutableStateFlow<List<Order>>(emptyList())
    private val _selectedFilter = MutableStateFlow(OrderStatusFilter.ALL)
    val selectedFilter: StateFlow<OrderStatusFilter> = _selectedFilter.asStateFlow()

    private val _state = MutableStateFlow<OrderListState>(OrderListState.Loading)
    val state: StateFlow<OrderListState> = _state.asStateFlow()

    init {
        loadOrders()
        observeFilterChanges()
    }

    /**
     * Load all orders for venue
     * TODO: Replace with OrderRepository.getOrders() when backend is ready
     */
    private fun loadOrders() {
        viewModelScope.launch {
            try {
                _state.value = OrderListState.Loading

                // Generate mock orders for testing
                val mockOrders = generateMockOrders()
                _allOrders.value = mockOrders

                Timber.d("📋 Loaded ${mockOrders.size} mock orders")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error loading orders")
                _state.value = OrderListState.Error("Error cargando órdenes: ${e.message}")
            }
        }
    }

    /**
     * Observe filter changes and update filtered list
     */
    private fun observeFilterChanges() {
        viewModelScope.launch {
            combine(_allOrders, _selectedFilter) { orders, filter ->
                orders.filter { filter.matches(it) }
            }.collect { filteredOrders ->
                _state.value = OrderListState.Success(filteredOrders)
            }
        }
    }

    /**
     * Select filter
     */
    fun selectFilter(filter: OrderStatusFilter) {
        _selectedFilter.value = filter
        Timber.d("🔍 Filter changed to: ${filter.label}")
    }

    /**
     * Refresh orders
     * TODO: Call OrderRepository.getOrders() when backend is ready
     */
    fun refreshOrders() {
        loadOrders()
    }

    // ============================================================================
    // Mock Data Generation
    // ============================================================================

    /**
     * Generate mock orders for testing
     * TODO: Remove when backend integration is complete
     */
    private fun generateMockOrders(): List<Order> {
        val venueId = deviceInfoManager.getVenueId() ?: "venue_unknown"
        val waiterId = secureStorage.getStaffId() ?: "waiter_unknown"
        val waiterName = secureStorage.getStaffName() ?: "Mesero"

        return listOf(
            // Open order - Table 5
            createMockOrder(
                id = "order_table5_${System.currentTimeMillis() - 300000}", // 5 min ago
                orderNumber = "ORD-001234",
                venueId = venueId,
                tableId = "table_5",
                tableName = "Mesa 5",
                waiterId = waiterId,
                waiterName = waiterName,
                status = OrderStatus.OPEN,
                kitchenStatus = KitchenStatus.PENDING,
                orderType = OrderType.DINE_IN,
                itemCount = 3,
                total = BigDecimal("174.00"),
                createdAt = Instant.now().minusSeconds(300)
            ),

            // In Progress - Table 3
            createMockOrder(
                id = "order_table3_${System.currentTimeMillis() - 900000}", // 15 min ago
                orderNumber = "ORD-001235",
                venueId = venueId,
                tableId = "table_3",
                tableName = "Mesa 3",
                waiterId = waiterId,
                waiterName = waiterName,
                status = OrderStatus.IN_PROGRESS,
                kitchenStatus = KitchenStatus.PREPARING,
                orderType = OrderType.DINE_IN,
                itemCount = 5,
                total = BigDecimal("371.78"),
                createdAt = Instant.now().minusSeconds(900)
            ),

            // Quick Order - TAKEOUT
            createMockOrder(
                id = "order_quick_${System.currentTimeMillis() - 60000}", // 1 min ago
                orderNumber = "ORD-001236",
                venueId = venueId,
                tableId = null,
                tableName = null,
                waiterId = waiterId,
                waiterName = waiterName,
                status = OrderStatus.OPEN,
                kitchenStatus = KitchenStatus.PENDING,
                orderType = OrderType.TAKEOUT,
                itemCount = 2,
                total = BigDecimal("98.60"),
                createdAt = Instant.now().minusSeconds(60)
            ),

            // In Progress - Table 7
            createMockOrder(
                id = "order_table7_${System.currentTimeMillis() - 1200000}", // 20 min ago
                orderNumber = "ORD-001237",
                venueId = venueId,
                tableId = "table_7",
                tableName = "Mesa 7",
                waiterId = waiterId,
                waiterName = waiterName,
                status = OrderStatus.IN_PROGRESS,
                kitchenStatus = KitchenStatus.READY,
                orderType = OrderType.DINE_IN,
                itemCount = 4,
                total = BigDecimal("256.40"),
                createdAt = Instant.now().minusSeconds(1200)
            ),

            // Completed - Table 2
            createMockOrder(
                id = "order_table2_${System.currentTimeMillis() - 3600000}", // 1 hour ago
                orderNumber = "ORD-001238",
                venueId = venueId,
                tableId = "table_2",
                tableName = "Mesa 2",
                waiterId = waiterId,
                waiterName = waiterName,
                status = OrderStatus.COMPLETED,
                kitchenStatus = KitchenStatus.SERVED,
                orderType = OrderType.DINE_IN,
                itemCount = 6,
                total = BigDecimal("512.80"),
                createdAt = Instant.now().minusSeconds(3600)
            ),

            // Open - Table 10
            createMockOrder(
                id = "order_table10_${System.currentTimeMillis() - 180000}", // 3 min ago
                orderNumber = "ORD-001239",
                venueId = venueId,
                tableId = "table_10",
                tableName = "Mesa 10",
                waiterId = waiterId,
                waiterName = waiterName,
                status = OrderStatus.OPEN,
                kitchenStatus = KitchenStatus.PENDING,
                orderType = OrderType.DINE_IN,
                itemCount = 2,
                total = BigDecimal("87.00"),
                createdAt = Instant.now().minusSeconds(180)
            )
        ).sortedByDescending { it.createdAt } // Most recent first
    }

    /**
     * Create mock order
     */
    private fun createMockOrder(
        id: String,
        orderNumber: String,
        venueId: String,
        tableId: String?,
        tableName: String?,
        waiterId: String,
        waiterName: String,
        status: OrderStatus,
        kitchenStatus: KitchenStatus,
        orderType: OrderType,
        itemCount: Int,
        total: BigDecimal,
        createdAt: Instant
    ): Order {
        // Create mock items to match itemCount
        val mockItems = (1..itemCount).map { index ->
            OrderItem(
                id = "item_${UUID.randomUUID()}",
                orderId = id,
                productId = "prod_$index",
                productName = "Producto $index",
                productSku = "SKU-$index",
                quantity = 1,
                unitPrice = total / BigDecimal(itemCount.toString()),
                totalPrice = total / BigDecimal(itemCount.toString()),
                modifiers = emptyList(),
                notes = null,
                kitchenStatus = kitchenStatus,
                createdAt = createdAt,
                sentToKitchenAt = if (status == OrderStatus.IN_PROGRESS) createdAt.plusSeconds(60) else null
            )
        }

        val subtotal = total  // ✅ FIX: Total = subtotal (no tax)
        val tax = BigDecimal.ZERO  // ✅ FIX: No tax (0%)

        return Order(
            id = id,
            orderNumber = orderNumber,
            venueId = venueId,
            tableId = tableId,
            tableName = tableName,
            covers = if (orderType == OrderType.TAKEOUT) 1 else 2,
            waiterId = waiterId,
            waiterName = waiterName,
            status = status,
            kitchenStatus = kitchenStatus,
            paymentStatus = if (status == OrderStatus.COMPLETED) PaymentStatus.PAID else PaymentStatus.PENDING,
            orderType = orderType,
            items = mockItems,
            subtotal = subtotal,
            tax = tax,
            total = total,
            notes = null,
            createdAt = createdAt,
            updatedAt = Instant.now(),
            version = 1
        )
    }
}

/**
 * Order list screen state
 */
sealed interface OrderListState {
    data object Loading : OrderListState
    data class Success(val orders: List<Order>) : OrderListState
    data class Error(val message: String) : OrderListState
}
