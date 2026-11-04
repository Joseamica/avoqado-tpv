package com.jaac.avoqado_tpv.features.payment.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.data.network.PendingVerificationDto
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.payment.presentation.components.ProofOfSalePhotoPreviewDialog
import com.jaac.avoqado_tpv.features.serialized_sale.presentation.RejectionReason
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.presentation.components.LinkingNumberDialog
import com.jaac.avoqado_tpv.features.verification.presentation.components.LinkingNumberDrafts
import com.jaac.avoqado_tpv.features.verification.presentation.numberKind
import com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

/** Map [VerificationDetailDto] to the shape [PendingVerificationItem] consumes. */
private fun VerificationDetailDto.toPendingDto(): PendingVerificationDto = PendingVerificationDto(
    id = id,
    paymentId = paymentId,
    amount = amount,
    orderNumber = orderNumber,
    date = date,
    serialNumbers = serialNumbers,
    isPortabilidad = isPortabilidad,
    photos = photos,
    requiredPhotos = requiredPhotos,
    linkingNumber = linkingNumber,
    portedNumber = portedNumber,
    linkingNumberLocked = linkingNumberLocked,
    portedNumberLocked = portedNumberLocked,
    alerts = alerts,
)

/**
 * Sale Correction screen — re-upload rejected documentation.
 *
 * Reached from "Mis Ventas" when the promoter taps a sale flagged
 * "Revisar documentación". Shows the back-office rejection reasons and lets
 * the promoter re-take the proof-of-sale photos on the SAME sale.
 */
