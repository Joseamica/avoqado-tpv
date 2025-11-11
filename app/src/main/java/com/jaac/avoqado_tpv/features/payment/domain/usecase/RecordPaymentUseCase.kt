package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
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
        }

        // Delegar al recorder apropiado
        return recorder.recordPayment(
            context = context,
            cardDetails = cardDetails,
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,
        )
    }
}
