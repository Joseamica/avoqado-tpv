package com.jaac.avoqado_tpv.features.verification.presentation.components

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Camera state
    var hasCameraPermission by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Check permission on launch
    LaunchedEffect(Unit) {
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
                onCapture = {
                    val capture = imageCapture ?: return@CameraControls
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
                },
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

        // Instructions
        Text(
            text = "Toma una foto de la venta",
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
            text = "Permiso de Camara Requerido",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Para capturar fotos de verificacion necesitamos acceso a la camara.",
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
