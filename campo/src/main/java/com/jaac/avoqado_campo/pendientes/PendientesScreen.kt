package com.jaac.avoqado_campo.pendientes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo

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

/**
 * «Mis pendientes» como el mockup: lo que se sube solo, con su estado en una pastilla, y aparte lo que necesita que la persona
 * haga algo (rechazos y avisos), con el motivo y sus botones.
 */
@Composable
fun PendientesContenido(filas: List<FilaPendiente>, e: EstadoPendientes, onRegresar: () -> Unit, onAccion: (Long, Accion) -> Unit) {
    val (atender, porSubir) = filas.partition { it.problema }
    PantallaCampo("Mis pendientes", onRegresar = onRegresar) {
        e.error?.let { Recuadro(it, Tono.AVISO) }
        if (filas.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp).background(ColoresCampo.VerdeClaro, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✓", color = ColoresCampo.VerdeTexto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Tenue("No tienes nada pendiente. Todo se subió.")
            }
        }
        if (atender.isNotEmpty()) {
            TituloDeSeccion("Necesitan tu atención (${atender.size})")
            atender.forEach { f ->
                Tarjeta {
                    Text(f.resumen, fontWeight = FontWeight.SemiBold)
                    Text(f.texto, color = ColoresCampo.RojoTinta, style = MaterialTheme.typography.bodyMedium)
                    f.botones.forEach { b ->
                        if (b.accion == Accion.QUITAR) BotonSecundario(b.texto, { onAccion(f.id, b.accion) }, enabled = !e.ocupado)
                        else BotonPrincipal(b.texto, { onAccion(f.id, b.accion) }, enabled = !e.ocupado)
                    }
                }
            }
        }
        if (porSubir.isNotEmpty()) {
            TituloDeSeccion("Por subir (${porSubir.size})")
            Tarjeta(relleno = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                porSubir.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = ColoresCampo.Gris100)
                    FilaPorSubir(f)
                }
            }
        }
    }
}

/** Un renglón de «Por subir»: el estado corto va en pastilla; uno largo («Se sube cuando entres a…»), debajo. */
@Composable
private fun FilaPorSubir(f: FilaPendiente) {
    val tono = if (f.texto == "Espera señal") Tono.SIN_RED else Tono.NEUTRO
    val corto = f.texto.length <= 18
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.resumen)
            if (!corto) Text(f.texto, color = tono.tinta, style = MaterialTheme.typography.bodySmall)
        }
        if (corto) Pastilla(f.texto, tono)
    }
}

@Composable
private fun Correccion(
    e: EstadoPendientes, c: CorreccionDeEvidencia, foto: () -> Unit, numero: (String) -> Unit, enviar: () -> Unit, cancelar: () -> Unit,
) {
    PantallaCampo(
        "Corregir evidencia", onRegresar = cancelar, regresarHabilitado = !e.ocupado,
        abajo = {
            BotonPrincipal(if (e.ocupado) "Guardando…" else "Guardar corrección", enviar, enabled = !e.ocupado, cargando = e.ocupado)
            BotonSecundario("Cancelar", cancelar, enabled = !e.ocupado)
        },
    ) {
        if (c.foto != null) Text("📷 Foto ✓", color = ColoresCampo.VerdeTexto, fontWeight = FontWeight.SemiBold)
        BotonSecundario(
            if (c.foto != null) "Tomar otra foto" else if (c.pideFoto) "📷 Tomar la foto" else "Tomar otra foto (si hace falta)", foto, enabled = !e.ocupado,
        )
        OutlinedTextField(
            c.numero, numero, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.ocupado, shape = MaterialTheme.shapes.small,
            label = { Text(if (c.photoLabel == "Portabilidad") "Número portado" else "Número de vinculación") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        e.error?.let { Recuadro(it, Tono.AVISO) }
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
