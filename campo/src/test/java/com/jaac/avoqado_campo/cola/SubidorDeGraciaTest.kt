package com.jaac.avoqado_campo.cola

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.DatosDeLatido
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.AparatoSinId
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.GuardiaDeSesion
import com.jaac.avoqado_campo.red.RedModule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubidorDeGraciaTest {
    @get:Rule val tmp = TemporaryFolder()

    /** [envio] = el `GraciaBody` de una petición a `/gracia` (null en las demás). */
    private data class Pedido(val path: String, val texto: String, val credencial: String?) {
        val envio: JsonObject? get() = if (path.endsWith("/gracia")) JsonParser.parseString(texto).asJsonObject else null
        val tipo: String? get() = envio?.get("tipo")?.asString
        val cuerpoJson: String? get() = envio?.get("cuerpoJson")?.asString
    }

    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private val quienSoy = QuienSoy(almacen)
    private var ahora = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private lateinit var dirCola: File
    private lateinit var encolador: Encolador
    private val pedidos: MutableList<Pedido> = Collections.synchronizedList(mutableListOf())
    /** null = la respuesta de [porDefecto]. Recibe el pedido ya registrado. */
    @Volatile private var responder: (Pedido) -> MockResponse? = { null }
    private val mutex = MutexDeSubida()

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
                val bytes = request.body.readByteArray()
                val p = Pedido(request.path!!, String(bytes), request.getHeader("X-Celular-Credencial"))
                pedidos += p
                return responder(p) ?: porDefecto(p, bytes)
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

    private fun fotos() = SubidorDeFotos(RedModule.proveerCliente(), server.url("/").toString())
    private fun gracia() = SubidorDeGracia(dao, api, quienSoy, fotos(), mutex) { ahora }
    private fun subidor() =
        Subidor(dao, api, GuardiaDeSesion(api, quienSoy, MutexDeSesion()), quienSoy, fotos(), mutex, dirCola) { ahora }

    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun error(status: Int, code: String) = ok("""{"code":"$code","message":"m"}""", status)
    private val ventaAplicada =
        """{"orderId":"o1","paymentId":"pay1","saleVerificationId":"sv1","repetida":false,"precio":"50.00","aplicadoEnGracia":true}"""

    private fun porDefecto(p: Pedido, bytes: ByteArray): MockResponse = when {
        p.path.startsWith("/v0/b/") -> ok("""{"name":"x","contentType":"image/jpeg","size":"${bytes.size}"}""")
        p.tipo == TipoPieza.VENTA -> ok(ventaAplicada, 201)
        p.tipo == TipoPieza.EVIDENCIA ->
            ok("""{"success":true,"verificationId":"sv1","outcome":"APPLIED","photoOutcome":"APPLIED","aplicadoEnGracia":true}""")
        p.path.endsWith("/gracia") -> ok("""{"custodyState":"PROMOTER_REJECTED","repetida":false,"aplicadoEnGracia":true}""")
        else -> ok("{}")
    }

    private fun gracias() = pedidos.filter { it.path.endsWith("/gracia") }
    private fun storage() = pedidos.filter { it.path.startsWith("/v0/b/") }
    private fun json(t: String?) = JsonParser.parseString(t).asJsonObject
    private suspend fun estado(p: Pieza) = dao.porId(p.id)!!

    private val serie = AtomicInteger()
    private suspend fun pieza(): Pieza {
        val cuerpo = """{"serialNumber":"S${serie.incrementAndGet()}","custodyVersion":1}"""
        val id = dao.insertar(
            Pieza(
                tipo = TipoPieza.CUSTODIA_RECHAZAR, llave = UUID.randomUUID().toString(), staffId = "s1", venueId = "v1",
                cuerpoJson = cuerpo, huella = huella(TipoPieza.CUSTODIA_RECHAZAR, "v1", cuerpo), creadaAt = ahora, resumen = "r",
            ),
        )
        return dao.porId(id)!!
    }

    private fun foto(): String = tmp.newFile("f-${System.nanoTime()}.jpg").also { it.writeBytes(ByteArray(321) { 9 }) }.absolutePath

    private suspend fun ventaConEvidencia(): Pair<Pieza, Pieza> {
        val llave = encolador.venta(
            VentaNueva("8952140000001", BigDecimal("50"), false, "v1", listOf(EvidenciaNueva("Vinculacion", "5512345678", foto())), "SIM 1"),
        )
        return dao.porLlave(llave)!! to dao.siguientesParaSubir().single { it.dependeDe == llave }
    }

    private fun desvincular() = quienSoy.marcarDesvinculado("2026-11-11T18:00:00Z")
    private fun darDeBaja() = quienSoy.guardarLatido(DatosDeLatido("DADO_DE_BAJA", null, null, emptyList()))

    // ---------- el texto exacto ----------

    @Test fun `la VENTA va con el MISMO cuerpoJson de la ruta normal y la huella que reporto el latido`() = runBlocking {
        val v = dao.porLlave(encolador.venta(VentaNueva("8952140000001", BigDecimal("50"), false, "v1", emptyList(), "SIM")))!!
        val huellas = dao.huellasPorSubir()
        desvincular()

        val r = gracia().vaciar()

        assertNull(r.cortadoPor)
        assertTrue(r.copiaPorRefrescar)
        val g = gracias().single()
        assertEquals("c".repeat(64), g.credencial)
        assertEquals(TipoPieza.VENTA, g.tipo)
        assertEquals("v1", g.envio!!["venueId"].asString)
        assertEquals(v.cuerpoJson, g.cuerpoJson)
        assertEquals(listOf(huella(TipoPieza.VENTA, "v1", g.cuerpoJson!!)), huellas)
        val hecha = estado(v)
        assertEquals(EstadoPieza.HECHA, hecha.estado)
        assertEquals("pay1", json(hecha.resultadoJson)["paymentId"].asString) // su evidencia lo encuentra (R7-4)
    }

    @Test fun `la EVIDENCIA va con ventaLlave y SIN paymentId, con photoLabel - se guarda y el reintento manda lo mismo`() = runBlocking {
        val (v, ev) = ventaConEvidencia()
        desvincular()
        val n = AtomicInteger()
        responder = { if (it.tipo == TipoPieza.EVIDENCIA && n.getAndIncrement() == 0) error(503, "X") else null }

        assertEquals("REINTENTABLE", gracia().vaciar().cortadoPor)
        val guardado = estado(ev).cuerpoJson!!
        val c = json(guardado)
        assertEquals(v.llave, c["ventaLlave"].asString)
        assertFalse(c.has("paymentId"))
        assertFalse(c.has("verificationId"))
        assertEquals("Vinculacion", c["photoLabel"].asString)
        assertEquals(0, c["replaceIndex"].asInt)
        assertEquals("5512345678", c["linkingNumber"].asString)
        assertEquals(ev.llave, c["idempotencyKey"].asString)
        val e = gsonCola.fromJson(ev.evidenciaJson, EvidenciaPendiente::class.java)
        assertEquals(e.fotoUrl, c["photoUrls"].asJsonArray.single().asString)
        assertEquals(e.capturedAt, c["capturedAt"].asString)

        ahora += 6_000
        gracia().vaciar()
        val deEvidencia = gracias().filter { it.tipo == TipoPieza.EVIDENCIA }
        assertEquals(2, deEvidencia.size)
        deEvidencia.forEach { assertEquals(guardado, it.cuerpoJson) }
        assertEquals(1, storage().size) // la foto no se vuelve a subir
        assertEquals(EstadoPieza.HECHA, estado(ev).estado)
        assertFalse(File(fotosDe(ev.fotosJson).single().archivo).exists())
    }

    @Test fun `evidencia suelta no se manda - RECHAZADA con el texto de R9-B1`() = runBlocking {
        val llave = encolador.evidenciaSuelta(EvidenciaSueltaNueva("v1", "cpay9", "cver9", "Vinculacion", null, foto(), "SIM 9"))
        val otra = pieza()
        desvincular()

        gracia().vaciar()

        val p = dao.porLlave(llave)!!
        assertEquals(EstadoPieza.RECHAZADA, p.estado)
        assertEquals(SubidorDeGracia.CORRECCION_SIN_SESION, p.rechazoCode)
        assertEquals(
            "El celular se desvinculó antes de subir esta corrección. Cuando tu celular esté activado otra vez, la venta va a " +
                "volver a salir en «Mis SIMs» como «Revisar» y la corriges de nuevo.",
            p.rechazoMensaje,
        )
        assertEquals(listOf(TipoPieza.CUSTODIA_RECHAZAR), gracias().map { it.tipo })
        assertTrue(storage().isEmpty())
        assertEquals(EstadoPieza.HECHA, estado(otra).estado)
    }

    // ---------- lo que decide el supervisor ----------

    @Test fun `202 retenido - RETENIDA, la siguiente si se manda, y a los 5 min APLICADO la deja HECHA`() = runBlocking {
        val a = pieza()
        val b = pieza()
        desvincular()
        responder = { if (it.cuerpoJson == a.cuerpoJson) ok("""{"retenido":true,"motivo":"SIN_HUELLA"}""", 202) else null }

        assertNull(gracia().vaciar().cortadoPor)
        val ret = estado(a)
        assertEquals(EstadoPieza.RETENIDA, ret.estado)
        assertEquals("SIN_HUELLA", ret.rechazoCode)
        assertEquals(ahora + 5 * 60_000L, ret.siguienteIntentoAt)
        assertEquals(EstadoPieza.HECHA, estado(b).estado)
        assertEquals(2, gracias().size)

        gracia().vaciar() // antes de los 5 min no se vuelve a mandar
        assertEquals(2, gracias().size)

        ahora += 5 * 60_000L
        responder = { if (it.cuerpoJson == a.cuerpoJson) ok("""{"resuelto":"APLICADO","resultado":{"custodyState":"PROMOTER_REJECTED"}}""") else null }
        assertEquals(1, gracia().vaciar().subidas)
        assertEquals(3, gracias().size)
        assertEquals(a.cuerpoJson, gracias().last().cuerpoJson)
        val hecha = estado(a)
        assertEquals(EstadoPieza.HECHA, hecha.estado)
        assertEquals("""{"custodyState":"PROMOTER_REJECTED"}""", hecha.resultadoJson)
        assertNull(hecha.rechazoCode)
    }

    @Test fun `DESCARTADO de una VENTA - DESCARTADA con la nota y su EVIDENCIA RECHAZADA SU_VENTA_SE_DESCARTO`() = runBlocking {
        val (v, ev) = ventaConEvidencia()
        desvincular()
        responder = { if (it.tipo == TipoPieza.VENTA) ok("""{"resuelto":"DESCARTADO","nota":"La SIM era de otra tienda"}""") else null }

        gracia().vaciar()

        assertEquals(EstadoPieza.DESCARTADA, estado(v).estado)
        assertEquals("La SIM era de otra tienda", estado(v).rechazoMensaje)
        assertEquals(EstadoPieza.RECHAZADA, estado(ev).estado)
        assertEquals("SU_VENTA_SE_DESCARTO", estado(ev).rechazoCode)
        assertEquals(1, gracias().size)
        assertTrue(storage().isEmpty())
    }

    @Test fun `aplicadoEnGracia con linkingNumberOutcome DISABLED - AVISO`() = runBlocking {
        val (_, ev) = ventaConEvidencia()
        desvincular()
        responder = {
            if (it.tipo == TipoPieza.EVIDENCIA) {
                ok("""{"success":true,"verificationId":"sv1","outcome":"APPLIED","photoOutcome":"APPLIED","linkingNumberOutcome":"DISABLED","aplicadoEnGracia":true}""")
            } else null
        }

        assertEquals(2, gracia().vaciar().subidas)

        assertEquals(EstadoPieza.AVISO, estado(ev).estado)
        assertEquals("DISABLED", estado(ev).rechazoCode)
    }

    // ---------- la sesión y los cortes ----------

    @Test fun `USA_TU_SESION con DADO_DE_BAJA - vuelve a VINCULADO y corta sin tocar la pieza`() = runBlocking {
        val a = pieza()
        pieza()
        darDeBaja()
        responder = { error(409, "USA_TU_SESION") }

        assertEquals("USA_TU_SESION", gracia().vaciar().cortadoPor)

        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
        assertEquals(1, gracias().size)
        assertEquals(a, estado(a))
    }

    @Test fun `USA_TU_SESION con DESVINCULADO - no cambia nada y corta (R8-2)`() = runBlocking {
        val a = pieza()
        desvincular()
        responder = { error(409, "USA_TU_SESION") }

        assertEquals("USA_TU_SESION", gracia().vaciar().cortadoPor)

        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals(a, estado(a))
    }

    @Test fun `RETENIDOS_LLENOS corta sin cambiar el estado`() = runBlocking {
        val a = pieza()
        pieza()
        desvincular()
        responder = { error(409, "RETENIDOS_LLENOS") }

        assertEquals("REINTENTABLE", gracia().vaciar().cortadoPor)

        assertEquals(1, gracias().size)
        assertEquals(EstadoPieza.PENDIENTE, estado(a).estado)
        assertEquals(1, estado(a).intentos)
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
    }

    @Test fun `401 CELULAR_DESCONOCIDO - corta sin tocar la pieza y marca DESCONOCIDO (R6-B1)`() = runBlocking {
        val a = pieza()
        desvincular()
        responder = { error(401, "CELULAR_DESCONOCIDO") }

        assertEquals("CELULAR_DESCONOCIDO", gracia().vaciar().cortadoPor)

        assertEquals(a, estado(a))
        assertEquals(EstadoDelCelular.DESCONOCIDO, quienSoy.estado)
        gracia().vaciar() // con DESCONOCIDO la gracia ya no manda nada
        assertEquals(1, gracias().size)
    }

    @Test fun `CUERPO_INVALIDO - RECHAZADA para que la revise el supervisor, y la siguiente sale`() = runBlocking {
        val a = pieza()
        val b = pieza()
        desvincular()
        responder = { if (it.cuerpoJson == a.cuerpoJson) error(400, "CUERPO_INVALIDO") else null }

        gracia().vaciar()

        assertEquals(EstadoPieza.RECHAZADA, estado(a).estado)
        assertEquals("CUERPO_INVALIDO", estado(a).rechazoCode)
        assertEquals("Pídele a tu supervisor que lo revise", estado(a).rechazoMensaje)
        assertEquals(EstadoPieza.HECHA, estado(b).estado)
    }

    @Test fun `sin red corta y la pieza no cambia`() = runBlocking {
        val a = pieza()
        desvincular()
        responder = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

        assertEquals("SIN_RED", gracia().vaciar().cortadoPor)
        assertEquals(a, estado(a))
    }

    // ---------- el trabajo de WorkManager ----------

    private fun trabajo(): TrabajoDeSubida =
        TestListenableWorkerBuilder<TrabajoDeSubida>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(c: Context, nombre: String, params: WorkerParameters): ListenableWorker {
                    val latido = Latido(api, quienSoy, dao, RelojAnclado(almacen, { ahora }, { 0L }, { 1 }), AparatoSinId(null, null, null, null)) { 0L }
                    val copia = RepositorioDeCopia(c, api, GuardiaDeSesion(api, quienSoy, MutexDeSesion()), dao, quienSoy)
                    return TrabajoDeSubida(c, params, subidor(), gracia(), quienSoy, latido, copia)
                }
            })
            .build()

    @Test fun `el trabajo con el celular desvinculado sube por la gracia`() = runBlocking {
        val a = pieza()
        desvincular()

        assertEquals(ListenableWorker.Result.success(), trabajo().doWork())

        assertEquals(listOf("/tpv/campo/gracia", "/tpv/campo/latido"), pedidos.map { it.path })
        assertEquals(EstadoPieza.HECHA, estado(a).estado)
    }

    @Test fun `el trabajo desvinculado a media pasada sigue por la gracia en el mismo trabajo`() = runBlocking {
        val a = pieza()
        responder = { if (it.path.endsWith("/custodia/rechazar")) error(401, "CELULAR_DESVINCULADO") else null }

        assertEquals(ListenableWorker.Result.success(), trabajo().doWork())

        assertEquals(listOf("/tpv/campo/venues/v1/custodia/rechazar", "/tpv/campo/gracia", "/tpv/campo/latido"), pedidos.map { it.path })
        assertEquals(EstadoDelCelular.DESVINCULADO, quienSoy.estado)
        assertEquals(EstadoPieza.HECHA, estado(a).estado)
    }

    @Test fun `el trabajo refresca la copia si subio una custodia, aunque despues pase por la gracia`() = runBlocking {
        val a = pieza()
        val b = pieza()
        responder = {
            when {
                it.path.endsWith("/gracia") -> error(409, "USA_TU_SESION") // la reactivaron: vuelve a VINCULADO
                !it.path.endsWith("/custodia/rechazar") -> null
                it.texto == a.cuerpoJson -> ok("""{"custodyState":"PROMOTER_REJECTED","repetida":false}""")
                else -> { darDeBaja(); error(403, "PERSONA_DADA_DE_BAJA") } // el latido de la pantalla ya lo supo
            }
        }

        assertEquals(ListenableWorker.Result.retry(), trabajo().doWork())

        assertEquals(
            listOf(
                "/tpv/campo/venues/v1/custodia/rechazar", "/tpv/campo/venues/v1/custodia/rechazar", "/tpv/campo/gracia",
                "/tpv/campo/latido", "/tpv/campo/venues/v1/mis-sims",
            ),
            pedidos.map { it.path },
        )
        assertEquals(EstadoPieza.HECHA, estado(a).estado)
        assertEquals(EstadoDelCelular.VINCULADO, quienSoy.estado)
    }

    @Test fun `el trabajo sin venta ni custodia subida no baja la copia`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), trabajo().doWork())
        assertEquals(listOf("/tpv/campo/latido"), pedidos.map { it.path })
    }
}
