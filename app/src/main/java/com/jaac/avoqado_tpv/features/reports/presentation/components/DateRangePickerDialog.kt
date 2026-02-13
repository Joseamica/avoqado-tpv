package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Lightweight Custom Date Range Picker (Optimized for 1GB RAM)
 *
 * Custom calendar implementation to replace heavy Material3 DateRangePicker.
 * Uses minimal memory and renders fast.
 *
 * **Performance Optimizations:**
 * - LazyVerticalGrid for efficient rendering
 * - Only shows ONE month at a time
 * - No animations (instant selection)
 * - Memoized date calculations
 * - Stable keys for grid items
 *
 * **UX Pattern (Airplane-style):**
 * 1. User clicks start date → Highlighted
 * 2. User clicks end date → Range highlighted
 * 3. User clicks "Confirmar" → Callback fires
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback with selected start and end dates
 */
@Composable
fun DateRangePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (startDate: Instant, endDate: Instant) -> Unit,
    zoneId: ZoneId = ZoneId.of("America/Mexico_City")
) {
    // Current month being displayed
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }

    // Selected date range
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }

    // Derived state for validation
    val canConfirm by remember {
        derivedStateOf { startDate != null && endDate != null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Seleccionar fechas")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Month navigation header
                MonthNavigationHeader(
                    currentMonth = currentMonth,
                    onPreviousMonth = {
                        currentMonth = currentMonth.minusMonths(1)
                    },
                    onNextMonth = {
                        // Don't allow navigating to future months
                        if (currentMonth < YearMonth.now()) {
                            currentMonth = currentMonth.plusMonths(1)
                        }
                    },
                    canGoNext = currentMonth < YearMonth.now()
                )

                // Week day headers (D L M M J V S)
                WeekDayHeaders()

                // Calendar grid
                CalendarGrid(
                    currentMonth = currentMonth,
                    startDate = startDate,
                    endDate = endDate,
                    onDateClick = { clickedDate ->
                        when {
                            startDate == null -> {
                                // First click: Set start date
                                startDate = clickedDate
                                endDate = null
                            }
                            endDate == null -> {
                                // Second click: Set end date
                                if (clickedDate.isBefore(startDate)) {
                                    // If clicked before start, swap
                                    endDate = startDate
                                    startDate = clickedDate
                                } else {
                                    endDate = clickedDate
                                }
                            }
                            else -> {
                                // Range already selected: Reset and start over
                                startDate = clickedDate
                                endDate = null
                            }
                        }
                    }
                )

                // Selected dates display
                SelectedDatesDisplay(
                    startDate = startDate,
                    endDate = endDate
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (startDate != null && endDate != null) {
                        val start = startDate!!.atStartOfDay(zoneId).toInstant()
                        val end = endDate!!.atTime(23, 59, 59).atZone(zoneId).toInstant()
                        onConfirm(start, end)
                    }
                },
                enabled = canConfirm
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/**
 * Month navigation header with previous/next buttons
 */
@Composable
private fun MonthNavigationHeader(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    canGoNext: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "Mes anterior"
            )
        }

        Text(
            text = "${currentMonth.month.getDisplayName(TextStyle.FULL, Locale("es", "ES"))} ${currentMonth.year}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        IconButton(
            onClick = onNextMonth,
            enabled = canGoNext
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Mes siguiente",
                tint = if (canGoNext) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                }
            )
        }
    }
}

/**
 * Week day headers (D L M M J V S)
 */
@Composable
private fun WeekDayHeaders() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        listOf("D", "L", "M", "M", "J", "V", "S").forEach { day ->
            Text(
                text = day,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Calendar grid with date cells
 */
@Composable
private fun CalendarGrid(
    currentMonth: YearMonth,
    startDate: LocalDate?,
    endDate: LocalDate?,
    onDateClick: (LocalDate) -> Unit
) {
    // Get all dates for current month (including empty cells for alignment)
    val dates = remember(currentMonth) {
        val firstDayOfMonth = currentMonth.atDay(1)
        val lastDayOfMonth = currentMonth.atEndOfMonth()

        // Get day of week for first day (0 = Sunday, 6 = Saturday)
        val firstDayOfWeek = (firstDayOfMonth.dayOfWeek.value % 7)

        // Create list with empty cells for alignment
        val datesList = mutableListOf<LocalDate?>()

        // Add empty cells before first day
        repeat(firstDayOfWeek) {
            datesList.add(null)
        }

        // Add all days of month
        var currentDate = firstDayOfMonth
        while (!currentDate.isAfter(lastDayOfMonth)) {
            datesList.add(currentDate)
            currentDate = currentDate.plusDays(1)
        }

        datesList
    }

    val today = remember { LocalDate.now() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp), // Fixed height for 6 rows max
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = dates,
            key = { index, date -> date?.toEpochDay() ?: "${currentMonth.year}-${currentMonth.monthValue}-empty-$index" }
        ) { _, date ->
            if (date != null) {
                DateCell(
                    date = date,
                    isSelected = date == startDate || date == endDate,
                    isInRange = startDate != null && endDate != null &&
                               !date.isBefore(startDate) && !date.isAfter(endDate),
                    isEnabled = !date.isAfter(today), // Disable future dates
                    onClick = { onDateClick(date) }
                )
            } else {
                // Empty cell for alignment
                Spacer(modifier = Modifier.aspectRatio(1f))
            }
        }
    }
}

/**
 * Individual date cell in calendar grid
 */
@Composable
private fun DateCell(
    date: LocalDate,
    isSelected: Boolean,
    isInRange: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isInRange -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val textColor = when {
        !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = isEnabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Display selected start and end dates
 */
@Composable
private fun SelectedDatesDisplay(
    startDate: LocalDate?,
    endDate: LocalDate?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (startDate != null) {
            Text(
                text = "Inicio: ${formatDate(startDate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        if (endDate != null) {
            Text(
                text = "Fin: ${formatDate(endDate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        if (startDate == null) {
            Text(
                text = "Selecciona fecha de inicio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        } else if (endDate == null) {
            Text(
                text = "Selecciona fecha de fin",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Format date for display (e.g., "15 Nov")
 */
private fun formatDate(date: LocalDate): String {
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale("es", "ES"))
    return "${date.dayOfMonth} $month"
}
