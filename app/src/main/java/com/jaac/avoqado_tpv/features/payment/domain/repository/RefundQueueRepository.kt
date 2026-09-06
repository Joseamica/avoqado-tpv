package com.jaac.avoqado_tpv.features.payment.domain.repository

import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome

/**
 * La cola durable de REEMBOLSOS.
 *
 * Gemela de [PaymentQueueRepository] para el carril de las devoluciones, con una diferencia que
 * cambia todas las decisiones: cuando algo entra aquí, **el dinero ya salió**. Un cobro encolado
 * que se pierde es una venta que no se cobró —malo, pero simétrico—; un reembolso encolado que se
 * pierde es dinero entregado que nadie anotó, y el corte del turno cuadra de más sin que nadie
 * pueda explicarlo.
 */
interface RefundQueueRepository {

    /**
     * Persiste el reembolso ANTES de tocar la red.
     *
     * 🔴 El cuerpo entero corre en `NonCancellable`, no sólo el insert. Esto se llama desde el
     * scope de un ViewModel que muere cuando la pantalla se va, y el cajero se va de la pantalla
     * justo después de ver «devolución aprobada» — que es exactamente el instante en el que se
     * necesita que la fila quede escrita.
     *
     * Idempotente: encolar dos veces la misma llave deja UNA fila (la PK lo garantiza).
     */
    suspend fun enqueue(refund: QueuedRefund): Result<Unit>

    /**
     * 🔴 WRITE-AHEAD (auditoría de Codex F1, 4-sep-2026): la fila nace ANTES del primer POST, ya
     * reclamada por el intento en vuelo (`SYNCING` + [token] + `claimed_at = ahora`), para que el
     * worker no la tome mientras la petición está en el aire. Quien la escribió cierra con
     * [markSuccess] / [release] / [markPermanentlyFailed] usando el MISMO token. Si el proceso muere
     * a media petición, el lease caduca y el worker la rescata con la misma llave: el servidor deduplica.
     */
    suspend fun enqueueClaimed(refund: QueuedRefund, token: String): Result<Unit>

    /**
     * Lo que un pago original tiene sin resolver (≠ SUCCESS). Candado contra un SEGUNDO reembolso
     * manual mientras el primero no esté en el servidor (auditoría F7).
     */
    suspend fun unresolvedForPayment(originalPaymentId: String): List<QueuedRefund>

    suspend fun getFailedCount(): Int

    /**
     * Reintenta el registro de una fila ya encolada contra el servidor.
     *
     * Manda la MISMA `idempotencyKey`, el MISMO monto y la MISMA autorización/referencia que se
     * guardaron: el servidor deriva su huella de idempotencia de esos cinco datos, así que
     * recalcular cualquiera rompería la deduplicación y el reintento nacería como un reembolso
     * nuevo.
     *
     * Clasifica el fallo con `classifySyncFailure` — la MISMA función que usan los cobros. No
     * inventa una taxonomía propia: 400/404/422 son definitivos; 401/403 son la sesión y se
     * reintentan; ante la duda, se reintenta.
     */
    suspend fun replay(refund: QueuedRefund): SyncOutcome

    /**
     * Lo que impide cerrar el turno de este negocio: reembolsos sin registrar, y rechazados que
     * el cajero todavía no ha visto.
     */
    suspend fun blockingForVenue(venueId: String): List<QueuedRefund>

    /**
     * El cajero vio el aviso de un rechazo definitivo y libera el cierre.
     *
     * 🔴 Sólo por toque manual. Es la salida de emergencia de la barrera: sin ella, un 400
     * definitivo dejaría la caja sin poder cerrarse nunca.
     */
    suspend fun acknowledge(idempotencyKey: String, staffId: String?): Int

    /** Reclama hasta [limit] filas para el worker; el token se genera adentro y viaja en `claimToken`, igual que en la cola de cobros. */
    suspend fun claimBatch(limit: Int): List<QueuedRefund>

    suspend fun markSuccess(idempotencyKey: String, token: String): Int

    suspend fun release(idempotencyKey: String, token: String, retryCount: Int, error: String): Int

    suspend fun markPermanentlyFailed(idempotencyKey: String, token: String, error: String): Int

    suspend fun getPendingCount(): Int

    suspend fun deleteOldSuccess(daysAgo: Int = 7): Int
}
