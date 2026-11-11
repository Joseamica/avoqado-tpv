package com.jaac.avoqado_campo.entrada

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.identidad.Sucursal
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

@Composable
fun PinContenido(estado: EstadoEntrada, sinSenal: Boolean, onEntrar: (String) -> Unit, onElegir: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(estado.mensaje) { if (estado.mensaje != null) pin = "" } // tras un rechazo, se escribe de nuevo
    val falta = estado.sucursales.size > 1 && estado.venueIdElegido == null
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(estado.nombre?.let { "Hola, $it" } ?: "Escribe tu PIN", style = MaterialTheme.typography.headlineSmall)
            if (sinSenal || estado.dentroSinSenal) {
                Text("Sin señal · entras con tu PIN guardado", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (estado.sucursales.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    estado.sucursales.forEach { s ->
                        FilterChip(
                            selected = s.venueId == estado.venueIdElegido,
                            onClick = { onElegir(s.venueId) },
                            label = { Text(s.nombre) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            Text("●".repeat(pin.length).ifEmpty { "PIN" }, style = MaterialTheme.typography.headlineMedium)
            estado.mensaje?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Teclado(
                habilitado = !estado.trabajando,
                onDigito = { if (pin.length < 10) pin += it },
                onBorrar = { pin = pin.dropLast(1) },
            )
            Button(
                onClick = { onEntrar(pin) },
                enabled = !estado.trabajando && pin.length >= 4 && !falta,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (estado.trabajando) "Entrando…" else "Entrar") }
        }
    }
}

@Composable
private fun Teclado(habilitado: Boolean, onDigito: (Char) -> Unit, onBorrar: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789", " 0<").forEach { fila ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { c ->
                    val boton = Modifier.weight(1f).heightIn(min = 56.dp)
                    when (c) {
                        ' ' -> Row(boton) {}
                        '<' -> OutlinedButton(onClick = onBorrar, enabled = habilitado, modifier = boton) { Text("Borrar") }
                        else -> OutlinedButton(onClick = { onDigito(c) }, enabled = habilitado, modifier = boton) {
                            Text(c.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
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
