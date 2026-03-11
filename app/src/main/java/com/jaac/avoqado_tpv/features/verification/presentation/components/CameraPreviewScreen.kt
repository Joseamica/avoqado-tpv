package com.jaac.avoqado_tpv.features.verification.presentation.components

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Intent action for volume button camera capture.
 * MainActivity broadcasts this when volume up is pressed and camera is active.
 */
const val ACTION_CAPTURE_PHOTO = "com.jaac.avoqado_tpv.CAPTURE_PHOTO"

/**
 * Companion object to track if camera is currently active.
 * MainActivity checks this before broadcasting CAPTURE_PHOTO intent.
 */
object CameraState {
    var isActive: Boolean = false
}

/**
 * Camera Preview Screen for Step 4 verification photo capture.
 *
 * **Features:**
 * - CameraX preview with back camera
 * - Flash toggle (on/off)
 * - Capture button with visual feedback
 * - Permission handling
 *
 * **Usage:**
 * Called when user taps "Tomar Foto" in VerificationScreen.
 * Returns captured photo path via onPhotoCaptured callback.
 *
 * @param onPhotoCaptured Callback with local file path of captured photo
 * @param onClose Callback to close camera preview
 * @param outputDirectory Directory to save captured photos
 */
@Composable
fun CameraPreviewScreen(
    onPhotoCaptured: (String) -> Unit,
    onClose: () -> Unit,
    outputDirectory: File,
    modifier: Modifier = Modifier,
    photoLabel: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Camera and Location state
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // Capture function that can be called from multiple sources (button or volume key)
    val performCapture: () -> Unit = {
        val capture = imageCapture
        if (capture != null && !isCapturing) {
            isCapturing = true
            scope.launch {
                try {
                    val photoFile = capturePhoto(
                        context = context,
                        imageCapture = capture,
                        outputDirectory = outputDirectory,
                        flashEnabled = flashEnabled
                    )
                    onPhotoCaptured(photoFile.absolutePath)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture photo")
                } finally {
                    isCapturing = false
                }
            }
        }
    }

    // Track camera active state for MainActivity volume button handling
    DisposableEffect(Unit) {
        CameraState.isActive = true
        Timber.d("📸 [CameraPreview] Camera active - volume button will capture photo")
        onDispose {
            CameraState.isActive = false
            Timber.d("📸 [CameraPreview] Camera inactive")
        }
    }

    // BroadcastReceiver for volume button capture
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CAPTURE_PHOTO) {
                    Timber.d("📸 [CameraPreview] Volume button capture triggered")
                    performCapture()
                }
            }
        }

        val filter = IntentFilter(ACTION_CAPTURE_PHOTO)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Permission launcher for both Camera and Location
    // Location is needed because GPS is automatically captured when taking clock-in/out photos
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        Timber.d("📸 Permissions granted - Camera: $hasCameraPermission, Location: $hasLocationPermission")
    }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasCameraPermission = cameraGranted
        hasLocationPermission = fineLocationGranted || coarseLocationGranted

        Timber.d("📸 Initial permissions - Camera: $cameraGranted, Fine Location: $fineLocationGranted, Coarse Location: $coarseLocationGranted")

        // Request all missing permissions at once
        val permissionsToRequest = mutableListOf<String>()
        if (!cameraGranted) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (!fineLocationGranted && !coarseLocationGranted) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Timber.d("📸 Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            // Camera preview
            CameraPreview(
                onImageCaptureReady = { capture ->
                    imageCapture = capture
                },
                flashEnabled = flashEnabled,
                modifier = Modifier.fillMaxSize()
            )

            // Controls overlay
            CameraControls(
                flashEnabled = flashEnabled,
                isCapturing = isCapturing,
                onFlashToggle = { flashEnabled = !flashEnabled },
                onCapture = performCapture,
                onClose = onClose,
                photoLabel = photoLabel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Permission denied - request both camera and location
            PermissionDeniedContent(
                onRequestPermission = {
                    val permissions = arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    permissionLauncher.launch(permissions)
                },
                onClose = onClose
            )
        }
    }
}

