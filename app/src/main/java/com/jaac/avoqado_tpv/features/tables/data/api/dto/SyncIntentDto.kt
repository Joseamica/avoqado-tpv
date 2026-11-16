package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.google.gson.JsonObject

/**
 * Contrato del replay offline — espejo EXACTO por nombre de
 * `avoqado-server src/services/mobile/sync.mobile.service.ts`
 * (`SyncIntentInput`/`SyncIntentAck`), reexportado bajo `/tpv` byte-idéntico
 * por `sync.tpv.controller.ts` (`export { syncIntents } from '../mobile/sync.mobile.controller'`).
 *
 * `payload`/`result` son `com.google.gson.JsonObject` (no `Map<String, Any>`):
 * la TPV usa Retrofit + Gson (`converter-gson`), a diferencia de
 * `avoqado-android` que usa kotlinx.serialization — mismo propósito
 * (payload de forma libre por tipo de intent), tipo distinto por el converter
 * del proyecto. `SyncIntentTypes` (las 14 constantes) NO vive aquí — es Task 3
 * (`features/tables/data/sync/SyncIntentTypes.kt`), dueña del outbox.
 */
data class SyncIntentInput(
    /** UUID generado en el dispositivo = idempotencyKey del server. */
    val id: String,
    /** Secuencia monotónica por dispositivo (orden de replay). Opcional en el wire. */
    val seq: Long? = null,
    val type: String,
    val payload: JsonObject,
    /** Reloj local al crear el intent — informativo, NUNCA ordena. */
    val createdAtLocal: Long? = null,
)

/** `POST /tpv/venues/{venueId}/sync/intents`. Body: `{ deviceId, intents }`. */
data class SyncIntentsRequest(
    val deviceId: String,
    val intents: List<SyncIntentInput>,
)

/**
 * Un ack por intent, en el MISMO orden en que se mandaron.
 *
 * - `ACKED` — aplicado, terminal.
 * - `REJECTED` — rechazo de negocio permanente → cuarentena visible, NUNCA se
 *   reintenta.
 * - `RETRY` — transitorio (hoy solo `VERSION_CONFLICT`): el cliente deja el
 *   intent PENDING y CORTA el batch ahí para preservar el FIFO. Convertir esto
 *   en `REJECTED` pierde el intent para siempre — fue un P1 real
 *   (`.claude/rules/offline-first-y-hub-lan.md` §2.2).
 *
 * `details` (opcional): datos del rechazo. Hoy sólo se lee `latestSeq` de `STALE_DEVICE_SEQUENCE` (D6, ver
 * [latestSeqDelRechazoDeNumero]).
 */
data class SyncIntentAck(
    val id: String,
    val status: String, // ACKED | REJECTED | RETRY
    val errorCode: String? = null,
    val message: String? = null,
    val result: JsonObject? = null,
    val details: JsonObject? = null,
) {
    val isAcked: Boolean get() = status == "ACKED"
    val isRejected: Boolean get() = status == "REJECTED"
    val isRetry: Boolean get() = status == "RETRY"

    /**
     * D6: el último número que el SERVIDOR ya vio de este aparato, cuando rechazó [sentSeq] por número
     * (`STALE_DEVICE_SEQUENCE`, `sync.mobile.service.ts`: rechaza ANTES de reservar, no aplicó nada). `null` si no es ese
     * rechazo, si no trae `latestSeq` entero o si dice algo imposible (menor que lo enviado): entonces se queda como un
     * rechazo normal.
     */
    fun latestSeqDelRechazoDeNumero(sentSeq: Long): Long? {
        if (!isRejected || errorCode != STALE_DEVICE_SEQUENCE) return null
        val raw = details?.get("latestSeq")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        val latest = raw.asBigDecimal.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 } ?: return null
        return runCatching { latest.longValueExact() }.getOrNull()?.takeIf { it >= sentSeq }
    }

    companion object {
        const val STALE_DEVICE_SEQUENCE = "STALE_DEVICE_SEQUENCE"
    }
}

/** `{ success, data: SyncIntentAck[] }`. */
data class SyncIntentsResponse(
    val success: Boolean = true,
    val data: List<SyncIntentAck> = emptyList(),
)
