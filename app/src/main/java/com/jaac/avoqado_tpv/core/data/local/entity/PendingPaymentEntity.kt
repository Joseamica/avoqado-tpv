package com.jaac.avoqado_tpv.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for queuing failed payment recordings.
 *
 * **Purpose:** Store payments that succeeded with Blumon SDK but failed to record to backend.
 * PaymentSyncWorker will retry these periodically until successful.
 *
 * **Lifecycle:**
 * 1. Payment approved by Blumon → Backend recording fails → Saved to Room DB
 * 2. PaymentSyncWorker fetches pending payments every 15 minutes
 * 3. Retry with exponential backoff (3 attempts max)
 * 4. On success → Mark as SUCCESS, delete after 7 days
 * 5. On max retries → Mark as FAILED for manual review
 *
 * **Idempotency:** referenceNumber from Blumon SDK is unique, prevents duplicates
 */
@Entity(
    tableName = "pending_payments",
    indices = [
        Index(value = ["reference_number"], unique = true), // Prevent duplicate queue entries
        Index(value = ["sync_status"]), // Fast filtering for pending payments
        Index(value = ["created_at"]) // FIFO processing order
    ]
)
data class PendingPaymentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ⭐ IDEMPOTENCY KEY: Blumon referenceNumber (e.g., "000000188231")
    // Backend uses this to detect duplicate payments during retry
    @ColumnInfo(name = "reference_number")
    val referenceNumber: String,

    // Payment Context (from PaymentContext.FastPayment)
    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "staff_id")
    val staffId: String,

    @ColumnInfo(name = "amount")
    val amount: String, // BigDecimal stored as String (e.g., "50.00")

    @ColumnInfo(name = "tip")
    val tip: String, // BigDecimal stored as String (e.g., "5.00")

    @ColumnInfo(name = "rating")
    val rating: Int?, // 🆕 Optional user rating (1-5 stars, null if skipped)

    // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING (2025-01-10)
    // 🆕 PRIMARY: Structured merchant account ID (FK to MerchantAccount)
    @ColumnInfo(name = "merchant_account_id")
    val merchantAccountId: String, // e.g., "cuid_abc123"

    // ⚠️ LEGACY: Blumon-specific serial (deprecated, kept for fallback)
    @ColumnInfo(name = "blumon_serial_number")
    val blumonSerialNumber: String, // e.g., "2841548417" or "9876543210"

    // ⭐ Device Serial Number for Terminal attribution (2026-01-08)
    @ColumnInfo(name = "device_serial_number")
    val deviceSerialNumber: String? = null, // e.g., "AVQD-2841548417"

    // Card Details (from CardDetails domain model)
    @ColumnInfo(name = "masked_pan")
    val maskedPan: String?, // e.g., "411111******1111"

    @ColumnInfo(name = "card_brand")
    val cardBrand: String?, // "VISA", "MASTERCARD", etc.

    @ColumnInfo(name = "entry_mode")
    val entryMode: String, // "CHIP", "CONTACTLESS", "SWIPE"

    @ColumnInfo(name = "is_international")
    val isInternational: Boolean,

    // Blumon Authorization Data
    @ColumnInfo(name = "authorization_number")
    val authorizationNumber: String?, // e.g., "502511"

    // Metadata for retry logic
    @ColumnInfo(name = "created_at")
    val createdAt: Long, // Unix timestamp (when payment was processed, not when queued)

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0, // Incremented on each failed retry

    @ColumnInfo(name = "last_error")
    val lastError: String? = null, // Last error message for debugging

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SYNC_STATUS_PENDING // PENDING, SYNCING, SUCCESS, FAILED
) {
    companion object {
        const val SYNC_STATUS_PENDING = "PENDING" // Waiting to sync
        const val SYNC_STATUS_SYNCING = "SYNCING" // Currently being synced
        const val SYNC_STATUS_SUCCESS = "SUCCESS" // Successfully synced (can be deleted)
        const val SYNC_STATUS_FAILED = "FAILED" // Failed after max retries (manual review needed)

        const val MAX_RETRY_ATTEMPTS = 10 // Max retry attempts before marking as FAILED
    }
}
