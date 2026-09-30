package com.jaac.avoqado_tpv.core.util

import android.net.wifi.WifiManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlDeWifiTest {
    private val wifi = mockk<WifiFailoverController>()
    private val critica = mockk<CriticalNetworkOperationManager>()
    private val marca = mockk<MarcaDeWifi>()
    private lateinit var control: ControlDeWifi
    private var radioPrendido = true
    private var marcaDesde: Long? = null
    private var reiniciosGuardados: List<Long> = emptyList()

    private fun toggle(enabled: Boolean) = WifiToggleResult(enabled, !enabled, enabled, true, false, null, true)

    @Before
    fun setup() {
        radioPrendido = true
        marcaDesde = null
        every { critica.isAnyCriticalOperationInProgress() } returns false
        every { wifi.estadoDelRadio() } answers {
            if (radioPrendido) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
        }
        coEvery { wifi.setWifiEnabled(any(), any(), any(), any()) } answers { radioPrendido = firstArg(); toggle(firstArg()) }
        every { marca.desde() } answers { marcaDesde }
        coEvery { marca.poner(any()) } answers { marcaDesde = firstArg(); true }
        coEvery { marca.quitar() } answers { marcaDesde = null }
        reiniciosGuardados = emptyList()
        every { marca.reinicios() } answers { reiniciosGuardados }
        every { marca.anotarReinicios(any()) } answers { reiniciosGuardados = firstArg() }
        control = ControlDeWifi(wifi, critica, marca)
    }

    private fun TestScope.reloj() { control.ahora = { 1_000_000L + testScheduler.currentTime } }
    private suspend fun reiniciar(vivo: Boolean = true, puede: () -> Boolean = { true }) =
        control.reiniciar(confirmarEnVivo = { vivo }, puedeActuar = puede)

    @Test fun `reinicia con la marca puesta antes, prende forzado y quita la marca al final`() = runTest {
        reloj()
        assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
        coVerifyOrder {
            marca.poner(any())
            wifi.setWifiEnabled(false, any(), any(), any())
            wifi.setWifiEnabled(true, any(), any(), true)
            marca.quitar()
        }
        assertTrue(radioPrendido)
    }

    @Test fun `P1 cobro en curso no reinicia ni pone la marca`() = runTest {
        every { critica.isAnyCriticalOperationInProgress() } returns true
        assertEquals(ResultadoReinicio.NoSeReinicio("cobro_en_curso"), reiniciar())
        coVerify(exactly = 0) { marca.poner(any()); wifi.setWifiEnabled(any(), any(), any(), any()) }
    }

    @Test fun `P1 si la sonda en vivo responde el enlace volvio y no se toca`() = runTest {
        // La sonda va DESPUÉS de la marca (Codex código #2): la marca se pone, la sonda responde y la marca se quita.
        assertEquals(ResultadoReinicio.NoSeReinicio("el_enlace_volvio"), reiniciar(vivo = false))
        coVerifyOrder { marca.poner(any()); marca.quitar() }
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
        assertNull(marcaDesde)
    }

    @Test fun `P2 la sonda en vivo va despues de la marca y justo antes de apagar`() = runTest {
        // Antes: sonda, marca, apagado. La escritura en disco suspendía entre la sonda y el apagado (Codex código #2).
        val eventos = mutableListOf<String>()
        coEvery { marca.poner(any()) } answers { eventos += "poner"; marcaDesde = firstArg(); true }
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } answers { eventos += "apagar"; radioPrendido = false; toggle(false) }
        control.reiniciar(confirmarEnVivo = { eventos += "sonda"; true }, puedeActuar = { true })
        assertEquals(listOf("poner", "sonda", "apagar"), eventos)
    }

    @Test fun `P1 cobro que empieza mientras se escribe la marca detiene el reinicio`() = runTest {
        coEvery { marca.poner(any()) } answers {
            marcaDesde = firstArg()
            every { critica.isAnyCriticalOperationInProgress() } returns true
            true
        }
        assertEquals(ResultadoReinicio.NoSeReinicio("cambio_a_ultimo_momento"), reiniciar())
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
        coVerify { marca.quitar() }
    }

    @Test fun `P1 marca que no se pudo guardar no reinicia ni sondea`() = runTest {
        coEvery { marca.poner(any()) } returns false
        var sondas = 0
        val r = control.reiniciar(confirmarEnVivo = { sondas++; true }, puedeActuar = { true })
        assertEquals(ResultadoReinicio.NoSeReinicio("marca_no_guardada"), r)
        assertEquals(0, sondas)
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
    }

    @Test fun `modo o senales que cambian antes de actuar no reinician`() = runTest {
        assertEquals(ResultadoReinicio.NoSeReinicio("ya_no_aplica"), reiniciar(puede = { false }))
    }

    @Test fun `P2 el callback del DAL relee cobro, modo y senales`() = runTest {
        var puede = true
        val antesDelDal = slot<() -> Boolean>()
        coEvery { wifi.setWifiEnabled(false, any(), capture(antesDelDal), any()) } answers { radioPrendido = false; toggle(false) }
        reiniciar(puede = { puede })
        assertTrue(antesDelDal.captured())
        puede = false
        assertFalse(antesDelDal.captured())
        puede = true
        every { critica.isAnyCriticalOperationInProgress() } returns true
        assertFalse(antesDelDal.captured())
    }

    @Test fun `tope de 3 por hora`() = runTest {
        reloj()
        repeat(3) {
            assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
            advanceTimeBy(61_000)
        }
        assertEquals(ResultadoReinicio.NoSeReinicio("tope_por_hora"), reiniciar())
    }

    @Test fun `no reinicia otra vez dentro de 60 s del anterior`() = runTest {
        reloj()
        assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), reiniciar())
    }

    @Test fun `P1 apagado que no se ve a tiempo igual pide prender forzado`() = runTest {
        // Android aceptó apagar pero la lectura sigue diciendo prendido (transición lenta): el plan v3 quitaba la marca y
        // no pedía prender, y el apagado tardío dejaba la terminal sin WiFi (Codex v3 #2).
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } returns toggle(false).copy(after = true)
        val r = reiniciar()
        advanceUntilIdle()
        assertEquals(ResultadoReinicio.NoSeReinicio("el_radio_no_se_apago"), r)
        coVerify { wifi.setWifiEnabled(true, any(), any(), true) }
        coVerify { marca.quitar() }   // sólo tras ver WIFI_STATE_ENABLED después del pedido de prender
    }

    @Test fun `P1 Android rechaza prender con el radio aun prendido y la marca queda`() = runTest {
        // Apagado aceptado y lento (la lectura sigue en ENABLED) y Android RECHAZA el encendido: antes se veía el ENABLED
        // viejo, se borraba la marca y el apagado pendiente dejaba la terminal sin WiFi con la app viva (Codex código #1).
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } returns toggle(false).copy(after = true)
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(requestResult = false)
        assertEquals(ResultadoReinicio.Incierto, reiniciar())
        coVerify(exactly = 0) { marca.quitar() }
        assertNotNull(marcaDesde)
        // Se concreta el apagado pendiente; la siguiente restauración, con Android aceptando, prende y quita la marca.
        radioPrendido = false
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } answers { radioPrendido = true; toggle(true) }
        assertTrue(control.restaurarSiQuedoApagado())
        assertTrue(radioPrendido)
        assertNull(marcaDesde)
    }

    @Test fun `P1 restaurar con Android rechazando prender conserva la marca`() = runTest {
        marcaDesde = 1L; radioPrendido = true   // la lectura dice prendido, pero hay un apagado pendiente
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(requestResult = false)
        assertFalse(control.restaurarSiQuedoApagado())
        coVerify(exactly = 0) { marca.quitar() }
        assertNotNull(marcaDesde)
    }

    @Test fun `P2 un apagado que no se vio igual cuenta para la cuota`() = runTest {
        // Un apagado aceptado y lento que se concreta tarde también corta: cuenta desde que se pide (Codex código #5).
        reloj()
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } returns toggle(false).copy(after = true)
        assertEquals(ResultadoReinicio.NoSeReinicio("el_radio_no_se_apago"), reiniciar())
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), reiniciar())
    }

    @Test fun `P1 con una marca pendiente no se reinicia otra vez ni se toca la marca`() = runTest {
        // Codex código r2 #1: apagado lento + encendido rechazado deja la marca; pasados los 60 s y con la restauración
        // fallando otra vez, un segundo intento la sobrescribía y, si su sonda respondía, la QUITABA. Después se concretaba
        // el apagado del primero y ya nadie pedía prender.
        reloj()
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } returns toggle(false).copy(after = true)
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(requestResult = false)
        assertEquals(ResultadoReinicio.Incierto, reiniciar())
        advanceTimeBy(61_000)
        assertFalse(control.restaurarSiQuedoApagado())
        assertEquals(ResultadoReinicio.Incierto, reiniciar(vivo = false))
        assertNotNull(marcaDesde)
        coVerify(exactly = 1) { wifi.setWifiEnabled(false, any(), any(), any()) }
        coVerify(exactly = 0) { marca.quitar() }
        // Se concreta el apagado tardío: la restauración, con Android aceptando, prende y quita la marca.
        radioPrendido = false
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } answers { radioPrendido = true; toggle(true) }
        assertTrue(control.restaurarSiQuedoApagado())
        assertTrue(radioPrendido)
        assertNull(marcaDesde)
    }

    @Test fun `P2 los 60 s se cuentan desde el pedido de apagado, no desde la entrada`() = runTest {
        // Codex código r2 #2: la preparación (marca + sonda) tarda 10 s ⇒ el apagado se pide en t=10 s. Un intento en
        // t=60 s está a sólo 50 s de ese apagado.
        reloj()
        assertEquals(
            ResultadoReinicio.Reiniciado,
            control.reiniciar(confirmarEnVivo = { delay(10_000); true }, puedeActuar = { true }),
        )
        advanceTimeBy(60_000 - testScheduler.currentTime)
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), reiniciar())
    }

    @Test fun `P2 el tope por hora sobrevive a la muerte del proceso`() = runTest {
        // QA N86 30-sep: el tope vivía en memoria; si el proceso moría, la app nueva podía reiniciar otras 3 veces.
        reloj()
        repeat(3) {
            assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
            advanceTimeBy(61_000)
        }
        control = ControlDeWifi(wifi, critica, marca)   // proceso nuevo: sólo queda lo guardado en disco
        reloj()
        assertEquals(ResultadoReinicio.NoSeReinicio("tope_por_hora"), reiniciar())
    }

    @Test fun `P2 los 60 s entre reinicios sobreviven a la muerte del proceso`() = runTest {
        reloj()
        assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
        control = ControlDeWifi(wifi, critica, marca)
        reloj()
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), reiniciar())
    }

    @Test fun `P2 una hora guardada en el futuro no bloquea (el reloj se movio hacia atras)`() = runTest {
        reloj()
        val ahora = control.ahora()
        reiniciosGuardados = listOf(ahora + 600_000, ahora + 700_000, ahora + 800_000)
        control = ControlDeWifi(wifi, critica, marca)
        reloj()
        assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
        assertEquals(listOf(ahora), reiniciosGuardados)   // lo del futuro se descarta al guardar
    }

    @Test fun `encendido no verificado conserva la marca`() = runTest {
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(after = false)   // no prende
        val r = reiniciar()
        advanceUntilIdle()
        assertEquals(ResultadoReinicio.Incierto, r)
        coVerify(exactly = 0) { marca.quitar() }
    }

    @Test fun `P1 reinicio cancelado por el llamador termina y prende`() = runTest {
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } coAnswers { delay(1_500); radioPrendido = false; toggle(false) }
        val job = launch { reiniciar() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(radioPrendido)
        coVerify { marca.quitar() }
    }

    @Test fun `P1 dos pedidos simultaneos reinician una sola vez`() = runTest {
        reloj()
        val puerta = CompletableDeferred<Unit>()
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } coAnswers { puerta.await(); radioPrendido = false; toggle(false) }
        var segundo: ResultadoReinicio? = null
        val a = launch { reiniciar() }
        val b = launch { segundo = reiniciar() }   // espera el Mutex y encuentra el reinicio recién hecho
        runCurrent()
        puerta.complete(Unit)
        advanceUntilIdle(); a.join(); b.join()
        coVerify(exactly = 1) { wifi.setWifiEnabled(false, any(), any(), any()) }
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), segundo)
    }

    @Test fun `restaurar pide prender forzado y quita la marca al verlo prendido`() = runTest {
        marcaDesde = 1L; radioPrendido = false
        assertTrue(control.restaurarSiQuedoApagado())
        assertTrue(radioPrendido)
        coVerify { wifi.setWifiEnabled(true, any(), any(), true) }
        coVerify { marca.quitar() }
    }

    @Test fun `restaurar con la lectura ya prendida igual pide prender forzado`() = runTest {
        marcaDesde = 1L; radioPrendido = true
        assertTrue(control.restaurarSiQuedoApagado())
        coVerify { wifi.setWifiEnabled(true, any(), any(), true) }
    }

    @Test fun `restaurar que no logra prender conserva la marca`() = runTest {
        marcaDesde = 1L; radioPrendido = false
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(after = false)
        assertFalse(control.restaurarSiQuedoApagado())
        coVerify(exactly = 0) { marca.quitar() }
    }

    @Test fun `sin marca restaurar no hace nada`() = runTest {
        assertTrue(control.restaurarSiQuedoApagado())
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
    }
}
