package com.jaac.avoqado_tpv.features.payment.presentation

/**
 * Qué dijo el kernel contactless del SDK de Blumon TPV cuando `StartCtlssTrans` falla, y qué hacemos
 * con eso. El kernel corre DENTRO de la PAX y decide ANTES de autorizar: lo que rechaza aquí nunca
 * llega a la plataforma de Blumon TPV, así que su portal no lo muestra.
 *
 * ## Por qué existe
 *
 * Testarudo, 2026-09-07: «no está pasando el contactless, en todas las tarjetas». En el log del
 * aparato (Crashlytics) cada toque fallido era `StartCtlssTransFailure$CtlssDeniedFailure` — nueve en
 * tres ventas — mientras el mismo aparato aceptaba otras tres tarjetas por NFC en esos minutos. La app
 * caía a la rama genérica («Error leyendo tarjeta contactless… intente nuevamente o inserte la
 * tarjeta»), la cajera cancelaba, la tablet reenviaba el cobro, volvían a acercar la MISMA tarjeta y el
 * kernel la denegaba otra vez: cuatro vueltas por venta hasta que entró con chip a la primera.
 *
 * ## Lo que da el SDK
 *
 * `StartCtlssTransFailure` tiene subclases (verificadas con `javap` en el AAR sandbox 1.6.1.2 y en
 * `blumon_sdk-prod`): `CtlssDeniedFailure`, `CtlssUseContactFailure`, `EmvNoAppFailure`,
 * `ReadingContactlessFailure`, `ContactlessFailure` y —sólo en sandbox— `ContactlessSeePhoneFailure`.
 * Todas cargan `emvCode: Int`. Su `toString()` NO lo imprime (sale `…CtlssDeniedFailure@2cba583`), así
 * que se clasifica por el NOMBRE de la clase y el código se reporta aparte.
 *
 * Función PURA, sin tipos del SDK ni de Android: `main` también compila para Nexgo, donde el SDK de
 * PAX no existe. Mismo patrón que `SdkFailureClassifier`.
 */
/**
 * 🔴 Las negativas que el kernel decide DENTRO de la PAX, antes de salir a autorizar:
 * no llegan al procesador, así que prueban que no hubo cobro y liberan la terminal.
 *
 * `TIMEOUT` y `OTHER` quedan FUERA a propósito — pueden esconder una transacción que sí
 * avanzó, y liberar sobre una duda es exactamente lo que produce un doble cobro.
 */
internal val KERNEL_REFUSALS_WITHOUT_CHARGE = setOf(
    ContactlessOutcome.DENIED_BY_KERNEL,
    ContactlessOutcome.USE_CONTACT,
    ContactlessOutcome.NO_APP,
    ContactlessOutcome.SEE_PHONE,
    ContactlessOutcome.CARD_REMOVED,
    ContactlessOutcome.COLLISION,
)

internal enum class ContactlessOutcome {
    /** El kernel denegó la tarjeta por contactless (`CtlssDeniedFailure`). */
    DENIED_BY_KERNEL,
    /** La tarjeta exige la interfaz de contacto (`CtlssUseContactFailure`). */
    USE_CONTACT,
    /** Ninguna aplicación EMV de la tarjeta sirve por contactless (`EmvNoAppFailure`). */
    NO_APP,
    /** El teléfono o reloj pide confirmación del titular (`ContactlessSeePhoneFailure`). */
    SEE_PHONE,
    /** La tarjeta se retiró antes de terminar la lectura (`ReadingContactlessFailure`). */
    CARD_REMOVED,
    TIMEOUT,
    COLLISION,
    OTHER,
}

internal data class ContactlessFailureVerdict(
    val outcome: ContactlessOutcome,
    /** Texto para la cajera: qué pasó y qué hacer. */
    val userMessage: String,
    /**
     * `true` cuando volver a acercar la MISMA tarjeta sólo repetiría el rechazo: el siguiente
     * `StartDetectCard` debe abrir el lector SIN PICC (sólo chip y banda).
     */
    val chipOnlyOnRetry: Boolean,
)

