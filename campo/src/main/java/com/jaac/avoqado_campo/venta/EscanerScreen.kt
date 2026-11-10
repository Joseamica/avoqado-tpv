package com.jaac.avoqado_campo.venta

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.jaac.avoqado_campo.fotos.ConCamara
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings

private val FORMATOS = listOf(
    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE,
)

/**
 * Copiado de `BarcodeScannerScreen` de la PAX (ZXing, Camera1, cámara trasera). Como al dar de alta una SIM, exige el mismo
 * código en dos cuadros seguidos (700 ms) para no tomar un dígito mal leído. Entrega el texto crudo UNA vez.
 */
@Composable
fun EscanerScreen(onCodigo: (String) -> Unit, onCerrar: () -> Unit) =
    ConCamara("para escanear el código de la SIM", onCerrar) {
        val entregar by rememberUpdatedState(onCodigo)
        var vista by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
        var linterna by remember { mutableStateOf(false) }
        val principal = remember { Handler(Looper.getMainLooper()) }
        LaunchedEffect(linterna, vista) { vista?.let { if (linterna) it.setTorchOn() else it.setTorchOff() } }
        // Suelta la cámara antes de que CameraX la pida (Camera1 y Camera2 no conviven).
        DisposableEffect(Unit) { onDispose { vista?.let { it.setTorchOff(); it.barcodeView.pauseAndWait() } } }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    var candidato: String? = null
                    var candidatoAt = 0L
                    var entregado = false
                    DecoratedBarcodeView(ctx).apply {
                        barcodeView.cameraSettings = CameraSettings().apply {
                            requestedCameraId = 0
                            isAutoFocusEnabled = true
                            isContinuousFocusEnabled = true
                            isBarcodeSceneModeEnabled = true
                        }
                        barcodeView.decoderFactory =
                            DefaultDecoderFactory(FORMATOS, mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to FORMATOS), null, 0)
                        decodeContinuous(BarcodeCallback { r ->
                            val texto = r?.text?.takeIf { it.isNotBlank() } ?: return@BarcodeCallback
                            val ahora = System.currentTimeMillis()
                            if (entregado) return@BarcodeCallback
                            if (texto == candidato && ahora - candidatoAt <= 700) {
                                entregado = true
                                entregar(texto)
                            } else {
                                candidato = texto
                                candidatoAt = ahora
                            }
                        })
                        statusView.visibility = View.GONE
                        // La superficie tiene que existir antes de abrir la cámara (igual que en la PAX).
                        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                principal.postDelayed({ runCatching { resume() } }, 300)
                            }
                        })
                        vista = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Apunta al código de barras de la SIM", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onCerrar, Modifier.heightIn(min = 48.dp)) { Text("Cerrar", color = Color.White) }
                    Button({ linterna = !linterna }, Modifier.heightIn(min = 48.dp)) { Text(if (linterna) "Apagar luz" else "Prender luz") }
                }
            }
        }
    }
