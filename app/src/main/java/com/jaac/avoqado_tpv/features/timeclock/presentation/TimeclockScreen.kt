package com.jaac.avoqado_tpv.features.timeclock.presentation

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen
import kotlinx.coroutines.delay
import java.io.File
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Timeclock Screen
 *
 * Allows employees to clock in/out and manage breaks.
 * Modern UI design with real-time clock display.
 *
 * @param onNavigateBack Navigate back to login screen (back button)
 * @param onNavigateToLogin Navigate to login screen without auto-login (error states)
 * @param onAutoLogin Perform automatic login after timeclock action (Done button)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeclockScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onAutoLogin: () -> Unit,
    viewModel: TimeclockViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Output directory for clock-in photos (cache directory for temporary storage)
    val outputDirectory = remember {
        File(context.cacheDir, "clockin_photos").apply { mkdirs() }
    }

    // Collect one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TimeclockEvent.NavigateToLogin -> onAutoLogin()  // Done button triggers auto-login
                else -> {} // Handle other events with snackbar if needed
            }
        }
    }

    // Handle camera states (full-screen, no scaffold)
    when (val currentState = state) {
        is TimeclockState.CapturingPhoto -> {
            CameraPreviewScreen(
                onPhotoCaptured = viewModel::onPhotoCaptured,
                onClose = viewModel::cancelPhotoCapture,
                outputDirectory = outputDirectory
            )
            return
        }
        else -> { /* Continue to normal scaffold UI */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeclock") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is TimeclockState.Loading -> {
                    AvoqadoLoadingOverlay(message = "Verificando...")
                }

                is TimeclockState.Processing -> {
                    TimeclockReadyContent(
                        state = TimeclockState.Ready(
                            staffId = "",
                            staffName = "",
                            currentEntry = null,
                            recentEntries = emptyList(),
                            totalHoursToday = BigDecimal.ZERO
                        ),
                        onClockIn = {},
                        onClockOut = {},
                        onStartBreak = {},
                        onEndBreak = {},
                        onDone = {}
                    )
                    AvoqadoLoadingOverlay(message = currentState.message)
                }

                is TimeclockState.Ready -> {
                    TimeclockReadyContent(
                        state = currentState,
                        onClockIn = viewModel::clockIn,
                        onClockOut = viewModel::clockOut,
                        onStartBreak = viewModel::startBreak,
                        onEndBreak = viewModel::endBreak,
                        onDone = viewModel::navigateToLogin
                    )
                }

                // Photo verification states (anti-fraud feature)
                is TimeclockState.RequiresPhoto -> {
                    ClockInPhotoPrompt(
                        staffName = currentState.staffName,
                        canSkip = currentState.canSkip,
                        onTakePhoto = viewModel::startPhotoCapture,
                        onSkip = viewModel::skipPhoto,
                        onCancel = viewModel::cancelPhotoCapture
                    )
                }

                is TimeclockState.CapturingPhoto -> {
                    // Handled above (full-screen camera)
                }

                is TimeclockState.UploadingPhoto -> {
                    PhotoUploadProgress(progress = currentState.progress)
                }

                is TimeclockState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = viewModel::retry,
                        onBack = onNavigateBack
                    )
                }

                is TimeclockState.InvalidPin -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = null,
                        onBack = onNavigateBack
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeclockReadyContent(
    state: TimeclockState.Ready,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onStartBreak: () -> Unit,
    onEndBreak: () -> Unit,
    onDone: () -> Unit
) {
    ResponsiveScaffold(
        scrollable = true,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val sizes = LocalResponsiveSizes.current

        // Current time display (updates every second)
        CurrentTimeDisplay()

        Spacer(modifier = Modifier.height(sizes.spacingMedium))

        // Staff info card
        StaffStatusCard(
            staffName = state.staffName,
            currentEntry = state.currentEntry,
            totalHoursToday = state.totalHoursToday
        )

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Action buttons
        ActionButtons(
            isClockedIn = state.isClockedIn,
            isOnBreak = state.isOnBreak,
            onClockIn = onClockIn,
            onClockOut = onClockOut,
            onStartBreak = onStartBreak,
            onEndBreak = onEndBreak,
            onDone = onDone
        )

        // Recent entries (if any)
        if (state.recentEntries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(sizes.spacingLarge))
            RecentEntriesSection(entries = state.recentEntries)
        }
    }
}

