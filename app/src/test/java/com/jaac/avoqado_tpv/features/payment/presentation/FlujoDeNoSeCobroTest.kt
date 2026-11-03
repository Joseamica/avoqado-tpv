package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.ledger.CobroDeclarable
import com.jaac.avoqado_tpv.features.payment.data.ledger.DeclaracionDesdeElAviso
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Aviso
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Cerrado
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Confirmar
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Consultando
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Elegir
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.PedirPin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Los pasos del botón «No se cobró», sin Compose: las pruebas de JVM del repo no tienen `createComposeRule` (ver
 * `AngelPayPaymentScreenSalidaTest`), así que los diálogos sólo pintan [PasoDeNoSeCobro] y lo que decide vive aquí.
 */
class FlujoDeNoSeCobroTest {
    private val venue = "v1"
    private val declaracion = mockk<DeclaracionDesdeElAviso>()
    private val uno = CobroDeclarable("a-1", "r-1", 12_000, 1, null)
    private val otro = CobroDeclarable("a-2", null, 5_000, 2, 3)

    private fun TestScope.flujo() = FlujoDeNoSeCobro(declaracion, venue, backgroundScope)

    private fun prepara(cobro: CobroDeclarable, resultado: ResultadoDeLaDeclaracion?) {
        coEvery { declaracion.preparar(venue, cobro) } returns resultado
    }

    private fun declara(cobro: CobroDeclarable, resultado: ResultadoDeLaDeclaracion?, pin: String? = null) {
        coEvery { declaracion.declarar(venue, cobro, pin) } returns resultado
    }

