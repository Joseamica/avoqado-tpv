package com.jaac.avoqado_tpv.features.payment.domain.model

import java.math.BigDecimal

/**
 * Recibo digital generado por el backend después de grabar un pago.
 *
 * El backend genera automáticamente un DigitalReceipt para cada Payment
 * con un access key único que permite acceso público sin autenticación.
 *
 * **Campos:**
 * - paymentId: ID único del Payment en database
 * - receiptUrl: URL pública del recibo (ej. https://api.avoqado.io/api/v1/public/receipt/{accessKey})
 * - accessKey: Token seguro para acceder al recibo sin login
 * - amount: Monto BASE pagado (subtotal SIN tip - así lo guarda el backend)
 * - tipAmount: Propina incluida (para confirmación rápida en UI)
 *
 * **IMPORTANTE - Backend Schema:**
 * El backend guarda `payment.amount = subtotal` (sin tip) y `payment.tipAmount = tip` por separado.
 * Por eso `totalAmount = amount + tipAmount` y `baseAmount = amount`.
 *
 * **Uso:**
 * ```kotlin
 * // Después de pago exitoso
 * val receipt = recordPaymentUseCase(...)
 * receipt.onSuccess { receipt ->
 *     // Mostrar recibo en app
 *     displayReceipt(receipt.receiptUrl)
 *
 *     // O enviar por email/SMS
 *     sendReceiptEmail(receipt.receiptUrl)
 * }
 * ```
 *
 * **Seguridad:**
 * - accessKey es único y no guessable (CUID)
 * - Recibo no expone información sensible de tarjeta
 * - URL es pública pero solo conocida por quien recibe el link
 *
 * @param paymentId ID del payment en database (ej. "clxxx...")
 * @param receiptUrl URL completa del recibo público
 * @param accessKey Token de acceso único (CUID)
 * @param amount Monto BASE pagado (subtotal SIN propina)
 * @param tipAmount Propina incluida en el pago
 */
data class PaymentReceipt(
    val paymentId: String,
    val receiptUrl: String,
    val accessKey: String,
    val amount: BigDecimal,
    val tipAmount: BigDecimal,
) {
    /**
     * Monto total del pago (base + propina).
     * ✅ FIX: Backend guarda subtotal en `amount`, tip en `tipAmount` por separado.
     */
    val totalAmount: BigDecimal
        get() = amount + tipAmount

    /**
     * Monto sin propina (solo base payment).
     * ✅ FIX: `amount` del backend YA ES el base (subtotal).
     */
    val baseAmount: BigDecimal
        get() = amount

    /**
     * Verifica si el pago incluye propina.
     */
    val hasTip: Boolean
        get() = tipAmount > BigDecimal.ZERO
}
