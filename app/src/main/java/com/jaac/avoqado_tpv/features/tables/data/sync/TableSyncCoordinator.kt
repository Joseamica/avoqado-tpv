package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan C, Task 5 — reconcilia [TableSession] con los acks que [SyncOutbox]
 * aplica al reproducir el outbox de Mesas. Puerto deliberado de
 * `avoqado-android/tables/data/TableSyncCoordinator.kt`, adaptado a que
 * `SyncOutbox` de este repo (Task 3) NO expone un `Flow<SyncAck>` — en vez de
 * eso, [replay] envuelve [SyncOutbox.replayNow] pasándole un callback: la
 * persistencia del ack (ACKED/REJECTED/RETRY, cuarentena) sigue siendo
 * responsabilidad EXCLUSIVA de [SyncOutbox.applyAck] (Task 3, ya verificado
 * en hardware); este coordinador NUNCA reimplementa esa lógica — solo
 * reacciona DESPUÉS de que ya se persistió, para:
 *
 * - Ack de OPEN_TABLE (`ACKED`, `result` con `localOrderId`+`orderId`):
 *   promover la sesión provisional (UUID local → id real del server).
 * - Ack de cualquier otro intent (`ACKED`, `result.orderId` == la sesión
 *   activa): refrescar la `version` (CAS) para que la siguiente ronda no
 *   choque con un 409 fantasma.
 * - `REJECTED`/`RETRY`: NUNCA mutan [TableSession] — la cuarentena visible ya
 *   la garantiza `SyncOutbox` (`rejectedIntents`/Task 9); este coordinador
 *   solo loguea, jamás descarta ni reintenta por su cuenta (ver
 *   `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.2: convertir
 *   un RETRY en REJECTED, o al revés, pierde el intent o rompe el FIFO).
 */
@Singleton
class TableSyncCoordinator @Inject constructor(
    private val outbox: SyncOutbox,
    private val tableSession: TableSession,
) {

    /**
     * Reproduce el outbox de [venueId] ([SyncOutbox.replayNow]) y reconcilia
     * [TableSession] con cada ack, en el MISMO orden FIFO en que el server
     * los regresó. Hereda todas las garantías de [SyncOutbox.replayNow] sin
     * reimplementarlas: batch cortado en el primer `RETRY`, sin lanzar ante
     * fallo de red/HTTP, `CancellationException` intacta.
     */
    suspend fun replay(venueId: String) {
        outbox.replayNow(venueId) { intent, ack -> handleAck(intent, ack) }
    }

    private fun handleAck(intent: SyncIntentEntity, ack: SyncIntentAck) {
        when {
            ack.isAcked -> handleAcked(intent, ack)

            ack.isRejected -> Timber.w(
                "🚫 [TableSync] %s %s rechazado (%s): %s — cuarentena visible, sesión intacta",
                intent.type,
                intent.id,
                ack.errorCode,
                ack.message,
            )

            ack.isRetry -> Timber.d(
                "🔁 [TableSync] %s %s en RETRY (%s) — sigue PENDING, sesión intacta",
                intent.type,
                intent.id,
                ack.errorCode,
            )
        }
    }

    private fun handleAcked(intent: SyncIntentEntity, ack: SyncIntentAck) {
        val result = ack.result ?: return
        onAck(
            intentType = intent.type,
            localOrderId = result.stringOrNull("localOrderId"),
            serverOrderId = result.stringOrNull("orderId"),
            orderNumber = result.stringOrNull("orderNumber"),
            version = result.intOrNull("version"),
        )
    }

    /**
     * Reconciliación pública — testeable sin levantar Room/Retrofit (ver
     * `TableSyncCoordinatorTest`). Es el único punto que muta [TableSession]
     * en reacción a un ack:
     *
     * - `localOrderId` + `serverOrderId` presentes (OPEN_TABLE ACKED):
     *   [TableSession.promoteProvisional]. `intentType` se exige
     *   [SyncIntentTypes.OPEN_TABLE] a propósito — defensa extra para que
     *   ningún otro tipo de intent (que por accidente trajera ambos campos
     *   en su `result`) dispare una promoción que no le corresponde.
     * - Solo `serverOrderId`, y coincide con la sesión activa: refresca la
     *   `version`. Una orden distinta (otra mesa, otro dispositivo) nunca
     *   toca la sesión activa.
     */
    fun onAck(
        intentType: String,
        localOrderId: String?,
        serverOrderId: String?,
        orderNumber: String? = null,
        version: Int? = null,
    ) {
        when {
            intentType == SyncIntentTypes.OPEN_TABLE && localOrderId != null && serverOrderId != null ->
                tableSession.promoteProvisional(localOrderId, serverOrderId, orderNumber, version ?: 1)

            serverOrderId != null && tableSession.current()?.orderId == serverOrderId ->
                version?.let { tableSession.updateVersion(it) }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}
