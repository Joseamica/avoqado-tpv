package com.jaac.avoqado_tpv.features.verification.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payment.domain.ScannedProduct
import java.io.File

/**
 * Step 4: Sale Verification Screen
 *
 * **Purpose:**
 * Capture evidence (photos + barcodes) for retail/telecomunicaciones venues.
 *
 * **Modes:**
 * - PRE-payment: Verification BEFORE payment (paymentId = null, canSkip based on requirements)
 * - POST-payment: Verification AFTER payment (paymentId = valid ID, always can skip)
 *
 * **Layout:**
 * - Header: Payment summary (amount, order number)
 * - Photos section: Horizontal scroll of captured photos + "Add" button
 * - Barcodes section: List of scanned products
 * - Actions: "Tomar Foto", "Escanear Codigo"
 * - Footer: "Confirmar" (primary), "Saltar" (secondary, hidden if requirements mandatory)
 * - Back button (PRE-payment mode only)
 *
 * **Validation:**
 * - If requireVerificationPhoto = true, at least 1 photo required
 * - If requireVerificationBarcode = true, at least 1 barcode required
 * - "Confirmar" button disabled until requirements met
 *
 * @param paymentId Payment ID (null for PRE-payment mode)
 * @param canSkip Whether "Saltar" button is visible (true = no mandatory requirements)
 * @param onNavigateBack Back navigation callback (null = no back button shown, PRE-payment mode)
 */
