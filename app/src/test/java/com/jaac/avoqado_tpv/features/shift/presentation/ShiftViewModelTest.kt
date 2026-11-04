package com.jaac.avoqado_tpv.features.shift.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationOutcome
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationResult
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.shift.data.repository.PaginaPagosDelCierre
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * ShiftViewModelTest
 *
 * Safety-net tests for ShiftViewModel covering:
 * - Shift loading (active, no shift, error)
 * - Open/close shift operations
 * - Validation guards
 * - Settings (shift system enabled)
 * - Initial loading state
 * - Offline mode detection
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Mocks
    private lateinit var shiftRepository: ShiftRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var connectionEventManager: ConnectionEventManager
    private lateinit var cachedShiftDao: CachedShiftDao
    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var permissionsRepository: PermissionsRepository
    private lateinit var refundQueueRepository: RefundQueueRepository

    // Flows we control
    private val fakeConnectionRestoredEvents = MutableSharedFlow<ConnectionRestoredEvent>()
    private val fakeNetworkStatus = MutableSharedFlow<NetworkStatus>()
    private val fakeShiftChanges = MutableSharedFlow<Unit>()

    private val testShift = Shift(
        id = "shift-123",
        venueId = "venue-123",
        staffId = "staff-123",
        staffName = "Maria Garcia",
        startTime = "2025-01-15T08:00:00Z",
        endTime = null,
        status = ShiftStatus.OPEN,
        startingCash = BigDecimal("500.00"),
        endingCash = null,
        totalSales = BigDecimal("1500.00"),
        totalTips = BigDecimal("200.00"),
        totalOrders = 25,
        totalCashPayments = BigDecimal("800.00"),
        totalCardPayments = BigDecimal("700.00"),
        totalVoucherPayments = BigDecimal.ZERO,
        totalOtherPayments = BigDecimal.ZERO,
        totalProductsSold = 50,
        durationMinutes = 120
    )

    @Before
    fun setup() {
        shiftRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        cachedShiftDao = mockk(relaxed = true)
        connectivityObserver = mockk(relaxed = true)
        permissionsRepository = mockk(relaxed = true)
        refundQueueRepository = mockk(relaxed = true)
        // Default: la cola de reembolsos no bloquea nada (Fase 2).
        coEvery { refundQueueRepository.blockingForVenue(any()) } returns emptyList()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returns
            com.jaac.avoqado_tpv.features.shift.data.repository.PaginaPagosDelCierre(0, emptyList(), 0)

        // Default returns
        every { secureStorage.getVenueId() } returns "venue-123"
        every { secureStorage.getStaffId() } returns "staff-123"
        every { secureStorage.isShiftSystemEnabled() } returns true
        every { secureStorage.isCashReconciliationEnabled() } returns false
        every { connectionEventManager.connectionRestoredEvents } returns fakeConnectionRestoredEvents
        every { connectivityObserver.observe() } returns fakeNetworkStatus
        every { shiftRepository.shiftChanges } returns fakeShiftChanges

        // Default permissions: all allowed.
        // 🔴 Nombres EXACTOS del server (`tpv-shifts:*` = operar el turno de ESTA caja).
        // Espejo de `avoqado-server/src/lib/permissions.ts`; ver el test
        // `los permisos del turno se piden con los nombres EXACTOS del server`.
        coEvery { permissionsRepository.hasPermission("tpv-shifts:create") } returns true
        coEvery { permissionsRepository.hasPermission("tpv-shifts:close") } returns true

        // Default: no active shift
        // These legacy fixtures supply current explicitly; a relaxed nullable snapshot is a fake mock, not Room.
        coEvery { shiftRepository.leerCajaLocal(any()) } returns null
        coEvery { shiftRepository.getCachedOpenShift(any()) } returns null
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(null)
        coEvery { shiftRepository.getShiftHistory(any(), any()) } returns Result.Success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(): ShiftViewModel {
        return ShiftViewModel(
            shiftRepository = shiftRepository,
            secureStorage = secureStorage,
            connectionEventManager = connectionEventManager,
            cachedShiftDao = cachedShiftDao,
            connectivityObserver = connectivityObserver,
            permissionsRepository = permissionsRepository,
            refundQueueRepository = refundQueueRepository
        )
    }

    // ========================================
    // PERMISSION NAME MIRRORING (server ↔ TPV)
    // ========================================

    /**
     * 🔴 El desajuste de nombre falla MUDO: el server dice que sí, la app pregunta por
     * otra llave, recibe `false` y esconde el botón sin un solo error en pantalla ni en
     * logcat. Por eso el guard no puede ser "el flujo funciona" (funciona igual con el
     * nombre equivocado, porque el mock es `relaxed`) sino la llave literal preguntada.
     */
    @Test
    fun `los permisos del turno se piden con los nombres EXACTOS del server`() = runTest {
        // When
        createViewModel()
        advanceUntilIdle()

        // Then — `tpv-shifts:*` = OPERAR el turno de esta caja (CASHIER+ lo tiene)
        coVerify(exactly = 1) { permissionsRepository.hasPermission("tpv-shifts:create") }
        coVerify(exactly = 1) { permissionsRepository.hasPermission("tpv-shifts:close") }
    }

    /**
     * `shifts:create` / `shifts:close` son el BACK-OFFICE de turnos (corregir turnos
     * ajenos) y se quedan en MANAGER+. Preguntar por ellos dejaba al cajero sin poder
     * abrir su turno; hoy sólo sobreviviría por el alias bidireccional del server, que
     * existe para los APK ya instalados en la calle — no para este código.
     */
    @Test
    fun `el turno NO se pide con los nombres viejos de back-office`() = runTest {
        // When
        createViewModel()
        advanceUntilIdle()

        // Then
        coVerify(exactly = 0) { permissionsRepository.hasPermission("shifts:create") }
        coVerify(exactly = 0) { permissionsRepository.hasPermission("shifts:close") }
    }

    // ========================================
    // LOAD SHIFT TESTS
    // ========================================

    @Test
    fun `loads ShiftActive when shift exists`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.ShiftActive::class.java)
        assertThat((state as ShiftState.ShiftActive).shift.id).isEqualTo("shift-123")
    }

    @Test
    fun `loads NoActiveShift when no shift`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.NoActiveShift::class.java)
    }

    @Test
    fun `Error on missing venueId`() = runTest {
        // Given
        every { secureStorage.getVenueId() } returns null

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
        assertThat((state as ShiftState.Error).message).contains("local")
    }

    @Test
    fun `Error on repository failure`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Error(
            ApiException.NetworkError(RuntimeException("NetworkError: Connection refused"))
        )

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
    }

    // ========================================
    // OPEN SHIFT TESTS
    // ========================================

    @Test
    fun `openShift calls repository`() = runTest {
        // Given
        coEvery { shiftRepository.openShift("venue-123", "staff-123", 500.0) } returns Result.Success(testShift)
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // When
        viewModel.openShift(500.0)
        advanceUntilIdle()

        // Then
        coVerify { shiftRepository.openShift("venue-123", "staff-123", 500.0) }
    }

    @Test
    fun `openShift Error on missing credentials`() = runTest {
        // Given: missing staffId
        every { secureStorage.getStaffId() } returns null
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // When
        viewModel.openShift(500.0)
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
        assertThat((state as ShiftState.Error).message).contains("sesión")
    }

    // ========================================
    // CLOSE SHIFT TESTS
    // ========================================

    @Test
    fun `closeShift calls repository from active state`() = runTest {
        // Given: active shift loaded
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        coEvery { shiftRepository.closeShift("venue-123", "shift-123") } returns Result.Success(
            testShift.copy(status = ShiftStatus.CLOSED, endTime = "2025-01-15T16:00:00Z")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)

        // When
        viewModel.closeShift()
        advanceUntilIdle()

        // Then
        coVerify { shiftRepository.closeShift("venue-123", "shift-123") }
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
        coVerify(exactly = 2) { shiftRepository.getCurrentShift("venue-123") }
    }

    // M-4: el cierre puede tardar hasta 5 s preguntándole a Avoqado (D3).

    /** Un cierre a media pregunta: el repositorio no contesta hasta que se complete [respuesta]. */
    private fun TestScope.cierreEsperando(respuesta: CompletableDeferred<Result<Shift>>): ShiftViewModel {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        coEvery { shiftRepository.closeShift("venue-123", "shift-123") } coAnswers { respuesta.await() }
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.closeShift()
        advanceUntilIdle()
        return viewModel
    }

    private val motivoDelBloqueo = Result.Error(
        ApiException.ValidationError("No se puede cerrar la caja: hay 1 operación con tarjeta sin confirmar."),
    )

    @Test
    fun `M-4 mientras cierra la pantalla lo dice, y al terminar deja de decirlo`() = runTest {
        val respuesta = CompletableDeferred<Result<Shift>>()
        val viewModel = cierreEsperando(respuesta)

        assertThat(viewModel.state.value).isEqualTo(ShiftState.Loading)
        assertThat(viewModel.cerrandoCaja.value).isTrue()

        respuesta.complete(motivoDelBloqueo)
        advanceUntilIdle()

        assertThat(viewModel.cerrandoCaja.value).isFalse()
    }

    @Test
    fun `M-4 cargar y cerrar dicen cosas distintas`() {
        assertThat(textoDelCargando(cerrandoCaja = true)).isEqualTo("Cerrando la caja...")
        assertThat(textoDelCargando(cerrandoCaja = false)).isEqualTo("Cargando turno de caja...")
    }

    @Test
    fun `M-4 un connectionRestored en esa ventana no se come el mensaje de bloqueo`() = runTest {
        val respuesta = CompletableDeferred<Result<Shift>>()
        val viewModel = cierreEsperando(respuesta)
        clearMocks(shiftRepository, answers = false, recordedCalls = true)

        fakeConnectionRestoredEvents.emit(ConnectionRestoredEvent(timestamp = "2026-11-03T08:00:00Z", attemptsBeforeReconnection = 1))
        advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(ShiftState.Loading) // sigue diciendo que está cerrando
        coVerify(exactly = 0) { shiftRepository.getCurrentShift(any()) }

        respuesta.complete(motivoDelBloqueo)
        advanceUntilIdle()

        assertThat((viewModel.state.value as ShiftState.Error).message).contains("No se puede cerrar la caja")
    }

    @Test
    fun `M-4 sin cierre en curso, la reconexion si recarga`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        clearMocks(shiftRepository, answers = false, recordedCalls = true)

        fakeConnectionRestoredEvents.emit(ConnectionRestoredEvent(timestamp = "2026-11-03T08:00:00Z", attemptsBeforeReconnection = 1))
        advanceUntilIdle()

        assertThat(viewModel.cerrandoCaja.value).isFalse()
        coVerify(exactly = 1) { shiftRepository.getCurrentShift(any()) }
    }

    @Test
    fun `closeShift no-op from non-active state`() = runTest {
        // Given: no active shift
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.NoActiveShift::class.java)

        // When
        viewModel.closeShift()
        advanceUntilIdle()

        // Then: closeShift was NOT called on repository
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any()) }
    }

    @Test
    fun `closeShift explains concurrent closing without retrying the post`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        coEvery { shiftRepository.closeShift("venue-123", "shift-123") } returns Result.Error(
            ApiException.HttpError(409, "Shift is currently being closed")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.closeShift()
        advanceUntilIdle()

        val error = viewModel.state.value as ShiftState.Error
        assertThat(error.message).isEqualTo(
            "La caja se está cerrando en otra terminal.\n\nEspera unos segundos y actualiza."
        )
        coVerify(exactly = 1) { shiftRepository.closeShift("venue-123", "shift-123") }
    }

    @Test
    fun `counted close forwards canonical amount and keeps exact zero result until acknowledged`() = runTest {
        val closedShift = testShift.copy(
            status = ShiftStatus.CLOSED,
            endTime = "2025-01-15T16:00:00Z",
            cashDeclared = BigDecimal("1300.00"),
            cashDifference = BigDecimal.ZERO,
            reconciliation = CashReconciliationResult(
                outcome = CashReconciliationOutcome.APPLIED,
                cashDeclared = BigDecimal("1300.00"),
                cashDifference = BigDecimal.ZERO
            )
        )
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift) andThen Result.Success(null)
        coEvery {
            shiftRepository.closeShift(
                "venue-123",
                "shift-123",
                CashReconciliationAction.COUNTED,
                BigDecimal("1300.00")
            )
        } returns Result.Success(closedShift)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.closeShift(CashReconciliationAction.COUNTED, BigDecimal("1300.00"))
        advanceUntilIdle()

        val closedState = viewModel.state.value as ShiftState.ShiftClosed
        assertThat(closedState.reconciliationAction).isEqualTo(CashReconciliationAction.COUNTED)
        assertThat(closedState.shift.cashDifference).isEqualTo(BigDecimal.ZERO)
        coVerify(exactly = 1) {
            shiftRepository.closeShift(
                "venue-123",
                "shift-123",
                CashReconciliationAction.COUNTED,
                BigDecimal("1300.00")
            )
        }

        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(closedState)

        viewModel.acknowledgeClosedShift()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.NoActiveShift::class.java)
    }

    @Test
    fun `only explicit reconciliation results require navigation acknowledgment`() {
        val legacyResult = ShiftState.ShiftClosed(
            shift = testShift.copy(status = ShiftStatus.CLOSED)
        )
        val countedResult = legacyResult.copy(
            reconciliationAction = CashReconciliationAction.COUNTED
        )
        val skippedResult = legacyResult.copy(
            reconciliationAction = CashReconciliationAction.SKIPPED
        )

        assertThat(requiresCashReconciliationAcknowledgement(legacyResult)).isFalse()
        assertThat(requiresCashReconciliationAcknowledgement(countedResult)).isTrue()
        assertThat(requiresCashReconciliationAcknowledgement(skippedResult)).isTrue()
        assertThat(requiresCashReconciliationAcknowledgement(ShiftState.Loading)).isFalse()
    }

    @Test
    fun `pull refresh stays available for legacy results but not unacknowledged reconciliation`() {
        val legacyResult = ShiftState.ShiftClosed(
            shift = testShift.copy(status = ShiftStatus.CLOSED)
        )
        val countedResult = legacyResult.copy(
            reconciliationAction = CashReconciliationAction.COUNTED
        )
        val skippedResult = legacyResult.copy(
            reconciliationAction = CashReconciliationAction.SKIPPED
        )

        assertThat(isShiftPullToRefreshEnabled(legacyResult)).isTrue()
        assertThat(isShiftPullToRefreshEnabled(ShiftState.NoActiveShift())).isTrue()
        assertThat(isShiftPullToRefreshEnabled(countedResult)).isFalse()
        assertThat(isShiftPullToRefreshEnabled(skippedResult)).isFalse()
        assertThat(isShiftPullToRefreshEnabled(ShiftState.Loading)).isFalse()
    }

    @Test
    fun `skipped close sends no amount and remains visible until acknowledged`() = runTest {
        val closedShift = testShift.copy(
            status = ShiftStatus.CLOSED,
            endTime = "2025-01-15T16:00:00Z",
            reconciliation = CashReconciliationResult(
                outcome = CashReconciliationOutcome.SKIPPED,
                cashDeclared = null,
                cashDifference = null
            )
        )
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift) andThen Result.Success(null)
        coEvery {
            shiftRepository.closeShift(
                "venue-123",
                "shift-123",
                CashReconciliationAction.SKIPPED,
                null
            )
        } returns Result.Success(closedShift)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.closeShift(CashReconciliationAction.SKIPPED)
        advanceUntilIdle()

        val closedState = viewModel.state.value as ShiftState.ShiftClosed
        assertThat(closedState.reconciliationAction).isEqualTo(CashReconciliationAction.SKIPPED)
        coVerify(exactly = 1) {
            shiftRepository.closeShift(
                "venue-123",
                "shift-123",
                CashReconciliationAction.SKIPPED,
                null
            )
        }

        fakeConnectionRestoredEvents.emit(
            ConnectionRestoredEvent(
                timestamp = "2026-08-08T08:00:00Z",
                attemptsBeforeReconnection = 1
            )
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(closedState)

        viewModel.acknowledgeClosedShift()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.NoActiveShift::class.java)
    }

    @Test
    fun `counted close preserves negative positive and null differences`() = runTest {
        val differences = listOf(BigDecimal("-25.50"), BigDecimal("18.75"), null)

        differences.forEach { difference ->
            clearMocks(shiftRepository, answers = false, recordedCalls = true)
            coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
            coEvery { shiftRepository.getShiftHistory(any(), any()) } returns Result.Success(emptyList())
            coEvery {
                shiftRepository.closeShift(
                    "venue-123",
                    "shift-123",
                    CashReconciliationAction.COUNTED,
                    BigDecimal("1274.50")
                )
            } returns Result.Success(
                testShift.copy(
                    status = ShiftStatus.CLOSED,
                    endTime = "2025-01-15T16:00:00Z",
                    cashDeclared = BigDecimal("1274.50"),
                    cashDifference = difference,
                    reconciliation = CashReconciliationResult(
                        outcome = if (difference == null) {
                            CashReconciliationOutcome.IGNORED_OVERFLOW
                        } else {
                            CashReconciliationOutcome.APPLIED
                        },
                        cashDeclared = BigDecimal("1274.50"),
                        cashDifference = difference
                    )
                )
            )

            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.closeShift(CashReconciliationAction.COUNTED, BigDecimal("1274.50"))
            advanceUntilIdle()

            val closedState = viewModel.state.value as ShiftState.ShiftClosed
            assertThat(closedState.shift.cashDifference).isEqualTo(difference)
        }
    }

    // ========================================
    // SETTINGS TESTS
    // ========================================

    @Test
    fun `isShiftSystemEnabled reflects settings`() = runTest {
        // Given
        every { secureStorage.isShiftSystemEnabled() } returns false

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isShiftSystemEnabled.value).isFalse()
    }

    @Test
    fun `cash reconciliation effective flag reflects secure server-owned setting`() = runTest {
        every { secureStorage.isCashReconciliationEnabled() } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.isCashReconciliationEnabled.value).isTrue()
    }

    // ========================================
    // INITIAL LOADING TESTS
    // ========================================

    @Test
    fun `isInitialLoading becomes false after load`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isInitialLoading.value).isFalse()
    }

    // ========================================
    // OFFLINE MODE TESTS
    // ========================================

    @Test
    fun `isOffline updates on connectivity change`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially online
        assertThat(viewModel.isOffline.value).isFalse()

        // When: network lost
        fakeNetworkStatus.emit(NetworkStatus.Unavailable)
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isOffline.value).isTrue()

        // When: network restored
        fakeNetworkStatus.emit(NetworkStatus.Available)
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isOffline.value).isFalse()
    }

    // ========================================
    // 💸 BARRERA DE REEMBOLSOS (Fase 2, Task 9)
    // ========================================

    private fun reembolso(key: String, permanent: Boolean = false) = QueuedRefund(
        idempotencyKey = key,
        venueId = "venue-123",
        staffId = "staff-123",
        processor = ProcessorType.BLUMON,
        originalPaymentId = "pay-orig",
        originalOrderId = null,
        amount = BigDecimal("50.00"),
        originalTotalAmount = BigDecimal("100.00"),
        tipRefundCents = null,
        isPartialRefund = true,
        refundReason = RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = "m1",
        blumonSerialNumber = "SER1",
        originalOperationNumber = 1,
        authorizationNumber = "502511",
        referenceNumber = "000000188231",
        maskedPan = null,
        cardBrand = null,
        entryMode = "CHIP",
        createdAt = 1_000L,
        permanent = permanent,
        lastError = if (permanent) "422: monto excede" else null,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.viewModelConTurnoActivo(): ShiftViewModel {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
        return viewModel
    }

    @Test
    fun `P1 closeShift se BLOQUEA con un reembolso sin registrar y NO toca el servidor`() = runTest {
        coEvery { refundQueueRepository.blockingForVenue("venue-123") } returns listOf(reembolso("k-1"))
        val viewModel = viewModelConTurnoActivo()

        viewModel.closeShift()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.CloseBlockedByRefunds::class.java)
        assertThat((state as ShiftState.CloseBlockedByRefunds).refunds.map { it.idempotencyKey }).containsExactly("k-1")
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si la cola de reembolsos no se puede leer, se BLOQUEA - nunca se cierra a ciegas`() = runTest {
        coEvery { refundQueueRepository.blockingForVenue("venue-123") } throws IllegalStateException("db cerrada")
        val viewModel = viewModelConTurnoActivo()

        viewModel.closeShift()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
        assertThat((state as ShiftState.Error).message).contains("devoluciones")
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
    }

    @Test
    fun `reconocer un rechazo PERMANENTE libera el cierre cuando ya no queda nada bloqueante`() = runTest {
        coEvery { refundQueueRepository.blockingForVenue("venue-123") } returnsMany listOf(
            listOf(reembolso("k-1", permanent = true)),
            emptyList(),
        )
        val viewModel = viewModelConTurnoActivo()
        viewModel.closeShift()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.CloseBlockedByRefunds::class.java)

        coEvery { refundQueueRepository.acknowledge("k-1", any()) } returns 1
        viewModel.acknowledgeRefund("k-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { refundQueueRepository.acknowledge("k-1", "staff-123") }
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
    }

    @Test
    fun `P1 un reembolso PENDIENTE no se puede reconocer - se registrara solo y no se le saca de la barrera`() = runTest {
        coEvery { refundQueueRepository.blockingForVenue("venue-123") } returns listOf(reembolso("k-1", permanent = false))
        val viewModel = viewModelConTurnoActivo()
        viewModel.closeShift()
        advanceUntilIdle()

        viewModel.acknowledgeRefund("k-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { refundQueueRepository.acknowledge(any(), any()) }
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.CloseBlockedByRefunds::class.java)
    }

    @Test
    fun `sin permiso de cerrar tampoco se puede reconocer un rechazo`() = runTest {
        coEvery { permissionsRepository.hasPermission("tpv-shifts:close") } returns false
        coEvery { refundQueueRepository.blockingForVenue("venue-123") } returns listOf(reembolso("k-1", permanent = true))
        val viewModel = viewModelConTurnoActivo()

        viewModel.acknowledgeRefund("k-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { refundQueueRepository.acknowledge(any(), any()) }
    }
    // ── Otra pantalla abre o cierra el turno (QA Nexgo N86, 7-sep-2026) ───────────────────
    // Cada pantalla tiene su propio ShiftViewModel: el de «Turnos de caja» abría la caja y el
    // de Inicio seguía en «Sin turno de caja» (bloqueando Cobrar) hasta reiniciar la app.

    @Test
    fun `cuando el repositorio avisa que el turno cambio, el ViewModel recarga el turno`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { shiftRepository.getCurrentShift("venue-123") }

        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        fakeShiftChanges.emit(Unit)
        advanceUntilIdle()

        coVerify(exactly = 2) { shiftRepository.getCurrentShift("venue-123") }
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
    }

    // ── Arranque SIN servidor (Nexgo N86, 23-sep-2026) ─────────────────────────────────────
    // Con la caja abierta en el servidor y guardada en el aparato, `force-stop` + reabrir sin
    // servidor dejaba el Inicio en «Sin turno de caja» con Pago rápido y Cobrar apagados («Abre
    // la caja primero»): la consulta fallaba por red y el ViewModel ignoraba la caché. El WiFi
    // seguía arriba, así que el ConnectivityObserver nunca dijo «sin red».

    private fun sinServidor() = Result.Error(
        ApiException.NetworkError(java.net.ConnectException("ECONNREFUSED (Connection refused)"))
    )

    @Test
    fun `P1 sin servidor al arrancar, el ultimo turno abierto guardado se muestra y deja vender`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns sinServidor()
        coEvery { cachedShiftDao.getCachedShift("venue-123") } returns
            com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(testShift, "venue-123")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.isOffline.value).isTrue()
        assertThat(viewModel.cachedShiftInfo.value?.isOpen).isTrue()
        assertThat(
            turnoPermiteVender(null, viewModel.isOffline.value, viewModel.cachedShiftInfo.value)
        ).isTrue()
    }

    @Test
    fun `P1 que el WiFi diga Disponible no borra el sin conexion si el servidor no contesto`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns sinServidor()
        coEvery { cachedShiftDao.getCachedShift("venue-123") } returns
            com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(testShift, "venue-123")
        val viewModel = createViewModel()
        advanceUntilIdle()

        fakeNetworkStatus.emit(NetworkStatus.Available)
        advanceUntilIdle()

        assertThat(viewModel.isOffline.value).isTrue()
        assertThat(viewModel.cachedShiftInfo.value?.isOpen).isTrue()
    }

    @Test
    fun `P1 sin servidor y SIN turno guardado, no se inventa un turno abierto`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns sinServidor()
        coEvery { cachedShiftDao.getCachedShift("venue-123") } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.isOffline.value).isTrue()
        assertThat(viewModel.cachedShiftInfo.value).isNull()
        assertThat(
            turnoPermiteVender(null, viewModel.isOffline.value, viewModel.cachedShiftInfo.value)
        ).isFalse()
    }

    @Test
    fun `pull to refresh sin servidor tambien cae al ultimo turno guardado`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        coEvery { cachedShiftDao.getCachedShift("venue-123") } returns
            com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(testShift, "venue-123")
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.isOffline.value).isFalse()

        coEvery { shiftRepository.getCurrentShift("venue-123") } returns sinServidor()
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.isOffline.value).isTrue()
        assertThat(viewModel.cachedShiftInfo.value?.isOpen).isTrue()
    }

    @Test
    fun `cuando el servidor vuelve a contestar se deja de usar el ultimo turno guardado`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns sinServidor()
        coEvery { cachedShiftDao.getCachedShift("venue-123") } returns
            com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(testShift, "venue-123")
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.isOffline.value).isTrue()

        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        viewModel.loadCurrentShift()
        advanceUntilIdle()

        assertThat(viewModel.isOffline.value).isFalse()
        assertThat(viewModel.cachedShiftInfo.value).isNull()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
    }

    @Test
    fun `un servidor que SI contesto con error no se toma como sin conexion`() = runTest {
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns
            Result.Error(ApiException.HttpError(403, "Forbidden"))
        coEvery { cachedShiftDao.getCachedShift("venue-123") } returns
            com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(testShift, "venue-123")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.isOffline.value).isFalse()
        assertThat(viewModel.cachedShiftInfo.value).isNull()
    }

    @Test
    fun `turnoPermiteVender - confirmado, ultimo conocido sin conexion, y nunca inventado`() {
        val abiertoGuardado = CachedShiftInfo(isOpen = true, staffName = "Maria", cachedMinutesAgo = 16)
        // Confirmado por el servidor.
        assertThat(turnoPermiteVender(testShift, sinConexion = false, ultimoConocido = null)).isTrue()
        // Sin conexión: vale el último turno ABIERTO que el aparato guardó.
        assertThat(turnoPermiteVender(null, sinConexion = true, ultimoConocido = abiertoGuardado)).isTrue()
        // Con conexión, la caché NO sustituye al servidor: si dice «sin turno», no hay turno.
        assertThat(turnoPermiteVender(null, sinConexion = false, ultimoConocido = abiertoGuardado)).isFalse()
        // Sin conexión y sin nada guardado: no se inventa.
        assertThat(turnoPermiteVender(null, sinConexion = true, ultimoConocido = null)).isFalse()
        assertThat(
            turnoPermiteVender(null, sinConexion = true, ultimoConocido = abiertoGuardado.copy(isOpen = false))
        ).isFalse()
    }
    @Test fun `ventas pendientes muestran total y pagina sin llamar al cierre`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        val page = PaginaPagosDelCierre(53, listOf(paymentForClose(1, "PENDING")), 0)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returns page
        val vm = createViewModel()
        vm.closeShift(); advanceUntilIdle()
        assertThat((vm.state.value as ShiftState.CloseBlockedByPayments).page).isEqualTo(page)
        vm.acknowledgePayment(1); advanceUntilIdle()
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
        coVerify(exactly = 0) { shiftRepository.reconocerPago(any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `error al verificar pagos conserva bloqueo sin inventar total cero`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } throws IllegalStateException("disk error")
        val vm = createViewModel()
        vm.closeShift(); advanceUntilIdle()
        val blocked = vm.state.value as ShiftState.CloseBlockedByPayments
        assertThat(blocked.page).isNull()
        assertThat(blocked.error).isNotEmpty()
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `error de pagina conserva total anterior y el cierre bloqueado`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        val page = PaginaPagosDelCierre(53, listOf(paymentForClose(1, "FAILED")), 0)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returns page
        val vm = createViewModel(); vm.closeShift(); advanceUntilIdle()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), 20) } throws IllegalStateException("disk error")
        vm.loadPaymentPage(20); advanceUntilIdle()
        val blocked = vm.state.value as ShiftState.CloseBlockedByPayments
        assertThat(blocked.page).isEqualTo(page)
        assertThat(blocked.error).isNotEmpty()
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `barrera tardia tras recargar otro turno no reemplaza la nueva Caja`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        val read = CompletableDeferred<PaginaPagosDelCierre>()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } coAnswers { read.await() }
        val vm = createViewModel(); vm.closeShift()
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift.copy(id = "new-shift"))
        vm.loadCurrentShift(); advanceUntilIdle()
        read.complete(PaginaPagosDelCierre(1, listOf(paymentForClose(1, "FAILED")), 0)); advanceUntilIdle()
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.id).isEqualTo("new-shift")
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `pagina tardia tras cambiar sucursal se descarta`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), 0) } returns PaginaPagosDelCierre(53, listOf(paymentForClose(1, "FAILED")), 0)
        val vm = createViewModel(); vm.closeShift(); advanceUntilIdle()
        val read = CompletableDeferred<PaginaPagosDelCierre>()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), 20) } coAnswers { read.await() }
        vm.loadPaymentPage(20)
        every { secureStorage.getVenueId() } returns "new-venue"
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift.copy(id = "new-shift", venueId = "new-venue"))
        vm.loadCurrentShift(); advanceUntilIdle()
        read.complete(PaginaPagosDelCierre(53, listOf(paymentForClose(2, "FAILED")), 20)); advanceUntilIdle()
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.venueId).isEqualTo("new-venue")
        vm.viewModelScope.cancel()
    }

    @Test fun `actor que cambia durante lectura no recibe una barrera vieja ni permite cerrar`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        val read = CompletableDeferred<PaginaPagosDelCierre>()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } coAnswers { read.await() }
        val vm = createViewModel(); vm.closeShift()
        every { secureStorage.getStaffId() } returns "new-actor"
        read.complete(PaginaPagosDelCierre(1, listOf(paymentForClose(1, "FAILED")), 0)); advanceUntilIdle()
        assertThat((vm.state.value as ShiftState.Error).message).contains("sesión cambió")
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `sin actor actual Revisado mantiene aviso visible y no llama al repositorio`() = runTest {
        every { secureStorage.getStaffId() } returns null
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returns PaginaPagosDelCierre(1, listOf(paymentForClose(1, "FAILED")), 0)
        val vm = createViewModel(); vm.closeShift(); advanceUntilIdle(); vm.acknowledgePayment(1); advanceUntilIdle()
        assertThat((vm.state.value as ShiftState.CloseBlockedByPayments).error).contains("Inicia sesión")
        coVerify(exactly = 0) { shiftRepository.reconocerPago(any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun `cero filas reconocidas no anuncia exito ni libera el cierre`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returns PaginaPagosDelCierre(1, listOf(paymentForClose(1, "FAILED")), 0)
        coEvery { shiftRepository.reconocerPago(any(), any(), any(), any(), any()) } returns 0
        val vm = createViewModel(); vm.closeShift(); advanceUntilIdle(); vm.acknowledgePayment(1); advanceUntilIdle()
        val blocked = vm.state.value as ShiftState.CloseBlockedByPayments
        assertThat(blocked.page!!.total).isEqualTo(1)
        assertThat(blocked.error).contains("No se pudo marcar")
        vm.viewModelScope.cancel()
    }

    @Test fun `Revisado de FAILED y SUCCESS con diferencia usa actor actual y permite volver al cierre`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        for ((i, status) in listOf("FAILED", "SUCCESS").withIndex()) {
            val id = i.toLong() + 1
            coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returnsMany listOf(
                PaginaPagosDelCierre(1, listOf(paymentForClose(id, status).copy(requiereRevision = true)), 0),
                PaginaPagosDelCierre(0, emptyList(), 0))
            coEvery { shiftRepository.reconocerPago(id, "venue-123", "staff-123", any(), any()) } returns 1
            val vm = createViewModel(); vm.closeShift(); advanceUntilIdle(); vm.acknowledgePayment(id); advanceUntilIdle()
            assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
            coVerify(exactly = 1) { shiftRepository.reconocerPago(id, "venue-123", "staff-123", any(), any()) }
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `cancelacion de lectura y respuesta de pagina tras salir no pintan Error tardio`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } returns PaginaPagosDelCierre(1, listOf(paymentForClose(1, "FAILED")), 0)
        val vm = createViewModel(); vm.closeShift(); advanceUntilIdle()
        val read = CompletableDeferred<PaginaPagosDelCierre>()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), any()) } coAnswers { read.await() }
        vm.loadPaymentPage(0); vm.onShiftScreenLeft(); vm.onShiftScreenEntered(); advanceUntilIdle()
        read.completeExceptionally(CancellationException("screen left")); advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
        vm.viewModelScope.cancel()
    }

    @Test fun `una pagina en carga no permite iniciar una segunda y publica su resultado`() = runTest {
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(testShift)
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), 0) } returns PaginaPagosDelCierre(53, listOf(paymentForClose(1, "FAILED")), 0)
        val vm = createViewModel(); vm.closeShift(); advanceUntilIdle()
        val read = CompletableDeferred<PaginaPagosDelCierre>()
        coEvery { shiftRepository.pagosQueBloqueanCierre(any(), 20) } coAnswers { read.await() }
        vm.loadPaymentPage(20); vm.loadPaymentPage(40)
        coVerify(exactly = 0) { shiftRepository.pagosQueBloqueanCierre(any(), 40) }
        read.complete(PaginaPagosDelCierre(53, listOf(paymentForClose(2, "FAILED")), 20)); advanceUntilIdle()
        val blocked = vm.state.value as ShiftState.CloseBlockedByPayments
        assertThat(blocked.busy).isFalse()
        assertThat(blocked.page!!.offset).isEqualTo(20)
        vm.viewModelScope.cancel()
    }

    private fun paymentForClose(id: Long, status: String) = PendingPaymentEntity(
        id = id, referenceNumber = "reference-$id", venueId = "venue-123", staffId = "original-seller", amount = "150.25", tip = "0.00",
        rating = null, merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null, entryMode = "CASH",
        isInternational = false, authorizationNumber = "EFECTIVO", syncStatus = status, createdAt = 100L)


    @Test fun `R12 cierre propio con alias exacto pulso y history lenta conserva su resultado`() = runTest {
        checkCloseResultPublication(null, beforeHistory = false, mappedAlias = true)
    }

    @Test fun `R12 cambio identidad antes history no inicia lectura ni publica cierre A`() = runTest {
        for (change in listOf("actor", "venue", "token")) {
            setup(); checkCloseResultPublication(change, beforeHistory = true)
        }
    }

    @Test fun `R12 cambio identidad durante history no publica cierre A`() = runTest {
        for (change in listOf("actor", "venue", "token")) {
            setup(); checkCloseResultPublication(change, beforeHistory = false)
        }
    }

    // R21: T3 publishes the durable CLOSED pulse immediately. The guarded suspension is now
    // its exact Room snapshot read after CLOSE, not remote History. This is a unit double;
    // Task7CajaRoomUiTest separately exercises real Room/commit/recreation.
    private suspend fun kotlinx.coroutines.test.TestScope.checkCloseResultPublication(change: String?, beforeHistory: Boolean, mappedAlias: Boolean = false) {
        every { secureStorage.getToken() } returns "original-token"
        val localId = "c000000000000000000000051"
        val source = testShift.copy(id = if (mappedAlias) localId else testShift.id,
            localShiftSessionId = localId, financialTotalsKnown = false)
        val closed = source.copy(id = if (mappedAlias) "c000000000000000000000052" else source.id, status = ShiftStatus.CLOSED)
        val openRow = com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity(
            localId, source.venueId, "LOCAL", serverShiftId = if (mappedAlias) null else source.id,
            openingOperationId = localId, openingStatus = if (mappedAlias) "PENDING" else "CONFIRMED",
            originalOpenedAt = source.startTime, startingCash = "500.00", openedByStaffId = source.staffId,
            localStatus = "OPEN", createdAt = 1, updatedAt = 1)
        val closeKey = "c000000000000000000000053"
        val closedAt = "2026-10-28T11:00:00Z"
        val closedRow = openRow.copy(serverShiftId = closed.id, openingStatus = "CONFIRMED", localStatus = "CLOSED", openSlot = null,
            closeOperationId = closeKey, cashReconciliationAction = "COUNTED", countedCash = "61.25", closedAtLocal = closedAt,
            closedByStaffId = source.staffId, closeStatus = "PENDING", closeRequestJson = com.google.gson.Gson().toJson(
                com.jaac.avoqado_tpv.features.shift.data.dto.LocalCloseShiftRequest(source.venueId, closeKey, closedAt,
                    source.staffId, localId, CashReconciliationAction.COUNTED, "61.25")))
        fun snapshot(shift: Shift, row: com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity) =
            com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(shift, row,
                com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary(row.startingCash?.toBigDecimal(),
                    row.countedCash?.toBigDecimal(), row.localStatus, row.openingStatus, row.closeStatus, row.adoptionNotice))
        var active = source
        var current = snapshot(source, openRow)
        var historyCalls = 0
        var ownSnapshotReads = 0
        val releaseSnapshot = CompletableDeferred<Unit>()
        val snapshotEntered = CompletableDeferred<Unit>()
        val pulseRead = CompletableDeferred<Unit>()
        var pulseInFlight = false
        var closingResult = false
        fun changeIdentity() { when (change) {
            "actor" -> every { secureStorage.getStaffId() } returns "other-staff"
            "venue" -> every { secureStorage.getVenueId() } returns "other-venue"
            "token" -> every { secureStorage.getToken() } returns "other-token"
        } }
        coEvery { shiftRepository.leerCajaLocal(any()) } coAnswers {
            val captured = current
            if (closingResult && !pulseInFlight && captured.row.localSessionId == localId) {
                ownSnapshotReads++; snapshotEntered.complete(Unit); releaseSnapshot.await()
            }
            captured
        }
        coEvery { shiftRepository.getCurrentShift(any()) } answers { Result.Success(active) }
        coEvery { shiftRepository.getShiftHistory(any(), any()) } coAnswers {
            historyCalls++
            if (pulseInFlight) pulseRead.complete(Unit)
            Result.Success(emptyList())
        }
        coEvery { shiftRepository.closeShift(any(), any(), any(), any()) } coAnswers {
            active = closed; current = snapshot(closed, closedRow)
            pulseInFlight = true
            fakeShiftChanges.emit(Unit)
            pulseRead.await() // The legitimate durable pulse has completed before local CLOSE returns.
            pulseInFlight = false; closingResult = true
            if (beforeHistory) changeIdentity()
            Result.Success(closed)
        }
        val vm = createViewModel()
        try {
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
            vm.closeShift(CashReconciliationAction.COUNTED, BigDecimal("61.25")); runCurrent()
            assertThat(historyCalls).isEqualTo(2) // Init + own pulse; local Success never waits on History.
            assertThat(ownSnapshotReads).isEqualTo(if (beforeHistory) 0 else 1)
            if (!beforeHistory) { assertThat(snapshotEntered.isCompleted).isTrue(); changeIdentity() }
            if (change != null) {
                // Anchor a genuinely new B, never a fake reopened A, before releasing A's old read.
                val bId = "c000000000000000000000054"
                val bVenue = secureStorage.getVenueId()!!
                active = source.copy(id = "remote-B", venueId = bVenue, localShiftSessionId = bId)
                current = snapshot(active, openRow.copy(localSessionId = bId, venueId = bVenue,
                    serverShiftId = "remote-B", openingOperationId = bId, openingStatus = "CONFIRMED"))
                vm.loadCurrentShift(); runCurrent()
                assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
                assertThat((vm.state.value as ShiftState.ShiftActive).shift.localShiftSessionId).isEqualTo(bId)
            }
            val before = vm.state.value
            val factsBefore = vm.localCaja.value
            releaseSnapshot.complete(Unit); runCurrent()
            if (change == null) {
                assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftClosed::class.java)
                assertThat((vm.state.value as ShiftState.ShiftClosed).shift).isEqualTo(closed)
                assertThat(vm.localCaja.value?.summary?.countedCash).isEqualTo(BigDecimal("61.25"))
            } else {
                assertThat(vm.state.value).isSameInstanceAs(before)
                assertThat(vm.state.value).isNotInstanceOf(ShiftState.ShiftClosed::class.java)
                assertThat(vm.localCaja.value).isSameInstanceAs(factsBefore)
            }
            coVerify(exactly = 1) { shiftRepository.closeShift(any(), any(), any(), any()) }
        } finally { releaseSnapshot.complete(Unit); vm.viewModelScope.cancel() }
    }

    @Test fun `R12 refresh demorado de resultado A no vuelve a leer despues de mostrar B`() = runTest {
        every { secureStorage.getToken() } returns "original-token"
        var active = testShift
        var reads = 0
        val pulseRead = CompletableDeferred<Unit>()
        var pulseInFlight = false
        coEvery { shiftRepository.getShiftHistory(any(), any()) } coAnswers {
            if (pulseInFlight) pulseRead.complete(Unit)
            Result.Success(emptyList())
        }
        coEvery { shiftRepository.getCurrentShift(any()) } answers { reads++; Result.Success(active) }
        coEvery { shiftRepository.closeShift(any(), any(), any(), any()) } coAnswers {
            pulseInFlight = true
            fakeShiftChanges.emit(Unit)
            pulseRead.await()
            pulseInFlight = false
            Result.Success(testShift.copy(status = ShiftStatus.CLOSED, financialTotalsKnown = false))
        }
        val vm = createViewModel()
        try {
            runCurrent(); vm.closeShift(); runCurrent()
            assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftClosed::class.java)
            active = testShift.copy(id = "another-shift")
            vm.loadCurrentShift(); runCurrent()
            assertThat((vm.state.value as ShiftState.ShiftActive).shift.id).isEqualTo("another-shift")
            val before = reads
            advanceTimeBy(2000); runCurrent()
            assertThat(reads).isEqualTo(before)
            assertThat((vm.state.value as ShiftState.ShiftActive).shift.id).isEqualTo("another-shift")
        } finally { vm.viewModelScope.cancel() }
    }

}
