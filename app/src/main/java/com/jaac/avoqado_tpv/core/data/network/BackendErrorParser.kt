package com.jaac.avoqado_tpv.core.data.network

import com.google.gson.JsonParser

/**
 * Devuelve el `code` del cuerpo de error del servidor, o null si no hay uno CONFIABLE.
 *
 * Null cuando el cuerpo está vacío, no es JSON, no es un objeto, o no trae `code`: quien
 * decide dinero con esto (el `PaymentSyncWorker`) tiene que poder distinguir «el servidor
 * dijo ORDER_NOT_FOUND» de «no sé por qué fue el 404». Nunca se adivina leyendo el mensaje.
 */
fun parseBackendErrorCode(rawBody: String?): String? {
    val trimmedBody = rawBody?.trim().orEmpty()
    if (trimmedBody.isBlank()) return null

    return runCatching {
        val json = JsonParser.parseString(trimmedBody)
        if (!json.isJsonObject) return@runCatching null
        json.asJsonObject.get("code")?.takeUnless { it.isJsonNull }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

fun parseBackendErrorMessage(rawBody: String?, fallback: String): String {
    val trimmedBody = rawBody?.trim().orEmpty()
    if (trimmedBody.isBlank()) {
        return fallback.ifBlank { "Sin detalle del servidor" }
    }

    val parsedMessage = runCatching {
        val json = JsonParser.parseString(trimmedBody)
        if (!json.isJsonObject) return@runCatching null

        val obj = json.asJsonObject
        obj.get("message")?.takeUnless { it.isJsonNull }?.asString
            ?: obj.get("error")?.takeUnless { it.isJsonNull }?.asString
            ?: obj.get("code")?.takeUnless { it.isJsonNull }?.asString
    }.getOrNull()

    return parsedMessage?.takeIf { it.isNotBlank() } ?: trimmedBody.take(300)
}
