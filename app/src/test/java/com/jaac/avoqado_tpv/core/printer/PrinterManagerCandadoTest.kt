package com.jaac.avoqado_tpv.core.printer

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import io.mockk.mockk
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * P4 · R83 (I1): la PAX tiene UNA impresora. Si la comanda de apoyo de un folio (hilo de IO) y el recibo del cajero (otro hilo)
 * imprimen a la vez, el `init()` de uno borra lo que el otro ya dictó: sale un papel revuelto y la comanda se confirma igual.
 * De `init()` a `start()` la impresora es de UNA sola impresión.
 */
class PrinterManagerCandadoTest {

    /** Anota cada orden con su hilo; cada renglón tarda un poco, para que dos impresiones sin candado se encimen. */
    private class ImpresoraQueAnota : ImpresoraTermica {
        val ordenes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        private fun anotar(orden: String) { ordenes += Thread.currentThread().name to orden }
        override fun init() = anotar("init")
        override fun printStr(str: String, charset: String?) { anotar("printStr"); Thread.sleep(1) }
        override fun printBitmap(bitmap: Bitmap) = anotar("printBitmap")
        override fun start(): Int { anotar("start"); return 0 }
        override fun getStatus(): Int = 0
    }

    private val renglones = listOf(
        OrderItem(
            id = "r1", orderId = "ord-1", productId = "", productName = "Latte", productSku = null, quantity = 1,
            unitPrice = BigDecimal.ZERO, totalPrice = BigDecimal.ZERO, notes = null, kitchenStatus = KitchenStatus.PENDING,
            createdAt = Instant.EPOCH, sentToKitchenAt = null,
        ),
    )

    @Test
    fun `dos impresiones a la vez nunca se enciman - de init a start la impresora es de una sola`() {
        val impresora = ImpresoraQueAnota()
        val manager = PrinterManager(mockk(relaxed = true), mockk(relaxed = true))
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }
            .set(manager, lazyOf(impresora))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(manager, true)

        val salida = CountDownLatch(1)
        val comanda = thread(name = "comanda") { salida.await(); repeat(3) { manager.printKitchenTicket("W7K3P", null, renglones) } }
        val recibo = thread(name = "recibo") { salida.await(); repeat(3) { manager.printCashConfirmationReceipt("85.00") } }
        salida.countDown()
        comanda.join()
        recibo.join()

        var dueno: String? = null
        impresora.ordenes.forEachIndexed { i, (hilo, orden) ->
            when (orden) {
                "init" -> {
                    assertWithMessage("orden $i: «$hilo» empezó con la impresora de «$dueno»").that(dueno).isNull()
                    dueno = hilo
                }
                else -> assertWithMessage("orden $i ($orden) de «$hilo» dentro de la impresión de «$dueno»").that(hilo).isEqualTo(dueno)
            }
            if (orden == "start") dueno = null
        }
        assertThat(impresora.ordenes.count { it.second == "start" }).isEqualTo(6)
    }
}
