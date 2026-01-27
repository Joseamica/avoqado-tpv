package com.jaac.avoqado_tpv.features.payments.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Payments ViewModel
 *
 * Manages payment history screen state and business logic.
 * Fetches paginated payment list with filtering options.
 *
 * **Features:**
 * - Paginated payment list (20 items per page)
 * - Date range filter (last 7 days, 30 days, custom)
 * - Payment method filter (CASH, CARD, all)
 * - Pull-to-refresh support
 * - Load more pagination
 *
 * **Pattern**: Toast POS + Square Terminal
 * - Simple list with "Load More" button
 * - Date range filter for reconciliation
 * - Method filter for commission tracking
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun PaymentsScreen(viewModel: PaymentsViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *
 *     when (val currentState = state) {
 *         is PaymentsState.Loading -> AvoqadoLoadingOverlay()
 *         is PaymentsState.Success -> PaymentsContent(currentState.payments)
 *         is PaymentsState.Error -> ErrorMessage(currentState.message)
 *     }
 * }
 * ```
 */
@HiltViewModel
class PaymentsViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val secureStorage: SecureStorage,
    private val printerManager: PrinterManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<PaymentsState>(PaymentsState.Loading)
    val state: StateFlow<PaymentsState> = _state.asStateFlow()

    private val _filterDateRange = MutableStateFlow(DateRangeFilter.LAST_7_DAYS)
    val filterDateRange: StateFlow<DateRangeFilter> = _filterDateRange.asStateFlow()

    private val _filterPaymentMethod = MutableStateFlow<PaymentMethod?>(null)
    val filterPaymentMethod: StateFlow<PaymentMethod?> = _filterPaymentMethod.asStateFlow()

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    // PRINT MODE STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _isPrintMode = MutableStateFlow(false)
    val isPrintMode: StateFlow<Boolean> = _isPrintMode.asStateFlow()

    private val _selectedPaymentsForPrint = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaymentsForPrint: StateFlow<Set<String>> = _selectedPaymentsForPrint.asStateFlow()

    private val _showPrintDialog = MutableStateFlow(false)
    val showPrintDialog: StateFlow<Boolean> = _showPrintDialog.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    // PAYMENT DETAIL STATE (for bottom sheet)
    // ══════════════════════════════════════════════════════════════════════

    private val _selectedPaymentForDetail = MutableStateFlow<Payment?>(null)
    val selectedPaymentForDetail: StateFlow<Payment?> = _selectedPaymentForDetail.asStateFlow()

    private val _showPaymentDetailSheet = MutableStateFlow(false)
    val showPaymentDetailSheet: StateFlow<Boolean> = _showPaymentDetailSheet.asStateFlow()

    /**
     * Whether current user can process refunds.
     *
     * Based on StaffRole.canRefund() which allows:
     * - SUPERADMIN
     * - OWNER
     * - ADMIN
     */
    val canProcessRefund: StateFlow<Boolean> = MutableStateFlow(
        secureStorage.getRole()?.canRefund() ?: false
    )

    // Payment selected for refund (to be passed to navigation)
    private val _paymentForRefund = MutableStateFlow<Payment?>(null)
    val paymentForRefund: StateFlow<Payment?> = _paymentForRefund.asStateFlow()

    // Pagination state
    private var currentPage = 1
    private var totalPages = 1

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        loadPayments()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Load payments for current filters
     *
     * Resets pagination and fetches first page.
     * Called when user changes filters or pulls to refresh.
     */
    fun loadPayments() {
        viewModelScope.launch {
            try {
                Timber.d("💳 [PaymentsViewModel] Loading payments (page 1)")

                _state.value = PaymentsState.Loading

                // Reset pagination
                currentPage = 1

                // Get venue ID
                val venueId = secureStorage.getVenueId()
                if (venueId.isNullOrEmpty()) {
                    Timber.e("❌ [PaymentsViewModel] No venueId found")
                    _state.value = PaymentsState.Error("No se encontró el ID del local")
                    return@launch
                }

                // Calculate date range
                val (fromDate, toDate) = getDateRange()

                Timber.d("📅 [PaymentsViewModel] Date range: $fromDate to $toDate")

                // Fetch payments
                val result = paymentRepository.getPaymentHistory(
                    venueId = venueId,
                    pageNumber = currentPage,
                    pageSize = 20,
                    fromDate = fromDate,
                    toDate = toDate,
                    staffId = null  // TODO: Add staff filter in future
                )

                when (result) {
                    is Result.Success -> {
                        val data = result.data

                        // Calculate total pages
                        totalPages = (data.total + 19) / 20  // Round up

                        Timber.i("✅ [PaymentsViewModel] Loaded ${data.payments.size} payments (page $currentPage/$totalPages)")
                        val displayPayments = mergeRefundsForDisplay(data.payments)

                        _state.value = PaymentsState.Success(
                            payments = displayPayments,
                            hasMore = data.hasMore,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            totalCount = displayPayments.size
                        )
                    }

                    is Result.Error -> {
                        val errorMessage = when (result.exception) {
                            is com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError -> {
                                "No se pudo conectar al servidor.\nVerifique su conexión a internet."
                            }
                            is com.jaac.avoqado_tpv.core.domain.models.ApiException.HttpError -> {
                                val httpError = result.exception as com.jaac.avoqado_tpv.core.domain.models.ApiException.HttpError
                                when (httpError.code) {
                                    401 -> "Sesión expirada. Por favor inicie sesión nuevamente."
                                    403 -> "No tiene permisos para ver los pagos."
                                    404 -> "No se encontraron pagos."
                                    else -> "Error del servidor (${httpError.code}).\nIntente nuevamente."
                                }
                            }
                            else -> {
                                "Error cargando pagos.\nIntente nuevamente."
                            }
                        }

                        Timber.e("❌ [PaymentsViewModel] Error: $errorMessage")
                        _state.value = PaymentsState.Error(errorMessage)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [PaymentsViewModel] Unexpected error")
                _state.value = PaymentsState.Error("Error inesperado.\nIntente nuevamente.")
            }
        }
    }

    /**
     * Refresh payments (Pull-to-refresh)
     *
     * Reloads payments without showing full-screen loading.
     * Shows only the pull-to-refresh indicator.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                Timber.d("🔄 [PaymentsViewModel] Pull-to-refresh")

                // Reset pagination
                currentPage = 1

                // Get venue ID
                val venueId = secureStorage.getVenueId()
                if (venueId.isNullOrEmpty()) {
                    Timber.e("❌ [PaymentsViewModel] No venueId found")
                    return@launch
                }

                // Calculate date range
                val (fromDate, toDate) = getDateRange()

                // Fetch payments
                val result = paymentRepository.getPaymentHistory(
                    venueId = venueId,
                    pageNumber = currentPage,
                    pageSize = 20,
                    fromDate = fromDate,
                    toDate = toDate,
                    staffId = null
                )

                when (result) {
                    is Result.Success -> {
                        val data = result.data
                        totalPages = (data.total + 19) / 20

                        Timber.i("✅ [PaymentsViewModel] Refreshed ${data.payments.size} payments")
                        val displayPayments = mergeRefundsForDisplay(data.payments)

                        _state.value = PaymentsState.Success(
                            payments = displayPayments,
                            hasMore = data.hasMore,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            totalCount = displayPayments.size
                        )
                    }

                    is Result.Error -> {
                        Timber.e("❌ [PaymentsViewModel] Refresh error: ${result.exception}")
                        // Keep existing data on refresh error, just log it
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [PaymentsViewModel] Refresh failed")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Load next page of payments
     *
     * Appends new payments to existing list.
     * Called when user scrolls to bottom or taps "Load More".
     */
    fun loadMore() {
        viewModelScope.launch {
            try {
                val currentState = _state.value
                if (currentState !is PaymentsState.Success || !currentState.hasMore) {
                    Timber.d("⚠️ [PaymentsViewModel] No more payments to load")
                    return@launch
                }

                Timber.d("💳 [PaymentsViewModel] Loading more payments (page ${currentPage + 1})")

                _state.value = PaymentsState.LoadingMore(
                    payments = currentState.payments,
                    currentPage = currentState.currentPage,
                    totalPages = currentState.totalPages,
                    totalCount = currentState.totalCount
                )

                // Increment page
                currentPage++

                // Get venue ID
                val venueId = secureStorage.getVenueId()
                if (venueId.isNullOrEmpty()) {
                    Timber.e("❌ [PaymentsViewModel] No venueId found")
                    _state.value = currentState  // Revert to previous state
                    return@launch
                }

                // Calculate date range
                val (fromDate, toDate) = getDateRange()

                // Fetch next page
                val result = paymentRepository.getPaymentHistory(
                    venueId = venueId,
                    pageNumber = currentPage,
                    pageSize = 20,
                    fromDate = fromDate,
                    toDate = toDate,
                    staffId = null
                )

                when (result) {
                    is Result.Success -> {
                        val data = result.data

                        // Append new payments to existing list
                        val allPayments = mergeRefundsForDisplay(currentState.payments + data.payments)

                        Timber.i("✅ [PaymentsViewModel] Loaded ${data.payments.size} more payments (total: ${allPayments.size})")

                        _state.value = PaymentsState.Success(
                            payments = allPayments,
                            hasMore = data.hasMore,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            totalCount = allPayments.size
                        )
                    }

                    is Result.Error -> {
                        Timber.e("❌ [PaymentsViewModel] Error loading more: ${result.exception}")
                        _state.value = currentState  // Revert to previous state
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [PaymentsViewModel] Unexpected error loading more")
            }
        }
    }

    /**
     * Change date range filter
     *
     * Reloads payments with new date range.
     */
    fun setDateRangeFilter(filter: DateRangeFilter) {
        if (_filterDateRange.value != filter) {
            Timber.d("📅 [PaymentsViewModel] Changing date range filter to: $filter")
            _filterDateRange.value = filter
            loadPayments()
        }
    }

    /**
     * Change payment method filter
     *
     * Reloads payments with new method filter.
     * Note: Backend filter not implemented yet, so we filter client-side.
     */
    fun setPaymentMethodFilter(method: PaymentMethod?) {
        if (_filterPaymentMethod.value != method) {
            Timber.d("💳 [PaymentsViewModel] Changing payment method filter to: $method")
            _filterPaymentMethod.value = method
            loadPayments()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRINT MODE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Toggle print mode on/off
     *
     * When enabled, payments can be selected for printing.
     * When disabled, clears selection and hides dialog.
     */
    fun togglePrintMode(enabled: Boolean) {
        Timber.d("🖨️ [PaymentsViewModel] Print mode: $enabled")
        _isPrintMode.value = enabled

        // Clear selection when disabling print mode
        if (!enabled) {
            _selectedPaymentsForPrint.value = emptySet()
            _showPrintDialog.value = false
        }
    }

    /**
     * Toggle payment selection for printing
     *
     * Max 20 payments can be selected (memory safety for 1GB RAM).
     */
    fun togglePaymentSelection(payment: Payment) {
        val paymentId = payment.id
        val currentSelection = _selectedPaymentsForPrint.value.toMutableSet()

        if (paymentId in currentSelection) {
            currentSelection.remove(paymentId)
            Timber.d("🖨️ [PaymentsViewModel] Deselected payment: $paymentId")
        } else {
            // Max 20 payments check
            if (currentSelection.size >= 20) {
                Timber.w("⚠️ [PaymentsViewModel] Max 20 payments can be printed at once")
                return
            }
            currentSelection.add(paymentId)
            Timber.d("🖨️ [PaymentsViewModel] Selected payment: $paymentId (total: ${currentSelection.size})")
        }

        _selectedPaymentsForPrint.value = currentSelection
    }

    /**
     * Show print dialog
     *
     * Only shows if at least one payment is selected.
     */
    fun showPrintDialog() {
        if (_selectedPaymentsForPrint.value.isEmpty()) {
            Timber.w("⚠️ [PaymentsViewModel] No payments selected for printing")
            return
        }
        Timber.d("🖨️ [PaymentsViewModel] Showing print dialog (${_selectedPaymentsForPrint.value.size} payments)")
        _showPrintDialog.value = true
    }

    /**
     * Dismiss print dialog
     */
    fun dismissPrintDialog() {
        _showPrintDialog.value = false
    }

    /**
     * Print selected payments
     *
     * @param printMode INDIVIDUAL (one receipt per payment) or SUMMARY (all in one)
     */
    fun printSelectedPayments(printMode: PaymentPrintMode) {
        viewModelScope.launch {
            try {
                val currentState = _state.value
                if (currentState !is PaymentsState.Success && currentState !is PaymentsState.LoadingMore) {
                    Timber.w("⚠️ [PaymentsViewModel] Cannot print - payments not loaded")
                    return@launch
                }

                // Get payments list from current state
                val payments = when (currentState) {
                    is PaymentsState.Success -> currentState.payments
                    is PaymentsState.LoadingMore -> currentState.payments
                    else -> emptyList()
                }

                // Get selected payments
                val selectedIds = _selectedPaymentsForPrint.value
                val selectedPayments = payments.filter { it.id in selectedIds }

                if (selectedPayments.isEmpty()) {
                    Timber.w("⚠️ [PaymentsViewModel] No payments found for selected IDs")
                    return@launch
                }

                Timber.i("🖨️ [PaymentsViewModel] Printing ${selectedPayments.size} payments (mode: $printMode)")

                val venueName = secureStorage.getVenueName()

                when (printMode) {
                    PaymentPrintMode.INDIVIDUAL -> {
                        // Print each payment individually
                        selectedPayments.forEach { payment ->
                            val result = printerManager.printPaymentHistoryReceipt(
                                payment = payment,
                                venueName = venueName
                            )
                            if (result.isFailure) {
                                Timber.e("❌ [PaymentsViewModel] Failed to print payment: ${payment.id}")
                            }
                        }
                    }

                    PaymentPrintMode.SUMMARY -> {
                        // Print all payments in one receipt
                        val dateRange = _filterDateRange.value.label
                        val result = printerManager.printPaymentsSummary(
                            payments = selectedPayments,
                            dateRangeLabel = dateRange,
                            venueName = venueName
                        )
                        if (result.isFailure) {
                            Timber.e("❌ [PaymentsViewModel] Failed to print payments summary")
                        }
                    }
                }

                // Close dialog and exit print mode
                _showPrintDialog.value = false
                togglePrintMode(false)

                Timber.i("✅ [PaymentsViewModel] Print completed")
            } catch (e: Exception) {
                Timber.e(e, "❌ [PaymentsViewModel] Unexpected error during printing")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PAYMENT DETAIL METHODS (for bottom sheet + refund)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Show payment detail bottom sheet
     *
     * Called when user taps a payment card (not in print mode).
     * Opens bottom sheet with full payment details and refund option.
     */
    fun showPaymentDetail(payment: Payment) {
        Timber.d("📋 [PaymentsViewModel] Showing payment detail: ${payment.id}")
        _selectedPaymentForDetail.value = payment
        _showPaymentDetailSheet.value = true
    }

    /**
     * Dismiss payment detail bottom sheet
     */
    fun dismissPaymentDetail() {
        Timber.d("📋 [PaymentsViewModel] Dismissing payment detail")
        _showPaymentDetailSheet.value = false
        _selectedPaymentForDetail.value = null
    }

    /**
     * Initiate refund for a payment
     *
     * Called when user taps "Refund" button in payment detail.
     * Sets the payment for refund and triggers navigation.
     *
     * **Pre-conditions checked:**
     * - User role is authorized (canRefund)
     * - Payment is refundable (not cash, not fully refunded, has merchantAccountId)
     *
     * @param payment Payment to refund
     */
    fun initiateRefund(payment: Payment) {
        // Validate refundability
        if (!payment.isRefundable()) {
            val reason = payment.getNonRefundableReason()
            Timber.w("⚠️ [PaymentsViewModel] Payment not refundable: $reason")
            return
        }

        // Validate user role
        val role = secureStorage.getRole()
        if (role == null || !role.canRefund()) {
            Timber.w("⚠️ [PaymentsViewModel] User role cannot process refunds: $role")
            return
        }

        Timber.i("🔄 [PaymentsViewModel] Initiating refund for payment: ${payment.id} | merchant: ${payment.merchantAccountId}")

        // Close detail sheet
        _showPaymentDetailSheet.value = false
        _selectedPaymentForDetail.value = null

        // Set payment for refund (triggers navigation)
        _paymentForRefund.value = payment
    }

    /**
     * Clear refund navigation state
     *
     * Called after navigation to refund screen is complete.
     */
    fun clearRefundNavigation() {
        _paymentForRefund.value = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * UI-only: Collapse refund entries into their original payment to avoid duplicates in history.
     *
     * Rules:
     * - If a refund matches an original payment (by orderId or orderNumber), hide the refund row.
     * - Aggregate refunded amounts into the original payment if backend didn't provide it.
     * - Keep unmatched refunds visible (safety net).
     */
    private fun mergeRefundsForDisplay(payments: List<Payment>): List<Payment> {
        if (payments.none { it.isRefund }) return payments

        fun refundKey(payment: Payment): String? = payment.orderId ?: payment.orderNumber

        val refundedByKey = mutableMapOf<String, BigDecimal>()
        payments.filter { it.isRefund }.forEach { refund ->
            val key = refundKey(refund) ?: return@forEach
            val amount = refund.totalAmount.abs()
            refundedByKey[key] = (refundedByKey[key] ?: BigDecimal.ZERO) + amount
        }

        val updatedByKey = mutableMapOf<String, Payment>()
        payments.filterNot { it.isRefund }.forEach { payment ->
            val key = refundKey(payment) ?: return@forEach
            val aggregatedRefund = refundedByKey[key] ?: return@forEach
            val existingRefunded = payment.refundedAmount
            val mergedRefunded = if (existingRefunded != null && existingRefunded > BigDecimal.ZERO) {
                existingRefunded
            } else {
                aggregatedRefund
            }
            val isFullyRefunded = payment.isFullyRefunded || mergedRefunded >= payment.totalAmount
            updatedByKey[key] = payment.copy(
                refundedAmount = mergedRefunded,
                isFullyRefunded = isFullyRefunded
            )
        }

        val result = mutableListOf<Payment>()
        payments.forEach { payment ->
            if (payment.isRefund) {
                val key = refundKey(payment)
                val hasOriginal = key != null && updatedByKey.containsKey(key)
                if (hasOriginal) return@forEach
                result += payment
            } else {
                val key = refundKey(payment)
                result += if (key != null && updatedByKey.containsKey(key)) {
                    updatedByKey.getValue(key)
                } else {
                    payment
                }
            }
        }
        return result
    }

    /**
     * Get date range based on selected filter
     */
    private fun getDateRange(): Pair<Instant, Instant> {
        val now = Instant.now()
        val fromDate = when (_filterDateRange.value) {
            DateRangeFilter.LAST_7_DAYS -> now.minus(7, ChronoUnit.DAYS)
            DateRangeFilter.LAST_30_DAYS -> now.minus(30, ChronoUnit.DAYS)
            DateRangeFilter.LAST_90_DAYS -> now.minus(90, ChronoUnit.DAYS)
            DateRangeFilter.ALL_TIME -> Instant.EPOCH  // Beginning of time
        }
        return Pair(fromDate, now)
    }
}

/**
 * Payments Screen State
 *
 * Sealed class representing all possible states of the payments screen.
 */
sealed class PaymentsState {
    /**
     * Loading initial payments
     */
    data object Loading : PaymentsState()

    /**
     * Successfully loaded payments
     */
    data class Success(
        val payments: List<Payment>,
        val hasMore: Boolean,
        val currentPage: Int,
        val totalPages: Int,
        val totalCount: Int
    ) : PaymentsState()

    /**
     * Loading more payments (pagination)
     */
    data class LoadingMore(
        val payments: List<Payment>,
        val currentPage: Int,
        val totalPages: Int,
        val totalCount: Int
    ) : PaymentsState()

    /**
     * Error loading payments
     */
    data class Error(
        val message: String
    ) : PaymentsState()
}

/**
 * Date Range Filter Options
 */
enum class DateRangeFilter(val label: String) {
    LAST_7_DAYS("Últimos 7 días"),
    LAST_30_DAYS("Últimos 30 días"),
    LAST_90_DAYS("Últimos 90 días"),
    ALL_TIME("Todo el tiempo")
}

/**
 * Print Mode Options for Payment History
 */
enum class PaymentPrintMode {
    /** Print one receipt per payment */
    INDIVIDUAL,
    /** Print all payments in one summary receipt */
    SUMMARY
}
