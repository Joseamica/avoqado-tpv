package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.presentation.AvisoSinConexion

/** Room conserva los conteos aunque se cierre la pantalla o vuelva el internet. */
@Composable
fun AvisoSinConexionDeCobrar(aviso: AvisoSinConexion, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(aviso.texto, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AvisoSinConexionPreview() {
    AvoqadoTheme { AvisoSinConexionDeCobrar(AvisoSinConexion(sinRed = true, guardadas = 12, conProblema = 2)) }
}
