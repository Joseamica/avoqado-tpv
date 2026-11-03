package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery.LecturaDelIntento
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * El refresco del banner, con reloj virtual y sin Room: «cada 15 s se consulta UN cobro, en rueda y con tope de 5 s». Codex plan r1 #3:
 * repetir la consulta de Caja con la misma lista vuelve a empezar por A; si A tarda más que el tope, B nunca recibe turno y su banner no
 * se va. Aquí A tarda 6 s y B contesta.
 */
class DeclaracionDesdeElAvisoTest {
    private val venue = "v1"
    private val recuperacion = mockk<LedgerServerRecovery>()
    private val bandeja = mockk<BandejaServerRecovery>()
    private val socket = mockk<SocketManager>(relaxed = true)
    private val declaracion = DeclaracionDesdeElAviso(
        mockk(relaxed = true), mockk(), recuperacion, bandeja, mockk(), dagger.Lazy { socket },
    )

    private fun intento(id: String) = CobroDeclarable(id, null, 100, 1, null)
    private fun ligado(id: String, requestId: String) = CobroDeclarable(id, requestId, 100, 1, null)
    private fun solicitud(requestId: String) = CobroDeclarable(null, requestId, 100, 1, null)

    private fun contesta(vararg ids: String) {
        for (id in ids) coEvery { recuperacion.recoverOne(venue, id, any(), false) } returns LecturaDelIntento(null, servidorContesto = true)
    }

