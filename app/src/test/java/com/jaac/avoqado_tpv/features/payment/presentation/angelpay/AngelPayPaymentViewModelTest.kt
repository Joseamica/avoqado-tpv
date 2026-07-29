package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.angelpay.angelpaysdk.models.CallResult
import com.angelpay.angelpaysdk.models.MerchantOption
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.angelpay.angelpaysdk.models.PaymentRequest
import com.angelpay.angelpaysdk.models.PaymentResult
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
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
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
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
    private lateinit var verificationUploadManager: com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
    private lateinit var observabilityManager: ObservabilityManager
    private lateinit var paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
    // 📒 La Libreta (Task 5) — relaxed: every mark is observational and must never affect the flow.
    private lateinit var paymentAttemptLedger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger

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
        verificationUploadManager = mockk(relaxed = true)
        observabilityManager = mockk(relaxed = true)
        paymentQueueRepository = mockk(relaxed = true)
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.success(Unit)
        paymentAttemptLedger = mockk(relaxed = true)

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
        // Real handle (a plain in-memory map here) — the socket arbitration fields are backed by
        // it so they survive Activity/VM death while the AngelPay SDK Activity is in front.
        savedStateHandle = androidx.lifecycle.SavedStateHandle(),
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

    // ----------------------------------------------------------------------
    // 6b. POS→TPV arbitration link survival (regression, 2026-07-14)
    //
    // The link (_socketRequestId) is BOTH the emit target AND the
    // terminalPaymentRequestId threaded into the recorded payment. It used to be
    // nulled on emit, which silently broke retry-after-decline: the decline emits
    // "failed" and nulls the id → cashier retries ON THE TERMINAL → card APPROVED
    // → the recorded payment carries a null link → the server can never reconcile
    // the FAILED row to COMPLETED and the 🚨 "money moved despite close" alert
    // never fires. Now de-duped by a separate flag; the id lives until reset.
    // ----------------------------------------------------------------------

    @Test
    fun `socket request id SURVIVES the emit so a retry-after-decline still threads the arbitration link`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-RETRY")
                runCurrent()

                // First terminal outcome (a decline) emits exactly once.
                vm.emitSocketResultForTest(status = "failed", errorMessage = "declinada")
                runCurrent()
                verify(exactly = 1) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-RETRY", status = "failed", any(), any(), any(), any(), any(), any(),
                    )
                }

                // 🔑 The id must STILL be there — it is the link the recorded payment carries.
                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-RETRY")

                // A second emit for the SAME request is suppressed (one result per request)…
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-1")
                runCurrent()
                verify(exactly = 0) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-RETRY", status = "success", any(), any(), any(), any(), any(), any(),
                    )
                }
                // …but the link is STILL intact for the retry's payment record.
                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-RETRY")
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `re-tagging the SAME request id is a no-op and cannot re-open the emit gate`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-SAME")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-9")
                runCurrent()

                // The screen re-tags on every recomposition / after an Activity+VM recreation.
                // Re-tagging the same request must NOT reset the emitted flag (double emit).
                vm.setSocketPaymentSource("SOCKET", "REQ-SAME")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-9")
                runCurrent()

                verify(exactly = 1) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-SAME", status = "success", any(), any(), any(), any(), any(), any(),
                    )
                }
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `a NEW request id re-opens the emit gate (next charge must report its own result)`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-1")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-1")
                runCurrent()

                vm.setSocketPaymentSource("SOCKET", "REQ-2")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-2")
                runCurrent()

                verify(exactly = 1) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-2", status = "success", any(), any(), any(), any(), any(), any(),
                    )
                }
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    // ----------------------------------------------------------------------
    // 7. D308 mid-payment session recovery (AppErrorCatalog, byte-identical in
    //    SDK 1.0.10/1.0.13)
    //
    // The sandbox variant compiles with ANGELPAY_SDK_ENABLED=false, so
    // startCardPayment can never reach the SDK launch path in this suite. The
    // tests prime the launched-request state through the @VisibleForTesting
    // launchSdkRequest seam and then drive onAngelPaySdkResult directly.
    // ----------------------------------------------------------------------

    /** Primes the VM as if an SDK payment had just been launched. */
    private fun AngelPayPaymentViewModel.primeSdkLaunch() {
        launchSdkRequest(mockk<PaymentRequest>(relaxed = true), usedQaTipFallback = false)
    }

    /** Builds a declined SDK PaymentResult carrying the given AppErrorCatalog code. */
    private fun sdkFailureResult(
        sdkCode: String,
        message: String = "Pago rechazado",
        category: String = "UNKNOWN",
    ): PaymentResult {
        val call = mockk<CallResult>(relaxed = true)
        every { call.code } returns sdkCode
        every { call.message } returns "msg-$sdkCode"
        every { call.category } returns category
        val result = mockk<PaymentResult>(relaxed = true)
        every { result.approved } returns false
        every { result.callResult } returns call
        every { result.message } returns message
        return result
    }

    @Test
    fun `D308 result triggers handleAuthExpiry and relaunches the same payment once`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            // Relaunched — back in LaunchingAngelPaySdk, not Error.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            // Recovery is NOT a terminal outcome: the D2 charging gate must stay set.
            coVerify(exactly = 0) { paymentStateHolder.setCharging(false) }
            // Recovered silently — nothing terminal happened, so nothing to report.
            verify(exactly = 0) { observabilityManager.logWarning(any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `second D308 on the same attempt surfaces Error instead of looping re-auth`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()
            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            // Only ONE re-auth per payment attempt — the second D308 falls through to Error.
            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("D308")
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
            // Re-auth failed — this IS a terminal decline shown to the cashier, so report it.
            verify(exactly = 1) { observabilityManager.logWarning(any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `non-session error codes do not trigger re-auth`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()

            coVerify(exactly = 0) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `E608 contactless-limit decline is reported to observability with the SDK code and category`() = runTest(testDispatcher) {
        // Regression coverage for the Amaena incident (2026-07-06): AngelPay's EMV kernel
        // rejects a plastic contactless tap over the regulatory limit (Visa/Mastercard
        // $1,000 MXN, Amex $1,500 MXN) BEFORE any gateway call, so this decline is invisible
        // to both AngelPay's and our own backend logs unless the TPV reports it itself.
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verify(exactly = 1) {
                observabilityManager.logWarning(
                    tag = "AngelPayDecline",
                    message = any(),
                    metadata = match { meta ->
                        meta["source"] == "sdk_contract" &&
                            meta["sdkCode"] == "E608" &&
                            meta["category"] == "EMV"
                    },
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `pre-charge register failure (N400) triggers re-auth and relaunches once`() = runTest(testDispatcher) {
        // SDK 1.0.10+/1.0.13: PaymentActivity registers the terminal BEFORE charging and,
        // on failure (expired session being the common cause), aborts with a HARDCODED
        // N400 + this exact message. Message-based detection — see
        // AngelPayErrorMapper.isPreChargeRegisterFailure. Safe to relaunch: the SDK
        // failed before the gateway call, no money moved.
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(
                sdkFailureResult(
                    sdkCode = "N400",
                    message = "No fue posible registrar la terminal antes del cobro",
                ),
            )
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            coVerify(exactly = 0) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `mid-charge N400 network error does NOT trigger re-auth`() = runTest(testDispatcher) {
        // Same N400 code but a normal network message: the charge may have reached the
        // gateway, so re-auth + relaunch is forbidden (double-charge risk).
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult(sdkCode = "N400", message = "Sin conexión a internet"))
            runCurrent()

            coVerify(exactly = 0) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `D308 with failing re-auth surfaces Error without relaunching`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers {
                Result.failure(Exception("re-auth failed"))
            }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Incidente Amaena (2026-07-29): alineación sesión↔merchant seleccionado.
    // La re-auth post-D308 aterrizaba en la cuenta PRIMARIA del venue y el
    // relanzamiento cobraba ESA afiliación mientras el registro llevaba la
    // seleccionada — dinero en A, libros en B.
    // ----------------------------------------------------------------------

    @Test
    fun `D308 recovery does NOT relaunch when the re-authenticated session lands on a different merchant`() = runTest(testDispatcher) {
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)
        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantB) // externalMerchantId 22
            runCurrent()
            activeMerchantIdFlow.value = 22 // sesión alineada al momento de lanzar

            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers {
                // La re-auth quedó en la sesión de OTRA cuenta: su merchant auto-
                // seleccionado es el 11, no el 22 que el cajero eligió (la firma
                // exacta del incidente Amaena).
                activeMerchantIdFlow.value = 11
                Result.success(Unit)
            }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            // NUNCA se relanza el cobro sobre una sesión en el merchant equivocado.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `D308 recovery relaunches when the re-authenticated session matches the selected merchant`() = runTest(testDispatcher) {
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)
        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantB)
            runCurrent()
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers {
                activeMerchantIdFlow.value = 22 // la re-auth volvió al merchant seleccionado
                Result.success(Unit)
            }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `selectMerchant with no tracked session account switches to the target account instead of skipping`() = runTest(testDispatcher) {
        val merchantOwnedByB = angelPayMerchantB.copy(angelpayUserAccountId = "acc-B")
        every { angelPayAuthRepository.getCurrentAngelPayAccountId() } returns null
        coEvery { angelPayAuthRepository.deriveSessionAccountId() } returns null
        coEvery { angelPayAuthRepository.switchAccount("acc-B") } returns Result.success(Unit)
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(merchantOwnedByB)
            runCurrent()

            // Antes del fix este switch se SALTABA en silencio (currentAccountId == null):
            // la sesión podía pertenecer a la primaria mientras la UI mostraba este merchant.
            coVerify(exactly = 1) { angelPayAuthRepository.switchAccount("acc-B") }
            assertThat(vm.currentMerchant.value).isEqualTo(merchantOwnedByB)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // skipReview (serialized SIM sales) — bypasses rating/tip, keeps verification
    // ----------------------------------------------------------------------
    @Test
    fun `initPayment with skipReview true bypasses rating and tip`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false,
            showReviewScreen = true,
            showTipScreen = true,
            showVerificationScreen = false,
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00", orderId = "order_1", skipReview = true)
            runCurrent()

            // showReviewScreen/showTipScreen are both ON, yet skipReview must jump
            // straight past them to merchant selection.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `initPayment with skipReview false keeps rating when enabled`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false,
            showReviewScreen = true,
            showTipScreen = true,
            showVerificationScreen = false,
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00", orderId = "order_1", skipReview = false)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.CollectingRating::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Offline queue on backend-record failure (P1 fix 2026-07-09)
    // ----------------------------------------------------------------------

    private fun angelPayContext() = com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment(
        venueId = "v1",
        staffId = "s1",
        shiftId = "shift-1",
        amount = java.math.BigDecimal("150.00"),
        tip = java.math.BigDecimal("15.00"),
        idempotencyKey = "idem-uuid-1",
        authorizationCode = "AUTH77",
        referenceNumber = "195978383755",
        orderId = "order-9",
        orderNumber = "SN00042",
        serialNumbers = listOf("8952000000000000001"),
    )

    @Test
    fun `handleRecordFailure enqueues the payment and reports queued state`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // F-1: money moved AND it's safely queued — a SUCCESS with a caveat, never Error.
            assertThat(state).isInstanceOf(AngelPayPaymentState.Queued::class.java)
            val queued = state as AngelPayPaymentState.Queued
            // Operator sees the self-healing message, still forbidding a re-charge.
            assertThat(queued.message).contains("EN COLA")
            assertThat(queued.message).contains("NO vuelvas a cobrar")

            coVerify(exactly = 1) {
                paymentQueueRepository.enqueue(
                    match {
                        it.processor == ProcessorType.ANGELPAY &&
                            it.referenceNumber == "195978383755" &&
                            it.idempotencyKey == "idem-uuid-1" &&
                            it.orderId == "order-9" &&
                            it.shiftId == "shift-1" &&
                            it.serialNumbers == listOf("8952000000000000001")
                    },
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `un cobro exitoso con registro encolado NO es un estado de Error`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago",
                context = angelPayContext(),
                error = java.io.IOException("backend no respondio"),
            )

            // 🔴 El bug: era Error. El cajero veia rojo y volvia a cobrar.
            assertThat(state).isInstanceOf(AngelPayPaymentState.Queued::class.java)
            val queued = state as AngelPayPaymentState.Queued
            assertThat(queued.message).contains("EN COLA")
            assertThat(queued.message).contains("NO vuelvas a cobrar")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleRecordFailure falls back to manual-review state when enqueue also fails`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.failure(RuntimeException("disk full"))

        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // Legacy manual-review message — nothing got queued, supervisor must act.
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            val errorState = state as AngelPayPaymentState.Error
            assertThat(errorState.message).contains("avisa al supervisor")
            assertThat(errorState.canRetry).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si el encolado TAMBIEN falla si es un Error real`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns
            Result.failure(IllegalStateException("no entro a la cola"))

        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago",
                context = angelPayContext(),
                error = java.io.IOException("backend no respondio"),
            )

            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("avisa al supervisor")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleRecordFailure uses idempotencyKey as reference fallback when SDK reference is blank`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext().copy(referenceNumber = ""),
                error = RuntimeException("HTTP 503"),
            )

            // reference_number is UNIQUE in Room — a blank reference must not collide.
            coVerify(exactly = 1) {
                paymentQueueRepository.enqueue(match { it.referenceNumber == "idem-uuid-1" })
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 📒 La Libreta write-ahead (Task 5) — observational marks on the AngelPay
    // path. AngelPay has NO separate host-response moment: the SDK's single
    // return IS the verdict, so markHostResponded fires at result arrival
    // (approved=false → DESCARTADA inside the ledger). All marks are relaxed
    // mocks: a ledger failure must never alter the payment flow.
    // ----------------------------------------------------------------------

    @Test
    fun `handleRecordFailure with enqueue OK marks the ledger row delivered to queue`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(), // idempotencyKey == attemptId == "idem-uuid-1"
                error = RuntimeException("HTTP 503"),
            )

            coVerify(exactly = 1) { paymentAttemptLedger.markDeliveredToQueue("idem-uuid-1") }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleRecordFailure does NOT mark delivered to queue when the enqueue also fails`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.failure(RuntimeException("disk full"))

        val vm = createViewModel()
        try {
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // Nothing got queued — the row must stay in REGISTRO_FALLIDO (sweep evidence).
            coVerify(exactly = 0) { paymentAttemptLedger.markDeliveredToQueue(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `declined SDK result marks the host verdict approved=false at result arrival`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch() // generates + registers the attemptId pre-launch
            runCurrent()

            val declined = mockk<PaymentResult>(relaxed = true)
            every { declined.approved } returns false
            every { declined.message } returns "Transaccion declinada"
            every { declined.callResult } returns null
            every { declined.authCode } returns null
            every { declined.reference } returns null
            every { declined.cardBin } returns null

            vm.onAngelPaySdkResult(declined)
            runCurrent()

            // The ledger converts approved=false into DESCARTADA — one mark, no record marks.
            coVerify(exactly = 1) {
                paymentAttemptLedger.markHostResponded(any(), false, null, null, null)
            }
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorized(any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markRecorded(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `cancelled app-to-app result marks the host verdict approved=false`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00") // caches venue/staff + attemptId
            runCurrent()

            // resultCode != RESULT_OK with no extras → AngelPayResult.Cancelled (no money moved).
            vm.onAngelPayResult(resultCode = 0, data = null)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            coVerify(exactly = 1) {
                paymentAttemptLedger.markHostResponded(any(), false, null, null, null)
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `approved SDK result marks host verdict then AUTORIZADO and REGISTRADO on record success`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-led-1",
                receiptUrl = "https://receipt/pay-led-1",
                accessKey = "key-led-1",
                amount = java.math.BigDecimal("100.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            vm.onAngelPaySdkResult(approvedSdkResult(authCode = "A1", reference = "R1"))
            runCurrent()

            // recordCardPayment hops through withContext(Dispatchers.IO) — wait for the tail mark.
            coVerify(timeout = 2000, exactly = 1) { paymentAttemptLedger.markRecorded(any()) }
            coVerify(exactly = 1) {
                paymentAttemptLedger.markHostResponded(any(), true, null, "R1", "A1")
            }
            // AngelPay returns no maskedPan; brand/entryMode captured from the built CardDetails.
            coVerify(exactly = 1) { paymentAttemptLedger.markAuthorized(any(), null, "UNKNOWN", "OTHER") }
            coVerify(exactly = 0) { paymentAttemptLedger.markRecordFailed(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `approved SDK result with record failure marks REGISTRO_FALLIDO then delivered to queue`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            // markRecordFailed runs BEFORE handleRecordFailure so the queue mark's CAS
            // (REGISTRO_FALLIDO → ENTREGADA_A_COLA) can match.
            coVerify(timeout = 2000, exactly = 1) { paymentAttemptLedger.markDeliveredToQueue(any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.markRecordFailed(any(), "HTTP 503") }
            coVerify(exactly = 0) { paymentAttemptLedger.markRecorded(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `D308 recovery relaunch defers the ledger verdict - no DESCARTADA for a live retry`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()

            // First D308: recovery relaunches the SAME attemptId — an early DESCARTADA would
            // blind the ledger to the retried charge's outcome, so NO verdict is written.
            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), any(), any(), any(), any()) }

            // Second D308 on the same attempt: recovery is spent → terminal decline → the
            // DEFERRED mark now fires exactly once with approved=false.
            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()
            coVerify(exactly = 1) { paymentAttemptLedger.markHostResponded(any(), false, null, any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `pre-launch guard suppresses re-open only for the same attempt id AND same amounts`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery {
            paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns true

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            // Legit reuse shape (retry-after-decline / SDK→app-to-app fallback): SAME id,
            // SAME amounts → the second call must NOT re-open (no false REUSE alarm)…
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            coVerify(exactly = 1) {
                paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
            }
            // …but AUTORIZANDO is (re)marked on every launch — the CAS resolves duplicates.
            coVerify(exactly = 2) { paymentAttemptLedger.markAuthorizing("attempt-X") }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `pre-launch guard does NOT suppress re-open when the amount changed - collision alarm stays reachable`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery {
            paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns true

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            // State-contamination shape (spec §6): a surviving VM without resetPayment —
            // initPayment overwrites pendingAmount while ensurePaymentAttemptId keeps the
            // stale id. DIFFERENT money on the SAME id must fall through to openAttempt so
            // the ledger's PK-collision "REUSE" alarm fires.
            vm.initPayment(amount = "250.00")
            runCurrent()
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            coVerify(exactly = 2) {
                paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `a collided or failed open does not arm the re-open suppression`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        // openAttempt = false ⇒ PK collision (the REUSE alarm path) — suppression must NOT arm.
        coEvery {
            paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns false

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            // Same id + same amounts, but the first open never succeeded → both calls open.
            coVerify(exactly = 2) {
                paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // POS→TPV terminal arbitration (Slice 4 Part B): terminal:payment_result
    // emission for SOCKET-initiated charges. Money rule (founder 2026-07):
    // money moved ⇒ "success"; money did NOT move ⇒ "failed". Guarded by
    // _paymentSource == "SOCKET" so device-initiated charges emit nothing.
    // ----------------------------------------------------------------------

    private fun approvedSdkResult(authCode: String = "A1", reference: String = "R1"): PaymentResult {
        val result = mockk<PaymentResult>(relaxed = true)
        every { result.approved } returns true
        every { result.authCode } returns authCode
        every { result.reference } returns reference
        every { result.cardBin } returns null
        every { result.callResult } returns null
        return result
    }

    @Test
    fun `socket-sourced recorded card payment emits success with paymentId`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-1",
                receiptUrl = "https://receipt/pay-1",
                accessKey = "key-1",
                amount = java.math.BigDecimal("100.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00") // caches venue/staff + attemptId
            runCurrent()
            vm.setSocketPaymentSource("SOCKET", "req-success")

            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            // recordCardPayment records via withContext(Dispatchers.IO); verify(timeout) waits for it.
            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-success",
                    status = "success",
                    paymentId = "pay-1",
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced enqueued (money-moved) payment emits success WITHOUT paymentId`() = runTest(testDispatcher) {
        // The gotcha: card WAS charged but backend record failed → enqueued offline. Money moved ⇒
        // report success now; paymentId arrives later when the queue syncs.
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-enqueue")

            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-enqueue",
                    status = "success",
                    paymentId = null,
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced SDK decline (no money moved) emits failed`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-decline")
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("G500")) // non-session decline → terminal
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-decline",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `charge-active flag flips false on a resolved decline screen (terminal not stuck-busy)`() = runTest(testDispatcher) {
        // Device-QA regression: after a decline the AngelPay screen stays on the payment route,
        // but the terminal is FREE. AppNavigation reads paymentStateHolder.isChargeAttemptActive()
        // to tell a live charge from a stale result screen — a decline must publish `false` so the
        // next POS-initiated charge isn't rejected with "Ya hay un pago en proceso" until an app
        // restart. (The bug: the guard checked only the route, conflating "on-screen" with "busy".)
        val vm = createViewModel()
        try {
            runCurrent()
            // A working state → active.
            vm.primeSdkLaunch() // LaunchingAngelPaySdk
            runCurrent()
            verify { paymentStateHolder.setChargeAttemptActive(true) }

            // Fix round 2: `paymentStateHolder` is relaxed with no call-clearing, and
            // `init{}`'s `state.collect{}` guard fires synchronously on construction
            // (UnconfinedTestDispatcher) observing the still-Idle state — that ALONE already
            // calls setChargeAttemptActive(false) before this test drives anything. A bare
            // `verify { setChargeAttemptActive(false) }` below would match THAT stale call and
            // pass regardless of what the decline actually does — proven by deliberately
            // removing `Error` from the ViewModel's guard and re-running: see "Fix round 2" in
            // task-6-report.md. Clearing history right after the `true` checkpoint forces the
            // next verify to be about THIS transition's call, not any earlier one.
            clearMocks(paymentStateHolder, answers = false)

            // Decline → Error is a RESOLVED state → NOT active (terminal free again). Declines
            // resolve synchronously (no backend record/enqueue attempt — money never moved), so
            // `runCurrent()` alone is enough; `exactly = 1` is what makes the assertion
            // discriminate (not the `timeout`, which is defensive/harmless here).
            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()
            verify(timeout = 2000, exactly = 1) { paymentStateHolder.setChargeAttemptActive(false) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `charge-active flag flips false on a resolved Queued screen (terminal not stuck-busy)`() = runTest(testDispatcher) {
        // F-1 fix round 1 (sibling of the Error case above): Queued is a RESOLVED outcome
        // (money moved, safely queued) exactly like Success/Error/Cancelled. Before the F-1
        // fix this path published Error, which WAS excluded from "active" — so the flag
        // already flipped false correctly. Adding Queued as a NEW resolved state reopened
        // the "screen stays up, terminal rejects every subsequent POS charge" bug from
        // 2026-07-14 unless Queued is excluded here too.
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            // A working state → active.
            vm.primeSdkLaunch() // LaunchingAngelPaySdk
            runCurrent()
            verify { paymentStateHolder.setChargeAttemptActive(true) }

            // Fix round 2: same stale-match hole as the decline sibling above — construction
            // already logged a `false` call from the initial Idle state, so a bare
            // `verify(timeout = 2000) { setChargeAttemptActive(false) }` matched THAT call and
            // returned instantly (0ms), never actually waiting on the Dispatchers.IO hop below.
            // Confirmed two ways: (1) run this test in isolation with the old code — it failed
            // on the NEXT line (state was still RecordingPayment); (2) remove `Queued` from the
            // guard and re-run — the class still went green. Clearing history right after the
            // `true` checkpoint fixes the discrimination.
            clearMocks(paymentStateHolder, answers = false)

            // Approved + record failure → Queued is a RESOLVED state → NOT active. The
            // record→enqueue attempt genuinely hops through Dispatchers.IO (same as the sibling
            // "approved SDK result with record failure..." test above, which waits on
            // paymentAttemptLedger the same way) — `exactly = 1` is what makes `timeout` do real
            // polling work now: right after the clear the count is 0 (only a same-transaction
            // `true` from the intermediate RecordingPayment state is recorded), and MockK keeps
            // retrying until the async Queued transition lands the `false` call or 2s elapse.
            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()
            verify(timeout = 2000, exactly = 1) { paymentStateHolder.setChargeAttemptActive(false) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Queued::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced E608 advisory does NOT emit failed and the in-session chip retry emits success`() = runTest(testDispatcher) {
        // Device-QA 2026-07-14 double-charge regression: a $1,501 tap hit the contactless
        // limit (E608). Emitting "failed" showed the POS "Reintentar" and dropped
        // _socketRequestId — while the cashier completed THE SAME charge by chip+PIN
        // (APPROVED, money moved) → orphaned Payment + human-mediated double charge.
        // Vendor catalog (AppErrorCatalog, AAR v1.0.15) marks E608 Retry.IMMEDIATE_AFTER_FIX:
        // hold the socket result so the in-session retry resolves the still-open long-poll.
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-e608",
                receiptUrl = "https://receipt/pay-e608",
                accessKey = "key-e608",
                amount = java.math.BigDecimal("1501.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "1501.00")
            runCurrent()
            vm.setSocketPaymentSource("SOCKET", "req-e608")
            vm.primeSdkLaunch()
            runCurrent()

            // 1) Tap over the contactless limit → E608 advisory. Cashier sees the error
            //    (canRetry), but the POS long-poll must stay OPEN: no emit at all.
            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any())
            }

            // 2) In-session retry: relaunch (resets the consume guard, same as the real
            //    Reintentar → Tarjeta path) + chip APPROVED → the ORIGINAL request id
            //    resolves as success (proves _socketRequestId survived the advisory).
            vm.primeSdkLaunch()
            runCurrent()
            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-e608",
                    status = "success",
                    paymentId = "pay-e608",
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced NEVER-retry EMV decline (E606 online rejection) still emits failed`() = runTest(testDispatcher) {
        // Guard the guard: only IMMEDIATE_AFTER_FIX advisories hold the socket result.
        // A real EMV decline (vendor retry=NEVER) must keep failing fast to the POS.
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-e606")
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult(sdkCode = "E606", message = "Rechazo online", category = "EMV"))
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-e606",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced terminal cancellation emits cancelled`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-cancel")

            vm.onAngelPayResult(resultCode = 0, data = null) // RESULT_CANCELED
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-cancel",
                    status = "cancelled",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced pre-charge error (invalid amount, no money moved) emits failed`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-premoney")

            vm.initPayment(amount = "0.00") // invalid amount → pre-charge failure
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-premoney",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `device-initiated charge (no socket source) emits nothing`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            // No setSocketPaymentSource → _paymentSource stays null → guard suppresses all emits.
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )
            vm.onAngelPayResult(resultCode = 0, data = null)
            runCurrent()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Money-safety: a charge whose money moved must ALWAYS be recorded or
    // enqueued — never dropped. venue/staff null AFTER a successful charge is
    // recovered (cached → auth → secureStorage); only a fully-lost session
    // falls back to the loud manual-review state (still a single success emit).
    // ----------------------------------------------------------------------

    @Test
    fun `cash charge with null cached venue recovers from auth, enqueues on record failure, emits success once`() = runTest(testDispatcher) {
        // No initPayment → cachedVenueId/staffId stay null (the post-charge null scenario).
        every { authRepository.getVenueId() } returns "v-recovered"
        every { authRepository.getStaffId() } returns "s-recovered"
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns
            Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-cash")
            vm.startCashPayment()
            runCurrent()

            // Charge NOT dropped: recovered venue/staff → context built → record fails → enqueued.
            coVerify(timeout = 2000) {
                paymentQueueRepository.enqueue(
                    match { it.referenceNumber.startsWith("CASH-") && it.idempotencyKey != null },
                )
            }
            val ctx = ctxSlot.captured as com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment
            assertThat(ctx.venueId).isEqualTo("v-recovered")   // recovered from authRepository
            assertThat(ctx.staffId).isEqualTo("s-recovered")
            assertThat(ctx.terminalPaymentRequestId).isEqualTo("req-cash") // threaded from _socketRequestId
            // Exactly ONE socket emit (money moved ⇒ success), from handleRecordFailure.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-cash", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `card SDK charge with null cached venue recovers from secureStorage, enqueues on record failure, emits success once`() = runTest(testDispatcher) {
        // cachedVenueId/staffId null; authRepository returns null → fall through to secureStorage.
        every { authRepository.getVenueId() } returns null
        every { authRepository.getStaffId() } returns null
        every { secureStorage.getVenueId() } returns "v-secure"
        every { secureStorage.getStaffId() } returns "s-secure"
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns
            Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-card")
            vm.onAngelPaySdkResult(approvedSdkResult(authCode = "A9", reference = "REF9"))

            coVerify(timeout = 2000) {
                paymentQueueRepository.enqueue(
                    match { it.referenceNumber == "REF9" && it.idempotencyKey != null && it.processor == ProcessorType.ANGELPAY },
                )
            }
            val ctx = ctxSlot.captured as com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment
            assertThat(ctx.venueId).isEqualTo("v-secure")  // recovered from secureStorage (auth was null)
            assertThat(ctx.terminalPaymentRequestId).isEqualTo("req-card")
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-card", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `charge with unrecoverable venue does NOT drop money - loud manual-review, no enqueue, one success emit`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns null
        every { authRepository.getStaffId() } returns null
        every { secureStorage.getVenueId() } returns null
        every { secureStorage.getStaffId() } returns null

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-orphan")
            vm.startCashPayment()
            runCurrent()

            // No venue/staff to build a context → never reaches recordPaymentUseCase or the queue.
            coVerify(exactly = 0) { recordPaymentUseCase(any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentQueueRepository.enqueue(any()) }
            // Loud manual-review state so a human reconciles the money that moved.
            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("avisa al supervisor")
            // Money moved ⇒ exactly one "success" to the POS.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-orphan", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Double-charge mitigation: TRANSIENT/retryable pre-money errors do NOT
    // emit and do NOT clear _socketRequestId, so a terminal-retry-into-success
    // still reports "success" on the original request (no stale "failed").
    // ----------------------------------------------------------------------

    @Test
    fun `transient merchant-select failure on a socket charge does NOT emit`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns
            Result.failure(RuntimeException("SDK switch failed"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-transient")
            vm.selectMerchant(angelPayMerchantB) // transient pre-money error (canRetry=true)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            // No socket result: the request stays open so a terminal retry can still report success.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `transient error then a successful charge emits exactly one success for the original request`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns
            Result.failure(RuntimeException("SDK switch failed"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-retry")

            // 1) Transient failure → no emit, _socketRequestId left intact.
            vm.selectMerchant(angelPayMerchantB)
            runCurrent()
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any()) }

            // 2) Cashier retries → charge succeeds (money-moved/enqueued path emits success).
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // Exactly ONE "success" for the ORIGINAL requestId → proves _socketRequestId survived.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-retry", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `real SDK decline still emits failed (regression)`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-decline-reg")
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("G500")) // real decline — money did NOT move
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-decline-reg", status = "failed", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
