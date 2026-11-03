package com.jaac.avoqado_tpv.features.verification.data

import android.content.Context
import androidx.room.withTransaction
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.ProofOfSaleResponse
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure
import com.jaac.avoqado_tpv.features.verification.data.local.ActionableSaleCacheDao
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueDao
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ProofSlot(val backendLabel: String) { VINCULACION("Vinculacion"), PORTABILIDAD("Portabilidad") }

data class PhotoCapture(
    val venueId: String,
    /** No se guarda: la v45 no tiene la columna; la ruta de Firebase usa la tienda de la sesión (ver [ProofOfSaleQueueRepository.drain]). */
    val venueSlug: String?,
    val staffId: String,
    val paymentId: String,
    val verificationId: String?,
    val slot: ProofSlot,
    val sourcePath: String,
    val number: String?,
)

data class NumberCapture(
    val venueId: String,
    val staffId: String,
    val paymentId: String,
    val verificationId: String,
    val slot: ProofSlot,
    val number: String,
)

/** `outcome`: APPLIED | SUPERSEDED_BY_NEWER | SALE_ALREADY_APPROVED | NUMBER_DISABLED | SALE_REJECTED | PERMANENT. */
data class ProofSyncResult(val key: String, val outcome: String, val linkingDuplicate: Boolean, val portedDuplicate: Boolean)

/**
 * Cola durable de la foto de comprobante y del número de vinculación (PlayTelecom). Ninguna pantalla sube por su
 * cuenta: encola (archivo primero, fila después, sin red de por medio) y pide `runNow`; la subida la hace
 * `PaymentSyncWorker` con [drain], un intento por fila y por corrida, CAS por `claim_token`.
 *
 * Desenlace de cada fila: por el `code` del cuerpo del error primero y por el status sólo si no hay uno conocido
 * (tabla en el reporte de C4). Lo que una persona debe ver se queda visible (NEEDS_ATTENTION / FAILED) hasta
 * «Descartar»; sólo lo entregado (APPLIED, SUPERSEDED_BY_NEWER) se borra solo.
 */
