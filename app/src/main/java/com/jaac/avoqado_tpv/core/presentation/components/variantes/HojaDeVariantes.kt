package com.jaac.avoqado_tpv.core.presentation.components.variantes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.TextosVariantes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

/** El «deshabilitado» de Material 3: el chip se ve apagado pero sigue tocable (Q3 de las reglas de familia). */
private const val ALFA_SIN_COMBINACION = 0.38f

/**
 * Lo que la hoja de variantes decide, sin Compose (para probarlo). Una instancia por APERTURA de la hoja.
 *
 * - [seleccion]: nombre del eje → valor elegido; arranca con [ReglasDeFamilia.seleccionInicial] (Q2) y [elegir] aplica Q3.
 * - [entregar]: devuelve la variante elegida UNA sola vez por apertura (`compareAndSet`, como Android): dos toques seguidos en
 *   «Agregar» suman una unidad, no dos. Sin elección completa devuelve `null` y NO gasta la entrega.
 * - [actualizar]: el catálogo se refrescó con la hoja abierta y la [Familia] cambió. La elección se CONSERVA en lo que sigue
 *   existiendo (el eje y el valor siguen en la familia nueva y todavía combinan con lo ya conservado, mismo recorrido codicioso que
 *   [ReglasDeFamilia.elegir]); lo demás se suelta y los ejes que quedaron con un solo valor se eligen solos (Q2). Nunca truena. La
 *   entrega ya hecha no se reinicia.
 *
 * Lo guarda `remember(groupId)`: otra familia, o cerrar y volver a abrir, es otra apertura (estado y entrega nuevos).
 * Quien la use debe pasar el MISMO `reglas` mientras la hoja esté abierta (las reglas son sólo accesores del modelo).
 */
class EstadoDeHoja<T>(familia: Familia<T>, private val reglas: ReglasDeFamilia<T>) {
    var familia: Familia<T> by mutableStateOf(familia)
        private set
    var seleccion: Map<String, String> by mutableStateOf(reglas.seleccionInicial(familia))
        private set
    private val entregada = AtomicBoolean(false)

    /** La variante de la elección COMPLETA; `null` si falta un eje o esa combinación no existe. */
    val elegida: T? get() = reglas.varianteElegida(familia, seleccion)

    fun elegir(eje: String, valor: String) {
        seleccion = reglas.elegir(familia, seleccion, eje, valor)
    }

    fun entregar(): T? = elegida?.takeIf { entregada.compareAndSet(false, true) }

    fun actualizar(nueva: Familia<T>) {
        // `==` (no `===`): una familia igual en contenido no escribe nada, así que quien la reconstruya sin cambios no provoca recomposiciones.
        if (nueva == familia) return
        seleccion = conservar(nueva, seleccion)
        familia = nueva
    }

    private fun conservar(f: Familia<T>, previa: Map<String, String>): Map<String, String> {
        val conservada = reglas.seleccionInicial(f).toMutableMap()
        for (e in f.ejes) {
            val valor = previa[e.nombre] ?: continue
            if (valor in e.valores && reglas.valorDisponible(f, conservada, e.nombre, valor)) conservada[e.nombre] = valor
        }
        return conservada
    }
}

