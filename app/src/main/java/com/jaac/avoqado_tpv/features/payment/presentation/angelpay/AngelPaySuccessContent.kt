package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.rememberQrBitmapPainter
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.presentation.components.WhatsAppReceiptDialog
import java.math.BigDecimal
import java.util.Locale

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

/**
 * Success content for AngelPay payments.
 *
 * Simplified version of Blumon's PaymentSuccessContent — same visual layout but without:
 * - Split payment "Continuar pagando"
 * - Kiosk auto-dismiss countdown
 * - Kitchen ticket printing
 * - Proof-of-sale verification
 * - Pay-later navigation
 *
 * Shown AFTER the PaymentApprovedScreen confetti animation completes.
 */
@Composable
fun AngelPaySuccessContent(
    state: AngelPayPaymentState.Success,
    showReceiptScreen: Boolean,
    showPrintButton: Boolean = true,
    onPrintReceipt: () -> Unit,
    onSendReceiptEmail: (String) -> Unit = {},
    onSendReceiptWhatsApp: (String) -> Unit = {},
    isSendingReceipt: Boolean = false,
    onNavigateHome: () -> Unit,
    /**
     * Deep-link to the unified Pagos list, auto-opening the detail
     * bottom sheet for the just-recorded payment. Receives the
     * recorded paymentId. Button is hidden if the success state has no
     * receipt yet (offline / pre-recording edge case).
     */
    onViewInPayments: (paymentId: String) -> Unit = {},
    onStartNewPayment: () -> Unit,
    // 📸 Serialized (SIM) sale — post-payment proof-of-sale, mirrors Blumon's own
    // "Vinculación" flow exactly. Default off/false for a normal AngelPay payment, so
    // the success screen stays byte-identical (QR always shown) unless this is a SIM sale.
    isSerializedFlow: Boolean = false,
    isPortabilidad: Boolean = false,
    isUploadingProofOfSale: Boolean = false,
    proofOfSaleComplete: Boolean = false,
    onProofOfSalePhotoTaken: (photoPath: String, photoLabel: String) -> Unit = { _, _ -> },
    onRetakeProofOfSalePhoto: (photoLabel: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val subtotalAmount = remember(state.amount) { state.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO }
    val tipAmount = remember(state.tipAmount) { state.tipAmount?.toBigDecimalOrNull() ?: BigDecimal.ZERO }
    val totalAmount = remember(subtotalAmount, tipAmount) { subtotalAmount.add(tipAmount) }

    var isPrinting by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var showWhatsAppDialog by remember { mutableStateOf(false) }

    // 📸 Proof-of-sale local UI state (mirrors Blumon's PaymentScreen locals)
    var showProofOfSaleCamera by remember { mutableStateOf(false) }
    var currentPhotoLabel by remember { mutableStateOf("linea") }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var viewingPhotoLabel by remember { mutableStateOf<String?>(null) }
    var lineaPhotoPath by remember { mutableStateOf<String?>(null) }
    var portabilidadPhotoPath by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Navigation header (Home | Nuevo Cobro | Transacciones) ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Home button (left)
            IconButton(
                onClick = onNavigateHome,
                modifier = Modifier
                    .size(48.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Inicio",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // "Nuevo Cobro" button (center)
            Button(
                onClick = onStartNewPayment,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Storefront,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Nuevo Cobro", style = MaterialTheme.typography.labelLarge)
            }

            // "Ver en Pagos" button (right) — opens the unified Pagos list
            // with this payment's detail bottom sheet auto-opened, so the
            // operator can issue refunds or actions on the just-made
            // payment without hunting through history. Hidden if the
            // success state has no recorded paymentId yet (offline edge
            // case before backend recording completes).
            val paymentId = state.receipt?.paymentId
            IconButton(
                onClick = { paymentId?.let(onViewInPayments) },
                enabled = paymentId != null,
                modifier = Modifier
                    .size(48.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = "Ver en Pagos",
                    tint = if (paymentId != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    },
                )
            }
        }

        // ── QR Code Receipt (normal) OR proof-of-sale photo (serialized SIM sale) ──
        if (!isSerializedFlow) {
            if (showReceiptScreen && state.receipt?.receiptUrl != null) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(
                            width = 8.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(24.dp),
                        ),
                ) {
                    Image(
                        painter = rememberQrBitmapPainter(
                            content = state.receipt.receiptUrl,
                            size = 120.dp,
                            padding = 0.dp,
                        ),
                        contentDescription = "Codigo QR del recibo",
                        modifier = Modifier
                            .size(120.dp)
                            .align(Alignment.Center),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Escanea el codigo QR para descargar el recibo",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        } else {
            com.jaac.avoqado_tpv.features.payment.presentation.components.ProofOfSalePhotoSection(
                isPortabilidad = isPortabilidad,
                lineaPhotoPath = lineaPhotoPath,
                portabilidadPhotoPath = portabilidadPhotoPath,
                isUploading = isUploadingProofOfSale,
                isComplete = proofOfSaleComplete,
                onTapLinea = {
                    if (lineaPhotoPath != null) {
                        viewingPhotoLabel = "linea"
                    } else {
                        currentPhotoLabel = "linea"
                        showProofOfSaleCamera = true
                    }
                },
                onTapPortabilidad = {
                    if (portabilidadPhotoPath != null) {
                        viewingPhotoLabel = "portabilidad"
                    } else {
                        currentPhotoLabel = "portabilidad"
                        showProofOfSaleCamera = true
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Cash indicator ───────────────────────────────────────────
        if (state.isCash) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Pago en efectivo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Total pagado ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Total pagado",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$${String.format(Locale.US, "%.2f", totalAmount)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(modifier = Modifier.height(8.dp))

        // ── Breakdown: Monto ─────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Monto",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$${String.format(Locale.US, "%.2f", subtotalAmount)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Breakdown: Propina ───────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Propina",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$${String.format(Locale.US, "%.2f", tipAmount)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Reference ────────────────────────────────────────────────
        if (state.referenceNumber != null && !state.isCash) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Ref: ${state.referenceNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Receipt actions (Print optional per device) ──────────────
        if (state.receipt != null) {
            val buttonShape = RoundedCornerShape(12.dp)
            val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(buttonShape)
                    .border(
                        width = 1.dp,
                        color = dividerColor,
                        shape = buttonShape,
                    ),
            ) {
                if (showPrintButton) {
                    // 🖨️ Print button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = !isPrinting) {
                                isPrinting = true
                                onPrintReceipt()
                                isPrinting = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (isPrinting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = "Imprimir",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPrinting) "..." else "Imprimir",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(dividerColor),
                    )
                }

                // 📧 Email button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showEmailDialog = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Email",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Email",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor),
                )

                // 💬 WhatsApp button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showWhatsAppDialog = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "WA",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF25D366),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "WhatsApp",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ── Email dialog (simple inline for AngelPay — no customer search) ──
    if (showEmailDialog) {
        AngelPayEmailDialog(
            onDismiss = { showEmailDialog = false },
            onSend = { email ->
                onSendReceiptEmail(email)
                showEmailDialog = false
            },
            isLoading = isSendingReceipt,
        )
    }

    // 💬 WhatsApp dialog
    if (showWhatsAppDialog) {
        WhatsAppReceiptDialog(
            onDismiss = { showWhatsAppDialog = false },
            onSend = { phone ->
                onSendReceiptWhatsApp(phone)
                showWhatsAppDialog = false
            },
            isLoading = isSendingReceipt,
        )
    }

    // 📸 Proof-of-sale camera dialog (full screen) — serialized SIM sale only.
    // Mirrors Blumon's own post-payment "Vinculación" camera wiring exactly.
    if (showProofOfSaleCamera) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val outputDirectory = remember {
            val dir = java.io.File(context.cacheDir, "proof_of_sale_photos")
            dir.mkdirs()
            dir
        }
        val cameraLabel = when (currentPhotoLabel) {
            "linea" -> "1. Vinculación"
            "portabilidad" -> "2. Portabilidad"
            else -> null
        }

        com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen(
            onPhotoCaptured = { photoPath ->
                showProofOfSaleCamera = false
                capturedPhotoPath = photoPath
            },
            onClose = { showProofOfSaleCamera = false },
            outputDirectory = outputDirectory,
            photoLabel = cameraLabel,
        )
    }

    // 📸 Photo preview dialog with confirm/retake (multi-photo wizard for portabilidad)
    capturedPhotoPath?.let { photoPath ->
        com.jaac.avoqado_tpv.features.payment.presentation.components.ProofOfSalePhotoPreviewDialog(
            photoPath = photoPath,
            onConfirm = {
                if (currentPhotoLabel == "linea") {
                    lineaPhotoPath = photoPath
                } else {
                    portabilidadPhotoPath = photoPath
                }
                onProofOfSalePhotoTaken(photoPath, currentPhotoLabel)
                capturedPhotoPath = null

                if (isPortabilidad) {
                    when {
                        currentPhotoLabel == "linea" && portabilidadPhotoPath == null -> {
                            currentPhotoLabel = "portabilidad"
                            showProofOfSaleCamera = true
                        }
                        currentPhotoLabel == "portabilidad" && lineaPhotoPath == null -> {
                            currentPhotoLabel = "linea"
                            showProofOfSaleCamera = true
                        }
                    }
                }
            },
            onRetake = {
                capturedPhotoPath = null
                showProofOfSaleCamera = true
            },
            onDismiss = { capturedPhotoPath = null },
        )
    }

    // 📸 View existing photo with retake option
    viewingPhotoLabel?.let { label ->
        val viewingPath = if (label == "linea") lineaPhotoPath else portabilidadPhotoPath
        viewingPath?.let { path ->
            com.jaac.avoqado_tpv.features.payment.presentation.components.ProofOfSalePhotoPreviewDialog(
                photoPath = path,
                onConfirm = { viewingPhotoLabel = null },
                onRetake = {
                    viewingPhotoLabel = null
                    onRetakeProofOfSalePhoto(label)
                    if (label == "linea") lineaPhotoPath = null else portabilidadPhotoPath = null
                    currentPhotoLabel = label
                    showProofOfSaleCamera = true
                },
                onDismiss = { viewingPhotoLabel = null },
                confirmText = "Cerrar",
                retakeText = "Retomar",
            )
        }
    }
}

/**
 * Simple email dialog for AngelPay (no customer search).
 */
@Composable
private fun AngelPayEmailDialog(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    isLoading: Boolean,
) {
    var email by remember { mutableStateOf("") }
    val isValid = email.contains("@") && email.contains(".")

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = "Enviar recibo",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Correo electronico") },
                    placeholder = { Text("ejemplo@correo.com") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancelar")
                    }
                    Button(onClick = { onSend(email.trim()) }, enabled = isValid && !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Enviando..." else "Enviar")
                    }
                }
            }
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun AngelPaySuccessWithQrPreview() {
    AvoqadoTheme {
        AngelPaySuccessContent(
            state = AngelPayPaymentState.Success(
                authCode = "502511",
                amount = "150.00",
                tipAmount = "15.00",
                receipt = PaymentReceipt(
                    paymentId = "test123",
                    receiptUrl = "https://api.avoqado.io/api/v1/public/receipt/test",
                    accessKey = "test",
                    amount = BigDecimal("150.00"),
                    tipAmount = BigDecimal("15.00"),
                ),
                referenceNumber = "REF123456",
            ),
            showReceiptScreen = true,
            onPrintReceipt = {},
            onNavigateHome = {},
            onStartNewPayment = {},
        )
    }
}

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun AngelPaySuccessCashPreview() {
    AvoqadoTheme {
        AngelPaySuccessContent(
            state = AngelPayPaymentState.Success(
                authCode = "EFECTIVO",
                amount = "200.00",
                tipAmount = null,
                isCash = true,
                referenceNumber = "CASH-1710000000000",
            ),
            showReceiptScreen = false,
            onPrintReceipt = {},
            onNavigateHome = {},
            onStartNewPayment = {},
        )
    }
}
