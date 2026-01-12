package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.OrderPaymentRequest
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
 * Implementación de PaymentRecorder para pagos de órdenes existentes.
 *
 * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}
 *
 * **⚠️ NOTA IMPORTANTE:**
 * Esta clase NO se usa todavía porque no hay feature de "create order" implementada.
 * El código está completo y listo para cuando se implemente.
 *
 * **Responsabilidades:**
 * 1. Validar que el contexto sea OrderPayment
 * 2. Convertir domain models a DTOs
 * 3. Llamar backend API con orderId en URL path
 * 4. Mapear response DTO a domain model
 * 5. Manejar errores específicos de orders (409 Conflict, etc.)
 *
 * **Diferencias vs FastPaymentRecorder:**
 * - Endpoint: /orders/{orderId} (NO /fast)
 * - Request incluye: venueId, paidProductsId
 * - Backend NO crea orden virtual (orden ya existe)
 * - Backend actualiza order.paymentStatus
 * - Backend deduce inventory si orden queda PAID
 *
 * **Cuándo se usará:**
 * Cuando el usuario:
 * 1. Crea una orden con productos
 * 2. La orden se guarda en backend (POST /orders)
 * 3. Procesa el pago para esa orden
 * 4. Este recorder vincula el pago a la orden existente
 *
 * **Ejemplo de uso futuro:**
 * ```kotlin
 * // 1. Usuario crea orden
 * val order = createOrder(items = listOf(burger, fries))
 *
 * // 2. Usuario paga
 * val context = PaymentContext.OrderPayment(
 *     venueId = "venue_123",
 *     staffId = "staff_456",
 *     orderId = order.id, // ← Orden ya existe
 *     amount = order.total,
 *     tip = selectedTip
 * )
 *
 * val result = orderPaymentRecorder.recordPayment(
 *     context = context,
 *     cardDetails = CardDetails(...),
 *     authorizationNumber = "502511",
 *     referenceNumber = "000000188231"
 * )
 * ```
 */
