package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import java.math.BigDecimal
import android.content.Context
import android.content.Intent
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
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
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
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, application = android.app.Application::class, sdk = [28])
class AngelPayPaymentReviewRoomTest {

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
    // 📊 Task 6 — relaxed: recording is fire-and-forget and observational only.
    private lateinit var authAttemptTelemetryStore: AuthAttemptTelemetryStore
    // 🔍 Verificador del desenlace INCIERTO: consulta el historial de AngelPay para saber
    // si un cobro sin veredicto llego a moverse. Mockeado aqui; su logica vive en
    // AngelPayChargeVerifierTest.
    private lateinit var chargeVerifier: AngelPayChargeVerifier

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
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cobroSinResolver() } returns null
            coEvery { openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { markAuthorizing(any()) } returns true
            // C.5: por defecto la solicitud no está cercada y el efectivo/cripto puede arrancar.
            coEvery { cercaDeSolicitud(any()) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.LIBRE
        }
        authAttemptTelemetryStore = mockk(relaxed = true)
        chargeVerifier = mockk(relaxed = true)

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

    private fun createViewModel(handle: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()): AngelPayPaymentViewModel = AngelPayPaymentViewModel(
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
        // T26: botón «Reintentar» del banner (recuperación MANUAL de la auth de AngelPay).
        angelPayAuthRecovery = mockk(relaxed = true),
        chargeVerifier = chargeVerifier,
        // Real handle (a plain in-memory map here) — the socket arbitration fields are backed by
        // it so they survive Activity/VM death while the AngelPay SDK Activity is in front.
        savedStateHandle = handle,
    )

    @Test
    fun `review Room attempt survives VM recreation and empty callback cannot claim PRE`() = kotlinx.coroutines.runBlocking {
        // 🔴 RELOJ REAL, no virtual — y esto NO es una preferencia de estilo, es lo único que
        // funciona aquí. MEDIDO con una sonda de bisección el 2026-09-09: con `runTest` y su
        // reloj virtual, el scheduler del test deja de ejecutar CUALQUIER corrutina justo
        // después de la primera escritura en la libreta (`abrioLibreta=true · trasAbrirLibreta=false`),
        // porque `PaymentAttemptLedger` salta a `Dispatchers.IO` — hilos de verdad — en 12
        // sitios. Con el reloj virtual muerto, el ViewModel recreado nunca llegaba a ejecutar
        // NADA y el rojo del test no decía absolutamente nada sobre producción.
        //
        // La sincronización sigue siendo determinista: se espera la CONDICIÓN (`first { … }`)
        // bajo un `withTimeout`, nunca un tiempo fijo. Un `Thread.sleep` sí sería una carrera
        // disfrazada de espera; esperar el evento con tope no lo es — o llega, o el test falla.
        Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        val handle = androidx.lifecycle.SavedStateHandle()
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("offline")
        paymentAttemptLedger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), tpvSettingsRepository)
        val original = createViewModel(handle)
        var recreated: AngelPayPaymentViewModel? = null
        try {
            original.initPayment("100.00")
            original.setSocketPaymentSource("SOCKET", "surviving-request")
            assertThat(handle.get<Boolean>("angelpay_socket_result_emitted") ?: false).isFalse()
            assertThat(original.openLedgerAttemptAndMarkAuthorizing("original-attempt")).isTrue()
            original.onIntentLaunched()
            // El sistema mata la Activity con el SDK de AngelPay en pantalla: el ViewModel muere
            // con TODOS sus campos en RAM. Lo único que sobrevive es la libreta y el handle.
            original.viewModelScope.cancel()

            // Sonda sin mockk: un DAO decorador cuenta las consultas REALES a la libreta.
            val sonda = java.util.concurrent.atomic.AtomicInteger(0)
            val daoBase = db.paymentAttemptDao()
            val daoSonda = object : com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao by daoBase {
                override suspend fun findUnresolvedCharge(): com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity? {
                    sonda.incrementAndGet()
                    return daoBase.findUnresolvedCharge()
                }
            }
            paymentAttemptLedger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(daoSonda, tpvSettingsRepository)
            val restoredHandle = androidx.lifecycle.SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            recreated = createViewModel(restoredHandle)
            assertThat(restoredHandle.get<String>("angelpay_socket_request_id")).isEqualTo("surviving-request")

            // El callback llega VACÍO (RESULT_CANCELED, sin Intent): «no me acuerdo» — que NO es
            // prueba de que no se cobró.
            recreated!!.onAngelPayResult(android.app.Activity.RESULT_CANCELED, null)

            // (1) MUESTRA INCERTIDUMBRE — nunca un «cancelado» que afirme que no hubo cobro.
            val estadoFinal = kotlinx.coroutines.withTimeout(15_000) {
                recreated!!.state.first { it !is AngelPayPaymentState.Idle && it !is AngelPayPaymentState.WaitingForResult }
            }
            com.google.common.truth.Truth.assertWithMessage(
                "consultasALaLibreta=%s fila=%s", sonda.get(), db.paymentAttemptDao().getById("original-attempt")?.state)
                .that(estadoFinal).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)

            // (2) CONSERVA EL INTENTO PENDIENTE — la fila del cobro no se descarta ni se pierde:
            // queda como no resuelta, que es lo que impide darla por buena.
            assertThat(db.paymentAttemptDao().getById("original-attempt")?.state).isAnyOf("AUTORIZANDO", "INDETERMINADO")
            assertThat(sonda.get()).isAtLeast(1)

            // (3) IMPIDE VOLVER A COBRAR — jamás se publica la prueba «no se autorizó nada».
            // Emitir PRE_AUTHORIZATION aquí le diría al POS que puede pasar la tarjeta otra vez.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION")
            }
        } finally { original.viewModelScope.cancel(); recreated?.viewModelScope?.cancel(); db.close() }
        Unit
    }

    @Test
    fun `review una declinacion tras recrear el ViewModel cierra la fila en vez de retener la terminal`() = kotlinx.coroutines.runBlocking {
        // 🔴 ESTE es el caso que la adopción del intento salva y el del callback vacío NO.
        // `markHostResponded` se llama SÓLO con `currentPaymentAttemptId` — sin fallback a la
        // libreta. En un ViewModel recreado ese campo nace null, así que una DECLINACIÓN clara
        // del emisor (código de dos dígitos, no un resultado incierto) no cerraba la fila:
        // quedaba AUTORIZANDO para siempre y la terminal seguía retenida sin que nadie pudiera
        // cobrar en ella. Con la adopción, la fila se cierra como corresponde a «no se cobró».
        Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        val handle = androidx.lifecycle.SavedStateHandle()
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("offline")
        paymentAttemptLedger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), tpvSettingsRepository)
        val original = createViewModel(handle)
        var recreado: AngelPayPaymentViewModel? = null
        try {
            original.initPayment("100.00")
            original.setSocketPaymentSource("SOCKET", "surviving-request")
            assertThat(original.openLedgerAttemptAndMarkAuthorizing("intento-declinado")).isTrue()
            original.onIntentLaunched()
            original.viewModelScope.cancel()

            val restoredHandle = androidx.lifecycle.SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            recreado = createViewModel(restoredHandle)

            // Declinación INEQUÍVOCA del emisor: `approved=false` con código de dos dígitos, que
            // el clasificador trata como rechazo del procesador y NO como resultado incierto.
            val intent = android.content.Intent().putExtra(
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser.EXTRA_TRANSACTION_RESULT,
                """{"approved":false,"code":"05","message":"Declinada por el emisor"}""",
            )
            recreado!!.onAngelPayResult(android.app.Activity.RESULT_OK, intent)

            // La fila DEJA de estar abierta: el veredicto del host quedó anotado.
            val estadoFila = kotlinx.coroutines.withTimeout(15_000) {
                var s = db.paymentAttemptDao().getById("intento-declinado")?.state
                while (s == "AUTORIZANDO") {
                    kotlinx.coroutines.delay(25)
                    s = db.paymentAttemptDao().getById("intento-declinado")?.state
                }
                s
            }
            com.google.common.truth.Truth.assertWithMessage("la fila se quedó en %s — la terminal sigue retenida", estadoFila)
                .that(estadoFila).isNotEqualTo("AUTORIZANDO")
        } finally { original.viewModelScope.cancel(); recreado?.viewModelScope?.cancel(); db.close() }
        Unit
    }

    @Test
    fun `review parsed contradictory issuer and timeout callback never emits processor decline proof`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("offline")
        // Igual que sus tres hermanos de este archivo: sin esto, el ShiftRepository relajado
        // devuelve Object en initPayment y revienta con ClassCastException ANTES de llegar al
        // desenlace financiero que este test mide.
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            vm.setSocketPaymentSource("SOCKET", "contradictory-result")
            vm.onIntentLaunched()
            val intent = Intent().putExtra(
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser.EXTRA_TRANSACTION_RESULT,
                """{"approved":false,"code":"05"}""").putExtra(
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser.EXTRA_CALL_RESULT,
                """{"code":"G505","status":"TIMEOUT"}""")
            vm.onAngelPayResult(android.app.Activity.RESULT_OK, intent)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PROCESSOR_DECLINED")
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `review staggered SDK entry cannot validate a second launch after Room CAS loses`() = runTest(testDispatcher) {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        every { sdkGateway.ensureInitialized(any(), any()) } returns Result.success(Unit)
        every { sdkGateway.isInitialized() } returns true
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.success(Unit)
        coEvery { angelPayAuthRepository.ensureAuthenticated() } returns Result.success(Unit)
        paymentAttemptLedger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), tpvSettingsRepository)
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            runCurrent()
            repeat(2) {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    val method = AngelPayPaymentViewModel::class.java.getDeclaredMethod("startSdkCardPayment",
                        com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials::class.java,
                        kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                    method.invoke(vm, com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials("", "", "", ""), continuation)
                }
            }
            verify(exactly = 1) { sdkGateway.validatePaymentIntent(any(), any()) }
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `review persisted affiliation belongs to authenticated session that will charge`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        every { sdkGateway.ensureInitialized(any(), any()) } returns Result.success(Unit)
        every { sdkGateway.isInitialized() } returns true
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.success(Unit)
        every { sdkGateway.getSessionInfo() } returns null
        coEvery { angelPayAuthRepository.ensureAuthenticated() } coAnswers {
            every { sdkGateway.getSessionInfo() } returns mockk(relaxed = true) { every { affiliation } returns "effective-affiliation" }
            Result.success(Unit)
        }
        val json = slot<String>()
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), capture(json), any()) } returns true
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            runCurrent()
            // Invoke the existing SDK orchestration entry, bypassing only UI merchant selection.
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = AngelPayPaymentViewModel::class.java.getDeclaredMethod("startSdkCardPayment",
                    com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials::class.java,
                    kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                method.invoke(vm, com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials("", "", "", ""), continuation)
            }
            assertThat(json.isCaptured).isTrue()
            val affiliation = com.google.gson.JsonParser.parseString(json.captured).asJsonObject.get("processorAffiliation")
            assertThat(affiliation?.takeUnless { it.isJsonNull }?.asString).isEqualTo("effective-affiliation")
        } finally { vm.viewModelScope.cancel() }
    }
}
