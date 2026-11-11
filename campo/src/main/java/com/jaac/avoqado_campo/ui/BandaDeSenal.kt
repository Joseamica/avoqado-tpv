package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.ui.tema.ColoresCampo

/**
 * Sin red es un estado normal: gris, nunca rojo. Con red: «Subiendo N…», salvo que la subida no pueda correr: con
 * `necesitaPin` espera el PIN, y `DESCONOCIDO` no sube nada (nunca se promete). null = sin banda.
 */
fun textoDeBanda(sinRed: Boolean, estado: EstadoDelCelular, necesitaPin: Boolean, pendientes: Int): String? = when {
    sinRed -> "Sin señal · lo que hagas se guarda en el celular y se sube solo"
    pendientes == 0 || estado == EstadoDelCelular.DESCONOCIDO || estado == EstadoDelCelular.SIN_ACTIVAR -> null
    estado == EstadoDelCelular.VINCULADO && necesitaPin -> "Esperando tu PIN para subir $pendientes"
    else -> "Subiendo $pendientes…"
}

@Composable
fun BandaDeSenal(texto: String?) {
    if (texto == null) return
    Text(
        texto,
        Modifier.fillMaxWidth().background(ColoresCampo.Gris100).padding(horizontal = 16.dp, vertical = 8.dp),
        color = ColoresCampo.Gris500,
        style = MaterialTheme.typography.bodySmall,
    )
}
