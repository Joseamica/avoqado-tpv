package com.jaac.avoqado_campo.checada

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.fotos.Camara
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.ui.BotonPrincipal
import com.jaac.avoqado_campo.ui.BotonSecundario
import com.jaac.avoqado_campo.ui.PantallaCampo
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tarjeta
import com.jaac.avoqado_campo.ui.Tenue
import com.jaac.avoqado_campo.ui.Tono
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.version.ACTUALIZA_PARA_SEGUIR

private val UBICACION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
// Android 13+: el aviso «Turno activo» del GPS. Si lo niega, el seguimiento sigue igual (Android lo muestra en sus tareas).
private val PEDIR = UBICACION + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

/**
 * «Checar»: entrada y salida, con selfie frontal si la sucursal la pide (B9) y la ubicación si hay permiso. La selfie se toma
 * con el óvalo y el estado de la ubicación del mockup.
 */
@Composable
fun ChecadaScreen(vm: ChecadaViewModel, obligatoria: Boolean, onRegresar: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun hay() = UBICACION.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var conUbicacion by remember { mutableStateOf(hay()) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { conUbicacion = hay() }
    LaunchedEffect(Unit) {
        val faltan = PEDIR.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (!conUbicacion || (Build.VERSION.SDK_INT >= 33 && faltan)) pedir.launch(PEDIR)
    }

    BackHandler(enabled = estado.ocupado) {} // mientras se guarda, Atrás no sale (la checada seguiría de todos modos)
    val selfie = estado.pideSelfie
    if (selfie != null) {
        Camara(
            titulo = if (selfie == TipoChecada.ENTRADA) "tu selfie de entrada" else "tu selfie de salida", frontal = true,
            onFoto = { vm.alTomarSelfie(it.absolutePath) }, onCerrar = vm::cancelarSelfie,
            textoDelBoton = "Tomar foto y checar", extra = { ChipDeUbicacion(estado, conUbicacion) },
        )
    } else {
        ChecadaContenido(estado, conUbicacion, { pedir.launch(PEDIR) }, vm::marcarEntrada, vm::marcarSalida, onRegresar, obligatoria)
    }
}

/**
 * El chip de la ubicación (el `.cp-ok` / `.cp-warn` del mockup), con los textos de siempre. Antes de checar sólo se sabe si
 * hay permiso: la ubicación se lee al checar («Buscando tu ubicación…»), y si no llega se checa igual, sin ubicación.
 */
@Composable
private fun ChipDeUbicacion(e: EstadoChecada, conUbicacion: Boolean) {
    when {
        e.buscandoUbicacion -> Recuadro("📍 Buscando tu ubicación…", Tono.AVISO)
        e.avisoUbicacion != null -> Recuadro("📍 ${e.avisoUbicacion}", Tono.AVISO)
        !conUbicacion -> Recuadro("📍 Sin permiso de ubicación: se checa sin ubicación", Tono.AVISO)
        // Con permiso no se sabe si la ubicación del celular está encendida: no se afirma (Codex rediseño #2).
        else -> Recuadro("📍 Al checar se guarda tu ubicación", Tono.NEUTRO)
    }
}

@Composable
fun ChecadaContenido(
    e: EstadoChecada, conUbicacion: Boolean, pedirUbicacion: () -> Unit,
    entrada: () -> Unit, salida: () -> Unit, regresar: () -> Unit, obligatoria: Boolean = false,
) {
    // Una sola acción, la que toca (como el mockup): sin entrada abierta, marcarla; con entrada, marcar la salida.
    val conEntrada = e.entrada != null
    PantallaCampo(
        if (conEntrada) "Checar salida" else "Checar entrada", onRegresar = regresar, regresarHabilitado = !e.ocupado,
        abajo = {
            if (obligatoria) Tenue(ACTUALIZA_PARA_SEGUIR)
            // D-2: con una obligatoria pendiente no se abre un turno nuevo; la salida sigue (no dejar un turno atrapado).
            if (conEntrada) BotonPrincipal("Marcar salida", salida, enabled = !e.ocupado, cargando = e.ocupado)
            else BotonPrincipal("Marcar entrada", entrada, enabled = !e.ocupado && !obligatoria, cargando = e.ocupado)
        },
    ) {
        Tarjeta {
            Text(if (conEntrada) "Tienes una entrada abierta en este celular." else "No tienes una entrada abierta.", fontWeight = FontWeight.SemiBold)
            Tenue(
                when {
                    !conEntrada -> "Al marcar la entrada se toma tu foto (si tu tienda la pide) y la hora de este momento."
                    else -> "Al marcar la salida se toma tu foto (si tu tienda la pide) y tu turno se cierra con la hora de este momento."
                },
            )
        }
        ChipDeUbicacion(e, conUbicacion)
        if (!conUbicacion) {
            Tarjeta {
                Text("Usamos tu ubicación para registrar dónde checas y, durante tu turno, para tu supervisor. Sin ella se checa igual, sin ubicación.")
                BotonSecundario("Permitir la ubicación", pedirUbicacion)
            }
        }
        e.aviso?.let { Recuadro(it, if (it.startsWith(AVISO_NO_SIGUE)) Tono.AVISO else Tono.OK) }
        e.error?.let { Recuadro(it, Tono.ERROR) }
        // El GPS en vivo se dice siempre que no sigue, salvo que el aviso de la checada diga EXACTAMENTE lo mismo (Codex D2
        // r3 y la pasada de la familia: tras «Entrada guardada», o si cambió el motivo, Checar dice el de ahora).
        (e.gps as? ResultadoGps.NoSigue)?.takeIf { e.entrada != null && e.aviso != "$AVISO_NO_SIGUE: ${it.motivo}" }?.let {
            Recuadro("No estamos siguiendo tu ubicación: ${it.motivo}", Tono.AVISO)
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
