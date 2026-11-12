package com.jaac.avoqado_campo.entrada

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.Iniciales
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tenue
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo

/**
 * El PIN de la persona, con teclado numérico en pantalla. [sinSenal] lo da quien la muestra (Tarea 13, con la red del
 * celular): sin señal se entra contra el PIN guardado.
 */
@Composable
fun PinScreen(vm: EntradaViewModel, sinSenal: Boolean, onDentro: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(estado.dentro) { if (estado.dentro) onDentro() }
    PinContenido(estado, sinSenal, vm::entrar, vm::elegirSucursal)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinContenido(estado: EstadoEntrada, sinSenal: Boolean, onEntrar: (String) -> Unit, onElegir: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(estado.mensaje) { if (estado.mensaje != null) pin = "" } // tras un rechazo, se escribe de nuevo
    val falta = estado.sucursales.size > 1 && estado.venueIdElegido == null
    val primerNombre = estado.nombre?.trim()?.substringBefore(' ')
    val sucursal = estado.sucursales.firstOrNull { it.venueId == estado.venueIdElegido }?.nombre
    PantallaCampo(null, centrado = true) {
        Iniciales(estado.nombre, 64.dp)
        Text(primerNombre?.let { "Hola, $it" } ?: "Escribe tu PIN", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (sucursal != null && estado.sucursales.size <= 1) Tenue(sucursal)
        if (sinSenal || estado.dentroSinSenal) Tenue("Sin señal · entras con tu PIN guardado")
        if (estado.sucursales.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                estado.sucursales.forEach { s ->
                    FilterChip(
                        selected = s.venueId == estado.venueIdElegido,
                        onClick = { onElegir(s.venueId) },
                        label = { Text(s.nombre) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ColoresCampo.Blanco, labelColor = ColoresCampo.Gris900,
                            selectedContainerColor = ColoresCampo.VerdeClaro, selectedLabelColor = ColoresCampo.VerdeTinta,
                        ),
                    )
                }
            }
        }
        Puntos(pin.length)
        // «Elige en qué sucursal…» es una instrucción, no un error.
        estado.mensaje?.let { Recuadro(it, if (it.startsWith("Elige")) Tono.AVISO else Tono.ERROR) }
        Teclado(
            habilitado = !estado.trabajando,
            onDigito = { if (pin.length < 10) pin += it },
            onBorrar = { pin = pin.dropLast(1) },
        )
        BotonPrincipal(
            if (estado.trabajando) "Entrando…" else "Entrar", { onEntrar(pin) }, Modifier.widthIn(max = 360.dp),
            enabled = !estado.trabajando && pin.length >= 4 && !falta, cargando = estado.trabajando,
        )
        Tenue(primerNombre?.let { "¿No eres $it? Pide ayuda a tu supervisor" } ?: "¿Problemas para entrar? Pide ayuda a tu supervisor")
    }
}

/** Un punto por número escrito (mínimo 4, como el mockup); el PIN nunca se dibuja. */
@Composable
private fun Puntos(escritos: Int) {
    Row(
        Modifier.padding(vertical = 6.dp).semantics { contentDescription = "PIN: $escritos números" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(maxOf(4, escritos)) { i ->
            val lleno = i < escritos
            Box(
                Modifier.size(14.dp).border(2.dp, ColoresCampo.Verde, CircleShape)
                    .background(if (lleno) ColoresCampo.Verde else ColoresCampo.Blanco, CircleShape),
            )
        }
    }
}

@Composable
private fun Teclado(habilitado: Boolean, onDigito: (Char) -> Unit, onBorrar: () -> Unit) {
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789", " 0<").forEach { fila ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { c ->
                    val tecla = Modifier.weight(1f).heightIn(min = 56.dp)
                    when (c) {
                        ' ' -> Box(tecla)
                        '<' -> Tecla("⌫", habilitado, tecla.semantics { contentDescription = "Borrar" }, onBorrar)
                        else -> Tecla(c.toString(), habilitado, tecla) { onDigito(c) }
                    }
                }
            }
        }
    }
}

/** Una tecla blanca con borde suave (el `.cp-keys` del mockup). */
@Composable
private fun Tecla(texto: String, habilitado: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick, modifier, enabled = habilitado, shape = RoundedCornerShape(12.dp),
        color = if (habilitado) ColoresCampo.Blanco else ColoresCampo.Gris100, border = BorderStroke(1.dp, ColoresCampo.Gris200),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(texto, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (habilitado) ColoresCampo.Gris900 else ColoresCampo.Gris400)
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PinPreview() = TemaCampo {
    PinContenido(
        EstadoEntrada(
            nombre = "Ana Pérez",
            sucursales = listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null)),
            venueIdElegido = "v1",
        ),
        sinSenal = true, onEntrar = {}, onElegir = {},
    )
}
