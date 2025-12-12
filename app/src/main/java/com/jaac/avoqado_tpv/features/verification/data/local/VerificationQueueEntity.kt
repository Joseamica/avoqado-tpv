package com.jaac.avoqado_tpv.features.verification.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for offline verification queue.
 *
 * Stores sale verification data (photos, barcodes) for later sync when:
 * - Network is unavailable
 * - Firebase upload fails
 * - Backend API is unreachable
 *
 * **Sync Flow:**
 * 1. User captures photo/barcode → saved locally
 * 2. Attempt Firebase upload → update photoUrls on success
 * 3. Send to backend API → mark as SYNCED on success
 * 4. WorkManager retries failed items periodically
 *
 * @property id Local UUID for the verification record
 * @property venueId Venue where the sale occurred
 * @property staffId Promoter/staff who made the sale (from PIN login)
 * @property paymentId Associated payment ID from backend
 * @property orderId Associated order ID (if applicable)
 * @property photoLocalPaths JSON array of local file paths to captured photos
 * @property photoUrls JSON array of Firebase Storage URLs (populated after upload)
 * @property scannedBarcodes JSON array of scanned product barcodes
 * @property syncStatus Current sync status: PENDING, UPLOADING_PHOTOS, SYNCING, SYNCED, FAILED
 * @property createdAt Unix timestamp when verification was created
 * @property syncAttempts Number of sync attempts (for retry backoff)
 * @property lastSyncError Last error message if sync failed
 */
@Entity(
    tableName = "verification_queue",
    indices = [
        Index(value = ["venueId"]),
        Index(value = ["paymentId"]),
        Index(value = ["syncStatus"])
    ]
)
data class VerificationQueueEntity(
    @PrimaryKey
    val id: String,

    val venueId: String,
    val staffId: String,
    val paymentId: String,
    val orderId: String?,

    // Local photo paths (before Firebase upload)
    val photoLocalPaths: String,  // JSON array: ["file:///path/to/photo1.jpg", ...]

    // Firebase Storage URLs (after successful upload)
    val photoUrls: String,  // JSON array: ["https://storage.../photo1.jpg", ...]

    // Scanned product barcodes
    val scannedBarcodes: String,  // JSON array: [{"barcode": "123", "productId": "abc", ...}, ...]

    // Sync state
    val syncStatus: String,  // PENDING, UPLOADING_PHOTOS, SYNCING, SYNCED, FAILED
    val createdAt: Long,
    val syncAttempts: Int = 0,
    val lastSyncError: String? = null
) {
    companion object {
        // Sync status constants
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING_PHOTOS = "UPLOADING_PHOTOS"
        const val STATUS_SYNCING = "SYNCING"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_FAILED = "FAILED"
    }
}
