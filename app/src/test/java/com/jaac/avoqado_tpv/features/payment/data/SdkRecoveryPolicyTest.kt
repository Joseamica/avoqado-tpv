package com.jaac.avoqado_tpv.features.payment.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests de [SdkRecoveryPolicy].
 *
 * Dos secciones, según `.claude/rules/testing-and-git.md`:
 * 1. Comportamiento nuevo (umbral, enfriamiento, guardia de operación crítica).
 * 2. Regresión — lo que NO debe pasar: recuperar durante un cobro y recuperar en ráfaga
 *    (que es lo que dispara el rate limit de Blumon y rompería los cobros de verdad).
 *
 * Fechas siempre relativas, nunca hardcodeadas.
 */
class SdkRecoveryPolicyTest {

    private val policy = SdkRecoveryPolicy()
    private val now = 1_000_000_000L
    private val cooldown = SdkRecoveryPolicy.COOLDOWN_MS
    private val threshold = SdkRecoveryPolicy.MIN_CONSECUTIVE_FAILURES

    // ─────────────────────────────────────────────────────────
    // 1. COMPORTAMIENTO NUEVO
    // ─────────────────────────────────────────────────────────

    @Test
    fun `recupera cuando hay suficientes fallos, sin recuperacion previa y nada critico`() {
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = null,
            consecutiveFailures = threshold,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isEqualTo(SdkRecoveryPolicy.Decision.Recover)
    }

    @Test
    fun `no recupera con menos fallos que el umbral`() {
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = null,
            consecutiveFailures = threshold - 1,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isInstanceOf(SdkRecoveryPolicy.Decision.NotEnoughFailures::class.java)
        val d = decision as SdkRecoveryPolicy.Decision.NotEnoughFailures
        assertThat(d.failures).isEqualTo(threshold - 1)
        assertThat(d.required).isEqualTo(threshold)
    }

    @Test
    fun `recupera de nuevo una vez cumplido el enfriamiento`() {
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = now - cooldown,
            consecutiveFailures = threshold,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isEqualTo(SdkRecoveryPolicy.Decision.Recover)
    }

    @Test
    fun `reporta el tiempo restante de enfriamiento`() {
        val transcurrido = cooldown / 4
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = now - transcurrido,
            consecutiveFailures = threshold + 5,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isInstanceOf(SdkRecoveryPolicy.Decision.Cooldown::class.java)
        assertThat((decision as SdkRecoveryPolicy.Decision.Cooldown).remainingMs)
            .isEqualTo(cooldown - transcurrido)
    }

    // ─────────────────────────────────────────────────────────
    // 2. REGRESIÓN — lo que NUNCA debe pasar
    // ─────────────────────────────────────────────────────────

    @Test
    fun `REGRESION nunca recupera durante una operacion critica, por muchos fallos que haya`() {
        // Invalidar el flag a media autorización EMV puede dejar la tarjeta cobrada sin
        // registro. El guardia es absoluto y gana sobre cualquier otra condición.
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = null,
            consecutiveFailures = threshold * 100,
            criticalOperationInProgress = true,
        )

        assertThat(decision).isEqualTo(SdkRecoveryPolicy.Decision.CriticalOperationInProgress)
    }

    @Test
    fun `REGRESION no recupera dos veces seguidas — protege del rate limit de Blumon`() {
        // El force-reinit incondicional está deshabilitado en InitializationManager porque
        // "causes GenericFailure due to rate limiting". Recuperar en ráfaga rompería los
        // cobros en vez de arreglarlos.
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = now - 1L,
            consecutiveFailures = threshold,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isInstanceOf(SdkRecoveryPolicy.Decision.Cooldown::class.java)
    }

    @Test
    fun `REGRESION un reloj que retrocede no habilita una recuperacion extra`() {
        // Si el equipo mueve la hora hacia atrás, `now - lastRecoveryAt` sale negativo.
        // Ante un reloj que no entendemos, lo seguro es NO reinicializar.
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = now + cooldown * 10,
            consecutiveFailures = threshold,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isInstanceOf(SdkRecoveryPolicy.Decision.Cooldown::class.java)
        assertThat((decision as SdkRecoveryPolicy.Decision.Cooldown).remainingMs).isAtLeast(0L)
    }

    @Test
    fun `REGRESION cero fallos nunca dispara recuperacion`() {
        // Una terminal sana jamás debe reinicializar sola.
        val decision = policy.evaluate(
            now = now,
            lastRecoveryAt = null,
            consecutiveFailures = 0,
            criticalOperationInProgress = false,
        )

        assertThat(decision).isInstanceOf(SdkRecoveryPolicy.Decision.NotEnoughFailures::class.java)
    }
}
