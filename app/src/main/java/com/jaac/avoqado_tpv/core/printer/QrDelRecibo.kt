package com.jaac.avoqado_tpv.core.printer

/**
 * Las dos reglas del QR del recibo, separadas de la impresora para poder probarlas.
 *
 * `PrinterManager` necesita el SDK de PAX y no se puede instanciar en un test unitario, así que la
 * DECISIÓN vive aquí y el cableado allá — el mismo patrón de `ReportPaymentBlock`.
 *
 * 🔴 Lo que estas reglas cierran (Asana, 11-sep-2026):
 *  - Una URL VACÍA no es una URL: el cliente guarda `""` cuando el servidor responde sin recibo, y
 *    con `!= null` entraba al bloque y ZXing fallaba con contenido vacío, dejando la leyenda sola.
 *  - La leyenda sólo promete factura si el ticket SE PUEDE autofacturar; prometerla donde el
 *    negocio no la tiene prendida manda al cliente a buscar un botón que no existe.
 *
 * (Que la leyenda además dependa de que el BITMAP se haya generado vive en `imprimirQrDelRecibo`,
 * porque necesita la impresora.)
 */
object QrDelRecibo {

    /** ¿Hay liga con la que valga la pena intentar el QR? */
    fun debeIntentarse(receiptUrl: String?): Boolean = !receiptUrl.isNullOrBlank()

    /** El texto que va debajo del QR. Espejo exacto de android e iOS. */
    fun leyenda(autofacturaAvailable: Boolean): String =
        if (autofacturaAvailable) "Escanea para recibo y factura\n\n" else "Escanea para recibo digital\n\n"
}
