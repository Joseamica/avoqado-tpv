package com.jaac.avoqado_tpv.features.activation.presentation.components

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import timber.log.Timber

/**
 * Dedicated QR Scanner Screen for Terminal Activation.
 *
 * **Features:**
 * - Square scan area optimized for QR codes
 * - Clear instructions for users (hold camera steady)
 * - Only scans QR codes (no barcode formats)
 * - Visual QR hint in scan area
 *
 * @param onQrScanned Callback with QR code data
 * @param onClose Callback to close scanner
 */
@Composable
fun ActivationQrScannerScreen(
    onQrScanned: (qrData: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Camera state
    var hasCameraPermission by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var lastScannedQr by remember { mutableStateOf<String?>(null) }
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        Timber.d("📱 [ActivationQrScanner] Permission granted: $granted")
    }

    // Check permission on launch with delay
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        Timber.d("📱 [ActivationQrScanner] Starting after delay")

        val permission = Manifest.permission.CAMERA
        val granted = ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (granted) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(permission)
        }
    }

    // Toggle flash
    LaunchedEffect(flashEnabled, barcodeView) {
        barcodeView?.let { view ->
            if (flashEnabled) {
                view.setTorchOn()
            } else {
                view.setTorchOff()
            }
        }
    }

    // Cleanup camera on dispose
    DisposableEffect(Unit) {
        onDispose {
            Timber.d("📱 [ActivationQrScanner] Disposing - stopping scanner")
            barcodeView?.let { view ->
                view.setTorchOff()
                view.barcodeView.pauseAndWait()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            // QR Scanner
            QrOnlyScanner(
                onBarcodeViewReady = { view ->
                    Timber.d("📱 [ActivationQrScanner] BarcodeView ready")
                    barcodeView = view
                },
                onQrDetected = { qrData ->
                    // Prevent duplicate scans
                    if (qrData != lastScannedQr) {
                        lastScannedQr = qrData
                        Timber.d("✅ [ActivationQrScanner] QR scanned: $qrData")
                        onQrScanned(qrData)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Square scan area overlay for QR
            QrScanAreaOverlay(modifier = Modifier.fillMaxSize())

            // Controls and instructions
            QrScannerControls(
                flashEnabled = flashEnabled,
                onFlashToggle = { flashEnabled = !flashEnabled },
                onClose = onClose,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Permission denied
            QrPermissionDeniedContent(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onClose = onClose
            )
        }
    }
}

/**
 * QR-only scanner - only detects QR codes for better performance
 */
@Composable
private fun QrOnlyScanner(
    onBarcodeViewReady: (DecoratedBarcodeView) -> Unit,
    onQrDetected: (qrData: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Only QR code format
    val formats = listOf(BarcodeFormat.QR_CODE)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        factory = { ctx ->
            Timber.d("📱 [QrOnlyScanner] Creating DecoratedBarcodeView")

            DecoratedBarcodeView(ctx).apply {
                // Camera settings
                val cameraSettings = CameraSettings().apply {
                    requestedCameraId = 0  // Back camera
                    isAutoFocusEnabled = true
                    isContinuousFocusEnabled = true
                    isMeteringEnabled = true
                    isAutoTorchEnabled = false
                    isBarcodeSceneModeEnabled = true
                }
                barcodeView.cameraSettings = cameraSettings

                // Decoder hints optimized for QR codes
                val hints = mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.POSSIBLE_FORMATS to formats,
                    DecodeHintType.ALSO_INVERTED to true  // Handle inverted QR codes
                )
                barcodeView.decoderFactory = DefaultDecoderFactory(formats, hints, null, 0)

                // Continuous decoding
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        result?.let {
                            val qrData = it.text
                            if (!qrData.isNullOrBlank()) {
                                Timber.d("📱 [QrOnlyScanner] QR detected: $qrData")
                                onQrDetected(qrData)
                            }
                        }
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                        // Optional: visual feedback
                    }
                })

                // Hide default status text
                statusView.visibility = android.view.View.GONE

                // Wait for view layout before starting camera
                viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)

                        Timber.d("📱 [QrOnlyScanner] View laid out (${width}x${height})")

                        mainHandler.postDelayed({
                            try {
                                Timber.d("📱 [QrOnlyScanner] Starting camera")
                                resume()
                                Timber.d("📱 [QrOnlyScanner] ✅ Camera started")
                            } catch (e: Exception) {
                                Timber.e(e, "📱 [QrOnlyScanner] ❌ Failed to start camera")
                            }
                        }, 500)
                    }
                })

                onBarcodeViewReady(this)
            }
        },
        modifier = modifier
    )
}

/**
 * Square overlay optimized for QR code scanning
 */
