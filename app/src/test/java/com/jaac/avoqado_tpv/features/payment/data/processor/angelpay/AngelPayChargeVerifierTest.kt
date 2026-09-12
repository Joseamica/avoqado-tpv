package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Resolver un desenlace INCIERTO preguntándole al procesador, que es lo que el propio
 * catálogo de AngelPay pide hacer: `G505` = «Resultado no concluyente, **verifique el
 * historial de transacciones**».
 *
 * 🔴 La regla que estas pruebas blindan es la identificación: se busca SÓLO por
 * `integratorReference` (nuestro `paymentAttemptId`, que AngelPay hace eco). Nunca por
 * monto ni por hora — el servidor ya documenta por qué adivinar ahí es peor que no
 * saber: la propina se elige EN la terminal, así que un cobro legítimo no coincide con
 * el monto pedido, y dos ventas del mismo importe en el mismo minuto son normales.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayChargeVerifierTest {

    private val adapter = mockk<PaymentPostOperationsAdapter>()
    private val factory = mockk<PostOperationsAdapterFactory>()
    private lateinit var verifier: AngelPayChargeVerifier

    @Before
    fun setup() {
        every { factory.get(ProcessorType.ANGELPAY) } returns adapter
        verifier = AngelPayChargeVerifier(factory)
    }

    @Test
    fun `encuentra el cobro por integratorReference y lo reporta cobrado`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(
            listOf(
                transaccion(integratorReference = "otro-intento", authorization = "999999"),
                transaccion(integratorReference = "intento-1", authorization = "251259", reference = "260908155812"),
            )
        )

        val resultado = verificar()

        assertThat(resultado).isInstanceOf(VerificacionDelCobro.Cobrado::class.java)
        val cobrado = resultado as VerificacionDelCobro.Cobrado
        assertThat(cobrado.authCode).isEqualTo("251259")
        assertThat(cobrado.referencia).isEqualTo("260908155812")
    }

    @Test
    fun `no confunde el cobro de OTRO intento del mismo monto`() = runTest {
        // Mismo importe, misma terminal, mismo minuto: sólo la referencia del integrador
        // distingue. Sin este filtro se registraría el cobro de la venta anterior.
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(
            listOf(transaccion(integratorReference = "otro-intento", authorization = "999999"))
        )

        assertThat(verificar(intentos = 1)).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    @Test
    fun `una transaccion sin autorizacion no cuenta como cobro`() = runTest {
        // Aparecer en el historial no basta: sin código de autorización no hay evidencia
        // de que el emisor aprobara (es el mismo criterio con el que este repo permite
        // cancelar o reembolsar una transaccion).
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(
            listOf(transaccion(integratorReference = "intento-1", authorization = ""))
        )

        assertThat(verificar(intentos = 1)).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    @Test
    fun `consulta el dia de hoy y el anterior, acotada a esta terminal`() = runTest {
        val query = slot<TransactionHistoryQuery>()
        coEvery { adapter.getTransactionHistory(capture(query)) } returns Result.success(emptyList())

        verificar(intentos = 1)

        // Ayer tambien: un cobro de las 23:59 que se verifica pasada la medianoche
        // desapareceria de una consulta de un solo dia.
        assertThat(query.captured.startDate).isEqualTo("2026-09-07")
        assertThat(query.captured.endDate).isEqualTo("2026-09-08")
        assertThat(query.captured.terminal).isEqualTo("N860W173400")
        // 🔴 `reference` va en null a proposito: ese campo filtra por la referencia de
        // AngelPay, no por la nuestra. Mandarle el attemptId devuelve SIEMPRE vacio, y
        // vacio se leeria como "no se cobro".
        assertThat(query.captured.reference).isNull()
        assertThat(query.captured.integratorReference).isEqualTo("intento-1")
    }

    @Test
    fun `reintenta cuando el historial todavia no trae el cobro`() = runTest {
        // El historial puede ir con retraso frente al cobro que acaba de ocurrir. Declarar
        // "no se cobro" al primer vistazo es exactamente lo que habilita el doble cobro.
        coEvery { adapter.getTransactionHistory(any()) } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(transaccion(integratorReference = "intento-1", authorization = "251259"))),
        )

        val resultado = verificar(intentos = 3)

        assertThat(resultado).isInstanceOf(VerificacionDelCobro.Cobrado::class.java)
        coVerify(exactly = 2) { adapter.getTransactionHistory(any()) }
    }

    @Test
    fun `si la consulta falla siempre, no se puede verificar — nunca se declara no cobrado`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.failure(RuntimeException("sin red"))

        val resultado = verificar(intentos = 2)

        assertThat(resultado).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
        coVerify(exactly = 2) { adapter.getTransactionHistory(any()) }
    }

    @Test
    fun `historial vacio tras agotar intentos sigue incierto`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())

        assertThat(verificar(intentos = 2)).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    @Test
    fun `una excepcion del adapter no se propaga — queda sin verificar`() = runTest {
        // Un fallo al verificar no puede tumbar la pantalla de un cobro que quiza si paso.
        coEvery { adapter.getTransactionHistory(any()) } throws IllegalStateException("boom")

        assertThat(verificar(intentos = 1)).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }


    @Test
    fun `codigo de autorizacion en operacion rechazada no confirma cobro`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(
            listOf(transaccion("intento-1", "123456").copy(status = "DECLINED"))
        )
        assertThat(verificar(1)).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    @Test
    fun `referencia exacta en otra terminal sigue incierta`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(
            listOf(transaccion("intento-1", "123456").copy(terminal = "OTHER"))
        )
        assertThat(verificar(1)).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    @Test
    fun `matching attempt from wrong affiliation cannot confirm approval`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(transaccion("intento-1", "123456").copy(affiliation = "other")))
        val result = verifier.verificar("intento-1", "N860W173400", intentos = 1, esperaEntreIntentosMs = 0, affiliation = "expected")
        assertThat(result).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    @Test
    fun `unknown affiliation cannot confirm approval from another account`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(transaccion("intento-1", "123456").copy(affiliation = "other")))
        assertThat(verifier.verificar("intento-1", "N860W173400", intentos = 1, esperaEntreIntentosMs = 0))
            .isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    private suspend fun verificar(intentos: Int = 2) = verifier.verificar(
        attemptId = "intento-1",
        terminalSerial = "N860W173400",
        hoy = java.time.LocalDate.of(2026, 9, 8),
        intentos = intentos,
        esperaEntreIntentosMs = 0L,
        affiliation = "merchant-1",
    )

    // ── El serial con el que se le pregunta a AngelPay (defecto medido en la N86, 2026-09-12) ──

    @Test
    fun `el serial para AngelPay va SIN el prefijo AVQD- que ponen nuestras fuentes`() {
        // `DeviceInfoManager.getSerialNumber()` documenta «Serial number with "AVQD-" prefix»
        // y AngelPay conoce el serial crudo del hardware. Con el prefijo puesto, el filtro de
        // la consulta vuelve vacío Y la condición `mia.terminal == terminalSerial` nunca casa.
        assertThat(serialParaAngelPay("AVQD-N860W173397")).isEqualTo("N860W173397")
    }

    @Test
    fun `un serial que ya viene crudo se deja igual`() {
        assertThat(serialParaAngelPay("N860W173397")).isEqualTo("N860W173397")
    }

    @Test
    fun `sin serial devuelve null en vez de una cadena vacia`() {
        // Una cadena vacía pasaría el `takeIf { it.isNotBlank() }` del verificador como filtro
        // ausente pero fallaría su condición de aceptación: mejor null explícito.
        assertThat(serialParaAngelPay(null)).isNull()
        assertThat(serialParaAngelPay("   ")).isNull()
        assertThat(serialParaAngelPay("AVQD-")).isNull()
    }

    @Test
    fun `el serial de un comercio Blumon NO se parece al del aparato — por eso el defecto`() {
        // `TerminalConfig.serialNumber` arranca en DEFAULT_SERIAL = "2841548417" (una PAX), y
        // sus únicos escritores le pasan seriales de MERCHANT Blumon. En una Nexgo nunca vale
        // el serial del aparato. Esta prueba fija que son cosas distintas, para que nadie
        // vuelva a reusar uno por el otro.
        assertThat(serialParaAngelPay("2841548417")).isNotEqualTo(serialParaAngelPay("AVQD-N860W173397"))
    }

    @Test
    fun `con el serial del aparato el cobro SI se confirma`() = runTest {
        coEvery { adapter.getTransactionHistory(any()) } returns
            Result.success(listOf(transaccion("intento-ok", "AUTH99")))

        val r = verifier.verificar(
            attemptId = "intento-ok",
            terminalSerial = serialParaAngelPay("AVQD-N860W173400"),
            hoy = java.time.LocalDate.of(2026, 9, 8),
            intentos = 1,
            esperaEntreIntentosMs = 0L,
            affiliation = "merchant-1",
        )

        assertThat(r).isInstanceOf(VerificacionDelCobro.Cobrado::class.java)
    }

    @Test
    fun `con el prefijo AVQD- puesto el MISMO cobro queda sin verificar`() = runTest {
        // Es el defecto exacto: el cobro está en el historial y aun así no se acredita.
        coEvery { adapter.getTransactionHistory(any()) } returns
            Result.success(listOf(transaccion("intento-ok", "AUTH99")))

        val r = verifier.verificar(
            attemptId = "intento-ok",
            terminalSerial = "AVQD-N860W173400",
            hoy = java.time.LocalDate.of(2026, 9, 8),
            intentos = 1,
            esperaEntreIntentosMs = 0L,
            affiliation = "merchant-1",
        )

        assertThat(r).isInstanceOf(VerificacionDelCobro.NoSePudoVerificar::class.java)
    }

    private fun transaccion(
        integratorReference: String?,
        authorization: String,
        reference: String = "ref-x",
    ) = UnifiedTransaction(
        processorType = ProcessorType.ANGELPAY,
        reference = reference,
        amount = 100.0,
        authorizationCode = authorization,
        status = "APROBADA",
        cardType = null,
        last4 = null,
        cardBin = "411111",
        entryMode = "CHIP",
        date = "2026-09-08",
        time = "15:58",
        operationType = "VENTA",
        merchantName = null,
        affiliation = "merchant-1",
        terminal = "N860W173400",
        folio = "1",
        waiter = null,
        tip = null,
        issuingBank = null,
        cardMethod = null,
        creationDate = "2026-09-08",
        aid = null,
        arqc = null,
        postOperationType = null,
        postOperationReference = null,
        postOperationAuthorization = null,
        postOperationStatus = null,
        integratorReference = integratorReference,
    )
}
