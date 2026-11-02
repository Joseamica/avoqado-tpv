package com.jaac.avoqado_tpv.features.checkout.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.features.checkout.data.CarritoEnCurso
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2b: la implementación real de lo que Cobrar guarda en el aparato — el carrito a medias (SharedPreferences) contra
 * las filas de `pending_payments` (Room), la venta en $0 y el turno guardado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VentaSinRedRepositoryImplTest {

    private val contexto get() = ApplicationProvider.getApplicationContext<Application>()
    private val llave = "ckx0000000000000000000001"
    private val hora = 60L * 60 * 1000

    private lateinit var db: AvoqadoDatabase
    private lateinit var carritos: CarritoEnCurso
    private lateinit var turnos: ShiftRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var repo: VentaSinRedRepositoryImpl

    private val carrito = CartState(
        items = listOf(CartItem(id = "i-0", type = CartItemType.ProductItem("p-1"), name = "Café", unitPriceCents = 5000, quantity = 2)),
        selectedStaffId = "staff-1", selectedStaffName = "María",
    )
    private val cliente = Customer(
        id = "c-1", firstName = "Ana", lastName = "López", email = "ana@x.mx", phone = "5551234567", loyaltyPoints = 120,
        totalVisits = 4, totalSpent = BigDecimal("900"), customerGroup = null,
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(contexto, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        carritos = CarritoEnCurso(contexto)
        runBlocking { carritos.borrar("venue-1") }
        turnos = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        every { secureStorage.getSerialNumber() } returns "AVQD-1"
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "N86-9"
        repo = VentaSinRedRepositoryImpl(carritos, db.pendingPaymentDao(), VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()), turnos, secureStorage, contexto)
    }

    @After fun tearDown() {
        runBlocking { carritos.borrar("venue-1") }
        db.close()
        unmockkObject(TerminalConfig)
    }

    private fun ventaEnCero() = VentaLocal(
        llave = llave, venueId = "venue-1", staffId = "staff-1", shiftId = "shift-1", createdAtLocal = 1_760_000_000_000L,
        totalCents = 0, pedidoJson = """{"externalId":"$llave","total":0,"items":[]}""",
        renglones = listOf(RenglonDeTicket("$llave:0", "Galleta", 1, 0, 3000, emptyList(), null)),
    )

    @Test fun `menos de 24 h y sin fila ⇒ restaura con la MISMA llave y el cliente`() = runTest {
        assertThat(repo.guardarCarrito("venue-1", llave, carrito, cliente)).isTrue()

        val aMedias = repo.carritoParaRestaurar("venue-1")!!

        assertThat(aMedias.llave).isEqualTo(llave)
        assertThat(aMedias.cart).isEqualTo(carrito)
        // Lo mínimo para volver a mostrarlo sin red: id, nombre y contacto (sin puntos ni grupo, que vienen del servidor).
        assertThat(aMedias.cliente?.id).isEqualTo("c-1")
        assertThat(aMedias.cliente?.displayName).isEqualTo("Ana López")
        assertThat(aMedias.cliente?.phone).isEqualTo("5551234567")
    }

    @Test fun `más de 24 h ⇒ no restaura y lo borra`() = runTest {
        carritos.guardar("venue-1", llave, carrito, guardadoEn = System.currentTimeMillis() - 25 * hora)

        assertThat(repo.carritoParaRestaurar("venue-1")).isNull()
        assertThat(carritos.leer("venue-1")).isNull()
    }

    @Test fun `con fila de pago con su llave ⇒ no restaura y lo borra`() = runTest {
        repo.guardarCarrito("venue-1", llave, carrito, null)
        VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
            .guardar(ventaEnCero().copy(totalCents = 10000), PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1")
            .getOrThrow()

        assertThat(repo.carritoParaRestaurar("venue-1")).isNull()
        assertThat(carritos.leer("venue-1")).isNull()
    }

    @Test fun `un carrito guardado vacío no se restaura`() = runTest {
        repo.guardarCarrito("venue-1", llave, CartState(), null)

        assertThat(repo.carritoParaRestaurar("venue-1")).isNull()
        assertThat(carritos.leer("venue-1")).isNull()
    }

    @Test fun `la venta en $0 queda como fila PENDING de efectivo con su pedido y el carrito a medias se borra`() = runTest {
        repo.guardarCarrito("venue-1", llave, carrito, null)

        repo.guardarVentaEnCero(ventaEnCero()).getOrThrow()

        val fila = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!
        assertThat(fila.amount).isEqualTo("0.00")
        assertThat(fila.syncStatus).isEqualTo(PendingPaymentEntity.SYNC_STATUS_PENDING)
        assertThat(fila.claimToken).isNull()
        assertThat(fila.orderRequestJson).isEqualTo(ventaEnCero().pedidoJson)
        assertThat(fila.idempotencyKey).isEqualTo(llave)
        // Contrato explícito por sabor: PAX usa la serie guardada; Nexgo usa la de TerminalConfig.
        val procesadorEsperado = if (BuildConfig.ENABLE_PAX_SDK) PendingPaymentEntity.PROCESSOR_BLUMON else PendingPaymentEntity.PROCESSOR_ANGELPAY
        val serieDelAparatoEsperada = if (BuildConfig.ENABLE_PAX_SDK) "AVQD-1" else "N86-9"
        assertThat(fila.paymentProcessor).isEqualTo(procesadorEsperado)
        assertThat(fila.blumonSerialNumber).isEqualTo("")
        assertThat(fila.deviceSerialNumber).isEqualTo(serieDelAparatoEsperada)
        assertThat(fila.relojMonotonoAlVender).isNotNull()
        assertThat(fila.exitoMostrado).isFalse()
        assertThat(carritos.leer("venue-1")).isNull()
    }

    @Test fun `la misma venta en $0 dos veces no se duplica y lo dice`() = runTest {
        repo.guardarVentaEnCero(ventaEnCero()).getOrThrow()

        assertThat(repo.guardarVentaEnCero(ventaEnCero()).isFailure).isTrue()
        assertThat(db.pendingPaymentDao().getPendingCount()).isEqualTo(1)
    }

    @Test fun `marcar la venta vista pone exito_mostrado`() = runTest {
        repo.guardarVentaEnCero(ventaEnCero()).getOrThrow()

        repo.marcarVentaVista(llave)

        assertThat(db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!.exitoMostrado).isTrue()
    }

    @Test fun `el turno de la venta es el último abierto guardado, o ninguno sin turnos`() = runTest {
        val turno = mockk<Shift>()
        every { turno.id } returns "shift-9"
        every { turnos.isShiftSystemEnabled() } returns true
        coEvery { turnos.getCachedOpenShift("venue-1") } returns turno
        assertThat(repo.turnoDeLaVenta("venue-1")).isEqualTo("shift-9")

        every { turnos.isShiftSystemEnabled() } returns false
        assertThat(repo.turnoDeLaVenta("venue-1")).isNull()
    }

    @Test fun `procesador y seriales del efectivo de cada sabor`() {
        assertThat(procesadorDelEfectivo(hayLectorBlumon = true, serieGuardada = "AVQD-1", serieDeLaTerminal = "N86-9"))
            .isEqualTo(ProcesadorDelEfectivo(PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1"))
        assertThat(procesadorDelEfectivo(hayLectorBlumon = false, serieGuardada = "AVQD-1", serieDeLaTerminal = "N86-9"))
            .isEqualTo(ProcesadorDelEfectivo(PendingPaymentEntity.PROCESSOR_ANGELPAY, "", "N86-9"))
    }
    @Test fun `confirmacion lee el ticket durable aunque el carrito y el catalogo ya no esten`() = runTest {
        val venta = ventaEnCero().copy(totalCents = 2500, renglones = listOf(
            RenglonDeTicket("$llave:0", "Nombre guardado", 2, 3000, 4000, listOf(
                com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket("extra", "Talla", 500, "SINGLE_CHOICE")), "Nota guardada")))
        VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()).guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1", propinaCents = 375).getOrThrow()
        val recuperada = repo.ultimaVentaSinMostrar("venue-1")
        assertThat(recuperada).isNotNull()
        val vista = recuperada!!
        assertThat(vista.venta).isEqualTo(venta)
        assertThat(vista.propinaCents).isEqualTo(375)
        assertThat(repo.ultimaVentaSinMostrar("venue-2")).isNull()
        repo.marcarVentaVista(llave)
        assertThat(repo.ultimaVentaSinMostrar("venue-1")).isNull()
    }

    @Test fun `error al leer existencia no equivale a ausencia y conserva el borrador`() = runTest {
        repo.guardarCarrito("venue-1", llave, carrito, cliente)
        val dao = io.mockk.spyk(db.pendingPaymentDao())
        coEvery { dao.existeFilaConLlave(llave) } throws java.io.IOException("No se pudo leer Room")
        val conError = VentaSinRedRepositoryImpl(carritos, dao, VentaLocalRepository(dao, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()), turnos, secureStorage, contexto)
        var fallo: Throwable? = null
        try { conError.carritoParaRestaurar("venue-1") } catch (e: Exception) { fallo = e }
        assertThat(fallo).isNotNull()
        assertThat(carritos.leer("venue-1")!!.first).isEqualTo(llave)
    }

    @Test fun `ticket ilegible no se marca visto ni se descarta como venta ausente`() = runTest {
        val dao = db.pendingPaymentDao()
        VentaLocalRepository(dao, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()).guardar(ventaEnCero(), PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1").getOrThrow()
        db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET ticket_json = ?", arrayOf("{no es ticket"))
        var fallo: Throwable? = null
        try { repo.ultimaVentaSinMostrar("venue-1") } catch (e: Exception) { fallo = e }
        assertThat(fallo).isNotNull()
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!.ticketJson).isEqualTo("{no es ticket")
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!.exitoMostrado).isFalse()
    }

    @Test fun `multiples confirmaciones Room se recorren por su llave sin reconocer otra sucursal`() = runTest {
        val primera = ventaEnCero().copy(createdAtLocal = 1000)
        val segunda = primera.copy(llave = "c000000000000000000000002", createdAtLocal = 2000,
            renglones = primera.renglones.map { it.copy(nombre = "Segundo ticket") })
        val otraSucursal = primera.copy(llave = "c000000000000000000000003", venueId = "venue-2", createdAtLocal = 3000)
        val escritor = VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
        for (venta in listOf(primera, segunda, otraSucursal)) {
            escritor.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1").getOrThrow()
        }
        assertThat(repo.ultimaVentaSinMostrar("venue-1")?.venta).isEqualTo(segunda)
        repo.marcarVentaVista(segunda.llave)
        assertThat(repo.ultimaVentaSinMostrar("venue-1")?.venta).isEqualTo(primera)
        assertThat(repo.ultimaVentaSinMostrar("venue-2")?.venta).isEqualTo(otraSucursal)
        repo.marcarVentaVista(primera.llave)
        assertThat(repo.ultimaVentaSinMostrar("venue-1")).isNull()
        assertThat(db.pendingPaymentDao().findByReference(referenciaDeEfectivo(otraSucursal.llave), "venue-2")!!.exitoMostrado).isFalse()
    }

}
