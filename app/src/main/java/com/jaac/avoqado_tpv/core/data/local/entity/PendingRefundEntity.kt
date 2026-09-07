package com.jaac.avoqado_tpv.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * La cola durable de REEMBOLSOS que el SDK ya aprobó pero que el servidor todavía no registró.
 *
 * 🔴 **Por qué existe (medido el 3-sep-2026, no supuesto):** cuando el SDK devolvió el dinero y el
 * POST de registro falla, hoy no pasa NADA — `PaymentViewModel.handleRefundSuccess` escribe dos
 * `Timber.w` y sigue, con el comentario «we don't queue refunds for offline retry»; el camino de
 * AngelPay muestra un Toast y sale. El `Payment type=REFUND` nunca se crea, así que el dinero salió
 * del cajón y el sistema no lo sabe: el corte del turno cuadra de MÁS y nadie se entera.
 *
 * Es la hermana de [PendingPaymentEntity], que ya resuelve lo mismo para los COBROS.
 *
 * 🔑 **La PK es la `idempotencyKey`, no un autoincremento y no la referencia.** Tres razones, y las
 * tres importan:
 *
 * 1. Encolar dos veces el mismo reembolso se vuelve **imposible por construcción**.
 * 2. Es la MISMA cadena que viaja al servidor, así que el reintento local y el remoto hablan de la
 *    misma cosa.
 * 3. 🔴 `reference_number` **no puede** ser la llave: el reembolso de AngelPay **reusa la referencia
 *    del pago ORIGINAL** (`referenceNumber = sdkReferenceNumber, // Reuse original ref`). Si el pago
 *    también está encolado en `pending_payments` —que sí tiene un índice único por referencia— el
 *    insert chocaría y se descartaría en silencio: el mismo defecto, una capa más abajo.
 *
 * ⚠️ **El payload se guarda VERBATIM y NUNCA se recalcula al reproducir.** El servidor deriva su
 * llave de idempotencia de (llave, pago original, monto, autorización, referencia), y en Blumon la
 * autorización y la referencia salen por reflexión del `SaleData` del SDK en el momento del cobro.
 * Si el replay las volviera a derivar cuando ese objeto ya no existe, caerían a cadena vacía, la
 * huella del servidor cambiaría y **la deduplicación desaparecería**: el reintento crearía un
 * reembolso nuevo en vez de reconocerse como el mismo.
 *
 * **Ciclo de vida** (idéntico al de los cobros, para no estrenar semántica):
 * 1. El SDK aprueba → se persiste AQUÍ, **antes** de tocar la red → se intenta el POST.
 * 2. `PaymentSyncWorker` la reproduce, **después** de los cobros (un reembolso sin su cobro
 *    registrado no tiene contra qué aplicarse).
 * 3. Un intento por corrida: el backoff lo lleva WorkManager, no un bucle aquí dentro.
 * 4. Éxito → `SUCCESS`, se borra a los 7 días.
 * 5. Fallo transitorio —incluidos 401/403, que casi siempre son el token y no la operación— vuelve a
 *    `PENDING` con `retry_count + 1`, SIEMPRE. 🔴 Nunca pasa a `FAILED` por conteo (auditoría de Codex F4):
 *    el servidor no lo rechazó, así que no es definitivo — y como no era `permanent`, la fila quedaba
 *    muerta: nadie la reclamaba, nadie podía reconocerla y bloqueaba el cierre para siempre.
 *    [MAX_RETRY_ATTEMPTS] queda como umbral INFORMATIVO para que la pantalla avise.
 * 6. Fallo definitivo de negocio (400/404/422 SOLAMENTE, ver `SyncOutcome.PERMANENT_HTTP_CODES`)
 *    → `FAILED` con [permanent] = true, para que un reintento masivo no lo resucite solo.
 */
