package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentInbox
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse
import com.jaac.avoqado_tpv.features.shift.data.dto.KnownShiftState
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/**
 * D65 (prueba final del 13-nov, PAX): la caja de la terminal se cerró desde la D3 y el negocio abrió otra. La TPV se quedaba en
 * «Caja cerrada o cerrándose en otro aparato» y rechazaba el cobro («Abre la caja antes de iniciar otra venta») hasta que alguien
 * hacía «Abrir nueva caja» → «Usar la caja vinculada». El turno es del negocio: la terminal usa sola la caja abierta que ve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class CajaDelNegocioTrasCierreRemotoRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private var mode = true
    private val venue = "v1"
    private val vieja = "c000000000000000000000001"
    private val nueva = "c000000000000000000000004"

    @Before fun setup() {
        every { storage.getVenueId() } returns venue
        every { storage.getStaffId() } returns "cajera"
        every { storage.getToken() } returns "token"
        every { storage.getKioskVenueId() } returns null
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } answers { mode }
        every { storage.getString("permissions_cache", null) } returns
            "$venue|cajera\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund"
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "d65-caja-del-negocio").allowMainThreadQueries().build()
        repo = ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk<RefundQueueRepository>(relaxed = true),
            appContext = ApplicationProvider.getApplicationContext())
    }

    @After fun cleanup() { db.close(); context.deleteDatabase("d65-caja-del-negocio") }

    private fun caja(id: String, status: String = "OPEN", drawer: String = "c00000000000000000000000d"): ShiftDto = Gson().fromJson("""{
        "id":"$id","venueId":"$venue","staffId":"gerente-d3","startTime":"2026-11-13T18:00:00Z","status":"$status",
        "startingCash":"1000.00","totalSales":"0","totalTips":"0","totalOrders":0,"totalCashPayments":"0",
        "totalCardPayments":"0","totalVoucherPayments":"0","totalOtherPayments":"0","totalProductsSold":0,
        "cashDrawerSessionId":"$drawer"} """, ShiftDto::class.java)

    private fun actual(shift: ShiftDto?) = coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(shift))

    /** La terminal ya usaba la caja vieja y la D3 la cerró. */
    private suspend fun cajaViejaCerradaEnOtroAparato(status: String = "CLOSED") =
        repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow().also {
            coEvery { api.getKnownShiftState(venue, vieja) } returns Response.success(KnownShiftState(vieja, venue, status))
        }

    @Test fun `la caja nueva del negocio se usa sola en la misma consulta que ve el cierre de la vieja`() = runTest {
        val a = cajaViejaCerradaEnOtroAparato()
        actual(caja(nueva))
        var avisos = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.shiftChanges.collect { avisos++ } }

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        val cerrada = db.localShiftSessionDao().leer(venue, a.localSessionId)!!
        assertThat(cerrada.localStatus).isEqualTo("CLOSED")
        assertThat(cerrada.closeStatus).isEqualTo("REVIEW")
        assertThat(cerrada.closeOperationId).isNull()
        val abierta = db.localShiftSessionDao().abierta(venue)!!
        assertThat(abierta.serverShiftId).isEqualTo(nueva)
        assertThat(abierta.openingKind).isEqualTo("REMOTE")
        assertThat(abierta.openingOperationId).isNull()
        assertThat(visible.id).isEqualTo(nueva)
        assertThat(visible.status.name).isEqualTo("OPEN")
        assertThat(visible.localShiftSessionId).isEqualTo(abierta.localSessionId)
        val belonging = repo.guardarNuevaObligacionDeCaja(venue) { it }
        assertThat(belonging.kind).isEqualTo("MANAGED")
        assertThat(belonging.localSessionId).isEqualTo(abierta.localSessionId)
        assertThat(belonging.serverShiftId).isEqualTo(nueva)
        runCurrent()
        assertThat(avisos).isAtLeast(1)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        coVerify(exactly = 0) { api.openShift(any(), any()) }
    }

    @Test fun `si la caja nueva se abre despues de ver el cierre la siguiente consulta la usa`() = runTest {
        val a = cajaViejaCerradaEnOtroAparato()
        actual(null)
        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("CLOSED")
        assertThat(db.localShiftSessionDao().abierta(venue)).isNull()

        actual(caja(nueva))
        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.id).isEqualTo(nueva)
        assertThat(db.localShiftSessionDao().abierta(venue)!!.serverShiftId).isEqualTo(nueva)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("REVIEW")
        // Repetir no crea otra caja local.
        repo.getCurrentShift(venue)
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(2)
    }

    @Test fun `sin red o sin otra caja abierta se sigue viendo la cerrada sin crear nada`() = runTest {
        val a = cajaViejaCerradaEnOtroAparato()
        actual(null)
        repo.getCurrentShift(venue)
        val cerrada = db.localShiftSessionDao().leer(venue, a.localSessionId)!!

        coEvery { api.getCurrentShift(venue) } throws IOException("sin red")
        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("CLOSED")
        actual(caja(vieja, status = "CLOSED"))
        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("CLOSED")
        actual(caja(nueva).copy(venueId = "otra-sucursal"))
        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("CLOSED")

        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(1)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)).isEqualTo(cerrada)
        assertThat(runCatching { repo.guardarNuevaObligacionDeCaja(venue) { it } }.isFailure).isTrue()
    }

    @Test fun `un cierre capturado en ESTE aparato sigue mandando y no se cambia por la caja de otro`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja)).getOrThrow()
        val propio = repo.guardarCierreLocal(venue, a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("1200.00")).getOrThrow()
        actual(caja(nueva))

        repo.getCurrentShift(venue)
        assertThat(repo.registrarCajaRemotaConocida(venue, caja(nueva)).getOrThrow()).isEqualTo(propio)

        assertThat(db.localShiftSessionDao().abierta(venue)).isNull()
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(1)
    }

    @Test fun `una apertura de este aparato pendiente de confirmar no se reemplaza por la caja de otro`() = runTest {
        val local = repo.guardarAperturaLocal(venue, "cajera", BigDecimal("500.00")).getOrThrow()
        actual(caja(nueva))

        repo.getCurrentShift(venue)

        assertThat(db.localShiftSessionDao().abierta(venue)).isEqualTo(local)
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(1)
    }

    @Test fun `antes de admitir un cobro remoto sin caja se pregunta al servidor y se admite en la caja nueva`() = runTest {
        cajaViejaCerradaEnOtroAparato()
        actual(null)
        repo.getCurrentShift(venue)
        actual(caja(nueva))

        repo.consultarCajaDelNegocioSiNoHayAbierta(venue)

        assertThat(db.localShiftSessionDao().abierta(venue)!!.serverShiftId).isEqualTo(nueva)
    }

    @Test fun `con caja abierta o con Caja apagada no se pregunta nada al servidor`() = runTest {
        repo.registrarCajaRemotaConocida(venue, caja(vieja)).getOrThrow()
        repo.consultarCajaDelNegocioSiNoHayAbierta(venue)
        coVerify(exactly = 0) { api.getCurrentShift(any()) }

        db.clearAllTables()
        mode = false
        repo.consultarCajaDelNegocioSiNoHayAbierta(venue)
        coVerify(exactly = 0) { api.getCurrentShift(any()) }
    }

    @Test fun `la bandeja pregunta por la caja del negocio antes de reclamar el cobro`() = runTest {
        val shifts = legacyShiftBoundaryFixture()
        val bandeja = RemotePaymentInbox(db.remotePaymentRequestDao(), shifts)

        bandeja.reclamarParaVenue("req-1", venue)

        coVerifyOrder {
            shifts.consultarCajaDelNegocioSiNoHayAbierta(venue, any())
            shifts.guardarNuevaObligacionDeCaja<Any?>(venue, any(), any(), any())
        }
    }
}
