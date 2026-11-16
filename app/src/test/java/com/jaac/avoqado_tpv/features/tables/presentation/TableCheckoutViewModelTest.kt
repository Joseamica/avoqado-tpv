package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.CAMBIOS_SIN_SINCRONIZAR
import com.jaac.avoqado_tpv.features.tables.data.CUENTA_SE_ACTUALIZA
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_SIN_SINCRONIZAR
import com.jaac.avoqado_tpv.features.tables.data.COBRO_CON_LO_DE_ESTA_TERMINAL
import com.jaac.avoqado_tpv.features.tables.data.SALDO_NO_RECUPERADO
import com.jaac.avoqado_tpv.features.tables.data.SIN_CUENTA_SIN_RED
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.SaldoDeMesaSinRed
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
    private val syncOutbox = mockk<SyncOutbox>()
    private val cambiosDeLaCola = kotlinx.coroutines.flow.MutableStateFlow(0)
    private lateinit var tableSession: TableSession
    private lateinit var letrero: ConnectionStateManager

    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, tableSyncCoordinator, networkMonitor, deviceInfoManager)

        tableSession = TableSession()
        letrero = ConnectionStateManager()

        every { deviceInfoManager.getVenueId() } returns venueId
        every { networkMonitor.isConnected() } returns true
        every { networkMonitor.networkStateFlow } returns emptyFlow()
        coEvery { tableSyncCoordinator.replay(any()) } returns Unit
        coEvery { syncOutbox.vivasQueMencionan(any(), any()) } returns emptyList()
        coEvery { syncOutbox.aperturasConfirmadasQueMencionan(any(), any()) } returns emptyList()
        coEvery { syncOutbox.aperturasConfirmadasConResultado(any(), any()) } returns emptyList()
        every { syncOutbox.observarCambios(any()) } returns cambiosDeLaCola
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(saldo: SaldoDeMesaSinRed = SaldoDeMesaSinRed(syncOutbox)): TableCheckoutViewModel = TableCheckoutViewModel(
        repository = repository,
        tableSession = tableSession,
        tableSyncCoordinator = tableSyncCoordinator,
        networkMonitor = networkMonitor,
        deviceInfoManager = deviceInfoManager,
        saldoDeMesaSinRed = saldo,
        connectionStateManager = letrero,
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
     * D90 (PAX, 16-nov): en «Cargando cuenta…» la tarjeta se veía habilitada, y con el TOTAL del plano (no lo que falta): un
     * toque ahí podía cobrar con la tarjeta una cuenta que ya tenía abonos. Mientras la cuenta no se lee, la tarjeta espera.
     */
    @Test
    fun D90_mientras_carga_la_cuenta_la_tarjeta_queda_en_gris() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("118.00")))
        val servidor = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { servidor.await() }
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.isLoading).isTrue()
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo("Cargando la cuenta…")

        servidor.complete(Result.success(OrderDetail(id = "orden-1", total = BigDecimal("118.00"), amountLeft = BigDecimal("78.00"))))

        assertThat(viewModel.state.value.cardPaymentEnabled).isTrue()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isNull()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D90_si_la_cuenta_no_se_pudo_leer_la_tarjeta_queda_en_gris_aunque_haya_red() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("118.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(BackendHttpException(500, "falla"))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.check).isNull()
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo("No se pudo cargar la cuenta")
        viewModel.viewModelScope.cancel()
    }

    /**
     * D90: al volver la red, el aparato avisa ~2 s antes de que el letrero «Sin conexión a internet» se quite (lo quita la
     * comprobación de conexión, no el aviso del aparato). En ese rato la tarjeta se veía habilitada bajo el letrero.
     */
    @Test
    fun D90_con_el_letrero_de_sin_internet_puesto_la_tarjeta_queda_en_gris_aunque_el_aparato_ya_tenga_red() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("118.00"), amountLeft = BigDecimal("118.00")))
        letrero.setInternetConnected(false)
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.isOnline).isTrue()
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo("Necesita conexión")

        letrero.setInternetConnected(true)

        assertThat(viewModel.state.value.cardPaymentEnabled).isTrue()
        viewModel.viewModelScope.cancel()
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

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
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
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        // El monto paga el restante COMPLETO — onPaymentCompleted libera la
        // mesa sola, así que releaseTable() sí llama clearTable.
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)

        viewModel.payCash(BigDecimal("150.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) }
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.notice).contains("Sin conexión")
    }

    @Test
    fun un_cobro_en_efectivo_rechazado_marca_error_y_NO_mueve_el_restante() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException("El cobro fue rechazado", "INVALID_PAYLOAD"))

        viewModel.payCash(BigDecimal("150.00"))

        assertThat(viewModel.state.value.error).isEqualTo("El cobro fue rechazado")
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
        assertThat(viewModel.state.value.tableReleased).isFalse()
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
    }

    @Test
    fun un_efectivo_rechazado_por_numero_le_dice_a_la_cajera_que_no_se_cobro_y_donde_verlo() = runTest {
        // D6: el texto del servidor («La secuencia del dispositivo ya fue utilizada…») no le dice nada a la cajera.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException(
                "La secuencia del dispositivo ya fue utilizada. Requiere revisión antes de repetir la operación.",
                "STALE_DEVICE_SEQUENCE",
            ))

        viewModel.payCash(BigDecimal("150.00"))

        assertThat(viewModel.state.value.error).isEqualTo(
            "No se registró el cobro: la terminal perdió su numeración (¿se reinstaló la app?). Avisa al gerente: queda en «Acciones rechazadas» de Mesas.",
        )
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
    }

    // endregion

    // region — 🧾 recibo de EFECTIVO: cierra la asimetría con TARJETA (propina ya
    // viajaba; lo nuevo es receiptUrl/autofactura + el gate de "invitar a
    // calificar SOLO al saldar la cuenta"). Ver KDoc de [CashReceiptSummary].

    @Test
    fun un_cobro_en_efectivo_exitoso_arma_cashReceipt_con_monto_propina_y_total() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), BigDecimal("20.00"), any()) } returns
            Result.success(
                TablesRepository.PayCashOutcome(
                    paymentId = "pay-1",
                    orderNumber = "A-100",
                    queued = false,
                    receiptUrl = "https://avoqado.io/r/1",
                    autofacturaAvailable = true,
                ),
            )
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)

        viewModel.payCash(BigDecimal("150.00"))

        val receipt = viewModel.state.value.cashReceipt
        assertThat(receipt).isNotNull()
        assertThat(receipt!!.amount).isEqualTo(BigDecimal("150.00"))
        assertThat(receipt.tipAmount).isEqualTo(BigDecimal("20.00"))
        assertThat(receipt.totalAmount).isEqualTo(BigDecimal("170.00"))
        assertThat(receipt.orderNumber).isEqualTo("A-100")
        assertThat(receipt.receiptUrl).isEqualTo("https://avoqado.io/r/1")
        assertThat(receipt.autofacturaAvailable).isTrue()
        assertThat(receipt.queued).isFalse()
    }

    /**
     * Regresión explícita de "Tip: on every payment": la propina tecleada en
     * pantalla SIEMPRE viaja al `payCash()` real — un refactor que la
     * olvidara (p.ej. al tocar el orden de los parámetros) pasaría en verde
     * si este test no capturara el valor EXACTO.
     */
    @Test
    fun la_propina_tecleada_SIEMPRE_viaja_al_payCash_de_EFECTIVO() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("15.50"))
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        // El monto paga el restante COMPLETO — onPaymentCompleted libera la
        // mesa sola, así que releaseTable() sí llama clearTable.
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)

        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("100.00"), BigDecimal("15.50"), any()) }
    }

    /** Gate central del founder: solo el pago que SALDA la cuenta invita a calificar. */
    @Test
    fun isSettlingPayment_true_cuando_el_monto_paga_el_restante_completo() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "pay-1", orderNumber = "A-1", queued = false))
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)

        viewModel.payCash(BigDecimal("150.00"))

        assertThat(viewModel.state.value.cashReceipt?.isSettlingPayment).isTrue()
    }

    /**
     * La regla dura del founder: pedir rating en CADA pago de una cuenta
     * dividida crearía un `Review` por split para la MISMA visita y sesgaría
     * el promedio del venue (`Review.venueId` los promedia TODOS —
     * `avoqado-server/prisma/schema.prisma:3716`). Un pago parcial jamás
     * debe traer `isSettlingPayment=true`.
     */
    @Test
    fun isSettlingPayment_false_en_un_pago_PARCIAL_de_una_cuenta_dividida() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal("200.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("50.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "pay-1", orderNumber = "A-1", queued = false))

        viewModel.payCash(BigDecimal("50.00")) // 1 de 4 partes de $200

        assertThat(viewModel.state.value.cashReceipt?.isSettlingPayment).isFalse()
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
    }

    @Test
    fun un_cobro_en_efectivo_encolado_offline_TAMBIEN_produce_cashReceipt_sin_receiptUrl() = runTest {
        // "Receipt: on every payment" incluye el camino offline — sin URL
        // digital todavía (el server no lo generó), pero la confirmación de
        // recibo SÍ aparece; nunca se queda en silencio como antes del fix.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)

        viewModel.payCash(BigDecimal("150.00"))

        val receipt = viewModel.state.value.cashReceipt
        assertThat(receipt).isNotNull()
        assertThat(receipt!!.queued).isTrue()
        assertThat(receipt.receiptUrl).isNull()
    }

    @Test
    fun consumeCashReceipt_limpia_el_recibo_del_estado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "pay-1", orderNumber = "A-1", queued = false))
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        viewModel.payCash(BigDecimal("150.00"))
        assertThat(viewModel.state.value.cashReceipt).isNotNull()

        viewModel.consumeCashReceipt()

        assertThat(viewModel.state.value.cashReceipt).isNull()
    }

    /** Regresión: TARJETA sigue sin tocar el mecanismo de EFECTIVO ni su recibo. */
    @Test
    fun un_cargo_con_TARJETA_nunca_arma_cashReceipt() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()

        viewModel.buildPaymentPayload(BigDecimal("150.00"))

        assertThat(viewModel.state.value.cashReceipt).isNull()
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
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
    }

    @Test
    fun al_llegar_a_cero_la_mesa_se_libera_sola() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal("200.00")))
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("200.00"))

        // BigDecimal("200.00") - BigDecimal("200.00") = BigDecimal("0.00") —
        // NO es .equals() a BigDecimal.ZERO (escala 0 vs 2); lo que importa
        // para "se liberó la mesa" es compareTo == 0, no la escala exacta.
        assertThat(viewModel.state.value.remaining.compareTo(BigDecimal.ZERO)).isEqualTo(0)
        assertThat(viewModel.state.value.tableReleased).isTrue()
        coVerify(exactly = 1) { repository.clearTable(venueId, "mesa-1", "orden-1", false) }
    }

    @Test
    fun D85_cobrar_una_cuenta_de_la_mesa_mientras_otra_debe_suelta_su_sesion_y_deja_la_mesa_ocupada() = runTest {
        // Codex r1 #2: M8 tiene la cuenta B (de la terminal, $20) y la A (de otro mesero, $30). Se cobra B completa: el
        // SERVIDOR no libera la mesa porque A debe (400). B ya no debe, así que la sesión se suelta: no queda «Tu mesa»
        // pegada a una cuenta pagada. La mesa sigue ocupada por A (no se reintenta ni se encola nada).
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder(venueId, "b") } returnsMany listOf(
            Result.success(OrderDetail(id = "b", total = BigDecimal("20.00"), amountLeft = BigDecimal("20.00"))),
            Result.success(OrderDetail(id = "b", status = "COMPLETED", paymentStatus = "PAID", total = BigDecimal("20.00"))),
        )
        coEvery { repository.clearTable(venueId, "m8", "b", false) } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "Cannot clear table with unpaid order ORD-A"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("20.00"))

        assertThat(viewModel.state.value.tableReleased).isTrue()
        assertThat(tableSession.current()).isNull()
        coVerify(exactly = 1) { repository.clearTable(venueId, "m8", "b", false) }
    }

    @Test
    fun D85_si_el_servidor_dice_que_SU_cuenta_todavia_debe_la_sesion_se_queda() = runTest {
        // El caso que ya protegía el rechazo: la propina de una tarjeta todavía no se refleja y el SERVIDOR ve la cuenta
        // con saldo. La sesión se queda para poder volver a ella.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00"))),
            Result.success(OrderDetail(id = "orden-1", status = "PENDING", paymentStatus = "PARTIAL", total = BigDecimal("100.00"))),
        )
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "Cannot clear table with unpaid order ORD-1"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("100.00"))

        assertThat(tableSession.current()?.orderId).isEqualTo("orden-1")
    }

    @Test
    fun D85_cerrar_el_recibo_antes_de_que_conteste_no_deja_la_sesion_pegada_a_la_cuenta_pagada() = runTest {
        // Codex r2 #2: la pregunta por la cuenta tarda y el cajero cierra el recibo (la pantalla se va y su scope se
        // cancela). La liberación termina igual.
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        val respuesta = CompletableDeferred<Result<OrderDetail>>()
        var llamadas = 0
        coEvery { repository.getOrder(venueId, "b") } coAnswers {
            llamadas++
            if (llamadas == 1) {
                Result.success(OrderDetail(id = "b", total = BigDecimal("20.00"), amountLeft = BigDecimal("20.00")))
            } else {
                respuesta.await()
            }
        }
        coEvery { repository.clearTable(venueId, "m8", "b", false) } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "Cannot clear table with unpaid order ORD-A"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("20.00"))
        viewModel.viewModelScope.cancel()
        respuesta.complete(Result.success(OrderDetail(id = "b", status = "COMPLETED", paymentStatus = "PAID")))

        assertThat(tableSession.current()).isNull()
    }

    @Test
    fun D85_una_liberacion_que_llega_tarde_no_borra_la_sesion_de_otra_mesa_que_ya_abrio_el_mesero() = runTest {
        // La liberación ya no muere con la pantalla: si contesta tarde y el mesero ya entró a otra mesa, no la toca.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("50.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("50.00"), amountLeft = BigDecimal("50.00")))
        val servidor = CompletableDeferred<Result<Unit>>()
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } coAnswers { servidor.await() }
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("50.00"))
        tableSession.start(TableSession.Active(tableId = "mesa-2", orderId = "orden-2"))
        servidor.complete(Result.success(Unit))

        assertThat(tableSession.current()?.orderId).isEqualTo("orden-2")
    }

    @Test
    fun D85_si_no_se_puede_preguntar_por_la_cuenta_la_sesion_se_queda() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder(venueId, "b") } returnsMany listOf(
            Result.success(OrderDetail(id = "b", total = BigDecimal("20.00"), amountLeft = BigDecimal("20.00"))),
            Result.failure(java.io.IOException("sin red")),
        )
        coEvery { repository.clearTable(venueId, "m8", "b", false) } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "Cannot clear table with unpaid order ORD-A"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("20.00"))

        assertThat(tableSession.current()?.orderId).isEqualTo("b")
    }

    @Test
    fun un_pago_de_MAS_del_restante_nunca_deja_el_restante_en_negativo() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("50.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("50.00"), amountLeft = BigDecimal("50.00")))
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
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

    @Test
    fun D79_mesa_abierta_sin_red_al_llegar_a_cero_libera_nombrando_su_cuenta_local() = runTest {
        // La mesa se abrió sin red: «Liberar mesa» debe decir QUÉ cuenta soltó (su id local), para que el SERVIDOR
        // no rechace por la otra cuenta que la mesa recibió mientras tanto (D51/D71) y no quede nada por revisar.
        tableSession.open(tableId = "mesa-1", localOrderId = "local-uuid-123")
        coEvery { repository.clearTable(any(), any(), any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("100.00"))

        coVerify(exactly = 1) { repository.clearTable(venueId, "mesa-1", "local-uuid-123", true) }
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

    // region — D9 (12-nov): la propina que escribe el mesero viaja con el cobro con TARJETA, y se cobra UNA vez

    @Test
    fun d9_la_propina_escrita_viaja_en_la_solicitud_de_TARJETA() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("845.50")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("845.50"), amountLeft = BigDecimal("845.50")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("50.00"))

        val payload = viewModel.buildPaymentPayload(BigDecimal("845.50"))

        assertThat(payload.amount).isEqualTo(BigDecimal("845.50"))
        assertThat(payload.tipAmount).isEqualTo(BigDecimal("50.00"))
    }

    @Test
    fun d9_entregar_el_cobro_a_TARJETA_lleva_la_propina_y_al_abrirse_Cobrar_vacia_la_casilla() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("845.50")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("845.50"), amountLeft = BigDecimal("845.50")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("50.00"))

        val payload = viewModel.prepararCobroConTarjeta(BigDecimal("400.00"))!!
        assertThat(payload.tipAmount).isEqualTo(BigDecimal("50.00"))
        assertThat(viewModel.state.value.isHandingOffToCard).isTrue()

        viewModel.cobroConTarjetaEntregado()

        // 🔴 Una propina, un cobro: si la pantalla vieja vuelve a cobrar (otra parte de la cuenta), no la repite.
        assertThat(viewModel.state.value.tipAmount).isEqualTo(BigDecimal.ZERO)
        assertThat(viewModel.state.value.isHandingOffToCard).isFalse()
        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("445.50"))!!.tipAmount).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun d9_si_Cobrar_no_se_abre_la_propina_se_queda_escrita() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("845.50")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("845.50"), amountLeft = BigDecimal("845.50")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("50.00"))

        viewModel.prepararCobroConTarjeta(BigDecimal("845.50"))
        viewModel.cobroConTarjetaNoSalio()

        assertThat(viewModel.state.value.tipAmount).isEqualTo(BigDecimal("50.00"))
        assertThat(viewModel.state.value.isHandingOffToCard).isFalse()
        // El segundo intento la vuelve a llevar.
        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("845.50"))!!.tipAmount).isEqualTo(BigDecimal("50.00"))
    }

    @Test
    fun d9_con_efectivo_esperando_respuesta_TARJETA_no_arranca_y_la_propina_no_sale_dos_veces() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("1000.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("1000.00"), amountLeft = BigDecimal("1000.00")))
        val respuesta = kotlinx.coroutines.CompletableDeferred<Result<TablesRepository.PayCashOutcome>>()
        coEvery { repository.payCash(venueId, "orden-1", false, any(), any(), any()) } coAnswers { respuesta.await() }
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))

        viewModel.payCash(BigDecimal("100.00"))
        assertThat(viewModel.state.value.isProcessingCash).isTrue()

        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("100.00"))).isNull()

        respuesta.complete(Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = null, queued = false)))
        assertThat(viewModel.state.value.tipAmount).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun d9_con_TARJETA_abriendose_el_efectivo_no_cobra() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("1000.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("1000.00"), amountLeft = BigDecimal("1000.00")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))

        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("100.00"))).isNotNull()
        viewModel.payCash(BigDecimal("100.00"))
        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("100.00"))).isNull()

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun d9_sin_propina_escrita_la_solicitud_de_TARJETA_lleva_cero() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()

        val payload = viewModel.prepararCobroConTarjeta(BigDecimal("150.00"))!!

        assertThat(payload.tipAmount).isEqualTo(BigDecimal.ZERO)
        assertThat(payload.propinaEnCentavos).isNull()
    }

    @Test
    fun d9_la_propina_viaja_en_centavos_redondeada_a_dos_decimales() {
        fun centavos(propina: String) =
            CardChargeRequest(orderId = "o", orderNumber = null, amount = BigDecimal("100.00"), tipAmount = BigDecimal(propina)).propinaEnCentavos

        assertThat(centavos("50")).isEqualTo(5_000L)
        assertThat(centavos("50.00")).isEqualTo(5_000L)
        assertThat(centavos("12.345")).isEqualTo(1_235L)
        assertThat(centavos("0.004")).isNull()
        assertThat(centavos("0")).isNull()
        assertThat(centavos("-5")).isNull()
    }

    @Test
    fun d9_un_cobro_en_EFECTIVO_exitoso_vacia_la_casilla_y_la_siguiente_parte_no_repite_la_propina() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal("200.00")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))
        coEvery { repository.payCash(venueId, "orden-1", false, any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = null, queued = false))

        viewModel.payCash(BigDecimal("100.00"))
        assertThat(viewModel.state.value.cashReceipt!!.tipAmount).isEqualTo(BigDecimal("20.00"))
        assertThat(viewModel.state.value.tipAmount).isEqualTo(BigDecimal.ZERO)

        viewModel.payCash(BigDecimal("50.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("100.00"), BigDecimal("20.00"), any()) }
        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("50.00"), BigDecimal.ZERO, any()) }
    }

    @Test
    fun d9_un_cobro_en_EFECTIVO_rechazado_conserva_la_propina_para_reintentar() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), BigDecimal("20.00"), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException("El cobro fue rechazado", "INVALID_PAYLOAD"))

        viewModel.payCash(BigDecimal("150.00"))

        assertThat(viewModel.state.value.tipAmount).isEqualTo(BigDecimal("20.00"))
    }

    // endregion

    // region — D86: «Cobrar» sin red nunca cobra con un número que no es el de la cuenta

    private fun filaD86(id: String, tipo: String, payload: String, estado: String = SyncIntentEntity.STATUS_PENDING, result: String? = null) =
        SyncIntentEntity(id = id, venueId = venueId, seq = id.hashCode().toLong(), type = tipo, payloadJson = payload, status = estado, resultJson = result)

    @Test
    fun D86_mesa_del_servidor_sin_cuenta_y_sin_red_no_cobra_con_el_total_del_plano() = runTest {
        // «Ver cuenta» arranca la sesión con el TOTAL del plano (no el saldo): sin red, eso no se cobra.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        every { networkMonitor.isConnected() } returns false
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(IOException("sin red"))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(SIN_CUENTA_SIN_RED)
        viewModel.payCash(BigDecimal("150.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.errorMessage).isEqualTo(SIN_CUENTA_SIN_RED)
    }

    @Test
    fun D86_mesa_abierta_sin_red_aqui_cobra_sus_rondas_menos_su_efectivo() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        val saldo = SaldoDeMesaSinRed(syncOutbox)
        saldo.libreta.anotarRonda("local-1", "r1", BigDecimal("65.00"))
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}"""),
            filaD86("r1", SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"local-1","items":[]}"""),
            filaD86("c1", SyncIntentTypes.PAY_CASH, """{"localOrderId":"local-1","amountCents":2000,"tipCents":0}"""),
        )
        coEvery { repository.payCash(venueId, "local-1", true, BigDecimal("45.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        coEvery { repository.clearTable(any(), any(), any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel(saldo)

        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("45.00"))
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
        assertThat(viewModel.state.value.avisoSinRed).isEqualTo(COBRO_CON_LO_DE_ESTA_TERMINAL)
        viewModel.payCash(BigDecimal("45.00"))

        // La provisional no manda saldo esperado (como ANDROID) y libera nombrando su cuenta local (D79).
        coVerify(exactly = 1) { repository.payCash(venueId, "local-1", true, BigDecimal("45.00"), any(), null) }
        coVerify(exactly = 1) { repository.clearTable(venueId, "mesa-1", "local-1", true) }
    }

    @Test
    fun D86_mesa_abierta_sin_red_sin_saldo_seguro_queda_en_gris() = runTest {
        // La app se reinició: la ronda sigue en la cola pero su valor (en memoria) ya no.
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}"""),
            filaD86("r1", SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"local-1","items":[]}"""),
        )
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(SALDO_NO_RECUPERADO)
        assertThat(viewModel.state.value.remaining.signum()).isEqualTo(0)
    }

    @Test
    fun D86_el_efectivo_de_una_cuenta_del_servidor_lleva_el_saldo_esperado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("120.00")))
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("50.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("50.00"), any(), BigDecimal("120.00")) }
    }

    @Test
    fun D86_un_cobro_propio_sin_subir_bloquea_efectivo_y_tarjeta_tras_leer_la_cuenta() = runTest {
        // Se abrió sin red, se cobró una parte sin red, la apertura ya subió pero el cobro todavía no: el SERVIDOR no lo cuenta.
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        tableSession.promoteProvisional("local-1", "orden-1", "ORD-1", 2)
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("c1", SyncIntentTypes.PAY_CASH, """{"localOrderId":"local-1","amountCents":4000,"tipCents":0}"""),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)
    }

    @Test
    fun D86_la_apertura_confirmada_por_otro_camino_promueve_y_lee_la_cuenta_del_servidor() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        coEvery { syncOutbox.aperturasConfirmadasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86(
                "abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}""",
                estado = SyncIntentEntity.STATUS_ACKED, result = """{"localOrderId":"local-1","orderId":"orden-1","orderNumber":"ORD-1","version":3}""",
            ),
        )
        every { tableSyncCoordinator.onAck(SyncIntentTypes.OPEN_TABLE, "local-1", "orden-1", "ORD-1", 3) } answers {
            tableSession.promoteProvisional("local-1", "orden-1", "ORD-1", 3)
        }
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("80.00"), amountLeft = BigDecimal("80.00")))
        val viewModel = createViewModel()

        assertThat(tableSession.current()?.isProvisional).isFalse()
        assertThat(viewModel.state.value.isProvisional).isFalse()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("80.00"))
        coVerify(exactly = 1) { repository.getOrder(venueId, "orden-1") }
    }

    // endregion

    // region — D86 · Codex r1: lo propio sin subir contra lo que sabe el SERVIDOR

    private fun aperturaConfirmadaD86() = filaD86(
        "abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}""",
        estado = SyncIntentEntity.STATUS_ACKED, result = """{"localOrderId":"local-1","orderId":"orden-1","orderNumber":"ORD-1","version":3}""",
    )

    @Test
    fun D86_r1_2_tras_volver_por_ver_cuenta_el_cobro_propio_sin_subir_se_encuentra_por_la_cola() = runTest {
        // La sesión de «Ver cuenta» ya no recuerda el id local: la cola sí (su apertura confirmada).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { syncOutbox.aperturasConfirmadasConResultado(venueId, "orden-1") } returns listOf(aperturaConfirmadaD86())
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("c1", SyncIntentTypes.PAY_CASH, """{"localOrderId":"local-1","amountCents":4000,"tipCents":0}"""),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)
        viewModel.payCash(BigDecimal("100.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun D86_r1_3_si_lo_propio_estaba_pendiente_ANTES_de_leer_la_lectura_no_sirve_para_cobrar() = runTest {
        // Pendiente antes del GET y ya subido después: la lectura pudo ser de antes de que subiera.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { syncOutbox.vivasQueMencionan(venueId, "orden-1") } returnsMany listOf(
            listOf(filaD86("c1", SyncIntentTypes.PAY_CASH, """{"orderId":"orden-1","amountCents":4000,"tipCents":0}""")),
            emptyList(),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)
    }

    @Test
    fun D86_r1_4_cuando_lo_propio_sube_la_cuenta_se_desbloquea_sola() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { syncOutbox.vivasQueMencionan(venueId, "orden-1") } returns
            listOf(filaD86("c1", SyncIntentTypes.PAY_CASH, """{"orderId":"orden-1","amountCents":4000,"tipCents":0}"""))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("60.00")))
        val viewModel = createViewModel()
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)

        coEvery { syncOutbox.vivasQueMencionan(venueId, "orden-1") } returns emptyList()
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("60.00"))
    }

    @Test
    fun D86_r1_1_la_promocion_durante_un_abono_no_lo_da_por_saldado() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        val saldo = SaldoDeMesaSinRed(syncOutbox)
        saldo.libreta.anotarRonda("local-1", "r1", BigDecimal("100.00"))
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}"""),
            filaD86("r1", SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"local-1","items":[]}"""),
        )
        every { tableSyncCoordinator.onAck(any(), any(), any(), any(), any()) } returns Unit
        // Mientras el abono de $40 espera, otro camino confirma la apertura.
        // «Monto personalizado» da `40` (escala 0): se compara por valor, no por escala.
        coEvery { repository.payCash(venueId, "local-1", true, match { it.compareTo(BigDecimal("40")) == 0 }, any(), any()) } coAnswers {
            tableSession.promoteProvisional("local-1", "orden-1", "ORD-1", 3)
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p1", orderNumber = "ORD-1", queued = false))
        }
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("60.00")))
        val viewModel = createViewModel(saldo)
        assertWithMessage("saldo sin red antes del abono").that(viewModel.state.value.remaining).isEqualTo(BigDecimal("100.00"))

        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        viewModel.setCustomAmountInput("40")
        viewModel.payCash(viewModel.chargeableAmount())

        coVerify(exactly = 1) { repository.payCash(venueId, "local-1", true, match { it.compareTo(BigDecimal("40")) == 0 }, any(), any()) }
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
        assertWithMessage("el abono parcial no libera la mesa").that(viewModel.state.value.tableReleased).isFalse()
        assertWithMessage("tras el abono manda la cuenta del SERVIDOR").that(viewModel.state.value.isProvisional).isFalse()
        // Al terminar el abono se lee la cuenta del SERVIDOR: lo que falta es lo que él dice.
        assertWithMessage("lo que falta es lo del SERVIDOR").that(viewModel.state.value.remaining).isEqualTo(BigDecimal("60.00"))
    }

    // endregion

    // region — D86 · Codex r2: el saldo se valida al cobrar y una lectura vieja no lo pisa

    @Test
    fun D86_r2_1_una_lectura_que_salio_antes_de_un_cobro_no_vuelve_a_ofrecer_lo_cobrado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        val lenta = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            when (lecturas) {
                1 -> Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
                2 -> { lenta.await(); Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00"))) }
                else -> Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("60.00")))
            }
        }
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p1", orderNumber = null, queued = false))
        val viewModel = createViewModel()

        viewModel.loadCheck() // sale con el saldo de antes del cobro y tarda
        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        viewModel.setCustomAmountInput("40")
        viewModel.payCash(viewModel.chargeableAmount())
        // Leer y cobrar van en fila (r5): el cobro espera a que termine la lectura en curso.
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }

        lenta.complete(Unit) // llega la lectura vieja ($100): se tira, cobra, y se vuelve a leer

        coVerify(exactly = 1) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("60.00"))
    }

    @Test
    fun D86_r2_2_una_apertura_rechazada_despues_de_pintar_el_saldo_no_deja_cobrar() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        val saldo = SaldoDeMesaSinRed(syncOutbox)
        saldo.libreta.anotarRonda("local-1", "r1", BigDecimal("100.00"))
        val ronda = filaD86("r1", SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"local-1","items":[]}""")
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns
            listOf(filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}"""), ronda)
        val viewModel = createViewModel(saldo)
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("100.00"))

        // El SERVIDOR rechaza la apertura (p. ej. la mesa estaba reservada) y la red se vuelve a ir.
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}""", estado = SyncIntentEntity.STATUS_REJECTED),
            ronda,
        )
        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.errorMessage).isEqualTo(CAMBIOS_SIN_SINCRONIZAR)
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CAMBIOS_SIN_SINCRONIZAR)
        assertThat(viewModel.state.value.isProcessingCash).isFalse()
    }

    @Test
    fun D86_r2_3_una_lectura_fallida_no_detiene_el_desbloqueo() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { syncOutbox.vivasQueMencionan(venueId, "orden-1") } returns
            listOf(filaD86("c1", SyncIntentTypes.PAY_CASH, """{"orderId":"orden-1","amountCents":4000,"tipCents":0}"""))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("60.00")))
        val viewModel = createViewModel()
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)

        // El cobro sube, pero la siguiente lectura falla una vez.
        coEvery { syncOutbox.vivasQueMencionan(venueId, "orden-1") } returns emptyList()
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 500, message = "error")),
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("60.00"))),
        )
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
    }

    // endregion

    // region — D86 · Codex r3: la primera lectura que tarda sí se aplica

    @Test
    fun D86_r3_la_primera_lectura_que_tarda_se_aplica_y_una_mas_vieja_no_pisa_a_la_nueva() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        val primera = kotlinx.coroutines.CompletableDeferred<Unit>()
        val segunda = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            if (lecturas == 1) {
                primera.await(); Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
            } else {
                segunda.await(); Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("70.00")))
            }
        }
        val viewModel = createViewModel() // el constructor termina con la primera lectura todavía en camino

        primera.complete(Unit)
        assertThat(viewModel.state.value.check).isNotNull()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("100.00"))

        viewModel.loadCheck() // la nueva
        segunda.complete(Unit)
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("70.00"))
    }

    // endregion

    @Test
    fun D86_r5_1_una_lectura_que_nace_durante_un_cobro_espera_su_turno_y_ve_el_cobro() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            if (lecturas == 1) {
                Result.success(OrderDetail(id = "orden-1", total = BigDecimal("110.00"), amountLeft = BigDecimal("110.00")))
            } else {
                Result.success(OrderDetail(id = "orden-1", total = BigDecimal("110.00"), amountLeft = BigDecimal("70.00")))
            }
        }
        val cobro = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } coAnswers {
            cobro.await()
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p1", orderNumber = null, queued = false))
        }
        val viewModel = createViewModel()
        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        viewModel.setCustomAmountInput("40")
        viewModel.payCash(viewModel.chargeableAmount()) // espera al procesador

        viewModel.loadCheck() // p. ej. la relectura tras quitar un descuento: nace durante el cobro
        assertThat(lecturas).isEqualTo(1) // espera su turno en la fila
        cobro.complete(Unit)

        assertThat(lecturas).isEqualTo(2)
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("70.00"))
    }

    @Test
    fun D86_r7_H3_un_rechazo_de_la_apertura_vuelve_gris_el_efectivo_sin_tocar_nada() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        val saldo = SaldoDeMesaSinRed(syncOutbox)
        saldo.libreta.anotarRonda("local-1", "r1", BigDecimal("100.00"))
        val ronda = filaD86("r1", SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"local-1","items":[]}""")
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns
            listOf(filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}"""), ronda)
        val viewModel = createViewModel(saldo)
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()

        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}""", estado = SyncIntentEntity.STATUS_REJECTED),
            ronda,
        )
        cambiosDeLaCola.value = 1
        testDispatcher.scheduler.advanceTimeBy(com.jaac.avoqado_tpv.features.tables.data.CALMA_DE_LA_COLA_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CAMBIOS_SIN_SINCRONIZAR)
    }

    // region — D92: un cambio de dinero desde «Cobrar» sin confirmar nunca deja cobrar con la cuenta de antes

    private fun cuentaD92(restante: String) = Result.success(
        OrderDetail(id = "orden-1", total = BigDecimal(restante), amountLeft = BigDecimal(restante)),
    )

    @Test
    fun D92_quitar_descuento_sin_respuesta_y_sin_red_no_cobra_con_la_cuenta_de_antes() = runTest {
        // El DELETE llegó al SERVIDOR (ya debe $100) pero la respuesta se perdió y la red se cayó: la cuenta en pantalla
        // ($90, con el descuento) ya no es la de verdad. Ni efectivo ni tarjeta hasta leerla otra vez.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("90.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaD92("90.00"), Result.failure(IOException("sin red")))
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(TablesRepository.RemoveDiscountRequiresConnectionException())
        val viewModel = createViewModel()
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()

        viewModel.removeDiscount("d1")
        viewModel.onConnectivityChanged(false)

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        viewModel.payCash(BigDecimal("90.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun D92_una_lectura_nueva_libera_el_cobro_con_la_cuenta_de_verdad() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("90.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaD92("90.00"), cuentaD92("100.00"))
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(TablesRepository.RemoveDiscountRequiresConnectionException())
        val viewModel = createViewModel()
        viewModel.removeDiscount("d1")
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)

        viewModel.reintentar()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("100.00"))
    }

    @Test
    fun D92_quitar_descuento_con_exito_y_la_relectura_falla_no_cobra_con_la_cuenta_de_antes() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("90.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaD92("90.00"), Result.failure(IOException("sin red")))
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.success(TablesRepository.DiscountOutcome(queued = false, amount = BigDecimal("10.00"), newOrderTotal = BigDecimal("100.00")))
        val viewModel = createViewModel()

        viewModel.removeDiscount("d1")

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
    }

    @Test
    fun D92_la_lectura_que_ya_viajaba_antes_del_cambio_no_lo_libera() = runTest {
        // Una lectura salió ANTES de quitar el descuento y regresa DESPUÉS con la cuenta vieja: no cuenta como confirmación.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("90.00")))
        val vieja = CompletableDeferred<Result<OrderDetail>>()
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            when (++lecturas) {
                1 -> cuentaD92("90.00")
                2 -> vieja.await()
                else -> Result.failure(IOException("sin red"))
            }
        }
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(TablesRepository.RemoveDiscountRequiresConnectionException())
        val viewModel = createViewModel()
        viewModel.reintentar() // la lectura 2 queda viajando

        viewModel.removeDiscount("d1")
        vieja.complete(cuentaD92("90.00"))

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
        // Y se vuelve a leer sola (sin red, sigue en gris).
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()
        assertThat(lecturas).isEqualTo(3)
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
    }

    @Test
    fun D92_quitar_cargo_por_servicio_sin_respuesta_no_cobra_con_la_cuenta_de_antes() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("110.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaD92("110.00"), Result.failure(IOException("sin red")))
        coEvery { repository.removeServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.failure(TablesRepository.RemoveServiceChargeRequiresConnectionException())
        val viewModel = createViewModel()

        viewModel.removeServiceCharge("sc1")

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
    }

    @Test
    fun D92_aplicar_descuento_con_exito_y_la_relectura_falla_no_cobra_con_la_cuenta_de_antes() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaD92("100.00"), Result.failure(IOException("sin red")))
        coEvery { repository.applyDiscount(venueId, "orden-1", "d1") } returns
            Result.success(TablesRepository.DiscountOutcome(queued = false, amount = BigDecimal("10.00"), newOrderTotal = BigDecimal("90.00")))
        val viewModel = createViewModel()

        viewModel.applyDiscount("d1")

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
    }

    @Test
    fun D92_aplicar_cargo_con_exito_y_la_relectura_falla_no_cobra_con_la_cuenta_de_antes() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaD92("100.00"), Result.failure(IOException("sin red")))
        coEvery { repository.applyServiceCharge(venueId, "orden-1", "sc1") } returns
            Result.success(TablesRepository.ServiceChargeOutcome(queued = false, total = BigDecimal("110.00"), serviceChargeAmount = BigDecimal("10.00")))
        val viewModel = createViewModel()

        viewModel.applyServiceCharge("sc1")

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
    }

    @Test
    fun D92_un_rechazo_del_servidor_tambien_se_confirma_releyendo() = runTest {
        // Codex r1 P1: el «no» puede ser el reenvío automático de un DELETE que sí pasó («ese descuento ya no está»).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD92("100.00")
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "No se puede quitar un descuento de una orden pagada"))
        val viewModel = createViewModel()

        viewModel.removeDiscount("d1")

        assertThat(viewModel.state.value.errorMessage).contains("orden pagada")
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
    }

    @Test
    fun D92_con_un_descuento_en_camino_no_se_cobra_ni_en_efectivo_ni_con_tarjeta() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("90.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD92("90.00")
        val enCamino = CompletableDeferred<Result<TablesRepository.DiscountOutcome>>()
        coEvery { repository.removeDiscount(venueId, "orden-1", "d1") } coAnswers { enCamino.await() }
        val viewModel = createViewModel()

        viewModel.removeDiscount("d1")
        // Codex r1 P2: los botones se ven en gris con su razón mientras va en camino, no sólo se niegan por dentro.
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_SE_ACTUALIZA)
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo(CUENTA_SE_ACTUALIZA)
        viewModel.payCash(BigDecimal("90.00"))
        val tarjeta = viewModel.prepararCobroConTarjeta(BigDecimal("90.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(tarjeta).isNull()
        enCamino.complete(Result.failure(TablesRepository.RemoveDiscountRequiresConnectionException()))
    }

    @Test
    fun D92_con_un_cobro_en_efectivo_en_curso_no_se_quita_el_descuento() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("90.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD92("90.00")
        val cobro = CompletableDeferred<Result<TablesRepository.PayCashOutcome>>()
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("10.00"), any(), any()) } coAnswers { cobro.await() }
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("10.00"))
        viewModel.removeDiscount("d1")

        coVerify(exactly = 0) { repository.removeDiscount(any(), any(), any()) }
        cobro.complete(Result.failure(IOException("fin de la prueba")))
    }

    // endregion
}
