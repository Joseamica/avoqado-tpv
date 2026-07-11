package com.jaac.avoqado_tpv.features.payment.domain.model

import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import java.math.BigDecimal

/**
 * Domain model for queued payment (failed backend recording).
 *
 * **Purpose:** Represents a payment that succeeded with the payment SDK (Blumon TPV or
 * AngelPay — the money already moved) but failed to record to the Avoqado backend.
 *
 * **Lifecycle:**
 * 1. Created when the payment ViewModel fails to record to backend after retries
 * 2. Stored in Room DB via PaymentQueueRepository
 * 3. Retrieved by PaymentSyncWorker for retry
 * 4. Converted back to PaymentContext + CardDetails for RecordPaymentUseCase
 *
 * **Mapping:**
 * ```
 * QueuedPayment (Domain)  ←→  PendingPaymentEntity (Room DB)
 *     ↓
 * PaymentContext.FastPayment  (processor = BLUMON)
 * PaymentContext.AngelPayPayment (processor = ANGELPAY — preserves orderId + SIM metadata)
 * ```
 *
 * **World-Class Examples:**
 * - Square Terminal: OfflineTransaction domain model
 * - Toast POS: QueuedPayment with retry metadata
 * - Stripe Terminal: PendingPaymentRecord
 */
data class QueuedPayment(
    // Queue Metadata
    val queueId: Long = 0, // Room auto-generated ID (0 for new entries)

    // Idempotency Key (Blumon referenceNumber)
    val referenceNumber: String,

    // Payment Context (FastPayment)
    val venueId: String,
    val staffId: String,
    val amount: BigDecimal, // Pesos (e.g., 50.00)
    val tip: BigDecimal, // Pesos (e.g., 5.00)
    val rating: Int?, // 🆕 Optional user rating (1-5 stars, null if skipped)

    // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING (2025-01-10)
    val merchantAccountId: String, // 🆕 PRIMARY: Merchant account ID (e.g., "cuid_abc123")
    val blumonSerialNumber: String, // ⚠️ LEGACY: Blumon serial (deprecated, kept for fallback)
    val deviceSerialNumber: String? = null, // ⭐ Terminal attribution (2026-01-08)

    // Card Details
    val maskedPan: String?,
    val cardBrand: String?,
    val entryMode: String, // "CHIP", "CONTACTLESS", "SWIPE"
    val isInternational: Boolean,

    // Blumon Authorization
    val authorizationNumber: String?,

    // 🛡️ IDEMPOTENCY KEY (2026-05-29) — carried from PaymentContext for queue retries
    val idempotencyKey: String? = null,

    // 🔶 PROCESSOR-AWARE QUEUE (2026-07-09) — which PaymentContext shape to rebuild
    // on replay. Pre-v25 rows are BLUMON (the queue was Blumon-fast-only until then).
    val processor: ProcessorType = ProcessorType.BLUMON,

    // Order linkage (AngelPay order payments — null for fast payments)
    val orderId: String? = null,
    val orderNumber: String? = null,
    val shiftId: String? = null,

    // 📸 Serialized inventory (SIM) proof-of-sale metadata (AngelPay)
    val isPortabilidad: Boolean = false,
    val serialNumbers: List<String> = emptyList(),

    // Retry Tracking
    val createdAt: Long, // Unix timestamp (when payment was originally processed)
    val retryCount: Int = 0,
    val lastError: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
) {
    /**
     * Cash payments are identified by generated references/auth markers.
     *
     * - CASH-* references come from cash fallback and kiosk cash confirmation flows.
     * - EFECTIVO* authorization markers are used for cash backend recording.
     */
    private fun isCashQueuedPayment(): Boolean {
        val auth = authorizationNumber.orEmpty()
        return referenceNumber.startsWith("CASH-", ignoreCase = true) ||
            auth.startsWith("EFECTIVO", ignoreCase = true)
    }

    /**
     * Convert to the PaymentContext shape this payment was originally recorded with.
     *
     * **Use Case:** PaymentSyncWorker calls RecordPaymentUseCase with this context.
     * ANGELPAY rows rebuild [PaymentContext.AngelPayPayment] so RecordPaymentUseCase
     * routes them to the SAME recorder as the online path (order vs fast, processor
     * tag, SIM proof-of-sale metadata). Everything else keeps the legacy
     * [PaymentContext.FastPayment] shape.
     */
    fun toPaymentContext(): PaymentContext {
        val normalizedMerchantAccountId = if (isCashQueuedPayment()) null else merchantAccountId

        if (processor == ProcessorType.ANGELPAY) {
            return PaymentContext.AngelPayPayment(
                venueId = venueId,
                staffId = staffId,
                shiftId = shiftId,
                amount = amount,
                tip = tip,
                rating = rating,
                merchantAccountId = normalizedMerchantAccountId?.ifBlank { null },
                deviceSerialNumber = deviceSerialNumber,
                idempotencyKey = idempotencyKey,
                cardDetails = toCardDetails(),
                authorizationCode = authorizationNumber ?: "",
                referenceNumber = referenceNumber,
                orderId = orderId,
                orderNumber = orderNumber,
                isPortabilidad = isPortabilidad,
                serialNumbers = serialNumbers,
            )
        }

        return PaymentContext.FastPayment(
            venueId = venueId,
            staffId = staffId,
            amount = amount,
            tip = tip,
            rating = rating, // 🆕 Preserve user rating for retry
            merchantAccountId = normalizedMerchantAccountId, // Cash queue retries must keep merchant null
            blumonSerialNumber = blumonSerialNumber, // ⚠️ LEGACY: Fallback for old records
            deviceSerialNumber = deviceSerialNumber, // ⭐ Terminal attribution (2026-01-08)
            idempotencyKey = idempotencyKey // 🛡️ Primary dedup key for queue retries (2026-05-29)
        )
    }

    /**
     * Convert to CardDetails for retry.
     *
     * **Use Case:** PaymentSyncWorker calls RecordPaymentUseCase with these card details
     */
    fun toCardDetails(): CardDetails {
        if (isCashQueuedPayment()) {
            return CardDetails.CASH
        }

        return CardDetails(
            maskedPan = maskedPan ?: "************",
            cardBrand = cardBrand?.let { brand ->
                when (brand.uppercase()) {
                    "VISA" -> CardBrand.VISA
                    "MASTERCARD" -> CardBrand.MASTERCARD
                    "AMEX", "AMERICAN_EXPRESS" -> CardBrand.AMEX
                    "DISCOVER" -> CardBrand.DISCOVER
                    "DINERS", "DINERS_CLUB" -> CardBrand.DINERS
                    "JCB" -> CardBrand.JCB
                    else -> CardBrand.UNKNOWN
                }
            } ?: CardBrand.UNKNOWN,
            entryMode = when (entryMode.uppercase()) {
                "CHIP" -> CardEntryMode.CHIP
                "CONTACTLESS" -> CardEntryMode.CONTACTLESS
                "SWIPE" -> CardEntryMode.SWIPE
                "MANUAL" -> CardEntryMode.MANUAL
                // AngelPay doesn't expose the entry mode — preserve OTHER instead of
                // inventing CHIP on replay (2026-07-09).
                "OTHER" -> CardEntryMode.OTHER
                else -> CardEntryMode.CHIP
            },
            isInternational = isInternational
        )
    }
}

/**
 * Sync status enum for queued payments.
 */
enum class SyncStatus {
    PENDING,  // Waiting to sync
    SYNCING,  // Currently being synced by worker
    SUCCESS,  // Successfully synced (can be deleted)
    FAILED    // Failed after max retries (manual review needed)
}
