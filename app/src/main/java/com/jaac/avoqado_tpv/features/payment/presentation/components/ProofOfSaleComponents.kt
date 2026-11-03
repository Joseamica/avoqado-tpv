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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.features.verification.presentation.NumberCaptureController
import com.jaac.avoqado_tpv.features.verification.presentation.NumberKind
import com.jaac.avoqado_tpv.features.verification.presentation.components.DuplicatePromptDialog
import com.jaac.avoqado_tpv.features.verification.presentation.components.LinkingNumberField
import com.jaac.avoqado_tpv.features.verification.presentation.components.NOT_CHECKING
import com.jaac.avoqado_tpv.features.verification.presentation.components.canConfirmLinkingNumber
import com.jaac.avoqado_tpv.features.verification.presentation.components.linkingNumberConfirmText
import com.jaac.avoqado_tpv.features.verification.presentation.components.linkingNumberInput
import com.jaac.avoqado_tpv.features.verification.presentation.components.submitLinkingNumber

/**
 * Proof-of-sale (post-payment, non-blocking) photo capture UI.
 *
 * `ProofOfSalePhotoSection` / `ProofOfSalePlaceholder` are a byte-for-byte duplicate of
 * Blumon's (private to `PaymentScreen.kt`), extracted here so the AngelPay success screen
 * reuses the exact same UX. If you change one, check whether the other needs the same
 * change. [ProofOfSalePhotoPreviewDialog] ya NO es copia: desde C7 (campo del número) es
 * la única vista previa de la foto y la usan las cuatro pantallas.
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
 * La vista previa de la foto de comprobante, con «Confirmar» / «Retomar». Es la ÚNICA de la app: la usan el cobro de la
 * PAX y el de la Nexgo, «Pendientes» y la corrección (antes eran cuatro copias).
 *
 * Con [numberLabel] (interruptor `features.linkingNumber` encendido) pide el número debajo de la foto y «Confirmar» sólo
 * se habilita con 9 o 10 dígitos (sin número no se sube la foto, Isaac 2-nov); con señal pregunta por repetido antes de
 * seguir ([numberCapture], con el [verificationId] de la venta para que no choque consigo misma). [onConfirm] recibe el
 * número, o null si no hay campo o si está [numberLocked] (lo corrigió el back-office: el servidor no lo pisa).
 */
@Composable
fun ProofOfSalePhotoPreviewDialog(
    photoPath: String,
    onConfirm: (number: String?) -> Unit,
    onRetake: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Vista Previa",
    confirmText: String = "Confirmar",
    retakeText: String = "Retomar",
    numberLabel: String? = null,
    initialNumber: String? = null,
    numberLocked: Boolean = false,
    numberKind: NumberKind = NumberKind.LINKING,
    verificationId: String? = null,
    numberCapture: NumberCaptureController? = null,
    /** Cada cambio del campo, para guardar el borrador de la ranura: «Retomar» no obliga a escribirlo otra vez. */
    onNumberChange: (String) -> Unit = {},
) {
    val withNumberField = numberLabel != null
    var number by remember(photoPath) { mutableStateOf(linkingNumberInput("", initialNumber.orEmpty())) }
    val scope = rememberCoroutineScope()
    val checking by (numberCapture?.checking ?: NOT_CHECKING).collectAsStateWithLifecycle()
    val confirmEnabled = (numberLabel == null || canConfirmLinkingNumber(number, numberLocked)) && !checking
    Dialog(
        onDismissRequest = onDismiss,
        properties = proofPreviewDialogProperties(withNumberField),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (withNumberField) Modifier.safeDrawingPadding() else Modifier),
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
                        text = title,
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

                numberLabel?.let { label ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        LinkingNumberField(
                            label = label,
                            value = number,
                            onValueChange = {
                                number = it
                                onNumberChange(it)
                            },
                            locked = numberLocked,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
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
                        onClick = {
                            if (numberLabel == null || numberLocked) {
                                onConfirm(null)
                            } else {
                                scope.submitLinkingNumber(number, numberKind, verificationId, numberCapture, onConfirm)
                            }
                        },
                        enabled = confirmEnabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(linkingNumberConfirmText(confirmText, checking))
                    }
                }
            }
        }
        // Dentro del diálogo: el aviso de repetido queda encima de la foto, y «Corregir» regresa a este campo.
        DuplicatePromptDialog(numberCapture)
    }
}

/**
 * La ventana de la vista previa. Sin campo (interruptor apagado, o sólo ver la foto) es EXACTAMENTE la de antes de C7.
 * Con el campo va sin decor, para que el teclado encoja la foto (`safeDrawingPadding`) en vez de tapar el número.
 */
fun proofPreviewDialogProperties(withNumberField: Boolean): DialogProperties =
    if (withNumberField) {
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    } else {
        DialogProperties(usePlatformDefaultWidth = false)
    }
