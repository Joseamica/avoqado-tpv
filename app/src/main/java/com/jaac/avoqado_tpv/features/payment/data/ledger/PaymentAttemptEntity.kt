package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * La Libreta — write-ahead ledger of card-charge attempts (spec 2026-07-17 §4).
 *
 * One row per attempt, keyed by the SAME UUID that travels to the backend as
 * `idempotencyKey`. Written BEFORE the SDK is invoked and at every durable
 * boundary, so a process death after bank approval (the Mindform $1,400 window:
 * "money moved, no record, no queue") always leaves evidence.
 *
 * NEVER feeds reports. NEVER blocks a charge (all writes are runCatching).
 */
@Entity(
    tableName = "payment_attempts",
    indices = [
        Index(value = ["state"]),
        Index(value = ["venue_id"]),
        Index(value = ["created_at"])
    ]
)
data class PaymentAttemptEntity(
    /** == idempotencyKey (paymentAttemptId). PK collision on a live row = attemptId reuse → double-charge signal. */
    @PrimaryKey @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    /** BLUMON | ANGELPAY */
    @ColumnInfo(name = "processor") val processor: String,
    /** SALE | REFUND (schema ready day-1; refund wiring ships later) */
    @ColumnInfo(name = "kind", defaultValue = "SALE") val kind: String = KIND_SALE,
    @ColumnInfo(name = "state") val state: String,
    /** Monotonic CAS counter — bumped on every accepted transition. */
    @ColumnInfo(name = "state_version", defaultValue = "0") val stateVersion: Int = 0,
    /** Centavos as Long — never decimal text (spec v2 correction). */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "tip_cents") val tipCents: Long,
    @ColumnInfo(name = "currency", defaultValue = "MXN") val currency: String = "MXN",
    /** FAST | ORDER | REFUND — recovery must replay the ORIGINAL route (spec §4.1). */
    @ColumnInfo(name = "recording_route") val recordingRoute: String,
    @ColumnInfo(name = "context_schema_version", defaultValue = "1") val contextSchemaVersion: Int = 1,
    /** Gson snapshot of the PaymentContext known BEFORE the charge (business data; card data lands in columns below). */
    @ColumnInfo(name = "payment_context_json") val paymentContextJson: String,
    // ── Host outcome (filled the instant the host responds) ──
    @ColumnInfo(name = "operation_id") val operationId: String? = null,
    @ColumnInfo(name = "reference_number") val referenceNumber: String? = null,
    @ColumnInfo(name = "auth_code") val authCode: String? = null,
    @ColumnInfo(name = "host_approved") val hostApproved: Boolean? = null,
    @ColumnInfo(name = "masked_pan") val maskedPan: String? = null,
    @ColumnInfo(name = "card_brand") val cardBrand: String? = null,
    @ColumnInfo(name = "entry_mode") val entryMode: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    // ── Recovery bookkeeping (Plan 3 uses these; schema ready day-1) ──
    @ColumnInfo(name = "verify_attempts", defaultValue = "0") val verifyAttempts: Int = 0,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        // States (spec §4.2). Spanish on purpose — they surface verbatim in ops tooling.
        const val STATE_PREPARANDO = "PREPARANDO"
        const val STATE_AUTORIZANDO = "AUTORIZANDO"
        const val STATE_HOST_RESPONDIO = "HOST_RESPONDIO"
        const val STATE_AUTORIZADO = "AUTORIZADO"
        const val STATE_REGISTRADO = "REGISTRADO"
        const val STATE_REGISTRO_FALLIDO = "REGISTRO_FALLIDO"
        const val STATE_ENTREGADA_A_COLA = "ENTREGADA_A_COLA"
        const val STATE_CERRADA = "CERRADA"
        const val STATE_DESCARTADA = "DESCARTADA"
        const val STATE_INDETERMINADO = "INDETERMINADO"

        const val PROCESSOR_BLUMON = "BLUMON"
        const val PROCESSOR_ANGELPAY = "ANGELPAY"
        const val KIND_SALE = "SALE"
        const val KIND_REFUND = "REFUND"
        const val ROUTE_FAST = "FAST"
        const val ROUTE_ORDER = "ORDER"
        const val ROUTE_REFUND = "REFUND"

        /** Non-terminal states = "money may have moved with no record" — the sweep watches these. */
        val OPEN_STATES = listOf(
            STATE_PREPARANDO, STATE_AUTORIZANDO, STATE_HOST_RESPONDIO,
            STATE_AUTORIZADO, STATE_REGISTRO_FALLIDO, STATE_INDETERMINADO
        )
    }
}
