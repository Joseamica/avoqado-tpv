package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_campo.cola.SubidorDeGracia
import com.jaac.avoqado_campo.cola.TurnoLocal
import com.jaac.avoqado_campo.copia.Copia
import com.jaac.avoqado_campo.copia.MarcaLocal
import com.jaac.avoqado_campo.gps.Bateria
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.ResultadoDelBorrado
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.version.AvisoDeVersion
import com.jaac.avoqado_campo.version.PasoDeActualizacion
import com.jaac.avoqado_campo.version.VersionNueva
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo que pinta el Inicio. [entradaAt]: la hora del toque de la entrada abierta (el chip «Turno abierto · entraste a las 9:02»).
 * [enCustodia]: null mientras no hay copia de «Mis SIMs» en el celular.
 */
data class DatosDeInicio(
    val nombre: String?, val pendientes: Int, val necesitaPin: Boolean, val esperanSucursal: List<String>,
    val turnoAbierto: Boolean, val gps: ResultadoGps?,
    val version: VersionNueva? = null, val obligatoria: Boolean = false, val paso: PasoDeActualizacion = PasoDeActualizacion.Quieto,
    val sucursal: String? = null, val entradaAt: Long? = null, val enCustodia: Int? = null, val articulo: String = "SIM",
)

class AccionesDeInicio(
    val vender: () -> Unit, val sims: () -> Unit, val checar: () -> Unit, val pendientes: () -> Unit, val ayuda: () -> Unit,
    val entrar: () -> Unit, val actualizar: () -> Unit = {},
)

/** El GPS del turno corre o debería correr: sólo entonces el Inicio avisa de él y de la batería (H-A1). */
internal fun DatosDeInicio.cuidaElGps() = turnoAbierto && gps != ResultadoGps.NoLoUsaLaTienda

/**
 * La hora del chip «Entraste HH:MM»: sólo la de la entrada tocada en ESTE celular. Si se usó la entrada que ya existía en el
 * SERVIDOR (`llaveEntrada` en null), la hora local es la del intento, no la de la entrada, y no se muestra (Codex rediseño #4).
 */
internal fun horaDeEntrada(t: TurnoLocal?, staffId: String?): Long? =
    t?.takeIf { it.abierto && it.staffId == staffId && it.llaveEntrada != null }?.abiertoAt

/** Las que cuenta «En tu custodia» de «Mis SIMs»: suyas y aceptadas, sin las que ya se vendieron en el celular. */
internal fun cuantasEnCustodia(c: Copia?, venueId: String?): Int? =
    c?.takeIf { it.venueId == venueId }?.sims?.count { it.custodyState == "PROMOTER_HELD" && it.localmente != MarcaLocal.VENDIDA_EN_EL_CELULAR }

