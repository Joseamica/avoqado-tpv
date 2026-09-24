package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/**
 * El comando que llega por el LATIDO tampoco se mide con el reloj de pared de la terminal.
 *
 * 24-sep-2026: una Nexgo con el reloj 9 min adelantado rechazaba como «expired before
 * execution» comandos vigentes. El servidor ahora manda `expiresInSeconds`; la terminal lo
 * suma a su propio «ahora».
 */
class PendingCommandExpiryTest {

    private fun dto(expiresAt: String?, expiresInSeconds: Long?) = PendingCommandDto(
        commandId = "cmd-1",
        correlationId = "corr-1",
        type = "FACTORY_RESET",
        payload = null,
        priority = "CRITICAL",
        requiresPin = true,
        expiresAt = expiresAt,
        requestedBy = "sa",
        requestedByName = "Super Admin",
        createdAt = "2026-09-24T17:48:33.000Z",
        expiresInSeconds = expiresInSeconds,
    )

    @Test
    fun `con segundos restantes, un expiresAt que el reloj del aparato ya ve pasado NO lo vence`() {
        val antes = Instant.now()

        // expiresAt absoluto en el pasado para este aparato, pero al comando le quedan 29 min
        val comando = dto(expiresAt = "2020-01-01T00:00:00Z", expiresInSeconds = 29 * 60).toTpvCommand()!!

        assertThat(comando.expiresAt).isAtLeast(antes.plusSeconds(29 * 60))
    }

    @Test
    fun `regresion - servidor viejo sin segundos restantes usa expiresAt tal cual`() {
        val comando = dto(expiresAt = "2026-09-24T18:18:33Z", expiresInSeconds = null).toTpvCommand()!!

        assertThat(comando.expiresAt).isEqualTo(Instant.parse("2026-09-24T18:18:33Z"))
    }

    @Test
    fun `regresion - sin caducidad conserva el default de 5 minutos`() {
        val antes = Instant.now()

        val comando = dto(expiresAt = null, expiresInSeconds = null).toTpvCommand()!!

        assertThat(comando.expiresAt).isAtLeast(antes.plusSeconds(300))
        assertThat(comando.expiresAt).isAtMost(Instant.now().plusSeconds(300))
    }
}
