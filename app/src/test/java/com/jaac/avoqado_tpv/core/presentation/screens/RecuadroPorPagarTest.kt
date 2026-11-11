package com.jaac.avoqado_tpv.core.presentation.screens

import com.jaac.avoqado_tpv.features.giftcards.data.SolicitudDeSaldo
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

    // region D12 — el cobro con saldo sin confirmar se ve aunque la terminal arranque sin red

    /** Una solicitud guardada como la guarda el repositorio: el cuerpo exacto del POST (con su monto) y el folio que ve la cajera. */
    private fun guardada(folio: String? = "W494E", monto: String = "20.00", orderId: String = "o1") = SolicitudDeSaldo(
        llave = "llave-$orderId",
        venueId = "v1",
        orderId = orderId,
        cuerpo = """{"idempotencyKey":"llave-$orderId","proof":{"kind":"CODE","code":"K7Q29DXM4R8HHB7TR"},"amount":$monto,"tip":0}""",
        creadaEn = 0L,
        folio = folio,
    )

    private val alDia = EstadoDeFolios(showTile = true, counts = ConteoDeFolios(1, 0), actualizadoEnMs = 1L)

    @Test fun `D12 arranque sin red con un saldo guardado - no hay recuadro pero si el aviso con folio y monto`() {
        val arranqueSinRed = EstadoDeFolios(sinConexion = true)
        assertNull(recuadro(arranqueSinRed))
        assertEquals(
            "Cobro con saldo sin confirmar en el folio W494E (\$20.00). No lo cobres otra vez: se comprueba en «Pedidos por pagar» " +
                "cuando haya conexión.",
            avisoDeSaldoSinConfirmar(arranqueSinRed, listOf(guardada())),
        )
    }

    @Test fun `D12 antes de la primera respuesta del servidor tambien avisa`() {
        assertEquals(
            "Cobro con saldo sin confirmar en el folio W494E (\$20.00). No lo cobres otra vez: se comprueba en «Pedidos por pagar» " +
                "cuando haya conexión.",
            avisoDeSaldoSinConfirmar(EstadoDeFolios(), listOf(guardada())),
        )
    }

    @Test fun `D12 con la red caida despues de tener lista tambien avisa`() {
        assertTrue(avisoDeSaldoSinConfirmar(alDia.copy(sinConexion = true), listOf(guardada())) != null)
    }

    @Test fun `D12 tras un 4xx la lista no es fiable y avisa`() {
        assertTrue(avisoDeSaldoSinConfirmar(alDia.copy(desactualizado = true), listOf(guardada())) != null)
    }

    @Test fun `D12 con la lista al dia no avisa - si el folio viene la marca esta en la lista y si ya no viene no se puede cobrar`() {
        assertNull(avisoDeSaldoSinConfirmar(alDia, listOf(guardada())))
    }

    @Test fun `D12 si el almacen no se pudo leer lo dice - nunca como si no hubiera nada`() {
        assertEquals(SALDO_GUARDADO_ILEGIBLE, avisoDeSaldoSinConfirmar(EstadoDeFolios(sinConexion = true), null))
        assertEquals(SALDO_GUARDADO_ILEGIBLE, avisoDeSaldoSinConfirmar(EstadoDeFolios(), null))
    }

    @Test fun `D12 sin cobro con saldo guardado no avisa`() {
        assertNull(avisoDeSaldoSinConfirmar(EstadoDeFolios(sinConexion = true), emptyList()))
    }

    @Test fun `D12 una guardada antes de este cambio (sin folio) avisa con el monto`() {
        assertEquals(
            "Cobro con saldo sin confirmar en un folio de la tienda (\$134.50). No lo cobres otra vez: se comprueba en «Pedidos por pagar» " +
                "cuando haya conexión.",
            avisoDeSaldoSinConfirmar(EstadoDeFolios(sinConexion = true), listOf(guardada(folio = null, monto = "134.50"))),
        )
    }

    @Test fun `D12 varias se nombran todas`() {
        assertEquals(
            "Cobros con saldo sin confirmar en el folio W494E (\$20.00) y el folio WYB8X (\$35.00). No los cobres otra vez: se comprueban " +
                "en «Pedidos por pagar» cuando haya conexión.",
            avisoDeSaldoSinConfirmar(
                EstadoDeFolios(sinConexion = true),
                listOf(guardada(), guardada(folio = "WYB8X", monto = "35.00", orderId = "o2")),
            ),
        )
    }

    // endregion
}
