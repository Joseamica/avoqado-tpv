package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.FastPaymentRequest
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Implementación de PaymentRecorder para pagos rápidos sin orden.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/fast
 *
 * **Responsabilidades:**
 * 1. Validar que el contexto sea FastPayment
 * 2. Convertir domain models a DTOs
 * 3. Convertir amounts de pesos (BigDecimal) a centavos (Int)
 * 4. Llamar backend API vía Retrofit
 * 5. Mapear response DTO a domain model
 * 6. Manejar errores de red
 *
 * **Flujo:**
 * ```
 * PaymentViewModel → RecordPaymentUseCase → FastPaymentRecorder → Backend API
 * ```
 *
 * **Ejemplo de uso:**
 * ```kotlin
 * val context = PaymentContext.FastPayment(
 *     venueId = "venue_123",
 *     staffId = "staff_456",
 *     amount = BigDecimal("50.00"),
 *     tip = BigDecimal("5.00")
 * )
 *
 * val result = fastPaymentRecorder.recordPayment(
 *     context = context,
 *     cardDetails = CardDetails(...),
 *     authorizationNumber = "502511",
 *     referenceNumber = "000000188231"
 * )
 *
 * result.onSuccess { receipt ->
 *     println("Receipt URL: ${receipt.receiptUrl}")
 * }
 * ```
 */
class FastPaymentRecorder @Inject constructor(
    private val apiService: PaymentApiService,
) : PaymentRecorder {

    override suspend fun recordPayment(
        context: PaymentContext,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<PaymentReceipt> = withContext(Dispatchers.IO) {
        try {
            // 1. Validar que sea FastPayment
            require(context is PaymentContext.FastPayment) {
                "FastPaymentRecorder only handles FastPayment context, got: ${context::class.simpleName}"
            }

            Timber.d(
                "🚀 Recording fast payment | venue=${context.venueId} | amount=${context.amount} | " +
                        "tip=${context.tip} | card=${cardDetails.cardBrand} | entry=${cardDetails.entryMode}"
            )

            // 2. Construir request DTO
            val request = buildFastPaymentRequest(context, cardDetails, authorizationNumber, referenceNumber)

            // 3. Llamar al backend
            val response = apiService.recordFastPayment(
                venueId = context.venueId,
                request = request
            )

            // 4. Procesar response
            when {
                response.isSuccessful && response.body() != null -> {
                    val body = response.body()!!
                    val receipt = PaymentReceipt(
                        paymentId = body.data.id,
                        receiptUrl = body.data.digitalReceipt.receiptUrl,
                        accessKey = body.data.digitalReceipt.accessKey,
                        amount = body.data.amount,
                        tipAmount = body.data.tipAmount,
                    )

                    Timber.i(
                        "✅ Fast payment recorded | paymentId=${receipt.paymentId} | " +
                                "receiptUrl=${receipt.receiptUrl}"
                    )

                    Result.success(receipt)
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
                    Timber.w("⚠️ Forbidden (403) - Missing payments:create permission")
                    Result.failure(
                        Exception(
                            "No tienes permisos para registrar pagos. " +
                                    "Contacta al administrador del venue."
                        )
                    )
                }

                response.code() == 404 -> {
                    Timber.w("⚠️ Not Found (404) - Venue ${context.venueId} not found")
                    Result.failure(
                        Exception(
                            "Venue no encontrado. Verifica tu configuración."
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
            Timber.e(e, "❌ Failed to record fast payment")
            Result.failure(
                Exception(
                    "Error registrando el pago: ${e.message ?: "Error desconocido"}. " +
                            "Verifica tu conexión a internet."
                )
            )
        }
    }

    /**
     * Construye el FastPaymentRequest DTO a partir de domain models.
     *
     * **Conversiones importantes:**
     * - amount: BigDecimal ($50.00) → Int (5000 cents)
     * - tip: BigDecimal ($5.00) → Int (500 cents)
     * - cardBrand: CardBrand enum → String ("VISA", "MASTERCARD")
     * - entryMode: CardEntryMode enum → String ("CHIP", "CONTACTLESS")
     *
     * @return FastPaymentRequest listo para enviar al backend
     */
    private fun buildFastPaymentRequest(
        context: PaymentContext.FastPayment,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): FastPaymentRequest {
        return FastPaymentRequest(
            // Venue ID (required in body in addition to URL path)
            venueId = context.venueId,

            // Amounts: Convert pesos to cents
            amount = (context.amount * 100.toBigDecimal()).toInt(),
            tip = (context.tip * 100.toBigDecimal()).toInt(),

            // Payment metadata
            status = "COMPLETED", // Payment is already approved by Blumon
            method = when (cardDetails.cardBrand) {
                CardBrand.VISA, CardBrand.MASTERCARD -> "CREDIT_CARD"
                CardBrand.AMEX -> "CREDIT_CARD"
                else -> "DEBIT_CARD"
            },
            source = "AVOQADO_TPV",
            splitType = "FULLPAYMENT",
            staffId = context.staffId,

            // Card details from Blumon SDK
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,
            maskedPan = cardDetails.maskedPan,
            cardBrand = if (cardDetails.cardBrand == CardBrand.UNKNOWN) null else cardDetails.cardBrand.name, // Send null if UNKNOWN
            entryMode = cardDetails.entryMode.toBackendString(), // CHIP → "CHIP"

            // Currency and international
            currency = "MXN",
            isInternational = cardDetails.isInternational,

            // Optional rating (null for fast payments)
            reviewRating = null,
        )
    }
}
