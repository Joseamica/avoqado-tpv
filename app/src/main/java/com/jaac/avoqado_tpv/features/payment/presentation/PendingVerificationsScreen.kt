package com.jaac.avoqado_tpv.features.payment.presentation

import android.widget.Toast
import com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.core.data.network.PendingVerificationDto
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ========== Constants ==========

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

// ========== Helper ==========

private fun formatDate(isoDate: String): String {
    return try {
        val instant = Instant.parse(isoDate)
        val venueZone = ZoneId.of("America/Mexico_City")
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("es", "MX"))
        instant.atZone(venueZone).format(formatter)
    } catch (_: Exception) {
        isoDate
    }
}

// ========== Main Screen ==========

@Composable
fun PendingVerificationsScreen(
    navController: NavController,
    viewModel: PendingVerificationsViewModel = hiltViewModel()
) {
    val verifications by viewModel.pendingVerifications.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val uploadingId by viewModel.uploadingId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(error) {
        error?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }

    // Camera state
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var selectedVerificationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPaymentId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var selectedReplaceIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var capturedPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }

    // In-app CameraX camera (has close button — PAX system camera doesn't)
    if (showCamera) {
        // Map label to display text: "Vinculacion" → "1. Vinculación", "Portabilidad" → "2. Portabilidad"
        val cameraLabel = when (selectedLabel) {
            "Vinculacion" -> "1. Vinculación"
            "Portabilidad" -> "2. Portabilidad"
            else -> null
        }

        CameraPreviewScreen(
            onPhotoCaptured = { photoPath ->
                showCamera = false
                capturedPhotoPath = photoPath // Show preview instead of uploading directly
            },
            onClose = {
                showCamera = false
                selectedReplaceIndex = null
            },
            outputDirectory = context.cacheDir,
            photoLabel = cameraLabel
        )
        return // Full-screen camera overlay — don't render the list behind it
    }

    // Photo preview dialog — confirm/retake before uploading
    capturedPhotoPath?.let { photoPath ->
        PhotoPreviewDialog(
            photoPath = photoPath,
            label = when (selectedLabel) {
                "Vinculacion" -> "1. Vinculación"
                "Portabilidad" -> "2. Portabilidad"
                else -> selectedLabel
            },
            onConfirm = {
                capturedPhotoPath = null
                val vId = selectedVerificationId
                val pId = selectedPaymentId
                if (vId != null && pId != null && photoPath.isNotEmpty()) {
                    viewModel.uploadPhoto(vId, pId, selectedLabel, photoPath, selectedReplaceIndex)
                }
                selectedReplaceIndex = null
            },
            onRetake = {
                capturedPhotoPath = null
                showCamera = true // Reopen camera
            },
            onDismiss = {
                capturedPhotoPath = null
                selectedReplaceIndex = null
            }
        )
    }

    // Block back navigation while uploading to prevent cancelling the coroutine
    val isUploading = uploadingId != null
    BackHandler(enabled = isUploading) {
        // Swallow back press while uploading — user must wait
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "En revisión por Administración",
                onNavigationClick = {
                    if (!isUploading) navController.popBackStack()
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && verifications.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                verifications.isEmpty() -> {
                    EmptyVerificationsState()
                }

                else -> {
                    AvoqadoPullToRefresh(
                        isRefreshing = isLoading,
                        onRefresh = { viewModel.loadPending() }
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = verifications,
                                key = { it.id }
                            ) { item ->
                                PendingVerificationItem(
                                    item = item,
                                    isUploading = uploadingId == item.id,
                                    isCameraActive = showCamera,
                                    onTakePhoto = { label, replaceIndex ->
                                        if (showCamera) return@PendingVerificationItem
                                        selectedVerificationId = item.id
                                        selectedPaymentId = item.paymentId
                                        selectedLabel = label
                                        selectedReplaceIndex = replaceIndex
                                        showCamera = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Upload overlay — blocks interaction and shows progress
            if (isUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = "Subiendo foto...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// ========== Verification Card ==========

@Composable
fun PendingVerificationItem(
    item: PendingVerificationDto,
    isUploading: Boolean,
    isCameraActive: Boolean = false,
    initiallyExpanded: Boolean = false,
    onTakePhoto: (label: String, replaceIndex: Int?) -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var previewPhotoUrl by remember { mutableStateOf<String?>(null) }

    val nonEmptyPhotos = item.photos.count { it.isNotEmpty() }
    val photosComplete = nonEmptyPhotos >= item.requiredPhotos
    val chipColor = if (photosComplete) {
        MaterialTheme.avoqadoColors.statusSuccess
    } else {
        MaterialTheme.avoqadoColors.statusWarning
    }

    // Full-screen photo preview dialog
    previewPhotoUrl?.let { url ->
        PhotoPreviewDialog(
            photoUrl = url,
            onDismiss = { previewPhotoUrl = null }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: Date + Amount + Order number
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (item.orderNumber != null) {
                        Text(
                            text = item.orderNumber,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = formatDate(item.date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = CurrencyFormatter.format(BigDecimal.valueOf(item.amount)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ICCID + Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.serialNumbers.isNotEmpty()) {
                    Text(
                        text = "SIM: ${item.serialNumbers.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhotoStatusChip(
                        current = nonEmptyPhotos,
                        required = item.requiredPhotos,
                        color = chipColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Contraer" else "Expandir",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded section: photo slots
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    // Upload progress indicator
                    if (isUploading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Subiendo foto...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Photo slots — fixed positions: index 0 = Vinculacion, index 1 = Portabilidad
                    // Empty strings represent unfilled slots (backend pads when photo uploaded out of order)
                    val lineaPhoto = item.photos.getOrNull(0)?.takeIf { it.isNotEmpty() }
                    val portabilidadPhoto = item.photos.getOrNull(1)?.takeIf { it.isNotEmpty() }

                    val canInteract = !isUploading && !isCameraActive

                    if (!item.isPortabilidad) {
                        // Single photo: wider placeholder
                        PhotoSlot(
                            label = "Vinculacion",
                            photoUrl = lineaPhoto,
                            enabled = canInteract,
                            onTakePhoto = {
                                // If photo exists at index 0 → replace; otherwise append
                                onTakePhoto("Vinculacion", if (lineaPhoto != null) 0 else null)
                            },
                            onPreview = { lineaPhoto?.let { previewPhotoUrl = it } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    } else {
                        // Two photos side by side
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PhotoSlot(
                                label = "Vinculacion",
                                photoUrl = lineaPhoto,
                                enabled = canInteract,
                                onTakePhoto = {
                                    onTakePhoto("Vinculacion", if (lineaPhoto != null) 0 else null)
                                },
                                onPreview = { lineaPhoto?.let { previewPhotoUrl = it } },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                            )
                            PhotoSlot(
                                label = "Portabilidad",
                                photoUrl = portabilidadPhoto,
                                enabled = canInteract,
                                onTakePhoto = {
                                    onTakePhoto("Portabilidad", if (portabilidadPhoto != null) 1 else null)
                                },
                                onPreview = { portabilidadPhoto?.let { previewPhotoUrl = it } },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========== Photo Slot ==========

/**
 * Visual photo placeholder — matches the dashed-border pattern from PaymentScreen.
 * - Empty: dashed border + camera icon + label (tap to capture)
 * - Filled: thumbnail with tap-to-preview and re-take overlay button
 */
@Composable
private fun PhotoSlot(
    label: String,
    photoUrl: String?,
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val warningColor = MaterialTheme.avoqadoColors.statusWarning

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) {
                if (photoUrl != null) onPreview() else onTakePhoto()
            }
            .then(
                if (photoUrl == null) {
                    // Dashed border for empty slot
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = warningColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                                    0f
                                )
                            ),
                            cornerRadius = CornerRadius(12.dp.toPx())
                        )
                    }
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (photoUrl != null) {
            // Filled: show photo thumbnail
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(photoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
            )

            // Re-take overlay button (bottom-right)
            if (enabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .clickable(onClick = onTakePhoto),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retomar foto",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                }
            }

            // Label overlay (bottom-left)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                    maxLines = 1
                )
            }
        } else {
            // Empty: dashed border + camera icon + label
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = label,
                    modifier = Modifier.size(28.dp),
                    tint = if (enabled) warningColor else mutedColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) warningColor else mutedColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Toca para foto",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (enabled) warningColor.copy(alpha = 0.7f) else mutedColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ========== Photo Preview Dialog ==========

@Composable
private fun PhotoPreviewDialog(
    photoUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(photoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Vista previa",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar",
                    tint = Color.White
                )
            }
        }
    }
}

// ========== Supporting Composables ==========

@Composable
private fun PhotoStatusChip(
    current: Int,
    required: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (current >= required) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color
                )
            }
            Text(
                text = "$current/$required fotos",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

@Composable
private fun EmptyVerificationsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = "No hay verificaciones pendientes",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ========== Photo Preview Dialog ==========

@Composable
private fun PhotoPreviewDialog(
    photoPath: String,
    label: String,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top bar with label and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
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
                    AsyncImage(
                        model = java.io.File(photoPath),
                        contentDescription = "Vista previa de foto",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                // Confirm / Retake buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retomar")
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Confirmar")
                    }
                }
            }
        }
    }
}

// ========== Previews ==========

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun PendingVerificationItemPreview() {
    AvoqadoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Portabilidad, 1 of 2 photos uploaded
                PendingVerificationItem(
                    item = PendingVerificationDto(
                        id = "1",
                        paymentId = "pay_001",
                        amount = 1250.0,
                        orderNumber = "SN00322",
                        date = "2026-03-10T14:30:00Z",
                        serialNumbers = listOf("8952140061234567890"),
                        isPortabilidad = true,
                        photos = listOf("https://example.com/photo1.jpg"),
                        requiredPhotos = 2
                    ),
                    isUploading = false,
                    onTakePhoto = { _, _ -> }
                )
                // Non-portabilidad, no photos
                PendingVerificationItem(
                    item = PendingVerificationDto(
                        id = "2",
                        paymentId = "pay_002",
                        amount = 499.0,
                        orderNumber = "SN00321",
                        date = "2026-03-09T10:15:00Z",
                        serialNumbers = listOf("8952140061234567892"),
                        isPortabilidad = false,
                        photos = emptyList(),
                        requiredPhotos = 1
                    ),
                    isUploading = false,
                    onTakePhoto = { _, _ -> }
                )
            }
        }
    }
}

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun EmptyVerificationsStatePreview() {
    AvoqadoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            EmptyVerificationsState()
        }
    }
}
