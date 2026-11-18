package com.jaac.avoqado_tpv.features.tables.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * hz:nube0000:nab0e38e (encargo 64000e, 18-nov-2026) — «Liberar mesa» sube ANTES que el cobro con tarjeta de su cuenta.
 *
 * La mesa se salda con TARJETA y la red se cae entre la aprobación del banco y el registro en Avoqado: el cobro queda en
 * `pending_payments`. «Nueva Orden» no puede liberar la mesa en línea y encola `CLEAR_TABLE` con la cuenta (D79). Al volver
 * la red, `PaymentSyncWorker.doWork` reproducía la cola de Mesas ANTES de reclamar los cobros: el SERVIDOR veía la cuenta
 * sin pagar, rechazaba el «Liberar mesa» (`Cannot clear table with unpaid order …`) y quedaba en «Acciones rechazadas»
 * aunque el cobro entrara segundos después.
 *
 * Regla (rediseño tras Codex r2): un `CLEAR_TABLE` cuya cuenta tiene un cobro con tarjeta de esta terminal que todavía no
 * sube NO se manda (queda DISCARDED): cuando ese cobro se registre, el SERVIDOR libera la mesa solo (`releaseTableIfSettled`),
 * y si nunca se registra, la cuenta sigue debiendo y el rechazo habría sido el mismo. La cola de Mesas no se frena. Un cobro
 * sin registrar y sin revisar (FAILED) no cambia nada: el «Liberar mesa» se manda como antes.
 *
 * Room, cola de cobros, `SyncOutbox`, `TableSyncCoordinator` y el trabajador son REALES; el SERVIDOR de mentiras sigue la regla
 * del real (`clearTableInTransaction`: rechaza si la cuenta todavía debe).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LiberarMesaConSuCobroEnColaRoomTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val tableApi = mockk<TablesApiService>()
    private val record = mockk<RecordPaymentUseCase>()
    private lateinit var db: AvoqadoDatabase
    private lateinit var outbox: SyncOutbox
    private lateinit var coordinator: TableSyncCoordinator

    /** Lo que el servidor recibió, en orden: `COBRO:<cuenta>` o el tipo del intent. */
    private val llegadas = CopyOnWriteArrayList<String>()
    private val cuentasPagadas = mutableSetOf<String>()
    private var cobrosSinRed = false
    /** Lo que hace el cajero MIENTRAS el trabajador sube el cobro (Codex r1 #4). */
    private var mientrasSubeElCobro: suspend () -> Unit = {}

    @Before fun setUp() {
        every { storage.getVenueId() } returns VENUE
        every { storage.getStaffId() } returns "staff-1"
        every { storage.getToken() } returns "token"
        every { storage.getSerialNumber() } returns "PAX-1"
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        outbox = SyncOutbox(db.syncIntentDao(), tableApi, storage, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(),
            libreta = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true)))
        coordinator = TableSyncCoordinator(outbox, TableSession(), PendingRoundCart())

        coEvery { tableApi.syncIntents(VENUE, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>()
            Response.success(SyncIntentsResponse(data = request.intents.map { intent ->
                llegadas += intent.type
                val cuenta = intent.payload.get("orderId")?.asString
                if (intent.type == SyncIntentTypes.CLEAR_TABLE && cuenta != null && cuenta !in cuentasPagadas) {
                    SyncIntentAck(intent.id, "REJECTED", "BUSINESS_RULE", "Cannot clear table with unpaid order 42")
                } else {
                    SyncIntentAck(intent.id, "ACKED")
                }
            }))
        }
        coEvery { record(any(), any(), any(), any(), true) } coAnswers {
            if (cobrosSinRed) return@coAnswers Result.failure(IOException("sin red"))
            mientrasSubeElCobro()
            val cuenta = (firstArg<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
                as com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.OrderPayment).orderId
            llegadas += "COBRO:$cuenta"
            cuentasPagadas += cuenta
            Result.success(recibo())
        }
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `P2 el Liberar mesa de una cuenta con su cobro con tarjeta en cola no se manda ni queda por revisar`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)

        assertThat(trabajador().doWork()).isEqualTo(ListenableWorker.Result.success())

        // El cobro sube (y en el SERVIDOR real libera la mesa al registrarse); el «Liberar mesa» nunca sale.
        assertThat(llegadas).containsExactly("COBRO:$CUENTA")
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_DISCARDED)
        // Nunca en silencio: la fila dice por qué no salió.
        assertThat(fila(liberar).errorCode).isEqualTo("LO_LIBERA_SU_COBRO")
        assertThat(fila(liberar).message).contains("el servidor libera la mesa")
        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
    }

    @Test
    fun `lo que va DETRAS del Liberar mesa no se frena`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)
        val abrirOtra = outbox.enqueue(VENUE, SyncIntentTypes.OPEN_TABLE,
            JsonObject().apply { addProperty("tableId", "mesa-2"); addProperty("localOrderId", "local-2") })

        trabajador().doWork()

        assertThat(llegadas).containsExactly(SyncIntentTypes.OPEN_TABLE, "COBRO:$CUENTA").inOrder()
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_DISCARDED)
        assertThat(fila(abrirOtra).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
    }

    @Test
    fun `si el cobro todavia no puede subir, el Liberar mesa tampoco se manda ni queda por revisar`() = runTest {
        cobrosSinRed = true
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)

        trabajador().doWork()

        assertThat(llegadas).doesNotContain(SyncIntentTypes.CLEAR_TABLE)
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_DISCARDED)
        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
        // El cobro sigue en su cola, intacto.
        assertThat(db.pendingPaymentDao().hayCobroSubiendose(VENUE, CUENTA)).isTrue()
    }

    @Test
    fun `Codex r1 4 - el Liberar mesa que se encola MIENTRAS sube el cobro sale en la siguiente pasada y entra`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        var liberar: String? = null
        mientrasSubeElCobro = { liberar = liberarMesa(MESA, CUENTA) }

        trabajador().doWork()
        trabajador().doWork()

        // Cuando sale, su cobro ya subió: el SERVIDOR lo acepta, nunca lo rechaza.
        assertThat(llegadas).containsExactly("COBRO:$CUENTA", SyncIntentTypes.CLEAR_TABLE).inOrder()
        assertThat(fila(checkNotNull(liberar)).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
    }

    @Test
    fun `control - el cobro en cola es de OTRA cuenta, el Liberar mesa sale primero como hoy`() = runTest {
        cuentasPagadas += CUENTA // su cuenta ya se cobró en línea
        db.pendingPaymentDao().insertRow(cobroConTarjeta(OTRA_CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)

        trabajador().doWork()

        assertThat(llegadas).containsExactly(SyncIntentTypes.CLEAR_TABLE, "COBRO:$OTRA_CUENTA").inOrder()
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
    }

    @Test
    fun `control - un cobro que ya no se sube solo (FAILED) no detiene la cola de Mesas para siempre`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA, estado = PendingPaymentEntity.SYNC_STATUS_FAILED))
        val liberar = liberarMesa(MESA, CUENTA)

        trabajador().doWork()

        assertThat(llegadas).containsExactly(SyncIntentTypes.CLEAR_TABLE)
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    // ─── Ayudantes ──────────────────────────────────────────────────────────────

    private suspend fun liberarMesa(mesa: String, cuenta: String): String =
        outbox.enqueue(VENUE, SyncIntentTypes.CLEAR_TABLE, JsonObject().apply {
            addProperty("tableId", mesa)
            addProperty("orderId", cuenta)
        })

    private suspend fun fila(id: String): SyncIntentEntity = db.syncIntentDao().allForVenue(VENUE).first { it.id == id }

    private fun trabajador(): PaymentSyncWorker {
        val state = PaymentQueueStateManager()
        val pagos = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        val reembolsos = RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), state)
        return TestListenableWorkerBuilder<PaymentSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    PaymentSyncWorker(appContext, workerParameters, pagos, record, state, reembolsos, libretaSinFila(),
                        mockk(), tableSyncCoordinator = coordinator)
            })
            .build()
    }

    /** La cola es anterior a la libreta (sin fila para la llave): el veredicto no cambia nada. */
    private fun libretaSinFila() = mockk<PaymentAttemptLedger>(relaxed = true).also {
        coEvery { it.aplicarVeredictoDelServidor(any()) } returns Result.success(
            ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.SIN_FILA, false, null, false),
        )
    }

    private fun recibo() = PaymentReceipt(
        paymentId = "pmt-nab0", receiptUrl = "", accessKey = "", amount = BigDecimal("240.00"), tipAmount = BigDecimal.ZERO,
        serverStatus = "COMPLETED",
    )

    private fun cobroConTarjeta(cuenta: String, estado: String = PendingPaymentEntity.SYNC_STATUS_PENDING) = PendingPaymentEntity(
        referenceNumber = "ref-$cuenta",
        venueId = VENUE,
        staffId = "staff-1",
        amount = "240.00",
        tip = "0.00",
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "2841548417",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CONTACTLESS",
        isInternational = false,
        authorizationNumber = "237886",
        idempotencyKey = "llave-$cuenta",
        paymentProcessor = ProcessorType.BLUMON.name,
        orderId = cuenta,
        orderNumber = "42",
        createdAt = System.currentTimeMillis(),
        syncStatus = estado,
    )

    private companion object {
        const val VENUE = "venue-1"
        const val MESA = "mesa-8"
        const val CUENTA = "c000000000000000000000aaa"
        const val OTRA_CUENTA = "c000000000000000000000bbb"
    }
}
