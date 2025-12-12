package com.jaac.avoqado_tpv.features.verification.presentation.components

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
 * Barcode Scanner Screen for Step 4 verification.
 *
 * **Features:**
 * - ZXing barcode scanning (pure Java, compatible with armeabi)
 * - Supports: EAN-13, EAN-8, UPC-A, UPC-E, Code 128, QR Code
 * - Visual scan area overlay
 * - Flash toggle
 * - Auto-close after successful scan
 *
 * **Note:**
 * Using ZXing instead of ML Kit because ML Kit requires armeabi-v7a
 * but PAX devices only support armeabi (required by Blumon SDK).
 *
 * **Camera Initialization:**
 * ZXing uses Camera1 API which requires careful lifecycle management.
 * We delay camera start until the view is fully attached to ensure
 * the preview surface is ready.
 *
 * @param onBarcodeScanned Callback with barcode value and format
 * @param onClose Callback to close scanner
 */
@Composable
fun BarcodeScannerScreen(
    onBarcodeScanned: (barcode: String, format: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Camera state
    var hasCameraPermission by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var lastScannedBarcode by remember { mutableStateOf<String?>(null) }
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    // Note: Camera is started internally by ZXingBarcodeScanner via ViewTreeObserver

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        Timber.d("📷 [BarcodeScannerScreen] Permission granted: $granted")
        // Don't start camera here - let the LaunchedEffect handle it
    }

    // Check permission on launch with delay to let CameraX release
    LaunchedEffect(Unit) {
        // ⚠️ Small delay to ensure CameraX (photo capture) has fully released the camera
        // This prevents conflict between Camera2 (CameraX) and Camera1 (ZXing)
        kotlinx.coroutines.delay(300)
        Timber.d("📷 [BarcodeScannerScreen] Starting after delay")

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

    // ⚠️ Camera is now started internally by ZXingBarcodeScanner via ViewTreeObserver
    // No need for external LaunchedEffect to call resume()

    // Toggle flash - only works after camera is started
    LaunchedEffect(flashEnabled, barcodeView) {
        barcodeView?.let { view ->
            if (flashEnabled) {
                view.setTorchOn()
            } else {
                view.setTorchOff()
            }
        }
    }

    // ⚠️ CRITICAL: Cleanup camera on dispose to prevent conflict with CameraX
    DisposableEffect(Unit) {
        onDispose {
            Timber.d("📷 [BarcodeScannerScreen] Disposing - stopping scanner")
            barcodeView?.let { view ->
                view.setTorchOff()
                // Use pauseAndWait() to block until camera is released (prevents conflict with CameraX)
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
            // ZXing Camera preview with barcode analysis
            ZXingBarcodeScanner(
                onBarcodeViewReady = { view ->
                    Timber.d("📷 [BarcodeScannerScreen] BarcodeView ready, storing reference")
                    barcodeView = view
                    // ⚠️ DON'T start camera here - let LaunchedEffect handle timing
                },
                onBarcodeDetected = { barcode, format ->
                    // Prevent duplicate scans
                    if (barcode != lastScannedBarcode) {
                        lastScannedBarcode = barcode
                        Timber.d("✅ [BarcodeScannerScreen] Barcode scanned: $barcode (format: $format)")
                        onBarcodeScanned(barcode, format)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Scan area overlay
            ScanAreaOverlay(modifier = Modifier.fillMaxSize())

            // Controls overlay
            ScannerControls(
                flashEnabled = flashEnabled,
                onFlashToggle = { flashEnabled = !flashEnabled },
                onClose = onClose,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Permission denied
            PermissionDeniedContent(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onClose = onClose
            )
        }
    }
}

/**
 * ZXing Barcode Scanner View wrapper for Compose.
 *
 * **PAX A910S Camera Fix:**
 * - Uses Camera1 API (ZXing default)
 * - Explicitly configures back camera (ID 0) since PAX only has one camera
 * - Uses ViewTreeObserver to wait for view layout before starting camera
 * - Handler.postDelayed ensures camera starts after surface is ready
 *
 * ⚠️ Camera is started internally when view is laid out - no external resume() needed
 */
@Composable
private fun ZXingBarcodeScanner(
    onBarcodeViewReady: (DecoratedBarcodeView) -> Unit,
    onBarcodeDetected: (barcode: String, format: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Supported barcode formats
    val formats = listOf(
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.ITF,
        BarcodeFormat.CODABAR,
        BarcodeFormat.PDF_417,
        BarcodeFormat.AZTEC
    )

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        factory = { ctx ->
            Timber.d("📷 [ZXingBarcodeScanner] Creating DecoratedBarcodeView")

            DecoratedBarcodeView(ctx).apply {
                // ⚠️ CRITICAL: Configure camera settings BEFORE anything else
                // PAX A910S only has back camera (ID 0)
                val cameraSettings = CameraSettings().apply {
                    requestedCameraId = 0  // Back camera
                    isAutoFocusEnabled = true
                    isContinuousFocusEnabled = true
                    isMeteringEnabled = true
                    isAutoTorchEnabled = false
                    isBarcodeSceneModeEnabled = true
                }
                barcodeView.cameraSettings = cameraSettings
                Timber.d("📷 [ZXingBarcodeScanner] Camera settings configured: cameraId=0 (back)")

                // Configure decoder with hints for better 1D barcode detection
                // ⚠️ PAX A910S has FIXED FOCUS camera - 1D barcodes need extra help
                val hints = mapOf(
                    DecodeHintType.TRY_HARDER to true,  // Spend more time trying to find barcodes
                    DecodeHintType.POSSIBLE_FORMATS to formats,
                    DecodeHintType.ALSO_INVERTED to true  // Also try inverted (white on black)
                )
                barcodeView.decoderFactory = DefaultDecoderFactory(formats, hints, null, 0)

                // Set continuous decoding
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        result?.let {
                            val barcode = it.text
                            val format = it.barcodeFormat?.name ?: "UNKNOWN"
                            if (!barcode.isNullOrBlank()) {
                                Timber.d("📷 [ZXingBarcodeScanner] Barcode detected: $barcode ($format)")
                                onBarcodeDetected(barcode, format)
                            }
                        }
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                        // Optional: Can be used to show scan feedback
                    }
                })

                // Hide default status text
                statusView.visibility = android.view.View.GONE

                // ⚠️ CRITICAL: Wait for view to be laid out before starting camera
                // This ensures the SurfaceView has a valid surface
                viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        // Remove listener to prevent multiple calls
                        viewTreeObserver.removeOnGlobalLayoutListener(this)

                        Timber.d("📷 [ZXingBarcodeScanner] View laid out (${width}x${height}), scheduling camera start")

                        // Post delayed to ensure surface is fully ready
                        mainHandler.postDelayed({
                            try {
                                Timber.d("📷 [ZXingBarcodeScanner] Starting camera via resume()")
                                resume()
                                Timber.d("📷 [ZXingBarcodeScanner] ✅ Camera started successfully")
                            } catch (e: Exception) {
                                Timber.e(e, "📷 [ZXingBarcodeScanner] ❌ Failed to start camera")
                            }
                        }, 500)  // 500ms delay to ensure surface is ready
                    }
                })

                Timber.d("📷 [ZXingBarcodeScanner] View configured, notifying parent")
                onBarcodeViewReady(this)
            }
        },
        modifier = modifier
        // ⚠️ NO update block - camera started via ViewTreeObserver
    )
}

