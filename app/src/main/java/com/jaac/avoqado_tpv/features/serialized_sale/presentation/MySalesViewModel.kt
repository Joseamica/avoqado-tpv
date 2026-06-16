package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.serialized_sale.data.dto.MySaleItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class MySalesUiState(
    val isLoading: Boolean = true,
    val month: String = "",
    val monthDisplay: String = "",
    val totalSales: Int = 0,
    val totalAmount: BigDecimal = BigDecimal.ZERO,
    val salesByDay: Map<String, List<SaleItem>> = emptyMap(),
    // Cross-month "Por revisar" feed (FAILED + PENDING/PROCESSING), pinned at the top.
    // Independent of the selected month — a rejected sale from a past month still shows.
    val salesToReview: List<SaleItem> = emptyList(),
    val error: String? = null
)

data class SaleItem(
    val id: String,
    val orderNumber: String,
    val serialNumber: String,
    val categoryName: String,
    val price: BigDecimal,
    val date: String,
    val paymentStatus: String,
    val isGift: Boolean,
    // Back-office documentation review (PlayTelecom / Walmart). Null when no verification exists
    // or when client is talking to a legacy backend that doesn't return these fields.
    val verificationStatus: VerificationReviewStatus = VerificationReviewStatus.NONE,
    val reviewedAt: String? = null,
    val reviewNotes: String? = null,
    val rejectionReasons: List<RejectionReason> = emptyList(),
    // IDs needed by the sale-correction flow (tap a rejected sale → re-upload docs).
    val verificationId: String? = null,
    val paymentId: String? = null,
)

/** Back-office review status for the photo documentation attached to a sale. */
enum class VerificationReviewStatus {
    NONE,       // No verification record exists (or legacy backend)
    PENDING,    // Photos uploaded, waiting for back-office to act
    COMPLETED,  // Back-office approved → "Venta correcta"
    FAILED,     // Back-office rejected, fixable → "Revisar documentación" (promoter re-uploads)
    REJECTED    // Terminal: sale lost (couldn't link/port, customer gone) → "Rechazada". Not correctable.
}

/** Rejection reasons echoed from backend; matches enum SaleVerificationRejectionReason. */
enum class RejectionReason(val raw: String, val label: String) {
    REVIEW_PORTABILIDAD("REVIEW_PORTABILIDAD", "Falta imagen de portabilidad"),
    REVIEW_DUPLICATE_VINCULACION("REVIEW_DUPLICATE_VINCULACION", "# de vinculación duplicada"),
    REVIEW_ILLEGIBLE_IMAGES("REVIEW_ILLEGIBLE_IMAGES", "Imágenes ilegibles"),
    REVIEW_MISSING_LINKING_IMAGE("REVIEW_MISSING_LINKING_IMAGE", "Falta imagen de vinculación"),
    OTHER("OTHER", "Otro motivo");

    companion object {
        fun parseList(values: List<String>?): List<RejectionReason> =
            values?.mapNotNull { v -> values().firstOrNull { it.raw == v } } ?: emptyList()
    }
}

