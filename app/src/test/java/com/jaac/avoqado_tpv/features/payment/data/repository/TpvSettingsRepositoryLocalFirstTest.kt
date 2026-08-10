package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigResponse
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalDto
import com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsUpdateResponse
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.plan.data.PlanManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * `TpvSettingsRepository.updateSettingLocalFirst` / `syncSettingsToBackend` —
 * the fix for a real production bug (2026-07-28): the founder toggled
 * "Modo Restaurante" ON in Settings, the switch showed `checked=true`, but
 * NO HTTP request ever left the device and the Home screen never showed the
 * "Mesas" tile — the flag was never actually persisted anywhere durable.
 *
 * Root cause: [TpvSettingsRepository.saveSettings] (used by the OLD
 * `updateSettings` path) only writes to [SecureStorage] and the `settings`
 * StateFlow INSIDE the HTTP-success branch. A terminal-local display
 * preference (which tile shows on Home) must survive with ZERO
 * connectivity — this app is offline-first (see avoqado-server
 * `.claude/rules/offline-first-y-hub-lan.md` §4, the printer-config
 * wipe-on-failed-refresh bug — same defect class).
 *
 * These tests prove [updateSettingLocalFirst] writes durably and
 * SYNCHRONOUSLY, with ZERO dependency on the network, and that
 * [syncSettingsToBackend] never undoes that local write on failure.
 *
 * Strict (non-relaxed) mocks + explicit `coVerify(exactly = ...)` on
 * purpose — a relaxed mock silently satisfied by a no-op call is exactly
 * the vacuous-test trap this repo has been bitten by before.
 */
class TpvSettingsRepositoryLocalFirstTest {

    private val apiService = mockk<ApiService>()
    private val secureStorage = mockk<SecureStorage>()
    private val planManager = mockk<PlanManager>(relaxed = true)
    private lateinit var repo: TpvSettingsRepository

    private val onSettings = TpvSettings(restaurantModeEnabled = true)

    @Before
    fun setUp() {
        clearMocks(apiService, secureStorage)
        every { secureStorage.getTpvSettings() } returns TpvSettings.DEFAULT
        every { secureStorage.saveTpvSettings(any()) } returns Unit
        every { secureStorage.getSerialNumber() } returns "SN-001"
        every { secureStorage.getRestaurantModePendingSync() } returns false
        every { secureStorage.setRestaurantModePendingSync(any()) } returns Unit
        every { secureStorage.setShiftSystemEnabled(any()) } returns Unit
        repo = TpvSettingsRepository(apiService, secureStorage, planManager)
    }

    private fun terminalConfigResponse(
        settings: TpvSettings,
        cashReconciliationEnabled: Boolean? = null,
    ): TerminalConfigResponse =
        TerminalConfigResponse(
            success = true,
            data = TerminalConfigData(
                terminal = TerminalDto(
                    id = "term-1",
                    serialNumber = "SN-001",
                    name = "Terminal 1",
                    type = "PAX",
                    status = "ACTIVE",
                    venueId = "venue-1",
                    venue = null
                ),
                merchantAccounts = emptyList(),
                tpvSettings = settings.toDto(),
                cashReconciliationEnabled = cashReconciliationEnabled,
            )
        )

    // region — server-owned cash-reconciliation capability

    @Test
    fun `refresh maps explicit root cash reconciliation capability to local settings`() = runTest {
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(
                terminalConfigResponse(
                    settings = TpvSettings.DEFAULT,
                    cashReconciliationEnabled = true,
                )
            )

        val refreshed = repo.refreshFromTerminalConfig("SN-001").getOrThrow()

        assertThat(refreshed.cashReconciliationEnabled).isTrue()
        assertThat(repo.getCurrentSettings().cashReconciliationEnabled).isTrue()
    }

    @Test
    fun `successful old-server refresh disables a stale cached capability`() = runTest {
        every { secureStorage.getTpvSettings() } returns
            TpvSettings(cashReconciliationEnabled = true)
        repo = TpvSettingsRepository(apiService, secureStorage, planManager)
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(terminalConfigResponse(TpvSettings.DEFAULT))

        val refreshed = repo.refreshFromTerminalConfig("SN-001").getOrThrow()

        assertThat(refreshed.cashReconciliationEnabled).isFalse()
    }

