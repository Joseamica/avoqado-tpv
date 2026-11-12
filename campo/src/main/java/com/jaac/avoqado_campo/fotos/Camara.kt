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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview as VistaPrevia
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jaac.avoqado_campo.ui.ANCHO_MAXIMO
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.BotonSecundario
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    PantallaCampo(
        "La app necesita la cámara", onRegresar = onCerrar,
        abajo = {
            BotonPrincipal("Permitir la cámara", onPedir)
            BotonSecundario("Regresar", onCerrar)
        },
    ) {
        Text("La usamos $para. Las fotos sólo se suben como evidencia de tu trabajo.", style = MaterialTheme.typography.bodyLarge)
        if (negada) {
            Recuadro("Si ya no aparece la pregunta, actívala en Ajustes del celular › Apps › Avoqado Campo › Permisos › Cámara.", Tono.AVISO)
        }
    }
}

/**
 * Toma UNA foto con CameraX (trasera o [frontal]), la comprime ([Compresor]) a `cacheDir/fotos/` y entrega esa ruta. La
 * cruda se borra. Como `CameraPreviewScreen` de la PAX, sin el flash ni la galería. Con [frontal] (la selfie) dibuja el óvalo
 * del mockup para acomodar la cara; [extra] va arriba de los botones (p. ej. el estado de la ubicación al checar).
 */
@Composable
fun Camara(
    titulo: String, frontal: Boolean = false, onFoto: (File) -> Unit, onCerrar: () -> Unit,
    textoDelBoton: String = "Tomar foto", extra: (@Composable () -> Unit)? = null,
) =
    ConCamara("para tomar $titulo", onCerrar) {
        val context = LocalContext.current
        val ciclo = LocalLifecycleOwner.current
        val alcance = rememberCoroutineScope()
        val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
        var proveedor by remember { mutableStateOf<ProcessCameraProvider?>(null) }
        var tomando by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val cerrada = remember { AtomicBoolean(false) }
        // Suelta la cámara para el escáner (Camera1); si el proveedor llega después de cerrar, ya no se abre.
        DisposableEffect(Unit) { onDispose { cerrada.set(true); proveedor?.unbindAll() } }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { vista ->
                        val futuro = ProcessCameraProvider.getInstance(ctx)
                        futuro.addListener({
                            if (cerrada.get()) return@addListener
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
            if (frontal) Ovalo(Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(titulo.replaceFirstChar { it.uppercase() }, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.widthIn(max = ANCHO_MAXIMO), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    extra?.invoke()
                    error?.let { Recuadro(it, Tono.ERROR) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BotonSecundario("Cancelar", onCerrar, Modifier.weight(1f))
                        BotonPrincipal(
                            if (tomando) "Guardando…" else textoDelBoton,
                            {
                                tomando = true
                                error = null
                                alcance.launch {
                                    runCatching { tomarYComprimir(context, captura) }
                                        .onSuccess(onFoto)
                                        .onFailure { error = "No se tomó la foto. Intenta otra vez." }
                                    tomando = false
                                }
                            },
                            Modifier.weight(1.4f),
                            enabled = !tomando && proveedor != null,
                            cargando = tomando,
                        )
                    }
                }
            }
        }
    }

/** El óvalo punteado de la selfie (el `.cp-cam` del mockup): sólo guía, no recorta la foto. */
@Composable
private fun Ovalo(modifier: Modifier) {
    Canvas(modifier) {
        val ancho = minOf(size.width * 0.62f, size.height * 0.42f)
        val alto = ancho * 1.3f
        drawOval(
            color = Color.White,
            topLeft = Offset((size.width - ancho) / 2, size.height * 0.36f - alto / 2),
            size = Size(ancho, alto),
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
        )
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
