package com.jaac.avoqado_tpv.core.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Instrumented tests for the claim/release contract on [PendingPaymentDao] (F-8), plus the
 * selective-reset contract added on top of it (F-10).
 *
 * **Why this exists:** `getAllPending()` returned every PENDING row with no intermediate
 * state — two concurrent [com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker] runs could
 * read and record the same payment twice. `claimBatch` marks rows SYNCING with a token inside
 * a single `@Transaction`, so two concurrent callers land on disjoint sets. Ver spec §4.2 F-8.
 *
 * **F-10:** `resetAllFailed()` used to resurrect EVERY FAILED row, including permanent
 * 400/404/422 business failures that reconnecting can never fix — they'd retry forever
 * instead of settling into a stable FAILED state. `markPermanentlyFailed` + the
 * `permanent` column close that gap. **Fix round 1** narrowed which HTTP codes count as
 * permanent (401/403 are almost always the session, not the payment — see
 * `SyncOutcome.kt`) and added `resetAllFailedIncludingPermanent()` as the deliberate
 * human escape hatch `DeviceHealthViewModel.retryFailedPayments()` uses.
 */
class PendingPaymentClaimTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AvoqadoDatabase::class.java,
        ).build()
        dao = db.pendingPaymentDao()
    }

    @After fun teardown() = db.close()

    @Test
    fun dos_claims_concurrentes_no_toman_la_misma_fila() = runTest {
        repeat(10) { dao.insert(newPending(reference = "ref-$it")) }

        val (a, b) = listOf(
            async { dao.claimBatch(limit = 10, token = "A", now = 1_000, staleBefore = 0) },
            async { dao.claimBatch(limit = 10, token = "B", now = 1_000, staleBefore = 0) },
        ).awaitAll()

        val ids = a.map { it.id } + b.map { it.id }
        assertThat(ids).containsNoDuplicates()   // 🔴 el bug
        assertThat(ids).hasSize(10)              // y nada se pierde
    }

    @Test
    fun una_fila_reclamada_no_vuelve_a_salir() = runTest {
        dao.insert(newPending(reference = "ref-1"))

        val first = dao.claimBatch(limit = 10, token = "A", now = 1_000, staleBefore = 0)
        val second = dao.claimBatch(limit = 10, token = "B", now = 1_000, staleBefore = 0)

        assertThat(first).hasSize(1)
        assertThat(second).isEmpty()
    }

    @Test
    fun una_fila_abandonada_se_puede_reclamar_despues() = runTest {
        // Si el worker muere a media tanda, la fila queda en SYNCING. Debe poder
        // reclamarse pasado el umbral, o el pago se queda atorado para siempre.
        dao.insert(newPending(reference = "ref-1"))
        dao.claimBatch(limit = 10, token = "MUERTO", now = 1_000, staleBefore = 0)

        val reclaimed = dao.claimBatch(
            limit = 10, token = "NUEVO", now = 999_000, staleBefore = 900_000,
        )

        assertThat(reclaimed).hasSize(1)
    }

    @Test
    fun release_devuelve_la_fila_a_PENDING_con_el_retry_incrementado() = runTest {
        dao.insert(newPending(reference = "ref-1"))
        val claimed = dao.claimBatch(limit = 1, token = "A", now = 1_000, staleBefore = 0)

        val affected = dao.release(claimed.first().id, token = "A", retryCount = 1, error = "red caida")

        assertThat(affected).isEqualTo(1)
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.syncStatus).isEqualTo("PENDING")
        assertThat(row.retryCount).isEqualTo(1)
        assertThat(row.claimToken).isNull()
    }

    // --- Fix round 1: release()/markSynced() son compare-and-swap sobre claim_token ---
    // Concurrencia real (coordinador, ronda 1): A reclama, se queda pasmado más de
    // STALE_CLAIM_MS. B reclama la misma fila por stale. Si el intento tardío de A pudiera
    // pisar la fila con SU token viejo, le borraría el claim vigente a B mientras B sigue
    // registrando el mismo pago — dos workers pueden terminar registrando el mismo pago ya
    // cobrado. Sin CAS, esto reabre exactamente el bug que F-8 existe para cerrar.

    @Test
    fun release_con_token_viejo_no_pisa_la_fila_reclamada_por_otro_worker() = runTest {
        dao.insert(newPending(reference = "ref-1"))
        val claimedByA = dao.claimBatch(limit = 10, token = "A", now = 1_000, staleBefore = 0)
        dao.claimBatch(limit = 10, token = "B", now = 999_000, staleBefore = 900_000) // reclamo por stale

        val affected = dao.release(claimedByA.first().id, token = "A", retryCount = 1, error = "tarde")

        assertThat(affected).isEqualTo(0) // el write de A no hizo nada — ya no es su fila
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.claimToken).isEqualTo("B") // sigue intacta, en manos de B
        assertThat(row.syncStatus).isEqualTo("SYNCING")
    }

    @Test
    fun markSynced_con_token_viejo_no_pisa_la_fila_reclamada_por_otro_worker() = runTest {
        // Mismo escenario, peor desenlace si no hubiera CAS: A completó tarde en el backend
        // y creería que debe cerrar la fila como SUCCESS — pero la fila ya es de B.
        dao.insert(newPending(reference = "ref-1"))
        val claimedByA = dao.claimBatch(limit = 10, token = "A", now = 1_000, staleBefore = 0)
        dao.claimBatch(limit = 10, token = "B", now = 999_000, staleBefore = 900_000)

        val affected = dao.markSynced(claimedByA.first().id, token = "A")

        assertThat(affected).isEqualTo(0)
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.claimToken).isEqualTo("B")
        assertThat(row.syncStatus).isEqualTo("SYNCING") // NO se marcó SUCCESS de más
    }

    @Test
    fun updateRetry_con_token_viejo_no_pisa_la_fila_reclamada_por_otro_worker() = runTest {
        // Mismo escenario que release/markSynced, sobre el tercer write CAS que ya tenía la
        // tabla (coordinador, ronda 2, Important 2). Este test SOBREVIVE a F-9 (Task 5, fix
        // round 1) sin cambios: sigue probando el SQL compare-and-swap de updateRetry() a
        // nivel DAO, aunque a nivel de aplicación ya no hay caller — el loop de
        // PaymentSyncWorker.syncPayment() que lo usaba (mid-loop, sin soltar el claim)
        // se eliminó, y PaymentQueueRepository ya NO expone updateRetry (footgun cerrado:
        // ver PendingPaymentDao.kt KDoc). Se conserva a propósito por esta cobertura CAS.
        dao.insert(newPending(reference = "ref-1"))
        val claimedByA = dao.claimBatch(limit = 10, token = "A", now = 1_000, staleBefore = 0)
        dao.claimBatch(limit = 10, token = "B", now = 999_000, staleBefore = 900_000)

        val affected = dao.updateRetry(claimedByA.first().id, token = "A", retryCount = 1, error = "tarde")

        assertThat(affected).isEqualTo(0)
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.claimToken).isEqualTo("B")
        assertThat(row.syncStatus).isEqualTo("SYNCING")
    }

    @Test
    fun markPermanentlyFailed_con_token_viejo_no_pisa_la_fila_reclamada_por_otro_worker() = runTest {
        // Mismo escenario que release/markSynced/updateRetry, sobre el CUARTO write CAS
        // que gana la tabla con F-10 (markPermanentlyFailed — la escritura del branch
        // SyncOutcome.Permanent). El invariante no es negociable: pase lo que pase con
        // F-10, un token viejo NUNCA debe poder pisar el claim vigente de otro worker.
        dao.insert(newPending(reference = "ref-1"))
        val claimedByA = dao.claimBatch(limit = 10, token = "A", now = 1_000, staleBefore = 0)
        dao.claimBatch(limit = 10, token = "B", now = 999_000, staleBefore = 900_000) // reclamo por stale

        val affected = dao.markPermanentlyFailed(claimedByA.first().id, token = "A", error = "HTTP 404")

        assertThat(affected).isEqualTo(0) // el write de A no hizo nada — ya no es su fila
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.claimToken).isEqualTo("B") // sigue intacta, en manos de B
        assertThat(row.syncStatus).isEqualTo("SYNCING")
        assertThat(row.permanent).isFalse() // tampoco se marcó permanent de más
    }

    @Test
    fun markSynced_limpia_claim_token_y_claimed_at() = runTest {
        // Fix round 2 (one-liner): SUCCESS es terminal. Antes dejaba claim_token/claimed_at
        // puestos hasta el borrado a los 7 días — sin riesgo de doble-claim (SUCCESS nunca es
        // claimable) pero basura innecesaria en una fila que nadie va a tocar de nuevo.
        dao.insert(newPending(reference = "ref-1"))
        val claimed = dao.claimBatch(limit = 1, token = "A", now = 1_000, staleBefore = 0)

        val affected = dao.markSynced(claimed.first().id, token = "A")

        assertThat(affected).isEqualTo(1)
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.syncStatus).isEqualTo("SUCCESS")
        assertThat(row.claimToken).isNull()
        assertThat(row.claimedAt).isNull()
    }

    @Test
    fun getPendingCount_cuenta_filas_PENDING_y_SYNCING() = runTest {
        // Important 1 (coordinador, ronda 2): antes de este fix, sync_status = 'PENDING' era
        // el único estado que existía, así que el badge de "N pagos pendientes" contaba todo
        // lo no sincronizado. Ahora una fila reclamada es SYNCING, no PENDING — sin este fix
        // el badge lee 0 mientras una tanda entera reclamada (hasta 10 pagos ya cobrados)
        // sigue sin llegar al backend.
        repeat(3) { dao.insert(newPending(reference = "ref-$it")) }
        dao.claimBatch(limit = 2, token = "A", now = 1_000, staleBefore = 0) // 2 quedan SYNCING, 1 sigue PENDING

        val pending = dao.getPendingCount()

        assertThat(pending).isEqualTo(3) // las 3 siguen siendo dinero sin registrar
    }

    // --- F-10: resetAllFailed() ya no resucita los fallos de negocio permanentes ---
    // Antes, resetAllFailed() volvía TODAS las filas FAILED a PENDING con retry_count=0,
    // incluyendo las que fallaron por un 400/404/422 que nunca se arregla solo (orden no
    // encontrada, payload mal formado) — se reintentaban en cada reconexión para siempre,
    // en vez de asentarse en un FAILED estable. `permanent` distingue ambos casos.

    @Test
    fun resetAllFailed_no_resucita_los_fallos_permanentes() = runTest {
        dao.insert(newPending(reference = "transitorio"))
        dao.insert(newPending(reference = "permanente"))
        val claimed = dao.claimBatch(limit = 2, token = "A", now = 1_000, staleBefore = 0)
        // FAILED transitorio: llegó al tope de reintentos (red, 5xx…) — SÍ se resucita.
        dao.release(claimed[0].id, token = "A", retryCount = PendingPaymentEntity.MAX_RETRY_ATTEMPTS, error = "red caida")
        // FAILED permanente: un 4xx de negocio — NUNCA se resucita.
        dao.markPermanentlyFailed(claimed[1].id, token = "A", error = "HTTP 404: Order not found")

        val reset = dao.resetAllFailed()

        assertThat(reset).isEqualTo(1) // solo el transitorio contó
        val permanente = dao.findByReference("permanente", venueId = "venue-1")!!
        assertThat(permanente.syncStatus).isEqualTo("FAILED") // sigue FAILED, intacta
        assertThat(permanente.permanent).isTrue()
        val transitorio = dao.findByReference("transitorio", venueId = "venue-1")!!
        assertThat(transitorio.syncStatus).isEqualTo("PENDING") // esta sí vuelve a intentar
        assertThat(transitorio.retryCount).isEqualTo(0) // con el contador en cero
    }

    @Test
    fun resetAllFailedIncludingPermanent_SI_resucita_los_fallos_permanentes() = runTest {
        // Fix round 1: el escape hatch humano. A diferencia de resetAllFailed() (el
        // camino automático, que debe seguir excluyendo permanent=1 para siempre), este
        // método es el tap deliberado del operador — debe poder resucitar TODO, incluidas
        // las filas permanentes, y devolverles permanent=0 (segunda oportunidad, no
        // amnistía en blanco: si el problema sigue ahí, el próximo intento la vuelve a
        // marcar permanent).
        dao.insert(newPending(reference = "transitorio"))
        dao.insert(newPending(reference = "permanente"))
        val claimed = dao.claimBatch(limit = 2, token = "A", now = 1_000, staleBefore = 0)
        dao.release(claimed[0].id, token = "A", retryCount = PendingPaymentEntity.MAX_RETRY_ATTEMPTS, error = "red caida")
        dao.markPermanentlyFailed(claimed[1].id, token = "A", error = "HTTP 404: Order not found")

        val reset = dao.resetAllFailedIncludingPermanent()

        assertThat(reset).isEqualTo(2) // AMBAS, a diferencia de resetAllFailed()
        val permanente = dao.findByReference("permanente", venueId = "venue-1")!!
        assertThat(permanente.syncStatus).isEqualTo("PENDING") // resucitada
        assertThat(permanente.permanent).isFalse() // y el flag se limpió — segunda oportunidad real
        assertThat(permanente.retryCount).isEqualTo(0)
        val transitorio = dao.findByReference("transitorio", venueId = "venue-1")!!
        assertThat(transitorio.syncStatus).isEqualTo("PENDING")
        assertThat(transitorio.retryCount).isEqualTo(0)
    }

    @Test
    fun markSynced_no_puede_voltear_una_fila_FAILED_permanente_a_SUCCESS() = runTest {
        // Peor desenlace posible sin la guarda: un worker rezagado que responde tarde
        // (el pago YA se marcó FAILED permanente mientras tanto) no debe poder resucitarlo
        // como si hubiera sincronizado. La guarda es la misma CAS de claim_token que ya
        // protege a release()/markSynced() entre sí — markPermanentlyFailed() limpia el
        // claim igual que los otros terminales, así que un markSynced() posterior con el
        // mismo token ya no encuentra fila que pisar.
        dao.insert(newPending(reference = "ref-1"))
        val claimed = dao.claimBatch(limit = 1, token = "A", now = 1_000, staleBefore = 0)
        dao.markPermanentlyFailed(claimed.first().id, token = "A", error = "HTTP 404")

        val affected = dao.markSynced(claimed.first().id, token = "A")

        assertThat(affected).isEqualTo(0)
        val row = dao.findByReference("ref-1", venueId = "venue-1")!!
        assertThat(row.syncStatus).isEqualTo("FAILED") // NUNCA se volteó a SUCCESS
        assertThat(row.permanent).isTrue()
    }

    /** Construye un [PendingPaymentEntity] mínimo válido, listo para encolar. */
    private fun newPending(reference: String): PendingPaymentEntity = PendingPaymentEntity(
        referenceNumber = reference,
        venueId = "venue-1",
        staffId = "staff-1",
        amount = "10.00",
        tip = "0.00",
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "SERIAL-1",
        maskedPan = null,
        cardBrand = null,
        entryMode = "CHIP",
        isInternational = false,
        authorizationNumber = null,
        createdAt = System.currentTimeMillis(),
        syncStatus = PendingPaymentEntity.SYNC_STATUS_PENDING,
    )
}
