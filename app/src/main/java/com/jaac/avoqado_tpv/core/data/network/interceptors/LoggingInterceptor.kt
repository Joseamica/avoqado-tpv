package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.BuildConfig
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber

/**
 * Logging Interceptor Factory
 *
 * Creates OkHttp logging interceptor that logs to Timber
 * Only enabled in DEBUG builds for security
 *
 * Logs:
 * - Request method, URL, headers
 * - Request body (JSON)
 * - Response status code
 * - Response body (JSON)
 * - Request duration
 */
object LoggingInterceptor {

    /**
     * Censura el VALOR de las claves JSON sensibles (`supervisorPin`, `pin`, `password`, `newPin`, `currentPin` — exactas,
     * sensibles a mayúsculas, con o sin espacios alrededor de `:`, valor cadena o número) en una línea de log ANTES de que
     * llegue a Timber: el PIN nunca se persiste ni se loguea, tampoco en DEBUG (Task 7 · fix 2, P1-1: el cuerpo de
     * `resolveNoInstrument` quedaba en logcat con `Level.BODY`). Pura y por línea; una línea que no es JSON pasa intacta, y una
     * clave que sólo CONTIENE «pin» (`pinned`, `spinCount`) no se toca porque el patrón exige las comillas de la clave completa.
     */
    fun censurarCuerpoSensible(message: String): String =
        if (!message.contains('"')) {
            message
        } else {
            val sinClaves = CUERPO_SENSIBLE.replace(message) { m -> "\"${m.groupValues[1]}\":\"***\"" }
            CODIGO_DE_TARJETA.replace(sinClaves) { m -> "\"${m.groupValues[1]}\":\"***\"" }
        }

    private val CUERPO_SENSIBLE = Regex(
        // "clave"  :  "cadena con \" escapadas"  |  -12.5
        // Tienda híbrida parte 2 (Codex r1 #2): `pageToken` y `proofToken` son la prueba de posesión de una tarjeta de regalo.
        // D1: `autorizacionDelDescuento` es el token del PIN del gerente que viaja en el cuerpo de `with-items`.
        "\"(supervisorPin|pin|password|newPin|currentPin|commandToken|token|accessToken|refreshToken|pageToken|proofToken|autorizacionDelDescuento)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?)",
    )

    /**
     * El código de una tarjeta de regalo (`XXXX-XXXX-XXXX-XXXX-X`, con o sin guiones) en `"code"`: con él se gasta su saldo. Sólo con esa
     * forma; los `"code"` de los errores (`GIFT_CARD_NOT_FOUND`, con guion bajo) se siguen viendo.
     */
    private val CODIGO_DE_TARJETA = Regex("\"(code)\"\\s*:\\s*\"[0-9A-Za-z]{4}(?:-?[0-9A-Za-z]{4}){3}-?[0-9A-Za-z]\"")

    /**
     * Create logging interceptor
     *
     * @return HttpLoggingInterceptor configured for Timber
     */
    fun create(): HttpLoggingInterceptor {
        val logger = HttpLoggingInterceptor.Logger { message ->
            Timber.tag("OkHttp").d(censurarCuerpoSensible(message))
        }

        return HttpLoggingInterceptor(logger).apply {
            redactHeader("Authorization")
            redactHeader("X-TPV-Command-Token")
            redactHeader("X-Permission-Override")
            level = if (BuildConfig.DEBUG) {
                // Log full request/response in DEBUG
                HttpLoggingInterceptor.Level.BODY
            } else {
                // No logging in RELEASE for security
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }
}
