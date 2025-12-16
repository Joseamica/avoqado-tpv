package com.jaac.avoqado_tpv.features.payment.domain.model

import java.math.BigDecimal

/**
 * Recibo de reembolso generado por el backend después de procesar un refund.
 *
 * Similar a PaymentReceipt pero específico para reembolsos, con campos
 * adicionales para rastreo del pago original.
 *
 * **Campos:**
 * - refundId: ID único del Refund record en database
 * - originalPaymentId: ID del Payment que fue reembolsado
 * - receiptUrl: URL pública del recibo de reembolso (nullable si backend no genera)
 * - accessKey: Token seguro para acceder al recibo sin login
 * - amount: Monto reembolsado (para confirmación rápida en UI)
 * - status: Estado del reembolso ("COMPLETED", "FAILED")
 *
 * **Uso:**
 * ```kotlin
 * // Después de refund exitoso
 * val result = recordRefundUseCase(...)
 * result.onSuccess { receipt ->
 *     // Mostrar confirmación
 *     displayRefundConfirmation(receipt.amount)
 *
 *     // Opcionalmente mostrar recibo
 *     receipt.receiptUrl?.let { displayReceipt(it) }
 * }
 * ```
 *
 * **Multi-Merchant Note:**
 * The refund is recorded against the same merchantAccountId as the original
 * payment to ensure proper settlement reconciliation.
 *
 * @param refundId ID del refund record en database (ej. "clxxx...")
 * @param originalPaymentId ID del payment que fue reembolsado
 * @param receiptUrl URL completa del recibo público (nullable)
 * @param accessKey Token de acceso único (nullable)
 * @param amount Monto reembolsado en pesos
 * @param status Estado del reembolso
 */
data class RefundReceipt(
    val refundId: String,
    val originalPaymentId: String,
    val receiptUrl: String?,
    val accessKey: String?,
    val amount: BigDecimal,
    val status: String,
) {
    /**
     * Verifica si el reembolso fue exitoso.
     */
    val isSuccessful: Boolean
        get() = status.equals("COMPLETED", ignoreCase = true)

    /**
     * Verifica si hay recibo disponible para mostrar.
     */
    val hasReceipt: Boolean
        get() = !receiptUrl.isNullOrBlank()
}
