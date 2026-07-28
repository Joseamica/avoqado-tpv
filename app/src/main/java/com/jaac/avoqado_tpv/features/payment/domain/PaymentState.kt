package com.jaac.avoqado_tpv.features.payment.domain

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentFlowOrigin

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
 * @param merchantAccountId Selected merchant account ID (backend CUID)
 * @param merchantLocalId Local merchant ID (for fallback merchants without CUID)
 * @param orderId Order ID (null = fast payment, non-null = order payment)
 * @param orderNumber Order number for display
 * @param splitType Split payment type (EQUALPARTS, PERPRODUCT, CUSTOMAMOUNT, FULLPAYMENT)
 * @param equalPartsPartySize Total people for EQUALPARTS mode
 * @param equalPartsPayedFor People already paid for in EQUALPARTS mode
 * @param paidProductIds Product IDs already paid for in PERPRODUCT mode
 * @param selectedMsiMonths Selected months without interest, null for direct payment
 */
data class RetryContext(
    val amount: String,
    val tipAmount: String,
    val rating: Int?,
    val merchantAccountId: String?,  // ✅ NULLABLE: null for cash payments
    val merchantLocalId: String? = null,  // 🆕 Fallback for merchants without backend CUID
    val flowOrigin: PaymentFlowOrigin = PaymentFlowOrigin.FAST,
    // 🆕 Order context fields (FIX: preserve order data for retry)
    val orderId: String? = null,
    val orderNumber: String? = null,
    val splitType: String? = null,
    val equalPartsPartySize: Int? = null,
    val equalPartsPayedFor: Int? = null,
    val paidProductIds: List<String>? = null,
    val selectedMsiMonths: Int? = null
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
     * ✅ FIXED: Removed merchantAccountId check (cash payments have null merchant)
     * @return true if amount > 0 (merchant can be null for cash)
     */
    fun isValid(): Boolean {
        return amount.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO } == true
        // ✅ Removed: merchantAccountId.isNotBlank() check (cash has null merchant)
    }

    /**
     * Check if this is an order payment (vs fast payment)
     */
    fun isOrderPayment(): Boolean = orderId != null
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

    // 🆕 PRE-payment verification state (Step 4: Verificación de Venta)
    /**
     * Verification state BEFORE payment processing.
     *
     * **Flow (NEW - PRE-Payment):**
     * CollectingTip → VerifyingPrePayment → SelectingMerchant → Payment
     *
     * **Purpose:**
     * Retail/telecomunicaciones venues capture evidence (photos + barcodes) BEFORE processing payment.
     * This ensures verification requirements are met BEFORE the transaction is authorized.
     *
     * **Skip Logic:**
     * - If BOTH requirePhoto=false AND requireBarcode=false → "Saltar" button visible
     * - If ANY requirement is mandatory → "Saltar" button hidden (user MUST complete)
     *
     * **UI:**
     * - Grid of captured photos (local paths)
     * - List of scanned products
     * - "Tomar Foto" / "Escanear Código" buttons
     * - "Continuar" (enabled when requirements met)
     * - "Saltar" (only visible if no mandatory requirements)
     * - Back button (returns to Tip/Rating screen)
     *
     * @param amount Original payment amount
     * @param rating User rating (null = skipped, 1-5 = rated)
     * @param tipAmount Tip amount ("0" if skipped)
     * @param capturedPhotos List of local file paths to captured photos
     * @param scannedBarcodes List of scanned product data
     * @param requirePhoto true if photo is mandatory (from TpvSettings)
     * @param requireBarcode true if barcode is mandatory (from TpvSettings)
     * @param isUploading true while uploading photos to Firebase (future)
     * @param error Error message if capture/upload failed
     */
    data class VerifyingPrePayment(
        val amount: String,
        val rating: Int?,
        val tipAmount: String,
        val photos: List<VerificationPhoto> = emptyList(),
        val scannedBarcodes: List<ScannedProduct> = emptyList(),
        val requirePhoto: Boolean = false,
        val requireBarcode: Boolean = false,
        val error: String? = null,
        /**
         * Pre-generated order reference for consistent naming.
         *
         * For fast payments (no existing order): "FAST-{timestamp}" generated ONCE when entering this state.
         * For order payments: Uses the existing order number (e.g., "ORDER-12345").
         *
         * This ensures photos uploaded to Firebase match the orderNumber created in backend.
         * Without this, photos would have different timestamps than the final order.
         */
        val orderReference: String? = null
    ) : PaymentState() {
        /**
         * Get local paths for UI display (backward compatibility).
         */
        val capturedPhotos: List<String>
            get() = photos.map { it.localPath }

        /**
         * Get successfully uploaded Firebase URLs.
         */
        val uploadedPhotoUrls: List<String>
            get() = photos.mapNotNull { it.firebaseUrl }

        /**
         * Check if any photo is currently uploading.
         */
        val isUploading: Boolean
            get() = photos.any { it.isUploading() }

        /**
         * Check if all photos are successfully uploaded.
         */
        val allPhotosUploaded: Boolean
            get() = photos.isNotEmpty() && photos.all { it.isUploaded() }

        /**
         * Check if any photo has upload error.
         */
        val hasUploadError: Boolean
            get() = photos.any { it.hasError() }

        /**
         * Get overall upload progress (0.0 to 1.0).
         */
        val overallUploadProgress: Float
            get() {
                if (photos.isEmpty()) return 0f
                val uploaded = photos.count { it.isUploaded() }
                val uploading = photos.find { it.isUploading() }?.uploadProgress ?: 0f
                return (uploaded + uploading) / photos.size
            }

        /**
         * Check if user can skip verification.
         * @return true if NO mandatory requirements (both requirePhoto=false AND requireBarcode=false)
         */
        fun canSkip(): Boolean = !requirePhoto && !requireBarcode

        /**
         * Check if user can proceed to payment.
         * Requirements must be met AND all photos must be uploaded.
         * @return true if all mandatory requirements are satisfied AND uploads complete
         */
        fun canProceed(): Boolean {
            val photoMet = !requirePhoto || photos.isNotEmpty()
            val barcodeMet = !requireBarcode || scannedBarcodes.isNotEmpty()
            val uploadsComplete = photos.isEmpty() || allPhotosUploaded
            val noErrors = !hasUploadError
            return photoMet && barcodeMet && uploadsComplete && noErrors
        }
    }

    data class SelectingMerchant(
        val subtotal: String,      // Original amount
        val tipAmount: String,     // Calculated tip
        val totalAmount: String,   // subtotal + tip
        val rating: Int?           // null = skipped, 1-5 = rated
    ) : PaymentState()

    // ==========================================
    // CRYPTO PAYMENT STATES (B4Bit Integration)
    // ==========================================

    /**
     * 🔗 CRYPTO: Generating QR code from B4Bit.
     *
     * **Flow:**
     * SelectingMerchant → [User taps "Cripto"] → GeneratingCryptoQR → AwaitingCryptoPayment
     *
     * **Purpose:**
     * Displays loading spinner while calling backend `/crypto/initiate` endpoint.
     * Backend calls B4Bit API to create payment order and returns payment URL.
     *
     * @param subtotal Original payment amount
     * @param tipAmount Tip amount
     * @param totalAmount Total (subtotal + tip)
     * @param rating User rating (null = skipped)
     */
    data class GeneratingCryptoQR(
        val subtotal: String,
        val tipAmount: String,
        val totalAmount: String,
        val rating: Int?
    ) : PaymentState()

    /**
     * 🪙 CRYPTO: Awaiting customer payment via QR code.
     *
     * **Flow:**
     * GeneratingCryptoQR → AwaitingCryptoPayment → Success (via Socket.IO event)
     *                                            → Error (on timeout/failure)
     *
     * **Purpose:**
     * Displays QR code for customer to scan with their crypto wallet.
     * Listens for `crypto:payment_confirmed` Socket.IO event from backend.
     * Has countdown timer for order expiration.
     *
     * **UI:**
     * - Large QR code (generated from paymentUrl)
     * - Total amount in MXN
     * - Countdown timer ("Expira en 4:32")
     * - "Cancelar" button
     * - Optional: crypto address for manual copy
     *
     * @param requestId B4Bit request ID for tracking
     * @param paymentId Our internal payment ID
     * @param paymentUrl URL to encode in QR (customer scans this)
     * @param subtotal Original payment amount
     * @param tipAmount Tip amount
     * @param totalAmount Total (subtotal + tip)
     * @param rating User rating (null = skipped)
     * @param expiresAt ISO timestamp when order expires
     * @param expiresInSeconds Seconds until expiration (for countdown)
     * @param cryptoAddress Optional: wallet address for manual transfer
     * @param cryptoSymbol Optional: selected cryptocurrency (BTC, ETH, etc.)
     */
    data class AwaitingCryptoPayment(
        val requestId: String,
        val paymentId: String,
        val paymentUrl: String,
        val subtotal: String,
        val tipAmount: String,
        val totalAmount: String,
        val rating: Int?,
        val expiresAt: String,
        val expiresInSeconds: Int,
        val cryptoAddress: String? = null,
        val cryptoSymbol: String? = null
    ) : PaymentState()

    /**
     * 🥝 KIOSK CASH CONFIRMATION: Awaiting staff to confirm cash received.
     *
     * **Purpose (McDonald's/Cinépolis Pattern):**
     * In kiosk mode, cash payments are NOT recorded until a staff member
     * confirms they actually received the money. This prevents walk-away fraud
     * where customer selects cash, order is marked paid, but customer leaves
     * without paying.
     *
     * **Flow:**
     * 1. Customer selects "Efectivo" in kiosk
     * 2. System shows "Espera al empleado" screen (this state)
     * 3. Receipt auto-prints with amount owed
     * 4. Staff enters PIN to confirm cash received
     * 5. ONLY THEN payment is recorded to backend
     * 6. Success screen shown
     *
     * **Timeout:**
     * After 3 minutes without confirmation, order can be cancelled.
     *
     * @param subtotal Original payment amount
     * @param tipAmount Tip amount (usually 0 for kiosk)
     * @param totalAmount subtotal + tip
     * @param rating Customer rating (usually null for kiosk)
     * @param orderId Order ID (for order payment)
     * @param orderNumber Display order number
     * @param orderItems Items in the order (for receipt printing)
     */
    data class AwaitingCashConfirmation(
        val subtotal: String,
        val tipAmount: String,
        val totalAmount: String,
        val rating: Int?,
        val orderId: String?,
        val orderNumber: String?,
        val orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>? = null,
        val printerWarning: String? = null  // 🖨️ Warning message if printer has issues (no paper, etc.)
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
        val referenceNumber: String? = null,  // Reference number for receipt
        val orderId: String? = null,  // 🆕 Order ID (for loading order items in success screen)
        val orderNumber: String? = null,  // 🆕 Order number (for display)
        val orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>? = null,  // 🆕 Order items (for displaying itemized receipt)
        val remainingBalance: java.math.BigDecimal? = null,  // ⭐ NEW: Amount left to pay (for split payments - shows "Continuar pagando" button)
        val discountAmount: String? = null,  // 🆕 Discount applied to order (for receipt printing)
        val verificationCompleted: Boolean = false,  // 📸 Prevents verification loop after confirmation
        val isRefund: Boolean = false,  // 💸 Indicates this was a refund transaction (for UI display)
        /**
         * 🚨 MONEY-SAFETY (2026-07): non-null ONLY when a Blumon card charge succeeded but
         * NEITHER the backend recording NOR the local offline queue could capture it — the
         * charge is real money moved with genuinely no record anywhere except this screen.
         *
         * Deliberately a flag ON [Success], not a new [PaymentState] subtype: Success is
         * published optimistically the instant the card is approved, BEFORE recording even
         * starts (see PaymentViewModel's handlePaymentSuccess), so by the time this failure
         * resolves the cashier is already looking at a Success screen with receipt/QR/print
         * wiring live. Swapping to a different sealed subtype would require every one of the
         * ~13 `PaymentState.Success(...)` sites and every `is/!is PaymentState.Success` check
         * across PaymentViewModel.kt and PaymentScreen.kt to learn about it. A nullable field
         * (default null — every existing site is unaffected) lets `.copy()` alarm the EXACT
         * live Success in place, the same pattern [applyRecordedReceiptToSuccessState] already
         * uses to attach a late-arriving receipt.
         *
         * Non-null renders an amber "don't recharge, reconcile via [referenceNumber]" banner
         * (visually distinct from a clean success, never red — the charge did NOT fail) and is
         * cleared back to null the moment a reconnect-triggered retry actually records the
         * payment (see `applyRecordedReceiptToSuccessState`'s `recordingLostMessage = null`).
         */
        val recordingLostMessage: String? = null,
        /**
         * 🟡 MONEY-SAFETY (2026-07): non-null ONLY when a Blumon card charge succeeded, backend
         * recording failed, but the LOCAL OFFLINE QUEUE captured it — the far more common sibling
         * of [recordingLostMessage] (that one requires BOTH to fail). The charge is safe: a queue
         * row exists and `PaymentSyncWorker` (or a reconnect retry) will record it automatically.
         *
         * Deliberately a flag on [Success], not a new [PaymentState] subtype — same reasoning as
         * [recordingLostMessage]: Success publishes optimistically the instant the card is
         * approved, seconds before backend recording is even attempted, so by the time the queue
         * outcome is known the cashier is already on a live Success screen with receipt/QR/print
         * wiring in place. `AngelPayPaymentViewModel` models this equivalent case as a distinct
         * `Queued` sealed state (see `AngelPayPaymentState.Queued`), which works there because its
         * state machine reaches `Success` and `Queued` as two DIFFERENT terminal transitions out of
         * `RecordingPayment` — Blumon has no such fork; only one `Success` is ever published, and
         * this outcome is learned strictly after the fact. A separate subtype here would require
         * every one of the ~13 `PaymentState.Success(...)` construction sites and every
         * `is/!is PaymentState.Success` check in `PaymentViewModel.kt` / `PaymentScreen.kt`
         * (including the money-window `state.collect` guard) to learn about it; a nullable field
         * lets `.copy()` attach the note to the EXACT live Success in place, same pattern already
         * proven for `recordingLostMessage` and `applyRecordedReceiptToSuccessState`'s late-arriving
         * receipt.
         *
         * Renders an amber "queued, don't recharge" note (never red — the charge did NOT fail) and
         * is cleared back to null the moment a reconnect-triggered retry actually records the
         * payment (`applyRecordedReceiptToSuccessState` clears it alongside `recordingLostMessage`).
         * If the cashier has already navigated away by the time `PaymentSyncWorker`'s background
         * sync succeeds, this flag is never live to clear — same accepted limitation as
         * `recordingLostMessage` (the worker has no reference back to this ViewModel/state).
         *
         * Mutually exclusive with [recordingLostMessage] in practice: they are set on the two
         * branches (`onSuccess`/`onFailure`) of the SAME queue-enqueue call in
         * `handleOfflineQueueOutcome` — never both non-null on the same instance.
         */
        val pendingSyncMessage: String? = null
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
     * **Shift Validation (NEW):**
     * When no shift is open, showOpenShiftButton = true displays "Abrir Turno" button
     * This enforces Square/Toast pattern of requiring shift for cash reconciliation
     *
     * @param message User-friendly error message (translated from SDK errors)
     * @param context Preserved payment data (amount, tip, rating, merchant)
     * @param canRetry true if user can retry with same context
     * @param showOpenShiftButton true if error is "no shift open" - shows "Abrir Turno" button
     * @param showCashFallback true if error should offer "Cobrar en Efectivo" fallback action
     */
    data class Error(
        val message: String,
        val context: RetryContext? = null,  // Preserved context for smart retry
        val canRetry: Boolean = true,
        val showOpenShiftButton: Boolean = false,  // ⭐ NEW: Show "Abrir Turno" button for shift validation errors
        val showCashFallback: Boolean = false
    ) : PaymentState()
    data object Cancelled : PaymentState()

    // 🆕 NEW: Receipt printing states
    data object Printing : PaymentState()
    data class PrintError(
        val message: String,
        val previousState: Success  // Return to success state after error
    ) : PaymentState()

    // 📸 Step 4: Sale Verification (for retail/telecomunicaciones venues)
    /**
     * Verification state after successful payment.
     *
     * **Flow (Step 4):**
     * Success → Verifying → Final (return to Idle or OrderScreen)
     *
     * **Purpose:**
     * Retail/telecomunicaciones venues capture evidence (photos + barcodes) to:
     * - Prove sale occurred (photo of purchased items)
     * - Link specific products to payment (barcode scan)
     * - Enable automatic inventory deduction
     *
     * **UI:**
     * - Grid of captured photos (local paths)
     * - List of scanned products
     * - "Tomar Foto" / "Escanear Código" buttons
     * - "Confirmar" (uploads to Firebase + syncs to backend)
     * - "Saltar" (skip verification)
     *
     * @param paymentId Backend payment ID (for linking verification)
     * @param amount Payment amount for display
     * @param orderId Optional order ID (null for fast payments)
     * @param orderNumber Optional order number for display
     * @param capturedPhotos List of local file paths to captured photos
     * @param scannedBarcodes List of scanned product data
     * @param isUploading true while uploading photos to Firebase
     * @param uploadProgress Upload progress (0.0 to 1.0)
     * @param error Error message if upload/sync failed
     */
    data class Verifying(
        val paymentId: String,
        val amount: String,
        val orderId: String? = null,
        val orderNumber: String? = null,
        val capturedPhotos: List<String> = emptyList(),  // Local file paths
        val scannedBarcodes: List<ScannedProduct> = emptyList(),
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val error: String? = null,
        val receipt: PaymentReceipt? = null  // 🆕 Preserve receipt for QR code after verification
    ) : PaymentState()
}

