package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.cola.SubidorDeGracia
import com.jaac.avoqado_campo.gps.Bateria
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.ResultadoDelBorrado
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class DatosDeInicio(
    val nombre: String?, val pendientes: Int, val necesitaPin: Boolean, val esperanSucursal: List<String>,
    val turnoAbierto: Boolean, val gps: ResultadoGps?,
)

class AccionesDeInicio(
    val vender: () -> Unit, val sims: () -> Unit, val checar: () -> Unit, val pendientes: () -> Unit, val ayuda: () -> Unit,
    val entrar: () -> Unit,
)

private val alto = Modifier.fillMaxWidth().heightIn(min = 48.dp)

/** El GPS del turno corre o debería correr: sólo entonces el Inicio avisa de él y de la batería (H-A1). */
internal fun DatosDeInicio.cuidaElGps() = turnoAbierto && gps != ResultadoGps.NoLoUsaLaTienda

@Composable
fun InicioScreen(d: DatosDeInicio, a: AccionesDeInicio) {
    val context = LocalContext.current
    // Se vuelve a medir al regresar de los ajustes del teléfono.
    val ciclo by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val bateria = remember(ciclo, d.turnoAbierto, d.gps) {
        if (d.cuidaElGps() && Bateria.estaOptimizada(context)) Bateria.intentParaArreglar(context) to true else null to false
    }
    Pantalla {
        Text(d.nombre?.let { "Hola, $it" } ?: "Avoqado Campo", style = MaterialTheme.typography.headlineSmall)
        if (d.necesitaPin) Aviso("Escribe tu PIN con señal para subir lo pendiente", "Escribir mi PIN", a.entrar)
        d.esperanSucursal.forEach { Aviso("Tienes cosas por subir de $it", "Entrar a $it", a.entrar) }
        if (d.turnoAbierto && d.gps is ResultadoGps.NoSigue) Aviso("El GPS del turno no está siguiendo: ${d.gps.motivo}", null) {}
        if (bateria.second) {
            Aviso("Tu celular puede apagar el GPS del turno para ahorrar batería", bateria.first?.let { "Arreglar" }) {
                bateria.first?.let(context::startActivity)
            }
        }
        Button(a.vender, alto) { Text("Vender") }
        Button(a.sims, alto) { Text("Mis SIMs") }
        Button(a.checar, alto) { Text("Checar") }
        OutlinedButton(a.pendientes, alto) { Text("Mis pendientes (${d.pendientes})") }
        OutlinedButton(a.ayuda, alto) { Text("Ayuda") }
    }
}

/** DESVINCULADO o DADO_DE_BAJA: la gracia sube lo que quedó; con la cola vacía se puede activar con otro código (B6). */
@Composable
fun PantallaDeGracia(
    pendientes: Int, puedeActivarDeNuevo: Boolean, onPendientes: () -> Unit, onActivarDeNuevo: suspend () -> ResultadoDelBorrado,
) {
    val alcance = rememberCoroutineScope()
    var mensaje by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    Pantalla {
        Text(
            if (puedeActivarDeNuevo) "Este celular ya no está vinculado. Ya no queda nada por subir." else SubidorDeGracia.TEXTO_DE_LA_PANTALLA,
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onPendientes, alto) { Text("Mis pendientes ($pendientes)") }
        if (puedeActivarDeNuevo) {
            Button({
                trabajando = true
                alcance.launch {
                    try {
                        mensaje = when (borrarSinTronar(onActivarDeNuevo)) {
                            ResultadoDelBorrado.BORRADO -> null
                            ResultadoDelBorrado.QUEDA_ALGO -> "Todavía queda algo por subir. Revisa «Mis pendientes»."
                            ResultadoDelBorrado.ESPERA -> ESPERA_UN_MOMENTO
                            null -> NO_SE_PUDO_TERMINAR
                        }
                    } finally {
                        trabajando = false
                    }
                }
            }, alto, enabled = !trabajando) { Text("Activar con un código nuevo") }
        }
        mensaje?.let { Text(it) }
    }
}

