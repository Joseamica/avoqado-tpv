package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * OrderListViewModel - Manages order list state and filtering
 *
 * **Architecture:**
 * - Fetches orders from backend (GET /tpv/venues/{venueId}/orders)
 * - Merges with local-only orders (not yet synced Quick Orders)
 * - Provides filtered list to UI based on status filter
 *
 * **Data Sources:**
 * 1. Backend: Orders with paymentStatus IN ['PENDING', 'PARTIAL']
 * 2. Local: Orders in Room DB not yet synced to backend
 *
 * State management: StateFlow for reactive UI updates
 */
@HiltViewModel
class OrderListViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val orderSyncCoordinator: OrderSyncCoordinator,
    private val deviceInfoManager: DeviceInfoManager
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
     * Load all orders for venue from backend + local orders merge.
     *
     * Strategy:
     * 1. Fetch orders from backend (returns PENDING/PARTIAL payment status)
     * 2. Get local-only orders (Quick Orders not yet synced)
     * 3. Merge and deduplicate by ID
     * 4. Sort by createdAt DESC (most recent first)
     *
     * Offline Fallback:
     * - If backend fails but local orders exist → show local orders
     * - If both fail → show error
     */
    private fun loadOrders() {
        viewModelScope.launch {
            _state.value = OrderListState.Loading

            val venueId = deviceInfoManager.getVenueId()
            if (venueId == null) {
                Timber.e("❌ [OrderList] Venue not configured")
                _state.value = OrderListState.Error("Venue no configurado.\n\nPor favor inicia sesión nuevamente.")
                return@launch
            }

            Timber.d("📋 [OrderList] Loading orders for venue=$venueId")

            // 1. Fetch from backend
            val backendResult = orderRepository.getOrders(venueId, null)

            // 2. Get local-only orders (Quick Orders not yet synced to backend)
            val localOrders = orderSyncCoordinator.getLocalOnlyOrders(venueId)
            Timber.d("📋 [OrderList] Local-only orders: ${localOrders.size}")

            backendResult.fold(
                onSuccess = { backendOrders ->
                    Timber.i("✅ [OrderList] Backend returned ${backendOrders.size} orders")

                    // Merge: backend + local (avoiding duplicates)
                    val allOrders = (backendOrders + localOrders)
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt }

                    Timber.i("✅ [OrderList] Total orders after merge: ${allOrders.size}")
                    _allOrders.value = allOrders
                },
                onFailure = { error ->
                    Timber.e(error, "❌ [OrderList] Backend fetch failed")

                    // Offline fallback: show local orders only
                    if (localOrders.isNotEmpty()) {
                        Timber.w("⚠️ [OrderList] Offline mode - showing ${localOrders.size} local orders")
                        _allOrders.value = localOrders.sortedByDescending { it.createdAt }
                    } else {
                        _state.value = OrderListState.Error(
                            "No se pudieron cargar las órdenes.\n\n${error.message ?: "Error de conexión"}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Observe filter changes and update filtered list.
     *
     * Combines _allOrders with _selectedFilter to produce filtered results.
     * UI automatically updates when either changes.
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
     * Select filter to apply to order list.
     *
     * @param filter OrderStatusFilter (ALL, OPEN, IN_PROGRESS, COMPLETED)
     */
    fun selectFilter(filter: OrderStatusFilter) {
        _selectedFilter.value = filter
        Timber.d("🔍 [OrderList] Filter changed to: ${filter.label}")
    }

    /**
     * Refresh orders from backend.
     *
     * Called when:
     * - User pulls to refresh
     * - Order is created/updated elsewhere
     * - App comes back to foreground
     */
    fun refreshOrders() {
        Timber.d("🔄 [OrderList] Refreshing orders...")
        loadOrders()
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
