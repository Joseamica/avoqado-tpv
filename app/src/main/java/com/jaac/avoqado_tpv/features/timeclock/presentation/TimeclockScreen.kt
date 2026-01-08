package com.jaac.avoqado_tpv.features.timeclock.presentation

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
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
import java.util.Locale

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

    val outputDirectory = remember {
        File(context.cacheDir, "clockin_photos").apply { mkdirs() }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TimeclockEvent.NavigateToLogin -> onAutoLogin()
                else -> {}
            }
        }
    }

    when (val currentState = state) {
        is TimeclockState.CapturingPhoto -> {
            CameraPreviewScreen(
                onPhotoCaptured = viewModel::onPhotoCaptured,
                onClose = viewModel::cancelPhotoCapture,
                outputDirectory = outputDirectory
            )
            return
        }
        else -> { /* Continue */ }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { }, // Title is inside the content for cleaner look
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
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
                is TimeclockState.Loading -> AvoqadoLoadingOverlay("Cargando...")
                is TimeclockState.Processing -> {
                    // Show last known state with overlay
                    PulseContent(
                        state = TimeclockState.Ready(
                            staffId = "",
                            staffName = "",
                            currentEntry = null,
                            recentEntries = emptyList(),
                            totalHoursToday = BigDecimal.ZERO
                        ),
                        onClockIn = {}, onClockOut = {}, onStartBreak = {}, onEndBreak = {}, onDone = {}
                    )
                    AvoqadoLoadingOverlay(currentState.message)
                }
                is TimeclockState.Ready -> {
                    PulseContent(
                        state = currentState,
                        onClockIn = viewModel::clockIn,
                        onClockOut = viewModel::clockOut,
                        onStartBreak = viewModel::startBreak,
                        onEndBreak = viewModel::endBreak,
                        onDone = viewModel::navigateToLogin
                    )
                }
                is TimeclockState.RequiresPhoto -> {
                    ClockInPhotoPrompt(
                        staffName = currentState.staffName,
                        canSkip = currentState.canSkip,
                        onTakePhoto = viewModel::startPhotoCapture,
                        onSkip = viewModel::skipPhoto,
                        onCancel = viewModel::cancelPhotoCapture
                    )
                }
                is TimeclockState.PhotoPreview -> {
                    PhotoConfirmationScreen(
                        staffName = currentState.staffName,
                        localPath = currentState.localPath,
                        isClockOut = currentState.isClockOut,
                        onConfirm = viewModel::confirmPhoto,
                        onRetake = viewModel::retakePhoto,
                        onCancel = viewModel::cancelPhotoCapture
                    )
                }
                is TimeclockState.UploadingPhoto -> {
                    PhotoUploadProgress(currentState.progress)
                }
                is TimeclockState.Error -> ErrorContent(currentState.message, viewModel::retry, onNavigateBack)
                is TimeclockState.InvalidPin -> ErrorContent(currentState.message, null, onNavigateBack)
                else -> {}
            }
        }
    }
}

/**
 * Avoqado Pulse UI Design
 * Focused on "Immersive Status" and "Thumb Ergonomics".
 */
@Composable
private fun PulseContent(
    state: TimeclockState.Ready,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onStartBreak: () -> Unit,
    onEndBreak: () -> Unit,
    onDone: () -> Unit
) {
    val status = state.currentEntry?.status ?: TimeEntryStatus.CLOCKED_OUT
    
    // Semantic Colors based on Status
    val statusColor by animateColorAsState(
        targetValue = when (status) {
            TimeEntryStatus.CLOCKED_IN -> MaterialTheme.colorScheme.primary
            TimeEntryStatus.ON_BREAK -> Color(0xFFFF9800) // Amber
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "statusColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when (status) {
            TimeEntryStatus.CLOCKED_IN -> MaterialTheme.colorScheme.onPrimary
            TimeEntryStatus.ON_BREAK -> Color.Black
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "contentColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()) // Enable scrolling for safety
            .padding(horizontal = 16.dp, vertical = 8.dp), // Reduce padding
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp) // Consistent spacing
    ) {
        // 1. Header Section (Staff Info)
        StaffHeader(state.staffName, state.totalHoursToday)

        // 2. Hero Section (The Pulse Card) - No weight, let it size to content
        PulseStatusCard(
            status = status,
            currentEntry = state.currentEntry,
            backgroundColor = statusColor,
            contentColor = contentColor
        )

        Spacer(modifier = Modifier.weight(1f)) // Push actions to bottom if space permits

        // 3. Thumb Zone (Actions)
        ThumbZoneActions(
            status = status,
            onClockIn = onClockIn,
            onClockOut = onClockOut,
            onStartBreak = onStartBreak,
            onEndBreak = onEndBreak,
            onDone = onDone
        )
    }
}

