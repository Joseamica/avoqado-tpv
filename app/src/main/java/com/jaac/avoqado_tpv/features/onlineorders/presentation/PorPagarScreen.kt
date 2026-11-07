package com.jaac.avoqado_tpv.features.onlineorders.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Warning
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioModificador
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * «Pedidos por pagar» (mockups v2 §2b–2f): la lista con «Por pagar» y «Por entregar», y la pantalla de un folio con «Cobrar».
 * Al tocar «Cobrar» el ViewModel relee el folio; si sigue por pagar, [onCobrar] abre el cobro de siempre con lo que falta.
 * Sin red la lista se sigue leyendo, pero no se cobra ni se toca ningún folio. No hay botón de imprimir antes de pagar.
 */
@Composable
fun PorPagarScreen(
    onBack: () -> Unit,
    /** Abre el cobro de siempre; suspende hasta que el lector está listo («Cobrar» sigue ocupado mientras). */
    onCobrar: suspend (AperturaDeCobro) -> Unit,
    viewModel: PorPagarViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val zona = remember { viewModel.zona }
    // Reloj de la pantalla: «Vence en N min» y «(de hace N min)» se mueven solos.
    val ahoraMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    BackHandler(enabled = ui.seleccionado != null) { viewModel.cerrarFolio() }
    // Cada vez que vuelve a verse (también al regresar de un cobro), la lista se pide ya.
    LifecycleResumeEffect(Unit) {
        viewModel.pantallaVisible()
        onPauseOrDispose { }
    }
    PorPagarContenido(
        ui = ui,
        ahoraMs = ahoraMs,
        zona = zona,
        onBack = { if (ui.seleccionado != null) viewModel.cerrarFolio() else onBack() },
        onBuscar = viewModel::buscar,
        onAbrir = viewModel::abrir,
        onCobrar = { folio -> scope.launch { viewModel.cobrar(folio, onCobrar) } },
        onCancelar = viewModel::cancelar,
        onListo = viewModel::listo,
        onEntregado = viewModel::entregado,
        onCargarMas = viewModel::cargarMas,
        onReintentar = viewModel::reintentar,
        onCerrarAviso = viewModel::cerrarAviso,
        onToastMostrado = viewModel::toastMostrado,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PorPagarContenido(
    ui: PorPagarUi,
    ahoraMs: Long,
    zona: ZoneId,
    onBack: () -> Unit,
    onBuscar: (String) -> Unit,
    onAbrir: (Folio) -> Unit,
    onCobrar: (Folio) -> Unit,
    onCancelar: (Folio) -> Unit,
    onListo: (Folio) -> Unit,
    onEntregado: (Folio) -> Unit,
    onCargarMas: (SeccionDeFolios) -> Unit,
    onReintentar: () -> Unit,
    onCerrarAviso: () -> Unit,
    onToastMostrado: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.toast) {
        val texto = ui.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(texto)
        onToastMostrado()
    }
    // «Vence en» se cuenta con la hora del SERVIDOR (la del aparato puede ir corrida).
    val ahora = Instant.ofEpochMilli(ahoraMs + ui.folios.desfaseDelServidorMs)
    val folio = ui.seleccionado

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(folio?.let { TextosPorPagar.tituloFolio(it.folio) } ?: TextosPorPagar.TITULO, fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (folio != null) {
                FolioDetalle(folio, ui, ahora, zona, onCobrar, onCancelar, onListo, onEntregado)
            } else {
                Lista(ui, ahora, ahoraMs, zona, onBuscar, onAbrir, onListo, onEntregado, onCargarMas, onReintentar)
            }
        }
    }

    ui.aviso?.let { aviso ->
        AlertDialog(
            onDismissRequest = onCerrarAviso,
            title = { Text(aviso.titulo) },
            text = { Text(aviso.cuerpo) },
            confirmButton = {
                TextButton(onClick = onCerrarAviso, modifier = Modifier.heightIn(min = 48.dp)) { Text(aviso.boton) }
            },
        )
    }
}

// region — la lista

