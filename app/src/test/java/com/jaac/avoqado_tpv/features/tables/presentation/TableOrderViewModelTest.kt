package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
    private lateinit var tableSession: TableSession
    private lateinit var pendingCart: PendingRoundCart
    private lateinit var ownershipFlow: MutableStateFlow<TablesRepository.TableOwnership>

    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, tableSyncCoordinator, syncOutbox, deviceInfoManager)

        tableSession = TableSession()
        pendingCart = PendingRoundCart()
        ownershipFlow = MutableStateFlow(TablesRepository.TableOwnership())

        every { deviceInfoManager.getVenueId() } returns venueId
        every { repository.ownership } returns ownershipFlow
        coEvery { tableSyncCoordinator.replay(any()) } returns Unit
        coEvery { syncOutbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
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
}
