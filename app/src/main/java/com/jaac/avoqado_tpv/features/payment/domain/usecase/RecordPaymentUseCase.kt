package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentRecorder
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case que orquesta la grabación de pagos en el backend.
 *
 * **Strategy Pattern:**
 * Este use case selecciona automáticamente el PaymentRecorder correcto
 * basándose en el tipo de PaymentContext (sealed class).
 *
 * **Responsabilidades:**
 * 1. Decidir qué recorder usar (Fast vs Order)
 * 2. Delegar la grabación al recorder apropiado
 * 3. (Futuro) Manejar retry logic
 * 4. (Futuro) Implementar offline queue
 *
 * **Por qué esta abstracción:**
 * - PaymentViewModel NO sabe si está procesando fast payment u order payment
 * - El ViewModel solo llama: `recordPaymentUseCase(context, ...)`
 * - Este UseCase encapsula la lógica de selección
 * - Exhaustive when garantiza que manejemos todos los casos
 *
 * **Flujo:**
 * ```
 * PaymentViewModel
 *   ↓ calls
 * RecordPaymentUseCase
 *   ↓ selects
 * FastPaymentRecorder OR OrderPaymentRecorder
 *   ↓ calls
 * Backend API (Retrofit)
 * ```
 *
 * **Ventajas:**
 * 1. ✅ Single Responsibility: Solo decide qué recorder usar
 * 2. ✅ Testeable: Mock recorders en unit tests
 * 3. ✅ Extensible: Fácil agregar RefundRecorder, SplitPaymentRecorder, etc.
 * 4. ✅ Type-safe: Sealed class garantiza exhaustive when
 *
 * **Ejemplo de uso:**
 * ```kotlin
 * // Fast payment
 * val fastContext = PaymentContext.FastPayment(...)
 * val result = recordPaymentUseCase(fastContext, cardDetails, auth, ref)
 * // → Automáticamente usa FastPaymentRecorder
 *
 * // Order payment (futuro)
 * val orderContext = PaymentContext.OrderPayment(orderId = "order_123", ...)
 * val result = recordPaymentUseCase(orderContext, cardDetails, auth, ref)
 * // → Automáticamente usa OrderPaymentRecorder
 * ```
 */
