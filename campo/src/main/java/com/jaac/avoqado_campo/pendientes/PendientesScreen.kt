package com.jaac.avoqado_campo.pendientes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo

private val alto = Modifier.fillMaxWidth().heightIn(min = 48.dp)

@Composable
fun PendientesScreen(vm: PendientesViewModel, onRegresar: () -> Unit, onMisSims: () -> Unit) {
    val filas by vm.filas.collectAsStateWithLifecycle()
    val e by vm.estado.collectAsStateWithLifecycle()
    var porQuitar by rememberSaveable { mutableStateOf<Long?>(null) }
    var camara by rememberSaveable { mutableStateOf(false) }
    val c = e.corrigiendo
    when {
        c != null && camara -> Camara(
            titulo = if (c.photoLabel == "Portabilidad") "la foto de portabilidad" else "la foto de vinculación",
            onFoto = { camara = false; vm.alTomarFoto(it.absolutePath) }, onCerrar = { camara = false },
        )
        c != null -> Correccion(e, c, { camara = true }, vm::alEscribirNumero, vm::enviarCorreccion, vm::cancelarCorreccion)
        else -> PendientesContenido(filas, e, onRegresar) { id, accion ->
            when (accion) {
                Accion.QUITAR -> porQuitar = id
                Accion.ENTENDIDO -> vm.entendido(id)
                Accion.CORREGIR_PRECIO -> vm.corregirPrecio(id)
                Accion.USAR_ENTRADA -> vm.usarEntrada(id)
                Accion.CORREGIR_EVIDENCIA -> vm.empezarCorreccion(id)
                Accion.VOLVER_A_DECIDIR -> { vm.volverADecidir(); onMisSims() }
            }
        }
    }
    porQuitar?.let { id ->
        AlertDialog(
            onDismissRequest = { porQuitar = null },
            text = { Text("Esto no se va a subir. ¿Seguro?") },
            confirmButton = { TextButton({ porQuitar = null; vm.quitar(id) }) { Text("Quitar") } },
            dismissButton = { TextButton({ porQuitar = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun PendientesContenido(filas: List<FilaPendiente>, e: EstadoPendientes, onRegresar: () -> Unit, onAccion: (Long, Accion) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mis pendientes", style = MaterialTheme.typography.headlineSmall)
            if (filas.isEmpty()) Text("No tienes nada pendiente. Todo se subió.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            filas.forEach { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(f.resumen, fontWeight = FontWeight.Bold)
                        Text(f.texto, color = if (f.problema) ColoresCampo.Naranja else MaterialTheme.colorScheme.onSurfaceVariant)
                        f.botones.forEach { b ->
                            if (b.accion == Accion.QUITAR) OutlinedButton({ onAccion(f.id, b.accion) }, alto, enabled = !e.ocupado) { Text(b.texto) }
                            else Button({ onAccion(f.id, b.accion) }, alto, enabled = !e.ocupado) { Text(b.texto) }
                        }
                    }
                }
            }
            OutlinedButton(onRegresar, alto) { Text("Regresar") }
        }
    }
}

@Composable
private fun Correccion(
    e: EstadoPendientes, c: CorreccionDeEvidencia, foto: () -> Unit, numero: (String) -> Unit, enviar: () -> Unit, cancelar: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Corregir evidencia", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(foto, alto, enabled = !e.ocupado) {
                Text(if (c.foto != null) "Foto lista · Tomar otra" else if (c.pideFoto) "Tomar la foto" else "Tomar otra foto (si hace falta)")
            }
            OutlinedTextField(
                c.numero, numero, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.ocupado,
                label = { Text(if (c.photoLabel == "Portabilidad") "Número portado" else "Número de vinculación") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enviar, alto, enabled = !e.ocupado) { Text(if (e.ocupado) "Guardando…" else "Guardar corrección") }
            OutlinedButton(cancelar, alto, enabled = !e.ocupado) { Text("Cancelar") }
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PendientesPreview() = TemaCampo {
    PendientesContenido(
        listOf(
            FilaPendiente(1, "SIM 8952…01", "Espera señal", false, emptyList()),
            FilaPendiente(2, "SIM 8952…02", "El precio cambió: ahora es \$150.00.", true,
                listOf(Boton(Accion.CORREGIR_PRECIO, "Corregir a \$150.00"), Boton(Accion.QUITAR, "Quitar de mi celular"))),
        ),
        EstadoPendientes(), {}, { _, _ -> },
    )
}
