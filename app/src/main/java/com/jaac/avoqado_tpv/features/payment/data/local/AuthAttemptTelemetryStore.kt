package com.jaac.avoqado_tpv.features.payment.data.local

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * One recorded authorization attempt. Privacy-safe BY CONSTRUCTION: these are the only
 * four fields that exist, mirroring [AuthAttemptEntity] — see that class's KDoc for why
 * nothing else may ever be added here (`AuthAttemptTelemetryTest` scans `toString()` for
 * card/amount/reference leakage).
 */
data class AuthAttemptRecord(
    val code: String,
    val durationMs: Long,
    val rail: String,
    val timestamp: String,
)

/**
 * Local batch of authorization-attempt telemetry (Task 6, plan
 * `2026-08-04-event-loop-no-bloqueante-reportes`) — the piece that answers "how many
 * charge attempts fail from network, per venue" without ever opening a network
 * connection of its own. It only ever rides along on the existing periodic heartbeat.
 *
 * **Two layers, deliberately split:**
 * 1. An in-memory, thread-safe, capped FIFO buffer — this is the ONLY thing
 *    [record], [drainBatch] and [batchForHeartbeat] read or write, so their behavior
 *    is synchronous and deterministic (exercised directly by `AuthAttemptTelemetryTest`,
 *    a plain JVM test with no Room/Android dependency at all).
 * 2. An optional Room-backed durability journal ([AuthAttemptDao]) — every [record]
 *    ALSO fire-and-forgets an insert there, and the buffer reloads any leftover rows on
 *    construction. This is what survives a process death between "authorization
 *    resolved" and "next successful heartbeat"; without it a crash at the wrong moment
 *    would silently drop the very attempts this feature exists to surface. It is
 *    strictly a backstop: the in-memory buffer is authoritative for anything already
 *    loaded, and a Room failure (`dao` throwing) never affects [record]'s caller — see
 *    the fire-and-forget note below.
 *
 * **Why `dao` is nullable with no default binding requirement in tests:** Hilt always
 * supplies the real [AuthAttemptDao] in production (see `core/di/DatabaseModule.kt`).
 * Tests that only care about the batching contract construct this class directly with
 * `AuthAttemptTelemetryStore()` — no Room, no Android framework, matching the pattern
 * `AuthAttemptTelemetryTest` uses.
 *
 * **Fire-and-forget, always.** [record] must never delay or fail the caller — it is
 * called from a `finally` block right after a card authorization resolves (Blumon
 * `performOnlineAuthorization()`, AngelPay `onAngelPayResult`/`onAngelPaySdkResult`).
 * The in-memory write is synchronous but trivial (list append); the Room write runs on
 * a dedicated background scope and any failure there is swallowed (logged, not thrown).
 */
@Singleton
class AuthAttemptTelemetryStore @Inject constructor(
    private val dao: AuthAttemptDao?,
) {
    // Test-only convenience — see the class KDoc's "why `dao` is nullable" note. Deliberately
    // a SECONDARY constructor (not a default value on the @Inject one): Hilt/Dagger rejects a
    // class with more than one @Inject-annotated constructor, and Kotlin's codegen for a
    // default parameter on an @Inject constructor produces exactly that (a synthetic overload
    // that also carries the annotation) — confirmed by a real hiltJavaCompile failure while
    // building this class ("may only contain one injected constructor").
    constructor() : this(dao = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val buffer = ArrayDeque<AuthAttemptRecord>()

    init {
        // Best-effort reload of whatever survived a process death since the last
        // successful drain. Fire-and-forget: if this fails, those rows simply stay in
        // Room and get picked up on the NEXT cold start instead — never blocks startup.
        dao?.let { d ->
            scope.launch {
                runCatching {
                    val rows = d.getAll()
                    synchronized(lock) {
                        rows.forEach { row ->
                            if (buffer.size < MAX_BATCH_SIZE) {
                                buffer.addLast(
                                    AuthAttemptRecord(
                                        code = row.code,
                                        durationMs = row.durationMs,
                                        rail = row.rail,
                                        timestamp = row.timestamp,
                                    )
                                )
                            }
                        }
                    }
                }.onFailure { e -> Timber.w(e, "📊 [AuthAttemptTelemetry] reload from Room failed") }
            }
        }
    }

    /**
     * Record one authorization attempt. Synchronous and non-suspend by design — called
     * from a `finally` block right after a charge resolves, never awaited.
     */
    fun record(code: String, durationMs: Long, rail: String) {
        val entry = AuthAttemptRecord(
            code = code,
            durationMs = durationMs,
            rail = rail,
            timestamp = Instant.now().toString(),
        )
        synchronized(lock) {
            // 🔴 SABOTAGE-COVERED (mandatory in Task 6's verification workflow): removing
            // this cap is exactly what test "el lote se limita para no crecer sin
            // control" catches. Without it a terminal stuck offline for hours with
            // repeated failed authorizations grows this buffer unbounded.
            if (buffer.size >= MAX_BATCH_SIZE) buffer.removeFirst()
            buffer.addLast(entry)
        }
        dao?.let { d ->
            scope.launch {
                runCatching {
                    d.insert(
                        AuthAttemptEntity(
                            code = entry.code,
                            durationMs = entry.durationMs,
                            rail = entry.rail,
                            timestamp = entry.timestamp,
                        )
                    )
                }.onFailure { e -> Timber.w(e, "📊 [AuthAttemptTelemetry] Room insert failed") }
            }
        }
    }

    /** Unconditionally returns and clears whatever is currently buffered. */
    fun drainBatch(): List<AuthAttemptRecord> {
        val drained = synchronized(lock) {
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        if (drained.isNotEmpty()) {
            dao?.let { d ->
                scope.launch {
                    runCatching { d.clear() }
                        .onFailure { e -> Timber.w(e, "📊 [AuthAttemptTelemetry] Room clear failed") }
                }
            }
        }
        return drained
    }

    /**
     * The ONLY entry point [HeartbeatRepository] should call. Returns null — never an
     * empty list — whenever there is nothing to attach, so the caller can `?.let` the
     * field onto the heartbeat DTO without an extra emptiness check.
     *
     * 🔴 SABOTAGE-COVERED: removing the `chargeInProgress` guard is exactly what test
     * "no se reporta mientras hay un cobro activo" catches — the non-negotiable
     * invariant that NOTHING may add network traffic while a card is in play. A
     * heartbeat that fires mid-charge must go out with `authAttempts = null`, same as
     * if the buffer were empty.
     */
    fun batchForHeartbeat(chargeInProgress: Boolean): List<AuthAttemptRecord>? {
        if (chargeInProgress) return null
        val batch = drainBatch()
        return batch.ifEmpty { null }
    }

    companion object {
        /** Bounds memory AND the heartbeat payload — see the sabotage note on [record]. */
        const val MAX_BATCH_SIZE = 100
    }
}
