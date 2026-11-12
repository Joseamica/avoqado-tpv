package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_campo.ui.tema.ColoresCampo as C
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * El texto de la banda de señal. La navegación lo pone UNA vez para todas las pantallas y cada [PantallaCampo] lo pinta
 * debajo de su encabezado, como el mockup (D2: nunca dos bandas).
 */
val LocalBanda = compositionLocalOf<String?> { null }

/** En la tableta del laboratorio (ancha) el contenido no se estira: se centra con este ancho máximo. */
val ANCHO_MAXIMO = 560.dp

internal const val ETIQUETA_CONTENIDO = "contenido"

/**
 * El esqueleto de cada pantalla: encabezado («‹ Título», su subtítulo y algo a la derecha), la banda de señal, el contenido con scroll y,
 * si hay, los botones de abajo fijos. [regresarHabilitado] apaga la flecha mientras algo se guarda.
 */
@Composable
fun PantallaCampo(
    titulo: String?,
    onRegresar: (() -> Unit)? = null,
    subtitulo: String? = null,
    regresarHabilitado: Boolean = true,
    derecha: (@Composable () -> Unit)? = null,
    centrado: Boolean = false,
    abajo: (@Composable ColumnScope.() -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = ANCHO_MAXIMO).fillMaxWidth().fillMaxHeight().testTag(ETIQUETA_CONTENIDO)) {
                if (titulo != null || onRegresar != null || derecha != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (onRegresar != null) 4.dp else 16.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onRegresar != null) {
                            IconButton(onRegresar, Modifier.semantics { contentDescription = "Regresar" }, enabled = regresarHabilitado) {
                                Text("‹", fontSize = 32.sp, fontWeight = FontWeight.Medium, color = if (regresarHabilitado) C.Gris900 else C.Gris400)
                            }
                        }
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(titulo.orEmpty(), style = MaterialTheme.typography.titleLarge)
                            subtitulo?.let { Text(it, color = C.Gris500, style = MaterialTheme.typography.bodyMedium) }
                        }
                        derecha?.invoke()
                    }
                }
                LocalBanda.current?.let { BandaDeSenal(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = if (centrado) Alignment.CenterHorizontally else Alignment.Start,
                    content = contenido,
                )
                if (abajo != null) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = abajo)
                }
            }
        }
    }
}

private val FormaDeBoton = RoundedCornerShape(12.dp)

/** El botón verde de la acción principal (el `.cp-btn` del mockup). En gris cuando no se puede. */
@Composable
fun BotonPrincipal(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, cargando: Boolean = false) {
    Button(
        onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = FormaDeBoton,
        colors = ButtonDefaults.buttonColors(
            containerColor = C.Verde, contentColor = C.Blanco, disabledContainerColor = C.Gris100, disabledContentColor = C.Gris400,
        ),
    ) {
        if (cargando) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 2.dp), color = C.Gris400, strokeWidth = 2.dp)
        Text(texto, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = if (cargando) 8.dp else 0.dp))
    }
}

/** El botón blanco con borde (el `.cp-btn2` del mockup). */
@Composable
fun BotonSecundario(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, lleno: Boolean = true) {
    OutlinedButton(
        onClick, (if (lleno) modifier.fillMaxWidth() else modifier).heightIn(min = 52.dp), enabled = enabled, shape = FormaDeBoton,
        border = BorderStroke(1.dp, if (enabled) C.Gris300 else C.Gris200),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = C.Blanco, contentColor = C.Gris900, disabledContainerColor = C.Gris100, disabledContentColor = C.Gris400,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(texto, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) }
}

/** Una liga subrayada dentro de un aviso («Arreglar», «Escribir mi PIN»), con área de toque de 48 dp. */
@Composable
fun Enlace(texto: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp), enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Text(texto, color = if (enabled) color else C.Gris400, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
    }
}

/** La tarjeta blanca con borde suave (el `.cp-card`). */
@Composable
fun Tarjeta(modifier: Modifier = Modifier, relleno: PaddingValues = PaddingValues(14.dp), contenido: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = C.Blanco, border = BorderStroke(1.dp, C.Gris200)) {
        Column(Modifier.padding(relleno), verticalArrangement = Arrangement.spacedBy(6.dp), content = contenido)
    }
}

/**
 * El tono de un aviso. Sin señal es naranja con borde (no es un error); ámbar para lo que pide atención; rojo sólo para un
 * error o un rechazo de verdad.
 */
enum class Tono(val fondo: Color, val tinta: Color, val borde: Color? = null) {
    OK(C.VerdeClaro, C.VerdeTinta),
    AVISO(C.Naranja.copy(alpha = 0.12f), C.AmbarTinta),
    SIN_RED(C.NaranjaSinRed.copy(alpha = 0.07f), C.NaranjaSinRed, C.NaranjaSinRed),
    ERROR(C.RojoClaro, C.RojoTinta),
    INFO(C.AzulClaro, C.AzulTinta),
    NEUTRO(C.Gris100, C.Gris700),
}

/** Un recuadro de color con lo que se quiera adentro (los `.cp-ok`, `.cp-warn`, `.cp-off`, `.cp-err` del mockup). */
@Composable
fun Caja(tono: Tono, modifier: Modifier = Modifier, contenido: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = tono.fondo, contentColor = tono.tinta, border = tono.borde?.let { BorderStroke(1.dp, it) }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = contenido)
    }
}

/** Un aviso de una línea (o varias) y, si hay, su liga. */
@Composable
fun Recuadro(texto: String, tono: Tono, modifier: Modifier = Modifier, boton: String? = null, onBoton: () -> Unit = {}) {
    Caja(tono, modifier) {
        Text(texto, color = tono.tinta, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (boton != null) Enlace(boton, tono.tinta, onBoton)
    }
}

/** La pastilla de estado de una fila («Esperando señal», «En revisión»…). */
@Composable
fun Pastilla(texto: String, tono: Tono, modifier: Modifier = Modifier) {
    Text(
        texto,
        modifier.background(tono.fondo, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
        color = tono.tinta, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

/** El título de una sección («POR SUBIR (3)»), como el `.cp-h`. */
@Composable
fun TituloDeSeccion(texto: String) {
    Text(texto.uppercase(), color = C.Gris700, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 4.dp))
}

/** Texto gris secundario. */
@Composable
fun Tenue(texto: String, modifier: Modifier = Modifier, color: Color = C.Gris500) {
    Text(texto, modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}

/** El círculo verde claro con las iniciales de la persona. */
@Composable
fun Iniciales(nombre: String?, tamano: Dp = 36.dp) {
    Box(Modifier.size(tamano).background(C.VerdeClaro, CircleShape), contentAlignment = Alignment.Center) {
        Text(iniciales(nombre), color = C.VerdeTinta, fontWeight = FontWeight.Bold, fontSize = (tamano.value * 0.36f).sp)
    }
}

/** «Ana Martínez» → «AM»; «Ana» → «A»; sin nombre, nada. */
fun iniciales(nombre: String?): String =
    nombre.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }

private val MEXICO = ZoneId.of("America/Mexico_City")
private val HORA = DateTimeFormatter.ofPattern("H:mm")

/** La hora corta de un instante, en la hora de México (9:02). */
fun horaCorta(epochMs: Long): String = HORA.format(Instant.ofEpochMilli(epochMs).atZone(MEXICO))

/** «8952020012345678471F» → «8952 0200 1234 5678 471F», como el mockup; sólo para mostrar. */
fun enGrupos(serie: String): String = serie.chunked(4).joinToString(" ")
