package com.jaac.avoqado_tpv.features.remote_command.domain

import java.time.Duration
import java.time.Instant

/**
 * ¿Hasta cuándo vale un comando remoto, medido con el reloj de ESTE aparato?
 *
 * La terminal compara la fecha límite contra su propio `Instant.now()`
 * (`CommandExecutor.execute`). Si esa fecha límite es la hora ABSOLUTA del servidor, un reloj
 * adelantado da por vencido un comando vigente: el 24-sep-2026 una Nexgo 9.2 min adelantada
 * rechazó en 0.2 s seis FACTORY_RESET a los que el servidor les daba 5 min. Las Nexgo medidas
 * iban todas de 4 a 9 min adelantadas; el reloj de pared de un aparato no es de fiar.
 *
 * Por eso la fecha límite se arma con el «ahora» del aparato + lo que al comando le QUEDA:
 * 1. `expiresInSeconds` del servidor (lo que le queda al momento de enviarlo);
 * 2. si no viene (servidor viejo), la diferencia entre dos horas DEL SERVIDOR —
 *    `expiresAt − timestamp` — que tampoco depende del reloj del aparato;
 * 3. si sólo hay `expiresAt`, se usa absoluto, como antes;
 * 4. sin nada legible, el default del canal.
 *
 * Puro a propósito: se prueba sin Android y sin reloj real.
 */
object CommandExpiry {

    fun fechaLimite(
        expiresInSeconds: Long?,
        expiresAt: String?,
        serverTimestamp: String?,
        ahora: Instant,
        porDefectoSegundos: Long,
    ): Instant {
        if (expiresInSeconds != null) {
            return ahora.plusSeconds(expiresInSeconds.coerceAtLeast(0))
        }

        val expira = leer(expiresAt) ?: return ahora.plusSeconds(porDefectoSegundos)
        val horaServidor = leer(serverTimestamp) ?: return expira

        val restante = Duration.between(horaServidor, expira)
        return if (restante.isNegative) ahora else ahora.plus(restante)
    }

    private fun leer(valor: String?): Instant? =
        valor?.takeIf { it.isNotBlank() && it != "null" }?.let {
            try {
                Instant.parse(it)
            } catch (e: Exception) {
                null
            }
        }
}
