package com.jaac.avoqado_campo.custodia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.ui.tema.TemaCampo

/** «Mis SIMs»: aceptar o rechazar lo que te asignaron, lo que tienes y lo vendido (con «Revisar» si el supervisor lo pide). */
@Composable
fun MisSimsScreen(vm: MisSimsViewModel, onRegresar: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var camara by rememberSaveable { mutableStateOf(false) }
    val c = estado.corrigiendo
    when {
        c != null && camara -> Camara(
            titulo = if (c.photoLabel == "Portabilidad") "la foto de portabilidad" else "la foto de vinculación",
            onFoto = { camara = false; vm.alTomarFotoDeCorreccion(it.absolutePath) }, onCerrar = { camara = false },
        )
        c != null -> PantallaDeCorreccion(estado, c, { camara = true }, vm::alEscribirNumeroDeCorreccion, vm::enviarCorreccion, vm::cancelarCorreccion)
        else -> MisSimsContenido(
            estado,
            AccionesDeMisSims(vm::aceptar, vm::rechazar, vm::aceptarTodas, vm::empezarCorreccion, vm::actualizar, onRegresar),
        )
    }
}

class AccionesDeMisSims(
    val aceptar: (String) -> Unit, val rechazar: (String) -> Unit, val aceptarTodas: () -> Unit,
    val corregir: (String, String) -> Unit, val actualizar: () -> Unit, val regresar: () -> Unit,
)

private val alto = Modifier.heightIn(min = 48.dp)

@Composable
fun MisSimsContenido(e: EstadoMisSims, a: AccionesDeMisSims) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mis SIMs", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(a.actualizar, alto.fillMaxWidth(), enabled = !e.actualizando) {
                Text(if (e.actualizando) "Actualizando…" else "Actualizar")
            }
            if (e.sinCopia) Text("Todavía no están tus SIMs en el celular. Conéctate a internet una vez para bajarlas.")
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Seccion("Por aceptar", e.porAceptar)
            if (e.porAceptar.count { it.estado == EstadoFila.POR_DECIDIR } > 1) {
                Button(a.aceptarTodas, alto.fillMaxWidth(), enabled = !e.ocupado) { Text("Aceptar todas") }
            }
            e.porAceptar.forEach { f ->
                Fila(f) {
                    if (f.estado == EstadoFila.POR_DECIDIR) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ a.aceptar(f.sim.serialNumber) }, alto, enabled = !e.ocupado) { Text("Aceptar") }
                            OutlinedButton({ a.rechazar(f.sim.serialNumber) }, alto, enabled = !e.ocupado) { Text("Rechazar") }
                        }
                    }
                }
            }
            Seccion("En tu custodia", e.enCustodia)
            e.enCustodia.forEach { Fila(it) {} }
            Seccion("Vendidas", e.vendidas)
            e.vendidas.forEach { f ->
                Fila(f) {
                    if (f.estado == EstadoFila.REVISAR) {
                        Button({ a.corregir(f.sim.serialNumber, "Vinculacion") }, alto.fillMaxWidth(), enabled = !e.ocupado) {
                            Text("Corregir evidencia de vinculación")
                        }
                        if ("REVIEW_PORTABILIDAD" in f.sim.rejectionReasons) {
                            OutlinedButton({ a.corregir(f.sim.serialNumber, "Portabilidad") }, alto.fillMaxWidth(), enabled = !e.ocupado) {
                                Text("Corregir evidencia de portabilidad")
                            }
                        }
                    }
                }
            }
            OutlinedButton(a.regresar, alto.fillMaxWidth()) { Text("Regresar") }
        }
    }
}

@Composable
private fun Seccion(titulo: String, filas: List<FilaSim>) {
    Text("$titulo (${filas.size})", style = MaterialTheme.typography.titleMedium)
    if (filas.isEmpty()) Text("Nada por ahora.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Fila(f: FilaSim, acciones: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f.sim.serialNumber, fontWeight = FontWeight.Bold)
            if (f.sim.categoria.isNotBlank()) Text(f.sim.categoria, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val etiqueta = if (f.estado == EstadoFila.REVISAR) "Revisar · ${f.nota}" else f.nota
            etiqueta?.let { Text(it, color = if (f.estado == EstadoFila.REVISAR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            acciones()
        }
    }
}

@Composable
private fun PantallaDeCorreccion(
    e: EstadoMisSims, c: Correccion, foto: () -> Unit, numero: (String) -> Unit, enviar: () -> Unit, cancelar: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Corregir evidencia", style = MaterialTheme.typography.headlineSmall)
            Text(c.serie, fontWeight = FontWeight.Bold)
            e.vendidas.firstOrNull { it.sim.serialNumber == c.serie }?.nota?.let { Text(it) }
            OutlinedButton(foto, alto.fillMaxWidth(), enabled = !e.ocupado) { Text(if (c.foto != null) "Foto lista · Tomar otra" else "Tomar la foto") }
            OutlinedTextField(
                c.numero, numero, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.ocupado,
                label = { Text(if (c.photoLabel == "Portabilidad") "Número portado (si cambia)" else "Número de vinculación (si cambia)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enviar, alto.fillMaxWidth(), enabled = !e.ocupado && c.foto != null) { Text(if (e.ocupado) "Guardando…" else "Enviar corrección") }
            OutlinedButton(cancelar, alto.fillMaxWidth(), enabled = !e.ocupado) { Text("Cancelar") }
        }
    }
}

private fun simDe(serie: String, estado: String, verificacion: String? = null) =
    SimEnCopia(serie, estado, 1, "Prepago $150", "150.00", null, verificacion, "v", "p", listOf("REVIEW_ILLEGIBLE_IMAGES"), null)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun MisSimsPreview() = TemaCampo {
    MisSimsContenido(
        EstadoMisSims(
            porAceptar = listOf(
                FilaSim(simDe("8952020012345678901F", "PROMOTER_PENDING"), EstadoFila.POR_DECIDIR),
                FilaSim(simDe("8952020012345678902F", "PROMOTER_PENDING"), EstadoFila.POR_SUBIR, "Por subir"),
            ),
            enCustodia = listOf(FilaSim(simDe("8952020012345678903F", "PROMOTER_HELD"), EstadoFila.EN_CUSTODIA)),
            vendidas = listOf(FilaSim(simDe("8952020012345678904F", "SOLD", "FAILED"), EstadoFila.REVISAR, "Imágenes ilegibles")),
            sinCopia = false,
        ),
        AccionesDeMisSims({}, {}, {}, { _, _ -> }, {}, {}),
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun CorreccionPreview() = TemaCampo {
    PantallaDeCorreccion(EstadoMisSims(sinCopia = false), Correccion("8952020012345678904F", "Vinculacion"), {}, {}, {}, {})
}
