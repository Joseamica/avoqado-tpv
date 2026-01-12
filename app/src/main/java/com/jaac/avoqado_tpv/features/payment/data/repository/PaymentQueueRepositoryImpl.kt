package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Implementation of PaymentQueueRepository using Room database.
 *
 * **Responsibilities:**
 * - Map between domain models (QueuedPayment) and Room entities (PendingPaymentEntity)
 * - Handle Room database operations via PendingPaymentDao
 * - Provide error handling and logging
 *
 * **Threading:** All database operations run on Dispatchers.IO
 *
 * **Injected By:** Hilt (Singleton)
 *
 * **Example Flow:**
 * ```
 * PaymentViewModel (failure)
 *   ↓ QueuedPayment
 * PaymentQueueRepositoryImpl.enqueue()
 *   ↓ PendingPaymentEntity (map)
 * PendingPaymentDao.insert()
 *   ↓
 * Room Database (SQLite)
 * ```
 */
class PaymentQueueRepositoryImpl @Inject constructor(
    private val pendingPaymentDao: PendingPaymentDao
) : PaymentQueueRepository {

    override suspend fun enqueue(payment: QueuedPayment): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = payment.toEntity()
            val id = pendingPaymentDao.insert(entity)

            if (id > 0) {
                Timber.i(
                    "💾 [Payment Queue] Payment queued | ref=${payment.referenceNumber} | " +
                            "amount=${payment.amount} | queueId=$id"
                )
                Result.success(Unit)
            } else {
                // ID = 0 means conflict (duplicate referenceNumber)
                Timber.w(
                    "⚠️ [Payment Queue] Duplicate payment skipped | ref=${payment.referenceNumber} | " +
                            "Already in queue"
                )
                Result.success(Unit) // Not an error - payment already queued
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to enqueue payment")
            Result.failure(e)
        }
    }

    override suspend fun getAllPending(): List<QueuedPayment> = withContext(Dispatchers.IO) {
        try {
            val entities = pendingPaymentDao.getAllPending()
            Timber.d("📋 [Payment Queue] Fetched ${entities.size} pending payments")
            entities.map { it.toDomain() }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to fetch pending payments")
            emptyList()
        }
    }

    override suspend fun markSynced(queueId: Long) = withContext(Dispatchers.IO) {
        try {
            pendingPaymentDao.markSynced(queueId)
            Timber.i("✅ [Payment Queue] Payment marked as synced | queueId=$queueId")
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to mark payment as synced")
        }
    }

    override suspend fun updateRetry(queueId: Long, retryCount: Int, error: String) = withContext(Dispatchers.IO) {
        try {
            pendingPaymentDao.updateRetry(queueId, retryCount, error)

            if (retryCount >= PendingPaymentEntity.MAX_RETRY_ATTEMPTS) {
                Timber.w(
                    "⚠️ [Payment Queue] Payment marked as FAILED after $retryCount attempts | " +
                            "queueId=$queueId | error=$error"
                )
            } else {
                Timber.d(
                    "🔄 [Payment Queue] Retry count updated | queueId=$queueId | " +
                            "retryCount=$retryCount | error=$error"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to update retry count")
        }
    }

    override suspend fun getPendingCount(): Int = withContext(Dispatchers.IO) {
        try {
            pendingPaymentDao.getPendingCount()
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to get pending count")
            0
        }
    }

    override suspend fun getFailedCount(): Int = withContext(Dispatchers.IO) {
        try {
            pendingPaymentDao.getFailedCount()
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to get failed count")
            0
        }
    }

    override suspend fun deleteOldSyncedPayments(daysAgo: Int) = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (daysAgo * 24 * 60 * 60 * 1000L)
            pendingPaymentDao.deleteOldSyncedPayments(cutoffTime)
            Timber.i("🧹 [Payment Queue] Deleted old synced payments (older than $daysAgo days)")
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to delete old synced payments")
        }
    }

    // ===========================================================================================
    // Mapping Functions: Domain ←→ Entity
    // ===========================================================================================

    /**
     * Map QueuedPayment (domain) → PendingPaymentEntity (Room).
     */
    private fun QueuedPayment.toEntity(): PendingPaymentEntity {
        return PendingPaymentEntity(
            id = if (queueId == 0L) 0 else queueId, // 0 = auto-generate new ID
            referenceNumber = referenceNumber,
            venueId = venueId,
            staffId = staffId,
            amount = amount.toPlainString(), // BigDecimal → String
            tip = tip.toPlainString(),
            rating = rating, // 🆕 Optional user rating (1-5 stars or null)
            merchantAccountId = merchantAccountId, // 🆕 PRIMARY: Provider-agnostic merchant ID
            blumonSerialNumber = blumonSerialNumber, // ⚠️ LEGACY: Fallback
            deviceSerialNumber = deviceSerialNumber, // ⭐ Terminal attribution (2026-01-08)
            maskedPan = maskedPan,
            cardBrand = cardBrand,
            entryMode = entryMode,
            isInternational = isInternational,
            authorizationNumber = authorizationNumber,
            createdAt = createdAt,
            retryCount = retryCount,
            lastError = lastError,
            syncStatus = when (syncStatus) {
                SyncStatus.PENDING -> PendingPaymentEntity.SYNC_STATUS_PENDING
                SyncStatus.SYNCING -> PendingPaymentEntity.SYNC_STATUS_SYNCING
                SyncStatus.SUCCESS -> PendingPaymentEntity.SYNC_STATUS_SUCCESS
                SyncStatus.FAILED -> PendingPaymentEntity.SYNC_STATUS_FAILED
            }
        )
    }

    /**
     * Map PendingPaymentEntity (Room) → QueuedPayment (domain).
     */
    private fun PendingPaymentEntity.toDomain(): QueuedPayment {
        return QueuedPayment(
            queueId = id,
            referenceNumber = referenceNumber,
            venueId = venueId,
            staffId = staffId,
            amount = amount.toBigDecimal(), // String → BigDecimal
            tip = tip.toBigDecimal(),
            rating = rating, // 🆕 Optional user rating (1-5 stars or null)
            merchantAccountId = merchantAccountId, // 🆕 PRIMARY: Provider-agnostic merchant ID
            blumonSerialNumber = blumonSerialNumber, // ⚠️ LEGACY: Fallback
            deviceSerialNumber = deviceSerialNumber, // ⭐ Terminal attribution (2026-01-08)
            maskedPan = maskedPan,
            cardBrand = cardBrand,
            entryMode = entryMode,
            isInternational = isInternational,
            authorizationNumber = authorizationNumber,
            createdAt = createdAt,
            retryCount = retryCount,
            lastError = lastError,
            syncStatus = when (syncStatus) {
                PendingPaymentEntity.SYNC_STATUS_PENDING -> SyncStatus.PENDING
                PendingPaymentEntity.SYNC_STATUS_SYNCING -> SyncStatus.SYNCING
                PendingPaymentEntity.SYNC_STATUS_SUCCESS -> SyncStatus.SUCCESS
                PendingPaymentEntity.SYNC_STATUS_FAILED -> SyncStatus.FAILED
                else -> SyncStatus.PENDING
            }
        )
    }
}
