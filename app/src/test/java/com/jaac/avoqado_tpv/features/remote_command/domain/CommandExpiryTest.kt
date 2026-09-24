package com.jaac.avoqado_tpv.features.remote_command.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/**
 * La caducidad de un comando remoto NO puede depender del reloj de pared de la terminal.
 *
 * 24-sep-2026, Nexgo `AVQD-N860W173570`: seis «Borrar almacenamiento y caché» (FACTORY_RESET)
 * rechazados en 0.2 s con «Command expired before execution». El comando traía
 * `expiresAt` = hora del servidor + 5 min, y el reloj de la N86 iba 9.2 min adelantado: al
 * compararlo contra SU «ahora», ya había vencido antes de llegar. Todas las Nexgo medidas iban
 * de 4 a 9 min adelantadas.
 *
 * La regla: la fecha límite se arma con el «ahora» del propio aparato + lo que al comando le
 * QUEDA según el servidor. Así da igual cuánto vaya desfasado el reloj.
 */
class CommandExpiryTest {

    // El reloj de la terminal, 9 min adelantado respecto al servidor
    private val ahoraTerminal = Instant.parse("2026-09-24T17:57:33Z")
    private val horaServidor = "2026-09-24T17:48:33.000Z"
    private val expiraServidor = "2026-09-24T18:18:33.000Z" // +30 min del servidor

    @Test
    fun `con segundos restantes, la fecha limite es el ahora del aparato mas esos segundos`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = 1800,
            expiresAt = expiraServidor,
            serverTimestamp = horaServidor,
            ahora = ahoraTerminal,
            porDefectoSegundos = 3600,
        )

        assertThat(limite).isEqualTo(ahoraTerminal.plusSeconds(1800))
    }

    @Test
    fun `el caso de la N86 - reloj adelantado mas que la vida del comando - ya NO lo da por vencido`() {
        // Servidor 17:48:33, vence 17:53:33 (los 5 min de antes). La terminal cree que son 17:57:33.
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = 300,
            expiresAt = "2026-09-24T17:53:33.000Z",
            serverTimestamp = horaServidor,
            ahora = ahoraTerminal,
            porDefectoSegundos = 3600,
        )

        assertThat(ahoraTerminal.isAfter(limite)).isFalse()
    }

    @Test
    fun `servidor viejo sin segundos restantes - usa la diferencia entre sus DOS horas, no el reloj del aparato`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = null,
            expiresAt = "2026-09-24T17:53:33.000Z",
            serverTimestamp = horaServidor,
            ahora = ahoraTerminal,
            porDefectoSegundos = 3600,
        )

        assertThat(limite).isEqualTo(ahoraTerminal.plusSeconds(300))
    }

    @Test
    fun `segundos restantes negativos cuentan como cero - un comando vencido sigue vencido`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = -30,
            expiresAt = null,
            serverTimestamp = null,
            ahora = ahoraTerminal,
            porDefectoSegundos = 3600,
        )

        assertThat(limite).isEqualTo(ahoraTerminal)
    }

    @Test
    fun `un comando que el servidor ya da por vencido sale vencido aunque llegue con hora del servidor`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = null,
            expiresAt = "2026-09-24T17:40:00.000Z", // antes de la hora del servidor
            serverTimestamp = horaServidor,
            ahora = ahoraTerminal,
            porDefectoSegundos = 3600,
        )

        assertThat(limite).isEqualTo(ahoraTerminal)
    }

    // ── Regresión: sin datos del servidor se conserva el comportamiento de antes ──

    @Test
    fun `solo con expiresAt absoluto (latido de un servidor viejo) se usa tal cual`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = null,
            expiresAt = expiraServidor,
            serverTimestamp = null,
            ahora = ahoraTerminal,
            porDefectoSegundos = 300,
        )

        assertThat(limite).isEqualTo(Instant.parse(expiraServidor))
    }

    @Test
    fun `sin caducidad usa el default del canal`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = null,
            expiresAt = null,
            serverTimestamp = null,
            ahora = ahoraTerminal,
            porDefectoSegundos = 300,
        )

        assertThat(limite).isEqualTo(ahoraTerminal.plusSeconds(300))
    }

    @Test
    fun `una caducidad ilegible usa el default del canal`() {
        val limite = CommandExpiry.fechaLimite(
            expiresInSeconds = null,
            expiresAt = "",
            serverTimestamp = horaServidor,
            ahora = ahoraTerminal,
            porDefectoSegundos = 3600,
        )

        assertThat(limite).isEqualTo(ahoraTerminal.plusSeconds(3600))
    }
}
