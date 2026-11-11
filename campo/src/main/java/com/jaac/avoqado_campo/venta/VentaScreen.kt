package com.jaac.avoqado_campo.venta

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.version.ACTUALIZA_PARA_SEGUIR
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * «Vender una SIM»: escanear (o escribir) el código, ver el precio, tomar las evidencias y «Registrar», que guarda en el
 * celular antes de cualquier red. [sinSenal] lo da quien la muestra (Tarea 13); sin señal se vende igual.
 */
@Composable
fun VentaScreen(vm: VentaViewModel, sinSenal: Boolean, obligatoria: Boolean, onRegresar: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var escaneando by rememberSaveable { mutableStateOf(false) }
    var fotoDe by rememberSaveable { mutableStateOf<Slot?>(null) }
    when {
        // Con obligatoria no entra una SIM nueva, ni siquiera si el escáner ya estaba abierto cuando llegó (Codex código r1 #1).
        escaneando -> EscanerScreen(onCodigo = { escaneando = false; if (!obligatoria) vm.alEscanear(it) }, onCerrar = { escaneando = false })
        fotoDe != null -> {
            val s = fotoDe!!
            Camara(
                titulo = if (s == Slot.VINCULACION) "la foto de vinculación" else "la foto de portabilidad",
                onFoto = { fotoDe = null; vm.alTomarFoto(s, it.absolutePath) },
                onCerrar = { fotoDe = null },
            )
        }
        else -> VentaContenido(
            estado, sinSenal,
            AccionesDeVenta(
                escanear = { escaneando = true }, buscar = vm::alEscanear, foto = { fotoDe = it }, numero = vm::alEscribirNumero,
                precio = vm::alEscribirPrecio, portabilidad = vm::alCambiarPortabilidad, registrar = vm::registrar,
                otra = vm::venderOtra, regresar = onRegresar,
            ),
            obligatoria,
        )
    }
}

class AccionesDeVenta(
    val escanear: () -> Unit, val buscar: (String) -> Unit, val foto: (Slot) -> Unit, val numero: (Slot, String) -> Unit,
    val precio: (String) -> Unit, val portabilidad: (Boolean) -> Unit, val registrar: () -> Unit, val otra: () -> Unit,
    val regresar: () -> Unit,
)

private val alto = Modifier.fillMaxWidth().heightIn(min = 48.dp)

@Composable
fun VentaContenido(e: EstadoVenta, sinSenal: Boolean, a: AccionesDeVenta, obligatoria: Boolean = false) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Vender una ${e.config?.etiquetaArticulo ?: "SIM"}", style = MaterialTheme.typography.headlineSmall)
            if (sinSenal) {
                Text("Sin señal · lo que hagas se guarda en el celular y se sube solo", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.guardada) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Venta guardada en el celular · se sube sola · no la vuelvas a hacer",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium,
                    )
                }
                // D-2: la venta que ya estaba en curso se termina (ya pasó en la calle); otra nueva, no.
                Button(a.otra, alto, enabled = !obligatoria) { Text("Vender otra") }
                if (obligatoria) Text(ACTUALIZA_PARA_SEGUIR, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(a.regresar, alto) { Text("Regresar") }
                return@Column
            }
            // D-2: con obligatoria no se EMPIEZA otra venta (también si Android restauró esta pantalla vacía); la SIM que ya
            // estaba elegida sí se termina, porque ya pasó en la calle.
            Buscar(e, a, bloqueado = obligatoria)
            if (obligatoria && e.sim == null) Text(ACTUALIZA_PARA_SEGUIR, color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val sim = e.sim ?: return@Column
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sim.serialNumber, fontWeight = FontWeight.Bold)
                    if (sim.categoria.isNotBlank()) Text(sim.categoria, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Precio(e, a)
            if (e.config?.portabilidad == true) {
                Row(alto, verticalAlignment = Alignment.CenterVertically) {
                    Text("Es portabilidad", Modifier.weight(1f))
                    Switch(e.portabilidad, a.portabilidad, enabled = !e.registrando)
                }
            }
            e.slots.forEach { Evidencia(it, e, a) }
            Button(a.registrar, alto, enabled = !e.registrando && e.falta == null) {
                Text(if (e.registrando) "Guardando…" else "Registrar")
            }
            e.falta?.takeIf { e.error == null }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Buscar(e: EstadoVenta, a: AccionesDeVenta, bloqueado: Boolean) {
    var escrito by remember { mutableStateOf("") }
    Button(a.escanear, alto, enabled = !bloqueado && !e.buscando && !e.registrando) { Text(if (e.buscando) "Buscando…" else "Escanear la SIM") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            escrito, { escrito = it }, Modifier.weight(1f), singleLine = true,
            label = { Text("O escribe el ${e.config?.etiquetaCodigo ?: "ICCID"}") },
        )
        OutlinedButton({ a.buscar(escrito) }, Modifier.heightIn(min = 48.dp), enabled = !bloqueado && escrito.isNotBlank() && !e.buscando) { Text("Buscar") }
    }
}

@Composable
private fun Precio(e: EstadoVenta, a: AccionesDeVenta) {
    when (e.tipoDePrecio) {
        TipoDePrecio.DE_CAJA -> Column {
            Text(pesos(BigDecimal.ZERO), style = MaterialTheme.typography.headlineMedium)
            Text("El cliente paga en la caja de la tienda")
        }
        TipoDePrecio.DE_PROMOTOR -> Column {
            Text(pesos(e.precio ?: BigDecimal.ZERO), style = MaterialTheme.typography.headlineMedium)
            Text("Cobra en efectivo")
        }
        TipoDePrecio.LIBRE -> OutlinedTextField(
            e.precioEscrito, a.precio, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.registrando,
            label = { Text("Importe que cobraste en efectivo") }, prefix = { Text("$") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        null -> Unit
    }
}

@Composable
private fun Evidencia(s: Slot, e: EstadoVenta, a: AccionesDeVenta) {
    val vinc = s == Slot.VINCULACION
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (vinc) "Vinculación" else "Portabilidad", style = MaterialTheme.typography.titleMedium)
        if (e.pideNumero(s)) {
            OutlinedTextField(
                e.numeros[s].orEmpty(), { a.numero(s, it) }, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.registrando,
                label = { Text(if (vinc) "Número de vinculación (9 o 10 dígitos)" else "Número portado (9 o 10 dígitos)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        val lista = e.fotos[s] != null
        OutlinedButton({ a.foto(s) }, alto, enabled = !e.registrando) { Text(if (lista) "Foto lista · Tomar otra" else "Tomar la foto") }
    }
}

private fun pesos(b: BigDecimal) = "$" + DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(b)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun VentaPreview() = TemaCampo {
    VentaContenido(
        EstadoVenta(
            sim = SimEnCopia("8952020012345678901F", "PROMOTER_HELD", 1, "Prepago $150", "150.00", null, null, null, null, emptyList(), null),
            config = ConfigDeSucursal("SIM", "ICCID", true, true, false, false, false, false, true),
            tipoDePrecio = TipoDePrecio.DE_PROMOTOR, precio = BigDecimal("150.00"), portabilidad = true,
            fotos = mapOf(Slot.VINCULACION to "/x.jpg"),
        ),
        sinSenal = true, AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}),
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun VentaGuardadaPreview() = TemaCampo {
    VentaContenido(EstadoVenta(guardada = true), sinSenal = false, AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}))
}
