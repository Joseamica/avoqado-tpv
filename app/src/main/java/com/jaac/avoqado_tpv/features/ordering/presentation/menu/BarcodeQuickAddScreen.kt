package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen
import timber.log.Timber

/**
 * Barcode Quick Add Screen
 *
 * Wraps BarcodeScannerScreen para agregar productos a una orden mediante escaneo.
 * Similar a Square POS "Scan & Go" feature.
 *
 * **Flujo:**
 * 1. Usuario presiona botón VOLUMEN + en MenuScreen (orden activa)
 * 2. Se abre esta pantalla en fullscreen
 * 3. Escanea múltiples productos rápidamente
 * 4. Cada escaneo → busca producto por SKU → agrega a orden
 * 5. Feedback visual: "✓ Pizza Margherita agregada" o "✗ Producto no encontrado"
 * 6. Tap "Listo" para cerrar y volver a MenuScreen
 *
 * **Square POS Pattern:**
 * - Escaneo continuo (no cierra después de cada código)
 * - Feedback instantáneo (toast-like overlay)
 * - Contador de productos agregados
 * - Botón "Listo" prominente cuando terminan
 *
 * @param onBarcodeScanned Callback cuando se escanea un código (barcode, format)
 * @param onDismiss Callback para cerrar la pantalla
 * @param isProcessing Estado de carga mientras busca producto
 * @param lastScannedProduct Último producto agregado (para feedback visual)
 * @param totalScannedCount Contador de productos escaneados exitosamente
 * @param modifier Modifier for customization
 */
@Composable
fun BarcodeQuickAddScreen(
    onBarcodeScanned: (barcode: String, format: String) -> Unit,
    onDismiss: () -> Unit,
    isProcessing: Boolean = false,
    lastScannedProduct: String? = null,
    totalScannedCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // ✅ Escáner de fondo (fullscreen) - Reutiliza componente existente
        BarcodeScannerScreen(
            onBarcodeScanned = onBarcodeScanned,
            onClose = onDismiss
        )

        // ✅ Overlay superior: Header con botón "Listo"
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Escanear Productos",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$totalScannedCount producto${if (totalScannedCount != 1) "s" else ""} agregado${if (totalScannedCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onDismiss,
                    enabled = !isProcessing
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Listo")
                }
            }
        }

        // ✅ Overlay central: Feedback de último producto escaneado
        // (Toast-like notification - Square POS pattern)
        if (lastScannedProduct != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = lastScannedProduct,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // ✅ Indicador de carga (mientras busca producto en backend)
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }
    }
}
