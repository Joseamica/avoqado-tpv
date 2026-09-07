package com.jaac.avoqado_tpv.features.remote_command.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El servidor difunde `tpv_command` a TODO el venue
 * (`broadcastToVenue(venueId, 'tpv_command', { terminalId, ... })`) y cada aparato debe decidir
 * si el comando era para él. Sin ese filtro, un FACTORY_RESET dirigido a UNA terminal lo
 * ejecutan todas las conectadas del negocio — Amaena tiene 4 aparatos en el mismo venue.
 *
 * El identificador que viaja es `terminal.serialNumber || terminal.id`
 * (`tpv-health.service.ts:324`), y en producción ese serial circula en varias formas
 * ("AVQD-N860W173570", "N860W173570", "n860w173570"), así que la comparación normaliza
 * antes de decidir.
 */
class CommandTargetTest {

    private val serialPropio = "AVQD-N860W173570"
    private val idPropio = "cmpeqgofe0055o52bog0sa5jb"

    // ── Es para mí ────────────────────────────────────────────────────

    @Test
    fun `acepta el serial exacto`() {
        assertThat(CommandTarget.isForThisTerminal("AVQD-N860W173570", serialPropio, idPropio)).isTrue()
    }

    @Test
    fun `acepta el serial sin el prefijo AVQD`() {
        assertThat(CommandTarget.isForThisTerminal("N860W173570", serialPropio, idPropio)).isTrue()
    }

    @Test
    fun `acepta el serial en minusculas`() {
        // Producción manda este formato en TerminalPaymentRequest.terminalId
        assertThat(CommandTarget.isForThisTerminal("n860w173570", serialPropio, idPropio)).isTrue()
    }

    @Test
    fun `acepta el serial con espacios alrededor`() {
        assertThat(CommandTarget.isForThisTerminal("  AVQD-N860W173570  ", serialPropio, idPropio)).isTrue()
    }

    @Test
    fun `acepta el id del servidor cuando el serial no coincide`() {
        // broadcastTpvCommand cae al CUID cuando la terminal no tiene serialNumber
        assertThat(CommandTarget.isForThisTerminal(idPropio, serialPropio, idPropio)).isTrue()
    }

    @Test
    fun `acepta el id del servidor aunque no haya serial guardado`() {
        assertThat(CommandTarget.isForThisTerminal(idPropio, null, idPropio)).isTrue()
    }

    // ── NO es para mí: el defecto que esta clase existe para impedir ──

    @Test
    fun `rechaza el comando dirigido a otra terminal del mismo venue`() {
        // Caso real: Amaena tiene 2 N86, una PAX y la tablet en el mismo venue.
        assertThat(CommandTarget.isForThisTerminal("AVQD-N860W175781", serialPropio, idPropio)).isFalse()
    }

    @Test
    fun `rechaza el comando dirigido al id de otra terminal`() {
        assertThat(CommandTarget.isForThisTerminal("cmpg3mdgc011ho62a0e7d8ygc", serialPropio, idPropio)).isFalse()
    }

    @Test
    fun `rechaza un serial que solo comparte el prefijo`() {
        assertThat(CommandTarget.isForThisTerminal("AVQD-N860W1735", serialPropio, idPropio)).isFalse()
    }

    @Test
    fun `no ejecuta nada si el aparato aun no sabe quien es`() {
        // Sin serial ni id no se puede demostrar que el comando sea nuestro: no se obedece.
        assertThat(CommandTarget.isForThisTerminal("AVQD-N860W173570", null, null)).isFalse()
    }

    // ── Compatibilidad: falla ABIERTO cuando el servidor no dice a quién ──

    @Test
    fun `obedece cuando el evento no trae destinatario`() {
        // Un servidor viejo que no manda `terminalId` no puede quedarse sin poder mandar
        // comandos: bloquearlo sería peor que el defecto que arreglamos.
        assertThat(CommandTarget.isForThisTerminal(null, serialPropio, idPropio)).isTrue()
    }

    @Test
    fun `obedece cuando el destinatario viene vacio`() {
        assertThat(CommandTarget.isForThisTerminal("", serialPropio, idPropio)).isTrue()
        assertThat(CommandTarget.isForThisTerminal("   ", serialPropio, idPropio)).isTrue()
    }
}
