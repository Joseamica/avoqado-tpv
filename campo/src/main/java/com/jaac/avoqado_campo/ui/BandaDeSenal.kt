package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_campo.ui.tema.ColoresCampo

/** Sin red es un estado normal: gris, nunca rojo. Con red y algo por subir: «Subiendo N…». */
@Composable
fun BandaDeSenal(sinRed: Boolean, subiendo: Int) {
    val texto = when {
        sinRed -> "Sin señal · lo que hagas se guarda en el celular y se sube solo"
        subiendo > 0 -> "Subiendo $subiendo…"
        else -> return
    }
    Text(
        texto,
        Modifier.fillMaxWidth().background(ColoresCampo.Gris100).padding(horizontal = 16.dp, vertical = 8.dp),
        color = ColoresCampo.Gris500,
        style = MaterialTheme.typography.bodySmall,
    )
}