    @Test
    fun `failed initial refresh stays disabled from the false-safe cache`() = runTest {
        coEvery { apiService.getTerminalConfig("SN-001") } throws IOException("sin red")

        val refreshed = repo.refreshFromTerminalConfig("SN-001").getOrThrow()

        assertThat(refreshed.cashReconciliationEnabled).isFalse()
    }

    @Test
    fun `successful capability refresh survives repository restart`() = runTest {
        var persisted = TpvSettings.DEFAULT
        every { secureStorage.getTpvSettings() } answers { persisted }
        every { secureStorage.saveTpvSettings(any()) } answers {
            persisted = it.invocation.args[0] as TpvSettings
        }
        repo = TpvSettingsRepository(apiService, secureStorage, planManager)
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(
                terminalConfigResponse(
                    settings = TpvSettings.DEFAULT,
                    cashReconciliationEnabled = true,
                )
            )

        repo.refreshFromTerminalConfig("SN-001")
        val afterRestart = TpvSettingsRepository(apiService, secureStorage, planManager)

        assertThat(afterRestart.getCurrentSettings().cashReconciliationEnabled).isTrue()
    }

    @Test
    fun `saveSettings preserves cached server-owned capability when PUT response omits it`() = runTest {
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(
                terminalConfigResponse(
                    settings = TpvSettings.DEFAULT,
                    cashReconciliationEnabled = true,
                )
            )
        repo.refreshFromTerminalConfig("SN-001")
        coEvery { apiService.updateTpvSettings("SN-001", any()) } returns
            Response.success(
                TpvSettingsUpdateResponse(
                    success = true,
                    data = TpvSettings(showTipScreen = false).toDto(),
                )
            )

        val saved = repo.saveSettings(
            serialNumber = "SN-001",
            settings = TpvSettings(showTipScreen = false),
        ).getOrThrow()

        assertThat(saved.cashReconciliationEnabled).isTrue()
        assertThat(repo.getCurrentSettings().cashReconciliationEnabled).isTrue()
    }

    // endregion

    // region — updateSettingLocalFirst: durable, synchronous, network-independent

    @Test
    fun `updateSettingLocalFirst escribe a SecureStorage de forma sincrona`() {
        repo.updateSettingLocalFirst(onSettings)

        verify(exactly = 1) { secureStorage.saveTpvSettings(onSettings) }
    }

