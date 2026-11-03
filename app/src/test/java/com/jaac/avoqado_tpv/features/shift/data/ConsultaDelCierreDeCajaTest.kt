package com.jaac.avoqado_tpv.features.shift.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery.LecturaDelIntento
import com.jaac.avoqado_tpv.features.shift.data.repository.ConsultaDelCierreDeCaja
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligation
import com.jaac.avoqado_tpv.features.shift.domain.textoDelCierreBloqueado
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D3 («ninguna duda de cobro sin salida», QA 2-nov): lo que hace el cierre de Caja al preguntar antes de rechazarse. Sin Room: aquí
 * se prueba el tope con el reloj virtual, que con los hilos de Room vencería antes de tiempo (el recorrido con Room de verdad va en
 * `LiberacionPorSolicitudRoomTest`).
 */
class ConsultaDelCierreDeCajaTest {
    private val recuperacion = mockk<LedgerServerRecovery>()
    private val bandeja = mockk<BandejaServerRecovery>()
    private val socket = mockk<SocketManager>(relaxed = true)
    private val consulta = ConsultaDelCierreDeCaja(recuperacion, bandeja, dagger.Lazy { socket })

    private fun intento(id: String) = LocalShiftObligation("ATTEMPT", id, 1, "MANAGED", "s1")
    private fun remota(id: String) = LocalShiftObligation("REMOTE", id, 1, "MANAGED", "s1")

    @Test fun `pregunta como Consultar - sin gastar el turno - y con respuesta no es falta de conexion`() = runTest {
        coEvery { recuperacion.recoverOne("v1", "a1", any(), false) } returns LecturaDelIntento(null, servidorContesto = true)

        assertThat(consulta.preguntar("v1", listOf(intento("a1")))).isFalse()

        coVerify(exactly = 1) { recuperacion.recoverOne("v1", "a1", any(), false) }
    }

    @Test fun `un resultado que la bandeja resolvio al preguntar se le emite al servidor`() = runTest {
        coEvery { recuperacion.recoverOne("v1", "a1", any(), false) } returns LecturaDelIntento("""{"status":"success"}""", servidorContesto = true)

        consulta.preguntar("v1", listOf(intento("a1")))

        verify(exactly = 1) { socket.emitDurableTerminalPaymentResult("""{"status":"success"}""") }
    }

    @Test fun `sin respuesta de nadie es falta de conexion`() = runTest {
        coEvery { recuperacion.recoverOne("v1", any(), any(), false) } returns LecturaDelIntento(null, sinRespuesta = true)

        assertThat(consulta.preguntar("v1", listOf(intento("a1"), intento("a2")))).isTrue()

        coVerify(exactly = 1) { recuperacion.recoverOne("v1", "a2", any(), false) } // una sin respuesta no deja sin turno a las demás
    }

    @Test fun `con que Avoqado conteste una vez ya no es falta de conexion`() = runTest {
        coEvery { recuperacion.recoverOne("v1", "a1", any(), false) } returns LecturaDelIntento(null, sinRespuesta = true)
        coEvery { recuperacion.recoverOne("v1", "a2", any(), false) } returns LecturaDelIntento(null, servidorContesto = true)

        assertThat(consulta.preguntar("v1", listOf(intento("a1"), intento("a2")))).isFalse()
    }

    @Test fun `lo que no habia que preguntar no es falta de conexion`() = runTest {
        coEvery { recuperacion.recoverOne("v1", "a1", any(), false) } returns LecturaDelIntento(null)

        assertThat(consulta.preguntar("v1", listOf(intento("a1")))).isFalse()
    }

    @Test fun `el tope corta la consulta aunque el servidor no conteste nunca`() = runTest {
        coEvery { recuperacion.recoverOne("v1", any(), any(), false) } coAnswers {
            delay(60_000)
            LecturaDelIntento(null, servidorContesto = true)
        }
        val inicio = currentTime

        val sinRespuesta = consulta.preguntar("v1", listOf(intento("a1"), intento("a2")))

        assertThat(currentTime - inicio).isEqualTo(ConsultaDelCierreDeCaja.TOPE_MS)
        assertThat(sinRespuesta).isTrue()
        coVerify(exactly = 0) { recuperacion.recoverOne("v1", "a2", any(), false) }
    }

    @Test fun `si el tope corta despues de una respuesta no se anuncia falta de conexion`() = runTest {
        coEvery { recuperacion.recoverOne("v1", "a1", any(), false) } returns LecturaDelIntento(null, servidorContesto = true)
        coEvery { recuperacion.recoverOne("v1", "a2", any(), false) } coAnswers { delay(60_000); LecturaDelIntento(null) }

        assertThat(consulta.preguntar("v1", listOf(intento("a1"), intento("a2")))).isFalse()
    }

    @Test fun `una remota se concilia por la bandeja y un intento nunca`() = runTest {
        coEvery { bandeja.conciliar("v1", any()) } returns BandejaServerRecovery.Resultado(consultadas = 1, conciliadas = 0, sinRespuesta = 0)

        assertThat(consulta.preguntar("v1", listOf(remota("r1")))).isFalse()

        coVerify(exactly = 1) { bandeja.conciliar("v1", any()) }
        coVerify(exactly = 0) { recuperacion.recoverOne(any(), any(), any(), any()) }
    }

    @Test fun `una remota sin respuesta es falta de conexion`() = runTest {
        coEvery { bandeja.conciliar("v1", any()) } returns BandejaServerRecovery.Resultado(consultadas = 0, conciliadas = 0, sinRespuesta = 1)

        assertThat(consulta.preguntar("v1", listOf(remota("r1")))).isTrue()
    }

    @Test fun `sin remotas no se toca la bandeja`() = runTest {
        coEvery { recuperacion.recoverOne("v1", "a1", any(), false) } returns LecturaDelIntento(null, servidorContesto = true)

        consulta.preguntar("v1", listOf(intento("a1")))

        coVerify(exactly = 0) { bandeja.conciliar(any(), any()) }
    }

    // ── El mensaje: qué falta y qué hacer ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `el mensaje cuenta lo que falta, en singular y en plural`() {
        for ((operaciones, cobros, esperado) in listOf(
            Triple(1, 0, "1 operación con tarjeta sin confirmar"),
            Triple(3, 0, "3 operaciones con tarjeta sin confirmar"),
            Triple(0, 1, "1 cobro del POS sin terminar"),
            Triple(0, 2, "2 cobros del POS sin terminar"),
            Triple(1, 2, "1 operación con tarjeta sin confirmar y 2 cobros del POS sin terminar"),
        )) {
            assertThat(textoDelCierreBloqueado(operaciones, cobros, sinRespuesta = false)).contains(esperado)
            assertThat(textoDelCierreBloqueado(operaciones, cobros, sinRespuesta = true)).contains(esperado)
        }
    }

    @Test fun `sin respuesta el mensaje pide internet, con respuesta dice donde se resuelve`() {
        val sinRed = textoDelCierreBloqueado(1, 0, sinRespuesta = true)
        assertThat(sinRed).contains("Sin conexión")
        assertThat(sinRed).contains("internet")

        val conRed = textoDelCierreBloqueado(1, 0, sinRespuesta = false)
        assertThat(conRed).doesNotContain("Sin conexión")
        assertThat(conRed).contains("POS")
        assertThat(conRed).contains("soporte")
    }
}
