package com.jaac.avoqado_tpv.features.payment.domain.usecase

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.di.NetworkModule
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.payment.data.api.PagoDeVentaGuardadaRequest
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.repository.toQueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

/** Contratos de mobile reales: createOrder -> {success, order} en pesos; payCash -> {success, payment} en centavos. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SubirVentaGuardadaUseCaseTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var context: Context
    private lateinit var api: VentaGuardadaApi
    private lateinit var subir: SubirVentaGuardadaUseCase
    private val llave = "c000000000000000000000001"
    private val orderId = "c000000000000000000000002"
    private val venta = VentaLocal(
        llave = llave, venueId = "venue-1", staffId = "staff-1", shiftId = "shift-1",
        createdAtLocal = 1_760_000_000_000L, totalCents = 10000,
        pedidoJson = """{ "externalId":"$llave", "items":[{"externalId":"$llave:0","unitPrice":10000,"quantity":1}], "total":10000 }""",
        renglones = listOf(RenglonDeTicket("$llave:0", "Blusa", 1, 10000, 10000, emptyList(), null)),
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        api = mockk()
        subir = SubirVentaGuardadaUseCase(api, db.pendingPaymentDao(), context)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 3)
        coEvery { api.crearOrden("venue-1", any()) } returns orden()
        coEvery { api.pagar("venue-1", orderId, any()) } returns pago()
    }

    @After fun tearDown() { db.close() }

    private suspend fun guardar(v: VentaLocal = venta, propina: Int = 0, reloj: Long? = null, arranque: Int? = null): PendingPaymentEntity {
        VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()).guardar(
            v, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1", reclamada = true, propinaCents = propina,
            relojMonotonoAlVender = reloj, arranqueAlVender = arranque,
        ).getOrThrow()
        return fila(v)
    }

    private suspend fun fila(v: VentaLocal = venta): PendingPaymentEntity =
        db.pendingPaymentDao().findByReference(referenciaDeEfectivo(v.llave), v.venueId)!!

    // Fijado desde CreatedOrderResponse/toCreatedOrderResponse y el sobre de createOrder del SERVIDOR.
    private fun orden(total: String = "100", status: String = "PENDING", items: String = item(), priceChanges: String = "[]") = json(
        """{"success":true,"order":{"id":"$orderId","orderNumber":"V-101","status":"PENDING","paymentStatus":"$status",
        "subtotal":$total,"taxAmount":0,"discountAmount":0,"total":$total,"items":[$items],"promotions":[],
        "createdAt":"2026-10-22T12:00:00.000Z","priceChanges":$priceChanges}}""",
    )

    private fun item(id: String = "$llave:0", total: String = "100", extras: String = "[]") =
        """{"id":"item-1","productId":"product-1","productName":"Blusa","quantity":1,"unitPrice":$total,"total":$total,
        "discountAmount":0,"appliedDiscountId":null,"orderPromotionId":null,"externalId":"$id","modifiers":$extras}"""

    // Fijado desde CashPaymentResponse y el sobre de payCash del SERVIDOR: paymentId, NO id, y todo el dinero en centavos.
    private fun pago(total: Int = 10000, tip: Int = 0, amount: Int = 10000) = json(
        """{"success":true,"payment":{"paymentId":"payment-1","orderId":"$orderId","orderNumber":"V-101","amount":$amount,
        "tipAmount":$tip,"method":"CASH","status":"COMPLETED","changeCents":0,"digitalReceipt":null,
        "remainingBalanceCents":0,"orderPaymentStatus":"PAID","orderTotalCents":$total,"totalPaidCents":$total}}""",
    )

    private fun json(text: String): Response<JsonObject> = Response.success(JsonParser.parseString(text).asJsonObject)
    private fun error(code: Int): Response<JsonObject> = Response.error(code, """{"message":"rechazo"}""".toResponseBody("application/json".toMediaType()))

    @Test fun `orden y pago confirmados suben una sola venta con sus unidades y su llave`() = runTest {
        val guardada = guardar()
        val request = slot<PagoDeVentaGuardadaRequest>()
        coEvery { api.pagar("venue-1", orderId, capture(request)) } returns pago()

        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)

        assertThat(fila().orderId).isEqualTo(orderId)
        assertThat(fila().orderNumber).isEqualTo("V-101")
        assertThat(fila().requiereRevision).isFalse()
        coVerify(exactly = 1) { api.crearOrden("venue-1", any()) }
        coVerify(exactly = 1) { api.pagar("venue-1", orderId, any()) }
        val cuerpo = Gson().toJsonTree(request.captured).asJsonObject
        assertThat(cuerpo["amount"].asInt).isEqualTo(10000)
        assertThat(cuerpo["tip"].asInt).isEqualTo(0)
        assertThat(cuerpo["venueId"].asString).isEqualTo("venue-1")
        assertThat(cuerpo["staffId"].asString).isEqualTo("staff-1")
        assertThat(cuerpo["shiftIdDeLaVenta"].asString).isEqualTo("shift-1")
        assertThat(cuerpo["idempotencyKey"].asString).isEqualTo(llave)
        assertThat(cuerpo["isOfflineReplay"].asBoolean).isTrue()
        assertThat(cuerpo["source"].asString).isEqualTo("TPV")
        assertThat(cuerpo["method"].asString).isEqualTo("CASH")
        assertThat(cuerpo["status"].asString).isEqualTo("COMPLETED")
        assertThat(cuerpo["createdAtLocal"].asLong).isEqualTo(1_760_000_000_000L)
    }

    @Test fun `el cuerpo de crear orden viaja byte a byte sin volver a serializarlo`() = runTest {
        val guardada = guardar()
        val cuerpo = slot<RequestBody>()
        coEvery { api.crearOrden("venue-1", capture(cuerpo)) } returns orden()

        subir(guardada.toQueuedPayment())

        assertThat(cuerpo.isCaptured).isTrue()
        val buffer = Buffer()
        cuerpo.captured.writeTo(buffer)
        assertThat(buffer.readUtf8()).isEqualTo(venta.pedidoJson)
    }

    @Test fun `Retrofit por defecto envia las rutas mobile y conserva el JSON guardado`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody(Gson().toJson(orden().body())))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Gson().toJson(pago().body())))
            val retrofit = NetworkModule.provideRetrofit(OkHttpClient(), server.url("/api/v1/").toString())
            val apiReal = retrofit.create(VentaGuardadaApi::class.java)
            val guardada = guardar()

            assertThat(SubirVentaGuardadaUseCase(apiReal, db.pendingPaymentDao(), context)(guardada.toQueuedPayment()))
                .isEqualTo(SyncOutcome.Synced)

            val crear = server.takeRequest()
            assertThat(crear.path).isEqualTo("/api/v1/mobile/venues/venue-1/orders")
            assertThat(crear.body.readUtf8()).isEqualTo(venta.pedidoJson)
            val pagar = server.takeRequest()
            assertThat(pagar.path).isEqualTo("/api/v1/mobile/venues/venue-1/orders/$orderId/pay")
            val body = JsonParser.parseString(pagar.body.readUtf8()).asJsonObject
            assertThat(body["amount"].asInt).isEqualTo(10000)
            assertThat(body["idempotencyKey"].asString).isEqualTo(llave)
            assertThat(body.has("edadDeLaVentaMs")).isFalse()
        } finally { server.shutdown() }
    }

    @Test fun `crear orden y perder la respuesta del pago conserva orderId y el reintento solo paga`() = runTest {
        val guardada = guardar()
        coEvery { api.pagar("venue-1", orderId, any()) } throws IOException("se cortó la red")
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        assertThat(fila().orderId).isEqualTo(orderId)

        coEvery { api.pagar("venue-1", orderId, any()) } returns pago()
        assertThat(subir(fila().toQueuedPayment())).isEqualTo(SyncOutcome.Synced)

        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
        coVerify(exactly = 2) { api.pagar(any(), orderId, match { it.idempotencyKey == llave && it.amount == 10000 }) }
    }

    @Test fun `crear orden con 422 es permanente y no intenta pagar`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns error(422)
        assertThat(subir(guardada.toQueuedPayment())).isInstanceOf(SyncOutcome.Permanent::class.java)
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `un pago con 409 se reintenta con la orden ya guardada`() = runTest {
        val guardada = guardar()
        coEvery { api.pagar(any(), any(), any()) } returns error(409)
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        assertThat(fila().orderId).isEqualTo(orderId)
    }

    @Test fun `un 2xx sin id de orden no acredita una venta subida`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns json("""{"success":true,"order":{"orderNumber":"V-101","total":100}}""")
        assertThat(subir(guardada.toQueuedPayment())).isInstanceOf(SyncOutcome.Permanent::class.java)
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `un 2xx de pago sin evidencia de pago no se cierra como exito`() = runTest {
        val guardada = guardar()
        coEvery { api.pagar(any(), any(), any()) } returns json("""{"success":true,"payment":{}}""")
        assertThat(subir(guardada.toQueuedPayment())).isInstanceOf(SyncOutcome.Permanent::class.java)
    }

    @Test fun `diferencia de total queda en revision aunque el pago si se registre`() = runTest {
        val guardada = guardar()
        coEvery { api.pagar(any(), any(), any()) } returns pago(total = 12000)
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
        assertThat(fila().lastError).startsWith("DIFERENCIA_DE_COBRO:")
        db.pendingPaymentDao().markSynced(guardada.id, guardada.claimToken!!)
        db.pendingPaymentDao().deleteOldSyncedPayments(Long.MAX_VALUE)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `dos renglones con diferencias que se compensan requieren revision`() = runTest {
        val dos = venta.copy(renglones = listOf(
            RenglonDeTicket("$llave:0", "Blusa", 1, 5000, 5000, emptyList(), null),
            RenglonDeTicket("$llave:1", "Falda", 1, 5000, 5000, emptyList(), null),
        ))
        val guardada = guardar(dos)
        coEvery { api.crearOrden(any(), any()) } returns orden(items = item(total = "70") + "," + item("$llave:1", "30"))
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `una linea sin pareja del servidor requiere revision aunque el total coincida`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns orden(items = item(id = "otra-linea"))
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `una linea local ausente del servidor requiere revision`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns orden(items = "")
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `la revision se guarda con la orden antes de pagar y sobrevive al fallo y al reintento`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns orden(items = item(total = "120"))
        coEvery { api.pagar(any(), any(), any()) } coAnswers {
            assertThat(fila().orderId).isEqualTo(orderId)
            assertThat(fila().requiereRevision).isTrue()
            throw IOException("sin red")
        }
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        db.pendingPaymentDao().release(guardada.id, guardada.claimToken!!, 1, "sin red")
        db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET sync_status='SYNCING', claim_token='nuevo' WHERE id=?", arrayOf(guardada.id))
        coEvery { api.pagar(any(), any(), any()) } returns pago()

        assertThat(subir(fila().toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
        assertThat(fila().lastError).startsWith("DIFERENCIA_DE_COBRO:")
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
    }

    @Test fun `una orden recuperada con un extra distinto conserva el dinero y exige revision`() = runTest {
        val conExtra = venta.copy(renglones = listOf(venta.renglones.single().copy(
            extras = listOf(ExtraDeTicket("m-1", "Bolsa", 500, "MULTIPLE_CHOICE")),
        )))
        val guardada = guardar(conExtra)
        coEvery { api.crearOrden(any(), any()) } returns orden(items = item(total = "110", extras = """[{"id":"m-1","name":"Bolsa","price":15}]"""))
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `una cortesia en cero ya PAID se sincroniza sin crear otro pago`() = runTest {
        val guardada = guardar(venta.copy(totalCents = 0, renglones = listOf(venta.renglones.single().copy(totalCents = 0))))
        coEvery { api.crearOrden(any(), any()) } returns orden(total = "0", status = "PAID")
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isFalse()
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `morir tras guardar la orden en cero PAID recupera el cierre sin intentar pagarla otra vez`() = runTest {
        val guardada = guardar(venta.copy(totalCents = 0, renglones = listOf(venta.renglones.single().copy(totalCents = 0))))
        coEvery { api.crearOrden(any(), any()) } returns orden(total = "0", status = "PAID")
        coEvery { api.pagar(any(), any(), any()) } returns error(400) // Backend: Order is already paid, no hay Payment de esta llave.
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().orderId).isEqualTo(orderId)
        assertThat(fila().syncStatus).isEqualTo("SYNCING") // Muere antes del markSynced del consumidor.
        db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET claim_token='reclamo-recuperado' WHERE id=?", arrayOf(guardada.id))

        assertThat(subir(fila().toQueuedPayment())).isEqualTo(SyncOutcome.Synced)

        assertThat(fila().requiereRevision).isFalse()
        coVerify(exactly = 2) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `una venta en cero con orden guardada aun PENDING si registra su pago`() = runTest {
        val guardada = guardar(venta.copy(totalCents = 0, renglones = listOf(venta.renglones.single().copy(totalCents = 0))))
        db.pendingPaymentDao().guardarOrdenDeVentaGuardada(guardada.id, guardada.claimToken!!, orderId, "V-101", false)
        coEvery { api.crearOrden(any(), any()) } returns orden(total = "0", status = "PENDING")
        coEvery { api.pagar(any(), any(), any()) } returns pago(total = 0, amount = 0)

        assertThat(subir(fila().toQueuedPayment())).isEqualTo(SyncOutcome.Synced)

        assertThat(fila().requiereRevision).isFalse()
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
        coVerify(exactly = 1) { api.pagar("venue-1", orderId, match { it.amount == 0 && it.tip == 0 }) }
    }

    @Test fun `recuperar una venta en cero con otro id de orden conserva el id durable y no paga`() = runTest {
        val guardada = guardar(venta.copy(totalCents = 0, renglones = listOf(venta.renglones.single().copy(totalCents = 0))))
        db.pendingPaymentDao().guardarOrdenDeVentaGuardada(guardada.id, guardada.claimToken!!, orderId, "V-101", false)
        val distinta = orden(total = "0", status = "PAID").body()!!.deepCopy()
        distinta["order"].asJsonObject.addProperty("id", "c000000000000000000000003")
        coEvery { api.crearOrden(any(), any()) } returns Response.success(distinta)
        coEvery { api.pagar(any(), any(), any()) } returns error(400)

        assertThat(subir(fila().toQueuedPayment())).isInstanceOf(SyncOutcome.Permanent::class.java)

        assertThat(fila().orderId).isEqualTo(orderId)
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `una venta de cien ya PAID por otro aparato si registra el efectivo de la cola`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns orden(status = "PAID")
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        coVerify(exactly = 1) { api.pagar("venue-1", orderId, match { it.amount == 10000 }) }
    }

    @Test fun `priceChanges de dos extras en una linea exige revision aun con el mismo total`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } returns orden(priceChanges = """[
            {"externalId":"$llave:0","modifierId":"extra-1","displayedPrice":1000,"currentPrice":1500},
            {"externalId":"$llave:0","modifierId":"extra-2","displayedPrice":1000,"currentPrice":500}]""")
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `la propina y el total devuelto se comparan ambos en centavos`() = runTest {
        val guardada = guardar(propina = 575)
        coEvery { api.pagar(any(), any(), any()) } returns pago(total = 10575, tip = 575)
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isFalse()
        coVerify { api.pagar(any(), any(), match { it.amount == 10000 && it.tip == 575 }) }
    }

    @Test fun `total tolera un centavo por pieza y cualquier diferencia mayor exige revision`() = runTest {
        val guardada = guardar(venta.copy(renglones = listOf(venta.renglones.single().copy(cantidad = 2))))
        coEvery { api.pagar(any(), any(), any()) } returns pago(total = 10002)
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isFalse()
        coEvery { api.pagar(any(), any(), any()) } returns pago(total = 10003)
        assertThat(subir(fila().toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(fila().requiereRevision).isTrue()
    }

    @Test fun `mismo arranque envia edad del reloj monotono sin usar la hora de pared`() = runTest {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500L))
        val inicio = SystemClock.elapsedRealtime() - 300L
        val guardada = guardar(reloj = inicio, arranque = 3)
        val request = slot<PagoDeVentaGuardadaRequest>()
        coEvery { api.pagar(any(), any(), capture(request)) } returns pago()
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(Gson().toJsonTree(request.captured).asJsonObject["edadDeLaVentaMs"].asLong).isEqualTo(300L)
    }

    @Test fun `reinicio entre venta y subida omite la edad y conserva la hora capturada`() = runTest {
        val guardada = guardar(reloj = 100L, arranque = 2)
        val request = slot<PagoDeVentaGuardadaRequest>()
        coEvery { api.pagar(any(), any(), capture(request)) } returns pago()
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        val body = Gson().toJsonTree(request.captured).asJsonObject
        assertThat(body.has("edadDeLaVentaMs")).isFalse()
        assertThat(body["createdAtLocal"].asLong).isEqualTo(1_760_000_000_000L)
    }

    @Test fun `una fila sin reloj confiable omite la edad`() = runTest {
        val guardada = guardar()
        val request = slot<PagoDeVentaGuardadaRequest>()
        coEvery { api.pagar(any(), any(), capture(request)) } returns pago()
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Synced)
        assertThat(Gson().toJsonTree(request.captured).asJsonObject.has("edadDeLaVentaMs")).isFalse()
    }

    @Test fun `si otro reclamo gano antes de subir no se llama la red`() = runTest {
        val guardada = guardar()
        db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET claim_token='otro' WHERE id=?", arrayOf(guardada.id))
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `si se pierde el claim al crear orden no se paga bajo el claim ajeno`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } coAnswers {
            db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET claim_token='otro' WHERE id=?", arrayOf(guardada.id))
            orden()
        }
        assertThat(subir(guardada.toQueuedPayment())).isEqualTo(SyncOutcome.Retryable)
        assertThat(fila().orderId).isNull()
        assertThat(fila().claimToken).isEqualTo("otro")
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `cancelar una subida conserva la fila y propaga la cancelacion`() = runTest {
        val guardada = guardar()
        coEvery { api.crearOrden(any(), any()) } throws CancellationException("proceso detenido")
        try {
            subir(guardada.toQueuedPayment())
            throw AssertionError("La cancelación no se propagó")
        } catch (_: CancellationException) { }
        assertThat(fila().syncStatus).isEqualTo("SYNCING")
    }
}
