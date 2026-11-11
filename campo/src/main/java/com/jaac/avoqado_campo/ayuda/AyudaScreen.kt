package com.jaac.avoqado_campo.ayuda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.gps.Bateria

private val alto = Modifier.fillMaxWidth().heightIn(min = 48.dp)

@Composable
fun AyudaScreen(vm: AyudaViewModel, onRegresar: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ciclo by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val optimizada = remember(ciclo) { Bateria.estaOptimizada(context) }
    var confirmar by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ayuda", style = MaterialTheme.typography.headlineSmall)
            Button(vm::actualizarDatos, alto, enabled = !e.trabajando) { Text("Actualizar datos") }
            OutlinedButton(vm::cerrarSesion, alto, enabled = !e.trabajando) { Text("Cerrar sesión") }
            OutlinedButton({ confirmar = true }, alto, enabled = !e.trabajando && e.porSubir == 0) { Text("Desvincular este celular") }
            if (e.porSubir > 0) Text("Primero sube lo pendiente (${e.porSubir})", color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.mensaje?.let { Text(it) }
            if (optimizada) {
                Text("Tu celular puede apagar el GPS del turno para ahorrar batería")
                Bateria.intentParaArreglar(context)?.let { i -> OutlinedButton({ context.startActivity(i) }, alto) { Text("Arreglar") } }
            }
            OutlinedButton(onRegresar, alto) { Text("Regresar") }
        }
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
