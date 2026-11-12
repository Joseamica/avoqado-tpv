package com.jaac.avoqado_campo.ayuda

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.gps.Bateria
import com.jaac.avoqado_campo.ui.Caja
import com.jaac.avoqado_campo.ui.Enlace
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tarjeta
import com.jaac.avoqado_campo.ui.Tenue
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.tema.ColoresCampo

/** «Ayuda» como el mockup: el celular, el aviso de batería y los botones que arreglan solos, sin perder lo que falta subir. */
@Composable
fun AyudaScreen(vm: AyudaViewModel, onRegresar: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ciclo by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val optimizada = remember(ciclo) { Bateria.estaOptimizada(context) }
    var confirmar by remember { mutableStateOf(false) }
    PantallaCampo("Ayuda", onRegresar = onRegresar) {
        Tarjeta {
            Text("Tu celular", fontWeight = FontWeight.Bold)
            Tenue("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app ${BuildConfig.VERSION_NAME}")
        }
        if (optimizada) {
            Caja(Tono.AVISO) {
                Text("🔋 Tu celular puede apagar el GPS del turno para ahorrar batería", color = Tono.AVISO.tinta, fontWeight = FontWeight.SemiBold)
                Bateria.intentParaArreglar(context)?.let { i -> Enlace("Arreglar", Tono.AVISO.tinta, { context.startActivity(i) }) }
            }
        }
        e.mensaje?.let { Recuadro(it, Tono.INFO) }
        Opcion("↻  Actualizar datos", "Baja otra vez tus SIMs y precios. No borra nada tuyo.", vm::actualizarDatos, !e.trabajando)
        Opcion("⇣  Buscar actualización", "Revisa si hay una versión nueva de la app.", vm::buscarActualizacion, !e.trabajando)
        Opcion("⎋  Cerrar sesión", "La próxima vez pide tu PIN.", vm::cerrarSesion, !e.trabajando)
        Opcion(
            "✕  Desvincular este celular",
            if (e.porSubir > 0) "No disponible: primero sube lo pendiente (${e.porSubir})." else "El celular queda vacío; para usarlo otra vez necesitas un código nuevo.",
            { confirmar = true }, !e.trabajando && e.porSubir == 0, avisoSiNo = e.porSubir > 0,
        )
    }
    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            text = { Text("El celular queda vacío y para usarlo otra vez necesitas un código nuevo de tu supervisor.") },
            confirmButton = { TextButton({ confirmar = false; vm.desvincular() }) { Text("Desvincular") } },
            dismissButton = { TextButton({ confirmar = false }) { Text("Cancelar") } },
        )
    }
}

/** Un botón de ayuda con su explicación debajo (el `.cp-btn2` alineado a la izquierda del mockup). */
@Composable
private fun Opcion(titulo: String, explicacion: String, onClick: () -> Unit, enabled: Boolean, avisoSiNo: Boolean = false) {
    Surface(
        onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.small,
        color = if (enabled) ColoresCampo.Blanco else ColoresCampo.Gris100, border = BorderStroke(1.dp, if (enabled) ColoresCampo.Gris300 else ColoresCampo.Gris200),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, fontWeight = FontWeight.SemiBold, color = if (enabled) ColoresCampo.Gris900 else ColoresCampo.Gris400)
            Text(
                explicacion, style = MaterialTheme.typography.bodySmall,
                color = if (!enabled && avisoSiNo) ColoresCampo.NaranjaSinRed else ColoresCampo.Gris500,
            )
        }
    }
}
