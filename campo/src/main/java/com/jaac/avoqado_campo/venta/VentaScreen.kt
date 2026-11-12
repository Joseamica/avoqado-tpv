package com.jaac.avoqado_campo.venta

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.BotonSecundario
import com.jaac.avoqado_campo.ui.Caja
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tarjeta
import com.jaac.avoqado_campo.ui.Tenue
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.enGrupos
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.version.ACTUALIZA_PARA_SEGUIR
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * «Vender una SIM»: escanear (o escribir) el código, ver el precio, tomar las evidencias y «Registrar», que guarda en el
 * celular antes de cualquier red. Sin señal se vende igual: lo dice la banda de la navegación, una sola vez (D2).
 */
@Composable
fun VentaScreen(vm: VentaViewModel, obligatoria: Boolean, onRegresar: () -> Unit, onPendientes: () -> Unit = {}) {
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
            estado,
            AccionesDeVenta(
                escanear = { escaneando = true }, buscar = vm::alEscanear, foto = { fotoDe = it }, numero = vm::alEscribirNumero,
                precio = vm::alEscribirPrecio, portabilidad = vm::alCambiarPortabilidad, registrar = vm::registrar,
                otra = vm::venderOtra, regresar = onRegresar, pendientes = onPendientes,
            ),
            obligatoria,
        )
    }
}

class AccionesDeVenta(
    val escanear: () -> Unit, val buscar: (String) -> Unit, val foto: (Slot) -> Unit, val numero: (Slot, String) -> Unit,
    val precio: (String) -> Unit, val portabilidad: (Boolean) -> Unit, val registrar: () -> Unit, val otra: () -> Unit,
    val regresar: () -> Unit, val pendientes: () -> Unit = {},
)

@Composable
fun VentaContenido(e: EstadoVenta, a: AccionesDeVenta, obligatoria: Boolean = false) {
    if (e.guardada) return VentaGuardada(e, a, obligatoria)
    val articulo = e.config?.etiquetaArticulo ?: "SIM"
    val sim = e.sim
    PantallaCampo(
        "Vender $articulo", onRegresar = a.regresar, regresarHabilitado = !e.registrando,
        abajo = {
            if (sim != null) {
                e.falta?.takeIf { e.error == null }?.let { Tenue(it, Modifier.fillMaxWidth()) }
                BotonPrincipal(if (e.registrando) "Guardando…" else "Registrar venta", a.registrar, enabled = !e.registrando && e.falta == null, cargando = e.registrando)
            }
        },
    ) {
        // D-2: con obligatoria no se EMPIEZA otra venta (también si Android restauró esta pantalla vacía); la SIM que ya
        // estaba elegida sí se termina, porque ya pasó en la calle.
        Buscar(e, a, bloqueado = obligatoria)
        if (obligatoria && sim == null) Recuadro(ACTUALIZA_PARA_SEGUIR, Tono.AVISO)
        e.error?.let { Recuadro(it, Tono.ERROR) }
        if (sim == null) return@PantallaCampo
        Tarjeta {
            Text(enGrupos(sim.serialNumber), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            // El ViewModel sólo deja aquí una SIM en custodia y aceptada (o con su aceptación por subir).
            Text("✓ Está en tu custodia y aceptada", color = ColoresCampo.VerdeTexto, style = MaterialTheme.typography.bodyMedium)
            if (sim.categoria.isNotBlank()) Tenue(sim.categoria)
        }
        Precio(e, a)
        if (e.config?.portabilidad == true) {
            Tarjeta(relleno = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Es portabilidad", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(
                        e.portabilidad, a.portabilidad, enabled = !e.registrando,
                        colors = SwitchDefaults.colors(checkedTrackColor = ColoresCampo.Verde, uncheckedTrackColor = ColoresCampo.Gris200),
                    )
                }
            }
        }
        e.slots.forEach { Evidencia(it, e, a) }
    }
}

/**
 * La zona del escáner (el `.cp-scan` del mockup): oscura, con la línea verde; al tocarla se abre la cámara. Con una SIM ya
 * elegida se encoge, para que la venta quepa sin tanto scroll.
 */
@Composable
private fun Buscar(e: EstadoVenta, a: AccionesDeVenta, bloqueado: Boolean) {
    var escrito by remember { mutableStateOf("") }
    val puede = !bloqueado && !e.buscando && !e.registrando
    val grande = e.sim == null
    Box(
        Modifier.fillMaxWidth().height(if (grande) 132.dp else 64.dp).clip(MaterialTheme.shapes.medium)
            .background(if (puede) ColoresCampo.Gris800 else ColoresCampo.Gris200)
            .clickable(enabled = puede, role = Role.Button, onClick = a.escanear),
        contentAlignment = Alignment.Center,
    ) {
        if (grande) Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(2.dp).background(if (puede) ColoresCampo.Verde else ColoresCampo.Gris300))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                if (e.buscando) "Buscando…" else "Escanear la SIM",
                color = if (puede) ColoresCampo.Blanco else ColoresCampo.GrisTexto, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            )
            if (grande) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "Toca y apunta al código de la SIM", color = if (puede) ColoresCampo.Gris200 else ColoresCampo.GrisTexto,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            escrito, { escrito = it }, Modifier.weight(1f), singleLine = true, shape = MaterialTheme.shapes.small,
            label = { Text("O escribe el ${e.config?.etiquetaCodigo ?: "ICCID"}") },
        )
        BotonSecundario("Buscar", { a.buscar(escrito) }, enabled = !bloqueado && escrito.isNotBlank() && !e.buscando, lleno = false)
    }
}

