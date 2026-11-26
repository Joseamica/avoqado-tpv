package com.avoqado.motorlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloque P0: por qué vía se lee la serie del aparato, sin enseñar más de los 4 últimos. Sin aparato y con series inventadas. */
class SerieDelAparatoTest {
    private val coincide = lineaDeLaSerie(listOf("DAL" to { "AB12345678" }), " ab12345678 ")

    @Test fun `una via legible que coincide`() {
        assertEquals("Serie del aparato: DAL …5678 (identidad: coincide)", coincide)
    }

    @Test fun `una via que lanza no tumba la medicion`() {
        val l = lineaDeLaSerie(listOf("DAL" to { throw SecurityException() }, "Build" to { "AB12345678" }), "OTRA0000")
        assertTrue(l.startsWith("Serie del aparato: "))
        assertTrue(l.contains("DAL ilegible (SecurityException)"))
        assertTrue(l.contains("Build …5678 (identidad: NO coincide)"))
    }

    @Test fun `vacia y sin identidad`() {
        val l = lineaDeLaSerie(listOf("DAL" to { "  " }, "Build" to { "XY99" }), null)
        assertEquals("Serie del aparato: DAL ilegible (vacía) · Build …XY99 (identidad: sin identidad)", l)
    }

    @Test fun `nunca ensena mas de cuatro`() {
        assertFalse(coincide.contains("AB1234"))
        val corta = lineaDeLaSerie(listOf("DAL" to { "AB1" }), null)
        assertEquals("Serie del aparato: DAL …? (identidad: sin identidad)", corta)
    }

    @Test fun `un Error tambien da ilegible`() {
        val l = lineaDeLaSerie(listOf("DAL" to { throw UnsatisfiedLinkError() }), null)
        assertTrue(l, l.contains("DAL ilegible (UnsatisfiedLinkError)"))
    }

    /** Una clase anónima no tiene nombre corto: sale `?`, nunca un paréntesis vacío. */
    @Test fun `una excepcion sin nombre sale como signo`() {
        val l = lineaDeLaSerie(listOf("DAL" to { throw object : Exception() {} }), null)
        assertEquals("Serie del aparato: DAL ilegible (?)", l)
    }

    /** Un DAL colgado no detiene la medición: su vía sale por plazo y la línea llega mucho antes de que regrese. */
    @Test fun `una via que no regresa sale por plazo`() {
        val desde = System.nanoTime()
        val l = lineaDeLaSerie(listOf("DAL" to conPlazo(200) { Thread.sleep(5_000); "AB12345678" }), null)
        val ms = (System.nanoTime() - desde) / 1_000_000
        assertTrue(l, l.contains("DAL ilegible (SinRespuesta)"))
        assertTrue("$ms ms", ms < 2_000)
    }

    @Test fun `conPlazo devuelve el valor y relanza lo de adentro`() {
        assertEquals("AB12345678", conPlazo(1_000) { "AB12345678" }())
        val adentro = IllegalStateException()
        assertSame(adentro, runCatching { conPlazo(1_000) { throw adentro }() }.exceptionOrNull())
    }
}
