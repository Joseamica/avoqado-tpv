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
 * Never feeds financial reports. Persistence is a mandatory pre-authorization barrier.
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
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * 🔴 Fila que escribió una versión ANTERIOR a Room v34 (la libreta en modo SHADOW de 2.9.x).
     *
     * Esas versiones no reservaban la terminal y su semántica de estados era otra: en 2.9.2 el
     * contactless entraba al kernel con la fila en PREPARANDO (no existía KERNEL_ACTIVO) y un `U101`
     * terminaba en DESCARTADA. Por eso una fila heredada:
     *  - NO reserva la terminal ([PaymentAttemptDao.reserveTerminal], `casTransition` a AUTORIZANDO,
     *    `findTerminalHold`, `findUnresolvedCharge`) — en 2.9.2 no reservaba;
     *  - SIGUE visible como obligación que conciliar (conteos y el log del barrido);
     *  - NUNCA se libera por antigüedad (`discardStalePreparing`) — su PREPARANDO no prueba nada;
     *  - NUNCA acredita un «no se cobró» (el CAS del cancel y el desenlace negativo la tratan como
     *    bloqueadora).
     * La migración 33→34 la pone en 1 para TODAS las filas existentes; nada del APK nuevo la escribe en 1.
     */
    @ColumnInfo(name = "legacy_shadow", defaultValue = "0") val legacyShadow: Boolean = false,

    // ── Checkpoint 2 (webhook como primer confirmador), Room v35. Todas ADITIVAS y nulas/0 por default. ──
    /**
     * La solicitud del POS (`terminalPaymentRequestId` del contexto) como COLUMNA, para que la recuperación
     * por servidor (N3) seleccione candidatas sin buscar dentro de `payment_context_json`. La escribe
     * `reserveTerminal` y la migración 34→35 la rellena para las filas que ya existían.
     */
    @ColumnInfo(name = "terminal_payment_request_id") val terminalPaymentRequestId: String? = null,
    /**
     * Lo que el SERVIDOR acreditó de este intento (S5 `terminal:payment_confirmed`, S6 `GET …/attempts/:attemptId`
     * o el 2xx del REST): `server_outcome` ∈ RECORDED · SECOND_CAPTURE_EVIDENCE · REFERENCE_COLLISION_EVIDENCE ·
     * PENDING_EVIDENCE. Es EVIDENCIA durable: una fila con `server_payment_id` no se poda por tiempo y veta cualquier
     * desenlace negativo de su solicitud. `server_recorded_via` es lo que dijo el servidor (`webhook`/`terminal`),
     * nunca inferido del canal por el que llegó el veredicto.
     */
    @ColumnInfo(name = "server_payment_id") val serverPaymentId: String? = null,
    @ColumnInfo(name = "server_outcome") val serverOutcome: String? = null,
    @ColumnInfo(name = "server_recorded_via") val serverRecordedVia: String? = null,
    @ColumnInfo(name = "server_amount_cents") val serverAmountCents: Long? = null,
    @ColumnInfo(name = "server_tip_cents") val serverTipCents: Long? = null,
    @ColumnInfo(name = "server_verdict_at") val serverVerdictAt: Long? = null,
    /** El Payment que cerró la SOLICITUD según el servidor (E4); null si el veredicto no acredita ganador. */
    @ColumnInfo(name = "server_winner_payment_id") val serverWinnerPaymentId: String? = null,
    /** Última consulta S6 de esta fila y cuántas van: el lote de N3 avanza (las consultadas van al final) y se espacia. */
    @ColumnInfo(name = "server_checked_at") val serverCheckedAt: Long? = null,
    @ColumnInfo(name = "server_check_count", defaultValue = "0") val serverCheckCount: Int = 0,
) {
    companion object {
        // States (spec §4.2). Spanish on purpose — they surface verbatim in ops tooling.
        const val STATE_PREPARANDO = "PREPARANDO"

        /**
         * 🔴 Una llamada NATIVA capaz de aprobar por su cuenta ya empezó (kernel contactless/EMV).
         *
         * Existe porque PREPARANDO promete algo muy concreto —«ninguna llamada capaz de autorizar
         * empezó»— y ese es justo el permiso que [STATE_DESCARTADA] necesita para afirmar «no se
         * cobró». El contactless de la PAX entra al kernel SIN pasar por [STATE_AUTORIZANDO] (ese
         * tramo es sólo del camino online), así que sin este estado un `RESULT_OFFLINE_APPROVED`
         * seguido de una muerte del proceso dejaba la fila diciendo PREPARANDO: dinero movido sobre
         * una fila que afirma que nada empezó, y descartable como prueba PRE_AUTHORIZATION.
         *
         * Desde aquí NO se puede descartar. Sólo se sale por veredicto real (host/aprobación) o,
         * si el proceso muere, por cuarentena a [STATE_INDETERMINADO] — nunca por tiempo a DESCARTADA.
         */
        const val STATE_KERNEL_ACTIVO = "KERNEL_ACTIVO"
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
        /**
         * 🔴 Marca de `last_error` que distingue «incierta porque lo dijo el procesador» de
         * «incierta porque se acabó el plazo». La segunda NO acredita que la llamada nativa
         * terminara, así que sigue apartando el aparato aunque la venta tenga identidad.
         */
        const val CUARENTENA_POR_ANTIGUEDAD = "cuarentena_por_antiguedad"

        /** Veredictos del servidor sobre un intento (`server_outcome`); mismos nombres que `AttemptOutcome` de S6. */
        const val SERVER_RECORDED = "RECORDED"
        const val SERVER_SECOND_CAPTURE_EVIDENCE = "SECOND_CAPTURE_EVIDENCE"
        const val SERVER_REFERENCE_COLLISION_EVIDENCE = "REFERENCE_COLLISION_EVIDENCE"
        /** Un Payment PENDING sin `reconciliation.kind` conocido: evidencia, nunca una venta normal. */
        const val SERVER_PENDING_EVIDENCE = "PENDING_EVIDENCE"
        /** El SERVIDOR liberó la solicitud (ventana de 30 s sin evidencia). La fila queda DESCARTADA con `last_error` = prefijo + evidencia. */
        const val SERVER_RELEASED_NO_EVIDENCE = "RELEASED_NO_EVIDENCE"
        /** El cajero declaró «no se presentó tarjeta» y el servidor lo acreditó (OPERATOR_RECONCILED). */
        const val SERVER_OPERATOR_NO_INSTRUMENT = "OPERATOR_NO_INSTRUMENT"
        const val LAST_ERROR_LIBERADA_PREFIX = "liberada_por_el_servidor:"

        /**
         * 🔴 CONTRADICCIÓN = predicado DERIVADO (nunca una columna, para que no se desincronice): el servidor tiene
         * evidencia que no es una venta normal, o dinero para un intento que la terminal dio por no cobrado o con otros
         * montos. Lo consumen la poda/cierre (la conservan), el veto de negativos (H.3 + `server_payment_id`) y el aviso.
         * Va como `const` para poder ir DENTRO de las anotaciones `@Query` (Room exige literales).
         *
         * 🔴 NUNCA puede evaluar a NULL: se usa como `AND NOT SQL_CONTRADICCION` en la poda, el cierre y el aviso de
         * pendientes, y en SQL `NOT NULL` es NULL — una fila SIN veredicto (`server_outcome` NULL, el caso normal) quedaba
         * fuera de la poda y del aviso (medido: dos pruebas del aviso F0 en cero). Por eso el `IS NOT NULL` externo y los
         * `IS` / `IS NOT` (NULL-seguros en SQLite) en vez de `=` / `!=`: `host_approved` NULL (sin veredicto del host) cuenta
         * como no rechazado, igual que en `registrarPorVeredictoDelServidor`; unos importes del servidor NULL sobre un RECORDED
         * (S6 sólo los omite sin Payment) cuentan como «no se pudo confirmar el importe» — contradicción, el lado seguro.
         */
        const val SQL_CONTRADICCION = "(server_outcome IS NOT NULL AND (" +
            "server_outcome IN ('SECOND_CAPTURE_EVIDENCE','REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE') " +
            "OR (server_outcome = 'RECORDED' AND (state = 'DESCARTADA' OR host_approved IS 0 " +
            "OR server_amount_cents IS NOT amount_cents OR server_tip_cents IS NOT tip_cents))))"

        const val KIND_SALE = "SALE"
        const val KIND_REFUND = "REFUND"
        const val ROUTE_FAST = "FAST"
        const val ROUTE_ORDER = "ORDER"
        const val ROUTE_REFUND = "REFUND"

        /** Non-terminal states = "money may have moved with no record" — the sweep watches these. */
        val OPEN_STATES = listOf(
            STATE_PREPARANDO, STATE_KERNEL_ACTIVO, STATE_AUTORIZANDO, STATE_HOST_RESPONDIO,
            STATE_AUTORIZADO, STATE_REGISTRO_FALLIDO, STATE_INDETERMINADO
        )
    }
}
