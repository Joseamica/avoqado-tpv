package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.SIN_FAMILIA
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import java.math.BigDecimal

/**
 * Cómo las reglas de familia leen un [Product] en Órdenes. La búsqueda de Órdenes de hoy es `contains` sin importar mayúsculas sobre
 * nombre, descripción y SKU (`MenuViewModel` la tenía así), y eso es justo lo que da el `normalizar` por defecto con `textosExtra` =
 * descripción. Un nombre de familia o una talla encontrada por nombre/descripción da la fila de la familia; un SKU da la talla.
 *
 * Cobro rápido usa el mismo modelo con SU búsqueda (sin acentos): `reglasDeOrdenes.conNormalizador { … }`.
 */
val reglasDeOrdenes = ReglasDeFamilia<Product>(
    datos = { it.familia ?: SIN_FAMILIA },
    imagen = { it.imageUrl },
    precio = { it.price },
    agotado = { !it.available },
    nombre = { it.name },
    codigos = { listOf(it.sku) },
    textosExtra = { listOf(it.description) },
)

/** Las mismas reglas con otro [normalizar] (p. ej. quitar acentos): `ReglasDeFamilia` no tiene `copy` y sus accesores no cambian. */
fun <T> ReglasDeFamilia<T>.conNormalizador(normalizar: (String) -> String): ReglasDeFamilia<T> = ReglasDeFamilia(
    datos = datos,
    imagen = imagen,
    precio = precio,
    agotado = agotado,
    nombre = nombre,
    codigos = codigos,
    normalizar = normalizar,
    textosExtra = textosExtra,
)

/** El precio como lo pinta el resto de la terminal (`CurrencyFormatter`, es-MX), para el botón de la familia y su hoja. */
val formatoDePrecio: (BigDecimal) -> String = { CurrencyFormatter.format(it) }

/**
 * Lo que Órdenes hace cuando la cajera elige una variante en la hoja. [varianteActual] es esa variante buscada POR `id` en el catálogo
 * VIGENTE (`MenuViewModel.productoVigente`), no la copia con la que se abrió la hoja: si el catálogo se refrescó con la hoja abierta,
 * los extras que cuentan son los nuevos. `null` (ya no está o ya no se puede vender) ⇒ la hoja sólo se cierra.
 */
fun accionAlElegir(varianteActual: Product?): AccionAlElegir<Product> = when {
    varianteActual == null -> AccionAlElegir.Cerrar
    varianteActual.hasModifiers -> AccionAlElegir.AbrirExtras(varianteActual)
    else -> AccionAlElegir.Agregar(varianteActual)
}

/**
 * ¿Puede estar abierta la hoja de tallas? No mientras se prepara el pago («Preparando pago…» sincroniza la orden y trae los descuentos
 * recalculados: un artículo que entre en ese momento saldría fuera del cobro) ni con el escáner de códigos abierto (que es una
 * pantalla completa propia). `MenuScreen` ni la pinta ni la deja abierta cuando esto da `false`.
 */
fun hojaDeVariantesPermitida(preparandoPago: Boolean, escanerAbierto: Boolean): Boolean = !preparandoPago && !escanerAbierto
