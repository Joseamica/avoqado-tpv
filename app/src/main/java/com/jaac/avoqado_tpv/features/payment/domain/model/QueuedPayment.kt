package com.jaac.avoqado_tpv.features.payment.domain.model

import java.math.BigDecimal

/**
 * Domain model for queued payment (failed backend recording).
 *
 * **Purpose:** Represents a payment that succeeded with Blumon SDK but failed to record to backend.
 *
 * **Lifecycle:**
 * 1. Created when PaymentViewModel.handlePaymentSuccess() fails to record to backend
 * 2. Stored in Room DB via PaymentQueueRepository
 * 3. Retrieved by PaymentSyncWorker for retry
 * 4. Converted back to PaymentContext + CardDetails for RecordPaymentUseCase
 *
 * **Mapping:**
 * ```
 * QueuedPayment (Domain)  ←→  PendingPaymentEntity (Room DB)
 *     ↓
 * PaymentContext.FastPayment + CardDetails (for retry)
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

    // Retry Tracking
    val createdAt: Long, // Unix timestamp (when payment was originally processed)
    val retryCount: Int = 0,
    val lastError: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
) {
    /**
     * Convert to PaymentContext.FastPayment for retry.
     *
     * **Use Case:** PaymentSyncWorker calls RecordPaymentUseCase with this context
     */
    fun toPaymentContext(): PaymentContext.FastPayment {
        return PaymentContext.FastPayment(
            venueId = venueId,
            staffId = staffId,
            amount = amount,
            tip = tip,
            rating = rating, // 🆕 Preserve user rating for retry
            merchantAccountId = merchantAccountId, // 🆕 PRIMARY: Preserve merchant account ID
            blumonSerialNumber = blumonSerialNumber, // ⚠️ LEGACY: Fallback for old records
            deviceSerialNumber = deviceSerialNumber // ⭐ Terminal attribution (2026-01-08)
        )
    }

    /**
     * Convert to CardDetails for retry.
     *
     * **Use Case:** PaymentSyncWorker calls RecordPaymentUseCase with these card details
     */
    fun toCardDetails(): CardDetails {
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
