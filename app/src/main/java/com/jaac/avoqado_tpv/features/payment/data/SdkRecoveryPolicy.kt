package com.jaac.avoqado_tpv.features.payment.data

/**
 * Decide **si** conviene forzar una reinicialización del SDK de Blumon para recuperar
 * una terminal que dejó de cobrar, sin llegar a reiniciar la app.
 *
 * ## Por qué existe
 *
 * `_isInitialized` de [InitializationManager] es un booleano **en memoria del proceso**.
 * Mientras esté en `true`, `ensureInitialized()` toma el fast path y devuelve éxito sin
 * volver a inicializar, sin importar cuánto tiempo pasó ni si el SDK quedó en mal estado.
 * La única forma que existe hoy de limpiarlo es matar el proceso — por eso "reiniciar la
 * terminal" es el único remedio conocido en campo.
 *
 * Incidente que motivó esto (Doña Simona, 2026-07-26): la app arrancó 11:30, cobró bien
 * 11:51, y a las 12:14 dejó de cobrar. A las 12:48 la app volvió a Home y **volvió a llamar
 * al init** — que cayó en el fast path y no hizo nada. Siguió sin cobrar hasta que la
 * apagaron a las 13:31. Los logs del server confirman que la red estaba perfecta durante
 * todo el episodio (200 OK en 105 ms a las 12:47), así que lo que falló fue estado del SDK.
 *
 * ## El riesgo que esta clase administra
 *
 * Forzar el init **no es gratis**. El propio [InitializationManager] tiene deshabilitado el
 * force-reinit incondicional con este comentario (línea ~275):
 *
 * > `⚠️ DISABLE FORCE RE-INIT: It causes GenericFailure due to rate limiting`
 *
 * O sea: reinicializar demasiado seguido hace que Blumon nos limite y **rompe los cobros**,
 * que es justo lo que queremos evitar. Por eso la recuperación NUNCA es incondicional:
 * exige varios fallos consecutivos y respeta un enfriamiento entre intentos.
 *
 * ## Diseño
 *
 * Lógica **pura**: sin Android, sin corrutinas, con el reloj por parámetro. Toda la
 * corrección vive aquí y se prueba sin dispositivo. Quien la usa se encarga de los efectos
 * (llamar a `forceReinitialize`, loguear, etc.).
 */
class SdkRecoveryPolicy(
    private val minConsecutiveFailures: Int = MIN_CONSECUTIVE_FAILURES,
    private val cooldownMs: Long = COOLDOWN_MS,
) {

    companion object {
        /**
         * Cuántos fallos **de estado del SDK** hacen falta para recuperar. Es **1**: al primero.
         *
         * Quien llama DEBE contar únicamente los que [SdkFailureClassifier.isSdkStateFailure]
         * marca como estado del SDK. Las declinaciones normales del banco (fondos, tarjeta
         * retirada, NIP) NO se cuentan y por lo tanto nunca disparan nada.
         *
         * Antes esto era 3, buscando protegerse del rate limit por la vía del volumen. Estaba
         * mal: tres cobros fallidos son tres clientes parados en el mostrador, y el objetivo
         * es que el cliente no tenga que hacer nada. Con el clasificador, la protección contra
         * el rate limit la da el enfriamiento ([COOLDOWN_MS]), no el umbral — así se recupera
         * al primer síntoma real sin arriesgar el límite de Blumon.
         */
        const val MIN_CONSECUTIVE_FAILURES = 1

        /**
         * Mínimo entre dos recuperaciones. Conservador a propósito: el costo de esperar
         * de más es que la cajera reintente una vez; el costo de esperar de menos es que
         * Blumon nos limite y la terminal deje de cobrar de verdad.
         */
        const val COOLDOWN_MS = 5L * 60L * 1000L
    }

    sealed class Decision {
        /** Conviene y se puede recuperar. */
        object Recover : Decision()

        /** Todavía no hay evidencia suficiente de que el SDK sea el problema. */
        data class NotEnoughFailures(val failures: Int, val required: Int) : Decision()

        /** Se recuperó hace poco; forzar otra vez arriesga el rate limit de Blumon. */
        data class Cooldown(val remainingMs: Long) : Decision()

        /** Hay un cobro / cambio de merchant / init en curso. Nunca se toca el SDK ahí. */
        object CriticalOperationInProgress : Decision()
    }

    /**
     * @param now reloj inyectado — nunca `System.currentTimeMillis()` acá adentro.
     * @param lastRecoveryAt cuándo se recuperó por última vez, o `null` si nunca.
     * @param consecutiveFailures cobros seguidos que fallaron sin llegar a autorizarse.
     * @param criticalOperationInProgress resultado de
     *   `CriticalNetworkOperationManager.isAnyCriticalOperationInProgress()`.
     */
    fun evaluate(
        now: Long,
        lastRecoveryAt: Long?,
        consecutiveFailures: Int,
        criticalOperationInProgress: Boolean,
    ): Decision {
        // El guardia de operación crítica va PRIMERO y es absoluto: invalidar el flag a
        // media autorización EMV puede dejar la tarjeta cobrada sin registro, que es peor
        // que no cobrar. Ver el contrato de seguridad de `invalidateForRefresh`.
        if (criticalOperationInProgress) return Decision.CriticalOperationInProgress

        if (consecutiveFailures < minConsecutiveFailures) {
            return Decision.NotEnoughFailures(consecutiveFailures, minConsecutiveFailures)
        }

        if (lastRecoveryAt != null) {
            val elapsed = now - lastRecoveryAt
            // `elapsed` negativo = el reloj del equipo se movió hacia atrás. Tratarlo como
            // "en enfriamiento" es lo seguro: ante un reloj que no entendemos, preferimos
            // no reinicializar antes que arriesgar el rate limit.
            if (elapsed < cooldownMs) {
                return Decision.Cooldown(remainingMs = (cooldownMs - elapsed).coerceAtLeast(0L))
            }
        }

        return Decision.Recover
    }
}
