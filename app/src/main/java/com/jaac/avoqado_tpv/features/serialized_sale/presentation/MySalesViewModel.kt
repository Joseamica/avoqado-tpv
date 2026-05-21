package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
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
    val rejectionReasons: List<RejectionReason> = emptyList()
)

/** Back-office review status for the photo documentation attached to a sale. */
enum class VerificationReviewStatus {
    NONE,       // No verification record exists (or legacy backend)
    PENDING,    // Photos uploaded, waiting for back-office to act
    COMPLETED,  // Back-office approved → "Venta correcta"
    FAILED      // Back-office rejected → "Revisar documentación"
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

                    val sales = data.sales.map { sale ->
                        SaleItem(
                            id = sale.id,
                            orderNumber = sale.orderNumber,
                            serialNumber = sale.serialNumber.orEmpty(),
                            categoryName = sale.categoryName.orEmpty(),
                            price = BigDecimal.valueOf(sale.price),
                            date = sale.date,
                            paymentStatus = sale.paymentStatus,
                            isGift = sale.isGift,
                            verificationStatus = parseVerificationStatus(sale.verificationStatus),
                            reviewedAt = sale.reviewedAt,
                            reviewNotes = sale.reviewNotes,
                            rejectionReasons = RejectionReason.parseList(sale.rejectionReasons)
                        )
                    }

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
            else -> VerificationReviewStatus.NONE
        }
    }
}
