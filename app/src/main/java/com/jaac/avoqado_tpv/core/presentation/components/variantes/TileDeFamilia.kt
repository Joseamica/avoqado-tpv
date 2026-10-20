package com.jaac.avoqado_tpv.core.presentation.components.variantes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.TextosVariantes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import java.math.BigDecimal

private val TileShape = RoundedCornerShape(12.dp)
private val InsigniaShape = RoundedCornerShape(4.dp)
private val AcentoShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)

/**
 * Q4 con la regla del botón de un producto suelto: el precio más bajo de la familia, con «desde» si los precios difieren y el
 * precio solo si todos son iguales. Puro (sin Compose) para probarlo; [formato] es el que ya usa cada pantalla para sus precios.
 *
 * Es `Familia.textoDePrecio` de avoqado-android MENOS la rama «/kg»: los modelos de producto de la terminal no tienen "vendido por
 * peso", así que ahí no hay nada que agregar.
 */
fun <T> ReglasDeFamilia<T>.textoDePrecio(f: Familia<T>, formato: (BigDecimal) -> String): String {
    val texto = formato(precioMinimo(f))
    return if (preciosIguales(f)) texto else TextosVariantes.desde(texto)
}

/**
 * El botón del PADRE de una familia en una cuadrícula (spec 2026-10-14 §11): tocarlo abre la hoja de variantes y nunca agrega nada
 * por sí solo. Mismo tamaño, forma y color que `ProductCard` para que la cuadrícula no cambie de ritmo.
 *
 * - Insignia `variantes(n)`; precio `desde(precio)` sólo si no todas cuestan lo mismo.
 * - «Agotado» sólo cuando TODAS las variantes lo están (Q5) y el modelo trae existencias (Cobro rápido). Nunca bloquea el toque:
 *   qué pasa al elegir una variante agotada lo decide cada pantalla. El «se puede vender igual» de Android no se usa aquí.
 */
@Composable
fun <T> TileDeFamilia(
    familia: Familia<T>,
    reglas: ReglasDeFamilia<T>,
    precioTexto: (BigDecimal) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val precio = reglas.textoDePrecio(familia, precioTexto)
    val agotada = reglas.agotada(familia)
    // Sin color de categoría (una familia puede abarcar varias): el acento es neutro.
    val acento = MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = TileShape,
            )
            .semantics { contentDescription = TextosVariantes.accesibleFamilia(familia.nombre, familia.variantes.size, precio) },
        onClick = onClick,
        shape = TileShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxHeight().width(4.dp).background(color = acento, shape = AcentoShape))

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = familia.nombre,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Insignia(
                            texto = TextosVariantes.variantes(familia.variantes.size),
                            fondo = MaterialTheme.colorScheme.primaryContainer,
                            letra = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (agotada) {
                            Insignia(
                                texto = TextosVariantes.AGOTADO,
                                fondo = MaterialTheme.colorScheme.error,
                                letra = MaterialTheme.colorScheme.onError,
                            )
                        }
                    }
                    Text(
                        text = precio,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Insignia(texto: String, fondo: Color, letra: Color) {
    Box(modifier = Modifier.background(color = fondo, shape = InsigniaShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelSmall,
            color = letra,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(name = "Botón con «desde» y botón agotado", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun TileDeFamiliaPreview() {
    AvoqadoTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            TileDeFamilia(
                familia = familiaDeEjemplo(),
                reglas = reglasDeEjemplo,
                precioTexto = ::precioDeEjemplo,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            TileDeFamilia(
                familia = familiaDeEjemplo(agotadas = true),
                reglas = reglasDeEjemplo,
                precioTexto = ::precioDeEjemplo,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}
