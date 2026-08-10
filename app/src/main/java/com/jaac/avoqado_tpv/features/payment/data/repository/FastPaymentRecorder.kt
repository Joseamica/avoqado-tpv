package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
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
import java.io.IOException
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
            // 1. Validar que sea FastPayment o AngelPayPayment (fast)
            require(context is PaymentContext.FastPayment || context is PaymentContext.AngelPayPayment) {
                "FastPaymentRecorder only handles FastPayment/AngelPayPayment context, got: ${context::class.simpleName}"
            }

            Timber.d(
                "🚀 Recording fast payment | venue=${context.venueId} | amount=${context.amount} | " +
                        "tip=${context.tip} | card=${cardDetails.cardBrand} | entry=${cardDetails.entryMode} | processor=${context::class.simpleName}"
            )

            // 2. Construir request DTO
            val request = when (context) {
                is PaymentContext.FastPayment -> {
                    // 🔍 DEBUG: Trace blumonOperationNumber through the chain
                    Timber.i("═══════════════════════════════════════════════════════════")
                    Timber.i("🔍 DEBUG TRACE - blumonOperationNumber")
                    Timber.i("   STEP 1 - In PaymentContext:")
                    Timber.i("      context.blumonOperationNumber = ${context.blumonOperationNumber}")
                    Timber.i("      context.blumonOperationNumber type = ${context.blumonOperationNumber?.javaClass?.name ?: "null"}")
                    Timber.i("═══════════════════════════════════════════════════════════")
                    buildFastPaymentRequest(context, cardDetails, authorizationNumber, referenceNumber)
                }
                is PaymentContext.AngelPayPayment -> {
                    Timber.d("🔶 [AngelPay] Building fast payment request from AngelPayPayment context")
                    buildAngelPayFastPaymentRequest(context, cardDetails, authorizationNumber, referenceNumber)
                }
                else -> error("Unexpected context type: ${context::class.simpleName}")
            }.normalizedCashMerchantAccount()

            // 🔍 DEBUG: Verify the DTO has the value
            Timber.i("═══════════════════════════════════════════════════════════")
            Timber.i("🔍 DEBUG TRACE - After building FastPaymentRequest")
            Timber.i("   STEP 2 - In FastPaymentRequest DTO:")
            Timber.i("      request.blumonOperationNumber = ${request.blumonOperationNumber}")
            Timber.i("      request.blumonOperationNumber type = ${request.blumonOperationNumber?.javaClass?.name ?: "null"}")
            Timber.i("      request.method = ${request.method}")
            Timber.i("      request.hasMerchantAccountId = ${!request.merchantAccountId.isNullOrBlank()}")
            Timber.i("═══════════════════════════════════════════════════════════")

            // 🔍 DEBUG: Manually serialize to JSON to see exactly what Gson produces
            try {
                val gson = com.google.gson.GsonBuilder().create()
                val jsonBody = gson.toJson(request)
                Timber.i("═══════════════════════════════════════════════════════════")
                Timber.i("🔍 DEBUG TRACE - Gson Serialization Result")
                Timber.i("   STEP 3 - JSON body to be sent:")
                Timber.i("      $jsonBody")
                Timber.i("   Contains 'blumonOperationNumber'? ${jsonBody.contains("blumonOperationNumber")}")
                Timber.i("═══════════════════════════════════════════════════════════")
            } catch (e: Exception) {
                Timber.e(e, "Failed to serialize request for debug logging")
            }

            // 3. Llamar al backend
            val response = apiService.recordFastPayment(
                venueId = context.venueId,
                request = request
            )

            // 4. Procesar response
            when {
                response.isSuccessful && response.body() != null -> {
                    val body = response.body()!!
                    // 🔴 `digitalReceipt` PUEDE venir null (ver PaymentResponse.kt). Leerlo sin
                    // proteger tiraba NPE, la NPE se clasificaba como transitoria y el pago —YA
                    // COBRADO Y YA REGISTRADO— se reintentaba para siempre. Un recibo faltante
                    // NO invalida el cobro: se acepta sin QR y la cola se cierra.
                    val digitalReceipt = body.data.digitalReceipt
                    if (digitalReceipt == null) {
                        Timber.w(
                            "⚠️ Fast payment registrado SIN recibo digital | paymentId=${body.data.id} | " +
                                    "el cobro está guardado; sólo no habrá QR. NO se reintenta."
                        )
                    }
                    val receipt = PaymentReceipt(
                        paymentId = body.data.id,
                        receiptUrl = digitalReceipt?.receiptUrl.orEmpty(),
                        accessKey = digitalReceipt?.accessKey.orEmpty(),
                        amount = body.data.amount,
                        tipAmount = body.data.tipAmount,
                        autofacturaAvailable = digitalReceipt?.autofacturaAvailable ?: false,
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
                        BackendHttpException(
                            statusCode = response.code(),
                            message = "Token de autenticación inválido o expirado. " +
                                    "Por favor, cierra sesión y vuelve a iniciar sesión."
                        )
                    )
                }

                response.code() == 403 -> {
                    Timber.w("⚠️ Forbidden (403) - Missing payments:create permission")
                    Result.failure(
                        BackendHttpException(
                            statusCode = response.code(),
                            message = "No tienes permisos para registrar pagos. " +
                                    "Contacta al administrador del venue."
                        )
                    )
                }

                response.code() == 404 -> {
                    Timber.w("⚠️ Not Found (404) - Venue ${context.venueId} not found")
                    Result.failure(
                        BackendHttpException(
                            statusCode = response.code(),
                            message = "Venue no encontrado. Verifica tu configuración."
                        )
                    )
                }

                response.code() == 429 -> {
                    Timber.w("⚠️ Rate Limit (429) - Too many requests")
                    Result.failure(
                        BackendHttpException(
                            statusCode = response.code(),
                            message = "Demasiadas solicitudes. Por favor, espera un momento e intenta nuevamente."
                        )
                    )
                }

                response.code() in 500..599 -> {
                    Timber.e("❌ Server Error (${response.code()}) - ${response.message()}")
                    Result.failure(
                        BackendHttpException(
                            statusCode = response.code(),
                            message = "Error del servidor (${response.code()}). " +
                                    "Por favor, intenta nuevamente en unos minutos."
                        )
                    )
                }

                else -> {
                    val errorMessage = parseBackendErrorMessage(
                        rawBody = response.errorBody()?.string(),
                        fallback = response.message()
                    )
                    Timber.e("❌ Unknown error (${response.code()}) - $errorMessage")
                    Result.failure(
                        BackendHttpException(
                            statusCode = response.code(),
                            message = "Error desconocido (${response.code()}): $errorMessage"
                        )
                    )
                }
            }
        } catch (e: IOException) {
            // NO envolver: classifySyncFailure() (SyncOutcome.kt) necesita la IOException
            // intacta para clasificarla como Retryable. Ver BackendHttpException.kt.
            Timber.w(e, "⚠️ Network error recording fast payment (will be retried)")
            Result.failure(e)
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
            status = "COMPLETED", // Payment is already approved by Blumon (or cash)
            method = cardDetails.toPaymentMethod(),
            source = "AVOQADO_TPV",
            splitType = "FULLPAYMENT",
            staffId = context.staffId,

            // Card details from Blumon SDK
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,

            // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING (2025-01-10)
            merchantAccountId = context.merchantAccountId, // 🆕 PRIMARY: Merchant account ID
            blumonSerialNumber = context.blumonSerialNumber.takeIf { it.isNotBlank() }, // ⚠️ LEGACY: Fallback

            maskedPan = cardDetails.maskedPan,
            cardBrand = if (cardDetails.cardBrand == CardBrand.UNKNOWN) null else cardDetails.cardBrand.backendName, // Use backendName for Prisma enum compatibility
            entryMode = cardDetails.entryMode.toBackendString(), // CHIP → "CHIP"

            // Currency and international
            currency = "MXN",
            isInternational = cardDetails.isInternational,
            issuerCountryCode = cardDetails.issuerCountryCode,
            issuerCountrySource = cardDetails.issuerCountrySource?.apiValue,

            // Optional rating: Send numeric rating as string (1-5 stars)
            reviewRating = context.rating?.toString(),

            // 📸 PRE-PAYMENT VERIFICATION (2025-01-14)
            // Order reference generated ONCE when entering VerifyingPrePayment state
            // Ensures Firebase photos match the order number created in backend
            orderReference = context.orderReference,
            // Firebase Storage URLs of verification photos (uploaded before payment)
            verificationPhotos = context.verificationPhotos.takeIf { it.isNotEmpty() },
            // Scanned barcodes from verification screen
            verificationBarcodes = context.verificationBarcodes.takeIf { it.isNotEmpty() },

            // 💸 Blumon Operation Number (2025-12-16) - For refunds without webhook
            // This comes from response.operation in SaleIccResponse
            blumonOperationNumber = context.blumonOperationNumber,

            // ⭐ Device Serial Number for Terminal attribution (2026-01-08)
            // Links payment to the Terminal that processed it (for device-based reporting)
            deviceSerialNumber = context.deviceSerialNumber,

            // 📸 NON-BLOCKING PROOF-OF-SALE (2026-03-10)
            isPortabilidad = context.isPortabilidad.takeIf { it },
            serialNumbers = context.serialNumbers.takeIf { it.isNotEmpty() },

            // 🛡️ IDEMPOTENCY KEY (2026-04-08) - Stripe/Square/Toast pattern
            // Generated ONCE per logical payment attempt in PaymentViewModel.startPayment()
            // and persisted through all retries of that attempt.
            idempotencyKey = context.idempotencyKey,

            // 📡 POS→TPV arbitration link — closes the TerminalPaymentRequest row server-side
            terminalPaymentRequestId = context.terminalPaymentRequestId,
        )
    }

    /**
     * Build FastPaymentRequest from AngelPayPayment context.
     * AngelPay uses the same backend endpoint as Blumon for fast payments.
     */
    private fun buildAngelPayFastPaymentRequest(
        context: PaymentContext.AngelPayPayment,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): FastPaymentRequest {
        return FastPaymentRequest(
            venueId = context.venueId,
            amount = (context.amount * 100.toBigDecimal()).toInt(),
            tip = (context.tip * 100.toBigDecimal()).toInt(),
            status = "COMPLETED",
            method = cardDetails.toPaymentMethod(),
            source = "AVOQADO_TPV",
            splitType = "FULLPAYMENT",
            staffId = context.staffId,
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,
            merchantAccountId = context.merchantAccountId,
            blumonSerialNumber = null,
            maskedPan = cardDetails.maskedPan,
            cardBrand = if (cardDetails.cardBrand == CardBrand.UNKNOWN) null else cardDetails.cardBrand.backendName,
            entryMode = cardDetails.entryMode.toBackendString(),
            currency = "MXN",
            isInternational = cardDetails.isInternational,
            issuerCountryCode = cardDetails.issuerCountryCode,
            issuerCountrySource = cardDetails.issuerCountrySource?.apiValue,
            reviewRating = context.rating?.toString(),
            deviceSerialNumber = context.deviceSerialNumber,
            // 📸 NON-BLOCKING PROOF-OF-SALE (serialized inventory / SIM) — empty for normal payments
            isPortabilidad = context.isPortabilidad.takeIf { it },
            serialNumbers = context.serialNumbers.takeIf { it.isNotEmpty() },
            idempotencyKey = context.idempotencyKey, // 🛡️ Idempotency key (2026-04-08)
            terminalPaymentRequestId = context.terminalPaymentRequestId, // 📡 POS→TPV arbitration link
        )
    }

    private fun FastPaymentRequest.normalizedCashMerchantAccount(): FastPaymentRequest {
        if (method != "CASH" || merchantAccountId.isNullOrBlank()) {
            return this
        }

        Timber.w(
            "⚠️ [FastPaymentRecorder] Dropping merchantAccountId for CASH payment before backend recording | " +
                    "merchant=${merchantAccountId.take(8)}..."
        )
        return copy(merchantAccountId = null)
    }

    private fun parseBackendErrorMessage(rawBody: String?, fallback: String): String {
        val trimmedBody = rawBody?.trim().orEmpty()
        if (trimmedBody.isBlank()) {
            return fallback.ifBlank { "Sin detalle del servidor" }
        }

        val parsedMessage = runCatching {
            val json = com.google.gson.JsonParser.parseString(trimmedBody)
            if (!json.isJsonObject) return@runCatching null

            val obj = json.asJsonObject
            obj.get("message")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("error")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("code")?.takeUnless { it.isJsonNull }?.asString
        }.getOrNull()

        return parsedMessage?.takeIf { it.isNotBlank() } ?: trimmedBody.take(300)
    }
}