@Composable
fun SaleCorrectionScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SaleCorrectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val linkingNumberEnabled by viewModel.linkingNumberEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }

    // Camera + photo-preview state
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var capturedPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    // «Corregir número»: la ranura cuyo número se está corrigiendo (sin foto).
    var correctingSlot by remember { mutableStateOf<ProofSlot?>(null) }
    // Lo escrito y no confirmado, por ranura: sobrevive «Retomar» (la cámara cierra la vista previa).
    val numberDrafts = remember { LinkingNumberDrafts() }

    fun displayLabel(label: String): String = when (label) {
        "Vinculacion" -> "1. Vinculación"
        "Portabilidad" -> "2. Portabilidad"
        else -> label
    }

    // Full-screen camera overlay
    if (showCamera) {
        CameraPreviewScreen(
            onPhotoCaptured = { photoPath ->
                showCamera = false
                capturedPhotoPath = photoPath
            },
            onClose = {
                showCamera = false
            },
            // cacheDir: la cola copia la foto a su carpeta; el barrido de C4 limpia la suya, no ésta.
            outputDirectory = context.cacheDir,
            photoLabel = displayLabel(selectedLabel).takeIf { selectedLabel.isNotEmpty() },
        )
        return
    }

    // Confirm / retake dialog before uploading (con el número de su ranura si el interruptor está encendido)
    capturedPhotoPath?.let { photoPath ->
        val slot = proofSlotOf(selectedLabel)
        val current = uiState.detail?.let { slotNumber(it.toPendingDto(), uiState.queued, slot) }
        ProofOfSalePhotoPreviewDialog(
            photoPath = photoPath,
            title = displayLabel(selectedLabel),
            numberLabel = slot.numberKind.label.takeIf { linkingNumberEnabled },
            initialNumber = numberDrafts.initial(slot.name, current?.number, locked = current?.locked == true),
            numberLocked = current?.locked == true,
            numberKind = slot.numberKind,
            verificationId = uiState.detail?.id,
            numberCapture = viewModel.numberCapture,
            saving = uiState.isUploading,
            onNumberChange = { numberDrafts.update(slot.name, it) },
            onConfirm = { number ->
                if (photoPath.isNotEmpty()) {
                    // Se encola (archivo y fila antes de la red); la sube PaymentSyncWorker. Sin señal, igual.
                    // La ventana y el borrador se van sólo si quedó guardada; si falla siguen para volver a confirmar.
                    viewModel.enqueuePhoto(slot, photoPath, number) {
                        numberDrafts.clear(slot.name)
                        capturedPhotoPath = null
                    }
                } else {
                    numberDrafts.clear(slot.name)
                    capturedPhotoPath = null
                }
            },
            onRetake = {
                capturedPhotoPath = null
                showCamera = true
            },
            onDismiss = {
                capturedPhotoPath = null
            },
        )
    }

    // «Corregir número»: el campo solo; se encola sólo el número (NUMBER_ONLY), sin volver a tomar la foto.
    correctingSlot?.let { slot ->
        LinkingNumberDialog(
            kind = slot.numberKind,
            initialNumber = uiState.detail?.let { slotNumber(it.toPendingDto(), uiState.queued, slot).number },
            verificationId = uiState.detail?.id,
            capture = viewModel.numberCapture,
            onConfirm = { number ->
                correctingSlot = null
                viewModel.correctNumber(slot, number)
            },
            onDismiss = { correctingSlot = null },
        )
    }

    val isUploading = uiState.isUploading
    BackHandler(enabled = isUploading) { /* swallow back while uploading */ }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Corregir venta",
                onNavigationClick = { if (!isUploading) onNavigateBack() },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading && uiState.detail == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                uiState.detail == null -> {
                    // Sin señal y sin la venta guardada: se dice en neutro; con señal, el error de siempre.
                    CorrectionErrorState(
                        message = uiState.offlineNotice ?: "No se pudo cargar la venta",
                        isError = uiState.offlineNotice == null,
                        onRetry = { viewModel.load() },
                    )
                }

                else -> {
                    val detail = uiState.detail!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RejectionReasonsBanner(
                            reasons = uiState.rejectionReasons,
                            reviewNotes = uiState.reviewNotes,
                        )

                        if (uiState.resubmitted) {
                            ResubmittedBanner()
                        }

                        // Reuse the photo-slot card from "Pendientes Verificación".
                        PendingVerificationItem(
                            item = detail.toPendingDto(),
                            isUploading = isUploading,
                            isCameraActive = showCamera,
                            initiallyExpanded = true,
                            queued = uiState.queued,
                            deliveredSlots = uiState.deliveredSlots,
                            linkingNumberEnabled = linkingNumberEnabled,
                            onCorrectNumber = { slot -> correctingSlot = slot },
                            onDiscard = viewModel::discard,
                            onTakePhoto = { label, _ ->
                                if (showCamera) return@PendingVerificationItem
                                selectedLabel = label
                                showCamera = true
                            },
                        )

                        if (uiState.resubmitted) {
                            Button(
                                onClick = onNavigateBack,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text("Listo")
                            }
                        }
                    }
                }
            }

            if (isUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = "Guardando foto…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RejectionReasonsBanner(
    reasons: List<RejectionReason>,
    reviewNotes: String?,
) {
    val errorColor = MaterialTheme.avoqadoColors.statusError

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = errorColor.copy(alpha = 0.12f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = errorColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Revisar por promotor",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = errorColor,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (reasons.isEmpty() && reviewNotes.isNullOrBlank()) {
                Text(
                    text = "Vuelve a tomar las fotos de la documentación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                reasons.forEach { reason ->
                    Text(
                        text = "• ${reason.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (!reviewNotes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = reviewNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Corrige las fotos y se reenviará a revisión automáticamente.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResubmittedBanner() {
    val successColor = MaterialTheme.avoqadoColors.statusSuccess

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = successColor.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = successColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Documentación reenviada. En revisión por administración.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun CorrectionErrorState(message: String, isError: Boolean, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Reintentar")
            }
        }
    }
}

// ========== Previews ==========

@Preview(widthDp = 360, heightDp = 640, name = "Sale Correction - PAX A910S")
@Preview(device = PAX_A910S, showSystemUi = true, name = "Sale Correction - PAX A910S (device)")
@Composable
private fun SaleCorrectionBannerPreview() {
    AvoqadoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RejectionReasonsBanner(
                    reasons = listOf(
                        RejectionReason.REVIEW_ILLEGIBLE_IMAGES,
                        RejectionReason.REVIEW_MISSING_LINKING_IMAGE,
                    ),
                    reviewNotes = "La foto de vinculación no se ve completa.",
                )
                ResubmittedBanner()
            }
        }
    }
}
