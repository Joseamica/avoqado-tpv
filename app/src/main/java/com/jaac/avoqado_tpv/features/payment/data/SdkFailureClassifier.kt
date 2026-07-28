package com.jaac.avoqado_tpv.features.payment.data

/**
 * Distingue un cobro que falló **porque el SDK quedó en mal estado** de uno que falló por
 * una razón normal del negocio (el banco declinó, el cliente retiró la tarjeta, NIP malo).
 *
 * ## Por qué importa la distinción
 *
 * La recuperación automática reinicializa el SDK. Eso NO es gratis: el force-reinit
 * incondicional está deshabilitado en [InitializationManager] porque *"causes GenericFailure
 * due to rate limiting"*. Si reinicializáramos ante cada declinación —que son normales y
 * frecuentes— nos limitarían y romperíamos los cobros en vez de arreglarlos.
 *
 * Pero esperar a 3 fallos seguidos tampoco sirve: son 3 clientes parados en el mostrador.
 * La salida es clasificar. Un error de estado del SDK dispara la recuperación **al primero**;
 * una declinación normal no dispara nada.
 *
 * ## Por qué NO se puede clasificar por el tipo de failure
 *
 * Lección del incidente de Doña Simona (2026-07-26): `MomentumFailure` aparece en los DOS
 * casos. A las 15:02 llegó con `FONDOS INSUFICIENTES` (declinación normal del banco), y es la
 * misma clase que el KDoc de [SdkTokenRefreshScheduler] documenta para el token vencido
 * (*"SaleIccFailure$MomentumFailure with a body containing invalid_token"*). El nombre de la
 * clase no distingue nada — **hay que mirar el cuerpo**.
 *
 * Por eso esto recibe texto ya extraído, no el objeto del SDK. Ojo: `failure.toString()`
 * produce `SaleCtlsFailure$MomentumFailure@75c4246`, que no sirve; hay que pasar la
 * descripción real que `performOnlineAuthorization` ya extrae por reflexión.
 *
 * Función **pura**: sin Android, sin tipos del SDK, testeable sin terminal — mismo patrón que
 * `blumonDeclineMessage`.
 */
object SdkFailureClassifier {

    /**
     * Firmas que significan "el SDK no está en condiciones de cobrar". Todas están
     * documentadas en el propio código:
     *
     * - `invalid_token` — token OAuth vencido (TTL 24h del server, sin refresh automático en
     *   el SDK). Confirmado por Edgardo/Blumon el 2026-05-12.
     * - `NA_002` — el SDK responde esto cuando `InitializerUseCase` se saltó y su estado de
     *   auth/DUKPT no sobrevivió. Citado en `InitializationManager`.
     * - `NO AUTORIZADO` — posId viejo en la tabla Init del SDK tras un cambio de merchant.
     *
     * Deliberadamente NO están aquí: `CtlssDenied`, `WithdrawnCard`, `CancelOperation`,
     * `FONDOS INSUFICIENTES`, NIP incorrecto. Todas ocurren con una terminal perfectamente
     * sana — el 26-jul hubo tres en un día bueno.
     */
    private val SDK_STATE_SIGNATURES = listOf(
        "invalid_token",
        "na_002",
        "no autorizado",
    )

    /**
     * @param failureBody cuerpo/descripción REAL del fallo, no `failure.toString()`.
     * @return `true` sólo si la firma indica estado inservible del SDK. Ante la duda, `false`
     *   — no recuperar de más es preferible a arriesgar el rate limit de Blumon.
     */
    fun isSdkStateFailure(failureBody: String?): Boolean {
        if (failureBody.isNullOrBlank()) return false
        val normalized = failureBody.lowercase()
        return SDK_STATE_SIGNATURES.any { normalized.contains(it) }
    }
}
