package com.jaac.avoqado_tpv.features.payment.domain

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt

/**
 * Immutable retry context - preserves all transaction data for smart retry.
 *
 * **Philosophy (Toast/Square/Stripe Pattern):**
 * When a payment fails, the user should NEVER lose their entered data.
 * Professional POS systems preserve amount, tip, rating, and merchant selection.
 *
 * **Used for:**
 * - Smart retry (go back to DetectingCard, not EnteringAmount)
 * - Error recovery (preserve context when card times out)
 * - State restoration (maintain flow continuity)
 *
 * **NOTE:** This is different from `PaymentContext` in domain/model which represents
 * the full payment request context (venueId, staffId, orderId). `RetryContext` only
 * preserves user-entered form data for retry purposes.
 *
 * @param amount Payment amount in decimal format (e.g., "500.00")
 * @param tipAmount Tip amount in decimal format (e.g., "50.00", "0" if no tip)
 * @param rating User rating (1-5 stars, null if skipped)
 * @param merchantAccountId Selected merchant account ID
 */
data class RetryContext(
    val amount: String,
    val tipAmount: String,
    val rating: Int?,
    val merchantAccountId: String
) {
    /**
     * Calculate total amount (amount + tip).
     * @return Total as BigDecimal for precise calculation
     */
    fun calculateTotal(): java.math.BigDecimal {
        val amountValue = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val tipValue = tipAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        return amountValue + tipValue
    }

    /**
     * Check if context is valid for payment processing.
     * @return true if amount > 0 and merchant is selected
     */
    fun isValid(): Boolean {
        return amount.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true &&
               merchantAccountId.isNotBlank()
    }
}

/**
 * Payment flow states
 *
 * **New Flow (Pre-Payment Steps):**
 * 1. EnteringAmount → User inputs amount
 * 2. CollectingRating → User rates experience (optional, can skip)
 * 3. CollectingTip → User adds tip (optional, can skip)
 * 4. SelectingMerchant → User selects merchant account
 *
 * **Payment Processing (Existing Flow):**
 * 5. ConfiguringKernel → Blumon SDK PreTrans
 * 6. DetectingCard → Waiting for card
 * 7. Processing → EMV transaction
 * 8. Success/Error/Cancelled → Final states
 *
 * **Error Handling Philosophy:**
 * Error state preserves PaymentContext to enable smart retry.
 * User should NEVER have to re-enter amount/tip/rating after card error.
 */
sealed class PaymentState {
    // Pre-payment states (NEW - Rating/Tip flow)
    data class EnteringAmount(
        val amount: String = ""
    ) : PaymentState()

    data class CollectingRating(
        val amount: String,
        val rating: Int = 0  // 0 = not rated, 1-5 = stars
    ) : PaymentState()

    data class CollectingTip(
        val amount: String,
        val rating: Int?,  // null = skipped, 1-5 = rated
        val selectedTipPercentage: Int? = null,  // 10, 15, 20
        val tipAmount: String = "0"
    ) : PaymentState()

    data class SelectingMerchant(
        val subtotal: String,      // Original amount
        val tipAmount: String,     // Calculated tip
        val totalAmount: String,   // subtotal + tip
        val rating: Int?           // null = skipped, 1-5 = rated
    ) : PaymentState()

    // Legacy: Kept for backward compatibility (redirects to EnteringAmount)
    data object Idle : PaymentState()

    // Payment processing states (EXISTING - No changes)
    data object ConfiguringKernel : PaymentState()
    data class DetectingCard(val amount: String) : PaymentState()
    data class Processing(val message: String = "Procesando...") : PaymentState()
    data class Success(
        val authCode: String,
        val amount: String,
        val tipAmount: String? = null,  // NEW: Include tip in success
        val rating: Int? = null,        // NEW: Include rating in success
        val receipt: PaymentReceipt? = null,  // 🆕 NEW: Digital receipt with QR code URL
        val cardDetails: com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails? = null,  // Card info for receipt printing
        val referenceNumber: String? = null  // Reference number for receipt
    ) : PaymentState()
    /**
     * Payment error with preserved context for smart retry.
     *
     * **Philosophy (Toast/Square pattern):**
     * When payment fails (card timeout, declined, etc.), preserve user's entered data.
     * On retry, restore amount/tip/rating/merchant and go directly to DetectingCard.
     *
     * **Example:**
     * User enters $50, 10% tip, 5-star rating → Card times out
     * Error state preserves RetryContext($50, $5, 5★, merchant_id)
     * User taps "Reintentar" → Goes back to DetectingCard (NOT EnteringAmount)
     *
     * @param message User-friendly error message (translated from SDK errors)
     * @param context Preserved payment data (amount, tip, rating, merchant)
     * @param canRetry true if user can retry with same context
     */
    data class Error(
        val message: String,
        val context: RetryContext? = null,  // Preserved context for smart retry
        val canRetry: Boolean = true
    ) : PaymentState()
    data object Cancelled : PaymentState()

    // 🆕 NEW: Receipt printing states
    data object Printing : PaymentState()
    data class PrintError(
        val message: String,
        val previousState: Success  // Return to success state after error
    ) : PaymentState()
}
