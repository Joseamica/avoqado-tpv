package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReceipt
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Use case que orquesta la grabación de reembolsos en el backend.
 *
 * **⚠️ MULTI-MERCHANT CRITICAL:**
 * This use case ensures refunds are recorded to the correct merchant account.
 * The merchantAccountId in RefundPayment context MUST match the original payment's
 * merchant to ensure proper settlement reconciliation.
 *
 * **Responsabilidades:**
 * 1. Validate RefundPayment context has required fields
 * 2. Validate refund amount is within bounds
 * 3. Delegate to RefundRecorder for API call
 * 4. (Future) Handle retry logic for transient failures
 * 5. (Future) Implement offline queue for connectivity issues
 *
 * **Flujo:**
 * ```
 * PaymentViewModel (TransType.REFUND success)
 *   ↓ calls
 * RecordRefundUseCase (validates & orchestrates)
 *   ↓ delegates
 * RefundRecorder (API call)
 *   ↓ calls
 * Backend API POST /tpv/venues/{venueId}/refunds
 * ```
 *
 * **Square POS Pattern:**
 * Following Square's refund flow where:
 * 1. User views payment in history
 * 2. Selects "Refund" (role-gated: only ADMIN+)
 * 3. Confirms amount and reason
 * 4. SDK processes TransType.REFUND
 * 5. This UseCase records to backend
 * 6. Receipt shown to user
 *
 * **Ejemplo de uso:**
 * ```kotlin
 * val context = PaymentContext.RefundPayment(
 *     venueId = "venue_123",
 *     staffId = "staff_456",
 *     amount = BigDecimal("50.00"),
 *     originalPaymentId = "payment_789",
 *     originalTotalAmount = BigDecimal("100.00"),
 *     merchantAccountId = "merchant_abc",
 *     blumonSerialNumber = "PAX123456",
 *     refundReason = RefundReason.CUSTOMER_REQUEST,
 *     isPartialRefund = true
 * )
 *
 * val result = recordRefundUseCase(
 *     context = context,
 *     cardDetails = cardDetails,
 *     authorizationNumber = "502511",
 *     referenceNumber = "000000188231"
 * )
 *
 * result.onSuccess { receipt ->
 *     println("✅ Refund recorded: ${receipt.refundId}")
 * }.onFailure { error ->
 *     println("❌ Error: ${error.message}")
 * }
 * ```
 */
