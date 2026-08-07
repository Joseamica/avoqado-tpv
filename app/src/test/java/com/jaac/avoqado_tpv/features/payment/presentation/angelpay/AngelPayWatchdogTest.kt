package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Task 3 (cobro resiliente en red lenta): verifica que el vigilante de autorización
 * (`AuthorizationWatchdog`, Task 1) quede correctamente cableado alrededor de la ventana
 * de autorización en el riel AngelPay (NEXGO).
 *
 * A diferencia de Blumon, aquí la autorización NO es una única llamada suspend: el
 * SDK/app AngelPay corre fuera de nuestro proceso y el resultado vuelve async vía
 * onAngelPayResult/onAngelPaySdkResult. El vigilante arranca en onIntentLaunched()
 * (equivalente al inicio de `performOnlineAuthorization` de Blumon) y se apaga en el
 * `finally` de esos manejadores de resultado.
 *
 * ⚠️ StandardTestDispatcher, NO UnconfinedTestDispatcher: con Unconfined el trabajo del
 * init corre antes del verify y el test pasa en falso — ya nos mordió una vez en este
 * repo (ver PaymentViewModelWatchdogTest.kt, riel Blumon). Nótese que
 * AngelPayPaymentViewModelTest.kt (el resto de la suite de este riel) sí usa
 * UnconfinedTestDispatcher — es su convención establecida para ese archivo, pero este
 * test de watchdog necesita control fino sobre el reloj virtual, así que usa
 * StandardTestDispatcher explícitamente, igual que su contraparte Blumon.
 *
 * Reusa el patrón de construcción de `AngelPayPaymentViewModelTest.kt` (mocks relajados
 * salvo los que este archivo necesita controlar) en vez de inventar uno nuevo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayWatchdogTest {

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private lateinit var appContext: Context
    private lateinit var recordPaymentUseCase: RecordPaymentUseCase
    private lateinit var shiftRepository: ShiftRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var merchantRepository: MerchantRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var terminalConfigRepository: com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
    private lateinit var intentBuilder: AngelPayIntentBuilder
    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var angelPayAuthRepository: AngelPayAuthRepository
    private lateinit var angelPayMerchantRepository: AngelPayMerchantRepository
    private lateinit var paymentStateHolder: PaymentStateHolder
    private lateinit var tpvSettingsRepository: TpvSettingsRepository
    private lateinit var printerManager: PrinterManager
    private lateinit var angelPayTicketBuilder: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayTicketBuilder
    private lateinit var paymentApiService: PaymentApiService
    private lateinit var apiService: ApiService
    private lateinit var socketManager: SocketManager
    private lateinit var verificationUploadManager: com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
    private lateinit var observabilityManager: ObservabilityManager
    private lateinit var paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
    private lateinit var paymentAttemptLedger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
    // 📊 Task 6 — relaxed: recording is fire-and-forget and observational only.
    private lateinit var authAttemptTelemetryStore: AuthAttemptTelemetryStore

    private val authStateFlow = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Authenticated)
    private val activeMerchantIdFlow = MutableStateFlow<Int?>(null)
    private val inFlightSwitchFlow = MutableStateFlow<Int?>(null)
    private val cachedMerchantsFlow = MutableStateFlow<List<com.angelpay.angelpaysdk.models.MerchantSummary>>(emptyList())
    private val merchantsFlow = MutableStateFlow<List<MerchantAccount>>(emptyList())
    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        appContext = mockk(relaxed = true)
        recordPaymentUseCase = mockk(relaxed = true)
        shiftRepository = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        terminalConfigRepository = mockk(relaxed = true)
        intentBuilder = mockk(relaxed = true)
        sdkGateway = mockk(relaxed = true)
        angelPayAuthRepository = mockk(relaxed = true)
        angelPayMerchantRepository = mockk(relaxed = true)
        paymentStateHolder = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        printerManager = mockk(relaxed = true)
        angelPayTicketBuilder = mockk(relaxed = true)
        paymentApiService = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        verificationUploadManager = mockk(relaxed = true)
        observabilityManager = mockk(relaxed = true)
        paymentQueueRepository = mockk(relaxed = true)
        paymentAttemptLedger = mockk(relaxed = true)
        authAttemptTelemetryStore = mockk(relaxed = true)

        every { angelPayAuthRepository.state } returns authStateFlow
        every { angelPayMerchantRepository.activeAngelPayMerchantId } returns activeMerchantIdFlow
        every { angelPayMerchantRepository.inFlightSwitch } returns inFlightSwitchFlow
        every { angelPayMerchantRepository.observeCachedMerchants() } returns cachedMerchantsFlow
        every { merchantRepository.getActiveMerchants() } returns merchantsFlow
        every { socketManager.events } returns socketEventsFlow
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AngelPayPaymentViewModel = AngelPayPaymentViewModel(
        appContext = appContext,
        recordPaymentUseCase = recordPaymentUseCase,
        shiftRepository = shiftRepository,
        authRepository = authRepository,
        merchantRepository = merchantRepository,
        merchantEligibilityRepository = mockk(relaxed = true),
        secureStorage = secureStorage,
        terminalConfigRepository = terminalConfigRepository,
        intentBuilder = intentBuilder,
        sdkGateway = sdkGateway,
        angelPayAuthRepository = angelPayAuthRepository,
        angelPayMerchantRepository = angelPayMerchantRepository,
        paymentStateHolder = paymentStateHolder,
        tpvSettingsRepository = tpvSettingsRepository,
        printerManager = printerManager,
        angelPayTicketBuilder = angelPayTicketBuilder,
        paymentApiService = paymentApiService,
        apiService = apiService,
        socketManager = socketManager,
        verificationUploadManager = verificationUploadManager,
        observability = observabilityManager,
        paymentQueueRepository = paymentQueueRepository,
        paymentAttemptLedger = paymentAttemptLedger,
        authAttemptTelemetryStore = authAttemptTelemetryStore,
        savedStateHandle = SavedStateHandle(),
    )

    /**
     * Deja al ViewModel en la ventana de espera de autorización (equivalente a lo que
     * produce el flujo real justo después de que la Screen lanza el SDK/intent de
     * AngelPay y llama a `onIntentLaunched()`): `_state` = `WaitingForResult`, vigilante
     * arrancado. No recorremos `startCardPayment()` completo — evitar eso ahorra simular
     * SDK init/auth/validation, que no son parte de lo que esta task cablea.
     */
    private fun buildAngelPayViewModelAwaitingAuthorization(): AngelPayPaymentViewModel {
        val vm = createViewModel()
        vm.onIntentLaunched()
        return vm
    }

    /**
     * Deja al ViewModel directamente en `RecordingPayment` (la espera del REGISTRO, no
     * de la autorización) CON un vigilante corriendo — simula el caso en que, por lo que
     * sea, el observador siguiera vivo mientras el estado ya avanzó. Arranca el
     * vigilante vía la función `@VisibleForTesting internal` (mismo patrón que
     * `performOnlineAuthorization` en el riel Blumon) para poder ejercer de verdad el
     * guard de `publishAuthWatchdogLevel`, no sólo asumirlo.
     */
    private fun buildAngelPayViewModelInRecordingPayment(): AngelPayPaymentViewModel {
        val vm = createViewModel()

        val stateField = AngelPayPaymentViewModel::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(vm) as MutableStateFlow<AngelPayPaymentState>
        stateFlow.value = AngelPayPaymentState.RecordingPayment()

        vm.startAuthorizationWatchdog()
        return vm
    }

    private fun AngelPayPaymentViewModel.currentWatchdogLevel(): AuthWatchdogLevel =
        (state.value as? AngelPayPaymentState.WaitingForResult)?.watchdogLevel ?: AuthWatchdogLevel.NONE

    /**
     * "Sigue activa" para AngelPay no es un `Job` (la llamada vive en el proceso del SDK,
     * fuera de nuestro control) sino que el estado NO fue forzado a un desenlace por el
     * vigilante — sigue siendo `WaitingForResult`, listo para recibir el resultado real
     * cuando llegue.
     */
    private fun angelPayAuthorizationStillActive(vm: AngelPayPaymentViewModel): Boolean =
        vm.state.value is AngelPayPaymentState.WaitingForResult

    private fun AngelPayPaymentViewModel.stateIsStillRecordingPayment(): Boolean =
        state.value is AngelPayPaymentState.RecordingPayment

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 3 — vigilante cableado en el riel AngelPay (NEXGO)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `autorizacion rapida sin aviso`() = runTest(testDispatcher) {
        val vm = buildAngelPayViewModelAwaitingAuthorization()
        advanceTimeBy(3_000L)
        assertEquals(AuthWatchdogLevel.NONE, vm.currentWatchdogLevel())

        vm.viewModelScope.cancel()
    }

    @Test
    fun `a los 8 segundos aparece el aviso`() = runTest(testDispatcher) {
        val vm = buildAngelPayViewModelAwaitingAuthorization()
        advanceTimeBy(8_100L)
        assertEquals(AuthWatchdogLevel.SLOW, vm.currentWatchdogLevel())

        vm.viewModelScope.cancel()
    }

    @Test
    fun `el vigilante no cancela la autorizacion de AngelPay`() = runTest(testDispatcher) {
        // El invariante de dinero: pase lo que pase con el reloj, la ventana de
        // autorización sigue abierta — el vigilante nunca la fuerza a un desenlace.
        // Si el procesador aprobó y "canceláramos" aquí, sólo mentiríamos en pantalla
        // sobre un cobro que sí se hizo.
        val vm = buildAngelPayViewModelAwaitingAuthorization()
        advanceTimeBy(120_000L)
        assertEquals(true, angelPayAuthorizationStillActive(vm))

        vm.viewModelScope.cancel()
    }

    @Test
    fun `el observador no toca el estado RecordingPayment`() = runTest(testDispatcher) {
        // RecordingPayment es la espera del REGISTRO, no de la autorizacion.
        // El vigilante no debe escribir sobre ella: tiene su propio diseño.
        val vm = buildAngelPayViewModelInRecordingPayment()
        advanceTimeBy(60_000L)
        assertEquals(true, vm.stateIsStillRecordingPayment())

        vm.viewModelScope.cancel()
    }
}
