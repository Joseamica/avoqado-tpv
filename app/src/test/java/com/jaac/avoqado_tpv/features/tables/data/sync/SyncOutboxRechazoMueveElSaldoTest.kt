package com.jaac.avoqado_tpv.features.tables.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.math.BigDecimal

/**
 * D8 (Codex r7): una ronda mandada sin red entra al saldo de la cuenta. Si al sincronizar el SERVIDOR la rechaza (p. ej. la
 * persona puede cobrar pero no crear órdenes), ya no se debe: el saldo queda por confirmar y el cobro relee, venga de donde
 * venga el replay (el del coordinador o el que hace el repositorio al cobrar). Con la cola y la sesión reales.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SyncOutboxRechazoMueveElSaldoTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val secureStorage = mockk<SecureStorage>().also { every { it.getSerialNumber() } returns "AVQD-2841548417" }
    private val api = mockk<TablesApiService>()
    private val db: AvoqadoDatabase = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.syncIntentDao()
    private val session = TableSession()
    private val outbox = SyncOutbox(
        dao,
        api,
        secureStorage,
        shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(),
        tableSession = session,
    )

    @After
    fun cerrar() = db.close()

    private fun servidorQue(status: String, errorCode: String? = null) {
        coEvery { api.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            Response.success(SyncIntentsResponse(data = request.intents.map { SyncIntentAck(it.id, status, errorCode = errorCode) }))
        }
    }

    /** Cuenta de $25 confirmada y una ronda de $15 mandada sin red: la terminal cree que se deben $40. */
    private suspend fun cuentaConRondaSinRed() {
        session.start(TableSession.Active(tableId = "mesa-12", orderId = ORDEN))
        session.cuentaDelServidor(ORDEN, OrderDetail(id = ORDEN, total = BigDecimal("25.00"), amountLeft = BigDecimal("25.00")), session.marcaDeSaldo())
        session.empezarCambioDeSaldo()
        outbox.enqueue(VENUE, SyncIntentTypes.ADD_ITEMS, JsonObject().apply { addProperty("orderId", ORDEN) })
        session.terminarCambioDeSaldo(ORDEN, BigDecimal("15.00"))
        assertThat(session.current()!!.total).isEqualToIgnoringScale("40.00")
    }

    @Test
    fun `si el servidor rechaza la ronda al sincronizar, el saldo queda por confirmar aunque el replay sea de otro`() = runTest {
        cuentaConRondaSinRed()
        servidorQue("REJECTED", errorCode = "PERMISSION_DENIED")
        val antes = session.sincronizaciones.value

        outbox.replayNow(VENUE) // el del repositorio: su propio callback no mira la sesión

        assertThat(session.current()!!.saldoConfirmado).isFalse() // ya no se ofrecen los $40
        assertThat(session.current()!!.total).isEqualToIgnoringScale("0")
        assertThat(session.sincronizaciones.value).isGreaterThan(antes) // y el cobro que espera relee
    }

    @Test
    fun `si la sube bien, el saldo se queda y el cobro se entera`() = runTest {
        cuentaConRondaSinRed()
        servidorQue("ACKED")
        val antes = session.sincronizaciones.value

        outbox.replayNow(VENUE)

        assertThat(session.current()!!.saldoConfirmado).isTrue()
        assertThat(session.current()!!.total).isEqualToIgnoringScale("40.00")
        assertThat(session.sincronizaciones.value).isGreaterThan(antes)
    }

    @Test
    fun `el rechazo de algo de otra cuenta no toca el saldo de esta`() = runTest {
        cuentaConRondaSinRed()
        coEvery { api.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            Response.success(SyncIntentsResponse(data = request.intents.map { SyncIntentAck(it.id, "ACKED") }))
        }
        outbox.replayNow(VENUE)
        outbox.enqueue(VENUE, SyncIntentTypes.ADD_ITEMS, JsonObject().apply { addProperty("orderId", "otra-orden") })
        servidorQue("REJECTED", errorCode = "PERMISSION_DENIED")

        outbox.replayNow(VENUE)

        assertThat(session.current()!!.saldoConfirmado).isTrue()
        assertThat(session.current()!!.total).isEqualToIgnoringScale("40.00")
    }

    @Test
    fun `aunque el aviso del que llama se cancele, la sesion se entera del rechazo ya guardado`() = runTest {
        cuentaConRondaSinRed()
        servidorQue("REJECTED", errorCode = "PERMISSION_DENIED")

        runCatching {
            outbox.replayNow(VENUE) { _, _ -> throw kotlinx.coroutines.CancellationException("el worker se fue") }
        }

        assertThat(dao.allForVenue(VENUE).single().status).isEqualTo("REJECTED") // el rechazo ya está guardado
        assertThat(session.current()!!.saldoConfirmado).isFalse() // y la sesión se enteró igual
    }

    private companion object {
        const val VENUE = "venue-1"
        const val ORDEN = "orden-1"
    }
}
