package com.jaac.avoqado_tpv.features.checkout.presentation

import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.conNormalizador
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.reglasDeOrdenes

/**
 * Las reglas de familia de Cobro rápido: las mismas que Órdenes ([reglasDeOrdenes], el mismo [Product]) pero con LA búsqueda de esta
 * pantalla de hoy: `normalizeForSearch` (la de `CheckoutViewModel`: `trim`, sin acentos, minúsculas) sobre nombre y SKU, y sin
 * descripción (`textosExtra` vacío). Así «  cafe » encuentra «Café» y un acierto sólo en la descripción no cuenta.
 *
 * Una diferencia a propósito de las reglas de familia, que viene con ellas: el nombre de la FAMILIA también se busca (es lo que hace que
 * «playera» dé el botón de la familia). Y un texto que el normalizador deja en nada (sólo acentos sueltos) no busca nada.
 */
val reglasDeCobro: ReglasDeFamilia<Product> = reglasDeOrdenes.conNormalizador(
    normalizar = { it.normalizeForSearch() },
    textosExtra = { emptyList() },
)

/**
 * Lo que Cobro rápido hace cuando la cajera elige una variante en la hoja, en el MISMO orden que al tocar un producto suelto:
 * agotado ⇒ el aviso de no disponible (no se agrega); con extras ⇒ la hoja de extras de ESA variante; si no ⇒ agregar.
 *
 * [varianteActual] es la variante buscada POR `id` en el catálogo VIGENTE ([CheckoutViewModel.productoVigente]), no la copia con la que
 * se abrió la hoja. `null` (ya no está) ⇒ la hoja sólo se cierra.
 */
fun accionAlElegirEnCobro(varianteActual: Product?): AccionAlElegir<Product> = when {
    varianteActual == null -> AccionAlElegir.Cerrar
    !varianteActual.available -> AccionAlElegir.NoDisponible(varianteActual)
    varianteActual.hasModifiers -> AccionAlElegir.AbrirExtras(varianteActual)
    else -> AccionAlElegir.Agregar(varianteActual)
}

/**
 * Lo que pinta la búsqueda de Cobro rápido. Con texto: [ReglasDeFamilia.entradasParaMostrar] (un nombre da la fila de la familia una
 * vez; un SKU da la talla). Sin texto (o en blanco) NO se agrupa: hoy un texto en blanco lista todos los productos, cada uno en su
 * fila, y eso se conserva (`entradasParaMostrar` con texto en blanco devolvería el catálogo agrupado).
 */
fun entradasDeBusquedaEnCobro(
    productos: List<Product>,
    familias: Map<String, Familia<Product>>,
    texto: String,
): List<EntradaDeCatalogo<Product>> =
    if (texto.isBlank()) productos.map { EntradaDeCatalogo.Suelto(it) }
    else reglasDeCobro.entradasParaMostrar(productos, familias, texto)
