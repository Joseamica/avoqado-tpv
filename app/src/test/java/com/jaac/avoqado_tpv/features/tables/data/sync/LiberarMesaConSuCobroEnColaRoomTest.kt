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
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TablesResponse
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
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
 * Regla (diseño final, tras Codex r3): la cola de Mesas NO cambia (FIFO, D6 y el rechazo, iguales que hoy). Cuando la terminal
 * va a enseñar «Acciones rechazadas» (el contador del plano y la lista), pregunta por la cuenta de cada «Liberar mesa»
 * rechazado; si ya está pagada (el cobro entró y el SERVIDOR liberó la mesa solo), lo quita con la razón escrita. Si la cuenta
 * todavía debe, o no se puede preguntar, se queda como hoy.
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
    private var sinRedParaPreguntar = false
    private var laLiberacionDelServidorFallo = false

    @Before fun setUp() {
        every { storage.getVenueId() } returns VENUE
        every { storage.getStaffId() } returns "staff-1"
        every { storage.getToken() } returns "token"
        every { storage.getSerialNumber() } returns "PAX-1"
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        outbox = SyncOutbox(db.syncIntentDao(), tableApi, storage, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
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
        coEvery { tableApi.getTables(VENUE) } coAnswers {
            if (sinRedParaPreguntar) throw IOException("sin red")
            // Como el SERVIDOR real: al registrar el cobro que salda la cuenta, libera la mesa (`releaseTableIfSettled`),
            // salvo que esa liberación falle (Codex r4 #1).
            val libre = CUENTA in cuentasPagadas && !laLiberacionDelServidorFallo
            Response.success(TablesResponse(data = listOf(
                if (libre) DiningTable(id = MESA, number = "8", status = "AVAILABLE")
                else DiningTable(id = MESA, number = "8", status = "OCCUPIED", currentOrder = mockk(relaxed = true)),
            )))
        }
        coEvery { record(any(), any(), any(), any(), true) } coAnswers {
            if (cobrosSinRed) return@coAnswers Result.failure(IOException("sin red"))
            val cuenta = (firstArg<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
                as com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.OrderPayment).orderId
            llegadas += "COBRO:$cuenta"
            cuentasPagadas += cuenta
            Result.success(recibo())
        }
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `P2 el Liberar mesa que llego antes que su cobro con tarjeta no se queda en Acciones rechazadas`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)

        assertThat(trabajador().doWork()).isEqualTo(ListenableWorker.Result.success())
        // La cola sigue como hoy: el «Liberar mesa» sale antes que el cobro y el SERVIDOR lo rechaza.
        assertThat(llegadas).containsExactly(SyncIntentTypes.CLEAR_TABLE, "COBRO:$CUENTA").inOrder()

        // Lo que hace la pantalla antes de enseñar «Acciones rechazadas»:
        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(1)

        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_DISMISSED)
        // Nunca en silencio: la fila dice por qué se quitó, y conserva el rechazo del SERVIDOR.
        assertThat(fila(liberar).message).contains("la mesa ya está libre en el servidor")
        assertThat(fila(liberar).message).contains("Cannot clear table with unpaid order 42")
        assertThat(fila(liberar).errorCode).isEqualTo("BUSINESS_RULE")
    }

    @Test
    fun `si el cobro todavia no sube, el rechazo se queda hasta que la cuenta este pagada`() = runTest {
        cobrosSinRed = true
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)
        trabajador().doWork()

        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(0)
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)

        // Vuelve la red: el cobro sube y, la siguiente vez que se ve la lista, el rechazo se quita solo.
        cobrosSinRed = false
        trabajador().doWork()
        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(1)
        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
    }

    @Test
    fun `control - la cuenta todavia debe (no hubo cobro): el rechazo se queda como hoy`() = runTest {
        val liberar = liberarMesa(MESA, CUENTA)
        trabajador().doWork()

        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(0)
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
        assertThat(outbox.rejectedIntents(VENUE)).hasSize(1)
    }

    @Test
    fun `Codex r4 1 - la cuenta quedo pagada pero el SERVIDOR no libero la mesa - el rechazo se queda para que alguien la vea`() = runTest {
        laLiberacionDelServidorFallo = true
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)
        trabajador().doWork()

        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(0)
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    @Test
    fun `control - sin red para preguntar por la cuenta, el rechazo se queda`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        val liberar = liberarMesa(MESA, CUENTA)
        trabajador().doWork()
        sinRedParaPreguntar = true

        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(0)
        assertThat(fila(liberar).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    @Test
    fun `control - otros rechazos de Mesas no se tocan aunque su cuenta este pagada`() = runTest {
        cuentasPagadas += CUENTA
        val pago = outbox.enqueue(VENUE, SyncIntentTypes.PAY_CASH, JsonObject().apply { addProperty("orderId", CUENTA) })
        outbox.applyAck(pago, SyncIntentEntity.STATUS_REJECTED, "BUSINESS_RULE", "otro rechazo")

        assertThat(mesas().resolverLiberarMesaQueYaNoHaceFalta(VENUE)).isEqualTo(0)
        assertThat(fila(pago).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    @Test
    fun `control - la cola de Mesas no cambia - lo que va detras del Liberar mesa sale en orden`() = runTest {
        db.pendingPaymentDao().insertRow(cobroConTarjeta(CUENTA))
        liberarMesa(MESA, CUENTA)
        val abrirOtra = outbox.enqueue(VENUE, SyncIntentTypes.OPEN_TABLE,
            JsonObject().apply { addProperty("tableId", "mesa-2"); addProperty("localOrderId", "local-2") })

        trabajador().doWork()

        assertThat(llegadas).containsExactly(SyncIntentTypes.CLEAR_TABLE, SyncIntentTypes.OPEN_TABLE, "COBRO:$CUENTA").inOrder()
        assertThat(fila(abrirOtra).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
    }

    // ─── Ayudantes ──────────────────────────────────────────────────────────────

    private suspend fun liberarMesa(mesa: String, cuenta: String): String =
        outbox.enqueue(VENUE, SyncIntentTypes.CLEAR_TABLE, JsonObject().apply {
            addProperty("tableId", mesa)
            addProperty("orderId", cuenta)
        })

    private fun mesas() = TablesRepository(tableApi, outbox, mockk(relaxed = true))

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
