package com.jaac.avoqado_campo.custodia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.BotonSecundario
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Pastilla
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tarjeta
import com.jaac.avoqado_campo.ui.Tenue
import com.jaac.avoqado_campo.ui.TituloDeSeccion
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.enGrupos
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.version.ACTUALIZA_PARA_SEGUIR

/** «Mis SIMs»: aceptar o rechazar lo que te asignaron, lo que tienes y lo vendido (con «Revisar» si el supervisor lo pide). */
@Composable
fun MisSimsScreen(vm: MisSimsViewModel, obligatoria: Boolean, onRegresar: () -> Unit) {
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
            obligatoria,
        )
    }
}

class AccionesDeMisSims(
    val aceptar: (String) -> Unit, val rechazar: (String) -> Unit, val aceptarTodas: () -> Unit,
    val corregir: (String, String) -> Unit, val actualizar: () -> Unit, val regresar: () -> Unit,
)

@Composable
fun MisSimsContenido(e: EstadoMisSims, a: AccionesDeMisSims, obligatoria: Boolean = false) {
    PantallaCampo(
        "Mis SIMs", onRegresar = a.regresar,
        derecha = {
            TextButton(a.actualizar, Modifier.heightIn(min = 48.dp), enabled = !e.actualizando) {
                Text(if (e.actualizando) "Actualizando…" else "Actualizar", color = if (e.actualizando) ColoresCampo.Gris400 else ColoresCampo.VerdeOscuro, fontWeight = FontWeight.Bold)
            }
        },
    ) {
        if (e.sinCopia) Recuadro("Todavía no están tus SIMs en el celular. Conéctate a internet una vez para bajarlas.", Tono.AVISO)
        e.error?.let { Recuadro(it, Tono.ERROR) }

        // D-2: con una obligatoria pendiente no se acepta ni rechaza custodia nueva; lo demás sigue.
        val decidir = !e.ocupado && !obligatoria
        val porDecidir = e.porAceptar.count { it.estado == EstadoFila.POR_DECIDIR }
        Seccion("Por aceptar", e.porAceptar, vacio = "No tienes SIMs por aceptar.") { f ->
            if (f.estado == EstadoFila.POR_DECIDIR) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonPrincipal("Aceptar", { a.aceptar(f.sim.serialNumber) }, Modifier.weight(1f), enabled = decidir)
                    BotonSecundario("Rechazar", { a.rechazar(f.sim.serialNumber) }, Modifier.weight(1f), enabled = decidir)
                }
            }
        }
        if (obligatoria && porDecidir > 0) Recuadro(ACTUALIZA_PARA_SEGUIR, Tono.AVISO)
        if (porDecidir > 1) BotonPrincipal("Aceptar todas", a.aceptarTodas, enabled = decidir)
        Seccion("En tu custodia", e.enCustodia, vacio = "Todavía no tienes SIMs en tu custodia.") {}
        Seccion("Vendidas", e.vendidas, vacio = "Aquí salen las que vendas.") { f ->
            if (f.estado == EstadoFila.REVISAR) {
                BotonPrincipal("Corregir evidencia de vinculación", { a.corregir(f.sim.serialNumber, "Vinculacion") }, enabled = !e.ocupado)
                if ("REVIEW_PORTABILIDAD" in f.sim.rejectionReasons) {
                    BotonSecundario("Corregir evidencia de portabilidad", { a.corregir(f.sim.serialNumber, "Portabilidad") }, enabled = !e.ocupado)
                }
            }
        }
    }
}

/** Una sección con su contador y sus SIMs en una sola tarjeta, separadas por una línea (como «Mis pendientes» del mockup). */
@Composable
private fun Seccion(titulo: String, filas: List<FilaSim>, vacio: String, acciones: @Composable (FilaSim) -> Unit) {
    TituloDeSeccion("$titulo (${filas.size})")
    if (filas.isEmpty()) {
        Tenue(vacio)
        return
    }
    Tarjeta(relleno = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        filas.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = ColoresCampo.Gris100)
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Fila(f)
                acciones(f)
            }
        }
    }
}

/** La serie en grupos de 4, su categoría y, a la derecha, en qué va. */
@Composable
private fun Fila(f: FilaSim) {
    val (pastilla, tono) = when (f.estado) {
        EstadoFila.POR_SUBIR -> "Por subir" to Tono.SIN_RED
        EstadoFila.VENDIDA -> "Vendida" to Tono.OK
        EstadoFila.EN_REVISION -> "En revisión" to Tono.INFO
        EstadoFila.REVISAR -> "Revisar" to Tono.ERROR
        EstadoFila.CORRECCION_POR_SUBIR -> "Corrección por subir" to Tono.SIN_RED
        EstadoFila.POR_DECIDIR, EstadoFila.EN_CUSTODIA, EstadoFila.ACTUALIZANDO -> null to Tono.NEUTRO
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(enGrupos(f.sim.serialNumber), fontWeight = FontWeight.Bold)
            if (f.sim.categoria.isNotBlank()) Tenue(f.sim.categoria)
        }
        pastilla?.let { Pastilla(it, tono) }
    }
    // La nota que no es la misma que la pastilla: los motivos de «Revisar» o «Te volvieron a asignar…».
    f.nota?.takeIf { it != pastilla }?.let {
        Text(it, color = if (f.estado == EstadoFila.REVISAR) ColoresCampo.RojoTinta else ColoresCampo.AmbarTinta, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PantallaDeCorreccion(
    e: EstadoMisSims, c: Correccion, foto: () -> Unit, numero: (String) -> Unit, enviar: () -> Unit, cancelar: () -> Unit,
) {
    PantallaCampo(
        "Corregir evidencia", onRegresar = cancelar, regresarHabilitado = !e.ocupado,
        abajo = {
            BotonPrincipal(if (e.ocupado) "Guardando…" else "Enviar corrección", enviar, enabled = !e.ocupado && c.foto != null, cargando = e.ocupado)
            BotonSecundario("Cancelar", cancelar, enabled = !e.ocupado)
        },
    ) {
        Tarjeta {
            Text(enGrupos(c.serie), fontWeight = FontWeight.Bold)
            e.vendidas.firstOrNull { it.sim.serialNumber == c.serie }?.nota?.let { Text(it, color = ColoresCampo.RojoTinta) }
        }
        if (c.foto != null) Text("📷 Foto ✓", color = ColoresCampo.VerdeOscuro, fontWeight = FontWeight.SemiBold)
        BotonSecundario(if (c.foto != null) "Tomar otra foto" else "📷 Tomar la foto", foto, enabled = !e.ocupado)
        OutlinedTextField(
            c.numero, numero, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.ocupado, shape = MaterialTheme.shapes.small,
            label = { Text(if (c.photoLabel == "Portabilidad") "Número portado (si cambia)" else "Número de vinculación (si cambia)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        e.error?.let { Recuadro(it, Tono.ERROR) }
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
