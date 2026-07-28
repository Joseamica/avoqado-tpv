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
 * 2. PaymentSyncWorker fetches pending payments every 15 minutes (or on-demand: connectivity
 *    restored, or PaymentSyncScheduler.runNow())
 * 3. Each payment gets exactly ONE attempt per worker run — no in-worker retry loop or
 *    backoff; WorkManager owns the retry cadence across separate runs (F-9)
 * 4. On success → Mark as SUCCESS, delete after 7 days
 * 5. On a transient failure — including 401/403, almost always a stuck token refresh,
 *    not the payment itself (fix round 1) — after MAX_RETRY_ATTEMPTS (10) attempts spent
 *    across separate runs → Mark as FAILED
 * 6. On a permanent business failure (400/404/422 ONLY — see SyncOutcome.PERMANENT_HTTP_CODES)
 *    → Mark as FAILED immediately with `permanent = true`, so resetAllFailed() never
 *    resurrects it automatically (F-10); a deliberate human tap still can
 *    (resetAllFailedIncludingPermanent, fix round 1)
 *
 * **Idempotency:** referenceNumber from Blumon SDK is unique, prevents duplicates
 */
@Entity(
    tableName = "pending_payments",
    indices = [
        Index(value = ["reference_number"], unique = true), // Prevent duplicate queue entries
        Index(value = ["sync_status"]), // Fast filtering for pending payments
        Index(value = ["created_at"]), // FIFO processing order
        Index(value = ["claim_token"]) // claim/release del worker
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

    // 🛡️ IDEMPOTENCY KEY (2026-05-29) — primary dedup key for queue retries
    // Mirrors PaymentContext.idempotencyKey so PaymentSyncWorker sends the same UUID
    // the online path would have used, enabling backend @@unique([venueId, idempotencyKey]) dedup.
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String? = null,

    // 🔶 PROCESSOR-AWARE QUEUE (2026-07-09) — lets PaymentSyncWorker rebuild the exact
    // PaymentContext shape on replay. Rows written before v25 default to BLUMON
    // (the queue was Blumon-fast-payment-only until then).
    @ColumnInfo(name = "payment_processor", defaultValue = PROCESSOR_BLUMON)
    val paymentProcessor: String = PROCESSOR_BLUMON,

    // 📡 POS→TPV arbitration link (2026-07-14, v26) — the requestId of the socket-initiated
    // charge this payment belongs to. WITHOUT it on the queue, a replayed payment reaches the
    // backend with terminalPaymentRequestId=null, so closeRowFromPaymentTx never runs and the
    // TerminalPaymentRequest row is left for the watchdog — which is BLIND to fast payments
    // (it reconciles via row.orderId, null for them) → the row parks at UNKNOWN and holds the
    // per-terminal slot forever. Both failures share one cause (a network drop), so they are
    // correlated, not a rare conjunction. Null for device-initiated charges and for rows
    // written before v26.
    @ColumnInfo(name = "terminal_payment_request_id")
    val terminalPaymentRequestId: String? = null,

    // Order linkage (AngelPay order payments — null for fast payments)
    @ColumnInfo(name = "order_id")
    val orderId: String? = null,

    @ColumnInfo(name = "order_number")
    val orderNumber: String? = null,

    @ColumnInfo(name = "shift_id")
    val shiftId: String? = null,

    // 📸 Serialized inventory (SIM) proof-of-sale metadata — rides on the payment
    // record so the SaleVerification created at replay time still has the ICCID.
    @ColumnInfo(name = "is_portabilidad", defaultValue = "0")
    val isPortabilidad: Boolean = false,

    // CSV of serial numbers (ICCIDs are digit-only, comma-safe). Null/empty = none.
    @ColumnInfo(name = "serial_numbers")
    val serialNumbers: String? = null,

    // Metadata for retry logic
    @ColumnInfo(name = "created_at")
    val createdAt: Long, // Unix timestamp (when payment was processed, not when queued)

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0, // Incremented on each failed retry

    @ColumnInfo(name = "last_error")
    val lastError: String? = null, // Last error message for debugging

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SYNC_STATUS_PENDING, // PENDING, SYNCING, SUCCESS, FAILED

    /**
     * Token del worker que reclamó esta fila. NULL = libre.
     * Existe para que dos workers concurrentes no tomen el mismo pago y lo
     * registren dos veces. Ver spec §4.2 F-8.
     */
    @ColumnInfo(name = "claim_token")
    val claimToken: String? = null,

    /** Epoch ms del claim. Permite reclamar filas abandonadas por un worker muerto. */
    @ColumnInfo(name = "claimed_at")
    val claimedAt: Long? = null,

    /**
     * `true` cuando el fallo fue de negocio PERMANENTE — SOLO 400/404/422 (orden no
     * encontrada, payload mal formado, regla de negocio violada), nunca 401/403 (eso es
     * la SESIÓN, no el pago — ver `SyncOutcome.PERMANENT_HTTP_CODES`, fix round 1) ni
     * transitorio (red, 5xx). `resetAllFailed()` lo respeta: reintentar uno de estos 4xx
     * en cada reconexión no lo arregla, solo quema los 10 intentos de
     * `MAX_RETRY_ATTEMPTS` una y otra vez en vez de dejar que la fila se asiente en un
     * FAILED estable — hoy visible solo por el badge de `getFailedCount()`;
     * `getAllFailed()` no tiene ningún caller todavía, así que no hay pantalla de
     * revisión dedicada (ese es un hueco real, esto no lo resuelve). Lo marca
     * [PendingPaymentDao.markPermanentlyFailed]. Ver spec §4.2 F-10.
     *
     * Filas FAILED de antes de v29 no tienen forma de saber si fueron permanentes — quedan
     * en `false` (resucitables) por default: perder para siempre un pago que sí se podía
     * recuperar es peor que un ciclo de retry extra. Ver `AvoqadoDatabase.MIGRATION_28_29`.
     *
     * El operador tiene un escape hatch aparte de `resetAllFailed()`:
     * `PendingPaymentDao.resetAllFailedIncludingPermanent()`, disparado solo por un tap
     * manual (`DeviceHealthViewModel.retryFailedPayments`), nunca por el reconector
     * automático — fix round 1.
     */
    @ColumnInfo(name = "permanent", defaultValue = "0")
    val permanent: Boolean = false,
) {
    companion object {
        const val SYNC_STATUS_PENDING = "PENDING" // Waiting to sync
        const val SYNC_STATUS_SYNCING = "SYNCING" // Currently being synced
        const val SYNC_STATUS_SUCCESS = "SUCCESS" // Successfully synced (can be deleted)
        const val SYNC_STATUS_FAILED = "FAILED" // Failed after max retries (manual review needed)

        const val MAX_RETRY_ATTEMPTS = 10 // Max retry attempts before marking as FAILED

        // payment_processor values — mirrors ProcessorType enum names (v25, 2026-07-09)
        const val PROCESSOR_BLUMON = "BLUMON"
        const val PROCESSOR_ANGELPAY = "ANGELPAY"
    }
}
