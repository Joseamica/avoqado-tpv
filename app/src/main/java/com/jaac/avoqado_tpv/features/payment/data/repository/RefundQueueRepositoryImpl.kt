package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.local.dao.PendingRefundDao
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.model.toDomain
import com.jaac.avoqado_tpv.features.payment.domain.model.toEntity
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RefundQueueRepositoryImpl @Inject constructor(
    private val dao: PendingRefundDao,
    private val recorder: RefundRecorder,
    /** El banner del aparato: quien escribe la cola publica sus conteos (ver [publicarConteos]). */
    private val queueState: PaymentQueueStateManager,
) : RefundQueueRepository {

    override suspend fun enqueue(refund: QueuedRefund): Result<Unit> = withContext(NonCancellable) {
        // 🔴 `NonCancellable` envuelve el cuerpo ENTERO, no sólo el insert. Esto se llama desde el
        // scope de un ViewModel que muere al salir de la pantalla, y el cajero sale justo después
        // de leer «devolución aprobada» — el instante exacto en el que la fila tiene que quedar
        // escrita. Un `withContext` sólo alrededor del `insert` dejaría el mapeo y el log fuera.
        runCatching {
            val insertado = dao.insertIgnore(refund.toEntity())
            if (insertado == -1L) {
                Timber.i("💸 [RefundQueue] ya estaba encolado, no se duplica | key=${refund.idempotencyKey}")
            } else {
                Timber.i("💸 [RefundQueue] encolado | key=${refund.idempotencyKey} estado=${refund.syncStatus}")
            }
            publicarConteos()
            Unit
        }.onFailure {
            // Si ni siquiera se pudo escribir en Room, el reembolso se pierde. Es lo peor que puede
            // pasar en este archivo, y por eso se grita en vez de tragárselo.
            Timber.e(it, "💸🔴 [RefundQueue] NO se pudo encolar el reembolso | key=${refund.idempotencyKey}")
        }
    }

    override suspend fun enqueueClaimed(refund: QueuedRefund, token: String): Result<Unit> = enqueue(
        refund.copy(
            syncStatus = com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity.SYNC_STATUS_SYNCING,
            claimToken = token,
            // 🔴 `claimed_at` SIEMPRE con valor: el rescate de claims caducados exige `claimed_at < ahora − lease`,
            // y con NULL la comparación es NULL — la fila quedaría SYNCING para siempre si el proceso muriera.
            claimedAt = System.currentTimeMillis(),
        )
    )

    override suspend fun unresolvedForPayment(originalPaymentId: String): List<QueuedRefund> =
        dao.unresolvedForPayment(originalPaymentId).map { it.toDomain() }

    override suspend fun getFailedCount(): Int = dao.getFailedCount()

    override suspend fun replay(refund: QueuedRefund): SyncOutcome {
        // 🔴 Sin `merchantAccountId` no hay NADA que reintentar (revisión 5-sep-2026).
        // `RefundRecorder` rechaza la fila con `IllegalArgumentException` ANTES de tocar la red, y
        // `classifySyncFailure` clasifica eso `Retryable` — con razón en general, pero aquí producía
        // el peor desenlace posible: la fila volvía a PENDING en CADA pasada del worker, para
        // siempre, bloqueando el cierre del turno (`blockingForVenue`) sin que nadie pudiera
        // reconocerla, porque sólo se reconocen los rechazos permanentes. Ningún reintento va a
        // inventar la cuenta del comerciante: es definitivo por construcción, se dice como tal, y el
        // cajero puede reconocerlo y conciliar a mano.
        //
        // Va aquí, y no sólo en el ViewModel que encola, porque éste es el único punto que alcanza a
        // las filas que YA existen en las terminales con el APK firmado.
        if (refund.merchantAccountId.isBlank()) {
            Timber.e("💸🔴 [RefundQueue] fila sin merchantAccountId — definitiva, no se reintenta | key=${refund.idempotencyKey}")
            return SyncOutcome.Permanent(
                "Sin cuenta de comerciante: la devolución SÍ se hizo en la terminal y hay que registrarla a mano"
            )
        }

        val resultado = runCatching {
            recorder.recordRefund(
                context = refund.toContext(),
                cardDetails = refund.toCardDetails(),
                // 🔴 Los del SDK, tal como se guardaron. El servidor deriva su huella de
                // idempotencia de (llave, pago original, monto, autorización, referencia): si aquí
                // se recalculara cualquiera, la huella cambiaría y el reintento nacería como un
                // reembolso NUEVO en vez de reconocerse como el mismo.
                authorizationNumber = refund.authorizationNumber,
                referenceNumber = refund.referenceNumber,
                tipRefundCents = refund.tipRefundCents,
                processor = if (refund.processor == ProcessorType.ANGELPAY) "angelpay" else null,
            )
        }.getOrElse { Result.failure(it) }

        return resultado.fold(
            onSuccess = { SyncOutcome.Synced },
            // Se clasifica con la MISMA función que los cobros. No se inventa una taxonomía propia:
            // 400/404/422 definitivos; 401/403 son la sesión y se reintentan; ante la duda, se
            // reintenta — porque dar por registrado un reembolso que no lo está es irreversible.
            onFailure = {
                Timber.w(it, "💸 [RefundQueue] replay falló | key=${refund.idempotencyKey} | ${it.message}")
                classifySyncFailure(it)
            },
        )
    }

    override suspend fun blockingForVenue(venueId: String): List<QueuedRefund> =
        dao.blockingForVenue(venueId).map { it.toDomain() }

    override suspend fun acknowledge(idempotencyKey: String, staffId: String?): Int =
        dao.acknowledge(idempotencyKey, by = staffId, at = System.currentTimeMillis()).also { publicarConteos() }

    override suspend fun claimBatch(limit: Int): List<QueuedRefund> {
        val ahora = System.currentTimeMillis()
        return dao.claimBatch(
            limit = limit,
            token = java.util.UUID.randomUUID().toString(),
            now = ahora,
            staleBefore = ahora - LEASE_MS,
        ).map { it.toDomain() }
    }

    override suspend fun markSuccess(idempotencyKey: String, token: String): Int =
        dao.markSuccess(idempotencyKey, token).also { publicarConteos() }

    override suspend fun release(idempotencyKey: String, token: String, retryCount: Int, error: String): Int =
        dao.release(idempotencyKey, token, retryCount, error).also { publicarConteos() }

    override suspend fun markPermanentlyFailed(idempotencyKey: String, token: String, error: String): Int =
        dao.markPermanentlyFailed(idempotencyKey, token, error).also { publicarConteos() }

    /**
     * 🔔 El banner del aparato se entera EN EL ACTO (QA Nexgo N86, 7-sep-2026). Antes sólo el
     * `PaymentSyncWorker` de 15 min informaba las devoluciones: una devolución encolada sin red era
     * invisible hasta la siguiente tanda, y el refresco de Inicio la borraba. Quien escribe la cola
     * publica sus conteos; contar nunca puede tirar la escritura que ya ocurrió.
     */
    private suspend fun publicarConteos() {
        runCatching { queueState.refreshRefundCounts(dao.getPendingCount(), dao.getFailedCount()) }
            .onFailure { Timber.w(it, "💸 [RefundQueue] No se pudieron publicar los conteos al banner") }
    }

    override suspend fun getPendingCount(): Int = dao.getPendingCount()

    override suspend fun deleteOldSuccess(daysAgo: Int): Int =
        dao.deleteOldSuccess(System.currentTimeMillis() - daysAgo * DIA_MS)

    // ─── Mapeos ─────────────────────────────────────────────────────────────────

    /** Reconstruye el contexto del SDK desde la fila, con su llave original intacta. */
    private fun QueuedRefund.toContext() = PaymentContext.RefundPayment(
        venueId = venueId,
        staffId = staffId,
        shiftId = null, // el turno lo resuelve el SERVIDOR; lo que mande el cliente se ignora
        amount = amount,
        merchantAccountId = merchantAccountId,
        blumonSerialNumber = blumonSerialNumber,
        idempotencyKey = idempotencyKey,
        originalPaymentId = originalPaymentId,
        originalOrderId = originalOrderId,
        originalTotalAmount = originalTotalAmount,
        refundReason = refundReason,
        isPartialRefund = isPartialRefund,
        originalOperationNumber = originalOperationNumber,
        tipRefundCents = tipRefundCents,
    )

    /**
     * Los datos de tarjeta son de AUDITORÍA, no de dinero: el servidor los guarda como referencia.
     * Un valor que este enum ya no reconozca cae a `UNKNOWN`/`OTHER` en vez de reventar — perder la
     * marca de la tarjeta es una molestia; perder el reembolso por no poder leerla sería el defecto
     * que esta cola viene a arreglar.
     */
    private fun QueuedRefund.toCardDetails() = CardDetails(
        maskedPan = maskedPan.orEmpty(),
        cardBrand = runCatching { CardBrand.valueOf(cardBrand.orEmpty()) }.getOrDefault(CardBrand.UNKNOWN),
        entryMode = runCatching { CardEntryMode.valueOf(entryMode) }.getOrDefault(CardEntryMode.OTHER),
    )

    companion object {
        /** Un claim abandonado más viejo que esto lo puede rescatar otro worker. */
        private const val LEASE_MS = 10 * 60 * 1000L

        private const val DIA_MS = 24 * 60 * 60 * 1000L
    }
}
