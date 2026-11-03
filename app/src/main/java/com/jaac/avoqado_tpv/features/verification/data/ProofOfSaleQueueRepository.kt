package com.jaac.avoqado_tpv.features.verification.data

import android.content.Context
import androidx.room.withTransaction
import com.jaac.avoqado_tpv.core.data.firebase.UnreadablePhotoException
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
    /** Se guarda en la fila: la foto va a la carpeta de SU tienda aunque se suba con la sesión en otra. */
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
 * cuenta: encola (archivo primero, fila después, sin red de por medio) y pide otra pasada del worker; la subida la hace
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
        insert(key, KIND_PHOTO, c.venueId, c.venueSlug, c.staffId, c.paymentId, c.verificationId, c.slot, file.absolutePath, c.number)
    }

    suspend fun enqueueNumberOnly(c: NumberCapture): String = withContext(NonCancellable + Dispatchers.IO) {
        insert(UUID.randomUUID().toString(), KIND_NUMBER_ONLY, c.venueId, null, c.staffId, c.paymentId, c.verificationId, c.slot, null, c.number)
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
     * Una fila que truena se suelta y no frena a las demás. Al final barre los archivos huérfanos.
     *
     * @return true si reclamó una tanda llena Y resolvió al menos una: puede haber más, que el worker pida otra pasada.
     *   Una tanda llena que sólo se soltó (diez filas de otra organización) devuelve false: otra pasada sería un bucle.
     */
    suspend fun drain(limit: Int = 10): Boolean {
        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        val rows = queueDao.claimBatch(limit, token, now, now - LEASE_MS)
        var resolved = 0
        for (row in rows) {
            val done = try {
                sync(row, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "📸 [ProofQueue] Fallo inesperado | key=${row.idempotencyKey}")
                runCatching { queueDao.release(row.idempotencyKey, token, row.retryCount + 1, e.message ?: "error inesperado") }
                false
            }
            if (done) resolved++
        }
        sweepOrphans()
        return rows.size == limit && resolved > 0
    }

    private suspend fun insert(
        key: String, kind: String, venueId: String, venueSlug: String?, staffId: String, paymentId: String, verificationId: String?,
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
                venueSlug = venueSlug,
            ),
        )
        // La fila ya está guardada. APPEND y no runNow/KEEP: con un worker a media pasada (ya después de las fotos), KEEP
        // descartaba el pedido y la foto esperaba al periódico de 15 min. Si WorkManager no responde, la sube el siguiente disparo.
        runCatching { PaymentSyncScheduler.continuarDespuesDeEstaPasada(context) }
        return key
    }

    /** @return true si la fila quedó resuelta (entregada, a la vista o definitiva); false si se soltó para reintentar. */
    private suspend fun sync(row: ProofOfSaleQueueEntity, token: String): Boolean {
        val response = try {
            if (row.kind == KIND_NUMBER_ONLY) remote.submitNumber(row) else remote.submitPhoto(row, uploadPhoto(row, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClaimLostException) {
            Timber.w("📸 [ProofQueue] Otro worker ya tiene la fila; no se hace el POST | key=${row.idempotencyKey}")
            return false // la fila es de su nuevo dueño: ni release ni desenlace
        } catch (e: Exception) {
            return onError(row, token, e)
        }
        val numberDisabled = response.linkingNumberOutcome == DISABLED || response.portedNumberOutcome == DISABLED
        return when {
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
                true
            }
        }
    }

    /**
     * La URL de Firebase. Si ya se subió en un intento anterior, la misma sin volver a subir. Lanza
     * [UnreadablePhotoException] si el archivo local falta, está vacío o no se decodifica (definitivo), y cualquier
     * otra cosa si es Firebase o no hay tienda (se reintenta): el reparto lo hace [onError].
     */
    private suspend fun uploadPhoto(row: ProofOfSaleQueueEntity, token: String): String {
        row.photoUrl?.let { return it }
        val path = row.localPath?.takeIf { File(it).length() > 0 }
            ?: throw UnreadablePhotoException("La foto ya no está en la terminal o está vacía")
        val slug = row.venueSlug ?: secureStorage.getVenueSlug() ?: throw IllegalStateException("Sin tienda para guardar la foto")
        val fecha = STORAGE_DATE.format(Instant.ofEpochMilli(row.capturedAt))
        val url = uploader.uploadToPath(path, "venues/$slug/proof-of-sale/$fecha/${row.paymentId}_${row.slot}_${row.idempotencyKey}.jpg")
            .getOrThrow()
        // 0 = venció el lease y otro worker reclamó la fila a media subida: el POST es suyo.
        if (queueDao.savePhotoUrl(row.idempotencyKey, token, url) == 0) throw ClaimLostException()
        return url
    }

    private class ClaimLostException : Exception()

    /** Por `code` primero; por status sólo si el código no se conoce o no viene. Ante la duda, se reintenta. */
    private suspend fun onError(row: ProofOfSaleQueueEntity, token: String, e: Exception): Boolean {
        val why = e.message ?: "error"
        if (e is UnreadablePhotoException) return fail(row, token, PERMANENT, why)
        when ((e as? BackendHttpException)?.errorCode) {
            SALE_REJECTED -> return fail(row, token, SALE_REJECTED, why)
            LINKING_NUMBER_DISABLED -> return needsAttention(row, token, NUMBER_DISABLED, null)
            VERIFICATION_MOVED, MODULE_DISABLED -> return release(row, token, why)
            INVALID_LINKING_NUMBER, CAPTURE_REQUIRED -> return fail(row, token, PERMANENT, why)
        }
        val notFound = e is BackendHttpException && e.statusCode == 404
        return when {
            // Otra organización (la terminal cambió) u otro promotor: no es definitivo, se sube cuando vuelva su sesión.
            notFound && row.venueId != secureStorage.getVenueId() -> release(row, token, WAITING_FOR_STORE)
            notFound && row.staffId != secureStorage.getStaffId() -> release(row, token, WAITING_FOR_STAFF)
            classifySyncFailure(e) is SyncOutcome.Permanent -> fail(row, token, PERMANENT, why) // otro 400, 404 propio, 422
            else -> release(row, token, why) // red, Firebase, sin tienda, 401/403/408/409/426/429, 5xx, 2xx vacío
        }
    }

    private suspend fun release(row: ProofOfSaleQueueEntity, token: String, why: String): Boolean {
        Timber.w("📸 [ProofQueue] Se reintenta | key=${row.idempotencyKey} | $why")
        queueDao.release(row.idempotencyKey, token, row.retryCount + 1, why)
        return false
    }

    private suspend fun fail(row: ProofOfSaleQueueEntity, token: String, outcome: String, why: String): Boolean {
        Timber.e("📸 [ProofQueue] Definitivo $outcome | key=${row.idempotencyKey} | $why")
        queueDao.markPermanentlyFailed(row.idempotencyKey, token, outcome, why)
        emit(row, outcome, null)
        return true
    }

    private suspend fun needsAttention(row: ProofOfSaleQueueEntity, token: String, outcome: String, response: ProofOfSaleResponse?): Boolean {
        db.withTransaction {
            if (outcome == NUMBER_DISABLED) markDelivered(row) // la foto sí entró; sólo el número no
            queueDao.markNeedsAttention(row.idempotencyKey, token, outcome)
        }
        emit(row, outcome, response)
        return true
    }

    /** Archivos de `proof_of_sale/` sin fila, de más de una hora (uno recién copiado todavía no tiene la suya). */
    private suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        runCatching {
            val files = File(context.filesDir, DIR).listFiles() ?: return@runCatching
            val inQueue = queueDao.localPaths().toSet()
            val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
            files.filter { it.absolutePath !in inQueue && it.lastModified() < cutoff }.forEach { it.delete() }
        }.onFailure { Timber.w(it, "📸 [ProofQueue] No se pudieron barrer los archivos huérfanos") }
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
        const val WAITING_FOR_STAFF = "Esperando la sesión de quien la tomó"

        /** Un archivo sin fila más viejo que esto ya no está a medio encolar (copiar y luego insertar tarda milisegundos). */
        const val ORPHAN_AGE_MS = 60 * 60 * 1000L

        /** Un claim abandonado más viejo que esto lo puede rescatar otro worker (como los reembolsos). */
        const val LEASE_MS = 10 * 60 * 1000L

        /** Ruta de Firebase (no la ve nadie): zona fija para que la carpeta no dependa del aparato. */
        val STORAGE_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("America/Mexico_City"))
    }
}
