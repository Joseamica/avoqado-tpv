package com.jaac.avoqado_campo.entrada

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.ui.tema.TemaCampo

/** Activar: el código de 6 que dio el supervisor y el PIN, un botón. La navegación (Tarea 13) llega por [onDentro]. */
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Activa tu celular", style = MaterialTheme.typography.headlineSmall)
            Text("Escribe el código que te dio tu supervisor y tu PIN.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = codigo,
                onValueChange = { v -> codigo = v.filter(Char::isLetterOrDigit).uppercase().take(6) },
                label = { Text("Código (6 letras o números)") },
                singleLine = true,
                enabled = !bloqueado,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter(Char::isDigit).take(10) },
                label = { Text("PIN") },
                singleLine = true,
                enabled = !bloqueado,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (estado.terminandoActivacion) Text("Terminando la activación anterior…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            estado.mensaje?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { onActivar(codigo, pin) },
                enabled = !estado.trabajando && (listo || estado.terminandoActivacion),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (estado.trabajando) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else Text(if (estado.terminandoActivacion) "Terminar la activación" else "Activar")
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun ActivarPreview() = TemaCampo { ActivarContenido(EstadoEntrada(mensaje = "Ese código ya se usó. Pide uno nuevo.")) { _, _ -> } }
