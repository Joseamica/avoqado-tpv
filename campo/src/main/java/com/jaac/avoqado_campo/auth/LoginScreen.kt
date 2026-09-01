package com.jaac.avoqado_campo.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel(), alEntrar: (String) -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var correo by remember { mutableStateOf("") }
    var contrasena by remember { mutableStateOf("") }

    LaunchedEffect(estado) { (estado as? EstadoLogin.Dentro)?.let { alEntrar(it.venueId) } }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Avoqado Campo", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(correo, { correo = it }, label = { Text("Correo") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(contrasena, { contrasena = it }, label = { Text("Contraseña") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { vm.entrar(correo.trim(), contrasena) },
            enabled = estado !is EstadoLogin.Cargando && correo.isNotBlank() && contrasena.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (estado is EstadoLogin.Cargando) "Entrando…" else "Entrar") }
        (estado as? EstadoLogin.Error)?.let {
            Spacer(Modifier.height(12.dp))
            Text(it.mensaje, color = MaterialTheme.colorScheme.error)
        }
    }
}
