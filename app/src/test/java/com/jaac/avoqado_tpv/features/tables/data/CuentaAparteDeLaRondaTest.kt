package com.jaac.avoqado_tpv.features.tables.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
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
 * D16 (tarjeta hz:033d44ca:D16): la ronda que la mesera mandó sin red y que el SERVIDOR puso en una cuenta aparte (D7, SERVIDOR
 * #471) porque otra caja ya cobró la mesa. El ACK del `ADD_ITEMS` nombra la cuenta que el aparato pidió (`orderId`) y, aparte, la
 * cuenta nueva (`cuentaAparte`). La TPV la guardaba en su cola y ninguna pantalla la usaba: la ronda desaparecía sin aviso (acta de
 * D7, paso 4d, N86 16-nov). El aviso se arma de la cola en disco, así que sobrevive a que la app muera entre el ACK y la pantalla.
 *
 * Room real bajo Robolectric: la consulta busca dentro de `result_json` y un error de SQL no lo ve un mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CuentaAparteDeLaRondaTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val secureStorage = mockk<SecureStorage>().also { every { it.getSerialNumber() } returns "AVQD-N860W175781" }
    private val api = mockk<TablesApiService>()
    private val db: AvoqadoDatabase = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val outbox = SyncOutbox(db.syncIntentDao(), api, secureStorage, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())

    @After
    fun cerrar() = db.close()

    /** El ACK tal cual lo grabó la N86 el 16-nov (acta de D7, 17:51:19). */
    private fun ackDeD7(id: String) = SyncIntentAck(
        id = id,
        status = "ACKED",
        result = JsonParser.parseString(
            """{"total":25,"orderId":"O1","version":3,"cuentaAparte":{"total":89,"orderId":"O2","version":2,"orderNumber":"ORD-1794873080800"}}""",
        ).asJsonObject,
    )

    @Test
    fun D16_el_ack_del_servidor_queda_en_la_cola_y_nombra_la_cuenta_aparte() = runTest {
        val ronda = outbox.enqueue(VENUE, SyncIntentTypes.ADD_ITEMS, JsonObject().apply { addProperty("orderId", "O1") })
        coEvery { api.syncIntents(VENUE, any()) } returns Response.success(SyncIntentsResponse(data = listOf(ackDeD7(ronda))))

        outbox.replayNow(VENUE)

        val aparte = SaldoDeMesaSinRed(outbox).cuentaAparte(VENUE, "O1")
        assertThat(aparte).isEqualTo(CuentaAparte(orderId = "O2", orderNumber = "ORD-1794873080800", version = 2, total = BigDecimal("89")))
        // La cuenta aparte misma, u otra cuenta, no tienen aviso.
        assertThat(SaldoDeMesaSinRed(outbox).cuentaAparte(VENUE, "O2")).isNull()
        assertThat(SaldoDeMesaSinRed(outbox).cuentaAparte(VENUE, "otra")).isNull()
    }

    @Test
    fun D16_una_ronda_que_sigue_sin_subir_no_da_aviso() = runTest {
        outbox.enqueue(VENUE, SyncIntentTypes.ADD_ITEMS, JsonObject().apply { addProperty("orderId", "O1") })
        assertThat(SaldoDeMesaSinRed(outbox).cuentaAparte(VENUE, "O1")).isNull()
    }

    // region — la regla pura sobre las filas de la cola

    private var seq = 0L
    private fun fila(resultado: String?, tipo: String = SyncIntentTypes.ADD_ITEMS, estado: String = SyncIntentEntity.STATUS_ACKED) =
        SyncIntentEntity(id = "i${++seq}", venueId = VENUE, seq = seq, type = tipo, payloadJson = "{}", status = estado, resultJson = resultado)

    @Test
    fun cuentaAparteDe_gana_el_mas_nuevo() {
        // Dos rondas a la misma cuenta aparte: la segunda trae el total con las dos. Y la cadena: la aparte ya se cobró y nació otra.
        val filas = listOf(
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O2","total":89}}"""),
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O2","total":134}}"""),
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O3","orderNumber":"ORD-3","total":20}}"""),
        )
        assertThat(cuentaAparteDe("O1", filas.shuffled())).isEqualTo(CuentaAparte("O3", "ORD-3", null, BigDecimal("20")))
    }

    @Test
    fun cuentaAparteDe_el_reintento_de_la_huella_solo_trae_orderId() {
        val filas = listOf(fila("""{"orderId":"O1","version":3,"total":25,"cuentaAparte":{"orderId":"O2"}}"""))
        assertThat(cuentaAparteDe("O1", filas)).isEqualTo(CuentaAparte("O2", null, null, null))
    }

    @Test
    fun cuentaAparteDe_no_da_aviso_sin_un_ack_de_ronda_que_la_nombre() {
        val filas = listOf(
            // Un cobro sin red que cayó en la cuenta aparte: ya avisa la ronda (fuera de D16).
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O2"}}""", tipo = SyncIntentTypes.PAY_CASH),
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O2"}}""", estado = SyncIntentEntity.STATUS_REJECTED),
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O2"}}""", estado = SyncIntentEntity.STATUS_PENDING),
            fila("""{"orderId":"OTRA","cuentaAparte":{"orderId":"O2"}}"""),
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":"O1"}}"""),
            fila("""{"orderId":"O1","cuentaAparte":{"orderId":""}}"""),
            fila("""{"orderId":"O1","cuentaAparte":"O2"}"""),
            fila("""{"orderId":"O1","version":4,"total":25}"""),
            fila("""{roto"""),
            fila(null),
        )
        assertThat(cuentaAparteDe("O1", filas)).isNull()
    }

    // endregion

    private companion object {
        const val VENUE = "venue-1"
    }
}
