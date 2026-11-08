package com.jaac.avoqado_tpv.core.printer

import com.angelpay.angelpaysdk.AngelPaySDK
import com.jaac.avoqado_tpv.features.onlineorders.data.ComandaDeApoyo
import com.jaac.avoqado_tpv.features.onlineorders.data.FoliosRepository
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
import org.junit.Assert.assertFalse
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
        coEvery { folios.reclamarImpresion("v1", "ord-1") } returns Result.success(true)
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
}