/**
 * La credencial ya no existe en el SERVIDOR: sólo se sale borrando, con el número exacto de lo que se pierde. r3: [onBorrar]
 * recibe el número que la persona confirmó; si ya cambió, no borra y se vuelve a preguntar con el nuevo.
 */
@Composable
fun PantallaDesconocido(porSubir: Int, onPendientes: () -> Unit, onBorrar: suspend (confirmadas: Int) -> ResultadoDelBorrado) {
    val alcance = rememberCoroutineScope()
    var confirmar by remember { mutableStateOf(false) }
    var cambio by remember { mutableStateOf(false) }
    var trabajando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    Pantalla {
        Text("Este celular ya no está registrado. Avísale a tu supervisor.", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onPendientes, alto) { Text("Mis pendientes") }
        Button({ confirmar = true; mensaje = null }, alto, enabled = !trabajando) { Text("Borrar este celular y activarlo de nuevo") }
        mensaje?.let { Text(it) }
    }
    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            text = {
                Text(
                    (if (cambio) "Cambió lo que queda sin subir. " else "") +
                        if (porSubir > 0) "Se van a borrar $porSubir cosas que no se subieron. Avísale a tu supervisor antes."
                        else "No queda nada sin subir. ¿Borrar este celular y activarlo de nuevo?",
                )
            },
            confirmButton = {
                TextButton({
                    val vistas = porSubir
                    confirmar = false
                    trabajando = true
                    alcance.launch {
                        try {
                            when (borrarSinTronar { onBorrar(vistas) }) {
                                ResultadoDelBorrado.BORRADO -> Unit
                                ResultadoDelBorrado.QUEDA_ALGO -> { cambio = true; confirmar = true }
                                ResultadoDelBorrado.ESPERA -> { mensaje = ESPERA_UN_MOMENTO }
                                null -> { mensaje = NO_SE_PUDO_TERMINAR }
                            }
                        } finally {
                            trabajando = false
                        }
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton({ confirmar = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Pantalla(contenido: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { contenido() }
    }
}

@Composable
private fun Aviso(texto: String, boton: String?, onBoton: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(texto)
            if (boton != null) OutlinedButton(onBoton, alto) { Text(boton) }
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun InicioPreview() = TemaCampo {
    InicioScreen(
        DatosDeInicio("Ana", 3, necesitaPin = true, esperanSucursal = listOf("Norte"), turnoAbierto = false, gps = null),
        AccionesDeInicio({}, {}, {}, {}, {}, {}),
    )
}

/** r4: algo que la persona ya tocó (la checada que espera el GPS) no llegó a guardarse a tiempo, u otro borrado sigue: no se borró nada. */
private const val ESPERA_UN_MOMENTO = "Espera un momento y vuelve a intentar."

/** r9 · R-C: el borrado falló (el almacenamiento del celular); lo que no se confirmó ya se deshizo y no se borró nada. */
internal const val NO_SE_PUDO_TERMINAR = "No se pudo terminar. Intenta otra vez."

/** R-C: el resultado del borrado, o null si falló: el error se queda aquí, nunca cierra la app (la pantalla dice [NO_SE_PUDO_TERMINAR]). */
internal suspend fun borrarSinTronar(borrar: suspend () -> ResultadoDelBorrado): ResultadoDelBorrado? = try {
    borrar()
} catch (c: CancellationException) {
    throw c
} catch (_: Exception) {
    null
}

/**
 * r12 · el arranque no pudo guardar (p. ej. sin espacio): sólo esto, sin otra pantalla ni escritura. «Reintentar» abre un
 * proceso nuevo que vuelve a correr la recuperación del arranque.
 */
@Composable
fun PantallaDetenida(onReintentar: () -> Unit) {
    Pantalla {
        Text(TEXTO_DETENIDA, style = MaterialTheme.typography.titleMedium)
        Button(onReintentar, alto) { Text("Reintentar") }
    }
}

internal const val TEXTO_DETENIDA = "Tu celular no tiene espacio o no pudo guardar. Libera espacio y toca «Reintentar». No se perdió nada."