@Composable
private fun QrScanAreaOverlay(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        // Square scan area (QR codes are square)
        val scanSize = minOf(size.width, size.height) * 0.65f
        val scanAreaLeft = (size.width - scanSize) / 2
        val scanAreaTop = (size.height - scanSize) / 2 - 40.dp.toPx()  // Slightly above center

        // Dark overlay
        drawRect(
            color = Color.Black.copy(alpha = 0.7f),
            size = size
        )

        // Clear square scan area
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(scanAreaLeft, scanAreaTop),
            size = Size(scanSize, scanSize),
            cornerRadius = CornerRadius(24.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        // ═══════════════════════════════════════════════════════════════════
        // QR Code hint illustration (semi-transparent guide)
        // ═══════════════════════════════════════════════════════════════════
        val qrHintColor = Color.White.copy(alpha = 0.12f)
        val qrSize = scanSize * 0.5f
        val qrLeft = scanAreaLeft + (scanSize - qrSize) / 2
        val qrTop = scanAreaTop + (scanSize - qrSize) / 2

        // Draw simplified QR pattern
        val cellSize = qrSize / 7f

        // Draw QR finder patterns (the 3 corner squares)
        // Top-left finder
        drawQrFinderPattern(qrLeft, qrTop, cellSize, qrHintColor)
        // Top-right finder
        drawQrFinderPattern(qrLeft + qrSize - cellSize * 3, qrTop, cellSize, qrHintColor)
        // Bottom-left finder
        drawQrFinderPattern(qrLeft, qrTop + qrSize - cellSize * 3, cellSize, qrHintColor)

        // Some random data cells in the middle
        val dataCells = listOf(
            Pair(3, 3), Pair(4, 3), Pair(3, 4),
            Pair(4, 4), Pair(5, 3), Pair(3, 5)
        )
        dataCells.forEach { (col, row) ->
            drawRect(
                color = qrHintColor,
                topLeft = Offset(qrLeft + col * cellSize, qrTop + row * cellSize),
                size = Size(cellSize * 0.8f, cellSize * 0.8f)
            )
        }

        // Scan area border
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(scanAreaLeft, scanAreaTop),
            size = Size(scanSize, scanSize),
            cornerRadius = CornerRadius(24.dp.toPx()),
            style = Stroke(width = 3.dp.toPx())
        )

        // Corner accents (thicker and more prominent)
        val cornerLength = 50.dp.toPx()
        val cornerStroke = 6.dp.toPx()
        val accentColor = Color(0xFF2196F3)  // Blue for QR (green is for barcodes)

        // Top-left corner
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft, scanAreaTop + cornerLength),
            end = Offset(scanAreaLeft, scanAreaTop),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft, scanAreaTop),
            end = Offset(scanAreaLeft + cornerLength, scanAreaTop),
            strokeWidth = cornerStroke
        )

        // Top-right corner
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanSize, scanAreaTop + cornerLength),
            end = Offset(scanAreaLeft + scanSize, scanAreaTop),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanSize, scanAreaTop),
            end = Offset(scanAreaLeft + scanSize - cornerLength, scanAreaTop),
            strokeWidth = cornerStroke
        )

        // Bottom-left corner
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft, scanAreaTop + scanSize - cornerLength),
            end = Offset(scanAreaLeft, scanAreaTop + scanSize),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft, scanAreaTop + scanSize),
            end = Offset(scanAreaLeft + cornerLength, scanAreaTop + scanSize),
            strokeWidth = cornerStroke
        )

        // Bottom-right corner
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanSize, scanAreaTop + scanSize - cornerLength),
            end = Offset(scanAreaLeft + scanSize, scanAreaTop + scanSize),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanSize, scanAreaTop + scanSize),
            end = Offset(scanAreaLeft + scanSize - cornerLength, scanAreaTop + scanSize),
            strokeWidth = cornerStroke
        )
    }
}

/**
 * Helper to draw a QR finder pattern (the corner squares)
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQrFinderPattern(
    x: Float,
    y: Float,
    cellSize: Float,
    color: Color
) {
    // Outer square (3x3 cells)
    drawRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(cellSize * 3, cellSize * 3),
        style = Stroke(width = cellSize * 0.5f)
    )
    // Inner square (1x1 cell in center)
    drawRect(
        color = color,
        topLeft = Offset(x + cellSize, y + cellSize),
        size = Size(cellSize, cellSize)
    )
}

@Composable
private fun QrScannerControls(
    flashEnabled: Boolean,
    onFlashToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Top bar: Close + Flash toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
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

            // Flash toggle
            IconButton(
                onClick = onFlashToggle,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (flashEnabled) "Flash encendido" else "Flash apagado",
                    tint = if (flashEnabled) Color.Yellow else Color.White
                )
            }
        }

        // Title at top
        Text(
            text = "Escanear código QR",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        )

        // Instructions at bottom
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .padding(horizontal = 24.dp)
        ) {
            // Main instruction card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.75f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size(32.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Apunta al código QR del dashboard",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Key instruction: hold steady
                    Text(
                        text = "📌 Mantén la cámara fija sin moverla",
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Additional tips
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        InstructionItem("Centra el QR dentro del recuadro")
                        InstructionItem("Asegúrate de que haya buena iluminación")
                        InstructionItem("Espera a que se detecte automáticamente")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "•",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun QrPermissionDeniedContent(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.QrCodeScanner,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Permiso de Cámara Requerido",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Para escanear el código QR de activación necesitamos acceso a la cámara.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2196F3)
            )
        ) {
            Text("Permitir Acceso")
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onClose) {
            Text("Cancelar", color = Color.White)
        }
    }
}
