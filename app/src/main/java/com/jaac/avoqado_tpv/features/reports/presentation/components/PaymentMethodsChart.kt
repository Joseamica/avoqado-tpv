package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import java.math.BigDecimal

/**
 * Payment Methods Chart Component
 *
 * Displays payment method breakdown as horizontal bars.
 * Shows percentage, absolute amount, and visual bar for each method.
 *
 * **Design:**
 * - Horizontal bar chart (easy to read on TPV devices)
 * - Color-coded by payment method
 * - Shows both percentage and currency amount
 * - Only displays methods with non-zero amounts
 *
 * @param breakdown Payment method breakdown data
 * @param modifier Modifier for customization
 */
@Composable
fun PaymentMethodsChart(
    breakdown: PaymentMethodBreakdown,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title
            Text(
                text = "Métodos de Pago",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            // Check if there's data
            if (breakdown.totalAmount == BigDecimal.ZERO) {
                // Empty state
                Text(
                    text = "No hay datos de pagos para mostrar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                // Payment method entries
                breakdown.getEntries().forEach { entry ->
                    PaymentMethodBar(
                        label = entry.label,
                        amount = entry.formatAmount(),
                        percentage = entry.formatPercentage(),
                        percentageValue = entry.percentage.toFloat(),
                        color = getColorForPaymentMethod(entry.method)
                    )
                }
            }
        }
    }
}

/**
 * Individual payment method bar
 *
 * @param label Payment method label (e.g., "Efectivo", "Tarjeta")
 * @param amount Formatted amount (e.g., "$1,250.00")
 * @param percentage Formatted percentage (e.g., "55.0%")
 * @param percentageValue Percentage as float (0-100) for bar width
 * @param color Bar color
 */
@Composable
private fun PaymentMethodBar(
    label: String,
    amount: String,
    percentage: String,
    percentageValue: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Label and amount row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = amount,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = percentage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (percentageValue / 100f).coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Get color for payment method
 *
 * Color-coding follows industry standards:
 * - Cash: Green (traditional cash color)
 * - Card: Blue (card/digital payment)
 * - Voucher: Purple (specific chart color)
 * - Other: Gray (neutral)
 */
@Composable
private fun getColorForPaymentMethod(method: PaymentMethodBreakdown.PaymentMethod): Color {
    return when (method) {
        PaymentMethodBreakdown.PaymentMethod.CASH -> MaterialTheme.avoqadoColors.statusSuccess
        PaymentMethodBreakdown.PaymentMethod.CARD -> MaterialTheme.avoqadoColors.statusInfo
        PaymentMethodBreakdown.PaymentMethod.VOUCHER -> Color(0xFF9C27B0)  // Purple (specific chart color)
        PaymentMethodBreakdown.PaymentMethod.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 400, heightDp = 300)
@Composable
private fun PaymentMethodsChartPreview() {
    AvoqadoTheme {
        val breakdown = PaymentMethodBreakdown(
            cashAmount = BigDecimal("6847.50"),
            cardAmount = BigDecimal("5229.00"),
            voucherAmount = BigDecimal("373.50"),
            otherAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("12450.00"),
            cashPercentage = BigDecimal("55.0"),
            cardPercentage = BigDecimal("42.0"),
            voucherPercentage = BigDecimal("3.0"),
            otherPercentage = BigDecimal.ZERO
        )

        PaymentMethodsChart(breakdown = breakdown)
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 200)
@Composable
private fun PaymentMethodsChartEmptyPreview() {
    AvoqadoTheme {
        val breakdown = PaymentMethodBreakdown.empty()
        PaymentMethodsChart(breakdown = breakdown)
    }
}
