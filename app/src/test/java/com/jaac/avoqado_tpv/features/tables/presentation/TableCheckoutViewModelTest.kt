package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableDiscount
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableServiceCharge
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * `TableCheckoutScreen` — la costura de pago (Plan C, Task 8, la tarea más
 * delicada del plan: conecta el módulo nuevo a dinero real). Literal del plan
 * adaptado a la arquitectura REAL establecida por Tasks 3-7: se mockea
 * [TablesRepository] (dueño de `payCash`/`clearTable`/`getOrder`), no sus
 * dependencias internas (`SyncOutbox`/`TablesApiService` — esas tienen su
 * propia cobertura en `TablesRepositoryPayCashTest.kt`).
 *
 * Cubre lo que pidió el enunciado explícitamente:
 * - un cargo con TARJETA apunta a la orden existente y NUNCA la crea de nuevo
 * - un cargo con TARJETA NUNCA se encola offline (es un ESTADO, no un error)
 * - EFECTIVO sí funciona offline
 * - dinero en BigDecimal exacto donde Double divergiría
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableCheckoutViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val repository = mockk<TablesRepository>()
    private val tableSyncCoordinator = mockk<TableSyncCoordinator>()
    private val networkMonitor = mockk<NetworkMonitor>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private lateinit var tableSession: TableSession

    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, tableSyncCoordinator, networkMonitor, deviceInfoManager)

        tableSession = TableSession()

        every { deviceInfoManager.getVenueId() } returns venueId
        every { networkMonitor.isConnected() } returns true
        every { networkMonitor.networkStateFlow } returns emptyFlow()
        coEvery { tableSyncCoordinator.replay(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TableCheckoutViewModel = TableCheckoutViewModel(
        repository = repository,
        tableSession = tableSession,
        tableSyncCoordinator = tableSyncCoordinator,
        networkMonitor = networkMonitor,
        deviceInfoManager = deviceInfoManager,
    )

    // region — el cobro con TARJETA apunta a la orden EXISTENTE, nunca crea una nueva

    @Test
    fun el_cobro_apunta_a_la_orden_de_la_mesa_y_NO_crea_una_nueva() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-456"))
        coEvery { repository.getOrder(venueId, "orden-real-456") } returns Result.success(OrderDetail(id = "orden-real-456"))
        val viewModel = createViewModel()

        val payload = viewModel.buildPaymentPayload(BigDecimal("150.00"))

        assertThat(payload.orderId).isEqualTo("orden-real-456")
        assertThat(payload.createsNewOrder).isFalse()
    }

    // endregion

    // region — sin red: TARJETA deshabilitada CON la razón — un ESTADO, nunca un error

    @Test
    fun sin_red_el_boton_de_tarjeta_queda_deshabilitado_con_la_razon() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()

        viewModel.onConnectivityChanged(online = false)

        val state = viewModel.state.value
        assertThat(state.cardPaymentEnabled).isFalse()
        assertThat(state.cardPaymentDisabledReason).isEqualTo("Necesita conexión")
        assertThat(state.error).isNull()
        assertThat(state.checkStaysOpen).isTrue()
    }

    /**
     * Garantía arquitectónica pedida explícitamente: "a card charge is never
     * queued offline". [TableCheckoutViewModel.buildPaymentPayload] es una
     * función PURA — nunca toca [TablesRepository.payCash] (el único punto
     * por el que algo se encola en este ViewModel). Sin red o con red, el
     * camino de tarjeta jamás pasa por el outbox.
     */
    @Test
    fun buildPaymentPayload_nunca_llama_al_camino_de_efectivo_o_encola_nada() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        viewModel.onConnectivityChanged(online = false)

        viewModel.buildPaymentPayload(BigDecimal("150.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.clearTable(any(), any()) }
    }

    // endregion

    // region — sin red: EFECTIVO SÍ funciona

    @Test
    fun sin_red_el_cobro_en_efectivo_si_funciona() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        viewModel.onConnectivityChanged(online = false)
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        // El monto paga el restante COMPLETO — onPaymentCompleted libera la
        // mesa sola, así que releaseTable() sí llama clearTable.
        coEvery { repository.clearTable(venueId, "mesa-1") } returns Result.success(Unit)

        viewModel.payCash(BigDecimal("150.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any()) }
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.notice).contains("Sin conexión")
    }

    @Test
    fun un_cobro_en_efectivo_rechazado_marca_error_y_NO_mueve_el_restante() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException("El cobro fue rechazado", "INVALID_PAYLOAD"))

        viewModel.payCash(BigDecimal("150.00"))

        assertThat(viewModel.state.value.error).isEqualTo("El cobro fue rechazado")
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
        assertThat(viewModel.state.value.tableReleased).isFalse()
        coVerify(exactly = 0) { repository.clearTable(any(), any()) }
    }

    // endregion

    // region — pago parcial regresa con el restante; llegar a cero libera la mesa sola

    @Test
    fun un_pago_parcial_regresa_al_cobro_con_el_restante() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal("200.00")))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("120.00"))

        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("80.00"))
        assertThat(viewModel.state.value.tableReleased).isFalse()
        coVerify(exactly = 0) { repository.clearTable(any(), any()) }
    }

    @Test
    fun al_llegar_a_cero_la_mesa_se_libera_sola() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal("200.00")))
        coEvery { repository.clearTable(venueId, "mesa-1") } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("200.00"))

        // BigDecimal("200.00") - BigDecimal("200.00") = BigDecimal("0.00") —
        // NO es .equals() a BigDecimal.ZERO (escala 0 vs 2); lo que importa
        // para "se liberó la mesa" es compareTo == 0, no la escala exacta.
        assertThat(viewModel.state.value.remaining.compareTo(BigDecimal.ZERO)).isEqualTo(0)
        assertThat(viewModel.state.value.tableReleased).isTrue()
        coVerify(exactly = 1) { repository.clearTable(venueId, "mesa-1") }
    }

    @Test
    fun un_pago_de_MAS_del_restante_nunca_deja_el_restante_en_negativo() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("50.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("50.00"), amountLeft = BigDecimal("50.00")))
        coEvery { repository.clearTable(venueId, "mesa-1") } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("60.00"))

        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal.ZERO)
        assertThat(viewModel.state.value.tableReleased).isTrue()
    }

    // endregion

    // region — dinero: BigDecimal exacto donde Double divergiría

    @Test
    fun chargeableAmount_por_articulo_suma_BigDecimal_exacto_donde_Double_divergiria() = runTest {
        // 0.10 + 0.20 + 0.05 = 0.35 exacto en BigDecimal. En Double da
        // 0.35000000000000003 (verificado — el mismo caso ya probado en
        // PendingRoundCartTest de la Task 7).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("0.35")))
        val check = OrderDetail(
            id = "orden-1",
            total = BigDecimal("0.35"),
            amountLeft = BigDecimal("0.35"),
            items = listOf(
                OrderDetailItem(id = "i1", total = BigDecimal("0.10")),
                OrderDetailItem(id = "i2", total = BigDecimal("0.20")),
                OrderDetailItem(id = "i3", total = BigDecimal("0.05")),
            ),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(check)
        val viewModel = createViewModel()
        viewModel.selectSplitType(CheckoutSplitType.PERPRODUCT)
        viewModel.toggleItemSelected("i1")
        viewModel.toggleItemSelected("i2")
        viewModel.toggleItemSelected("i3")

        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("0.35"))
    }

    /**
     * "Split is all-or-nothing" para la selección de artículos: deseleccionar
     * UNO cambia el monto exacto de lo que se cobra — nunca se queda un
     * residuo fantasma de una línea que ya no está seleccionada.
     */
    @Test
    fun chargeableAmount_por_articulo_al_deseleccionar_uno_recalcula_exacto() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("0.35")))
        val check = OrderDetail(
            id = "orden-1",
            total = BigDecimal("0.35"),
            amountLeft = BigDecimal("0.35"),
            items = listOf(
                OrderDetailItem(id = "i1", total = BigDecimal("0.10")),
                OrderDetailItem(id = "i2", total = BigDecimal("0.20")),
                OrderDetailItem(id = "i3", total = BigDecimal("0.05")),
            ),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(check)
        val viewModel = createViewModel()
        viewModel.selectSplitType(CheckoutSplitType.PERPRODUCT)
        viewModel.toggleItemSelected("i1")
        viewModel.toggleItemSelected("i2")
        viewModel.toggleItemSelected("i3")
        viewModel.toggleItemSelected("i2") // deselecciona i2

        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("0.15"))
    }

    @Test
    fun chargeableAmount_nunca_excede_el_restante_sin_importar_el_monto_tecleado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        val viewModel = createViewModel()
        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        viewModel.setCustomAmountInput("999.00")

        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("100.00"))
    }

    // endregion

    // region — regresión: offline-first de lectura (patrón ya establecido en Tasks 6/7)

    @Test
    fun un_refresco_fallido_no_borra_el_cheque_ya_cargado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("50.00"), amountLeft = BigDecimal("50.00")))
        val viewModel = createViewModel()
        assertThat(viewModel.state.value.check).isNotNull()

        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(IOException("sin red"))
        viewModel.loadCheck()

        assertThat(viewModel.state.value.check).isNotNull()
        assertThat(viewModel.state.value.errorMessage).isNull()
    }

    // endregion

    // region — sesión provisional: EFECTIVO puede cobrar (localOrderId), TARJETA no

    @Test
    fun sesion_provisional_deshabilita_tarjeta_pero_efectivo_sigue_disponible() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-uuid-123")
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo("La mesa aún no se sincroniza")
        coVerify(exactly = 0) { repository.getOrder(any(), any()) } // nada que cargar todavía
    }

    // endregion

    // region — Fase 1: APPLY_DISCOUNT

    @Test
    fun loadAvailableDiscounts_puebla_la_lista_desde_el_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        val discounts = listOf(
            AvailableDiscount(id = "d1", name = "Cliente frecuente", type = "PERCENTAGE", value = BigDecimal("10"), requiresApproval = false, estimatedSavings = BigDecimal("45.00")),
        )
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } returns Result.success(discounts)

        viewModel.loadAvailableDiscounts()

        assertThat(viewModel.availableDiscounts.value).isEqualTo(discounts)
        assertThat(viewModel.isLoadingDiscounts.value).isFalse()
    }

    @Test
    fun applyDiscount_encolado_sin_red_se_ve_como_pendiente_NUNCA_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.applyDiscount(venueId, "orden-1", "d1") } returns
            Result.success(TablesRepository.DiscountOutcome(queued = true, amount = null, newOrderTotal = null))
        val viewModel = createViewModel()

        viewModel.applyDiscount("d1")

        assertThat(viewModel.state.value.notice).contains("Sin conexión")
        assertThat(viewModel.state.value.errorMessage).isNull()
        coVerify(exactly = 1) { repository.applyDiscount(venueId, "orden-1", "d1") }
    }

    @Test
    fun applyDiscount_rechazado_marca_errorMessage() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.applyDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 400, message = "Ese descuento ya está aplicado a la cuenta"))
        val viewModel = createViewModel()

        viewModel.applyDiscount("d1")

        assertThat(viewModel.state.value.errorMessage).contains("ya está aplicado")
    }

    // endregion

    // region — removeDiscount: paridad Android 2026-08-06 (paridad-android-tpv.md,
    // Hallazgo #4). SIEMPRE online — a diferencia de applyDiscount arriba,
    // aquí NUNCA hay una rama "queued".

    @Test
    fun removeDiscount_exitoso_avisa_y_recarga_el_cheque() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.success(TablesRepository.DiscountOutcome(queued = false, amount = BigDecimal("10.00"), newOrderTotal = BigDecimal("100.00")))
        val viewModel = createViewModel()

        viewModel.removeDiscount("d1")

        assertThat(viewModel.state.value.notice).isEqualTo("Descuento quitado")
        assertThat(viewModel.state.value.errorMessage).isNull()
        coVerify(exactly = 1) { repository.removeDiscount(venueId, "orden-1", "d1") }
        // "Recarga el cheque" = un segundo getOrder tras el de loadCheck() inicial.
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
    }

    @Test
    fun removeDiscount_sin_conexion_muestra_el_mensaje_explicito_NUNCA_generico() = runTest {
        // Regla dura del gap: quitar un descuento NUNCA se encola — un
        // fallo de red debe decir "necesita conexión", no un genérico que
        // sugiera que se guardó para después (no se guardó nada).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(TablesRepository.RemoveDiscountRequiresConnectionException())
        val viewModel = createViewModel()

        viewModel.removeDiscount("d1")

        assertThat(viewModel.state.value.errorMessage).contains("necesita conexión")
        assertThat(viewModel.state.value.notice).isNull()
    }

    @Test
    fun removeDiscount_rechazado_marca_errorMessage() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 400, message = "No se puede quitar un descuento de una orden pagada"))
        val viewModel = createViewModel()

        viewModel.removeDiscount("d1")

        assertThat(viewModel.state.value.errorMessage).contains("orden pagada")
    }

    // endregion

    // region — Fase 3: APPLY_SERVICE_CHARGE

    @Test
    fun loadAvailableServiceCharges_puebla_la_lista_desde_el_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        val charges = listOf(
            AvailableServiceCharge(id = "sc1", name = "Propina automática", type = "PERCENTAGE", value = BigDecimal("15"), taxable = true, autoApplyMinCovers = 6),
        )
        coEvery { repository.getAvailableServiceCharges(venueId) } returns Result.success(charges)

        viewModel.loadAvailableServiceCharges()

        assertThat(viewModel.availableServiceCharges.value).isEqualTo(charges)
        assertThat(viewModel.isLoadingServiceCharges.value).isFalse()
    }

    @Test
    fun applyServiceCharge_encolado_sin_red_se_ve_como_pendiente_NUNCA_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.applyServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.success(TablesRepository.ServiceChargeOutcome(queued = true, total = null, serviceChargeAmount = null))
        val viewModel = createViewModel()

        viewModel.applyServiceCharge("sc1")

        assertThat(viewModel.state.value.notice).contains("Sin conexión")
        assertThat(viewModel.state.value.errorMessage).isNull()
        coVerify(exactly = 1) { repository.applyServiceCharge(venueId, "orden-1", "sc1") }
    }

    @Test
    fun applyServiceCharge_exitoso_online_recarga_la_cuenta() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.applyServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.success(TablesRepository.ServiceChargeOutcome(queued = false, total = BigDecimal("115.00"), serviceChargeAmount = BigDecimal("15.00")))
        val viewModel = createViewModel()

        viewModel.applyServiceCharge("sc1")

        assertThat(viewModel.state.value.notice).isEqualTo("Cargo por servicio aplicado")
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") } // carga inicial + recarga tras aplicar
    }

    @Test
    fun applyServiceCharge_rechazado_por_duplicado_marca_errorMessage() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        coEvery { repository.applyServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 400, message = "Ese cobro ya está aplicado a la cuenta"))
        val viewModel = createViewModel()

        viewModel.applyServiceCharge("sc1")

        assertThat(viewModel.state.value.errorMessage).contains("ya está aplicado")
    }

    // endregion

    // region — removeServiceCharge: paridad Android 2026-08-06 (paridad-android-tpv.md,
    // Hallazgo #4, último hueco cerrado por avoqado-server commit a0470a74).
    // SIEMPRE online — a diferencia de applyServiceCharge arriba, aquí NUNCA
    // hay una rama "queued".

    @Test
    fun removeServiceCharge_exitoso_avisa_y_recarga_el_cheque() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("115.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("115.00"), amountLeft = BigDecimal("115.00")))
        coEvery { repository.removeServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.success(TablesRepository.ServiceChargeOutcome(queued = false, total = BigDecimal("100.00"), serviceChargeAmount = BigDecimal.ZERO))
        val viewModel = createViewModel()

        viewModel.removeServiceCharge("sc1")

        assertThat(viewModel.state.value.notice).isEqualTo("Cargo por servicio quitado")
        assertThat(viewModel.state.value.errorMessage).isNull()
        coVerify(exactly = 1) { repository.removeServiceCharge(venueId, "orden-1", "sc1") }
        // "Recarga el cheque" = un segundo getOrder tras el de loadCheck() inicial —
        // así es como los totales/restante en pantalla reflejan el cargo quitado.
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
    }

    @Test
    fun removeServiceCharge_sin_conexion_muestra_el_mensaje_explicito_NUNCA_generico() = runTest {
        // Regla dura del gap: quitar un cargo por servicio NUNCA se encola —
        // un fallo de red debe decir "necesita conexión", no un genérico que
        // sugiera que se guardó para después (no se guardó nada).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("115.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("115.00"), amountLeft = BigDecimal("115.00")))
        coEvery { repository.removeServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.failure(TablesRepository.RemoveServiceChargeRequiresConnectionException())
        val viewModel = createViewModel()

        viewModel.removeServiceCharge("sc1")

        assertThat(viewModel.state.value.errorMessage).contains("necesita conexión")
        assertThat(viewModel.state.value.notice).isNull()
    }

    @Test
    fun removeServiceCharge_rechazado_marca_errorMessage() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("115.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("115.00"), amountLeft = BigDecimal("115.00")))
        coEvery { repository.removeServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 400, message = "No se puede modificar una orden ya pagada"))
        val viewModel = createViewModel()

        viewModel.removeServiceCharge("sc1")

        assertThat(viewModel.state.value.errorMessage).contains("orden ya pagada")
    }

    // endregion
}
