package com.jaac.avoqado_campo.version

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.Caja
import com.jaac.avoqado_campo.ui.Tono

/** D-2: lo que dicen las pantallas cuyos botones de algo NUEVO quedan en gris con una obligatoria pendiente. */
internal const val ACTUALIZA_PARA_SEGUIR = "Actualiza para seguir vendiendo. Lo que ya hiciste sigue subiendo"
internal const val ACTIVA_EL_PERMISO = "Para actualizar, activa «Permitir de esta fuente» y regresa"

/**
 * El aviso del Inicio. Con [nueva]: «Hay una versión nueva (x)» y «Actualizar» (D-4: NONE y BANNER igual; un celular personal
 * no instala en silencio). Con [obligatoria] y sin nada que ofrecer (la que llegó no pasó la revisión, D-3): avisar al
 * supervisor. [paso] dice en qué va «Actualizar». Ámbar si es obligatoria (frena lo nuevo), azul si sólo informa.
 */
@Composable
fun AvisoDeVersion(nueva: VersionNueva?, obligatoria: Boolean, paso: PasoDeActualizacion, onActualizar: () -> Unit) {
    // Sin nada pendiente no hay aviso, aunque el último intento haya fallado (el superadmin retiró la versión mala).
    if (nueva == null && !obligatoria) return
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
    val tono = if (obligatoria) Tono.AVISO else Tono.INFO
    Caja(tono) {
        lineas.forEachIndexed { i, l ->
            if (i == 0) Text(l, color = tono.tinta, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            else Text(l, color = tono.tinta, style = MaterialTheme.typography.bodyMedium)
        }
        if (nueva != null) {
            val ocupado = paso == PasoDeActualizacion.Bajando || paso == PasoDeActualizacion.Instalando
            val texto = when (paso) {
                PasoDeActualizacion.Bajando -> "Bajando…"
                PasoDeActualizacion.Instalando -> "Instalando…"
                else -> "Actualizar"
            }
            BotonPrincipal(texto, onActualizar, Modifier.padding(top = 4.dp), enabled = !ocupado, cargando = ocupado)
        }
    }
}
