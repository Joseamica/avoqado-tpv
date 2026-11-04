package com.jaac.avoqado_tpv.features.verification.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.firebase.UnreadablePhotoException
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.verification.data.local.ActionableSaleCacheEntity
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueDao
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import com.jaac.avoqado_tpv.features.verification.data.remote.ProofOfSaleRemote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
        var antesDeInsertar: suspend () -> Unit = {}
        var despuesDeInsertar: suspend () -> Unit = {}
        var mueroAlBorrar = false
        override suspend fun insertRow(row: ProofOfSaleQueueEntity): Long {
            antesDeInsertar()
            return real.insertRow(row).also { despuesDeInsertar() }
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
        every { storage.getStaffId() } returns "s1"
        coEvery { uploader.uploadToPath(any(), any()) } answers { Result.success("https://fb/" + secondArg<String>().substringAfterLast('/')) }
        mockkObject(PaymentSyncScheduler)
        every { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) } returns Unit
        every { PaymentSyncScheduler.runNow(any()) } returns Unit
        cola = ColaEspia(db.proofOfSaleQueueDao())
        repo = ProofOfSaleQueueRepository(
            context, db, cola, db.actionableSaleCacheDao(), ProofOfSaleRemote(api), uploader, ProofCaptureSequence(context), storage,
        )
    }

    @After fun tearDown() {
        unmockkObject(PaymentSyncScheduler)
        db.close()
        runCatching { servidor.shutdown() }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun foto(
        slot: ProofSlot = ProofSlot.VINCULACION, venue: String = "v1", payment: String = "pay-1", number: String? = "5512345678",
        slug: String? = null, staff: String = "s1",
    ): PhotoCapture {
        val camara = File(context.cacheDir, "cam-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return PhotoCapture(venue, slug, staff, payment, "ver-1", slot, camara.absolutePath, number)
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

    @Test fun `enqueuePhoto inserta la fila reservada ANTES de copiar el archivo y la libera al final, sin red`() = runBlocking {
        var enDiscoAlInsertar: List<String>? = null
        var filaAlInsertar: ProofOfSaleQueueEntity? = null
        var reclamablesAlInsertar: List<ProofOfSaleQueueEntity>? = null
        cola.despuesDeInsertar = {
            val dir = File(context.filesDir, "proof_of_sale")
            enDiscoAlInsertar = dir.listFiles()?.map { it.absolutePath }.orEmpty()
            filaAlInsertar = db.proofOfSaleQueueDao().observeStaff("s1").first().single()
            // Ningún worker puede subir un archivo a medio copiar: la fila ya está tomada.
            reclamablesAlInsertar = System.currentTimeMillis().let { db.proofOfSaleQueueDao().claimBatch(10, "otro-worker", it, it - 10 * 60 * 1000L) }
        }

        val key = repo.enqueuePhoto(foto(slug = "tienda-x"))

        val esperado = File(context.filesDir, "proof_of_sale/$key.jpg").absolutePath
        assertThat(enDiscoAlInsertar).isEmpty() // la fila va primero: el archivo todavía no existe
        assertThat(filaAlInsertar!!.localPath).isEqualTo(esperado)
        assertThat(filaAlInsertar!!.syncStatus).isEqualTo("SYNCING")
        assertThat(filaAlInsertar!!.claimToken).isNotNull()
        assertThat(filaAlInsertar!!.claimedAt).isNotNull()
        assertThat(reclamablesAlInsertar).isEmpty()

        val f = fila(key)!!
        assertThat(f.localPath).isEqualTo(esperado)
        assertThat(f.venueSlug).isEqualTo("tienda-x")
        assertThat(f.photoUrl).isNull()
        // Pasada encadenada (APPEND): una foto que llega con el worker a media corrida no espera 15 minutos.
        verify(exactly = 1) { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) }
        verify(exactly = 0) { PaymentSyncScheduler.runNow(any()) }
        assertThat(File(esperado).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(context.filesDir, "proof_of_sale").listFiles()!!.map { it.name }).containsExactly("$key.jpg")
        assertThat(f.kind).isEqualTo("PHOTO")
        assertThat(f.slot).isEqualTo("VINCULACION")
        assertThat(f.linkingNumber).isEqualTo("5512345678")
        assertThat(f.portedNumber).isNull()
        // Liberada: PENDING y sin reclamo, y ya la puede reclamar un worker.
        assertThat(f.syncStatus).isEqualTo("PENDING")
        assertThat(f.claimToken).isNull()
        assertThat(f.claimedAt).isNull()
        assertThat(f.captureSeq).isEqualTo(1L)
        assertThat(f.captureDeviceId).isNotEmpty()
        assertThat(db.proofOfSaleQueueDao().claimBatch(10, "worker", System.currentTimeMillis(), 0L).map { it.idempotencyKey })
            .containsExactly(key)
        assertThat(servidor.requestCount).isEqualTo(0)
        coVerify(exactly = 0) { uploader.uploadToPath(any(), any()) }
    }

    @Test fun `si la copia falla no queda fila ni archivo a medias, se avisa con la excepcion y la foto de la camara sigue`() = runBlocking {
        val camara = File(context.cacheDir, "cam-borrada.jpg") // no existe: copyTo lanza
        val c = PhotoCapture("v1", null, "s1", "pay-1", "ver-1", ProofSlot.VINCULACION, camara.absolutePath, "5512345678")

        val e = runCatching { repo.enqueuePhoto(c) }.exceptionOrNull()

        assertThat(e).isInstanceOf(java.io.IOException::class.java)
        assertThat(db.proofOfSaleQueueDao().observeStaff("s1").first()).isEmpty()
        assertThat(File(context.filesDir, "proof_of_sale").listFiles().orEmpty().toList()).isEmpty()
        verify(exactly = 0) { PaymentSyncScheduler.continuarDespuesDeEstaPasada(any()) }
    }

    @Test fun `si el contador de capturas no se puede guardar no se toca el archivo ni la cola`() = runBlocking {
        val secuencia = mockk<ProofCaptureSequence>()
        every { secuencia.next() } throws java.io.IOException("No se pudo guardar el contador de capturas")
        every { secuencia.deviceId() } returns "d1"
        val repo2 = ProofOfSaleQueueRepository(
            context, db, cola, db.actionableSaleCacheDao(), ProofOfSaleRemote(
                Retrofit.Builder().baseUrl(servidor.url("/")).addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java),
            ), uploader, secuencia, storage,
        )

        val e = runCatching { repo2.enqueuePhoto(foto()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(java.io.IOException::class.java)
        assertThat(db.proofOfSaleQueueDao().observeStaff("s1").first()).isEmpty()
        assertThat(File(context.filesDir, "proof_of_sale").listFiles().orEmpty().toList()).isEmpty()
    }

    @Test fun `una fila reservada cuyo archivo nunca llego (murio el proceso) vence a los 10 min y falla definitiva y visible`() = runBlocking {
        val dir = File(context.filesDir, "proof_of_sale").apply { mkdirs() }
        val ahora = System.currentTimeMillis()
        fun reservada(key: String, claimedAt: Long) = ProofOfSaleQueueEntity(
            idempotencyKey = key, kind = "PHOTO", venueId = "v1", staffId = "s1", paymentId = "pay-1", verificationId = "ver-1",
            slot = "VINCULACION", localPath = File(dir, "$key.jpg").absolutePath, linkingNumber = "5512345678", portedNumber = null,
            capturedAt = ahora, captureSeq = 1, captureDeviceId = "d1", syncStatus = "SYNCING", claimToken = "muerto",
            claimedAt = claimedAt, createdAt = ahora,
        )
        db.proofOfSaleQueueDao().insertRow(reservada("fresca", ahora))
        db.proofOfSaleQueueDao().insertRow(reservada("vencida", ahora - 11 * 60 * 1000L))

        repo.drain()

        assertThat(fila("fresca")!!.syncStatus).isEqualTo("SYNCING") // todavía dentro de su lease: nadie la toca
        val v = fila("vencida")!!
        assertThat(v.syncStatus).isEqualTo("FAILED")
        assertThat(v.permanent).isTrue()
        assertThat(v.outcome).isEqualTo("PERMANENT")
        assertThat(servidor.requestCount).isEqualTo(0)
        coVerify(exactly = 0) { uploader.uploadToPath(any(), any()) }
        // «Descartar» la quita (sigue visible en Pendientes hasta que alguien la descarte).
        repo.discard("vencida")
        assertThat(fila("vencida")).isNull()
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
        // C7: el resultado trae la verificación de la venta (con ella number-check no choca con esta misma venta).
        assertThat(repo.awaitResult(key, 1_000))
            .isEqualTo(ProofSyncResult(key, "APPLIED", linkingDuplicate = true, portedDuplicate = false, verificationId = "ver-1"))
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
        assertThat(repo.awaitResult(key, 1_000))
            .isEqualTo(ProofSyncResult(key, "APPLIED", linkingDuplicate = false, portedDuplicate = true, verificationId = "ver-1"))
    }

    // ─── drenar: tiendas y organizaciones ───────────────────────────────────────

    @Test fun `una foto guardada en otra tienda se sube con la sesion de la tienda nueva, en la carpeta de SU tienda`() = runBlocking {
        every { storage.getVenueId() } returns "vB"
        every { storage.getVenueSlug() } returns "tienda-b"
        val conSlug = repo.enqueuePhoto(foto(venue = "vA", payment = "pA", slug = "tienda-a"))
        val sinSlug = repo.enqueuePhoto(foto(venue = "vA", payment = "pV")) // fila sin slug: la de la sesión
        porPago(emptyMap(), otro = respuesta(200, ok()))

        repo.drain()

        coVerify(exactly = 1) { uploader.uploadToPath(any(), match { it.startsWith("venues/tienda-a/proof-of-sale/") && it.contains("/pA_") }) }
        coVerify(exactly = 1) { uploader.uploadToPath(any(), match { it.startsWith("venues/tienda-b/proof-of-sale/") && it.contains("/pV_") }) }
        assertThat(fila(conSlug)).isNull()
        assertThat(fila(sinSlug)).isNull()
    }

    @Test fun `un 404 de otra tienda o de otro promotor se suelta esperando su sesion, uno de la sesion es permanente`() = runBlocking {
        val ajena = repo.enqueuePhoto(foto(venue = "otra-org", payment = "pA"))
        val propia = repo.enqueuePhoto(foto(venue = "v1", payment = "pB"))
        val deOtro = repo.enqueuePhoto(foto(venue = "v1", payment = "pC", staff = "otro-promotor"))
        val noEsta = respuesta(404, """{"success":false,"message":"Payment not found in organization"}""")
        porPago(mapOf("pA" to noEsta, "pB" to noEsta, "pC" to noEsta))

        repo.drain()

        val a = fila(ajena)!!
        assertThat(a.syncStatus).isEqualTo("PENDING")
        assertThat(a.permanent).isFalse()
        assertThat(a.lastError).isEqualTo("Esperando una sesión de su tienda")
        val c = fila(deOtro)!!
        assertThat(c.syncStatus).isEqualTo("PENDING")
        assertThat(c.permanent).isFalse()
        assertThat(c.lastError).isEqualTo("Esperando la sesión de quien la tomó")
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
        // Un fallo inesperado que NO es del archivo (el de la foto ilegible es permanente, prueba de abajo).
        coEvery { uploader.uploadToPath(any(), match { it.contains("/pA_") }) } throws IllegalStateException("Firebase se cayó")
        porPago(mapOf("pB" to respuesta(200, ok())))

        repo.drain()

        assertThat(fila(mala)!!.syncStatus).isEqualTo("PENDING")
        assertThat(fila(buena)).isNull()
    }

    // ─── ronda de arreglos 1 ────────────────────────────────────────────────────

    @Test fun `la foto ya subida a Firebase no se vuelve a subir cuando se reintenta el POST`() = runBlocking {
        val key = repo.enqueuePhoto(foto())
        val url = "https://fb/pay-1_VINCULACION_$key.jpg"
        json(500, """{"message":"boom"}""")
        repo.drain()
        assertThat(fila(key)!!.photoUrl).isEqualTo(url)

        json(200, ok())
        repo.drain()

        coVerify(exactly = 1) { uploader.uploadToPath(any(), any()) }
        servidor.takeRequest()
        val reintento = JsonParser.parseString(servidor.takeRequest().body.readUtf8()).asJsonObject
        assertThat(reintento.get("photoUrls").asJsonArray[0].asString).isEqualTo(url)
        assertThat(fila(key)).isNull()
    }

    @Test fun `si otro worker se quedo con la fila mientras subia a Firebase, no hace el POST y se la deja a su nuevo dueno`() = runBlocking {
        val key = repo.enqueuePhoto(foto())
        coEvery { uploader.uploadToPath(any(), any()) } answers {
            // Venció el lease a media subida y otro worker reclamó la fila.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE proof_of_sale_queue SET claim_token = 'otro' WHERE idempotency_key = ?", arrayOf<Any>(key),
            )
            Result.success("https://fb/x.jpg")
        }
        json(200, ok())

        assertThat(repo.drain(limit = 1)).isFalse()

        assertThat(servidor.requestCount).isEqualTo(0)
        val f = fila(key)!!
        assertThat(f.claimToken).isEqualTo("otro")
        assertThat(f.syncStatus).isEqualTo("SYNCING")
        assertThat(f.photoUrl).isNull()
        assertThat(f.retryCount).isEqualTo(0)
        assertThat(repo.awaitResult(key, 200)).isNull()
    }

    @Test fun `una foto vacia o que no se puede leer queda PERMANENT y no se reintenta`() = runBlocking {
        val vacia = repo.enqueuePhoto(foto(payment = "pvacia"))
        File(fila(vacia)!!.localPath!!).writeBytes(ByteArray(0))
        val ilegible = repo.enqueuePhoto(foto(payment = "pilegible"))
        coEvery { uploader.uploadToPath(any(), match { it.contains("/pilegible_") }) } returns
            Result.failure(UnreadablePhotoException("La foto de la terminal no se puede leer"))

        repo.drain()

        for (key in listOf(vacia, ilegible)) {
            val f = fila(key)!!
            assertThat(f.syncStatus).isEqualTo("FAILED")
            assertThat(f.permanent).isTrue()
            assertThat(f.outcome).isEqualTo("PERMANENT")
        }
        coVerify(exactly = 0) { uploader.uploadToPath(any(), match { it.contains("/pvacia_") }) }
        assertThat(servidor.requestCount).isEqualTo(0)
    }

    @Test fun `uploadToPath real dice ilegible a la foto local que no se puede leer, sin tocar Firebase`() = runBlocking {
        val r = VerificationUploadManager(context).uploadToPath(File(context.filesDir, "no-existe.jpg").absolutePath, "venues/x/y.jpg")
        assertThat(r.exceptionOrNull()).isInstanceOf(UnreadablePhotoException::class.java)
    }

    @Test fun `drain borra los archivos huerfanos de mas de una hora y nada mas`() = runBlocking {
        val dir = File(context.filesDir, "proof_of_sale").apply { mkdirs() }
        val hace2h = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        val huerfanoViejo = File(dir, "viejo.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(hace2h) }
        val huerfanoNuevo = File(dir, "a-medio-encolar.jpg").apply { writeBytes(byteArrayOf(1)) }
        val enCola = File(dir, "en-cola.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(hace2h) }
        db.proofOfSaleQueueDao().insertRow(
            ProofOfSaleQueueEntity(
                idempotencyKey = "en-cola", kind = "PHOTO", venueId = "v1", staffId = "s1", paymentId = "p1", verificationId = null,
                slot = "VINCULACION", localPath = enCola.absolutePath, linkingNumber = "5512345678", portedNumber = null,
                capturedAt = 1, captureSeq = 1, captureDeviceId = "dev", syncStatus = "NEEDS_ATTENTION", createdAt = 1,
            ),
        )

        repo.drain()

        assertThat(huerfanoViejo.exists()).isFalse()
        assertThat(huerfanoNuevo.exists()).isTrue()
        assertThat(enCola.exists()).isTrue()
    }

    @Test fun `drain dice que hay mas solo si llena la tanda y avanza, nunca con filas que siempre se sueltan`() = runBlocking {
        repeat(3) { repo.enqueuePhoto(foto(payment = "p$it")) }
        porPago(emptyMap(), otro = respuesta(200, ok()))
        assertThat(repo.drain(limit = 2)).isTrue()
        assertThat(repo.drain(limit = 2)).isFalse()

        repeat(2) { repo.enqueuePhoto(foto(venue = "otra-org", payment = "x$it")) }
        porPago(emptyMap(), otro = respuesta(404, """{"message":"Payment not found in organization"}"""))
        assertThat(repo.drain(limit = 2)).isFalse() // tanda llena pero sin avance: otra pasada sería un bucle
    }
}
