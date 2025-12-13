package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * ViewModel for OrderingWelcomeScreen
 *
 * Responsibilities:
 * - Fetch all open orders from repository
 * - Count unpaid TAKEOUT orders (PENDING or PARTIAL payment status)
 * - Expose count to UI for warning banner
 *
 * **Pattern:**
 * - Uses StateFlow for reactive UI updates
 * - Automatically fetches orders when screen is opened
 * - Logs count for debugging (ADB monitoring)
 *
 * **Business Logic:**
 * Unpaid TAKEOUT order criteria:
 * - orderType == TAKEOUT
 * - paymentStatus in [PENDING, PARTIAL]
 * - remainingBalance > 0
 */
@HiltViewModel
class OrderingWelcomeViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val orderSyncCoordinator: OrderSyncCoordinator,
    private val deviceInfoManager: DeviceInfoManager
) : ViewModel() {

    // State
    private val _unpaidTakeoutCount = MutableStateFlow(0)
    val unpaidTakeoutCount: StateFlow<Int> = _unpaidTakeoutCount.asStateFlow()

    init {
        loadOrders()
    }

    /**
     * Load all open orders and count unpaid TAKEOUT orders
     *
     * Strategy (same as OrderListViewModel):
     * 1. Fetch orders from backend (returns PENDING/PARTIAL payment status)
     * 2. Get local-only orders (Quick Orders not yet synced)
     * 3. Merge and deduplicate by ID
     * 4. Count unpaid TAKEOUT orders
     *
     * Offline Fallback:
     * - If backend fails but local orders exist → count local orders
     * - If both fail → show count 0
     */
    fun loadOrders() {
        viewModelScope.launch {
            try {
                val venueId = deviceInfoManager.getVenueId()
                if (venueId == null) {
                    Timber.e("🔔 [OrderingWelcome] Venue not configured")
                    _unpaidTakeoutCount.value = 0
                    return@launch
                }

                Timber.d("🔔 [OrderingWelcome] Loading orders for venue=$venueId")

                // 🚀 PARALLEL FETCH: Backend + Local (same pattern as OrderListViewModel)
                val backendDeferred = async { orderRepository.getOrders(venueId, null) }
                val localDeferred = async { orderSyncCoordinator.getLocalOnlyOrders(venueId) }

                val backendResult = backendDeferred.await()
                val localOrders = localDeferred.await()
                Timber.d("🔔 [OrderingWelcome] Local-only orders: ${localOrders.size}")

                backendResult.fold(
                    onSuccess = { backendOrders ->
                        Timber.i("✅ [OrderingWelcome] Backend returned ${backendOrders.size} orders")

                        // Merge: backend + local (avoiding duplicates)
                        val allOrders = (backendOrders + localOrders)
                            .distinctBy { it.id }

                        Timber.i("✅ [OrderingWelcome] Total orders after merge: ${allOrders.size}")

                        // Count unpaid TAKEOUT orders
                        val count = allOrders.count { order ->
                            order.orderType == OrderType.TAKEOUT &&
                            order.paymentStatus in listOf(PaymentStatus.PENDING, PaymentStatus.PARTIAL) &&
                            order.remainingBalance > BigDecimal.ZERO
                        }

                        _unpaidTakeoutCount.value = count

                        Timber.d("🔔 [OrderingWelcome] Unpaid TAKEOUT orders: $count")

                        if (count > 0) {
                            Timber.w("⚠️ [OrderingWelcome] Warning: $count unpaid TAKEOUT orders detected")
                            // HIGH #1 FIX: Use consistent filter criteria for logging
                            allOrders.filter {
                                it.orderType == OrderType.TAKEOUT &&
                                it.paymentStatus in listOf(PaymentStatus.PENDING, PaymentStatus.PARTIAL) &&
                                it.remainingBalance > BigDecimal.ZERO
                            }.forEach { order ->
                                Timber.d("   - Order #${order.orderNumber} | Balance: $${order.remainingBalance}")
                            }
                        }
                    },
                    onFailure = { error ->
                        Timber.e(error, "🔔 [OrderingWelcome] Backend error, using local orders only")

                        // Offline fallback: count local orders
                        val count = localOrders.count { order ->
                            order.orderType == OrderType.TAKEOUT &&
                            order.paymentStatus in listOf(PaymentStatus.PENDING, PaymentStatus.PARTIAL) &&
                            order.remainingBalance > BigDecimal.ZERO
                        }

                        _unpaidTakeoutCount.value = count
                        Timber.d("🔔 [OrderingWelcome] Offline mode: $count unpaid TAKEOUT orders from local DB")
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "🔔 [OrderingWelcome] Unexpected error loading orders")
                _unpaidTakeoutCount.value = 0
            }
        }
    }

    /**
     * Refresh orders (called when returning from OrderListScreen)
     */
    fun refresh() {
        loadOrders()
    }
}