/**
 * Catálogo de códigos del kernel EMV de PAX.
 *
 * El `emvCode` que carga `StartCtlssTransFailure` **es** `TransResult.getResultCode()`, o sea una
 * constante de `com.pax.jemv.clcommon.RetCode` (viene en `emv/libs/COMMON_v103.jar`). Verificado
 * desensamblando `TransProcessRepositoryImpl.startCtlssTrans` con `javap -c` en los dos AAR.
 *
 * 🔴 **Por qué los números viven aquí a mano y no se importa `RetCode`:** este archivo es de
 * `main/`, que compila también para Nexgo, y la regla del archivo es no arrastrar tipos del SDK de
 * PAX. La copia se fija contra el jar real en
 * `ContactlessKernelResultTest.las constantes son las de PAX` — si PAX cambia un valor, truena la
 * prueba en vez de que la cajera lea un motivo equivocado.
 *
 * ## Por qué traducirlo importa
 *
 * `startCtlssTrans` sólo tipa unos pocos códigos y manda **todo lo demás** al `default`, que
 * devuelve `CtlssDeniedFailure`:
 *
 * ```
 *   SDK de producción (nov-2025) : switch de 4 casos  (-6, -23, -27, -2)
 *   SDK del 10-dic-2025          : switch de 5 casos  (-6, -23, -27, -2, -40)
 * ```
 *
 * O sea que «el lector no aceptó la tarjeta» agrupa hoy un rechazo real (-27) con «vuelve a
 * acercarla» (-48), «tarjeta vencida» (-36) y «confirma en tu teléfono» (-40). Testarudo,
 * 2026-09-07: nueve toques «denegados» en tres ventas, indistinguibles entre sí.
 */
internal object EmvKernelCode {

    const val ICC_CMD_ERR = -2
    const val ICC_BLOCK = -3
    const val EMV_APP_BLOCK = -5
    const val EMV_NO_APP = -6
    const val EMV_USER_CANCEL = -7
    const val EMV_TIME_OUT = -8
    const val EMV_NOT_ACCEPT = -10
    const val EMV_DENIAL = -11
    const val CLSS_USE_CONTACT = -23
    const val CLSS_TERMINATE = -25
    const val CLSS_FAILED = -26
    const val CLSS_DECLINE = -27
    const val CLSS_TRY_ANOTHER_CARD = -28
    const val CLSS_RESELECT_APP = -35
    const val CLSS_CARD_EXPIRED = -36
    const val CLSS_CVMDECLINE = -39
    const val CLSS_REFER_CONSUMER_DEVICE = -40
    const val CLSS_CARD_EXPIRED_REQ_ONLINE = -46
    const val CLSS_TRY_AGAIN = -48
    const val CLSS_AUTH_CONSUMER_DEVICE = -51
    const val CLSS_PAYMENT_NOT_ACCEPT = -200

    /**
     * Motivo en lenguaje de mostrador. Va DENTRO de una frase, así que empieza en minúscula y no
     * lleva punto final.
     */
    val SIGNIFICADOS: Map<Int, String> = mapOf(
        ICC_CMD_ERR to "no se pudo leer la tarjeta",
        ICC_BLOCK to "la tarjeta está bloqueada",
        EMV_APP_BLOCK to "la aplicación de la tarjeta está bloqueada",
        EMV_NO_APP to "la tarjeta no trae una aplicación compatible",
        EMV_USER_CANCEL to "se canceló la operación",
        EMV_TIME_OUT to "se agotó el tiempo de espera",
        EMV_NOT_ACCEPT to "el lector no acepta esta tarjeta",
        EMV_DENIAL to "el lector la denegó sin salir a autorizar",
        CLSS_USE_CONTACT to "la tarjeta exige el chip",
        CLSS_TERMINATE to "el lector terminó la operación",
        CLSS_FAILED to "falló la lectura contactless",
        CLSS_DECLINE to "la tarjeta rechazó el pago sin salir a autorizar",
        CLSS_TRY_ANOTHER_CARD to "el lector pide otra tarjeta",
        CLSS_RESELECT_APP to "hay que volver a elegir la aplicación de la tarjeta",
        CLSS_CARD_EXPIRED to "la tarjeta está vencida",
        CLSS_CVMDECLINE to "no se pudo verificar al titular",
        CLSS_REFER_CONSUMER_DEVICE to "hay que confirmar en el teléfono o reloj del cliente",
        CLSS_CARD_EXPIRED_REQ_ONLINE to "la tarjeta está vencida y pide autorización en línea",
        CLSS_TRY_AGAIN to "el lector pide volver a acercarla",
        CLSS_AUTH_CONSUMER_DEVICE to "hay que autenticar en el dispositivo del cliente",
        CLSS_PAYMENT_NOT_ACCEPT to "el lector no acepta este pago",
    )

    fun significado(emvCode: Int?): String? = emvCode?.let { SIGNIFICADOS[it] }

    /** `" (código -36)"`, o `""` si el SDK no dio código. */
    fun sufijo(emvCode: Int?): String = emvCode?.let { " (código $it)" } ?: ""
}

internal object ContactlessKernelResult {

    private const val INSERTAR = "Pide al cliente que INSERTE la tarjeta en la ranura del chip."

