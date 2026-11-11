package com.jaac.avoqado_tpv.features.giftcards.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.di.NetworkModule
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * P1 (tienda híbrida, parte 2): el cobro con saldo de un folio. El repositorio REAL (Retrofit + Gson + OkHttp) contra un servidor de
 * mentiras, sobre un almacén en memoria que hace las veces de `EncryptedSharedPreferences` (el mismo almacén = la misma terminal tras
 * reiniciar la app). Lo que se prueba es el contrato de la llave: el cuerpo COMPLETO se guarda antes de mandarlo, un reenvío repite
 * esos mismos bytes con la misma llave, y sólo una respuesta definitiva lo borra.
 */
class CobroConSaldoRepositoryTest {

    private lateinit var server: MockWebServer
    private val almacen = mutableMapOf<String, String>()
    private var guardarFalla = false
    /** D12 r3: el valor cifrado de los cobros con saldo ya no se deja descifrar (Keystore invalidado, respaldo restaurado). */
    private var cifradoRoto = false
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val device = mockk<DeviceInfoManager>()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        every { storage.getVenueId() } returns "v1"
        every { storage.getString(any(), any()) } answers {
            if (cifradoRoto && firstArg<String>() == ALMACEN) throw SecurityException("Could not decrypt value")
            almacen[firstArg()] ?: secondArg()
        }
        every { storage.putStringDurably(any(), any()) } answers {
            check(!guardarFalla) { "Could not persist" }
            almacen[firstArg()] = secondArg()
        }
        every { device.getSerialNumber() } returns "AVQD-N86-001"
    }

    @After
    fun tearDown() = server.shutdown()

    /** El cliente base de la app (reintenta en una conexión nueva, como el OkHttp de siempre), con tiempos cortos para las pruebas. */
    private fun base() = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()

    private fun retrofit(cliente: OkHttpClient) = Retrofit.Builder()
        .baseUrl(server.url("/api/v1/"))
        .client(cliente)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** Las APIs con el MISMO armado que la app (`NetworkModule`): consultas con el cliente de siempre, el cobro con el suyo. */
    private fun consultas(b: OkHttpClient = base()) = NetworkModule.provideGiftCardsTpvApi(retrofit(b))
    private fun cobros(b: OkHttpClient = base()) = NetworkModule.provideCobroConSaldoApi(b, server.url("/api/v1/").toString())

    /** Un repositorio nuevo sobre el MISMO almacén: es la app que se reabre después de morir. */
    private fun repo(): CobroConSaldoRepository = CobroConSaldoRepository(consultas(), cobros(), storage, device)

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun cobro(monto: String = "50.00", restante: String = "84.00", estado: String = "PARTIAL", replay: Boolean = false) = json(
        200,
        """{"success":true,"data":{"paymentId":"cpay1","orderId":"cord1","orderNumber":"W7K3P","amount":$monto,"tipAmount":0,
           "redeemed":[{"giftCardId":"cgc1","last4":"HB7T","amount":$monto,"balanceAfter":0}],
           "orderRemaining":$restante,"orderPaymentStatus":"$estado","digitalReceipt":null,"replay":$replay}}""",
    )

    private fun rechazo(http: Int, code: String?, message: String) =
        json(http, """{"success":false,"message":"$message"${code?.let { ",\"code\":\"$it\"" }.orEmpty()}}""")

    private val tarjeta = PruebaDeSaldo.Codigo("K7Q29DXM4R8HHB7TR")

    private fun RecordedRequest.cuerpo() = body.readUtf8()
    private fun llaveDe(cuerpo: String) = JsonParser.parseString(cuerpo).asJsonObject.get("idempotencyKey").asString

    // region — guardar antes, borrar con lo definitivo

    @Test
    fun `guarda el cuerpo completo antes de mandar`() = runTest {
        var guardadoAlLlegar: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                guardadoAlLlegar = almacen.values.firstOrNull()
                return cobro()
            }
        }

        repo().cobrar("cord1", tarjeta, 5000)

        val enviado = server.takeRequest()
        val cuerpo = enviado.cuerpo()
        assertThat(enviado.path).isEqualTo("/api/v1/tpv/venues/v1/gift-cards/orders/cord1/pay")
        assertThat(enviado.getHeader("x-device-id")).isEqualTo("AVQD-N86-001")
        // Lo guardado al llegar el POST ya traía ESE cuerpo, byte por byte (con su llave y su prueba).
        assertThat(guardadoAlLlegar).isNotNull()
        assertThat(guardadoAlLlegar).contains(llaveDe(cuerpo))
        val prueba = JsonParser.parseString(cuerpo).asJsonObject.getAsJsonObject("proof")
        assertThat(prueba.get("kind").asString).isEqualTo("CODE")
        assertThat(prueba.get("code").asString).isEqualTo("K7Q29DXM4R8HHB7TR")
        assertThat(llaveDe(cuerpo)).matches("[A-Za-z0-9_-]{8,64}")
    }

    @Test
    fun `200 lo borra y dice cuanto falta en centavos`() = runTest {
        server.enqueue(cobro(monto = "50.00", restante = "84.00"))
        val r = repo()

        val d = r.cobrar("cord1", tarjeta, 5000)

        assertThat(d).isEqualTo(DesenlaceDeSaldo.Cobrado(restanteCents = 8400, montoCents = 5000, last4 = "HB7T", saldoRestanteCents = 0))
        assertThat(r.pendienteDe("cord1")).isNull()
    }

    @Test
    fun `sin respuesta lo conserva con el mismo cuerpo`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = repo()

        val d = r.cobrar("cord1", tarjeta, 5000)

        assertThat(d).isEqualTo(DesenlaceDeSaldo.Incierto)
        val enviado = server.takeRequest().cuerpo()
        val pendiente = r.pendienteDe("cord1")!!
        assertThat(pendiente.cuerpo).isEqualTo(enviado)
        assertThat(pendiente.llave).isEqualTo(llaveDe(enviado))
        assertThat(pendiente.venueId).isEqualTo("v1")
    }

    @Test
    fun `si no se puede guardar en el aparato no manda nada`() = runTest {
        guardarFalla = true

        val d = repo().cobrar("cord1", tarjeta, 5000)

        assertThat(d).isInstanceOf(DesenlaceDeSaldo.NoSalio::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `404 dice que no encontro la tarjeta y lo borra`() = runTest {
        server.enqueue(rechazo(404, "GIFT_CARD_NOT_FOUND", "No encontramos esa tarjeta de regalo en este negocio. Revisa el código."))
        val r = repo()

        val d = r.cobrar("cord1", tarjeta, 5000)

        assertThat(d).isEqualTo(DesenlaceDeSaldo.NoSalio("No encontramos esa tarjeta de regalo en este negocio. Revisa el código."))
        assertThat(r.pendienteDe("cord1")).isNull()
    }

    @Test
    fun `429 y 5xx lo conservan`() = runTest {
        server.enqueue(rechazo(429, null, "Demasiados intentos"))
        val r = repo()
        assertThat(r.cobrar("cord1", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(r.pendienteDe("cord1")).isNotNull()

        server.enqueue(rechazo(503, null, "Servicio no disponible"))
        // Otro folio: su propia solicitud.
        assertThat(r.cobrar("cord2", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(r.pendienteDe("cord2")).isNotNull()
    }

    // endregion

    // region — la llave nunca cambia

    @Test
    fun `ORDER_PAYMENT_CONFLICT conserva y reintenta con la misma llave`() = runTest {
        server.enqueue(rechazo(409, "ORDER_PAYMENT_CONFLICT", "Otro cobro cambió esta cuenta al mismo tiempo. Intenta otra vez."))
        server.enqueue(cobro())

        val d = repo().cobrar("cord1", tarjeta, 5000)

        assertThat(d).isInstanceOf(DesenlaceDeSaldo.Cobrado::class.java)
        val primero = server.takeRequest().cuerpo()
        val segundo = server.takeRequest().cuerpo()
        assertThat(segundo).isEqualTo(primero)
    }

    @Test
    fun `ORDER_PAYMENT_CONFLICT siempre deja la solicitud guardada`() = runTest {
        repeat(3) { server.enqueue(rechazo(409, "ORDER_PAYMENT_CONFLICT", "Otro cobro cambió esta cuenta al mismo tiempo.")) }
        val r = repo()

        val d = r.cobrar("cord1", tarjeta, 5000)

        assertThat(d).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(r.pendienteDe("cord1")).isNotNull()
        val cuerpos = List(server.requestCount) { server.takeRequest().cuerpo() }
        assertThat(cuerpos.toSet()).hasSize(1)
    }

    @Test
    fun `codex-plan2-r1 3 al reabrir, found false mientras el primer POST sigue vivo reenvia el mismo cuerpo con la misma llave`() = runTest {
        // 1) El primer POST llega al SERVIDOR y la terminal se queda sin respuesta (la app muere o la red se cae).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThat(repo().cobrar("cord1", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        val primero = server.takeRequest().cuerpo()
        val llave = llaveDe(primero)

        // 2) La app se reabre (otro repositorio, el mismo almacén). El SERVIDOR aún no confirma el primero: `found:false`.
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        // 3) El reenvío espera al primero bajo el candado de la llave y recibe SU cobro (replay).
        server.enqueue(cobro(replay = true))
        val reabierta = repo()
        assertThat(reabierta.pendienteDe("cord1")!!.llave).isEqualTo(llave)

        val d = reabierta.resolver("cord1")

        assertThat(d).isEqualTo(DesenlaceDeSaldo.Cobrado(restanteCents = 8400, montoCents = 5000, last4 = "HB7T", saldoRestanteCents = 0))
        val consulta = server.takeRequest()
        assertThat(consulta.method).isEqualTo("GET")
        assertThat(consulta.path).isEqualTo("/api/v1/tpv/venues/v1/gift-cards/payments/$llave")
        val reenvio = server.takeRequest()
        assertThat(reenvio.method).isEqualTo("POST")
        assertThat(reenvio.cuerpo()).isEqualTo(primero)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(reabierta.pendienteDe("cord1")).isNull()
    }

    @Test
    fun `al reabrir, found true es el cobro y no se reenvia`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        repo().cobrar("cord1", tarjeta, 13400)
        server.takeRequest()
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"found":true,"payment":{"paymentId":"cpay1","orderId":"cord1","orderNumber":"W7K3P",
                   "amount":134,"tipAmount":0,"redeemed":[{"giftCardId":"cgc1","last4":"HB7T","amount":134,"balanceAfter":216}],
                   "orderRemaining":0,"orderPaymentStatus":"PAID"}}}""",
            ),
        )
        val reabierta = repo()

        val d = reabierta.resolver("cord1")

        assertThat(d).isEqualTo(DesenlaceDeSaldo.Cobrado(restanteCents = 0, montoCents = 13400, last4 = "HB7T", saldoRestanteCents = 21600))
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(reabierta.pendienteDe("cord1")).isNull()
    }

    @Test
    fun `al reabrir sin red sigue en duda y conserva`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        repo().cobrar("cord1", tarjeta, 5000)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val reabierta = repo()

        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord1")).isNotNull()
    }

    @Test
    fun `tras la duda, un 401 o 403 del reenvio no la borra (salen antes del candado de la llave)`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        repo().cobrar("cord1", tarjeta, 5000)
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(rechazo(401, null, "Token expirado"))
        val reabierta = repo()

        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord1")).isNotNull()

        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(rechazo(403, null, "Sin permiso"))
        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord1")).isNotNull()
    }

    @Test
    fun `Codex r1 1 un 401 o 403 del primer envio tampoco la borra (OkHttp pudo reenviar una copia que ya cobro)`() = runTest {
        server.enqueue(rechazo(401, null, "Token expirado"))
        val r = repo()
        assertThat(r.cobrar("cord1", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(r.pendienteDe("cord1")).isNotNull()

        server.enqueue(rechazo(403, null, "Sin permiso"))
        assertThat(r.cobrar("cord2", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(r.pendienteDe("cord2")).isNotNull()
    }

    @Test
    fun `Codex r3 1 tras una respuesta perdida, un 426 de actualizacion obligatoria tampoco la borra`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        repo().cobrar("cord1", tarjeta, 5000)
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(json(426, """{"success":false,"error":"UPGRADE_REQUIRED","message":"Esta versión ya no es compatible."}"""))
        val reabierta = repo()

        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord1")).isNotNull()
        // Y en el primer envío, igual.
        server.enqueue(json(426, """{"success":false,"error":"UPGRADE_REQUIRED","message":"Esta versión ya no es compatible."}"""))
        assertThat(reabierta.cobrar("cord2", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord2")).isNotNull()
    }

    @Test
    fun `Codex r4 y r5 F1 tras una duda, ningun 4xx del reenvio la borra, ni con el tiempo, solo un cobro confirmado`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        repo().cobrar("cord1", tarjeta, 5000)
        // La cajera fue dada de baja: `validateStaffVenue` contesta 400 sin código ANTES del candado de la llave.
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(rechazo(400, null, "El usuario no tiene acceso a este negocio"))
        val reabierta = repo()
        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord1")).isNotNull()

        // Aunque la solicitud sea vieja (el reloj del aparato no prueba nada), un rechazo definitivo de la lógica tampoco la borra.
        almacen.keys.forEach { k -> almacen[k] = almacen[k]!!.replace(Regex("\"creadaEn\":\\d+"), "\"creadaEn\":1") }
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        server.enqueue(rechazo(422, "GIFT_CARD_INSUFFICIENT_BALANCE", "Saldo insuficiente"))
        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(reabierta.pendienteDe("cord1")).isNotNull()

        // Sólo el cobro confirmado la cierra (la copia anterior sí había cobrado).
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"found":true,"payment":{"paymentId":"cpay1","orderId":"cord1","amount":50,"tipAmount":0,
                   "redeemed":[{"last4":"HB7T","balanceAfter":0}],"orderRemaining":84,"orderPaymentStatus":"PARTIAL"}}}""",
            ),
        )
        assertThat(reabierta.resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Cobrado(8400, 5000, "HB7T", 0))
        assertThat(reabierta.pendienteDe("cord1")).isNull()
    }

    @Test
    fun `Codex r5 el cliente de esta API no reenvia solo un 503 con Retry-After 0 ni sigue redirecciones`() = runTest {
        // Si OkHttp reenviara solo, la segunda copia se quedaría sin respuesta (cola vacía) y el conteo sería 2.
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}"))
        val r = repo()
        assertThat(r.cobrar("cord1", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(server.requestCount).isEqualTo(1)

        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/api/v1/otra"))
        assertThat(r.cobrar("cord2", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `IDEMPOTENCY_KEY_REUSED consulta la llave y nunca manda otra`() = runTest {
        server.enqueue(rechazo(409, "IDEMPOTENCY_KEY_REUSED", "Esta llave ya registró otro cobro."))
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))
        val r = repo()

        assertThat(r.cobrar("cord1", tarjeta, 5000)).isEqualTo(DesenlaceDeSaldo.Incierto)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(r.pendienteDe("cord1")).isNotNull()
    }

    @Test
    fun `con una solicitud pendiente del folio, otro cobro con saldo no arranca`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = repo()
        r.cobrar("cord1", tarjeta, 5000)
        val antes = server.requestCount

        val otra = r.cobrar("cord1", PruebaDeSaldo.Codigo("M3TX8KQ42WZN7RJCW"), 5000)

        assertThat(otra).isEqualTo(DesenlaceDeSaldo.NoSalio(TextosDeSaldo.COMPROBANDO))
        assertThat(server.requestCount).isEqualTo(antes)
    }

    @Test
    fun `Codex r2 1 el cobro remoto del POS no entra sobre una cuenta con saldo sin confirmar ni mientras se cobra con saldo`() = runTest {
        val r = repo()
        assertThat(r.motivoParaNoCobrarRemoto("cord1")).isNull()

        // Mientras el cobro con saldo está en el aire: ninguna cuenta.
        server.enqueue(cobro().setBodyDelay(1, TimeUnit.SECONDS))
        val enCurso = async(Dispatchers.Default) { r.cobrar("cord1", tarjeta, 5000) }
        server.takeRequest(5, TimeUnit.SECONDS)
        assertThat(r.motivoParaNoCobrarRemoto("otra")).isEqualTo(TextosDeSaldo.REMOTO_COBRANDO_CON_SALDO)
        assertThat(enCurso.await()).isInstanceOf(DesenlaceDeSaldo.Cobrado::class.java)
        assertThat(r.motivoParaNoCobrarRemoto("cord1")).isNull()

        // Con un cobro con saldo sin confirmar: esa cuenta no; las demás sí.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        r.cobrar("cord1", tarjeta, 5000)
        assertThat(r.motivoParaNoCobrarRemoto("cord1")).isEqualTo(TextosDeSaldo.REMOTO_SALDO_SIN_CONFIRMAR)
        assertThat(r.motivoParaNoCobrarRemoto("cord2")).isNull()
        assertThat(r.motivoParaNoCobrarRemoto(null)).isNull()
    }

    @Test
    fun `Codex r2 2 la solicitud guardada nunca imprime su cuerpo (trae el codigo)`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = repo()
        r.cobrar("cord1", tarjeta, 5000)

        assertThat(r.pendienteDe("cord1").toString()).doesNotContain("K7Q29DXM4R8HHB7TR")
        assertThat(PruebaDeSaldo.Codigo("K7Q29DXM4R8HHB7TR").toString()).doesNotContain("K7Q2")
    }

    // region — full-testing en la N86 (H1, H2, H4)

    @Test
    fun `H1 una consulta por una conexion que el SERVIDOR ya cerro reintenta en otra y no dice Sin conexion`() = runTest {
        // El SERVIDOR cierra la conexión después de contestar (como Node tras 5 s sin uso); el cliente la deja en su pool.
        val api = consultas()
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}""").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(json(200, """{"success":true,"data":{"found":false}}"""))

        assertThat(api.cobroDeLaLlave("v1", "llave-1").isSuccessful).isTrue()
        // La segunda sale por la conexión muerta del pool: «unexpected end of stream» si no reintenta.
        assertThat(api.cobroDeLaLlave("v1", "llave-1").isSuccessful).isTrue()
    }

    @Test
    fun `H1 cada cobro sale por una conexion nueva, nunca por una dormida del pool`() = runTest {
        val api = cobros()
        server.enqueue(cobro())
        server.enqueue(cobro())
        val cuerpo = """{"idempotencyKey":"k1","proof":{"kind":"CODE","code":"K7Q29DXM4R8HHB7TR"},"amount":50.00,"tip":0}"""
        val tipo = "application/json".toMediaType()

        api.cobrar("v1", "cord1", "AVQD-1", cuerpo.toRequestBody(tipo)).body()
        api.cobrar("v1", "cord1", "AVQD-1", cuerpo.toRequestBody(tipo)).body()

        // sequenceNumber = el lugar de la petición en su conexión: 0 = conexión nueva.
        assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
        assertThat(server.takeRequest().sequenceNumber).isEqualTo(0)
    }

    @Test
    fun `H2 los folios con un cobro con saldo sin desenlace se leen de una vez, sólo de este negocio`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = repo()
        r.cobrar("cord1", tarjeta, 5000)
        r.cobrar("cord2", tarjeta, 5000)

        assertThat(r.foliosPendientes()).containsExactly("cord1", "cord2")
        every { storage.getVenueId() } returns "otro"
        assertThat(r.foliosPendientes()).isEmpty()
    }

    @Test
    fun `H4 un folio vencido o ya pagado por otra caja dice lo del mockup y cierra el cobro`() = runTest {
        server.enqueue(rechazo(400, "ORDER_CANCELLED_NO_NEW_CHARGE", "Esta cuenta está cancelada, abre una nueva."))
        val r = repo()
        assertThat(r.cobrar("cord1", tarjeta, 5000))
            .isEqualTo(DesenlaceDeSaldo.NoSalio(TextosDeSaldo.FOLIO_VENCIDO, FolioCerrado.VENCIDO))

        server.enqueue(rechazo(400, null, "Order is already paid"))
        assertThat(r.cobrar("cord2", tarjeta, 5000))
            .isEqualTo(DesenlaceDeSaldo.NoSalio(TextosDeSaldo.FOLIO_YA_PAGADO, FolioCerrado.PAGADO))
        assertThat(TextosDeSaldo.FOLIO_VENCIDO).startsWith("Este pedido ya venció")
        assertThat(TextosDeSaldo.FOLIO_YA_PAGADO).contains("Lo cobró otra caja")
    }

    // endregion

    @Test
    fun `sin solicitud pendiente, resolver no pregunta nada`() = runTest {
        assertThat(repo().resolver("cord1")).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // endregion

    // region — dinero y consulta

    @Test
    fun `montos en centavos exactos`() = runTest {
        server.enqueue(cobro(monto = "50.1", restante = "84.1"))

        val d = repo().cobrar("cord1", tarjeta, 5010)

        val amount = JsonParser.parseString(server.takeRequest().cuerpo()).asJsonObject
        assertThat(amount.get("amount").asBigDecimal.compareTo(BigDecimal("50.10"))).isEqualTo(0)
        assertThat(amount.get("tip").asBigDecimal.signum()).isEqualTo(0)
        assertThat((d as DesenlaceDeSaldo.Cobrado).restanteCents).isEqualTo(8410)
        assertThat(d.montoCents).isEqualTo(5010)
    }

    @Test
    fun `consultar manda el codigo en el cuerpo y devuelve el saldo en centavos`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"usableBalance":350.5,"cards":[{"giftCardId":"cgc1","last4":"HB7T","balance":350.5,
                   "status":"ACTIVE","expiresAt":null,"usable":true,"notUsableReason":null}]}}""",
            ),
        )

        val c = repo().consultar(tarjeta).getOrThrow()

        assertThat(c).isEqualTo(ConsultaDeSaldo(last4 = "HB7T", saldoCents = 35050, usable = true, razon = null))
        val enviado = server.takeRequest()
        assertThat(enviado.path).isEqualTo("/api/v1/tpv/venues/v1/gift-cards/lookup")
        assertThat(JsonParser.parseString(enviado.cuerpo()).asJsonObject.get("code").asString).isEqualTo("K7Q29DXM4R8HHB7TR")
    }

    @Test
    fun `consultar una tarjeta vencida dice por que no sirve`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"usableBalance":0,"cards":[{"last4":"HB7T","balance":80,"usable":false,"notUsableReason":"EXPIRED"}]}}""",
            ),
        )

        val c = repo().consultar(tarjeta).getOrThrow()

        assertThat(c.usable).isFalse()
        assertThat(c.saldoCents).isEqualTo(0)
        assertThat(c.razon).isEqualTo("Vencida")
    }

    @Test
    fun `consultar sin red lo dice`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val fallo = repo().consultar(tarjeta).exceptionOrNull() as FalloDeSaldo

        assertThat(fallo.sinRed).isTrue()
    }

    // endregion

    // region D12 — lo que el Inicio sin red necesita para avisar: el folio y el monto, guardados antes de mandar

    @Test
    fun `D12 la duda guarda el folio y el monto y se leen al reabrir la app`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertThat(repo().cobrar("cord1", tarjeta, 5000, folio = "W7K3P")).isEqualTo(DesenlaceDeSaldo.Incierto)

        // Otra instancia sobre el mismo almacén = la app que se reabre sin red.
        val guardada = repo().pendientes()!!.single()
        assertThat(guardada.orderId).isEqualTo("cord1")
        assertThat(guardada.folio).isEqualTo("W7K3P")
        assertThat(guardada.montoCents).isEqualTo(5000)
    }

    @Test
    fun `D12 el folio va guardado ANTES de mandar`() = runTest {
        var guardadoAlLlegar: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                guardadoAlLlegar = almacen.values.firstOrNull()
                return cobro()
            }
        }

        repo().cobrar("cord1", tarjeta, 5000, folio = "W7K3P")

        assertThat(guardadoAlLlegar).contains("W7K3P")
    }

    @Test
    fun `D12 una guardada por la version anterior (sin folio) se lee con su monto`() {
        almacen[ALMACEN] =
            """[{"llave":"k1234567","venueId":"v1","orderId":"cord1","cuerpo":"{\"idempotencyKey\":\"k1234567\",\"amount\":134.50,\"tip\":0}","creadaEn":1}]"""

        val guardada = repo().pendientes()!!.single()

        assertThat(guardada.folio).isNull()
        assertThat(guardada.montoCents).isEqualTo(13450)
    }

    @Test
    fun `D12 sólo las de este negocio`() {
        almacen[ALMACEN] =
            """[{"llave":"k1234567","venueId":"otro","orderId":"cord1","cuerpo":"{}","creadaEn":1,"folio":"W1111"},""" +
            """{"llave":"k7654321","venueId":"v1","orderId":"cord2","cuerpo":"{}","creadaEn":1,"folio":"W2222"}]"""

        val guardadas = repo().pendientes()!!

        assertThat(guardadas.map { it.folio }).containsExactly("W2222")
        // Un cuerpo sin monto no tumba el aviso: sale sin monto.
        assertThat(guardadas.single().montoCents).isNull()
    }

    // endregion

    // region D12 r3 — pasada de la familia «almacén ilegible»: un solo lector que dice «no se sabe» y nunca lanza

    /** Las tres formas en que el almacén deja de leerse: no se descifra, el JSON no se entiende o trae una fila incompleta. */
    private val ilegibles: List<Pair<String, () -> Unit>> = listOf(
        "no se descifra" to { almacen[ALMACEN] = "[]"; cifradoRoto = true },
        "JSON roto" to { almacen[ALMACEN] = "{esto no es json" },
        "fila incompleta" to { almacen[ALMACEN] = """[{"llave":"k1234567","venueId":"v1","orderId":"cord1"}]""" },
    )

    private inline fun conCadaIlegible(prueba: (String) -> Unit) = ilegibles.forEach { (nombre, romper) ->
        almacen.clear(); cifradoRoto = false
        romper()
        prueba(nombre)
    }

    @Test
    fun `D12r3 el lector distingue ilegible de vacio y nunca lanza`() {
        assertThat(repo().pendientes()).isEmpty()
        assertThat(repo().saldoDe("cord1")).isEqualTo(SaldoGuardado.Ninguno)
        conCadaIlegible { nombre ->
            assertWithMessage(nombre).that(repo().pendientes()).isNull()
            assertWithMessage(nombre).that(repo().saldoDe("cord1")).isEqualTo(SaldoGuardado.Ilegible)
        }
        // Tampoco si la sesión (el negocio) no se deja leer.
        almacen.clear(); cifradoRoto = false
        every { storage.getVenueId() } throws SecurityException("Could not decrypt value")
        assertThat(repo().pendientes()).isNull()
        assertThat(repo().saldoDe("cord1")).isEqualTo(SaldoGuardado.Ilegible)
    }

    @Test
    fun `D12r3 cobrar con el almacen ilegible no manda nada ni escribe encima`() = runTest {
        conCadaIlegible { nombre ->
            val antes = almacen[ALMACEN]
            val d = repo().cobrar("cord1", tarjeta, 5000, folio = "W7K3P")
            assertWithMessage(nombre).that(d).isEqualTo(DesenlaceDeSaldo.Ilegible)
            assertWithMessage(nombre).that(almacen[ALMACEN]).isEqualTo(antes)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `D12r3 comprobar con el almacen ilegible dice Ilegible sin preguntar ni reenviar`() = runTest {
        conCadaIlegible { nombre ->
            assertWithMessage(nombre).that(repo().resolver("cord1")).isEqualTo(DesenlaceDeSaldo.Ilegible)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `D12r3 el cobro remoto de una cuenta no se admite con el almacen ilegible y sin cuenta si`() {
        conCadaIlegible { nombre ->
            assertWithMessage(nombre).that(repo().motivoParaNoCobrarRemoto("cord1")).isEqualTo(TextosDeSaldo.REMOTO_SALDO_ILEGIBLE)
            assertWithMessage(nombre).that(repo().motivoParaNoCobrarRemoto(null)).isNull()
        }
    }

    @Test
    fun `D12r3 si el almacen deja de leerse a media confirmacion no borra nada ni lanza`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                cifradoRoto = true
                return cobro()
            }
        }

        val d = repo().cobrar("cord1", tarjeta, 5000, folio = "W7K3P")

        assertThat(d).isInstanceOf(DesenlaceDeSaldo.Cobrado::class.java)
        // Sólo la escritura de antes de mandar: el borrado de la solicitud ya cobrada no escribe a ciegas sobre lo que no leyó.
        io.mockk.verify(exactly = 1) { storage.putStringDurably(ALMACEN, any()) }
        cifradoRoto = false
        assertThat(repo().saldoDe("cord1")).isInstanceOf(SaldoGuardado.EnDuda::class.java)
    }

    // endregion
}

private const val ALMACEN = "tienda_hibrida_cobros_con_saldo"

/** Para las pruebas de siempre: la solicitud guardada de [orderId], o null si no hay (el lector de verdad es [CobroConSaldoRepository.saldoDe]). */
private fun CobroConSaldoRepository.pendienteDe(orderId: String): SolicitudDeSaldo? =
    (saldoDe(orderId) as? SaldoGuardado.EnDuda)?.solicitud

private fun CobroConSaldoRepository.foliosPendientes(): Set<String> = pendientes()!!.mapTo(HashSet()) { it.orderId }
