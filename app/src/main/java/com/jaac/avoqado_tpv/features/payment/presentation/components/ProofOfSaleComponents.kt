package com.jaac.avoqado_tpv.features.payment.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Proof-of-sale (post-payment, non-blocking) photo capture UI.
 *
 * This is a byte-for-byte duplicate of Blumon's `ProofOfSalePhotoSection` /
 * `ProofOfSalePlaceholder` / `ProofOfSalePhotoPreviewDialog` (private to
 * `PaymentScreen.kt`) — extracted here so the AngelPay success screen can reuse the
 * exact same UX without modifying any Blumon file. If you change one, check whether
 * the other needs the same change; they're intentionally not shared to keep Blumon
 * completely untouched.
 */
@Composable
fun ProofOfSalePhotoSection(
    isPortabilidad: Boolean,
    lineaPhotoPath: String?,
    portabilidadPhotoPath: String?,
    isUploading: Boolean,
    isComplete: Boolean,
    onTapLinea: () -> Unit,
    onTapPortabilidad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showWarning = !isComplete
    if (!isPortabilidad) {
        ProofOfSalePlaceholder(
            label = "Vinculacion",
            photoPath = lineaPhotoPath,
            showWarning = showWarning && lineaPhotoPath == null,
            onClick = onTapLinea,
            modifier = modifier.size(width = 160.dp, height = 110.dp),
        )
    } else {
        Row(
            modifier = modifier.width(220.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProofOfSalePlaceholder(
                label = "Vinculacion",
                photoPath = lineaPhotoPath,
                showWarning = showWarning && lineaPhotoPath == null,
                onClick = onTapLinea,
                modifier = Modifier.weight(1f).height(96.dp),
            )
            ProofOfSalePlaceholder(
                label = "Portabilidad",
                photoPath = portabilidadPhotoPath,
                showWarning = showWarning && portabilidadPhotoPath == null,
                onClick = onTapPortabilidad,
                modifier = Modifier.weight(1f).height(96.dp),
            )
        }
    }
}

/**
 * Single proof-of-sale photo placeholder. Dashed border when empty with camera icon
 * + label. Red border when warning. Thumbnail when photo taken.
 */
@Composable
fun ProofOfSalePlaceholder(
    label: String,
    photoPath: String?,
    showWarning: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val dashedBorderColor = if (showWarning) errorColor else mutedColor
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .then(
                if (photoPath == null) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = dashedBorderColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                                    0f,
                                ),
                            ),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                        )
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (photoPath != null) {
            coil.compose.AsyncImage(
                model = java.io.File(photoPath),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = if (showWarning) errorColor.copy(alpha = 0.8f) else mutedColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (showWarning) errorColor.copy(alpha = 0.9f) else mutedColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (showWarning) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Toma foto",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = errorColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Photo preview dialog for proof-of-sale confirmation. Shows captured photo with
 * Confirm/Retake buttons before uploading.
 */
@Composable
fun ProofOfSalePhotoPreviewDialog(
    photoPath: String,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Confirmar",
    retakeText: String = "Retomar",
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Vista Previa",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    coil.compose.AsyncImage(
                        model = java.io.File(photoPath),
                        contentDescription = "Preview de foto",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(retakeText)
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