@Singleton
class ProofOfSaleQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AvoqadoDatabase,
    private val queueDao: ProofOfSaleQueueDao,
    private val cacheDao: ActionableSaleCacheDao,
    private val remote: ProofOfSaleRemote,
    private val uploader: VerificationUploadManager,
    private val sequence: ProofCaptureSequence,
    private val secureStorage: SecureStorage,
) {
    // DROP_OLDEST: el worker nunca espera a una pantalla lenta.
    private val _results = MutableSharedFlow<ProofSyncResult>(replay = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val results: SharedFlow<ProofSyncResult> = _results.asSharedFlow()

    /** Copia la foto a `filesDir/proof_of_sale/{key}.jpg`, y sólo entonces inserta la fila. No toca la red. */
    suspend fun enqueuePhoto(c: PhotoCapture): String = withContext(NonCancellable + Dispatchers.IO) {
        val key = UUID.randomUUID().toString()
        val file = File(File(context.filesDir, DIR), "$key.jpg")
        File(c.sourcePath).copyTo(file, overwrite = true)
        insert(key, KIND_PHOTO, c.venueId, c.staffId, c.paymentId, c.verificationId, c.slot, file.absolutePath, c.number)
    }

    suspend fun enqueueNumberOnly(c: NumberCapture): String = withContext(NonCancellable + Dispatchers.IO) {
        insert(UUID.randomUUID().toString(), KIND_NUMBER_ONLY, c.venueId, c.staffId, c.paymentId, c.verificationId, c.slot, null, c.number)
    }

    fun observeStaff(staffId: String): Flow<List<ProofOfSaleQueueEntity>> = queueDao.observeStaff(staffId)

    /** null = no llegó a tiempo: «Se subirá sola». */
    suspend fun awaitResult(key: String, timeoutMs: Long = 10_000): ProofSyncResult? =
        withTimeoutOrNull(timeoutMs) { results.first { it.key == key } }

    /** Sólo lo que ya no se va a subir solo (FAILED o NEEDS_ATTENTION): borra la fila y su archivo. */
    suspend fun discard(key: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            val row = queueDao.observeKey(key).first() ?: return@withContext
            if (row.syncStatus != STATUS_FAILED && row.syncStatus != STATUS_NEEDS_ATTENTION) return@withContext
            queueDao.delete(key)
            row.localPath?.let { File(it).delete() }
        }
    }

    /**
     * Un intento por fila reclamada, de cualquier tienda (el servidor busca la venta en la organización del token).
     * Una fila que truena se suelta y no frena a las demás.
     */
    suspend fun drain(limit: Int = 10) {
        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        for (row in queueDao.claimBatch(limit, token, now, now - LEASE_MS)) {
            try {
                sync(row, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "📸 [ProofQueue] Fallo inesperado | key=${row.idempotencyKey}")
                runCatching { queueDao.release(row.idempotencyKey, token, row.retryCount + 1, e.message ?: "error inesperado") }
            }
        }
    }

    private suspend fun insert(
        key: String, kind: String, venueId: String, staffId: String, paymentId: String, verificationId: String?,
        slot: ProofSlot, localPath: String?, number: String?,
    ): String {
        val now = System.currentTimeMillis()
        queueDao.insertRow(
            ProofOfSaleQueueEntity(
                idempotencyKey = key, kind = kind, venueId = venueId, staffId = staffId, paymentId = paymentId,
                verificationId = verificationId, slot = slot.name, localPath = localPath,
                linkingNumber = number.takeIf { slot == ProofSlot.VINCULACION },
                portedNumber = number.takeIf { slot == ProofSlot.PORTABILIDAD },
                capturedAt = now, captureSeq = sequence.next(), captureDeviceId = sequence.deviceId(), createdAt = now,
            ),
        )
        // La fila ya está guardada: si WorkManager no responde, la sube el siguiente disparo (red de vuelta o 15 min).
        runCatching { PaymentSyncScheduler.runNow(context) }
        return key
    }

    private suspend fun sync(row: ProofOfSaleQueueEntity, token: String) {
        val response = try {
            if (row.kind == KIND_NUMBER_ONLY) remote.submitNumber(row) else remote.submitPhoto(row, uploadPhoto(row, token) ?: return)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return onError(row, token, e)
        }
        val numberDisabled = response.linkingNumberOutcome == DISABLED || response.portedNumberOutcome == DISABLED
        when {
            response.outcome == SALE_ALREADY_APPROVED -> needsAttention(row, token, SALE_ALREADY_APPROVED, response)
            numberDisabled -> needsAttention(row, token, NUMBER_DISABLED, response)
            else -> {
                // La ranura entregada y la fila borrada, juntas: si el proceso muere a la mitad no queda una sin la otra.
                db.withTransaction {
                    markDelivered(row)
                    queueDao.delete(row.idempotencyKey)
                }
                row.localPath?.let { File(it).delete() }
                emit(row, response.outcome ?: APPLIED, response) // un 2xx sin outcome (servidor viejo) guardó la foto como hoy
            }
        }
    }

    /** La URL de Firebase, o null si la fila ya quedó resuelta (soltada o permanente). */
    private suspend fun uploadPhoto(row: ProofOfSaleQueueEntity, token: String): String? {
        val path = row.localPath
        if (path == null || !File(path).exists()) {
            fail(row, token, PERMANENT, "La foto ya no está en la terminal")
            return null
        }
        // ponytail: la fila no guarda el slug de su tienda (la v45 no tiene la columna), así que va la tienda de la sesión.
        // La carpeta sólo es nombre: el servidor guarda la URL. Si importa, columna `venue_slug` en una migración.
        val slug = secureStorage.getVenueSlug() ?: run {
            release(row, token, "Sin tienda en la sesión para guardar la foto")
            return null
        }
        val fecha = STORAGE_DATE.format(Instant.ofEpochMilli(row.capturedAt))
        return uploader.uploadToPath(path, "venues/$slug/proof-of-sale/$fecha/${row.paymentId}_${row.slot}_${row.idempotencyKey}.jpg")
            .getOrElse {
                release(row, token, "No se pudo subir la foto: ${it.message}")
                return null
            }
    }

    /** Por `code` primero; por status sólo si el código no se conoce o no viene. Ante la duda, se reintenta. */
    private suspend fun onError(row: ProofOfSaleQueueEntity, token: String, e: Exception) {
        val why = e.message ?: "error"
        when ((e as? BackendHttpException)?.errorCode) {
            SALE_REJECTED -> return fail(row, token, SALE_REJECTED, why)
            LINKING_NUMBER_DISABLED -> return needsAttention(row, token, NUMBER_DISABLED, null)
            VERIFICATION_MOVED, MODULE_DISABLED -> return release(row, token, why)
            INVALID_LINKING_NUMBER, CAPTURE_REQUIRED -> return fail(row, token, PERMANENT, why)
        }
        when {
            // Otra organización (la terminal cambió): no es definitivo, se sube cuando vuelva una sesión de su tienda.
            e is BackendHttpException && e.statusCode == 404 && row.venueId != secureStorage.getVenueId() ->
                release(row, token, WAITING_FOR_STORE)
            classifySyncFailure(e) is SyncOutcome.Permanent -> fail(row, token, PERMANENT, why) // otro 400, 404 propio, 422
            else -> release(row, token, why) // red, 401/403/408/409/426/429, 5xx, 2xx vacío
        }
    }

    private suspend fun release(row: ProofOfSaleQueueEntity, token: String, why: String) {
        Timber.w("📸 [ProofQueue] Se reintenta | key=${row.idempotencyKey} | $why")
        queueDao.release(row.idempotencyKey, token, row.retryCount + 1, why)
    }

    private suspend fun fail(row: ProofOfSaleQueueEntity, token: String, outcome: String, why: String) {
        Timber.e("📸 [ProofQueue] Definitivo $outcome | key=${row.idempotencyKey} | $why")
        queueDao.markPermanentlyFailed(row.idempotencyKey, token, outcome, why)
        emit(row, outcome, null)
    }

    private suspend fun needsAttention(row: ProofOfSaleQueueEntity, token: String, outcome: String, response: ProofOfSaleResponse?) {
        db.withTransaction {
            if (outcome == NUMBER_DISABLED) markDelivered(row) // la foto sí entró; sólo el número no
            queueDao.markNeedsAttention(row.idempotencyKey, token, outcome)
        }
        emit(row, outcome, response)
    }

    /** Suma la ranura a `delivered_slots` de la venta guardada (sólo filas PHOTO). Va dentro de la transacción del llamador. */
    private suspend fun markDelivered(row: ProofOfSaleQueueEntity) {
        if (row.kind != KIND_PHOTO) return
        val sale = cacheDao.get(row.paymentId) ?: return
        val slots = (sale.deliveredSlots.split(',').filter { it.isNotBlank() } + row.slot).distinct().joinToString(",")
        cacheDao.setDeliveredSlots(row.paymentId, slots)
    }

    private fun emit(row: ProofOfSaleQueueEntity, outcome: String, response: ProofOfSaleResponse?) {
        _results.tryEmit(
            ProofSyncResult(row.idempotencyKey, outcome, response?.linkingDuplicate == true, response?.portedDuplicate == true),
        )
    }

    private companion object {
        const val DIR = "proof_of_sale"
        const val KIND_PHOTO = "PHOTO"
        const val KIND_NUMBER_ONLY = "NUMBER_ONLY"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_NEEDS_ATTENTION = "NEEDS_ATTENTION"

        // Desenlaces (espejo por nombre EXACTO de sale-verification.service.ts del SERVIDOR).
        const val APPLIED = "APPLIED"
        const val SALE_ALREADY_APPROVED = "SALE_ALREADY_APPROVED"
        const val DISABLED = "DISABLED"
        const val NUMBER_DISABLED = "NUMBER_DISABLED"
        const val PERMANENT = "PERMANENT"
        const val SALE_REJECTED = "SALE_REJECTED"
        const val LINKING_NUMBER_DISABLED = "LINKING_NUMBER_DISABLED"
        const val VERIFICATION_MOVED = "VERIFICATION_MOVED"
        const val MODULE_DISABLED = "MODULE_DISABLED"
        const val INVALID_LINKING_NUMBER = "INVALID_LINKING_NUMBER"
        const val CAPTURE_REQUIRED = "CAPTURE_REQUIRED"

        const val WAITING_FOR_STORE = "Esperando una sesión de su tienda"

        /** Un claim abandonado más viejo que esto lo puede rescatar otro worker (como los reembolsos). */
        const val LEASE_MS = 10 * 60 * 1000L

        /** Ruta de Firebase (no la ve nadie): zona fija para que la carpeta no dependa del aparato. */
        val STORAGE_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("America/Mexico_City"))
    }
}
