package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
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
    onNavigateToCorrection: (verificationId: String) -> Unit = {},
    viewModel: MySalesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MySalesScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onPreviousMonth = { viewModel.navigateMonth(-1) },
        onNextMonth = { viewModel.navigateMonth(1) },
        onRetry = { viewModel.loadSales() },
        onSaleClick = { sale ->
            // Only rejected sales are actionable — tap navigates to the correction flow.
            if (sale.verificationStatus == VerificationReviewStatus.FAILED && sale.verificationId != null) {
                onNavigateToCorrection(sale.verificationId)
            }
        }
    )
}

@Composable
private fun MySalesScreenContent(
    uiState: MySalesUiState,
    onNavigateBack: () -> Unit = {},
    onPreviousMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSaleClick: (SaleItem) -> Unit = {}
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

                    // Sales list: pinned "Por revisar" section (cross-month) + day groups.
                    if (uiState.salesByDay.isEmpty() && uiState.salesToReview.isEmpty()) {
                        EmptyState()
                    } else {
                        SalesList(
                            salesToReview = uiState.salesToReview,
                            salesByDay = uiState.salesByDay,
                            onSaleClick = onSaleClick
                        )
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
    salesToReview: List<SaleItem>,
    salesByDay: Map<String, List<SaleItem>>,
    onSaleClick: (SaleItem) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 📌 Pinned "Por revisar" — cross-month sales needing attention (rejected or in review).
        // Field finding: with many correct sales, promoters get complacent and miss the rejected
        // ones. Surfacing them at the very top, independent of the selected month, prevents that.
        if (salesToReview.isNotEmpty()) {
            item(key = "to_review_section") {
                ToReviewSection(sales = salesToReview, onSaleClick = onSaleClick)
            }
        }

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
                SaleRow(sale = sale, onSaleClick = onSaleClick)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Pinned "Por revisar" section shown at the very top of "Mis Ventas".
 * Cross-month: lists sales that are rejected (FAILED → tap to correct) or still under
 * review (PENDING). Red-tinted card so it stands out from the normal day-grouped list.
 */
@Composable
private fun ToReviewSection(
    sales: List<SaleItem>,
    onSaleClick: (SaleItem) -> Unit = {}
) {
    val errorColor = MaterialTheme.avoqadoColors.statusError
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = errorColor.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = errorColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Por revisar (${sales.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = errorColor
                )
            }
            Text(
                text = "Estas ventas necesitan tu atención antes de que se acumulen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            sales.forEachIndexed { index, sale ->
                SaleRow(sale = sale, onSaleClick = onSaleClick)
                if (index < sales.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = errorColor.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SaleRow(sale: SaleItem, onSaleClick: (SaleItem) -> Unit = {}) {
    val giftColor = MaterialTheme.avoqadoColors.statusWarning
    val successColor = MaterialTheme.avoqadoColors.statusSuccess

    // Rejected sales are tappable — promoter navigates to the correction flow.
    val isCorrectable = sale.verificationStatus == VerificationReviewStatus.FAILED &&
        sale.verificationId != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCorrectable) Modifier.clickable { onSaleClick(sale) } else Modifier
            )
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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

        // Back-office documentation review status (PlayTelecom / Walmart)
        if (sale.verificationStatus != VerificationReviewStatus.NONE) {
            Spacer(modifier = Modifier.height(4.dp))
            VerificationStatusBadge(
                status = sale.verificationStatus,
                rejectionReasons = sale.rejectionReasons,
                reviewNotes = sale.reviewNotes,
            )
            // Call-to-action so the promoter knows the rejected row is tappable.
            if (isCorrectable) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Toca para corregir",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerificationStatusBadge(
    status: VerificationReviewStatus,
    rejectionReasons: List<RejectionReason>,
    reviewNotes: String?,
) {
    val (label, bg, fg) = when (status) {
        VerificationReviewStatus.PENDING -> Triple(
            "En revisión por Administración",
            MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.15f),
            MaterialTheme.avoqadoColors.statusWarning,
        )
        VerificationReviewStatus.COMPLETED -> Triple(
            "Venta correcta",
            MaterialTheme.avoqadoColors.statusSuccess.copy(alpha = 0.15f),
            MaterialTheme.avoqadoColors.statusSuccess,
        )
        VerificationReviewStatus.FAILED -> Triple(
            "Revisar documentación",
            MaterialTheme.avoqadoColors.statusError.copy(alpha = 0.15f),
            MaterialTheme.avoqadoColors.statusError,
        )
        VerificationReviewStatus.REJECTED -> Triple(
            // Terminal — sale lost. Solid red chip (no "toca para corregir"); not actionable.
            "Rechazada",
            MaterialTheme.avoqadoColors.statusError,
            Color.White,
        )
        VerificationReviewStatus.NONE -> return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .background(bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }

        // Rejection reasons only apply to the fixable "Revisar documentación" (FAILED) outcome.
        if (status == VerificationReviewStatus.FAILED && rejectionReasons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            rejectionReasons.forEach { reason ->
                Text(
                    text = "• ${reason.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.avoqadoColors.statusError,
                    fontSize = 11.sp,
                )
            }
        }
        // The back-office note (motivo) is shown for both FAILED ("qué corregir") and
        // REJECTED ("por qué se perdió la venta").
        if ((status == VerificationReviewStatus.FAILED || status == VerificationReviewStatus.REJECTED) && !reviewNotes.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = reviewNotes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
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
                salesToReview = listOf(
                    SaleItem(
                        id = "r1",
                        orderNumber = "SN098",
                        serialNumber = "8952140000003331111",
                        categoryName = "SIM Negra",
                        price = BigDecimal("55.00"),
                        date = "2026-02-18T12:00:00.000Z",
                        paymentStatus = "COMPLETED",
                        isGift = false,
                        verificationStatus = VerificationReviewStatus.FAILED,
                        rejectionReasons = listOf(RejectionReason.REVIEW_ILLEGIBLE_IMAGES),
                        verificationId = "ver_r1"
                    ),
                    SaleItem(
                        id = "r2",
                        orderNumber = "SN099",
                        serialNumber = "8952140000004442222",
                        categoryName = "SIM Blanca",
                        price = BigDecimal("55.00"),
                        date = "2026-03-20T12:00:00.000Z",
                        paymentStatus = "COMPLETED",
                        isGift = false,
                        verificationStatus = VerificationReviewStatus.PENDING,
                        verificationId = "ver_r2"
                    )
                ),
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
