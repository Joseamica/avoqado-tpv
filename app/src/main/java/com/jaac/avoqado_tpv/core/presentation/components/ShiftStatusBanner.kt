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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
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
import com.jaac.avoqado_tpv.features.shift.presentation.CachedShiftInfo
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
 * - Orange indicator when offline (shows last known state)
 * - Shows staff name, start time, and current sales
 * - Tappable to navigate to full Shifts screen
 *
 * **States (Square/Toast Prevention Pattern):**
 * 1. **Online + Shift Open**: Green checkmark, staff name, sales
 * 2. **Online + No Shift**: Red warning, "Sin turno activo", tap to open
 * 3. **Offline + Cache**: Orange cloud-off, "Último estado conocido (hace X min)"
 * 4. **Offline + No Cache**: Orange question, "Estado desconocido"
 *
 * **Usage:**
 * ```kotlin
 * ShiftStatusBanner(
 *     shift = currentShift,
 *     isOffline = isOffline,
 *     cachedInfo = cachedShiftInfo,
 *     onClick = { navController.navigate(NavRoute.Shifts.route) }
 * )
 * ```
 *
 * @param shift Current active shift (null if no shift open or offline)
 * @param isOffline Whether device is offline
 * @param cachedInfo Cached shift info for offline display
 * @param onClick Callback when banner is tapped (navigate to Shifts screen)
 * @param modifier Modifier for customization
 */
@Composable
fun ShiftStatusBanner(
    shift: Shift?,
    isOffline: Boolean = false,
    cachedInfo: CachedShiftInfo? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine which UI to show based on online/offline and cached state
    when {
        // Offline with cached data: Show last known state
        isOffline && cachedInfo != null -> {
            OfflineCachedBanner(
                cachedInfo = cachedInfo,
                onClick = onClick,
                modifier = modifier
            )
        }
        // Offline without cached data: Show unknown state
        isOffline -> {
            OfflineUnknownBanner(
                onClick = onClick,
                modifier = modifier
            )
        }
        // Online: Show normal banner
        else -> {
            OnlineShiftBanner(
                shift = shift,
                onClick = onClick,
                modifier = modifier
            )
        }
    }
}

/**
 * Online Shift Banner - Normal operation
 *
 * Shows current shift status when connected to server.
 */
@Composable
private fun OnlineShiftBanner(
    shift: Shift?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isShiftOpen = shift != null && shift.status == ShiftStatus.OPEN

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = 12.dp,
            bottomEnd = 12.dp
        ),
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
                    tint = if (isShiftOpen) MaterialTheme.colorScheme.tertiary else Color(0xFFEB5757),
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Shift info
                Column {
                    if (isShiftOpen && shift != null) {
                        // Turno Abierto
                        Text(
                            text = "Turno Abierto",
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
                        color = MaterialTheme.colorScheme.tertiary  // ✅ Verde Avoqado del theme
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
 * Offline Cached Banner - Shows last known state
 *
 * Displays cached shift info with "Último estado conocido" when offline.
 * Square/Toast prevention pattern: operations are blocked when offline.
 */
@Composable
private fun OfflineCachedBanner(
    cachedInfo: CachedShiftInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = 12.dp,
            bottomEnd = 12.dp
        ),
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
            // Left: Offline indicator + Info
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Offline icon (orange, not red - it's a warning, not an error)
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Sin conexión",
                    tint = Color(0xFFE65100), // Darker orange (elegant)
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Cached shift info
                Column {
                    Text(
                        text = if (cachedInfo.isOpen) {
                            "Turno Abierto"
                        } else {
                            "Sin turno activo"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Show "Último estado conocido"
                    val minutesText = when {
                        cachedInfo.cachedMinutesAgo < 1 -> "ahora"
                        cachedInfo.cachedMinutesAgo == 1 -> "hace 1 min"
                        cachedInfo.cachedMinutesAgo < 60 -> "hace ${cachedInfo.cachedMinutesAgo} min"
                        else -> {
                            val hours = cachedInfo.cachedMinutesAgo / 60
                            if (hours == 1) "hace 1 hora" else "hace $hours horas"
                        }
                    }

                    Text(
                        text = "Último estado conocido ($minutesText)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100) // Orange to match icon
                    )
                }
            }

            // Right: Offline badge
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "Offline",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color(0xFFE65100)
                )
            }
        }
    }
}

/**
 * Offline Unknown Banner - No cached data available
 *
 * Shows when device is offline and no cached shift data exists.
 * This typically happens on first launch without network.
 */
@Composable
private fun OfflineUnknownBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = 12.dp,
            bottomEnd = 12.dp
        ),
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
            // Left: Unknown state indicator + Info
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Question mark icon (orange)
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = "Estado desconocido",
                    tint = Color(0xFFE65100), // Darker orange
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Unknown state info
                Column {
                    Text(
                        text = "Estado desconocido",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Sin conexión al servidor",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100)
                    )
                }
            }

            // Right: Offline badge
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "Offline",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color(0xFFE65100)
                )
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

@Preview(showBackground = true)
@Composable
private fun ShiftStatusBannerOfflineCachedPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            ShiftStatusBanner(
                shift = null,
                isOffline = true,
                cachedInfo = CachedShiftInfo(
                    isOpen = true,
                    staffName = "Juan Pérez",
                    cachedMinutesAgo = 5
                ),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShiftStatusBannerOfflineUnknownPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            ShiftStatusBanner(
                shift = null,
                isOffline = true,
                cachedInfo = null,
                onClick = {}
            )
        }
    }
}
