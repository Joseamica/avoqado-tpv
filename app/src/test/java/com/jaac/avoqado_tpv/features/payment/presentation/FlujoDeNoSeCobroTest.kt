package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.ledger.CobroDeclarable
import com.jaac.avoqado_tpv.features.payment.data.ledger.DeclaracionDesdeElAviso
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Aviso
import com.jaac.avoqado_tpv.features.payment.presentation.PasoDeNoSeCobro.Cerrado
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
 * F1 mínimo, tarea 4 (17-nov): «Por revisar» es la entrada del GERENTE. Elige la fila exacta y firma con su PIN en UN solo paso
 * (Codex código r1 #10): el paso dice qué se firma y [Firmar] manda el PIN; la firma va atada a ESA fila aunque la lista cambie. Sin Compose: las pruebas de JVM del repo no tienen
 * `createComposeRule` (ver `AngelPayPaymentScreenSalidaTest`), así que los diálogos sólo pintan [PasoDeNoSeCobro].
 */
class FlujoDeNoSeCobroTest {
    private val venue = "v1"
    private val pin = "918273"
    private val declaracion = mockk<DeclaracionDesdeElAviso>()
    private val uno = CobroDeclarable("a-1", "r-1", 12_000, 1, null)
    private val otro = CobroDeclarable("a-2", null, 5_000, 2, 3)

    private fun TestScope.flujo() = FlujoDeNoSeCobro(declaracion, venue, backgroundScope)

    private fun prepara(cobro: CobroDeclarable, resultado: ResultadoDeLaDeclaracion?) {
        coEvery { declaracion.preparar(venue, cobro) } returns resultado
    }

    private fun declara(cobro: CobroDeclarable, resultado: ResultadoDeLaDeclaracion?, conPin: String? = pin) {
        coEvery { declaracion.declarar(venue, cobro, conPin) } returns resultado
    }

    /** Hasta el paso del PIN: elegir y consultar. */
    private fun TestScope.hastaElPin(flujo: FlujoDeNoSeCobro, cobro: CobroDeclarable) {
        flujo.tocar(listOf(cobro))
        runCurrent()
    }

    @Test fun `con un cobro, tras consultar pide PRIMERO el PIN del gerente`() = runTest {
        prepara(uno, null)
        val flujo = flujo()

        flujo.tocar(listOf(uno))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno))
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `con varios deja elegir, y al elegir consulta y pide el PIN`() = runTest {
        prepara(otro, null)
        val flujo = flujo()

        flujo.tocar(listOf(uno, otro))
        assertThat(flujo.paso.value).isEqualTo(Elegir(listOf(uno, otro)))
        coVerify(exactly = 0) { declaracion.preparar(any(), any()) }

        flujo.elegir(otro)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(PedirPin(otro))
    }

    @Test fun `Codex r1 10 - un solo paso - Firmar manda ESE PIN por ESA fila, sin pantalla de decision aparte`() = runTest {
        prepara(uno, null)
        declara(uno, ResultadoDeLaDeclaracion.Declarado)
        val flujo = flujo()

        hastaElPin(flujo, uno)
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno))
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }

        flujo.firmar(pin)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
        coVerify(exactly = 1) { declaracion.declarar(venue, uno, pin) }
    }

    @Test fun `el PIN no vive en el paso`() = runTest {
        prepara(uno, null)
        val puerta = CompletableDeferred<ResultadoDeLaDeclaracion?>()
        coEvery { declaracion.declarar(venue, uno, pin) } coAnswers { puerta.await() }
        val flujo = flujo()
        hastaElPin(flujo, uno)
        flujo.firmar(pin)
        runCurrent()
        assertThat(flujo.paso.value.toString()).doesNotContain(pin)
    }

    @Test fun `el paso del PIN dice que se firma, por cuanto y a que hora - el cajero no ve otra decision`() {
        val texto = textoDeLaFirma(CobroDeclarable("a-1", null, 12_000, 0, 1), java.time.ZoneOffset.UTC)
        // Codex código r2 #7: lo que firma, completo — lo mismo que Avoqado le atribuye después.
        assertThat(texto).isEqualTo("Gerente: revisé la terminal y este cobro de \$120.00 de las 00:00 (ref A1) no aparece. Escribe tu PIN para firmarlo.")
    }

    @Test fun `P1 F1 - dos filas del mismo importe y minuto, aparece B y la lista cambia de orden mientras teclea - solo se firma A`() = runTest {
        val a = CobroDeclarable("a-1", null, 12_000, 1_000, 3)
        val b = CobroDeclarable("a-2", null, 12_000, 1_000, 3)
        prepara(a, null)
        declara(a, ResultadoDeLaDeclaracion.Declarado)
        val flujo = flujo()

        flujo.tocar(listOf(a))
        runCurrent()
        flujo.vigentesCambiaron(listOf(b, a))
        assertThat(flujo.paso.value).isEqualTo(PedirPin(a))

        flujo.vigentesCambiaron(listOf(a, b))
        flujo.vigentesCambiaron(listOf(b, a))
        assertThat(flujo.paso.value).isEqualTo(PedirPin(a))

        flujo.firmar(pin)
        runCurrent()
        coVerify(exactly = 1) { declaracion.declarar(venue, a, pin) }
        coVerify(exactly = 0) { declaracion.declarar(venue, b, any()) }
    }

    @Test fun `P1 F1 - si A se resolvio mientras el gerente teclea, aparece su resultado - no se firma`() = runTest {
        prepara(uno, null)
        coEvery { declaracion.resultadoDe(venue, uno) } returns ResultadoDeLaDeclaracion.HayEvidencia
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.vigentesCambiaron(listOf(otro))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Aviso(ResultadoDeLaDeclaracion.HayEvidencia.texto()))
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `P1 F1 - al elegir, si A ya estaba resuelto aparece su resultado, no se cierra en silencio`() = runTest {
        prepara(otro, ResultadoDeLaDeclaracion.Declarado)
        coEvery { declaracion.resultadoDe(venue, otro) } returns ResultadoDeLaDeclaracion.YaResuelto
        val flujo = flujo()

        flujo.tocar(listOf(otro))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Aviso(ResultadoDeLaDeclaracion.YaResuelto.texto()))
    }

    @Test fun `P1 F1 - una fila con evidencia contradictoria se consulta, pero no pide PIN ni admite declaracion`() = runTest {
        val contradicha = otro.copy(declarable = false)
        coEvery { declaracion.consultarYDecir(venue, contradicha) } returns ResultadoDeLaDeclaracion.HayEvidencia
        val flujo = flujo()

        flujo.tocar(listOf(contradicha))
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(Aviso(ResultadoDeLaDeclaracion.HayEvidencia.texto()))
        coVerify(exactly = 0) { declaracion.preparar(any(), any()) }
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `P1 Codex 2 - un Pago rapido que falta consultar nunca llega al PIN`() = runTest {
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
        assertThat(flujo.paso.value).isEqualTo(PedirPin(otro))
    }

    @Test fun `al firmar se apaga el boton mientras vuela, y declarado cierra`() = runTest {
        prepara(uno, null)
        val puerta = CompletableDeferred<ResultadoDeLaDeclaracion?>()
        coEvery { declaracion.declarar(venue, uno, pin) } coAnswers { puerta.await() }
        val flujo = flujo()
        hastaElPin(flujo, uno)

        flujo.firmar(pin)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno, enviando = true))

        // Un segundo toque y Cancelar no hacen nada con el envío en vuelo.
        flujo.firmar(pin)
        flujo.cerrar()
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno, enviando = true))
        coVerify(exactly = 1) { declaracion.declarar(venue, uno, pin) }

        puerta.complete(ResultadoDeLaDeclaracion.Declarado)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `P1 un PIN que Avoqado rechaza vuelve al PIN con el aviso, y el bueno firma`() = runTest {
        prepara(uno, null)
        declara(uno, ResultadoDeLaDeclaracion.PidePin, conPin = "0000")
        declara(uno, ResultadoDeLaDeclaracion.Declarado)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        flujo.firmar("0000")
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno, pinRechazado = true))

        flujo.firmar(pin)
        runCurrent()
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `un PIN fuera de la regla del servidor ni avanza`() = runTest {
        prepara(uno, null)
        val flujo = flujo()
        flujo.tocar(listOf(uno))
        runCurrent()

        for (malo in listOf("", "123", "12345678901", "12a4", " 1234")) flujo.firmar(malo)

        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno))
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `la regla del PIN es la del servidor, de 4 a 10 digitos`() {
        for (bueno in listOf("1234", "123456", "1234567890")) assertThat(pinDeGerenteValido(bueno)).isTrue()
        for (malo in listOf("", "123", "12345678901", "12a4", "12 34", "١٢٣٤")) assertThat(pinDeGerenteValido(malo)).isFalse()
    }

    @Test fun `cada rechazo se dice con su texto y se cierra con Entendido`() = runTest {
        prepara(uno, null)
        val casos = mapOf(
            ResultadoDeLaDeclaracion.SinRed to "Sin conexión: la firma del gerente necesita internet. El cobro sigue por confirmar; inténtalo cuando vuelva.",
            ResultadoDeLaDeclaracion.UsaElPos to "Esta versión todavía no permite declararlo desde la terminal: contéstalo en el POS.",
            ResultadoDeLaDeclaracion.HayEvidencia to "El banco sí registró algo de este cobro: no lo vuelvas a cobrar ni lo marques como no cobrado.",
            ResultadoDeLaDeclaracion.SinPermiso to "Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente.",
            ResultadoDeLaDeclaracion.Rechazado("Lo que dijo el servidor") to "Lo que dijo el servidor",
        )
        for ((resultado, texto) in casos) {
            declara(uno, resultado)
            val flujo = flujo()
            hastaElPin(flujo, uno)
            flujo.firmar(pin)
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
        hastaElPin(flujo, uno)

        flujo.firmar(pin)
        runCurrent()

        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno))
    }

    @Test fun `cancelar cierra sin declarar y olvida el PIN`() = runTest {
        prepara(uno, null)
        val flujo = flujo()
        hastaElPin(flujo, uno)

        flujo.cerrar()

        assertThat(flujo.paso.value).isEqualTo(Cerrado)
        coVerify(exactly = 0) { declaracion.declarar(any(), any(), any()) }
    }

    @Test fun `la lista se mantiene al dia y se cierra cuando se vacia`() = runTest {
        val flujo = flujo()
        flujo.tocar(listOf(uno, otro))

        flujo.vigentesCambiaron(listOf(otro))
        assertThat(flujo.paso.value).isEqualTo(Elegir(listOf(otro)))

        flujo.vigentesCambiaron(emptyList())
        assertThat(flujo.paso.value).isEqualTo(Cerrado)
    }

    @Test fun `con el envio en vuelo no se cambia nada por la lista`() = runTest {
        prepara(uno, null)
        coEvery { declaracion.declarar(venue, uno, pin) } coAnswers { CompletableDeferred<ResultadoDeLaDeclaracion?>().await() }
        val flujo = flujo()
        hastaElPin(flujo, uno)
        flujo.firmar(pin)
        runCurrent()

        flujo.vigentesCambiaron(emptyList())
        assertThat(flujo.paso.value).isEqualTo(PedirPin(uno, enviando = true))
    }

    @Test fun `el aviso no se va por la lista - hay que leerlo`() = runTest {
        prepara(uno, null)
        declara(uno, ResultadoDeLaDeclaracion.HayEvidencia)
        val flujo = flujo()
        hastaElPin(flujo, uno)
        flujo.firmar(pin)
        runCurrent()

        flujo.vigentesCambiaron(emptyList())

        assertThat(flujo.paso.value).isInstanceOf(Aviso::class.java)
    }
}
