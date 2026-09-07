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
        val codigo = emvCode?.let { " (código $it)" } ?: ""
        return when {
            name.contains("CtlssUseContact", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.USE_CONTACT,
                userMessage = "Esta tarjeta pide chip$codigo.\n\n$INSERTAR\n\n" +
                    "Si la vuelven a acercar, el lector la va a rechazar otra vez.",
                chipOnlyOnRetry = true,
            )
            name.contains("CtlssDenied", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.DENIED_BY_KERNEL,
                userMessage = "El lector no aceptó esta tarjeta por contactless$codigo.\n\n$INSERTAR\n\n" +
                    "No es un rechazo del banco: el cobro todavía no se ha intentado.",
                chipOnlyOnRetry = true,
            )
            name.contains("EmvNoApp", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.NO_APP,
                userMessage = "Esta tarjeta no es compatible con contactless en esta terminal$codigo.\n\n$INSERTAR",
                chipOnlyOnRetry = true,
            )
            name.contains("SeePhone", ignoreCase = true) -> ContactlessFailureVerdict(
                outcome = ContactlessOutcome.SEE_PHONE,
                userMessage = "El cliente debe confirmar el pago en su teléfono (huella, cara o código) " +
                    "y volver a acercarlo al lector.",
                chipOnlyOnRetry = false,
            )
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
                userMessage = "Error leyendo tarjeta contactless$codigo.\n\n" +
                    "Intente nuevamente o inserte la tarjeta en el chip.",
                chipOnlyOnRetry = false,
            )
        }
    }
}