@Composable
private fun Lista(
    ui: PorPagarUi,
    ahora: Instant,
    ahoraMs: Long,
    zona: ZoneId,
    onBuscar: (String) -> Unit,
    onAbrir: (Folio) -> Unit,
    onListo: (Folio) -> Unit,
    onEntregado: (Folio) -> Unit,
    onCargarMas: (SeccionDeFolios) -> Unit,
    onReintentar: () -> Unit,
) {
    val e = ui.folios
    Column(Modifier.fillMaxSize()) {
        when {
            e.sinConexion -> AvisoSinConexion()
            e.desactualizado -> AvisoSinActualizar(onReintentar)
        }
        Buscador(ui.busqueda, onBuscar)
        val actualizado = e.actualizadoEnMs
        // Sin red, la lista es la última que llegó: se dice de cuándo es.
        val deCuando = if (e.sinConexion && actualizado != null) " " + deHace(actualizado, ahoraMs) else ""
        when {
            actualizado == null && !e.sinConexion && !e.desactualizado -> Cargando()
            actualizado == null -> NoSePudoCargar(onReintentar)
            !ui.buscando && ui.porPagar.isEmpty() && ui.porEntregar.isEmpty() && e.nextCursors.isEmpty() ->
                Vacio(TextosPorPagar.VACIO, TextosPorPagar.VACIO_AYUDA)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                val filaPorPagar: @Composable (Folio) -> Unit = { FilaPorPagar(it, ahora, e.sinConexion, zona, onAbrir) }
                val filaPorEntregar: @Composable (Folio) -> Unit = {
                    FilaPorEntregar(it, ui, zona, onAbrir, onListo, onEntregado)
                }
                if (ui.buscando) {
                    val n = ui.porPagar.size + ui.porEntregar.size
                    if (n == 0) {
                        item { SinResultados(ui.busqueda.trim(), hayMas = e.nextCursors.isNotEmpty()) }
                    } else {
                        item { Encabezado(TextosPorPagar.resultados(n) + deCuando) }
                    }
                    items(ui.porPagar, key = { "p-" + it.orderId }) { filaPorPagar(it) }
                    items(ui.porEntregar, key = { "e-" + it.orderId }) { filaPorEntregar(it) }
                    cargarMas(e, SeccionDeFolios.POR_PAGAR, onCargarMas)
                    cargarMas(e, SeccionDeFolios.POR_ENTREGAR, onCargarMas)
                } else {
                    item { Encabezado("${TextosPorPagar.POR_PAGAR} · ${ui.numeroPorPagar}$deCuando") }
                    if (ui.porPagar.isEmpty()) item { TextoDeSeccion(TextosPorPagar.VACIO) }
                    items(ui.porPagar, key = { "p-" + it.orderId }) { filaPorPagar(it) }
                    cargarMas(e, SeccionDeFolios.POR_PAGAR, onCargarMas)
                    item { Encabezado("${TextosPorPagar.POR_ENTREGAR} · ${ui.numeroPorEntregar}") }
                    if (ui.porEntregar.isEmpty()) item { TextoDeSeccion(TextosPorPagar.NADA_POR_ENTREGAR) }
                    items(ui.porEntregar, key = { "e-" + it.orderId }) { filaPorEntregar(it) }
                    cargarMas(e, SeccionDeFolios.POR_ENTREGAR, onCargarMas)
                }
                if (e.errorAlCargarMas) item { TextoDeSeccion(TextosPorPagar.NO_SE_PUDIERON_CARGAR_MAS) }
            }
        }
    }
}

private fun LazyListScope.cargarMas(e: EstadoDeFolios, seccion: SeccionDeFolios, onCargarMas: (SeccionDeFolios) -> Unit) {
    if (seccion !in e.nextCursors) return
    item(key = "mas-" + seccion.clave) {
        OutlinedButton(
            onClick = { onCargarMas(seccion) },
            enabled = !e.sinConexion,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
        ) { Text(TextosPorPagar.CARGAR_MAS) }
    }
}

