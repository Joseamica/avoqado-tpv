package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.Result as AppResult
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.*
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class Task5ShiftOwnershipRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var shifts: ShiftRepository
    private lateinit var ledger: PaymentAttemptLedger
    private val api = mockk<ApiService>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private var enabled = true
    private var duringQuery: (String) -> Unit = {}

    @Before fun setup() {
        every { storage.getVenueId() } returns "v1"
        every { storage.getStaffId() } returns "staff-a"
        every { storage.getToken() } returns "token-a"
        every { storage.getKioskVenueId() } returns null
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } answers { enabled }
        every { storage.getString("permissions_cache", null) } returns
            "v1|staff-a\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund"
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ sql, _ -> duringQuery(sql) }, java.util.concurrent.Executor { it.run() }).build()
        shifts = ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk<RefundQueueRepository>(relaxed = true), appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val settings = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>()
        every { settings.getCurrentSettings() } returns TpvSettings.DEFAULT.copy(paymentLedgerMode = PaymentLedgerMode.OFF)
        ledger = PaymentAttemptLedger(db.paymentAttemptDao(), settings)
    }
    @After fun cleanup() { db.close() }
    private suspend fun open() = (shifts.guardarAperturaLocal("v1", "staff-a", BigDecimal("100.00")) as AppResult.Success).data
    /** R5: sólo el transporte S1 está simulado; mapping/autoridad/commit son los de Room. */
    private suspend fun confirmCryptoOpening(a: com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity) {
        val dto = Gson().fromJson("""{"id":"c000000000000000000000041","venueId":"v1","staffId":"staff-a","startTime":"2026-10-28T10:00:00Z","status":"OPEN","startingCash":"100.00","cashDrawerSessionId":"c000000000000000000000042","localId":"${a.localSessionId}","openingDisposition":"OWN","fondoAplicado":"100.00"}""", com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto::class.java)
        shifts.registrarResultadoAperturaLocal("v1", a.localSessionId, dto).getOrThrow()
    }
    private fun json(kind: String, id: String? = null) = Gson().toJson(mapOf(
        "shiftMembership" to kind, "localShiftSessionId" to id, "venueId" to "v1", "staffId" to "staff-a"))
    private suspend fun reserve(key: String, kind: String, id: String? = null, refund: Boolean = false) =
        ledger.openAttempt(key, "v1", "BLUMON", 1000, 0, if (refund) "REFUND" else "FAST", json(kind, id), if (refund) "REFUND" else "SALE")

    @Test fun `libreta guarda pertenencia completa antes del kernel con observabilidad OFF`() = runTest {
        val a = open()
        assertThat(ledger.isEnabled()).isTrue()
        assertThat(reserve("card-a", "MANAGED", a.localSessionId)).isTrue()
        val row = db.paymentAttemptDao().getById("card-a")!!
        assertThat(row.shiftMembership).isEqualTo("MANAGED")
        assertThat(row.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(shifts.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(AppResult.Error::class.java)
    }

    @Test fun `reserva MANAGED perdedora contra CLOSE no puede entrar al SDK`() = runTest {
        val a = open()
        assertThat(shifts.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(AppResult.Success::class.java)
        assertThat(reserve("after-close", "MANAGED", a.localSessionId)).isFalse()
        assertThat(ledger.markKernelEntered("after-close")).isFalse()
        assertThat(db.paymentAttemptDao().getById("after-close")).isNull()
    }

    @Test fun `OFF explicito sobrevive cambio de config sin crear apertura`() = runTest {
        enabled = false
        assertThat(reserve("off-card", "NO_SHIFT_SYSTEM")).isTrue()
        enabled = true
        assertThat(db.paymentAttemptDao().getById("off-card")?.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
        assertThat(ledger.markKernelEntered("off-card")).isTrue()
    }

    @Test fun `refund sin OPEN conserva UNKNOWN y no toma caja vieja`() = runTest {
        assertThat(reserve("refund-current", "UNKNOWN", refund = true)).isTrue()
        val row = db.paymentAttemptDao().getById("refund-current")!!
        assertThat(row.shiftMembership).isEqualTo("UNKNOWN")
        assertThat(row.localShiftSessionId).isNull()
        assertThat(ledger.markKernelEntered("refund-current")).isTrue()
    }

    @Test fun `efectivo usa cerca real de INSERT contra CLOSE sin asumir abierto por contexto`() = runTest {
        val a = open()
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val payment = QueuedPayment.desdeContexto(PaymentContext.FastPayment(
            venueId = "v1", staffId = "staff-a", amount = BigDecimal.TEN, merchantAccountId = null,
            idempotencyKey = "cash-a", shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId),
            null, CardDetails.CASH, "EFECTIVO", "CASH-a", null)
        assertThat(PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueueAndClaim(payment).isFailure).isTrue()
        assertThat(db.pendingPaymentDao().findByReference("CASH-a", "v1")).isNull()
    }

    private fun queued(key: String, membership: String = "UNKNOWN", session: String? = null, request: String? = null) =
        QueuedPayment.desdeContexto(PaymentContext.FastPayment("v1", "staff-a", amount = BigDecimal.TEN,
            merchantAccountId = null, idempotencyKey = key, shiftMembership = membership,
            localShiftSessionId = session, terminalPaymentRequestId = request), null, CardDetails.CASH,
            "EFECTIVO", "CASH-$key", null)

    @Test fun `familia tarjeta y refund captura ON OFF y no hereda turno del pago antiguo`() = runTest {
        for (processor in listOf("BLUMON", "ANGELPAY")) for (refund in listOf(false, true)) {
            enabled = false
            val key = "$processor-$refund"
            assertThat(ledger.openAttemptForShift(shifts, key, "v1", processor, 1000, 0,
                if (refund) "REFUND" else "FAST", "{\"shiftId\":\"old-payment-shift\"}", if (refund) "REFUND" else "SALE")).isTrue()
            enabled = true
            val row = db.paymentAttemptDao().getById(key)!!
            assertThat(row.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
            assertThat(row.localShiftSessionId).isNull()
            assertThat(com.google.gson.JsonParser.parseString(row.paymentContextJson).asJsonObject.get("shiftId").isJsonNull).isTrue()
            assertThat(ledger.markKernelEntered(key)).isTrue()
            ledger.markKernelRefused(key, "DECLINED_BY_KERNEL")
        }
    }

    @Test fun `config ilegible no autoriza venta por defecto y refund ON sin OPEN sigue permitido`() = runTest {
        every { storage.contains("enable_shifts") } returns false
        assertThat(ledger.openAttemptForShift(shifts, "unknown-sale", "v1", "ANGELPAY", 1000, 0, "FAST", "{}")).isFalse()
        every { storage.contains("enable_shifts") } returns true
        assertThat(ledger.openAttemptForShift(shifts, "refund-no-open", "v1", "BLUMON", 1000, 0, "REFUND", "{}", "REFUND")).isTrue()
        assertThat(db.paymentAttemptDao().getById("refund-no-open")?.shiftMembership).isEqualTo("UNKNOWN")
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
    }

    @Test fun `ON OFF efectivo NO_SHIFT ON cierre A espera directo y worker sin esperar OPEN B`() = runTest {
        val a = open()
        enabled = false
        val queue = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        val claim = shifts.guardarNuevaObligacionDeCaja("v1") { belonging ->
            queue.enqueueAndClaim(queued("off-cash", belonging.kind)).getOrThrow()
        }
        enabled = true
        assertThat(shifts.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(AppResult.Success::class.java)
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isFalse()
        assertThat(db.pendingPaymentDao().findByReference("CASH-off-cash", "v1")?.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(queue.markSynced(claim.queueId, claim.claimToken!!)).isEqualTo(1)
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isTrue()
        val b = open()
        queue.enqueueAndClaim(queued("b", "MANAGED", b.localSessionId)).getOrThrow()
        assertThat(shifts.obligacionesDelCierreLocal("v1", a.localSessionId).total).isEqualTo(0)
        assertThat(shifts.obligacionesDelCierreLocal("v1", b.localSessionId).total).isEqualTo(1)
    }

    @Test fun `callback aprobado tras cierre conserva A y entrega responsabilidad en el mismo commit`() = runTest {
        val a = open()
        assertThat(ledger.openAttemptForShift(shifts, "late", "v1", "BLUMON", 1000, 0, "FAST", "{\"staffId\":\"staff-a\"}")).isTrue()
        ledger.markAuthorized("late", null, null, null)
        // Simula cierre remoto ya observado: una aprobación aceptada no se readmite ni se pierde.
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
        val row = queued("late").copy(authorizationNumber = "APPROVED", merchantAccountId = "merchant")
        assertThat(PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(row).isSuccess).isTrue()
        assertThat(db.paymentAttemptDao().getById("late")?.state).isEqualTo("ENTREGADA_A_COLA")
        assertThat(db.pendingPaymentDao().findByReference("CASH-late", "v1")?.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(shifts.obligacionesDelCierreLocal("v1", a.localSessionId).rows.map { it.owner }).containsExactly("PAYMENT")
    }

    @Test fun `handoff falla segunda escritura y revierte cola sin liberar intento`() = runTest {
        val a = open()
        ledger.openAttemptForShift(shifts, "rollback", "v1", "ANGELPAY", 1000, 0, "FAST", "{\"staffId\":\"staff-a\"}")
        ledger.markAuthorized("rollback", null, null, null)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER handoff_failure BEFORE UPDATE ON payment_attempts WHEN NEW.state='ENTREGADA_A_COLA' BEGIN SELECT RAISE(ABORT,'handoff failed'); END")
        assertThat(PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(queued("rollback")).isFailure).isTrue()
        assertThat(db.pendingPaymentDao().findByReference("CASH-rollback", "v1")).isNull()
        assertThat(db.paymentAttemptDao().getById("rollback")?.state).isEqualTo("AUTORIZADO")
        assertThat(shifts.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(AppResult.Error::class.java)
    }

    @Test fun `inbox PROCESSING sin intento bloquea y cancel anterior nunca genera pertenencia`() = runTest {
        val a = open()
        val inbox = com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentInbox(db.remotePaymentRequestDao(), shifts)
        val received = com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity(
            "remote", "v1", 1000, 0, null, false, null, null, null, "2026-10-28", "RECEIVED", createdAt = 1, updatedAt = 1)
        db.remotePaymentRequestDao().insert(received)
        assertThat(inbox.markProcessingForVenue("remote", "v1")).isTrue()
        assertThat(db.remotePaymentRequestDao().getById("remote")?.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(shifts.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(AppResult.Error::class.java)
        assertThat(shifts.obligacionesDelCierreLocal("v1", a.localSessionId).rows.map { it.owner }).containsExactly("REMOTE")
        db.remotePaymentRequestDao().insert(received.copy(requestId = "cancel-first"))
        inbox.cancel("cancel-first")
        assertThat(inbox.markProcessingForVenue("cancel-first", "v1")).isFalse()
        assertThat(db.remotePaymentRequestDao().getById("cancel-first")?.shiftMembership).isEqualTo("UNKNOWN")
    }

    @Test fun `Mesas PAY_CASH usa Room comun y tag OFF no requiere apertura`() = runTest {
        val a = open()
        val outbox = com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox(db.syncIntentDao(), mockk(), storage, shifts)
        enabled = false
        outbox.enqueue("v1", "PAY_CASH", com.google.gson.JsonObject().apply { addProperty("amountCents", 1000) }, "table-cash")
        enabled = true
        shifts.guardarCierreLocal("v1", a.localSessionId)
        assertThat(db.syncIntentDao().allForVenue("v1").single().shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isFalse()
        outbox.applyAck("table-cash", "ACKED", null)
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isTrue()
    }

    @Test fun `COUNT pagina reconocimiento usan mismo predicado con mas de veinte y B demostrada`() = runTest {
        val a = open()
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val b = open()
        for (i in 1..25) PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(queued("unknown-$i")).getOrThrow()
        PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(queued("b", "MANAGED", b.localSessionId)).getOrThrow()
        val page1 = shifts.obligacionesDelCierreLocal("v1", a.localSessionId)
        val page2 = shifts.obligacionesDelCierreLocal("v1", a.localSessionId, 20)
        assertThat(page1.total).isEqualTo(25)
        assertThat(page1.rows).hasSize(20)
        assertThat(page2.rows).hasSize(5)
        assertThat((page1.rows+page2.rows).map { it.ownerId }.toSet()).hasSize(25)
        for (row in page1.rows+page2.rows) assertThat(db.localShiftSessionDao().perteneceAlCierre("v1", a.localSessionId, row.owner, row.ownerId)).isEqualTo(1)
    }

    @Test fun `cripto respuesta POST perdida conserva clave cuerpo y consulta sin emitir otra vez`() = runTest {
        val a = open()
        confirmCryptoOpening(a)
        val key = "cabcdefghijklmnopqrstuvwx"
        val request = com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest(1000, staffId = "staff-a", deviceSerialNumber = "N86", idempotencyKey = key)
        coEvery { api.initiateDurableCryptoPayment("v1", any()) } throws java.io.IOException("response lost")
        assertThat(runCatching { shifts.iniciarCriptoDurable("v1", request) }.isFailure).isTrue()
        val row = db.localCryptoOperationDao().leer("v1", key)!!
        assertThat(row.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(row.emissionStatus).isEqualTo("UNCERTAIN")
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val result = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto(paymentId = key,
            operation = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationDto(key, "SENDING", "PENDING", false, requiresReview = true))
        coEvery { api.getCryptoOperation("v1", key) } returns retrofit2.Response.success(com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResponse(true, result))
        assertThat(runCatching { shifts.iniciarCriptoDurable("v1", request) }.isFailure).isTrue()
        coVerify(exactly = 1) { api.initiateDurableCryptoPayment("v1", any()) }
        coVerify(exactly = 1) { api.getCryptoOperation("v1", key) }
        assertThat(db.localCryptoOperationDao().leer("v1", key)?.requestJson).isEqualTo(row.requestJson)
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isFalse()
    }

    @Test fun `cripto cancelUI positivo proveedor sin registro sigue bloqueando y aviso tardio persiste sin sesion`() = runTest {
        val a = open()
        confirmCryptoOpening(a)
        val key = "cabcdefghijklmnopqrstuvw1"
        val data = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto(paymentId = key, requestId = "provider-1",
            paymentUrl = "https://example.test/pay", expiresAt = "2026-10-29T00:00:00Z", expiresInSeconds = 60,
            operation = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationDto(key, "ISSUED", "FAILED", false, "CO", true))
        coEvery { api.initiateDurableCryptoPayment("v1", any()) } returns retrofit2.Response.success(com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResponse(true, data))
        shifts.iniciarCriptoDurable("v1", com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest(1000, staffId = "staff-a", deviceSerialNumber = "N86", idempotencyKey = key))
        shifts.guardarCierreLocal("v1", a.localSessionId)
        every { storage.getStaffId() } returns null
        shifts.anotarAvisoCripto("v1", key, "provider-1")
        assertThat(db.localCryptoOperationDao().leer("v1", key)?.lastError).contains("Aviso recibido")
        assertThat(db.localCryptoOperationDao().leer("v1", key)?.financialRecorded).isFalse()
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isFalse()
        every { storage.getStaffId() } returns "staff-a"
        val recorded = data.copy(operation = data.operation!!.copy(financialStatus = "COMPLETED", financialRecorded = true))
        coEvery { api.getCryptoOperation("v1", key) } returns retrofit2.Response.success(com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResponse(true, recorded))
        shifts.consultarCriptoDurable("v1", key)
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isTrue()
        assertThat(db.localCryptoOperationDao().leer("v1", key)?.staffId).isEqualTo("staff-a")
    }

    @Test fun `VentaLocal PAX Nexgo guarda tag al confirmar y no al preparar carrito`() = runTest {
        for (processor in listOf("BLUMON", "ANGELPAY")) {
            val a = open()
            val writer = com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository(db.pendingPaymentDao(), shifts)
            val cart = com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal("cart-$processor", "v1", "staff-a", "old-shift", 1, 1000, "{}", emptyList())
            shifts.guardarCierreLocal("v1", a.localSessionId)
            assertThat(writer.guardar(cart, processor, "", "terminal").isFailure).isTrue()
            enabled = false
            writer.guardar(cart, processor, "", "terminal").getOrThrow()
            val saved = db.pendingPaymentDao().getAllPending().first { it.idempotencyKey == cart.llave }
            assertThat(saved.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
            assertThat(saved.localShiftSessionId).isNull()
            assertThat(saved.shiftId).isNull()
            enabled = true
        }
    }

    private fun refundRow(key: String) = com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity(
        idempotencyKey = key, venueId = "v1", staffId = "new-pin", processor = "BLUMON", originalPaymentId = "old-payment",
        amount = "10.00", originalTotalAmount = "10.00", isPartialRefund = false, refundReason = "OTHER",
        merchantAccountId = "merchant", blumonSerialNumber = "serial", originalOperationNumber = 1,
        authorizationNumber = "APPROVED", referenceNumber = "refund-reference", entryMode = "CHIP", createdAt = 1)

    @Test fun `refund callback tras A CLOSED entrega tag y autor originales y worker confirma ledger en el mismo commit`() = runTest {
        val a = open()
        ledger.openAttemptForShift(shifts, "refund-late", "v1", "BLUMON", 1000, 0, "REFUND", "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":null,\"tipRefundCents\":null}", "REFUND")
        ledger.markAuthorized("refund-late", null, null, null)
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
        enabled = false
        db.pendingRefundDao().insertIgnore(refundRow("refund-late"))
        assertThat(db.paymentAttemptDao().getById("refund-late")?.state).isEqualTo("ENTREGADA_A_COLA")
        val row = db.pendingRefundDao().existingRow("v1", "refund-late")!!
        assertThat(row.staffId).isEqualTo("staff-a")
        assertThat(row.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(row.toDomain().shiftMembership).isEqualTo("MANAGED")
        assertThat(row.toDomain().toEntity().localShiftSessionId).isEqualTo(a.localSessionId)
        val token = "refund-worker"
        db.pendingRefundDao().claimBatch(10, token, System.currentTimeMillis(), 0)
        assertThat(db.pendingRefundDao().markSuccess("refund-late", token)).isEqualTo(1)
        assertThat(db.paymentAttemptDao().getById("refund-late")?.state).isEqualTo("REGISTRADO")
        assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isTrue()
    }

    @Test fun `remoto tras negativo conserva owner de futura ejecucion y no crea cripto en A cerrada`() = runTest {
        val a = open()
        val inbox = com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentInbox(db.remotePaymentRequestDao(), shifts)
        db.remotePaymentRequestDao().insert(com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity(
            "remote-next", "v1", 1000, 0, null, false, null, null, null, "2026-10-28", "RECEIVED", createdAt = 1, updatedAt = 1))
        assertThat(inbox.markProcessingForVenue("remote-next", "v1")).isTrue()
        ledger.openAttemptForShift(shifts, "discarded", "v1", "BLUMON", 1000, 0, "FAST", "{\"terminalPaymentRequestId\":\"remote-next\"}")
        ledger.markDiscardedBeforeCharge("discarded", "pre-sdk")
        assertThat(shifts.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(AppResult.Error::class.java)
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
        assertThat(runCatching { shifts.iniciarCriptoDurable("v1", com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest(
            1000, staffId = "staff-a", deviceSerialNumber = "N86", idempotencyKey = "cabcdefghijklmnopqrstuvw2"), "remote-next") }.isFailure).isTrue()
        assertThat(db.localCryptoOperationDao().contar("v1")).isEqualTo(0)
        coVerify(exactly = 0) { api.initiateDurableCryptoPayment(any(), any()) }
    }

    @Test fun `PIN token permiso o storage falla durante write ahead real y revierte antes del POST`() = runTest {
        val a = open()
        for (change in listOf("PIN", "token", "permission", "storage")) {
            every { storage.getStaffId() } returns "staff-a"
            every { storage.getToken() } returns "token-a"
            every { storage.getString("permissions_cache", null) } returns "v1|staff-a\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund"
            val recorder = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder>()
            val usecase = com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase(recorder,
                mockk(relaxed = true), PaymentQueueRepositoryImpl(db.pendingPaymentDao()), shifts)
            duringQuery = { sql -> if (sql.startsWith("INSERT") && sql.contains("pending_payments")) {
                when (change) {
                    "PIN" -> every { storage.getStaffId() } returns "staff-b"
                    "token" -> every { storage.getToken() } returns "token-b"
                    "permission" -> every { storage.getString("permissions_cache", null) } returns "v1|staff-a\nshifts:read"
                    else -> every { storage.getBoolean("enable_shifts", true) } throws IllegalStateException("secure read failed")
                }
            } }
            val result = usecase(PaymentContext.FastPayment("v1", "staff-a", amount = BigDecimal.TEN,
                merchantAccountId = null, idempotencyKey = "cash-$change"), CardDetails.CASH, "EFECTIVO", "CASH-$change")
            duringQuery = {}
            assertThat(com.jaac.avoqado_tpv.features.payment.domain.usecase.CashAdmissionRejectedException.identifica(result.exceptionOrNull())).isTrue()
            assertThat(db.pendingPaymentDao().findByReference("CASH-$change", "v1")).isNull()
            assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
            coVerify(exactly = 0) { recorder.recordPayment(any(), any(), any(), any()) }
        }
    }

    @Test fun `reserva PREPARANDO real contra CLOSE serializa ambas ordenes sin hueco preSDK`() = runTest {
        val a = open()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        duringQuery = { sql -> if (sql.startsWith("INSERT") && sql.contains("payment_attempts")) {
            entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        } }
        val reservation = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            ledger.openAttemptForShift(shifts, "race", "v1", "BLUMON", 1000, 0, "FAST", "{}")
        }
        entered.await()
        val close = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { shifts.guardarCierreLocal("v1", a.localSessionId) }
        release.countDown()
        assertThat(reservation.await()).isTrue()
        assertThat(close.await()).isInstanceOf(AppResult.Error::class.java)
        duringQuery = {}
        assertThat(db.paymentAttemptDao().getById("race")?.localShiftSessionId).isEqualTo(a.localSessionId)
        ledger.markDiscardedBeforeCharge("race", "before-sdk")
        shifts.guardarCierreLocal("v1", a.localSessionId)
        assertThat(ledger.openAttemptForShift(shifts, "loses", "v1", "BLUMON", 1000, 0, "FAST", "{}")).isFalse()
        assertThat(db.paymentAttemptDao().getById("loses")).isNull()
    }

    @Test fun `reconocimiento local solo efectivo permite UNKNOWN y excluye B demostrada sin acuse`() = runTest {
        val a = open()
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val b = open()
        val dao = db.pendingPaymentDao()
        val unknown = dao.insert(cashEntity("ack-unknown").copy(syncStatus = "FAILED"))
        val other = dao.insert(cashEntity("ack-b", "MANAGED", b.localSessionId).copy(syncStatus = "FAILED"))
        assertThat(shifts.reconocerPagoEnCierreLocal(a.localSessionId, other, "v1", "staff-a", "token-a") { true }).isEqualTo(0)
        assertThat(dao.leerParaReconocer(other, "v1")?.acknowledged).isFalse()
        assertThat(shifts.reconocerPagoEnCierreLocal(a.localSessionId, unknown, "v1", "staff-a", "token-a") { true }).isEqualTo(1)
        assertThat(dao.leerParaReconocer(unknown, "v1")?.acknowledged).isTrue()
        val pending = dao.insert(cashEntity("ack-pending"))
        val card = dao.insert(cashEntity("ack-card").copy(referenceNumber = "CARD-ack", authorizationNumber = "APPROVED", syncStatus = "FAILED"))
        assertThat(shifts.reconocerPagoEnCierreLocal(a.localSessionId, pending, "v1", "staff-a", "token-a") { true }).isEqualTo(0)
        assertThat(shifts.reconocerPagoEnCierreLocal(a.localSessionId, card, "v1", "staff-a", "token-a") { true }).isEqualTo(0)
        assertThat(dao.leerParaReconocer(card, "v1")?.acknowledged).isFalse()
    }

    @Test fun `reconocer revierte acuse cuando PIN token pantalla o storage cambian durante UPDATE`() = runTest {
        val a = open()
        for (change in listOf("PIN", "token", "screen", "storage")) {
            every { storage.getVenueId() } returns "v1"
            every { storage.getStaffId() } returns "staff-a"
            every { storage.getToken() } returns "token-a"
            var inClose = true
            val id = db.pendingPaymentDao().insert(cashEntity("ack-$change").copy(syncStatus = "FAILED"))
            val original = db.pendingPaymentDao().leerParaReconocer(id, "v1")
            duringQuery = { sql -> if (sql.trimStart().startsWith("UPDATE") && sql.contains("pending_payments") && sql.contains("acknowledged")) {
                when (change) {
                    "PIN" -> every { storage.getStaffId() } returns "staff-b"
                    "token" -> every { storage.getToken() } returns "token-b"
                    "screen" -> inClose = false
                    else -> every { storage.getVenueId() } throws IllegalStateException("secure read failed")
                }
            } }
            assertThat(runCatching { shifts.reconocerPagoEnCierreLocal(a.localSessionId, id, "v1", "staff-a", "token-a") { inClose } }.isFailure).isTrue()
            duringQuery = {}
            assertThat(db.pendingPaymentDao().leerParaReconocer(id, "v1")).isEqualTo(original)
            assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
        }
    }

    @Test fun `reconocer revierte si trigger cambia pertenencia o caja durante el mismo UPDATE`() = runTest {
        val a = open()
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val b = open()
        val closedA = db.localShiftSessionDao().leer("v1", a.localSessionId)
        val dao = db.pendingPaymentDao()
        for (change in listOf("membership", "session")) {
            val id = dao.insert(cashEntity("ack-trigger-$change").copy(syncStatus = "FAILED"))
            val original = dao.leerParaReconocer(id, "v1")
            val update = if (change == "membership") "UPDATE pending_payments SET shift_membership='MANAGED',local_shift_session_id='${b.localSessionId}' WHERE id=NEW.id;"
                else "UPDATE local_shift_sessions SET updated_at=updated_at+1 WHERE local_session_id='${a.localSessionId}';"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER task5_ack_change AFTER UPDATE OF acknowledged ON pending_payments WHEN NEW.id=$id BEGIN $update END")
            assertThat(runCatching { shifts.reconocerPagoEnCierreLocal(a.localSessionId, id, "v1", "staff-a", "token-a") { true } }.isFailure).isTrue()
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER task5_ack_change")
            assertThat(dao.leerParaReconocer(id, "v1")).isEqualTo(original)
            assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(closedA)
        }
    }


    @Test fun `snapshot ilegible nuevo conserva columnas B y legacy ilegible nunca demuestra OFF`() = runTest {
        val a = open()
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val b = open()
        ledger.openAttemptForShift(shifts, "known-b", "v1", "BLUMON", 1000, 0, "FAST", "{\"staffId\":\"staff-a\"}")
        ledger.markAuthorized("known-b", null, null, null)
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET payment_context_json='{' WHERE attempt_id='known-b'")
        val restored = shifts.restaurarContextoDelIntento(PaymentContext.FastPayment("v1", "staff-a", amount = BigDecimal.TEN,
            merchantAccountId = null, idempotencyKey = "known-b"))
        assertThat(restored.shiftMembership).isEqualTo("MANAGED")
        assertThat(restored.localShiftSessionId).isEqualTo(b.localSessionId)
        assertThat(db.paymentAttemptDao().getById("known-b")?.lastError).isEqualTo("snapshot_caja_ilegible_o_contradictorio")
        assertThat(shifts.obligacionesDelCierreLocal("v1", a.localSessionId).total).isEqualTo(0)
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET payment_context_json=? WHERE attempt_id='known-b'",
            arrayOf("{\"shiftMembership\":\"NO_SHIFT_SYSTEM\",\"staffId\":\"new-pin\"}"))
        val contradictory = shifts.restaurarContextoDelIntento(restored)
        assertThat(contradictory.localShiftSessionId).isEqualTo(b.localSessionId)
        assertThat(contradictory.staffId).isEqualTo("staff-a")
        ledger.markRecorded("known-b")
        assertThat(reserve("legacy-unreadable", "UNKNOWN")).isTrue()
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET payment_context_json='{' WHERE attempt_id='legacy-unreadable'")
        val legacy = shifts.restaurarContextoDelIntento(PaymentContext.FastPayment("v1", "staff-a", amount = BigDecimal.TEN,
            merchantAccountId = null, idempotencyKey = "legacy-unreadable", shiftMembership = "NO_SHIFT_SYSTEM"))
        assertThat(legacy.shiftMembership).isEqualTo("UNKNOWN")
        assertThat(shifts.obligacionesDelCierreLocal("v1", a.localSessionId).rows.map { it.ownerId }).contains("legacy-unreadable")
    }

    @Test fun `handoff contradictorio no sobreescribe pertenencia original ni entrega intento`() = runTest {
        val a = open()
        ledger.openAttemptForShift(shifts, "mismatch", "v1", "BLUMON", 1000, 0, "FAST", "{\"staffId\":\"staff-a\"}")
        ledger.markAuthorized("mismatch", null, null, null)
        val result = PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(queued("mismatch", "NO_SHIFT_SYSTEM"))
        assertThat(result.isFailure).isTrue()
        assertThat(db.pendingPaymentDao().findByReference("CASH-mismatch", "v1")).isNull()
        val row = db.paymentAttemptDao().getById("mismatch")!!
        assertThat(row.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(row.state).isEqualTo("AUTORIZADO")
    }


    @Test fun `referencias autoritativas contradictorias de B siguen en COUNT pagina y reconocimiento de A`() = runTest {
        val a = open()
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val b = open()
        val id = db.pendingPaymentDao().insert(cashEntity("contradict-b", "MANAGED", b.localSessionId).copy(
            shiftId = "wrong-server-shift", cashDrawerSessionId = "wrong-drawer", syncStatus = "FAILED"))
        val page = shifts.obligacionesDelCierreLocal("v1", a.localSessionId)
        assertThat(page.total).isEqualTo(1)
        assertThat(page.rows.single().ownerId).isEqualTo(id.toString())
        assertThat(db.localShiftSessionDao().perteneceAlCierre("v1", a.localSessionId, "PAYMENT", id.toString())).isEqualTo(1)
    }


    private fun cashEntity(key: String, membership: String = "UNKNOWN", session: String? = null) =
        com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity(referenceNumber = "CASH-$key", venueId = "v1",
            staffId = "staff-a", amount = "10.00", tip = "0.00", rating = null, merchantAccountId = "", blumonSerialNumber = "",
            maskedPan = null, cardBrand = null, entryMode = "CASH", isInternational = false, authorizationNumber = "EFECTIVO",
            idempotencyKey = key, createdAt = 1, shiftMembership = membership, localShiftSessionId = session)


    @Test fun `efectivo ya durable no se readmite ni se marca rechazo antes de admitir al repetir tras CLOSE`() = runTest {
        val a = open()
        enabled = false
        val recorder = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder>()
        coEvery { recorder.recordPayment(any(), any(), any(), any()) } returns kotlin.Result.success(
            PaymentReceipt("cash-recorded", "", "", BigDecimal.TEN, BigDecimal.ZERO))
        val usecase = com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase(recorder, mockk(relaxed = true),
            PaymentQueueRepositoryImpl(db.pendingPaymentDao()), shifts)
        val context = PaymentContext.FastPayment("v1", "staff-a", amount = BigDecimal.TEN, merchantAccountId = null, idempotencyKey = "already-owned")
        assertThat(usecase(context, CardDetails.CASH, "EFECTIVO", "CASH-already-owned").isSuccess).isTrue()
        val original = db.pendingPaymentDao().findByReference("CASH-already-owned", "v1")!!
        assertThat(original.syncStatus).isEqualTo("SUCCESS")
        enabled = true
        shifts.guardarCierreLocal("v1", a.localSessionId)
        val repeated = usecase(context, CardDetails.CASH, "EFECTIVO", "CASH-already-owned")
        assertThat(repeated.isFailure).isTrue()
        assertThat(repeated.exceptionOrNull()?.message).isEqualTo("La venta ya está guardada; no vuelvas a cobrarla.")
        assertThat(com.jaac.avoqado_tpv.features.payment.domain.usecase.CashAdmissionRejectedException.identifica(repeated.exceptionOrNull())).isFalse()
        assertThat(db.pendingPaymentDao().findByReference("CASH-already-owned", "v1")).isEqualTo(original)
        val contradictory = usecase(context.copy(amount = BigDecimal("11.00")), CardDetails.CASH, "EFECTIVO", "CASH-new-reference")
        assertThat(contradictory.isFailure).isTrue()
        assertThat(contradictory.exceptionOrNull()).isInstanceOf(com.jaac.avoqado_tpv.features.payment.domain.usecase.CashOwnerReviewRequiredException::class.java)
        assertThat(contradictory.exceptionOrNull()?.message).contains("no coincide")
        assertThat(db.pendingPaymentDao().findByReference("CASH-new-reference", "v1")).isNull()
        coVerify(exactly = 1) { recorder.recordPayment(any(), any(), any(), any()) }
    }

    @Test fun `primer handoff SALE amount incompatible conserva owner evidencia y diagnostico`() = runTest {
        comprobarHandoffIncompatible(refund = false, change = "amount")
    }

    @Test fun `primer handoff SALE tip incompatible conserva owner evidencia y diagnostico`() = runTest {
        comprobarHandoffIncompatible(refund = false, change = "tip")
    }

    @Test fun `primer handoff SALE order incompatible conserva owner evidencia y diagnostico`() = runTest {
        comprobarHandoffIncompatible(refund = false, change = "order")
    }

    @Test fun `primer handoff REFUND amount incompatible conserva owner evidencia y diagnostico`() = runTest {
        comprobarHandoffIncompatible(refund = true, change = "amount")
    }

    @Test fun `primer handoff REFUND tip incompatible conserva owner evidencia y diagnostico`() = runTest {
        comprobarHandoffIncompatible(refund = true, change = "tip")
    }

    @Test fun `primer handoff REFUND order incompatible conserva owner evidencia y diagnostico`() = runTest {
        comprobarHandoffIncompatible(refund = true, change = "order")
    }

    private suspend fun comprobarHandoffIncompatible(refund: Boolean, change: String) {
        val a = open()
        val key = "conflict-$refund-$change"
        val json = if (refund) "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":\"order-original\",\"tipRefundCents\":100}"
            else "{\"staffId\":\"staff-a\",\"orderId\":\"order-original\"}"
        assertThat(ledger.openAttemptForShift(shifts, key, "v1", "BLUMON", 1000, if (refund) 0 else 100,
            if (refund) "REFUND" else "ORDER", json, if (refund) "REFUND" else "SALE")).isTrue()
        assertThat(ledger.markHostResponded(key, true, "op", "original-ref", "original-auth")).isTrue()
        ledger.markAuthorized(key, null, null, "CHIP")
        val original = db.paymentAttemptDao().getById(key)!!
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
        every { storage.getStaffId() } returns "other-pin"
        val amount = if (change == "amount") "11.00" else "10.00"
        val order = if (change == "order") "order-other" else "order-original"
        val result = if (refund) {
            val queue = com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager())
            queue.enqueue(refundRow(key).copy(amount = amount, originalOrderId = order,
                tipRefundCents = if (change == "tip") 200 else 100, referenceNumber = "original-ref", authorizationNumber = "original-auth").toDomain())
        } else PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(queued(key).copy(referenceNumber = "original-ref",
            authorizationNumber = "original-auth", entryMode = "CHIP", orderId = order, amount = BigDecimal(amount),
            tip = BigDecimal(if (change == "tip") "2.00" else "1.00")))
        assertThat(result.isFailure).isTrue()
        assertThat(db.pendingPaymentDao().findByReference("original-ref", "v1")).isNull()
        assertThat(db.pendingRefundDao().existingRow("v1", key)).isNull()
        val preserved = db.paymentAttemptDao().getById(key)!!
        assertThat(preserved.copy(lastError = original.lastError)).isEqualTo(original)
        assertThat(preserved.lastError).contains("handoff_contrato_contradictorio")
        assertThat(preserved.hostApproved).isTrue()
        assertThat(preserved.authCode).isEqualTo("original-auth")
        assertThat(preserved.referenceNumber).isEqualTo("original-ref")
        assertThat(preserved.state).isEqualTo("AUTORIZADO")
        ledger.markRecordFailed(key, "later recording failure")
        assertThat(db.paymentAttemptDao().getById(key)?.lastError).contains("handoff_contrato_contradictorio")
        assertThat(db.paymentAttemptDao().getById(key)?.state).isEqualTo("REGISTRO_FALLIDO")
        val now = System.currentTimeMillis()
        val lease = now + 60_000
        assertThat(db.paymentAttemptDao().claimRecovery(key, "v1", now, lease)).isEqualTo(1)
        assertThat(db.paymentAttemptDao().completeRecovery(key, "v1", lease, "REGISTRO_FALLIDO", now, "otro error de red")).isEqualTo(1)
        assertThat(db.paymentAttemptDao().getById(key)?.lastError).contains("handoff_contrato_contradictorio")
        assertThat(db.localShiftSessionDao().contarObligaciones("v1", a.localSessionId)).isEqualTo(1)
    }

    @Test fun `Nexgo captura nulls opcionales de refund antes SDK real de fixture sin OPEN`() = runTest {
        enabled = false
        val factory = mockk<com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory>()
        val adapter = mockk<com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter>()
        every { factory.get(com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.ANGELPAY) } returns adapter
        val sale = com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction(
            processorType = com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.ANGELPAY, reference = "000000188231", amount = 10.0,
            authorizationCode = "AUTH", status = "APROBADA", cardType = null, last4 = null, cardBin = "45551203", entryMode = "contactless",
            date = "2026-07-01", time = "06:00:00", operationType = "VENTA", merchantName = null, affiliation = null, terminal = null,
            folio = "F1", waiter = null, tip = 0.0, issuingBank = null, cardMethod = null, creationDate = "2026-07-01T12:00:00Z",
            aid = null, arqc = null, postOperationType = null, postOperationReference = null, postOperationAuthorization = null, postOperationStatus = null)
        coEvery { adapter.getTransactionHistory(any()) } returns kotlin.Result.success(listOf(sale))
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } coAnswers {
            val row = db.paymentAttemptDao().getOpenOlderThan("v1", listOf("AUTORIZANDO"), Long.MAX_VALUE).single()
            assertThat(row.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
            val captured = com.google.gson.JsonParser.parseString(row.paymentContextJson).asJsonObject
            assertThat(captured.has("originalOrderId")).isTrue()
            assertThat(captured.get("originalOrderId").isJsonNull).isTrue()
            assertThat(captured.has("tipRefundCents")).isTrue()
            assertThat(captured.get("tipRefundCents").isJsonNull).isTrue()
            assertThat(db.inTransaction()).isFalse()
            kotlin.Result.success(com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult(
                approved = true, status = "APPROVED", message = "Approved", authorizationCode = "REFUND-AUTH", reference = "REFUND-REF", errorCode = null))
        }
        val auth = mockk<com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository>()
        every { auth.getStaffId() } returns "staff-a"
        val refunds = com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager())
        val usecase = com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordAngelPayRefundUseCase(mockk(), auth, factory, refunds, ledger,
            java.time.Clock.fixed(java.time.Instant.parse("2026-07-01T18:00:00Z"), java.time.ZoneOffset.UTC),
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder(), shifts)
        val approved = usecase.processSdkRefund("000000188231", java.time.Instant.parse("2026-07-01T12:00:00Z"), RefundReason.CUSTOMER_REQUEST,
            ApplicationProvider.getApplicationContext(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, "old-payment", "v1")
        com.google.common.truth.Truth.assertWithMessage("SDK fixture result: %s", approved.exceptionOrNull()).that(approved.isSuccess).isTrue()
        val saved = db.paymentAttemptDao().getById(approved.getOrThrow().idempotencyKey)!!
        assertThat(saved.hostApproved).isTrue()
        assertThat(saved.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
        coVerify(exactly = 1) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.refundTransaction(any(), any(), any(), any()) }
    }


    @Test fun `registro directo SALE no hace POST de un cuerpo contradictorio con el owner`() = runTest {
        open()
        val context = PaymentContext.OrderPayment("v1", "staff-a", amount = BigDecimal.TEN, merchantAccountId = "merchant",
            orderId = "order-original", idempotencyKey = "restore-sale")
        ledger.openAttemptForShift(shifts, "restore-sale", "v1", "BLUMON", 1000, 0, "ORDER", Gson().toJson(context))
        ledger.markHostResponded("restore-sale", true, "op", "original-ref", "original-auth")
        val before = db.paymentAttemptDao().getById("restore-sale")!!
        val fast = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder>()
        val order = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder>()
        coEvery { order.recordPayment(any(), any(), any(), any()) } returns kotlin.Result.success(PaymentReceipt("p", "", "", BigDecimal("11.00"), BigDecimal.ZERO))
        val usecase = com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase(fast, order, PaymentQueueRepositoryImpl(db.pendingPaymentDao()), shifts)
        val result = runCatching { usecase(context.copy(amount = BigDecimal("11.00")), CardDetails("****", CardBrand.UNKNOWN, CardEntryMode.CHIP), "original-auth", "original-ref") }
        assertThat(result.isFailure || result.getOrThrow().isFailure).isTrue()
        coVerify(exactly = 0) { order.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
        val after = db.paymentAttemptDao().getById("restore-sale")!!
        assertThat(after.copy(lastError = before.lastError)).isEqualTo(before)
        assertThat(after.lastError).contains("handoff_contrato_contradictorio")
    }

    @Test fun `restaurar REFUND no entrega cuerpo contradictorio para POST ni pierde evidencia positiva`() = runTest {
        open()
        val context = PaymentContext.RefundPayment("v1", "staff-a", amount = BigDecimal.TEN, merchantAccountId = "merchant",
            blumonSerialNumber = "serial", idempotencyKey = "restore-refund", originalPaymentId = "old-payment", originalOrderId = "order-original",
            originalTotalAmount = BigDecimal.TEN, refundReason = RefundReason.OTHER, originalOperationNumber = 1, tipRefundCents = 100)
        ledger.openAttemptForShift(shifts, "restore-refund", "v1", "BLUMON", 1000, 0, "REFUND", Gson().toJson(context), "REFUND")
        ledger.markHostResponded("restore-refund", true, "op", "original-ref", "original-auth")
        val before = db.paymentAttemptDao().getById("restore-refund")!!
        assertThat(runCatching { shifts.restaurarContextoDelIntento(context.copy(amount = BigDecimal("11.00"))) }.isFailure).isTrue()
        val after = db.paymentAttemptDao().getById("restore-refund")!!
        assertThat(after.copy(lastError = before.lastError)).isEqualTo(before)
        assertThat(after.lastError).contains("handoff_contrato_contradictorio")
    }


    @Test fun `handoff legacy SALE sin contrato demostrado no se libera por segundo CAS`() = runTest { comprobarSegundoCasLegacy(false) }
    @Test fun `handoff legacy REFUND sin contrato demostrado no se libera por segundo CAS`() = runTest { comprobarSegundoCasLegacy(true) }

    private suspend fun comprobarSegundoCasLegacy(refund: Boolean) {
        enabled = false
        val key = "legacy-second-cas-$refund"
        assertThat(ledger.openAttemptForShift(shifts, key, "v1", "BLUMON", 1000, 0,
            if (refund) "REFUND" else "FAST", "{}", if (refund) "REFUND" else "SALE")).isTrue()
        ledger.markHostResponded(key, true, "op", "ref-$key", "auth-$key")
        ledger.markRecordFailed(key, "pendiente de registrar")
        val source = db.paymentAttemptDao().getById(key)!!
        if (refund) db.pendingRefundDao().insertIgnore(refundRow(key))
        else PaymentQueueRepositoryImpl(db.pendingPaymentDao()).enqueue(queued(key)).getOrThrow()
        assertThat(db.paymentAttemptDao().getById(key)).isEqualTo(source)
        ledger.markDeliveredToQueue(key)
        assertThat(db.paymentAttemptDao().getById(key)).isEqualTo(source)
    }

    @Test fun `repetir refund con llave durable y contrato incompatible falla sin pisar el original`() = runTest {
        val queue = com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager())
        for (change in listOf("amount", "order", "tip")) {
            val key = "duplicate-refund-$change"
            val original = refundRow(key).copy(originalOrderId = "order-original", tipRefundCents = null)
            assertThat(queue.enqueue(original.toDomain()).isSuccess).isTrue()
            val saved = db.pendingRefundDao().existingRow("v1", key)!!
            val repeated = original.copy(amount = if (change == "amount") "11.00" else original.amount,
                originalOrderId = if (change == "order") "order-other" else original.originalOrderId,
                tipRefundCents = if (change == "tip") 0 else null)
            assertThat(queue.enqueue(repeated.toDomain()).isFailure).isTrue()
            val kept = db.pendingRefundDao().existingRow("v1", key)!!
            assertThat(kept.copy(lastError = saved.lastError)).isEqualTo(saved)
            assertThat(kept.lastError).contains("handoff_contrato_contradictorio")
        }
    }

    @Test fun `compatibilidad de handoff prueba sucesor existente y SUCCESS resuelve sin reautorizar`() = runTest {
        enabled = false
        for (refund in listOf(false, true)) for (synced in listOf(false, true)) {
            val key = "compatible-$refund-$synced"
            val json = if (refund) "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":null,\"tipRefundCents\":null}"
                else "{\"staffId\":\"staff-a\"}"
            ledger.openAttemptForShift(shifts, key, "v1", "BLUMON", 1000, 0, if (refund) "REFUND" else "FAST", json, if (refund) "REFUND" else "SALE")
            ledger.markHostResponded(key, true, "op", "ref-$key", "auth-$key")
            ledger.markRecordFailed(key, "pendiente")
            // Representa un sucesor durable de la versión anterior, sin ejecutar su handoff nuevo.
            val status = if (synced) "SUCCESS" else "PENDING"
            if (refund) db.pendingRefundDao().insertRow(refundRow(key).copy(staffId = "staff-a", shiftMembership = "NO_SHIFT_SYSTEM", syncStatus = status))
            else db.pendingPaymentDao().insertRow(cashEntity(key, "NO_SHIFT_SYSTEM").copy(syncStatus = status))
            ledger.markDeliveredToQueue(key)
            assertThat(db.paymentAttemptDao().getById(key)?.state).isEqualTo(if (synced) "REGISTRADO" else "ENTREGADA_A_COLA")
        }
    }

    @Test fun `Nexgo refund duplicado contradictorio conserva cola original y no hace POST`() = runTest {
        val original = refundRow("duplicate-before-post").copy(staffId = "staff-a", processor = "ANGELPAY", blumonSerialNumber = "",
            originalOperationNumber = 0, authorizationNumber = "ANGELPAY_SDK", referenceNumber = "sdk-reference", entryMode = "OTHER")
        db.pendingRefundDao().insertRow(original)
        val auth = mockk<com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository>()
        every { auth.getStaffId() } returns "staff-a"
        val recorder = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder>()
        val refunds = com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl(db.pendingRefundDao(), recorder, com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager())
        val usecase = com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordAngelPayRefundUseCase(recorder, auth, mockk(), refunds, ledger,
            java.time.Clock.systemUTC(), com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder(), shifts)
        val result = usecase.recordInBackend("old-payment", null, "v1", "merchant", BigDecimal.TEN, BigDecimal("11.00"), RefundReason.OTHER,
            "sdk-reference", null, BigDecimal.ZERO, "duplicate-before-post")
        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { recorder.recordRefund(any(), any(), any(), any(), any(), any()) }
        val kept = db.pendingRefundDao().existingRow("v1", "duplicate-before-post")!!
        assertThat(kept.copy(lastError = original.lastError)).isEqualTo(original)
        assertThat(kept.lastError).contains("handoff_contrato_contradictorio")
    }


    @Test fun `confirmacion SALE amount incompatible conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "amount") }
    @Test fun `confirmacion SALE tip incompatible conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "tip") }
    @Test fun `confirmacion SALE order incompatible conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "order") }
    @Test fun `confirmacion REFUND amount incompatible conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "amount") }
    @Test fun `confirmacion REFUND tip incompatible conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "tip") }
    @Test fun `confirmacion REFUND order incompatible conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "order") }

    @Test fun `confirmacion SALE staff no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "staff") }
    @Test fun `confirmacion SALE membership no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "membership") }
    @Test fun `confirmacion SALE session no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "session") }
    @Test fun `confirmacion SALE drawer no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "drawer") }
    @Test fun `confirmacion SALE legacy no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "legacy") }
    @Test fun `confirmacion SALE unreadable no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "unreadable") }
    @Test fun `confirmacion SALE no_actor no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(false, "no_actor") }
    @Test fun `confirmacion REFUND staff no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "staff") }
    @Test fun `confirmacion REFUND membership no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "membership") }
    @Test fun `confirmacion REFUND session no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "session") }
    @Test fun `confirmacion REFUND drawer no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "drawer") }
    @Test fun `confirmacion REFUND legacy no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "legacy") }
    @Test fun `confirmacion REFUND unreadable no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "unreadable") }
    @Test fun `confirmacion REFUND no_actor no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "no_actor") }
    @Test fun `confirmacion REFUND payment no demostrada conserva SUCCESS y fuente original`() = runTest { comprobarConfirmacionIncompatible(true, "payment") }

    private suspend fun comprobarConfirmacionIncompatible(refund: Boolean, change: String, delivered: Boolean = false) {
        val a = open()
        val key = "ack-conflict-$refund-$change"
        val json = if (refund) "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":\"order-original\",\"tipRefundCents\":100}"
            else "{\"staffId\":\"staff-a\",\"orderId\":\"order-original\"}"
        assertThat(ledger.openAttemptForShift(shifts, key, "v1", "BLUMON", 1000, if (refund) 0 else 100,
            if (refund) "REFUND" else "ORDER", json, if (refund) "REFUND" else "SALE")).isTrue()
        ledger.markHostResponded(key, true, "op", "original-ref", "original-auth")
        ledger.markAuthorized(key, null, null, "CHIP")
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET last_error='motivo_financiero_original' WHERE attempt_id=?", arrayOf(key))
        if (delivered) db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state='ENTREGADA_A_COLA' WHERE attempt_id=?", arrayOf(key))
        if (change == "legacy") db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET legacy_shadow=1 WHERE attempt_id=?", arrayOf(key))
        if (change == "unreadable" || change == "no_actor") db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET payment_context_json=? WHERE attempt_id=?", arrayOf(if (change == "unreadable") "{" else "{}", key))
        val original = db.paymentAttemptDao().getById(key)!!
        val staff = if (change == "staff") "another-actor" else "staff-a"
        val membership = if (change == "membership") "UNKNOWN" else "MANAGED"
        val session = if (change == "session") "another-session" else a.localSessionId
        val drawer = if (change == "drawer") "another-drawer" else null
        val amount = if (change == "amount") "11.00" else "10.00"
        val order = if (change == "order") "order-other" else "order-original"
        // insertRow representa una cola heredada ya durable; no simula la cerca/Tx que se verifica.
        val token = "legacy-token"
        if (refund) {
            db.pendingRefundDao().insertRow(refundRow(key).copy(staffId = staff, amount = amount, originalOrderId = order,
                originalPaymentId = if (change == "payment") "another-payment" else "old-payment",
                tipRefundCents = if (change == "tip") 200 else 100, referenceNumber = "original-ref", authorizationNumber = "original-auth",
                shiftMembership = membership, localShiftSessionId = session, cashDrawerSessionId = drawer, syncStatus = "SYNCING", claimToken = token, claimedAt = 1))
            assertThat(db.pendingRefundDao().markSuccess(key, token)).isEqualTo(1)
            assertThat(db.pendingRefundDao().existingRow("v1", key)?.syncStatus).isEqualTo("SUCCESS")
        } else {
            val id = db.pendingPaymentDao().insertRow(cashEntity(key, membership, session).copy(amount = amount, staffId = staff, cashDrawerSessionId = drawer,
                tip = if (change == "tip") "2.00" else "1.00", orderId = order, referenceNumber = "original-ref", authorizationNumber = "original-auth",
                entryMode = "CHIP", syncStatus = "SYNCING", claimToken = token, claimedAt = 1))
            assertThat(db.pendingPaymentDao().markSynced(id, token)).isEqualTo(1)
            assertThat(db.pendingPaymentDao().findByReference("original-ref", "v1")?.syncStatus).isEqualTo("SUCCESS")
        }
        val source = db.paymentAttemptDao().getById(key)!!
        assertThat(source.copy(lastError = original.lastError)).isEqualTo(original)
        assertThat(source.lastError).contains("handoff_contrato_contradictorio")
        assertThat(source.lastError).contains("motivo_financiero_original")
        assertThat(shifts.obligacionesDelCierreLocal("v1", a.localSessionId).rows.map { it.owner }).containsExactly("ATTEMPT")
    }

    @Test fun `confirmacion SALE compatible y lease obsoleto conservan identidad y atomicidad`() = runTest { comprobarConfirmacionCompatible(false) }
    @Test fun `confirmacion REFUND compatible y lease obsoleto conservan identidad y atomicidad`() = runTest { comprobarConfirmacionCompatible(true) }
    @Test fun `confirmacion SALE revierte SUCCESS si falla escritura del intento`() = runTest { comprobarConfirmacionCompatible(false, rollback = true) }
    @Test fun `confirmacion REFUND revierte SUCCESS si falla escritura del intento`() = runTest { comprobarConfirmacionCompatible(true, rollback = true) }

    private suspend fun comprobarConfirmacionCompatible(refund: Boolean, rollback: Boolean = false) {
        val a = open()
        val key = "ack-compatible-$refund-$rollback"
        val json = if (refund) "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":null,\"tipRefundCents\":null}"
            else "{\"staffId\":\"staff-a\"}"
        assertThat(ledger.openAttemptForShift(shifts, key, "v1", "BLUMON", 1000, 0,
            if (refund) "REFUND" else "FAST", json, if (refund) "REFUND" else "SALE")).isTrue()
        ledger.markHostResponded(key, true, "op", "ref", "auth")
        ledger.markAuthorized(key, null, null, "CHIP")
        val source = db.paymentAttemptDao().getById(key)!!
        val token = "current-token"
        val refundDao = db.pendingRefundDao()
        val saleDao = db.pendingPaymentDao()
        val saleId = if (refund) {
            refundDao.insertRow(refundRow(key).copy(staffId = "staff-a", shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId,
                syncStatus = "SYNCING", claimToken = token, claimedAt = 1))
            0L
        } else saleDao.insertRow(cashEntity(key, "MANAGED", a.localSessionId).copy(syncStatus = "SYNCING", claimToken = token, claimedAt = 1))
        val sale = if (refund) null else saleDao.findByReference("CASH-$key", "v1")!!
        val returned = if (refund) refundDao.existingRow("v1", key)!! else null
        assertThat(if (refund) refundDao.markSuccess(key, "stale-token") else saleDao.markSynced(saleId, "stale-token")).isEqualTo(0)
        assertThat(db.paymentAttemptDao().getById(key)).isEqualTo(source)
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
        if (rollback) db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ack_failure BEFORE UPDATE ON payment_attempts WHEN NEW.state='REGISTRADO' BEGIN SELECT RAISE(ABORT,'ack failed'); END")
        val result = runCatching { if (refund) refundDao.markSuccess(key, token) else saleDao.markSynced(saleId, token) }
        if (rollback) {
            assertThat(result.isFailure).isTrue()
            assertThat(db.paymentAttemptDao().getById(key)).isEqualTo(source)
            if (refund) assertThat(refundDao.existingRow("v1", key)).isEqualTo(returned)
            else assertThat(saleDao.findByReference("CASH-$key", "v1")).isEqualTo(sale)
        } else {
            assertThat(result.getOrThrow()).isEqualTo(1)
            val saved = db.paymentAttemptDao().getById(key)!!
            assertThat(saved.state).isEqualTo("REGISTRADO")
            assertThat(saved.copy(state = source.state, stateVersion = source.stateVersion, updatedAt = source.updatedAt)).isEqualTo(source)
            if (refund) assertThat(refundDao.existingRow("v1", key)?.syncStatus).isEqualTo("SUCCESS")
            else assertThat(saleDao.findByReference("CASH-$key", "v1")?.syncStatus).isEqualTo("SUCCESS")
            assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isTrue()
        }
    }

    @Test fun `matriz SALE fases sucesor y diagnostico conservan predicado y legado resuelto`() = runTest { comprobarMatrizDeConfirmacion(false) }
    @Test fun `matriz REFUND fases sucesor y diagnostico conservan predicado y legado resuelto`() = runTest { comprobarMatrizDeConfirmacion(true) }
    @Test fun `confirmacion SALE ENTREGADA incompatible retiene obligacion original`() = runTest { comprobarConfirmacionIncompatible(false, "amount", delivered = true) }
    @Test fun `confirmacion REFUND ENTREGADA incompatible retiene obligacion original`() = runTest { comprobarConfirmacionIncompatible(true, "amount", delivered = true) }

    private suspend fun comprobarMatrizDeConfirmacion(refund: Boolean) {
        for (phase in listOf("AUTORIZADO", "ENTREGADA_A_COLA"))
            for (status in listOf("ABSENT", "PENDING", "SYNCING", "SUCCESS"))
                for (knownConflict in listOf(false, true)) {
                    // Cada celda tiene su Room real aislada; no maquilla la cerca que se prueba.
                    cleanup()
                    setup()
                    val a = open()
                    val key = "matrix-$refund-$phase-$status-$knownConflict"
                    val context = if (refund) "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":null,\"tipRefundCents\":null}"
                        else "{\"staffId\":\"staff-a\"}"
                    val source = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
                        attemptId = key, venueId = "v1", processor = "BLUMON", kind = if (refund) "REFUND" else "SALE",
                        state = phase, amountCents = 1000, tipCents = 0, recordingRoute = if (refund) "REFUND" else "FAST",
                        paymentContextJson = context, hostApproved = true, createdAt = 1, updatedAt = 1,
                        lastError = "motivo_financiero_original" + if (knownConflict) " | handoff_contrato_contradictorio:amount" else "")
                    db.paymentAttemptDao().insert(source)
                    var saleId = 0L
                    if (status != "ABSENT") {
                        if (refund) db.pendingRefundDao().insertRow(refundRow(key).copy(staffId = "staff-a", syncStatus = status,
                            amount = if (knownConflict) "11.00" else "10.00", claimToken = if (status == "SYNCING") "token" else null))
                        else saleId = db.pendingPaymentDao().insertRow(cashEntity(key).copy(syncStatus = status,
                            amount = if (knownConflict) "11.00" else "10.00", claimToken = if (status == "SYNCING") "token" else null))
                    }
                    db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
                    val attemptBlocks = phase == "AUTORIZADO" || status == "ABSENT" || knownConflict
                    val queueBlocks = status == "PENDING" || status == "SYNCING"
                    val page = shifts.obligacionesDelCierreLocal("v1", a.localSessionId)
                    assertThat(page.total).isEqualTo((if (attemptBlocks) 1 else 0) + (if (queueBlocks) 1 else 0))
                    assertThat(page.rows.size).isEqualTo(page.total)
                    assertThat(db.localShiftSessionDao().perteneceAlCierre("v1", a.localSessionId, "ATTEMPT", key)).isEqualTo(if (attemptBlocks) 1 else 0)
                    val queueOwner = if (refund) "REFUND" else "PAYMENT"
                    val queueId = if (refund) key else saleId.toString()
                    assertThat(db.localShiftSessionDao().perteneceAlCierre("v1", a.localSessionId, queueOwner, queueId)).isEqualTo(if (queueBlocks) 1 else 0)
                    assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isEqualTo(!attemptBlocks && !queueBlocks)
                    if (phase == "ENTREGADA_A_COLA" && status == "SUCCESS" && !knownConflict) {
                        val fast = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder>()
                        val order = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder>()
                        val recovery = com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerApprovalRecovery(db.paymentAttemptDao(), fast, order)
                        assertThat(recovery.recover("v1", System.currentTimeMillis())).isEqualTo(0)
                        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
                        coVerify(exactly = 0) { order.recordPayment(any(), any(), any(), any()) }
                        ledger.markAuthorized(key, null, null, null)
                        ledger.markRecordFailed(key, "late callback")
                        assertThat(ledger.markHostResponded(key, true, "late-op", "late-ref", "late-auth")).isFalse()
                        assertThat(db.paymentAttemptDao().getById(key)).isEqualTo(source)
                    }
                }
    }

    @Test fun `financiero ACK AUTORIZADO respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("ACK", "AUTORIZADO") }
    @Test fun `financiero ACK ENTREGADA_A_COLA respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("ACK", "ENTREGADA_A_COLA") }
    @Test fun `financiero ACK REGISTRO_FALLIDO respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("ACK", "REGISTRO_FALLIDO") }
    @Test fun `financiero DUPLICATE AUTORIZADO respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("DUPLICATE", "AUTORIZADO") }
    @Test fun `financiero DUPLICATE ENTREGADA_A_COLA respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("DUPLICATE", "ENTREGADA_A_COLA") }
    @Test fun `financiero DUPLICATE REGISTRO_FALLIDO respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("DUPLICATE", "REGISTRO_FALLIDO") }
    @Test fun `financiero CAS AUTORIZADO respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("CAS", "AUTORIZADO") }
    @Test fun `financiero CAS ENTREGADA_A_COLA respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("CAS", "ENTREGADA_A_COLA") }
    @Test fun `financiero CAS REGISTRO_FALLIDO respeta writer exacto y conserva obligacion`() = runTest { comprobarAutoridadFinanciera("CAS", "REGISTRO_FALLIDO") }

    private suspend fun comprobarAutoridadFinanciera(writer: String, phase: String) {
        val variants = listOf("pending", "collision", "second_no_winner", "recorded_amount", "recorded_tip", "recorded_null_amount",
            "recorded_veto", "recorded_host_rejected", "recorded", "second_winner")
        for (processor in listOf("ANGELPAY", "BLUMON")) for (variant in variants) {
            cleanup()
            setup()
            val a = open()
            val key = "financial-$processor-$writer-$phase-$variant"
            assertThat(ledger.openAttemptForShift(shifts, key, "v1", processor, 1000, 0, "FAST", "{\"staffId\":\"staff-a\"}")).isTrue()
            ledger.markHostResponded(key, true, "op", "ref-$key", "auth")
            ledger.markAuthorized(key, null, null, "CHIP")
            db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET state=? WHERE attempt_id=?", arrayOf(phase, key))
            if (variant == "recorded_host_rejected") db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET host_approved=0 WHERE attempt_id=?", arrayOf(key))
            val token = "financial-token"
            val queue = cashEntity(key, "MANAGED", a.localSessionId).copy(paymentProcessor = processor, entryMode = "CHIP",
                authorizationNumber = "auth", referenceNumber = "ref-$key", syncStatus = if (writer == "ACK") "SYNCING" else "SUCCESS",
                claimToken = if (writer == "ACK") token else null, claimedAt = if (writer == "ACK") 1 else null)
            val id = db.pendingPaymentDao().insertRow(queue)
            val outcome = when {
                variant.startsWith("recorded") -> com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.RECORDED
                variant.startsWith("second") -> com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.SECOND_CAPTURE_EVIDENCE
                variant == "collision" -> com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.REFERENCE_COLLISION_EVIDENCE
                else -> com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.PENDING_EVIDENCE
            }
            val applied = variant == "recorded" || variant == "second_winner"
            val verdict = com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento("v1", key, null, outcome, "payment-$key", "terminal",
                if (variant == "recorded_null_amount") null else if (variant == "recorded_amount") 1100L else 1000L,
                if (variant == "recorded_tip") 100L else 0L,
                if (variant == "second_winner") "winner-$key" else if (variant.startsWith("recorded")) "payment-$key" else null,
                com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento.Fuente.REST,
                if (variant == "recorded_veto") com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE else null)
            val decision = db.paymentAttemptDao().aplicarVeredictoDelServidor(verdict, System.currentTimeMillis())
            val expectedDecision = if (applied) com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.APLICADO
                else com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR
            com.google.common.truth.Truth.assertWithMessage("$processor/$writer/$phase/$variant decision").that(decision.decision).isEqualTo(expectedDecision)
            val source = db.paymentAttemptDao().getById(key)!!
            val beforeCount = shifts.obligacionesDelCierreLocal("v1", a.localSessionId).total
            when (writer) {
                "ACK" -> assertThat(db.pendingPaymentDao().markSynced(id, token)).isEqualTo(1)
                "DUPLICATE" -> assertThat(db.pendingPaymentDao().insert(queue)).isEqualTo(-1L)
                else -> {
                    if (phase == "REGISTRO_FALLIDO") ledger.markDeliveredToQueue(key)
                    else db.paymentAttemptDao().casTransition(key, listOf(phase), "ENTREGADA_A_COLA", System.currentTimeMillis(), "test-process")
                }
            }
            com.google.common.truth.Truth.assertWithMessage("$processor/$writer/$phase/$variant source").that(db.paymentAttemptDao().getById(key)).isEqualTo(source)
            assertThat(db.pendingPaymentDao().findByReference("ref-$key", "v1")?.syncStatus).isEqualTo("SUCCESS")
            val expectedBefore = (if (applied) 0 else 1) + if (writer == "ACK") 1 else 0
            com.google.common.truth.Truth.assertWithMessage("$processor/$writer/$phase/$variant before ACK count").that(beforeCount).isEqualTo(expectedBefore)
            val page = shifts.obligacionesDelCierreLocal("v1", a.localSessionId)
            com.google.common.truth.Truth.assertWithMessage("$processor/$writer/$phase/$variant after count").that(page.total).isEqualTo(if (applied) 0 else 1)
            assertThat(page.rows.map { it.owner }).containsExactlyElementsIn(if (applied) emptyList<String>() else listOf("ATTEMPT"))
            assertThat(db.localShiftSessionDao().perteneceAlCierre("v1", a.localSessionId, "ATTEMPT", key)).isEqualTo(if (applied) 0 else 1)
            db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET local_status='CLOSED',open_slot=NULL WHERE local_session_id=?", arrayOf(a.localSessionId))
            assertThat(shifts.puedeEnviarCierreLocal("v1", a.localSessionId)).isEqualTo(applied)
        }
    }

    @Test fun `financiero refund y legacy mantienen autoridad previa fuera del alcance del veredicto`() = runTest {
        for (processor in listOf("ANGELPAY", "BLUMON")) for (refund in listOf(false, true)) {
            cleanup()
            setup()
            val a = open()
            val key = "financial-outside-$processor-$refund"
            val json = if (refund) "{\"staffId\":\"staff-a\",\"originalPaymentId\":\"old-payment\",\"originalOrderId\":null,\"tipRefundCents\":null}" else "{\"staffId\":\"staff-a\"}"
            assertThat(ledger.openAttemptForShift(shifts, key, "v1", processor, 1000, 0, if (refund) "REFUND" else "FAST", json, if (refund) "REFUND" else "SALE")).isTrue()
            ledger.markAuthorized(key, null, null, null)
            if (!refund) db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET legacy_shadow=1 WHERE attempt_id=?", arrayOf(key))
            val source = db.paymentAttemptDao().getById(key)!!
            val verdict = com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento("v1", key, null,
                com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.PENDING_EVIDENCE, "payment-$key", "terminal", 1000, 0, null,
                com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento.Fuente.REST)
            assertThat(db.paymentAttemptDao().aplicarVeredictoDelServidor(verdict, System.currentTimeMillis()).decision)
                .isEqualTo(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE)
            assertThat(db.paymentAttemptDao().getById(key)).isEqualTo(source)
            if (refund) {
                db.pendingRefundDao().insertRow(refundRow(key).copy(staffId = "staff-a", processor = processor,
                    shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId, syncStatus = "SYNCING", claimToken = "token", claimedAt = 1))
                assertThat(db.pendingRefundDao().markSuccess(key, "token")).isEqualTo(1)
            } else ledger.markRecorded(key)
            assertThat(db.paymentAttemptDao().getById(key)?.state).isEqualTo("REGISTRADO")
        }
    }

}
