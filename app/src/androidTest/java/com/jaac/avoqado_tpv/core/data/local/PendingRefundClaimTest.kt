package com.jaac.avoqado_tpv.core.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingRefundDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * El contrato de la cola durable de REEMBOLSOS ([PendingRefundDao]).
 *
 * **Por qué existe:** cuando el SDK ya devolvió el dinero y el POST de registro falla, hoy no se
 * encola nada — el `Payment type=REFUND` no nace, el dinero salió del cajón y el corte del turno
 * cuadra de más. Esta cola es el arreglo, y estas pruebas fijan las tres propiedades sin las
 * cuales la cola sería PEOR que el defecto:
 *
 * 1. **Dos workers no toman la misma fila** — si la tomaran, registrarían el mismo reembolso dos
 *    veces. El servidor ya es idempotente (Fase 0), pero depender sólo de eso sería construir
 *    sobre una red que puede no estar desplegada todavía.
 * 2. **Encolar dos veces no duplica** — la PK es la `idempotencyKey`, así que es imposible por
 *    construcción, no por una comprobación que alguien pueda olvidar.
 * 3. **Lo que no se registró BLOQUEA el cierre de turno** — incluido lo rechazado que el cajero
 *    todavía no ha visto. Sin eso, el turno se firmaría con dinero que salió y no está anotado.
 *
 * Espeja a [PendingPaymentClaimTest], que fija el mismo contrato para los cobros.
 */
class PendingRefundClaimTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PendingRefundDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AvoqadoDatabase::class.java,
        ).build()
        dao = db.pendingRefundDao()
    }

    @After fun teardown() = db.close()

    private fun fila(
        key: String,
        venueId: String = "v1",
        amount: String = "50.00",
        createdAt: Long = 1_000L,
    ) = PendingRefundEntity(
        idempotencyKey = key,
        venueId = venueId,
        staffId = "s1",
        processor = PendingRefundEntity.PROCESSOR_BLUMON,
        originalPaymentId = "pay-orig",
        originalOrderId = null,
        amount = amount,
        originalTotalAmount = "100.00",
        tipRefundCents = null,
        isPartialRefund = true,
        refundReason = "cliente",
        merchantAccountId = "m1",
        blumonSerialNumber = "SER1",
        originalOperationNumber = 42,
        authorizationNumber = "502511",
        referenceNumber = "000000188231",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        createdAt = createdAt,
    )

    // ─── El claim ───────────────────────────────────────────────────────────────

    @Test
    fun dos_claims_concurrentes_no_toman_la_misma_fila() = runTest {
        dao.insertIgnore(fila("k1"))

        val a = dao.claimBatch(limit = 10, token = "worker-a", now = 1_000L, staleBefore = 0L)
        val b = dao.claimBatch(limit = 10, token = "worker-b", now = 1_000L, staleBefore = 0L)

        assertThat(a.map { it.idempotencyKey }).containsExactly("k1")
        assertThat(b).isEmpty()
    }

    @Test
    fun una_fila_abandonada_por_un_worker_muerto_se_puede_reclamar() = runTest {
        // Sin esto, un worker que muere a media reproducción deja el reembolso atorado para
        // siempre: dinero fuera del cajón que nunca se registra.
        dao.insertIgnore(fila("k1"))
        dao.claimBatch(limit = 10, token = "worker-muerto", now = 1_000L, staleBefore = 0L)

        val rescate = dao.claimBatch(limit = 10, token = "worker-vivo", now = 700_000L, staleBefore = 600_000L)

        assertThat(rescate.map { it.idempotencyKey }).containsExactly("k1")
    }

    @Test
    fun encolar_dos_veces_la_misma_llave_deja_UNA_fila() = runTest {
        dao.insertIgnore(fila("k1"))
        val segunda = dao.insertIgnore(fila("k1"))

        assertThat(segunda).isEqualTo(-1L) // IGNORE: no insertó
        assertThat(dao.getPendingCount()).isEqualTo(1)
    }

    // ─── El CAS: un worker tardío no pisa a otro ────────────────────────────────

    @Test
    fun marcar_exito_con_el_token_equivocado_no_toca_la_fila() = runTest {
        dao.insertIgnore(fila("k1"))
        dao.claimBatch(limit = 10, token = "worker-a", now = 1_000L, staleBefore = 0L)

        val afectadas = dao.markSuccess("k1", token = "worker-b")

        assertThat(afectadas).isEqualTo(0)
        assertThat(dao.getPendingCount()).isEqualTo(1) // sigue en vuelo, de A
    }

    @Test
    fun P1_al_agotar_los_intentos_la_fila_SIGUE_PENDING_porque_el_servidor_nunca_la_rechazo() = runTest {
        // Auditoría de Codex F4: FAILED por conteo sin `permanent` era una fila muerta — nadie la
        // reclamaba, nadie podía reconocerla y bloqueaba el cierre para siempre.
        dao.insertIgnore(fila("k1"))
        dao.claimBatch(limit = 10, token = "w", now = 1_000L, staleBefore = 0L)

        dao.release("k1", token = "w", retryCount = PendingRefundEntity.MAX_RETRY_ATTEMPTS, error = "sin red")

        assertThat(dao.getPendingCount()).isEqualTo(1)
        assertThat(dao.getFailedCount()).isEqualTo(0)
        assertThat(dao.claimBatch(limit = 10, token = "w2", now = 2_000L, staleBefore = 0L).single().retryCount)
            .isEqualTo(PendingRefundEntity.MAX_RETRY_ATTEMPTS)
    }

    @Test
    fun unresolvedForPayment_ve_lo_pendiente_y_lo_rechazado_pero_no_lo_registrado() = runTest {
        dao.insertIgnore(fila("a"))
        dao.insertIgnore(fila("b").copy(syncStatus = PendingRefundEntity.SYNC_STATUS_FAILED, permanent = true, acknowledged = true))
        dao.insertIgnore(fila("c").copy(syncStatus = PendingRefundEntity.SYNC_STATUS_SUCCESS))

        val sinResolver = dao.unresolvedForPayment(fila("a").originalPaymentId)

        assertThat(sinResolver.map { it.idempotencyKey }).containsExactly("a", "b")
    }

    // ─── La barrera del cierre de turno ─────────────────────────────────────────

    @Test
    fun bloquea_el_cierre_lo_pendiente_y_lo_rechazado_no_reconocido() = runTest {
        dao.insertIgnore(fila("pend"))
        dao.insertIgnore(
            fila("rech").copy(syncStatus = PendingRefundEntity.SYNC_STATUS_FAILED, permanent = true),
        )
        dao.insertIgnore(
            fila("visto").copy(
                syncStatus = PendingRefundEntity.SYNC_STATUS_FAILED,
                permanent = true,
                acknowledged = true,
            ),
        )
        dao.insertIgnore(fila("ok").copy(syncStatus = PendingRefundEntity.SYNC_STATUS_SUCCESS))
        dao.insertIgnore(fila("otro", venueId = "v2"))

        val bloquean = dao.blockingForVenue("v1").map { it.idempotencyKey }

        assertThat(bloquean).containsExactly("pend", "rech")
    }

    @Test
    fun reconocer_un_rechazo_libera_el_cierre() = runTest {
        // La salida de emergencia: sin ella, un 400 definitivo dejaría la caja sin poder cerrarse.
        dao.insertIgnore(
            fila("rech").copy(syncStatus = PendingRefundEntity.SYNC_STATUS_FAILED, permanent = true),
        )
        assertThat(dao.blockingForVenue("v1")).hasSize(1)

        dao.acknowledge("rech", by = "staff-1", at = 1_000L)

        assertThat(dao.blockingForVenue("v1")).isEmpty()
    }

    @Test
    fun la_limpieza_no_se_lleva_pendientes_ni_rechazados() = runTest {
        val viejo = 1_000L
        dao.insertIgnore(fila("ok", createdAt = viejo).copy(syncStatus = PendingRefundEntity.SYNC_STATUS_SUCCESS))
        dao.insertIgnore(fila("pend", createdAt = viejo))
        dao.insertIgnore(
            fila("rech", createdAt = viejo).copy(syncStatus = PendingRefundEntity.SYNC_STATUS_FAILED),
        )

        val borradas = dao.deleteOldSuccess(cutoff = 900_000L)

        assertThat(borradas).isEqualTo(1)
        assertThat(dao.getPendingCount()).isEqualTo(1)
        assertThat(dao.getFailedCount()).isEqualTo(1)
    }
}
