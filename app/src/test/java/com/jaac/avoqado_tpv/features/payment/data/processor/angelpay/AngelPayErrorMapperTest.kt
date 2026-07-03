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
    fun `isAuthError matches only D308 session expiry`() {
        // Vendor-confirmed vs SDK 1.0.10 AppErrorCatalog (2026-07-03): session
        // expiry is exactly D308 (category DEVICE).
        assertTrue(AngelPayErrorMapper.isAuthError("D308"))

        // The old startsWith("C2") heuristic must stay dead — C2xx are CLIENT
        // config errors (amount/tip/MSI) where a re-auth can't help.
        assertFalse(AngelPayErrorMapper.isAuthError("C201"))
        assertFalse(AngelPayErrorMapper.isAuthError("C299"))
        assertFalse(AngelPayErrorMapper.isAuthError("C2"))

        // A0xx auth-service failures and other families never trigger payment re-auth.
        assertFalse(AngelPayErrorMapper.isAuthError("A007"))
        assertFalse(AngelPayErrorMapper.isAuthError("D306"))
        assertFalse(AngelPayErrorMapper.isAuthError("D307"))
        assertFalse(AngelPayErrorMapper.isAuthError("G500"))
        assertFalse(AngelPayErrorMapper.isAuthError("U100"))
        assertFalse(AngelPayErrorMapper.isAuthError(null))
        assertFalse(AngelPayErrorMapper.isAuthError(""))
    }

    @Test
    fun `isPreChargeRegisterFailure matches only the SDK terminal-registration message`() {
        // Exact string emitted by the SDK's PaymentActivity pre-charge register step
        // (byte-identical in the 1.0.10 and 1.0.13 AARs).
        assertTrue(
            AngelPayErrorMapper.isPreChargeRegisterFailure(
                "No fue posible registrar la terminal antes del cobro",
            ),
        )
        // Case-insensitive containment — survives minor casing/wrapping changes.
        assertTrue(
            AngelPayErrorMapper.isPreChargeRegisterFailure(
                "NO FUE POSIBLE REGISTRAR LA TERMINAL ANTES DEL COBRO.",
            ),
        )

        // A genuine mid-charge network error (same hardcoded N400 family) must NOT
        // trigger re-auth — the charge may have reached the gateway.
        assertFalse(AngelPayErrorMapper.isPreChargeRegisterFailure("Sin conexión a internet"))
        assertFalse(AngelPayErrorMapper.isPreChargeRegisterFailure("Pago rechazado"))
        assertFalse(AngelPayErrorMapper.isPreChargeRegisterFailure(null))
        assertFalse(AngelPayErrorMapper.isPreChargeRegisterFailure(""))
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