@HiltViewModel
class MySalesViewModel @Inject constructor(
    private val apiService: ApiService,
    private val socketManager: SocketManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MySalesUiState())
    val uiState: StateFlow<MySalesUiState> = _uiState.asStateFlow()

    private val venueZone: ZoneId = ZoneId.of("America/Mexico_City")
    private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    init {
        loadSales()
        loadSalesToReview()
        observeSocketEvents()
    }

    /**
     * Subscribe to back-office review events. When a verification is approved/rejected
     * from the dashboard, refetch the current month's sales so the badge updates in real time.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events
                .onEach { event ->
                    if (event is SocketEvent.SaleVerificationReviewed) {
                        Timber.d("📨 sale-verification.reviewed received status=${event.status} — refreshing My Sales")
                        refreshCurrentMonth()
                        loadSalesToReview()
                    }
                }
                .collect {}
        }
    }

    fun loadSales(month: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val targetMonth = month ?: LocalDate.now(venueZone).format(monthFormat)
                val response = apiService.getMySalesHistory(month = targetMonth)

                if (response.isSuccessful && response.body()?.success == true) {
                    val data = response.body()!!.data!!

                    val sales = data.sales.map { it.toSaleItem() }

                    // Group by day (venue timezone)
                    val salesByDay = sales.groupBy { sale ->
                        try {
                            val instant = java.time.Instant.parse(sale.date)
                            val localDate = instant.atZone(venueZone).toLocalDate()
                            localDate.format(
                                DateTimeFormatter.ofPattern(
                                    "d 'de' MMMM",
                                    Locale("es", "MX")
                                )
                            )
                        } catch (e: Exception) {
                            sale.date.take(10)
                        }
                    }

                    // Get month display name
                    val monthDate = YearMonth.parse(data.month)
                    val monthDisplay = monthDate.month
                        .getDisplayName(TextStyle.FULL, Locale("es", "MX"))
                        .replaceFirstChar { it.uppercase() }

                    _uiState.value = MySalesUiState(
                        isLoading = false,
                        month = data.month,
                        monthDisplay = monthDisplay,
                        totalSales = data.totalSales,
                        totalAmount = BigDecimal.valueOf(data.totalAmount),
                        salesByDay = salesByDay
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Error al cargar ventas"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load my sales")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error de conexion: ${e.message}"
                )
            }
        }
    }

    /**
     * Load the cross-month "Por revisar" feed (FAILED + PENDING/PROCESSING). Independent
     * of the selected month — runs once on open and again when a back-office review event
     * arrives. Failures are non-fatal: the section just stays empty / unchanged so it never
     * blocks the month list.
     */
    fun loadSalesToReview() {
        viewModelScope.launch {
            try {
                val response = apiService.getSalesToReview()
                if (response.isSuccessful && response.body()?.success == true) {
                    val items = response.body()?.data?.sales.orEmpty().map { it.toSaleItem() }
                    _uiState.value = _uiState.value.copy(salesToReview = items)
                } else {
                    Timber.w("sales-to-review failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load sales-to-review")
            }
        }
    }

    fun navigateMonth(delta: Int) {
        val currentMonth = _uiState.value.month
        if (currentMonth.isEmpty()) return
        val current = YearMonth.parse(currentMonth)
        val target = current.plusMonths(delta.toLong())
        loadSales(target.format(monthFormat))
    }

    /**
     * Refresh the current month's sales — called when a socket event signals
     * that a back-office review has changed the verification status.
     */
    fun refreshCurrentMonth() {
        val currentMonth = _uiState.value.month
        if (currentMonth.isEmpty()) {
            loadSales()
        } else {
            loadSales(currentMonth)
        }
    }

    companion object {
        internal fun parseVerificationStatus(raw: String?): VerificationReviewStatus = when (raw) {
            "PENDING", "PROCESSING" -> VerificationReviewStatus.PENDING
            "COMPLETED" -> VerificationReviewStatus.COMPLETED
            "FAILED" -> VerificationReviewStatus.FAILED
            "REJECTED" -> VerificationReviewStatus.REJECTED
            else -> VerificationReviewStatus.NONE
        }
    }
}

/** Maps the network DTO to the UI model. Shared by the month list and the "Por revisar" feed. */
internal fun MySaleItem.toSaleItem(): SaleItem = SaleItem(
    id = id,
    orderNumber = orderNumber,
    serialNumber = serialNumber.orEmpty(),
    categoryName = categoryName.orEmpty(),
    price = BigDecimal.valueOf(price),
    date = date,
    paymentStatus = paymentStatus,
    isGift = isGift,
    verificationStatus = MySalesViewModel.parseVerificationStatus(verificationStatus),
    reviewedAt = reviewedAt,
    reviewNotes = reviewNotes,
    rejectionReasons = RejectionReason.parseList(rejectionReasons),
    verificationId = verificationId,
    paymentId = paymentId,
)