/**
 * Scanned product data from barcode.
 *
 * **ML Kit Barcode Scan Result:**
 * When user scans a barcode, we attempt to look up the product in local cache.
 * If found, we populate productName/productId/hasInventory.
 * If not found, we still capture the barcode for backend resolution.
 *
 * @param barcode Raw barcode value (EAN-13, UPC-A, QR, etc.)
 * @param format Barcode format (e.g., "EAN_13", "UPC_A", "QR_CODE")
 * @param productName Product name if found in local cache (null if unknown)
 * @param productId Backend product ID if found (null if unknown)
 * @param hasInventory true if product has trackInventory=true (for auto-deduction)
 * @param quantity Number of units scanned (default 1, can increment if same barcode scanned multiple times)
 */
data class ScannedProduct(
    val barcode: String,
    val format: String = "UNKNOWN",
    val productName: String? = null,
    val productId: String? = null,
    val hasInventory: Boolean = false,
    val quantity: Int = 1
)

/**
 * Verification photo with upload tracking.
 *
 * **Upload Flow:**
 * 1. Photo captured → status = PENDING, localPath set
 * 2. Upload starts → status = UPLOADING, uploadProgress updating
 * 3. Upload success → status = UPLOADED, firebaseUrl set
 * 4. Upload fails → status = ERROR, error message set
 *
 * @param localPath Local file path for preview display
 * @param status Current upload status
 * @param firebaseUrl Firebase Storage download URL (null until uploaded)
 * @param uploadProgress Upload progress 0.0 to 1.0 (only during UPLOADING)
 * @param error Error message if upload failed
 */
data class VerificationPhoto(
    val localPath: String,
    val status: PhotoUploadStatus = PhotoUploadStatus.PENDING,
    val firebaseUrl: String? = null,
    val uploadProgress: Float = 0f,
    val error: String? = null
) {
    /**
     * Check if photo is successfully uploaded.
     */
    fun isUploaded(): Boolean = status == PhotoUploadStatus.UPLOADED && firebaseUrl != null

    /**
     * Check if photo is currently uploading.
     */
    fun isUploading(): Boolean = status == PhotoUploadStatus.UPLOADING

    /**
     * Check if photo upload failed.
     */
    fun hasError(): Boolean = status == PhotoUploadStatus.ERROR
}

/**
 * Upload status for verification photos.
 */
enum class PhotoUploadStatus {
    /** Photo captured, waiting to upload */
    PENDING,
    /** Currently uploading to Firebase Storage */
    UPLOADING,
    /** Successfully uploaded, URL available */
    UPLOADED,
    /** Upload failed, can retry */
    ERROR
}
