package com.jaac.avoqado_tpv.features.payment.presentation

/**
 * Decides whether a Blumon `SaleIcc`/`SaleCtls` response that came back NON-null is actually an
 * issuer DECLINE disguised as success.
 *
 * Blumon returns issuer declines (e.g. "PAGO NO PERMITIDO EMISOR") as a NON-null `SaleIccResponse`
 * with `authorization` BLANK and `description` carrying the reason (`error` = null). The legacy
 * `response == null` check in `performOnlineAuthorization` misses these, so the flow proceeded to
 * `CompleteEmvTrans` and HUNG ("se queda pasmada") instead of showing the decline.
 *
 * A genuine approval ALWAYS carries an authorization code — verified against production on
 * 2026-06-29: **2850/2850** real Blumon approvals (CHIP + CONTACTLESS) have a non-blank
 * `authorization`. So a blank authorization reliably means the transaction was declined, with no
 * risk of false declines on real approvals.
 *
 * Pure function (no SDK / Android types) so it can be unit-tested without a terminal.
 *
 * @return a user-facing decline message when [authorization] is blank — the caller should route it
 *   through the existing (already-tested) `response == null` error path; or `null` when the
 *   transaction is a genuine approval and the flow should proceed normally.
 */
internal fun blumonDeclineMessage(
    authorization: String?,
    description: String?,
    isRefund: Boolean,
): String? {
    if (!authorization.isNullOrBlank()) return null
    val prefix = if (isRefund) "Reembolso rechazado" else "Pago rechazado"
    val reason = description?.takeIf { it.isNotBlank() } ?: "El banco no autorizó la transacción"
    return "$prefix:\n\n$reason\n\nSolicita otra forma de pago."
}

/**
 * Códigos del emisor que resuelven un cobro por sí solos: el banco contestó y dijo que no, así que no
 * hay dinero en duda y la terminal debe seguir cobrando.
 */
internal val BLUMON_CODIGOS_RECHAZO_EMISOR = setOf("05", "14", "41", "43", "51", "54", "55", "57", "58", "61", "62", "65")

/**
 * Código del emisor de un fallo del SDK de Blumon, o null si el fallo no trae uno.
 *
 * 🔴 El rechazo del banco llega como `Sale*Failure$MomentumFailure`, que NO sobreescribe `toString()` (da
 * `…MomentumFailure@75c4246`): el código vive en su campo `momentumFailure`, un data class cuyo texto es
 * `MomentumDataFailure(httpCode=409, code=57, description=PAGO NO PERMITIDO EMISOR, …)`. Buscarlo sólo en
 * `failure.toString()` —lo que se hacía— nunca lo encontraba, y TODO rechazo del emisor en la PAX quedaba como
 * resultado desconocido y apartaba la terminal (medido en la PAX 2841548417 el 24-sep con una tarjeta real).
 * Se lee del texto de cada campo, igual que la descripción en `PaymentViewModel`: no depende del nombre del
 * campo ni de un getter.
 */
internal fun blumonIssuerCode(failure: Any): String? {
    val textos = buildList {
        runCatching {
            failure.javaClass.declaredFields.forEach { campo ->
                campo.isAccessible = true
                campo.get(failure)?.let { add(it.toString()) }
            }
        }
        add(failure.toString())
    }
    val patrones = listOf(
        Regex("""\bcode=([0-9A-Z]{2})\b"""),                   // data class del SDK: code=57
        Regex(""""code"\s*:\s*"([0-9A-Z]{2})""""),             // cuerpo JSON de Momentum
        Regex("codeResponse[\\\"=:\\s]+([0-9A-Z]{2})"),        // formato viejo
    )
    for (patron in patrones) for (texto in textos) {
        patron.find(texto)?.groupValues?.get(1)?.let { return it }
    }
    return null
}

internal const val BLUMON_SIN_VEREDICTO = "Blumon sin veredicto: "

/**
 * Lo que contestó Blumon cuando el cobro quedó sin veredicto, para guardarlo en la libreta junto al intento: la clase
 * del fallo y la descripción, corta y sólo con letras, números y puntuación simple (nunca el cuerpo JSON ni la tarjeta).
 * 24-sep: la PAX guardaba sólo «MomentumFailure» y ninguna pantalla podía decir que Blumon había contestado «NO AUTORIZADO».
 */
internal fun blumonMotivoSinVeredicto(failure: Any, descripcion: String?): String {
    val limpia = descripcion?.filter { it.isLetterOrDigit() || it in " .,:-()" }?.trim()?.take(60)
    return BLUMON_SIN_VEREDICTO + failure.javaClass.simpleName + (limpia?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

/** La descripción que [blumonMotivoSinVeredicto] guardó en `last_error`, o null si esa fila no trae una. */
internal fun blumonRespuestaGuardada(lastError: String?): String? =
    lastError?.takeIf { it.startsWith(BLUMON_SIN_VEREDICTO) }?.substringAfter(" · ", "")?.takeIf { it.isNotBlank() }

/** ¿El banco contestó con un rechazo explícito? Sólo entonces el cobro queda resuelto sin dinero en duda. */
internal fun blumonRechazoDefinitivo(failure: Any, isContactless: Boolean): Boolean {
    if (failure.javaClass.simpleName == "GenericFailure") return false
    val code = blumonIssuerCode(failure) ?: return false
    return (isContactless && code == "1A") || code in BLUMON_CODIGOS_RECHAZO_EMISOR
}

/**
 * ¿Blumon RECHAZÓ el reembolso (`ValidateCancel` / `CancelIcc`) con un «no» explícito? Entonces nada se devolvió y la
 * terminal debe seguir cobrando. Blumon contesta 4xx cuando revisa la petición y la rechaza — medido el 25-sep en la PAX
 * de pruebas: otra tarjeta ⇒ `MomentumDataFailureCancel(httpCode=400, code=TX_010, description=TARJETA INVALIDA, …)`,
 * y el intento se quedaba AUTORIZANDO apartando la terminal. Un 5xx, un 408, la red o un fallo del SDK sin respuesta NO
 * dicen si la cancelación se hizo: siguen en duda. Lee el texto de los campos, como [blumonIssuerCode], porque el SDK de
 * producción y el de pruebas no tienen la misma forma (7 y 11 campos).
 */
internal fun blumonReembolsoRechazado(failure: Any): Boolean {
    if (failure.javaClass.simpleName != "MomentumFailure") return false
    val textos = buildList {
        runCatching {
            failure.javaClass.declaredFields.forEach { campo ->
                campo.isAccessible = true
                campo.get(failure)?.let { add(it.toString()) }
            }
        }
        add(failure.toString())
    }
    val http = textos.firstNotNullOfOrNull { Regex("""\bhttpCode=(\d{3})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    return http != null && http in 400..499 && http != 408
}
