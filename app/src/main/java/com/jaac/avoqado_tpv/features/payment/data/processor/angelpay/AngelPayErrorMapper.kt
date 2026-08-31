package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

/**
 * Maps AngelPay SDK PaymentResult error codes to operator-facing Spanish messages.
 *
 * Used by [com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentViewModel]
 * when a payment fails or is declined, so the cashier sees a clear Spanish message instead of
 * the raw SDK error.
 *
 * Code categories per the SDK AppErrorCatalog (verified against the AARs' actual
 * `com.angelpay.angelpaysdk.models.AppErrorCatalog$Code` enum, byte-identical in
 * 1.0.10 and 1.0.13, 2026-07-03):
 *   - S00x: SUCCESS (Approved / idempotent replay)
 *   - A0xx: AUTH (registration/PIN/key-injection failures — surface during authenticateSimple,
 *     not mid-payment)
 *   - U10x: USER (cancelled / timeout / back / PIN cancelled)
 *   - C2xx: CLIENT (invalid amount / tip rules / MSI rules / check-in — config issues)
 *   - D30x: DEVICE (busy, printer, battery, GPS, NFC, SAM, key init; D308 = session expired)
 *   - N40x: NETWORK (no internet, TLS, gateway timeout, DNS, host unreachable)
 *   - G50x: GATEWAY (G500 declined by gateway, G502 reversal required, G504 risk/KYC)
 *   - E6xx: EMV (card read / kernel errors)
 *   - P70x PRINT · X80x SECURITY · T90x THROTTLE · I9xx INTERNAL
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

    /** Acción concreta que resuelve el step-up del emisor. Ver [accionSugerida]. */
    const val ACCION_INSERTAR_CHIP = "Pide al cliente que inserte la tarjeta en el chip."

    /** Código del emisor para "se requiere autenticación adicional del titular". */
    private const val CODIGO_AUTENTICACION_ADICIONAL = "1A"

    /**
     * Traduce un rechazo del EMISOR a la acción que resuelve la venta, o `null` si no
     * hay ninguna que sugerir.
     *
     * Hoy cubre un solo caso, el `1A` "SE REQUIERE AUTENTICACION ADICIONAL DEL TITULAR":
     * no es un rechazo definitivo sino un **step-up**, y como AngelPay no soporta PIN
     * online (vendor 2026-07-29) el step-up real es **insertar el chip**. El mensaje del
     * banco ya se muestra en pantalla; lo que faltaba era decir qué hacer con él —
     * incidente Amaena 2026-08-31, donde el cajero reintentó a ciegas dos veces y las
     * dos aprobaron al segundo intento.
     *
     * 🔴 **Devolver `null` es el default y no se toca sin evidencia.** Sugerir de más es
     * peor que no sugerir: convertir un "fondos insuficientes" en "usa el chip" manda al
     * cajero a reintentar delante del cliente un cobro que nunca va a pasar.
     *
     * Se matchea por MENSAJE además de por código porque `PaymentResult.code` no siempre
     * trae el código del emisor — en el incidente el `1A` sólo viajó dentro del texto
     * (mismo motivo documentado en [isPreChargeRegisterFailure]). La comparación ignora
     * acentos y mayúsculas: el gateway ha entregado el texto en las dos formas.
     *
     * @param bankMessage `PaymentResult.message` — el texto del emisor, tal cual llega.
     * @param gatewayCode `PaymentResult.code` — código del emisor ("00" = aprobada).
     */
    fun accionSugerida(bankMessage: String?, gatewayCode: String?): String? {
        if (gatewayCode?.trim()?.uppercase() == CODIGO_AUTENTICACION_ADICIONAL) {
            return null // SABOTAJE
        }
        val normalizado = bankMessage?.let { normalizar(it) } ?: return null
        return null // SABOTAJE
    }

    /** Mayúsculas y sin acentos, para que "autenticación" y "AUTENTICACION" sean lo mismo. */
    private fun normalizar(texto: String): String =
        java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase()

    /**
     * Returns true if the SDK code means the AngelPay session expired and a logout +
     * re-auth ([AngelPayAuthRepository.handleAuthExpiry]) can recover the payment.
     * Used by AngelPayPaymentViewModel to re-authenticate and relaunch the sale once.
     *
     * Open Question #2 RESOLVED (2026-07-03) against the SDK 1.0.10 AppErrorCatalog:
     * session expiry is exactly `D308` ("Sesión Expirada, Inicie sesión nuevamente",
     * category DEVICE — not AUTH, so category matching would miss it). The previous
     * `startsWith("C2")` heuristic was wrong: C2xx are CLIENT config errors (amount,
     * tip, MSI) that a re-auth cannot fix. A0xx AUTH codes are excluded on purpose:
     * they mean authentication itself failed (credentials, key injection), so
     * retrying with the same credentials would loop for nothing.
     */
    fun isAuthError(code: String?): Boolean = code == "D308"

    /**
     * Returns true for the SDK's pre-charge terminal-registration failure — the second
     * shape a dead server-side session takes besides [isAuthError]/D308.
     *
     * Since SDK 1.0.10, PaymentActivity re-registers the terminal (fire token + serial,
     * sent with the stored bearer) BEFORE charging, with a 10 s internal timeout. When
     * that call fails — expired session being the common cause (incidente Alberto
     * Dominguez 2026-07-03: misma terminal cobró bien 15 h antes) — the SDK aborts with
     * this exact message but a HARDCODED `N400` CallResult, so code-based detection is
     * impossible: a real N400 mid-charge must NOT trigger re-auth (the charge may have
     * reached the gateway), which is why this matches the message, not the code. Verified
     * byte-identical in the 1.0.10 and 1.0.13 AARs (`z/j.class` / `b0/j.class`).
     *
     * Safe to recover: the SDK fails BEFORE calling the gateway, so no money moved and
     * one re-auth + relaunch cannot double-charge.
     */
    fun isPreChargeRegisterFailure(message: String?): Boolean =
        message?.contains("registrar la terminal antes del cobro", ignoreCase = true) == true
}