@Composable
private fun FilaPorPagar(folio: Folio, ahora: Instant, sinConexion: Boolean, zona: ZoneId, onAbrir: (Folio) -> Unit) {
    Tarjeta(onClick = { onAbrir(folio) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FolioYNombre(folio, Modifier.weight(1f))
            // La cifra grande es lo que hay que cobrar: con abono, lo que falta.
            Text(dinero(folio.falta), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (folio.tieneAbono) {
                Text(TextosPorPagar.faltaDe(dinero(folio.falta), dinero(folio.total)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(TextosPorPagar.yaPago(dinero(folio.abono)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(TextosPorPagar.productos(cuantos(folio)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vencimiento(folio, ahora, sinConexion, zona)?.let { TextoDeVencimiento(it) }
            }
        }
    }
}

@Composable
private fun FilaPorEntregar(
    folio: Folio,
    ui: PorPagarUi,
    zona: ZoneId,
    onAbrir: (Folio) -> Unit,
    onListo: (Folio) -> Unit,
    onEntregado: (Folio) -> Unit,
) {
    Tarjeta(onClick = { onAbrir(folio) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FolioYNombre(folio, Modifier.weight(1f))
            Text(dinero(folio.total), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        if (estaListo(folio)) MarcaListo(Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(textoDePago(folio, zona).orEmpty(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(TextosPorPagar.productos(cuantos(folio)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AccionesDeEntrega(folio, ui, onListo, onEntregado, Modifier.padding(top = 12.dp))
    }
}

/** P6: la marca de un folio que ya está «Listo» (READY). Con los colores del tema, nunca en rojo: no es un error. */
@Composable
private fun MarcaListo(modifier: Modifier = Modifier) {
    Text(
        TextosPorPagar.LISTO,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * «Listo» con borde y «Entregado» relleno, en ese orden; un folio que ya está «Listo» (READY, P6) sólo trae «Entregado». Sin red,
 * el aviso de que vuelven con la conexión.
 */
@Composable
private fun AccionesDeEntrega(folio: Folio, ui: PorPagarUi, onListo: (Folio) -> Unit, onEntregado: (Folio) -> Unit, modifier: Modifier) {
    val sinConexion = ui.folios.sinConexion
    when {
        muestraListoYEntregado(ui.puedeEntregar, sinConexion) -> {
            val ocupado = folio.orderId in ui.ocupados
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (muestraBotonListo(folio, ui.puedeEntregar, sinConexion)) OutlinedButton(
                    onClick = { onListo(folio) },
                    enabled = !ocupado,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(TextosPorPagar.LISTO, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { onEntregado(folio) },
                    enabled = !ocupado,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(TextosPorPagar.ENTREGADO, fontWeight = FontWeight.Bold) }
            }
        }
        sinConexion && ui.puedeEntregar ->
            Text(TextosPorPagar.ENTREGA_SIN_RED, modifier, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// endregion

// region — el folio

@Composable
private fun FolioDetalle(
    folio: Folio,
    ui: PorPagarUi,
    ahora: Instant,
    zona: ZoneId,
    onCobrar: (Folio) -> Unit,
    onCancelar: (Folio) -> Unit,
    onListo: (Folio) -> Unit,
    onEntregado: (Folio) -> Unit,
) {
    val sinConexion = ui.folios.sinConexion
    val pagado = folio.falta.signum() == 0
    val ocupado = folio.orderId in ui.ocupados
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmarCancelar by remember(folio.orderId) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(folio.nombre.orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (estaListo(folio)) MarcaListo(Modifier.padding(top = 6.dp))
                    val cuando = if (pagado) textoDePago(folio, zona)?.let { Vencimiento(it, pronto = false) } else vencimiento(folio, ahora, sinConexion, zona)
                    Row(Modifier.padding(top = 4.dp)) {
                        folio.creadoEn?.let { Text(TextosPorPagar.pidioALas(hora(it, zona)), fontSize = 14.sp, color = apagado) }
                        if (folio.creadoEn != null && cuando != null) Text(" · ", fontSize = 14.sp, color = apagado)
                        cuando?.let { TextoDeVencimiento(it) }
                    }
                }
            }
            items(folio.productos) { Renglon(it) }
            folio.nota?.let { nota ->
                item {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = apagado, fontWeight = FontWeight.SemiBold)) { append(TextosPorPagar.NOTA) }
                            append(" ")
                            append(nota)
                        },
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        fontSize = 14.sp,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(TextosPorPagar.TOTAL, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(dinero(folio.total), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (folio.tieneAbono) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(TextosPorPagar.faltaDe(dinero(folio.falta), dinero(folio.total)), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(TextosPorPagar.yaPago(dinero(folio.abono)), fontSize = 14.sp, color = apagado)
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when {
                pagado -> AccionesDeEntrega(folio, ui, onListo, onEntregado, Modifier)
                // Sin red se ve lo que pidió para cobrarlo en «Cobrar», pero no hay botón de cobrar el folio.
                sinConexion -> Text(
                    TextosPorPagar.FOLIO_SIN_RED,
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    fontSize = 15.sp,
                    color = apagado,
                    textAlign = TextAlign.Center,
                )
                else -> {
                    Button(
                        onClick = { onCobrar(folio) },
                        enabled = !ocupado,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (ocupado) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Text(TextosPorPagar.cobrar(dinero(folio.falta)), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Lejos de «Cobrar» (20 dp) y siempre pregunta; no existe en un folio con dinero.
                    if (muestraCancelarFolio(folio, ui.puedeCancelar, sinConexion)) {
                        Spacer(Modifier.height(20.dp))
                        TextButton(
                            onClick = { confirmarCancelar = true },
                            enabled = !ocupado,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(TextosPorPagar.CANCELAR_FOLIO, color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (confirmarCancelar) {
        AlertDialog(
            onDismissRequest = { confirmarCancelar = false },
            title = { Text(TextosPorPagar.cancelarTitulo(folio.folio)) },
            text = { Text(TextosPorPagar.cancelarCuerpo(folio.nombre)) },
            dismissButton = {
                TextButton(onClick = { confirmarCancelar = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(TextosPorPagar.NO) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmarCancelar = false
                        onCancelar(folio)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(TextosPorPagar.SI_CANCELAR, color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun Renglon(p: FolioProducto) {
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${p.cantidad} × ${p.nombre}", fontSize = 16.sp)
            p.extras.forEach { x -> Text(if (x.cantidad > 1) "${x.cantidad} × ${x.nombre}" else x.nombre, fontSize = 13.sp, color = apagado) }
            p.nota?.let { Text("${TextosPorPagar.NOTA} $it", fontSize = 13.sp, color = apagado) }
        }
        // R76: el precio del renglón sólo si el SERVIDOR lo manda.
        p.precioDelRenglon?.let { Text(dinero(it), fontSize = 16.sp) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

// endregion

// region — piezas

/** Mockup 2d: «Folio W7K3P · Ana» bajo el monto de «Método de pago» (PAX y Nexgo), con el folio espaciado (R61). */
@Composable
fun FolioDelCobro(folio: ComandaDeFolio, modifier: Modifier = Modifier) {
    val texto = TextosPorPagar.folioDelCobro(folio.orderNumber, folio.nombre)
    val inicio = "Folio ".length
    Text(
        buildAnnotatedString {
            append(texto)
            addStyle(SpanStyle(letterSpacing = 0.08.em, fontWeight = FontWeight.SemiBold), inicio, inicio + folio.orderNumber.length)
        },
        modifier = modifier,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** El folio primero, grande y espaciado (es lo que dice la clienta); luego su nombre. */
@Composable
private fun FolioYNombre(folio: Folio, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(folio.folio, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em)
        Spacer(Modifier.width(8.dp))
        Text(folio.nombre.orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TextoDeVencimiento(v: Vencimiento) {
    Text(
        v.texto,
        fontSize = 14.sp,
        // Ámbar con 5 min o menos; nunca rojo.
        color = if (v.pronto) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (v.pronto) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun Tarjeta(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun Buscador(texto: String, onBuscar: (String) -> Unit) {
    val foco = LocalFocusManager.current
    OutlinedTextField(
        value = texto,
        onValueChange = onBuscar,
        singleLine = true,
        placeholder = { Text(TextosPorPagar.BUSCAR) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (texto.isEmpty()) null else {
            { IconButton(onClick = { onBuscar("") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Borrar búsqueda") } }
        },
        // El folio va en mayúsculas y números («W7K3P»).
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { foco.clearFocus() }),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** El aviso del diseño (§11) tal cual. La lista de abajo se sigue leyendo: nada se pone en gris. */
@Composable
private fun AvisoSinConexion() {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.avoqadoColors.offlineOrange, modifier = Modifier.size(22.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) {
                    append(TextosPorPagar.SIN_CONEXION_TITULO)
                }
                append(" ")
                append(TextosPorPagar.SIN_CONEXION_CUERPO)
            },
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AvisoSinActualizar(onReintentar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            TextosPorPagar.SIN_ACTUALIZAR,
            Modifier.weight(1f),
            fontSize = 14.sp,
            color = MaterialTheme.avoqadoColors.offlineOrange,
        )
        TextButton(onClick = onReintentar, modifier = Modifier.heightIn(min = 48.dp)) { Text(TextosPorPagar.REINTENTAR) }
    }
}

@Composable
private fun Encabezado(texto: String) {
    Text(
        texto,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TextoDeSeccion(texto: String) {
    Text(
        texto,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Vacio(titulo: String, ayuda: String) {
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(titulo, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(ayuda, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SinResultados(texto: String, hayMas: Boolean) =
    Vacio(TextosPorPagar.sinResultados(texto), if (hayMas) TextosPorPagar.PUEDE_HABER_MAS else TextosPorPagar.SIN_RESULTADOS_AYUDA)

@Composable
private fun Cargando() {
    Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(TextosPorPagar.LEYENDO, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoSePudoCargar(onReintentar: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(TextosPorPagar.NO_SE_PUDO_CARGAR, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onReintentar, modifier = Modifier.heightIn(min = 48.dp)) { Text(TextosPorPagar.REINTENTAR) }
    }
}

private fun dinero(monto: BigDecimal): String = CurrencyFormatter.format(monto)

private fun cuantos(folio: Folio): Int = folio.productos.sumOf { it.cantidad }

// endregion

// region — vistas previas (PAX A910S, 360×640)

private val AHORA_PREVIA = Instant.parse("2026-11-06T16:30:00Z")

private fun folioDePrueba(id: String, nombre: String, total: String, falta: String = total, minutos: Long = 22, pagado: Boolean = false) = Folio(
    orderId = id, onlineOrderId = null, folio = id, estado = if (pagado) "ACCEPTED" else "PENDING_ACCEPTANCE", nombre = nombre,
    telefono = null, nota = "sin azúcar, por favor", creadoEn = AHORA_PREVIA.minusSeconds(600), venceEn = AHORA_PREVIA.plusSeconds(minutos * 60),
    pagadoEn = if (pagado) AHORA_PREVIA.minusSeconds(1500) else null, total = BigDecimal(total), falta = BigDecimal(falta),
    estadoDePago = null, necesitaImprimir = false,
    productos = listOf(
        FolioProducto("Latte", 1, null, listOf(FolioModificador("Leche de almendra", 1)), total = BigDecimal("65.00")),
        FolioProducto("Shake de proteína", 1, null, emptyList(), total = BigDecimal("69.00")),
    ),
)

private fun uiDePrueba(sinConexion: Boolean = false, seleccionado: Folio? = null): PorPagarUi {
    val porPagar = listOf(
        folioDePrueba("W9D5F", "Pau", "95.00", minutos = 3),
        folioDePrueba("W4R9T", "Sofía", "185.00", falta = "85.00"),
        folioDePrueba("W7K3P", "Ana", "134.00"),
    )
    val porEntregar = listOf(folioDePrueba("W8M2C", "Leo", "69.00", falta = "0.00", pagado = true))
    return PorPagarUi(
        folios = EstadoDeFolios(
            showTile = true, counts = ConteoDeFolios(3, 1), porPagar = porPagar, porEntregar = porEntregar,
            sinConexion = sinConexion, actualizadoEnMs = AHORA_PREVIA.toEpochMilli() - 120_000,
        ),
        porPagar = porPagar, porEntregar = porEntregar, numeroPorPagar = 3, numeroPorEntregar = 1,
        puedeEntregar = true, puedeCancelar = true, seleccionado = seleccionado,
    )
}

@Composable
private fun Previa(ui: PorPagarUi) = AvoqadoTheme(darkTheme = true) {
    PorPagarContenido(
        ui, AHORA_PREVIA.toEpochMilli(), ZoneOffset.ofHours(-6), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaLista() = Previa(uiDePrueba())

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaSinRed() = Previa(uiDePrueba(sinConexion = true))

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaFolio() = Previa(uiDePrueba(seleccionado = folioDePrueba("W7K3P", "Ana", "134.00")))

// endregion
