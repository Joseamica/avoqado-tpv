package com.jaac.avoqado_tpv.features.verification.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.verification.data.local.ActionableSaleCacheEntity
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueDao
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * La cola de comprobantes contra Room real y respuestas HTTP reales (MockWebServer + ProofOfSaleRemote): el
 * desenlace sale del `code` que el SERVIDOR pone en el cuerpo, no de un mock que ya trae el tipo correcto.
 * runBlocking: hay red real de por medio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ProofOfSaleQueueRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AvoqadoDatabase
    private lateinit var servidor: MockWebServer
    private lateinit var cola: ColaEspia
    private lateinit var repo: ProofOfSaleQueueRepository
    private val uploader = mockk<VerificationUploadManager>()
    private val storage = mockk<SecureStorage>()

    /** La DAO real con dos costuras: ver el disco al insertar y "matar el proceso" al borrar. */
    private class ColaEspia(private val real: ProofOfSaleQueueDao) : ProofOfSaleQueueDao by real {
        var antesDeInsertar: () -> Unit = {}
        var mueroAlBorrar = false
        override suspend fun insertRow(row: ProofOfSaleQueueEntity): Long {
            antesDeInsertar()
            return real.insertRow(row)
        }
        override suspend fun delete(key: String): Int {
            if (mueroAlBorrar) error("el proceso murió entre marcar la ranura y borrar la fila")
            return real.delete(key)
        }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        servidor = MockWebServer().also { it.start() }
        val api = Retrofit.Builder().baseUrl(servidor.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
        every { storage.getVenueId() } returns "v1"
        every { storage.getVenueSlug() } returns "tienda-a"
        coEvery { uploader.uploadToPath(any(), any()) } answers { Result.success("https://fb/" + secondArg<String>().substringAfterLast('/')) }
        cola = ColaEspia(db.proofOfSaleQueueDao())
        repo = ProofOfSaleQueueRepository(
            context, db, cola, db.actionableSaleCacheDao(), ProofOfSaleRemote(api), uploader, ProofCaptureSequence(context), storage,
        )
    }

    @After fun tearDown() {
        db.close()
        runCatching { servidor.shutdown() }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun foto(slot: ProofSlot = ProofSlot.VINCULACION, venue: String = "v1", payment: String = "pay-1", number: String? = "5512345678"): PhotoCapture {
        val camara = File(context.cacheDir, "cam-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return PhotoCapture(venue, null, "s1", payment, "ver-1", slot, camara.absolutePath, number)
    }

    private suspend fun fila(key: String) = db.proofOfSaleQueueDao().observeKey(key).first()

    private suspend fun guardada(payment: String, ranuras: String = "") =
        db.actionableSaleCacheDao().upsert(listOf(ActionableSaleCacheEntity(payment, "v1", "s1", "ver-1", "{}", ranuras, false, 1L)))

    private suspend fun ranuras(payment: String) = db.actionableSaleCacheDao().get(payment)!!.deliveredSlots

    private fun respuesta(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun json(code: Int, body: String) = servidor.enqueue(respuesta(code, body))

    /** Cada foto recibe la respuesta de SU pago, sin depender del orden en que se reclamen. */
    private fun porPago(respuestas: Map<String, MockResponse>, otro: MockResponse? = null) {
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return respuestas.entries.firstOrNull { body.contains("\"paymentId\":\"${it.key}\"") }?.value ?: otro!!
            }
        }
    }

    private fun ok(extra: String = """"linkingNumberOutcome":"APPLIED","linkingDuplicate":true""") =
        """{"success":true,"verificationId":"ver-1","status":"PENDING","message":"ok","outcome":"APPLIED","photoOutcome":"APPLIED",$extra}"""

    // ─── encolar ────────────────────────────────────────────────────────────────

    @Test fun `enqueuePhoto deja el archivo en filesDir antes de insertar y devuelve la llave sin red`() = runBlocking {
        var enDiscoAlInsertar: List<String>? = null
        cola.antesDeInsertar = { enDiscoAlInsertar = File(context.filesDir, "proof_of_sale").listFiles()?.map { it.absolutePath } }

        val key = repo.enqueuePhoto(foto())

        val f = fila(key)!!
        val esperado = File(context.filesDir, "proof_of_sale/$key.jpg").absolutePath
        assertThat(f.localPath).isEqualTo(esperado)
        assertThat(enDiscoAlInsertar).containsExactly(esperado)
        assertThat(File(esperado).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(f.kind).isEqualTo("PHOTO")
        assertThat(f.slot).isEqualTo("VINCULACION")
        assertThat(f.linkingNumber).isEqualTo("5512345678")
        assertThat(f.portedNumber).isNull()
        assertThat(f.syncStatus).isEqualTo("PENDING")
        assertThat(f.captureSeq).isEqualTo(1L)
        assertThat(f.captureDeviceId).isNotEmpty()
        assertThat(servidor.requestCount).isEqualTo(0)
        coVerify(exactly = 0) { uploader.uploadToPath(any(), any()) }
    }

    @Test fun `enqueuePhoto termina de guardar aunque cancelen a quien la llamo`() = runBlocking {
        lateinit var pantalla: Job
        cola.antesDeInsertar = { pantalla.cancel() } // el promotor salió de la pantalla justo al confirmar
        pantalla = launch { repo.enqueuePhoto(foto()) }
        pantalla.join()

        assertThat(db.proofOfSaleQueueDao().observeStaff("s1").first()).hasSize(1)
    }

    // ─── drenar: entregadas ─────────────────────────────────────────────────────

    @Test fun `drain sube la foto a su ruta y la entrega - borra archivo y fila, marca la ranura y avisa`() = runBlocking {
        guardada("pay-1")
        val key = repo.enqueuePhoto(foto())
        val f = fila(key)!!
        json(200, ok())

        repo.drain()

        val fecha = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("America/Mexico_City")).format(Instant.ofEpochMilli(f.capturedAt))
        coVerify(exactly = 1) { uploader.uploadToPath(f.localPath!!, "venues/tienda-a/proof-of-sale/$fecha/pay-1_VINCULACION_$key.jpg") }
        val req = servidor.takeRequest()
        assertThat(req.path).isEqualTo("/tpv/verification/proof-of-sale")
        val b = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertThat(b.get("photoUrls").asJsonArray[0].asString).isEqualTo("https://fb/pay-1_VINCULACION_$key.jpg")
        assertThat(b.get("idempotencyKey").asString).isEqualTo(key)
        assertThat(b.get("linkingNumber").asString).isEqualTo("5512345678")
        assertThat(fila(key)).isNull()
        assertThat(File(f.localPath!!).exists()).isFalse()
        assertThat(ranuras("pay-1")).isEqualTo("VINCULACION")
        assertThat(repo.awaitResult(key, 1_000)).isEqualTo(ProofSyncResult(key, "APPLIED", linkingDuplicate = true, portedDuplicate = false))
    }

    @Test fun `SUPERSEDED_BY_NEWER tambien se da por entregada y la ranura se suma a la que ya habia`() = runBlocking {
        guardada("pay-1", ranuras = "VINCULACION")
        val key = repo.enqueuePhoto(foto(slot = ProofSlot.PORTABILIDAD))
        json(200, """{"success":true,"verificationId":"ver-1","message":null,"outcome":"SUPERSEDED_BY_NEWER","photoOutcome":"SUPERSEDED_BY_NEWER"}""")

        repo.drain()

        assertThat(fila(key)).isNull()
        assertThat(ranuras("pay-1")).isEqualTo("VINCULACION,PORTABILIDAD")
        assertThat(repo.awaitResult(key, 1_000)?.outcome).isEqualTo("SUPERSEDED_BY_NEWER")
    }

    @Test fun `marcar la ranura y borrar la fila van juntos - si el proceso muere entre los dos no queda una sin la otra`() = runBlocking {
        guardada("pay-1")
        val key = repo.enqueuePhoto(foto())
        json(200, ok())
        cola.mueroAlBorrar = true

        repo.drain()

        assertThat(ranuras("pay-1")).isEmpty()
        val f = fila(key)!!
        assertThat(f.syncStatus).isEqualTo("PENDING")
        assertThat(File(f.localPath!!).exists()).isTrue()

        // Al revivir, la misma fila se vuelve a mandar (misma llave: el servidor la reconoce) y ahora sí quedan las dos.
        cola.mueroAlBorrar = false
        json(200, ok())
        repo.drain()
        assertThat(fila(key)).isNull()
        assertThat(ranuras("pay-1")).isEqualTo("VINCULACION")
    }

    // ─── drenar: lo que una persona tiene que ver ───────────────────────────────

    @Test fun `un numero DISABLED deja la foto entregada pero la fila en NEEDS_ATTENTION con NUMBER_DISABLED`() = runBlocking {
        guardada("pay-1")
        val key = repo.enqueuePhoto(foto())
        json(200, ok(""""linkingNumberOutcome":"DISABLED""""))

        repo.drain()

        val f = fila(key)!!
        assertThat(f.syncStatus).isEqualTo("NEEDS_ATTENTION")
        assertThat(f.outcome).isEqualTo("NUMBER_DISABLED")
        assertThat(File(f.localPath!!).exists()).isTrue()
        assertThat(ranuras("pay-1")).isEqualTo("VINCULACION")
        assertThat(repo.awaitResult(key, 1_000)?.outcome).isEqualTo("NUMBER_DISABLED")
    }

    @Test fun `SALE_ALREADY_APPROVED deja la fila en NEEDS_ATTENTION sin marcar la ranura`() = runBlocking {
        guardada("pay-1")
        val key = repo.enqueuePhoto(foto())
        json(200, """{"success":true,"verificationId":"ver-1","status":"COMPLETED","message":"ok","outcome":"SALE_ALREADY_APPROVED"}""")

        repo.drain()

        val f = fila(key)!!
        assertThat(f.syncStatus).isEqualTo("NEEDS_ATTENTION")
        assertThat(f.outcome).isEqualTo("SALE_ALREADY_APPROVED")
        assertThat(File(f.localPath!!).exists()).isTrue()
        assertThat(ranuras("pay-1")).isEmpty()
        assertThat(repo.awaitResult(key, 1_000)?.outcome).isEqualTo("SALE_ALREADY_APPROVED")
    }

    @Test fun `400 SALE_REJECTED queda FAILED permanente con su archivo, y solo Descartar lo borra`() = runBlocking {
        val key = repo.enqueuePhoto(foto())
        val pendiente = repo.enqueuePhoto(foto(payment = "pay-2"))
        porPago(
            mapOf(
                "pay-1" to respuesta(400, """{"success":false,"message":"Esta venta fue rechazada","code":"SALE_REJECTED"}"""),
                "pay-2" to respuesta(500, """{"message":"boom"}"""),
            ),
        )

        repo.drain()

        val f = fila(key)!!
        assertThat(f.syncStatus).isEqualTo("FAILED")
        assertThat(f.permanent).isTrue()
        assertThat(f.outcome).isEqualTo("SALE_REJECTED")
        assertThat(File(f.localPath!!).exists()).isTrue()
        assertThat(repo.awaitResult(key, 1_000)?.outcome).isEqualTo("SALE_REJECTED")

        repo.discard(pendiente) // PENDING: no se descarta
        assertThat(fila(pendiente)).isNotNull()
        repo.discard(key)
        assertThat(fila(key)).isNull()
        assertThat(File(f.localPath!!).exists()).isFalse()
    }

    @Test fun `un numero suelto con 422 LINKING_NUMBER_DISABLED queda NEEDS_ATTENTION aunque el status solo diria permanente`() = runBlocking {
        val key = repo.enqueueNumberOnly(NumberCapture("v1", "s1", "pay-1", "ver-1", ProofSlot.VINCULACION, "5512345678"))
        json(422, """{"message":"El número de vinculación no está activo en esta organización","code":"LINKING_NUMBER_DISABLED"}""")

        repo.drain()

        val f = fila(key)!!
        assertThat(f.syncStatus).isEqualTo("NEEDS_ATTENTION")
        assertThat(f.outcome).isEqualTo("NUMBER_DISABLED")
    }

    // ─── drenar: permanentes y reintentos ───────────────────────────────────────

    @Test fun `otro 400, un 422 sin code y una foto que ya no esta en la terminal quedan PERMANENT`() = runBlocking {
        val invalido = repo.enqueuePhoto(foto(payment = "p400"))
        val sinCode = repo.enqueuePhoto(foto(payment = "p422"))
        val sinArchivo = repo.enqueuePhoto(foto(payment = "pfile"))
        File(fila(sinArchivo)!!.localPath!!).delete()
        porPago(
            mapOf(
                "p400" to respuesta(400, """{"message":"El número de vinculación debe tener 9 o 10 dígitos","code":"INVALID_LINKING_NUMBER"}"""),
                "p422" to respuesta(422, """{"message":"regla"}"""),
            ),
        )

        repo.drain()

        for (key in listOf(invalido, sinCode, sinArchivo)) {
            val f = fila(key)!!
            assertThat(f.syncStatus).isEqualTo("FAILED")
            assertThat(f.permanent).isTrue()
            assertThat(f.outcome).isEqualTo("PERMANENT")
        }
        assertThat(servidor.requestCount).isEqualTo(2)
    }

    @Test fun `409 VERIFICATION_MOVED, 500, un 2xx vacio y sin red sueltan la fila para reintentar`() = runBlocking {
        val keys = listOf("p409", "p500", "pnull", "pred").associateWith { repo.enqueuePhoto(foto(payment = it)) }
        porPago(
            mapOf(
                "p409" to respuesta(409, """{"message":"La venta cambió de tienda","code":"VERIFICATION_MOVED"}"""),
                "p500" to respuesta(500, "<html>boom</html>"),
                "pnull" to respuesta(200, "null"),
                "pred" to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            ),
        )

        repo.drain()

        for (key in keys.values) {
            val f = fila(key)!!
            assertThat(f.syncStatus).isEqualTo("PENDING")
            assertThat(f.permanent).isFalse()
            assertThat(f.retryCount).isEqualTo(1)
            assertThat(File(f.localPath!!).exists()).isTrue()
        }
        assertThat(repo.awaitResult(keys.getValue("p409"), 50)).isNull() // «Se subirá sola»
    }

    @Test fun `sin tienda en la sesion o si Firebase falla se suelta sin llamar al servidor`() = runBlocking {
        val key = repo.enqueuePhoto(foto())
        every { storage.getVenueSlug() } returns null
        repo.drain()
        assertThat(fila(key)!!.syncStatus).isEqualTo("PENDING")
        coVerify(exactly = 0) { uploader.uploadToPath(any(), any()) }

        every { storage.getVenueSlug() } returns "tienda-a"
        coEvery { uploader.uploadToPath(any(), any()) } returns Result.failure(IOException("sin red"))
        repo.drain()
        val f = fila(key)!!
        assertThat(f.syncStatus).isEqualTo("PENDING")
        assertThat(f.retryCount).isEqualTo(2)
        assertThat(f.permanent).isFalse()
        assertThat(servidor.requestCount).isEqualTo(0)
    }

    @Test fun `NUMBER_ONLY no sube nada a Firebase, usa la ruta del numero y no marca ranura`() = runBlocking {
        guardada("pay-1")
        val key = repo.enqueueNumberOnly(NumberCapture("v1", "s1", "pay-1", "ver-1", ProofSlot.PORTABILIDAD, "5587654321"))
        val f = fila(key)!!
        assertThat(f.kind).isEqualTo("NUMBER_ONLY")
        assertThat(f.localPath).isNull()
        assertThat(f.portedNumber).isEqualTo("5587654321")
        assertThat(f.linkingNumber).isNull()
        json(200, """{"success":true,"verificationId":"ver-1","message":"Número guardado","outcome":"APPLIED","portedNumberOutcome":"APPLIED","portedDuplicate":true}""")

        repo.drain()

        val req = servidor.takeRequest()
        assertThat(req.path).isEqualTo("/tpv/verification/ver-1/number")
        assertThat(JsonParser.parseString(req.body.readUtf8()).asJsonObject.get("number").asString).isEqualTo("5587654321")
        coVerify(exactly = 0) { uploader.uploadToPath(any(), any()) }
        assertThat(fila(key)).isNull()
        assertThat(ranuras("pay-1")).isEmpty()
        assertThat(repo.awaitResult(key, 1_000)).isEqualTo(ProofSyncResult(key, "APPLIED", linkingDuplicate = false, portedDuplicate = true))
    }

    // ─── drenar: tiendas y organizaciones ───────────────────────────────────────

    @Test fun `una foto guardada en otra tienda se sube con la sesion de la tienda nueva`() = runBlocking {
        every { storage.getVenueId() } returns "vB"
        every { storage.getVenueSlug() } returns "tienda-b"
        val key = repo.enqueuePhoto(foto(venue = "vA"))
        json(200, ok())

        repo.drain()

        coVerify(exactly = 1) { uploader.uploadToPath(any(), match { it.startsWith("venues/tienda-b/proof-of-sale/") }) }
        assertThat(fila(key)).isNull()
    }

    @Test fun `un 404 de otra tienda se suelta esperando su sesion, uno de la tienda de la sesion es permanente`() = runBlocking {
        val ajena = repo.enqueuePhoto(foto(venue = "otra-org", payment = "pA"))
        val propia = repo.enqueuePhoto(foto(venue = "v1", payment = "pB"))
        val noEsta = respuesta(404, """{"success":false,"message":"Payment not found in organization"}""")
        porPago(mapOf("pA" to noEsta, "pB" to noEsta))

        repo.drain()

        val a = fila(ajena)!!
        assertThat(a.syncStatus).isEqualTo("PENDING")
        assertThat(a.permanent).isFalse()
        assertThat(a.lastError).isEqualTo("Esperando una sesión de su tienda")
        val b = fila(propia)!!
        assertThat(b.syncStatus).isEqualTo("FAILED")
        assertThat(b.permanent).isTrue()
        assertThat(b.outcome).isEqualTo("PERMANENT")
    }

    @Test fun `diez fotos viejas de otra organizacion que siempre fallan no tapan una nueva de la sesion`() = runBlocking {
        repeat(10) { i ->
            val archivo = File(context.filesDir, "vieja$i.jpg").apply { writeBytes(byteArrayOf(9)) }
            db.proofOfSaleQueueDao().insertRow(
                ProofOfSaleQueueEntity(
                    idempotencyKey = "vieja-$i", kind = "PHOTO", venueId = "otra-org", staffId = "s1", paymentId = "pv$i",
                    verificationId = null, slot = "VINCULACION", localPath = archivo.absolutePath, linkingNumber = "5512345678",
                    portedNumber = null, capturedAt = i.toLong(), captureSeq = i.toLong(), captureDeviceId = "dev",
                    retryCount = 3, createdAt = i.toLong(),
                ),
            )
        }
        val nueva = repo.enqueuePhoto(foto(payment = "nueva"))
        porPago(mapOf("nueva" to respuesta(200, ok())), otro = respuesta(404, """{"message":"Payment not found in organization"}"""))

        repo.drain()

        assertThat(fila(nueva)).isNull()
        assertThat(fila("vieja-0")!!.lastError).isEqualTo("Esperando una sesión de su tienda")
    }

    @Test fun `una foto que truena no frena a las demas`() = runBlocking {
        val mala = repo.enqueuePhoto(foto(payment = "pA"))
        val buena = repo.enqueuePhoto(foto(payment = "pB"))
        coEvery { uploader.uploadToPath(any(), match { it.contains("/pA_") }) } throws IllegalStateException("decodificador roto")
        porPago(mapOf("pB" to respuesta(200, ok())))

        repo.drain()

        assertThat(fila(mala)!!.syncStatus).isEqualTo("PENDING")
        assertThat(fila(buena)).isNull()
    }
}
