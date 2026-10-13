package com.jaac.avoqado_tpv.core.printer

import android.graphics.Bitmap
import com.pax.dal.IPrinter

/**
 * Lo único que [PrinterManager] le pide a una impresora: que le dicten renglón por renglón
 * (`printStr`, `printBitmap`) y que al final imprima (`start`).
 *
 * Existe para que las 10 impresiones de [PrinterManager] (recibo, corte, reportes, historial,
 * resumen de pagos, comanda, kiosco, efectivo, prueba) salgan en las DOS marcas sin tocarlas:
 * - PAX → [ImpresoraPax], que pasa cada orden tal cual al `IPrinter` de Neptune.
 * - Nexgo → [ImpresoraAngelPay], que junta el dictado y lo manda como UN ticket por el SDK
 *   de AngelPay (en la Nexgo la impresora sólo se alcanza por ahí).
 *
 * Los nombres y firmas copian los de `IPrinter` a propósito: así las ~220 llamadas de
 * [PrinterManager] no cambian ni una letra.
 */
internal interface ImpresoraTermica {
    fun init()
    fun printStr(str: String, charset: String?)
    fun printBitmap(bitmap: Bitmap)

    /** 0 = salió; cualquier otro número = falló (mismo contrato que `IPrinter.start()`). */
    fun start(): Int
    fun getStatus(): Int
}

/** PAX: delega 1:1 en Neptune. Lo que ya imprimía la PAX sigue igual, orden por orden. */
internal class ImpresoraPax(private val pax: IPrinter) : ImpresoraTermica {
    override fun init() = pax.init()
    override fun printStr(str: String, charset: String?) = pax.printStr(str, charset)
    override fun printBitmap(bitmap: Bitmap) = pax.printBitmap(bitmap)
    override fun start(): Int = pax.start()
    override fun getStatus(): Int = pax.getStatus()
}
