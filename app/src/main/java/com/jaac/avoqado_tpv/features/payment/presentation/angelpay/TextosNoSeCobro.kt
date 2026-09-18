package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

/**
 * Lo que la terminal Nexgo DICE cuando el SDK de AngelPay 1.0.19 acreditó que el cobro NO salió al banco
 * (`AngelPayOutcomeClassifier.decidirSegunElSdk119` → `SIN_AUTORIZACION`). Diseño `diseno-nexgo-sdk-1.0.19.md` §2.4.
 *
 * Tres destinatarios, un solo sitio (un texto distinto en cada lado es un texto que nadie revisa):
 *  - [pagoRapido]: la pantalla de la terminal en un cobro LOCAL (Pago rápido o una orden cobrada en la terminal).
 *    Va con «Intentar de nuevo»: un intento y una referencia NUEVOS.
 *  - [paraElPos]: el `errorMessage` del `failed + PRE_AUTHORIZATION` que ve la tablet en un cobro que mandó el POS.
 *  - [terminalEnCobroDelPos]: la pantalla de la terminal en ese cobro del POS; la solicitud ya se cerró, así que dice
 *    que se vuelva a enviar desde el punto de venta (y la pantalla sale sola).
 *
 * 🔴 Todos afirman «no se cobró» porque ése es el HECHO que el SDK acreditó; ninguno culpa al cajero.
 */
internal object TextosNoSeCobro {

    const val TITULO = "No se cobró"
    const val INTENTAR_DE_NUEVO = "Intentar de nuevo"
    const val VUELVE_A_ENVIAR = "Vuelve a enviar el cobro desde el punto de venta."

    fun pagoRapido(codigoSdk: String?, mensajeSdk: String?): String = when (normalizar(codigoSdk)) {
        "U101" -> "Tiempo agotado: nadie acercó una tarjeta. No se cobró nada."
        "E618" -> "Retira la tarjeta del lector: no se cobró nada."
        "U100" -> "Cobro cancelado en la terminal: no se cobró nada."
        else -> "No se cobró nada: ${causa(codigoSdk, mensajeSdk)}"
    }

    fun paraElPos(codigoSdk: String?, mensajeSdk: String?): String = when (normalizar(codigoSdk)) {
        "U101" -> "Nadie acercó una tarjeta: no se cobró."
        "E618" -> "La terminal tenía una tarjeta puesta: no se cobró. Pide que la retiren y vuelve a cobrar."
        "U100" -> "Se canceló en la terminal: no se cobró."
        // §2.4: para E622 · E699 · I999 (y cualquier otro) la tablet dice «lo mismo» que la terminal.
        else -> pagoRapido(codigoSdk, mensajeSdk)
    }

    fun terminalEnCobroDelPos(codigoSdk: String?, mensajeSdk: String?): String =
        "${pagoRapido(codigoSdk, mensajeSdk)} $VUELVE_A_ENVIAR"

    /** La causa, en palabras del mostrador. Un código que no está aquí se nombra con el mensaje del SDK y su código. */
    private fun causa(codigoSdk: String?, mensajeSdk: String?): String {
        val codigo = normalizar(codigoSdk)
        return when (codigo) {
            "E622" -> "esta terminal no acepta tarjetas AMEX (E622)."
            "E699" -> "la terminal no pudo procesar la tarjeta (E699)."
            "I999" -> "la terminal tuvo un error antes de enviar el cobro al banco (I999)."
            else -> {
                val mensaje = mensajeSdk?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: "el cobro no salió al banco"
                if (codigo.isNullOrBlank()) "$mensaje." else "$mensaje ($codigo)."
            }
        }
    }

    private fun normalizar(codigo: String?): String? = codigo?.trim()?.uppercase()
}