@Entity(
    tableName = "pending_refunds",
    indices = [
        Index(value = ["sync_status"]), // filtrado del worker
        Index(value = ["created_at"]), // orden FIFO de reproducción
        Index(value = ["venue_id"]), // aislamiento de tenant: toda consulta filtra por venue
        Index(value = ["claim_token"]) // claim/release del worker
    ]
)
data class PendingRefundEntity(
    /** == la `idempotencyKey` que viaja al servidor. Ver la nota de la PK arriba. */
    @PrimaryKey
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "staff_id")
    val staffId: String,

    /** [PROCESSOR_BLUMON] | [PROCESSOR_ANGELPAY] — decide el tag `processor` del request. */
    @ColumnInfo(name = "processor")
    val processor: String,

    @ColumnInfo(name = "original_payment_id")
    val originalPaymentId: String,

    @ColumnInfo(name = "original_order_id")
    val originalOrderId: String? = null,

    /** BigDecimal como String. NUNCA Float — regla dura del repo. */
    @ColumnInfo(name = "amount")
    val amount: String,

    @ColumnInfo(name = "original_total_amount")
    val originalTotalAmount: String,

    /** Desglose de propina que eligió el cajero. `null` = que el servidor reparta. */
    @ColumnInfo(name = "tip_refund_cents")
    val tipRefundCents: Int? = null,

    @ColumnInfo(name = "is_partial_refund")
    val isPartialRefund: Boolean,

    @ColumnInfo(name = "refund_reason")
    val refundReason: String,

    @ColumnInfo(name = "merchant_account_id")
    val merchantAccountId: String,

    /** Vacío en AngelPay: el backend lo tolera cuando `processor = "angelpay"`. */
    @ColumnInfo(name = "blumon_serial_number")
    val blumonSerialNumber: String,

    /** El entero chico del webhook de Blumon, obligatorio para `CancelIcc`. 0 en AngelPay. */
    @ColumnInfo(name = "original_operation_number")
    val originalOperationNumber: Int,

    /** 🔴 Se guarda, jamás se recalcula al reproducir. Ver la nota del payload verbatim. */
    @ColumnInfo(name = "authorization_number")
    val authorizationNumber: String,

    /** 🔴 Se guarda, jamás se recalcula al reproducir. */
    @ColumnInfo(name = "reference_number")
    val referenceNumber: String,

    @ColumnInfo(name = "masked_pan")
    val maskedPan: String? = null,

    @ColumnInfo(name = "card_brand")
    val cardBrand: String? = null,

    @ColumnInfo(name = "entry_mode")
    val entryMode: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SYNC_STATUS_PENDING,

    @ColumnInfo(name = "claim_token")
    val claimToken: String? = null,

    @ColumnInfo(name = "claimed_at")
    val claimedAt: Long? = null,

    /** true SÓLO con 400/404/422. Nunca con 401/403, que son la sesión y no la operación. */
    @ColumnInfo(name = "permanent", defaultValue = "0")
    val permanent: Boolean = false,

    /**
     * El cajero ya vio el aviso de un reembolso RECHAZADO y liberó el cierre.
     *
     * 🔴 Sólo lo pone un toque manual; el worker nunca. Es la salida de emergencia de la barrera
     * del cierre de turno: sin ella, un rechazo definitivo dejaría la caja sin poder cerrarse nunca.
     */
    @ColumnInfo(name = "acknowledged", defaultValue = "0")
    val acknowledged: Boolean = false,

    /** Quién tocó «Ya lo vi» (staffId de la sesión) y cuándo — auditoría de Codex F9: sin esto se liberaba el cierre sin evidencia durable del responsable. */
    @ColumnInfo(name = "acknowledged_by")
    val acknowledgedBy: String? = null,

    @ColumnInfo(name = "acknowledged_at")
    val acknowledgedAt: Long? = null,
) {
    companion object {
        const val SYNC_STATUS_PENDING = "PENDING"
        const val SYNC_STATUS_SYNCING = "SYNCING"
        const val SYNC_STATUS_SUCCESS = "SUCCESS"
        const val SYNC_STATUS_FAILED = "FAILED"

        /** Mismo tope que los cobros: el backoff entre intentos lo lleva WorkManager. */
        const val MAX_RETRY_ATTEMPTS = 10

        const val PROCESSOR_BLUMON = "BLUMON"
        const val PROCESSOR_ANGELPAY = "ANGELPAY"
    }
}
