package com.jaac.avoqado_tpv.features.payment.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.R

/**
 * Fondo de nota de venta: el vector con las muescas a los costados
 * (`ilu_ticket_background`, 324x368dp), tenido con el color del tema.
 *
 * Es el MISMO asset que ya usan el riel Blumon (PaymentScreen) y el kiosko, para que
 * la nota se vea igual en las tres pantallas — el cajero no deberia notar en cual
 * terminal esta parado.
 *
 * El tinte NO es cosmetico: el vector trae `fillColor="#ffffff"` FIJO. Sin
 * ColorFilter.tint la nota sale blanca sobre fondo oscuro en tema dark.
 *
 * 🔴 Y NO uses `surfaceVariant` aqui, aunque sea lo que hace PaymentScreen. Medido en
 * la N86: esta pantalla se asienta sobre `surface` (DarkSurface #2A2A2A) y
 * `surfaceVariant` es #282828 — DOS tonos MAS OSCURO, contraste 1.03:1, invisible.
 * En Blumon si funciona solo porque esa pantalla va sobre `background` (#1C1C1C).
 * `onSurface` a alfa bajo se separa del fondo en la direccion correcta en AMBOS
 * temas: en dark aclara (onSurface casi blanco), en light oscurece (casi negro).
 * El tema no define tokens DarkSurfaceContainer*, asi que surfaceContainerHigh
 * caeria al default violaceo de Material y romperia la paleta neutra.
 *
 * @param topOverlap cuanto se empuja el ticket hacia abajo para que el QR (dibujado
 *   encima, alineado arriba) sobresalga del borde superior. Ese encimado es
 *   deliberado: es lo que hace que se lea como papel y no como tarjeta.
 */
@Composable
fun ReceiptTicketBackground(
    modifier: Modifier = Modifier,
    topOverlap: Dp = 0.dp,
) {
    Image(
        painter = painterResource(R.drawable.ilu_ticket_background),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(receiptChromeFill()),
        modifier = modifier.padding(top = topOverlap),
    )
}

/**
 * Perforacion de la nota — la linea punteada donde "se corta" el ticket.
 *
 * Va a la altura de las muescas del vector para que las tres piezas (muesca
 * izquierda, punteado, muesca derecha) se lean como un solo corte.
 */
@Composable
fun ReceiptPerforation(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
        )
    }
}

/**
 * El gris de la nota. UN solo valor para el ticket Y para los botones de la pantalla,
 * porque separarlos es exactamente como se llego al bug: cada elemento eligio su
 * propio token y todos cayeron sobre un fondo que no era el que asumian.
 *
 * `onSurface` a alfa bajo es lo unico que se separa del fondo en la direccion correcta
 * en AMBOS temas: aclara en dark, oscurece en light. Medido en la N86: sobre
 * `surface` (42,42,42) rinde (63,63,63) — contraste 1.34:1, visible sin gritar.
 */
@Composable
fun receiptChromeFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
