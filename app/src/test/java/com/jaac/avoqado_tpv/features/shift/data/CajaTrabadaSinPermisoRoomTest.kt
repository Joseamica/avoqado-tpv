package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse
import com.jaac.avoqado_tpv.features.shift.data.dto.KnownShiftState
import com.jaac.avoqado_tpv.features.shift.data.dto.OpenShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftIdentityDto
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftIdentityResponse
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftResponse
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * D24 (Testarudo Cafe, 22-nov, N86 AVQD-N860W173400): la caja del 21 la cerró el dueño en otro aparato y la del 22 ya estaba
 * abierta. Entró alguien de Cocina (cobra, pero no tiene `shifts:read`) y la terminal se quedó en «Caja cerrada o cerrándose en
 * otro aparato · Cierre observado en el servidor (CLOSED)», con «Abrir nueva caja» y «Usar la caja vinculada» en gris.
 * Dos causas: el paso solo a la caja del negocio (D65) pedía `shifts:read`, y se saltaba las cajas «vinculadas» (`ADOPTED`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class CajaTrabadaSinPermisoRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private val venue = "v1"
    private val vieja = "c000000000000000000000001"
    private val nueva = "c000000000000000000000004"
    private val drawerNuevo = "c00000000000000000000000d"
    private var staff = "cajera"
    private var permisos = "tpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund"

    @Before fun setup() {
        every { storage.getVenueId() } returns venue
        every { storage.getStaffId() } answers { staff }
        every { storage.getToken() } answers { "token-$staff" }
        every { storage.getKioskVenueId() } returns null
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } returns true
        every { storage.getString("permissions_cache", null) } answers { "$venue|$staff\n$permisos" }
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "d24-caja-trabada").allowMainThreadQueries().build()
        repo = ShiftRepository(api, db, storage, db.cachedShiftDao(), mockk<RefundQueueRepository>(relaxed = true),
            appContext = ApplicationProvider.getApplicationContext())
    }

    @After fun cleanup() { db.close(); context.deleteDatabase("d24-caja-trabada") }

    private fun caja(id: String, status: String = "OPEN", drawer: String = drawerNuevo): ShiftDto = Gson().fromJson("""{
        "id":"$id","venueId":"$venue","staffId":"duena","startTime":"2026-11-22T14:24:00Z","status":"$status",
        "startingCash":"2000.00","totalSales":"0","totalTips":"0","totalOrders":0,"totalCashPayments":"0",
        "totalCardPayments":"0","totalVoucherPayments":"0","totalOtherPayments":"0","totalProductsSold":0,
        "cashDrawerSessionId":"$drawer"} """, ShiftDto::class.java)

    private fun identidad(id: String = nueva, venueId: String = venue) =
        ShiftIdentityDto(id, venueId, "OPEN", "2026-11-22T14:24:00Z", "duena", "2000.00", drawerNuevo)

    /** Entra Cocina: cobra (`payments:create`) pero no ve turnos (`shifts:read`) ni abre caja (`tpv-shifts:create`). */
    private fun entraCocina(conPermisoDeCobrar: Boolean = true) {
        staff = "cocina"
        permisos = if (conPermisoDeCobrar) "home:read,orders:read,orders:create,orders:update,menu:read,payments:create" else "home:read,orders:read,menu:read"
        coEvery { api.getCurrentShift(venue) } returns Response.error(403, """{"error":"Forbidden"}""".toResponseBody())
    }

    private fun sinCifras(shift: ShiftIdentityDto?, known: KnownShiftState? = null, conocida: String? = null) =
        coEvery { api.getShiftIdentity(venue, conocida) } returns Response.success(ShiftIdentityResponse(shift, known))

    /** La caja de ayer, que este aparato ya vio cerrada (como la N86: lo vio quien estaba antes, con `shifts:read`). */
    private suspend fun cajaDeAyerVistaCerrada() = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow().also {
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
        coEvery { api.getKnownShiftState(venue, vieja) } returns Response.success(KnownShiftState(vieja, venue, "CLOSED"))
        repo.getCurrentShift(venue)
        assertThat(db.localShiftSessionDao().leer(venue, it.localSessionId)!!.localStatus).isEqualTo("CLOSED")
    }

    @Test fun `Cocina con la caja de ayer vista cerrada pasa sola a la caja abierta del negocio y puede cobrar`() = runTest {
        val ayer = cajaDeAyerVistaCerrada()
        entraCocina()
        sinCifras(identidad())

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.id).isEqualTo(nueva)
        assertThat(visible.status.name).isEqualTo("OPEN")
        // Sin cifras: no se presume ningún total.
        assertThat(visible.financialTotalsKnown).isFalse()
        val abierta = db.localShiftSessionDao().abierta(venue)!!
        assertThat(abierta.serverShiftId).isEqualTo(nueva)
        assertThat(abierta.startingCash).isEqualTo("2000.00")
        assertThat(abierta.cashDrawerSessionId).isEqualTo(drawerNuevo)
        assertThat(db.localShiftSessionDao().leer(venue, ayer.localSessionId)!!.localStatus).isEqualTo("CLOSED")
        val belonging = repo.guardarNuevaObligacionDeCaja(venue) { it }
        assertThat(belonging.serverShiftId).isEqualTo(nueva)
        assertThat(db.cachedShiftDao().getCachedShift(venue)).isNull()
    }

    @Test fun `el cierre observado se dice en llano, sin el estado crudo del servidor`() = runTest {
        val ayer = cajaDeAyerVistaCerrada()
        val texto = db.localShiftSessionDao().leer(venue, ayer.localSessionId)!!.lastError!!
        assertThat(texto).doesNotContain("(CLOSED)")
        assertThat(texto).isEqualTo("Esta caja se cerró en otro aparato. Este aparato no capturó su conteo.")
    }

    @Test fun `Cocina con la caja vieja todavia abierta aqui pasa a la nueva si el servidor dice que la vieja cerro`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()
        entraCocina()
        sinCifras(identidad(), KnownShiftState(vieja, venue, "CLOSED"), conocida = vieja)

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.id).isEqualTo(nueva)
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("REVIEW")
        assertThat(db.localShiftSessionDao().abierta(venue)!!.serverShiftId).isEqualTo(nueva)
    }

    @Test fun `Cocina con la vieja abierta aqui no cambia si la vieja apenas se esta cerrando`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()
        entraCocina()
        sinCifras(identidad(), KnownShiftState(vieja, venue, "CLOSING"), conocida = vieja)

        val r = repo.getCurrentShift(venue)

        assertThat(((r as Result.Error).exception as ApiException.HttpError).code).isEqualTo(403)
        assertThat(db.localShiftSessionDao().abierta(venue)).isEqualTo(a)
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(1)
    }

    @Test fun `Cocina sin caja en este aparato usa la abierta del negocio`() = runTest {
        entraCocina()
        sinCifras(identidad())

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.id).isEqualTo(nueva)
        assertThat(db.localShiftSessionDao().abierta(venue)!!.openingKind).isEqualTo("REMOTE")
    }

    @Test fun `antes del cobro de la tablet, Cocina con la vieja abierta aqui pasa a la nueva`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()
        entraCocina()
        sinCifras(identidad(), KnownShiftState(vieja, venue, "CLOSED"), conocida = vieja)

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { repo.consultarCajaDelNegocio(venue) }

        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.localStatus).isEqualTo("CLOSED")
        assertThat(repo.guardarNuevaObligacionDeCaja(venue) { it }.serverShiftId).isEqualTo(nueva)
    }

    @Test fun `con un SERVIDOR sin la ruta nueva (404) todo sigue como hoy`() = runTest {
        val ayer = cajaDeAyerVistaCerrada()
        entraCocina()
        coEvery { api.getShiftIdentity(venue, any()) } returns Response.error(404, "{}".toResponseBody())

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.status.name).isEqualTo("CLOSED")
        assertThat(db.localShiftSessionDao().abierta(venue)).isNull()
        assertThat(db.localShiftSessionDao().leer(venue, ayer.localSessionId)!!.localStatus).isEqualTo("CLOSED")
    }

    @Test fun `sin red se queda con la ultima caja conocida y no inventa nada`() = runTest {
        cajaDeAyerVistaCerrada()
        entraCocina()
        coEvery { api.getShiftIdentity(venue, any()) } throws IOException("sin red")

        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("CLOSED")
        coEvery { api.getCurrentShift(venue) } throws IOException("sin red")
        assertThat(repo.getCurrentShift(venue).getOrThrow()!!.status.name).isEqualTo("CLOSED")
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(1)
    }

    @Test fun `sin el permiso de cobrar ni de ver turnos no se escribe nada y el 403 sigue siendo un 403`() = runTest {
        entraCocina(conPermisoDeCobrar = false)
        sinCifras(identidad())

        val r = repo.getCurrentShift(venue)

        assertThat((r as Result.Error).exception).isInstanceOf(ApiException.HttpError::class.java)
        assertThat(r.exception.servidorNoContesto).isFalse()
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(0)
    }

    @Test fun `una caja de otra sucursal no se adopta`() = runTest {
        entraCocina()
        sinCifras(identidad(venueId = "otra-sucursal"))

        repo.getCurrentShift(venue)

        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(0)
    }

    /** La caja de la N86 era «vinculada» (salía «Usar la caja vinculada»): D65 se la saltaba aunque el permiso sobrara. */
    @Test fun `una caja vinculada cerrada en otro aparato tambien pasa a la abierta del negocio`() = runTest {
        val local = repo.guardarAperturaLocal(venue, "cajera", BigDecimal("500.00")).getOrThrow()
        val adoptada = caja(vieja, drawer = "c00000000000000000000000a").copy(localId = "c0000000000000000000000ff",
            openingDisposition = "ADOPTED", fondoAplicado = "1500.00")
        repo.registrarResultadoAperturaLocal(venue, local.localSessionId, adoptada).getOrThrow()
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(null))
        coEvery { api.getKnownShiftState(venue, vieja) } returns Response.success(KnownShiftState(vieja, venue, "CLOSED"))
        repo.getCurrentShift(venue)
        assertThat(db.localShiftSessionDao().leer(venue, local.localSessionId)!!.localStatus).isEqualTo("CLOSED")
        entraCocina()
        sinCifras(identidad())

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.id).isEqualTo(nueva)
        assertThat(db.localShiftSessionDao().abierta(venue)!!.serverShiftId).isEqualTo(nueva)
    }

    @Test fun `abrir con el PIN de un gerente manda su token y guarda la caja que abrio el servidor`() = runTest {
        entraCocina()
        val pedido = slot<OpenShiftRequest>()
        coEvery { api.openShiftConPin(venue, capture(pedido), "tok-1") } returns
            Response.success(ShiftResponse(true, caja(nueva).copy(openingDisposition = "OWN")))

        val abierta = repo.abrirCajaConPin(venue, "cocina", BigDecimal("800.00"), "tok-1")

        assertThat(abierta.getOrThrow().id).isEqualTo(nueva)
        assertThat(pedido.captured.staffId).isEqualTo("cocina")
        assertThat(pedido.captured.isOfflineReplay).isFalse()
        assertThat(pedido.captured.operationId).isEqualTo(pedido.captured.localId)
        assertThat(db.localShiftSessionDao().abierta(venue)!!.serverShiftId).isEqualTo(nueva)
        coVerify(exactly = 0) { api.openShift(any(), any()) }
    }

    @Test fun `abrir con PIN sin red o con el token gastado no guarda nada`() = runTest {
        entraCocina()
        coEvery { api.openShiftConPin(venue, any(), "tok-1") } throws IOException("sin red")
        val sinRed = repo.abrirCajaConPin(venue, "cocina", BigDecimal("800.00"), "tok-1")
        assertThat((sinRed as Result.Error).exception).isInstanceOf(ApiException.NetworkError::class.java)

        coEvery { api.openShiftConPin(venue, any(), "tok-1") } returns Response.error(403, """{"error":"Forbidden"}""".toResponseBody())
        val gastado = repo.abrirCajaConPin(venue, "cocina", BigDecimal("800.00"), "tok-1")
        assertThat((gastado as Result.Error).exception.userMessage).contains("PIN")

        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(0)
    }

    /** Codex r1 #1: después de pasarse a B, la siguiente lectura (la de antes de cobrar en la N86) confirma B y no vuelve al 403. */
    @Test fun `Cocina ya en la caja nueva sigue viendola abierta en la siguiente lectura y puede cobrar`() = runTest {
        cajaDeAyerVistaCerrada()
        entraCocina()
        sinCifras(identidad())
        repo.getCurrentShift(venue).getOrThrow()
        sinCifras(identidad(), KnownShiftState(nueva, venue, "OPEN"), conocida = nueva)

        val otraVez = repo.getCurrentShift(venue)

        assertThat(otraVez.getOrThrow()!!.id).isEqualTo(nueva)
        assertThat(otraVez.getOrThrow()!!.status.name).isEqualTo("OPEN")
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(2)
    }

    /** Codex r1 #2: sin caja abierta en el negocio, Cocina ve «Sin turno de caja» con «Abrir caja» (PIN), no un error. */
    @Test fun `sin caja abierta en el negocio Cocina recibe sin caja y no un error`() = runTest {
        entraCocina()
        sinCifras(null)

        assertThat(repo.getCurrentShift(venue)).isEqualTo(Result.Success(null))
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(0)
    }

    @Test fun `con la vieja abierta aqui, cerrada en el servidor y sin otra, se ve cerrada para abrir otra`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()
        entraCocina()
        sinCifras(null, KnownShiftState(vieja, venue, "CLOSED"), conocida = vieja)

        val visible = repo.getCurrentShift(venue).getOrThrow()!!

        assertThat(visible.status.name).isEqualTo("CLOSED")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("REVIEW")
        assertThat(db.localShiftSessionDao().abierta(venue)).isNull()
    }

    /** Codex r1 #9: con Caja apagada no se guarda ninguna caja (el 403 de siempre). */
    @Test fun `con Caja apagada Cocina no guarda ninguna caja`() = runTest {
        entraCocina()
        every { storage.getBoolean("enable_shifts", true) } returns false
        sinCifras(identidad())

        assertThat(repo.getCurrentShift(venue)).isInstanceOf(Result.Error::class.java)
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(0)
    }

    /** Codex r1 #8: una foto vieja de A no reabre A aunque la última caja (B) ya esté cerrada y confirmada. */
    @Test fun `una foto vieja no reabre una caja que este aparato ya vio cerrar aunque la ultima este confirmada`() = runTest {
        val a = cajaDeAyerVistaCerrada()
        coEvery { api.getCurrentShift(venue) } returns Response.success(CurrentShiftResponse(caja(nueva)))
        repo.getCurrentShift(venue)
        val b = db.localShiftSessionDao().abierta(venue)!!
        repo.guardarCierreLocal(venue, b.localSessionId, null, null).getOrThrow()
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET close_status = 'CONFIRMED' WHERE local_session_id = ?", arrayOf(b.localSessionId))

        repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()

        assertThat(db.localShiftSessionDao().porServidor(venue, vieja)!!.localSessionId).isEqualTo(a.localSessionId)
        assertThat(db.localShiftSessionDao().abierta(venue)).isNull()
        assertThat(db.localShiftSessionDao().contar(venue)).isEqualTo(2)
    }

    /** Codex r1 #3: con un cierre de este aparato todavía por enviar, abrir con PIN espera: el cierre va primero. */
    @Test fun `abrir con PIN espera a que se envie el cierre pendiente de este aparato`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()
        repo.guardarCierreLocal(venue, a.localSessionId, null, null).getOrThrow()
        entraCocina()

        val r = repo.abrirCajaConPin(venue, "cocina", BigDecimal("800.00"), "tok-1")

        assertThat((r as Result.Error).exception.userMessage).contains("todavía no se envía")
        coVerify(exactly = 0) { api.openShiftConPin(any(), any(), any()) }
    }

    /** Codex r2 #2: el servidor lee la abierta y la conocida por separado; si en medio se cerró la de aquí, no se confirma. */
    @Test fun `si el servidor ve abierta la de aqui pero ya la vio cerrar no se confirma`() = runTest {
        val a = repo.registrarCajaRemotaConocida(venue, caja(vieja, drawer = "c00000000000000000000000a")).getOrThrow()
        entraCocina()
        sinCifras(identidad(vieja), KnownShiftState(vieja, venue, "CLOSED"), conocida = vieja)

        val r = repo.getCurrentShift(venue)

        assertThat(((r as Result.Error).exception as ApiException.HttpError).code).isEqualTo(403)
        assertThat(db.localShiftSessionDao().abierta(venue)).isEqualTo(a)
    }

    /** Codex r2 #6: con Caja apagada tampoco se guarda la caja del negocio después de un cierre observado. */
    @Test fun `con Caja apagada tras un cierre observado Cocina no guarda la caja nueva`() = runTest {
        cajaDeAyerVistaCerrada()
        entraCocina()
        every { storage.getBoolean("enable_shifts", true) } returns false
        sinCifras(identidad())

        repo.getCurrentShift(venue)

        assertThat(db.localShiftSessionDao().abierta(venue)).isNull()
        coVerify(exactly = 0) { api.getShiftIdentity(any(), any()) }
    }
}
