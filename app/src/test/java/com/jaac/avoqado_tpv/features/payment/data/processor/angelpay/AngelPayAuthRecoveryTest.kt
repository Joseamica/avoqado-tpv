package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.AuthenticateSimpleResult
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.data.network.dto.AngelPayAuthDto
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantAccountDto
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import com.jaac.avoqado_tpv.core.data.repository.TerminalConfigUnreachableException
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

/**
 * T26 (Testarudo, 2026-09-11): la N86 arrancó sin red, la auth de AngelPay falló UNA vez y
 * nada la reintentó — ~4 h sin poder cobrar con tarjeta. Estas pruebas fijan la
 * recuperación sola y sus candados (Amaena: nunca a la primaria sin red, nunca tocar una
 * sesión viva, nunca con un cobro en curso).
 *
 * Usa el [AngelPayAuthRepository] REAL con sus dependencias simuladas: lo que se prueba es
 * la conducta de punta a punta, no un simulacro de la API nueva.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayAuthRecoveryTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var credentialResolver: AngelPayCredentialResolver
    private lateinit var configValidator: AngelPayConfigValidator
    private lateinit var terminalConfigRepository: TerminalConfigRepository
    private lateinit var merchantRepository: AngelPayMerchantRepository
    private lateinit var paymentStateProvider: PaymentStateProvider
    private lateinit var crashlytics: FirebaseCrashlytics
    private lateinit var reportApi: AngelPayReportApi
    private lateinit var repo: AngelPayAuthRepository
    private lateinit var recovery: AngelPayAuthRecovery

    private var ahoraMs = 1_000_000L
    private val clock = AngelPayClock { ahoraMs }

    /** Estado de la "red" que ven la config y el SDK simulados. */
    private var hayRed = false
    private var sesionViva = false

    private val primaria = AngelPayCreds("ops@venue.io", "111111", "QA", "backend", "acc-1")
    private val secundaria = AngelPayCreds("ventas@venue.io", "222222", "QA", "backend", "acc-2")
    private val dtoPrimaria = AngelPayAuthDto("acc-1", "ops@venue.io", "111111", "QA")
    private val dtoSecundaria = AngelPayAuthDto("acc-2", "ventas@venue.io", "222222", "QA")

    /** Cuentas AngelPay que trae la config en caché cuando hay red (por default: una). */
    private var cuentasDelVenue = listOf(dtoPrimaria)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        sdkGateway = mockk(relaxed = true)
        credentialResolver = mockk()
        configValidator = mockk(relaxed = true)
        terminalConfigRepository = mockk()
        merchantRepository = mockk(relaxed = true)
        paymentStateProvider = mockk()
        crashlytics = mockk(relaxed = true)
        reportApi = mockk(relaxed = true)

        every { paymentStateProvider.isCharging() } returns false
        every { paymentStateProvider.isChargeAttemptActive() } returns false
        every { terminalConfigRepository.getCachedAngelPayAccounts() } answers { if (hayRed) cuentasDelVenue else emptyList() }
        every { terminalConfigRepository.getCachedAngelPayAuth() } answers { if (hayRed) cuentasDelVenue.first() else null }
        coEvery { merchantRepository.ultimoComercioActivoConocido() } returns null
        every { sdkGateway.isAuthenticated() } answers { sesionViva }
        every { sdkGateway.getSessionInfo() } returns null
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(emptyList())
        every { terminalConfigRepository.getCachedConfig() } returns null
        coEvery { terminalConfigRepository.fetchConfig(any()) } answers {
            if (hayRed) Result.success(mockk(relaxed = true))
            else Result.failure(
                TerminalConfigUnreachableException("Sin conexión a internet.", UnknownHostException("api.avoqado.io")),
            )
        }
        // Sin red la caché en memoria está vacía: el resolver falla hasta que la config llega.
        every { credentialResolver.resolve() } answers {
            if (hayRed) Result.success(primaria) else Result.failure(MissingAngelPayCredsError)
        }
        coEvery { credentialResolver.resolveByAccountId("acc-2") } answers {
            if (hayRed) Result.success(secundaria) else Result.failure(MissingAngelPayCredsError)
        }
        coEvery { sdkGateway.authenticateSimple(any(), any()) } answers {
            sesionViva = true
            Result.success(AuthenticateSimpleResult.Success)
        }

        repo = AngelPayAuthRepository(
            sdkGateway = sdkGateway,
            credentialResolver = credentialResolver,
            configValidator = configValidator,
            terminalConfigRepository = terminalConfigRepository,
            deviceInfoManager = mockk(relaxed = true),
            merchantRepository = merchantRepository,
            paymentStateProvider = paymentStateProvider,
            crashlytics = crashlytics,
            reportApi = reportApi,
            clock = clock,
        )
        recovery = AngelPayAuthRecovery(repo, paymentStateProvider, clock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** Arranque sin red: la auth falla una vez y queda atorada (el incidente). */
    private suspend fun arrancarSinRed() {
        hayRed = false
        repo.ensureAuthenticated()
        val estado = repo.state.value
        assertTrue("el arranque sin red debe quedar en AuthError, quedó $estado", estado is AngelPayAuthState.AuthError)
    }

    @Test
    fun `P1 al volver la red la auth se recupera sola tras un arranque sin red`() = runTest(dispatcher) {
        arrancarSinRed()
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }

        hayRed = true
        ahoraMs += 45_000
        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.RECUPERADA, resultado)
        assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
        coVerify(exactly = 1) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
    }

    @Test
    fun `P1 al recuperarse reporta al servidor cuanto estuvo atorada con tipo y disparador`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        ahoraMs += 125_000

        recovery.recoverIfStuck(DisparadorRecuperacion.CONFIG_ACTUALIZADA)

        coVerify(exactly = 1) {
            reportApi.reportValidation(
                accountId = "acc-1",
                state = "AUTH_ERROR",
                externalUserId = any(),
                error = match<String> { it.contains("atorada 125s") && it.contains("tipo=SIN_RED") && it.contains("trigger=CONFIG_ACTUALIZADA") },
                missingInAvoqado = any(),
                missingInSdk = any(),
            )
        }
    }

    @Test
    fun `P1 nunca actua con un cobro en curso`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        every { paymentStateProvider.isCharging() } returns true

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO, resultado)
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        verify(exactly = 0) { sdkGateway.logout() }
    }

    @Test
    fun `P1 nunca toca una sesion viva del SDK`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        sesionViva = true // otra ruta ya autenticó; el estado aún no se enteró

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.SERVIDOR_ALCANZABLE)

        assertEquals(ResultadoRecuperacion.OMITIDA_NO_ATORADA, resultado)
        verify(exactly = 0) { sdkGateway.logout() }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
    }

    @Test
    fun `P1 recupera la cuenta del comercio elegido y nunca cae a la primaria`() = runTest(dispatcher) {
        // El cobro pidió la cuenta 2 sin red: falla SIN_RED y NO cae a la primaria.
        hayRed = false
        repo.ensureAuthenticatedAs("acc-2")
        val estado = repo.state.value
        assertTrue("esperaba AuthError, quedó $estado", estado is AngelPayAuthState.AuthError)
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }

        hayRed = true
        ahoraMs += 60_000
        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.RECUPERADA, resultado)
        coVerify(exactly = 1) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
        coVerify(exactly = 0) { credentialResolver.resolve() }
    }

    @Test
    fun `enfriamiento de 30 s entre reintentos de fondo`() = runTest(dispatcher) {
        arrancarSinRed()
        // La red "vuelve" pero AngelPay sigue sin responder: cada intento falla por transporte.
        hayRed = true
        coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.failure(AngelPayNetworkError())

        ahoraMs += 40_000
        assertEquals(ResultadoRecuperacion.FALLIDA, recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA))
        ahoraMs += 10_000
        assertEquals(
            ResultadoRecuperacion.OMITIDA_ENFRIAMIENTO,
            recovery.recoverIfStuck(DisparadorRecuperacion.SERVIDOR_ALCANZABLE),
        )
        ahoraMs += 21_000
        assertEquals(ResultadoRecuperacion.FALLIDA, recovery.recoverIfStuck(DisparadorRecuperacion.CONFIG_ACTUALIZADA))

        // 2 corridas × 5 intentos de backoff cada una — nunca 3 corridas.
        coVerify(exactly = 10) { sdkGateway.authenticateSimple(any(), any()) }
    }

    @Test
    fun `el boton Reintentar no espera el enfriamiento`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        coEvery { sdkGateway.authenticateSimple(any(), any()) } returns Result.failure(AngelPayNetworkError())
        recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        coEvery { sdkGateway.authenticateSimple(any(), any()) } answers {
            sesionViva = true
            Result.success(AuthenticateSimpleResult.Success)
        }
        ahoraMs += 2_000
        assertEquals(ResultadoRecuperacion.RECUPERADA, recovery.recoverIfStuck(DisparadorRecuperacion.MANUAL))
    }

    @Test
    fun `una sola corrida a la vez aunque lleguen dos disparadores juntos`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        val puerta = CompletableDeferred<Unit>()
        coEvery { sdkGateway.authenticateSimple(any(), any()) } coAnswers {
            puerta.await()
            sesionViva = true
            Result.success(AuthenticateSimpleResult.Success)
        }

        val primera = async { recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA) }
        runCurrent()
        val segunda = async { recovery.recoverIfStuck(DisparadorRecuperacion.MANUAL) }
        runCurrent()
        puerta.complete(Unit)

        assertEquals(ResultadoRecuperacion.RECUPERADA, primera.await())
        assertEquals(ResultadoRecuperacion.OMITIDA_EN_CURSO, segunda.await())
        coVerify(exactly = 1) { sdkGateway.authenticateSimple(any(), any()) }
    }

    @Test
    fun `un error que no es de red no se reintenta en fondo`() = runTest(dispatcher) {
        hayRed = true
        coEvery { sdkGateway.authenticateSimple(any(), any()) } returns
            Result.failure(IllegalArgumentException("PIN invalido"))
        repo.ensureAuthenticated()
        coVerify(exactly = 5) { sdkGateway.authenticateSimple(any(), any()) }

        ahoraMs += 60_000
        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.OMITIDA_NO_ATORADA, resultado)
        // Repetir un PIN rechazado contra AngelPay no arregla nada.
        coVerify(exactly = 5) { sdkGateway.authenticateSimple(any(), any()) }
    }

    @Test
    fun `hasServer que sube de false a true despierta la recuperacion y el valor inicial no`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        val hasServer = MutableStateFlow(true)
        val job = launch { recovery.observarServidor(hasServer) }
        runCurrent()
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }

        hasServer.value = false
        runCurrent()
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }

        hasServer.value = true
        runCurrent()
        coVerify(exactly = 1) { sdkGateway.authenticateSimple(any(), any()) }
        assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
        job.cancel()
    }

    // ----------------------------------------------------------------------
    // Segunda indicación (incidente de Amaena, $4,344.50): la recuperación de fondo
    // NUNCA puede dejar la sesión en la primaria mientras un cobro autentica o lanza.
    // ----------------------------------------------------------------------

    @Test
    fun `P1 si un cobro es duenio de la sesion la recuperacion no entra`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        // El cobro tomó el candado («auth → alineación → lanzamiento»).
        assertTrue(repo.candadoDeSesion.tryLock())

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO, resultado)
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        verify(exactly = 0) { sdkGateway.logout() }
        repo.candadoDeSesion.unlock()
    }

    @Test
    fun `P1 una pantalla de cobro trabajando cuenta como cobro en curso`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        every { paymentStateProvider.isChargeAttemptActive() } returns true

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.SERVIDOR_ALCANZABLE)

        assertEquals(ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO, resultado)
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
    }

    @Test
    fun `P1 revalida DENTRO del candado aunque el chequeo previo haya pasado`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        // Afuera todavía no había cobro; entre el chequeo y el candado se abrió uno.
        every { paymentStateProvider.isChargeAttemptActive() } returnsMany listOf(false, true)

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.CONFIG_ACTUALIZADA)

        assertEquals(ResultadoRecuperacion.OMITIDA_COBRO_EN_CURSO, resultado)
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        assertTrue("el candado debe quedar libre", !repo.candadoDeSesion.isLocked)
    }

    @Test
    fun `el Reintentar desde la pantalla de metodo de pago quieta si entra`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        every { paymentStateProvider.isChargeAttemptActive() } returns true // la pantalla está abierta

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.MANUAL, pantallaDeCobroQuieta = true)

        assertEquals(ResultadoRecuperacion.RECUPERADA, resultado)
    }

    @Test
    fun `P1 en un venue con varias cuentas y ninguna conocida NO autentica la primaria`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        cuentasDelVenue = listOf(dtoPrimaria, dtoSecundaria)

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.OMITIDA_SIN_CUENTA, resultado)
        coVerify(exactly = 0) { sdkGateway.authenticateSimple(any(), any()) }
        // Queda «requiere auth»: el cobro autenticará la cuenta del comercio que el cajero elija.
        assertEquals(AngelPayAuthState.Unauthenticated, repo.state.value)
    }

    @Test
    fun `en multicuenta el Reintentar autentica la cuenta del comercio elegido en pantalla`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        cuentasDelVenue = listOf(dtoPrimaria, dtoSecundaria)

        val resultado = recovery.recoverIfStuck(
            DisparadorRecuperacion.MANUAL,
            pantallaDeCobroQuieta = true,
            cuentaDelComercioElegido = "acc-2",
        )

        assertEquals(ResultadoRecuperacion.RECUPERADA, resultado)
        coVerify(exactly = 1) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
    }

    @Test
    fun `P1 en un venue con varias cuentas vuelve a la cuenta del ultimo comercio activo`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        cuentasDelVenue = listOf(dtoPrimaria, dtoSecundaria)
        coEvery { merchantRepository.ultimoComercioActivoConocido() } returns 22
        val config = mockk<TerminalConfigData>(relaxed = true) {
            every { merchantAccounts } returns listOf(
                mockk<MerchantAccountDto>(relaxed = true) {
                    every { providerCode } returns "ANGELPAY"
                    every { externalMerchantId } returns "22"
                    every { angelpayUserAccountId } returns "acc-2"
                },
            )
        }
        every { terminalConfigRepository.getCachedConfig() } returns config
        coEvery { configValidator.validate(any(), any()) } returns ValidationResult.AllClear

        val resultado = recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA)

        assertEquals(ResultadoRecuperacion.RECUPERADA, resultado)
        coVerify(exactly = 1) { sdkGateway.authenticateSimple("ventas@venue.io", any()) }
        coVerify(exactly = 0) { sdkGateway.authenticateSimple("ops@venue.io", any()) }
    }

    @Test
    fun `mientras la recuperacion autentica el estado es Recuperando y no Authenticating`() = runTest(dispatcher) {
        arrancarSinRed()
        hayRed = true
        val puerta = CompletableDeferred<Unit>()
        coEvery { sdkGateway.authenticateSimple(any(), any()) } coAnswers {
            puerta.await()
            sesionViva = true
            Result.success(AuthenticateSimpleResult.Success)
        }

        val corrida = async { recovery.recoverIfStuck(DisparadorRecuperacion.RED_RECUPERADA) }
        runCurrent()

        // Estado propio: apaga la Tarjeta pero no el Efectivo (ver AngelPayMetodosDePago).
        assertEquals(AngelPayAuthState.Recuperando, repo.state.value)
        puerta.complete(Unit)
        assertEquals(ResultadoRecuperacion.RECUPERADA, corrida.await())
        assertEquals(AngelPayAuthState.Authenticated, repo.state.value)
    }
}
