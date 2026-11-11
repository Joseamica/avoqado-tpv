package com.jaac.avoqado_campo.version

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** D-2: lo que dicen las pantallas cuyos botones de algo NUEVO quedan en gris con una obligatoria pendiente. */
internal const val ACTUALIZA_PARA_SEGUIR = "Actualiza para seguir vendiendo. Lo que ya hiciste sigue subiendo"
internal const val ACTIVA_EL_PERMISO = "Para actualizar, activa «Permitir de esta fuente» y regresa"

/**
 * El aviso del Inicio. Con [nueva]: «Hay una versión nueva (x)» y «Actualizar» (D-4: NONE y BANNER igual; un celular personal
 * no instala en silencio). Con [obligatoria] y sin nada que ofrecer (la que llegó no pasó la revisión, D-3): avisar al
 * supervisor. [paso] dice en qué va «Actualizar».
 */
@Composable
fun AvisoDeVersion(nueva: VersionNueva?, obligatoria: Boolean, paso: PasoDeActualizacion, onActualizar: () -> Unit) {
    val lineas = listOfNotNull(
        nueva?.let { "Hay una versión nueva (${it.versionName})" } ?: NO_SE_PUDO_COMPROBAR.takeIf { obligatoria },
        ACTUALIZA_PARA_SEGUIR.takeIf { obligatoria },
        when (paso) {
            PasoDeActualizacion.FaltaPermiso -> ACTIVA_EL_PERMISO
            is PasoDeActualizacion.Fallo -> paso.texto
            else -> null
        },
    ).distinct()
    if (lineas.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lineas.forEachIndexed { i, l -> Text(l, style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium) }
            if (nueva != null) {
                val ocupado = paso == PasoDeActualizacion.Bajando || paso == PasoDeActualizacion.Instalando
                Button(onActualizar, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !ocupado) {
                    Text(
                        when (paso) {
                            PasoDeActualizacion.Bajando -> "Bajando…"
                            PasoDeActualizacion.Instalando -> "Instalando…"
                            else -> "Actualizar"
                        },
                    )
                }
            }
        }
    }
}
