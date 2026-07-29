package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.presentation.components.ScreenProfile
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `TablesScreen` view-state logic (Plan C, Task 6). Cubre lo que el plan pide
 * explícitamente: el toggle canvas/lista (default por tamaño de pantalla +
 * persistencia de la elección del usuario), el mapeo de estado de mesa, y el
 * render offline-first (nunca tapar un plano que ya cargó con una pantalla de
 * error — `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3). La
 * corrección visual (colores, contraste, layout) se verifica en el
 * dispositivo, no aquí.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TablesViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val repository = mockk<TablesRepository>()
    private val coordinator = mockk<TableSyncCoordinator>()
    private val deviceInfoManager = mockk<DeviceInfoManager>()
    private val socketManager = mockk<SocketManager>()
    private lateinit var tableSession: TableSession
    private lateinit var socketEvents: MutableSharedFlow<SocketEvent>

    private val venueId = "venue-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearMocks(repository, coordinator, deviceInfoManager, socketManager)

        tableSession = TableSession()
        socketEvents = MutableSharedFlow()

        every { deviceInfoManager.getVenueId() } returns venueId
        every { socketManager.events } returns socketEvents
        coEvery { coordinator.replay(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TablesViewModel = TablesViewModel(
        tablesRepository = repository,
        tableSyncCoordinator = coordinator,
        tableSession = tableSession,
        deviceInfoManager = deviceInfoManager,
        socketManager = socketManager,
    )

    private fun table(
        id: String = "t1",
        number: String = "1",
        status: String = "AVAILABLE",
        areaId: String? = "a1",
        areaName: String? = "Terraza",
    ) = DiningTable(id = id, number = number, status = status, areaId = areaId, areaName = areaName)

    // region — defaultViewMode: literal del plan + extensión documentada

    @Test
    fun en_pantalla_chica_el_modo_por_default_es_LISTA() {
        // Un canvas de 20 mesas en 480x480 (NEXGO) es inoperable.
        assertThat(defaultViewMode(ScreenProfile.CompactSquare)).isEqualTo(TableViewMode.LIST)
    }

    @Test
    fun en_pantalla_grande_el_modo_por_default_es_CANVAS() {
        assertThat(defaultViewMode(ScreenProfile.RegularPortrait)).isEqualTo(TableViewMode.CANVAS)
    }

    @Test
    fun pax_a80_compact_portrait_tambien_arranca_en_LISTA() {
        // Chica para el mismo propósito que NEXGO, aunque el plan no la puso
        // bajo test explícito — documentado en el KDoc de defaultViewMode.
        assertThat(defaultViewMode(ScreenProfile.CompactPortrait)).isEqualTo(TableViewMode.LIST)
    }

    // endregion

    // region — el toggle persiste la elección del usuario

    @Test
    fun el_toggle_persiste_la_eleccion_del_usuario() = runTest {
        val vm = createViewModel()

        vm.setViewMode(TableViewMode.CANVAS)

        assertThat(vm.viewMode.value).isEqualTo(TableViewMode.CANVAS)
    }

    @Test
    fun initialize_no_pisa_la_eleccion_del_usuario_en_llamadas_posteriores() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.success(emptyList())
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()

        vm.initialize(ScreenProfile.CompactSquare) // default → LIST
        vm.setViewMode(TableViewMode.CANVAS) // el usuario elige a mano
        vm.initialize(ScreenProfile.CompactSquare) // p.ej. una rotación re-dispara initialize()

        assertThat(vm.viewMode.value).isEqualTo(TableViewMode.CANVAS)
    }

    // endregion

    // region — mapeo de estado visual de mesa

    @Test
    fun mapea_los_4_status_crudos_del_server_al_estado_visual() {
        assertThat(tableDisplayStatus("AVAILABLE")).isEqualTo(TableDisplayStatus.FREE)
        assertThat(tableDisplayStatus("OCCUPIED")).isEqualTo(TableDisplayStatus.OCCUPIED)
        assertThat(tableDisplayStatus("RESERVED")).isEqualTo(TableDisplayStatus.RESERVED)
        assertThat(tableDisplayStatus("CLEANING")).isEqualTo(TableDisplayStatus.NEEDS_ATTENTION)
    }

    @Test
    fun un_status_desconocido_del_server_se_ve_como_NEEDS_ATTENTION_nunca_como_libre() {
        // El server agregó un status nuevo que esta TPV no conoce todavía —
        // nunca se debe esconder como si la mesa estuviera disponible.
        assertThat(tableDisplayStatus("SOME_NEW_STATUS")).isEqualTo(TableDisplayStatus.NEEDS_ATTENTION)
    }

    // endregion

    // region — agrupación por área (TablesUiState.areas)

    @Test
    fun agrupa_las_mesas_por_area_y_cuenta_correctamente() {
        val state = TablesUiState(
            tables = listOf(
                table(id = "1", areaId = "a1", areaName = "Terraza"),
                table(id = "2", areaId = "a1", areaName = "Terraza"),
                table(id = "3", areaId = "a2", areaName = "Salón"),
                table(id = "4", areaId = null, areaName = null),
            ),
        )

        val areas = state.areas.associateBy { it.name }
        assertThat(areas["Terraza"]?.tableCount).isEqualTo(2)
        assertThat(areas["Salón"]?.tableCount).isEqualTo(1)
        assertThat(areas["Sin área"]?.tableCount).isEqualTo(1)
    }

    // endregion

    // region — offline-first de LECTURA: nunca tapar un plano cargado con un error

    @Test
    fun al_cargar_con_exito_puebla_el_plano() = runTest {
        val tables = listOf(table())
        coEvery { repository.getTables(venueId) } returns Result.success(tables)
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()

        vm.initialize(ScreenProfile.RegularPortrait)

        assertThat(vm.uiState.value.tables).isEqualTo(tables)
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.isOffline).isFalse()
        assertThat(vm.uiState.value.errorMessage).isNull()
    }

    @Test
    fun la_carga_inicial_en_frio_NUNCA_prende_isRefreshing_solo_isLoading() = runTest {
        // El spinner de pull-to-refresh es SOLO para el gesto manual con datos
        // ya en pantalla — el primer arranque en frío usa el loader de
        // pantalla completa (isLoading), nunca los dos indicadores a la vez.
        coEvery { repository.getTables(venueId) } returns Result.success(listOf(table()))
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()

        vm.initialize(ScreenProfile.RegularPortrait)

        assertThat(vm.uiState.value.isRefreshing).isFalse()
    }

    @Test
    fun refresh_con_datos_ya_cargados_prende_isRefreshing_mientras_dura_y_lo_apaga_al_terminar() = runTest {
        val tables = listOf(table())
        coEvery { repository.getTables(venueId) } returns Result.success(tables)
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.initialize(ScreenProfile.RegularPortrait) // primera carga: ya hay datos

        // El segundo refresco tarda un poco — para poder observar el estado
        // A MEDIA carga, no solo el final.
        coEvery { repository.getTables(venueId) } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(tables)
        }

        vm.refresh()
        assertThat(vm.uiState.value.isRefreshing).isTrue() // a media carga
        assertThat(vm.uiState.value.isLoading).isFalse() // NUNCA el loader de pantalla completa aquí

        advanceTimeBy(101)

        assertThat(vm.uiState.value.isRefreshing).isFalse() // terminó
    }

    @Test
    fun un_refresh_manual_que_falla_tambien_apaga_isRefreshing() = runTest {
        val tables = listOf(table())
        coEvery { repository.getTables(venueId) } returns Result.success(tables)
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.initialize(ScreenProfile.RegularPortrait)

        coEvery { repository.getTables(venueId) } returns Result.failure(java.io.IOException("sin red"))
        vm.refresh()

        assertThat(vm.uiState.value.isRefreshing).isFalse()
        assertThat(vm.uiState.value.isOffline).isTrue()
    }

    @Test
    fun sin_plano_cacheado_y_sin_red_muestra_error_con_reintentar() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.failure(java.io.IOException("sin red"))
        coEvery { repository.getFloorElements(venueId) } returns Result.failure(java.io.IOException("sin red"))
        val vm = createViewModel()

        vm.initialize(ScreenProfile.RegularPortrait)

        assertThat(vm.uiState.value.tables).isEmpty()
        assertThat(vm.uiState.value.errorMessage).isNotNull()
        assertThat(vm.uiState.value.isOffline).isFalse() // no hay nada que "seguir mostrando"
    }

    @Test
    fun con_plano_ya_cargado_un_refresco_fallido_NO_tapa_la_pantalla_con_un_error() = runTest {
        // 🔴 La regla de oro del módulo, aplicada a lectura: "offline es
        // estado normal, no error" — ver offline-first-y-hub-lan.md §2.3.
        val tables = listOf(table())
        coEvery { repository.getTables(venueId) } returns Result.success(tables)
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.initialize(ScreenProfile.RegularPortrait) // primera carga: éxito

        coEvery { repository.getTables(venueId) } returns Result.failure(java.io.IOException("sin red"))
        vm.refresh() // segundo refresco: falla

        assertThat(vm.uiState.value.tables).isEqualTo(tables) // el plano sigue ahí
        assertThat(vm.uiState.value.isOffline).isTrue()
        assertThat(vm.uiState.value.errorMessage).isNull() // NUNCA un error duro
    }

    @Test
    fun refresh_drena_el_outbox_antes_de_pedir_el_plano() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.success(emptyList())
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()

        vm.initialize(ScreenProfile.RegularPortrait)

        coVerify(exactly = 1) { coordinator.replay(venueId) }
    }

    @Test
    fun un_replay_que_falla_no_tumba_el_refresco_del_plano() = runTest {
        // replay() es best-effort — si truena (bug propio, lo que sea), el
        // refresco de mesas se sigue de todos modos.
        coEvery { coordinator.replay(any()) } throws RuntimeException("boom")
        val tables = listOf(table())
        coEvery { repository.getTables(venueId) } returns Result.success(tables)
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()

        vm.initialize(ScreenProfile.RegularPortrait)

        assertThat(vm.uiState.value.tables).isEqualTo(tables)
        assertThat(vm.uiState.value.errorMessage).isNull()
    }

    // endregion

    // region — TableSession: resaltar "tu mesa"

    @Test
    fun refleja_la_mesa_de_la_sesion_activa_de_este_dispositivo() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.success(emptyList())
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.initialize(ScreenProfile.RegularPortrait)

        tableSession.start(TableSession.Active(tableId = "mesa-9", orderId = "orden-1"))

        assertThat(vm.uiState.value.activeSessionTableId).isEqualTo("mesa-9")
    }

    @Test
    fun sin_sesion_activa_activeSessionTableId_es_null() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.success(emptyList())
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()

        vm.initialize(ScreenProfile.RegularPortrait)

        assertThat(vm.uiState.value.activeSessionTableId).isNull()
    }

    // endregion

    // region — tiempo real: socket dispara un refresco debounced

    @Test
    fun un_cambio_de_status_por_socket_dispara_un_refresco_tras_el_debounce() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.success(emptyList())
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.initialize(ScreenProfile.RegularPortrait)
        coVerify(exactly = 1) { repository.getTables(venueId) } // solo la carga inicial hasta aquí

        val refreshedTables = listOf(table(id = "new"))
        coEvery { repository.getTables(venueId) } returns Result.success(refreshedTables)

        socketEvents.emit(
            SocketEvent.TableStatusChange(type = "status", userId = "u1", role = "WAITER", tableId = "t1", venueId = venueId, timestamp = ""),
        )
        advanceTimeBy(801)

        assertThat(vm.uiState.value.tables).isEqualTo(refreshedTables)
        coVerify(exactly = 2) { repository.getTables(venueId) }
    }

    @Test
    fun eventos_irrelevantes_de_socket_NO_disparan_un_refresco() = runTest {
        coEvery { repository.getTables(venueId) } returns Result.success(emptyList())
        coEvery { repository.getFloorElements(venueId) } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.initialize(ScreenProfile.RegularPortrait)

        socketEvents.emit(SocketEvent.Connected)
        advanceTimeBy(1000)

        coVerify(exactly = 1) { repository.getTables(venueId) } // solo la carga inicial
    }

    // endregion
}
