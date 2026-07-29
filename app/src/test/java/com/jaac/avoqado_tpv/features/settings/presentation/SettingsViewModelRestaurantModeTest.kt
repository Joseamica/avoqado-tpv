package com.jaac.avoqado_tpv.features.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.KioskModeManager
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.payment.data.MultiMerchantSDKManager
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.plan.data.PlanManager
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Regression tests for the real bug reported 2026-07-28: the founder turned
 * on "Modo Restaurante" in Settings, the switch showed `checked=true`, but
 * NO HTTP request left the device and the "Mesas" tile never appeared on
 * Home — the flag was never actually persisted.
 *
 * Root cause: [SettingsViewModel.toggleRestaurantMode] used to route through
 * the generic `updateSetting()` helper, which is network-only — it writes
 * to [TpvSettingsRepository] (SecureStorage + the StateFlow Home reads)
 * ONLY inside the HTTP-success branch, and the "optimistic" UI update only
 * touched this ViewModel's own `_state`, never the repository. So an
 * offline toggle, a slow request, or this screen's ViewModel being cleared
 * by navigation before the coroutine finished left the switch stuck showing
 * ON while nothing was ever durably saved.
 *
 * **`StandardTestDispatcher` on purpose** (not `UnconfinedTestDispatcher`,
 * unlike other ViewModel tests in this repo): it does NOT auto-run queued
 * coroutines. This lets the tests assert that the local write already
 * happened — and the network was never even called — WITHOUT ever
 * advancing the dispatcher. That is exactly the property that was missing:
 * persistence must not depend on a coroutine getting a chance to run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelRestaurantModeTest {

    private val testDispatcher = StandardTestDispatcher()

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val planManager = mockk<PlanManager>(relaxed = true)
    private lateinit var tpvSettingsRepository: TpvSettingsRepository

    private val printerManager = mockk<PrinterManager>(relaxed = true)
    private val shiftRepository = mockk<ShiftRepository>(relaxed = true)
    private val kioskModeManager = mockk<KioskModeManager>(relaxed = true)
    private val permissionsRepository = mockk<PermissionsRepository>(relaxed = true)
    private val merchantRepository = mockk<MerchantRepository>(relaxed = true)
    private val multiMerchantSDKManager = mockk<MultiMerchantSDKManager>(relaxed = true)

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { secureStorage.getTpvSettings() } returns TpvSettings.DEFAULT
        every { secureStorage.getSerialNumber() } returns "SN-001"
        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        every { kioskModeManager.isKioskMode } returns MutableStateFlow(false)
        every { merchantRepository.getActiveMerchants() } returns flowOf(emptyList())
        coEvery { permissionsRepository.hasPermission(any()) } returns false

        tpvSettingsRepository = TpvSettingsRepository(apiService, secureStorage, planManager)

        viewModel = SettingsViewModel(
            secureStorage = secureStorage,
            tpvSettingsRepository = tpvSettingsRepository,
            printerManager = printerManager,
            shiftRepository = shiftRepository,
            kioskModeManager = kioskModeManager,
            permissionsRepository = permissionsRepository,
            merchantRepository = merchantRepository,
            multiMerchantSDKManager = multiMerchantSDKManager
        )
        // Let init{}'s coroutines (loadSettings/observeTpvSettings/...) settle
        // so every test starts from a known baseline: restaurantModeEnabled=false.
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `baseline antes de tocar el switch es apagado`() {
        assertThat(viewModel.state.value.tpvSettings.restaurantModeEnabled).isFalse()
    }

    @Test
    fun `toggle persiste local YA, ANTES de que corra ninguna corrutina de red`() {
        // A propósito: NO se llama advanceUntilIdle() antes de estas
        // aserciones. Si la escritura local viviera dentro del
        // viewModelScope.launch (como el bug original), este test fallaría
        // porque nada se habría ejecutado todavía.
        viewModel.toggleRestaurantMode()

        assertThat(viewModel.state.value.tpvSettings.restaurantModeEnabled).isTrue()
        assertThat(tpvSettingsRepository.getCurrentSettings().restaurantModeEnabled).isTrue()
        verify(exactly = 1) { secureStorage.saveTpvSettings(match { it.restaurantModeEnabled }) }
        // La red todavía no corrió — la persistencia no depende de ella.
        coVerify(exactly = 0) { apiService.updateTpvSettings(any(), any()) }
    }

    @Test
    fun `toggle SI intenta sincronizar a backend en segundo plano, best-effort`() = runTest {
        coEvery { apiService.updateTpvSettings(any(), any()) } throws IOException("sin red")

        viewModel.toggleRestaurantMode()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { apiService.updateTpvSettings("SN-001", any()) }
    }

    @Test
    fun `sin red, el switch NO se revierte tras el intento de sync`() = runTest {
        coEvery { apiService.updateTpvSettings(any(), any()) } throws IOException("sin red")

        viewModel.toggleRestaurantMode()
        testDispatcher.scheduler.advanceUntilIdle() // deja correr (y fallar) el sync de fondo

        assertThat(viewModel.state.value.tpvSettings.restaurantModeEnabled).isTrue()
        assertThat(tpvSettingsRepository.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    @Test
    fun `si el coroutine de sync nunca corre (pantalla cerrada de inmediato), el flag ya quedo guardado`() {
        // Simula exactamente el escenario reportado: el operador toca el
        // switch y navega fuera de Configuración de inmediato — el
        // viewModelScope se cancela y el launch{} del sync NUNCA se ejecuta
        // (nunca llamamos advanceUntilIdle en este test). La persistencia
        // debe sobrevivir de todos modos.
        viewModel.toggleRestaurantMode()

        assertThat(tpvSettingsRepository.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    @Test
    fun `apagar el switch revierte simetricamente, tambien local-first`() {
        viewModel.toggleRestaurantMode() // ON
        assertThat(tpvSettingsRepository.getCurrentSettings().restaurantModeEnabled).isTrue()

        viewModel.toggleRestaurantMode() // OFF
        assertThat(viewModel.state.value.tpvSettings.restaurantModeEnabled).isFalse()
        assertThat(tpvSettingsRepository.getCurrentSettings().restaurantModeEnabled).isFalse()
    }
}
