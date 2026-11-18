package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.DineroDeTarjetaDeLaCuenta
import com.jaac.avoqado_tpv.features.tables.data.SaldoDeMesaSinRed
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
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
import java.math.BigDecimal

/**
 * hz:nube0000:na286b5f (encargo 64000e, 18-nov-2026), parte del EFECTIVO: «Tu mesa» se quedaba pegada a una cuenta ya
 * pagada si el «Liberar mesa» en línea contestaba 500 o un error inesperado: sólo se preguntaba por la cuenta tras un 4xx.
 * Ahora se pregunta tras cualquier respuesta que no es de red (una vez, de inmediato). Si la cuenta todavía debe o la pregunta
 * no contesta, la sesión se queda, como antes (reintentar más tarde podía pisar la siguiente mesa: Codex r3 #5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableCheckoutSesionNoQuedaPegadaTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<TablesRepository>()
    private val tableSyncCoordinator = mockk<TableSyncCoordinator>()
    private val networkMonitor = mockk<NetworkMonitor>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private val syncOutbox = mockk<SyncOutbox>()
    private val dineroDeTarjeta = mockk<DineroDeTarjetaDeLaCuenta>()
    private lateinit var tableSession: TableSession
    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tableSession = TableSession()
        every { deviceInfoManager.getVenueId() } returns venueId
        every { networkMonitor.isConnected() } returns true
        every { networkMonitor.networkStateFlow } returns emptyFlow()
        coEvery { tableSyncCoordinator.replay(any()) } returns Unit
        coEvery { syncOutbox.vivasQueMencionan(any(), any()) } returns emptyList()
        coEvery { syncOutbox.aperturasConfirmadasQueMencionan(any(), any()) } returns emptyList()
        coEvery { syncOutbox.aperturasConfirmadasConResultado(any(), any()) } returns emptyList()
        every { syncOutbox.observarCambios(any()) } returns MutableStateFlow(0)
        coEvery { dineroDeTarjeta.motivo(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = TableCheckoutViewModel(
        repository = repository,
        tableSession = tableSession,
        tableSyncCoordinator = tableSyncCoordinator,
        networkMonitor = networkMonitor,
        deviceInfoManager = deviceInfoManager,
        saldoDeMesaSinRed = SaldoDeMesaSinRed(syncOutbox),
        connectionStateManager = ConnectionStateManager(),
        dineroDeTarjeta = dineroDeTarjeta,
    )

    private val porCobrar = Result.success(OrderDetail(id = "b", total = BigDecimal("20.00"), amountLeft = BigDecimal("20.00")))
    private val pagada = Result.success(OrderDetail(id = "b", status = "COMPLETED", paymentStatus = "PAID", total = BigDecimal("20.00")))

    @Test
    fun na286b5f_un_500_al_liberar_la_mesa_pregunta_por_la_cuenta_y_suelta_la_sesion_si_ya_no_debe() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder(venueId, "b") } returnsMany listOf(porCobrar, pagada)
        coEvery { repository.clearTable(venueId, "m8", "b", false) } returns
            Result.failure(BackendHttpException(statusCode = 500, message = "Internal Server Error"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("20.00"))

        assertThat(tableSession.current()).isNull()
    }

    @Test
    fun control_si_la_cuenta_todavia_debe_tras_un_500_la_sesion_se_queda() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder(venueId, "b") } returnsMany listOf(
            porCobrar,
            Result.success(OrderDetail(id = "b", status = "PENDING", paymentStatus = "PARTIAL", total = BigDecimal("20.00"))),
        )
        coEvery { repository.clearTable(venueId, "m8", "b", false) } returns
            Result.failure(BackendHttpException(statusCode = 500, message = "Internal Server Error"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("20.00"))

        assertThat(tableSession.current()?.orderId).isEqualTo("b")
    }

    @Test
    fun na286b5f_un_error_inesperado_al_liberar_tambien_pregunta_por_la_cuenta() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder(venueId, "b") } returnsMany listOf(porCobrar, pagada)
        coEvery { repository.clearTable(venueId, "m8", "b", false) } returns Result.failure(IllegalStateException("respuesta rara"))
        val viewModel = createViewModel()

        viewModel.onPaymentCompleted(BigDecimal("20.00"))

        assertThat(tableSession.current()).isNull()
    }
}
