package com.jaac.avoqado_campo.entrada

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tenue
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo

/**
 * Activar (mockup A, una sola vez): el código de 6 que dio el supervisor, en cuadritos, y el PIN de siempre; un botón. La
 * navegación (Tarea 13) llega por [onDentro].
 */
@Composable
fun ActivarScreen(vm: EntradaViewModel, onDentro: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(estado.dentro) { if (estado.dentro) onDentro() }
    ActivarContenido(estado, vm::activar)
}

@Composable
fun ActivarContenido(estado: EstadoEntrada, onActivar: (codigo: String, pin: String) -> Unit) {
    var codigo by rememberSaveable { mutableStateOf("") }
    var pin by remember { mutableStateOf("") } // nunca saveable: el Bundle del estado guardado va en claro
    val bloqueado = estado.trabajando || estado.terminandoActivacion
    val listo = codigo.length == 6 && pin.length in 4..10
    PantallaCampo(
        "Activa este celular",
        abajo = {
            BotonPrincipal(
                if (estado.terminandoActivacion) "Terminar la activación" else "Activar",
                { onActivar(codigo, pin) },
                enabled = !estado.trabajando && (listo || estado.terminandoActivacion),
                cargando = estado.trabajando,
            )
        },
    ) {
        Text(
            "Pídele a tu supervisor tu código de 6 letras. Sólo se hace una vez en este celular.",
            color = ColoresCampo.Gris700, style = MaterialTheme.typography.bodyLarge,
        )
        CodigoEnCuadros(codigo, enabled = !bloqueado) { v -> codigo = v.filter(Char::isLetterOrDigit).uppercase().take(6) }
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter(Char::isDigit).take(10) },
            label = { Text("Tu PIN de siempre") },
            supportingText = { Text("De 4 a 10 números") },
            singleLine = true,
            enabled = !bloqueado,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (estado.terminandoActivacion) Tenue("Terminando la activación anterior…")
        estado.mensaje?.let { Recuadro(it, Tono.ERROR) }
    }
}

/** Los 6 cuadritos del código (el `.cp-code` del mockup); detrás es un solo campo de texto, con su teclado en mayúsculas. */
@Composable
private fun CodigoEnCuadros(codigo: String, enabled: Boolean, onCambio: (String) -> Unit) {
    BasicTextField(
        value = codigo,
        onValueChange = onCambio,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Código de 6 letras o números" },
        decorationBox = { campo ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(1.dp).alpha(0f)) { campo() } // el campo real, invisible: los cuadros pintan lo escrito
                Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val c = codigo.getOrNull(i)
                        val activo = enabled && (i == codigo.length || (i == 5 && codigo.length == 6))
                        Box(
                            Modifier.weight(1f).height(56.dp)
                                .background(if (enabled) ColoresCampo.Blanco else ColoresCampo.Gris100, RoundedCornerShape(10.dp))
                                .border(if (activo) 2.dp else 1.5.dp, if (c != null || activo) ColoresCampo.Verde else ColoresCampo.Gris300, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text(c?.toString().orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ColoresCampo.Gris900) }
                    }
                }
            }
        },
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun ActivarPreview() = TemaCampo { ActivarContenido(EstadoEntrada(mensaje = "Ese código ya se usó. Pide uno nuevo.")) { _, _ -> } }
