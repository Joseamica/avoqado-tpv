package com.jaac.avoqado_tpv.features.payment.data

import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6 (plan `2026-08-04-event-loop-no-bloqueante-reportes`) — local telemetry of
 * authorization attempts. Exercises ONLY the pure in-memory batching logic of
 * [AuthAttemptTelemetryStore] (no Room, no Android framework) — see the class KDoc for
 * why the Room-backed durability layer is a separate, optional concern from this core
 * batch/cap/privacy behavior.
 */
class AuthAttemptTelemetryTest {

    private fun buildTelemetryStore(): AuthAttemptTelemetryStore = AuthAttemptTelemetryStore()

    @Test
    fun `un intento fallido de red se registra con su codigo y duracion`() {
        val store = buildTelemetryStore()
        store.record(code = "N400", durationMs = 12_400L, rail = "BLUMON")
        val batch = store.drainBatch()
        assertEquals(1, batch.size)
        assertEquals("N400", batch[0].code)
        assertEquals(12_400L, batch[0].durationMs)
    }

    @Test
    fun `no se reporta mientras hay un cobro activo`() {
        // Invariante del spec: cero bytes nuevos en la ventana del cobro.
        val store = buildTelemetryStore()
        store.record(code = "N402", durationMs = 30_000L, rail = "ANGELPAY")
        assertEquals(null, store.batchForHeartbeat(chargeInProgress = true))
        assertTrue(store.batchForHeartbeat(chargeInProgress = false)!!.isNotEmpty())
    }

    @Test
    fun `la telemetria no guarda datos de tarjeta ni montos`() {
        val store = buildTelemetryStore()
        store.record(code = "S000", durationMs = 900L, rail = "BLUMON")
        val json = store.drainBatch().first().toString().lowercase()
        listOf("pan", "card", "amount", "monto", "reference").forEach {
            assertTrue("la telemetria filtro '$it'", !json.contains(it))
        }
    }

    @Test
    fun `el lote se limita para no crecer sin control`() {
        val store = buildTelemetryStore()
        repeat(500) { store.record(code = "N400", durationMs = 1_000L, rail = "BLUMON") }
        assertTrue(store.drainBatch().size <= 100)
    }
}
