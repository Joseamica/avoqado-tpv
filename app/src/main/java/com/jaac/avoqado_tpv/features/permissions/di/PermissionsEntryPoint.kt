package com.jaac.avoqado_tpv.features.permissions.di

import com.jaac.avoqado_tpv.core.data.manager.KioskModeManager
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.plan.data.PlanManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Permissions Entry Point for Hilt Dependency Injection
 *
 * Provides access to PermissionsRepository from @Composable functions.
 *
 * **Why EntryPoint?**
 * - @Composable functions cannot use @Inject directly
 * - EntryPoint allows manual dependency resolution
 * - Installed in SingletonComponent for app-wide access
 *
 * **Usage in Composables:**
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val context = LocalContext.current
 *
 *     // Get permissions repository via EntryPoint
 *     val permissionsRepository = remember {
 *         val hiltEntryPoint = EntryPointAccessors.fromApplication(
 *             context.applicationContext,
 *             PermissionsEntryPoint::class.java
 *         )
 *         hiltEntryPoint.permissionsRepository()
 *     }
 *
 *     // Check permission
 *     var hasAccess by remember { mutableStateOf(false) }
 *     LaunchedEffect(Unit) {
 *         hasAccess = permissionsRepository.hasPermission("tpv-terminal:settings")
 *     }
 * }
 * ```
 *
 * **Alternative for ViewModels:**
 * ViewModels can use @Inject directly, no EntryPoint needed:
 * ```kotlin
 * @HiltViewModel
 * class MyViewModel @Inject constructor(
 *     private val permissionsRepository: PermissionsRepository
 * ) : ViewModel()
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PermissionsEntryPoint {
    /**
     * Get PermissionsRepository instance
     *
     * @return Singleton instance of PermissionsRepository
     */
    fun permissionsRepository(): PermissionsRepository

    /**
     * Get KioskModeManager instance
     *
     * @return Singleton instance of KioskModeManager
     */
    fun kioskModeManager(): KioskModeManager

    /**
     * Get ModulesRepository instance
     *
     * Used for configuration-driven UI (e.g., simplified order flow for telecom)
     *
     * @return Singleton instance of ModulesRepository
     */
    fun modulesRepository(): ModulesRepository

    /**
     * Get TpvSettingsRepository instance
     *
     * Used for accessing TPV settings (e.g., kioskModeEnabled, showReviewScreen)
     *
     * @return Singleton instance of TpvSettingsRepository
     */
    fun tpvSettingsRepository(): TpvSettingsRepository

    /**
     * Get PlanManager instance
     *
     * Used for plan-tier gating UI (visible teasers for REFERRAL_PROGRAM,
     * PROMOTIONS, ADVANCED_REPORTS, SERIALIZED_INVENTORY). Fail-open: when
     * plan info is unknown the manager allows everything.
     *
     * @return Singleton instance of PlanManager
     */
    fun planManager(): PlanManager
}