class OrderPaymentRecorder @Inject constructor(
    private val apiService: PaymentApiService,
) : PaymentRecorder {

    override suspend fun recordPayment(
        context: PaymentContext,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<PaymentReceipt> = withContext(Dispatchers.IO) {
        try {
            // 1. Validar que sea OrderPayment
            require(context is PaymentContext.OrderPayment) {
                "OrderPaymentRecorder only handles OrderPayment context, got: ${context::class.simpleName}"
            }

            // 🔍 DEBUG: Full payment context
            Timber.i("═══════════════════════════════════════════════════════════")
            Timber.i("🚀 RECORDING ORDER PAYMENT - START")
            Timber.i("   venueId: ${context.venueId}")
            Timber.i("   orderId: ${context.orderId}")
            Timber.i("   staffId: ${context.staffId}")
            Timber.i("   ─────────────────────────────────────────────────────")
            Timber.i("   💰 PAYMENT AMOUNTS:")
            Timber.i("      amount (pesos): ${context.amount}")
            Timber.i("      tip (pesos): ${context.tip}")
            Timber.i("      total (pesos): ${context.amount + context.tip}")
            Timber.i("   ─────────────────────────────────────────────────────")
            Timber.i("   🔀 SPLIT PAYMENT INFO:")
            Timber.i("      splitType: ${context.splitType}")
            Timber.i("      paidProductIds: ${context.paidProductIds}")
            Timber.i("      merchantAccountId: ${context.merchantAccountId}")
            Timber.i("   ─────────────────────────────────────────────────────")
            Timber.i("   💳 CARD DETAILS:")
            Timber.i("      cardBrand: ${cardDetails.cardBrand}")
            Timber.i("      maskedPan: ${cardDetails.maskedPan}")
            Timber.i("      entryMode: ${cardDetails.entryMode}")
            Timber.i("      isInternational: ${cardDetails.isInternational}")
            Timber.i("   ─────────────────────────────────────────────────────")
            Timber.i("   🔐 AUTHORIZATION:")
            Timber.i("      authorizationNumber: $authorizationNumber")
            Timber.i("      referenceNumber: $referenceNumber")
            Timber.i("═══════════════════════════════════════════════════════════")

            // 2. Construir request DTO
            val request = buildOrderPaymentRequest(context, cardDetails, authorizationNumber, referenceNumber)

            // 🔍 DEBUG: Request to send
            Timber.d("📤 OrderPaymentRequest: amount=${request.amount}cents, tip=${request.tip}cents, method=${request.method}, splitType=${request.splitType}")

            // 3. Llamar al backend (diferente endpoint que fast payment)
            val response = apiService.recordOrderPayment(
                venueId = context.venueId,
                orderId = context.orderId, // ← Diferencia clave vs fast payment
                request = request
            )

            // 4. Procesar response (idéntico a FastPaymentRecorder)
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

                    // 🔍 DEBUG: Payment success details
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("✅ ORDER PAYMENT RECORDED SUCCESSFULLY")
                    Timber.i("   paymentId: ${receipt.paymentId}")
                    Timber.i("   orderId: ${context.orderId}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   💰 RECORDED AMOUNTS:")
                    Timber.i("      amount: ${receipt.amount}")
                    Timber.i("      tipAmount: ${receipt.tipAmount}")
                    Timber.i("   ─────────────────────────────────────────────────────")
                    Timber.i("   🧾 RECEIPT:")
                    Timber.i("      receiptUrl: ${receipt.receiptUrl}")
                    Timber.i("      accessKey: ${receipt.accessKey}")
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("📝 NOTE: Backend should have deducted inventory if order is now PAID")

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
                    Timber.w("⚠️ Not Found (404) - Venue or Order not found")
                    Result.failure(
                        Exception(
                            "Orden no encontrada. Verifica que la orden existe."
                        )
                    )
                }

                response.code() == 409 -> {
                    // 409 Conflict: Orden ya está completamente pagada
                    Timber.w("⚠️ Conflict (409) - Order already fully paid")
                    Result.failure(
                        Exception(
                            "Esta orden ya está completamente pagada."
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
            Timber.e(e, "❌ Failed to record order payment")
            Result.failure(
                Exception(
                    "Error registrando el pago: ${e.message ?: "Error desconocido"}. " +
                            "Verifica tu conexión a internet."
                )
            )
        }
    }

    /**
     * Construye el OrderPaymentRequest DTO a partir de domain models.
     *
     * **Diferencias vs FastPaymentRequest:**
     * - Incluye venueId en body (además del path)
     * - Incluye paidProductsId (para split payments)
     *
     * **TODO:** Cuando se implemente split payments, obtener paidProductsId
     * de la orden actual. Por ahora se envía lista vacía (full payment).
     */
    private fun buildOrderPaymentRequest(
        context: PaymentContext.OrderPayment,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): OrderPaymentRequest {
        // ✅ CASH vs CARD: Determine payment method
        val isCashPayment = context.merchantAccountId == null

        return OrderPaymentRequest(
            // Venue ID (requerido en body además del path)
            venueId = context.venueId,

            // Amounts: Backend expects centavos (10000 = $100.00)
            amount = (context.amount * 100.toBigDecimal()).toInt(),
            tip = (context.tip * 100.toBigDecimal()).toInt(),

            // Payment metadata
            status = "COMPLETED",
            method = if (isCashPayment) {
                "CASH"  // ✅ Cash payment (merchantAccountId = null)
            } else {
                when (cardDetails.cardBrand) {
                    CardBrand.VISA, CardBrand.MASTERCARD -> "CREDIT_CARD"
                    CardBrand.AMEX -> "CREDIT_CARD"
                    else -> "DEBIT_CARD"
                }
            },
            source = "AVOQADO_TPV",
            splitType = context.splitType.value,
            staffId = context.staffId,

            // ⭐ SPLIT PAYMENT: Product IDs for PERPRODUCT mode
            paidProductsId = context.paidProductIds,

            // Card details from Blumon SDK
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,
            maskedPan = cardDetails.maskedPan,
            cardBrand = if (cardDetails.cardBrand == CardBrand.UNKNOWN) null else cardDetails.cardBrand.backendName, // Use backendName for Prisma enum compatibility
            entryMode = cardDetails.entryMode.toBackendString(),

            // Currency and international
            currency = "MXN",
            isInternational = cardDetails.isInternational,

            // Optional rating: Send numeric rating as string (1-5 stars)
            reviewRating = context.rating?.toString(),

            // ⭐ RECONCILIATION: null = cash (no processor cost), CUID = card payment
            merchantAccountId = context.merchantAccountId,

            // 💸 Blumon Operation Number (2025-12-16) - For refunds without webhook
            // This comes from response.operation in SaleIccResponse
            blumonOperationNumber = context.blumonOperationNumber,

            // ⭐ Device Serial Number for Terminal attribution (2026-01-08)
            // Links payment to the Terminal that processed it (for device-based reporting)
            deviceSerialNumber = context.deviceSerialNumber,
        )
    }
}
