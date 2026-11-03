package com.jaac.avoqado_tpv.features.verification.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.util.Collections
import java.util.UUID

/**
 * «Pendientes» sin señal contra Room real y un SERVIDOR de mentira por HTTP (MockWebServer + el `ProofOfSaleRemote`
 * real): la lista guardada, las ventas recién cobradas y la cola encima. 🔴 La TPV no adivina: una tarjeta sólo se va
 * cuando `status` dice COMPLETED o REJECTED. «Reiniciar» = otro repositorio sobre la MISMA base, con el servidor caído.
 * runBlocking: hay red real de por medio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ActionableSalesRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AvoqadoDatabase
    private lateinit var mock: MockWebServer
    private lateinit var api: ApiService
    private lateinit var servidor: Servidor
    private lateinit var repo: ActionableSalesRepository
    private lateinit var cola: ProofOfSaleQueueRepository
    private val storage = mockk<SecureStorage>()
    private val uploader = mockk<VerificationUploadManager>()

    /** El SERVIDOR: páginas por cursor, estado por pago, detalle por verificación y la respuesta del proof-of-sale. */
    private inner class Servidor : Dispatcher() {
        @Volatile var caido = false
        val paginas = mutableMapOf<String?, String>()
        val estados = mutableMapOf<String, String>()
        val detalles = mutableMapOf<String, String>()
        @Volatile var proofOfSale = ok()
        val peticiones: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        override fun dispatch(request: RecordedRequest): MockResponse {
            peticiones += request
            if (caido) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            val url = request.requestUrl!!
            return when (val path = url.encodedPath) {
                "/tpv/verification/actionable" -> paginas[url.queryParameter("cursor")]?.let { json(200, it) }
                    ?: json(400, """{"success":false,"message":"El cursor no es válido","code":"INVALID_CURSOR"}""")
                "/tpv/verification/status" -> {
                    val data = url.queryParameter("paymentIds")!!.split(',')
                        .joinToString(",") { """{"paymentId":"$it","status":"${estados[it] ?: "NONE"}"}""" }
                    json(200, """{"success":true,"data":[$data]}""")
                }
                "/tpv/verification/proof-of-sale" -> proofOfSale
                else -> detalles[path.substringAfterLast('/')]?.let { json(200, """{"success":true,"data":$it}""") }
                    ?: json(404, """{"success":false,"message":"no existe"}""")
            }
        }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        servidor = Servidor()
        mock = MockWebServer().also { it.dispatcher = servidor; it.start() }
        api = Retrofit.Builder().baseUrl(mock.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
        every { storage.getVenueId() } returns "v1"
        every { storage.getVenueSlug() } returns "tienda-a"
        every { storage.getStaffId() } returns "s1"
        coEvery { uploader.uploadToPath(any(), any()) } answers { Result.success("https://fb/" + secondArg<String>().substringAfterLast('/')) }
        mockkObject(PaymentSyncScheduler)
        every { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } returns Unit
        every { PaymentSyncScheduler.runNow(any()) } returns Unit
        repo = nuevoRepo()
        cola = ProofOfSaleQueueRepository(
            context, db, db.proofOfSaleQueueDao(), db.actionableSaleCacheDao(), ProofOfSaleRemote(api), uploader, ProofCaptureSequence(context), storage,
        )
    }

    @After fun tearDown() {
        unmockkObject(PaymentSyncScheduler)
        db.close()
        runCatching { mock.shutdown() }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    /** Un proceso nuevo sobre la misma base: lo que había en memoria (sin señal, cursor) se pierde, Room no. */
    private fun nuevoRepo() = ActionableSalesRepository(
        db, db.actionableSaleCacheDao(), db.proofOfSaleQueueDao(), ProofOfSaleRemote(api), SaleVerificationRepository(api), storage,
    )

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun ok() = json(
        200,
        """{"success":true,"verificationId":"ver-x","status":"PENDING","message":"ok","outcome":"APPLIED","photoOutcome":"APPLIED"}""",
    )

    private fun venta(payment: String, status: String = "PENDING", portabilidad: Boolean = false, fotos: String = "", dia: Int = 1) =
        """{"id":"ver-$payment","paymentId":"$payment","amount":149.0,"orderNumber":"SN-$payment","date":"2026-10-${"%02d".format(dia)}T10:00:00.000Z",""" +
            """"serialNumbers":["8952$payment"],"isPortabilidad":$portabilidad,"photos":[$fotos],"requiredPhotos":${if (portabilidad) 2 else 1},"status":"$status"}"""

    private fun pagina(vararg ventas: String, cursor: String? = null) =
        """{"success":true,"data":[${ventas.joinToString(",")}],"total":${ventas.size},"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    private suspend fun estado() = repo.observe("s1").first()

    private suspend fun ids() = estado().items.map { it.paymentId }

    private fun camara() = File(context.cacheDir, "cam-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath

    private suspend fun foto(item: ActionableItem, slot: ProofSlot) = cola.enqueuePhoto(
        PhotoCapture("v1", "tienda-a", "s1", item.paymentId, item.verificationId, slot, camara(), null),
    )

    private fun esperaGet(path: String) = servidor.peticiones.count { it.method == "GET" && it.requestUrl!!.encodedPath == path }

    // ─── sin red y tras reiniciar ───────────────────────────────────────────────

    @Test fun `sin red y tras reiniciar muestra la lista guardada con su aviso y la venta recien cobrada`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1"), venta("pay-2"))
        assertThat(repo.refresh().isSuccess).isTrue()
        repo.addLocalSale("pay-nueva", BigDecimal("149.00"), "SN-9", isPortabilidad = false, serialNumbers = listOf("8952999"))

        servidor.caido = true
        repo = nuevoRepo()
        val fallo = repo.refresh()

        assertThat(fallo.exceptionOrNull()).isInstanceOf(IOException::class.java)
        val s = estado()
        assertThat(s.isOffline).isTrue()
        assertThat(s.items.map { it.paymentId }).containsExactly("pay-nueva", "pay-2", "pay-1")
        val nueva = s.items.first()
        assertThat(nueva.localOnly).isTrue()
        assertThat(nueva.verificationId).isNull()
        assertThat(nueva.detail!!.serialNumbers).containsExactly("8952999")
        assertThat(nueva.detail!!.amount).isEqualTo(149.0)
        assertThat(s.items.single { it.paymentId == "pay-2" }.verificationId).isEqualTo("ver-pay-2")
        assertThat(ACTIONABLE_OFFLINE_MESSAGE).isEqualTo("Lista guardada en la terminal; se actualiza al volver la señal")
    }

    @Test fun `un rechazo del servidor no es sin senal - lo decide la peticion, no una bandera`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1"))
        repo.refresh()
        servidor.paginas.clear()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = json(500, """{"success":false,"message":"boom"}""")
        }

        assertThat(repo.refresh().isFailure).isTrue()

        assertThat(estado().isOffline).isFalse()
        assertThat(ids()).containsExactly("pay-1") // nada se borra por un error
    }

    @Test fun `tomar una foto sin red la encola encima de su tarjeta`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1"))
        repo.refresh()
        servidor.caido = true
        repo = nuevoRepo()
        repo.refresh()

        val key = foto(estado().items.single(), ProofSlot.VINCULACION)

        val item = estado().items.single()
        assertThat(item.queued.map { it.idempotencyKey }).containsExactly(key)
        assertThat(item.queued.single().verificationId).isEqualTo("ver-pay-1")
        assertThat(item.queued.single().venueSlug).isEqualTo("tienda-a")
        assertThat(item.queued.single().syncStatus).isEqualTo("PENDING")
    }

    // ─── portabilidad recién cobrada ────────────────────────────────────────────

    @Test fun `portabilidad recien cobrada con la primera foto entregada sigue tras reiniciar sin red y acepta la segunda`() = runBlocking<Unit> {
        repo.addLocalSale("pay-p", BigDecimal("299.00"), null, isPortabilidad = true, serialNumbers = listOf("8952777"))
        foto(estado().items.single(), ProofSlot.VINCULACION)
        cola.drain() // el servidor la aplicó: la ranura queda entregada y la fila se borra

        servidor.caido = true
        repo = nuevoRepo()
        repo.refresh()

        val item = estado().items.single()
        assertThat(item.paymentId).isEqualTo("pay-p")
        assertThat(item.deliveredSlots).containsExactly(ProofSlot.VINCULACION)
        assertThat(item.queued).isEmpty()
        assertThat(item.detail!!.requiredPhotos).isEqualTo(2)

        foto(item, ProofSlot.PORTABILIDAD)
        assertThat(estado().items.single().queued.map { it.slot }).containsExactly("PORTABILIDAD")
    }

    // ─── 🔴 el servidor decide cuándo se va una tarjeta ─────────────────────────

    @Test fun `con 201 ventas incompletas ninguna de la segunda pagina se borra ni local ni en el servidor`() = runBlocking<Unit> {
        val primera = (1..200).map { venta("pay-$it") }.toTypedArray()
        servidor.paginas[null] = pagina(*primera, cursor = "c2")
        servidor.paginas["c2"] = pagina(venta("pay-201"))
        repo.refresh()
        repo.loadMore()
        assertThat(ids()).hasSize(201)

        servidor.estados["pay-201"] = "PENDING"
        repo.refresh()

        assertThat(ids()).hasSize(201)
        assertThat(ids()).contains("pay-201")
        assertThat(servidor.peticiones.map { it.method }.toSet()).containsExactly("GET")
    }

    @Test fun `una portabilidad con sus dos fotos que regresa a Revisar sigue tras refresh y reinicio sin red y se puede repetir la foto`() = runBlocking<Unit> {
        val fotos = """"https://fb/v.jpg","https://fb/p.jpg""""
        servidor.paginas[null] = pagina(venta("pay-p", portabilidad = true, fotos = fotos))
        repo.refresh()
        db.actionableSaleCacheDao().setDeliveredSlots("pay-p", "VINCULACION,PORTABILIDAD")

        // El back-office la regresa a «Revisar» por ilegible; ya no viene en la primera página.
        servidor.paginas[null] = pagina()
        servidor.estados["pay-p"] = "FAILED"
        repo.refresh()
        assertThat(ids()).containsExactly("pay-p")

        servidor.caido = true
        repo = nuevoRepo()
        repo.refresh()
        val item = estado().items.single()
        assertThat(item.deliveredSlots).containsExactly(ProofSlot.VINCULACION, ProofSlot.PORTABILIDAD)
        foto(item, ProofSlot.PORTABILIDAD)
        assertThat(estado().items.single().queued).hasSize(1)
    }

    @Test fun `una venta local aprobada antes de la primera descarga y sin fotos en cola desaparece porque status dice COMPLETED`() = runBlocking<Unit> {
        repo.addLocalSale("pay-ok", BigDecimal("149.00"), null, isPortabilidad = false, serialNumbers = emptyList())
        servidor.paginas[null] = pagina()
        servidor.estados["pay-ok"] = "COMPLETED"

        repo.refresh()

        assertThat(ids()).isEmpty()
    }

    @Test fun `REJECTED tambien la quita y PENDING, FAILED y PROCESSING la dejan`() = runBlocking<Unit> {
        listOf("pay-r", "pay-p", "pay-f", "pay-x").forEach {
            repo.addLocalSale(it, BigDecimal.TEN, null, isPortabilidad = false, serialNumbers = emptyList())
        }
        servidor.paginas[null] = pagina()
        servidor.estados.putAll(mapOf("pay-r" to "REJECTED", "pay-p" to "PENDING", "pay-f" to "FAILED", "pay-x" to "PROCESSING"))

        repo.refresh()

        assertThat(ids()).containsExactly("pay-p", "pay-f", "pay-x")
    }

    @Test fun `un cobro cuya verificacion no se creo - NONE - sigue y su foto se puede encolar`() = runBlocking<Unit> {
        repo.addLocalSale("pay-n", BigDecimal("149.00"), null, isPortabilidad = false, serialNumbers = listOf("8952"))
        servidor.paginas[null] = pagina()

        repo.refresh() // el servidor no conoce el pago: NONE

        val item = estado().items.single()
        assertThat(item.paymentId).isEqualTo("pay-n")
        foto(item, ProofSlot.VINCULACION)
        val fila = estado().items.single().queued.single()
        assertThat(fila.paymentId).isEqualTo("pay-n")
        assertThat(fila.verificationId).isNull()
    }

    @Test fun `sin respuesta de status no se quita ninguna tarjeta`() = runBlocking<Unit> {
        repo.addLocalSale("pay-1", BigDecimal.TEN, null, isPortabilidad = false, serialNumbers = emptyList())
        servidor.paginas[null] = pagina()
        servidor.estados["pay-1"] = "COMPLETED"
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl!!.encodedPath.endsWith("/status")) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                else servidor.dispatch(request)
        }

        assertThat(repo.refresh().isSuccess).isTrue()

        assertThat(ids()).containsExactly("pay-1")
    }

    @Test fun `descartar un La venta ya estaba aprobada de una venta reabierta deja la tarjeta tambien tras reiniciar sin red`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-a"))
        repo.refresh()
        servidor.proofOfSale = json(
            200,
            """{"success":true,"verificationId":"ver-pay-a","status":"COMPLETED","message":"ok","outcome":"SALE_ALREADY_APPROVED"}""",
        )
        val key = foto(estado().items.single(), ProofSlot.VINCULACION)
        cola.drain()
        assertThat(estado().items.single().queued.single().outcome).isEqualTo("SALE_ALREADY_APPROVED")

        // El back-office la reabre y vuelve a «Revisar».
        servidor.paginas[null] = pagina(venta("pay-a", status = "FAILED"))
        repo.refresh()
        cola.discard(key)

        assertThat(estado().items.single().queued).isEmpty()
        servidor.caido = true
        repo = nuevoRepo()
        repo.refresh()
        assertThat(ids()).containsExactly("pay-a")
        assertThat(estado().items.single().detail!!.status).isEqualTo("FAILED")
    }

    // ─── páginas ────────────────────────────────────────────────────────────────

    @Test fun `Cargar mas agrega la siguiente pagina sin borrar nada`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1", dia = 3), venta("pay-2", dia = 2), cursor = "c2")
        servidor.paginas["c2"] = pagina(venta("pay-3", dia = 1))
        repo.refresh()
        assertThat(estado().nextCursor).isEqualTo("c2")

        assertThat(repo.loadMore().isSuccess).isTrue()

        assertThat(ids()).containsExactly("pay-1", "pay-2", "pay-3").inOrder()
        assertThat(estado().nextCursor).isNull()
        assertThat(repo.loadMore().isSuccess).isTrue() // sin cursor no pide nada
        assertThat(esperaGet("/tpv/verification/actionable")).isEqualTo(2)
    }

    @Test fun `una venta que cambia de pagina mientras se pagina no sale dos veces ni se pierde y conserva sus ranuras`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1"), venta("pay-2"), cursor = "c2")
        // pay-2 recibió su foto y se movió al final: sale otra vez en la segunda página.
        servidor.paginas["c2"] = pagina(venta("pay-2", fotos = """"https://fb/v.jpg""""), venta("pay-3"))
        repo.refresh()
        db.actionableSaleCacheDao().setDeliveredSlots("pay-2", "VINCULACION")

        repo.loadMore()

        assertThat(ids().sorted()).containsExactly("pay-1", "pay-2", "pay-3").inOrder()
        val dos = estado().items.single { it.paymentId == "pay-2" }
        assertThat(dos.detail!!.photos).containsExactly("https://fb/v.jpg")
        assertThat(dos.deliveredSlots).containsExactly(ProofSlot.VINCULACION)
    }

    @Test fun `un cursor que el servidor ya no reconoce - INVALID_CURSOR por su code - se olvida y no borra nada`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1"), cursor = "viejo")
        repo.refresh()

        val fallo = repo.loadMore() // el servidor no tiene página para "viejo": 400 INVALID_CURSOR

        assertThat(fallo.isFailure).isTrue()
        assertThat(estado().nextCursor).isNull()
        assertThat(estado().isOffline).isFalse()
        assertThat(ids()).containsExactly("pay-1")
    }

    @Test fun `la pagina del servidor reemplaza a la venta local y conserva sus ranuras entregadas`() = runBlocking<Unit> {
        repo.addLocalSale("pay-1", BigDecimal.TEN, null, isPortabilidad = true, serialNumbers = emptyList())
        db.actionableSaleCacheDao().setDeliveredSlots("pay-1", "VINCULACION")
        servidor.paginas[null] = pagina(venta("pay-1", portabilidad = true, fotos = """"https://fb/v.jpg""""))

        repo.refresh()

        val item = estado().items.single()
        assertThat(item.localOnly).isFalse()
        assertThat(item.verificationId).isEqualTo("ver-pay-1")
        assertThat(item.deliveredSlots).containsExactly(ProofSlot.VINCULACION)
    }

    @Test fun `la venta recien cobrada no pisa a la que ya mando el servidor`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1"))
        repo.refresh()

        repo.addLocalSale("pay-1", BigDecimal.ONE, null, isPortabilidad = false, serialNumbers = emptyList())

        val item = estado().items.single()
        assertThat(item.localOnly).isFalse()
        assertThat(item.detail!!.amount).isEqualTo(149.0)
    }

    // ─── otra tienda y corrección ───────────────────────────────────────────────

    @Test fun `una foto guardada en otra tienda de la organizacion sigue visible`() = runBlocking<Unit> {
        db.proofOfSaleQueueDao().insertRow(
            ProofOfSaleQueueEntity(
                idempotencyKey = "k-b", kind = "PHOTO", venueId = "v2", staffId = "s1", paymentId = "pay-b", verificationId = "ver-b",
                slot = "VINCULACION", localPath = camara(), linkingNumber = null, portedNumber = null, capturedAt = 5L, captureSeq = 1L,
                captureDeviceId = "d", createdAt = 5L, venueSlug = "tienda-b",
            ),
        )

        val item = estado().items.single()

        assertThat(item.paymentId).isEqualTo("pay-b")
        assertThat(item.detail).isNull()
        assertThat(item.queued.single().venueId).isEqualTo("v2")
    }

    @Test fun `la correccion abre su detalle sin red desde la lista guardada`() = runBlocking<Unit> {
        servidor.paginas[null] = pagina(venta("pay-1", status = "FAILED"))
        repo.refresh()

        servidor.caido = true
        repo = nuevoRepo()
        val detalle = repo.detail("ver-pay-1")

        assertThat(detalle!!.paymentId).isEqualTo("pay-1")
        assertThat(detalle.status).isEqualTo("FAILED")
        assertThat(repo.detail("ver-otra")).isNull()
    }

    @Test fun `con red la correccion trae el detalle del servidor y lo guarda para despues`() = runBlocking<Unit> {
        servidor.detalles["ver-pay-9"] = venta("pay-9", status = "FAILED")

        assertThat(repo.detail("ver-pay-9")!!.status).isEqualTo("FAILED")

        servidor.caido = true
        assertThat(nuevoRepo().detail("ver-pay-9")!!.paymentId).isEqualTo("pay-9")
    }
}
