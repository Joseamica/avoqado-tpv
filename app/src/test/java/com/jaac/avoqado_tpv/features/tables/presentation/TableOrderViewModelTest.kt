package com.jaac.avoqado_tpv.features.tables.presentation

import com.jaac.avoqado_tpv.core.util.NetworkType
import com.jaac.avoqado_tpv.core.util.NetworkInfo
import com.jaac.avoqado_tpv.features.tables.data.SaldoSinRed
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.PermisoRequeridoException
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.SaldoDeMesaSinRed
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    private val autorizacion = mockk<AutorizacionDeGerente>()
    private val socketManager = mockk<SocketManager>()
    private val avisosDelSocket = MutableSharedFlow<SocketEvent>()
    private var secuenciaDeLaCola = 0L
    private lateinit var tableSession: TableSession
    private lateinit var pendingCart: PendingRoundCart
    private lateinit var saldoDeMesaSinRed: SaldoDeMesaSinRed
    private val cambiosDeLaCola = MutableStateFlow(0)
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
        coEvery { autorizacion.paraCortesia(any(), any(), any()) } returns Autorizacion.NoHaceFalta
        every { networkMonitor.networkStateFlow } returns emptyFlow()
        coEvery { syncOutbox.vivasQueMencionan(any(), any()) } returns emptyList()
        coEvery { syncOutbox.aperturasConfirmadasQueMencionan(any(), any()) } returns emptyList()
        coEvery { syncOutbox.aperturasConfirmadasConResultado(any(), any()) } returns emptyList()
        coEvery { syncOutbox.confirmadasConCuentaAparte(any(), any()) } returns emptyList()
        every { syncOutbox.observarCambios(any()) } returns cambiosDeLaCola
        every { socketManager.events } returns avisosDelSocket
        coEvery { syncOutbox.ultimaSecuencia(any()) } coAnswers { secuenciaDeLaCola }
        saldoDeMesaSinRed = SaldoDeMesaSinRed(syncOutbox)
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
        autorizacionDeGerente = autorizacion,
        saldoDeMesaSinRed = saldoDeMesaSinRed,
        socketManager = socketManager,
    )

    // region — D1 (hz:163ad26c:D1): la pantalla de la mesa se entera de lo que OTRO aparato le hace a la cuenta

    /**
     * Otra caja abona $40 a la cuenta de $118 con la pantalla de la mesa abierta en la TPV. Antes la pantalla no se enteraba
     * hasta salir y volver: «Pagar» decía $118 mientras «Cobrar» decía $78. El aviso del SERVIDOR de ESA orden la relee sola,
     * sin reproducir la cola (la reproduce el trabajador; reproducirla desde un aviso puede dar vueltas, D86 r8).
     */
    @Test
    fun D1_un_abono_de_otro_aparato_a_esta_cuenta_la_relee_sola() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            val restante = if (++lecturas == 1) "118.00" else "78.00"
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("118.00"), amountLeft = BigDecimal(restante)))
        }
        val vm = createViewModel()
        assertThat(vm.check.value?.amountLeft).isEqualTo(BigDecimal("118.00"))

        avisosDelSocket.emit(
            SocketEvent.PaymentCompleted(paymentId = "p1", amount = 4000, currency = "MXN", tableId = "mesa-1", orderId = "orden-1", venueId = venueId, timestamp = ""),
        )
        advanceTimeBy(801)

        assertThat(vm.check.value?.amountLeft).isEqualTo(BigDecimal("78.00"))
        coVerify(exactly = 1) { tableSyncCoordinator.replay(any()) } // sólo la carga al entrar
    }

    @Test
    fun D1_una_ronda_de_otro_aparato_a_esta_cuenta_la_relee_sola() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val vm = createViewModel()

        avisosDelSocket.emit(
            SocketEvent.OrderUpdated(orderId = "orden-1", venueId = venueId, tableId = "mesa-1", status = null, items = null, total = null, timestamp = ""),
        )
        advanceTimeBy(801)

        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
    }

    @Test
    fun D1_el_aviso_de_otra_cuenta_no_la_relee() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val vm = createViewModel()

        avisosDelSocket.emit(
            SocketEvent.OrderUpdated(orderId = "otra", venueId = venueId, tableId = "mesa-2", status = null, items = null, total = null, timestamp = ""),
        )
        advanceTimeBy(801)

        coVerify(exactly = 1) { repository.getOrder(venueId, "orden-1") }
    }

    /**
     * D1 (Codex D88-D90 r6): la ronda propia relee (A) y su aviso del SERVIDOR relee otra vez (B). Si B fallaba después, la
     * guarda vieja («la que empezó al último manda») tiraba A, que traía la cuenta buena: la pantalla se quedaba atrasada.
     */
    @Test
    fun D1_la_lectura_buena_no_se_tira_si_la_del_aviso_falla_despues() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        val lecturaA = CompletableDeferred<Result<OrderDetail>>()
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            when (++lecturas) {
                1 -> Result.success(OrderDetail(id = "orden-1", amountLeft = BigDecimal("118.00")))
                2 -> lecturaA.await()
                else -> Result.failure(java.io.IOException("sin red"))
            }
        }
        val vm = createViewModel()

        vm.loadCheck() // A
        avisosDelSocket.emit(
            SocketEvent.OrderUpdated(orderId = "orden-1", venueId = venueId, tableId = "mesa-1", status = null, items = null, total = null, timestamp = ""),
        )
        advanceTimeBy(801) // B: falla
        lecturaA.complete(Result.success(OrderDetail(id = "orden-1", amountLeft = BigDecimal("78.00"))))

        assertThat(vm.check.value?.amountLeft).isEqualTo(BigDecimal("78.00"))
        assertThat(vm.isLoadingCheck.value).isFalse()
    }

    /**
     * D1 (Codex D88-D90 r7): A pide la cuenta; mientras viaja, se manda una ronda sin red («Por sincronizar»), la del aviso (B)
     * falla y el trabajador sube la ronda. A vuelve con la cuenta de ANTES de la ronda: puede pintarse, pero no puede quitar
     * «Por sincronizar» ni dejar de releer (las dos revisiones de la cola la ven vacía: nació y subió en medio).
     */
    @Test
    fun D1_una_lectura_anterior_a_una_ronda_propia_no_quita_por_sincronizar() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        val lecturaA = CompletableDeferred<Result<OrderDetail>>()
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            when (++lecturas) {
                1 -> Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00")))
                2 -> lecturaA.await()
                3 -> Result.failure(java.io.IOException("sin red"))
                else -> Result.success(OrderDetail(id = "orden-1", total = BigDecimal("140.00")))
            }
        }
        coEvery { repository.addItems(venueId, "orden-1", any(), any()) } returns
            Result.success(OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-1", quantity = 1))))
        val vm = createViewModel()

        vm.loadCheck() // A
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("40.00"))
        vm.sendRound()
        secuenciaDeLaCola = 1L // la ronda entró a la cola
        assertThat(vm.queuedLines.value).hasSize(1)
        avisosDelSocket.emit(
            SocketEvent.OrderUpdated(orderId = "orden-1", venueId = venueId, tableId = "mesa-1", status = null, items = null, total = null, timestamp = ""),
        )
        advanceTimeBy(801) // B: falla
        lecturaA.complete(Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"))))

        assertThat(vm.queuedLines.value).hasSize(1)
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()
        assertThat(vm.check.value?.total).isEqualTo(BigDecimal("140.00"))
        assertThat(vm.queuedLines.value).isEmpty()
    }

    /** D1 (Codex D88-D90 r6): A espera la cola y pide DESPUÉS que B (la del aviso, sin cola): la respuesta de A sí pinta. */
    @Test
    fun D1_la_lectura_que_espera_la_cola_y_pide_despues_si_pinta() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        val colaDeA = CompletableDeferred<Unit>()
        var replays = 0
        coEvery { tableSyncCoordinator.replay(any()) } coAnswers { if (++replays == 2) colaDeA.await() }
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            val restante = if (++lecturas <= 2) "118.00" else "78.00"
            Result.success(OrderDetail(id = "orden-1", amountLeft = BigDecimal(restante)))
        }
        val vm = createViewModel()

        vm.loadCheck() // A: espera la cola
        avisosDelSocket.emit(
            SocketEvent.OrderUpdated(orderId = "orden-1", venueId = venueId, tableId = "mesa-1", status = null, items = null, total = null, timestamp = ""),
        )
        advanceTimeBy(801) // B: pide y pinta $118
        assertThat(vm.check.value?.amountLeft).isEqualTo(BigDecimal("118.00"))

        colaDeA.complete(Unit) // A: pide después y trae $78
        assertThat(vm.check.value?.amountLeft).isEqualTo(BigDecimal("78.00"))
    }

    // endregion

    // region — D85: una lectura de la cuenta nunca cambia la mesa de la sesión (Codex r5: se quitó corregirMesa)

    @Test
    fun D85_una_lectura_que_llega_despues_de_mover_mesa_no_regresa_la_sesion_a_la_mesa_vieja() = runTest {
        // Codex r3 #1: el getOrder de B sale con la sesión en M8; el cajero mueve B a M9 y se confirma; después llega la
        // lectura vieja (M8). La sesión se queda en M9.
        tableSession.start(TableSession.Active(tableId = "m8", tableNumber = "M8", orderId = "b", version = 3))
        val lectura = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "b") } coAnswers { lectura.await() }
        coEvery { repository.moveOrder(venueId, "b", false, "m9") } returns
            Result.success(TablesRepository.MoveOutcome(queued = false, targetTableId = "m9", version = 4))
        val viewModel = createViewModel()

        viewModel.moveOrder(DiningTable(id = "m9", number = "M9", status = "AVAILABLE"))
        lectura.complete(Result.success(OrderDetail(id = "b", version = 3, tableId = "m8", tableName = "Mesa M8")))

        assertThat(tableSession.current()?.tableId).isEqualTo("m9")
    }

    // endregion

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

    // region — lo que el mesero agrega MIENTRAS se envía la ronda no se borra (QA de variantes, 20-oct)

    @Test
    fun lo_que_se_agrega_mientras_se_envia_la_ronda_se_queda_en_el_carrito() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 3))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))
        val servidor = CompletableDeferred<Unit>()
        coEvery { repository.addItems(venueId, "orden-1", any(), any()) } coAnswers {
            servidor.await()
            Result.success(OrderDetail(id = "orden-1", version = 4))
        }

        viewModel.sendRound() // envío lento: el server no contesta todavía
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00")) // otro café: se suma a la línea en vuelo
        pendingCart.addSimple(productId = "prod-2", name = "Agua", unitPrice = BigDecimal("20.00"))
        servidor.complete(Unit)

        // Se quita SÓLO lo que se mandó (1 café); el segundo café y el agua esperan la siguiente ronda.
        assertThat(viewModel.pendingLines.value.map { it.productId to it.quantity })
            .containsExactly("prod-1" to 1, "prod-2" to 1)
        coVerify(exactly = 1) { repository.addItems(venueId, "orden-1", match { it.size == 1 && it.first().quantity == 1 }, any()) }
    }

    @Test
    fun el_cafe_de_mas_mandado_en_otra_ronda_sin_red_no_repite_la_llave_de_la_lista() = runTest {
        // Codex r1: lo que queda de una línea en vuelo conservaba su lineId; al encolarse también, «Por sincronizar»
        // tenía dos renglones con la misma llave y la LazyColumn truena (`queued-<lineId>`).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 3))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", version = 3))
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))
        val servidor = CompletableDeferred<Unit>()
        val encolada = OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-1", quantity = 1)))
        coEvery { repository.addItems(venueId, "orden-1", any(), any()) } coAnswers { servidor.await(); Result.success(encolada) }

        viewModel.sendRound()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))
        servidor.complete(Unit)
        viewModel.sendRound() // el café de más, también sin red

        assertThat(viewModel.queuedLines.value.map { it.quantity }).containsExactly(1, 1)
        assertThat(viewModel.queuedLines.value.map { it.lineId }.distinct()).hasSize(2)
        assertThat(viewModel.pendingLines.value).isEmpty()
    }

    @Test
    fun sesion_provisional_lo_que_se_agrega_mientras_se_encola_la_ronda_se_queda_en_el_carrito() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-uuid-123")
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))
        val disco = CompletableDeferred<Unit>()
        coEvery { syncOutbox.enqueue(any(), any(), any(), any()) } coAnswers { disco.await(); "intent-id" }

        viewModel.sendRound()
        pendingCart.addSimple(productId = "prod-2", name = "Agua", unitPrice = BigDecimal("20.00"))
        disco.complete(Unit)

        assertThat(viewModel.pendingLines.value.map { it.productId }).containsExactly("prod-2")
        assertThat(viewModel.queuedLines.value.map { it.productId }).containsExactly("prod-1")
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

    // MARK: - El CAJERO cobra la mesa ajena (`tables:pay-any`)

    @Test
    fun el_CAJERO_no_edita_pero_SI_cobra_readOnly_true_readOnlyForPayment_false() = runTest {
        // 🔴 DINERO. Mismo estado exacto que `ownership_bloquea_a_quien_no_es_dueno_readOnly_true`
        // — la única diferencia es `canPayAny`, que sale de `tables:pay-any`.
        // Los DOS flujos tienen que divergir aquí: si `readOnlyForPayment` copiara
        // a `readOnly`, el botón "Pagar" seguiría escondido y el cajero seguiría
        // sin poder hacer su trabajo.
        ownershipFlow.value = TablesRepository.TableOwnership(
            enforced = true, canManageAll = false, staffId = "yo", canPayAny = true,
        )
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )

        val viewModel = createViewModel()

        assertThat(viewModel.readOnly.value).isTrue()
        assertThat(viewModel.readOnlyForPayment.value).isFalse()
        // El banner "Mesa de {mesero}" sigue: no puede editarla, y hay que decírselo.
        assertThat(viewModel.lockOwnerName.value).isEqualTo("Fátima Flores")
    }

    @Test
    fun el_CAJERO_con_pay_any_sigue_sin_poder_enviar_ronda() = runTest {
        // Contención: el arreglo abre COBRAR y nada más. Si abriera editar sería
        // peor que el defecto — le regalaría descuentos, cortesías y cancelaciones
        // sobre CUALQUIER mesa.
        ownershipFlow.value = TablesRepository.TableOwnership(
            enforced = true, canManageAll = false, staffId = "yo", canPayAny = true,
        )
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        viewModel.sendRound()

        coVerify(exactly = 0) { repository.addItems(any(), any(), any(), any()) }
        assertThat(viewModel.error.value).contains("Fátima Flores")
    }

    @Test
    fun el_MESERO_en_mesa_ajena_sigue_bloqueado_para_cobrar() = runTest {
        ownershipFlow.value = TablesRepository.TableOwnership(
            enforced = true, canManageAll = false, staffId = "yo", canPayAny = false,
        )
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(
            OrderDetail(id = "orden-1", servedBy = OrderStaffSummary(id = "otro-mesero", firstName = "Fátima", lastName = "Flores")),
        )

        val viewModel = createViewModel()

        assertThat(viewModel.readOnly.value).isTrue()
        assertThat(viewModel.readOnlyForPayment.value).isTrue()
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

        coVerify(exactly = 0) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
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
    fun mover_la_mesa_mientras_llega_el_id_real_no_regresa_la_cuenta_al_id_local_ni_vacia_el_carrito() = runTest {
        // Codex r2: moveOrder escribía encima la copia de la sesión de ANTES de esperar al servidor. Si el ack de
        // OPEN_TABLE la promovía mientras tanto, la cuenta volvía al id local y el carrito (ya del id real) se vaciaba.
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1", tableNumber = "1")
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))
        val servidor = CompletableDeferred<Unit>()
        coEvery { repository.moveOrder(venueId, "local-1", true, "mesa-9") } coAnswers {
            servidor.await()
            Result.success(TablesRepository.MoveOutcome(queued = true, targetTableId = "mesa-9", version = null))
        }
        coEvery { repository.getOrder(venueId, "real-1") } returns Result.success(OrderDetail(id = "real-1", version = 2))

        viewModel.moveOrder(DiningTable(id = "mesa-9", number = "9", areaName = "Terraza", status = "AVAILABLE"))
        // Mientras se mueve vuelve la red: el ack de OPEN_TABLE promueve carrito y cuenta (lo que hace TableSyncCoordinator).
        pendingCart.promote("local-1", "real-1")
        tableSession.promoteProvisional("local-1", "real-1", orderNumber = null, version = 2)
        servidor.complete(Unit)
        viewModel.loadCheck()

        val session = tableSession.current()!!
        assertThat(session.orderId).isEqualTo("real-1")
        assertThat(session.isProvisional).isFalse()
        assertThat(session.tableId).isEqualTo("mesa-9")
        assertThat(viewModel.pendingLines.value.map { it.productId }).containsExactly("prod-1")
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

    // 🔴 Fix online-primero (2026-08-07): el rechazo ahora puede llegar como
    // BackendHttpException directo de la ruta online (no solo como
    // CancelOrderRejectedException de un ack offline) — ver KDoc de
    // TablesRepository.cancelOrder. Mismo criterio que mergeOrders/splitOrder.
    @Test
    fun cancelOrder_rechazado_online_BackendHttpException_muestra_el_mensaje_real_y_NO_limpia_la_sesion() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        coEvery { repository.cancelOrder(venueId, "orden-1", false, null) } returns
            Result.failure(BackendHttpException(statusCode = 409, message = "Hay un cobro en curso en la terminal para esta orden."))
        val viewModel = createViewModel()

        viewModel.cancelOrder(null)

        assertThat(viewModel.error.value).contains("cobro en curso")
        assertThat(viewModel.cancelled.value).isFalse()
        assertThat(tableSession.current()).isNotNull()
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

    // region — PIN de gerente en la cortesía

    private fun conCuenta(): TableOrderViewModel {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        return createViewModel()
    }

    private val okComp = Result.success(TablesRepository.CompOutcome(queued = false, total = null, version = null))

    @Test
    fun con_permiso_no_pide_pin_ni_manda_header() = runTest {
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) } returns okComp
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, "Para dar esta cortesía", false) }
        coVerify(exactly = 1) { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) }
        assertThat(viewModel.notice.value).isEqualTo("Cortesía aplicada")
    }

    @Test
    fun sin_permiso_pide_pin_y_manda_token() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.ConPin("tok-1")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-1") } returns okComp
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        coVerify(exactly = 1) { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-1") }
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun negada_muestra_el_texto_y_no_llama_al_repositorio() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.Negada("Esta cortesía la da un gerente con su sesión")
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        coVerify(exactly = 0) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.error.value).isEqualTo("Esta cortesía la da un gerente con su sesión")
        assertThat(viewModel.isComping.value).isFalse()
    }

    @Test
    fun cancelada_no_llama_al_repositorio_ni_muestra_error_y_deja_isComping_en_false() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.Cancelada
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        coVerify(exactly = 0) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.isComping.value).isFalse()

        // una segunda cortesía sí arranca
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.NoHaceFalta
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) } returns okComp
        viewModel.compWholeOrder("Reclamo")
        coVerify(exactly = 1) { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) }
    }

    @Test
    fun no_confirmada_muestra_el_texto_y_recarga_la_cuenta() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.ConPin("tok-1")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-1") } returns
            Result.failure(TablesRepository.NoConfirmadaException())
        val viewModel = conCuenta()
        clearMocks(repository, answers = false, recordedCalls = true)

        viewModel.compWholeOrder("Reclamo")

        assertThat(viewModel.error.value).isEqualTo("No pudimos confirmar la cortesía. Revisa la cuenta")
        coVerify(atLeast = 1) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.isComping.value).isFalse()
    }

    @Test
    fun `403_orders_comp_tras_NoHaceFalta_pide_pin_forzado_y_reintenta_una_vez`() = runTest {
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        coEvery { autorizacion.paraCortesia(venueId, any(), true) } returns Autorizacion.ConPin("tok-2")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-2") } returns okComp
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, "Para dar esta cortesía", true) }
        coVerify(exactly = 1) { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-2") }
        assertThat(viewModel.notice.value).isEqualTo("Cortesía aplicada")
    }

    @Test
    fun segundo_403_no_reintenta_otra_vez_y_muestra_el_texto() = runTest {
        val e = PermisoRequeridoException("orders:comp", true, "Esta cortesía la da un gerente con su sesión")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) } returns Result.failure(e)
        coEvery { autorizacion.paraCortesia(venueId, any(), true) } returns Autorizacion.ConPin("tok-2")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-2") } returns Result.failure(e)
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, any(), true) }
        coVerify(exactly = 2) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.error.value).isEqualTo("No pudimos confirmar la cortesía. Revisa la cuenta")
        assertThat(viewModel.isComping.value).isFalse()
    }

    @Test
    fun conPin_primer_intento_403_es_NoConfirmada_recarga_y_no_pide_otra_vez() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.ConPin("tok-1")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-1") } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        val viewModel = conCuenta()
        clearMocks(repository, answers = false, recordedCalls = true)

        viewModel.compWholeOrder("Reclamo")

        assertThat(viewModel.error.value).isEqualTo("No pudimos confirmar la cortesía. Revisa la cuenta")
        coVerify(atLeast = 1) { repository.getOrder(venueId, "orden-1") }
        coVerify(exactly = 1) { autorizacion.paraCortesia(any(), any(), any()) }
        assertThat(viewModel.isComping.value).isFalse()
    }

    @Test
    fun NoHaceFalta_403_luego_ConPin_403_es_NoConfirmada_recarga_y_sin_tercera_llamada() = runTest {
        val e = PermisoRequeridoException("orders:comp", true, "x")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) } returns Result.failure(e)
        coEvery { autorizacion.paraCortesia(venueId, any(), true) } returns Autorizacion.ConPin("tok-2")
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", "tok-2") } returns Result.failure(e)
        val viewModel = conCuenta()
        clearMocks(repository, answers = false, recordedCalls = true)

        viewModel.compWholeOrder("Reclamo")

        assertThat(viewModel.error.value).isEqualTo("No pudimos confirmar la cortesía. Revisa la cuenta")
        coVerify(atLeast = 1) { repository.getOrder(venueId, "orden-1") }
        coVerify(exactly = 2) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun forzada_cancelada_es_silenciosa_e_isComping_false() = runTest {
        coEvery { repository.compOrder(venueId, "orden-1", emptyList(), "Reclamo", "staff-1", null) } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        coEvery { autorizacion.paraCortesia(venueId, any(), true) } returns Autorizacion.Cancelada
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.isComping.value).isFalse()
        coVerify(exactly = 1) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun si_el_coordinador_lanza_se_muestra_error_e_isComping_false() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } throws IllegalStateException("boom")
        val viewModel = conCuenta()

        viewModel.compWholeOrder("Reclamo")

        assertThat(viewModel.error.value).isEqualTo("No se pudo aplicar la cortesía")
        assertThat(viewModel.isComping.value).isFalse()
    }

    /**
     * D4 (N86, 10-nov, TPV 2.18.0): con el PIN de gerente apagado, la 1.ª cortesía espera la red y la hoja se cierra; la
     * 2.ª, con «apagado» ya guardado, termina sin esperar nada. La pantalla observa el ViewModel con un colector que corre
     * DESPUÉS del toque (Compose), no dentro de él: eso es [observar]. Mirando [TableOrderViewModel.isComping], la 2.ª vez
     * nunca veía el true (la hoja se quedaba abierta tapando el aviso); por eso la hoja se cierra con
     * [TableOrderViewModel.compTerminadas].
     */
    private fun <T> TestScope.observar(flow: StateFlow<T>): List<T> {
        val vistos = mutableListOf<T>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { flow.collect { vistos += it } }
        runCurrent()
        return vistos
    }

    private val textoNegada = "Esta cortesía la da un gerente con su sesión"

    @Test
    fun D4_misma_pantalla_primera_vez_espera_la_red_segunda_contesta_al_instante_y_las_dos_terminan_y_avisan() = runTest {
        val red = CompletableDeferred<Autorizacion>()
        var llamadas = 0
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } coAnswers {
            if (++llamadas == 1) red.await() else Autorizacion.Negada(textoNegada)
        }
        val viewModel = conCuenta()
        val terminadas = observar(viewModel.compTerminadas)
        val avisos = observar(viewModel.error)

        // 1.ª: no hay nada guardado, espera la red.
        viewModel.compWholeOrder("Reclamo")
        runCurrent()
        red.complete(Autorizacion.Negada(textoNegada))
        runCurrent()
        assertThat(terminadas).containsExactly(0, 1).inOrder()
        viewModel.consumeError(textoNegada, 1) // la pantalla vacía el aviso al mostrarlo
        runCurrent()

        // 2.ª, en el mismo ViewModel: «apagado» ya guardado, termina dentro del toque.
        viewModel.compWholeOrder("Reclamo")
        runCurrent()
        assertThat(terminadas).containsExactly(0, 1, 2).inOrder()
        assertThat(avisos).containsExactly(null, textoNegada, null, textoNegada).inOrder()

        // 3.ª, con el aviso de la 2.ª aún en pantalla (sin vaciar): el error no cambia, sólo el contador. Por eso la
        // pantalla también repite el aviso con compTerminadas.
        viewModel.compWholeOrder("Reclamo")
        runCurrent()
        assertThat(terminadas).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(avisos).containsExactly(null, textoNegada, null, textoNegada).inOrder()
        coVerify(exactly = 0) { repository.compOrder(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun D4_el_aviso_viejo_no_vacia_uno_mas_nuevo_aunque_traiga_el_mismo_texto() = runTest {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.Negada(textoNegada)
        val viewModel = conCuenta()
        viewModel.compWholeOrder("Reclamo") // 1.ª: la pantalla lo muestra con el contador en 1
        viewModel.compWholeOrder("Reclamo") // 2.ª: mismo texto, contador en 2, antes de que la pantalla recomponga

        viewModel.consumeError(textoNegada, 1) // vence el 1.er aviso
        assertThat(viewModel.error.value).isEqualTo(textoNegada)
        viewModel.consumeError("Otro aviso", 2)
        assertThat(viewModel.error.value).isEqualTo(textoNegada)
        viewModel.consumeError(textoNegada, 2) // la pantalla ya mostró el 2.º
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun D4_con_la_pantalla_mirando_isComping_la_segunda_vez_no_se_ve_el_aplicando() = runTest {
        // La causa, fijada: si alguien vuelve a cerrar la hoja con la bajada de isComping, esto explica por qué no sirve.
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.Negada(textoNegada)
        val viewModel = conCuenta()
        val aplicando = observar(viewModel.isComping)

        viewModel.compWholeOrder("Reclamo")
        runCurrent()

        assertThat(aplicando).containsExactly(false)
        assertThat(viewModel.error.value).isEqualTo(textoNegada)
    }

    // endregion

    // region — D86: la pantalla de la mesa se pone al día sola y «Pagar» de una mesa abierta sin red

    @Test
    fun D86_mesa_abierta_sin_red_enseña_pagar_con_lo_que_le_mandó_esta_terminal() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        coEvery { syncOutbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) } returns "r1"
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            SyncIntentEntity(id = "abrir", venueId = venueId, seq = 1, type = SyncIntentTypes.OPEN_TABLE, payloadJson = """{"tableId":"mesa-1","localOrderId":"local-1"}"""),
            SyncIntentEntity(id = "r1", venueId = venueId, seq = 2, type = SyncIntentTypes.ADD_ITEMS, payloadJson = """{"localOrderId":"local-1","items":[]}"""),
        )
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("45.00"))

        viewModel.sendRound()

        val saldo = viewModel.saldoSinRed.value
        assertThat(saldo).isInstanceOf(SaldoSinRed.Conocido::class.java)
        assertThat((saldo as SaldoSinRed.Conocido).porPagar).isEqualTo(BigDecimal("90.00"))
        assertThat(checkoutButtonVisible(readOnlyForPayment = false, isProvisional = true, checkTotal = null, saldoProvisional = saldo.porPagar)).isTrue()
    }

    @Test
    fun D86_al_confirmarse_la_apertura_la_pantalla_lee_la_cuenta_sola() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1", total = BigDecimal("20.00")))
        val viewModel = createViewModel()
        coVerify(exactly = 0) { repository.getOrder(any(), any()) }

        tableSession.promoteProvisional("local-1", "orden-1", "ORD-1", 2)

        coVerify(exactly = 1) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.check.value?.total).isEqualTo(BigDecimal("20.00"))
        assertThat(viewModel.saldoSinRed.value).isNull()
    }

    @Test
    fun D86_al_volver_la_red_la_pantalla_lee_la_cuenta_sola() = runTest {
        val red = MutableStateFlow(NetworkInfo(type = NetworkType.WIFI, isMetered = false, isConnected = false, signalStrength = null))
        every { networkMonitor.isConnected() } returns false
        every { networkMonitor.networkStateFlow } returns red
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        createViewModel()
        coVerify(exactly = 1) { repository.getOrder(venueId, "orden-1") }

        red.value = red.value.copy(isConnected = true)

        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
    }

    // endregion

    @Test
    fun D86_r4_un_calculo_viejo_del_saldo_que_llega_tarde_no_esconde_pagar() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        val apertura = SyncIntentEntity(id = "abrir", venueId = venueId, seq = 1, type = SyncIntentTypes.OPEN_TABLE, payloadJson = """{"tableId":"mesa-1","localOrderId":"local-1"}""")
        val ronda = SyncIntentEntity(id = "r1", venueId = venueId, seq = 2, type = SyncIntentTypes.ADD_ITEMS, payloadJson = """{"localOrderId":"local-1","items":[]}""")
        val vieja = CompletableDeferred<Unit>()
        var consultas = 0
        // La primera consulta (antes de la ronda) tarda y llega al final, sin la ronda.
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } coAnswers {
            consultas++
            if (consultas == 1) { vieja.await(); listOf(apertura) } else listOf(apertura, ronda)
        }
        coEvery { syncOutbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) } returns "r1"
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("100.00"))
        viewModel.sendRound()
        assertThat((viewModel.saldoSinRed.value as SaldoSinRed.Conocido).porPagar).isEqualTo(BigDecimal("100.00"))

        vieja.complete(Unit)

        assertThat((viewModel.saldoSinRed.value as SaldoSinRed.Conocido).porPagar).isEqualTo(BigDecimal("100.00"))
    }

    @Test
    fun D86_r5_2_si_la_apertura_se_confirma_antes_que_su_ronda_por_sincronizar_se_queda_hasta_que_suba() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        coEvery { syncOutbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) } returns "r1"
        val viewModel = createViewModel()
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("100.00"))
        viewModel.sendRound()
        assertThat(viewModel.queuedLines.value).hasSize(1)

        // La apertura sube, la ronda todavía no: el SERVIDOR devuelve la cuenta vacía.
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            SyncIntentEntity(id = "r1", venueId = venueId, seq = 2, type = SyncIntentTypes.ADD_ITEMS, payloadJson = """{"localOrderId":"local-1","items":[]}"""),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        tableSession.promoteProvisional("local-1", "orden-1", "ORD-1", 2)

        assertThat(viewModel.queuedLines.value).hasSize(1)

        // Sube la ronda: la siguiente lectura sola ya la trae y «Por sincronizar» se va.
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns emptyList()
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()
        assertThat(viewModel.queuedLines.value).isEmpty()
    }

    @Test
    fun D86_r7_H2_cuando_la_ronda_sube_por_otro_camino_la_pantalla_se_relee_sola() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        // La ronda se encola sin red y aparece «Por sincronizar».
        coEvery { repository.addItems(venueId, "orden-1", any(), any()) } returns
            Result.success(OrderDetail(id = "orden-1", items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-1", quantity = 1))))
        pendingCart.addSimple(productId = "prod-1", name = "Café", unitPrice = BigDecimal("100.00"))
        viewModel.sendRound()
        assertThat(viewModel.queuedLines.value).hasSize(1)

        // El trabajador la sube: la cola cambia y, tras la calma, la pantalla relee sola.
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), items = listOf(OrderDetailItem(id = "i1", productId = "prod-1", quantity = 1, total = BigDecimal("100.00")))))
        cambiosDeLaCola.value = 1
        testDispatcher.scheduler.advanceTimeBy(com.jaac.avoqado_tpv.features.tables.data.CALMA_DE_LA_COLA_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertThat(viewModel.queuedLines.value).isEmpty()
        assertThat(viewModel.check.value?.total).isEqualTo(BigDecimal("100.00"))
    }

    // region — D16 (hz:033d44ca:D16): la ronda que cayó en una cuenta aparte se avisa en la cuenta cobrada

    /** El ACK de la ronda sin red tal cual lo grabó la N86 el 16-nov (acta de D7). Un reintento sólo trae `cuentaAparte.orderId`. */
    private fun ackDeRondaEnCuentaAparte(cuentaAparte: String = """{"total":89,"orderId":"O2","version":2,"orderNumber":"ORD-2"}""") = SyncIntentEntity(
        id = "ronda-sin-red", venueId = venueId, seq = 2, type = SyncIntentTypes.ADD_ITEMS, payloadJson = """{"orderId":"O1"}""",
        status = SyncIntentEntity.STATUS_ACKED,
        resultJson = """{"total":25,"orderId":"O1","version":3,"cuentaAparte":$cuentaAparte}""",
    )

    private fun cuentaCobrada() = OrderDetail(
        id = "O1", total = BigDecimal("25.00"), amountLeft = BigDecimal.ZERO, paymentStatus = "PAID", version = 3,
        items = listOf(OrderDetailItem(id = "i1", productId = "coca", quantity = 1, total = BigDecimal("25.00"))),
    )

    /** La cuenta aparte como la lee el SERVIDOR (`getOrder`: `tableName` = «Mesa <número>»). */
    private fun cuentaAparteLeida(mesa: String = "mesa-5", nombreDeMesa: String = "Mesa M5", version: Int = 2) = OrderDetail(
        id = "O2", orderNumber = "ORD-2", total = BigDecimal("89.00"), amountLeft = BigDecimal("89.00"), paymentStatus = "PENDING",
        version = version, tableId = mesa, tableName = nombreDeMesa,
        items = listOf(OrderDetailItem(id = "i2", productId = "tacos", quantity = 1, total = BigDecimal("89.00"))),
    )

    private fun enLaCuentaCobradaDeM5() {
        tableSession.start(TableSession.Active(tableId = "mesa-5", tableNumber = "M5", areaName = "Barra", orderId = "O1", total = BigDecimal("25.00")))
        coEvery { repository.getOrder(venueId, "O1") } returns Result.success(cuentaCobrada())
    }

    private fun calmaDeLaCola() {
        testDispatcher.scheduler.advanceTimeBy(com.jaac.avoqado_tpv.features.tables.data.CALMA_DE_LA_COLA_MS + 1)
        testDispatcher.scheduler.runCurrent()
    }

    private val avisoDeM5 = "Tu ronda no se perdió: como esta cuenta ya estaba cobrada, quedó en una cuenta aparte de la Mesa M5 por $89.00."

    @Test
    fun D16_al_entrar_a_la_cuenta_cobrada_avisa_que_la_ronda_quedo_en_la_cuenta_aparte() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())

        val vm = createViewModel()

        assertThat(vm.cuentaAparte.value?.texto).isEqualTo(avisoDeM5)
        assertThat(vm.cuentaAparte.value?.orderId).isEqualTo("O2")
        // La cuenta que se ve sigue siendo la cobrada (el aviso no la cambia solo) y el aviso no le pregunta nada al SERVIDOR:
        // sólo dice lo que el ACK guardó, que ya no cambia (Codex r2).
        assertThat(tableSession.current()?.orderId).isEqualTo("O1")
        coVerify(exactly = 0) { repository.getOrder(venueId, "O2") }
    }

    @Test
    fun D16_el_ack_que_llega_con_la_pantalla_abierta_da_el_aviso() = runTest {
        enLaCuentaCobradaDeM5()
        val vm = createViewModel()
        assertThat(vm.cuentaAparte.value).isNull()

        // El trabajador sube la ronda con la pantalla abierta: el ACK se guarda en la cola y la cola avisa.
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        cambiosDeLaCola.value = 1
        calmaDeLaCola()

        assertThat(vm.cuentaAparte.value?.texto).isEqualTo(avisoDeM5)
    }

    /** Codex r1 #1: el ACK ya está en la cola, pero la relectura de la cuenta cobrada falla (timeout): el aviso sale igual. */
    @Test
    fun D16_el_aviso_sale_aunque_la_cuenta_cobrada_no_se_pueda_releer() = runTest {
        enLaCuentaCobradaDeM5()
        val vm = createViewModel()

        coEvery { repository.getOrder(venueId, "O1") } returns Result.failure(java.net.SocketTimeoutException("timeout"))
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        cambiosDeLaCola.value = 1
        calmaDeLaCola()

        assertThat(vm.cuentaAparte.value?.texto).isEqualTo(avisoDeM5)
    }

    @Test
    fun D16_el_reintento_que_solo_trae_el_id_avisa_sin_monto() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte("""{"orderId":"O2"}"""))

        val vm = createViewModel()

        assertThat(vm.cuentaAparte.value?.texto)
            .isEqualTo("Tu ronda no se perdió: como esta cuenta ya estaba cobrada, quedó en una cuenta aparte de la Mesa M5.")
    }

    @Test
    fun D16_sin_ronda_en_cuenta_aparte_no_hay_aviso() = runTest {
        enLaCuentaCobradaDeM5()
        val vm = createViewModel()
        assertThat(vm.cuentaAparte.value).isNull()
    }

    @Test
    fun D16_abrir_la_cuenta_aparte_la_lee_y_la_carga_en_la_misma_mesa_con_el_carrito() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        coEvery { repository.getOrder(venueId, "O2") } returns Result.success(cuentaAparteLeida())
        val vm = createViewModel()
        // Lo que tenía sin mandar en la cuenta cobrada no se pierde: se va a la cuenta aparte.
        pendingCart.addSimple(productId = "agua", name = "Agua", unitPrice = BigDecimal("20.00"))

        vm.abrirCuentaAparte()

        val sesion = tableSession.current()!!
        assertThat(sesion.orderId).isEqualTo("O2")
        assertThat(sesion.tableId).isEqualTo("mesa-5")
        assertThat(sesion.label).isEqualTo("Mesa M5 · Barra")
        assertThat(sesion.orderNumber).isEqualTo("ORD-2")
        assertThat(sesion.version).isEqualTo(2)
        assertThat(sesion.total).isEqualTo(BigDecimal("89.00"))
        assertThat(sesion.isProvisional).isFalse()
        assertThat(sesion.localOrderId).isNull()
        assertThat(sesion.mode).isEqualTo(TableSession.Mode.ORDERING)
        assertThat(vm.check.value?.id).isEqualTo("O2")
        assertThat(vm.pendingLines.value.map { it.productId }).containsExactly("agua")
        assertThat(vm.abriendoCuentaAparte.value).isFalse()
        // En la cuenta aparte (que debe) no hay aviso.
        assertThat(vm.cuentaAparte.value).isNull()
    }

    /** Codex r1 #3 y r2 #2: otro aparato movió la cuenta aparte a M6: se abre donde el SERVIDOR dice que está AL TOCAR. */
    @Test
    fun D16_si_movieron_la_cuenta_aparte_a_otra_mesa_se_abre_en_esa() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        coEvery { repository.getOrder(venueId, "O2") } returns Result.success(cuentaAparteLeida(mesa = "mesa-6", nombreDeMesa = "Mesa M6"))
        val vm = createViewModel()

        vm.abrirCuentaAparte()

        val sesion = tableSession.current()!!
        assertThat(sesion.tableId).isEqualTo("mesa-6")
        assertThat(sesion.label).isEqualTo("Mesa M6")
        assertThat(sesion.orderId).isEqualTo("O2")
    }

    @Test
    fun D16_si_no_se_puede_leer_la_cuenta_aparte_no_se_mueve_y_lo_dice() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        coEvery { repository.getOrder(venueId, "O2") } returns Result.failure(java.io.IOException("sin red"))
        val vm = createViewModel()
        pendingCart.addSimple(productId = "agua", name = "Agua", unitPrice = BigDecimal("20.00"))

        vm.abrirCuentaAparte()

        assertThat(tableSession.current()?.orderId).isEqualTo("O1")
        assertThat(vm.error.value).isEqualTo(TableOrderViewModel.NO_SE_ABRIO_LA_CUENTA_APARTE)
        assertThat(vm.cuentaAparte.value?.orderId).isEqualTo("O2") // el aviso se queda para volver a intentar
        assertThat(vm.pendingLines.value.map { it.productId }).containsExactly("agua")
        assertThat(vm.abriendoCuentaAparte.value).isFalse()
    }

    /**
     * Codex r3: otra ronda de esta terminal a la cobrada sigue sin subir (su reenvío dio RETRY). Esa ronda nombra la cobrada: en la
     * cuenta aparte nadie la vería («Por sincronizar» se vaciaba) ni frenaría su cobro. No se abre hasta que suba.
     */
    @Test
    fun D16_con_otra_ronda_de_la_cobrada_sin_subir_no_abre_la_cuenta_aparte() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        coEvery { repository.getOrder(venueId, "O2") } returns Result.success(cuentaAparteLeida())
        val vm = createViewModel()
        coEvery { syncOutbox.vivasQueMencionan(venueId, "O1") } returns listOf(
            SyncIntentEntity(id = "otra-ronda", venueId = venueId, seq = 3, type = SyncIntentTypes.ADD_ITEMS, payloadJson = """{"orderId":"O1","items":[]}"""),
        )

        vm.abrirCuentaAparte()

        assertThat(tableSession.current()?.orderId).isEqualTo("O1")
        assertThat(vm.error.value).isEqualTo(TableOrderViewModel.ESPERA_A_QUE_SE_SUBA_PARA_ABRIR)
        assertThat(vm.cuentaAparte.value?.orderId).isEqualTo("O2")
        assertThat(vm.abriendoCuentaAparte.value).isFalse()
    }

    /** Codex r4: «Abrir» y «Enviar ronda» no se cruzan: una ronda nueva nunca nace para la cobrada a media apertura. */
    @Test
    fun D16_mientras_se_abre_la_cuenta_aparte_no_se_manda_una_ronda() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        val tarde = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "O2") } coAnswers { tarde.await() }
        val vm = createViewModel()
        pendingCart.addSimple(productId = "agua", name = "Agua", unitPrice = BigDecimal("20.00"))

        vm.abrirCuentaAparte()
        vm.sendRound()
        coVerify(exactly = 0) { repository.addItems(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncOutbox.enqueue(any(), any(), any(), any()) }

        tarde.complete(Result.success(cuentaAparteLeida()))
        runCurrent()
        assertThat(tableSession.current()?.orderId).isEqualTo("O2")
        assertThat(vm.pendingLines.value.map { it.productId }).containsExactly("agua")
    }

    @Test
    fun D16_mientras_se_manda_una_ronda_no_se_abre_la_cuenta_aparte() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        val enviando = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.addItems(venueId, "O1", any(), any()) } coAnswers { enviando.await() }
        val vm = createViewModel()
        pendingCart.addSimple(productId = "agua", name = "Agua", unitPrice = BigDecimal("20.00"))

        vm.sendRound()
        vm.abrirCuentaAparte()

        coVerify(exactly = 0) { repository.getOrder(venueId, "O2") }
        assertThat(tableSession.current()?.orderId).isEqualTo("O1")
        enviando.complete(Result.failure(java.io.IOException("sin red")))
        runCurrent()
    }

    @Test
    fun D16_si_sale_de_la_cuenta_mientras_se_lee_la_aparte_no_la_abre() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        val tarde = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "O2") } coAnswers { tarde.await() }
        val vm = createViewModel()

        vm.abrirCuentaAparte()
        vm.abrirCuentaAparte() // doble toque: una sola lectura
        assertThat(vm.abriendoCuentaAparte.value).isTrue()
        vm.exitToFloor()
        tarde.complete(Result.success(cuentaAparteLeida()))
        runCurrent()

        assertThat(tableSession.current()).isNull()
        coVerify(exactly = 1) { repository.getOrder(venueId, "O2") }
    }

    @Test
    fun D16_una_lectura_vieja_de_la_cuenta_cobrada_no_se_pinta_sobre_la_cuenta_aparte() = runTest {
        enLaCuentaCobradaDeM5()
        coEvery { syncOutbox.confirmadasConCuentaAparte(venueId, any()) } returns listOf(ackDeRondaEnCuentaAparte())
        // La lectura al tocar «Abrir» contesta; la carga que sigue se queda esperando (la red está lenta).
        var lecturasDeO2 = 0
        val tardeO2 = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "O2") } coAnswers { if (++lecturasDeO2 == 1) Result.success(cuentaAparteLeida()) else tardeO2.await() }
        val vm = createViewModel()
        // Una relectura de la cobrada sale y tarda; mientras, la mesera abre la cuenta aparte.
        val tardeO1 = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "O1") } coAnswers { tardeO1.await() }
        vm.actualizarCuenta()
        vm.abrirCuentaAparte()
        assertThat(tableSession.current()?.orderId).isEqualTo("O2")

        tardeO1.complete(Result.success(cuentaCobrada()))
        runCurrent()
        // La cuenta cobrada llega tarde: no se pinta ni le pone su versión (3) a la sesión de la cuenta aparte.
        assertThat(vm.check.value?.id).isEqualTo("O2")
        assertThat(tableSession.current()?.version).isEqualTo(2)
    }

    // endregion
}