@Composable
private fun ScanAreaOverlay(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            // ⚠️ CRITICAL: Required for BlendMode.Clear to work correctly
            // Without this, "clear" shows as BLACK instead of transparent
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val scanAreaSize = size.minDimension * 0.6f
        val scanAreaLeft = (size.width - scanAreaSize) / 2
        val scanAreaTop = (size.height - scanAreaSize) / 2

        // Dark overlay
        drawRect(
            color = Color.Black.copy(alpha = 0.6f),
            size = size
        )

        // Clear scan area
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(scanAreaLeft, scanAreaTop),
            size = Size(scanAreaSize, scanAreaSize),
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        // Scan area border
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(scanAreaLeft, scanAreaTop),
            size = Size(scanAreaSize, scanAreaSize),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 3.dp.toPx())
        )

        // Corner accents
        val cornerLength = 40.dp.toPx()
        val cornerStroke = 5.dp.toPx()
        val accentColor = Color(0xFF4CAF50)  // Green

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
            start = Offset(scanAreaLeft + scanAreaSize, scanAreaTop + cornerLength),
            end = Offset(scanAreaLeft + scanAreaSize, scanAreaTop),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanAreaSize, scanAreaTop),
            end = Offset(scanAreaLeft + scanAreaSize - cornerLength, scanAreaTop),
            strokeWidth = cornerStroke
        )

        // Bottom-left corner
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft, scanAreaTop + scanAreaSize - cornerLength),
            end = Offset(scanAreaLeft, scanAreaTop + scanAreaSize),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft, scanAreaTop + scanAreaSize),
            end = Offset(scanAreaLeft + cornerLength, scanAreaTop + scanAreaSize),
            strokeWidth = cornerStroke
        )

        // Bottom-right corner
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanAreaSize, scanAreaTop + scanAreaSize - cornerLength),
            end = Offset(scanAreaLeft + scanAreaSize, scanAreaTop + scanAreaSize),
            strokeWidth = cornerStroke
        )
        drawLine(
            color = accentColor,
            start = Offset(scanAreaLeft + scanAreaSize, scanAreaTop + scanAreaSize),
            end = Offset(scanAreaLeft + scanAreaSize - cornerLength, scanAreaTop + scanAreaSize),
            strokeWidth = cornerStroke
        )
    }
}

@Composable
private fun ScannerControls(
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

        // Instructions
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Coloca el codigo de barras dentro del area",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PermissionDeniedContent(
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
            text = "Permiso de Camara Requerido",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Para escanear codigos de barras necesitamos acceso a la camara.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
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
