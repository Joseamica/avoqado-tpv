package com.jaac.avoqado_tpv.core.domain.variantes

/**
 * Lo que una pantalla hace cuando la cajera elige una variante en la hoja de la familia. Vive en `core` porque la usan Órdenes, Cobro
 * rápido, Kiosco y Mesas, y Mesas no puede importar nada de `features/ordering`. Cada pantalla usa los casos que le aplican; aquí no
 * hay más lógica que el tipo.
 */
sealed interface AccionAlElegir<out T> {
    /** Agrega la variante tal cual (no tiene extras que pedir). */
    data class Agregar<out T>(val variante: T) : AccionAlElegir<T>

    /** Abre la hoja de extras de ESA variante, nunca la de la familia ni la de otra talla. */
    data class AbrirExtras<out T>(val variante: T) : AccionAlElegir<T>

    /** La variante no se puede vender hoy (p. ej. agotada en Cobro rápido): la pantalla lo avisa como lo hace con un producto suelto. */
    data class NoDisponible<out T>(val variante: T) : AccionAlElegir<T>

    /** No hay nada que hacer: la hoja sólo se cierra. */
    data object Cerrar : AccionAlElegir<Nothing>
}
