package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.*
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Replays saved approvals only. No processor authorization is reachable from this class. */
@Singleton
class LedgerApprovalRecovery(
    private val dao: PaymentAttemptDao,
    private val fast: FastPaymentRecorder,
    private val order: OrderPaymentRecorder,
    /**
     * 🔴 D3 (encargo 9308a7): ¿se puede registrar AHORA (sesión y red)? Sin sesión cada pasada terminaba en un 401, y sin
     * red en un error de transporte; los dos gastaban uno de los CINCO intentos (`verify_attempts < 5`, que sube al
     * reclamar) de un cobro ya aprobado, y tras cinco la venta quedaba fuera de la recuperación para siempre (desde el D5 mínimo de
     * Cobro simple ya no hay tope, pero un intento sin sesión o sin red sólo alargaría la pausa). Sin sesión o
     * sin red no se reclama ninguna fila: esperan intactas (entrar y reconectar vuelven a pedir la pasada). Se pregunta
     * ANTES DE CADA FILA: un 401 con refresco fallido borra la sesión a media pasada (Codex r1 #1).
     */
    private val puedeRegistrar: () -> Boolean = { true },
    /**
     * 🔴 D5 (encargo 533d77, Codex r1 #1): el token de ESTE proceso. Con él, el reclamo no toma una venta de la PAX cuyo SDK
     * puede seguir dentro (el cierre EMV pendiente). null = el reclamo de siempre (pruebas viejas).
     */
    private val tokenDelProceso: () -> String? = { null },
) {
    /** Hilt cablea la sesión y la red REALES. Constructor secundario a propósito (mismo patrón que [PaymentAttemptLedger]). */
    @Inject constructor(
        dao: PaymentAttemptDao,
        fast: FastPaymentRecorder,
        order: OrderPaymentRecorder,
        secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
        networkMonitor: com.jaac.avoqado_tpv.core.util.NetworkMonitor,
        ledger: PaymentAttemptLedger,
    ) : this(
        dao, fast, order, { secureStorage.isAuthenticated() && networkMonitor.isConnected() },
        { ledger.tokenDelProceso() },
    )

    suspend fun recover(venueId: String, now: Long): Int {
        if (!puedeRegistrar()) {
            Timber.i("📒 [LedgerApproval] sin sesión o sin red: las aprobaciones esperan (no se gasta ningún intento)")
            return 0
        }
        var recovered = 0
        // Let a live SDK/recorder finish first; page size is capped by the DAO.
        val rows = dao.getApprovalRecoveryCandidates(venueId, now - ESPERA_MS, now)
        for (row in rows) {
            if (!puedeRegistrar()) {
                Timber.i("📒 [LedgerApproval] se fue la sesión o la red a media pasada: las demás aprobaciones esperan")
                break
            }
            // 🔴 Cobro simple · D5 mínimo (rescate de F1 `7d12a7c8`): en la PAX sin tope de intentos (antes `verifyAttempts >= 5` ⇒ fuera
            // para siempre). Un aprobado de Blumon TPV nunca se abandona: la pausa es 5 → 5 → 8 → 16 → 32 min y se queda en 32, con la llave
            // del intento. La Nexgo conserva su tope de hoy (Codex d5-minimo r1 #5: no se le cambia nada).
            if (row.venueId != venueId || row.state !in RECOVERABLE ||
                (row.hostApproved != true && row.state != PaymentAttemptEntity.STATE_AUTORIZADO) ||
                (row.verifyAttempts >= 5 && row.processor != PaymentAttemptEntity.PROCESSOR_BLUMON)) continue
            try {
                val waitMs = 120_000L * (1L shl row.verifyAttempts.coerceIn(0, 4))
                val acquiredAt = maxOf(now, System.currentTimeMillis())
                val ownedLease = acquiredAt + maxOf(waitMs, 300_000L)
                val token = tokenDelProceso()
                val reclamada = if (token != null) dao.claimRecoveryConElSdkFuera(row.attemptId, venueId, acquiredAt, ownedLease, token)
                    else dao.claimRecovery(row.attemptId, venueId, acquiredAt, ownedLease)
                if (reclamada != 1) continue
                val context = restoreContext(row) ?: continue
                val card = CardDetails(
                    maskedPan = row.maskedPan.orEmpty(),
                    cardBrand = CardBrand.entries.firstOrNull { it.name == row.cardBrand } ?: CardBrand.UNKNOWN,
                    entryMode = CardEntryMode.entries.firstOrNull { it.name == row.entryMode } ?: CardEntryMode.OTHER,
                )
                // Direct recorder = exactly one HTTP attempt. The ledger owns retry/backoff;
                // ENTREGADA_A_COLA is excluded because PaymentSyncWorker already owns it.
                val result = kotlinx.coroutines.withTimeout(240_000L) {
                    if (row.recordingRoute == PaymentAttemptEntity.ROUTE_ORDER)
                        order.recordPayment(context, card, row.authCode.orEmpty(), row.referenceNumber.orEmpty())
                    else fast.recordPayment(context, card, row.authCode.orEmpty(), row.referenceNumber.orEmpty())
                }
                val completedAt = maxOf(now, System.currentTimeMillis())
                val receipt = result.getOrNull()
                if (receipt != null && row.processor != PaymentAttemptEntity.PROCESSOR_ANGELPAY) {
                    // Codex (código, P1-6): el checkpoint 2 es Nexgo/AngelPay. Una aprobación Blumon/PAX conserva la recuperación
                    // que tenía ANTES (REST 2xx ⇒ REGISTRADO) hasta el port a PAX; pasarla por el veredicto la dejaba retenida
                    // (el DAO la declara FUERA_DE_ALCANCE) hasta agotar sus cinco intentos.
                    if (dao.completeRecovery(row.attemptId, venueId, ownedLease, PaymentAttemptEntity.STATE_REGISTRADO, completedAt, null) == 1) recovered++
                } else if (receipt != null) {
                    // Checkpoint 2 (E1/E5): el 2xx se aplica como VEREDICTO por la MISMA regla que S5/S6 — REGISTRADO sólo
                    // con un veredicto final aprobado y los mismos montos; una evidencia (segunda captura sin ganador,
                    // colisión, PENDING) se guarda y la fila conserva su estado. Un fallo al persistir deja la fila con su
                    // lease: la siguiente pasada vuelve a registrar (idempotente) y a aplicar.
                    val aplicado = dao.aplicarVeredictoDelServidor(
                        VeredictoDeIntento.desdeRecibo(venueId, row.attemptId, row.terminalPaymentRequestId, receipt), completedAt,
                    )
                    when (aplicado.decision) {
                        ResultadoDelVeredicto.Decision.APLICADO -> if (aplicado.transiciono) recovered++
                        ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR ->
                            Timber.e("🚨 [LedgerApproval] el servidor conserva %s como %s (sin liberar) | attemptId=%s",
                                receipt.paymentId, receipt.veredictoDelServidor, row.attemptId)
                        // Una fila AngelPay que el DAO declara fuera de alcance (devolución, heredada) sigue el camino anterior.
                        ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE ->
                            if (dao.completeRecovery(row.attemptId, venueId, ownedLease, PaymentAttemptEntity.STATE_REGISTRADO, completedAt, null) == 1) recovered++
                        // Un rechazo no guardó nada: la fila conserva su estado y su lease; se reintenta con la pausa (≤ 32 min) y S6/F0 la ven.
                        else -> Timber.e("🚨 [LedgerApproval] la libreta RECHAZÓ el veredicto del 2xx (%s) | attemptId=%s paymentId=%s",
                            aplicado.decision, row.attemptId, receipt.paymentId)
                    }
                } else {
                    dao.completeRecovery(row.attemptId, venueId, ownedLease, PaymentAttemptEntity.STATE_REGISTRO_FALLIDO,
                        completedAt, "Cobrado; registro pendiente")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Saved approval still pending registration: %s", row.attemptId)
            }
        }
        return recovered
    }

    /**
     * Codex r4–r5 (D3, encargo 9308a7): ¿cuándo hace falta OTRA pasada? Se pregunta al terminar [recover]. Cuenta toda
     * aprobación que sigue candidata sin lease: la que se saltó por reciente, la que cumplió sus 2 min DURANTE la pasada y la
     * que no cupo en la página de 50. Lo que esta pasada ya intentó quedó con lease y no cuenta (no hay vuelta sin fin; su
     * reintento sigue el backoff de siempre). Devuelve en cuántos ms, o null si no hace falta o si no se puede registrar
     * ahora (sin sesión o sin red no se sondea: entrar y reconectar vuelven a pedir la pasada).
     */
    suspend fun cuandoHaceFaltaOtraPasada(venueId: String, now: Long): Long? {
        if (!puedeRegistrar()) return null
        val siguiente = dao.getApprovalRecoveryCandidates(venueId, Long.MAX_VALUE, now)
            .filter { it.venueId == venueId }
            .minOfOrNull { it.createdAt } ?: return null
        return (siguiente + ESPERA_MS - now).coerceAtLeast(0L) + MARGEN_MS
    }

    private suspend fun restoreContext(row: PaymentAttemptEntity): PaymentContext? {
        val gson = Gson()
        val operation = row.operationId?.toIntOrNull()
        val context: PaymentContext = when {
            row.processor == PaymentAttemptEntity.PROCESSOR_ANGELPAY ->
                gson.fromJson(row.paymentContextJson, PaymentContext.AngelPayPayment::class.java)
                    .copy(authorizationCode = row.authCode.orEmpty(), referenceNumber = row.referenceNumber.orEmpty(),
                        capturadoEnAparato = row.createdAt)
            row.recordingRoute == PaymentAttemptEntity.ROUTE_ORDER ->
                gson.fromJson(row.paymentContextJson, PaymentContext.OrderPayment::class.java)
                    .copy(blumonOperationNumber = operation)
            row.recordingRoute == PaymentAttemptEntity.ROUTE_FAST ->
                gson.fromJson(row.paymentContextJson, PaymentContext.FastPayment::class.java)
                    // D7: la hora del intento viaja para que el servidor avise si en medio se cobró en efectivo.
                    .copy(blumonOperationNumber = operation, capturadoEnAparato = row.createdAt)
            else -> return null
        }
        // Old SHADOW rows have only RetryContext/partial JSON. Never invent the missing
        // staff, venue, merchant or order from today's session; keep those for reconciliation.
        if (context.venueId != row.venueId || context.staffId.isNullOrBlank() ||
            context.idempotencyKey != row.attemptId || context.merchantAccountId.isNullOrBlank() ||
            context.deviceSerialNumber.isNullOrBlank() ||
            context.amount.movePointRight(2).longValueExact() != row.amountCents ||
            context.tip.movePointRight(2).longValueExact() != row.tipCents) return null
        val known = row.shiftMembership == "MANAGED" || row.shiftMembership == "NO_SHIFT_SYSTEM"
        val belonging = com.jaac.avoqado_tpv.features.shift.domain.ShiftBelonging.fromStored(
            row.shiftMembership, row.localShiftSessionId, context.shiftId, row.cashDrawerSessionId)
        if (known && (belonging.kind == "UNKNOWN" || context.shiftMembership != row.shiftMembership ||
                context.localShiftSessionId != row.localShiftSessionId || context.cashDrawerSessionId != row.cashDrawerSessionId)) {
            dao.anotarProblemaDeSnapshot(row.venueId, row.attemptId, "snapshot_caja_ilegible_o_contradictorio")
            return null // No se inventa un registro financiero a partir de un snapshot contradictorio.
        }
        return context.withShiftBelonging(belonging)
    }

    companion object {
        val RECOVERABLE = listOf(PaymentAttemptEntity.STATE_HOST_RESPONDIO,
            PaymentAttemptEntity.STATE_AUTORIZADO, PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)

        /** La espera que [recover] le da a un SDK o registro vivo antes de tomar una fila (`now - ESPERA_MS`). */
        const val ESPERA_MS = 120_000L
        const val MARGEN_MS = 15_000L

    }
}
