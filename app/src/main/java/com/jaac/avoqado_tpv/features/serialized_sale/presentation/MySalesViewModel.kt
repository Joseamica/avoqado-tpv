package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isGift: Boolean
)

@HiltViewModel
class MySalesViewModel @Inject constructor(
    private val apiService: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(MySalesUiState())
    val uiState: StateFlow<MySalesUiState> = _uiState.asStateFlow()

    private val venueZone: ZoneId = ZoneId.of("America/Mexico_City")
    private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    init {
        loadSales()
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
                            serialNumber = sale.serialNumber,
                            categoryName = sale.categoryName,
                            price = BigDecimal.valueOf(sale.price),
                            date = sale.date,
                            paymentStatus = sale.paymentStatus,
                            isGift = sale.isGift
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
}
