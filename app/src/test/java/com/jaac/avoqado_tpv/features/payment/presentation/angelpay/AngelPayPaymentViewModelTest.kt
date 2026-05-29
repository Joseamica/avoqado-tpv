package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.angelpay.angelpaysdk.models.MerchantOption
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AngelPayPaymentViewModel] — Task 32 of the AngelPay SDK 1.0.5
 * multi-merchant migration. Covers the §6.7 / §18.1 D2 race protection,
 * the new `Switching` + `Charging` sub-states, and the `selectMerchant`
 * branching on the [AngelPayAuthState] state machine.
 *
 * Uses [UnconfinedTestDispatcher] following the repo's established pattern for
 * ViewModel + StateFlow-heavy tests (memory note 2026-02-06). Each test cancels
 * `viewModelScope` in a `finally` so `runTest` doesn't hang on the init-block
 * collectors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayPaymentViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // ── Mocks ────────────────────────────────────────────────────────
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

    // Backing state for repositories whose flows the VM observes
    private val authStateFlow = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Authenticated)
    private val activeMerchantIdFlow = MutableStateFlow<Int?>(null)
    private val inFlightSwitchFlow = MutableStateFlow<Int?>(null)
    private val cachedMerchantsFlow = MutableStateFlow<List<MerchantSummary>>(emptyList())
    private val merchantsFlow = MutableStateFlow<List<MerchantAccount>>(emptyList())
    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()

    private val angelPayMerchantA = MerchantAccount(
        id = "merchant_a",
        merchantAccountId = "cma-001",
        serialNumber = "N86-001",
        displayName = "Bar",
        environment = MerchantEnvironment.SANDBOX,
        processorType = ProcessorType.ANGELPAY,
        externalMerchantId = "11",
    )

    private val angelPayMerchantB = MerchantAccount(
        id = "merchant_b",
        merchantAccountId = "cma-002",
        serialNumber = "N86-002",
        displayName = "Restaurant",
        environment = MerchantEnvironment.SANDBOX,
        processorType = ProcessorType.ANGELPAY,
        externalMerchantId = "22",
    )

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

        // Reactive flows the VM observes
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
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): AngelPayPaymentViewModel = AngelPayPaymentViewModel(
        appContext = appContext,
        recordPaymentUseCase = recordPaymentUseCase,
        shiftRepository = shiftRepository,
        authRepository = authRepository,
        merchantRepository = merchantRepository,
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
    )

    // ----------------------------------------------------------------------
    // 1. selectMerchant — SelectingMerchant branch
    // ----------------------------------------------------------------------
    @Test
    fun `selectMerchant in SelectingMerchant state calls completeMerchantSelection with temporaryToken`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.SelectingMerchant(
            merchants = listOf(MerchantOption(id = 11, name = "Bar", afiliationNumber = "1001")),
            temporaryToken = "TEMP-TOKEN-XYZ",
        )
        coEvery {
            angelPayAuthRepository.completeMerchantSelection(11, "TEMP-TOKEN-XYZ")
        } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            coVerify(exactly = 1) {
                angelPayAuthRepository.completeMerchantSelection(11, "TEMP-TOKEN-XYZ")
            }
            coVerify(exactly = 0) { angelPayMerchantRepository.switchActiveMerchant(any()) }
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantA)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 2. selectMerchant — Authenticated branch
    // ----------------------------------------------------------------------
    @Test
    fun `selectMerchant in Authenticated state calls switchActiveMerchant`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantB)
            runCurrent()

            coVerify(exactly = 1) { angelPayMerchantRepository.switchActiveMerchant(22) }
            coVerify(exactly = 0) { angelPayAuthRepository.completeMerchantSelection(any(), any()) }
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantB)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 3. selectMerchant — failure reverts _currentMerchant
    // ----------------------------------------------------------------------
    @Test
    fun `selectMerchant in failure case reverts _currentMerchant`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns
            Result.failure(RuntimeException("SDK switch failed"))

        // Seed previous merchant via a successful first call (still Authenticated branch).
        coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantA)

            vm.selectMerchant(angelPayMerchantB)
            runCurrent()

            // Reverts back to A on failure
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantA)
            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message)
                .contains("No se pudo cambiar de merchant")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 4. startPayment waits for in-flight switch (success path)
    // ----------------------------------------------------------------------
    @Test
    fun `startCardPayment waits for in-flight switch up to 8s when active merchant differs from selected`() = runTest(testDispatcher) {
        // Cashier has selected merchant 11, but SDK is still on a different one.
        activeMerchantIdFlow.value = 99
        authStateFlow.value = AngelPayAuthState.Authenticated
        every { secureStorage.getAngelPayCredentials() } returns mockk(relaxed = true)
        // Ensure we skip the SDK path entirely (force app-to-app) so the test
        // doesn't blow up on SDK init mocks.
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = false,
            angelPaySdkFallbackEnabled = true,
        )

        val vm = createViewModel()
        try {
            // Seed selected merchant by routing through the Authenticated branch.
            coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            // Kick off card payment — should enter Switching while waiting for active id.
            vm.startCardPayment()
            // Let the launch run until it suspends on `first { it == 11 }`.
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)
            val switching = vm.state.value as AngelPayPaymentState.Switching
            assertThat(switching.targetMerchantId).isEqualTo(11)
            assertThat(switching.previousMerchantId).isEqualTo(99)

            // Repository finishes the switch → activeMerchantIdFlow flips to 11.
            activeMerchantIdFlow.value = 11
            runCurrent()

            // The guard advances; we should have transitioned past Switching.
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.Switching::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 5. startPayment errors after 8s if switch never completes
    // ----------------------------------------------------------------------
    @Test
    fun `startCardPayment errors after 8s if switch never completes`() = runTest(testDispatcher) {
        activeMerchantIdFlow.value = 99
        authStateFlow.value = AngelPayAuthState.Authenticated
        every { secureStorage.getAngelPayCredentials() } returns mockk(relaxed = true)
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = false,
            angelPaySdkFallbackEnabled = true,
        )

        val vm = createViewModel()
        try {
            coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)

            // Advance past the 8s watchdog without the active id ever flipping.
            advanceTimeBy(8_500L)
            runCurrent()

            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("Cambio de merchant")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 6. paymentStateHolder.setCharging set and cleared around payment
    // ----------------------------------------------------------------------
    @Test
    fun `startCardPayment sets charging then clearChargingOnTerminal clears it on Cancelled`() = runTest(testDispatcher) {
        // No mismatch — guard short-circuits to true, VM enters Charging.
        activeMerchantIdFlow.value = 11
        authStateFlow.value = AngelPayAuthState.Authenticated
        every { secureStorage.getAngelPayCredentials() } returns mockk(relaxed = true)
        // Force app-to-app path with a no-op intent builder (relaxed mock returns mockk Intent).
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = false,
            angelPaySdkFallbackEnabled = true,
        )

        val vm = createViewModel()
        try {
            coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            vm.startCardPayment()
            runCurrent()

            // Charging gate engaged before SDK launch.
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(true) }

            // Simulate a cancellation from the AngelPay app — the result handler
            // must flip the gate back off.
            vm.onAngelPayResult(resultCode = 0, data = null)
            runCurrent()

            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
