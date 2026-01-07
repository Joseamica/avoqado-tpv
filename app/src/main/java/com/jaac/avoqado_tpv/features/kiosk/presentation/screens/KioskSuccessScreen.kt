package com.jaac.avoqado_tpv.features.kiosk.presentation.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.components.ShimmerBox
import com.jaac.avoqado_tpv.core.presentation.components.rememberQrBitmapPainter
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import kotlinx.coroutines.delay
import timber.log.Timber
import java.math.BigDecimal
import java.util.Locale

/**
 * Kiosk Success Screen - Payment Confirmation
 *
 * Shows order confirmation with QR code for digital receipt.
 * UX Pattern based on Toast/Square/McDonald's kiosks:
 * - Prominent "Nueva Orden" button (user decides when to proceed)
 * - Long timeout (~60s) as fallback only
 * - Pauses timer when user is interacting (email dialog)
 * - NO "Queda por pagar" (kiosk payments are always full amount)
 *
 * @param orderNumber Display order number for customer
 * @param receipt Payment receipt with QR code URL and amounts
 * @param orderItems Order items (for displaying itemized receipt modal)
 * @param onTimeout Callback when user taps "Nueva Orden" or timeout
 * @param onPrintReceipt Callback to print receipt
 * @param onSendReceipt Callback to send receipt by email
 * @param countdownSeconds Seconds before auto-reset (default: 60s - Toast/Square standard)
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KioskSuccessScreen(
    orderNumber: String,
    receipt: PaymentReceipt? = null,
    orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>? = null,
    onTimeout: () -> Unit,
    onPrintReceipt: () -> Unit = {},
    onSendReceipt: (email: String) -> Unit = {},
    countdownSeconds: Int = 60  // 🔄 Increased from 12s to 60s (Toast/Square standard)
) {
    var secondsRemaining by remember { mutableIntStateOf(countdownSeconds) }

    // Parse amounts from receipt
    val totalAmount = receipt?.amount ?: BigDecimal.ZERO
    val tipAmount = receipt?.tipAmount ?: BigDecimal.ZERO
    val subtotalAmount = receipt?.baseAmount ?: totalAmount

    // Email dialog state
    var showEmailDialog by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }

    // Order details modal state
    var showOrderDetailsModal by remember { mutableStateOf(false) }

    Timber.d("🥝 [KIOSK-SUCCESS] Composing (orderNumber: $orderNumber, receipt: ${receipt != null}, total: $totalAmount)")
    Timber.d("🥝 [KIOSK-SUCCESS] Receipt details: receiptUrl=${receipt?.receiptUrl}, amount=${receipt?.amount}, tipAmount=${receipt?.tipAmount}")

    // Countdown timer - PAUSES when email dialog is open (Toast/Square pattern)
    LaunchedEffect(showEmailDialog) {
        if (!showEmailDialog) {
            Timber.i("🥝 [KIOSK-SUCCESS] Timer active for order: $orderNumber (${secondsRemaining}s remaining)")
            while (secondsRemaining > 0 && !showEmailDialog) {
                delay(1000)
                secondsRemaining--
                if (secondsRemaining <= 5) {
                    Timber.d("🥝 [KIOSK-SUCCESS] Countdown: $secondsRemaining seconds remaining")
                }
            }
            if (secondsRemaining <= 0) {
                Timber.i("🥝 [KIOSK-SUCCESS] Timeout - auto-resetting kiosk")
                onTimeout()
            }
        } else {
            Timber.d("🥝 [KIOSK-SUCCESS] Timer PAUSED (email dialog open)")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Custom toolbar with individual buttons (matching PaymentSuccessContent design)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home button (left) - Disabled in kiosk mode (just a spacer for balance)
                Spacer(modifier = Modifier.size(48.dp))

                // Center button - "Nueva Orden" with countdown timer
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            Timber.i("🥝 [KIOSK-SUCCESS] 'Nueva Orden' button clicked - user ready for next order")
                            onTimeout()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            text = "Nueva Orden ($secondsRemaining)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Order details sheet button (only if there are order items)
                // ✅ Keeps right side balanced with left spacer
                if (!orderItems.isNullOrEmpty()) {
                    IconButton(
                        onClick = { showOrderDetailsModal = true },
                        modifier = Modifier
                            .size(48.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = "Ver detalles de la orden",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    // Spacer to balance layout when no receipt button
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }

        // Receipt visual (weight 1f to take remaining space)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                // Receipt background image (ticket paper texture)
                Image(
                    painter = painterResource(R.drawable.ilu_ticket_background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 70.dp),
                    contentDescription = "",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surface)
                )

                // QR Code (centered on top)
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .border(
                            width = 10.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(24.dp)
                        )
                ) {
                    receipt?.receiptUrl?.let { qrUrl ->
                        // Receipt arrived → Show QR code
                        Image(
                            painter = rememberQrBitmapPainter(
                                content = qrUrl,
                                size = 140.dp,
                                padding = 0.dp
                            ),
                            contentDescription = "Código QR del recibo",
                            modifier = Modifier
                                .size(140.dp)
                                .align(Alignment.Center)
                        )
                    } ?: run {
                        // Receipt pending → Show shimmer
                        ShimmerBox(
                            modifier = Modifier
                                .size(140.dp)
                                .align(Alignment.Center),
                            cornerRadius = 12.dp
                        )
                    }
                }

                // Receipt content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    // Instruction text
                    Text(
                        text = "Escanea el código QR para descargar el recibo y dejar una calificación",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Dashed divider
                    KioskDashedDivider()

                    Spacer(modifier = Modifier.height(24.dp))

                    // Total pagado
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Total pagado",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$${String.format(Locale.US, "%.2f", totalAmount)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 🥝 NO "Queda por pagar" - Kiosk payments are always full amount

                    Spacer(modifier = Modifier.height(12.dp))

                    // Solid divider
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Breakdown: Total
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Total",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$${String.format(Locale.US, "%.2f", subtotalAmount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Breakdown: Propina
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Propina",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$${String.format(Locale.US, "%.2f", tipAmount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // 🖨️ Print & Email buttons (matching staff UI)
        val buttonShape = RoundedCornerShape(12.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(48.dp)
                .clip(buttonShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = buttonShape
                )
        ) {
            // 🖨️ Print receipt button (left)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        Timber.i("🥝 [KIOSK-SUCCESS] Print button clicked")
                        onPrintReceipt()
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = "Imprimir",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Imprimir",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )

            // 📧 Send receipt by email button (right)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        Timber.i("🥝 [KIOSK-SUCCESS] Email button clicked")
                        showEmailDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Enviar",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Enviar",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 📧 Email input dialog
    if (showEmailDialog) {
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = {
                Text(
                    text = "Enviar recibo por correo",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column {
                    Text(
                        text = "Ingresa tu correo electrónico para recibir el recibo:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Correo electrónico") },
                        placeholder = { Text("ejemplo@correo.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (emailInput.isNotBlank() && emailInput.contains("@")) {
                                    Timber.i("🥝 [KIOSK-SUCCESS] Sending receipt to: $emailInput")
                                    onSendReceipt(emailInput)
                                    showEmailDialog = false
                                    emailInput = ""
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (emailInput.isNotBlank() && emailInput.contains("@")) {
                            Timber.i("🥝 [KIOSK-SUCCESS] Sending receipt to: $emailInput")
                            onSendReceipt(emailInput)
                            showEmailDialog = false
                            emailInput = ""
                        }
                    },
                    enabled = emailInput.isNotBlank() && emailInput.contains("@")
                ) {
                    Text("Enviar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Order details modal (bottom sheet)
    if (showOrderDetailsModal && !orderItems.isNullOrEmpty()) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showOrderDetailsModal = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Text(
                    text = "Orden #$orderNumber",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Items list
                orderItems.forEach { item ->
                    // Calculate base product price (without modifiers)
                    val modifiersUnitPrice = item.modifiers.sumOf { it.priceAdjustment }
                    val baseUnitPrice = item.unitPrice - modifiersUnitPrice
                    val baseTotalPrice = baseUnitPrice * item.quantity.toBigDecimal()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        // Product name + quantity + base price
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${item.quantity}x ${item.productName}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = String.format(java.util.Locale.US, "$%.2f", baseTotalPrice),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Modifiers (if any) - with individual prices
                        if (item.modifiers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(
                                modifier = Modifier.padding(start = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                item.modifiers.forEach { modifier ->
                                    val modifierTotalPrice = modifier.priceAdjustment * item.quantity.toBigDecimal()

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "  • ${modifier.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Text(
                                            text = String.format(java.util.Locale.US, "+$%.2f", modifierTotalPrice),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Notes (if any)
                        if (!item.notes.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Close button
                Button(
                    onClick = { showOrderDetailsModal = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Cerrar")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Dashed divider - like receipt paper perforation
 */
@Composable
private fun KioskDashedDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )
    }
}