@Composable
private fun CameraPreview(
    onImageCaptureReady: (ImageCapture) -> Unit,
    flashEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(android.view.Surface.ROTATION_0)
            .build()
    }
    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Store camera provider for cleanup
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Update flash mode
    LaunchedEffect(flashEnabled) {
        imageCapture.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
    }

    // Provide imageCapture to parent
    LaunchedEffect(imageCapture) {
        onImageCaptureReady(imageCapture)
    }

    // ⚠️ CRITICAL: Cleanup camera on dispose to prevent conflict with ZXing barcode scanner
    DisposableEffect(Unit) {
        onDispose {
            Timber.d("📸 [CameraPreview] Disposing - unbinding camera")
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                cameraProvider = provider  // Store for cleanup

                // Unbind previous use cases
                provider.unbindAll()

                // Bind to lifecycle
                try {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    Timber.d("📸 [CameraPreview] Camera bound successfully")
                } catch (e: Exception) {
                    Timber.e(e, "Camera binding failed")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}

@Composable
private fun CameraControls(
    flashEnabled: Boolean,
    isCapturing: Boolean,
    onFlashToggle: () -> Unit,
    onCapture: () -> Unit,
    onClose: () -> Unit,
    photoLabel: String? = null,
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

        // Volume button hint - positioned on the LEFT side, ~25% from top (where PAX volume buttons are)
        VolumeButtonHint(
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = -1f, verticalBias = -0.5f))  // -0.5 = 25% from top
                .padding(start = 16.dp)
        )

        // Bottom: Capture button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
        ) {
            // Capture button
            IconButton(
                onClick = onCapture,
                enabled = !isCapturing,
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = if (isCapturing) Color.Gray else Color.White,
                        shape = CircleShape
                    )
                    .border(
                        width = 4.dp,
                        color = Color.White.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Capturar",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Photo label banner (e.g., "1. Vinculación" or "2. Portabilidad")
        if (photoLabel != null) {
            val labelColor = MaterialTheme.avoqadoColors.statusInfo
            Text(
                text = photoLabel,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(
                        color = labelColor.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        // Instructions
        Text(
            text = if (photoLabel != null) "Toma foto de $photoLabel" else "Toma una foto de verificacion",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * Volume button hint with arrow pointing to the physical volume up button location.
 * On PAX terminals, volume buttons are on the LEFT side, near the top.
 * Shows a pill-shaped badge with volume icon and instruction text.
 */
@Composable
private fun VolumeButtonHint(
    modifier: Modifier = Modifier
) {
    val hintColor = MaterialTheme.avoqadoColors.statusSuccess
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start  // Align to left
    ) {
        // Arrow pointing UP-LEFT toward volume button (PAX has buttons on left side)
        Canvas(
            modifier = Modifier
                .width(40.dp)
                .height(50.dp)
                .padding(start = 8.dp)
        ) {
            val arrowColor = hintColor

            // Draw curved arrow pointing UP-LEFT
            val path = Path().apply {
                // Start from bottom-right of the canvas
                moveTo(size.width * 0.8f, size.height * 0.9f)
                // Curve up and to the LEFT
                quadraticTo(
                    size.width * 0.5f, size.height * 0.3f,  // Control point
                    size.width * 0.1f, size.height * 0.1f   // End point (top-LEFT)
                )
            }
            drawPath(
                path = path,
                color = arrowColor,
                style = Stroke(width = 4f)
            )

            // Arrowhead pointing up-left
            val arrowHeadPath = Path().apply {
                moveTo(size.width * 0.1f, size.height * 0.1f)
                lineTo(size.width * 0.35f, size.height * 0.15f)  // Right arm
                moveTo(size.width * 0.1f, size.height * 0.1f)
                lineTo(size.width * 0.15f, size.height * 0.35f)  // Down arm
            }
            drawPath(
                path = arrowHeadPath,
                color = arrowColor,
                style = Stroke(width = 4f)
            )
        }

        // Hint badge
        Row(
            modifier = Modifier
                .background(
                    color = hintColor,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Botón de Volumen = Foto",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
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
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Permisos Requeridos",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Para capturar fotos de verificación con ubicación GPS necesitamos acceso a la cámara y ubicación.",
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
            Text("Permitir Permisos")
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onClose) {
            Text("Cancelar", color = Color.White)
        }
    }
}

/**
 * Capture photo using ImageCapture.
 *
 * @param context Android context
 * @param imageCapture ImageCapture use case
 * @param outputDirectory Directory to save photos
 * @param flashEnabled Whether flash is enabled
 * @return File path of captured photo
 */
private suspend fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    outputDirectory: File,
    flashEnabled: Boolean
): File = suspendCoroutine { continuation ->
    // Create output file
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(outputDirectory, "VERIFICATION_${timestamp}.jpg")

    // Set flash mode
    imageCapture.flashMode = if (flashEnabled) {
        ImageCapture.FLASH_MODE_ON
    } else {
        ImageCapture.FLASH_MODE_OFF
    }

    // Create output options
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    // Capture image
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Timber.d("Photo captured: ${photoFile.absolutePath}")
                continuation.resume(photoFile)
            }

            override fun onError(exception: ImageCaptureException) {
                Timber.e(exception, "Photo capture failed")
                continuation.resumeWith(Result.failure(exception))
            }
        }
    )
}
