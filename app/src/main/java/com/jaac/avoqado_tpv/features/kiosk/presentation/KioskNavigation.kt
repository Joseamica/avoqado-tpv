package com.jaac.avoqado_tpv.features.kiosk.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.presentation.navigation.NavRoute
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskOperationalState
import com.jaac.avoqado_tpv.features.kiosk.domain.model.UnavailableReason
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminAuth
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminBottomSheet
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminPinDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskCartScreen
import com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskMenuScreen
import com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskSuccessScreen
import com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskUnavailableScreen
import com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskWelcomeScreen
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * EntryPoint for accessing dependencies in KioskNavigation
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskNavigationEntryPoint {
    fun kioskModeManager(): com.jaac.avoqado_tpv.core.data.manager.KioskModeManager
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
    fun shiftRepository(): com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
}

/**
 * Kiosk Mode Navigation Graph
 *
 * Self-contained navigation for customer self-service mode.
 * Completely separate from staff navigation to ensure:
 * - No accidental access to staff features
 * - Simplified flow for customers
 * - Clean separation of concerns
 *
 * **v2 Features**:
 * - Operational state guard (shows unavailable screen when not ready)
 * - Periodic shift status checking (every 30 seconds)
 * - Admin mode accessible from unavailable screen
 *
 * Flow:
 * KioskWelcome → KioskMenu → KioskCart → Payment → KioskSuccess → (auto-reset) → KioskWelcome
 *
 * @param navController Navigation controller for this graph
 * @param onExitKiosk Callback when staff successfully exits kiosk mode
 * @param onNavigateToPayment Callback to navigate to payment screen with order details
 * @param onClearCartRequest Callback to request cart clearing from outside KioskNavigation
 */
