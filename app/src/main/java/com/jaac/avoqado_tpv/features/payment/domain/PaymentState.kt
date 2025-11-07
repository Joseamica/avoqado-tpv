package com.jaac.avoqado_tpv.features.payment.domain

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
    data object DetectingCard : PaymentState()
    data class Processing(val message: String = "Procesando...") : PaymentState()
    data class Success(
        val authCode: String,
        val amount: String,
        val tipAmount: String? = null,  // NEW: Include tip in success
        val rating: Int? = null         // NEW: Include rating in success
    ) : PaymentState()
    data class Error(val message: String, val canRetry: Boolean = true) : PaymentState()
    data object Cancelled : PaymentState()
}
