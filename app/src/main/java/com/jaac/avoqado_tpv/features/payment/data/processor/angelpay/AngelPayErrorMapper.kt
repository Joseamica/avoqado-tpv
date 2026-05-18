package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

/**
 * Maps AngelPay SDK 1.0.5 PaymentResult error codes to operator-facing Spanish messages.
 *
 * Used by [com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentViewModel]
 * when a payment fails or is declined, so the cashier sees a clear Spanish message instead of
 * the raw SDK error.
 *
 * Code categories per SDK 1.0.5 manual page 14 (AppErrorCatalog):
 *   - S000: SUCCESS (Approved)
 *   - G5xx: GATEWAY (Transaction rejected by the gateway — e.g., declined)
 *   - U100: USER (Operation cancelled by the user)
 *   - U101: USER (Timeout expired)
 *   - E6xx: EMV (chip read error)
 *   - C2xx: CLIENT (Invalid amount / tip rules / MSI rules — config issues; also heuristic
 *     for auth-category errors per spec Open Question #2 — vendor confirmation pending)
 *   - I999: INTERNAL (Unknown error)
 *   - NETWORK (no specific code — connection-related)
 *
 * Note: this codebase uses [CallResultData] (a local mirror of the SDK's CallResult, defined
 * in [AngelPayResultParser]) since the AngelPay AAR is `compileOnly` on PAX flavors. The
 * mapper takes [CallResultData] rather than the SDK type directly so it compiles on every
 * flavor and is trivially unit-testable without the AAR on the test classpath for this file.
 */
object AngelPayErrorMapper {

    /**
     * Builds the operator-facing decline/error message from the SDK's PaymentResult fields.
     *
     * Both `status` (e.g., "DECLINED", "CANCELLED", "TIMEOUT", "ERROR") and the structured
     * [CallResultData] (with `category` + `message` + `code`) are inputs. `code` here is the
     * top-level SDK transaction code (e.g., "00" = approved, "05" = generic decline) — distinct
     * from `callResult?.code` which is the AppErrorCatalog code (e.g., "G500", "U100").
     */
    fun toUserMessage(
        status: String?,
        code: String?,
        callResult: CallResultData?,
    ): String = when {
        status == "DECLINED" -> "Tarjeta declinada${code?.let { " ($it)" } ?: ""}"
        status == "CANCELLED" -> "Pago cancelado"
        status == "TIMEOUT" -> "Tiempo agotado, intenta de nuevo"
        callResult?.category == "GATEWAY" -> "Error del banco: ${callResult.message}"
        callResult?.category == "USER" -> callResult.message ?: "Operación interrumpida"
        callResult?.category == "EMV" -> "Error de chip, intenta nuevamente"
        callResult?.category == "NETWORK" -> "Sin red, reintentando..."
        callResult?.category == "CLIENT" -> "Configuración inválida: ${callResult.message}"
        else -> callResult?.message ?: "Error desconocido"
    }

    /**
     * Returns true if the SDK code indicates an authentication problem
     * (session expired, token rejected). Used by AngelPayPaymentViewModel
     * to trigger [AngelPayAuthRepository.handleAuthExpiry] + retry.
     *
     * Per spec §6.10 + Open Question #2: codes starting with "C2" are the
     * heuristic match for auth-category errors per the AngelPay SDK catalog.
     * Vendor confirmation pending — adjust if the real catalog disagrees.
     */
    fun isAuthError(code: String?): Boolean = code?.startsWith("C2") == true
}
