package com.jaac.avoqado_tpv.core.printer

import com.angelpay.angelpaysdk.AngelPaySDK
import com.jaac.avoqado_tpv.features.onlineorders.data.ComandaDeApoyo
import com.jaac.avoqado_tpv.features.onlineorders.data.FoliosRepository
import com.jaac.avoqado_tpv.features.onlineorders.data.ReclamoDeComanda
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.nexgo.oaf.apiv3.SdkResult
import com.nexgo.oaf.apiv3.device.printer.Printer
import com.nexgo.oaf.apiv3.device.printer.OnPrintListener
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class ComandaNexgoResultadoFinalTest {
    private val printer = mockk<Printer>(relaxed = true)
    private val gateway = AngelPaySdkGateway()
    private val folios = mockk<FoliosRepository>()
    private lateinit var manager: PrinterManager
    private val folio = ComandaDeFolio(
        "ord-1", "W5GZD", "Ana", null,
        listOf(FolioProducto("Latte", 1, null, emptyList())),
    )

    @Before fun setup() {
        mockkObject(AngelPaySDK)
        every { AngelPaySDK.getDeviceInfo() } returns null
        every { AngelPaySDK.printTicket(any()) } returns Result.success(Unit)
        every { printer.status } returns SdkResult.Printer_PaperLack
        gateway.receiptPrinterFactory = { printer }
        manager = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), javax.inject.Provider { gateway })
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(manager, false)
        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(ReclamoDeComanda(true))
        coEvery { folios.confirmarImpresion("v1", "ord-1") } returns Result.success(true)
    }

    @After fun finish() = unmockkAll()

    @Test fun `una comanda sin papel no se confirma aunque el SDK acepte el envio`() = runBlocking {
        ComandaDeApoyo(folios, manager).intentar("v1", folio)

        coVerify(exactly = 1) { folios.reclamarImpresion("v1", "ord-1") }
        coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
        verify(exactly = 0) { printer.startPrint(any(), any()) }
        verify(exactly = 0) { AngelPaySDK.printTicket(any()) }
    }

    private fun callbackFinal(code: Int) {
        every { printer.status } returns SdkResult.Success
        val started = CountDownLatch(1)
        var callback: OnPrintListener? = null
        every { printer.startPrint(any(), any()) } answers {
            callback = secondArg()
            started.countDown()
            SdkResult.Success
        }
        val executor = Executors.newSingleThreadExecutor()
        val pending = executor.submit {
            runBlocking { ComandaDeApoyo(folios, manager).intentar("v1", folio) }
        }
        try {
            assertTrue("la comanda debe llegar a la impresora", started.await(5, TimeUnit.SECONDS))
            assertFalse("aceptar startPrint no acredita papel", pending.isDone)
            coVerify(exactly = 0) { folios.confirmarImpresion(any(), any()) }
            callback!!.onPrintResult(code)
            pending.get(5, TimeUnit.SECONDS)
            coVerify(exactly = if (code == SdkResult.Success) 1 else 0) { folios.confirmarImpresion("v1", "ord-1") }
            verify(exactly = 1) { printer.startPrint(any(), any()) }
            verify(exactly = 0) { AngelPaySDK.printTicket(any()) }
        } finally {
            callback?.onPrintResult(SdkResult.Printer_PaperLack)
            executor.shutdownNow()
        }
    }

    @Test fun `el fallo final de papel no confirma una comanda cuyo inicio fue aceptado`() {
        callbackFinal(SdkResult.Printer_PaperLack)
    }

    @Test fun `solo el callback final exitoso confirma la comanda una vez`() {
        callbackFinal(SdkResult.Success)
    }

    @Test fun `las reimpresiones conservan la negativa del SDK y no resuelven el gateway`() {
        every { AngelPaySDK.printTicket(any()) } returns Result.failure<Unit>(IllegalStateException("C219"))

        assertTrue(manager.printCashConfirmationReceipt("119.00").isFailure)
        assertTrue(manager.printKitchenTicket("W5GZD", orderItems = listOf(mockk(relaxed = true))).isFailure)

        verify(exactly = 2) { AngelPaySDK.printTicket(any()) }
        verify(exactly = 0) { printer.startPrint(any(), any()) }
    }

    @Test fun `la comanda en PAX no obtiene el proveedor de AngelPay`() {
        val provider = mockk<javax.inject.Provider<AngelPaySdkGateway>>()
        val pax = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), provider)
        val hardware = mockk<ImpresoraTermica>(relaxed = true)
        every { hardware.start() } returns 0
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(pax, true)
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }.set(pax, lazyOf(hardware))

        assertTrue(pax.printKitchenTicket("W5GZD", orderItems = listOf(mockk(relaxed = true)), esComandaDeFolio = true).isSuccess)

        verify(exactly = 0) { provider.get() }
        verify(exactly = 0) { AngelPaySDK.printTicket(any()) }
    }

    /** D661 · Ruling 9/37: el reclamo se concedió sobre otro que venció sin confirmarse: la cocina lo lee antes que nada. */
    @Test fun `Ruling 9 una comanda posiblemente repetida dice POSIBLE REPETIDA arriba de todo`() {
        val pax = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), mockk())
        val hardware = mockk<ImpresoraTermica>(relaxed = true)
        every { hardware.start() } returns 0
        val renglones = mutableListOf<String>()
        every { hardware.printStr(capture(renglones), any()) } returns Unit
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(pax, true)
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }.set(pax, lazyOf(hardware))

        assertTrue(pax.printKitchenTicket("W5GZD", orderItems = listOf(mockk(relaxed = true)), esComandaDeFolio = true, posibleRepetida = true).isSuccess)
        val marca = renglones.indexOfFirst { it.contains("POSIBLE REPETIDA") }
        assertTrue("la marca sale: $renglones", marca >= 0)
        assertTrue("antes del encabezado de la comanda", marca < renglones.indexOfFirst { it.contains("COMANDA DE COCINA") })
        assertTrue(renglones.any { it.contains("revisa si ya salió") })

        renglones.clear()
        assertTrue(pax.printKitchenTicket("W5GZD", orderItems = listOf(mockk(relaxed = true)), esComandaDeFolio = true).isSuccess)
        assertFalse("sin la marca, como siempre", renglones.any { it.contains("POSIBLE REPETIDA") })
    }

    /**
     * D661 · Ruling 50 (enmendado): se pregunta justo antes del ARRANQUE físico (`start()`), con todo ya dictado y la impresora en mano;
     * si ya no toca, no arranca: el dictado se tira y no sale nada.
     */
    @Test fun `Ruling 50 si ya no toca justo antes de arrancar la impresora no sale nada aunque ya este dictada`() {
        val pax = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), mockk())
        val hardware = mockk<ImpresoraTermica>(relaxed = true)
        every { hardware.start() } returns 0
        val renglones = mutableListOf<String>()
        every { hardware.printStr(capture(renglones), any()) } returns Unit
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(pax, true)
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }.set(pax, lazyOf(hardware))
        val candado = PrinterManager::class.java.getDeclaredField("candado").apply { isAccessible = true }.get(pax)!!
        var dictadosAlPreguntar = -1
        var conLaImpresora = false

        val r = pax.printKitchenTicket(
            "W5GZD", orderItems = listOf(mockk(relaxed = true)), esComandaDeFolio = true,
            sigueVigente = {
                dictadosAlPreguntar = renglones.size
                conLaImpresora = Thread.holdsLock(candado)
                false
            },
        )
        assertTrue("se pregunta ya dictada, justo antes de arrancar: $dictadosAlPreguntar", dictadosAlPreguntar > 0)
        assertTrue("con la impresora en mano", conLaImpresora)
        assertTrue("regresa ComandaNoEmpezada: ${r.exceptionOrNull()}", r.exceptionOrNull() is ComandaNoEmpezada)
        verify(exactly = 0) { hardware.start() }

        assertTrue(pax.printKitchenTicket("W5GZD", orderItems = listOf(mockk(relaxed = true)), esComandaDeFolio = true, sigueVigente = { true }).isSuccess)
        verify(exactly = 1) { hardware.start() }
    }

    /**
     * D661 · Ruling 69 (b), ronda 3: un arranque fallido de la impresora no se queda guardado, pero tampoco se reintenta en cada uso
     * (`canPrintReceipt` se lee al pintar: sin tope, una PAX con la impresora mala reintentaba en cada recomposición). A lo más uno cada
     * [REINTENTO_DE_ARRANQUE_MS]; uno bueno se guarda.
     */
    @Test fun `Ruling 69 un arranque fallido de la impresora se reintenta a lo mas cada 30 s y uno bueno se guarda`() {
        var ahora = 1_000L
        var intentos = 0
        var arranca = false
        val impresora by lazyQueReintenta(reloj = { ahora }) {
            intentos++
            if (arranca) "PAX" else null
        }
        assertNull("la primera falla", impresora)
        assertEquals(1, intentos)

        ahora += 10_000
        arranca = true
        assertNull("a los 10 s no se intenta otra vez", impresora)
        assertNull(impresora)
        assertEquals(1, intentos)

        ahora += REINTENTO_DE_ARRANQUE_MS - 10_000
        assertEquals("a los 30 s se intenta y arranca, sin reiniciar la app", "PAX", impresora)
        assertEquals(2, intentos)

        ahora += 10 * REINTENTO_DE_ARRANQUE_MS
        assertEquals("PAX", impresora)
        assertEquals("la buena se guarda", 2, intentos)
    }
}
