package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.MarcaDeCierre
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
    fun `P2 trae la clienta y los beneficios de la cuenta cuando vienen, en pesos exactos`() = runTest {
        server.enqueue(
            json(
                """{"success":true,"data":{"serverTime":"2026-11-06T18:30:00.000Z","showTile":true,"counts":{"toPay":2,"toDeliver":0},
                  "toPay":[
                    {"orderId":"cord1","orderNumber":"W7K3P","status":"PENDING_ACCEPTANCE","total":69,"remaining":69,
                     "cliente":{"id":"ccu1","nombre":"Ana L."},
                     "beneficios":[{"nombre":"Café gratis (premio de Ana)","monto":65},{"nombre":"Cupón","monto":"4.1"}],
                     "beneficiosTotal":12,"items":[]},
                    {"orderId":"cord2","orderNumber":"W9D5F","status":"PENDING_ACCEPTANCE","total":95,"remaining":95,
                     "cliente":null,"items":[]}
                  ],"toDeliver":[]}}""",
            ),
        )

        val pagina = repo.listar("v1", puedeCobrar = false).getOrThrow()

        val ana = pagina.porPagar[0]
        assertThat(ana.cliente).isEqualTo("Ana L.")
        assertThat(ana.beneficios.map { it.nombre to it.monto })
            .containsExactly("Café gratis (premio de Ana)" to BigDecimal("65.00"), "Cupón" to BigDecimal("4.10")).inOrder()
        assertThat(ana.beneficiosTotal).isEqualTo(12)
        // Un SERVIDOR viejo o un folio sin cuenta: nada.
        val pau = pagina.porPagar[1]
        assertThat(pau.cliente).isNull()
        assertThat(pau.beneficios).isEmpty()
        assertThat(pau.beneficiosTotal).isEqualTo(0)
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

        assertThat(repo.reclamarImpresion("v1", "cord3").getOrThrow().reclamado).isTrue()
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

    // region — D661 (Task 12): pagados con tarjeta en línea en «No, entran solos»

    @Test
    fun `FolioDto sin campos nuevos se lee como hoy`() = runTest {
        // Un SERVIDOR sin D661: ni tarjetasPorAbrir, ni comandasPorImprimir, ni marcas en las filas.
        server.enqueue(json(respuesta))

        val pagina = repo.listar("v1", puedeCobrar = false).getOrThrow()

        assertThat(pagina.tarjetasPorAbrir).isEmpty()
        assertThat(pagina.comandasPorImprimir).isEmpty()
        assertThat((pagina.porPagar + pagina.porEntregar).map { it.marca }).containsExactly(null, null, null)
        // Lo de siempre, igual.
        assertThat(pagina.porPagar.map { it.folio }).containsExactly("W7KQ2", "W9D5F").inOrder()
        assertThat(pagina.porEntregar.single().necesitaImprimir).isTrue()
        assertThat(pagina.pollSeconds).isEqualTo(30)
    }

    @Test
    fun `lee los pagados en linea por abrir, las comandas por imprimir y las marcas de cierre`() = runTest {
        server.enqueue(
            json(
                """{"success":true,"data":{"showTile":true,"pollSeconds":30,"counts":{"toPay":0,"toDeliver":4},"toPay":[],
                  "toDeliver":[
                    {"orderId":"c1","orderNumber":"W1","status":"ACCEPTED","total":10,"remaining":0,"refundRequestedAt":"2026-11-21T10:00:00.000Z","notPickedUpAt":null,"refundedAt":null,"items":[]},
                    {"orderId":"c2","orderNumber":"W2","status":"COMPLETED","total":10,"remaining":0,"refundRequestedAt":"2026-11-21T10:00:00.000Z","refundedAt":"2026-11-21T10:05:00.000Z","notPickedUpAt":"2026-11-21T10:00:05.000Z","items":[]},
                    {"orderId":"c3","orderNumber":"W3","status":"COMPLETED","total":10,"remaining":0,"notPickedUpAt":"2026-11-21T10:00:05.000Z","items":[]},
                    {"orderId":"c4","orderNumber":"W4","status":"ACCEPTED","total":10,"remaining":0,"refundRequestedAt":"no-es-fecha","items":[]}
                  ],
                  "tarjetasPorAbrir":[
                    {"orderId":"co1","orderNumber":"W5GZD","customerName":"Ana N.","total":250.5,"createdAt":"2026-11-20T18:00:00.000Z","cobrando":false},
                    {"orderId":"co2","orderNumber":"W6","customerName":"","total":0.1,"createdAt":null,"cobrando":true},
                    {"orderId":null,"orderNumber":"W7","total":1}
                  ],
                  "comandasPorImprimir":[{"orderId":"co9","orderNumber":"W9"},{"orderId":"","orderNumber":"W8"}]
                }}""",
            ),
        )

        val pagina = repo.listar("v1", puedeCobrar = false).getOrThrow()

        val ana = pagina.tarjetasPorAbrir[0]
        assertThat(ana.orderId).isEqualTo("co1")
        assertThat(ana.folio).isEqualTo("W5GZD")
        assertThat(ana.nombre).isEqualTo("Ana N.")
        assertThat(ana.total).isEqualTo(BigDecimal("250.50"))
        assertThat(ana.creadoEn?.toString()).isEqualTo("2026-11-20T18:00:00Z")
        assertThat(ana.cobrando).isFalse()
        val otra = pagina.tarjetasPorAbrir[1]
        assertThat(otra.nombre).isNull()
        assertThat(otra.total).isEqualTo(BigDecimal("0.10"))
        assertThat(otra.cobrando).isTrue()
        assertThat(pagina.tarjetasPorAbrir).hasSize(2) // sin cuenta no se puede abrir: se descarta, la lista sigue
        assertThat(pagina.comandasPorImprimir.map { it.orderId to it.folio }).containsExactly("co9" to "W9")
        assertThat(pagina.porEntregar.map { it.marca }).containsExactly(
            MarcaDeCierre.DEVOLVIENDO, MarcaDeCierre.DEVUELTO, MarcaDeCierre.NO_LO_RECOGIO, null,
        ).inOrder()
    }

    @Test
    fun `Ruling 45 la cabecera de aviso de tarjetas va solo si el vigia la pide - paginar nunca`() = runTest {
        server.enqueue(json(respuesta))
        server.enqueue(json(respuesta))
        server.enqueue(json(respuesta))

        repo.listar("v1", puedeCobrar = true, avisaTarjetas = true).getOrThrow()
        repo.listar("v1", puedeCobrar = true).getOrThrow()
        repo.cargarMas("v1", SeccionDeFolios.POR_ENTREGAR, "c1").getOrThrow()

        assertThat(server.takeRequest().getHeader("x-online-orders-card-alert")).isEqualTo("1")
        assertThat(server.takeRequest().getHeader("x-online-orders-card-alert")).isNull()
        assertThat(server.takeRequest().getHeader("x-online-orders-card-alert")).isNull()
    }

    @Test
    fun `abrir un pagado en linea manda abrirPagadoEnLinea true y aceptar sigue con el cuerpo vacio`() = runTest {
        server.enqueue(json("""{"success":true,"data":{"status":"ACCEPTED"}}"""))
        server.enqueue(json("""{"success":true,"data":{}}"""))

        repo.aceptar("v1", "co1", abrirPagadoEnLinea = true).getOrThrow()
        repo.aceptar("v1", "cord1").getOrThrow()

        val abrir = server.takeRequest()
        assertThat(abrir.path).isEqualTo("/api/v1/tpv/venues/v1/online-orders/co1/accept")
        assertThat(abrir.body.readUtf8()).isEqualTo("""{"abrirPagadoEnLinea":true}""")
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `Ruling 37 el reclamo de la comanda dice que esta caja imprime POSIBLE REPETIDA y lee posibleRepetida`() = runTest {
        server.enqueue(json("""{"success":true,"data":{"claimed":true,"posibleRepetida":true}}"""))
        server.enqueue(json("""{"success":true,"data":{"claimed":true}}"""))
        server.enqueue(json("""{"success":true,"data":{"claimed":false}}"""))

        assertThat(repo.reclamarImpresion("v1", "co9").getOrThrow()).isEqualTo(ReclamoDeComanda(reclamado = true, posibleRepetida = true))
        assertThat(repo.reclamarImpresion("v1", "co9").getOrThrow()).isEqualTo(ReclamoDeComanda(reclamado = true, posibleRepetida = false))
        assertThat(repo.reclamarImpresion("v1", "co9").getOrThrow()).isEqualTo(ReclamoDeComanda(reclamado = false, posibleRepetida = false))

        repeat(3) {
            val claim = server.takeRequest()
            assertThat(claim.getHeader("x-online-orders-card-alert")).isEqualTo("1")
            assertThat(claim.getHeader("x-device-id")).isEqualTo("AVQD-N86-001")
        }
    }

    @Test
    fun `lee un pedido por su propio camino`() = runTest {
        server.enqueue(
            json(
                """{"success":true,"data":{"orderId":"co9","orderNumber":"W9","status":"COMPLETED","customerName":"Ana","total":69,"remaining":0,
                  "needsPrint":true,"notPickedUpAt":"2026-11-21T10:00:05.000Z","items":[{"name":"Latte","quantity":2,"modifiers":[{"name":"Avena","quantity":1}]}]}}""",
            ),
        )
        server.enqueue(json("""{"success":true,"data":null}"""))

        val pedido = repo.leerPedido("v1", "co9").getOrThrow()

        assertThat(server.takeRequest().path).isEqualTo("/api/v1/tpv/venues/v1/online-orders/co9")
        assertThat(pedido.folio).isEqualTo("W9")
        assertThat(pedido.necesitaImprimir).isTrue()
        assertThat(pedido.productos.single().cantidad).isEqualTo(2)
        assertThat(pedido.productos.single().extras.single().nombre).isEqualTo("Avena")
        assertThat(pedido.marca).isEqualTo(MarcaDeCierre.NO_LO_RECOGIO)
        // Sin pedido en la respuesta: falla (nunca un pedido vacío que se imprima).
        assertThat(repo.leerPedido("v1", "co9").isFailure).isTrue()
    }

    @Test
    fun `Ruling 36 un 409 ORDER_STATE_CHANGED trae el estado real y su marca de cierre`() = runTest {
        val noLoRecogio = "Este pedido se cerró como «No lo recogió» al terminar el día: no se marca entregado. No se aplicó nada."
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
                """{"success":false,"code":"ORDER_STATE_CHANGED","status":"COMPLETED","notPickedUpAt":"2026-11-21T10:00:05.000Z","error":"$noLoRecogio"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
                """{"success":false,"code":"ORDER_STATE_CHANGED","status":"READY","error":"El pedido ya cambió de estado."}""",
            ),
        )

        val marcado = repo.entregado("v1", "co9").exceptionOrNull() as PedidoYaCambio
        val sinMarca = repo.aceptar("v1", "co9", abrirPagadoEnLinea = true).exceptionOrNull() as PedidoYaCambio

        assertThat(marcado.estado).isEqualTo("COMPLETED")
        assertThat(marcado.marca).isEqualTo(MarcaDeCierre.NO_LO_RECOGIO)
        assertThat(marcado.message).isEqualTo(noLoRecogio)
        assertThat(sinMarca.estado).isEqualTo("READY")
        assertThat(sinMarca.marca).isNull()
        assertThat(sinMarca.cerradoSinEntregar).isFalse()
    }

    @Test
    fun `Ruling 71 ORDER_CLOSED_NOT_DELIVERED se lee como un cierre marcado, con o sin marcas en el cuerpo`() = runTest {
        val devuelto = "Este pedido se devolvió: no se marca entregado. No se aplicó nada."
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
                """{"success":false,"code":"ORDER_CLOSED_NOT_DELIVERED","status":"COMPLETED","refundRequestedAt":"2026-11-21T10:00:00.000Z","refundedAt":"2026-11-21T10:00:05.000Z","error":"$devuelto"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
                """{"success":false,"code":"ORDER_CLOSED_NOT_DELIVERED","status":"COMPLETED","error":"$devuelto"}""",
            ),
        )

        val conMarca = repo.entregado("v1", "co9").exceptionOrNull() as PedidoYaCambio
        val sinMarca = repo.listo("v1", "co9").exceptionOrNull() as PedidoYaCambio

        assertThat(conMarca.estado).isEqualTo("COMPLETED")
        assertThat(conMarca.marca).isEqualTo(MarcaDeCierre.DEVUELTO)
        assertThat(conMarca.message).isEqualTo(devuelto)
        assertThat(conMarca.cerradoSinEntregar).isTrue()
        // Aun sin marcas en el cuerpo, ese código nunca es «hecho».
        assertThat(sinMarca.marca).isNull()
        assertThat(sinMarca.cerradoSinEntregar).isTrue()
        assertThat(sinMarca.message).isEqualTo(devuelto)
    }

    // endregion
}