    /**
     * @param failureClassName `failure.javaClass.simpleName` (p. ej. `CtlssDeniedFailure`).
     * @param emvCode `StartCtlssTransFailure.emvCode`, si se pudo leer.
     * @param failureText `failure.toString()`; sólo decide en los casos que el SDK no tipa
     *   (timeout, colisión), igual que antes de este cambio.
     */
    fun classify(failureClassName: String?, emvCode: Int?, failureText: String?): ContactlessFailureVerdict {
        val name = failureClassName.orEmpty()
        val text = failureText.orEmpty()
        val codigo = EmvKernelCode.sufijo(emvCode)
        // Motivo traducido, como frase propia. Sólo cuando lo conocemos: inventar un motivo
        // sería peor que dar el número pelón.
        val motivo = EmvKernelCode.significado(emvCode)?.let { "Motivo: $it.\n\n" } ?: ""
        return when {
            name.contains("CtlssUseContact", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.USE_CONTACT,
                userMessage = "Esta tarjeta pide chip$codigo.\n\n$INSERTAR\n\n" +
                    "Si la vuelven a acercar, el lector la va a rechazar otra vez.",
                chipOnlyOnRetry = true,
            )
            // 🔴 El SDK de producción de hoy NO tipa el -40: su switch tiene 4 casos y este código
            // cae al `default`, que devuelve `CtlssDeniedFailure`. Pero -40 no es un rechazo —
            // `CLSS_REFER_CONSUMER_DEVICE` pide autenticar en el teléfono o reloj del cliente. Con
            // el veredicto genérico le decíamos «inserta la tarjeta» y además CERRÁBAMOS el lector
            // NFC (`chipOnlyOnRetry`), así que ni siquiera podía volver a acercar el teléfono, que
            // era lo único que faltaba. El SDK del 10-dic-2025 lo devuelve ya como
            // `ContactlessSeePhoneFailure`; traducir el código nos da ese comportamiento HOY.
            name.contains("CtlssDenied", ignoreCase = true) &&
                emvCode == EmvKernelCode.CLSS_REFER_CONSUMER_DEVICE -> confirmarEnElTelefono(codigo)
            name.contains("CtlssDenied", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.DENIED_BY_KERNEL,
                userMessage = "El lector no aceptó esta tarjeta por contactless$codigo.\n\n$motivo$INSERTAR\n\n" +
                    "No es un rechazo del banco: el cobro todavía no se ha intentado.",
                chipOnlyOnRetry = true,
            )
            name.contains("EmvNoApp", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.NO_APP,
                userMessage = "Esta tarjeta no es compatible con contactless en esta terminal$codigo.\n\n$INSERTAR",
                chipOnlyOnRetry = true,
            )
            name.contains("SeePhone", ignoreCase = true) -> confirmarEnElTelefono(codigo)
            name.contains("ReadingContactless", ignoreCase = true) ||
                text.contains("ReadingContactlessFailure", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.CARD_REMOVED,
                userMessage = "La tarjeta se retiró demasiado rápido.\n\n" +
                    "Por favor, mantenga la tarjeta sobre el lector hasta que aparezca el mensaje de confirmación.",
                chipOnlyOnRetry = false,
            )
            text.contains("Timeout", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.TIMEOUT,
                userMessage = "Tiempo de espera agotado.\n\n" +
                    "Por favor, mantenga la tarjeta cerca del lector durante toda la transacción.",
                chipOnlyOnRetry = false,
            )
            text.contains("Collision", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.COLLISION,
                userMessage = "Se detectaron múltiples tarjetas.\n\nPor favor, presente solo una tarjeta a la vez.",
                chipOnlyOnRetry = false,
            )
            else -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.OTHER,
                userMessage = "Error leyendo tarjeta contactless$codigo.\n\n$motivo" +
                    "Intente nuevamente o inserte la tarjeta en el chip.",
                chipOnlyOnRetry = false,
            )
        }
    }

    /**
     * Un solo verdicto para las DOS puertas del «confirma en tu teléfono»: la clase que tipa el SDK
     * nuevo (`ContactlessSeePhoneFailure`) y el `emvCode` -40 que el SDK viejo mete en el cajón
     * genérico. Compartirlo es lo que garantiza que actualizar el SDK no cambie lo que ve la cajera.
     */
    private fun confirmarEnElTelefono(codigo: String) = ContactlessFailureVerdict(
        outcome = ContactlessOutcome.SEE_PHONE,
        userMessage = "El cliente debe confirmar el pago en su teléfono o reloj (huella, cara o código) " +
            "y volver a acercarlo al lector.$codigo",
        chipOnlyOnRetry = false,
    )
}
