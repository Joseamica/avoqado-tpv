package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedTimeEntryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.entities.CachedTimeEntryEntity
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.timeclock.data.dto.TimeEntriesListResponseDto
import com.jaac.avoqado_tpv.features.timeclock.data.repository.TimeEntryRepositoryImpl
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDateTime

/**
 * «Cobrar sin red», D4: el reloj checador se recuerda sin red.
 *
 * Con `requireClockInToLogin` encendido, sin red no se podía leer la entrada activa, `HomeViewModel` dejaba
 * `currentTimeEntry = null` y TODOS los botones (Cobrar incluido) quedaban apagados con «Registra tu entrada».
 * Ahora, si el servidor no contestó, se usa la última entrada que ESTE aparato confirmó para ESA persona en ESE negocio
 * (igual que el turno guardado); un 4xx sí es una respuesta y no se sustituye.
 *
 * Los tests del ViewModel usan `Dispatchers.IO` de verdad (el `fetchAttendanceState` lo lanza ahí): por eso `runBlocking`
 * con tope en tiempo real y no `runTest` (el tiempo virtual dispararía el tope antes de que termine el hilo de IO).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsistenciaSinRedTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val creados = mutableListOf<HomeViewModel>()

    private lateinit var secureStorage: SecureStorage
    private lateinit var timeEntryRepository: TimeEntryRepository
    private lateinit var tpvSettingsRepository: com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
    private lateinit var connectionStateManager: com.jaac.avoqado_tpv.core.util.ConnectionStateManager
    private lateinit var sessionManager: SessionManager
    private lateinit var socketManager: com.jaac.avoqado_tpv.core.data.realtime.SocketManager
    private lateinit var connectionEventManager: com.jaac.avoqado_tpv.core.util.ConnectionEventManager
    private lateinit var updateCheckManager: com.jaac.avoqado_tpv.core.util.UpdateCheckManager
    private lateinit var saleVerificationRepository: com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
    private val dao = DaoEnMemoria()

    /** Un DAO en memoria con la misma llave (negocio + persona) que el real: así «otra persona» se prueba de verdad. */
    private class DaoEnMemoria : CachedTimeEntryDao {
        val filas = mutableMapOf<Pair<String, String>, CachedTimeEntryEntity>()
        override suspend fun guardar(e: CachedTimeEntryEntity) { filas[e.venueId to e.staffId] = e }
        override suspend fun leer(venueId: String, staffId: String) = filas[venueId to staffId]
        override suspend fun borrar(venueId: String, staffId: String) { filas.remove(venueId to staffId) }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        secureStorage = mockk(relaxed = true)
        timeEntryRepository = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        connectionStateManager = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        updateCheckManager = mockk(relaxed = true)
        saleVerificationRepository = mockk(relaxed = true)
        coEvery { saleVerificationRepository.getPendingVerifications() } returns kotlin.Result.success(emptyList())

        every { connectionStateManager.connectionState } returns
            MutableStateFlow(com.jaac.avoqado_tpv.core.util.ConnectionState())
        every { sessionManager.sessionEvents } returns MutableSharedFlow()
        every { socketManager.events } returns MutableSharedFlow()
        every { socketManager.isConnected } returns MutableSharedFlow()
        every { connectionEventManager.connectionRestoredEvents } returns MutableSharedFlow()
        every { updateCheckManager.pendingUpdate } returns MutableStateFlow(null)

        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        every { secureStorage.getVenueId() } returns VENUE
        every { secureStorage.getStaffId() } returns STAFF
        every { secureStorage.getToken() } returns "jwt-token"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings.DEFAULT.copy(requireClockInToLogin = true)
    }

    @After
    fun tearDown() {
        try {
            runBlocking {
                creados.forEach { it.viewModelScope.cancel() }
                creados.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            creados.clear()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    // ───────────────────────── HomeViewModel ─────────────────────────

    @Test
    fun `sin red con entrada guardada CLOCKED_IN ⇒ canWork true`() {
        dao.filas[VENUE to STAFF] = guardada(entryId = "te-1", status = "CLOCKED_IN", clockIn = "2026-10-21T09:30:00")
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns sinRed()

        val vm = crearVm()
        esperarAsistencia(vm)

        assertThat(vm.currentTimeEntry.value?.status).isEqualTo(TimeEntryStatus.CLOCKED_IN)
        assertThat(vm.currentTimeEntry.value?.id).isEqualTo("te-1")
        assertThat(vm.currentTimeEntry.value?.clockInTime).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 30))
        assertThat(vm.clockInTime.value).isEqualTo("Desde las 09:30")
        assertThat(vm.requireClockInToLogin.value).isTrue()
        assertThat(canWork(vm)).isTrue() // la misma cuenta que hace WelcomeScreen para encender los botones
    }

    @Test
    fun `sin red el servidor que responde 503 o 524 también cuenta como sin red`() {
        dao.filas[VENUE to STAFF] = guardada(status = "CLOCKED_IN")
        for (codigo in listOf(502, 503, 504, 520, 524, 527)) {
            coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns
                kotlin.Result.failure(ApiException.HttpError(codigo, "borde"))
            val vm = crearVm()
            esperarAsistencia(vm)
            assertThat(canWork(vm)).isTrue()
        }
    }

    @Test
    fun `sin red sin entrada guardada ⇒ como hoy`() {
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns sinRed()

        val vm = crearVm()
        esperarAsistencia(vm)

        assertThat(vm.currentTimeEntry.value).isNull()
        assertThat(vm.clockInTime.value).isNull()
        assertThat(canWork(vm)).isFalse() // «Registra tu entrada», como antes
    }

    @Test
    fun `con red sin entrada ⇒ borra la guardada`() {
        dao.filas[VENUE to STAFF] = guardada(status = "CLOCKED_IN")
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns kotlin.Result.success(null)

        val vm = crearVm()
        esperarAsistencia(vm)

        assertThat(vm.currentTimeEntry.value).isNull()
        assertThat(dao.filas).isEmpty()
    }

    @Test
    fun `con red con entrada ⇒ la guarda para la siguiente vez sin red`() {
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns
            kotlin.Result.success(entrada(id = "te-9", status = TimeEntryStatus.ON_BREAK, clockIn = LocalDateTime.of(2026, 10, 21, 8, 5)))

        val vm = crearVm()
        esperarAsistencia(vm)

        val fila = dao.filas[VENUE to STAFF]
        assertThat(fila?.entryId).isEqualTo("te-9")
        assertThat(fila?.status).isEqualTo("ON_BREAK")
        assertThat(LocalDateTime.parse(fila?.clockInTime)).isEqualTo(LocalDateTime.of(2026, 10, 21, 8, 5))

        // y esa fila se lee de vuelta tal cual cuando ya no hay red
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns sinRed()
        val sinRedVm = crearVm()
        esperarAsistencia(sinRedVm)
        assertThat(sinRedVm.currentTimeEntry.value?.status).isEqualTo(TimeEntryStatus.ON_BREAK)
        assertThat(sinRedVm.clockInTime.value).isEqualTo("Desde las 08:05")
    }

    @Test
    fun `otra persona en la terminal ⇒ no usa la entrada guardada ajena`() {
        dao.filas[VENUE to "staff-ajena"] = guardada(status = "CLOCKED_IN").copy(staffId = "staff-ajena")
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns sinRed()

        val vm = crearVm()
        esperarAsistencia(vm)

        assertThat(vm.currentTimeEntry.value).isNull()
        assertThat(canWork(vm)).isFalse()
    }

    @Test
    fun `otro negocio ⇒ no usa la entrada guardada de un negocio anterior`() {
        dao.filas["venue-anterior" to STAFF] = guardada(status = "CLOCKED_IN").copy(venueId = "venue-anterior")
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns sinRed()

        val vm = crearVm()
        esperarAsistencia(vm)

        assertThat(vm.currentTimeEntry.value).isNull()
    }

    @Test
    fun `un 403 del servidor es una respuesta, no un sin red ⇒ no resucita la guardada ni la borra`() {
        dao.filas[VENUE to STAFF] = guardada(status = "CLOCKED_IN")
        coEvery { timeEntryRepository.findActiveEntryForStaff(VENUE, STAFF) } returns
            kotlin.Result.failure(ApiException.HttpError(403, "sin permiso"))

        val vm = crearVm()
        esperarAsistencia(vm)

        assertThat(vm.currentTimeEntry.value).isNull()
        assertThat(dao.filas).hasSize(1)
    }

    // ─────────────── TimeEntryRepositoryImpl: qué falla es «sin red» ───────────────

    private val api: ApiService = mockk()
    private val repo by lazy { TimeEntryRepositoryImpl(api) }

    @Test
    fun `repositorio - un IOException o un timeout es NetworkError y servidorNoContesto`() = runBlocking {
        for (e in listOf(IOException("Unable to resolve host"), SocketTimeoutException("timeout"))) {
            coEvery { api.getMyTimeEntries(any(), any(), any(), any(), any()) } throws e
            val falla = repo.findActiveEntryForStaff(VENUE, STAFF).exceptionOrNull()
            assertThat(falla).isInstanceOf(ApiException.NetworkError::class.java)
            assertThat((falla as ApiException).servidorNoContesto).isTrue()
        }
    }

    @Test
    fun `repositorio - 502 a 504 y 520 a 527 son servidorNoContesto, y conservan el codigo`() = runBlocking {
        for (codigo in listOf(502, 503, 504, 520, 524, 527)) {
            coEvery { api.getMyTimeEntries(any(), any(), any(), any(), any()) } returns
                Response.error(codigo, "".toResponseBody())
            val falla = repo.findActiveEntryForStaff(VENUE, STAFF).exceptionOrNull()
            assertThat(falla).isInstanceOf(ApiException.HttpError::class.java)
            assertThat((falla as ApiException.HttpError).code).isEqualTo(codigo)
            assertThat(falla.servidorNoContesto).isTrue()
        }
    }

    @Test
    fun `repositorio - un 403 o un 500 no son servidorNoContesto`() = runBlocking {
        for (codigo in listOf(400, 403, 500, 501)) {
            coEvery { api.getMyTimeEntries(any(), any(), any(), any(), any()) } returns
                Response.error(codigo, "".toResponseBody())
            val falla = repo.findActiveEntryForStaff(VENUE, STAFF).exceptionOrNull()
            assertThat((falla as ApiException).servidorNoContesto).isFalse()
        }
    }

    @Test
    fun `repositorio - un 404 sigue siendo exito sin entrada`() = runBlocking {
        coEvery { api.getMyTimeEntries(any(), any(), any(), any(), any()) } returns
            Response.error(404, "".toResponseBody())
        val r = repo.findActiveEntryForStaff(VENUE, STAFF)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrNull()).isNull()
    }

    @Test
    fun `repositorio - 200 sin entradas activas sigue siendo exito sin entrada`() = runBlocking {
        coEvery { api.getMyTimeEntries(any(), any(), any(), any(), any()) } returns
            Response.success(TimeEntriesListResponseDto(success = true, data = emptyList(), meta = null))
        val r = repo.findActiveEntryForStaff(VENUE, STAFF)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrNull()).isNull()
    }

    // ───────────────────────── ayudas ─────────────────────────

    private fun crearVm(): HomeViewModel = HomeViewModel(
        authRepository = mockk(relaxed = true),
        socketManager = socketManager,
        secureStorage = secureStorage,
        initializationManager = mockk(relaxed = true),
        getMerchantsUseCase = mockk(relaxed = true),
        commandExecutor = mockk(relaxed = true),
        heartbeatRepository = mockk(relaxed = true),
        lockScreenManager = mockk(relaxed = true),
        maintenanceManager = mockk(relaxed = true),
        productRepository = mockk(relaxed = true),
        catalogoGuardado = mockk<CatalogoGuardado>(relaxed = true),
        productCategoryDao = mockk<ProductCategoryDao>(relaxed = true),
        apiService = mockk<ApiService>(relaxed = true),
        connectionEventManager = connectionEventManager,
        terminalConfigRepository = mockk(relaxed = true),
        merchantRepository = mockk(relaxed = true),
        deviceInfoManager = mockk(relaxed = true),
        angelPaySdkGatewayProvider = mockk(relaxed = true),
        angelPayAuthRepositoryProvider = mockk(relaxed = true),
        angelPayAuthRecoveryProvider = mockk(relaxed = true),
        connectionStateManager = connectionStateManager,
        remotePaymentCoordinator = mockk(relaxed = true),
        printerManager = mockk(relaxed = true),
        updateCheckManager = updateCheckManager,
        sessionManager = sessionManager,
        paymentQueueRepository = mockk(relaxed = true),
        paymentQueueStateManager = com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager(),
        observabilityManager = mockk(relaxed = true),
        orderSyncCoordinator = mockk(relaxed = true),
        timeEntryRepository = timeEntryRepository,
        cachedTimeEntryDao = dao,
        tpvSettingsRepository = tpvSettingsRepository,
        saleVerificationRepository = saleVerificationRepository
    ).also { creados.add(it) }

    /** `_isAttendanceLoading` nace en true y baja a false en el `finally` del fetch (que corre en Dispatchers.IO). */
    private fun esperarAsistencia(vm: HomeViewModel) = runBlocking {
        withTimeout(10_000) { vm.isAttendanceLoading.first { !it } }
    }

    /** La misma cuenta de WelcomeScreen (`isClockedIn || !requireClockInToLogin`). */
    private fun canWork(vm: HomeViewModel) =
        vm.currentTimeEntry.value?.status == TimeEntryStatus.CLOCKED_IN || !vm.requireClockInToLogin.value

    private fun sinRed() = kotlin.Result.failure<TimeEntry?>(ApiException.NetworkError(IOException("sin red")))

    private fun guardada(entryId: String = "te-1", status: String, clockIn: String? = "2026-10-21T09:30:00") =
        CachedTimeEntryEntity(VENUE, STAFF, entryId, status, clockIn, cachedAt = 1_000L)

    private fun entrada(id: String, status: TimeEntryStatus, clockIn: LocalDateTime) = TimeEntry(
        id = id, staffId = STAFF, staffName = "Empleado", venueId = VENUE, clockInTime = clockIn, clockOutTime = null,
        jobRole = null, totalHours = null as BigDecimal?, breakMinutes = 0, status = status, checkInPhotoUrl = null, breaks = emptyList()
    )

    private companion object {
        const val VENUE = "venue-123"
        const val STAFF = "staff-A"
    }
}
