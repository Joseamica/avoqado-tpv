package com.jaac.avoqado_tpv.features.tables.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.ShiftBelonging
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
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
 * hz:nube0000:na78d478 (Codex, pasada exhaustiva de D86, r7 A3): `payCash` escribe el `PAY_CASH` y espera su turno en la cola
 * (`replayMutex`). Si mientras espera OTRO reproductor (el trabajador, el coordinador de Mesas, la recuperación) manda la cola y el
 * SERVIDOR contesta, el acuse ya quedó en Room y el `onAck` de `payCash` nunca ve su intent. Antes contestaba «encolado» y la
 * pantalla decía «Sin conexión — cobro guardado», descontaba y podía liberar la mesa con el cobro RECHAZADO.
 *
 * Room real y el MISMO `SyncOutbox` (el mismo candado, como en producción, Codex r2): el «otro reproductor» corre en cuanto el
 * cobro quedó escrito y antes de que `payCash` pida su turno. Sin `spyk`: espiar una función que suspende de verdad hacía que
 * MockK devolviera basura a la mitad de las veces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PayCashConOtroReproductorRoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val secureStorage = mockk<SecureStorage>().also { every { it.getSerialNumber() } returns "AVQD-N860W175781" }
    private val api = mockk<TablesApiService>()
    private val db: AvoqadoDatabase = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    /** Lo que hace el otro reproductor con la cola justo después de que el cobro quedó escrito. */
    private var otroReproductor: suspend () -> Unit = {}
    private lateinit var outbox: SyncOutbox

    private val caja = mockk<ShiftRepository>(relaxed = true).also { repo ->
        coEvery { repo.guardarNuevaObligacionDeCaja<Any?>(any(), any(), any(), any()) } coAnswers {
            val escrito = arg<suspend (ShiftBelonging) -> Any?>(3).invoke(ShiftBelonging())
            otroReproductor()
            escrito
        }
    }

    private val repo by lazy {
        outbox = SyncOutbox(db.syncIntentDao(), api, secureStorage, shiftRepository = caja)
        TablesRepository(api, outbox, mockk<PermissionsRepository>(relaxed = true))
    }

    @After
    fun cerrar() = db.close()

    /** El SERVIDOR contesta [ack] para cada intent que le llega. */
    private fun servidorContesta(ack: (id: String) -> SyncIntentAck) {
        coEvery { api.syncIntents(VENUE, any()) } answers {
            Response.success(SyncIntentsResponse(data = secondArg<SyncIntentsRequest>().intents.map { ack(it.id) }))
        }
    }

    private fun otroReproductorLlegaPrimero(despues: suspend () -> Unit = {}) {
        otroReproductor = {
            outbox.replayNow(VENUE)
            despues()
        }
    }

    @Test
    fun P1_otro_reproductor_ya_lo_mando_y_el_servidor_lo_rechazo_no_dice_cobro_guardado() = runTest {
        servidorContesta { SyncIntentAck(id = it, status = "REJECTED", errorCode = "ORDER_ALREADY_PAID", message = "La cuenta ya está pagada") }
        otroReproductorLlegaPrimero()

        val resultado = repo.payCash(VENUE, ORDEN, isProvisional = false, amount = BigDecimal("150.00"))

        val rechazo = resultado.exceptionOrNull()
        assertThat(rechazo).isInstanceOf(TablesRepository.PayCashRejectedException::class.java)
        assertThat(rechazo?.message).isEqualTo("La cuenta ya está pagada")
        assertThat((rechazo as TablesRepository.PayCashRejectedException).errorCode).isEqualTo("ORDER_ALREADY_PAID")
        assertThat(db.syncIntentDao().rejectedForVenue(VENUE)).hasSize(1)
    }

    @Test
    fun P1_otro_reproductor_ya_lo_mando_y_el_servidor_lo_registro_dice_registrado_con_su_recibo() = runTest {
        servidorContesta {
            SyncIntentAck(
                id = it, status = "ACKED",
                result = JsonParser.parseString(
                    """{"paymentId":"pay-1","orderNumber":"ORD-7","digitalReceipt":{"receiptUrl":"https://r/1","autofacturaAvailable":true}}""",
                ).asJsonObject,
            )
        }
        otroReproductorLlegaPrimero()

        val outcome = repo.payCash(VENUE, ORDEN, isProvisional = false, amount = BigDecimal("150.00")).getOrThrow()

        assertThat(outcome.queued).isFalse()
        assertThat(outcome.paymentId).isEqualTo("pay-1")
        assertThat(outcome.orderNumber).isEqualTo("ORD-7")
        assertThat(outcome.receiptUrl).isEqualTo("https://r/1")
        assertThat(outcome.autofacturaAvailable).isTrue()
    }

    /** Codex r1 #7: alguien ya marcó ese rechazo como resuelto (DISMISSED) mientras el cobro esperaba: sigue siendo un rechazo. */
    @Test
    fun un_rechazo_ya_marcado_como_resuelto_sigue_siendo_rechazo() = runTest {
        servidorContesta { SyncIntentAck(id = it, status = "REJECTED", errorCode = "ORDER_ALREADY_PAID", message = "La cuenta ya está pagada") }
        otroReproductorLlegaPrimero { db.syncIntentDao().rejectedForVenue(VENUE).forEach { outbox.dismissRejected(VENUE, it.id) } }

        val rechazo = repo.payCash(VENUE, ORDEN, isProvisional = false, amount = BigDecimal("150.00")).exceptionOrNull()

        assertThat(rechazo).isInstanceOf(TablesRepository.PayCashRejectedException::class.java)
        assertThat(rechazo?.message).isEqualTo("La cuenta ya está pagada")
        assertThat(db.syncIntentDao().allForVenue(VENUE).map { it.status }).containsExactly(SyncIntentEntity.STATUS_DISMISSED)
    }

    @Test
    fun sin_red_sigue_diciendo_guardado() = runTest {
        coEvery { api.syncIntents(VENUE, any()) } throws java.io.IOException("sin red")

        val outcome = repo.payCash(VENUE, ORDEN, isProvisional = false, amount = BigDecimal("150.00")).getOrThrow()

        assertThat(outcome.queued).isTrue()
        assertThat(db.syncIntentDao().pendingForVenue(VENUE).map { it.status }).containsExactly(SyncIntentEntity.STATUS_PENDING)
    }

    private companion object {
        const val VENUE = "venue-1"
        const val ORDEN = "orden-1"
    }
}
