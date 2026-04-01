package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import java.math.BigDecimal

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

@Composable
fun MySalesScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: MySalesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MySalesScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onPreviousMonth = { viewModel.navigateMonth(-1) },
        onNextMonth = { viewModel.navigateMonth(1) },
        onRetry = { viewModel.loadSales() }
    )
}

@Composable
private fun MySalesScreenContent(
    uiState: MySalesUiState,
    onNavigateBack: () -> Unit = {},
    onPreviousMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Mis Ventas",
                onNavigationClick = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Month selector
            MonthSelector(
                monthDisplay = uiState.monthDisplay,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth
            )

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                uiState.error != null -> {
                    ErrorState(
                        error = uiState.error,
                        onRetry = onRetry
                    )
                }

                else -> {
                    // Summary card
                    SummaryCard(
                        monthDisplay = uiState.monthDisplay,
                        totalSales = uiState.totalSales,
                        totalAmount = uiState.totalAmount
                    )

                    // Sales list grouped by day
                    if (uiState.salesByDay.isEmpty()) {
                        EmptyState()
                    } else {
                        SalesList(salesByDay = uiState.salesByDay)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    monthDisplay: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Mes anterior",
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = monthDisplay,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Mes siguiente",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun SummaryCard(
    monthDisplay: String,
    totalSales: Int,
    totalAmount: BigDecimal
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ventas mes: $monthDisplay",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "$totalSales",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = CurrencyFormatter.format(totalAmount),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SalesList(
    salesByDay: Map<String, List<SaleItem>>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        salesByDay.forEach { (dayLabel, sales) ->
            // Day header
            item(key = "header_$dayLabel") {
                Text(
                    text = dayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Sale items for this day
            items(
                items = sales,
                key = { it.id }
            ) { sale ->
                SaleRow(sale = sale)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun SaleRow(sale: SaleItem) {
    val giftColor = MaterialTheme.avoqadoColors.statusWarning
    val successColor = MaterialTheme.avoqadoColors.statusSuccess

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: serial + category
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = sale.serialNumber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sale.categoryName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right: amount or "Regalo"
        if (sale.isGift) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Icon(
                    imageVector = Icons.Default.CardGiftcard,
                    contentDescription = "Regalo",
                    modifier = Modifier.size(16.dp),
                    tint = giftColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Regalo",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = giftColor
                )
            }
        } else {
            Text(
                text = CurrencyFormatter.format(sale.price),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = successColor
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Receipt,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sin ventas este mes",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorState(
    error: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Reintentar")
            }
        }
    }
}

// ========== Previews ==========

@Preview(widthDp = 360, heightDp = 640, name = "My Sales - PAX A910S")
@Preview(device = PAX_A910S, showSystemUi = true, name = "My Sales - PAX A910S (device)")
@Composable
private fun MySalesScreenPreview() {
    AvoqadoTheme {
        MySalesScreenContent(
            uiState = MySalesUiState(
                isLoading = false,
                month = "2026-03",
                monthDisplay = "Marzo",
                totalSales = 4,
                totalAmount = BigDecimal("220.00"),
                salesByDay = mapOf(
                    "26 de marzo" to listOf(
                        SaleItem(
                            id = "1",
                            orderNumber = "001",
                            serialNumber = "8952140000001234567",
                            categoryName = "SIM Negra",
                            price = BigDecimal("55.00"),
                            date = "2026-03-26T14:30:00.000Z",
                            paymentStatus = "COMPLETED",
                            isGift = false
                        ),
                        SaleItem(
                            id = "2",
                            orderNumber = "002",
                            serialNumber = "8952140000009876543",
                            categoryName = "SIM Blanca",
                            price = BigDecimal.ZERO,
                            date = "2026-03-26T10:15:00.000Z",
                            paymentStatus = "COMPLETED",
                            isGift = true
                        )
                    ),
                    "25 de marzo" to listOf(
                        SaleItem(
                            id = "3",
                            orderNumber = "003",
                            serialNumber = "8952140000005551234",
                            categoryName = "SIM Negra",
                            price = BigDecimal("55.00"),
                            date = "2026-03-25T16:00:00.000Z",
                            paymentStatus = "COMPLETED",
                            isGift = false
                        ),
                        SaleItem(
                            id = "4",
                            orderNumber = "004",
                            serialNumber = "8952140000007779876",
                            categoryName = "SIM Negra",
                            price = BigDecimal("55.00"),
                            date = "2026-03-25T11:00:00.000Z",
                            paymentStatus = "COMPLETED",
                            isGift = false
                        )
                    )
                )
            )
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, name = "My Sales - Loading")
@Composable
private fun MySalesScreenLoadingPreview() {
    AvoqadoTheme {
        MySalesScreenContent(
            uiState = MySalesUiState(
                isLoading = true,
                monthDisplay = "Marzo"
            )
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, name = "My Sales - Empty")
@Composable
private fun MySalesScreenEmptyPreview() {
    AvoqadoTheme {
        MySalesScreenContent(
            uiState = MySalesUiState(
                isLoading = false,
                month = "2026-03",
                monthDisplay = "Marzo",
                totalSales = 0,
                totalAmount = BigDecimal.ZERO,
                salesByDay = emptyMap()
            )
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, name = "My Sales - Error")
@Composable
private fun MySalesScreenErrorPreview() {
    AvoqadoTheme {
        MySalesScreenContent(
            uiState = MySalesUiState(
                isLoading = false,
                error = "Error de conexion: timeout"
            )
        )
    }
}
