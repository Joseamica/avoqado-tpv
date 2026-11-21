package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.CAMBIOS_SIN_SINCRONIZAR
import com.jaac.avoqado_tpv.features.tables.data.CUENTA_SE_ACTUALIZA
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_SIN_SINCRONIZAR
import com.jaac.avoqado_tpv.features.tables.data.MESA_COBRO_POR_REVISAR
import com.jaac.avoqado_tpv.features.tables.data.COBRO_CON_LO_DE_ESTA_TERMINAL
import com.jaac.avoqado_tpv.features.tables.data.SALDO_NO_RECUPERADO
import com.jaac.avoqado_tpv.features.tables.data.SIN_CUENTA_SIN_RED
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.remotepayment.AvisoDeCobrosPendientes
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.DineroDeTarjetaDeLaCuenta
import com.jaac.avoqado_tpv.features.tables.data.SaldoDeMesaSinRed
import com.jaac.avoqado_tpv.features.tables.data.TARJETA_REGISTRANDOSE
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
    private val dineroDeTarjeta = mockk<DineroDeTarjetaDeLaCuenta>()
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
        coEvery { dineroDeTarjeta.motivo(any(), any()) } returns null
        coEvery { dineroDeTarjeta.avisoDeDuda(any()) } returns null
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
        dineroDeTarjeta = dineroDeTarjeta,
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

        // D19: el motivo va en su propio aviso (una relectura ya no lo borra antes de verse).
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("El cobro fue rechazado")
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

        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo(
            "No se registró el cobro: la terminal perdió su numeración (¿se reinstaló la app?). Avisa al gerente: queda en «Acciones rechazadas» de Mesas.",
        )
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
    }

    // endregion

    // region — D49: otro aparato cambió la cuenta mientras «Cobrar» seguía abierto

    private fun cuentaCon(saldo: String): Result<OrderDetail> =
        Result.success(OrderDetail(id = "orden-1", total = BigDecimal(saldo), amountLeft = BigDecimal(saldo)))

    private fun cobroRegistrado(): Result<TablesRepository.PayCashOutcome> =
        Result.success(TablesRepository.PayCashOutcome(paymentId = "pay-1", orderNumber = "A-1", queued = false, receiptUrl = "https://avoqado.io/r/1"))

    @Test
    fun D49_si_otro_aparato_agrego_una_ronda_el_efectivo_no_cobra_el_saldo_viejo_y_ensena_el_nuevo() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("25.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaCon("25.00"), cuentaCon("75.00"))
        val viewModel = createViewModel()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("25.00"))

        viewModel.payCash(BigDecimal("25.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
        val s = viewModel.state.value
        assertThat(s.remaining).isEqualTo(BigDecimal("75.00"))
        // Codex r1 #4: en el aviso del cobro (se queda hasta «Entendido»), no en el error que una relectura borra.
        assertThat(s.avisoDelCobro).isEqualTo("La cuenta cambió: ahora falta \$75.00. Revisa antes de cobrar.")
        assertThat(s.cashReceipt).isNull()
        assertThat(s.isProcessingCash).isFalse()
        assertThat(s.tableReleased).isFalse()
        assertThat(tableSession.current()?.total).isEqualTo(BigDecimal("75.00"))
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_si_otro_aparato_ya_cobro_una_parte_el_efectivo_no_cobra_de_mas() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaCon("100.00"), cuentaCon("40.00"))
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("40.00"))
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("La cuenta cambió: ahora falta \$40.00. Revisa antes de cobrar.")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_una_cuenta_anulada_en_otro_aparato_no_se_cobra() = runTest {
        // Codex r1 #1: la anulación conserva los importes; comparar sólo el saldo la dejaba cobrar (y PAY_CASH la reabría).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(
            cuentaCon("100.00"),
            Result.success(OrderDetail(id = "orden-1", status = "CANCELLED", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00"))),
        )
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("Esta cuenta se anuló en otro aparato: no se cobró.")
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal.ZERO)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_una_cuenta_que_otro_aparato_ya_cobro_no_se_cobra_otra_vez() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(
            cuentaCon("100.00"),
            Result.success(OrderDetail(id = "orden-1", paymentStatus = "PAID", total = BigDecimal("100.00"), amountLeft = BigDecimal.ZERO)),
        )
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("Esta cuenta ya se cobró en otro aparato: no se cobró otra vez.")
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal.ZERO)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_un_importe_mayor_a_lo_que_falta_no_se_cobra_ni_sin_red() = runTest {
        // Codex r1 #3: el botón pudo dibujarse con el saldo anterior ($100) cuando la pantalla ya tenía $60.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("60.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaCon("60.00")
        val viewModel = createViewModel()
        viewModel.onConnectivityChanged(online = false)

        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("La cuenta cambió: ahora falta \$60.00. Revisa antes de cobrar.")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_si_el_cajero_sale_de_la_cuenta_mientras_se_relee_no_se_cobra_ni_se_toca_la_otra_sesion() = runTest {
        // Codex r1 #2: la respuesta tardía no escribe sobre la sesión que quedó activa ni cobra la cuenta que se dejó.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            if (lecturas == 2) tableSession.start(TableSession.Active(tableId = "mesa-2", orderId = "orden-2", total = BigDecimal("30.00")))
            cuentaCon(if (lecturas == 2) "90.00" else "75.00")
        }
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("75.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(tableSession.current()?.orderId).isEqualTo("orden-2")
        assertThat(tableSession.current()?.total).isEqualTo(BigDecimal("30.00"))
        assertThat(viewModel.state.value.isProcessingCash).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_si_otro_cobro_de_la_cuenta_esta_en_curso_no_cobra_y_dice_que_vuelva_a_abrirla() = runTest {
        // Codex r4 #2: otra TPV abrió un QR por los $100; el SERVIDOR los aparta (D4) y la cuenta lee $0 sin estar cobrada.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(
            cuentaCon("100.00"),
            Result.success(OrderDetail(id = "orden-1", paymentStatus = "PENDING", total = BigDecimal("100.00"), amountLeft = BigDecimal.ZERO)),
        )
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("100.00"))

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo(OTRO_COBRO_EN_CURSO)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_una_cuenta_anulada_que_llega_en_una_lectura_normal_queda_en_cero() = runTest {
        // Codex r2 #1: la recarga pendiente copiaba los $100 que el SERVIDOR conserva en la cuenta anulada y volvía a ofrecerlos.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", status = "CANCELLED", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal.ZERO)
        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal.ZERO)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_un_segundo_toque_del_boton_viejo_no_cobra_aunque_sea_menor_que_el_saldo_nuevo() = runTest {
        // Codex r2 #2: el botón quedó en $25; la pantalla ya tiene $75. Antes pasaban los $25 como abono.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaCon("75.00")
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("25.00"), restanteDeLaPantalla = BigDecimal("25.00"))
        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("25.00"), restanteDeLaPantalla = BigDecimal("25.00"))).isNull()

        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("La cuenta cambió: ahora falta \$75.00. Revisa antes de cobrar.")
        assertThat(viewModel.state.value.isHandingOffToCard).isFalse()
        // El botón ya repintado (mismo saldo) sí cobra.
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) } returns cobroRegistrado()
        coEvery { repository.clearTable(any(), any(), any(), any()) } returns Result.success(Unit)
        viewModel.payCash(BigDecimal("75.00"), restanteDeLaPantalla = BigDecimal("75.00"))
        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_una_lectura_de_la_cuenta_que_se_dejo_no_escribe_en_la_sesion_nueva() = runTest {
        // Codex r2 #3: la recarga que vuelve después de salir de la cuenta no toca la que quedó activa.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            if (lecturas == 2) tableSession.start(TableSession.Active(tableId = "mesa-2", orderId = "orden-2", total = BigDecimal("30.00")))
            cuentaCon(if (lecturas == 2) "90.00" else "75.00")
        }
        val viewModel = createViewModel()

        viewModel.loadCheck()

        assertThat(tableSession.current()?.total).isEqualTo(BigDecimal("30.00"))
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("75.00"))
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_un_abono_propio_con_fraccion_de_centavo_no_dispara_un_falso_cambio() = runTest {
        // Codex r1 #5: «Monto libre» acepta $10.004; la cola manda $10.00 y la pantalla queda en 89.996 (se ve $90.00).
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { lecturas++; cuentaCon(if (lecturas <= 2) "100.00" else "90.00") }
        coEvery { repository.payCash(venueId, "orden-1", false, any(), any(), any()) } returns cobroRegistrado()
        coEvery { repository.clearTable(any(), any(), any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("10.004"))
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("89.996"))
        viewModel.payCash(viewModel.state.value.remaining)

        coVerify(exactly = 2) { repository.payCash(venueId, "orden-1", false, any(), any(), any()) }
        assertThat(viewModel.state.value.avisoDelCobro).isNull()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_con_red_relee_la_cuenta_justo_antes_de_cobrar_y_si_no_cambio_cobra_y_dice_saldada() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        var lecturas = 0
        var lecturasAlCobrar = -1
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { lecturas++; cuentaCon("75.00") }
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) } coAnswers {
            lecturasAlCobrar = lecturas
            cobroRegistrado()
        }
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        val viewModel = createViewModel()
        assertThat(lecturas).isEqualTo(1)

        viewModel.payCash(BigDecimal("75.00"))

        assertWithMessage("la cuenta se volvió a leer antes de cobrar").that(lecturasAlCobrar).isEqualTo(2)
        assertThat(viewModel.state.value.cashReceipt?.isSettlingPayment).isTrue()
        assertThat(viewModel.state.value.error).isNull()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_si_la_relectura_falla_el_efectivo_cobra_como_siempre() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returnsMany listOf(cuentaCon("75.00"), Result.failure(IOException("sin datos")))
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) } returns cobroRegistrado()
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("75.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) }
        assertThat(viewModel.state.value.error).isNull()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_si_la_relectura_tarda_mas_de_3_segundos_el_efectivo_no_espera_y_cobra() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            if (lecturas == 2) kotlinx.coroutines.delay(10_000)
            cuentaCon("75.00")
        }
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) } returns cobroRegistrado()
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("75.00"))
        testScheduler.advanceTimeBy(2_999)
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D49_sin_red_no_se_relee_la_cuenta_y_el_efectivo_cobra_como_siempre() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("75.00")))
        var lecturas = 0
        var lecturasAlCobrar = -1
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { lecturas++; cuentaCon("75.00") }
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("75.00"), any(), any()) } coAnswers {
            lecturasAlCobrar = lecturas
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        }
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        val viewModel = createViewModel()
        viewModel.onConnectivityChanged(online = false)

        viewModel.payCash(BigDecimal("75.00"))

        assertThat(lecturasAlCobrar).isEqualTo(1)
        assertThat(viewModel.state.value.notice).contains("Sin conexión")
        viewModel.viewModelScope.cancel()
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
     * 🔴 Codex final r10 #2 (TPV SOLA → Mesas, dinero): el cajero confirma el efectivo, la lectura del aviso tarda y pulsa «Atrás». Salir de
     * Mesas cancela la corrutina: con la lectura ANTES de guardar, el efectivo recibido no quedaba guardado. Ahora se guarda primero (como la
     * base) y el aviso se lee después.
     */
    @Test
    fun r10_2_si_la_lectura_del_aviso_tarda_y_el_cajero_sale_el_efectivo_ya_quedo_guardado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
        coEvery { dineroDeTarjeta.avisoDeDuda("orden-1") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = null, queued = false))
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.payCash(BigDecimal("150.00"))
        viewModel.viewModelScope.cancel()   // «Atrás»: salir de Mesas

        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) }
    }

    /**
     * F1 · Codex r9 R9-2 (TPV SOLA → Mesas, D10 «B»): una DUDA de tarjeta de esta cuenta no frena el efectivo. Se cobra (con y sin red)
     * y el recibo dice K19c; sin duda, el recibo no dice nada. Qué cuenta como duda lo prueba `DineroDeTarjetaDeLaCuentaRoomTest`.
     */
    @Test
    fun r9_2_con_una_duda_de_tarjeta_el_efectivo_se_cobra_y_el_recibo_avisa_K19c() = runTest {
        for (sinRed in listOf(false, true)) {
            tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
            coEvery { repository.getOrder(venueId, "orden-1") } returns
                Result.success(OrderDetail(id = "orden-1", total = BigDecimal("150.00"), amountLeft = BigDecimal("150.00")))
            coEvery { dineroDeTarjeta.avisoDeDuda("orden-1") } returns AvisoDeCobrosPendientes.cobroPorConfirmar()
            coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) } returns
                Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = null, queued = sinRed))
            coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
            val viewModel = createViewModel()
            if (sinRed) viewModel.onConnectivityChanged(online = false)

            viewModel.payCash(BigDecimal("150.00"))

            assertWithMessage("sinRed=$sinRed: el efectivo se cobra").that(viewModel.state.value.cashReceipt?.amount).isEqualTo(BigDecimal("150.00"))
            assertWithMessage("sinRed=$sinRed: el recibo avisa K19c").that(viewModel.state.value.cashReceipt?.avisoDeTarjeta)
                .isEqualTo("Esta cuenta tiene un cobro por confirmar. Avísale al gerente.")
            viewModel.consumeCashReceipt()
        }
        coVerify(exactly = 2) { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) }

        // Sin duda: se cobra igual y el recibo no avisa nada.
        coEvery { dineroDeTarjeta.avisoDeDuda("orden-1") } returns null
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("150.00")))
        val viewModel = createViewModel()
        viewModel.payCash(BigDecimal("150.00"))
        assertThat(viewModel.state.value.cashReceipt?.amount).isEqualTo(BigDecimal("150.00"))
        assertThat(viewModel.state.value.cashReceipt?.avisoDeTarjeta).isNull()
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
        assertThat(viewModel.discountsLoadError.value).isNull()
    }

    // D2 (hz:6884f503:D2, N86 17-nov): sin red la hoja decía «No hay descuentos disponibles para esta cuenta», que es otra
    // cosa. La lista vacía de un ÉXITO y la de un fallo ya no se confunden, y el «sin red» sale de ESTA petición.
    @Test
    fun loadAvailableDiscounts_sin_red_dice_sin_conexion_y_no_que_no_hay_descuentos() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } returns Result.failure(java.io.IOException("Unable to resolve host"))

        viewModel.loadAvailableDiscounts()

        assertThat(viewModel.discountsLoadError.value).startsWith("Sin conexión")
        assertThat(viewModel.discountsLoadError.value).contains("necesita internet")
        assertThat(viewModel.availableDiscounts.value).isEmpty()
        assertThat(viewModel.isLoadingDiscounts.value).isFalse()
    }

    @Test
    fun loadAvailableDiscounts_rechazo_del_servidor_no_dice_sin_conexion() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 403, message = "Sin permiso"))

        viewModel.loadAvailableDiscounts()

        assertThat(viewModel.discountsLoadError.value).isEqualTo("No se pudieron cargar los descuentos. Cierra y vuelve a intentar.")
    }

    @Test
    fun loadAvailableDiscounts_que_vuelve_a_cargar_bien_borra_el_aviso_anterior() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } returns Result.failure(java.io.IOException("timeout"))
        viewModel.loadAvailableDiscounts()
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } returns Result.success(emptyList())

        viewModel.loadAvailableDiscounts()

        // Ahora sí es «no hay»: un éxito con la lista vacía.
        assertThat(viewModel.discountsLoadError.value).isNull()
        assertThat(viewModel.availableDiscounts.value).isEmpty()
    }

    // D2 (Codex r1): abrir, cerrar y volver a abrir la hoja deja dos cargas en vuelo. La que llega TARDE (la vieja) no pisa a la vigente.
    @Test
    fun una_carga_vieja_de_descuentos_que_falla_tarde_no_pisa_a_la_vigente() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        val vieja = CompletableDeferred<Result<List<AvailableDiscount>>>()
        val vigente = CompletableDeferred<Result<List<AvailableDiscount>>>()
        val respuestas = ArrayDeque(listOf(vieja, vigente))
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } coAnswers { respuestas.removeFirst().await() }

        viewModel.loadAvailableDiscounts()
        viewModel.loadAvailableDiscounts()
        vigente.complete(Result.success(emptyList()))
        assertThat(viewModel.isLoadingDiscounts.value).isFalse()
        vieja.complete(Result.failure(java.io.IOException("timeout")))

        assertThat(viewModel.discountsLoadError.value).isNull()
        assertThat(viewModel.availableDiscounts.value).isEmpty()
        assertThat(viewModel.isLoadingDiscounts.value).isFalse()
    }

    @Test
    fun un_exito_viejo_de_descuentos_no_borra_el_aviso_de_la_carga_vigente_ni_apaga_su_carga() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        val vieja = CompletableDeferred<Result<List<AvailableDiscount>>>()
        val vigente = CompletableDeferred<Result<List<AvailableDiscount>>>()
        val respuestas = ArrayDeque(listOf(vieja, vigente))
        coEvery { repository.getAvailableDiscounts(venueId, "orden-1") } coAnswers { respuestas.removeFirst().await() }

        viewModel.loadAvailableDiscounts()
        viewModel.loadAvailableDiscounts()
        vieja.complete(Result.success(emptyList()))
        // La vigente sigue en camino: la hoja sigue cargando.
        assertThat(viewModel.isLoadingDiscounts.value).isTrue()
        vigente.complete(Result.failure(java.io.IOException("timeout")))

        assertThat(viewModel.discountsLoadError.value).startsWith("Sin conexión")
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
        assertThat(viewModel.serviceChargesLoadError.value).isNull()
    }

    // D2: la hoja de cargos decía «Este venue no tiene cargos por servicio configurados» sin red.
    @Test
    fun loadAvailableServiceCharges_sin_red_dice_sin_conexion_y_no_que_no_hay_cargos() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        coEvery { repository.getAvailableServiceCharges(venueId) } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(statusCode = 503, message = "Service Unavailable"))

        viewModel.loadAvailableServiceCharges()

        assertThat(viewModel.serviceChargesLoadError.value).startsWith("Sin conexión")
        assertThat(viewModel.availableServiceCharges.value).isEmpty()
    }

    @Test
    fun una_carga_vieja_de_cargos_que_falla_tarde_no_pisa_a_la_vigente() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1"))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.success(OrderDetail(id = "orden-1"))
        val viewModel = createViewModel()
        val vieja = CompletableDeferred<Result<List<AvailableServiceCharge>>>()
        val vigente = CompletableDeferred<Result<List<AvailableServiceCharge>>>()
        val respuestas = ArrayDeque(listOf(vieja, vigente))
        coEvery { repository.getAvailableServiceCharges(venueId) } coAnswers { respuestas.removeFirst().await() }

        viewModel.loadAvailableServiceCharges()
        viewModel.loadAvailableServiceCharges()
        vigente.complete(Result.success(emptyList()))
        vieja.complete(Result.failure(java.io.IOException("timeout")))

        assertThat(viewModel.serviceChargesLoadError.value).isNull()
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
        // Encargo 49276c: antes de otro cobro, la pantalla vieja relee la cuenta al volver al frente.
        viewModel.alVolverAlFrente()
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
        // D49: el SERVIDOR simulado descuenta lo cobrado, como el real (la terminal relee la cuenta antes de cada efectivo).
        var cobrado = BigDecimal.ZERO
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal("200.00") - cobrado))
        }
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))
        coEvery { repository.payCash(venueId, "orden-1", false, any(), any(), any()) } coAnswers {
            cobrado += arg<BigDecimal>(3)
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = null, queued = false))
        }

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

    // region — D9 · encargo 49276c: tras entregar una TARJETA, la «Cobrar mesa» vieja no cobra con el saldo de antes

    private fun cuenta(porPagar: String) =
        Result.success(OrderDetail(id = "orden-1", total = BigDecimal("200.00"), amountLeft = BigDecimal(porPagar)))

    private fun cobroEnEfectivoOk() {
        coEvery { repository.payCash(venueId, "orden-1", false, any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = null, queued = false))
        // Saldar la cuenta libera la mesa (onPaymentCompleted → releaseTable → clearTable) — Codex r2 #2.
        coEvery { repository.clearTable(venueId, "mesa-1", "orden-1", false) } returns Result.success(Unit)
    }

    @Test
    fun p1_tras_entregar_TARJETA_el_efectivo_espera_a_releer_la_cuenta_y_cobra_lo_que_de_verdad_queda() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()

        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        viewModel.setCustomAmountInput("50")
        viewModel.prepararCobroConTarjeta(viewModel.chargeableAmount())!!
        viewModel.cobroConTarjetaEntregado()
        // La tarjeta pagó $50 en «Cobrar»; el mesero regresa con el «atrás» del sistema desde «Aprobado».
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("150.00")
        viewModel.selectSplitType(CheckoutSplitType.FULLPAYMENT)

        // Antes de releer: la pantalla todavía cree que se deben $200 y NO deja cobrar.
        assertThat(viewModel.state.value.saldoPorConfirmar).isTrue()
        viewModel.payCash(viewModel.chargeableAmount())
        assertThat(viewModel.prepararCobroConTarjeta(viewModel.chargeableAmount())).isNull()
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }

        viewModel.alVolverAlFrente()

        assertThat(viewModel.state.value.saldoPorConfirmar).isFalse()
        assertThat(viewModel.state.value.avisoDeSaldoPorConfirmar).isNull()
        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("150.00"))
        viewModel.payCash(viewModel.chargeableAmount())
        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) }
        coVerify(exactly = 0) { repository.payCash(venueId, "orden-1", false, BigDecimal("200.00"), any(), any()) }
    }

    @Test
    fun p1_si_no_se_puede_releer_tras_la_TARJETA_los_botones_siguen_esperando_y_lo_dice() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()
        viewModel.prepararCobroConTarjeta(BigDecimal("200.00"))!!
        viewModel.cobroConTarjetaEntregado()

        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(IOException("sin red"))
        viewModel.alVolverAlFrente()

        val estado = viewModel.state.value
        assertThat(estado.saldoPorConfirmar).isTrue()
        assertThat(estado.revisandoSaldo).isFalse()
        assertThat(estado.avisoDeSaldoPorConfirmar).contains("No se pudo confirmar cuánto queda")
        viewModel.payCash(BigDecimal("200.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }

        // «Reintentar» con la red de vuelta: la tarjeta saldó todo, ya no queda nada que cobrar.
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("0.00")
        viewModel.loadCheck()
        assertThat(viewModel.state.value.saldoPorConfirmar).isFalse()
        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun p1_una_lectura_que_salio_antes_de_la_TARJETA_no_confirma_el_saldo() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()
        // Con la cuenta ya leída sale otra lectura (p. ej. la relectura por un cambio de la cola) que se queda en vuelo. Sin la
        // cuenta leída la tarjeta ya ni se prepara (D90, PR #58), así que la lectura vieja sólo puede salir así.
        val lecturaVieja = kotlinx.coroutines.CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { lecturaVieja.await() }
        viewModel.loadCheck()

        viewModel.prepararCobroConTarjeta(BigDecimal("50.00"))!!
        viewModel.cobroConTarjetaEntregado()
        lecturaVieja.complete(cuenta("200.00")) // llega tarde, con el saldo de antes de la tarjeta

        assertThat(viewModel.state.value.saldoPorConfirmar).isTrue()
        viewModel.payCash(BigDecimal("200.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }

        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("150.00")
        viewModel.alVolverAlFrente()
        assertThat(viewModel.state.value.saldoPorConfirmar).isFalse()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
    }

    @Test
    fun p1_una_lectura_que_empieza_con_Cobrar_abierto_no_confirma_el_saldo() = runTest {
        // Codex r2: «Quitar descuento» (o un cargo) relee la cuenta; si esa lectura empieza DESPUÉS de entregar la
        // tarjeta pero ANTES de que cobre, trae el saldo de antes del abono. No debe apagar el bloqueo.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()

        viewModel.prepararCobroConTarjeta(BigDecimal("50.00"))!!
        viewModel.cobroConTarjetaEntregado()
        viewModel.loadCheck() // con «Cobrar» abierto: la tarjeta aún no cobra, el servidor dice $200

        assertThat(viewModel.state.value.saldoPorConfirmar).isTrue()
        assertThat(viewModel.state.value.revisandoSaldo).isFalse()
        viewModel.payCash(BigDecimal("200.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }

        // La tarjeta cobró $50; al volver la pantalla relee y ahora sí confirma.
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("150.00")
        viewModel.alVolverAlFrente()
        assertThat(viewModel.state.value.saldoPorConfirmar).isFalse()
        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("150.00"))
    }

    @Test
    fun p1_una_lectura_de_cuando_Cobrar_estaba_abierto_que_llega_tarde_no_pisa_el_saldo_confirmado() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()
        viewModel.prepararCobroConTarjeta(BigDecimal("50.00"))!!
        viewModel.cobroConTarjetaEntregado()

        // Con «Cobrar» abierto sale una lectura (p. ej. tras «Quitar descuento») que se queda en vuelo con $200.
        val lecturaDeEnMedio = kotlinx.coroutines.CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { lecturaDeEnMedio.await() }
        viewModel.loadCheck()

        // La tarjeta cobra $50; la pantalla vuelve y su lectura espera turno detrás de la de en medio (D86: una sola fila).
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("150.00")
        viewModel.alVolverAlFrente()
        // Mientras espera, los dos botones siguen esperando y el aviso dice «Revisando…» (no «No se pudo confirmar»).
        assertThat(viewModel.state.value.saldoPorConfirmar).isTrue()
        assertThat(viewModel.state.value.revisandoSaldo).isTrue()
        assertThat(viewModel.state.value.avisoDeSaldoPorConfirmar).startsWith("Revisando")

        lecturaDeEnMedio.complete(cuenta("200.00")) // llega tarde: se tira, y luego la de la pantalla confirma $150

        assertThat(viewModel.state.value.saldoPorConfirmar).isFalse()
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("150.00"))
    }

    // Codex r1 de la unión (16-nov): la PAX enseña «Aprobado» ANTES de registrar el cobro en el SERVIDOR. Si el cajero vuelve
    // con «atrás» mientras registra, la cuenta que lee el SERVIDOR todavía no descuenta la tarjeta.
    @Test
    fun d9_tarjeta_aprobada_que_todavia_se_registra_no_deja_cobrar_el_saldo_de_antes() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()
        viewModel.prepararCobroConTarjeta(BigDecimal("50.00"))!!
        viewModel.cobroConTarjetaEntregado()

        // «Aprobado» y «atrás» mientras el registro de la tarjeta sigue en camino: el SERVIDOR todavía dice $200.
        coEvery { dineroDeTarjeta.motivo(venueId, "orden-1") } returns TARJETA_REGISTRANDOSE
        viewModel.alVolverAlFrente()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(TARJETA_REGISTRANDOSE)
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        viewModel.payCash(BigDecimal("200.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }

        // La tarjeta ya se registró: la relectura sola trae lo que de verdad falta y se cobra eso.
        coEvery { dineroDeTarjeta.motivo(venueId, "orden-1") } returns null
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("150.00")
        testDispatcher.scheduler.advanceTimeBy(RELECTURA_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
        assertThat(viewModel.chargeableAmount()).isEqualTo(BigDecimal("150.00"))
        viewModel.payCash(BigDecimal("150.00"))
        coVerify(exactly = 1) { repository.payCash(venueId, "orden-1", false, BigDecimal("150.00"), any(), any()) }
    }

    @Test
    fun d9_si_no_se_puede_revisar_la_tarjeta_de_la_cuenta_no_se_cobra() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        coEvery { dineroDeTarjeta.motivo(venueId, "orden-1") } throws IllegalStateException("libreta ilegible")
        cobroEnEfectivoOk()
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(SALDO_NO_RECUPERADO)
        viewModel.payCash(BigDecimal("200.00"))
        coVerify(exactly = 0) { repository.payCash(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun volver_al_frente_sin_haber_entregado_TARJETA_no_relee() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("200.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuenta("200.00")
        val viewModel = createViewModel()

        viewModel.alVolverAlFrente()

        coVerify(exactly = 1) { repository.getOrder(venueId, "orden-1") }
        assertThat(viewModel.state.value.saldoPorConfirmar).isFalse()
    }

    // endregion

    // region — D86: «Cobrar» sin red nunca cobra con un número que no es el de la cuenta

    private fun filaD86(id: String, tipo: String, payload: String, estado: String = SyncIntentEntity.STATUS_PENDING, result: String? = null) =
        SyncIntentEntity(id = id, venueId = venueId, seq = id.hashCode().toLong(), type = tipo, payloadJson = payload, status = estado, resultJson = result)

    @Test
    fun D90_sin_cuenta_leida_tocar_tarjeta_no_entrega_un_cobro() = runTest {
        // D90: se revalida al tocar, no sólo al pintar el botón.
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("118.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(BackendHttpException(500, "falla"))
        val viewModel = createViewModel()

        assertThat(viewModel.prepararCobroConTarjeta(BigDecimal("118.00"))).isNull()
        assertThat(viewModel.state.value.isHandingOffToCard).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D90_con_un_cobro_propio_sin_subir_y_la_lectura_fallida_la_tarjeta_dice_por_que() = runTest {
        // D90 (PAX, 16-nov, caso 6 del D86): $20 en efectivo propio sin subir y la lectura de la cuenta falla. La tarjeta salía
        // habilitada con $58 y el aviso de cobro sin sincronizar nunca salía.
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        tableSession.promoteProvisional("local-1", "orden-1", "ORD-1", 2)
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } returns listOf(
            filaD86("c1", SyncIntentTypes.PAY_CASH, """{"localOrderId":"local-1","amountCents":2000,"tipCents":0}"""),
        )
        coEvery { repository.getOrder(venueId, "orden-1") } returns Result.failure(BackendHttpException(500, "falla"))
        val viewModel = createViewModel()

        assertThat(viewModel.state.value.check).isNull()
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.cardPaymentDisabledReason).isEqualTo(MESA_COBRO_SIN_SINCRONIZAR)
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNotNull()
        viewModel.viewModelScope.cancel()
    }

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
        var cobrado = BigDecimal.ZERO
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            when (lecturas) {
                1 -> Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00")))
                2 -> { lenta.await(); Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00"))) }
                // D49: el SERVIDOR simulado sólo descuenta el cobro DESPUÉS de recibirlo (la relectura antes de cobrar ve $100).
                else -> Result.success(OrderDetail(id = "orden-1", total = BigDecimal("100.00"), amountLeft = BigDecimal("100.00") - cobrado))
            }
        }
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } coAnswers {
            cobrado += arg<BigDecimal>(3)
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p1", orderNumber = null, queued = false))
        }
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
        var cobrado = BigDecimal.ZERO
        var lecturas = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers {
            lecturas++
            // D49: el SERVIDOR simulado descuenta el cobro sólo cuando lo recibe (la relectura antes de cobrar ve $110).
            Result.success(OrderDetail(id = "orden-1", total = BigDecimal("110.00"), amountLeft = BigDecimal("110.00") - cobrado))
        }
        val cobro = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } coAnswers {
            cobro.await()
            cobrado += arg<BigDecimal>(3)
            Result.success(TablesRepository.PayCashOutcome(paymentId = "p1", orderNumber = null, queued = false))
        }
        val viewModel = createViewModel()
        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        viewModel.setCustomAmountInput("40")
        viewModel.payCash(viewModel.chargeableAmount()) // espera al procesador

        viewModel.loadCheck() // p. ej. la relectura tras quitar un descuento: nace durante el cobro
        assertThat(lecturas).isEqualTo(2) // la carga y la relectura antes de cobrar (D49); la nueva espera su turno en la fila
        cobro.complete(Unit)

        assertThat(lecturas).isEqualTo(3)
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

    // region — D19 (na78d478, PAX 18-nov): el desenlace del efectivo que NO entró

    private fun cuentaD19(total: String) =
        Result.success(OrderDetail(id = "orden-1", total = BigDecimal(total), amountLeft = BigDecimal(total)))

    @Test
    fun P1_D19_rechazado_no_descuenta_ni_libera_y_efectivo_y_tarjeta_esperan_una_lectura_nueva() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("203.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD19("203.00")
        val viewModel = createViewModel()
        val relectura = CompletableDeferred<Result<OrderDetail>>()
        // D49: la relectura antes de cobrar contesta; la de después del rechazo es la que espera.
        var tras = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { if (++tras == 1) cuentaD19("203.00") else relectura.await() }
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("203.00"), any(), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException("Order not found", "BUSINESS_RULE"))

        viewModel.payCash(BigDecimal("203.00"))

        val s = viewModel.state.value
        assertThat(s.avisoDelCobro).isEqualTo("Order not found")
        assertThat(s.cashReceipt).isNull()
        assertThat(s.notice).isNull()
        assertThat(s.remaining).isEqualTo(BigDecimal("203.00"))
        assertThat(s.tableReleased).isFalse()
        // Antes: los dos botones quedaban libres con el saldo de antes hasta que la cola avisara.
        assertThat(s.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
        assertThat(s.cardPaymentEnabled).isFalse()
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }

        // La relectura ve el cobro rechazado en la cola: la cuenta queda «por revisar», nunca libre con el saldo viejo.
        coEvery { syncOutbox.vivasQueMencionan(venueId, "orden-1") } returns listOf(
            filaD86("c1", SyncIntentTypes.PAY_CASH, """{"orderId":"orden-1","amountCents":20300,"tipCents":0}""", SyncIntentEntity.STATUS_REJECTED),
        )
        relectura.complete(cuentaD19("203.00"))

        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_POR_REVISAR)
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("Order not found") // la relectura no lo borra
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D19_tras_un_rechazo_una_relectura_limpia_libera_los_botones_con_el_saldo_del_servidor() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("203.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD19("203.00")
        val viewModel = createViewModel()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException("Order not found", "BUSINESS_RULE"))
        // D49 (Codex r2 #4): la relectura ANTES de cobrar todavía ve $203 (si no, D49 frena antes del rechazo y esta prueba no lo
        // ejercita); la de DESPUÉS del rechazo trae el abono de otra caja.
        var tras = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { if (++tras == 1) cuentaD19("203.00") else cuentaD19("150.00") }

        viewModel.payCash(BigDecimal("203.00"))

        coVerify(exactly = 1) { repository.payCash(any(), any(), any(), any(), any(), any()) }
        assertThat(viewModel.state.value.remaining).isEqualTo(BigDecimal("150.00"))
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()
        assertThat(viewModel.state.value.cardPaymentEnabled).isTrue()
    }

    @Test
    fun P1_D19_en_la_duda_no_descuenta_quita_la_propina_y_los_botones_esperan() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD19("100.00")
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("20.00"))
        val relectura = CompletableDeferred<Result<OrderDetail>>()
        // D49: la relectura antes de cobrar contesta; la de después de la duda es la que espera.
        var tras = 0
        coEvery { repository.getOrder(venueId, "orden-1") } coAnswers { if (++tras == 1) cuentaD19("100.00") else relectura.await() }
        coEvery { repository.payCash(venueId, "orden-1", false, BigDecimal("50.00"), BigDecimal("20.00"), any()) } returns
            Result.failure(TablesRepository.PayCashSinConfirmarException())

        viewModel.payCash(BigDecimal("50.00"))

        val s = viewModel.state.value
        assertThat(s.avisoDelCobro).isEqualTo(TablesRepository.COBRO_SIN_CONFIRMAR)
        assertThat(s.tipAmount).isEqualTo(BigDecimal.ZERO) // pudo entrar con su propina: no se ofrece otra vez
        assertThat(s.remaining).isEqualTo(BigDecimal("100.00"))
        assertThat(s.cashReceipt).isNull()
        assertThat(s.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
        assertThat(s.cardPaymentEnabled).isFalse()
        relectura.complete(Result.failure(IOException("sin red")))
        // Una relectura fallida no libera nada: siguen esperando, con «Reintentar».
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(CUENTA_POR_CONFIRMAR)
        assertThat(viewModel.state.value.cardPaymentEnabled).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun D19_si_el_cobro_no_se_pudo_guardar_lo_dice_sin_bloquear_la_cuenta_ni_releer() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD19("100.00")
        val viewModel = createViewModel()
        viewModel.setTip(BigDecimal("10.00"))
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(TablesRepository.PayCashNoGuardadoException("Abre la caja antes de iniciar otra venta."))

        viewModel.payCash(BigDecimal("100.00"))

        val s = viewModel.state.value
        assertThat(s.avisoDelCobro).isEqualTo("Abre la caja antes de iniciar otra venta.")
        assertThat(s.isProcessingCash).isFalse()
        assertThat(s.efectivoBloqueadoPor).isNull()
        assertThat(s.cardPaymentEnabled).isTrue()
        assertThat(s.tipAmount).isEqualTo(BigDecimal("10.00"))
        assertThat(s.remaining).isEqualTo(BigDecimal("100.00"))
        // La carga inicial y la relectura antes de cobrar (D49); ninguna después del fallo.
        coVerify(exactly = 2) { repository.getOrder(venueId, "orden-1") }
    }

    @Test
    fun D19_el_aviso_del_cobro_no_lo_borra_cambiar_la_division_y_se_consume_una_vez() = runTest {
        tableSession.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", total = BigDecimal("100.00")))
        coEvery { repository.getOrder(venueId, "orden-1") } returns cuentaD19("100.00")
        val viewModel = createViewModel()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(TablesRepository.PayCashRejectedException("Order not found", "BUSINESS_RULE"))

        viewModel.payCash(BigDecimal("100.00"))
        viewModel.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)

        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("Order not found")
        viewModel.consumeAvisoDelCobro()
        assertThat(viewModel.state.value.avisoDelCobro).isNull()
    }

    @Test
    fun P1_D19_mesa_abierta_sin_red_aqui_rechazada_no_reofrece_efectivo_con_el_saldo_de_antes() = runTest {
        tableSession.open(tableId = "mesa-1", localOrderId = "local-1")
        val saldo = SaldoDeMesaSinRed(syncOutbox)
        saldo.libreta.anotarRonda("local-1", "r1", BigDecimal("65.00"))
        var filas = listOf(
            filaD86("abrir", SyncIntentTypes.OPEN_TABLE, """{"tableId":"mesa-1","localOrderId":"local-1"}"""),
            filaD86("r1", SyncIntentTypes.ADD_ITEMS, """{"localOrderId":"local-1","items":[]}"""),
        )
        var espera: CompletableDeferred<Unit>? = null
        coEvery { syncOutbox.vivasQueMencionan(venueId, "local-1") } coAnswers { espera?.await(); filas }
        coEvery { repository.payCash(venueId, "local-1", true, BigDecimal("65.00"), any(), any()) } coAnswers {
            espera = CompletableDeferred()
            filas = filas + filaD86("c1", SyncIntentTypes.PAY_CASH, """{"localOrderId":"local-1","amountCents":6500,"tipCents":0}""", SyncIntentEntity.STATUS_REJECTED)
            Result.failure(TablesRepository.PayCashRejectedException("Order not found", "BUSINESS_RULE"))
        }
        val viewModel = createViewModel(saldo)
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isNull()

        viewModel.payCash(BigDecimal("65.00"))

        // Mientras recalcula, el efectivo espera (antes seguía libre con los $65 de antes).
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo("Calculando lo que debe la mesa…")
        assertThat(viewModel.state.value.avisoDelCobro).isEqualTo("Order not found")
        espera!!.complete(Unit)
        assertThat(viewModel.state.value.efectivoBloqueadoPor).isEqualTo(MESA_COBRO_POR_REVISAR)
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    // endregion
}
