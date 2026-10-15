package com.jaac.avoqado_tpv.features.ordering.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D5 (Mesas40, 15-oct-2026): la nota de un artículo no tenía tope (se midió una de 10,038 caracteres). La TPV la corta
 * en 200 caracteres COMPLETOS —puntos de código, igual que el servidor con `Array.from(s).slice(0, 500)`—: un emoji
 * nunca queda partido a la mitad.
 */
class NotaDeArticuloTest {

    @Test
    fun `una nota de 200 caracteres o menos no cambia`() {
        val nota = "Sin cebolla, ".repeat(15).take(200)
        assertEquals(nota, limitarNotaDeArticulo(nota))
        assertEquals("", limitarNotaDeArticulo(""))
    }

    @Test
    fun `una nota de 10038 caracteres se corta en 200`() {
        assertEquals("a".repeat(200), limitarNotaDeArticulo("a".repeat(10_038)))
    }

    @Test
    fun `el corte no parte un emoji a la mitad`() {
        val nota = "a".repeat(199) + "😀😀"
        val cortada = limitarNotaDeArticulo(nota)
        assertEquals("a".repeat(199) + "😀", cortada)
        assertEquals(200, cortada.codePointCount(0, cortada.length))
    }
}
