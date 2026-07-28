package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentInput
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El outbox de Mesas — corazón del offline (Plan C, Task 3). Puerto deliberado de
 * `avoqado-android/core/data/sync/SyncOutbox.kt` (ya probado en producción): mismo
 * contrato de wire, mismas tres garantías de dinero/orden. Ver
 * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` antes de tocar esto.
 *
 * **Qué se reusó de la cola de pagos (Plan A) y por qué:**
 * - `NonCancellable` alrededor de la escritura del intent en [enqueue] — mismo
 *   patrón que `PaymentQueueRepositoryImpl.enqueue()`: el efecto de negocio (abrir
 *   mesa, mandar ronda, cobrar efectivo) ya ocurrió en la UI antes de llamar
 *   `enqueue()`; esta fila es la ÚNICA prueba de esa acción mientras no hay red, no
 *   trabajo opcional que se pueda perder si el ViewModel se cancela a media
 *   escritura.
 * - `catch (e: CancellationException) { throw e }` ANTES de cualquier `catch
 *   (e: Exception)` — `CancellationException` ES una `RuntimeException` en
 *   Kotlin; un catch desnudo se la traga. Ver `PaymentSyncWorker.markSyncedChecked`.
 * - Clasificación de fallo de red vs. servidor por código, nunca por texto —
 *   aquí no aplica `classifySyncFailure` directo (esa es la Task 4,
 *   `orQueueOffline`) pero [replayNow] seguía la misma disciplina: una excepción
 *   de red o un HTTP no-exitoso deja el batch PENDING; nunca se interpreta un
 *   fallo de transporte como un rechazo de negocio.
 *
 * **Qué se hizo DELIBERADAMENTE distinto de `avoqado-android`:**
 * - Sin lifecycle propio (`start()`/`stop()`, timer de seguridad, listener de
 *   conectividad). El "Produces" de la Task 3 es `enqueue` · `replayNow` ·
 *   `rejectedIntents` · `dismissRejected` — CUÁNDO llamar `replayNow` es decisión
 *   de la Task 4 (`TablesRepository.orQueueOffline`) y la Task 5
 *   (`TableSyncCoordinator`), que sí conocen la señal de conectividad de Mesas.
 *   Cablear eso aquí habría sido diseñar una tarea que todavía no existe.
 * - `deviceId` NO es un UUID generado y guardado en SharedPreferences propio
 *   (como hace android) — se REUSA el serial de terminal ya persistido por la
 *   cola de pagos (`SecureStorage.getSerialNumber()`, poblado en
 *   `PendingPaymentEntity.deviceSerialNumber` y usado en heartbeats/comandos
 *   remotos). Instrucción explícita de la Task 3: si el id cambiara al reiniciar,
 *   el mismo POS se vería como dos peers para el árbitro del hub LAN y para el
 *   reducer del server.
 * - `applyAck` es un método público de primera clase (no un detalle interno de
 *   `replayNow`) para que los tests de la Task 3 puedan ejercer las tres
 *   transiciones de ack sin levantar HTTP — y para que la Task 5
 *   (`TableSyncCoordinator`) pueda reaccionar a un ack sin duplicar la lógica de
 *   persistencia.
 */
@Singleton
class SyncOutbox @Inject constructor(
    private val dao: SyncIntentDao,
    private val api: TablesApiService,
    private val secureStorage: SecureStorage,
) {
    private val replayMutex = Mutex()
    private val gson = Gson()

    /**
     * Identidad del dispositivo para el server — REUSADA del outbox de pagos, ver
     * KDoc de la clase. `"UNKNOWN-DEVICE"` solo puede ocurrir si Mesas se usa
     * antes de que la terminal se active (no debería pasar: abrir/operar una mesa
     * ya requiere sesión de venue), pero un fallback explícito es preferible a un
     * NPE en medio de una ronda.
     */
    private val deviceId: String
        get() = secureStorage.getSerialNumber() ?: "UNKNOWN-DEVICE"

    /**
     * Escribe el intent ANTES de cualquier efecto (write-ahead) y devuelve su id.
     *
     * Ese id ES el `idempotencyKey` que el server usa tal cual — ver
     * `applyPayCash` en `sync.mobile.service.ts`, que pasa `intent.id` directo a
     * `payCashOrder({ idempotencyKey: intent.id })`. Un PAY_CASH que se reproduce
     * (retry de red, batch reenviado) nunca duplica el cobro porque el id nunca
     * cambia entre reintentos: se asigna UNA vez aquí y de ahí en adelante viaja
     * tal cual en cada replay ([toWire]).
     *
     * @return el id del intent (UUID = idempotencyKey).
     */
    suspend fun enqueue(venueId: String, type: String, payload: JsonObject): String {
        val id = UUID.randomUUID().toString()
        val entity = SyncIntentEntity(
            id = id,
            venueId = venueId,
            seq = 0, // reemplazado dentro de insertWithSeq, dentro de la misma transacción
            type = type,
            payloadJson = gson.toJson(payload),
        )
        val seq = withContext(NonCancellable + Dispatchers.IO) {
            dao.insertWithSeq(entity)
        }
        Timber.i("📥 [Tables] Encolado %s seq=%d id=%s venue=%s", type, seq, id, venueId)
        return id
    }

    /**
     * Aplica el ack del server a un intent.
     *
     * - `ACKED`/`REJECTED` → estados terminales, se persisten.
     * - `RETRY` (hoy solo `VERSION_CONFLICT`) → NUNCA se persiste. El intent se
     *   deja tal cual (PENDING); el próximo `replayNow` lo reintenta solo.
     *   Convertir esto en `REJECTED` perdería el intent PARA SIEMPRE — fue un P1
     *   real (`.claude/rules/offline-first-y-hub-lan.md` §2.2).
     * - Cualquier otro string → no-op defensivo (se queda PENDING). Un server más
     *   nuevo que mande un status que este cliente no conoce NUNCA debe
     *   interpretarse como éxito ni como rechazo permanente por default.
     *
     * Reproducir el mismo ack dos veces (mismo `intentId`, mismo `status`) tiene
     * un solo efecto: es un `UPDATE ... WHERE id = :id`, no un `INSERT` — la
     * segunda llamada solo re-escribe los mismos valores.
     */
    suspend fun applyAck(
        intentId: String,
        status: String,
        errorCode: String?,
        message: String? = null,
        resultJson: String? = null,
    ) {
        when (status) {
            SyncIntentEntity.STATUS_ACKED, SyncIntentEntity.STATUS_REJECTED -> {
                persistTerminalAck(intentId, status, errorCode, message, resultJson)
                if (status == SyncIntentEntity.STATUS_REJECTED) {
                    Timber.w("🚫 [Tables] Intent %s RECHAZADO: %s — %s", intentId, errorCode, message)
                }
            }

            STATUS_RETRY -> {
                Timber.d(
                    "🔁 [Tables] Intent %s en RETRY (%s) — sigue PENDING, se reintenta solo",
                    intentId,
                    errorCode,
                )
            }

            else -> {
                Timber.w(
                    "⚠️ [Tables] Ack con status desconocido '%s' para intent %s — ignorado, se queda PENDING",
                    status,
                    intentId,
                )
            }
        }
    }

    /**
     * Escritura del ack terminal, aislada para poder blindarla igual que
     * `PaymentQueueRepositoryImpl.markSynced`: `CancellationException` se
     * repropaga SIN tocar la fila (un worker/caller cancelado no debe dejar un
     * ack a medio escribir); cualquier otra excepción se traga y se loguea — la
     * fila se queda PENDING, y como el `id` es el idempotencyKey, el próximo
     * replay vuelve a mandar el MISMO intent: el server lo dedupea y regresa el
     * mismo ack (ver dedup en `processIntents`), así que reintentar aquí es
     * seguro, nunca duplica el efecto.
     */
    private suspend fun persistTerminalAck(
        intentId: String,
        status: String,
        errorCode: String?,
        message: String?,
        resultJson: String?,
    ) {
        try {
            dao.resolve(intentId, status, errorCode, message, resultJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] No se pudo persistir el ack de %s — queda PENDING, se reintenta", intentId)
        }
    }

    /**
     * Reproduce el outbox de [venueId] contra el reducer: batch FIFO por `seq`,
     * `BATCH_SIZE` a la vez.
     *
     * Un fallo de RED o un HTTP no-exitoso deja el batch INTACTO (todo sigue
     * PENDING) y retorna sin lanzar — "red caída ⇒ outbox, nunca un error"
     * también aplica al lado del replay: quien haya disparado esta llamada
     * (Task 4/5) no debe tratar esto como una falla, solo como "todavía no".
     *
     * Al primer ack `RETRY` la reproducción se CORTA: el server ya dejó de
     * procesar el resto del batch ahí mismo (ver `processIntents` — corta FIFO
     * porque los intents posteriores dependen del que quedó en RETRY), así que
     * `response.data` nunca trae acks después de uno RETRY. Cortar aquí evita
     * además re-consultar el mismo PENDING en un hot-loop.
     *
     * `CancellationException` se repropaga sin tocar nada — el batch se queda
     * como estaba, listo para el próximo trigger.
     */
    suspend fun replayNow(venueId: String) {
        replayMutex.withLock {
            while (true) {
                val batch = dao.pendingForVenue(venueId).take(BATCH_SIZE)
                if (batch.isEmpty()) return

                val request = SyncIntentsRequest(
                    deviceId = deviceId,
                    intents = batch.map { it.toWire() },
                )

                val response = try {
                    val httpResponse = api.syncIntents(venueId, request)
                    if (!httpResponse.isSuccessful) {
                        Timber.w(
                            "⚠️ [Tables] Replay interrumpido — HTTP %d (%d pendientes, venue=%s)",
                            httpResponse.code(),
                            batch.size,
                            venueId,
                        )
                        return
                    }
                    httpResponse.body()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [Tables] Replay interrumpido (sin red, %d pendientes, venue=%s)", batch.size, venueId)
                    return
                }

                if (response == null) {
                    Timber.w("⚠️ [Tables] sync/intents respondió sin cuerpo — se reintenta después (venue=%s)", venueId)
                    return
                }

                var sawRetry = false
                for (ack in response.data) {
                    applyAck(
                        intentId = ack.id,
                        status = ack.status,
                        errorCode = ack.errorCode,
                        message = ack.message,
                        resultJson = ack.result?.let { gson.toJson(it) },
                    )
                    if (ack.isRetry) {
                        sawRetry = true
                        break // el server ya cortó aquí — no hay más acks después de un RETRY
                    }
                }
                Timber.i("✅ [Tables] Replay de %d intents aplicado (venue=%s)", batch.size, venueId)
                if (sawRetry) return // no hot-loop sobre el mismo PENDING
            }
        }
    }

    // MARK: - Cuarentena

    /** Un intent rechazado, ya legible para la pantalla de resolución (Task 9). */
    data class RejectedIntent(
        val id: String,
        val type: String,
        val errorCode: String?,
        val message: String?,
        val createdAt: Long,
    )

    suspend fun rejectedIntents(venueId: String): List<RejectedIntent> =
        dao.rejectedForVenue(venueId).map {
            RejectedIntent(id = it.id, type = it.type, errorCode = it.errorCode, message = it.message, createdAt = it.createdAt)
        }

    /** El mesero/gerente descartó un rechazo tras resolverlo a mano. */
    suspend fun dismissRejected(venueId: String, id: String) {
        dao.dismiss(id, venueId)
    }

    private fun SyncIntentEntity.toWire(): SyncIntentInput = SyncIntentInput(
        id = id,
        seq = seq,
        type = type,
        payload = gson.fromJson(payloadJson, JsonObject::class.java),
        createdAtLocal = createdAt,
    )

    private companion object {
        const val BATCH_SIZE = 50

        /**
         * NO vive en [SyncIntentEntity] porque `RETRY` nunca se persiste — no es
         * un valor válido de la columna `status`, solo un status transitorio del
         * wire (`SyncIntentAck.status`).
         */
        const val STATUS_RETRY = "RETRY"
    }
}