@Composable
private fun StaffHeader(name: String, hoursToday: BigDecimal) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = name.split(" ").firstOrNull() ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (hoursToday > BigDecimal.ZERO) {
                Text(
                    text = "${hoursToday.setScale(2)}h hoy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun PulseStatusCard(
    status: TimeEntryStatus,
    currentEntry: TimeEntry?,
    backgroundColor: Color,
    contentColor: Color
) {
    // 2. Breathing Animation Setup
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status != TimeEntryStatus.CLOCKED_OUT) 1.1f else 1f, // Only breathe if active
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconScale"
    )

    // 3. Visual Depth (Gradient)
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            backgroundColor,
            backgroundColor.copy(alpha = 0.8f) // Slightly darker/transparent at bottom
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 200.dp),
        shape = RoundedCornerShape(24.dp),
        // Use transparent container to allow Brush background
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp) // Higher elevation for depth
    ) {
        // Box needed to apply Brush background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Icon Badge with Breathing Animation
                Surface(
                    shape = CircleShape,
                    color = contentColor.copy(alpha = 0.2f),
                    modifier = Modifier
                        .size(48.dp)
                        .scale(scale) // Apply breathing effect
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (status) {
                                TimeEntryStatus.CLOCKED_IN -> Icons.Default.Work
                                TimeEntryStatus.ON_BREAK -> Icons.Default.Coffee
                                else -> Icons.Default.Schedule
                            },
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = when (status) {
                        TimeEntryStatus.CLOCKED_IN -> "TRABAJANDO"
                        TimeEntryStatus.ON_BREAK -> "EN DESCANSO"
                        else -> "FUERA DE TURNO"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = contentColor.copy(alpha = 0.9f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (status == TimeEntryStatus.CLOCKED_OUT) {
                    RealtimeClock(color = contentColor)
                } else {
                    if (currentEntry != null) {
                        ElapsedTimeHero(startTime = currentEntry.clockInTime, color = contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun RealtimeClock(color: Color) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(1000)
        }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    
    // 1. Fix Jittery Timer (Monospace)
    Text(
        text = currentTime.format(formatter),
        style = MaterialTheme.typography.displayMedium.copy(
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Monospace, // STABILITY
            letterSpacing = (-2).sp // Tighten monospace gaps
        ),
        color = color
    )
    Text(
        text = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM", Locale("es", "MX"))),
        style = MaterialTheme.typography.bodyLarge,
        color = color.copy(alpha = 0.8f)
    )
}

@Composable
private fun ElapsedTimeHero(startTime: LocalDateTime, color: Color) {
    var duration by remember { mutableStateOf(Duration.ZERO) }
    LaunchedEffect(startTime) {
        while (true) {
            duration = Duration.between(startTime, LocalDateTime.now())
            delay(1000)
        }
    }
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    val seconds = duration.seconds % 60
    
    // 1. Fix Jittery Timer (Monospace)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = String.format("%02d:%02d", hours, minutes),
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = 56.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace, // STABILITY
                letterSpacing = (-2).sp
            ),
            color = color
        )
        Text(
            text = String.format(":%02d", seconds),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace // STABILITY
            ),
            color = color.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun ThumbZoneActions(
    status: TimeEntryStatus,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onStartBreak: () -> Unit,
    onEndBreak: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        when (status) {
            TimeEntryStatus.CLOCKED_OUT -> {
                PrimaryActionButton(
                    text = "ENTRADA",
                    icon = Icons.Default.Login,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onClockIn,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeEntryStatus.CLOCKED_IN -> {
                // Stacked vertical buttons to prevent text wrapping
                SecondaryActionButton(
                    text = "DESCANSO",
                    icon = Icons.Default.Coffee,
                    onClick = onStartBreak,
                    modifier = Modifier.fillMaxWidth()
                )
                
                PrimaryActionButton(
                    text = "SALIDA",
                    icon = Icons.Default.Logout,
                    color = MaterialTheme.colorScheme.error,
                    onClick = onClockOut,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            TimeEntryStatus.ON_BREAK -> {
                SecondaryActionButton(
                    text = "FIN DESCANSO",
                    icon = Icons.Default.PlayArrow,
                    onClick = onEndBreak,
                    modifier = Modifier.fillMaxWidth()
                )
                
                PrimaryActionButton(
                    text = "SALIDA",
                    icon = Icons.Default.Logout,
                    color = MaterialTheme.colorScheme.error,
                    onClick = onClockOut,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PrimaryActionButton(
                    text = "ENTRADA",
                    icon = Icons.Default.Login,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onClockIn,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("LISTO", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp), // Slightly reduced height
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// Reuse existing helper components for Photo and Error states...
@Composable
private fun PhotoUploadProgress(progress: Float) { /* ... keep existing ... */ }

@Composable
private fun ClockInPhotoPrompt(
    staffName: String,
    canSkip: Boolean,
    onTakePhoto: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit
) {
    // Dialog-style card centered on screen
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon with background circle
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Title
                Text(
                    text = "Foto de Verificación",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                // Staff name chip
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = staffName,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                // Description
                Text(
                    text = "Se requiere una foto selfie para registrar tu entrada. También se guardará tu ubicación GPS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Primary action - Take Photo
                Button(
                    onClick = onTakePhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tomar Foto", fontWeight = FontWeight.Bold)
                }

                // Skip button - only for authorized roles
                if (canSkip) {
                    TextButton(
                        onClick = onSkip,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Omitir (Admin)", fontSize = 14.sp)
                    }
                }

                // Cancel button
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}

/**
 * Photo confirmation screen showing the captured photo before upload.
 * User can confirm to proceed or retake if the photo is not satisfactory.
 */
@Composable
private fun PhotoConfirmationScreen(
    staffName: String,
    localPath: String,
    isClockOut: Boolean,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    // Load the image from local path
    val imageBitmap = remember(localPath) {
        try {
            val file = java.io.File(localPath)
            if (file.exists()) {
                android.graphics.BitmapFactory.decodeFile(localPath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with title
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.8f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isClockOut) "Confirmar Foto de Salida" else "Confirmar Foto de Entrada",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = staffName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            // Photo preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap.asImageBitmap(),
                        contentDescription = "Foto capturada",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    // Fallback if image can't be loaded
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No se pudo cargar la foto",
                            color = Color.Gray
                        )
                    }
                }
            }

            // Action buttons
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Confirm button
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Confirmar y Continuar", fontWeight = FontWeight.Bold)
                    }

                    // Retake button
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Volver a Tomar")
                    }

                    // Cancel button
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: (() -> Unit)?, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (onRetry != null) Button(onClick = onRetry) { Text("Reintentar") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack) { Text("Volver") }
    }
}

@Preview(name = "Pulse - Working", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun PulseWorkingPreview() {
    AvoqadoTheme {
        PulseContent(
            state = TimeclockState.Ready(
                staffId = "1", staffName = "Fernando",
                currentEntry = TimeEntry(
                    "1", "1", "Fernando", "1",
                    LocalDateTime.now().minusHours(2).minusMinutes(15), null,
                    "Waiter", null, 0, TimeEntryStatus.CLOCKED_IN, null, emptyList()
                ),
                recentEntries = emptyList(), totalHoursToday = BigDecimal.ZERO
            ),
            {}, {}, {}, {}, {}
        )
    }
}
