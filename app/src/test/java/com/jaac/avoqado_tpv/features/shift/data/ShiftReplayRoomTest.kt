package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.repository.toQueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import com.jaac.avoqado_tpv.features.shift.data.dto.*
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftReplayRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val sales = mockk<VentaGuardadaApi>()
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private var actor = "original"
    private var mode = true
    private val venue = "v1"
    private val key = "c000000000000000000000003"
    private val remote = "c000000000000000000000001"
    private val drawer = "c000000000000000000000002"

    @Before fun setup() {
        every { storage.getVenueId() } returns venue
        every { storage.getStaffId() } answers { actor }
        every { storage.getToken() } returns "token"
        every { storage.getKioskVenueId() } returns null
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } answers { mode }
        every { storage.getString("permissions_cache", null) } answers {
            "$venue|$actor\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund"
        }
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task6-replay").allowMainThreadQueries().build()
        repo = ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk<RefundQueueRepository>(relaxed = true), appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
        coEvery { api.getKnownShiftState(any(), any()) } returns Response.error(404, "missing".toResponseBody())
        coEvery { sales.crearOrden(any(), any()) } returns json("""{"success":true,"order":{"id":"order","orderNumber":"V1","paymentStatus":"PENDING","items":[{"externalId":"$key:0","total":10}]}}""")
        coEvery { sales.pagar(any(), any(), any()) } returns json("""{"success":true,"payment":{"paymentId":"payment","orderId":"order","status":"COMPLETED","orderTotalCents":1000}}""")
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("task6-replay") }
    private fun json(value: String): Response<JsonObject> = Response.success(JsonParser.parseString(value).asJsonObject)
    private fun remote(id: String = remote, local: String? = null, disposition: String = "OWN"): ShiftDto = Gson().fromJson("""{
        "id":"$id","venueId":"v1","staffId":"original","startTime":"2026-10-28T10:00:00Z","status":"OPEN",
        "startingCash":"50.00","totalSales":"0","totalTips":"0","totalOrders":0,"totalCashPayments":"0",
        "totalCardPayments":"0","totalVoucherPayments":"0","totalOtherPayments":"0","totalProductsSold":0,
        "cashDrawerSessionId":"$drawer","localId":"$local","openingDisposition":"$disposition","fondoAplicado":"50.00"} """, ShiftDto::class.java)
    private suspend fun local() = (repo.guardarAperturaLocal(venue, actor, BigDecimal("50.00")) as com.jaac.avoqado_tpv.core.domain.models.Result.Success).data
    private suspend fun sale(saleKey: String = key, claimed: Boolean = true): com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity {
        val key = saleKey
        val original = """{ "externalId":"$key", "staffId":"original", "items":[{"externalId":"$key:0","quantity":1,"unitPrice":1000}], "total":1000 }"""
        VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = repo).guardar(VentaLocal(
            key, venue, actor, null, 123L, 1000, original,
            listOf(RenglonDeTicket("$key:0", "Producto", 1, 1000, 1000, emptyList(), null))),
            "BLUMON", "", "SERIAL", reclamada = claimed).getOrThrow()
        return db.pendingPaymentDao().findByReference(com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo(key), venue)!!
    }
    private fun subir() = SubirVentaGuardadaUseCase(sales, db.pendingPaymentDao(), context, com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate(db, storage))

    @Test fun `current null reconoce CLOSED de la identidad exacta sin inventar CLOSE ni perder obligaciones`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, remote()).getOrThrow()
        val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        val payment = sale()
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
        coEvery { api.getKnownShiftState(venue, remote) } returns Response.success(KnownShiftState(remote, venue, "CLOSED"))
        var notices = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shiftChanges.collect { notices++ } }

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.status.name).isEqualTo("CLOSED")
        val observed = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(observed.localStatus).isEqualTo("CLOSED")
        assertThat(observed.closeStatus).isEqualTo("REVIEW")
        assertThat(observed.closeOperationId).isNull()
        assertThat(observed.closeRequestJson).isNull()
        assertThat(observed.countedCash).isNull()
        assertThat(observed.openingEvidenceJson).isEqualTo(original.openingEvidenceJson)
        assertThat(db.pendingPaymentDao().findByReference(payment.referenceNumber, venue)).isEqualTo(payment)
        runCurrent()
        assertThat(notices).isEqualTo(1)
        recreate()
        assertThat(repo.getCachedOpenShift(venue)).isNull()
        var admitted = false
        assertThat(runCatching { repo.guardarNuevaObligacionDeCaja(venue) { admitted = true } }.isFailure).isTrue()
        assertThat(admitted).isFalse()
        repo.getCurrentShift(venue)
        coVerify(exactly = 1) { api.getKnownShiftState(venue, remote) }
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `otra current no borra A pero su identidad CLOSING si cierra admision de A`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(remote(id = key)))
        coEvery { api.getKnownShiftState(venue, remote) } returns Response.success(KnownShiftState(remote, venue, "CLOSING"))

        repo.getCurrentShift(venue).getOrThrow()

        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.localStatus).isEqualTo("CLOSED")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeOperationId).isNull()
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `ausencia fallo OPEN o identidad ajena nunca acreditan cierre`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, remote()).getOrThrow()
        val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
        val responses = listOf(
            Response.success<KnownShiftState>(null),
            Response.success(KnownShiftState(remote, venue, "OPEN")),
            Response.success(KnownShiftState(key, venue, "CLOSED")),
            Response.success(KnownShiftState(remote, "other-venue", "CLOSED")),
            Response.success(KnownShiftState(remote, venue, "UNKNOWN")),
            Response.error<KnownShiftState>(404, "missing".toResponseBody()),
            Response.error<KnownShiftState>(503, "unavailable".toResponseBody()),
        )
        for (response in responses) {
            coEvery { api.getKnownShiftState(venue, remote) } returns response
            assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("OPEN")
            assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)).isEqualTo(original)
        }
        coEvery { api.getKnownShiftState(venue, remote) } throws IOException("no response")
        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("OPEN")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)).isEqualTo(original)
    }

    @Test fun `cancelar lectura exacta se propaga sin escribir un cierre`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, remote()).getOrThrow()
        val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
        coEvery { api.getKnownShiftState(venue, remote) } throws CancellationException("screen left")

        val failure = runCatching { repo.getCurrentShift(venue) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)).isEqualTo(original)
    }

    @Test fun `estado exacto tardio no cambia conteo comprometido ni cierra B`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
        var b: com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity? = null
        coEvery { api.getKnownShiftState(venue, remote) } coAnswers {
            repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
            b = local()
            Response.success(KnownShiftState(remote, venue, "CLOSED"))
        }

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(b).isNotNull()
        assertThat(visible.localShiftSessionId).isEqualTo(b!!.localSessionId)
        assertThat(visible.status.name).isEqualTo("OPEN")
        val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(original.closeStatus).isEqualTo("PENDING")
        assertThat(original.countedCash).isEqualTo("61.25")
        assertThat(db.localShiftSessionDao().leer(venue, b!!.localSessionId)!!.localStatus).isEqualTo("OPEN")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `PIN token y venue cambiados durante lectura exacta no adoptan autoridad nueva`() = runTest {
        for (change in listOf("PIN", "TOKEN", "VENUE")) {
            val a = repo.registrarCajaRemotaConocida(venue, remote()).getOrThrow()
            val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
            coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
            coEvery { api.getKnownShiftState(venue, remote) } coAnswers {
                when (change) {
                    "PIN" -> actor = "new-actor"
                    "TOKEN" -> every { storage.getToken() } returns "new-token"
                    else -> every { storage.getVenueId() } returns "other-venue"
                }
                Response.success(KnownShiftState(remote, venue, "CLOSED"))
            }
            repo.getCurrentShift(venue)
            coVerify(exactly = 1) { api.getKnownShiftState(venue, remote) }
            assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)).isEqualTo(original)
            clearMocks(api, answers = false, recordedCalls = true)
            db.close(); context.deleteDatabase("task6-replay"); actor = "original"; setup()
        }
    }

    @Test fun `aviso de apertura sincronizada permite refrescar la caja y no se repite en pasada vacia`() = runTest {
        val a = local()
        coEvery { api.openShift(venue, any()) } returns Response.success(ShiftResponse(true, remote(local = a.localSessionId)))
        var notices = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shiftChanges.collect { notices++ } }

        assertThat(repo.sincronizarAperturasLocales(venue)).isEqualTo(1)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.openingStatus).isEqualTo("CONFIRMED")
        runCurrent()
        assertThat(notices).isEqualTo(1)
        assertThat(repo.sincronizarAperturasLocales(venue)).isEqualTo(0)
        runCurrent()
        assertThat(notices).isEqualTo(1)
    }

    @Test fun `aviso de cierre sincronizado permite quitar el pendiente sin tocar el conteo`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        serveCloses(mutableListOf())
        var notices = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shiftChanges.collect { notices++ } }

        assertThat(repo.sincronizarCierresLocales(venue)).isEqualTo(1)
        val saved = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(saved.closeStatus).isEqualTo("CONFIRMED")
        assertThat(saved.countedCash).isEqualTo("61.25")
        runCurrent()
        assertThat(notices).isEqualTo(1)
        assertThat(repo.sincronizarCierresLocales(venue)).isEqualTo(0)
        runCurrent()
        assertThat(notices).isEqualTo(1)
    }

    @Test fun `aviso de cierre recuperado por consulta no vuelve a enviar el cierre`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        val localClose = repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        coEvery { api.closeShift(venue, remote, any()) } throws IOException("respuesta perdida")
        repo.sincronizarCierresLocales(venue)
        coEvery { api.getShiftOperation(venue, localClose.closeOperationId!!) } returns Response.success(
            ShiftOperationResponse(true, closed(localClose), shift = ShiftOperationShiftDto(id = remote, status = "CLOSED")))
        var notices = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shiftChanges.collect { notices++ } }

        assertThat(repo.sincronizarCierresLocales(venue)).isEqualTo(1)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
        runCurrent()
        assertThat(notices).isEqualTo(1)
        coVerify(exactly = 1) { api.closeShift(venue, remote, any()) }
    }

    @Test fun `aviso de apertura en revision permite mostrar el rechazo persistido`() = runTest {
        val a = local()
        coEvery { api.openShift(venue, any()) } returns Response.error(422, "invalid".toResponseBody())
        var notices = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shiftChanges.collect { notices++ } }

        assertThat(repo.sincronizarAperturasLocales(venue)).isEqualTo(0)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.openingStatus).isEqualTo("REVIEW")
        runCurrent()
        assertThat(notices).isEqualTo(1)
    }

    @Test fun `apertura publica queda durable antes del POST y conserva respuesta perdida`() = runTest {
        var countAtPost = -1
        coEvery { api.openShift(venue, any()) } coAnswers {
            countAtPost = db.localShiftSessionDao().contar(venue)
            throw IOException("respuesta perdida")
        }
        repo.openShift(venue, actor, 50.0)
        repo.sincronizarAperturasLocales(venue)
        assertThat(countAtPost).isEqualTo(1)
        val saved = db.localShiftSessionDao().abierta(venue)!!
        assertThat(saved.openingStatus).isEqualTo("PENDING")
        assertThat(saved.openingOperationId).isEqualTo(saved.localSessionId)
        assertThat(saved.startingCash).isEqualTo("50.00")
    }

    @Test fun `GET remoto B no revive A que ya se cerro localmente`() = runTest {
        val a = local()
        repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25"))
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(remote(id = "other")))
        val result = repo.getCurrentShift(venue) as com.jaac.avoqado_tpv.core.domain.models.Result.Success
        assertThat(result.data?.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(result.data?.status?.name).isEqualTo("CLOSED")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.countedCash).isEqualTo("61.25")
    }

    @Test fun `MANAGED sin mapping no crea orden ni gasta retry`() = runTest {
        local()
        val row = sale()
        assertThat(subir()(row.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        coVerify(exactly = 0) { sales.crearOrden(any(), any()) }
        coVerify(exactly = 0) { sales.pagar(any(), any(), any()) }
        assertThat(db.pendingPaymentDao().findByReference(row.referenceNumber, venue)!!.retryCount).isEqualTo(0)
    }

    @Test fun `venta de B con refs capturadas null envia su mapping sin reetiquetar evidencia`() = runTest {
        val b = local()
        val row = sale()
        repo.registrarResultadoAperturaLocal(venue, b.localSessionId, remote(local = b.localSessionId))
        actor = "quien-recupera"
        val result = subir()(row.toQueuedPayment())
        assertThat(result).isEqualTo(SyncOutcome.Synced)
        coVerify { sales.pagar(venue, "order", match { it.shiftIdDeLaVenta == remote && it.staffId == "original" && it.idempotencyKey == key }) }
        val saved = db.pendingPaymentDao().findByReference(row.referenceNumber, venue)!!
        assertThat(saved.localShiftSessionId).isEqualTo(b.localSessionId)
        assertThat(saved.cashDrawerSessionId).isNull()
        assertThat(saved.shiftId).isNull()
        assertThat(saved.orderRequestJson).isEqualTo(row.orderRequestJson)
        assertThat(saved.amount).isEqualTo(row.amount)
        assertThat(saved.claimToken).isEqualTo(row.claimToken)
        assertThat(db.pendingPaymentDao().markSynced(saved.id, row.claimToken!!)).isEqualTo(1)
        assertThat(repo.obligacionesDelCierreLocal(venue, b.localSessionId).total).isEqualTo(0)
    }
    private fun recreate() {
        db.close()
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task6-replay").allowMainThreadQueries().build()
        repo = ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk<RefundQueueRepository>(relaxed = true), appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }
    private fun closed(row: com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity) = ShiftOperationDto(
        operationId = row.closeOperationId, kind = "CLOSE", state = "APPLIED", closedAtLocal = row.closedAtLocal,
        cashDrawerSessionId = row.cashDrawerSessionId, submittedCountedCash = row.countedCash, countApplied = true, pairStatus = "COMPLETE")
    private fun serveCloses(events: MutableList<String>) {
        coEvery { api.closeShift(venue, any(), any()) } coAnswers {
            val request = thirdArg<CloseShiftRequest>()
            val row = db.localShiftSessionDao().porReproducir(venue).first { it.closeOperationId == request.operationId }
            events += "close:${row.localSessionId}"
            Response.success(ShiftResponse(true, remote(id = request.shiftId).copy(status = "CLOSED"), operation = closed(row)))
        }
    }

    @Test fun `A ventas cierre y B mantienen secuencia al recrear sin mezclar el conteo ni el autor`() = runTest {
        val a = local()
        val saleA = sale()
        repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        val b = local()
        val saleB = sale("c000000000000000000000004")
        repo.guardarCierreLocal(venue, b.localSessionId, CashReconciliationAction.SKIPPED).getOrThrow()
        val events = mutableListOf<String>()
        coEvery { api.openShift(venue, any()) } coAnswers {
            val request = secondArg<OpenShiftRequest>()
            events += "open:${request.localId}"
            Response.success(ShiftResponse(true, remote(id = if (request.localId == a.localSessionId) remote else "c000000000000000000000005", local = request.localId)))
        }
        serveCloses(events)
        recreate()
        actor = "recuperador"
        repo.sincronizarAperturasLocales(venue)
        assertThat(events).containsExactly("open:${a.localSessionId}")
        repo.sincronizarCierresLocales(venue)
        assertThat(events).hasSize(1)
        assertThat(subir()(saleA.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        db.pendingPaymentDao().markSynced(saleA.id, saleA.claimToken!!)
        assertThat(repo.obligacionesDelCierreLocal(venue, a.localSessionId).total).isEqualTo(0)
        assertThat(repo.obligacionesDelCierreLocal(venue, b.localSessionId).total).isEqualTo(1)
        repo.sincronizarCierresLocales(venue)
        repo.sincronizarAperturasLocales(venue)
        assertThat(events).containsExactly("open:${a.localSessionId}", "close:${a.localSessionId}", "open:${b.localSessionId}").inOrder()
        coEvery { sales.crearOrden(venue, any()) } returns json("""{"success":true,"order":{"id":"order-b","orderNumber":"V2","paymentStatus":"PENDING","items":[{"externalId":"c000000000000000000000004:0","total":10}]}}""")
        coEvery { sales.pagar(venue, "order-b", any()) } returns json("""{"success":true,"payment":{"paymentId":"payment-b","orderId":"order-b","status":"COMPLETED","orderTotalCents":1000}}""")
        assertThat(subir()(saleB.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        db.pendingPaymentDao().markSynced(saleB.id, saleB.claimToken!!)
        repo.sincronizarCierresLocales(venue)
        assertThat(events.last()).isEqualTo("close:${b.localSessionId}")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.countedCash).isEqualTo("61.25")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closedByStaffId).isEqualTo("original")
    }

    @Test fun `dos trabajadores solo reclaman una apertura y el resultado ajeno no cambia la pareja`() = runTest {
        val a = local()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var posts = 0
        coEvery { api.openShift(venue, any()) } coAnswers {
            posts++; entered.complete(Unit); release.await()
            Response.success(ShiftResponse(true, remote(local = a.localSessionId)))
        }
        val first = async(Dispatchers.Default) { repo.sincronizarAperturasLocales(venue) }
        entered.await()
        val other = ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk(relaxed = true), appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
        other.sincronizarAperturasLocales(venue)
        assertThat(posts).isEqualTo(1)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.claimToken).isNotNull()
        release.complete(Unit); first.await()
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.serverShiftId).isEqualTo(remote)
    }

    @Test fun `cierre perdido se consulta por K tras reiniciar y no cierra B ni pierde su conteo`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        val closedLocal = repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        val b = local()
        var posts = 0
        coEvery { api.closeShift(venue, remote, any()) } coAnswers { posts++; throw IOException("respuesta perdida después del commit") }
        repo.sincronizarCierresLocales(venue)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeWireRequestJson).isNotNull()
        recreate()
        coEvery { api.getShiftOperation(venue, closedLocal.closeOperationId!!) } returns Response.success(ShiftOperationResponse(true, closed(closedLocal), shift = ShiftOperationShiftDto(id = remote, status = "CLOSED")))
        repo.sincronizarCierresLocales(venue)
        assertThat(posts).isEqualTo(1)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.countedCash).isEqualTo("61.25")
        assertThat(db.localShiftSessionDao().leer(venue, b.localSessionId)!!.openingStatus).isEqualTo("PENDING")
    }

    @Test fun `ADOPTED conserva los dos fondos y el cierre en REVIEW sin enviarlo a caja ajena`() = runTest {
        val a = local()
        repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        coEvery { api.openShift(venue, any()) } returns Response.success(ShiftResponse(true, remote(disposition = "ADOPTED").copy(fondoAplicado = "90.00")))
        repo.sincronizarAperturasLocales(venue)
        repo.sincronizarCierresLocales(venue)
        val saved = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(saved.startingCash).isEqualTo("50.00")
        assertThat(saved.remoteStartingCash).isEqualTo("90.00")
        assertThat(saved.countedCash).isEqualTo("61.25")
        assertThat(saved.closeStatus).isEqualTo("REVIEW")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `ON OFF efectivo NO_SHIFT ON cierra A solo despues de su ACK sin esperar B`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        mode = false
        val noShift = sale()
        mode = true
        repo.guardarCierreLocal(venue, a.localSessionId).getOrThrow()
        val b = local()
        val bSale = sale("c000000000000000000000004")
        assertThat(noShift.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(repo.puedeEnviarCierreLocal(venue, a.localSessionId)).isFalse()
        assertThat(subir()(noShift.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        db.pendingPaymentDao().markSynced(noShift.id, noShift.claimToken!!)
        assertThat(repo.puedeEnviarCierreLocal(venue, a.localSessionId)).isTrue()
        assertThat(bSale.localShiftSessionId).isEqualTo(b.localSessionId)
        assertThat(db.localShiftSessionDao().leer(venue, b.localSessionId)!!.openingStatus).isEqualTo("PENDING")
    }

    @Test fun `copia wire conserva original y un claim robado no puede pagar ni cambiar mapping`() = runTest {
        val a = local(); val row = sale()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        val gate = com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate(db, storage)
        val mapped = gate.sale(row, row.claimToken!!)
        assertThat(JsonParser.parseString(mapped.requestJson).asJsonObject["shiftIdDeLaVenta"].asString).isEqualTo(remote)
        assertThat(db.pendingPaymentDao().findByReference(row.referenceNumber, venue)!!.orderRequestJson).isEqualTo(row.orderRequestJson)
        db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET claim_token = 'other-worker' WHERE id = ?", arrayOf(row.id))
        assertThat(subir()(row.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        coVerify(exactly = 0) { sales.pagar(any(), any(), any()) }
        assertThat(db.pendingPaymentDao().findByReference(row.referenceNumber, venue)!!.cashDrawerSessionId).isNull()
    }

    @Test fun `proyeccion tras recreacion y cache Gson vieja distinguen totales desconocidos`() = runTest {
        val a = local(); recreate()
        val projection = (repo.getCurrentShift(venue) as com.jaac.avoqado_tpv.core.domain.models.Result.Success).data!!
        assertThat(projection.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(projection.financialTotalsKnown).isFalse()
        assertThat(projection.reconciliation).isNull()
        assertThat(projection.endingCash).isNull()
        val gson = Gson()
        val oldJson = gson.toJsonTree(projection).asJsonObject.apply { remove("financialTotalsKnown") }
        assertThat(gson.fromJson(oldJson, com.jaac.avoqado_tpv.features.shift.domain.Shift::class.java).financialTotalsKnown).isNull()
    }

    @Test fun `fallo de segunda escritura revierte mapping y conserva claim para recuperar misma K`() = runTest {
        val a = local()
        coEvery { api.openShift(venue, any()) } returns Response.success(ShiftResponse(true, remote(local = a.localSessionId)))
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_release BEFORE UPDATE OF claim_token ON local_shift_sessions
            WHEN OLD.claim_token IS NOT NULL AND NEW.claim_token IS NULL BEGIN SELECT RAISE(ABORT, 'second-write'); END""")
        runCatching { repo.sincronizarAperturasLocales(venue) }
        val saved = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(saved.openingStatus).isEqualTo("SYNCING")
        assertThat(saved.serverShiftId).isNull()
        assertThat(saved.claimToken).isNotNull()
        assertThat(saved.openingRequestJson).isEqualTo(a.openingRequestJson)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_release")
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET lease_until = 0 WHERE local_session_id = ?", arrayOf(a.localSessionId))
        recreate()
        repo.sincronizarAperturasLocales(venue)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.serverShiftId).isEqualTo(remote)
        coVerify(exactly = 2) { api.openShift(venue, match { it.localId == a.localSessionId && it.operationId == a.openingOperationId }) }
    }

    @Test fun `apertura HTTP transitorio 403 y permanente conserva intencion con estado honesto`() = runTest {
        for ((code, status) in listOf(503 to "PENDING", 403 to "PENDING", 422 to "REVIEW")) {
            val a = local()
            coEvery { api.openShift(venue, any()) } returns Response.error(code, okhttp3.ResponseBody.create(null, "rechazo"))
            repo.sincronizarAperturasLocales(venue)
            val saved = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
            assertThat(saved.openingStatus).isEqualTo(status)
            assertThat(saved.openingRequestJson).isEqualTo(a.openingRequestJson)
            assertThat(saved.lastError).contains(code.toString())
            db.close(); context.deleteDatabase("task6-replay"); setup()
        }
    }

    @Test fun `cierre HTTP transitorio 403 y permanente no firma ni altera conteo`() = runTest {
        for ((code, status) in listOf(503 to "PENDING", 403 to "PENDING", 422 to "REVIEW")) {
            val a = local()
            repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
            val pending = repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
            coEvery { api.closeShift(venue, any(), any()) } returns Response.error(code, okhttp3.ResponseBody.create(null, "rechazo"))
            repo.sincronizarCierresLocales(venue)
            val saved = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
            assertThat(saved.closeStatus).isEqualTo(status)
            assertThat(saved.closeRequestJson).isEqualTo(pending.closeRequestJson)
            assertThat(saved.countedCash).isEqualTo("61.25")
            assertThat(saved.closeEvidenceJson).isNull()
            db.close(); context.deleteDatabase("task6-replay"); setup()
        }
    }

    @Test fun `cripto B espera mapping y despues de restart solo consulta su K incierta`() = runTest {
        val a = local()
        repo.guardarCierreLocal(venue, a.localSessionId).getOrThrow()
        val b = local()
        val request = com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest(1000, null, actor, deviceSerialNumber = "SERIAL", idempotencyKey = key)
        val denied = runCatching { repo.iniciarCriptoDurable(venue, request) }
        assertThat(denied.isFailure).isTrue()
        assertThat(db.localCryptoOperationDao().leer(venue, key)).isNull()
        coVerify(exactly = 0) { api.initiateDurableCryptoPayment(any(), any()) }
        repo.registrarResultadoAperturaLocal(venue, b.localSessionId, remote(local = b.localSessionId)).getOrThrow()
        coEvery { api.initiateDurableCryptoPayment(venue, any()) } throws IOException("respuesta perdida")
        runCatching { repo.iniciarCriptoDurable(venue, request) }
        val original = db.localCryptoOperationDao().leer(venue, key)!!
        assertThat(original.localShiftSessionId).isEqualTo(b.localSessionId)
        recreate()
        coEvery { api.getCryptoOperation(venue, key) } throws IOException("consulta aun sin red")
        runCatching { repo.iniciarCriptoDurable(venue, request) }
        coVerify(exactly = 1) { api.initiateDurableCryptoPayment(venue, any()) }
        coVerify(exactly = 1) { api.getCryptoOperation(venue, key) }
        assertThat(db.localCryptoOperationDao().leer(venue, key)!!.requestJson).isEqualTo(original.requestJson)
        assertThat(db.localCryptoOperationDao().leer(venue, key)!!.financialRecorded).isNull()
    }

    @Test fun `R6 apertura publica entrega resultado antes de esperar HTTP retenido`() = runTest {
        val reachedHttp = CompletableDeferred<Unit>()
        val releaseHttp = CompletableDeferred<Unit>()
        coEvery { api.openShift(venue, any()) } coAnswers {
            reachedHttp.complete(Unit); releaseHttp.await(); throw IOException("red retenida")
        }
        val result = async(Dispatchers.Default) { repo.openShift(venue, actor, 50.0) }
        try {
            val first = kotlinx.coroutines.selects.select<String> {
                result.onAwait { "resultado" }
                reachedHttp.onAwait { "HTTP" }
            }
            assertThat(first).isEqualTo("resultado")
            assertThat(db.localShiftSessionDao().abierta(venue)!!.startingCash).isEqualTo("50.00")
        } finally { releaseHttp.complete(Unit); result.await() }
    }

    @Test fun `R6 cierre publico de A conocida entrega conteo antes de HTTP retenido sin GET`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        val reachedHttp = CompletableDeferred<Unit>()
        val releaseHttp = CompletableDeferred<Unit>()
        coEvery { api.closeShift(venue, remote, any()) } coAnswers {
            reachedHttp.complete(Unit); releaseHttp.await(); throw IOException("red retenida")
        }
        val result = async(Dispatchers.Default) { repo.closeShift(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")) }
        try {
            val first = kotlinx.coroutines.selects.select<String> {
                result.onAwait { "resultado" }
                reachedHttp.onAwait { "HTTP" }
            }
            assertThat(first).isEqualTo("resultado")
            assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.countedCash).isEqualTo("61.25")
            coVerify(exactly = 0) { api.getCurrentShift(any()) }
        } finally { releaseHttp.complete(Unit); result.await() }
    }

    @Test fun `R6 cierre valida wire real sin inventar operation shiftId`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        val pending = repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        // Contrato literal de closeOperationResult: la identidad de Shift vive en data.id, no en operation.
        coEvery { api.closeShift(venue, remote, any()) } returns Response.success(ShiftResponse(true,
            remote(local = a.localSessionId).copy(status = "CLOSED"), operation = closed(pending).copy(shiftId = null, closedAtLocal = pending.closedAtLocal)))
        repo.sincronizarCierresLocales(venue)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
    }

    @Test fun `R7 cierre importa cache remota A sin esperar GET ni inventar fondo`() = runTest {
        val cached = com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
            id = remote, venueId = venue, status = "OPEN", staffId = "original-anterior", staffName = "Anterior",
            startTime = "2026-10-28T10:00:00Z", totalSales = "110.00", totalOrders = 2, durationMinutes = 3, cachedAt = 123L)
        db.cachedShiftDao().cacheShift(cached)
        val reachedHttp = CompletableDeferred<Unit>(); val releaseHttp = CompletableDeferred<Unit>()
        coEvery { api.getCurrentShift(venue) } coAnswers { reachedHttp.complete(Unit); releaseHttp.await(); throw IOException("sin red") }
        val result = async(Dispatchers.Default) { repo.closeShift(venue, remote, CashReconciliationAction.COUNTED, BigDecimal("61.25")) }
        try {
            val first = kotlinx.coroutines.selects.select<String> { result.onAwait { "resultado" }; reachedHttp.onAwait { "HTTP" } }
            assertThat(first).isEqualTo("resultado")
            assertThat(result.await()).isInstanceOf(com.jaac.avoqado_tpv.core.domain.models.Result.Success::class.java)
            val saved = db.localShiftSessionDao().ultima(venue)!!
            assertThat(saved.serverShiftId).isEqualTo(remote)
            assertThat(saved.startingCash).isNull()
            assertThat(saved.cashDrawerSessionId).isNull()
            assertThat(saved.openedByStaffId).isEqualTo("original-anterior")
            assertThat(saved.countedCash).isEqualTo("61.25")
            assertThat(repo.resumenLocalDeCaja(venue, saved.localSessionId)!!.startingCash).isNull()
        } finally { releaseHttp.complete(Unit); result.await() }
    }

    @Test fun `R8 adopcion explicita misma pareja no queda detras de su propia fuente NONE`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(disposition = "ADOPTED")).getOrThrow()
        val b = repo.adoptarCajaRemotaParaNuevosGestos(venue, a.localSessionId).getOrThrow()
        repo.guardarCierreLocal(venue, b.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("91.25")).getOrThrow()
        val events = mutableListOf<String>(); serveCloses(events)
        repo.sincronizarCierresLocales(venue)
        assertThat(db.localShiftSessionDao().leer(venue, b.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeOperationId).isNull()
    }

    @Test fun `R8 nueva REMOTE de misma pareja espera la obligacion original A hasta su ACK`() = runTest {
        val a = local(); val payment = sale()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(disposition = "ADOPTED")).getOrThrow()
        val b = repo.adoptarCajaRemotaParaNuevosGestos(venue, a.localSessionId).getOrThrow()
        repo.guardarCierreLocal(venue, b.localSessionId).getOrThrow()
        val obligation = repo.obligacionesDelCierreLocal(venue, b.localSessionId)
        assertThat(obligation.total).isEqualTo(1)
        assertThat(obligation.rows.single().ownerId).isEqualTo(payment.id.toString())
        assertThat(repo.puedeEnviarCierreLocal(venue, b.localSessionId)).isFalse()
        db.pendingPaymentDao().markSynced(payment.id, payment.claimToken!!)
        assertThat(repo.obligacionesDelCierreLocal(venue, b.localSessionId).total).isEqualTo(0)
    }

    private fun serveCashSales() {
        coEvery { sales.crearOrden(venue, any()) } coAnswers {
            val buffer = okio.Buffer(); secondArg<okhttp3.RequestBody>().writeTo(buffer)
            val id = JsonParser.parseString(buffer.readUtf8()).asJsonObject["externalId"].asString
            json("""{"success":true,"order":{"id":"$id","orderNumber":"V1","paymentStatus":"PENDING","items":[{"externalId":"$id:0","total":10}]}}""")
        }
        coEvery { sales.pagar(venue, any(), any()) } coAnswers {
            val id = secondArg<String>()
            json("""{"success":true,"payment":{"paymentId":"$id","orderId":"$id","status":"COMPLETED","orderTotalCents":1000}}""")
        }
    }

    private fun worker(): com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns emptyList()
        val gate = com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate(db, storage)
        val queue = com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        return androidx.work.testing.TestListenableWorkerBuilder<com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: android.content.Context, workerClassName: String, params: androidx.work.WorkerParameters): androidx.work.ListenableWorker =
                    com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker(appContext, params, queue, mockk(relaxed = true),
                        com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager(), refunds, mockk(relaxed = true), subir(), repo, gate, com.jaac.avoqado_tpv.features.shift.data.emptyTableReplayFixture())
            }).build()
    }

    @Test fun `once pagos esperan al onceavo antes de cerrar y la siguiente pasada conserva el avance`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        val payments = (1..11).map { sale("c" + (100 + it).toString().padStart(24, '0'), claimed = false) }
        repo.guardarCierreLocal(venue, a.localSessionId).getOrThrow()
        serveCashSales()
        val events = mutableListOf<String>(); serveCloses(events)
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } just Runs
        try {
            worker().doWork()
            assertThat(db.pendingPaymentDao().findByReference(payments.last().referenceNumber, venue)!!.syncStatus).isEqualTo("PENDING")
            assertThat(repo.obligacionesDelCierreLocal(venue, a.localSessionId).total).isEqualTo(1)
            assertThat(events).isEmpty()
            worker().doWork()
            assertThat(repo.obligacionesDelCierreLocal(venue, a.localSessionId).total).isEqualTo(0)
            assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
            assertThat(events).containsExactly("close:${a.localSessionId}")
        } finally { unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler) }
    }

    @Test fun `veintiuna sesiones continúan despues de la tanda veinte sin truncar la ultima`() = runTest {
        val sessions = (1..21).map { n ->
            val a = local()
            repo.guardarCierreLocal(venue, a.localSessionId).getOrThrow()
            repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(id = "c" + n.toString().padStart(24, '0'), local = a.localSessionId)
                .copy(cashDrawerSessionId = "c" + (1000 + n).toString().padStart(24, '0'))).getOrThrow()
            a
        }
        val events = mutableListOf<String>(); serveCloses(events)
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } just Runs
        try {
            worker().doWork()
            assertThat(events).hasSize(20)
            assertThat(db.localShiftSessionDao().leer(venue, sessions.last().localSessionId)!!.closeStatus).isEqualTo("PENDING")
            worker().doWork()
            assertThat(events).hasSize(21)
            assertThat(db.localShiftSessionDao().leer(venue, sessions.last().localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
        } finally { unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler) }
    }

    @Test fun `scheduler persiste continuacion detras de trabajo existente y reconexion despierta caja`() = runTest {
        val config = androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor())
            .setTaskExecutor(androidx.work.testing.SynchronousExecutor()).build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val manager = androidx.work.WorkManager.getInstance(context)
        try {
            val first = androidx.work.OneTimeWorkRequestBuilder<com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker>()
                .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
            manager.enqueueUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP, first).result.get()
            com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(context)
            val chain = manager.getWorkInfosForUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME).get()
            assertThat(chain).hasSize(2)
            assertThat(chain.map { it.state }).contains(androidx.work.WorkInfo.State.BLOCKED)
            manager.cancelAllWork().result.get()
            com.jaac.avoqado_tpv.core.util.ConnectionEventManager(context).emitConnectionRestored(1)
            val afterReconnect = manager.getWorkInfosForUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME).get()
            assertThat(afterReconnect.any { it.state == androidx.work.WorkInfo.State.ENQUEUED }).isTrue()
        } finally { manager.cancelAllWork().result.get() }
    }

    @Test fun `respuesta perdida del pago MANAGED conserva orden wire autor y claim nuevo sin crear otra orden`() = runTest {
        val a = local(); val row = sale()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        coEvery { sales.pagar(any(), any(), any()) } throws IOException("se perdio la respuesta despues de cobrar")
        assertThat(subir()(row.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        val original = db.pendingPaymentDao().findByReference(row.referenceNumber, venue)!!
        assertThat(original.orderId).isEqualTo("order")
        assertThat(original.orderWireRequestJson).isNotNull()
        db.pendingPaymentDao().release(row.id, row.claimToken!!, 0, "respuesta perdida")
        recreate(); actor = "recuperador"
        val queue = com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        val replay = queue.claimBatch(10).single()
        coEvery { sales.pagar(any(), any(), any()) } returns json("""{"success":true,"payment":{"paymentId":"payment","orderId":"order","status":"COMPLETED","orderTotalCents":1000}}""")
        assertThat(subir()(replay)).isEqualTo(SyncOutcome.Synced)
        assertThat(queue.markSynced(replay.queueId, replay.claimToken!!)).isEqualTo(1)
        val after = db.pendingPaymentDao().findByReference(row.referenceNumber, venue)!!
        assertThat(after.staffId).isEqualTo("original")
        assertThat(after.orderRequestJson).isEqualTo(original.orderRequestJson)
        assertThat(after.orderWireRequestJson).isEqualTo(original.orderWireRequestJson)
        assertThat(after.cashDrawerSessionId).isNull()
        coVerify(exactly = 1) { sales.crearOrden(any(), any()) }
        coVerify(exactly = 2) { sales.pagar(venue, "order", match { it.shiftIdDeLaVenta == remote && it.staffId == "original" && it.idempotencyKey == key }) }
    }


    @Test fun `GET OPEN viejo de A confirmada nunca revive cierre local confirmado`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        repo.guardarCierreLocal(venue, a.localSessionId).getOrThrow()
        serveCloses(mutableListOf()); repo.sincronizarCierresLocales(venue)
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(remote(local = a.localSessionId)))
        val current = repo.getCurrentShift(venue).getOrThrow()
        assertThat(current?.status?.name).isEqualTo("CLOSED")
        assertThat(current?.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(repo.getCachedOpenShift(venue)).isNull()
    }


    @Test fun `REMOTE parcial A REVIEW conserva aprobacion tardia al adoptar misma S con drawer conocido`() = runTest {
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(remote().copy(cashDrawerSessionId = null)))
        val fetched = repo.getCurrentShift(venue).getOrThrow()!!
        val a = db.localShiftSessionDao().leer(venue, fetched.localShiftSessionId!!)!!
        assertThat(a.openingKind).isEqualTo("REMOTE")
        assertThat(a.cashDrawerSessionId).isNull()
        val settings = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
        val ledger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), settings)
        assertThat(ledger.openAttemptForShift(repo, key, venue, "ANGELPAY", 1000, 0, "FAST", "{\"staffId\":\"original\"}")).isTrue()
        assertThat(ledger.markAuthorizing(key)).isTrue()
        assertThat(ledger.markSinAutorizacion(key, venue, "sin-autorizacion")).isTrue()
        repo.closeShift(venue, a.localSessionId).getOrThrow()
        coEvery { api.closeShift(venue, remote, any()) } returns Response.error(422, okhttp3.ResponseBody.create(null, "revision"))
        repo.sincronizarCierresLocales(venue)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("REVIEW")
        val b = repo.openShift(venue, actor, 50.0).getOrThrow()
        coEvery { api.openShift(venue, any()) } returns Response.success(ShiftResponse(true, remote(disposition = "ADOPTED")))
        repo.sincronizarAperturasLocales(venue)
        val c = repo.adoptarCajaRemotaParaNuevosGestos(venue, b.localShiftSessionId!!).getOrThrow()
        repo.closeShift(venue, c.localSessionId).getOrThrow()
        assertThat(ledger.reabrirSinAutorizacion(key, venue, "sin-autorizacion", "aprobacion tardia no escrita", "unattributed_evidence")).isTrue()
        val attempt = db.paymentAttemptDao().getById(key)!!
        assertThat(attempt.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(attempt.cashDrawerSessionId).isNull()
        val obligation = repo.obligacionesDelCierreLocal(venue, c.localSessionId)
        assertThat(obligation.total).isEqualTo(1)
        assertThat(obligation.rows.single().ownerId).isEqualTo(key)
        assertThat(repo.puedeEnviarCierreLocal(venue, c.localSessionId)).isFalse()
        repo.sincronizarCierresLocales(venue)
        coVerify(exactly = 1) { api.closeShift(venue, remote, any()) }
    }


    @Test fun `Mesas Room44 PAY_CASH conserva A FIFO RETRY cuarentena y no usa B actual`() = runTest {
        val a = local()
        val tables = mockk<com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService>()
        fun outbox() = com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox(db.syncIntentDao(), tables, storage, repo,
            com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate(db, storage))
        val payload = JsonParser.parseString("""{"orderId":"c000000000000000000000021","amountCents":1000,"tipCents":0,"method":"CASH"}""").asJsonObject
        outbox().enqueue(venue, "OPEN_TABLE", JsonObject(), id = "c000000000000000000000022")
        outbox().enqueue(venue, "PAY_CASH", payload, id = key)
        outbox().enqueue(venue, "ADD_ITEMS", JsonObject(), id = "c000000000000000000000023")
        val original = db.syncIntentDao().leer(venue, key)!!
        val sent = mutableListOf<com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest>()
        var disposition = "ACKED"
        coEvery { tables.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest>(); sent += request
            Response.success(com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse(data = request.intents.map {
                com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck(it.id, if (it.id == key) disposition else "ACKED")
            }))
        }
        outbox().replayNow(venue)
        assertThat(sent.single().intents.map { it.type }).containsExactly("OPEN_TABLE")
        assertThat(db.syncIntentDao().leer(venue, key)).isEqualTo(original)
        repo.guardarCierreLocal(venue, a.localSessionId).getOrThrow(); local() // B actual permanece sin mapping.
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        recreate(); actor = "recuperador"
        disposition = "RETRY"
        outbox().replayNow(venue)
        val wire = sent.last().intents.first()
        assertThat(wire.id).isEqualTo(key)
        assertThat(wire.payload["shiftIdDeLaVenta"].asString).isEqualTo(remote)
        assertThat(wire.payload["amountCents"].asInt).isEqualTo(1000)
        assertThat(db.syncIntentDao().leer(venue, key)!!.status).isEqualTo("PENDING")
        assertThat(db.syncIntentDao().leer(venue, "c000000000000000000000023")!!.status).isEqualTo("PENDING")
        disposition = "REJECTED"; outbox().replayNow(venue)
        val rejected = db.syncIntentDao().leer(venue, key)!!
        assertThat(rejected.status).isEqualTo("REJECTED")
        assertThat(rejected.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(rejected.cashDrawerSessionId).isNull()
        assertThat(repo.obligacionesDelCierreLocal(venue, a.localSessionId).rows.map { it.owner }).contains("TABLE_CASH")
    }

    @Test fun `Mesas NO_SHIFT y legacy conservan body y no esperan apertura posterior`() = runTest {
        mode = false
        val tables = mockk<com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService>()
        val outbox = com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox(db.syncIntentDao(), tables, storage, repo,
            com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate(db, storage))
        val payload = JsonParser.parseString("""{"amountCents":1000,"tipCents":0,"method":"CASH"}""").asJsonObject
        outbox.enqueue(venue, "PAY_CASH", payload, id = key)
        val legacyKey = "c000000000000000000000024"
        db.syncIntentDao().insertWithSeq(com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity(
            legacyKey, venue, 0, "PAY_CASH", """{"shiftIdDeLaVenta":"$remote","amountCents":750,"tipCents":0,"method":"CASH"}"""))
        mode = true; local()
        val captured = slot<com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest>()
        coEvery { tables.syncIntents(venue, capture(captured)) } coAnswers { Response.success(
            com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse(data = captured.captured.intents.map {
                com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck(it.id, "ACKED") })) }
        outbox.replayNow(venue)
        assertThat(captured.captured.intents[0].payload).isEqualTo(payload)
        assertThat(captured.captured.intents[1].payload["shiftIdDeLaVenta"].asString).isEqualTo(remote)
        assertThat(db.syncIntentDao().leer(venue, key)!!.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(db.syncIntentDao().leer(venue, legacyKey)!!.status).isEqualTo("ACKED")
    }

    @Test fun `R8 pareja exacta sola incluye otra sesion null parcial distinta y otro venue no se prueban iguales`() = runTest {
        val a = local(); val payment = sale()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(disposition = "ADOPTED")).getOrThrow()
        val b = repo.adoptarCajaRemotaParaNuevosGestos(venue, a.localSessionId).getOrThrow()
        repo.guardarCierreLocal(venue, b.localSessionId).getOrThrow()
        for ((shift, drawerValue, expected) in listOf(Triple(remote, drawer, 1), Triple(null, null, 0),
            Triple(remote, null, 1), Triple(null, drawer, 0), Triple("c000000000000000000000025", drawer, 0),
            Triple(remote, "c000000000000000000000026", 0))) {
            db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET server_shift_id=?,cash_drawer_session_id=? WHERE local_session_id=?", arrayOf(shift, drawerValue, a.localSessionId))
            assertThat(db.localShiftSessionDao().contarObligaciones(venue, b.localSessionId)).isEqualTo(expected)
            assertThat(db.localShiftSessionDao().paginaObligaciones(venue, b.localSessionId, 0).size).isEqualTo(expected)
            assertThat(db.localShiftSessionDao().perteneceAlCierre(venue, b.localSessionId, "PAYMENT", payment.id.toString())).isEqualTo(expected)
            assertThat(db.localShiftSessionDao().contarObligaciones("v2", b.localSessionId)).isEqualTo(0)
        }
    }

    @Test fun `cache desconocido y fondo cero conocido sobreviven restart sin COALESCE`() = runTest {
        repo.openShift(venue, actor, 0.0).getOrThrow()
        val zero = db.localShiftSessionDao().abierta(venue)!!
        repo.closeShift(venue, zero.localSessionId).getOrThrow()
        val cached = com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
            remote, venue, "OPEN", "staff-cache", "", "2026-10-28T10:00:00Z", "0", 0, 0, 1)
        db.cachedShiftDao().cacheShift(cached)
        repo.closeShift(venue, remote, CashReconciliationAction.COUNTED, BigDecimal("0.00")).getOrThrow()
        val unknown = db.localShiftSessionDao().ultima(venue)!!
        recreate()
        assertThat(repo.resumenLocalDeCaja(venue, zero.localSessionId)!!.startingCash).isEqualTo(BigDecimal("0.00"))
        assertThat(repo.resumenLocalDeCaja(venue, unknown.localSessionId)!!.startingCash).isNull()
        assertThat(repo.resumenLocalDeCaja(venue, unknown.localSessionId)!!.countedCash).isEqualTo(BigDecimal("0.00"))
        assertThat(db.localShiftSessionDao().leer(venue, unknown.localSessionId)!!.openedByStaffId).isEqualTo("staff-cache")
    }

    @Test fun `cache importada se cierra en misma Tx y nunca admite venta nueva en fila parcial`() = runTest {
        db.cachedShiftDao().cacheShift(com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
            remote, venue, "OPEN", actor, "", "2026-10-28T10:00:00Z", "0", 0, 0, 1))
        repo.closeShift(venue, remote).getOrThrow()
        val imported = db.localShiftSessionDao().ultima(venue)!!
        assertThat(imported.localStatus).isEqualTo("CLOSED")
        var admitted = false
        assertThat(runCatching { repo.guardarNuevaObligacionDeCaja(venue) { admitted = true } }.isFailure).isTrue()
        assertThat(admitted).isFalse()
        assertThat(imported.serverShiftId).isEqualTo(remote)
        assertThat(imported.cashDrawerSessionId).isNull()
        assertThat(imported.startingCash).isNull()
    }


    @Test fun `GET externo CLOSING o CLOSED cierra admision durable sin fabricar conteo ni perder callback A`() = runTest {
        for (status in listOf("CLOSING", "CLOSED")) {
            val a = local()
            repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
            val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
            val settings = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
            val ledger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertThat(ledger.openAttemptForShift(repo, key, venue, "BLUMON", 1000, 0, "FAST", "{\"staffId\":\"original\"}")).isTrue()
            coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(remote(local = a.localSessionId).copy(status = status)))
            val result = repo.getCurrentShift(venue).getOrThrow()!!
            assertThat(result.status.name).isNotEqualTo("OPEN")
            val observed = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
            assertThat(observed.localStatus).isEqualTo("CLOSED")
            assertThat(observed.closeStatus).isEqualTo("REVIEW")
            assertThat(observed.closeOperationId).isNull()
            assertThat(observed.closeRequestJson).isNull()
            assertThat(observed.countedCash).isNull()
            assertThat(observed.openingEvidenceJson).isEqualTo(original.openingEvidenceJson)
            assertThat(observed.openingRequestJson).isEqualTo(original.openingRequestJson)
            db.cachedShiftDao().clearCache(venue); recreate()
            assertThat(repo.getCachedOpenShift(venue)).isNull()
            var admitted = false
            assertThat(runCatching { repo.guardarNuevaObligacionDeCaja(venue) { admitted = true } }.isFailure).isTrue()
            assertThat(admitted).isFalse()
            val recoveredLedger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), settings)
            assertThat(recoveredLedger.markHostResponded(key, true, "op", "late-reference", "auth")).isTrue()
            recoveredLedger.markAuthorized(key, null, null, "CHIP")
            assertThat(db.paymentAttemptDao().getById(key)!!.state).isEqualTo("AUTORIZADO")
            assertThat(db.paymentAttemptDao().getById(key)!!.localShiftSessionId).isEqualTo(a.localSessionId)
            assertThat(repo.obligacionesDelCierreLocal(venue, a.localSessionId).total).isEqualTo(1)
            repo.sincronizarCierresLocales(venue)
            coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
            db.close(); context.deleteDatabase("task6-replay"); setup()
        }
    }

    @Test fun `GET tardio CLOSING de A no cierra B ni cambia conteo A ya comprometido`() = runTest {
        val a = local()
        repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(local = a.localSessionId)).getOrThrow()
        var b: com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity? = null
        coEvery { api.getCurrentShift(venue) } coAnswers {
            repo.closeShift(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
            b = local()
            Response.success(CurrentShiftResponse(remote(local = a.localSessionId).copy(status = "CLOSING")))
        }
        val result = repo.getCurrentShift(venue).getOrThrow()!!
        assertThat(b).isNotNull()
        assertThat(result.localShiftSessionId).isEqualTo(b!!.localSessionId)
        assertThat(result.status.name).isEqualTo("OPEN")
        assertThat(db.localShiftSessionDao().leer(venue, b!!.localSessionId)!!.localStatus).isEqualTo("OPEN")
        val original = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(original.countedCash).isEqualTo("61.25")
        assertThat(original.closeStatus).isEqualTo("PENDING")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }


    @Test fun `R8 ACK sin desenlace financiero no adelanta cierre misma pareja en BLUMON y ANGELPAY`() = runTest {
        for (processor in listOf("BLUMON", "ANGELPAY")) {
            val a = local()
            val queue = com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl(db.pendingPaymentDao())
            val q = com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity(referenceNumber = "ref-$key", venueId = venue,
                staffId = "original", amount = "10.00", tip = "0.00", rating = null, merchantAccountId = "", blumonSerialNumber = "",
                maskedPan = null, cardBrand = null, entryMode = "CHIP", isInternational = false, authorizationNumber = "auth",
                createdAt = 1, idempotencyKey = key, paymentProcessor = processor, shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId,
                syncStatus = "SYNCING", claimToken = "own-token", claimedAt = 1)
            val id = db.pendingPaymentDao().insertRow(q)
            repo.registrarResultadoAperturaLocal(venue, a.localSessionId, remote(disposition = "ADOPTED")).getOrThrow()
            val b = repo.adoptarCajaRemotaParaNuevosGestos(venue, a.localSessionId).getOrThrow()
            repo.guardarCierreLocal(venue, b.localSessionId).getOrThrow()
            // Simula la entrega tardía del SDK sobre la captura original, sin cambiar refs al mapping nuevo.
            db.paymentAttemptDao().insert(com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
                attemptId = key, venueId = venue, processor = processor, kind = "SALE", state = "AUTORIZADO",
                amountCents = 1000, tipCents = 0, recordingRoute = "FAST", hostApproved = true,
                referenceNumber = "ref-$key", authCode = "auth", paymentContextJson = "{\"staffId\":\"original\"}",
                shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId, createdAt = 1, updatedAt = 1))
            fun verdict(final: Boolean) = com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento(
                venue, key, null,
                if (final) com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.RECORDED else com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.PENDING_EVIDENCE,
                "payment-$key", "terminal", 1000, 0, if (final) "payment-$key" else null,
                com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento.Fuente.REST)
            val pending = db.paymentAttemptDao().aplicarVeredictoDelServidor(verdict(false), System.currentTimeMillis())
            assertThat(pending.decision.name).isEqualTo("GUARDADO_SIN_LIBERAR")
            assertThat(queue.markSynced(id, "own-token")).isEqualTo(1)
            assertThat(db.pendingPaymentDao().findByReference("ref-$key", venue)!!.syncStatus).isEqualTo("SUCCESS")
            assertThat(db.paymentAttemptDao().getById(key)!!.state).isEqualTo("AUTORIZADO")
            val page = repo.obligacionesDelCierreLocal(venue, b.localSessionId)
            assertThat(page.total).isEqualTo(1)
            assertThat(page.rows.single().owner).isEqualTo("ATTEMPT")
            assertThat(db.localShiftSessionDao().perteneceAlCierre(venue, b.localSessionId, "ATTEMPT", key)).isEqualTo(1)
            val events = mutableListOf<String>(); serveCloses(events)
            repo.sincronizarCierresLocales(venue)
            assertThat(events).isEmpty()
            val applied = db.paymentAttemptDao().aplicarVeredictoDelServidor(verdict(true), System.currentTimeMillis())
            assertThat(applied.decision.name).isEqualTo("APLICADO")
            assertThat(repo.obligacionesDelCierreLocal(venue, b.localSessionId).total).isEqualTo(0)
            assertThat(db.localShiftSessionDao().perteneceAlCierre(venue, b.localSessionId, "ATTEMPT", key)).isEqualTo(0)
            repo.sincronizarCierresLocales(venue)
            assertThat(events).containsExactly("close:${b.localSessionId}")
            assertThat(db.localShiftSessionDao().leer(venue, b.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
            db.close(); context.deleteDatabase("task6-replay"); setup()
        }
    }


    @Test fun `fallo al despertar scheduler conserva commit diagnostico y recuperacion tras recrear`() = runTest {
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) } throws IllegalStateException("WorkManager no pudo agendar")
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } throws IllegalStateException("WorkManager no pudo agendar")
        try {
            val opened = repo.openShift(venue, actor, 50.0).getOrThrow()
            val original = db.localShiftSessionDao().leer(venue, opened.localShiftSessionId!!)!!
            repo.closeShift(venue, original.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
            recreate()
            val recovered = db.localShiftSessionDao().leer(venue, original.localSessionId)!!
            assertThat(recovered.openingRequestJson).isEqualTo(original.openingRequestJson)
            assertThat(recovered.openingOperationId).isEqualTo(original.openingOperationId)
            assertThat(recovered.startingCash).isEqualTo("50.00")
            assertThat(recovered.countedCash).isEqualTo("61.25")
            assertThat(recovered.closeRequestJson).isNotNull()
            assertThat(recovered.closeStatus).isEqualTo("PENDING")
            assertThat(recovered.lastError).contains("guardada")
            assertThat(repo.haySesionesPorReproducir()).isTrue()
            coVerify(exactly = 0) { api.openShift(any(), any()) }
            coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        } finally { unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler) }
    }


    @Test fun `gestos nuevos durante worker retenido tras OPEN dejan despertar durable sin duplicar intencion`() = runTest {
        val enteredRefund = CompletableDeferred<Unit>()
        val releaseRefund = CompletableDeferred<Unit>()
        val firstRefund = java.util.concurrent.atomic.AtomicBoolean(true)
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } coAnswers {
            if (firstRefund.compareAndSet(true, false)) { enteredRefund.complete(Unit); releaseRefund.await() }
            emptyList()
        }
        val queue = com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        val gate = com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate(db, storage)
        val config = androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor())
            .setTaskExecutor(androidx.work.testing.SynchronousExecutor())
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: android.content.Context, workerClassName: String, params: androidx.work.WorkerParameters): androidx.work.ListenableWorker =
                    com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker(appContext, params, queue, mockk(relaxed = true),
                        com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager(), refunds, mockk(relaxed = true), subir(), repo, gate, com.jaac.avoqado_tpv.features.shift.data.emptyTableReplayFixture())
            }).build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val manager = androidx.work.WorkManager.getInstance(context)
        val driver = androidx.work.testing.WorkManagerTestInitHelper.getTestDriver(context)!!
        val first = androidx.work.OneTimeWorkRequestBuilder<com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker>().build()
        val events = mutableListOf<String>()
        try {
            manager.enqueueUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP, first).result.get()
            driver.setAllConstraintsMet(first.id)
            withContext(Dispatchers.Default) { withTimeout(10_000) { enteredRefund.await() } }
            assertThat(manager.getWorkInfoById(first.id).get()!!.state).isEqualTo(androidx.work.WorkInfo.State.RUNNING)
            // Esta pasada ya dejó atrás OPEN sin avance. Los gestos ocurren mientras drena refunds.
            val b = repo.openShift(venue, actor, 50.0).getOrThrow()
            assertThat(repo.openShift(venue, actor, 999.0).getOrThrow().localShiftSessionId).isEqualTo(b.localShiftSessionId)
            repo.closeShift(venue, b.localShiftSessionId!!, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
            repo.closeShift(venue, b.localShiftSessionId!!, CashReconciliationAction.COUNTED, BigDecimal("999.00")).getOrThrow()
            val chain = manager.getWorkInfosForUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME).get()
            assertThat(chain).hasSize(3) // worker actual + exactamente un despertar por OPEN y por CLOSE nuevos.
            coEvery { api.openShift(venue, any()) } returns Response.success(ShiftResponse(true, remote(local = b.localShiftSessionId)))
            serveCloses(events)
            releaseRefund.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) {
                while (db.localShiftSessionDao().leer(venue, b.localShiftSessionId!!)!!.closeStatus != "CONFIRMED") {
                    manager.getWorkInfosForUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME).get()
                        .filter { it.state == androidx.work.WorkInfo.State.ENQUEUED }.forEach { driver.setAllConstraintsMet(it.id) }
                    delay(10)
                }
            } }
            assertThat(db.localShiftSessionDao().leer(venue, b.localShiftSessionId!!)!!.countedCash).isEqualTo("61.25")
            coVerify(exactly = 1) { api.openShift(venue, any()) }
            coVerify(exactly = 1) { api.closeShift(venue, remote, any()) }
            assertThat(events).containsExactly("close:${b.localShiftSessionId}")
        } finally {
            releaseRefund.complete(Unit)
            manager.cancelAllWork().result.get()
            // La fábrica sólo pertenece a este WorkManager de prueba y no sobrevive a su Room.
            androidx.work.impl.WorkManagerImpl.setDelegate(null)
        }
    }

    @Test fun `worker sin avance ni mapping no crea append financiero ni consume retry`() = runTest {
        local(); val payment = sale(claimed = false)
        coEvery { api.openShift(venue, any()) } returns Response.error(503, okhttp3.ResponseBody.create(null, "sin red"))
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } just Runs
        try {
            worker().doWork()
            verify(exactly = 0) { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) }
            val after = db.pendingPaymentDao().findByReference(payment.referenceNumber, venue)!!
            assertThat(after.syncStatus).isEqualTo("PENDING")
            assertThat(after.retryCount).isEqualTo(0)
            coVerify(exactly = 0) { sales.crearOrden(any(), any()) }
            coVerify(exactly = 0) { sales.pagar(any(), any(), any()) }
        } finally { unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler) }
    }

}
