package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Timeclock Status Card
 *
 * Shows attendance status on WelcomeScreen. Always visible when attendance
 * tracking is relevant (requireClockInToLogin or has active entry).
 *
 * States:
 * A) Not clocked in — tappable to navigate to TimeclockScreen for clock-in
 * B) Clocked in (CLOCKED_IN) — shows real-time elapsed timer, expandable detail panel
 * C) Loading — shimmer placeholder
 *
 * @param currentEntry Current active time entry (null = not clocked in)
 * @param isLoading Whether attendance data is still loading
 * @param onClockIn Navigate to TimeclockScreen for clock-in
 * @param onClockOut Navigate to TimeclockScreen for clock-out
 * @param onLogout Logout callback
 * @param modifier Modifier
 */
@Composable
fun TimeclockStatusCard(
    currentEntry: TimeEntry?,
    isLoading: Boolean,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        // Simple loading placeholder
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Cargando asistencia...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        return
    }

    val isClockedIn = currentEntry?.status == TimeEntryStatus.CLOCKED_IN

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isClockedIn)
                MaterialTheme.avoqadoColors.statusSuccess.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Main row — tappable: clock-in when not clocked in, clock-out when clocked in
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isClockedIn) {
                            onClockOut()
                        } else {
                            onClockIn()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isClockedIn) {
                    // Green dot indicator
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.avoqadoColors.statusSuccess,
                        modifier = Modifier.size(10.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(10.dp))

                    // Status label
                    Text(
                        text = "TRABAJANDO",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.avoqadoColors.statusSuccess,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Real-time elapsed timer
                    ElapsedTimer(startTime = currentEntry!!.clockInTime)
                } else {
                    // Not clocked in
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Sin registro de entrada",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Toca para registrar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Clock-in time + "Registrar Salida" link (when clocked in)
            if (isClockedIn && currentEntry != null) {
                val formatter = remember { DateTimeFormatter.ofPattern("h:mm a") }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Entrada: ${currentEntry.clockInTime.format(formatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Registrar Salida",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onClockOut() }
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Real-time elapsed timer that updates every second.
 * Shows format HH:MM:SS in monospace font.
 */
@Composable
private fun ElapsedTimer(startTime: LocalDateTime) {
    val venueZone = remember { ZoneId.of("America/Mexico_City") }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(startTime) {
        while (true) {
            val now = LocalDateTime.now(venueZone)
            val duration = Duration.between(startTime, now)
            elapsedSeconds = duration.seconds.coerceAtLeast(0)
            delay(1000)
        }
    }

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    Text(
        text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
        style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.avoqadoColors.statusSuccess
    )
}
