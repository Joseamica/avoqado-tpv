package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Payment Sync Worker
 *
 * Periodic background worker that retries failed payment recordings to the backend.
 * Follows Square Terminal and Toast POS patterns for offline payment queue.
 *
 * **Execution Pattern:**
 * - Runs every 15 minutes (configurable via Constraints), or immediately via
 *   PaymentSyncScheduler.runNow() right after a payment gets queued
 * - Claims a batch of pending payments from Room database (claimBatch — F-8)
 * - Attempts each payment EXACTLY ONCE per run — no loop, no delay() inside the
 *   worker (F-9). WorkManager owns the retry cadence: the 15-min periodic run,
 *   plus the NetworkType.CONNECTED constraint firing when connectivity returns.
 * - Updates queue status: SUCCESS, or back to PENDING/FAILED
 * - Continues to run even when app is closed (background worker)
 *
 * **Design Principles:**
 * - **Offline-first**: Payments succeed locally (Blumon SDK), then queue for backend sync
 * - **Idempotent**: Backend uses Blumon referenceNumber to prevent duplicates
 * - **Eventually consistent**: Payments eventually sync when network available
 * - **Graceful degradation**: Terminal works fine if sync fails (payments are already approved)
 *
 * **Lifecycle:**
 * 1. PaymentSyncScheduler.start() → Enqueues periodic work
 * 2. WorkManager triggers every 15 minutes (or on-demand via runNow())
 * 3. Worker claims a batch from PaymentQueueRepository (claimBatch — F-8, other workers can't take the same rows)
 * 4. Worker attempts each payment ONCE via RecordPaymentUseCase (F-9 — no retry loop in this worker)
 * 5. On success → markSynced() and continue to next payment
 * 6. On a TRANSIENT failure → release() with incremented retry count (returns the row to
 *    PENDING, clears the claim, so the NEXT trigger picks it up)
 * 7. After MAX_RETRY_ATTEMPTS (10) transient attempts SPENT ACROSS SEPARATE RUNS → Status
 *    becomes FAILED (manual review needed)
 * 8. On a PERMANENT business failure (4xx) → markPermanentlyFailed() straight to FAILED
 *    with `permanent = true` (F-10) — NEVER release(): reconnecting doesn't fix a 4xx, and
 *    `resetAllFailed()` will never resurrect a row marked permanent
 * 9. Worker returns Result.success() to schedule next run
 *
 * **Error Handling** (classifySyncFailure — by HTTP status code, never by message text):
 * - HTTP 409 → Retryable. NO afirma que el pago quedara registrado: el reintento
 *   idempotente real responde 200 con el pago existente, nunca 409 (ver classifySyncFailure)
 * - HTTP 4xx, except 408/429 (client error) → markPermanentlyFailed(): FAILED immediately
 *   with `permanent = true` — won't fix itself, and resetAllFailed() excludes it (F-10)
 * - HTTP 5xx / 408 / 429 / network / unknown → Retryable: release back to PENDING;
 *   WorkManager (not this worker) decides when the next attempt happens
 *
 * **Usage:**
 * This worker is NOT called directly. Use PaymentSyncScheduler to manage it:
 * ```kotlin
 * PaymentSyncScheduler.start(context) // Start on login
 * PaymentSyncScheduler.stop(context)  // Stop on logout
 * ```
 *
 * **Example Flow:**
 * ```
 * 1. User pays $50.00 → Blumon approves → PaymentViewModel records to backend
 * 2. Backend network timeout → PaymentViewModel queues payment
 * 3. PaymentSyncWorker runs (15 min later) → Attempt 1: fails (network still down)
 *    → release() back to PENDING, retryCount=1 (no delay, no retry within this run)
 * 4. NetworkType.CONNECTED constraint fires once connectivity returns
 *    → PaymentSyncWorker runs again → Attempt 2: success → markSynced()
 * 5. Payment now visible in dashboard
 * ```
 *
 * **💸 Reembolsos (Fase 1, Task 8 — 2026-09-04):** la MISMA corrida drena después la cola de
 * reembolsos (`pending_refunds`), con el mismo contrato: reclamar con token, UN intento por
 * fila, y compare-and-swap al escribir. Van DESPUÉS de los cobros y aunque no haya cobros:
 * el `return` temprano de «no hay pagos» dejaba los reembolsos sin reproducir NUNCA.
 *
 * **World-Class References:**
 * - Square Terminal: OfflineSyncWorker with 3 retries
 * - Toast POS: PaymentQueueWorker with 15-min periodic sync
 * - Stripe Terminal: TransactionSyncService with exponential backoff
 */
@HiltWorker
class PaymentSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val paymentQueueRepository: PaymentQueueRepository,
    private val recordPaymentUseCase: RecordPaymentUseCase,
    private val paymentQueueStateManager: PaymentQueueStateManager,
    private val refundQueueRepository: RefundQueueRepository,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        /**
         * Unique worker name for WorkManager.
         * Use this to enqueue/cancel work.
         */
        const val WORKER_NAME = "payment_sync_worker"

        // 🔴 F-10: NO local MAX_RETRY_ATTEMPTS constant here on purpose. The only caller
        // (the SyncOutcome.Permanent branch of syncPayment()) used to release() a row
        // straight to FAILED by claiming retryCount == MAX_RETRY_ATTEMPTS — replaced by
        // markPermanentlyFailedClaim(), which sets FAILED + permanent=true directly and
        // never touches retry_count. PendingPaymentEntity.MAX_RETRY_ATTEMPTS (10) remains
        // the single source of truth for transient-failure retry accounting (release()'s
        // CASE in PendingPaymentDao).

        /**
         * Maximum payments to process per worker run.
         * Prevents WorkManager 10-minute timeout when queue is large.
         * Remaining payments will be processed in the next periodic run (15 min).
         */
        private const val MAX_PAYMENTS_PER_RUN = 10

        /** Tope de reembolsos por corrida, por la misma razón que [MAX_PAYMENTS_PER_RUN]. */
        private const val MAX_REFUNDS_PER_RUN = 10
    }

    /**
     * Execute payment sync task
     *
     * **Return Values:**
     * - Result.success() → All pending payments processed (even if some failed)
     * - Result.retry() → Worker crashed, WorkManager should retry
     * - Result.failure() → Permanent failure (e.g., terminal not activated)
     *
     * **Retry Policy:**
     * This worker ALWAYS returns Result.success() to continue periodic runs.
     * Each payment gets exactly ONE attempt per call to doWork() (F-9) — the retry
     * cadence across attempts is owned by WorkManager (15-min periodic run + the
     * NetworkType.CONNECTED constraint), never by a loop/delay() in this worker.
     *
     * **Important:** Unlike HeartbeatWorker, this worker processes ALL pending payments
     * in a single run. It doesn't stop at the first failure — `markSyncedChecked` /
     * `releaseClaim` / `markPermanentlyFailedClaim` and the repository writes underneath
     * them (`markSynced`, `release`, `markPermanentlyFailed`) all catch internally and
     * return `0` on error, so one payment's write failing (disk full, DB closed
     * mid-teardown) can't throw out of the `for (payment in batch)` loop
     * and abandon the rest of an already-claimed, already-charged batch (F-9, Fix round 1
     * — `release()` used to be the one write in `PaymentQueueRepositoryImpl` without a
     * try/catch, unlike its siblings).
     */
    override suspend fun doWork(): Result {
        return try {
            Timber.d("💾 [Payment Sync] Worker started")

            // Reclamar la tanda: otro worker corriendo en paralelo NO puede tomar estas filas.
            val batch = paymentQueueRepository.claimBatch(MAX_PAYMENTS_PER_RUN)

            if (batch.isEmpty()) {
                // 🔴 Ya NO se hace `return` aquí: los reembolsos se drenan abajo aunque no haya
                // cobros pendientes. Con el `return` temprano, una cola con sólo reembolsos no se
                // reproducía nunca.
                Timber.d("✅ [Payment Sync] No hay pagos pendientes por sincronizar")
            } else {
                Timber.i("🔄 [Payment Sync] ${batch.size} pagos reclamados para sincronizar")

                // Process each payment independently (one failure doesn't block others)
                var successCount = 0
                var failedCount = 0

                for (payment in batch) {
                    val success = syncPayment(payment)
                    if (success) {
                        successCount++
                    } else {
                        failedCount++
                    }
                }

                // Log summary
                Timber.i(
                    "✅ [Payment Sync] Worker completed | " +
                            "success=$successCount | failed=$failedCount | batch=${batch.size}"
                )
            }

            // 💸 Los reembolsos van DESPUÉS de los cobros: un reembolso apunta al pago original
            // por su id de servidor, así que primero tiene que existir el pago allá.
            drainRefunds()

            // 🔴 Avisar al banner (bug real 2026-08-07): el contador de
            // "pagos pendientes" es un StateFlow de push manual
            // (PaymentQueueStateManager) — el boton de reintentar lo refresca al
            // RESETEAR, pero nadie lo refrescaba cuando la tanda TERMINABA. Los 2
            // pagos atorados sincronizaron (success=2) y el banner siguio diciendo
            // "2 pagos pendientes" hasta reiniciar la app: el cajero reintenta un
            // trabajo ya hecho. Refrescar aqui cierra el ciclo con la verdad de Room.
            // En try/catch propio: un fallo al CONTAR jamas puede tirar el worker —
            // los pagos de esta tanda YA se procesaron.
            // 💸 Se refresca SIEMPRE, también cuando sólo hubo reembolsos (auditoría de Codex F10): un
            // reembolso atorado era invisible hasta intentar cerrar el turno.
            try {
                val pending = paymentQueueRepository.getPendingCount()
                val failed = paymentQueueRepository.getFailedCount()
                val pendingRefunds = refundQueueRepository.getPendingCount()
                val failedRefunds = refundQueueRepository.getFailedCount()
                paymentQueueStateManager.refreshCounts(pending, failed, pendingRefunds, failedRefunds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [Payment Sync] No se pudieron refrescar los conteos del banner")
            }

            // ALWAYS return success to continue periodic runs
            // Even if some payments failed, we want the worker to run again in 15 minutes
            Result.success()
        } catch (e: CancellationException) {
            throw e  // Worker cancelled — don't treat as error
        } catch (e: Exception) {
            // Unexpected error (e.g., database crash)
            Timber.e(e, "💥 [Payment Sync] Worker crashed, will retry")
            Result.retry()
        }
    }

    /**
     * Intenta registrar UN pago, UNA vez (F-9 — sin loop ni delay() dentro del worker).
     *
     * El retry NO vive aquí: el constraint NetworkType.CONNECTED hace que WorkManager
     * dispare al volver la red, y el periódico de 15 min es la garantía de fondo. Un loop
     * con backoff interno sumaba hasta ~40 min por tanda (10 pagos x 10 intentos x hasta
     * 30s) contra el límite de ejecución de WorkManager (~10 min): se moría a media tanda
     * dejando `retry_count` inflado, y pagos buenos acababan en FAILED. Ver spec §4.2 F-9.
     *
     * Tanto un `Result.failure` devuelto por [recordPaymentUseCase] como una excepción que
     * se le escape terminan en el MISMO camino: se normalizan a `Result.failure` y pasan
     * por [classifySyncFailure] una sola vez. Ante la duda, [classifySyncFailure] clasifica
     * Retryable — la fila vuelve a PENDING (claim liberado) y el siguiente disparo
     * (periódico o NetworkType.CONNECTED) reintenta; nunca un loop ni un delay() locales.
     *
     * `CancellationException` se repropaga sin tocar la fila: el worker cancelado la deja
     * SYNCING/reclamada, y el barrido de stale-claim (`STALE_CLAIM_MS`) la recupera.
     *
     * @param payment Queued payment to sync
     * @return true si quedó sincronizado (o el backend ya lo tenía), false si no
     */
    private suspend fun syncPayment(payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment): Boolean =
        registrarYClasificar(payment, payment.toPaymentContext(), permitirCaidaAVentaRapida = true)

    /**
     * Registra [context] y clasifica el desenlace. Recursiva **una sola vez**, y sólo por el
     * camino de [esOrdenQueYaNoExiste]: el segundo intento entra con
     * `permitirCaidaAVentaRapida = false`, así que no puede haber bucle.
     */
    private suspend fun registrarYClasificar(
        payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment,
        context: PaymentContext,
        permitirCaidaAVentaRapida: Boolean,
    ): Boolean {
        Timber.d("🔄 [Payment Sync] Syncing payment | ref=${payment.referenceNumber} | queueId=${payment.queueId}")

        val result = try {
            recordPaymentUseCase(
                context = context,
                cardDetails = payment.toCardDetails(),
                authorizationNumber = payment.authorizationNumber ?: "",
                referenceNumber = payment.referenceNumber,
            )
        } catch (e: CancellationException) {
            throw e // Worker cancelado — no tocar la fila, la reclama el barrido de stale-claim
        } catch (e: Exception) {
            // Excepción inesperada (no un Result.failure) — se normaliza para pasar por el
            // mismo clasificador que un fallo devuelto normalmente. Ver KDoc arriba.
            // 🔴 kotlin.Result CALIFICADO A PROPÓSITO: dentro de una clase que extiende
            // CoroutineWorker, el `Result` sin calificar resuelve al `Result` ANIDADO de
            // ListenableWorker (androidx.work), no al de Kotlin — error de tipos silencioso
            // en compilación (esperaba `Data`, no `Throwable`).
            Timber.e(e, "💥 [Payment Sync] Excepción inesperada al sincronizar | ref=${payment.referenceNumber}")
            kotlin.Result.failure(e)
        }

        if (result.isSuccess) {
            Timber.i(
                "✅ [Payment Sync] Payment synced successfully | " +
                        "ref=${payment.referenceNumber} | queueId=${payment.queueId}"
            )
            markSyncedChecked(payment)
            return true
        }

        // 🔴 F-6: clasificar por código HTTP, NUNCA por texto del mensaje.
        // Antes: errorMessage.contains("409") — un reference number como
        // "000000409231" contiene "409" y marcaba como SUCCESS una venta que
        // nunca llegó al backend. Ver SyncOutcome.kt / BackendHttpException.kt.
        return when (val outcome = classifySyncFailure(result.exceptionOrNull())) {
            is SyncOutcome.Synced -> {
                Timber.i(
                    "✅ [Payment Sync] El backend ya tenía el pago (409) | ref=%s",
                    payment.referenceNumber,
                )
                markSyncedChecked(payment)
                true
            }

            is SyncOutcome.Permanent -> {
                // 🔴 La orden ya no existe (borrada, migrada, de otro venue) — auditoría Codex,
                // 2026-09-07. Hasta el 7-sep una fila Blumon con `orderId` se reproducía como venta
                // FAST y el cobro SIEMPRE quedaba registrado; desde que vuelve a SU orden, ese mismo
                // 404 la mata para siempre (`permanent = true`, que sólo un tap manual revierte) —
                // incluso a un cobro de TARJETA ya capturado, o sea dinero que salió del cliente y
                // no existe en Avoqado. Se reintenta UNA vez como venta rápida, con la MISMA llave
                // de idempotencia y la MISMA referencia: el saldo queda registrado y lo único que
                // se pierde es el vínculo con una orden que ya no está.
                if (permitirCaidaAVentaRapida && esOrdenQueYaNoExiste(payment, context, result.exceptionOrNull())) {
                    Timber.w(
                        "⚠️ [Payment Sync] orden no encontrada al reproducir: se registró como venta rápida | " +
                            "ref=%s | orderId=%s",
                        payment.referenceNumber,
                        payment.orderId,
                    )
                    return registrarYClasificar(
                        payment,
                        payment.copy(orderId = null, orderNumber = null).toPaymentContext(),
                        permitirCaidaAVentaRapida = false,
                    )
                }
                Timber.e(
                    "❌ [Payment Sync] Error permanente (no se reintenta) | ref=%s | %s",
                    payment.referenceNumber,
                    outcome.reason,
                )
                // F-10: FAILED + permanent=true, NUNCA release() — un 4xx no se arregla
                // reintentando, y release() habría vuelto la fila PENDING-resucitable
                // en cada reconexión. Ver markPermanentlyFailedClaim().
                markPermanentlyFailedClaim(payment, error = outcome.reason)
                false
            }

            is SyncOutcome.Retryable -> {
                // 🩹 Fix round 1: el mensaje real de la excepción, NO
                // outcome.toString() — en un `data object` eso es literalmente
                // el string "Retryable", perdiendo la razón real del fallo que
                // last_error necesita para revisión manual.
                val transientError = result.exceptionOrNull()?.message ?: "error transitorio"
                Timber.w(
                    "⚠️ [Payment Sync] Fallo transitorio | ref=%s | %s",
                    payment.referenceNumber,
                    transientError,
                )
                releaseClaim(payment, retryCount = payment.retryCount + 1, error = transientError)
                false
            }
        }
    }

    /**
     * ¿El 404 que acabamos de recibir es «la orden ya no existe» sobre un cobro de ORDEN?
     *
     * Las TRES condiciones importan:
     * - el contexto que se mandó era [PaymentContext.OrderPayment] (un pago rápido no tiene
     *   orden que perder, y volver a mandarlo sería el mismo intento otra vez);
     * - la fila NO es de AngelPay — su replay de orden es el comportamiento de siempre y no se
     *   toca (su contexto tampoco es `OrderPayment`, así que esto es cinturón y tirantes);
     * - el código es **404** exacto. Un 400 (payload mal formado) o un 422 (regla de negocio,
     *   p.ej. orden ya cerrada) NO se arreglan quitando la orden: ahí sí es permanente.
     */
    private fun esOrdenQueYaNoExiste(
        payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment,
        context: PaymentContext,
        error: Throwable?,
    ): Boolean = context is PaymentContext.OrderPayment &&
        payment.processor != ProcessorType.ANGELPAY &&
        error is BackendHttpException &&
        error.statusCode == 404

    /**
     * Marca la fila como SUCCESS — compare-and-swap con el `claimToken` que trae [payment]
     * (F-8, Fix round 1). Si otro worker ya reclamó esta fila (se puso stale y alguien más
     * la tomó), la escritura afecta 0 filas: el backend YA tiene el pago — eso no cambia —
     * solo perdimos la carrera por el bookkeeping local. NUNCA reintentar el write en ese
     * caso; quien tenga el claim vigente resolverá su propio intento por su cuenta.
     */
    private suspend fun markSyncedChecked(payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment) {
        val affected = paymentQueueRepository.markSynced(payment.queueId, payment.claimToken.orEmpty())
        if (affected == 0) {
            Timber.w(
                "⚠️ [Payment Sync] markSynced() no afectó filas | ref=%s | queueId=%d | " +
                    "otro worker ya reclamó esta fila (el pago SÍ quedó registrado en backend)",
                payment.referenceNumber,
                payment.queueId,
            )
        }
    }

    /**
     * Suelta el claim de [payment] — compare-and-swap con su `claimToken` (F-8, Fix round 1).
     * Si otro worker ya reclamó esta fila (stale-claim), la escritura afecta 0 filas y esta
     * llamada es un no-op seguro: soltar/reintentar aquí le borraría el claim vigente al otro
     * worker mientras sigue registrando el mismo pago — exactamente el doble-registro que F-8
     * existe para evitar.
     */
    private suspend fun releaseClaim(
        payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment,
        retryCount: Int,
        error: String,
    ) {
        val affected = paymentQueueRepository.release(
            queueId = payment.queueId,
            token = payment.claimToken.orEmpty(),
            retryCount = retryCount,
            error = error,
        )
        if (affected == 0) {
            Timber.w(
                "⚠️ [Payment Sync] release() no afectó filas | ref=%s | queueId=%d | " +
                    "otro worker ya reclamó esta fila, no se reintenta el write",
                payment.referenceNumber,
                payment.queueId,
            )
        }
    }

    /**
     * Marca [payment] como fallo de negocio PERMANENTE (F-10) — compare-and-swap con su
     * `claimToken` (F-8, Fix round 1), mismo patrón que [markSyncedChecked]/[releaseClaim].
     * A diferencia de [releaseClaim], la fila NUNCA vuelve a PENDING: pasa directo a FAILED
     * con `permanent = true`, así `resetAllFailed()` no la resucita en cada reconexión — un
     * 4xx (orden no encontrada, venue inactivo, etc.) no se arregla solo reintentando.
     *
     * Si otro worker ya reclamó esta fila (stale-claim), la escritura afecta 0 filas y es
     * un no-op seguro — igual razón que sus hermanos: el backend YA rechazó este pago de
     * forma permanente, eso no cambia; solo perdimos la carrera por el bookkeeping local.
     */
    private suspend fun markPermanentlyFailedClaim(
        payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment,
        error: String,
    ) {
        val affected = paymentQueueRepository.markPermanentlyFailed(
            queueId = payment.queueId,
            token = payment.claimToken.orEmpty(),
            error = error,
        )
        if (affected == 0) {
            Timber.w(
                "⚠️ [Payment Sync] markPermanentlyFailed() no afectó filas | ref=%s | queueId=%d | " +
                    "otro worker ya reclamó esta fila, no se reintenta el write",
                payment.referenceNumber,
                payment.queueId,
            )
        }
    }

    // ─── 💸 Reembolsos (Fase 1, Task 8) ──────────────────────────────────────────

    /**
     * Drena la cola de REEMBOLSOS con el mismo contrato que los cobros: reclamar con token, UN
     * intento por fila por corrida, y compare-and-swap con ese token al escribir.
     *
     * 🔴 Un fallo al reclamar o al escribir una fila NUNCA se propaga: tumbar el worker aquí
     * dejaría el resto de una tanda ya reclamada —dinero que YA se devolvió— esperando a que
     * caduque su lease. Cada fila se aísla en su propio try/catch (salvo cancelación).
     */
    private suspend fun drainRefunds() {
        val refunds = try {
            refundQueueRepository.claimBatch(MAX_REFUNDS_PER_RUN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "💸 [Refund Sync] No se pudo reclamar la cola de reembolsos")
            return
        }
        if (refunds.isEmpty()) {
            Timber.d("💸 [Refund Sync] Sin reembolsos pendientes")
            return
        }
        Timber.i("💸 [Refund Sync] ${refunds.size} reembolsos reclamados para registrar")

        var successCount = 0
        var failedCount = 0
        for (refund in refunds) {
            val ok = try {
                syncRefund(refund)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "💸 [Refund Sync] Fallo inesperado en una fila | key=${refund.idempotencyKey}")
                false
            }
            if (ok) successCount++ else failedCount++
        }
        Timber.i("💸 [Refund Sync] Worker completed | success=$successCount | failed=$failedCount | batch=${refunds.size}")
    }

    /**
     * Reproduce UN reembolso, UNA vez. El repositorio manda la MISMA llave, monto y datos del SDK
     * que se guardaron, y clasifica con `classifySyncFailure` (nunca por texto).
     *
     * `Retryable` vuelve a PENDING con `retry_count + 1` (la DAO lo pasa a FAILED al llegar a
     * `MAX_RETRY_ATTEMPTS`); `Permanent` va directo a FAILED + `permanent = true` y bloquea el
     * cierre de turno hasta que alguien lo reconozca. Cero filas afectadas = otro worker se
     * quedó con el claim: no se reintenta el write.
     */
    private suspend fun syncRefund(refund: QueuedRefund): Boolean {
        val token = refund.claimToken.orEmpty()
        Timber.d("💸 [Refund Sync] Reproduciendo | key=${refund.idempotencyKey} | intento=${refund.retryCount + 1}")
        return when (val outcome = refundQueueRepository.replay(refund)) {
            is SyncOutcome.Synced -> {
                val affected = refundQueueRepository.markSuccess(refund.idempotencyKey, token)
                if (affected == 0) Timber.w("💸 [Refund Sync] markSuccess() no afectó filas (otro worker tiene el claim) | key=${refund.idempotencyKey}")
                true
            }

            is SyncOutcome.Permanent -> {
                Timber.e("💸 [Refund Sync] Rechazo PERMANENTE del servidor | key=${refund.idempotencyKey} | ${outcome.reason}")
                val affected = refundQueueRepository.markPermanentlyFailed(refund.idempotencyKey, token, outcome.reason)
                if (affected == 0) Timber.w("💸 [Refund Sync] markPermanentlyFailed() no afectó filas | key=${refund.idempotencyKey}")
                false
            }

            is SyncOutcome.Retryable -> {
                // ⚠️ `replay` sólo devuelve la clasificación; el detalle del fallo queda en su log.
                val affected = refundQueueRepository.release(
                    refund.idempotencyKey,
                    token,
                    retryCount = refund.retryCount + 1,
                    error = "error transitorio (intento ${refund.retryCount + 1})",
                )
                if (affected == 0) Timber.w("💸 [Refund Sync] release() no afectó filas | key=${refund.idempotencyKey}")
                false
            }
        }
    }
}
