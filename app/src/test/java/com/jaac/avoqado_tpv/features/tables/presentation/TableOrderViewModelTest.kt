package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.ActiveStaffMember
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.OpenCheckSummary
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderStaffSummary
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * `TableOrderScreen` — "Enviar ronda" (Plan C, Task 7). Literal del plan
 * adaptado a la arquitectura REAL ya establecida por Tasks 3-6: el plan
 * mockeaba `api`/`outbox` directo como si el ViewModel los llamara — en este
 * repo esa responsabilidad YA vive en [TablesRepository.addItems] (P1
 * write-ahead, ver su KDoc), así que aquí se mockea [TablesRepository], no
 * sus dependencias internas. Misma cobertura de comportamiento, límite
 * correcto.
 *
 * Cubre lo que pidió el enunciado: una ronda encolada sin red se ve como
 * pendiente (nunca error), propiedad de mesa bloquea a quien no es dueño, y
 * (en [com.jaac.avoqado_tpv.features.tables.data.PendingRoundCartTest]) la
 * aritmética de dinero es `BigDecimal` exacto donde `Double` divergiría.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val repository = mockk<TablesRepository>()
    private val tableSyncCoordinator = mockk<TableSyncCoordinator>()
    private val syncOutbox = mockk<SyncOutbox>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private val secureStorage = mockk<SecureStorage>()
    private val networkMonitor = mockk<NetworkMonitor>()
    private lateinit var tableSession: TableSession
    private lateinit var pendingCart: PendingRoundCart
    private lateinit var ownershipFlow: MutableStateFlow<TablesRepository.TableOwnership>

    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, tableSyncCoordinator, syncOutbox, deviceInfoManager, secureStorage, networkMonitor)

        tableSession = TableSession()
        pendingCart = PendingRoundCart()
        ownershipFlow = MutableStateFlow(TablesRepository.TableOwnership())

        every { deviceInfoManager.getVenueId() } returns venueId
        every { repository.ownership } returns ownershipFlow
        coEvery { tableSyncCoordinator.replay(any()) } returns Unit
        coEvery { syncOutbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
        every { secureStorage.getStaffId() } returns "staff-1"
        every { networkMonitor.isConnected() } returns true
        every { networkMonitor.networkStateFlow } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TableOrderViewModel = TableOrderViewModel(
        repository = repository,
        tableSession = tableSession,
        pendingCart = pendingCart,
        tableSyncCoordinator = tableSyncCoordinator,
        syncOutbox = syncOutbox,
        deviceInfoManager = deviceInfoManager,
        secureStorage = secureStorage,
        networkMonitor = networkMonitor,
    )

    // region — enviar ronda sin red: se ve como pendiente, NUNCA error

    @Test
    fun enviar_ronda_sin_red_encola_ADD_ITEMS_y_la_ronda_se_ve_como_pendiente() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 3))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        // TablesRepository.addItems YA encola internamente cuando no hay red
        // (P1 write-ahead) y regresa éxito con el OrderDetail OPTIMISTA — este
        // es el contrato documentado que TableOrderViewModel usa para
        // distinguir "se envió online" de "se encoló": TablesRepository.wasQueuedOffline().
        coEvery { repository.addItems(venueId, "orden-1", any(), 3) } returns Result.success(
            OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-1", quantity = 1))),
        )

        viewModel.sendRound()

        assertThat(viewModel.pendingLines.value).isEmpty()
        assertThat(viewModel.queuedLines.value).hasSize(1)
        assertThat(viewModel.queuedLines.value.first().productId).isEqualTo("prod-1")
        assertThat(viewModel.error.value).isNull() // NO es un error
        assertThat(viewModel.notice.value).contains("Sin conexión")
        coVerify(exactly = 1) { repository.addItems(venueId, "orden-1", any(), 3) }
    }

    // endregion

    // region — 409 VERSION_CONFLICT: recarga la cuenta y avisa, sin perder el carrito

    @Test
    fun un_VERSION_CONFLICT_recarga_la_cuenta_y_avisa_sin_perder_el_carrito() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        // El init() de la ViewModel YA dispara un loadCheck() que llama a
        // getOrder y actualiza tableSession.version al valor que regrese este
        // mock — por eso `addItems` abajo matchea la version con any(), no un
        // literal: fijarla en 3 acopla el test al orden interno de llamadas.
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 4))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        coEvery { repository.addItems(venueId, "orden-1", any(), any()) } returns
            Result.failure(BackendHttpException(statusCode = 409, message = "version conflict"))

        viewModel.sendRound()

        assertThat(viewModel.notice.value).contains("cambió")
        assertThat(viewModel.error.value).isNull()
        // El carrito NO se pierde — el mesero reintenta tras ver la cuenta fresca.
        assertThat(viewModel.pendingLines.value).hasSize(1)
        coVerify(atLeast = 1) { repository.getOrder(venueId, "orden-1") } // la del init() + la del reintento
    }

    // endregion

    // region — checkLoadFailed (Mesa M1, cmsds40wp000cc9ixmm8u71f4, bug real en vivo):
    // la carga INICIAL de la cuenta fallando (blip de red, timeout, 500) dejaba
    // `check == null` sin ninguna señal — la pantalla no podía distinguir
    // "cuenta vacía" de "no se pudo cargar" y mostraba "Todavía no hay nada en
    // esta cuenta" sobre una orden con 3 artículos y $199.00 reales en el
    // server. Ver KDoc de TableOrderViewModel.checkLoadFailed.

    @Test
    fun getOrder_falla_por_red_en_la_carga_inicial_enciende_checkLoadFailed_sin_marcar_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(java.io.IOException("blip de red"))

        val viewModel = createViewModel()

        // "Sin red NO es un error" (regla de oro offline) — pero la pantalla
        // SÍ necesita saber que la carga falló para no disfrazarlo de "cuenta
        // vacía" (ver resolveTableOrderBodyState).
        assertThat(viewModel.checkLoadFailed.value).isTrue()
        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.check.value).isNull()
    }

    @Test
    fun getOrder_falla_por_error_de_servidor_en_la_carga_inicial_enciende_checkLoadFailed_Y_mantiene_el_error_toast() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.failure(BackendHttpException(statusCode = 500, message = "boom"))

        val viewModel = createViewModel()

        assertThat(viewModel.checkLoadFailed.value).isTrue()
        assertThat(viewModel.error.value).isNotNull() // el toast de error existente se conserva
        assertThat(viewModel.check.value).isNull()
    }

    @Test
    fun getOrder_exitoso_apaga_checkLoadFailed_y_puebla_el_cheque_con_sus_items() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(
                id = "orden-1",
                total = BigDecimal("199"),
                items = listOf(OrderDetailItem(id = "i1", productName = "Coca-Cola 600ml", quantity = 1, total = BigDecimal("25"))),
            ),
        )

        val viewModel = createViewModel()

        assertThat(viewModel.checkLoadFailed.value).isFalse()
        assertThat(viewModel.check.value?.items).hasSize(1)
    }

    @Test
    fun un_refresh_fallido_sobre_una_cuenta_YA_cargada_NO_enciende_checkLoadFailed_ni_la_borra() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        val loaded = OrderDetail(id = "orden-1", total = BigDecimal("199"), items = listOf(OrderDetailItem(id = "i1", quantity = 1)))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(loaded)
        val viewModel = createViewModel()
        assertThat(viewModel.check.value).isNotNull()

        // Un refresco posterior (p.ej. tras un 409) falla — la cuenta YA
        // visible no debe reemplazarse por la pantalla de "no se pudo cargar".
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(java.io.IOException("blip de red"))
        viewModel.loadCheck()

        assertThat(viewModel.checkLoadFailed.value).isFalse()
        assertThat(viewModel.check.value).isEqualTo(loaded)
    }

    // endregion

    // region — sesión provisional (mesa abierta offline): NUNCA llama TablesRepository.addItems

    @Test
    fun sesion_provisional_rutea_por_el_outbox_directo_nunca_por_repository_addItems() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-uuid-123")
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        viewModel.sendRound()

        coVerify(exactly = 0) { repository.addItems(any(), any(), any(), any()) }
        coVerify(exactly = 1) { syncOutbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) }
        assertThat(viewModel.pendingLines.value).isEmpty()
        assertThat(viewModel.queuedLines.value).hasSize(1)
        assertThat(viewModel.error.value).isNull()
    }

    // endregion

    // region — propiedad de mesa: bloquea a quien no es dueño

    @Test
    fun ownership_bloquea_a_quien_no_es_dueno_readOnly_true() = runTest {
        ownershipFlow.value = TablesRepository.TableOwnership(enforced = true, canManageAll = false, staffId = "yo")
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )

        val viewModel = createViewModel()

        assertThat(viewModel.readOnly.value).isTrue()
        assertThat(viewModel.lockOwnerName.value).isEqualTo("Fátima Flores")
    }

    @Test
    fun ownership_bloquea_sendRound_nunca_llama_addItems_y_marca_error() = runTest {
        ownershipFlow.value = TablesRepository.TableOwnership(enforced = true, canManageAll = false, staffId = "yo")
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        viewModel.sendRound()

        coVerify(exactly = 0) { repository.addItems(any(), any(), any(), any()) }
        assertThat(viewModel.error.value).isNotNull()
        assertThat(viewModel.error.value).contains("Fátima Flores")
        // El carrito no se toca — sigue ahí si el gerente resuelve la mesa y reintenta.
        assertThat(viewModel.pendingLines.value).hasSize(1)
    }

    @Test
    fun sin_ownership_habilitado_el_dueño_de_otra_mesa_no_bloquea_read_only() = runTest {
        // enforced = false (switch del venue apagado) — nunca debe bloquear,
        // aunque servedBy sea de otro staff.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )

        val viewModel = createViewModel()

        assertThat(viewModel.readOnly.value).isFalse()
    }

    // endregion

    // region — guardas: sin mesa activa / carrito vacío

    @Test
    fun sendRound_con_carrito_vacio_marca_error_sin_llamar_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()

        viewModel.sendRound()

        coVerify(exactly = 0) { repository.addItems(any(), any(), any(), any()) }
        assertThat(viewModel.error.value).isNotNull()
    }

    // endregion

    // region — Fase 1: splitOrder / compWholeOrder / compItems / moveOrder

    @Test
    fun splitOrder_manda_TODOS_los_itemIds_seleccionados_en_UNA_sola_llamada() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.splitOrder(venueId, "orden-1", listOf("i1", "i2")) } returns
            Result.success(TablesRepository.SplitOutcome(queued = false, createdOrderId = "order-2", createdOrderNumber = "A-101", sourceVersion = 2))
        val viewModel = createViewModel()

        viewModel.splitOrder(setOf("i1", "i2"))

        // UNA sola invocación con la lista COMPLETA — nunca una llamada por artículo.
        coVerify(exactly = 1) { repository.splitOrder(venueId, "orden-1", listOf("i1", "i2")) }
        assertThat(viewModel.notice.value).contains("A-101")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun splitOrder_sin_seleccion_marca_error_sin_llamar_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()

        viewModel.splitOrder(emptySet())

        coVerify(exactly = 0) { repository.splitOrder(any(), any(), any()) }
        assertThat(viewModel.error.value).isNotNull()
    }

    @Test
    fun splitOrder_ownership_bloquea_a_quien_no_es_dueno() = runTest {
        ownershipFlow.value = TablesRepository.TableOwnership(enforced = true, canManageAll = false, staffId = "yo")
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )
        val viewModel = createViewModel()

        viewModel.splitOrder(setOf("i1"))

        coVerify(exactly = 0) { repository.splitOrder(any(), any(), any()) }
        assertThat(viewModel.error.value).contains("Fátima Flores")
    }

    @Test
    fun compWholeOrder_encolado_sin_red_se_ve_como_pendiente_NUNCA_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo del cliente", "staff-1") } returns
            Result.success(TablesRepository.CompOutcome(queued = true, total = null, version = null))
        val viewModel = createViewModel()

        viewModel.compWholeOrder("Reclamo del cliente")

        assertThat(viewModel.notice.value).contains("Sin conexión")
        assertThat(viewModel.error.value).isNull()
    }

    /**
     * "Comp de un artículo ya enviado se rechaza offline con una razón clara"
     * — pedido explícito del enunciado. [TablesRepository.compOrder] es quien
     * decide esto (ver su test dedicado); aquí se prueba que el ViewModel
     * PROPAGA ese mensaje tal cual, nunca lo esconde ni lo reintenta como si
     * fuera un encolado exitoso.
     */
    @Test
    fun compItems_sin_red_se_rechaza_con_razon_clara_NUNCA_se_ve_como_pendiente() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.compOrder(venueId, "orden-1", listOf("i1"), "Error de entrada", "staff-1") } returns
            Result.failure(TablesRepository.ItemCompRequiresConnectionException())
        val viewModel = createViewModel()

        viewModel.compItems(setOf("i1"), "Error de entrada")

        assertThat(viewModel.error.value).contains("conexión")
        assertThat(viewModel.notice.value).isNull()
    }

    @Test
    fun compItems_sin_seleccion_marca_error_sin_llamar_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()

        viewModel.compItems(emptySet(), "Error de entrada")

        coVerify(exactly = 0) { repository.compOrder(any(), any(), any(), any(), any()) }
        assertThat(viewModel.error.value).isNotNull()
    }

    @Test
    fun moveOrder_exitoso_reparienta_la_sesion_a_la_mesa_destino() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", tableNumber = "1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val target = DiningTable(id = "mesa-9", number = "9", areaName = "Terraza", status = "AVAILABLE")
        coEvery { repository.moveOrder(venueId, "orden-1", false, "mesa-9") } returns
            Result.success(TablesRepository.MoveOutcome(queued = false, targetTableId = "mesa-9", version = 4))
        val viewModel = createViewModel()

        viewModel.moveOrder(target)

        val session = tableSession.current()!!
        assertThat(session.tableId).isEqualTo("mesa-9")
        assertThat(session.tableNumber).isEqualTo("9")
        assertThat(session.areaName).isEqualTo("Terraza")
        assertThat(session.orderId).isEqualTo("orden-1") // MISMA cuenta, mesa nueva
        assertThat(viewModel.notice.value).contains("9")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun moveOrder_rechazado_NO_toca_la_sesion() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", tableNumber = "1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val target = DiningTable(id = "mesa-9", number = "9", status = "AVAILABLE")
        coEvery { repository.moveOrder(venueId, "orden-1", false, "mesa-9") } returns
            Result.failure(TablesRepository.MoveOrderRejectedException("La mesa 9 ya tiene una cuenta abierta", "BUSINESS_RULE"))
        val viewModel = createViewModel()

        viewModel.moveOrder(target)

        assertThat(tableSession.current()?.tableId).isEqualTo("mesa-1")
        assertThat(viewModel.error.value).contains("ya tiene una cuenta")
    }

    // endregion

    // region — Fase 2 (2026-08-03): MERGE_ORDERS · CANCEL_ORDER · ASSIGN_ORDER

    @Test
    fun mergeOrders_exitoso_recarga_la_cuenta() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val source = DiningTable(
            id = "mesa-7", number = "7", status = "OCCUPIED",
            openOrders = listOf(OpenCheckSummary(id = "orden-2", orderNumber = "A-202", total = BigDecimal("120.00"))),
        )
        coEvery { repository.mergeOrders(venueId, "orden-1", "orden-2") } returns
            Result.success(TablesRepository.MergeOutcome(queued = false, targetTotal = BigDecimal("560.00"), mergedOrderNumber = "A-202", tableFreed = true))
        val viewModel = createViewModel()

        viewModel.mergeOrders(source)

        coVerify(exactly = 1) { repository.mergeOrders(venueId, "orden-1", "orden-2") }
        // init() + la recarga tras éxito = 2 llamadas a getOrder.
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.notice.value).contains("fusionada")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun mergeOrders_mesa_origen_sin_cuenta_abierta_marca_error_sin_llamar_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val source = DiningTable(id = "mesa-7", number = "7", status = "AVAILABLE") // sin openOrders
        val viewModel = createViewModel()

        viewModel.mergeOrders(source)

        coVerify(exactly = 0) { repository.mergeOrders(any(), any(), any()) }
        assertThat(viewModel.error.value).contains("Mesa 7")
    }

    @Test
    fun mergeOrders_bloqueado_por_propiedad_de_mesa_no_llama_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero")))
        ownershipFlow.value = TablesRepository.TableOwnership(enforced = true, canManageAll = false, staffId = "yo")
        val source = DiningTable(
            id = "mesa-7", number = "7", status = "OCCUPIED",
            openOrders = listOf(OpenCheckSummary(id = "orden-2", total = BigDecimal("120.00"))),
        )
        val viewModel = createViewModel()

        viewModel.mergeOrders(source)

        coVerify(exactly = 0) { repository.mergeOrders(any(), any(), any()) }
        assertThat(viewModel.error.value).contains("otro mesero")
    }

    @Test
    fun cancelOrder_encolado_sin_red_se_ve_como_guardado_NUNCA_error_y_dispara_la_salida() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.cancelOrder(venueId, "orden-1", false, "Cliente se fue") } returns
            Result.success(TablesRepository.CancelOutcome(queued = true))
        val viewModel = createViewModel()

        viewModel.cancelOrder("Cliente se fue")

        assertThat(viewModel.error.value).isNull() // NUNCA un error — regla de oro offline
        assertThat(viewModel.notice.value).contains("Sin conexión")
        assertThat(viewModel.cancelled.value).isTrue()
        assertThat(tableSession.current()).isNull() // sesión limpiada -> TableOrderScreen sale sola al plano
    }

    @Test
    fun cancelOrder_exitoso_online_limpia_la_sesion_y_dispara_la_salida() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.cancelOrder(venueId, "orden-1", false, null) } returns
            Result.success(TablesRepository.CancelOutcome(queued = false))
        val viewModel = createViewModel()

        viewModel.cancelOrder(null)

        assertThat(viewModel.notice.value).isEqualTo("Cuenta cancelada")
        assertThat(viewModel.cancelled.value).isTrue()
        assertThat(tableSession.current()).isNull()
    }

    @Test
    fun cancelOrder_rechazado_por_cuenta_pagada_NO_limpia_la_sesion() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.cancelOrder(venueId, "orden-1", false, null) } returns
            Result.failure(TablesRepository.CancelOrderRejectedException("Cannot cancel a paid order", "BUSINESS_RULE"))
        val viewModel = createViewModel()

        viewModel.cancelOrder(null)

        assertThat(viewModel.error.value).contains("paid order")
        assertThat(viewModel.cancelled.value).isFalse()
        assertThat(tableSession.current()).isNotNull() // la mesa sigue activa — nada que cancelar se perdió
    }

    @Test
    fun assignOrder_exitoso_recarga_la_cuenta_para_reflejar_el_nuevo_servedBy() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val staff = ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz")
        coEvery { repository.assignOrder(venueId, "orden-1", false, "staff-9") } returns
            Result.success(TablesRepository.AssignOutcome(queued = false, version = 8))
        val viewModel = createViewModel()

        viewModel.assignOrder(staff)

        coVerify(exactly = 1) { repository.assignOrder(venueId, "orden-1", false, "staff-9") }
        // init() + la recarga tras el ACKED = 2 llamadas — así el `check.servedBy`
        // (y por tanto `readOnly`) refleja al mesero nuevo de inmediato.
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.notice.value).contains("Diego Ruiz")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun assignOrder_encolado_sin_red_NO_recarga_y_nunca_es_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val staff = ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz")
        coEvery { repository.assignOrder(venueId, "orden-1", false, "staff-9") } returns
            Result.success(TablesRepository.AssignOutcome(queued = true, version = null))
        val viewModel = createViewModel()

        viewModel.assignOrder(staff)

        // Solo la del init() — encolado no dispara recarga (nada cambió todavía en el server).
        coVerify(exactly = 1) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.notice.value).contains("Sin conexión")
    }

    @Test
    fun assignOrder_rechazado_por_permiso_propaga_el_mensaje_del_server() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val staff = ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz")
        coEvery { repository.assignOrder(venueId, "orden-1", false, "staff-9") } returns
            Result.failure(TablesRepository.AssignOrderRejectedException("No tienes permiso para reproducir ASSIGN_ORDER.", "PERMISSION_DENIED"))
        val viewModel = createViewModel()

        viewModel.assignOrder(staff)

        assertThat(viewModel.error.value).contains("permiso")
    }

    @Test
    fun loadActiveStaff_puebla_activeStaff_desde_el_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getActiveStaff(venueId) } returns
            Result.success(listOf(ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz")))
        val viewModel = createViewModel()

        viewModel.loadActiveStaff()

        assertThat(viewModel.activeStaff.value).hasSize(1)
        assertThat(viewModel.activeStaff.value.first().staffId).isEqualTo("staff-9")
    }

    // endregion

    // region — pickers offline: "unavailable" ≠ "vacío" (mismo bug de 7df5819,
    // aquí en Mover mesa / Fusionar cuentas / Reasignar mesero). Reproducido en
    // hardware sin red: los 3 pickers mostraban "no hay X" en vez de señalar
    // que no se pudo verificar.

    @Test
    fun loadAvailableTargetTables_exitoso_puebla_la_lista_de_mesas_libres() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getTables(venueId) } returns
            Result.success(listOf(DiningTable(id = "mesa-9", number = "9", status = "AVAILABLE")))
        val viewModel = createViewModel()

        viewModel.loadAvailableTargetTables()

        assertThat(viewModel.availableTargetTables.value).hasSize(1)
        assertThat(viewModel.targetTablesUnavailable.value).isFalse()
    }

    /**
     * 🔴 El defecto reportado en hardware: sin red, [MoveTableSheet] pintaba
     * "No hay mesas libres en este momento" — un hecho plausible pero FALSO
     * (no sabemos si hay mesas libres, no pudimos preguntar). El ViewModel
     * ahora expone [TableOrderViewModel.targetTablesUnavailable] para que la
     * UI distinga los dos casos.
     */
    @Test
    fun loadAvailableTargetTables_sin_red_marca_unavailable_NUNCA_se_ve_como_vacio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getTables(venueId) } returns Result.failure(java.io.IOException("sin red"))
        val viewModel = createViewModel()

        viewModel.loadAvailableTargetTables()

        assertThat(viewModel.availableTargetTables.value).isEmpty()
        assertThat(viewModel.targetTablesUnavailable.value).isTrue()
        assertThat(viewModel.error.value).isNotNull()
    }

    /**
     * "Mover mesa" NUNCA sirve una lista vieja — a diferencia de "Reasignar
     * mesero" (ver `loadActiveStaff_sin_red_con_cache_previa_*` abajo), una
     * mesa "libre" desactualizada es un choque real con otro dispositivo. Un
     * éxito seguido de un fallo (reabrir el sheet sin red) debe BORRAR la
     * lista, no dejar la del último éxito en pantalla disfrazada de fresca.
     */
    @Test
    fun loadAvailableTargetTables_fallo_tras_exito_previo_borra_la_lista_vieja() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getTables(venueId) } returns
            Result.success(listOf(DiningTable(id = "mesa-9", number = "9", status = "AVAILABLE")))
        val viewModel = createViewModel()
        viewModel.loadAvailableTargetTables()
        assertThat(viewModel.availableTargetTables.value).hasSize(1) // precondición: sí hubo un éxito antes

        coEvery { repository.getTables(venueId) } returns Result.failure(java.io.IOException("sin red"))
        viewModel.loadAvailableTargetTables()

        assertThat(viewModel.availableTargetTables.value).isEmpty()
        assertThat(viewModel.targetTablesUnavailable.value).isTrue()
    }

    @Test
    fun loadMergeCandidateTables_exitoso_puebla_la_lista_de_cuentas_abiertas() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getTables(venueId) } returns
            Result.success(
                listOf(
                    DiningTable(
                        id = "mesa-7", number = "7", status = "OCCUPIED",
                        openOrders = listOf(OpenCheckSummary(id = "orden-2", total = BigDecimal("120.00"))),
                    ),
                ),
            )
        val viewModel = createViewModel()

        viewModel.loadMergeCandidateTables()

        assertThat(viewModel.mergeCandidateTables.value).hasSize(1)
        assertThat(viewModel.mergeCandidatesUnavailable.value).isFalse()
    }

    /**
     * Mismo defecto que Mover mesa, aquí para "Fusionar cuentas" — el riesgo
     * es TODAVÍA mayor: una fila vieja puede apuntar a una cuenta que ya se
     * cobró. Nunca "no hay otras mesas con cuenta abierta" cuando en realidad
     * es "no se pudo preguntar".
     */
    @Test
    fun loadMergeCandidateTables_sin_red_marca_unavailable_y_borra_lista_vieja() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getTables(venueId) } returns
            Result.success(
                listOf(
                    DiningTable(
                        id = "mesa-7", number = "7", status = "OCCUPIED",
                        openOrders = listOf(OpenCheckSummary(id = "orden-2", total = BigDecimal("120.00"))),
                    ),
                ),
            )
        val viewModel = createViewModel()
        viewModel.loadMergeCandidateTables()
        assertThat(viewModel.mergeCandidateTables.value).hasSize(1) // precondición: sí hubo un éxito antes

        coEvery { repository.getTables(venueId) } returns Result.failure(java.io.IOException("sin red"))
        viewModel.loadMergeCandidateTables()

        assertThat(viewModel.mergeCandidateTables.value).isEmpty()
        assertThat(viewModel.mergeCandidatesUnavailable.value).isTrue()
        assertThat(viewModel.error.value).isNotNull()
    }

    /**
     * "Reasignar mesero" primer intento sin red y SIN caché previa — no hay
     * nada que servir, así que es "unavailable" (mismo trato que Mover/Fusionar
     * cuando tampoco hay caché), nunca "no hay personal en turno".
     */
    @Test
    fun loadActiveStaff_sin_red_y_sin_cache_previa_marca_unavailable() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getActiveStaff(venueId) } returns Result.failure(java.io.IOException("sin red"))
        val viewModel = createViewModel()

        viewModel.loadActiveStaff()

        assertThat(viewModel.activeStaff.value).isEmpty()
        assertThat(viewModel.activeStaffUnavailable.value).isTrue()
        assertThat(viewModel.activeStaffStale.value).isFalse()
        assertThat(viewModel.error.value).isNotNull()
    }

    /**
     * 🔴 A diferencia de Mover/Fusionar: "Reasignar mesero" SÍ sigue sirviendo
     * la última lista conocida cuando el refresh falla — riesgo bajo
     * (reasignar a alguien que ya salió es corregible, `ASSIGN_ORDER` ya es
     * offline-capable) — marcada [TableOrderViewModel.activeStaffStale] para
     * que la UI avise, nunca oculta las filas ni las reemplaza por "no hay
     * personal en turno".
     */
    @Test
    fun loadActiveStaff_sin_red_con_cache_previa_mantiene_la_lista_y_marca_stale() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getActiveStaff(venueId) } returns
            Result.success(listOf(ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz")))
        val viewModel = createViewModel()
        viewModel.loadActiveStaff()
        assertThat(viewModel.activeStaff.value).hasSize(1) // precondición: sí hubo un éxito antes

        coEvery { repository.getActiveStaff(venueId) } returns Result.failure(java.io.IOException("sin red"))
        viewModel.loadActiveStaff()

        // La lista de la carga anterior SIGUE ahí — nunca se borra ni se
        // reemplaza por "no hay personal en turno".
        assertThat(viewModel.activeStaff.value).hasSize(1)
        assertThat(viewModel.activeStaff.value.first().staffId).isEqualTo("staff-9")
        assertThat(viewModel.activeStaffStale.value).isTrue()
        assertThat(viewModel.activeStaffUnavailable.value).isFalse()
    }

    @Test
    fun loadActiveStaff_reintento_exitoso_tras_stale_limpia_la_bandera() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.getActiveStaff(venueId) } returns
            Result.success(listOf(ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz")))
        val viewModel = createViewModel()
        viewModel.loadActiveStaff()
        coEvery { repository.getActiveStaff(venueId) } returns Result.failure(java.io.IOException("sin red"))
        viewModel.loadActiveStaff()
        assertThat(viewModel.activeStaffStale.value).isTrue() // precondición

        coEvery { repository.getActiveStaff(venueId) } returns
            Result.success(listOf(ActiveStaffMember(staffId = "staff-9", name = "Diego Ruiz"), ActiveStaffMember(staffId = "staff-10", name = "Fátima Flores")))
        viewModel.loadActiveStaff()

        assertThat(viewModel.activeStaff.value).hasSize(2)
        assertThat(viewModel.activeStaffStale.value).isFalse()
    }

    // endregion

    // region — Fase 3 (2026-08-03): SPLIT_BY_SEAT · UPDATE_DETAILS

    @Test
    fun splitOrderBySeat_exitoso_recarga_la_cuenta() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.splitOrderBySeat(venueId, "orden-1") } returns
            Result.success(TablesRepository.SplitBySeatOutcome(queued = false, createdCount = 2, sourceSeat = 1))
        val viewModel = createViewModel()

        viewModel.splitOrderBySeat()

        coVerify(exactly = 1) { repository.splitOrderBySeat(venueId, "orden-1") }
        // init() + la recarga tras éxito = 2 llamadas a getOrder.
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.notice.value).contains("dividida")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun splitOrderBySeat_encolado_sin_red_se_ve_como_pendiente_NUNCA_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.splitOrderBySeat(venueId, "orden-1") } returns
            Result.success(TablesRepository.SplitBySeatOutcome(queued = true, createdCount = null, sourceSeat = null))
        val viewModel = createViewModel()

        viewModel.splitOrderBySeat()

        assertThat(viewModel.notice.value).contains("Sin conexión")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun splitOrderBySeat_sesion_provisional_marca_error_sin_llamar_al_repositorio() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-uuid-123")
        val viewModel = createViewModel()

        viewModel.splitOrderBySeat()

        coVerify(exactly = 0) { repository.splitOrderBySeat(any(), any()) }
        assertThat(viewModel.error.value).contains("sincroniza")
    }

    @Test
    fun splitOrderBySeat_bloqueado_por_propiedad_de_mesa_no_llama_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero")))
        ownershipFlow.value = TablesRepository.TableOwnership(enforced = true, canManageAll = false, staffId = "yo")
        val viewModel = createViewModel()

        viewModel.splitOrderBySeat()

        coVerify(exactly = 0) { repository.splitOrderBySeat(any(), any()) }
        assertThat(viewModel.error.value).contains("otro mesero")
    }

    @Test
    fun splitOrderBySeat_rechazado_por_menos_de_dos_asientos_marca_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.splitOrderBySeat(venueId, "orden-1") } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "Se necesitan al menos dos asientos con artículos para dividir por puesto"))
        val viewModel = createViewModel()

        viewModel.splitOrderBySeat()

        assertThat(viewModel.error.value).contains("al menos dos asientos")
    }

    @Test
    fun updateOrderDetails_exitoso_recarga_la_cuenta() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.updateOrderDetails(venueId, "orden-1", false, "Cumpleaños de Ana", 6) } returns
            Result.success(TablesRepository.UpdateDetailsOutcome(queued = false))
        val viewModel = createViewModel()

        viewModel.updateOrderDetails("Cumpleaños de Ana", 6)

        coVerify(exactly = 1) { repository.updateOrderDetails(venueId, "orden-1", false, "Cumpleaños de Ana", 6) }
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.notice.value).isEqualTo("Detalles actualizados")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun updateOrderDetails_encolado_sin_red_se_ve_como_guardado_NUNCA_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.updateOrderDetails(venueId, "orden-1", false, "Ana", null) } returns
            Result.success(TablesRepository.UpdateDetailsOutcome(queued = true))
        val viewModel = createViewModel()

        viewModel.updateOrderDetails("Ana", null)

        assertThat(viewModel.notice.value).contains("Sin conexión")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun updateOrderDetails_funciona_con_sesion_provisional() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-uuid-123")
        coEvery { repository.updateOrderDetails(venueId, "local-uuid-123", true, "Ana", 4) } returns
            Result.success(TablesRepository.UpdateDetailsOutcome(queued = true))
        val viewModel = createViewModel()

        viewModel.updateOrderDetails("Ana", 4)

        coVerify(exactly = 1) { repository.updateOrderDetails(venueId, "local-uuid-123", true, "Ana", 4) }
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun updateOrderDetails_rechazado_marca_error() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.updateOrderDetails(venueId, "orden-1", false, null, 999) } returns
            Result.failure(TablesRepository.UpdateDetailsRejectedException("covers inválido", "INVALID_PAYLOAD"))
        val viewModel = createViewModel()

        viewModel.updateOrderDetails(null, 999)

        assertThat(viewModel.error.value).contains("covers inválido")
    }

    @Test
    fun updateOrderDetails_bloqueado_por_propiedad_de_mesa_no_llama_al_repositorio() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero")))
        ownershipFlow.value = TablesRepository.TableOwnership(enforced = true, canManageAll = false, staffId = "yo")
        val viewModel = createViewModel()

        viewModel.updateOrderDetails("Ana", 4)

        coVerify(exactly = 0) { repository.updateOrderDetails(any(), any(), any(), any(), any()) }
        assertThat(viewModel.error.value).contains("otro mesero")
    }

    // endregion

    // region — "se quedó trabado" (founder, PAX en vivo): un envío lento
    // debe distinguirse de uno congelado, y un doble-tap nunca debe duplicar
    // la ronda. Ver KDoc de `TableOrderViewModel._sendTakingLong`.

    @Test
    fun ronda_lenta_enciende_la_senal_de_reafirmacion_pasado_el_umbral_y_la_apaga_al_terminar() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 3))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        // Servidor lento (ngrok/red real) — nunca responde antes del umbral
        // de reafirmación. classifyTablesSyncFailure ni siquiera entra en
        // juego aquí: esto es puramente "¿el mesero puede distinguir
        // 'sigue trabajando' de 'se congeló'?", no una falla de red.
        coEvery { repository.addItems(venueId, "orden-1", any(), 3) } coAnswers {
            delay(20_000)
            Result.success(OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "i1", productId = "prod-1", quantity = 1))))
        }

        viewModel.sendRound()

        // Justo tras el tap: sigue enviando, pero AÚN no pasó el umbral — un
        // envío rápido normal nunca debe mostrar la señal de reafirmación.
        assertThat(viewModel.isSending.value).isTrue()
        assertThat(viewModel.sendTakingLong.value).isFalse()

        // Pasado el umbral, la petición SIGUE en vuelo — el mesero debe ver
        // una señal de "sigue trabajando", nunca un spinner mudo
        // indistinguible de "se congeló".
        advanceTimeBy(TableOrderViewModel.SEND_ROUND_REASSURANCE_DELAY_MS + 100)
        runCurrent()
        assertThat(viewModel.isSending.value).isTrue()
        assertThat(viewModel.sendTakingLong.value).isTrue()

        // Cuando por fin responde, la señal se apaga junto con isSending —
        // nunca se queda pegada en pantalla.
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(viewModel.isSending.value).isFalse()
        assertThat(viewModel.sendTakingLong.value).isFalse()
        assertThat(viewModel.notice.value).isEqualTo("Ronda enviada a cocina")
    }

    @Test
    fun ronda_rapida_nunca_enciende_la_senal_de_reafirmacion() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 3))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        coEvery { repository.addItems(venueId, "orden-1", any(), 3) } returns Result.success(
            OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "i1", productId = "prod-1", quantity = 1))),
        )

        viewModel.sendRound()
        advanceTimeBy(TableOrderViewModel.SEND_ROUND_REASSURANCE_DELAY_MS + 100)
        runCurrent()

        assertThat(viewModel.sendTakingLong.value).isFalse()
    }

    @Test
    fun doble_tap_en_enviar_ronda_manda_addItems_UNA_sola_vez_nunca_duplica_la_ronda() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 3))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        // Servidor lento — es justo la ventana donde un mesero impaciente
        // vuelve a tocar el botón mientras el primer tap sigue en vuelo.
        coEvery { repository.addItems(venueId, "orden-1", any(), 3) } coAnswers {
            delay(5_000)
            Result.success(OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "i1", productId = "prod-1", quantity = 1))))
        }

        viewModel.sendRound() // primer tap
        viewModel.sendRound() // segundo tap, "impaciente", mientras el primero sigue en vuelo
        viewModel.sendRound() // por si las dudas, un tercero

        advanceTimeBy(5_100)
        runCurrent()

        coVerify(exactly = 1) { repository.addItems(venueId, "orden-1", any(), 3) }
        assertThat(viewModel.isSending.value).isFalse()
    }

    // endregion
}
