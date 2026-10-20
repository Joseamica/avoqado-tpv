package com.jaac.avoqado_tpv.features.tables.presentation

import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.SIN_FAMILIA
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuProduct

// Variantes de producto en Mesas. 🔴 Regla dura de Mesas: nada de `features/ordering` (lo compartido vive en `core/`); una prueba lo vigila.

/**
 * Cómo las reglas de familia leen un [MenuProduct] en Mesas. Mesas no conoce existencias (su catálogo no trae stock), así que nada
 * sale «Agotado» y la hoja de tallas nunca avisa de eso. Tampoco tiene búsqueda ni escáner: no hay códigos que comparar.
 */
val reglasDeMesas = ReglasDeFamilia<MenuProduct>(
    datos = { it.familia ?: SIN_FAMILIA },
    imagen = { it.imageUrl },
    precio = { it.price },
    agotado = { false },
    nombre = { it.name },
    codigos = { emptyList() },
)

/**
 * Lo que Mesas hace cuando el mesero elige una variante en la hoja, igual que con un producto suelto de hoy: con extras ⇒
 * `ModifierPickerSheet` de ESA variante; si no ⇒ `addSimple` (que escribe en `PendingRoundCart`). [varianteActual] es la variante
 * buscada POR `id` en el catálogo VIGENTE ([TableMenuViewModel.productoVigente]), no la copia con la que se abrió la hoja; `null` (ya no
 * está) ⇒ la hoja sólo se cierra. Mesas no tiene diálogo de «no disponible», así que nunca devuelve `NoDisponible`.
 */
fun accionAlElegirEnMesas(varianteActual: MenuProduct?): AccionAlElegir<MenuProduct> = when {
    varianteActual == null -> AccionAlElegir.Cerrar
    varianteActual.hasModifiers -> AccionAlElegir.AbrirExtras(varianteActual)
    else -> AccionAlElegir.Agregar(varianteActual)
}

/** Llave de una entrada en la cuadrícula: con prefijo, para que un producto y una familia nunca choquen. */
fun claveDeEntradaDeMesas(entrada: EntradaDeCatalogo<MenuProduct>): String = when (entrada) {
    is EntradaDeCatalogo.Suelto -> "prod_${entrada.producto.id}"
    is EntradaDeCatalogo.DeFamilia -> "familia_${entrada.familia.groupId}"
}

/** La cuenta que lleva el botón de una familia: lo que ya hay en la ronda de TODAS sus tallas (como un producto suelto lleva la suya). */
fun cantidadEnLaRondaDeFamilia(familia: Familia<MenuProduct>, cantidades: Map<String, Int>): Int =
    familia.variantes.sumOf { cantidades[it.id] ?: 0 }
