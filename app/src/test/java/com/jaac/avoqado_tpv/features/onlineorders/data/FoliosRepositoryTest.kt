package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal

/** El repositorio REAL (Retrofit + Gson) contra un servidor de mentiras: lo que se prueba es el JSON de verdad. */
class FoliosRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: FoliosRepository
    private val device = mockk<DeviceInfoManager>()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        every { device.getSerialNumber() } returns "AVQD-N86-001"
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OnlineOrdersApiService::class.java)
        repo = FoliosRepository(api, device)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val respuesta = """
        {"success":true,"data":{
          "serverTime":"2026-11-06T18:30:00.000Z","enabled":true,"requiresAcceptance":false,"showTile":true,"pollSeconds":30,
          "campoNuevoQueNoConocemos":{"x":1},
          "counts":{"toPay":2,"toDeliver":1},
          "toPay":[
            {"orderId":"cord1","onlineOrderId":"coo1","orderNumber":"W7KQ2","status":"PENDING_ACCEPTANCE","paymentMode":"PAY_AT_PICKUP",
             "customerName":"María","customerPhone":null,"note":"sin azúcar","createdAt":"2026-11-06T18:00:00.000Z",
             "acceptDeadlineAt":"2026-11-06T18:30:00.000Z","estimatedReadyAt":null,"total":186.1,"remaining":66.10,"paymentStatus":"PARTIAL",
             "cardStatus":null,"card":null,"rejectReason":null,"requiresAcceptance":false,"channel":"QR","needsPrint":false,
             "otroCampo":"ignorado",
             "items":[{"productId":"p1","categoryId":"c1","name":"Flautas","quantity":2,"note":null,
                       "modifiers":[{"name":"Salsa verde","quantity":1}]}]},
            {"orderId":"cord2","orderNumber":"W9D5F","status":"PENDING_ACCEPTANCE","customerName":"Pau","createdAt":"2026-11-06T18:05:00.000Z",
             "acceptDeadlineAt":"2026-11-06T18:35:00.000Z","total":0.1,"remaining":0.1,"paymentStatus":"PENDING","items":[]}
          ],
          "toDeliver":[
            {"orderId":"cord3","onlineOrderId":"coo3","orderNumber":"W8M2C","status":"ACCEPTED","customerName":"Leo",
             "createdAt":"2026-11-06T18:01:00.000Z","acceptDeadlineAt":"2026-11-06T18:31:00.000Z","total":69,"remaining":0,"paidAt":"2026-11-06T18:05:00.000Z",
             "paymentStatus":"PAID","needsPrint":true,"items":[]}
          ],
          "nextCursors":{"toPay":null,"toDeliver":"2026-11-06T18:00:05.000Z_coo100"},
          "store":{"status":"OPEN","reason":null,"pausedUntil":null,"manualPause":false}
        }}
    """.trimIndent()

    @Test
    fun `parsea toPay y toDeliver con saldo en centavos exactos`() = runTest {
        server.enqueue(json(respuesta))

        val pagina = repo.listar("v1", puedeCobrar = true).getOrThrow()

        assertThat(pagina.showTile).isTrue()
        assertThat(pagina.pollSeconds).isEqualTo(30)
        assertThat(pagina.conteo.porPagar).isEqualTo(2)
        assertThat(pagina.conteo.porEntregar).isEqualTo(1)
        val maria = pagina.porPagar[0]
        assertThat(maria.orderId).isEqualTo("cord1")
        assertThat(maria.folio).isEqualTo("W7KQ2")
        assertThat(maria.nombre).isEqualTo("María")
        assertThat(maria.total).isEqualTo(BigDecimal("186.10"))
        assertThat(maria.falta).isEqualTo(BigDecimal("66.10"))
        assertThat(maria.faltaCentavos).isEqualTo(6610L)
        assertThat(maria.totalCentavos).isEqualTo(18610L)
        assertThat(maria.abono).isEqualTo(BigDecimal("120.00"))
        assertThat(maria.tieneAbono).isTrue()
        assertThat(maria.productos.single().extras.single().nombre).isEqualTo("Salsa verde")
        // 0.1 pesos son 10 centavos, no 9 ni 11 (nada pasa por Double)
        assertThat(pagina.porPagar[1].faltaCentavos).isEqualTo(10L)
        assertThat(pagina.porPagar[1].tieneAbono).isFalse()
        val leo = pagina.porEntregar.single()
        assertThat(leo.faltaCentavos).isEqualTo(0L)
        assertThat(leo.necesitaImprimir).isTrue()
        // paidAt opcional: presente en Leo, ausente (null) en los que no lo traen
        assertThat(leo.pagadoEn?.toString()).isEqualTo("2026-11-06T18:05:00Z")
        assertThat(maria.pagadoEn).isNull()
        assertThat(pagina.siguientes).containsExactly(SeccionDeFolios.POR_ENTREGAR, "2026-11-06T18:00:05.000Z_coo100")
        assertThat(pagina.horaDelServidor?.toString()).isEqualTo("2026-11-06T18:30:00Z")
    }

    @Test
    fun `un campo desconocido se ignora`() = runTest {
        server.enqueue(json(respuesta))
        // si Gson fallara con campos nuevos, listar() sería failure; llegar aquí ya lo prueba
        val pagina = repo.listar("v1", puedeCobrar = false).getOrThrow()
        assertThat(pagina.porPagar).hasSize(2)
    }

    @Test
    fun `cargarMas pide la sección y el cursor`() = runTest {
        server.enqueue(json(respuesta))

        repo.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "2026-11-06T18:00:05.000Z_coo100").getOrThrow()

        val pedida = server.takeRequest()
        assertThat(pedida.requestUrl!!.encodedPath).isEqualTo("/api/v1/tpv/venues/v1/online-orders/to-pay")
        assertThat(pedida.requestUrl!!.queryParameter("section")).isEqualTo("toDeliver")
        assertThat(pedida.requestUrl!!.queryParameter("cursor")).isEqualTo("2026-11-06T18:00:05.000Z_coo100")
        // paginar nunca late
        assertThat(pedida.getHeader("x-online-orders-to-pay")).isNull()
    }

    @Test
    fun `la cabecera de latido va solo cuando la terminal puede cobrar`() = runTest {
        server.enqueue(json(respuesta))
        server.enqueue(json(respuesta))

        repo.listar("v1", puedeCobrar = true).getOrThrow()
        repo.listar("v1", puedeCobrar = false).getOrThrow()

        assertThat(server.takeRequest().getHeader("x-online-orders-to-pay")).isEqualTo("1")
        assertThat(server.takeRequest().getHeader("x-online-orders-to-pay")).isNull()
    }

    @Test
    fun `las llamadas de impresion mandan x-device-id con la serie`() = runTest {
        server.enqueue(json("""{"success":true,"data":{"claimed":true}}"""))
        server.enqueue(json("""{"success":true,"data":{"ok":false}}"""))

        assertThat(repo.reclamarImpresion("v1", "cord3").getOrThrow()).isTrue()
        assertThat(repo.confirmarImpresion("v1", "cord3").getOrThrow()).isFalse()

        val claim = server.takeRequest()
        assertThat(claim.path).isEqualTo("/api/v1/tpv/venues/v1/online-orders/cord3/print/claim")
        assertThat(claim.getHeader("x-device-id")).isEqualTo("AVQD-N86-001")
        assertThat(server.takeRequest().getHeader("x-device-id")).isEqualTo("AVQD-N86-001")
    }

    @Test
    fun `un error del servidor conserva el codigo`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
                .setBody("""{"success":false,"code":"PAYMENT_REQUIRED","error":"Cobra el pedido antes de entregarlo."}"""),
        )

        val error = repo.entregado("v1", "cord3").exceptionOrNull() as BackendHttpException

        assertThat(error.statusCode).isEqualTo(409)
        assertThat(error.errorCode).isEqualTo("PAYMENT_REQUIRED")
        assertThat(server.takeRequest().path).isEqualTo("/api/v1/tpv/venues/v1/online-orders/cord3/delivered")
    }

    /** R76: precio por renglón opcional, en pesos. Si falta queda null; un valor raro no tumba la lista. */
    @Test
    fun `precio por renglon opcional y leido sin tumbar la lista`() = runTest {
        server.enqueue(
            json(
                """
                {"success":true,"data":{"showTile":true,"counts":{"toPay":1,"toDeliver":0},"toDeliver":[],
                  "toPay":[{"orderId":"cord1","orderNumber":"W7K3P","customerName":"Ana","total":134,"remaining":134,"items":[
                    {"name":"Latte","quantity":1,"unitPrice":65,"total":65.00,"modifiers":[{"name":"Leche de almendra","quantity":1}]},
                    {"name":"Shake de proteína","quantity":2,"unitPrice":"34.50"},
                    {"name":"Galleta","quantity":1},
                    {"name":"Raro","quantity":1,"unitPrice":"abc","total":{"x":1}},
                    {"name":"Nulo","quantity":1,"unitPrice":null,"total":null}
                  ]}]}}
                """.trimIndent(),
            ),
        )

        val renglones = repo.listar("v1", puedeCobrar = false).getOrThrow().porPagar.single().productos

        assertThat(renglones[0].precioUnitario).isEqualTo(BigDecimal("65.00"))
        assertThat(renglones[0].total).isEqualTo(BigDecimal("65.00"))
        assertThat(renglones[0].precioDelRenglon).isEqualTo(BigDecimal("65.00"))
        // Sin total: la pieza por la cantidad.
        assertThat(renglones[1].total).isNull()
        assertThat(renglones[1].precioDelRenglon).isEqualTo(BigDecimal("69.00"))
        // Un SERVIDOR sin R76, un valor raro o null: sin precio, y la lista sigue.
        listOf(renglones[2], renglones[3], renglones[4]).forEach {
            assertThat(it.precioUnitario).isNull()
            assertThat(it.total).isNull()
            assertThat(it.precioDelRenglon).isNull()
        }
    }

    @Test
    fun `aceptar manda un cuerpo vacio y cancelar usa la cuenta`() = runTest {
        server.enqueue(json("""{"success":true,"data":{}}"""))
        server.enqueue(json("""{"success":true,"data":{}}"""))

        repo.aceptar("v1", "cord1").getOrThrow()
        repo.cancelar("v1", "cord1").getOrThrow()

        val aceptar = server.takeRequest()
        assertThat(aceptar.path).isEqualTo("/api/v1/tpv/venues/v1/online-orders/cord1/accept")
        assertThat(aceptar.body.readUtf8()).isEqualTo("{}")
        assertThat(server.takeRequest().path).isEqualTo("/api/v1/tpv/venues/v1/online-orders/cord1/cancel")
    }
}