@Composable
fun VerificationScreen(
    paymentId: String?,  // 📸 Nullable for PRE-payment verification
    amount: String,
    orderNumber: String?,
    capturedPhotos: List<String>,
    scannedBarcodes: List<ScannedProduct>,
    requirePhoto: Boolean,
    requireBarcode: Boolean,
    isUploading: Boolean,
    uploadProgress: Float,
    error: String?,
    onTakePhoto: () -> Unit,
    onScanBarcode: () -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onRemoveBarcode: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    // 📸 PRE-payment verification parameters
    canSkip: Boolean = true,  // 🆕 Hide skip button when requirements are mandatory
    onNavigateBack: (() -> Unit)? = null  // 🆕 Back navigation (null = no back button)
) {
    // Validation: Check if requirements are met
    val photoRequirementMet = !requirePhoto || capturedPhotos.isNotEmpty()
    val barcodeRequirementMet = !requireBarcode || scannedBarcodes.isNotEmpty()
    val canConfirm = photoRequirementMet && barcodeRequirementMet && !isUploading

    ResponsiveScaffold(
        modifier = modifier,
        scrollable = true,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ⚠️ FIX: Access LocalResponsiveSizes INSIDE ResponsiveScaffold lambda
        val sizes = LocalResponsiveSizes.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = sizes.paddingScreen),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(sizes.spacingMedium))

            // Payment Summary
            PaymentSummaryCard(
                amount = amount,
                orderNumber = orderNumber,
                isPrePayment = paymentId == null  // 📸 PRE-payment mode: different header
            )

            Spacer(modifier = Modifier.height(sizes.spacingLarge))

            // Photos Section
            PhotosSection(
                photos = capturedPhotos,
                requirePhoto = requirePhoto,
                onTakePhoto = onTakePhoto,
                onRemovePhoto = onRemovePhoto
            )

            Spacer(modifier = Modifier.height(sizes.spacingLarge))

            // Barcodes Section
            BarcodesSection(
                barcodes = scannedBarcodes,
                requireBarcode = requireBarcode,
                onScanBarcode = onScanBarcode,
                onRemoveBarcode = onRemoveBarcode
            )

            // Error message
            if (error != null) {
                Spacer(modifier = Modifier.height(sizes.spacingMedium))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Upload progress
            if (isUploading) {
                Spacer(modifier = Modifier.height(sizes.spacingMedium))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Subiendo evidencia...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Validation hints
            if (!photoRequirementMet || !barcodeRequirementMet) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!photoRequirementMet) {
                        Text(
                            text = "* Se requiere al menos 1 foto",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!barcodeRequirementMet) {
                        Text(
                            text = "* Se requiere escanear al menos 1 codigo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.height(sizes.spacingSmall))
            }

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 📸 PRE-payment mode: Show "Continuar" instead of "Confirmar"
                val confirmText = if (paymentId == null) {
                    if (isUploading) "Procesando..." else "Continuar"
                } else {
                    if (isUploading) "Subiendo..." else "Confirmar"
                }

                AvoqadoButton(
                    text = confirmText,
                    onClick = onConfirm,
                    enabled = canConfirm,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(sizes.spacingSmall))

                // 📸 "Saltar" button - only visible if canSkip is true
                // In PRE-payment mode with mandatory requirements, this button is hidden
                if (canSkip) {
                    AvoqadoSecondaryButton(
                        text = "Saltar",
                        onClick = onSkip,
                        enabled = !isUploading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(sizes.spacingSmall))
                }

                // 📸 PRE-payment mode: Back button
                if (onNavigateBack != null) {
                    TextButton(
                        onClick = onNavigateBack,
                        enabled = !isUploading
                    ) {
                        Text(
                            text = "← Volver",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(sizes.spacingSmall))
            }
        }
    }
}

@Composable
private fun PaymentSummaryCard(
    amount: String,
    orderNumber: String?,
    isPrePayment: Boolean = false,  // 📸 PRE-payment mode: different header
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 📸 Different header text for PRE vs POST payment
            Text(
                text = if (isPrePayment) "Verificación de Venta" else "Pago Exitoso",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$$amount",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            if (orderNumber != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Orden #$orderNumber",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 📸 PRE-payment mode: Explain that verification is required before payment
            if (isPrePayment) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Capture evidencia antes de procesar el pago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PhotosSection(
    photos: List<String>,
    requirePhoto: Boolean,
    onTakePhoto: () -> Unit,
    onRemovePhoto: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (requirePhoto) "Fotos *" else "Fotos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${photos.size} capturada${if (photos.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Photos horizontal scroll
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Add photo button
            item {
                AddPhotoButton(onClick = onTakePhoto)
            }

            // Captured photos
            items(photos.size) { index ->
                PhotoThumbnail(
                    photoPath = photos[index],
                    onRemove = { onRemovePhoto(index) }
                )
            }
        }
    }
}

@Composable
private fun AddPhotoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Tomar foto",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Foto",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photoPath: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        AsyncImage(
            model = File(photoPath),
            contentDescription = "Foto capturada",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Eliminar foto",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BarcodesSection(
    barcodes: List<ScannedProduct>,
    requireBarcode: Boolean,
    onScanBarcode: () -> Unit,
    onRemoveBarcode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (requireBarcode) "Productos Escaneados *" else "Productos Escaneados",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${barcodes.size} producto${if (barcodes.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scan barcode button
        OutlinedButton(
            onClick = onScanBarcode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Escanear Codigo")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scanned products list
        if (barcodes.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                barcodes.forEach { product ->
                    ScannedProductItem(
                        product = product,
                        onRemove = { onRemoveBarcode(product.barcode) }
                    )
                }
            }
        } else {
            Text(
                text = "No hay productos escaneados",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ScannedProductItem(
    product: ScannedProduct,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.productName ?: "Producto desconocido",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = product.barcode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (product.quantity > 1) {
                    Text(
                        text = "Cantidad: ${product.quantity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Eliminar producto",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun VerificationScreenPreview() {
    AvoqadoTheme {
        VerificationScreen(
            paymentId = "pay_123",
            amount = "1,250.00",
            orderNumber = "A-001",
            capturedPhotos = listOf("/path/to/photo1.jpg", "/path/to/photo2.jpg"),
            scannedBarcodes = listOf(
                ScannedProduct(
                    barcode = "7501234567890",
                    format = "EAN_13",
                    productName = "iPhone 15 Pro Max 256GB",
                    productId = "prod_123",
                    hasInventory = true
                ),
                ScannedProduct(
                    barcode = "7509876543210",
                    format = "EAN_13",
                    productName = "AirPods Pro 2",
                    productId = "prod_456",
                    hasInventory = true
                )
            ),
            requirePhoto = true,
            requireBarcode = true,
            isUploading = false,
            uploadProgress = 0f,
            error = null,
            onTakePhoto = {},
            onScanBarcode = {},
            onRemovePhoto = {},
            onRemoveBarcode = {},
            onConfirm = {},
            onSkip = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun VerificationScreenEmptyPreview() {
    AvoqadoTheme {
        VerificationScreen(
            paymentId = "pay_123",
            amount = "500.00",
            orderNumber = null,
            capturedPhotos = emptyList(),
            scannedBarcodes = emptyList(),
            requirePhoto = true,
            requireBarcode = false,
            isUploading = false,
            uploadProgress = 0f,
            error = null,
            onTakePhoto = {},
            onScanBarcode = {},
            onRemovePhoto = {},
            onRemoveBarcode = {},
            onConfirm = {},
            onSkip = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun VerificationScreenUploadingPreview() {
    AvoqadoTheme {
        VerificationScreen(
            paymentId = "pay_123",
            amount = "750.00",
            orderNumber = "B-002",
            capturedPhotos = listOf("/path/to/photo1.jpg"),
            scannedBarcodes = listOf(
                ScannedProduct(
                    barcode = "7501234567890",
                    productName = "Samsung Galaxy S24",
                    hasInventory = true
                )
            ),
            requirePhoto = false,
            requireBarcode = false,
            isUploading = true,
            uploadProgress = 0.65f,
            error = null,
            onTakePhoto = {},
            onScanBarcode = {},
            onRemovePhoto = {},
            onRemoveBarcode = {},
            onConfirm = {},
            onSkip = {}
        )
    }
}