class RecordRefundUseCase @Inject constructor(
    private val refundRecorder: RefundRecorder,
) {
    /**
     * Records a refund in the backend.
     *
     * **Validation Steps:**
     * 1. Validates context is RefundPayment
     * 2. Validates merchantAccountId is present (multi-merchant requirement)
     * 3. Validates refund amount is positive and <= original amount
     * 4. Delegates to RefundRecorder
     *
     * @param context RefundPayment context with original payment info
     * @param cardDetails Card details from Blumon SDK (for audit trail)
     * @param authorizationNumber Authorization code from Blumon SDK REFUND transaction
     * @param referenceNumber Reference number from Blumon SDK REFUND transaction
     * @return Result with RefundReceipt or Exception
     */
    suspend operator fun invoke(
        context: PaymentContext.RefundPayment,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<RefundReceipt> {
        // 1. Validate refund amount
        if (!context.isValidRefundAmount()) {
            Timber.e(
                "❌ Invalid refund amount: ${context.amount} (original: ${context.originalTotalAmount})"
            )
            return Result.failure(
                IllegalArgumentException(
                    "El monto de reembolso (${context.amount}) es inválido. " +
                    "Debe ser mayor a 0 y menor o igual al monto original (${context.originalTotalAmount})."
                )
            )
        }

        // 2. Validate merchantAccountId (multi-merchant critical)
        if (context.merchantAccountId.isNullOrBlank()) {
            Timber.e("❌ merchantAccountId is required for refund")
            return Result.failure(
                IllegalArgumentException(
                    "No se puede procesar el reembolso: información del comerciante no disponible. " +
                    "El pago original no tiene información de merchant account."
                )
            )
        }

        // 3. Validate blumonSerialNumber (required for SDK merchant switch)
        if (context.blumonSerialNumber.isBlank()) {
            Timber.e("❌ blumonSerialNumber is required for refund SDK initialization")
            return Result.failure(
                IllegalArgumentException(
                    "No se puede procesar el reembolso: número de serie del terminal no disponible. " +
                    "El pago original no tiene información del terminal."
                )
            )
        }

        // 4. Validate originalOperationNumber (CRITICAL for CancelIcc)
        // This is the small integer from Blumon webhook, NOT the SDK's large referenceNumber
        if (context.originalOperationNumber <= 0) {
            Timber.e("❌ originalOperationNumber is required for CancelIcc (value: ${context.originalOperationNumber})")
            return Result.failure(
                IllegalArgumentException(
                    "No se puede procesar el reembolso: número de operación Blumon no disponible. " +
                    "El webhook de Blumon aún no se ha recibido para este pago."
                )
            )
        }

        Timber.d(
            "📍 RecordRefundUseCase | originalPayment=${context.originalPaymentId} | " +
            "amount=${context.amount} | merchant=${context.merchantAccountId} | " +
            "serial=${context.blumonSerialNumber} | operationNumber=${context.originalOperationNumber} | reason=${context.refundReason}"
        )

        // 4. Delegate to RefundRecorder
        return refundRecorder.recordRefund(
            context = context,
            cardDetails = cardDetails,
            authorizationNumber = authorizationNumber,
            referenceNumber = referenceNumber,
        )
    }

    /**
     * Creates a RefundPayment context from Payment history item.
     *
     * **Use this helper when:**
     * User selects a payment from PaymentsScreen and wants to refund it.
     * This ensures all required fields are populated correctly.
     *
     * @param payment Payment from history (must have merchantAccountId for card payments)
     * @param currentStaffId ID of staff processing the refund
     * @param currentVenueId ID of current venue (for validation)
     * @param currentShiftId ID of current shift (for reconciliation)
     * @param refundReason Reason for the refund
     * @param refundAmount Amount to refund (defaults to full payment amount)
     * @return PaymentContext.RefundPayment or null if payment can't be refunded
     */
    companion object {
        /**
         * Validates if a payment can be refunded and creates the context.
         *
         * @param paymentId Payment ID
         * @param paymentAmount Original payment total amount
         * @param orderId Associated order ID (nullable for fast payments)
         * @param merchantAccountId Merchant account from original payment (required for card)
         * @param blumonSerialNumber Terminal serial from original payment (required)
         * @param blumonOperationNumber CRITICAL: Blumon operation number for CancelIcc (from webhook)
         * @param refundedAmount Amount already refunded (for partial refund tracking)
         * @param venueId Venue ID
         * @param staffId Staff ID processing the refund
         * @param shiftId Current shift ID
         * @param refundReason Reason for refund
         * @param refundAmount Amount to refund (defaults to remaining refundable)
         * @return RefundPayment context or null if cannot refund
         */
        fun createRefundContext(
            paymentId: String,
            paymentAmount: BigDecimal,
            orderId: String?,
            merchantAccountId: String?,
            blumonSerialNumber: String?,
            blumonOperationNumber: Int?, // 🎫 CRITICAL: Blumon operation number for CancelIcc
            refundedAmount: BigDecimal?,
            venueId: String,
            staffId: String,
            shiftId: String?,
            refundReason: com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason,
            refundAmount: BigDecimal? = null,
        ): PaymentContext.RefundPayment? {
            // Validate merchantAccountId is present
            if (merchantAccountId.isNullOrBlank()) {
                Timber.w("Cannot create refund context: merchantAccountId is missing")
                return null
            }

            // Validate blumonSerialNumber is present
            if (blumonSerialNumber.isNullOrBlank()) {
                Timber.w("Cannot create refund context: blumonSerialNumber is missing")
                return null
            }

            // 🎫 CRITICAL: Validate blumonOperationNumber is present for CancelIcc
            // This is the small integer from Blumon webhook, NOT the SDK's large referenceNumber
            // Without this, Blumon cannot identify which transaction to cancel/refund
            if (blumonOperationNumber == null || blumonOperationNumber <= 0) {
                Timber.w("Cannot create refund context: blumonOperationNumber is missing or invalid (required for CancelIcc)")
                return null
            }

            // Calculate refundable amount
            val alreadyRefunded = refundedAmount ?: BigDecimal.ZERO
            val remainingRefundable = paymentAmount - alreadyRefunded

            if (remainingRefundable <= BigDecimal.ZERO) {
                Timber.w("Cannot create refund context: payment already fully refunded")
                return null
            }

            // Determine actual refund amount
            val actualRefundAmount = refundAmount ?: remainingRefundable
            val isPartial = actualRefundAmount < remainingRefundable

            return PaymentContext.RefundPayment(
                venueId = venueId,
                staffId = staffId,
                shiftId = shiftId,
                amount = actualRefundAmount,
                tip = BigDecimal.ZERO, // Tip refund calculated separately if needed
                merchantAccountId = merchantAccountId,
                blumonSerialNumber = blumonSerialNumber,
                originalPaymentId = paymentId,
                originalOrderId = orderId,
                originalTotalAmount = paymentAmount,
                refundReason = refundReason,
                isPartialRefund = isPartial,
                originalOperationNumber = blumonOperationNumber, // 🎫 CRITICAL for CancelIcc
            )
        }
    }
}
