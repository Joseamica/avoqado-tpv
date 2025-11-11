package com.jaac.avoqado_tpv.features.payment.domain.repository

import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt

/**
 * Interface unificada para grabar pagos en el backend.
 *
 * **Strategy Pattern:**
 * Esta interface tiene múltiples implementaciones:
 * - FastPaymentRecorder: POST /tpv/venues/{venueId}/fast
 * - OrderPaymentRecorder: POST /tpv/venues/{venueId}/orders/{orderId}
 *
 * **Por qué esta abstracción:**
 * 1. PaymentViewModel NO sabe si está procesando fast payment u order payment
 * 2. RecordPaymentUseCase selecciona el recorder correcto automáticamente
 * 3. Fácil agregar nuevos tipos de pago (refund, split, etc.)
 * 4. Testeable: Mock de recorder para unit tests
 *
 * **Flujo:**
 * ```
 * PaymentViewModel
 *   ↓
 * RecordPaymentUseCase (selecciona recorder)
 *   ↓
 * PaymentRecorder.recordPayment() ← Esta interface
 *   ↓
 * Backend API (Retrofit)
 * ```
 *
 * **Implementaciones:**
 * - data/repository/FastPaymentRecorder.kt (implementada)
 * - data/repository/OrderPaymentRecorder.kt (stub para futuro)
 */
interface PaymentRecorder {
    /**
     * Graba el pago en el backend y genera recibo digital.
     *
     * **Responsabilidades:**
     * 1. Convertir domain models a DTOs (amount BigDecimal → cents Int)
     * 2. Llamar endpoint apropiado (fast vs order)
     * 3. Mapear response DTO a domain model (PaymentReceipt)
     * 4. Manejar errores de red (retry, timeout)
     *
     * **Flow:**
     * 1. Validar contexto (FastPayment vs OrderPayment)
     * 2. Construir request DTO con datos de Blumon SDK
     * 3. POST al backend (Retrofit)
     * 4. Backend crea Payment + DigitalReceipt
     * 5. Retornar PaymentReceipt o error
     *
     * **Error Handling:**
     * - NetworkException: Problema de conexión (retry)
     * - HttpException: 4xx/5xx del backend (no retry)
     * - Timeout: 30s timeout (retry 1 vez)
     *
     * @param context Contexto del pago (FastPayment o OrderPayment)
     * @param cardDetails Detalles extraídos del Blumon SDK (maskedPan, brand, etc.)
     * @param authorizationNumber Código de autorización de Blumon (ej. "502511")
     * @param referenceNumber Número de referencia de Blumon (ej. "000000188231")
     * @return Result con PaymentReceipt o Exception
     *
     * @throws IllegalArgumentException Si el contexto no es compatible con este recorder
     *
     * **Ejemplo:**
     * ```kotlin
     * val context = PaymentContext.FastPayment(venueId, staffId, amount, tip)
     * val result = recorder.recordPayment(context, cardDetails, authCode, ref)
     *
     * result.onSuccess { receipt ->
     *     println("Receipt URL: ${receipt.receiptUrl}")
     * }.onFailure { error ->
     *     println("Error: ${error.message}")
     * }
     * ```
     */
    suspend fun recordPayment(
        context: PaymentContext,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<PaymentReceipt>
}
