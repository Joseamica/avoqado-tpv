package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AngelPayErrorMapper] — spec §6.10.
 *
 * Covers branch coverage of the operator-message mapper + auth-error heuristic. Uses local
 * [CallResultData] (the codebase's mirror of SDK `CallResult`) so the test compiles without
 * needing the AngelPay AAR on the test classpath for this file.
 */
class AngelPayErrorMapperTest {

    @Test
    fun `toUserMessage maps known SDK statuses`() {
        // DECLINED with code
        assertEquals(
            "Tarjeta declinada (05)",
            AngelPayErrorMapper.toUserMessage("DECLINED", "05", null),
        )
        // DECLINED without code
        assertEquals(
            "Tarjeta declinada",
            AngelPayErrorMapper.toUserMessage("DECLINED", null, null),
        )
        // CANCELLED
        assertEquals(
            "Pago cancelado",
            AngelPayErrorMapper.toUserMessage("CANCELLED", null, null),
        )
        // TIMEOUT
        assertEquals(
            "Tiempo agotado, intenta de nuevo",
            AngelPayErrorMapper.toUserMessage("TIMEOUT", null, null),
        )
    }

    @Test
    fun `toUserMessage maps callResult categories when status is not standard`() {
        val gateway = makeCallResult("G500", "GATEWAY", "Tarjeta rechazada por banco")
        assertEquals(
            "Error del banco: Tarjeta rechazada por banco",
            AngelPayErrorMapper.toUserMessage("ERROR", null, gateway),
        )

        val user = makeCallResult("U100", "USER", "Cancelado por el usuario")
        assertEquals(
            "Cancelado por el usuario",
            AngelPayErrorMapper.toUserMessage("ERROR", null, user),
        )

        // USER category with null message — falls back to default text
        val userNullMsg = makeCallResult("U100", "USER", null)
        assertEquals(
            "Operación interrumpida",
            AngelPayErrorMapper.toUserMessage("ERROR", null, userNullMsg),
        )

        val emv = makeCallResult("E699", "EMV", "EMV failure")
        assertEquals(
            "Error de chip, intenta nuevamente",
            AngelPayErrorMapper.toUserMessage("ERROR", null, emv),
        )

        val net = makeCallResult("N100", "NETWORK", "No connection")
        assertEquals(
            "Sin red, reintentando...",
            AngelPayErrorMapper.toUserMessage("ERROR", null, net),
        )

        val cli = makeCallResult("C200", "CLIENT", "MSI minimum amount not met")
        assertEquals(
            "Configuración inválida: MSI minimum amount not met",
            AngelPayErrorMapper.toUserMessage("ERROR", null, cli),
        )
    }

    @Test
    fun `toUserMessage falls back to callResult message or generic`() {
        // Unknown category — falls back to callResult.message
        val unknown = makeCallResult("X000", "UNKNOWN", "Some odd error")
        assertEquals(
            "Some odd error",
            AngelPayErrorMapper.toUserMessage("ERROR", null, unknown),
        )

        // No status, no callResult — generic
        assertEquals(
            "Error desconocido",
            AngelPayErrorMapper.toUserMessage(null, null, null),
        )

        // CallResult with null category AND null message — generic
        val empty = makeCallResult(null, null, null)
        assertEquals(
            "Error desconocido",
            AngelPayErrorMapper.toUserMessage("ERROR", null, empty),
        )
    }

    @Test
    fun `isAuthError returns true only for C2xx prefix`() {
        assertTrue(AngelPayErrorMapper.isAuthError("C201"))
        assertTrue(AngelPayErrorMapper.isAuthError("C299"))
        assertTrue(AngelPayErrorMapper.isAuthError("C2"))
        assertFalse(AngelPayErrorMapper.isAuthError("C100"))
        assertFalse(AngelPayErrorMapper.isAuthError("G500"))
        assertFalse(AngelPayErrorMapper.isAuthError("U100"))
        assertFalse(AngelPayErrorMapper.isAuthError(null))
        assertFalse(AngelPayErrorMapper.isAuthError(""))
    }

    private fun makeCallResult(
        code: String?,
        category: String?,
        message: String?,
    ): CallResultData = CallResultData(
        code = code,
        status = null,
        category = category,
        message = message,
    )
}