/**
 * La hoja para elegir la variante de una familia (spec 2026-10-14 §11): un grupo de chips por eje, el precio de la elegida con su
 * aviso, y un botón que dice por qué está apagado. Pantalla completa sin animación (como `ProductSelectorBottomSheet`: la terminal
 * tiene 1 GB de RAM). Espejo de `HojaDeVariantes.kt` de avoqado-android, con DOS diferencias a propósito:
 * - no usa `AGOTADO_AVISO` («se puede vender igual»): la terminal conserva lo que hace hoy cada pantalla con lo agotado, así que
 *   el aviso por variante lo decide quien la llama ([aviso]; `null` = no decir nada);
 * - el precio llega ya formateado por quien la llama ([precioTexto]): cada modelo de producto lo formatea a su manera.
 *
 * Elegir no escribe nada: sólo [onElegir], UNA vez por apertura. Quien la abre guarda el `groupId` (`familiaAbierta: String?`),
 * le pasa `familias[familiaAbierta]` en cada recomposición (el mapa guardado por cambio de catálogo, no uno recalculado con productos
 * nuevos en cada recomposición), cierra la hoja si eso es `null`, y resuelve la variante recibida por `id`
 * contra su catálogo vigente antes de seguir.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> HojaDeVariantes(
    familia: Familia<T>,
    reglas: ReglasDeFamilia<T>,
    precioTexto: (T) -> String,
    aviso: (T) -> String?,
    onElegir: (T) -> Unit,
    onCerrar: () -> Unit,
) {
    val estado = remember(familia.groupId) { EstadoDeHoja(familia, reglas) }
    // El catálogo se puede refrescar con la hoja abierta: la elección se concilia ANTES de leerla (misma idea que rememberUpdatedState).
    estado.actualizar(familia)
    val seleccion = estado.seleccion
    val elegida = estado.elegida

    BackHandler(enabled = true) { onCerrar() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCerrar) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = TextosVariantes.CERRAR,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = familia.nombre,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(48.dp))   // el mismo ancho que el botón de cerrar, para que el nombre quede al centro
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(familia.ejes, key = { it.nombre }) { eje ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = eje.nombre,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        // Cada chip mide lo que su texto y el renglón se parte: «Azul marino» y «Azul cielo» nunca se cortan.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            eje.valores.forEach { valor ->
                                val disponible = reglas.valorDisponible(familia, seleccion, eje.nombre, valor)
                                FilterChip(
                                    selected = seleccion[eje.nombre] == valor,
                                    onClick = { estado.elegir(eje.nombre, valor) },
                                    label = { Text(valor) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = if (disponible) Modifier else Modifier
                                        .alpha(ALFA_SIN_COMBINACION)
                                        .semantics { contentDescription = "$valor, ${TextosVariantes.SIN_COMBINACION}" },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (elegida != null) {
                    Text(
                        text = precioTexto(elegida),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    aviso(elegida)?.let { texto ->
                        Text(
                            text = texto,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reglas.agotado(elegida)) MaterialTheme.avoqadoColors.statusWarning
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    // Dos toques seguidos entregan una vez (EstadoDeHoja.entregar); deshabilitar el botón no cubre eventos ya en cola.
                    onClick = { estado.entregar()?.let(onElegir) },
                    enabled = elegida != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                ) {
                    // Con la variante, su precio; sin ella, sin un precio inventado.
                    Text(
                        text = elegida?.let { TextosVariantes.agregarCon(precioTexto(it)) } ?: TextosVariantes.AGREGAR,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (elegida == null) {
                    // Nunca un botón muerto sin explicación.
                    Text(
                        text = TextosVariantes.FALTA_ELEGIR,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ============================================================================
// Previews (los datos de ejemplo también los usa el de TileDeFamilia)
// ============================================================================

internal data class VarianteDeEjemplo(
    val id: String,
    val talla: String,
    val color: String,
    val precio: BigDecimal,
    val agotado: Boolean = false,
)

private val TALLAS_DE_EJEMPLO = listOf("S", "M", "L")
private val COLORES_DE_EJEMPLO = listOf("Azul", "Negro", "Azul marino")

internal val reglasDeEjemplo = ReglasDeFamilia<VarianteDeEjemplo>(
    datos = {
        DatosDeFamilia(
            groupId = "playera",
            groupName = "Playera básica",
            groupImageUrl = null,
            opciones = listOf(
                OpcionDeVariante("Talla", it.talla, 0, TALLAS_DE_EJEMPLO.indexOf(it.talla)),
                OpcionDeVariante("Color", it.color, 1, COLORES_DE_EJEMPLO.indexOf(it.color)),
            ),
        )
    },
    imagen = { null },
    precio = { it.precio },
    agotado = { it.agotado },
    nombre = { "Playera ${it.talla} ${it.color}" },
    codigos = { emptyList() },
)

internal fun familiaDeEjemplo(agotadas: Boolean = false): Familia<VarianteDeEjemplo> = reglasDeEjemplo.familiasDe(
    listOf(
        VarianteDeEjemplo("s-azul", "S", "Azul", BigDecimal("199.00"), agotadas),
        VarianteDeEjemplo("m-azul", "M", "Azul", BigDecimal("199.00"), agotadas),
        VarianteDeEjemplo("m-negro", "M", "Negro", BigDecimal("249.00"), agotadas),
        VarianteDeEjemplo("l-marino", "L", "Azul marino", BigDecimal("249.00"), agotadas),
    ),
).getValue("playera")

internal fun precioDeEjemplo(precio: BigDecimal) = "$" + precio.setScale(2)

@Preview(name = "Hoja con 2 ejes", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HojaDeVariantesPreview() {
    AvoqadoTheme {
        HojaDeVariantes(
            familia = familiaDeEjemplo(),
            reglas = reglasDeEjemplo,
            precioTexto = { precioDeEjemplo(it.precio) },
            aviso = { null },
            onElegir = {},
            onCerrar = {},
        )
    }
}
