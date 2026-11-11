package com.jaac.avoqado_campo.cola

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.RedModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubidorTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Pedido(val path: String, val texto: String, val bytes: ByteArray, val contentType: String?)

    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private var ahora = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private lateinit var dirCola: File
    private val dirFotos by lazy { File(tmp.root, "fotos") }
    private lateinit var encolador: Encolador
    private val pedidos: MutableList<Pedido> = Collections.synchronizedList(mutableListOf())
    /** null = la respuesta normal de [porDefecto]. */
    @Volatile private var responder: (path: String, bytes: ByteArray) -> MockResponse? = { _, _ -> null }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BaseDeCampo::class.java)
            .allowMainThreadQueries().build()
        dao = db.piezaDao()
        quienSoy.guardarActivacion(
            DatosDeActivacion(
                "cel", "c".repeat(64), "s1", "Ana", Sesion("acc1", "1".repeat(64), "v1"),
                listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null)),
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                val bytes = request.body.readByteArray()
                pedidos += Pedido(path, String(bytes), bytes, request.getHeader("Content-Type"))
                return responder(path, bytes) ?: porDefecto(path, bytes)
            }
        }
        server.start()
        api = RedModule.crearRetrofit(server.url("/").toString(), RedModule.proveerCliente()).create(CampoApi::class.java)
        dirCola = File(tmp.root, "cola")
        encolador = Encolador(dao, quienSoy, RelojAnclado(almacen, { ahora }, { 0L }, { 1 }), SecuenciaDeCaptura(almacen), dirCola, "dev")
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun subidor(mutex: MutexDeSubida = mutexCompartido) = Subidor(
        dao, api, GuardiaDeSesion(api, quienSoy, MutexDeSesion()), quienSoy,
        SubidorDeFotos(RedModule.proveerCliente(), server.url("/").toString()), mutex, dirCola, dirFotos,
    ) { ahora }
    private val mutexCompartido = MutexDeSubida()

    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun error(status: Int, code: String, extra: String = "") =
        ok("""{"code":"$code","message":"m"$extra}""", status)
    private fun venta(repetida: Boolean = false) = ok(
        """{"orderId":"o1","paymentId":"pay1","saleVerificationId":"sv1","repetida":$repetida,"precio":"50.00","hora":"2026-11-10T18:00:00.000Z"}""",
        if (repetida) 200 else 201,
    )

    private fun porDefecto(path: String, bytes: ByteArray): MockResponse = when {
        path.startsWith("/v0/b/") -> ok("""{"name":"x","contentType":"image/jpeg","size":"${bytes.size}"}""")
        path.endsWith("/ventas") -> venta()
        path.endsWith("/evidencia") -> ok("""{"success":true,"verificationId":"sv1","status":"PENDING","outcome":"APPLIED","photoOutcome":"APPLIED"}""")
        path.endsWith("/checadas/entrada") -> ok("""{"timeEntryId":"te1","repetida":false,"hora":"2026-11-10T18:00:00.000Z"}""", 201)
        path.endsWith("/checadas/salida") -> ok("""{"timeEntryId":"te1","repetida":false,"hora":"2026-11-10T19:00:00.000Z"}""")
        path.endsWith("/custodia/aceptar") -> ok("""{"summary":{"total":1,"succeeded":1,"failed":0},"results":[]}""")
        else -> ok("""{"custodyState":"PROMOTER_REJECTED","repetida":false}""")
    }

    private fun de(sufijo: String) = pedidos.filter { it.path.endsWith(sufijo) }
    private fun storage() = pedidos.filter { it.path.startsWith("/v0/b/") }
    private fun json(t: String?) = JsonParser.parseString(t).asJsonObject

    private val serie = AtomicInteger()
    /** Una custodia rechazada: la pieza más simple (sin fotos ni dependencias). */
    private suspend fun pieza(venueId: String = "v1", tipo: String = TipoPieza.CUSTODIA_RECHAZAR): Pieza {
        val cuerpo = """{"serialNumber":"S${serie.incrementAndGet()}","custodyVersion":1}"""
        val id = dao.insertar(
            Pieza(tipo = tipo, llave = UUID.randomUUID().toString(), staffId = "s1", venueId = venueId, cuerpoJson = cuerpo, creadaAt = ahora, resumen = "r"),
        )
        return dao.porId(id)!!
    }

    private fun foto(): String = tmp.newFile("f-${System.nanoTime()}.jpg").also { it.writeBytes(ByteArray(321) { 9 }) }.absolutePath

    private suspend fun ventaConEvidencia(): Pair<Pieza, Pieza> {
        val llave = encolador.venta(quienSoy.generacion, 
            VentaNueva("8952140000001", BigDecimal("50"), false, "v1", listOf(EvidenciaNueva("Vinculacion", "5512345678", foto())), "SIM 1"),
        )
        val v = dao.porLlave(llave)!!
        return v to dao.siguientesParaSubir().single { it.dependeDe == llave }
    }

    private suspend fun estado(p: Pieza) = dao.porId(p.id)!!

    // ---------- el texto exacto, una sola vez ----------

    @Test fun `respuesta perdida - se repite la MISMA llave y el MISMO cuerpo byte a byte`() = runBlocking {
        val v = dao.porLlave(encolador.venta(quienSoy.generacion, VentaNueva("8952140000001", BigDecimal("50"), false, "v1", emptyList(), "SIM")))!!
        val n = AtomicInteger()
        responder = { path, _ ->
            if (path.endsWith("/ventas")) {
                if (n.getAndIncrement() == 0) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else venta(repetida = true)
            } else null
        }
        assertEquals("SIN_RED", subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.PENDIENTE, estado(v).estado)
        assertEquals(0, estado(v).intentos)

        assertEquals(1, subidor().vaciar().subidas)
        assertEquals(EstadoPieza.HECHA, estado(v).estado)
        assertTrue(json(estado(v).resultadoJson)["repetida"].asBoolean)
        assertEquals(2, de("/ventas").size)
        de("/ventas").forEach {
            assertEquals(v.cuerpoJson, it.texto)
            assertEquals("application/json; charset=utf-8", it.contentType)
        }
    }

    @Test fun `proceso muerto a mitad - el Subidor nuevo manda la misma llave, nunca otra`() = runBlocking {
        val v = dao.porLlave(encolador.venta(quienSoy.generacion, VentaNueva("8952140000001", BigDecimal("50"), false, "v1", emptyList(), "SIM")))!!
        val llego = CountDownLatch(1)
        val suelta = CountDownLatch(1)
        val n = AtomicInteger()
        responder = { path, _ ->
            if (path.endsWith("/ventas")) {
                if (n.getAndIncrement() == 0) { llego.countDown(); suelta.await(5, TimeUnit.SECONDS); venta() } else venta(repetida = true)
            } else null
        }
        val muerto = launch(Dispatchers.IO) { subidor(MutexDeSubida()).vaciar() }
        assertTrue(llego.await(5, TimeUnit.SECONDS))
        muerto.cancelAndJoin()
        suelta.countDown()
        assertEquals(EstadoPieza.PENDIENTE, estado(v).estado)

        subidor(MutexDeSubida()).vaciar()
        assertEquals(EstadoPieza.HECHA, estado(v).estado)
        assertEquals(2, de("/ventas").size)
        de("/ventas").forEach { assertEquals(v.cuerpoJson, it.texto) }
    }

    @Test fun `dos vaciados a la vez mandan cada pieza una sola vez`() = runBlocking {
        val ps = List(3) { pieza() }
        responder = { _, _ -> Thread.sleep(50); null }
        // Dos instancias (como dos trabajos de WorkManager) con el MISMO mutex de proceso.
        List(2) { val s = subidor(); async(Dispatchers.IO) { s.vaciar() } }.awaitAll()
        assertEquals(ps.map { it.cuerpoJson }, de("/custodia/rechazar").map { it.texto })
        ps.forEach { assertEquals(EstadoPieza.HECHA, estado(it).estado) }
    }

    // ---------- el orden ----------

    @Test fun `un 500 corta, y un segundo vaciado antes de su espera tampoco manda lo de atras (P1)`() = runBlocking {
        val (a, b, c) = List(3) { pieza() }
        responder = { _, _ -> MockResponse().setResponseCode(500) }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        assertEquals(1, pedidos.size)
        assertEquals(1, estado(a).intentos)
        assertEquals(ahora + 5_000, estado(a).siguienteIntentoAt)
        listOf(b, c).forEach { assertEquals(0, estado(it).intentos) }

        responder = { _, _ -> null }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        assertEquals(1, pedidos.size)

        ahora += 6_000
        assertEquals(3, subidor().vaciar().subidas)
        assertEquals(listOf(a, a, b, c).map { it.cuerpoJson }, pedidos.map { it.texto })
    }

    @Test fun `el Retry-After manda sobre la espera calculada`() = runBlocking {
        val a = pieza()
        responder = { _, _ -> MockResponse().setResponseCode(429).setHeader("Retry-After", "40") }
        subidor().vaciar()
        assertEquals(ahora + 40_000, estado(a).siguienteIntentoAt)
    }

    @Test fun `422 PRECIO_CAMBIO rechaza con precioActual, su evidencia espera y la siguiente si se manda`() = runBlocking {
        val (v, ev) = ventaConEvidencia()
        val otra = pieza()
        responder = { path, _ -> if (path.endsWith("/ventas")) error(422, "PRECIO_CAMBIO", ""","precioActual":"60.00"""") else null }
        val r = subidor().vaciar()
        assertNull(r.cortadoPor)
        val rv = estado(v)
        assertEquals(EstadoPieza.RECHAZADA, rv.estado)
        assertEquals("PRECIO_CAMBIO", rv.rechazoCode)
        assertEquals("60.00", json(rv.rechazoDatosJson)["precioActual"].asString)
        assertEquals(EstadoPieza.PENDIENTE, estado(ev).estado) // barrera: ni la foto ni la evidencia salen
        assertTrue(storage().isEmpty())
        assertTrue(de("/evidencia").isEmpty())
        assertEquals(EstadoPieza.HECHA, estado(otra).estado)
    }

    @Test fun `barrera - la evidencia sale despues de su venta, con el paymentId de la respuesta y su foto subida`() = runBlocking {
        val (v, ev) = ventaConEvidencia()
        val p = gsonCola.fromJson(ev.evidenciaJson, EvidenciaPendiente::class.java)
        val archivo = File(fotosDe(ev.fotosJson).single().archivo)
        val original = archivo.readBytes()
        assertEquals(2, subidor().vaciar().subidas)

        assertEquals(listOf("/ventas", "storage", "/evidencia"), pedidos.map { if (it.path.startsWith("/v0/b/")) "storage" else it.path.substringAfterLast("/v1") })
        assertEquals("image/jpeg", storage().single().contentType)
        assertArrayEquals(original, storage().single().bytes)
        val cuerpo = json(de("/evidencia").single().texto)
        assertEquals("pay1", cuerpo["paymentId"].asString)
        assertFalse(cuerpo.has("verificationId"))
        assertEquals(p.fotoUrl, cuerpo["photoUrls"].asJsonArray.single().asString)
        assertEquals("Vinculacion", cuerpo["photoLabel"].asString)
        assertEquals(0, cuerpo["replaceIndex"].asInt)
        assertEquals("5512345678", cuerpo["linkingNumber"].asString)
        assertEquals(ev.llave, cuerpo["idempotencyKey"].asString)
        assertEquals(p.captureSeq, cuerpo["captureSeq"].asLong)
        assertEquals(p.captureDeviceId, cuerpo["captureDeviceId"].asString)
        assertEquals(p.capturedAt, cuerpo["capturedAt"].asString)
        assertEquals(EstadoPieza.HECHA, estado(v).estado)
        assertEquals(EstadoPieza.HECHA, estado(ev).estado)
        assertFalse(archivo.exists())
    }

    @Test fun `evidencia suelta lleva el paymentId y verificationId de su evidenciaJson (R8-3)`() = runBlocking {
        val llave = encolador.evidenciaSuelta(quienSoy.generacion, EvidenciaSueltaNueva("v1", "cpay9", "cver9", "Portabilidad", "5587654321", foto(), "SIM 9"))
        subidor().vaciar()
        val cuerpo = json(de("/evidencia").single().texto)
        assertEquals("cpay9", cuerpo["paymentId"].asString)
        assertEquals("cver9", cuerpo["verificationId"].asString)
        assertEquals("5587654321", cuerpo["portedNumber"].asString)
        assertEquals(EstadoPieza.HECHA, dao.porLlave(llave)!!.estado)
    }

    // ---------- 2xx sin el dato que prueba que se aplicó (H4): «algo en medio», la pieza sólo espera ----------

    @Test fun `200 {} en una VENTA no la da por hecha - espera, y el 201 completo la deja HECHA (H4)`() = runBlocking {
        val v = dao.porLlave(encolador.venta(quienSoy.generacion, VentaNueva("8952140000001", BigDecimal("50"), false, "v1", emptyList(), "SIM")))!!
        responder = { path, _ -> if (path.endsWith("/ventas")) ok("{}") else null }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        val e = estado(v)
        assertEquals(EstadoPieza.PENDIENTE, e.estado)
        assertEquals(1, e.intentos)
        assertEquals(ahora + 5_000, e.siguienteIntentoAt)
        assertNull(e.resultadoJson)

        ahora += 6_000
        responder = { _, _ -> null }
        assertEquals(1, subidor().vaciar().subidas)
        assertEquals("pay1", json(estado(v).resultadoJson)["paymentId"].asString)
        assertEquals(EstadoPieza.HECHA, estado(v).estado)
    }

    @Test fun `custodias con un 2xx incompleto no se dan por hechas - esperan, y la respuesta completa si (R2-2)`() = runBlocking {
        val casos = listOf(
            Triple(TipoPieza.CUSTODIA_ACEPTAR, "/custodia/aceptar", listOf("""{"summary":{}}""", """{"eventos":[]}""", """{"resuelto":"APLICADO","eventos":[]}""")),
            Triple(TipoPieza.CUSTODIA_RECHAZAR, "/custodia/rechazar", listOf("""{"eventos":[]}""", """{"resuelto":"APLICADO","eventos":[]}""")),
        )
        for ((tipo, ruta, incompletos) in casos) {
            val p = pieza(tipo = tipo)
            for ((i, cuerpo) in incompletos.withIndex()) {
                ahora += 3_600_000 // ya pasó su espera: esta vez sí se manda
                responder = { path, _ -> if (path.endsWith(ruta)) ok(cuerpo) else null }
                assertEquals(cuerpo, "REINTENTABLE", subidor().vaciar().cortadoPor)
                assertEquals(cuerpo, EstadoPieza.PENDIENTE, estado(p).estado)
                assertEquals(cuerpo, i + 1, estado(p).intentos)
            }
            ahora += 3_600_000
            responder = { _, _ -> null } // lo que contesta el SERVIDOR (porDefecto)
            assertEquals(1, subidor().vaciar().subidas)
            assertEquals(EstadoPieza.HECHA, estado(p).estado)
        }
    }

    @Test fun `201 {} en una ENTRADA no la da por hecha - su foto se queda y el turno no recibe id (H4)`() = runBlocking {
        val e = dao.porLlave(encolador.entrada(quienSoy.generacion, ChecadaNueva("v1", null, null, null, null, foto(), "Entrada")))!!
        val archivo = File(fotosDe(e.fotosJson).single().archivo)
        responder = { path, _ -> if (path.endsWith("/checadas/entrada")) ok("{}", 201) else null }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.PENDIENTE, estado(e).estado)
        assertTrue(archivo.exists())
        assertNull(dao.turnoAhora()!!.timeEntryId)

        ahora += 6_000
        responder = { _, _ -> null }
        subidor().vaciar()
        assertEquals(EstadoPieza.HECHA, estado(e).estado)
        assertEquals("te1", dao.turnoAhora()!!.timeEntryId)
        assertFalse(archivo.exists())
    }

    @Test fun `200 {} en una EVIDENCIA no la da por hecha y su foto local no se borra (H4)`() = runBlocking {
        val (v, ev) = ventaConEvidencia()
        val archivo = File(fotosDe(ev.fotosJson).single().archivo)
        responder = { path, _ -> if (path.endsWith("/evidencia")) ok("{}") else null }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.HECHA, estado(v).estado)
        assertEquals(EstadoPieza.PENDIENTE, estado(ev).estado)
        assertTrue(archivo.exists())

        ahora += 6_000
        responder = { _, _ -> null }
        subidor().vaciar()
        assertEquals(EstadoPieza.HECHA, estado(ev).estado)
        assertFalse(archivo.exists())
    }

    @Test fun `traeSuResultado - el dato de cada tipo, tambien en las formas de la gracia (H4)`() {
        assertTrue(traeSuResultado(TipoPieza.VENTA, """{"orderId":"o","paymentId":"p","repetida":false}"""))
        assertTrue(traeSuResultado(TipoPieza.VENTA, """{"resuelto":"APLICADO","resultado":{"paymentId":"p","recuperado":true}}"""))
        assertFalse(traeSuResultado(TipoPieza.VENTA, """{"paymentId":""}"""))
        assertFalse(traeSuResultado(TipoPieza.VENTA, """{"aplicadoEnGracia":true}"""))
        assertTrue(traeSuResultado(TipoPieza.ENTRADA, """{"timeEntryId":"t","repetida":true}"""))
        assertTrue(traeSuResultado(TipoPieza.SALIDA, """{"timeEntryId":"t","totalHours":1}"""))
        assertFalse(traeSuResultado(TipoPieza.SALIDA, "{}"))
        assertTrue(traeSuResultado(TipoPieza.EVIDENCIA, """{"success":true,"verificationId":"v"}"""))
        assertFalse(traeSuResultado(TipoPieza.EVIDENCIA, """{"success":true}"""))
        assertTrue(traeSuResultado(TipoPieza.CUSTODIA_ACEPTAR, """{"summary":{"total":1,"succeeded":1,"failed":0},"results":[]}"""))
        val enParte = """{"resuelto":"APLICADO","resultado":{"summary":{"total":2,"succeeded":1,"failed":1},"fallidas":[]}}"""
        assertTrue(traeSuResultado(TipoPieza.CUSTODIA_ACEPTAR, enParte))
        val bitacora = """{"resuelto":"APLICADO","resultado":{"recuperadoDeLaBitacora":true,"eventos":[{"serialNumber":"S1"}]}}"""
        assertTrue(traeSuResultado(TipoPieza.CUSTODIA_ACEPTAR, bitacora))
        assertTrue(traeSuResultado(TipoPieza.CUSTODIA_RECHAZAR, bitacora))
        assertFalse(traeSuResultado(TipoPieza.CUSTODIA_ACEPTAR, "{}"))
        // R2-2: la forma COMPLETA que manda el SERVIDOR; un summary vacío o cero eventos no prueban nada.
        for (tipo in listOf(TipoPieza.CUSTODIA_ACEPTAR, TipoPieza.CUSTODIA_RECHAZAR)) {
            for (incompleto in listOf(
                """{"summary":{}}""", """{"summary":{"failed":0}}""", """{"summary":{"total":"1","succeeded":1,"failed":0}}""",
                """{"eventos":[]}""", """{"resuelto":"APLICADO","eventos":[]}""", """{"eventos":[{"serialNumber":"S1"}]}""",
                """{"resuelto":"APLICADO","resultado":{"recuperadoDeLaBitacora":true,"eventos":[]}}""",
            )) assertFalse("$tipo $incompleto", traeSuResultado(tipo, incompleto))
        }
        assertTrue(traeSuResultado(TipoPieza.CUSTODIA_RECHAZAR, """{"custodyState":"PROMOTER_REJECTED","repetida":true}"""))
        assertFalse(traeSuResultado(TipoPieza.CUSTODIA_RECHAZAR, """{"repetida":true}"""))
        assertFalse(traeSuResultado(TipoPieza.VENTA, "<html>"))
    }

    // ---------- 2xx que no guardó todo (AVISO) ----------

    @Test fun `evidencia con SALE_ALREADY_APPROVED queda AVISO con su texto y sin foto`() = runBlocking {
        val (_, ev) = ventaConEvidencia()
        responder = { path, _ -> if (path.endsWith("/evidencia")) ok("""{"success":true,"verificationId":"sv1","status":"COMPLETED","outcome":"SALE_ALREADY_APPROVED"}""") else null }
        subidor().vaciar()
        val r = estado(ev)
        assertEquals(EstadoPieza.AVISO, r.estado)
        assertEquals("SALE_ALREADY_APPROVED", r.rechazoCode)
        assertTrue(r.rechazoMensaje!!.isNotBlank())
        assertFalse(File(fotosDe(r.fotosJson).single().archivo).exists())
    }

    @Test fun `aceptar 3 SIMs con una fallida queda AVISO con la fila fallida`() = runBlocking {
        val p = pieza(tipo = TipoPieza.CUSTODIA_ACEPTAR)
        responder = { _, _ ->
            ok(
                """{"summary":{"total":3,"succeeded":2,"failed":1},"results":[{"serialNumber":"S1","status":"ok","repetida":false},""" +
                    """{"serialNumber":"S2","status":"error","code":"VERSION_CAMBIO","message":"m"},{"serialNumber":"S3","status":"ok","repetida":false}]}""",
            )
        }
        val r = subidor().vaciar()
        assertTrue(r.copiaPorRefrescar)
        val e = estado(p)
        assertEquals(EstadoPieza.AVISO, e.estado)
        val fallidas = json(e.rechazoDatosJson)["fallidas"].asJsonArray
        assertEquals("S2", fallidas.single().asJsonObject["serialNumber"].asString)
    }

    @Test fun `desenlaceDe - numero apagado es AVISO, SUPERSEDED_BY_NEWER es HECHA`() {
        val disabled = """{"success":true,"verificationId":"v","outcome":"APPLIED","photoOutcome":"APPLIED","linkingNumberOutcome":"DISABLED"}"""
        assertEquals(Desenlace.AVISO, desenlaceDe(TipoPieza.EVIDENCIA, disabled, pidioNumero = true).desenlace)
        val nueva = """{"success":true,"verificationId":"v","outcome":"SUPERSEDED_BY_NEWER","photoOutcome":"SUPERSEDED_BY_NEWER"}"""
        assertEquals(Desenlace.HECHA, desenlaceDe(TipoPieza.EVIDENCIA, nueva, pidioNumero = true).desenlace)
        val fotoRara = """{"verificationId":"v","outcome":"APPLIED","photoOutcome":"OTRA_COSA"}"""
        assertEquals(Desenlace.AVISO, desenlaceDe(TipoPieza.EVIDENCIA, fotoRara).desenlace)
        assertEquals(Desenlace.HECHA, desenlaceDe(TipoPieza.VENTA, """{"paymentId":"p","repetida":true}""").desenlace)
    }

    @Test fun `desenlaceDe - las formas de la gracia (R2-3, R3-1)`() {
        val custodia = """{"resuelto":"APLICADO","resultado":{"recuperadoDeLaBitacora":true,"eventos":[{"serialNumber":"S1"}],"faltan":["S2"]}}"""
        val l = desenlaceDe(TipoPieza.CUSTODIA_ACEPTAR, custodia)
        assertEquals(Desenlace.AVISO, l.desenlace)
        assertEquals("S2", json(l.datosJson)["faltan"].asJsonArray.single().asString)

        val gracia = """{"aplicadoEnGracia":true,"success":true,"verificationId":"v","outcome":"APPLIED","photoOutcome":"APPLIED","linkingNumberOutcome":"DISABLED"}"""
        assertEquals(Desenlace.AVISO, desenlaceDe(TipoPieza.EVIDENCIA, gracia, pidioNumero = true).desenlace)

        val recuperado = """{"resuelto":"APLICADO","resultado":{"verificationId":"v","paymentId":"p","recuperado":true}}"""
        val conNumero = desenlaceDe(TipoPieza.EVIDENCIA, recuperado, pidioNumero = true)
        assertEquals(Desenlace.AVISO, conNumero.desenlace)
        assertEquals("La foto quedó; no pudimos confirmar el número. Revísalo con tu supervisor.", conNumero.texto)
        assertEquals(Desenlace.HECHA, desenlaceDe(TipoPieza.EVIDENCIA, recuperado, pidioNumero = false).desenlace)
    }

    @Test fun `cuerpoEfectivo desenvuelve resuelto APLICADO y deja lo demas igual`() {
        assertEquals("p", json(cuerpoEfectivo("""{"resuelto":"APLICADO","resultado":{"paymentId":"p"}}"""))["paymentId"].asString)
        val directo = """{"aplicadoEnGracia":true,"paymentId":"p"}"""
        assertEquals(directo, cuerpoEfectivo(directo))
    }

    // ---------- sucursales ----------

    @Test fun `sucursal que ya no es tuya - RECHAZADA YA_NO_ES_TU_SUCURSAL y la siguiente si se manda`() = runBlocking {
        val vieja = pieza(venueId = "v9")
        val buena = pieza()
        subidor().vaciar()
        assertEquals(EstadoPieza.RECHAZADA, estado(vieja).estado)
        assertEquals("YA_NO_ES_TU_SUCURSAL", estado(vieja).rechazoCode)
        assertEquals(EstadoPieza.HECHA, estado(buena).estado)
        assertEquals(1, pedidos.size)
    }

    @Test fun `si a mitad del vaciado el celular se desvincula, la siguiente queda PENDIENTE y el resultado trae modoGracia`() = runBlocking {
        val primera = pieza()
        val segunda = pieza(venueId = "v9")
        responder = { _, _ -> quienSoy.marcarDesvinculado(null); null }
        val r = subidor().vaciar()
        assertTrue(r.modoGracia)
        assertEquals(EstadoPieza.HECHA, estado(primera).estado)
        assertEquals(EstadoPieza.PENDIENTE, estado(segunda).estado)
        assertNull(estado(segunda).rechazoCode)
    }

    @Test fun `pieza de la sucursal B con la sesion en A - no se manda, no tapa a la de A y no se rechaza`() = runBlocking {
        val deB = pieza(venueId = "v2")
        val deA = pieza()
        assertNull(subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.PENDIENTE, estado(deB).estado)
        assertEquals(0, estado(deB).intentos)
        assertEquals(EstadoPieza.HECHA, estado(deA).estado)
        assertTrue(pedidos.none { it.path.contains("/v2/") })
    }

    @Test fun `403 OTRA_SUCURSAL del servidor en una pieza de la sucursal de la sesion - VENTA_MOVIDA y sigue (R6-B2)`() = runBlocking {
        val llave = encolador.evidenciaSuelta(quienSoy.generacion, EvidenciaSueltaNueva("v1", "cpay9", "cver9", "Vinculacion", null, foto(), "SIM 9"))
        val otra = pieza()
        responder = { path, _ -> if (path.endsWith("/evidencia")) error(403, "OTRA_SUCURSAL") else null }
        subidor().vaciar()
        assertEquals("VENTA_MOVIDA", dao.porLlave(llave)!!.rechazoCode)
        assertEquals(EstadoPieza.RECHAZADA, dao.porLlave(llave)!!.estado)
        assertEquals(EstadoPieza.HECHA, estado(otra).estado)
    }

    @Test fun `403 OTRA_SUCURSAL en una pieza que no es evidencia - RECHAZADA con el codigo del servidor, no VENTA_MOVIDA`() = runBlocking {
        val a = pieza()
        val b = pieza()
        responder = { path, _ -> if (path.endsWith("/custodia/rechazar") && pedidos.size == 1) error(403, "OTRA_SUCURSAL") else null }
        assertNull(subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.RECHAZADA, estado(a).estado)
        assertEquals("OTRA_SUCURSAL", estado(a).rechazoCode)
        assertEquals("m", estado(a).rechazoMensaje)
        assertEquals(EstadoPieza.HECHA, estado(b).estado)
    }

    @Test fun `un tipo desconocido se rechaza y no traba la cola`() = runBlocking {
        val rara = pieza(tipo = "DEL_FUTURO")
        val buena = pieza()
        assertNull(subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.RECHAZADA, estado(rara).estado)
        assertEquals("TIPO_DESCONOCIDO", estado(rara).rechazoCode)
        assertEquals(EstadoPieza.HECHA, estado(buena).estado)
        assertEquals(1, pedidos.size)
    }

    // ---------- sesión ----------

    @Test fun `401 CELULAR_DESVINCULADO - modoGracia y la pieza sigue PENDIENTE`() = runBlocking {
        val p = pieza()
        responder = { _, _ -> error(401, "CELULAR_DESVINCULADO") }
        val r = subidor().vaciar()
        assertTrue(r.modoGracia)
        assertEquals(EstadoPieza.PENDIENTE, estado(p).estado)
        assertEquals(0, estado(p).intentos)
    }

    @Test fun `401 CELULAR_DESCONOCIDO corta sin tocar la pieza (R6-B1)`() = runBlocking {
        val p = pieza()
        responder = { _, _ -> error(401, "CELULAR_DESCONOCIDO") }
        val r = subidor().vaciar()
        assertEquals("DESCONOCIDO", r.cortadoPor)
        assertFalse(r.modoGracia)
        assertEquals(p, estado(p))
        assertEquals(EstadoDelCelular.DESCONOCIDO, quienSoy.estado)
    }

    @Test fun `renovar con 429 - la pieza espera el Retry-After y corta (R6-B3)`() = runBlocking {
        val a = pieza()
        pieza()
        responder = { path, _ ->
            if (path == "/tpv/campo/renovar") MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
            else error(401, "SESION_INVALIDA")
        }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        assertEquals(1, estado(a).intentos)
        assertEquals(ahora + 30_000, estado(a).siguienteIntentoAt)
        assertEquals(1, de("/custodia/rechazar").size)
    }

    @Test fun `403 SIN_ACCESO_A_LA_SUCURSAL - RECHAZADA, PIN pedido y la siguiente no se manda (R5-1)`() = runBlocking {
        val a = pieza()
        val b = pieza()
        responder = { _, _ -> error(403, "SIN_ACCESO_A_LA_SUCURSAL") }
        assertEquals("NECESITA_PIN", subidor().vaciar().cortadoPor)
        assertEquals(EstadoPieza.RECHAZADA, estado(a).estado)
        assertEquals("SIN_ACCESO_A_LA_SUCURSAL", estado(a).rechazoCode)
        assertTrue(quienSoy.necesitaPin)
        assertTrue(quienSoy.bloqueada)
        assertEquals(EstadoPieza.PENDIENTE, estado(b).estado)
        assertEquals(1, pedidos.size)
    }

    @Test fun `con necesitaPin corta sin llamar al servidor (R3-2)`() = runBlocking {
        pieza()
        quienSoy.marcarNecesitaPin(true)
        assertEquals("NECESITA_PIN", subidor().vaciar().cortadoPor)
        assertEquals(0, server.requestCount)
    }

    // ---------- fotos ----------

    @Test fun `checada con foto - primero la foto, luego la checada con la url guardada, y el turno recibe su timeEntryId`() = runBlocking {
        val llave = encolador.entrada(quienSoy.generacion, ChecadaNueva("v1", 19.4, -99.1, 10.0, false, foto(), "Entrada"))
        val e = dao.porLlave(llave)!!
        val f = fotosDe(e.fotosJson).single()
        subidor().vaciar()
        assertEquals(2, pedidos.size)
        assertEquals("/v0/b/avoqado-d0a24.appspot.com/o?name=${codificar(f.ruta)}", pedidos[0].path)
        assertEquals("image/jpeg", pedidos[0].contentType)
        assertEquals(321, pedidos[0].bytes.size)
        assertEquals(e.cuerpoJson, pedidos[1].texto)
        assertEquals(f.url, json(pedidos[1].texto)["checkInPhotoUrl"].asString)
        assertEquals(EstadoPieza.HECHA, estado(e).estado)
        assertEquals("te1", dao.turnoAhora()!!.timeEntryId)
        assertFalse(File(f.archivo).exists())
    }

    @Test fun `un 200 de Storage con otro size - la checada NO se manda`() = runBlocking {
        val e = dao.porLlave(encolador.entrada(quienSoy.generacion, ChecadaNueva("v1", null, null, null, null, foto(), "Entrada")))!!
        responder = { path, _ -> if (path.startsWith("/v0/b/")) ok("""{"contentType":"image/jpeg","size":"1"}""") else null }
        assertEquals("REINTENTABLE", subidor().vaciar().cortadoPor)
        assertTrue(de("/checadas/entrada").isEmpty())
        assertEquals(1, estado(e).intentos)
        assertFalse(fotosDe(estado(e).fotosJson).single().subida)
    }

    @Test fun `foto que ya no esta - RECHAZADA FOTO_NO_SUBIO y la siguiente se manda`() = runBlocking {
        val e = dao.porLlave(encolador.entrada(quienSoy.generacion, ChecadaNueva("v1", null, null, null, null, foto(), "Entrada")))!!
        File(fotosDe(e.fotosJson).single().archivo).delete()
        val otra = pieza()
        subidor().vaciar()
        assertEquals("FOTO_NO_SUBIO", estado(e).rechazoCode)
        assertEquals(EstadoPieza.HECHA, estado(otra).estado)
    }

    // ---------- limpieza ----------

    @Test fun `el barrido tambien borra de cacheDir-fotos lo de mas de 24 h y no toca lo reciente (H5)`() = runBlocking {
        dirFotos.mkdirs()
        val reciente = File(dirFotos, "reciente.jpg").apply { writeText("x"); setLastModified(ahora - 60_000) }
        val vieja = File(dirFotos, "vieja.jpg").apply { writeText("x"); setLastModified(ahora - 25 * 3_600_000L) }
        subidor().vaciar()
        assertTrue(reciente.exists())
        assertFalse(vieja.exists())
    }

    @Test fun `el barrido sólo borra archivos sin pieza de hace mas de 24 h (P2)`() = runBlocking {
        dirCola.mkdirs()
        val reciente = File(dirCola, "reciente.jpg").apply { writeText("x"); setLastModified(ahora - 60_000) }
        val vieja = File(dirCola, "vieja.jpg").apply { writeText("x"); setLastModified(ahora - 25 * 3_600_000L) }
        val e = dao.porLlave(encolador.entrada(quienSoy.generacion, ChecadaNueva("v1", null, null, null, null, foto(), "Entrada")))!!
        val viva = File(fotosDe(e.fotosJson).single().archivo).apply { setLastModified(ahora - 25 * 3_600_000L) }
        quienSoy.marcarNecesitaPin(true) // que nada se suba: la viva sigue siendo de una pieza viva
        subidor().vaciar()
        assertTrue(reciente.exists())
        assertFalse(vieja.exists())
        assertTrue(viva.exists())
    }
}