/** El renglón del precio: «de caja» se registra en $0 y se paga en la caja; «de promotor», efectivo con precio fijo. */
@Composable
private fun Precio(e: EstadoVenta, a: AccionesDeVenta) {
    when (e.tipoDePrecio) {
        TipoDePrecio.DE_CAJA -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RenglonDePrecio("Se registra en ${pesos(BigDecimal.ZERO)}", "Paga en caja")
            Tenue("El cliente paga en la caja de la tienda. Aquí no cobras nada.")
        }
        TipoDePrecio.DE_PROMOTOR -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RenglonDePrecio(pesos(e.precio ?: BigDecimal.ZERO), "Cobra en efectivo")
            Tenue("El precio lo pone la categoría; no se cambia.")
        }
        TipoDePrecio.LIBRE -> OutlinedTextField(
            e.precioEscrito, a.precio, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.registrando, shape = MaterialTheme.shapes.small,
            label = { Text("Importe que cobraste en efectivo") }, prefix = { Text("$") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        null -> Unit
    }
}

@Composable
private fun RenglonDePrecio(izquierda: String, derecha: String) {
    Tarjeta {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(izquierda, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(derecha, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** El número (si la sucursal lo pide) y la foto de cada evidencia, en una tarjeta. */
@Composable
private fun Evidencia(s: Slot, e: EstadoVenta, a: AccionesDeVenta) {
    val vinc = s == Slot.VINCULACION
    val lista = e.fotos[s] != null
    Tarjeta {
        Text(if (vinc) "Vinculación" else "Portabilidad", fontWeight = FontWeight.SemiBold)
        if (e.pideNumero(s)) {
            OutlinedTextField(
                e.numeros[s].orEmpty(), { a.numero(s, it) }, Modifier.fillMaxWidth(), singleLine = true, enabled = !e.registrando,
                shape = MaterialTheme.shapes.small,
                label = { Text(if (vinc) "Número de vinculación (9 o 10 dígitos)" else "Número portado (9 o 10 dígitos)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (lista) Text("📷 Foto ✓", color = ColoresCampo.VerdeTexto, fontWeight = FontWeight.SemiBold)
        BotonSecundario(if (lista) "Tomar otra foto" else "📷 Tomar la foto", { a.foto(s) }, enabled = !e.registrando)
    }
}

/** La confirmación honesta: «guardada en el celular», nunca «vendida»; se sube sola y no se repite. */
@Composable
private fun VentaGuardada(e: EstadoVenta, a: AccionesDeVenta, obligatoria: Boolean) {
    val detalle = listOfNotNull(
        e.sim?.serialNumber?.takeLast(4)?.let { "${e.config?.etiquetaArticulo ?: "SIM"} …$it" },
        when (e.tipoDePrecio) {
            TipoDePrecio.DE_CAJA -> "${pesos(BigDecimal.ZERO)} de caja"
            null -> null
            else -> e.precio?.let { "${pesos(it)} en efectivo" }
        },
    ).joinToString(" · ")
    // «Regresar» quedó donde estaba «Registrar venta»: el segundo toque de un doble toque se saltaba esta confirmación y el
    // promotor no leía «No la vuelvas a hacer» (SAMSUNG, rediseño). Los botones responden tras un instante.
    var listo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(ESPERA_DE_LA_CONFIRMACION_MS); listo = true }
    fun cuandoListo(f: () -> Unit): () -> Unit = { if (listo) f() }
    PantallaCampo(
        null, centrado = true,
        abajo = {
            // D-2: la venta que ya estaba en curso se termina (ya pasó en la calle); otra nueva, no.
            BotonPrincipal("Vender otra", cuandoListo(a.otra), enabled = !obligatoria)
            if (obligatoria) Tenue(ACTUALIZA_PARA_SEGUIR)
            BotonSecundario("Ver mis pendientes", cuandoListo(a.pendientes))
            TextButton(cuandoListo(a.regresar), Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Regresar", color = ColoresCampo.Gris700, fontWeight = FontWeight.SemiBold) }
        },
    ) {
        Box(Modifier.padding(top = 16.dp).size(72.dp).background(ColoresCampo.VerdeClaro, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = ColoresCampo.VerdeTexto, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        }
        Text("Venta guardada en el celular", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (detalle.isNotEmpty()) Tenue(detalle)
        Caja(Tono.SIN_RED) {
            Text(
                buildAnnotatedString {
                    append("Se sube sola cuando haya señal. ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("No la vuelvas a hacer.") }
                },
                color = Tono.SIN_RED.tinta, fontWeight = FontWeight.SemiBold,
            )
        }
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
        AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}),
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun VentaGuardadaPreview() = TemaCampo {
    VentaContenido(EstadoVenta(guardada = true), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}))
}

/** Lo que la confirmación espera antes de aceptar toques (un doble toque en «Registrar venta» dura menos). */
private const val ESPERA_DE_LA_CONFIRMACION_MS = 700L