@Composable
fun InicioScreen(d: DatosDeInicio, a: AccionesDeInicio) {
    val context = LocalContext.current
    // Se vuelve a medir al regresar de los ajustes del teléfono.
    val ciclo by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val bateria = remember(ciclo, d.turnoAbierto, d.gps) {
        if (d.cuidaElGps() && Bateria.estaOptimizada(context)) Bateria.intentParaArreglar(context) to true else null to false
    }
    val saludo = listOfNotNull(d.nombre?.let { "Hola, $it" }, d.sucursal).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    PantallaCampo("Avoqado Campo", subtitulo = saludo, derecha = { if (d.nombre != null) Iniciales(d.nombre) }) {
        if (d.turnoAbierto) Recuadro(d.entradaAt?.let { "Turno abierto · entraste a las ${horaCorta(it)}" } ?: "Turno abierto", Tono.OK)
        AvisoDeVersion(d.version, d.obligatoria, d.paso, a.actualizar)
        if (d.necesitaPin) Recuadro("Escribe tu PIN con señal para subir lo pendiente", Tono.AVISO, boton = "Escribir mi PIN", onBoton = a.entrar)
        d.esperanSucursal.forEach { Recuadro("Tienes cosas por subir de $it", Tono.AVISO, boton = "Entrar a $it", onBoton = a.entrar) }
        if (d.turnoAbierto && d.gps is ResultadoGps.NoSigue) Recuadro("El GPS del turno no está siguiendo: ${d.gps.motivo}", Tono.AVISO)
        if (bateria.second) {
            Recuadro(
                "Tu celular puede apagar el GPS del turno para ahorrar batería", Tono.AVISO,
                boton = bateria.first?.let { "Arreglar" }, onBoton = { bateria.first?.let(context::startActivity) },
            )
        }
        // D-2: con una obligatoria pendiente no se empieza nada NUEVO; Mis SIMs y Checar siguen (dentro, aceptar y entrar en gris).
        ParDeMosaicos(
            { Mosaico("Vender ${d.articulo}", null, a.vender, it, enabled = !d.obligatoria, principal = true) },
            { Mosaico("Mis SIMs", d.enCustodia?.let { n -> "$n en tu custodia" }, a.sims, it) },
        )
        ParDeMosaicos(
            {
                val detalle = if (d.turnoAbierto) d.entradaAt?.let { t -> "Entraste ${horaCorta(t)}" } ?: "Turno abierto" else "Sin turno abierto"
                Mosaico(if (d.turnoAbierto) "Checar salida" else "Checar entrada", detalle, a.checar, it)
            },
            {
                val detalle = if (d.pendientes == 0) "Todo subido" else "por subir o revisar"
                Mosaico("Mis pendientes", detalle, a.pendientes, it, insignia = d.pendientes.takeIf { n -> n > 0 })
            },
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(a.ayuda, Modifier.heightIn(min = 48.dp)) { Text("Ayuda", color = ColoresCampo.Gris700, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Dos mosaicos de la misma altura, uno junto al otro. */
@Composable
private fun ParDeMosaicos(izquierda: @Composable (Modifier) -> Unit, derecha: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        izquierda(Modifier.weight(1f).fillMaxHeight())
        derecha(Modifier.weight(1f).fillMaxHeight())
    }
}

/** Un mosaico del Inicio (el `.cp-tile`): arriba el detalle (o el «+»), abajo el nombre; [insignia] es el contador naranja. */
@Composable
private fun Mosaico(
    titulo: String, detalle: String?, onClick: () -> Unit, modifier: Modifier,
    enabled: Boolean = true, principal: Boolean = false, insignia: Int? = null,
) {
    val fondo = when {
        !enabled -> ColoresCampo.Gris100
        principal -> ColoresCampo.Verde
        else -> ColoresCampo.Blanco
    }
    val tinta = when {
        !enabled -> ColoresCampo.Gris400
        principal -> ColoresCampo.Blanco
        else -> ColoresCampo.Gris900
    }
    Surface(
        onClick, modifier.heightIn(min = 108.dp), enabled = enabled, shape = MaterialTheme.shapes.large, color = fondo, contentColor = tinta,
        border = if (principal && enabled) null else BorderStroke(1.dp, ColoresCampo.Gris200),
    ) {
        Box(Modifier.padding(14.dp)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                if (principal) Text("+", color = tinta, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                else Text(
                    detalle.orEmpty(), Modifier.padding(end = if (insignia != null) 32.dp else 0.dp),
                    color = if (enabled) ColoresCampo.Gris500 else tinta, style = MaterialTheme.typography.bodySmall,
                )
                Text(titulo, color = tinta, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            if (insignia != null) {
                Text(
                    "$insignia",
                    Modifier.align(Alignment.TopEnd).background(ColoresCampo.NaranjaFuerte, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
                    color = ColoresCampo.Blanco, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
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
    PantallaCampo("Avoqado Campo") {
        Tarjeta {
            Text(
                if (puedeActivarDeNuevo) "Este celular ya no está vinculado. Ya no queda nada por subir." else SubidorDeGracia.TEXTO_DE_LA_PANTALLA,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        BotonSecundario("Mis pendientes ($pendientes)", onPendientes)
        if (puedeActivarDeNuevo) {
            BotonPrincipal("Activar con un código nuevo", {
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
            }, enabled = !trabajando, cargando = trabajando)
        }
        mensaje?.let { Recuadro(it, Tono.AVISO) }
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
    PantallaCampo("Avoqado Campo") {
        Recuadro("Este celular ya no está registrado. Avísale a tu supervisor.", Tono.AVISO)
        BotonSecundario("Mis pendientes", onPendientes)
        BotonSecundario("Borrar este celular y activarlo de nuevo", { confirmar = true; mensaje = null }, enabled = !trabajando)
        mensaje?.let { Recuadro(it, Tono.AVISO) }
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

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun InicioPreview() = TemaCampo {
    androidx.compose.runtime.CompositionLocalProvider(LocalBanda provides SIN_SENAL) {
        InicioScreen(
            DatosDeInicio(
                "Ana Ruiz", 3, necesitaPin = false, esperanSucursal = emptyList(), turnoAbierto = true, gps = null,
                sucursal = "Walmart Coapa", entradaAt = 1_794_236_520_000, enCustodia = 24,
            ),
            AccionesDeInicio({}, {}, {}, {}, {}, {}),
        )
    }
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
    PantallaCampo("Avoqado Campo") {
        Recuadro(TEXTO_DETENIDA, Tono.AVISO)
        BotonPrincipal("Reintentar", onReintentar)
    }
}

internal const val TEXTO_DETENIDA = "Tu celular no tiene espacio o no pudo guardar. Libera espacio y toca «Reintentar». No se perdió nada."
