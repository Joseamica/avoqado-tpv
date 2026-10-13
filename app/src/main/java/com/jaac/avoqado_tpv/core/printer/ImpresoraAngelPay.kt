package com.jaac.avoqado_tpv.core.printer

import android.graphics.Bitmap
import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.PrintAlignmentRequest
import com.angelpay.angelpaysdk.models.PrintStyleRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemType
import com.angelpay.angelpaysdk.models.PrintTicketRequest
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * La impresora de la Nexgo, vista como si fuera la de PAX.
 *
 * 🔴 Hasta la 2.12.4, en una Nexgo sólo salía el recibo del cobro (que arma su propio ticket en
 * `AngelPayTicketBuilder`). El corte, los reportes, el historial, la comanda y todo lo demás de
 * [PrinterManager] contestaba «Esta terminal no imprime desde Avoqado»: esas impresiones le
 * dictan renglón por renglón a la impresora de PAX, y en la Nexgo esa impresora no existe —
 * sólo se alcanza la suya a través del SDK de AngelPay, que pide el ticket COMPLETO de una vez.
 *
 * Esta clase escucha el mismo dictado, lo junta y en [start] lo entrega como un solo
 * `PrintTicketRequest`. Una instancia por impresión (la crea [paraEstaTerminal]), así que dos
 * impresiones nunca comparten el dictado.
 *
 * El dictado viene acomodado con ESPACIOS para la letra de ancho fijo de la PAX. El SDK de
 * AngelPay imprime con `Typeface.DEFAULT` (letra de ancho variable, visto con javap en el AAR
 * 1.0.21: `x.c.a` → `Printer.setTypeface(Typeface.DEFAULT)`), así que copiar los espacios
 * dejaría columnas y centrados chuecos. [TicketDictado] traduce cada renglón a la pieza de
 * AngelPay que hace eso de verdad (dos columnas, centrado, divisor).
 */
internal class ImpresoraAngelPay(
    private val enviar: (PrintTicketRequest) -> Result<*> = { AngelPaySDK.printTicket(it) },
) : ImpresoraTermica {

    private val dictado = StringBuilder()
    private val piezas = mutableListOf<PrintTicketItemRequest>()

    override fun init() {
        dictado.clear()
        piezas.clear()
    }

    override fun printStr(str: String, charset: String?) {
        dictado.append(str)
    }

    override fun printBitmap(bitmap: Bitmap) {
        volcarDictado()
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        piezas += PrintTicketItemRequest(
            type = PrintTicketItemType.IMAGE_BASE64,
            imageBase64 = android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP),
            targetWidthPx = bitmap.width,
        )
    }

    override fun start(): Int {
        volcarDictado()
        // El SDK 1.0.16+ DEVUELVE `Result` en vez de lanzar: si no se abre, un fallo pasa por éxito.
        return enviar(PrintTicketRequest(body = piezas.toList())).fold(
            onSuccess = { 0 },
            onFailure = {
                Timber.e(it, "🖨️ [Printer] AngelPay no imprimió el ticket")
                -1
            },
        )
    }

    /** El SDK de AngelPay no expone el estado de la impresora; el error real llega en [start]. */
    override fun getStatus(): Int = 0

    private fun volcarDictado() {
        piezas += TicketDictado.traducir(dictado.toString())
        dictado.clear()
    }

    companion object {
        /** Modelos Nexgo con impresora. La N62 no tiene. Lista única: la usa también la pantalla de cobro. */
        val MODELOS_CON_IMPRESORA = listOf("N86")

        /**
         * `null` sólo si el SDK dice que ESTE modelo no tiene impresora. Si el modelo no se conoce
         * (SDK todavía sin iniciar), se intenta y el SDK contesta con su error.
         */
        fun paraEstaTerminal(): ImpresoraAngelPay? {
            val modelo = runCatching { AngelPaySDK.getDeviceInfo()?.model }.getOrNull()?.uppercase().orEmpty()
            if (modelo.isNotBlank() && MODELOS_CON_IMPRESORA.none { it in modelo }) return null
            return ImpresoraAngelPay()
        }
    }
}

/**
 * Traduce el dictado de ancho fijo (32 letras, el de la PAX) a piezas del ticket de AngelPay.
 * Puro y sin Android: se prueba en `TicketDictadoTest`.
 */
internal object TicketDictado {

    /** «izquierda   derecha»: el ÚLTIMO hueco de 2+ espacios parte el renglón. */
    private val DOS_COLUMNAS = Regex("""^(\s*\S.*\S|\s*\S)\s{2,}(\S+(?: \S+)*)$""")

    /** Sangría a partir de la cual el renglón venía centrado (o pegado a la derecha) con espacios. */
    private const val SANGRIA_DE_CENTRADO = 3
    private const val ANCHO = 32

    fun traducir(texto: String): List<PrintTicketItemRequest> {
        if (texto.isEmpty()) return emptyList()
        val renglones = texto.split('\n').let { if (texto.endsWith('\n')) it.dropLast(1) else it }
        return renglones.map(::renglon)
    }

    private fun renglon(linea: String): PrintTicketItemRequest {
        val texto = linea.trimEnd()
        val limpio = texto.trim()
        if (limpio.isEmpty()) return PrintTicketItemRequest(type = PrintTicketItemType.SPACER, lines = 1)

        // «====» y «----»: siempre con «-». El «=» es más ancho en la letra de AngelPay y
        // 32 de ellos no caben en el papel (se partirían en dos renglones).
        if (limpio.length >= 8 && limpio.all { it == '=' || it == '-' }) {
            return PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER)
        }

        DOS_COLUMNAS.matchEntire(texto)?.let { m ->
            return PrintTicketItemRequest(
                type = PrintTicketItemType.TWO_COLUMNS,
                // Conserva la sangría corta del corte («  Venta» debajo de «Efectivo»).
                left = m.groupValues[1],
                right = m.groupValues[2],
            )
        }

        val sangria = texto.length - texto.trimStart().length
        val alineacion = when {
            sangria < SANGRIA_DE_CENTRADO -> PrintAlignmentRequest.LEFT
            texto.length >= ANCHO - 1 -> PrintAlignmentRequest.RIGHT
            else -> PrintAlignmentRequest.CENTER
        }
        return PrintTicketItemRequest(
            type = PrintTicketItemType.TEXT,
            text = if (alineacion == PrintAlignmentRequest.LEFT) texto else limpio,
            style = PrintStyleRequest(alignment = alineacion),
        )
    }
}
