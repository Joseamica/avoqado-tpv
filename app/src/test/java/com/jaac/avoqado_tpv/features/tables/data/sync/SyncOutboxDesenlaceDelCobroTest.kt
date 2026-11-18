package com.jaac.avoqado_tpv.features.tables.data.sync

import android.app.Application
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

/**
 * D19 (`na78d478`, 18-nov, PAX del Alien): el efectivo de Mesas decía «Cobro guardado · Cobrado $203» con el cobro RECHAZADO
 * por el SERVIDOR. Otro reproductor de la cola (el plano o el trabajador) mandó el PAY_CASH mientras «Cobrar» esperaba el
 * candado; cuando «Cobrar» lo tuvo, su lote ya no traía ese cobro y, sin acuse propio, contestaba «guardado».
 *
 * [SyncOutbox.reproducirYResolver] decide, BAJO el mismo candado, qué contestó el SERVIDOR de ESE intent: el acuse de la
 * pasada propia (de la respuesta completa, se haya podido guardar o no) o, si no lo hubo, su fila en la cola. Room real bajo
 * Robolectric; las fallas de la base se inyectan con un `spyk` del DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SyncOutboxDesenlaceDelCobroTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val secureStorage = mockk<SecureStorage>().also { every { it.getSerialNumber() } returns "AVQD-2841548417" }
    private val api = mockk<TablesApiService>()
    private val enviados = mutableListOf<SyncIntentsRequest>()
    private val db: AvoqadoDatabase = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao: SyncIntentDao = spyk(db.syncIntentDao())
    private val outbox = SyncOutbox(dao, api, secureStorage, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())

    @After
    fun cerrar() = db.close()

    /** El servidor contesta cada envío con [responde]; una excepción simula la red caída o la respuesta perdida. */
    private fun servidor(responde: (SyncIntentsRequest) -> List<SyncIntentAck>) {
        coEvery { api.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            enviados += request
            Response.success(SyncIntentsResponse(data = responde(request)))
        }
    }

    private fun acepta(id: String) = SyncIntentAck(id, "ACKED", result = JsonObject().apply {
        addProperty("paymentId", "pago-$id")
        addProperty("orderNumber", "ORD-7770")
    })

    private fun rechaza(id: String, message: String? = "Order not found") =
        SyncIntentAck(id, "REJECTED", errorCode = "BUSINESS_RULE", message = message)

    private fun stale(id: String, latestSeq: Long) = SyncIntentAck(
        id = id,
        status = "REJECTED",
        errorCode = "STALE_DEVICE_SEQUENCE",
        message = "La secuencia del dispositivo ya fue utilizada. Requiere revisión antes de repetir la operación.",
        details = JsonObject().apply { addProperty("latestSeq", latestSeq) },
    )

    private suspend fun encola(type: String = SyncIntentTypes.PAY_CASH): String = outbox.enqueue(VENUE, type, JsonObject())
    private suspend fun fila(id: String): SyncIntentEntity = db.syncIntentDao().allForVenue(VENUE).first { it.id == id }

    // region — otro reproductor ya mandó el cobro y GUARDÓ la respuesta (el caso que se reprodujo en la PAX)

    @Test
    fun `P1 otro reproductor guardo el rechazo antes de que Cobrar tomara el candado - rechazado, nunca guardado`() = runTest {
        servidor { request -> request.intents.map { rechaza(it.id) } }
        val cobro = encola()
        outbox.replayNow(VENUE) // el plano o el trabajador, antes que «Cobrar»

        val desenlace = outbox.reproducirYResolver(VENUE, cobro)

        assertThat(desenlace).isEqualTo(SyncOutbox.DesenlaceDelIntent.Rechazado("BUSINESS_RULE", "Order not found"))
    }

    @Test
    fun `otro reproductor guardo la aceptacion - registrado con lo que contesto el servidor`() = runTest {
        servidor { request -> request.intents.map { acepta(it.id) } }
        val cobro = encola()
        outbox.replayNow(VENUE)

        val desenlace = outbox.reproducirYResolver(VENUE, cobro)

        assertThat(desenlace).isInstanceOf(SyncOutbox.DesenlaceDelIntent.Aceptado::class.java)
        val result = (desenlace as SyncOutbox.DesenlaceDelIntent.Aceptado).result
        assertThat(result?.get("paymentId")?.asString).isEqualTo("pago-$cobro")
        assertThat(result?.get("orderNumber")?.asString).isEqualTo("ORD-7770")
    }

    @Test
    fun `un rechazo que el gerente ya marco resuelto sigue siendo rechazo`() = runTest {
        servidor { request -> request.intents.map { rechaza(it.id) } }
        val cobro = encola()
        outbox.replayNow(VENUE)
        outbox.dismissRejected(VENUE, cobro)

        assertThat(outbox.reproducirYResolver(VENUE, cobro))
            .isEqualTo(SyncOutbox.DesenlaceDelIntent.Rechazado("BUSINESS_RULE", "Order not found"))
    }

    // endregion

    // region — la pasada propia

    @Test
    fun `sin red el cobro sigue en espera`() = runTest {
        coEvery { api.syncIntents(VENUE, any()) } throws IOException("sin red")
        val cobro = encola()

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isEqualTo(SyncOutbox.DesenlaceDelIntent.EnEspera)
        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_PENDING)
    }

    @Test
    fun `un RETRY nunca es la ultima palabra - sigue en espera`() = runTest {
        servidor { request -> request.intents.map { SyncIntentAck(it.id, "RETRY", errorCode = "VERSION_CONFLICT") } }
        val cobro = encola()

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isEqualTo(SyncOutbox.DesenlaceDelIntent.EnEspera)
    }

    @Test
    fun `P1 el rechazo propio que la base no deja guardar sigue siendo rechazo`() = runTest {
        servidor { request -> request.intents.map { rechaza(it.id) } }
        val cobro = encola()
        coEvery { dao.resolve(cobro, any(), any(), any(), any()) } throws SQLiteFullException("disco lleno")

        assertThat(outbox.reproducirYResolver(VENUE, cobro))
            .isEqualTo(SyncOutbox.DesenlaceDelIntent.Rechazado("BUSINESS_RULE", "Order not found"))
        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_PENDING) // se reenvía después; el servidor deduplica
    }

    @Test
    fun `la aceptacion propia que la base no deja guardar sigue siendo aceptacion`() = runTest {
        servidor { request -> request.intents.map { acepta(it.id) } }
        val cobro = encola()
        coEvery { dao.resolve(cobro, any(), any(), any(), any()) } throws SQLiteFullException("disco lleno")

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isInstanceOf(SyncOutbox.DesenlaceDelIntent.Aceptado::class.java)
    }

    @Test
    fun `P1 el acuse del cobro detras de una fila que la base no deja guardar se toma de la respuesta completa`() = runTest {
        servidor { request -> request.intents.map { if (it.type == SyncIntentTypes.PAY_CASH) rechaza(it.id) else acepta(it.id) } }
        val ronda = encola(SyncIntentTypes.ADD_ITEMS)
        val cobro = encola()
        coEvery { dao.resolve(ronda, any(), any(), any(), any()) } throws SQLiteFullException("disco lleno")

        assertThat(outbox.reproducirYResolver(VENUE, cobro))
            .isEqualTo(SyncOutbox.DesenlaceDelIntent.Rechazado("BUSINESS_RULE", "Order not found"))
    }

    @Test
    fun `si la base no dejo guardar un acuse de la pasada y el cobro quedo en espera - no se sabe`() = runTest {
        // El servidor contestó la ronda y cortó en RETRY antes del cobro; la ronda no se pudo guardar: la cola ya no es de fiar.
        servidor { request ->
            request.intents.map { if (it.type == SyncIntentTypes.PAY_CASH) SyncIntentAck(it.id, "RETRY") else acepta(it.id) }
        }
        val ronda = encola(SyncIntentTypes.ADD_ITEMS)
        val cobro = encola()
        coEvery { dao.resolve(ronda, any(), any(), any(), any()) } throws SQLiteFullException("disco lleno")

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isInstanceOf(SyncOutbox.DesenlaceDelIntent.NoSeSabe::class.java)
    }

    @Test
    fun `una fila anterior ya descartada por su ruta en linea NO es una falla - el cobro sigue en espera`() = runTest {
        // Codex r3 #4: el `0` filas de `resolve` es normal (la ronda ya salió por su ruta en línea y se descartó).
        val ronda = encola(SyncIntentTypes.ADD_ITEMS)
        val cobro = encola()
        coEvery { api.syncIntents(VENUE, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>()
            db.syncIntentDao().discardPending(ronda) // mientras el lote viajaba
            Response.success(SyncIntentsResponse(data = request.intents.map {
                if (it.type == SyncIntentTypes.PAY_CASH) SyncIntentAck(it.id, "RETRY") else acepta(it.id)
            }))
        }

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isEqualTo(SyncOutbox.DesenlaceDelIntent.EnEspera)
    }

    @Test
    fun `si no se puede leer el lote de la cola no sale una excepcion - no se sabe`() = runTest {
        val cobro = encola()
        coEvery { dao.pendingBatchForVenue(VENUE, any()) } throws SQLiteFullException("base dañada")

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isInstanceOf(SyncOutbox.DesenlaceDelIntent.NoSeSabe::class.java)
    }

    @Test
    fun `si no se puede leer la fila del cobro - no se sabe`() = runTest {
        coEvery { api.syncIntents(VENUE, any()) } throws IOException("sin red")
        val cobro = encola()
        coEvery { dao.leer(VENUE, cobro) } throws SQLiteFullException("base dañada")

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isInstanceOf(SyncOutbox.DesenlaceDelIntent.NoSeSabe::class.java)
    }

    @Test
    fun `un cobro de otra sucursal no se confunde - no se sabe`() = runTest {
        coEvery { api.syncIntents(any(), any()) } throws IOException("sin red")
        val cobro = encola()

        assertThat(outbox.reproducirYResolver("otra-sucursal", cobro)).isInstanceOf(SyncOutbox.DesenlaceDelIntent.NoSeSabe::class.java)
    }

    // endregion

    // region — D6: un rechazo por número sólo cuenta cuando queda como rechazo

    @Test
    fun `D6 renumera y el reenvio se acepta - registrado, no el rechazo por numero`() = runTest {
        var visto = 40L
        servidor { request ->
            request.intents.map { if ((it.seq ?: 0L) <= visto) stale(it.id, visto) else acepta(it.id).also { _ -> visto = it.seq!! } }
        }
        val cobro = encola()

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isInstanceOf(SyncOutbox.DesenlaceDelIntent.Aceptado::class.java)
    }

    @Test
    fun `D6 renumera y el reenvio pierde la respuesta - sigue en espera, no el rechazo por numero`() = runTest {
        var llamadas = 0
        coEvery { api.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            llamadas++
            if (llamadas == 1) Response.success(SyncIntentsResponse(data = request.intents.map { stale(it.id, 40) }))
            else throw IOException("respuesta perdida")
        }
        val cobro = encola()

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isEqualTo(SyncOutbox.DesenlaceDelIntent.EnEspera)
        assertThat(fila(cobro).seq).isEqualTo(41L)
    }

    @Test
    fun `D6 renumera y el reenvio queda en RETRY - sigue en espera`() = runTest {
        var llamadas = 0
        servidor { request ->
            llamadas++
            if (llamadas == 1) request.intents.map { stale(it.id, 40) } else request.intents.map { SyncIntentAck(it.id, "RETRY") }
        }
        val cobro = encola()

        assertThat(outbox.reproducirYResolver(VENUE, cobro)).isEqualTo(SyncOutbox.DesenlaceDelIntent.EnEspera)
    }

    @Test
    fun `D6 el rechazo por numero que se guarda como rechazo si cuenta`() = runTest {
        // El reenvío vuelve a chocar (otra instalación sigue adelante): D6 ya no renumera otra vez y lo guarda como rechazo.
        servidor { request -> request.intents.map { stale(it.id, 1000) } }
        val cobro = encola()

        val desenlace = outbox.reproducirYResolver(VENUE, cobro)

        assertThat(desenlace).isInstanceOf(SyncOutbox.DesenlaceDelIntent.Rechazado::class.java)
        assertThat((desenlace as SyncOutbox.DesenlaceDelIntent.Rechazado).errorCode).isEqualTo("STALE_DEVICE_SEQUENCE")
        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    // endregion

    // region — una respuesta que ya llegó se guarda completa (Codex r3 #1)

    @Test
    fun `P1 el trabajador cancelado a media respuesta igual guarda el rechazo del cobro`() = runTest {
        servidor { request -> request.intents.map { if (it.type == SyncIntentTypes.PAY_CASH) rechaza(it.id) else acepta(it.id) } }
        val ronda = encola(SyncIntentTypes.ADD_ITEMS)
        val cobro = encola()

        lateinit var trabajador: Job
        trabajador = launch {
            outbox.replayNow(VENUE) { intent, _ -> if (intent.id == ronda) trabajador.cancel() } // WorkManager lo detiene
        }
        trabajador.join()

        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
        coEvery { api.syncIntents(VENUE, any()) } throws IOException("sin red") // «Cobrar» ya sin red
        assertThat(outbox.reproducirYResolver(VENUE, cobro))
            .isEqualTo(SyncOutbox.DesenlaceDelIntent.Rechazado("BUSINESS_RULE", "Order not found"))
    }

    // endregion

    // region — el replay de siempre no cambia

    @Test
    fun `los demas acuses siguen yendo al callback de siempre y la cola se drena en orden`() = runTest {
        servidor { request -> request.intents.map { acepta(it.id) } }
        val abrir = encola(SyncIntentTypes.OPEN_TABLE)
        val ronda = encola(SyncIntentTypes.ADD_ITEMS)
        val cobro = encola()

        outbox.reproducirYResolver(VENUE, cobro)

        assertThat(enviados.single().intents.map { it.id }).containsExactly(abrir, ronda, cobro).inOrder()
        assertThat(listOf(abrir, ronda, cobro).map { fila(it).status }.distinct()).containsExactly(SyncIntentEntity.STATUS_ACKED)
    }

    // endregion

    private companion object {
        const val VENUE = "venue-1"
    }
}
