package com.jaac.avoqado_campo.fotos

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview as VistaPrevia
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Pide la cámara con su porqué en español; mientras no la den, explica y ofrece pedirla otra vez. */
@Composable
fun ConCamara(para: String, onCerrar: () -> Unit, contenido: @Composable () -> Unit) {
    val context = LocalContext.current
    var permitida by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var negada by remember { mutableStateOf(false) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitida = it; negada = !it }
    LaunchedEffect(Unit) { if (!permitida) pedir.launch(Manifest.permission.CAMERA) }
    if (permitida) contenido() else PermisoDeCamara(para, negada, { pedir.launch(Manifest.permission.CAMERA) }, onCerrar)
}

@Composable
private fun PermisoDeCamara(para: String, negada: Boolean, onPedir: () -> Unit, onCerrar: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("La app necesita la cámara", style = MaterialTheme.typography.headlineSmall)
            Text("La usamos $para. Las fotos sólo se suben como evidencia de tu trabajo.")
            if (negada) {
                Text(
                    "Si ya no aparece la pregunta, actívala en Ajustes del celular › Apps › Avoqado Campo › Permisos › Cámara.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onPedir, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Permitir la cámara") }
            OutlinedButton(onCerrar, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Regresar") }
        }
    }
}

/**
 * Toma UNA foto con CameraX (trasera o [frontal]), la comprime ([Compresor]) a `cacheDir/fotos/` y entrega esa ruta. La
 * cruda se borra. Como `CameraPreviewScreen` de la PAX, sin el flash ni la galería.
 */
@Composable
fun Camara(titulo: String, frontal: Boolean = false, onFoto: (File) -> Unit, onCerrar: () -> Unit) =
    ConCamara("para tomar $titulo", onCerrar) {
        val context = LocalContext.current
        val ciclo = LocalLifecycleOwner.current
        val alcance = rememberCoroutineScope()
        val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
        var proveedor by remember { mutableStateOf<ProcessCameraProvider?>(null) }
        var tomando by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        DisposableEffect(Unit) { onDispose { proveedor?.unbindAll() } } // suelta la cámara para el escáner (Camera1)

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { vista ->
                        val futuro = ProcessCameraProvider.getInstance(ctx)
                        futuro.addListener({
                            val p = futuro.get().also { proveedor = it }
                            val previa = Preview.Builder().build().also { it.setSurfaceProvider(vista.surfaceProvider) }
                            val lado = if (frontal) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                            runCatching { p.unbindAll(); p.bindToLifecycle(ciclo, lado, previa, captura) }
                                .onFailure { error = "No se pudo abrir la cámara." }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(titulo, color = Color.White, style = MaterialTheme.typography.titleMedium)
                error?.let { Text(it, color = Color.White) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onCerrar, Modifier.heightIn(min = 48.dp)) { Text("Cancelar", color = Color.White) }
                    Button(
                        onClick = {
                            tomando = true
                            error = null
                            alcance.launch {
                                runCatching { tomarYComprimir(context, captura) }
                                    .onSuccess(onFoto)
                                    .onFailure { error = "No se tomó la foto. Intenta otra vez." }
                                tomando = false
                            }
                        },
                        enabled = !tomando && proveedor != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (tomando) "Guardando…" else "Tomar foto") }
                }
            }
        }
    }

private suspend fun tomarYComprimir(context: android.content.Context, captura: ImageCapture): File {
    val dir = File(context.cacheDir, "fotos").apply { mkdirs() }
    val nombre = UUID.randomUUID().toString()
    val cruda = File(dir, "$nombre-cruda.jpg")
    suspendCancellableCoroutine { cont ->
        captura.takePicture(
            ImageCapture.OutputFileOptions.Builder(cruda).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) = cont.resume(Unit)
                override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
            },
        )
    }
    // ponytail: una foto retomada deja la anterior en cacheDir; Android limpia cacheDir cuando le falta espacio.
    return withContext(Dispatchers.IO) {
        val lista = File(dir, "$nombre.jpg")
        try { Compresor.comprimir(cruda, lista) } finally { cruda.delete() }
        lista
    }
}

@VistaPrevia(widthDp = 360, heightDp = 640)
@Composable
private fun PermisoPreview() = TemaCampo {
    PermisoDeCamara("para escanear la SIM y tomar las fotos de la venta", negada = true, onPedir = {}, onCerrar = {})
}
