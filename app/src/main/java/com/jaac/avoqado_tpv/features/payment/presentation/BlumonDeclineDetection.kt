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
internal val BLUMON_CODIGOS_RECHAZO_EMISOR = setOf("05", "14", "41", "43", "51", "54", "55", "56", "57", "58", "61", "62", "65")

/**
 * Códigos con los que Momentum contesta, pero sin decir que el banco rechazó, así que el cobro sigue en duda (ISO 8583):
 * aprobados que no deberían venir en un fallo (00, 08, 10, 11, 16) y «no pude saberlo»: error (06), en proceso (09),
 * reintenta (19), falla (22), respuesta tardía (68), corte (90), emisor o ruta no disponibles (91, 92), falla del sistema (96).
 * El `2000` no es ISO: el SDK de pruebas lo convierte en `ConnectionProcessorFailure`, y el de producción no.
 */
private val BLUMON_CODIGOS_SIN_RECHAZO = setOf("00", "08", "10", "11", "16", "06", "09", "19", "22", "68", "90", "91", "92", "96", "2000")

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
    val textos = textosDelFallo(failure)
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

/** El texto de cada campo del fallo: el SDK de producción y el de pruebas no tienen la misma forma. */
private fun textosDelFallo(failure: Any): List<String> = buildList {
    runCatching {
        failure.javaClass.declaredFields.forEach { campo ->
            campo.isAccessible = true
            campo.get(failure)?.let { add(it.toString()) }
        }
    }
    add(failure.toString())
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

/**
 * ¿Blumon contestó que no? Entonces es un rechazo normal: se ve el motivo y se puede reintentar luego luego.
 *
 * Founder, 3-nov: «ningún rechazo de Blumon directo debería de trabar la terminal». Antes sólo contaba una lista de códigos,
 * y una tarjeta que el banco no reconoce (`httpCode=200, code=56, TARJETA INVALIDA`, PAX 2841548417) apartaba la venta en
 * «Estamos confirmando el cobro». La duda queda para lo que de verdad no dice: sin respuesta de Momentum (red, tiempo
 * agotado, `GenericFailure`), 5xx, 408, una respuesta sin código ni motivo, y [BLUMON_CODIGOS_SIN_RECHAZO].
 */
internal fun blumonRechazoDefinitivo(failure: Any, isContactless: Boolean): Boolean {
    if (failure.javaClass.simpleName == "GenericFailure") return false
    val code = blumonIssuerCode(failure)
    if (code == "1A") return isContactless
    if (code in BLUMON_CODIGOS_RECHAZO_EMISOR) return true
    if (failure.javaClass.simpleName != "MomentumFailure") return false
    val textos = textosDelFallo(failure)
    fun campo(nombre: String) = textos.firstNotNullOfOrNull { Regex("""\b$nombre=([^,)]*)""").find(it)?.groupValues?.get(1)?.trim() }
        ?.takeIf { it.isNotEmpty() && it != "null" }
    val crudo = campo("code")
    val motivo = campo("description")
    if (crudo?.uppercase() in BLUMON_CODIGOS_SIN_RECHAZO || crudo.esCodigoTecnico() || motivo.suenaAprobado()) return false
    val http = campo("httpCode")?.toIntOrNull() ?: return false
    val contesto = http in 200..299 || (http in 400..499 && http != 408)
    return contesto && (crudo != null || motivo != null)
}

/** Cuatro dígitos no son un código del emisor: `2000` es falla de conexión, y el catálogo del host no está en el SDK. */
private fun String?.esCodigoTecnico() = this?.matches(Regex("""\d{4}""")) == true

/** «APROBADA» sí; «NO APROBADA» o «DESAPROBADA» no. */
private fun String?.suenaAprobado() = this != null && Regex("""(?<!NO )\bAPROB""", RegexOption.IGNORE_CASE).containsMatchIn(this)

/**
 * ¿Esta respuesta con la autorización vacía es un «no»? Blumon también entrega rechazos como respuesta normal (ver
 * [blumonDeclineMessage]), con el motivo en `description` y el código en EMV 8A, que puede venir en hexadecimal («3536» = 56).
 * Una respuesta sin código ni motivo sigue en duda: está incompleta y no dice nada.
 */
internal fun blumonRespuestaEsRechazo(emvResponseCode: String?, description: String?): Boolean {
    val crudo = emvResponseCode?.trim()?.uppercase().orEmpty()
    val code = if (crudo.length == 4) {
        runCatching { crudo.chunked(2).map { it.toInt(16).toChar() }.joinToString("") }.getOrDefault(crudo)
    } else {
        crudo
    }
    if (code in BLUMON_CODIGOS_RECHAZO_EMISOR) return true
    if (code in BLUMON_CODIGOS_SIN_RECHAZO || description.suenaAprobado()) return false
    return code.isNotEmpty() || !description.isNullOrBlank()
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
    val textos = textosDelFallo(failure)
    val http = textos.firstNotNullOfOrNull { Regex("""\bhttpCode=(\d{3})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    return http != null && http in 400..499 && http != 408
}
