package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiSinSalidaMonitorTest {
    private val probe = mockk<InternetExternoProbe>()
    private val socket = mockk<SocketManager>()
    private val red = mockk<NetworkMonitor>()
    private val estadoFlow = MutableStateFlow(ConnectionState(hasInternet = true, hasServer = false))
    private val estado = mockk<ConnectionStateManager>(relaxed = true)
    private val obs = mockk<ObservabilityManager>(relaxed = true)
    private lateinit var monitor: WifiSinSalidaMonitor

    private val wifi = NetworkInfo(type = NetworkType.WIFI, isMetered = false, isConnected = true, signalStrength = 3)

    @Before
    fun setup() {
        every { socket.isCurrentlyConnected() } returns false
        every { red.getCurrentNetworkInfo() } returns wifi
        every { estado.connectionState } returns estadoFlow
        coEvery { probe.consultar() } returns ProbeExterno.INALCANZABLE
        monitor = WifiSinSalidaMonitor(probe, socket, red, estado, obs)
    }

    private suspend fun ev(ms: Long, modo: CellularFailoverMode = CellularFailoverMode.OFF, causa: String? = null) =
        monitor.evaluar(ms, causa, modo)

    @Test fun `socket vivo no sondea ni sospecha`() = runTest {
        every { socket.isCurrentlyConnected() } returns true
        assertEquals(VeredictoWifi.SANO, ev(0))
        coVerify(exactly = 0) { probe.consultar() }
    }

    @Test fun `P1 google 503 es problema de servidor y no marca el wifi`() = runTest {
        coEvery { probe.consultar() } returns respuestaExterna(503)
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, ev(0))
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, ev(90_000))
        verify(exactly = 0) { estado.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO) }
    }

    @Test fun `sondea a lo mucho cada 30 s`() = runTest {
        ev(0); ev(10_000); ev(29_999)
        coVerify(exactly = 1) { probe.consultar() }
        ev(30_000)
        coVerify(exactly = 2) { probe.consultar() }
    }

    @Test fun `P1 relee las senales despues de la sonda`() = runTest {
        coEvery { probe.consultar() } answers {
            every { socket.isCurrentlyConnected() } returns true   // volvió el socket durante la sonda
            ProbeExterno.INALCANZABLE
        }
        assertEquals(VeredictoWifi.SANO, ev(0))
    }

    @Test fun `P1 dos evaluaciones a la vez sondean una sola vez`() = runTest {
        val puerta = CompletableDeferred<Unit>()
        coEvery { probe.consultar() } coAnswers { puerta.await(); ProbeExterno.INALCANZABLE }
        val a = launch { ev(0) }
        val b = launch { ev(1) }
        runCurrent()
        puerta.complete(Unit)
        a.join(); b.join()
        coVerify(exactly = 1) { probe.consultar() }
    }

    @Test fun `al confirmar publica DETECTADO y deja evidencia una sola vez`() = runTest {
        ev(0, causa = "UnknownHostException")
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, ev(60_000))
        ev(90_000)
        verify(exactly = 1) { estado.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO) }
        verify(exactly = 1) { obs.logWarning("WifiSinSalida", match { it.contains("confirmado") }, any()) }
    }

    @Test fun `al volver el servidor cierra con duracion, acciones y causa`() = runTest {
        ev(0, CellularFailoverMode.AUTO_ENFORCED, "SocketTimeoutException")
        ev(60_000, CellularFailoverMode.AUTO_ENFORCED)
        monitor.registrarAccion("wifi_reiniciado")
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true)
        ev(150_000, CellularFailoverMode.AUTO_ENFORCED)
        verify { estado.setWifiSinSalida(EstadoWifiSinSalida.NO) }
        verify(exactly = 1) {
            obs.logWarning(
                "WifiSinSalida",
                match { it.contains("resuelto") },
                match { it["duracionS"] == 150L && it["acciones"].toString().contains("wifi_reiniciado") && it["causa"] == "SocketTimeoutException" },
            )
        }
    }

    @Test fun `un bache que no se confirmo no deja reporte`() = runTest {
        ev(0)
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true)
        ev(40_000)
        verify(exactly = 0) { obs.logWarning(any(), any(), any()) }
    }

    @Test fun `tras reiniciar la confirmacion exige otros 60 s y no duplica el reporte`() = runTest {
        ev(0); ev(60_000)
        monitor.trasReiniciar()
        assertEquals(VeredictoWifi.SOSPECHA, ev(90_000))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, ev(150_000))
        verify(exactly = 1) { obs.logWarning("WifiSinSalida", match { it.contains("confirmado") }, any()) }
    }

    @Test fun `P2 una sonda en vuelo no cuenta despues de trasReiniciar`() = runTest {
        // Una evaluación suspendida en la sonda escribía su resultado viejo DESPUÉS del reinicio (Codex código #3).
        val puerta = CompletableDeferred<Unit>()
        coEvery { probe.consultar() } coAnswers { puerta.await(); ProbeExterno.INALCANZABLE }
        val a = launch { ev(0) }
        runCurrent()   // `a` tiene el mutex y espera la sonda
        val b = launch { monitor.trasReiniciar() }
        runCurrent()   // `b` espera el mutex
        puerta.complete(Unit)
        a.join(); b.join()
        ev(10_000)   // a 10 s de la sonda vieja: sólo sondea si el reinicio la borró de verdad
        coVerify(exactly = 2) { probe.consultar() }
    }

    @Test fun `P2 cerrar sin incidente igual limpia un DETECTADO`() = runTest {
        // El VM pudo resucitar DETECTADO tras un incidente ya cerrado: cerrar() lo limpia aunque no haya incidente (Codex #4).
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true, wifiSinSalida = EstadoWifiSinSalida.DETECTADO)
        assertEquals(VeredictoWifi.SANO, ev(0))
        verify { estado.setWifiSinSalida(EstadoWifiSinSalida.NO) }
        verify(exactly = 0) { obs.logWarning(any(), any(), any()) }
    }

    @Test fun `sigueSinSalida relee red, servidor y socket`() {
        assertEquals(true, monitor.sigueSinSalida())
        every { socket.isCurrentlyConnected() } returns true
        assertEquals(false, monitor.sigueSinSalida())
    }

    @Test fun `P1 confirmarEnVivo sondea aunque haya sondeado hace poco y respeta una respuesta`() = runTest {
        ev(0)   // sonda #1
        assertEquals(true, monitor.confirmarEnVivo())   // sonda #2, sin esperar 30 s
        coVerify(exactly = 2) { probe.consultar() }
        coEvery { probe.consultar() } returns respuestaExterna(204)
        assertEquals(false, monitor.confirmarEnVivo())
    }

    @Test fun `la bitacora no repite acciones`() = runTest {
        ev(0); ev(60_000)
        repeat(5) { monitor.registrarAccion("no_se_reinicio:cobro_en_curso") }
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true)
        ev(90_000)
        verify { obs.logWarning("WifiSinSalida", match { it.contains("resuelto") }, match { it["acciones"] == "no_se_reinicio:cobro_en_curso" }) }
    }
}