    @Test
    fun `updateSettingLocalFirst actualiza el StateFlow que Home observa, sin esperar red`() {
        repo.updateSettingLocalFirst(onSettings)

        // getCurrentSettings() lee `_settings.value` directamente — si esto
        // ya refleja el cambio SIN haber llamado a ninguna función suspend,
        // confirma que Home (que colecta este mismo StateFlow) lo vería
        // igual de inmediato, sin depender de un round-trip al backend.
        assertThat(repo.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    @Test
    fun `updateSettingLocalFirst NUNCA toca la red`() {
        repo.updateSettingLocalFirst(onSettings)

        coVerify(exactly = 0) { apiService.updateTpvSettings(any(), any()) }
    }

    // endregion

    // region — syncSettingsToBackend: best-effort, never reverts local state

    @Test
    fun `syncSettingsToBackend exitoso llama al endpoint correcto`() = runTest {
        coEvery { apiService.updateTpvSettings(any(), any()) } returns
            Response.success(TpvSettingsUpdateResponse(success = true, data = onSettings.toDto()))

        repo.updateSettingLocalFirst(onSettings)
        repo.syncSettingsToBackend(onSettings)

        coVerify(exactly = 1) { apiService.updateTpvSettings("SN-001", any()) }
    }

    @Test
    fun `syncSettingsToBackend offline NO revierte el valor local ya guardado`() = runTest {
        coEvery { apiService.updateTpvSettings(any(), any()) } throws IOException("sin red")

        repo.updateSettingLocalFirst(onSettings)
        // El sync de fondo falla — no debe lanzar, y el StateFlow local debe
        // seguir mostrando el valor que el operador realmente puso.
        repo.syncSettingsToBackend(onSettings)

        assertThat(repo.getCurrentSettings().restaurantModeEnabled).isTrue()
        verify(exactly = 1) { secureStorage.saveTpvSettings(onSettings) } // no se re-escribió ni se limpió
    }

    @Test
    fun `syncSettingsToBackend con HTTP error NO revierte el valor local`() = runTest {
        coEvery { apiService.updateTpvSettings(any(), any()) } returns
            Response.error(500, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

        repo.updateSettingLocalFirst(onSettings)
        repo.syncSettingsToBackend(onSettings)

        assertThat(repo.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    @Test
    fun `syncSettingsToBackend sin numero de serie no llama a la red y no truena`() = runTest {
        every { secureStorage.getSerialNumber() } returns null

        repo.updateSettingLocalFirst(onSettings)
        repo.syncSettingsToBackend(onSettings) // no debe lanzar

        coVerify(exactly = 0) { apiService.updateTpvSettings(any(), any()) }
        assertThat(repo.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    // endregion

    // region — survives "restart": a fresh repository re-reads what was persisted

    @Test
    fun `una instancia nueva del repositorio lee el flag persistido (sobrevive reinicio)`() {
        // Simula SecureStorage real: lo que se guarda es lo que se relee.
        var persisted = TpvSettings.DEFAULT
        every { secureStorage.getTpvSettings() } answers { persisted }
        every { secureStorage.saveTpvSettings(any()) } answers {
            persisted = it.invocation.args[0] as TpvSettings
        }
        repo = TpvSettingsRepository(apiService, secureStorage, planManager)

        repo.updateSettingLocalFirst(TpvSettings(restaurantModeEnabled = true))

        // "Reinicio de la app" = nueva instancia del repositorio (Hilt Singleton
        // recreado en un proceso nuevo), leyendo la MISMA SecureStorage.
        val repoAfterRestart = TpvSettingsRepository(apiService, secureStorage, planManager)
        assertThat(repoAfterRestart.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    // endregion

    // region — refreshFromTerminalConfig must NOT let a stale GET clobber an unsynced local-first write

    /**
     * The second-order bug found VERIFYING ON REAL HARDWARE (PAX A910S,
     * 2026-07-28), not just imagined: toggle restaurantModeEnabled ON while
     * offline (updateSettingLocalFirst persists it correctly) → force-stop →
     * relaunch (now online) → the startup `refreshFromTerminalConfig` GET
     * still returns the server's stale `false` (the background sync never
     * got a chance to run) → without a guard, that GET response silently
     * reverts the operator's choice and the "Mesas" tile disappears again.
     */
    @Test
    fun `refresh con cambio local pendiente de sync NO deja que el servidor revierta el flag`() = runTest {
        every { secureStorage.getRestaurantModePendingSync() } returns true
        // Local-first ya puso restaurantModeEnabled=true (mientras estaba offline)
        repo.updateSettingLocalFirst(onSettings) // restaurantModeEnabled = true
        // El servidor todavía tiene el valor viejo porque el sync nunca corrió
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(terminalConfigResponse(TpvSettings(restaurantModeEnabled = false)))
        coEvery { apiService.updateTpvSettings(any(), any()) } returns
            Response.success(TpvSettingsUpdateResponse(success = true, data = onSettings.toDto()))

        repo.refreshFromTerminalConfig("SN-001")

        assertThat(repo.getCurrentSettings().restaurantModeEnabled).isTrue()
    }

    @Test
    fun `refresh con cambio pendiente intenta re-sincronizar de una vez (self-healing)`() = runTest {
        every { secureStorage.getRestaurantModePendingSync() } returns true
        repo.updateSettingLocalFirst(onSettings)
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(terminalConfigResponse(TpvSettings(restaurantModeEnabled = false)))
        coEvery { apiService.updateTpvSettings(any(), any()) } returns
            Response.success(TpvSettingsUpdateResponse(success = true, data = onSettings.toDto()))

        repo.refreshFromTerminalConfig("SN-001")

        // Ya estamos online (el GET recién tuvo éxito) — debe aprovechar y
        // mandar el cambio pendiente, en vez de esperar a que el operador
        // toque el switch otra vez.
        coVerify(exactly = 1) { apiService.updateTpvSettings("SN-001", any()) }
    }

    @Test
    fun `sin cambio pendiente, refresh SI aplica el valor del servidor normalmente`() = runTest {
        // Regresión: el guard no debe congelar el valor local para siempre —
        // solo mientras haya un cambio sin confirmar.
        every { secureStorage.getRestaurantModePendingSync() } returns false
        coEvery { apiService.getTerminalConfig("SN-001") } returns
            Response.success(terminalConfigResponse(TpvSettings(restaurantModeEnabled = true)))

        repo.refreshFromTerminalConfig("SN-001")

        assertThat(repo.getCurrentSettings().restaurantModeEnabled).isTrue()
        coVerify(exactly = 0) { apiService.updateTpvSettings(any(), any()) } // no hay nada pendiente que empujar
    }

    // endregion
}
