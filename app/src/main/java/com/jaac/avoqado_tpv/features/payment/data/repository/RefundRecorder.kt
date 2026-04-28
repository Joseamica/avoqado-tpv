package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.RefundRequest
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Implementación de refund recording para reembolsos.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/refunds
 *
 * **⚠️ MULTI-MERCHANT CRITICAL:**
 * Refunds MUST be processed through the same merchant account that processed
 * the original payment. This recorder ensures proper merchant routing by:
 * 1. Validating merchantAccountId is present in RefundPayment context
 * 2. Including merchantAccountId in the API request
 * 3. Using blumonSerialNumber for SDK merchant switching
 *
 * **Responsabilidades:**
 * 1. Validar que el contexto sea RefundPayment
 * 2. Validar que merchantAccountId is present (required for multi-merchant)
 * 3. Convertir domain models a DTOs
 * 4. Convertir amounts de pesos (BigDecimal) a centavos (Int)
 * 5. Llamar backend API vía Retrofit
 * 6. Mapear response DTO a domain model (RefundReceipt)
 * 7. Manejar errores de red y business logic errors
 *
 * **Flujo:**
 * ```
 * PaymentViewModel
 *   ↓ (TransType.REFUND completed)
 * RecordRefundUseCase
 *   ↓ (validates context)
 * RefundRecorder
 *   ↓ (validates merchantAccountId)
 * Backend API /tpv/venues/{venueId}/refunds
 * ```
 *
 * **Ejemplo de uso:**
 * ```kotlin
 * val context = PaymentContext.RefundPayment(
 *     venueId = "venue_123",
 *     staffId = "staff_456",
 *     amount = BigDecimal("50.00"),
 *     originalPaymentId = "payment_789",
 *     merchantAccountId = "merchant_abc", // MUST match original payment
 *     blumonSerialNumber = "PAX123456",
 *     refundReason = RefundReason.CUSTOMER_REQUEST
 * )
 *
 * val result = refundRecorder.recordRefund(
 *     context = context,
 *     cardDetails = CardDetails(...),
 *     authorizationNumber = "502511",
 *     referenceNumber = "000000188231"
 * )
 * ```
 */
