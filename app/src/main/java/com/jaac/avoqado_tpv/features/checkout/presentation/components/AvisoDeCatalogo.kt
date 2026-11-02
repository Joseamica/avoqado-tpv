package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** El fallo del catálogo permanece visible también dentro de las hojas que tapan la cuadrícula. */
@Composable
fun AvisoDeCatalogo(errorCatalogo: String?, isLoading: Boolean, onRetry: () -> Unit) {
    if (errorCatalogo != null) {
        Text(errorCatalogo, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry, enabled = !isLoading) { Text("Reintentar") }
    }
}