@Composable
fun KioskNavigation(
    navController: NavHostController,
    onExitKiosk: () -> Unit,
    onNavigateToPayment: (orderId: String, amount: Long, orderNumber: String) -> Unit,
    onClearCartRequest: ((clearCart: () -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current

    // Get dependencies
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskNavigationEntryPoint::class.java
        )
    }
    val kioskModeManager = remember { entryPoint.kioskModeManager() }
    val secureStorage = remember { entryPoint.secureStorage() }
    val shiftRepository = remember { entryPoint.shiftRepository() }

    // Create ViewModel scoped to this navigation graph - shared between all kiosk screens
    val kioskViewModel: KioskViewModel = hiltViewModel()

    // Provide clearCart function to parent (AppNavigation) for post-payment cleanup
    LaunchedEffect(kioskViewModel) {
        onClearCartRequest?.invoke { kioskViewModel.clearCart() }
    }

    // Observe operational state
    val operationalState by kioskModeManager.operationalState.collectAsStateWithLifecycle()

    // Admin mode state for unavailable screen
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var showAdminBottomSheet by remember { mutableStateOf(false) }
    var adminAuth by remember { mutableStateOf<KioskAdminAuth?>(null) }

    // Check shift status periodically
    LaunchedEffect(Unit) {
        val venueId = secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: ""
        val isShiftSystemEnabled = secureStorage.isShiftSystemEnabled()

        Timber.d("🥝 [KIOSK-NAV] Starting shift status check (venueId: $venueId, shiftsEnabled: $isShiftSystemEnabled)")

        // Initial check
        if (isShiftSystemEnabled && venueId.isNotEmpty()) {
            checkShiftStatus(shiftRepository, kioskModeManager, venueId)
        } else {
            // No shift system, mark as ready
            kioskModeManager.setReady()
        }

        // Periodic check every 30 seconds
        while (true) {
            delay(30_000) // 30 seconds
            if (isShiftSystemEnabled && venueId.isNotEmpty()) {
                checkShiftStatus(shiftRepository, kioskModeManager, venueId)
            }
        }
    }

    Timber.d("🥝 [KIOSK-NAV] KioskNavigation composable rendering - state: $operationalState")

    // Handle operational state
    when (val state = operationalState) {
        is KioskOperationalState.Checking -> {
            // Show loading or welcome screen while checking
            Timber.d("🥝 [KIOSK-NAV] Checking operational state...")
            // Just render welcome - it will update when state changes
            KioskWelcomeScreen(
                onStart = {
                    // Don't start if still checking
                    Timber.d("🥝 [KIOSK-NAV] Start tapped while checking - waiting...")
                },
                onExitKiosk = onExitKiosk
            )
        }

        is KioskOperationalState.Ready -> {
            // Normal kiosk flow
            NavHost(
                navController = navController,
                startDestination = NavRoute.KioskWelcome.route
            ) {
                // Welcome screen - "Touch to Start"
                composable(NavRoute.KioskWelcome.route) {
                    Timber.d("🥝 [KIOSK-NAV] → Welcome screen")
                    KioskWelcomeScreen(
                        onStart = {
                            Timber.i("🥝 [KIOSK-NAV] Welcome → Menu (start order)")
                            kioskModeManager.setOrderInProgress(true)
                            navController.navigate(NavRoute.KioskMenu.route)
                        },
                        onExitKiosk = {
                            Timber.i("🥝 [KIOSK-NAV] Exit from Welcome screen")
                            onExitKiosk()
                        }
                    )
                }

                // Menu screen - Product selection with cart
                composable(NavRoute.KioskMenu.route) {
                    Timber.d("🥝 [KIOSK-NAV] → Menu screen")
                    KioskMenuScreen(
                        viewModel = kioskViewModel,
                        onCartClick = {
                            Timber.i("🥝 [KIOSK-NAV] Menu → Cart (view order)")
                            navController.navigate(NavRoute.KioskCart.route)
                        },
                        onBack = {
                            Timber.i("🥝 [KIOSK-NAV] Menu → Welcome (cancel order)")
                            kioskModeManager.setOrderInProgress(false)
                            navController.navigate(NavRoute.KioskWelcome.route) {
                                popUpTo(NavRoute.KioskWelcome.route) { inclusive = true }
                            }
                        },
                        onExitKiosk = {
                            Timber.i("🥝 [KIOSK-NAV] Exit from Menu screen")
                            onExitKiosk()
                        }
                    )
                }

                // Cart screen - Order summary before payment
                composable(NavRoute.KioskCart.route) {
                    Timber.d("🥝 [KIOSK-NAV] → Cart screen")
                    KioskCartScreen(
                        viewModel = kioskViewModel,
                        onBack = {
                            Timber.d("🥝 [KIOSK-NAV] Cart → Menu (back)")
                            navController.popBackStack()
                        },
                        onPayNow = { orderId, amount, orderNumber ->
                            Timber.i("🥝 [KIOSK-NAV] Cart → Payment (order: $orderNumber, amount: $amount)")
                            onNavigateToPayment(orderId, amount, orderNumber)
                        },
                        onExitKiosk = {
                            Timber.i("🥝 [KIOSK-NAV] Exit from Cart screen")
                            onExitKiosk()
                        }
                    )
                }

                // Success screen - Thank you with auto-reset
                composable(
                    route = NavRoute.KioskSuccess.route,
                    arguments = listOf(
                        navArgument("orderNumber") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val orderNumber = backStackEntry.arguments?.getString("orderNumber") ?: ""
                    Timber.d("🥝 [KIOSK-NAV] → Success screen (order: $orderNumber)")
                    KioskSuccessScreen(
                        orderNumber = orderNumber,
                        onTimeout = {
                            // Reset to welcome screen after countdown
                            Timber.i("🥝 [KIOSK-NAV] Success → Welcome (timeout reset)")
                            kioskModeManager.setOrderInProgress(false)
                            kioskViewModel.clearCart()
                            navController.navigate(NavRoute.KioskWelcome.route) {
                                popUpTo(NavRoute.KioskWelcome.route) { inclusive = true }
                            }
                        }
                    )
                }
            }
        }

        is KioskOperationalState.NoShift -> {
            if (state.allowCurrentOrder) {
                // Allow customer to finish their order even though shift closed
                Timber.d("🥝 [KIOSK-NAV] NoShift but allowing current order to finish")
                NavHost(
                    navController = navController,
                    startDestination = NavRoute.KioskCart.route
                ) {
                    composable(NavRoute.KioskCart.route) {
                        KioskCartScreen(
                            viewModel = kioskViewModel,
                            onBack = {
                                // If they go back, they can't continue - show unavailable
                                kioskModeManager.setOrderInProgress(false)
                            },
                            onPayNow = { orderId, amount, orderNumber ->
                                onNavigateToPayment(orderId, amount, orderNumber)
                            },
                            onExitKiosk = onExitKiosk
                        )
                    }

                    composable(
                        route = NavRoute.KioskSuccess.route,
                        arguments = listOf(
                            navArgument("orderNumber") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val orderNumber = backStackEntry.arguments?.getString("orderNumber") ?: ""
                        KioskSuccessScreen(
                            orderNumber = orderNumber,
                            onTimeout = {
                                kioskModeManager.setOrderInProgress(false)
                                kioskViewModel.clearCart()
                            }
                        )
                    }
                }
            } else {
                // Show unavailable screen
                Timber.d("🥝 [KIOSK-NAV] Showing unavailable screen - NoShift")
                KioskUnavailableScreen(
                    reason = UnavailableReason.SHIFT_CLOSED,
                    onAdminClick = {
                        showAdminPinDialog = true
                    }
                )
            }
        }

        is KioskOperationalState.AuthExpired -> {
            Timber.d("🥝 [KIOSK-NAV] Showing unavailable screen - AuthExpired")
            KioskUnavailableScreen(
                reason = UnavailableReason.AUTH_EXPIRED,
                onAdminClick = {
                    showAdminPinDialog = true
                }
            )
        }

        is KioskOperationalState.NetworkError -> {
            Timber.d("🥝 [KIOSK-NAV] Showing unavailable screen - NetworkError")
            KioskUnavailableScreen(
                reason = UnavailableReason.NETWORK_ERROR,
                onAdminClick = {
                    showAdminPinDialog = true
                }
            )
        }

        is KioskOperationalState.Disabled -> {
            // This shouldn't happen in KioskNavigation since we exit kiosk mode when disabled
            Timber.w("🥝 [KIOSK-NAV] Disabled state in KioskNavigation - should have exited")
            onExitKiosk()
        }
    }

    // Admin PIN Dialog (for unavailable screen)
    if (showAdminPinDialog) {
        KioskAdminPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-NAV] Admin PIN dialog dismissed")
                showAdminPinDialog = false
            },
            onAuthSuccess = { auth ->
                Timber.i("🥝 [KIOSK-NAV] Admin authenticated: ${auth.staffName} (${auth.role})")
                showAdminPinDialog = false
                adminAuth = auth
                showAdminBottomSheet = true
            }
        )
    }

    // Admin Bottom Sheet
    if (showAdminBottomSheet && adminAuth != null) {
        KioskAdminBottomSheet(
            adminAuth = adminAuth!!,
            onDismiss = {
                Timber.d("🥝 [KIOSK-NAV] Admin bottom sheet dismissed")
                showAdminBottomSheet = false
                adminAuth = null
            },
            onExitKiosk = {
                Timber.i("🥝 [KIOSK-NAV] Exit kiosk from admin bottom sheet")
                showAdminBottomSheet = false
                adminAuth = null
                onExitKiosk()
            },
            onShiftStatusChanged = {
                // Re-check shift status immediately
                Timber.i("🥝 [KIOSK-NAV] Shift status changed - rechecking")
                val venueId = secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: ""
                // The shift check will happen on next coroutine cycle
            }
        )
    }
}

/**
 * Check shift status and update operational state
 */
private suspend fun checkShiftStatus(
    shiftRepository: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository,
    kioskModeManager: com.jaac.avoqado_tpv.core.data.manager.KioskModeManager,
    venueId: String
) {
    try {
        val result = shiftRepository.getCurrentShift(venueId)
        when (result) {
            is Result.Success -> {
                val shift = result.data
                val isOpen = shift?.status == ShiftStatus.OPEN
                kioskModeManager.updateShiftStatus(isOpen)
                kioskModeManager.resetNetworkFailures()
            }
            is Result.Error -> {
                Timber.e(result.exception, "🥝 [KIOSK-NAV] Error checking shift status")
                kioskModeManager.handleNetworkError()
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "🥝 [KIOSK-NAV] Exception checking shift status")
        kioskModeManager.handleNetworkError()
    }
}
