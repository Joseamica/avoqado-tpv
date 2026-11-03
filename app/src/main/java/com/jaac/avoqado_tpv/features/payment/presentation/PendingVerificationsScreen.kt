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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TextButton
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
import com.jaac.avoqado_tpv.features.verification.data.ACTIONABLE_OFFLINE_MESSAGE
import com.jaac.avoqado_tpv.features.verification.data.ProofSlot
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.payment.presentation.components.ProofOfSalePhotoPreviewDialog
import com.jaac.avoqado_tpv.features.verification.presentation.canDiscardCapture
import com.jaac.avoqado_tpv.features.verification.presentation.isPhotoCapture
import com.jaac.avoqado_tpv.features.verification.presentation.components.CORRECT_NUMBER_TEXT
import com.jaac.avoqado_tpv.features.verification.presentation.components.LINKING_NUMBER_LOCKED_TEXT
import com.jaac.avoqado_tpv.features.verification.presentation.components.LINKING_NUMBER_NEEDS_SALE_TEXT
import com.jaac.avoqado_tpv.features.verification.presentation.components.LinkingNumberDialog
import com.jaac.avoqado_tpv.features.verification.presentation.numberKind
import com.jaac.avoqado_tpv.features.verification.presentation.queuedCaptureMessage
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
        val venueZone = com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val verifications = state.items
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val uploadingId by viewModel.savingPaymentId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val linkingNumberEnabled by viewModel.linkingNumberEnabled.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(error) {
        error?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }

    // Camera state
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var selectedPaymentId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var capturedPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    // «Corregir número»: la venta y la ranura cuyo número se está corrigiendo (sin foto).
    var correcting by remember { mutableStateOf<Pair<String, ProofSlot>?>(null) }

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
            },
            // cacheDir: la cola copia la foto a su carpeta; el barrido de C4 limpia la suya, no ésta.
            outputDirectory = context.cacheDir,
            photoLabel = cameraLabel
        )
        return // Full-screen camera overlay — don't render the list behind it
    }

    // Photo preview dialog — confirm/retake before uploading (con el número de su ranura si el interruptor está encendido)
    capturedPhotoPath?.let { photoPath ->
        val slot = proofSlotOf(selectedLabel)
        val selected = verifications.firstOrNull { it.paymentId == selectedPaymentId }
        val current = selected?.let { slotNumber(it.toPendingCard(), it.queued, slot) }
        ProofOfSalePhotoPreviewDialog(
            photoPath = photoPath,
            title = when (selectedLabel) {
                "Vinculacion" -> "1. Vinculación"
                "Portabilidad" -> "2. Portabilidad"
                else -> selectedLabel
            },
            numberLabel = slot.numberKind.label.takeIf { linkingNumberEnabled },
            initialNumber = current?.number,
            numberLocked = current?.locked == true,
            numberKind = slot.numberKind,
            verificationId = selected?.verificationId,
            numberCapture = viewModel.numberCapture,
            onConfirm = { number ->
                capturedPhotoPath = null
                val pId = selectedPaymentId
                if (pId != null && photoPath.isNotEmpty()) {
                    // Se encola (archivo y fila antes de la red); la sube PaymentSyncWorker. Sin señal, igual.
                    viewModel.enqueuePhoto(pId, slot, photoPath, number)
                }
            },
            onRetake = {
                capturedPhotoPath = null
                showCamera = true // Reopen camera
            },
            onDismiss = {
                capturedPhotoPath = null
            }
        )
    }

    // «Corregir número»: el campo solo; se encola sólo el número (NUMBER_ONLY), sin volver a tomar la foto.
    correcting?.let { (paymentId, slot) ->
        val item = verifications.firstOrNull { it.paymentId == paymentId }
        LinkingNumberDialog(
            kind = slot.numberKind,
            initialNumber = item?.let { slotNumber(it.toPendingCard(), it.queued, slot).number },
            verificationId = item?.verificationId,
            capture = viewModel.numberCapture,
            onConfirm = { number ->
                correcting = null
                viewModel.correctNumber(paymentId, slot, number)
            },
            onDismiss = { correcting = null },
        )
    }

    // Mientras la foto se guarda en la terminal (milisegundos) no se sale: la copia y la fila van juntas.
    val isUploading = uploadingId != null
    BackHandler(enabled = isUploading) {
        // Swallow back press while saving — user must wait
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "En revisión por administración",
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (state.isOffline) SavedListBanner(Modifier.padding(12.dp))
                        EmptyVerificationsState()
                    }
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
                            if (state.isOffline) {
                                item(key = "lista-guardada") { SavedListBanner() }
                            }
                            items(
                                items = verifications,
                                key = { it.paymentId } // una venta local todavía no tiene id de verificación
                            ) { item ->
                                PendingVerificationItem(
                                    item = item.toPendingCard(),
                                    isUploading = uploadingId == item.paymentId,
                                    isCameraActive = showCamera,
                                    queued = item.queued,
                                    deliveredSlots = item.deliveredSlots,
                                    showAmount = item.detail != null,
                                    review = item.review(),
                                    linkingNumberEnabled = linkingNumberEnabled,
                                    // Sin verificación (venta que el servidor aún no registra) el botón queda en gris.
                                    onCorrectNumber = item.verificationId?.let { { slot: ProofSlot -> correcting = item.paymentId to slot } },
                                    onDiscard = viewModel::discard,
                                    onTakePhoto = { label, _ ->
                                        if (showCamera) return@PendingVerificationItem
                                        selectedPaymentId = item.paymentId
                                        selectedLabel = label
                                        showCamera = true
                                    }
                                )
                            }
                            if (state.nextCursor != null) {
                                item(key = "cargar-mas") {
                                    OutlinedButton(
                                        onClick = viewModel::loadMore,
                                        enabled = !isLoadingMore,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(if (isLoadingMore) "Cargando…" else "Cargar más")
                                    }
                                }
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
                            text = "Guardando foto…",
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

/** La etiqueta fija de la ranura ("Vinculacion" / "Portabilidad") a su ranura de la cola. */
internal fun proofSlotOf(label: String): ProofSlot =
    if (label == ProofSlot.PORTABILIDAD.backendLabel) ProofSlot.PORTABILIDAD else ProofSlot.VINCULACION

/** Sin señal la lista es la guardada: se dice con todas sus letras, en neutro (no es un error). */
@Composable
private fun SavedListBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = ACTIONABLE_OFFLINE_MESSAGE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

// ========== Verification Card ==========

@Composable
fun PendingVerificationItem(
    item: PendingVerificationDto,
    isUploading: Boolean,
    isCameraActive: Boolean = false,
    initiallyExpanded: Boolean = false,
    /** Las capturas de esta venta en la cola, la más nueva primero: cada una dice su estado. */
    queued: List<ProofOfSaleQueueEntity> = emptyList(),
    /** Ranuras que esta terminal ya entregó, aunque la lista guardada todavía no traiga la foto. */
    deliveredSlots: Set<ProofSlot> = emptySet(),
    /** false en una venta de la que sólo hay capturas en la cola: no se sabe el importe. */
    showAmount: Boolean = true,
    /** La venta está en «Revisar» (FAILED): chip ámbar, motivos del back-office y nunca «completa». */
    review: CardReview? = null,
    /** Interruptor `features.linkingNumber`: bajo cada foto, su número, el aviso de repetido y «Corregir número». */
    linkingNumberEnabled: Boolean = false,
    /** «Corregir número» de una ranura; null = la venta todavía no tiene verificación (botón en gris, con su razón). */
    onCorrectNumber: ((ProofSlot) -> Unit)? = null,
    onDiscard: (idempotencyKey: String) -> Unit = {},
    onTakePhoto: (label: String, replaceIndex: Int?) -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var previewPhotoUrl by remember { mutableStateOf<String?>(null) }

    // Por ranura: la foto del servidor; si no, la de su captura más nueva en la cola (el archivo de la terminal).
    fun serverPhoto(slot: ProofSlot) = item.photos.getOrNull(slot.ordinal)?.takeIf { it.isNotEmpty() }
    fun queuedPhoto(slot: ProofSlot) = queued.firstOrNull { it.slot == slot.name && isPhotoCapture(it) }?.localPath

    val progress = cardProgress(item, queued, deliveredSlots, review)
    val numberSlots = if (item.isPortabilidad) ProofSlot.entries else listOf(ProofSlot.VINCULACION)
    val numbers = if (linkingNumberEnabled) numberSlots.associateWith { slotNumber(item, queued, it) } else emptyMap()
    val nonEmptyPhotos = progress.filled
    val photosComplete = progress.complete
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
                if (showAmount) {
                    Text(
                        text = CurrencyFormatter.format(BigDecimal.valueOf(item.amount)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
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
                    if (review != null) {
                        ReviewChip(color = MaterialTheme.avoqadoColors.statusWarning)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    PhotoStatusChip(
                        current = nonEmptyPhotos,
                        required = item.requiredPhotos,
                        color = chipColor,
                        complete = photosComplete
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

            // «Revisar»: lo que pidió el back-office, a la vista sin abrir la tarjeta (mismas etiquetas que la corrección).
            review?.let { r ->
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    if (r.reasons.isEmpty() && r.notes == null) {
                        Text(
                            text = "Vuelve a tomar las fotos de la documentación.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    r.reasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    r.notes?.let { notes ->
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Número repetido: el aviso de Isaac a la vista sin abrir la tarjeta (ámbar: avisa, no bloquea).
            numbers.filterValues { it.duplicate }.keys.forEach { slot ->
                Text(
                    text = slot.numberKind.duplicateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.avoqadoColors.statusWarning,
                    modifier = Modifier.padding(top = 6.dp)
                )
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
                                text = "Guardando foto…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Photo slots — fixed positions: index 0 = Vinculacion, index 1 = Portabilidad
                    // Empty strings represent unfilled slots (backend pads when photo uploaded out of order)
                    val lineaPhoto = serverPhoto(ProofSlot.VINCULACION) ?: queuedPhoto(ProofSlot.VINCULACION)
                    val portabilidadPhoto = serverPhoto(ProofSlot.PORTABILIDAD) ?: queuedPhoto(ProofSlot.PORTABILIDAD)

                    val canInteract = !isUploading && !isCameraActive

                    if (!item.isPortabilidad) {
                        // Single photo: wider placeholder
                        PhotoSlot(
                            label = "Vinculacion",
                            photoUrl = lineaPhoto,
                            delivered = ProofSlot.VINCULACION in deliveredSlots,
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
                                delivered = ProofSlot.VINCULACION in deliveredSlots,
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
                                delivered = ProofSlot.PORTABILIDAD in deliveredSlots,
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

                    // El número bajo las fotos, con «Corregir número» (sin volver a tomar la foto).
                    numbers.forEach { (slot, number) ->
                        SlotNumberRow(slot = slot, number = number, enabled = !isUploading, onCorrect = onCorrectNumber?.let { { it(slot) } })
                    }

                    // Lo que pasa con cada captura de la cola; «Descartar» en las que ya no se suben solas.
                    queued.forEach { row ->
                        QueuedCaptureRow(row = row, onDiscard = { onDiscard(row.idempotencyKey) })
                    }
                }
            }
        }
    }
}

/**
 * «Número de vinculación: 5512345678» con «Corregir número». Bloqueado: «Corregido por el back-office» y sin botón. Sin
 * verificación: el botón en gris con «Disponible cuando se registre la venta».
 */
@Composable
private fun SlotNumberRow(slot: ProofSlot, number: SlotNumber, enabled: Boolean, onCorrect: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${slot.numberKind.label}: ${number.number ?: "sin capturar"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            when {
                number.locked -> LINKING_NUMBER_LOCKED_TEXT
                onCorrect == null -> LINKING_NUMBER_NEEDS_SALE_TEXT
                else -> null
            }?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!number.locked) {
            TextButton(onClick = { onCorrect?.invoke() }, enabled = enabled && onCorrect != null) { Text(CORRECT_NUMBER_TEXT) }
        }
    }
}

@Composable
private fun QueuedCaptureRow(row: ProofOfSaleQueueEntity, onDiscard: () -> Unit) {
    val slot = if (row.slot == ProofSlot.PORTABILIDAD.name) ProofSlot.PORTABILIDAD else ProofSlot.VINCULACION
    // Una corrección de número (sin foto) se nombra por su número, no por su foto.
    val slotName = when {
        !isPhotoCapture(row) -> slot.numberKind.label
        slot == ProofSlot.PORTABILIDAD -> "Portabilidad"
        else -> "Vinculación"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$slotName: ${queuedCaptureMessage(row)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (canDiscardCapture(row)) {
            TextButton(onClick = onDiscard) { Text("Descartar") }
        }
    }
}

// ========== Photo Slot ==========

/**
 * Visual photo placeholder — matches the dashed-border pattern from PaymentScreen.
 * - Empty: dashed border + camera icon + label (tap to capture)
 * - Filled: thumbnail with tap-to-preview and re-take overlay button
 * - Delivered: esta terminal ya la entregó pero la lista guardada aún no trae la foto (sin señal): «Foto entregada»
 */
@Composable
private fun PhotoSlot(
    label: String,
    photoUrl: String?,
    delivered: Boolean = false,
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val warningColor = MaterialTheme.avoqadoColors.statusWarning
    val borderColor = if (delivered) MaterialTheme.avoqadoColors.statusSuccess else warningColor

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
                            color = borderColor,
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
            val tint = when {
                !enabled -> mutedColor
                delivered -> MaterialTheme.avoqadoColors.statusSuccess
                else -> warningColor
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = if (delivered) Icons.Default.CheckCircle else Icons.Default.PhotoCamera,
                    contentDescription = label,
                    modifier = Modifier.size(28.dp),
                    tint = tint
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (delivered) "Foto entregada" else "Toca para foto",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (enabled) tint.copy(alpha = 0.7f) else mutedColor,
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

/** «Revisar» en ámbar (no rojo): el back-office regresó la venta, no es un error de la terminal. */
@Composable
private fun ReviewChip(color: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text = REVIEW_CHIP_TEXT,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PhotoStatusChip(
    current: Int,
    required: Int,
    color: Color,
    modifier: Modifier = Modifier,
    complete: Boolean = current >= required
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
            if (complete) {
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