    @Test fun `con un cobro pide confirmar directo, sin lista`() = runTest {
        prepara(uno, null)
        val flujo = flujo()

        flujo.tocar(listOf(uno))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Confirmar(uno))
    }

    @Test fun `con varios deja elegir, y al elegir pide confirmar`() = runTest {
        prepara(otro, null)
        val flujo = flujo()

        flujo.tocar(listOf(uno, otro))
        assertThat(flujo.paso.value).isEqualTo(Elegir(listOf(uno, otro)))
        coVerify(exactly = 0) { declaracion.preparar(any(), any()) }

        flujo.elegir(otro)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Confirmar(otro))
    }

    @Test fun `P1 Codex 2 - un Pago rapido que falta consultar nunca llega a confirmar`() = runTest {
        prepara(otro, ResultadoDeLaDeclaracion.FaltaConsultar)
        val flujo = flujo()

        flujo.tocar(listOf(otro))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Aviso("Primero hay que consultar a Avoqado: conéctate a internet."))
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `mientras consulta se ve que consulta`() = runTest {
        val puerta = CompletableDeferred<ResultadoDeLaDeclaracion?>()
        coEvery { declaracion.preparar(venue, otro) } coAnswers { puerta.await() }
        val flujo = flujo()

        flujo.tocar(listOf(otro))
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Consultando)

        puerta.complete(null)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Confirmar(otro))
    }

    @Test fun `al preparar, si ya no hay nada que declarar se cierra sin decir nada`() = runTest {
        prepara(otro, ResultadoDeLaDeclaracion.Declarado)
        val flujo = flujo()

        flujo.tocar(listOf(otro))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `al confirmar se apaga el boton mientras vuela, y declarado cierra`() = runTest {
        prepara(uno, null)
        val puerta = CompletableDeferred<ResultadoDeLaDeclaracion?>()
        coEvery { declaracion.declarar(venue, uno, null) } coAnswers { puerta.await() }
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.confirmar()
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Confirmar(uno, enviando = true))

        // Un segundo toque y Cancelar no hacen nada con el envío en vuelo.
        flujo.confirmar()
        flujo.cerrar()
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Confirmar(uno, enviando = true))
        coVerify(exactly = 1) { declaracion.declarar(venue, uno, null) }

        puerta.complete(ResultadoDeLaDeclaracion.Declarado)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `P1 mesero - el servidor pide PIN, un PIN equivocado se dice, y el bueno declara`() = runTest {
        prepara(uno, null)
        declara(uno, ResultadoDeLaDeclaracion.PidePin)
        declara(uno, ResultadoDeLaDeclaracion.PidePin, pin = "0000")
        declara(uno, ResultadoDeLaDeclaracion.Declarado, pin = "918273")
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.confirmar()
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno))

        flujo.confirmar("0000")
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno, pinRechazado = true))

        flujo.confirmar("918273")
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `un PIN fuera de la regla del servidor ni se manda`() = runTest {
        prepara(uno, null)
        declara(uno, ResultadoDeLaDeclaracion.PidePin)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()
        flujo.confirmar()
        runCurrent()

        for (pin in listOf("", "123", "12345678901", "12a4", " 1234")) flujo.confirmar(pin)
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno))
        coVerify(exactly = 1) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `la regla del PIN es la del servidor, de 4 a 10 digitos`() {
        for (pin in listOf("1234", "123456", "1234567890")) assertThat(pinDeGerenteValido(pin)).isTrue()
        for (pin in listOf("", "123", "12345678901", "12a4", "12 34", "١٢٣٤")) assertThat(pinDeGerenteValido(pin)).isFalse()
    }

    @Test fun `cada rechazo se dice con su texto y se cierra con Entendido`() = runTest {
        prepara(uno, null)
        val casos = mapOf(
            ResultadoDeLaDeclaracion.SinRed to "Sin conexión: esto necesita internet. Cuando vuelva, inténtalo otra vez.",
            ResultadoDeLaDeclaracion.UsaElPos to "Esta versión todavía no permite declararlo desde la terminal: contéstalo en el POS.",
            ResultadoDeLaDeclaracion.HayEvidencia to "Avoqado tiene evidencia de este cobro: no lo declares ni lo vuelvas a cobrar.",
            ResultadoDeLaDeclaracion.SinPermiso to "Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente.",
            ResultadoDeLaDeclaracion.Rechazado("Lo que dijo el servidor") to "Lo que dijo el servidor",
        )
        for ((resultado, texto) in casos) {
            declara(uno, resultado)
            val flujo = flujo()
            flujo.tocar(listOf(uno))
            runCurrent()
            flujo.confirmar()
            runCurrent()
            assertThat(flujo.paso.value).isEqualTo(Aviso(texto))

            flujo.cerrar()
            assertThat(flujo.paso.value).isEqualTo(Cerrado)
        }
    }

    @Test fun `si otro envio seguia en vuelo no se afirma nada y se puede volver a tocar - el dialogo no se queda trabado`() = runTest {
        prepara(uno, null)
        declara(uno, null)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.confirmar()
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Confirmar(uno))
    }

    @Test fun `cancelar cierra sin declarar`() = runTest {
        prepara(uno, null)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.cerrar()

        assertThat(flujo.paso.value).isEqualTo(Cerrado)
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `P1 si se declaro en el POS con el dialogo abierto, se cierra solo`() = runTest {
        prepara(uno, null)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.vigentesCambiaron(listOf(uno))
        assertThat(flujo.paso.value).isEqualTo(Confirmar(uno))

        flujo.vigentesCambiaron(emptyList())
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `la lista se mantiene al dia y se cierra cuando se vacia`() = runTest {
        val flujo = flujo()
        flujo.tocar(listOf(uno, otro))

        flujo.vigentesCambiaron(listOf(otro))
        assertThat(flujo.paso.value).isEqualTo(Elegir(listOf(otro)))

        flujo.vigentesCambiaron(emptyList())
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `con el envio en vuelo o un aviso a la vista no se cierra por la lista`() = runTest {
        prepara(uno, null)
        coEvery { declaracion.declarar(venue, uno, null) } coAnswers { CompletableDeferred<ResultadoDeLaDeclaracion?>().await() }
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()
        flujo.confirmar()
        runCurrent()

        flujo.vigentesCambiaron(emptyList())
        assertThat(flujo.paso.value).isEqualTo(Confirmar(uno, enviando = true))
    }

    @Test fun `el aviso no se va por la lista - el cajero tiene que leerlo`() = runTest {
        prepara(uno, null)
        declara(uno, ResultadoDeLaDeclaracion.HayEvidencia)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()
        flujo.confirmar()
        runCurrent()

        flujo.vigentesCambiaron(emptyList())

        assertThat(flujo.paso.value).isInstanceOf(Aviso::class.java)
    }
}
