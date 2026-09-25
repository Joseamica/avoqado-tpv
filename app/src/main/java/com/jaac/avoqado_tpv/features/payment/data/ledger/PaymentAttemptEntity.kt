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

    // ── Task 7 · fix 4 (Room v36): evidencia POSITIVA del servidor SIN veredicto aplicable. ──
    /**
     * 🔴 El banco aprobó este intento y el servidor lo sabe, pero NO hay Payment que aplicar: S6/2xx con
     * `attempt.processorEvidence = APPROVED` (aprobación tardía, con otro importe: el servidor guarda el evento
     * PENDING/AMOUNT_MISMATCH sin crear Payment) o un outcome con dinero SIN `paymentId`. `desdeConsultaS6` no produce
     * veredicto sin id, así que `server_outcome` no lo guarda — y el veto vivía sólo en la RAM del ViewModel, que un cancel
     * remoto, una liberación atrasada, el CAS de negativos (H.3) o recrear la pantalla borraban (Codex, fix 4, P1).
     *
     * MARCA normalizada y DURABLE: único valor [SERVER_PROCESSOR_EVIDENCE_APPROVED]; se escribe en CUALQUIER estado
     * (INDETERMINADO, DESCARTADA…) sin tocar `state`, `state_version`, `host_approved` ni `server_outcome`; nunca se degrada a
     * NULL ni se convierte en `host_approved`. La leen la cancelación y H.3 (`contarIntentosBloqueadores`), la contradicción
     * ([SQL_CONTRADICCION] y el aviso F0), las cercas de venta y aparato, la restauración del ViewModel y el CAS de liberación.
     * `server_processor_evidence_at` conserva la PRIMERA vez (COALESCE): repetir la evidencia no renueva las 72 h del aviso.
     */
    @ColumnInfo(name = "server_processor_evidence") val serverProcessorEvidence: String? = null,
    @ColumnInfo(name = "server_processor_evidence_at") val serverProcessorEvidenceAt: Long? = null,
    /**
     * 🔴 Codex r5-4 (22-sep): el VETO del servidor, hecho durable. Hasta hoy sólo el dinero propio
     * (`server_processor_evidence = APPROVED`) sobrevivía al proceso; los otros tres avisos —pago no atribuible,
     * evidencia de otra terminal, evidencia sin dueño— vivían en RAM. Con tres consumidores concurrentes reales
     * (el sondeo de la pantalla, la recuperación inmediata y el worker), una respuesta LIMPIA y ATRASADA llegaba
     * después de la que traía la contradicción, pasaba el CAS y liberaba la venta.
     *
     * Guarda el PRIMER motivo visto y no se pisa: lo que importa es que algo contradice, no cuál fue el último.
     */
    @ColumnInfo(name = "server_veto") val serverVeto: String? = null,
    /**
     * 🔴 Codex r8 (P2-4, Room v38): la última vez que el servidor CONTESTÓ (2xx) una consulta S6 de esta fila.
     * `server_checked_at` no sirve para eso: es el TURNO de la recuperación y también se estampa tras un 401, 403, 404
     * o 5xx, para que una fila que el servidor no reconoce no se consulte en cada pasada. Usarlo como «el servidor ya
     * contestó» dejaba declarar sin red y podar una declaración después de un 503. Lo leen la declaración local
     * (candado 5) y la poda (la vigilancia de una declaración hecha sin red). Nunca afecta a `updated_at`.
     */
    @ColumnInfo(name = "server_answered_at") val serverAnsweredAt: Long? = null,
    /**
     * Decisión del founder (23-sep, «corrige y avisa»; Room v40): cuándo y quién confirmó con «Entendido» que un cobro que la
     * terminal dio por NO cobrado SÍ pasó (el servidor lo registró). La fila pasa entonces a REGISTRADO, pero lo que dijo el
     * lector (`host_approved`, `last_error`) se conserva: esto es la constancia de que una PERSONA reconoció la diferencia.
     */
    @ColumnInfo(name = "acknowledged_at") val acknowledgedAt: Long? = null,
    @ColumnInfo(name = "acknowledged_by") val acknowledgedBy: String? = null,
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
        /**
         * 🔴 Ronda 20 (founder, 23-sep): la duda que deja un proceso MUERTO a media venta. A diferencia de
         * [CUARENTENA_POR_ANTIGUEDAD] SÍ acredita que la llamada nativa terminó —el SDK vive en el proceso de la app (su
         * manifiesto no declara `android:process`), así que murió con él—: por eso, con orden, sólo cerca SU venta.
         */
        const val LAST_ERROR_PROCESO_TERMINADO = "proceso_terminado"

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
        /** Único valor de `server_processor_evidence`: evidencia positiva del servidor sin veredicto aplicable (fix 4). */
        const val SERVER_PROCESSOR_EVIDENCE_APPROVED = "APPROVED"

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
        // Fix 4 (Codex, D3b): la evidencia positiva PENDIENTE también es contradicción — `IS 'APPROVED'` (NULL-seguro) y sólo
        // mientras no haya Payment o la fila no esté REGISTRADO/CERRADA: la marca nunca se borra, y un intento registrado
        // después no puede quedar como contradicción permanente. Paréntesis externos: se usa como `AND NOT (…)`.
        const val SQL_CONTRADICCION = "((server_outcome IS NOT NULL AND (" +
            "server_outcome IN ('SECOND_CAPTURE_EVIDENCE','REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE') " +
            // Decisión del founder (23-sep): un rechazo del host que una PERSONA reconoció («Entendido», `acknowledged_at`) ya
            // no es contradicción — el cobro SÍ pasó y está registrado; el rechazo se conserva como historia, no como alarma.
            "OR (server_outcome = 'RECORDED' AND (state = 'DESCARTADA' OR (host_approved IS 0 AND acknowledged_at IS NULL) " +
            "OR server_amount_cents IS NOT amount_cents OR server_tip_cents IS NOT tip_cents)))) " +
            "OR (server_processor_evidence IS 'APPROVED' AND (server_payment_id IS NULL OR state NOT IN ('REGISTRADO','CERRADA'))) " +
            // 🔴 Codex r6 (P1-4): un VETO que llega DESPUÉS de una liberación no la deshacía, y la fila seguía
            // diciendo «se puede volver a cobrar» y quedaba fuera del aviso y de la protección contra la poda. NO se
            // reabre la fila a propósito: reabrirla apartaría el aparato por una contradicción SIN salida acreditada
            // (P2-5 de la misma pasada) — que es exactamente la terminal muerta que este trabajo elimina. Se hace
            // VISIBLE: entra al aviso F0 como contradicción, sobrevive a la poda y la pantalla deja de invitar a
            // cobrar otra vez.
            // 🔴 Ronda 23 (Codex r21, P1-1): en CUALQUIER estado. Excluía REGISTRADO/CERRADA como «escapatoria» — pero desde la
            // ronda 22 un veto ya no deja promover, así que una fila registrada con veto es un veto que llegó DESPUÉS del registro
            // (una consulta S6 que seguía en vuelo), y excluirla lo hacía desaparecer: ni aviso ni protección de la poda. No aparta
            // el aparato (el cobro está registrado; apartarlo sería la terminal muerta sin salida): sale en el aviso de Inicio.
            "OR server_veto IS NOT NULL)"

        /**
         * 🔴 Decisión del founder (23-sep, «corrige y avisa»; Codex r17/r18, P1): el cobro que la terminal dio por NO cobrado
         * y el servidor SÍ registró, en su caso LIMPIO — Payment de ESTE intento, mismos importes, sin veto y, si vino del POS,
         * el ganador de esa solicitud. Es lo ÚNICO que el cajero puede confirmar con «Entendido»; lo demás (PENDING, segunda
         * captura, colisión, otros importes, un veto) sigue apartado, porque ahí «sí pasó» no está acreditado.
         * UNA sola regla para la lista del aviso, el UPDATE que confirma, la barrera y la exclusión del aviso de contradicciones.
         * NULL-segura a propósito (sólo `IS`, `IN` y columnas NOT NULL): se usa también negada, y `NOT NULL` es NULL.
         */
        const val SQL_COBRO_POR_RECONOCER = "(legacy_shadow = 0 AND processor IN ('ANGELPAY','BLUMON') AND kind = 'SALE' " +
            "AND state IN ('DESCARTADA','INDETERMINADO') AND server_outcome IS 'RECORDED' AND server_payment_id IS NOT NULL " +
            "AND server_amount_cents IS amount_cents AND server_tip_cents IS tip_cents AND server_veto IS NULL " +
            "AND acknowledged_at IS NULL " +
            "AND (terminal_payment_request_id IS NULL OR server_winner_payment_id IS server_payment_id))"

        /**
         * Gemelo en Kotlin de la mitad «evidencia de dinero» de [SQL_CONTRADICCION]: los `server_outcome` con los que el
         * servidor acredita dinero de ESTE intento (lo mismo que `VeredictoDeIntento.desdeConsultaS6` devuelve como veredicto).
         * Una fila con cualquiera de ellos que NO esté en REGISTRADO es «Avoqado tiene evidencia de cobro»: nunca «se puede
         * volver a cobrar». Las liberaciones (`RELEASED_NO_EVIDENCE`, `OPERATOR_NO_INSTRUMENT`) quedan fuera a propósito.
         */
        val SERVER_OUTCOMES_CON_DINERO = setOf(
            SERVER_RECORDED, SERVER_SECOND_CAPTURE_EVIDENCE, SERVER_REFERENCE_COLLISION_EVIDENCE, SERVER_PENDING_EVIDENCE,
        )

        /** Motivos de [PaymentAttemptEntity.serverVeto]: lo que el servidor publica y prohíbe usar para liberar. */
        const val VETO_PAYMENT_CONTRADICTION = "PAYMENT_CONTRADICTION"
        const val VETO_EVIDENCE_CONTRADICTION = "EVIDENCE_CONTRADICTION"
        const val VETO_UNATTRIBUTED_EVIDENCE = "UNATTRIBUTED_EVIDENCE"

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
