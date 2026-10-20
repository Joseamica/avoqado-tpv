package com.jaac.avoqado_tpv.features.kiosk.presentation

import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.SIN_FAMILIA
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskState

/**
 * Cómo las reglas de familia leen un [KioskProduct] en el kiosco. La búsqueda es la de hoy de `KioskSearchDialog`:
 * `name.contains(texto, ignoreCase = true)` — sólo el nombre (el modelo no trae SKU ni descripción), sin `trim`, sin quitar acentos.
 *
 * [normalizar] reproduce `ignoreCase = true` carácter por carácter: dos letras son iguales si sus mayúsculas o las minúsculas de sus
 * mayúsculas coinciden (`Char.equals(ignoreCase)` de Kotlin), así que llevarlas a esa forma y comparar con `contains` da lo mismo que hoy,
 * hasta con letras raras donde `lowercase()` difiere. Como la búsqueda de hoy, es una cadena del mismo largo que el texto.
 *
 * Una diferencia a propósito, que viene con las reglas de familia: el nombre de la FAMILIA también se busca (es lo que hace que
 * «playera» dé el botón de la familia). Y un texto en blanco no busca nada: ver [entradasDeBusquedaEnKiosco].
 */
val reglasDeKiosco = ReglasDeFamilia<KioskProduct>(
    datos = { it.familia ?: SIN_FAMILIA },
    imagen = { it.imageUrl },
    precio = { it.price },
    agotado = { !it.isAvailable },
    nombre = { it.name },
    codigos = { emptyList() },   // el kiosco no busca ni escanea por código: KioskProduct no tiene SKU
    normalizar = { texto -> String(CharArray(texto.length) { texto[it].uppercaseChar().lowercaseChar() }) },
)

/**
 * Las familias de lo que el kiosco MUESTRA: sólo lo disponible (`KioskState.filteredProducts` y `allProducts` esconden lo demás), de modo
 * que una talla agotada nunca entra a la hoja. Se calcula sobre TODAS las categorías: la categoría elegida sólo decide cuál variante
 * coloca el botón en la lista. Si queda una sola talla disponible, ya no es familia y sale suelta.
 */
fun familiasDeKiosco(productos: List<KioskProduct>): Map<String, Familia<KioskProduct>> =
    reglasDeKiosco.familiasDe(productos.filter { it.isAvailable })

/**
 * Lo que el kiosco hace cuando el cliente elige una variante en la hoja: con extras ⇒ `KioskModifierDialog` de ESA variante (porque
 * `addToCart` rechaza lo que tiene extras); si no ⇒ agregarla. [varianteActual] es la variante buscada POR `id` en el catálogo VIGENTE
 * ([KioskViewModel.productoVigente]), no la copia con la que se abrió la hoja.
 *
 * `null` (ya no está) o no disponible ⇒ la hoja sólo se cierra: el kiosco no vende lo agotado ni tiene un aviso de «no disponible»
 * (hoy simplemente no lo muestra), y no se inventa uno.
 */
fun accionAlElegirEnKiosco(varianteActual: KioskProduct?): AccionAlElegir<KioskProduct> = when {
    varianteActual == null -> AccionAlElegir.Cerrar
    !varianteActual.isAvailable -> AccionAlElegir.Cerrar
    varianteActual.hasModifiers -> AccionAlElegir.AbrirExtras(varianteActual)
    else -> AccionAlElegir.Agregar(varianteActual)
}

/**
 * Lo que la búsqueda del kiosco muestra: un texto en blanco no busca nada (hoy muestra «Escribe para buscar»; por eso NO se llama a
 * `entradasParaMostrar`, que con el texto en blanco enseña el catálogo agrupado); con texto, una familia sale como familia (una vez) y
 * un producto suelto como producto.
 */
fun entradasDeBusquedaEnKiosco(
    productos: List<KioskProduct>,
    familias: Map<String, Familia<KioskProduct>>,
    texto: String,
): List<EntradaDeCatalogo<KioskProduct>> =
    if (texto.isBlank()) emptyList() else reglasDeKiosco.entradasParaMostrar(productos, familias, texto)

/** Llave de una entrada en las cuadrículas (menú y búsqueda): con prefijo, para que un producto y una familia nunca choquen. */
fun claveDeEntradaDeKiosco(entrada: EntradaDeCatalogo<KioskProduct>): String = when (entrada) {
    is EntradaDeCatalogo.Suelto -> "prod_${entrada.producto.id}"
    is EntradaDeCatalogo.DeFamilia -> "familia_${entrada.familia.groupId}"
}

/**
 * ¿Puede estar abierta la hoja de tallas? No mientras el aviso de pantalla completa del sistema de pagos está puesto (preparando,
 * reintentando o con error): ese aviso bloquea el menú y la hoja no se queda viva debajo. El pago en sí no se hace en esta pantalla
 * (la orden se crea en `KioskCartScreen`, otra ruta), así que éste es el único freno que el menú tiene. `KioskMenuScreen` ni la pinta ni
 * la deja abierta cuando esto da `false`.
 */
fun hojaDeVariantesPermitidaEnKiosco(estado: KioskState): Boolean = !estado.avisoDePagosVisible
