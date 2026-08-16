package com.jaac.avoqado_tpv.features.payment.domain.sync

import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import java.io.IOException

/** Resultado de clasificar un fallo al registrar un pago en el backend. */
sealed class SyncOutcome {
    /**
     * El backend confirma que YA tiene el pago. La fila se cierra como SUCCESS.
     *
     * 🔴 Un 409 NO llega aquí (ver [classifySyncFailure]): el reintento idempotente real
     * responde **200** con el pago existente, nunca 409. Este caso queda para cuando el
     * backend afirme explícitamente el registro.
     */
    data object Synced : SyncOutcome()

    /** Fallo transitorio (red, 5xx, desconocido, o un 4xx no listado en [PERMANENT_HTTP_CODES]). Se reintenta. */
    data object Retryable : SyncOutcome()

    /** Fallo de negocio permanente — solo [PERMANENT_HTTP_CODES]. No se arregla solo. */
    data class Permanent(val reason: String) : SyncOutcome()
}

/**
 * Códigos 4xx que describen un problema PERMANENTE del PAGO mismo — nunca de la
 * SESIÓN ni de PERMISOS, que se autocorrigen (re-login, un admin arregla el venue) y
 * jamás deberían matar un pago para siempre. Fuera de esta lista, todo 4xx cae a
 * [SyncOutcome.Retryable] — "ante la duda, Retryable" (ver KDoc de [classifySyncFailure]).
 *
 * - 400 Bad Request — el payload que arma el propio TPV está mal formado; reintentar
 *   el mismo payload nunca cambia el resultado.
 * - 404 Not Found — el recurso (orden, venue) no existe; reconectar no lo crea.
 * - 422 Unprocessable Entity — el backend entendió la petición pero la rechaza por una
 *   regla de negocio (p.ej. orden ya cerrada); no se autocura solo.
 *
 * **401/403 EXCLUIDOS a propósito (fix round 1 — hallazgo Crítico de revisión):** casi
 * siempre son la SESIÓN, no el pago. `TokenAuthenticator.kt` (`:252-266`) documenta que
 * un refresh de token que se atora tras Doze devuelve el 401 ORIGINAL sin reintentar —
 * "el token puede seguir siendo válido, la red solo se colgó" — y a propósito NO llama
 * `notifySessionExpired()` ahí. Marcarlos Permanent volteaba ese 401 transitorio de
 * sesión en un pago YA COBRADO muerto para siempre: nada en el código limpia
 * `permanent` de vuelta a 0 salvo el tap manual de `DeviceHealthViewModel`, así que la
 * PRIMERA vez que un refresh se atora tras una reconexión, el pago queda huérfano
 * (tarjeta cobrada, sin registro en backend, sin badge que avise, sin reintento
 * automático) hasta que alguien lo note y toque "reintentar" a mano.
 */
private val PERMANENT_HTTP_CODES = setOf(400, 404, 422)

/**
 * Clasifica un fallo de registro **por código**, nunca por el texto del mensaje.
 *
 * Regla de seguridad: ante la duda → [SyncOutcome.Retryable]. Perder un reintento
 * es barato; marcar como sincronizada una venta que no lo está es irreversible
 * (deja de reintentar y la fila se borra a los 7 días). Desde el fix round 1, esa
 * misma regla aplica a [SyncOutcome.Permanent]: solo entran los códigos de
 * [PERMANENT_HTTP_CODES] — todo lo demás, incluido 401/403, es Retryable.
 */
fun classifySyncFailure(error: Throwable?): SyncOutcome = when {
    // 🔴 Un 409 NO afirma que el cobro haya quedado registrado — por eso NO es Synced.
    //
    // Se asumía que 409 == "duplicado, ya lo tengo" y la fila se cerraba como SUCCESS,
    // o sea que dejaba de reintentarse y se borraba a los 7 días. Verificado en el
    // server (`payment.tpv.service.ts`, rama "Idempotent retry detected by
    // idempotencyKey"): el reintento idempotente devuelve **200 con el pago existente**,
    // y hasta la red de seguridad de P2002 resuelve al ganador y lo devuelve — NUNCA 409.
    // O sea que esta rama se apoyaba en una premisa que el backend no cumple.
    //
    // Cae a Retryable, que es lo que este mismo archivo manda: "ante la duda → Retryable;
    // marcar como sincronizada una venta que no lo está es irreversible". Y no añade
    // riesgo nuevo: 5xx, 408/429 y los fallos de red ya se reintentan con el mismo
    // `idempotencyKey` (nullable) — el 409 era el único outlier que se cerraba solo.
    //
    // Mismo bug, mismo fix, en las colas de avoqado-android (73b7f40) y avoqado-ios
    // (d336599). Lección compartida: nunca concluir un desenlace desde una respuesta
    // que no lo afirma.

    // 408 y 429 son 4xx que significan "reintenta", no "no". Square los clasifica en
    // su propia categoria (RATE_LIMIT_ERROR) justo por esto, y el conjunto transitorio
    // estandar de una capa de idempotencia es 408 + 429 + 5xx. Nuestra propia rafaga de
    // pagos encolados al volver la red es la que provoca el 429.
    error is BackendHttpException && (error.statusCode == 429 || error.statusCode == 408) ->
        SyncOutcome.Retryable

    error is BackendHttpException && error.statusCode in PERMANENT_HTTP_CODES ->
        SyncOutcome.Permanent("HTTP ${error.statusCode}: ${error.message}")

    error is BackendHttpException -> SyncOutcome.Retryable // 401/403/405... y 5xx y cualquier otro
    error is IOException -> SyncOutcome.Retryable          // red caída, timeout, DNS

    else -> SyncOutcome.Retryable                          // desconocido y null
}