@Composable
private fun CurrentTimeDisplay() {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }

    // Update time every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(1000)
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val secondsFormatter = remember { DateTimeFormatter.ofPattern(":ss") }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = currentTime.format(timeFormatter),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 72.sp,
                fontWeight = FontWeight.Light
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = currentTime.format(secondsFormatter),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Light
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun StaffStatusCard(
    staffName: String,
    currentEntry: TimeEntry?,
    totalHoursToday: BigDecimal
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Staff name with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = staffName.ifEmpty { "Empleado" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Current status
            if (currentEntry != null) {
                val statusText = when (currentEntry.status) {
                    TimeEntryStatus.CLOCKED_IN -> "Trabajando"
                    TimeEntryStatus.ON_BREAK -> "En descanso"
                    else -> "Registrado"
                }

                val statusColor = when (currentEntry.status) {
                    TimeEntryStatus.ON_BREAK -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Time since clock in
                ElapsedTimeDisplay(clockInTime = currentEntry.clockInTime)

                if (currentEntry.breakMinutes > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Descanso: ${currentEntry.breakMinutes} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "No registrado",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (totalHoursToday > BigDecimal.ZERO) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Horas hoy: ${totalHoursToday.setScale(2)} h",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ElapsedTimeDisplay(clockInTime: LocalDateTime) {
    var elapsedText by remember { mutableStateOf("") }

    LaunchedEffect(clockInTime) {
        while (true) {
            val now = LocalDateTime.now()
            val duration = Duration.between(clockInTime, now)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60

            elapsedText = if (hours > 0) {
                "${hours}h ${minutes}m"
            } else {
                "${minutes} min"
            }
            delay(60000) // Update every minute
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Desde ${clockInTime.format(timeFormatter)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = elapsedText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ActionButtons(
    isClockedIn: Boolean,
    isOnBreak: Boolean,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onStartBreak: () -> Unit,
    onEndBreak: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isClockedIn) {
            // Not clocked in: Show Clock In + Done
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onClockIn,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Login,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clock In")
                }

                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Done")
                }
            }
        } else if (isOnBreak) {
            // On break: Show End Break + Clock Out + Done
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onEndBreak,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("End Break")
                }

                OutlinedButton(
                    onClick = onClockOut,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clock Out")
                }
            }

            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        } else {
            // Clocked in (working): Show Start Break + Clock Out + Done
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onStartBreak,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Coffee,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Break")
                }

                Button(
                    onClick = onClockOut,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clock Out")
                }
            }

            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun RecentEntriesSection(entries: List<TimeEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Historial de hoy",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        entries.take(5).forEach { entry ->
            RecentEntryItem(entry = entry)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RecentEntryItem(entry: TimeEntry) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.clockInTime.format(timeFormatter),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "→",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = entry.clockOutTime?.format(timeFormatter) ?: "En curso",
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.clockOutTime == null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            if (entry.totalHours != null) {
                Text(
                    text = "${entry.totalHours}h",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Photo prompt screen for clock-in verification.
 *
 * Shown when venue requires clock-in photo (anti-fraud feature).
 * Allows user to take photo or skip (if admin/manager).
 */
@Composable
private fun ClockInPhotoPrompt(
    staffName: String,
    canSkip: Boolean,
    onTakePhoto: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Camera icon
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = "Foto Requerida",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Staff name
        Text(
            text = staffName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Instructions
        Text(
            text = "Para verificar tu entrada, toma una foto con la cámara del dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Take photo button
        Button(
            onClick = onTakePhoto,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Tomar Foto")
        }

        // Skip button (only for admins)
        if (canSkip) {
            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Saltar (Solo Admin)",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancelar")
        }
    }
}

/**
 * Upload progress indicator for clock-in photo.
 *
 * Shows circular progress while photo uploads to Firebase Storage.
 */
@Composable
private fun PhotoUploadProgress(progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Progress indicator
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(64.dp),
            strokeWidth = 6.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Progress text
        Text(
            text = "Subiendo foto...",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text("Reintentar")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedButton(onClick = onBack) {
            Text("Volver")
        }
    }
}

// ========== Previews ==========

@Preview(
    name = "Timeclock - Not Clocked In",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun TimeclockNotClockedInPreview() {
    AvoqadoTheme(darkTheme = true) {
        TimeclockReadyContent(
            state = TimeclockState.Ready(
                staffId = "staff_1",
                staffName = "Fernando Arcieri",
                currentEntry = null,
                recentEntries = emptyList(),
                totalHoursToday = BigDecimal("4.5")
            ),
            onClockIn = {},
            onClockOut = {},
            onStartBreak = {},
            onEndBreak = {},
            onDone = {}
        )
    }
}

@Preview(
    name = "Timeclock - Clocked In",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun TimeclockClockedInPreview() {
    AvoqadoTheme(darkTheme = true) {
        TimeclockReadyContent(
            state = TimeclockState.Ready(
                staffId = "staff_1",
                staffName = "Fernando Arcieri",
                currentEntry = TimeEntry(
                    id = "entry_1",
                    staffId = "staff_1",
                    staffName = "Fernando Arcieri",
                    venueId = "venue_1",
                    clockInTime = LocalDateTime.now().minusHours(2),
                    clockOutTime = null,
                    jobRole = "Mesero",
                    totalHours = null,
                    breakMinutes = 15,
                    status = TimeEntryStatus.CLOCKED_IN,
                    checkInPhotoUrl = null,
                    breaks = emptyList()
                ),
                recentEntries = emptyList(),
                totalHoursToday = BigDecimal.ZERO
            ),
            onClockIn = {},
            onClockOut = {},
            onStartBreak = {},
            onEndBreak = {},
            onDone = {}
        )
    }
}

@Preview(
    name = "Timeclock - On Break",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun TimeclockOnBreakPreview() {
    AvoqadoTheme(darkTheme = true) {
        TimeclockReadyContent(
            state = TimeclockState.Ready(
                staffId = "staff_1",
                staffName = "Fernando Arcieri",
                currentEntry = TimeEntry(
                    id = "entry_1",
                    staffId = "staff_1",
                    staffName = "Fernando Arcieri",
                    venueId = "venue_1",
                    clockInTime = LocalDateTime.now().minusHours(3),
                    clockOutTime = null,
                    jobRole = "Mesero",
                    totalHours = null,
                    breakMinutes = 0,
                    status = TimeEntryStatus.ON_BREAK,
                    checkInPhotoUrl = null,
                    breaks = emptyList()
                ),
                recentEntries = emptyList(),
                totalHoursToday = BigDecimal.ZERO
            ),
            onClockIn = {},
            onClockOut = {},
            onStartBreak = {},
            onEndBreak = {},
            onDone = {}
        )
    }
}
