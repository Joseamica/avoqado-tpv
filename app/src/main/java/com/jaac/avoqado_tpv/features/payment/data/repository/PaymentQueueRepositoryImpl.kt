package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
            // 🔴 F-9, Fix round 3: NonCancellable — la tarjeta YA se cobró antes de que
            // enqueue() se llame (solo se invoca cuando el registro al backend falló); esta
            // fila es la red de seguridad COMPLETA de ese cobro, no trabajo opcional que se
            // pueda abandonar si la pantalla se cierra a mitad de camino (viewModelScope
            // cancelado al hacer clear() del ViewModel — AngelPayPaymentViewModel
            // .handleRecordFailure(), llamado desde dentro de viewModelScope.launch{} en los
            // 3 sitios que registran un pago). Mismo patrón que los marks post-SDK de
            // PaymentAttemptLedger (NonCancellable + Dispatchers.IO — "survive screen pops and
            // ViewModel clears"), aplicado aquí por la misma razón: proteger dinero que YA se
            // movió, no dato que se pueda perder sin costo.
            //
            // Acotado a LA ESCRITURA sola (no a toda la función): en cuanto este insert
            // compromete la fila en SQLite, el dato ya quedó a salvo pase lo que pase después
            // con esta corrutina — que el `return@withContext` de más abajo o el resto de
            // enqueue() se cancelen luego no deshace un commit ya hecho. El diagnóstico de
            // duplicados que sigue (findByReference, cuando hay choque de índice) es una
            // LECTURA sobre una fila que, de existir, ya quedó a salvo por un intento
            // anterior — no protege dinero nuevo y no necesita el escudo.
            val id = withContext(NonCancellable + Dispatchers.IO) {
                pendingPaymentDao.insert(entity)
            }

            if (id > 0) {
                Timber.i(
                    "💾 [Payment Queue] Pago encolado | ref=${payment.referenceNumber} | " +
                        "amount=${payment.amount} | queueId=$id",
                )
                return@withContext Result.success(Unit)
            }

            // rowId 0 = choque con el índice único de reference_number.
            // Solo es benigno si la fila existente sigue viva Y es el MISMO pago:
            // - MISMO venue — el índice es global y pending_payments NO se vacía al
            //   cambiar de venue/logout (a propósito: guarda datos de dinero). Un
            //   terminal reasignado de un venue a otro (patrón operativo real —
            //   promotores de relevo, re-parenting) puede reusar un reference_number
            //   corto/secuencial que OTRO venue aún tiene PENDING localmente.
            //   findByReference ya filtra por venue_id en el SQL, así que una fila de
            //   otro venue es invisible aquí (Fix round 1, finding 1).
            // - MISMA idempotencyKey cuando ambos lados la tienen — mismo venue+reference
            //   con idempotencyKey distinta es un pago DISTINTO, no un doble-submit seguro.
            val existing = pendingPaymentDao.findByReference(payment.referenceNumber, payment.venueId)
            val sameIdentity = existing != null &&
                (payment.idempotencyKey == null || existing.idempotencyKey == null ||
                    payment.idempotencyKey == existing.idempotencyKey)
            val stillQueued = sameIdentity &&
                (existing?.syncStatus == PendingPaymentEntity.SYNC_STATUS_PENDING ||
                    existing?.syncStatus == PendingPaymentEntity.SYNC_STATUS_SYNCING)

            if (stillQueued) {
                Timber.w("⚠️ [Payment Queue] Pago ya encolado | ref=${payment.referenceNumber}")
                Result.success(Unit)
            } else {
                // 🔴 Fila SUCCESS/FAILED vieja, de OTRO venue, o de OTRO pago (idempotencyKey
                // distinta) bloquea el índice: este pago NO entró. Reportarlo como fallo es
                // lo que hace que el cajero vea el aviso rojo real ("avisa al supervisor")
                // en vez del falso "EN COLA".
                Timber.e(
                    "❌ [Payment Queue] Encolado BLOQUEADO por fila previa (%s) | ref=%s venue=%s",
                    existing?.syncStatus ?: "de otro venue/pago",
                    payment.referenceNumber,
                    payment.venueId,
                )
                Result.failure(
                    IllegalStateException(
                        "El pago no pudo encolarse: ya existe una fila ${existing?.syncStatus ?: "de otro venue/pago"} " +
                            "con reference ${payment.referenceNumber}",
                    ),
                )
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

    override suspend fun markSynced(queueId: Long, token: String): Int = withContext(Dispatchers.IO) {
        try {
            val affected = pendingPaymentDao.markSynced(queueId, token)
            if (affected > 0) {
                Timber.i("✅ [Payment Queue] Payment marked as synced | queueId=$queueId")
            } else {
                // 🔒 CAS miss (F-8, Fix round 1): another worker reclaimed this row after our
                // claim went stale. The backend call that got us here already succeeded — this
                // is lost local bookkeeping, not a failed sync. Caller must not retry the write.
                Timber.w("⚠️ [Payment Queue] markSynced() no afectó filas (claim reclamado por otro worker) | queueId=$queueId")
            }
            affected
        } catch (e: CancellationException) {
            // 🔴 F-9, Fix round 2 (pre-existing, not introduced by round 1): CancellationException
            // IS a RuntimeException/Exception in Kotlin — a bare `catch (e: Exception)` below
            // would swallow worker cancellation and return 0 instead of letting it propagate to
            // doWork()'s `catch (e: CancellationException) { throw e }`. A cancelled worker must
            // leave this row claimed for the stale sweep, never keep writing/looping. Rethrow
            // BEFORE the generic catch.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to mark payment as synced")
            0
        }
    }

    // 🔴 F-9, Fix round 1 (Important 2): NO override fun updateRetry() here on purpose.
    // The repository-level wrapper was deleted — it had zero callers in src/main after F-9
    // removed the in-worker retry loop that used to call it mid-loop (keeping the claim so
    // the SAME worker run could try again). That's structurally gone now: every outcome in
    // PaymentSyncWorker.syncPayment() goes through markSynced (success) or release (anything
    // else), both of which CLEAR the claim. A discoverable, claim-RETAINING write on this
    // interface was exactly the footgun this plan removes — a future caller reaching for "record
    // a failed attempt" here instead of release() would recreate the "claimed row nobody acts on
    // again until stale-claim" bug. The underlying `PendingPaymentDao.updateRetry` Room query
    // still exists (kept for its own CAS regression test, PendingPaymentClaimTest.kt) — it's
    // just no longer exposed through this application-facing repository.

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

    override suspend fun resetAllFailed(): Int = withContext(Dispatchers.IO) {
        try {
            val count = pendingPaymentDao.resetAllFailed()
            if (count > 0) {
                Timber.i("🔄 [Payment Queue] Reset $count failed payments back to PENDING")
            }
            count
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to reset failed payments")
            0
        }
    }

    override suspend fun resetAllFailedIncludingPermanent(): Int = withContext(Dispatchers.IO) {
        try {
            val count = pendingPaymentDao.resetAllFailedIncludingPermanent()
            if (count > 0) {
                Timber.i("🔄 [Payment Queue] Reset $count failed payments (incl. permanent) back to PENDING — manual tap")
            }
            count
        } catch (e: Exception) {
            Timber.e(e, "❌ [Payment Queue] Failed to reset failed payments (including permanent)")
            0
        }
    }

    override suspend fun markPermanentlyFailed(queueId: Long, token: String, error: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val affected = pendingPaymentDao.markPermanentlyFailed(queueId, token, error)
                if (affected > 0) {
                    Timber.i("🚫 [Payment Queue] Payment marked as permanently failed | queueId=$queueId")
                } else {
                    // 🔒 CAS miss (F-8/F-10): another worker reclaimed this row after our claim
                    // went stale. Same rule as markSynced/release — never retry this write.
                    Timber.w("⚠️ [Payment Queue] markPermanentlyFailed() no afectó filas (claim reclamado por otro worker) | queueId=$queueId")
                }
                affected
            } catch (e: CancellationException) {
                // Mismo motivo que en markSynced/release: CancellationException ES una
                // Exception en Kotlin — un catch (e: Exception) desnudo se la tragaría y
                // devolvería 0 en vez de repropagarla a doWork(). Un worker cancelado debe
                // dejar la fila reclamada para el barrido de stale-claim, nunca seguir
                // escribiendo.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "❌ [Payment Queue] markPermanentlyFailed() falló | queueId=$queueId")
                0
            }
        }

    override suspend fun claimBatch(limit: Int): List<QueuedPayment> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        pendingPaymentDao
            .claimBatch(
                limit = limit,
                token = java.util.UUID.randomUUID().toString(),
                now = now,
                staleBefore = now - STALE_CLAIM_MS,
            )
            .map { it.toDomain() }
    }

    override suspend fun release(queueId: Long, token: String, retryCount: Int, error: String): Int =
        withContext(Dispatchers.IO) {
            try {
                pendingPaymentDao.release(queueId, token, retryCount, error)
            } catch (e: CancellationException) {
                // 🔴 F-9, Fix round 2: introduced by Fix round 1's own try/catch below —
                // CancellationException IS a RuntimeException/Exception in Kotlin, so
                // `catch (e: Exception)` alone would swallow worker cancellation and return 0
                // instead of propagating. Before round 1, release() had NO catch at all, so
                // cancellation reached doWork()'s `catch (e: CancellationException) { throw e }`
                // untouched — a cancelled worker must leave this row claimed for the stale sweep
                // and must NEVER keep writing into the next payment of the batch. Rethrow BEFORE
                // the generic catch.
                throw e
            } catch (e: Exception) {
                // 🔒 F-9, Fix round 1 (Important 1): sin este try/catch, un throw aquí (disco
                // lleno, DB cerrada durante teardown) escapaba de releaseClaim → syncPayment →
                // el for (payment in batch) de doWork() → el catch genérico → Result.retry(),
                // abandonando el resto de la tanda YA reclamada (hasta 9 pagos ya cobrados)
                // en SYNCING hasta que venciera STALE_CLAIM_MS (15 min). markSynced ya tenía
                // este mismo try/catch (aunque con el mismo bug de CancellationException,
                // arreglado arriba en la misma ronda) — release() era el único write sin ÉL.
                Timber.e(e, "❌ [Payment Queue] release() falló | queueId=$queueId")
                0
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
            idempotencyKey = idempotencyKey, // 🛡️ Primary dedup key for queue retries (2026-05-29)
            paymentProcessor = processor.name, // 🔶 Processor-aware queue (2026-07-09)
            terminalPaymentRequestId = terminalPaymentRequestId, // 📡 POS→TPV arbitration link (2026-07-14)
            orderId = orderId,
            orderNumber = orderNumber,
            shiftId = shiftId,
            isPortabilidad = isPortabilidad,
            serialNumbers = serialNumbers.takeIf { it.isNotEmpty() }?.joinToString(","),
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
            idempotencyKey = idempotencyKey, // 🛡️ Primary dedup key for queue retries (2026-05-29)
            // 🔶 Processor-aware queue (2026-07-09) — unknown/legacy values fall back
            // to BLUMON (the pre-v25 semantics of every existing row).
            processor = runCatching {
                com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.valueOf(paymentProcessor)
            }.getOrDefault(com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.BLUMON),
            terminalPaymentRequestId = terminalPaymentRequestId, // 📡 POS→TPV arbitration link (2026-07-14)
            orderId = orderId,
            orderNumber = orderNumber,
            shiftId = shiftId,
            isPortabilidad = isPortabilidad,
            serialNumbers = serialNumbers?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
            createdAt = createdAt,
            retryCount = retryCount,
            lastError = lastError,
            syncStatus = when (syncStatus) {
                PendingPaymentEntity.SYNC_STATUS_PENDING -> SyncStatus.PENDING
                PendingPaymentEntity.SYNC_STATUS_SYNCING -> SyncStatus.SYNCING
                PendingPaymentEntity.SYNC_STATUS_SUCCESS -> SyncStatus.SUCCESS
                PendingPaymentEntity.SYNC_STATUS_FAILED -> SyncStatus.FAILED
                else -> SyncStatus.PENDING
            },
            claimToken = claimToken, // 🔒 F-8, Fix round 1 — round-trips so release()/markSynced() can CAS
        )
    }

    private companion object {
        /**
         * Una fila SYNCING más vieja que esto se considera abandonada y puede reclamarse de
         * nuevo (stale reclaim, F-8).
         *
         * 🔒 INVARIANTE (Fix round 2) — NO tocar sin releer esto primero:
         *
         * Este valor DEBE exceder el tiempo máximo de ejecución de un `CoroutineWorker` bajo
         * WorkManager estándar (~10 minutos, límite del sistema). Ese margen — 5 minutos, no
         * documentado hasta ahora — es lo que hoy garantiza que un worker VIVO (no muerto)
         * nunca sobreviva a su propio claim antes de que WorkManager lo corte. Si ese margen
         * desaparece, un worker legítimamente lento (no muerto) puede seguir corriendo después
         * de que un segundo worker reclame su fila por stale, y las dos escrituras compiten por
         * la misma fila ya cobrada.
         *
         * - NO bajar `STALE_CLAIM_MS` sin volver a calcular este margen contra el límite real
         *   de ejecución del worker.
         * - NO convertir `PaymentSyncWorker` en foreground/expedited/long-running — eso extiende
         *   cuánto puede vivir más allá del límite estándar de WorkManager y reduce o borra el
         *   margen que sostiene este número.
         *
         * `markSynced`/`release` ya son compare-and-swap por `claim_token` (Fix rounds 1–2),
         * así que una violación de este margen ya NO causa un doble-registro por sí sola —
         * esto es defensa en profundidad, no la única defensa. (`updateRetry` YA NO vive en
         * este repositorio desde F-9, Fix round 1 — ver la nota junto a `getPendingCount()`.)
         * Pero la próxima persona que toque este número necesita saber por qué es 15 y no,
         * por ejemplo, 5.
         */
        const val STALE_CLAIM_MS = 15 * 60 * 1000L
    }
}
