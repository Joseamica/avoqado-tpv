package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.core.presentation.systemui.ImmersiveSystemUiEffect
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * 💵 «Cobrar» sin red: un carrito en $0 (cortesía o descuento completo) que ya quedó guardado en la terminal sin pasar por la pantalla
 * de cobro. Es un estado normal, no un error: nada de rojo. «Imprimir ticket» puede tocarse varias veces; «Listo» (o el atrás) cierra
 * el comprobante. El carrito ya se consumió al guardar la venta; el ViewModel conserva el ticket si la pantalla se recrea.
 */
@Composable
fun VentaEnCeroGuardadaDialog(
    articulos: Int,
    imprimiendo: Boolean,
    errorDeImpresion: String?,
    onImprimir: () -> Unit,
    onListo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onListo,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Venta en \$0 guardada") },
        text = {
            Column {
                ImmersiveSystemUiEffect()
                Text(
                    text = "${if (articulos == 1) "1 artículo" else "$articulos artículos"} sin cobro. " +
                        "Quedó guardada en la terminal y se registra sola.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (errorDeImpresion != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorDeImpresion,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onImprimir, enabled = !imprimiendo) {
                Text(if (imprimiendo) "Imprimiendo…" else "Imprimir ticket")
            }
        },
        confirmButton = {
            TextButton(onClick = onListo) { Text("Listo") }
        },
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun VentaEnCeroGuardadaDialogPreview() {
    AvoqadoTheme {
        VentaEnCeroGuardadaDialog(articulos = 2, imprimiendo = false, errorDeImpresion = null, onImprimir = {}, onListo = {})
    }
}
