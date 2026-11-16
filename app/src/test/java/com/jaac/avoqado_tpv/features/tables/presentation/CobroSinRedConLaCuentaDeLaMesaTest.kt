package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDiscountLine
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * D8 (hz:72be07e6:D8, visto en la PAX el 14-nov): el mesero abre la mesa y manda una ronda CON red (la mesa dice
 * «Pagar · $25.00»), se cae el WiFi y toca «Pagar»: el cobro decía «Restante $0.00» y el efectivo no se podía cobrar. La
 * pantalla de la mesa cargaba la cuenta pero nunca se la pasaba a [TableSession], y el cobro arrancaba con `total = 0`.
 *
 * Estas pruebas usan las DOS pantallas reales sobre la MISMA sesión, como en el aparato: la mesa la carga, el cobro la lee.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroSinRedConLaCuentaDeLaMesaTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val repository = mockk<TablesRepository>()
    private val tableSyncCoordinator = mockk<TableSyncCoordinator>()
    private val syncOutbox = mockk<SyncOutbox>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private val secureStorage = mockk<SecureStorage>()
    private val networkMonitor = mockk<NetworkMonitor>()
    private val autorizacion = mockk<AutorizacionDeGerente>()
    private lateinit var tableSession: TableSession
    private lateinit var pendingCart: PendingRoundCart

    private val venueId = "venue-1"
    private val orden = "orden-1"

    /** La cuenta como la contesta el GET del SERVIDOR: una ronda de $25 sin pagos. */
    private val cuentaDe25 = OrderDetail(
        id = orden,
        version = 2,
        subtotal = BigDecimal("25.00"),
        total = BigDecimal("25.00"),
        amountLeft = BigDecimal("25.00"),
        items = listOf(OrderDetailItem(id = "item-1", productId = "prod-1", quantity = 1, total = BigDecimal("25.00"))),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, tableSyncCoordinator, syncOutbox, deviceInfoManager, secureStorage, networkMonitor)
        tableSession = TableSession()
        pendingCart = PendingRoundCart()

        every { deviceInfoManager.getVenueId() } returns venueId
        every { repository.ownership } returns MutableStateFlow(TablesRepository.TableOwnership())
        coEvery { tableSyncCoordinator.replay(any()) } returns Unit
        coEvery { tableSyncCoordinator.hayPendientesDe(any(), any()) } returns false
        coEvery { syncOutbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
        every { secureStorage.getStaffId() } returns "staff-1"
        coEvery { autorizacion.paraCortesia(any(), any(), any()) } returns Autorizacion.NoHaceFalta
        every { networkMonitor.networkStateFlow } returns emptyFlow()
        conRed()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun conRed() {
        every { networkMonitor.isConnected() } returns true
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(cuentaDe25)
    }

    private fun sinRed() {
        every { networkMonitor.isConnected() } returns false
        coEvery { repository.getOrder(venueId, orden) } returns Result.failure(IOException("sin red"))
    }

    private fun pantallaDeLaMesa() = TableOrderViewModel(
        repository = repository,
        tableSession = tableSession,
        pendingCart = pendingCart,
        tableSyncCoordinator = tableSyncCoordinator,
        syncOutbox = syncOutbox,
        deviceInfoManager = deviceInfoManager,
        secureStorage = secureStorage,
        networkMonitor = networkMonitor,
        autorizacionDeGerente = autorizacion,
    )

    private fun pantallaDeCobro() = TableCheckoutViewModel(
        repository = repository,
        tableSession = tableSession,
        tableSyncCoordinator = tableSyncCoordinator,
        networkMonitor = networkMonitor,
        deviceInfoManager = deviceInfoManager,
    )

    /** «Abrir mesa» con red: la sesión nace sin total (`TablesViewModel.openTableAndOrder`). */
    private fun abrirMesa() = tableSession.start(TableSession.Active(tableId = "mesa-12", tableNumber = "M12", orderId = orden))

    private fun cobroGuardadoSinRed() {
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        coEvery { repository.clearTable(any(), any()) } returns Result.success(Unit)
    }

    @Test
    fun d8_la_mesa_ya_vio_la_cuenta_y_sin_red_el_cobro_arranca_con_su_restante_y_su_desglose() = runTest {
        abrirMesa()
        pantallaDeLaMesa() // con red: carga la cuenta de $25
        sinRed()

        val cobro = pantallaDeCobro()

        val estado = cobro.state.value
        assertThat(estado.remaining).isEqualToIgnoringScale("25.00")
        assertThat(estado.check).isEqualTo(cuentaDe25) // el desglose de la última vez, no «abierta local»
        assertThat(estado.error).isNull()
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("25.00")
    }

    @Test
    fun d8_sin_red_el_efectivo_cobra_ese_restante_y_manda_lo_que_la_terminal_creia_que_se_debia() = runTest {
        abrirMesa()
        pantallaDeLaMesa()
        sinRed()
        cobroGuardadoSinRed()
        val cobro = pantallaDeCobro()

        cobro.payCash(cobro.chargeableAmount())

        coVerify(exactly = 1) {
            repository.payCash(venueId, orden, false, BigDecimal("25.00"), BigDecimal.ZERO, BigDecimal("25.00"))
        }
        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("0")
        assertThat(cobro.state.value.notice).contains("Sin conexión")
    }

    @Test
    fun una_ronda_mandada_sin_red_entra_al_restante_para_no_cobrar_de_menos() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa() // $25 con red
        sinRed()
        // La ronda sin red: TablesRepository la encola y regresa la cuenta OPTIMISTA («pending-»).
        coEvery { repository.addItems(venueId, orden, any(), any()) } returns Result.success(
            OrderDetail(id = orden, items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-2", quantity = 1))),
        )
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))
        mesa.sendRound()

        val cobro = pantallaDeCobro()

        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("40.00")
        assertThat(cobro.state.value.porSincronizar).isEqualToIgnoringScale("15.00")
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("40.00")
    }

    @Test
    fun una_ronda_con_red_no_se_cuenta_doble_cuando_llega_la_cuenta_real() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa()
        // Con red: el PATCH contesta sin `amount_left`; la recarga trae la cuenta real de $40.
        coEvery { repository.addItems(venueId, orden, any(), any()) } returns
            Result.success(OrderDetail(id = orden, version = 3, items = listOf(OrderDetailItem(id = "item-2", productId = "prod-2"))))
        val cuentaDe40 = cuentaDe25.copy(version = 3, total = BigDecimal("40.00"), amountLeft = BigDecimal("40.00"))
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(cuentaDe40)
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))

        mesa.sendRound()

        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("40.00")
        assertThat(tableSession.current()!!.porSincronizar).isEqualToIgnoringScale("0")
    }

    @Test
    fun la_cuenta_que_salio_antes_de_un_cobro_no_regresa_el_saldo_viejo() = runTest {
        abrirMesa()
        pantallaDeLaMesa() // $25
        // El GET del cobro tarda: sale ANTES del efectivo y contesta DESPUÉS, con el saldo de antes ($25).
        val lenta = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, orden) } coAnswers { lenta.await() }
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } returns
            Result.success(TablesRepository.PayCashOutcome(paymentId = "pay-1", orderNumber = "A-1", queued = false))
        val cobro = pantallaDeCobro()
        cobro.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        cobro.setCustomAmountInput("10")
        cobro.payCash(cobro.chargeableAmount()) // abona $10: quedan $15

        // La respuesta vieja llega, y la recarga que provoca ya trae el abono.
        coEvery { repository.getOrder(venueId, orden) } returns
            Result.success(cuentaDe25.copy(paidAmount = BigDecimal("10.00"), amountLeft = BigDecimal("15.00")))
        lenta.complete(Result.success(cuentaDe25))

        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("15.00")
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("15.00")
        assertThat(cobro.state.value.check?.paidAmount).isEqualToIgnoringScale("10.00")
    }

    @Test
    fun una_cuenta_que_todavia_no_existe_en_el_servidor_no_manda_saldo_esperado() = runTest {
        // Mesa abierta sin red: su id es local; ANDROID tampoco lo manda para la provisional.
        tableSession.open(tableId = "mesa-12", localOrderId = "local-1")
        tableSession.empezarCambioDeSaldo()
        tableSession.terminarCambioDeSaldo("local-1", BigDecimal("25.00"))
        cobroGuardadoSinRed()
        sinRed()
        val cobro = pantallaDeCobro()

        cobro.payCash(BigDecimal("25.00"))

        coVerify(exactly = 1) { repository.payCash(venueId, "local-1", true, BigDecimal("25.00"), BigDecimal.ZERO, null) }
    }

    @Test
    fun codex_r1_1_la_recarga_que_ya_trae_la_siguiente_ronda_no_la_cuenta_doble() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa() // $25
        // Ronda 1 ($15) con red: su recarga se queda en camino…
        val recargaDeLaRonda1 = CompletableDeferred<Result<OrderDetail>>()
        val respuestas = ArrayDeque(
            listOf<suspend () -> Result<OrderDetail>>(
                { recargaDeLaRonda1.await() },
                { Result.failure(IOException("se cayó la red")) },
            ),
        )
        coEvery { repository.getOrder(venueId, orden) } coAnswers { respuestas.removeFirst()() }
        val ronda2 = CompletableDeferred<Result<OrderDetail>>()
        var envios = 0
        coEvery { repository.addItems(venueId, orden, any(), any()) } coAnswers {
            if (++envios == 1) Result.success(OrderDetail(id = orden, version = 3)) else ronda2.await()
        }
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))
        mesa.sendRound()
        // …y mientras, sale la ronda 2 ($10). El SERVIDOR la aplica y la recarga de la ronda 1 ya la trae: $50.
        pendingCart.addSimple(productId = "prod-3", name = "Café", unitPrice = BigDecimal("10.00"))
        mesa.sendRound()
        recargaDeLaRonda1.complete(Result.success(cuentaDe25.copy(total = BigDecimal("50.00"), amountLeft = BigDecimal("50.00"))))
        ronda2.complete(Result.success(OrderDetail(id = orden, version = 4))) // su recarga falla: se cayó la red

        // $25 + $15 + $10, una sola vez cada ronda (sin el arreglo: $60).
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("50.00")
    }

    @Test
    fun codex_r1_2_si_la_ronda_sigue_en_la_cola_la_lectura_no_la_borra_del_restante() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa() // $25
        sinRed()
        coEvery { repository.addItems(venueId, orden, any(), any()) } returns Result.success(
            OrderDetail(id = orden, items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-2", quantity = 1))),
        )
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))
        mesa.sendRound() // $40, con $15 por sincronizar
        // Vuelve la red a medias: el replay no alcanza a subir la ronda (RETRY), pero la lectura sí llega, todavía en $25.
        conRed()
        coEvery { tableSyncCoordinator.hayPendientesDe(venueId, setOf(orden)) } returns true

        val cobro = pantallaDeCobro()

        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("40.00")
        assertThat(cobro.state.value.porSincronizar).isEqualToIgnoringScale("15.00")
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("40.00")
    }

    @Test
    fun codex_r2_1_la_cuenta_que_ya_trae_el_abono_antes_de_que_el_cobro_termine_no_lo_resta_dos_veces() = runTest {
        abrirMesa()
        pantallaDeLaMesa() // $25
        val lenta = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, orden) } coAnswers { lenta.await() } // el GET del cobro sale primero…
        val cobroEnCamino = CompletableDeferred<Result<TablesRepository.PayCashOutcome>>()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } coAnswers { cobroEnCamino.await() }
        val cobro = pantallaDeCobro()
        cobro.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        cobro.setCustomAmountInput("10")
        cobro.payCash(cobro.chargeableAmount()) // …el abono de $10 sale…

        // …el SERVIDOR lo aplica y el GET llega con $15 ANTES de que el cobro termine aquí.
        coEvery { repository.getOrder(venueId, orden) } returns Result.failure(IOException("se cayó la red"))
        lenta.complete(Result.success(cuentaDe25.copy(paidAmount = BigDecimal("10.00"), amountLeft = BigDecimal("15.00"))))
        cobroEnCamino.complete(Result.success(TablesRepository.PayCashOutcome(paymentId = "pay-1", orderNumber = "A-1", queued = false)))

        // $25 − $10 una sola vez (sin el arreglo: $5).
        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("15.00")
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("15.00")
    }

    @Test
    fun codex_r2_2_una_cuenta_vista_solo_en_el_plano_no_se_cobra_con_su_total_bruto_si_la_lectura_no_se_puede_tomar() = runTest {
        // «Ver cuenta»: $100 en el plano, con $60 ya abonados. La sesión ya no arranca con los $100 brutos.
        tableSession.start(TableSession.Active(tableId = "mesa-12", orderId = orden))
        coEvery { tableSyncCoordinator.hayPendientesDe(venueId, setOf(orden)) } returns true // un cobro sin red de esta terminal sin subir
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(
            cuentaDe25.copy(total = BigDecimal("100.00"), paidAmount = BigDecimal("60.00"), amountLeft = BigDecimal("40.00")),
        )

        val cobro = pantallaDeCobro()

        // Ni $100 (el bruto) ni $40 (la lectura que no trae el cobro pendiente): no se sabe, así que no se cobra y se dice.
        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("0")
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("0")
        assertThat(cobro.state.value.saldoPorConfirmar).isTrue()
        assertThat(cobro.state.value.check?.amountLeft).isEqualToIgnoringScale("40.00") // el desglose sí se ve

        // Codex r3 #3: en cuanto la cola sube lo pendiente, el cobro vuelve a leer solo y ya se puede cobrar.
        coEvery { tableSyncCoordinator.hayPendientesDe(venueId, setOf(orden)) } returns false
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(
            cuentaDe25.copy(total = BigDecimal("100.00"), paidAmount = BigDecimal("70.00"), amountLeft = BigDecimal("30.00")),
        )
        tableSession.algoSeSincronizo()

        assertThat(cobro.state.value.saldoPorConfirmar).isFalse()
        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("30.00")
    }

    @Test
    fun codex_r3_1_una_ronda_sobre_un_saldo_que_no_se_conoce_no_lo_vuelve_cobrable() = runTest {
        // «Ver cuenta» de una mesa con $25: la primera lectura tarda y el mesero manda $15 mientras tanto.
        tableSession.start(TableSession.Active(tableId = "mesa-12", orderId = orden))
        val primera = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, orden) } coAnswers { primera.await() }
        val mesa = pantallaDeLaMesa()
        val rondaEnCamino = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.addItems(venueId, orden, any(), any()) } coAnswers { rondaEnCamino.await() }
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))
        mesa.sendRound()
        primera.complete(Result.success(cuentaDe25)) // llega con la ronda en camino: se ve, pero no se toma
        sinRed() // y la recarga de después de la ronda ya no llega
        rondaEnCamino.complete(Result.success(OrderDetail(id = orden, version = 3)))

        // Se deben $40; la terminal no lo sabe. Nunca $15 como si fuera toda la cuenta.
        assertThat(tableSession.current()!!.saldoConfirmado).isFalse()
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("0")
        val cobro = pantallaDeCobro()
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("0")
        assertThat(cobro.state.value.saldoPorConfirmar).isTrue()
    }

    @Test
    fun codex_r3_2_una_lectura_de_antes_de_separar_no_deshace_el_saldo_de_despues() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa() // $25
        // Ronda de $15 con red: su recarga ($40) tarda.
        val recargaDeLaRonda = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrder(venueId, orden) } coAnswers { recargaDeLaRonda.await() }
        coEvery { repository.addItems(venueId, orden, any(), any()) } returns Result.success(OrderDetail(id = orden, version = 3))
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))
        mesa.sendRound()
        // Mientras, el mesero separa el artículo de $25: la recarga de la separación llega primero, con $15.
        coEvery { repository.splitOrder(venueId, orden, any()) } returns
            Result.success(TablesRepository.SplitOutcome(queued = false, createdOrderId = "orden-2", createdOrderNumber = "A-2", sourceVersion = 5))
        coEvery { repository.getOrder(venueId, orden) } returns
            Result.success(cuentaDe25.copy(version = 5, total = BigDecimal("15.00"), amountLeft = BigDecimal("15.00")))
        mesa.splitOrder(setOf("item-1"))
        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("15.00")

        // Ahora llega la recarga vieja de la ronda, con $40.
        recargaDeLaRonda.complete(Result.success(cuentaDe25.copy(version = 3, total = BigDecimal("40.00"), amountLeft = BigDecimal("40.00"))))

        assertThat(tableSession.current()!!.total).isEqualToIgnoringScale("15.00") // sin el arreglo: $40
    }

    @Test
    fun separar_sin_poder_releer_deja_el_saldo_por_confirmar_y_no_se_cobra_el_de_antes() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa() // $25
        coEvery { repository.splitOrder(venueId, orden, any()) } returns
            Result.success(TablesRepository.SplitOutcome(queued = false, createdOrderId = "orden-2", createdOrderNumber = "A-2", sourceVersion = 5))
        sinRed() // separó con red, pero la recarga ya no llega

        mesa.splitOrder(setOf("item-1"))
        val cobro = pantallaDeCobro()

        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("0") // nunca los $25 de antes de separar
        assertThat(cobro.state.value.saldoPorConfirmar).isTrue()
        assertThat(cobro.state.value.check).isEqualTo(cuentaDe25) // el desglose de la última vez sí se ve
    }

    @Test
    fun codex_r4_1_con_un_cobro_en_camino_no_se_quita_un_descuento_y_con_un_ajuste_en_camino_no_se_cobra() = runTest {
        abrirMesa()
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(
            cuentaDe25.copy(
                total = BigDecimal("90.00"),
                discountAmount = BigDecimal("10.00"),
                amountLeft = BigDecimal("90.00"),
                orderDiscounts = listOf(OrderDiscountLine(id = "desc-1", name = "Cortesía", amount = BigDecimal("10.00"))),
            ),
        )
        pantallaDeLaMesa()
        val cobroEnCamino = CompletableDeferred<Result<TablesRepository.PayCashOutcome>>()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } coAnswers { cobroEnCamino.await() }
        coEvery { repository.removeDiscount(any(), any(), any()) } returns
            Result.success(TablesRepository.DiscountOutcome(queued = false, amount = null, newOrderTotal = null))
        val cobro = pantallaDeCobro()
        cobro.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        cobro.setCustomAmountInput("10")
        cobro.payCash(cobro.chargeableAmount()) // abono de $10, en camino

        cobro.removeDiscount("desc-1") // el mesero toca «Quitar» mientras: no sale
        coVerify(exactly = 0) { repository.removeDiscount(any(), any(), any()) }

        cobroEnCamino.complete(Result.success(TablesRepository.PayCashOutcome(paymentId = "p", orderNumber = "A", queued = false)))
        assertThat(cobro.state.value.remaining).isEqualToIgnoringScale("80.00")
        assertThat(cobro.state.value.tableReleased).isFalse()

        // Y al revés: con el «Quitar» en camino, el efectivo no sale.
        val quitarEnCamino = CompletableDeferred<Result<TablesRepository.DiscountOutcome>>()
        coEvery { repository.removeDiscount(any(), any(), any()) } coAnswers { quitarEnCamino.await() }
        cobro.removeDiscount("desc-1")
        assertThat(cobro.state.value.ajusteEnCurso).isTrue()
        cobro.payCash(BigDecimal("10.00"))
        coVerify(exactly = 1) { repository.payCash(any(), any(), any(), any(), any(), any()) } // sólo el primero
        quitarEnCamino.complete(Result.success(TablesRepository.DiscountOutcome(queued = false, amount = null, newOrderTotal = null)))
        assertThat(cobro.state.value.ajusteEnCurso).isFalse()
    }

    @Test
    fun codex_r4_2_una_ronda_sobre_una_cuenta_con_cargo_o_descuento_deja_el_saldo_por_confirmar() = runTest {
        abrirMesa()
        // $100 de productos + 10 % de cargo por servicio = $110 confirmados.
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(
            cuentaDe25.copy(subtotal = BigDecimal("100.00"), serviceChargeAmount = BigDecimal("10.00"), total = BigDecimal("110.00"), amountLeft = BigDecimal("110.00")),
        )
        val mesa = pantallaDeLaMesa()
        sinRed()
        coEvery { repository.addItems(venueId, orden, any(), any()) } returns Result.success(
            OrderDetail(id = orden, items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-2", quantity = 1))),
        )
        pendingCart.addSimple(productId = "prod-2", name = "Otra ronda", unitPrice = BigDecimal("100.00"))

        mesa.sendRound()
        val cobro = pantallaDeCobro()

        // El SERVIDOR cobrará $220 (el cargo se recalcula); la terminal no lo sabe: ni $210 ni nada, por confirmar.
        assertThat(tableSession.current()!!.saldoConfirmado).isFalse()
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("0")
        assertThat(cobro.state.value.saldoPorConfirmar).isTrue()
    }

    @Test
    fun codex_r5_salir_con_el_abono_en_camino_deja_el_saldo_por_confirmar_y_no_se_ofrece_el_de_antes() = runTest {
        abrirMesa()
        pantallaDeLaMesa() // $25 confirmados
        val cobroEnCamino = CompletableDeferred<Result<TablesRepository.PayCashOutcome>>()
        coEvery { repository.payCash(any(), any(), any(), any(), any(), any()) } coAnswers { cobroEnCamino.await() }
        val cobro = pantallaDeCobro()
        cobro.selectSplitType(CheckoutSplitType.CUSTOMAMOUNT)
        cobro.setCustomAmountInput("10")
        cobro.payCash(cobro.chargeableAmount()) // el abono de $10 ya va en la cola, su respuesta tarda…

        cobro.viewModelScope.cancel() // …y el mesero toca «Atrás»: la pantalla se destruye
        sinRed()
        val otraVez = pantallaDeCobro() // vuelve a «Pagar»

        // Nunca los $25 de antes del abono: no se sabe, así que no se cobra hasta sincronizar.
        assertThat(tableSession.current()!!.saldoConfirmado).isFalse()
        assertThat(otraVez.chargeableAmount()).isEqualToIgnoringScale("0")
        assertThat(otraVez.state.value.saldoPorConfirmar).isTrue()
    }

    @Test
    fun codex_r6_1_si_quitar_un_descuento_falla_no_se_sabe_si_quedo_y_no_se_cobra_el_saldo_de_antes() = runTest {
        abrirMesa()
        coEvery { repository.getOrder(venueId, orden) } returns Result.success(
            cuentaDe25.copy(
                total = BigDecimal("90.00"),
                discountAmount = BigDecimal("10.00"),
                amountLeft = BigDecimal("90.00"),
                orderDiscounts = listOf(OrderDiscountLine(id = "desc-1", name = "Cortesía", amount = BigDecimal("10.00"))),
            ),
        )
        pantallaDeLaMesa()
        val cobro = pantallaDeCobro()
        // El SERVIDOR quitó el descuento ($100), pero la respuesta se perdió: el repositorio lo reporta como «necesita conexión».
        coEvery { repository.removeDiscount(any(), any(), any()) } returns
            Result.failure(TablesRepository.RemoveDiscountRequiresConnectionException())
        sinRed()

        cobro.removeDiscount("desc-1")

        // Ni los $90 de antes ni nada: por confirmar hasta releer.
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("0")
        assertThat(cobro.state.value.saldoPorConfirmar).isTrue()
        assertThat(tableSession.current()!!.saldoConfirmado).isFalse()
    }

    @Test
    fun codex_r8_1_si_la_cola_deja_el_saldo_por_confirmar_el_cobro_abierto_deja_de_ofrecer_el_de_antes() = runTest {
        abrirMesa()
        val mesa = pantallaDeLaMesa() // $25
        sinRed()
        coEvery { repository.addItems(venueId, orden, any(), any()) } returns Result.success(
            OrderDetail(id = orden, items = listOf(OrderDetailItem(id = "pending-0", productId = "prod-2", quantity = 1))),
        )
        pendingCart.addSimple(productId = "prod-2", name = "Postre", unitPrice = BigDecimal("15.00"))
        mesa.sendRound()
        val cobro = pantallaDeCobro()
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("40.00")

        // Vuelve la red y el worker sube la ronda: el SERVIDOR la rechaza. La cola deja la sesión por confirmar y avisa
        // (lo que hace `SyncOutbox.reaccionarAlSaldo`; probado con la cola real en SyncOutboxRechazoMueveElSaldoTest).
        tableSession.saldoPorConfirmar()
        tableSession.algoSeSincronizo()

        assertThat(cobro.state.value.saldoPorConfirmar).isTrue()
        assertThat(cobro.chargeableAmount()).isEqualToIgnoringScale("0") // ya no los $40
    }
}
