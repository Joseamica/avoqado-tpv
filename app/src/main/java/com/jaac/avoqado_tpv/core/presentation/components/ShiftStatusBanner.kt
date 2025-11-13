package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shift Status Banner
 *
 * Compact banner component that shows current shift status on the main screen.
 * Follows Toast/Square POS pattern for glanceable shift information.
 *
 * **Visual Design:**
 * - Green indicator when shift is OPEN
 * - Red indicator when no shift is active
 * - Shows staff name, start time, and current sales
 * - Tappable to navigate to full Shifts screen
 *
 * **Usage:**
 * ```kotlin
 * ShiftStatusBanner(
 *     shift = currentShift,
 *     onClick = { navController.navigate(NavRoute.Shifts.route) }
 * )
 * ```
 *
 * @param shift Current active shift (null if no shift open)
 * @param onClick Callback when banner is tapped (navigate to Shifts screen)
 * @param modifier Modifier for customization
 */
@Composable
fun ShiftStatusBanner(
    shift: Shift?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isShiftOpen = shift != null && shift.status == ShiftStatus.OPEN

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Status indicator + Info
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                Icon(
                    imageVector = if (isShiftOpen) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = if (isShiftOpen) "Turno abierto" else "Sin turno activo",
                    tint = if (isShiftOpen) Color(0xFF4CAF50) else Color(0xFFEB5757),
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Shift info
                Column {
                    if (isShiftOpen && shift != null) {
                        // Staff name + start time
                        Text(
                            text = "Turno: ${shift.staffName}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Start time + duration
                        Text(
                            text = formatShiftTime(shift.startTime, shift.durationMinutes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    } else {
                        // No shift active
                        Text(
                            text = "Sin turno activo",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Toca para abrir un turno",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Right: Sales amount (if shift open)
            if (isShiftOpen && shift != null) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "$${shift.totalSales}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = Color(0xFF4CAF50)
                    )

                    Text(
                        text = "Ventas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Format shift time for display
 *
 * Examples:
 * - "Inicio: 14:30 - Duración: 2h 30m"
 * - "Inicio: 09:00 - Duración: 45m"
 *
 * @param startTime ISO 8601 timestamp
 * @param durationMinutes Duration in minutes (null if shift is open but < 1 min)
 */
private fun formatShiftTime(startTime: String, durationMinutes: Int?): String {
    return try {
        val instant = Instant.parse(startTime)
        val localTime = instant.atZone(ZoneId.systemDefault()).toLocalTime()
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val formattedTime = localTime.format(formatter)

        val duration = if (durationMinutes != null && durationMinutes > 0) {
            val hours = durationMinutes / 60
            val minutes = durationMinutes % 60

            when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }
        } else {
            "Ahora"
        }

        "Inicio: $formattedTime - Duración: $duration"
    } catch (e: Exception) {
        "Inicio: Desconocido"
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun ShiftStatusBannerOpenPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            ShiftStatusBanner(
                shift = Shift(
                    id = "shift-123",
                    venueId = "venue-1",
                    staffId = "staff-1",
                    staffName = "Juan Pérez",
                    startTime = "2025-01-15T14:30:00Z",
                    endTime = null,
                    status = ShiftStatus.OPEN,
                    startingCash = BigDecimal("500.00"),
                    endingCash = null,
                    totalSales = BigDecimal("1250.50"),
                    totalTips = BigDecimal("125.00"),
                    totalOrders = 15,
                    totalCashPayments = BigDecimal("600.00"),
                    totalCardPayments = BigDecimal("650.50"),
                    totalVoucherPayments = BigDecimal("0.00"),
                    totalOtherPayments = BigDecimal("0.00"),
                    totalProductsSold = 23,
                    durationMinutes = 150  // 2h 30m
                ),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShiftStatusBannerClosedPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            ShiftStatusBanner(
                shift = null,
                onClick = {}
            )
        }
    }
}
