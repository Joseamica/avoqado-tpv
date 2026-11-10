package com.jaac.avoqado_campo.checada

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.ui.tema.TemaCampo

private val UBICACION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** «Checar»: entrada y salida, con selfie frontal si la sucursal la pide (B9) y la ubicación si hay permiso. */
@Composable
fun ChecadaScreen(vm: ChecadaViewModel, onRegresar: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun hay() = UBICACION.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var conUbicacion by remember { mutableStateOf(hay()) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { conUbicacion = hay() }
    LaunchedEffect(Unit) { if (!conUbicacion) pedir.launch(UBICACION) }

    val selfie = estado.pideSelfie
    if (selfie != null) {
        Camara(
            titulo = if (selfie == TipoChecada.ENTRADA) "tu selfie de entrada" else "tu selfie de salida", frontal = true,
            onFoto = { vm.alTomarSelfie(it.absolutePath) }, onCerrar = vm::cancelarSelfie,
        )
    } else {
        ChecadaContenido(estado, conUbicacion, { pedir.launch(UBICACION) }, vm::marcarEntrada, vm::marcarSalida, onRegresar)
    }
}

private val alto = Modifier.fillMaxWidth().heightIn(min = 48.dp)

@Composable
fun ChecadaContenido(
    e: EstadoChecada, conUbicacion: Boolean, pedirUbicacion: () -> Unit,
    entrada: () -> Unit, salida: () -> Unit, regresar: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Checar", style = MaterialTheme.typography.headlineSmall)
            Text(if (e.entrada != null) "Tienes una entrada abierta en este celular." else "No tienes una entrada abierta.")
            if (!conUbicacion) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Usamos tu ubicación para registrar dónde checas y, durante tu turno, para tu supervisor. Sin ella se checa igual, sin ubicación.")
                        OutlinedButton(pedirUbicacion, alto) { Text("Permitir la ubicación") }
                    }
                }
            }
            Button(entrada, alto, enabled = !e.ocupado) { Text("Marcar entrada") }
            OutlinedButton(salida, alto, enabled = !e.ocupado) { Text("Marcar salida") }
            if (e.buscandoUbicacion) Text("Buscando tu ubicación…")
            e.avisoUbicacion?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            e.aviso?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) } }
            e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            (e.gps as? ResultadoGps.NoSigue)?.takeIf { e.entrada != null && e.aviso == null }?.let {
                Text("No estamos siguiendo tu ubicación: ${it.motivo}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(regresar, alto) { Text("Regresar") }
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun ChecadaPreview() = TemaCampo {
    ChecadaContenido(
        EstadoChecada(
            entrada = RefEntrada.PorLlave("k"), avisoUbicacion = "Se checa sin ubicación",
            aviso = "La checada quedó guardada · no estamos siguiendo tu ubicación: la ubicación del teléfono está apagada",
        ),
        conUbicacion = false, {}, {}, {}, {},
    )
}
