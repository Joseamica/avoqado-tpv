package com.jaac.avoqado_campo.identidad

import org.junit.Assert.assertEquals
import org.junit.Test

class RelojAncladoTest {
    private var celular = 1_577_836_800_000L // 2020-01-01
    private var transcurrido = 1_000L
    private var arranque = 7
    private val almacen = AlmacenEnMemoria()

    private fun reloj() = RelojAnclado(almacen, { celular }, { transcurrido }, { arranque })

    @Test fun `anclado avanza con el transcurrido aunque el celular diga 2020`() {
        val r = reloj()
        r.anclar("2026-11-10T15:00:00Z")
        transcurrido = 61_000
        val h = r.ahora()
        assertEquals("2026-11-10T15:01:00Z", h.iso)
        assertEquals("RELOJ_ANCLADO", h.origen)
    }

    @Test fun `otro arranque usa la hora del celular`() {
        val r = reloj()
        r.anclar("2026-11-10T15:00:00Z")
        arranque = 8
        val h = r.ahora()
        assertEquals("RELOJ_DEL_CELULAR", h.origen)
        assertEquals(celular, h.epochMs)
    }

    @Test fun `sin ancla usa la hora del celular`() {
        assertEquals("RELOJ_DEL_CELULAR", reloj().ahora().origen)
    }

    @Test fun `transcurrido menor que el anclado usa la hora del celular`() {
        val r = reloj()
        r.anclar("2026-11-10T15:00:00Z")
        transcurrido = 500
        assertEquals("RELOJ_DEL_CELULAR", r.ahora().origen)
    }

    @Test fun `el ancla sobrevive en el almacen`() {
        reloj().anclar("2026-11-10T15:00:00Z")
        transcurrido = 2_000
        assertEquals("2026-11-10T15:00:01Z", reloj().ahora().iso)
    }
}