    @Test fun `P1 Codex 3 - A tarda 6 s y B contesta - en vueltas sucesivas B recibe turno, y A vuelve a tener el suyo`() = runTest {
        coEvery { recuperacion.recoverOne(venue, "a", any(), false) } coAnswers {
            delay(6_000)
            LecturaDelIntento(null, servidorContesto = true)
        }
        contesta("b")
        val cobros = listOf(intento("a"), intento("b"))

        val inicio = currentTime
        declaracion.refrescarUno(venue, cobros)
        assertThat(currentTime - inicio).isEqualTo(DeclaracionDesdeElAviso.TOPE_DEL_REFRESCO_MS)
        coVerify(exactly = 0) { recuperacion.recoverOne(venue, "b", any(), any()) }

        declaracion.refrescarUno(venue, cobros)
        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "b", any(), false) }

        declaracion.refrescarUno(venue, cobros)
        coVerify(exactly = 2) { recuperacion.recoverOne(venue, "a", any(), false) }
        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "b", any(), false) }
    }

    @Test fun `una identidad por llamada, y nunca gasta el turno de la recuperacion`() = runTest {
        contesta("a", "b", "c")

        declaracion.refrescarUno(venue, listOf(intento("a"), intento("b"), intento("c")))

        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "a", any(), false) }
        coVerify(exactly = 0) { recuperacion.recoverOne(venue, "b", any(), any()) }
        coVerify(exactly = 0) { recuperacion.recoverOne(venue, "c", any(), any()) }
        coVerify(exactly = 0) { recuperacion.recoverOne(any(), any(), any(), true) }
    }

    @Test fun `la rueda no depende del orden en que llegue la lista ni de que se vaya un cobro`() = runTest {
        contesta("a", "b", "c")

        declaracion.refrescarUno(venue, listOf(intento("c"), intento("a"), intento("b"))) // a
        declaracion.refrescarUno(venue, listOf(intento("b"), intento("c"))) // se fue «a»: b
        declaracion.refrescarUno(venue, listOf(intento("c"), intento("b"))) // c
        declaracion.refrescarUno(venue, listOf(intento("c"), intento("b"))) // da la vuelta: b

        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "a", any(), false) }
        coVerify(exactly = 2) { recuperacion.recoverOne(venue, "b", any(), false) }
        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "c", any(), false) }
    }

    @Test fun `una solicitud sin intento se concilia a ella sola - otra lenta no la tapa`() = runTest {
        coEvery { bandeja.conciliar(venue, any(), "r-lenta") } coAnswers {
            delay(6_000)
            BandejaServerRecovery.Resultado(1, 0, 0)
        }
        coEvery { bandeja.conciliar(venue, any(), "r-b") } returns BandejaServerRecovery.Resultado(1, 1, 0)
        val cobros = listOf(solicitud("r-b"), solicitud("r-lenta"))

        declaracion.refrescarUno(venue, cobros) // r-b, por orden
        declaracion.refrescarUno(venue, cobros) // r-lenta, cortada por el tope
        declaracion.refrescarUno(venue, cobros) // otra vez r-b

        coVerify(exactly = 2) { bandeja.conciliar(venue, any(), "r-b") }
        coVerify(exactly = 1) { bandeja.conciliar(venue, any(), "r-lenta") }
        coVerify(exactly = 0) { recuperacion.recoverOne(any(), any(), any(), any()) }
    }

    @Test fun `un intento ligado se pregunta por el intento, no por la bandeja`() = runTest {
        contesta("a")

        declaracion.refrescarUno(venue, listOf(ligado("a", "r-1")))

        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "a", any(), false) }
        coVerify(exactly = 0) { bandeja.conciliar(any(), any(), any()) }
    }

    @Test fun `lo que la bandeja resolvio al preguntar se le emite al servidor, como el trigger`() = runTest {
        coEvery { recuperacion.recoverOne(venue, "a", any(), false) } returns LecturaDelIntento("""{"status":"success"}""", servidorContesto = true)

        declaracion.refrescarUno(venue, listOf(intento("a")))

        verify(exactly = 1) { socket.emitDurableTerminalPaymentResult("""{"status":"success"}""") }
    }

    @Test fun `no pide la liberacion de las dudas locales - esa puede hacer POST por otros intentos del negocio`() = runTest {
        contesta("a")

        declaracion.refrescarUno(venue, listOf(intento("a")))

        coVerify(exactly = 0) { recuperacion.liberarDudasLocales(any(), any()) }
    }

    @Test fun `sin cobros no hace nada`() = runTest {
        declaracion.refrescarUno(venue, emptyList())

        coVerify(exactly = 0) { recuperacion.recoverOne(any(), any(), any(), any()) }
        coVerify(exactly = 0) { bandeja.conciliar(any(), any(), any()) }
    }

    @Test fun `un error al consultar no tumba el refresco - la siguiente vuelta sigue`() = runTest {
        coEvery { recuperacion.recoverOne(venue, "a", any(), false) } throws IllegalStateException("base rota")
        contesta("b")
        val cobros = listOf(intento("a"), intento("b"))

        declaracion.refrescarUno(venue, cobros)
        declaracion.refrescarUno(venue, cobros)

        coVerify(exactly = 1) { recuperacion.recoverOne(venue, "b", any(), false) }
    }

    @Test fun `una cancelacion de afuera si se propaga`() = runTest {
        coEvery { recuperacion.recoverOne(venue, "a", any(), false) } throws CancellationException("salió de la pantalla")

        val resultado = runCatching { declaracion.refrescarUno(venue, listOf(intento("a"))) }

        assertThat(resultado.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test fun `una falla inesperada al preparar o declarar no tumba la app ni afirma nada`() = runTest {
        val resolver = mockk<BlumonAttemptResolver>()
        val conFalla = DeclaracionDesdeElAviso(mockk(relaxed = true), resolver, recuperacion, bandeja, mockk(), dagger.Lazy { socket })
        coEvery { resolver.prepareChecked(venue, "a") } throws IllegalStateException("base rota")
        coEvery { resolver.declareChecked(venue, "a") } throws IllegalStateException("base rota")
        val generico = ResultadoDeLaDeclaracion.Rechazado(DeclaracionDesdeElAviso.TEXTO_GENERICO)

        assertThat(conFalla.preparar(venue, intento("a"))).isEqualTo(generico)
        assertThat(conFalla.declarar(venue, intento("a"))).isEqualTo(generico)
    }

    @Test fun `una cancelacion al declarar suelta el candado - el siguiente toque manda`() = runTest {
        val resolver = mockk<BlumonAttemptResolver>()
        val conCancelacion = DeclaracionDesdeElAviso(mockk(relaxed = true), resolver, recuperacion, bandeja, mockk(), dagger.Lazy { socket })
        coEvery { resolver.declareChecked(venue, "a") } throws CancellationException("salió de la pantalla")
        assertThat(runCatching { conCancelacion.declarar(venue, intento("a")) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)

        coEvery { resolver.declareChecked(venue, "a") } returns BlumonAttemptResolver.Result(BlumonAttemptResolver.State.RELEASED)

        assertThat(conCancelacion.declarar(venue, intento("a"))).isEqualTo(ResultadoDeLaDeclaracion.Declarado)
    }

    @Test fun `el texto de cada resultado es el acordado`() {
        val textos = mapOf(
            ResultadoDeLaDeclaracion.SinRed to "Sin conexión: esto necesita internet. Cuando vuelva, inténtalo otra vez.",
            ResultadoDeLaDeclaracion.UsaElPos to "Esta versión todavía no permite declararlo desde la terminal: contéstalo en el POS.",
            ResultadoDeLaDeclaracion.FaltaConsultar to "Primero hay que consultar a Avoqado: conéctate a internet.",
            ResultadoDeLaDeclaracion.SinPermiso to "Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente.",
            ResultadoDeLaDeclaracion.HayEvidencia to "Avoqado tiene evidencia de este cobro: no lo declares ni lo vuelvas a cobrar.",
            ResultadoDeLaDeclaracion.Rechazado("lo que dijo el servidor") to "lo que dijo el servidor",
        )
        for ((resultado, texto) in textos) assertThat(resultado.texto()).isEqualTo(texto)
        assertThat(DeclaracionDesdeElAviso.TEXTO_GENERICO).isEqualTo("Este cobro todavía no se puede declarar. Vuelve a consultar en un momento.")
    }
}