class RefundRecorder @Inject constructor(
    private val apiService: PaymentApiService,
) {
    /**
     * Records a refund in the backend.
     *
     * @param context Must be PaymentContext.RefundPayment with valid merchantAccountId
     * @param cardDetails Card details from Blumon SDK (for audit trail)
     * @param authorizationNumber Authorization code from Blumon SDK for the REFUND transaction
     * @param referenceNumber Reference number from Blumon SDK for the REFUND transaction
     * @return Result with RefundReceipt on success or Exception on failure
     */
    suspend fun recordRefund(
        context: PaymentContext.RefundPayment,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
        tipRefundCents: Int? = null,
    ): Result<RefundReceipt> = withContext(Dispatchers.IO) {
        try {
            // 1. Validate merchantAccountId is present (multi-merchant critical)
            if (context.merchantAccountId.isNullOrBlank()) {
                Timber.e("❌ RefundRecorder: merchantAccountId is required for refund processing")
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "No se puede procesar el reembolso: información del comerciante no disponible. " +
                        "El pago original no tiene información de merchant account."
                    )
                )
            }

            Timber.d(
                "🔄 Recording refund | venue=${context.venueId} | originalPayment=${context.originalPaymentId} | " +
                "amount=${context.amount} | merchant=${context.merchantAccountId} | reason=${context.refundReason}"
            )

            // 2. Build request DTO
            val request = buildRefundRequest(context, cardDetails, authorizationNumber, referenceNumber, tipRefundCents)
            val idempotencyKey = context.idempotencyKey

            // 3. Call backend API
            val response = apiService.recordRefund(
                venueId = context.venueId,
                request = request,
                idempotencyKey = idempotencyKey,
            )

            // 4. Process response
            when {
                response.isSuccessful && response.body() != null -> {
                    val body = response.body()!!
                    val receipt = RefundReceipt(
                        refundId = body.data.id,
                        originalPaymentId = body.data.originalPaymentId,
                        receiptUrl = body.data.digitalReceipt?.receiptUrl,
                        accessKey = body.data.digitalReceipt?.accessKey,
                        amount = body.data.amount,
                        status = body.data.status,
                    )

                    Timber.i(
                        "✅ Refund recorded | refundId=${receipt.refundId} | " +
                        "originalPayment=${receipt.originalPaymentId} | amount=${receipt.amount}"
                    )

                    Result.success(receipt)
                }

                response.code() == 400 -> {
                    Timber.w("⚠️ Bad Request (400) - Invalid refund data")
                    Result.failure(
                        Exception(
                            "Datos de reembolso inválidos. Verifica el monto y la información del pago original."
                        )
                    )
                }

                response.code() == 401 -> {
                    Timber.w("⚠️ Unauthorized (401) - Token may be expired")
                    Result.failure(
                        Exception(
                            "Token de autenticación inválido o expirado. " +
                            "Por favor, cierra sesión y vuelve a iniciar sesión."
                        )
                    )
                }

                response.code() == 403 -> {
                    Timber.w("⚠️ Forbidden (403) - Missing refunds:create permission or role not authorized")
                    Result.failure(
                        Exception(
                            "No tienes permisos para procesar reembolsos. " +
                            "Solo administradores y gerentes pueden realizar reembolsos."
                        )
                    )
                }

                response.code() == 404 -> {
                    Timber.w("⚠️ Not Found (404) - Payment ${context.originalPaymentId} not found")
                    Result.failure(
                        Exception(
                            "Pago no encontrado. El pago original puede haber sido eliminado."
                        )
                    )
                }

                response.code() == 409 -> {
                    Timber.w("⚠️ Conflict (409) - Payment already fully refunded or concurrent refund")
                    Result.failure(
                        Exception(
                            "Este pago ya fue reembolsado completamente o hay otro reembolso en proceso."
                        )
                    )
                }

                response.code() == 429 -> {
                    Timber.w("⚠️ Rate Limit (429) - Too many requests")
                    Result.failure(
                        Exception(
                            "Demasiadas solicitudes. Por favor, espera un momento e intenta nuevamente."
                        )
                    )
                }

                response.code() in 500..599 -> {
                    Timber.e("❌ Server Error (${response.code()}) - ${response.message()}")
                    Result.failure(
                        Exception(
                            "Error del servidor (${response.code()}). " +
                            "Por favor, intenta nuevamente en unos minutos."
                        )
                    )
                }

                else -> {
                    Timber.e("❌ Unknown error (${response.code()}) - ${response.message()}")
                    Result.failure(
                        Exception(
                            "Error desconocido (${response.code()}): ${response.message()}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to record refund")
            Result.failure(
                Exception(
                    "Error registrando el reembolso: ${e.message ?: "Error desconocido"}. " +
                    "Verifica tu conexión a internet."
                )
            )
        }
    }

    /**
     * Builds the RefundRequest DTO from domain models.
     *
     * **Conversiones importantes:**
     * - amount: BigDecimal ($50.00) → Int (5000 cents)
     * - refundReason: RefundReason enum → String ("CUSTOMER_REQUEST")
     * - cardBrand: CardBrand enum → String ("VISA", "MASTERCARD")
     * - entryMode: CardEntryMode enum → String ("CHIP", "CONTACTLESS")
     *
     * @return RefundRequest ready to send to backend
     */
    private fun buildRefundRequest(
        context: PaymentContext.RefundPayment,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
        tipRefundCents: Int? = null,
    ): RefundRequest {
        return RefundRequest(
            // Venue and payment identification
            venueId = context.venueId,
            originalPaymentId = context.originalPaymentId,
            originalOrderId = context.originalOrderId,

            // Amount in cents
            amount = (context.amount * BigDecimal(100)).toInt(),

            // Refund metadata
            reason = context.refundReason.name,
            staffId = context.staffId,
            shiftId = context.shiftId,

            // ⭐ MULTI-MERCHANT CRITICAL
            // These fields ensure refund goes to correct merchant
            merchantAccountId = context.merchantAccountId,
            blumonSerialNumber = context.blumonSerialNumber,

            // Blumon SDK response data (from TransType.REFUND)
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,

            // Card details (for audit trail)
            maskedPan = cardDetails.maskedPan,
            cardBrand = cardDetails.cardBrand.backendName,
            entryMode = cardDetails.entryMode.toBackendString(),
            idempotencyKey = context.idempotencyKey,

            // Partial refund flag
            isPartialRefund = context.isPartialRefund,

            // Currency
            currency = "MXN",

            // Optional tip-split override — null = backend default (proportional).
            // Does NOT change the Blumon charge; only affects internal booking.
            tipRefundCents = tipRefundCents,
        )
    }
}