class RecordPaymentUseCase @Inject constructor(
    private val fastPaymentRecorder: FastPaymentRecorder,
    private val orderPaymentRecorder: OrderPaymentRecorder,
) {
    companion object {
        /**
         * Número máximo de intentos para grabar un pago antes de marcarlo como fallido.
         *
         * **Backoff exponencial:**
         * - Intento 1: Inmediato
         * - Intento 2: +500ms delay
         * - Intento 3: +1s delay
         * - Intento 4: +2s delay
         * - Intento 5: +4s delay
         *
         * Total: ~7.5s de intentos antes de ir a la cola offline
         */
        private const val MAX_RETRIES = 5

        /**
         * Delay inicial en milisegundos para el primer retry.
         * Se duplica exponencialmente en cada intento: 500ms → 1s → 2s → 4s
         */
        private const val INITIAL_RETRY_DELAY_MS = 500L
    }
    /**
     * Graba el pago en el backend usando el recorder apropiado.
     *
     * **Proceso:**
     * 1. Analiza el tipo de PaymentContext (sealed class)
     * 2. Selecciona el recorder correcto:
     *    - FastPayment → FastPaymentRecorder
     *    - OrderPayment → OrderPaymentRecorder
     * 3. Delega la grabación al recorder
     * 4. Retorna Result<PaymentReceipt>
     *
     * **Nota sobre error handling:**
     * Los errores de red (timeout, 500, etc.) son manejados por los recorders.
     * Este UseCase solo se encarga de la selección del recorder correcto.
     *
     * En el futuro, este UseCase puede agregar:
     * - Retry logic (3 intentos con exponential backoff)
     * - Offline queue (guardar en Room DB si no hay red)
     * - Idempotency checks (evitar duplicados usando referenceNumber)
     *
     * @param context Contexto del pago (FastPayment o OrderPayment)
     * @param cardDetails Detalles de la tarjeta extraídos del Blumon SDK
     * @param authorizationNumber Código de autorización de Blumon
     * @param referenceNumber Número de referencia de Blumon (único por transacción)
     * @return Result con PaymentReceipt o Exception
     *
     * **Ejemplo:**
     * ```kotlin
     * val context = PaymentContext.FastPayment(
     *     venueId = "venue_123",
     *     staffId = "staff_456",
     *     amount = BigDecimal("50.00"),
     *     tip = BigDecimal("5.00")
     * )
     *
     * val cardDetails = CardDetails(
     *     maskedPan = "411111******1111",
     *     cardBrand = CardBrand.VISA,
     *     entryMode = CardEntryMode.CHIP,
     *     isInternational = false
     * )
     *
     * val result = recordPaymentUseCase(
     *     context = context,
     *     cardDetails = cardDetails,
     *     authorizationNumber = "502511",
     *     referenceNumber = "000000188231"
     * )
     *
     * result.onSuccess { receipt ->
     *     println("✅ Payment recorded: ${receipt.receiptUrl}")
     * }.onFailure { error ->
     *     println("❌ Error: ${error.message}")
     * }
     * ```
     */
    suspend operator fun invoke(
        context: PaymentContext,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<PaymentReceipt> {
        // Seleccionar recorder basado en contexto (exhaustive when)
        val recorder = when (context) {
            is PaymentContext.FastPayment -> {
                Timber.d("📍 Using FastPaymentRecorder for fast payment")
                fastPaymentRecorder
            }

            is PaymentContext.OrderPayment -> {
                Timber.d("📍 Using OrderPaymentRecorder for order payment (orderId=${context.orderId})")
                orderPaymentRecorder
            }

            is PaymentContext.AngelPayPayment -> {
                // 🔶 ANGELPAY: Route based on whether it's a fast or order payment
                if (context.orderId != null) {
                    Timber.d("📍 Using OrderPaymentRecorder for AngelPay order payment (orderId=${context.orderId})")
                    orderPaymentRecorder
                } else {
                    Timber.d("📍 Using FastPaymentRecorder for AngelPay fast payment")
                    fastPaymentRecorder
                }
            }

            is PaymentContext.RefundPayment -> {
                // 🔐 REFUNDS: Use dedicated RecordRefundUseCase instead
                // Refunds follow a different flow:
                // 1. SDK processes TransType.REFUND
                // 2. RecordRefundUseCase records to backend
                // This UseCase is for SALES only
                Timber.e("❌ RefundPayment should use RecordRefundUseCase, not RecordPaymentUseCase")
                return Result.failure(
                    IllegalStateException(
                        "Refunds must be recorded via RecordRefundUseCase. " +
                        "Use PaymentViewModel's processRefund() method instead."
                    )
                )
            }
        }

        // 🔄 Delegar con retry automático
        return recordPaymentWithRetry(
            recorder = recorder,
            context = context,
            cardDetails = cardDetails,
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,
        )
    }

    /**
     * Intenta grabar el pago con retry automático en caso de errores transitorios.
     *
     * **Estrategia de retry:**
     * - Máximo 5 intentos
     * - Exponential backoff: 500ms → 1s → 2s → 4s
     * - Solo reintenta errores transitorios (5xx, network, timeout)
     * - NO reintenta errores permanentes (401, 403, 404, 429)
     *
     * **Errores con retry (transitorios):**
     * - 500-599 (server errors)
     * - Timeout / Network errors
     * - Connection errors
     *
     * **Errores sin retry (permanentes):**
     * - 401 (Unauthorized - token expirado)
     * - 403 (Forbidden - sin permisos)
     * - 404 (Not Found - venue no existe)
     * - 429 (Rate Limit - demasiadas requests)
     *
     * **Ejemplo de log:**
     * ```
     * 📡 Recording payment (attempt 1/5)
     * ⚠️ Retriable error (attempt 1/5): Error del servidor (502)
     * 🔄 Retry attempt 2/5 in 500ms...
     * 📡 Recording payment (attempt 2/5)
     * ✅ Payment recorded successfully after 2 attempts
     * ```
     */
    private suspend fun recordPaymentWithRetry(
        recorder: PaymentRecorder,
        context: PaymentContext,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<PaymentReceipt> {
        var lastError: Exception? = null

        repeat(MAX_RETRIES) { attemptIndex ->
            val attemptNumber = attemptIndex + 1

            // Delay exponencial ANTES del intento (excepto el primero)
            if (attemptIndex > 0) {
                val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl (attemptIndex - 1)) // 2^(n-1)
                Timber.w("🔄 Retry attempt $attemptNumber/$MAX_RETRIES in ${delayMs}ms...")
                delay(delayMs)
            }

            Timber.d("📡 Recording payment (attempt $attemptNumber/$MAX_RETRIES)")

            // Intentar grabar el pago
            val result = recorder.recordPayment(
                context = context,
                cardDetails = cardDetails,
                authorizationNumber = authorizationNumber,
                referenceNumber = referenceNumber,
            )

            // ✅ Si fue exitoso, retornar inmediatamente
            if (result.isSuccess) {
                if (attemptIndex > 0) {
                    Timber.i("✅ Payment recorded successfully after $attemptNumber attempts")
                }
                return result
            }

            // ❌ Analizar el error
            val exception = result.exceptionOrNull()
            lastError = exception as? Exception

            // Determinar si es un error retriable
            val isRetriable = isRetriableError(exception)

            if (!isRetriable) {
                Timber.w("⚠️ Non-retriable error, stopping retries: ${exception?.message}")
                return result
            }

            Timber.w("⚠️ Retriable error (attempt $attemptNumber/$MAX_RETRIES): ${exception?.message}")
        }

        // Si llegamos aquí, agotamos todos los intentos
        Timber.e("❌ Payment recording failed after $MAX_RETRIES attempts")
        return Result.failure(
            lastError ?: Exception("Error registrando el pago después de $MAX_RETRIES intentos")
        )
    }

    /**
     * Determina si un error es retriable (transitorio) o no.
     *
     * **Retriable (transitorios):**
     * - Server errors (500-599) - backend temporalmente caído
     * - Network/timeout errors - problemas de conectividad
     * - Connection errors
     *
     * **No retriable (permanentes):**
     * - 401 Unauthorized - token expirado, requiere re-login
     * - 403 Forbidden - sin permisos, requiere cambio de rol
     * - 404 Not Found - recurso no existe, requiere corrección
     * - 429 Rate Limit - demasiadas requests, requiere esperar más tiempo
     *
     * @param exception El error a analizar
     * @return true si el error es retriable, false si es permanente
     */
    private fun isRetriableError(exception: Throwable?): Boolean {
        if (exception == null) return false

        val message = exception.message ?: ""

        return when {
            // ✅ Server errors (5xx) - RETRIABLE
            message.contains("Error del servidor", ignoreCase = true) -> true
            message.contains(Regex("5\\d{2}")) -> true // Match 500, 502, 503, etc.

            // ✅ Network/timeout errors - RETRIABLE
            message.contains("conexión", ignoreCase = true) -> true
            message.contains("timeout", ignoreCase = true) -> true
            message.contains("network", ignoreCase = true) -> true
            message.contains("Verifica tu conexión", ignoreCase = true) -> true

            // ❌ Auth/Permission errors - NO RETRIABLE
            message.contains("401", ignoreCase = false) -> false
            message.contains("Unauthorized", ignoreCase = true) -> false
            message.contains("Token", ignoreCase = true) && message.contains("expirado", ignoreCase = true) -> false

            message.contains("403", ignoreCase = false) -> false
            message.contains("Forbidden", ignoreCase = true) -> false
            message.contains("permisos", ignoreCase = true) -> false

            message.contains("404", ignoreCase = false) -> false
            message.contains("Not Found", ignoreCase = true) -> false
            message.contains("no encontrado", ignoreCase = true) -> false

            message.contains("429", ignoreCase = false) -> false
            message.contains("Rate Limit", ignoreCase = true) -> false
            message.contains("Demasiadas solicitudes", ignoreCase = true) -> false

            // ✅ Default: retriable para cualquier otro error
            else -> true
        }
    }
}
