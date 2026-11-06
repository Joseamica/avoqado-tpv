package com.jaac.avoqado_tpv.core.presentation.screens

import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecuadroPorPagarTest {
    private fun estado(
        porPagar: Int = 3,
        porEntregar: Int = 1,
        sinConexion: Boolean = false,
        desactualizado: Boolean = false,
    ) = EstadoDeFolios(
        showTile = true,
        counts = ConteoDeFolios(porPagar = porPagar, porEntregar = porEntregar),
        sinConexion = sinConexion,
        desactualizado = desactualizado,
    )

    private fun recuadro(e: EstadoDeFolios, canOperate: Boolean = true, canWork: Boolean = true, motivo: String? = "Abre la caja primero") =
        recuadroPorPagar(e, canOperate, canWork, motivo)

    @Test fun `sin showTile no hay recuadro`() {
        assertNull(recuadro(EstadoDeFolios(showTile = false)))
    }

    @Test fun `con turno y entrada el numero va en la insignia y los por entregar debajo`() {
        val r = recuadro(estado())!!
        assertTrue(r.habilitado)
        assertEquals("3", r.badge)
        assertEquals("1 por entregar", r.subtitulo)
        assertFalse(r.subtituloEsAviso)
    }

    @Test fun `con 0 por pagar no hay insignia y se queda lo de por entregar`() {
        val r = recuadro(estado(porPagar = 0, porEntregar = 2))!!
        assertTrue(r.habilitado)
        assertNull(r.badge)
        assertEquals("2 por entregar", r.subtitulo)
    }

    @Test fun `sin nada por entregar no hay renglon de abajo`() {
        assertNull(recuadro(estado(porEntregar = 0))!!.subtitulo)
    }

    @Test fun `sin turno queda deshabilitado con Abre la caja primero`() {
        val r = recuadro(estado(), canOperate = false)!!
        assertFalse(r.habilitado)
        assertEquals("Abre la caja primero", r.badge)
        assertNull(r.subtitulo)
    }

    @Test fun `sin entrada registrada el motivo es registrar la entrada`() {
        val r = recuadro(estado(), canWork = false)!!
        assertFalse(r.habilitado)
        assertEquals("Registra tu entrada", r.badge)
    }

    @Test fun `sin conexion conserva el ultimo numero y avisa abajo en naranja`() {
        val r = recuadro(estado(sinConexion = true))!!
        assertTrue(r.habilitado)
        assertEquals("3", r.badge)
        assertEquals("Sin conexión", r.subtitulo)
        assertTrue(r.subtituloEsAviso)
    }

    @Test fun `desactualizado no muestra un numero viejo como si fuera nuevo`() {
        val r = recuadro(estado(desactualizado = true))!!
        assertNull(r.badge)
        assertEquals("Sin actualizar", r.subtitulo)
        assertTrue(r.subtituloEsAviso)
    }

    @Test fun `desactualizado y sin conexion dice Sin conexion y no enseña numero`() {
        val r = recuadro(estado(desactualizado = true, sinConexion = true))!!
        assertNull(r.badge)
        assertEquals("Sin conexión", r.subtitulo)
        assertTrue(r.subtituloEsAviso)
    }
}
