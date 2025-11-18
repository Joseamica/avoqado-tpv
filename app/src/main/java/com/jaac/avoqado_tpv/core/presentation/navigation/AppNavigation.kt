package com.jaac.avoqado_tpv.core.presentation.navigation

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.R
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jaac.avoqado_tpv.core.presentation.screens.FastPaymentEntryScreen
import com.jaac.avoqado_tpv.core.presentation.screens.WelcomeScreen
import com.jaac.avoqado_tpv.core.session.SessionEvent
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationScreen
import com.jaac.avoqado_tpv.features.authentication.presentation.LoginScreen
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationState
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationViewModel
import com.jaac.avoqado_tpv.features.payment.presentation.PaymentScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.FloorPlanCanvasScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderingWelcomeScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderListScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.TableServiceScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.MenuScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import timber.log.Timber

/**
 * AppNavigation
 *
 * Main navigation graph for Avoqado TPV.
 * Handles conditional routing based on:
 * 1. Terminal activation status
 * 2. User authentication status
 *
 * Flow:
 * Splash → (Not activated?) → Activation
 *       → (Activated + Not logged in?) → Login
 *       → (Logged in?) → Home
 *
 * Similar to Square POS navigation pattern.
 */
@Composable
fun AppNavigation(
    deviceInfoManager: DeviceInfoManager,
    secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
    sessionManager: SessionManager,
    navController: NavHostController = rememberNavController(),
    startDestination: String = NavRoute.Splash.route
) {
    // 🔐 GLOBAL SESSION EXPIRATION LISTENER
    // Observes session events from TokenAuthenticator and navigates accordingly
    LaunchedEffect(Unit) {
        sessionManager.sessionEvents.collect { event ->
            when (event) {
                is SessionEvent.Expired -> {
                    Timber.w("🚪 [AppNavigation] Session expired - navigating to Login")
                    navController.navigate(NavRoute.Login.route) {
                        popUpTo(0) { inclusive = true } // Clear entire back stack
                    }
                }
                is SessionEvent.TerminalDeactivated -> {
                    Timber.e("🔐 [AppNavigation] Terminal deactivated - navigating to Activation")
                    navController.navigate(NavRoute.Activation.route) {
                        popUpTo(0) { inclusive = true } // Clear entire back stack
                    }
                }
            }
        }
    }

    // 🌐 CONNECTION MONITORING (Square/Toast pattern)
    // Shows discrete banner when backend is unreachable, doesn't block operations
    val connectionViewModel: com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // Main navigation content
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
        // Splash Screen - Determines initial route based on activation status
        composable(NavRoute.Splash.route) {
            SplashScreen(
                deviceInfoManager = deviceInfoManager,
                secureStorage = secureStorage,
                onNavigateToActivation = {
                    navController.navigate(NavRoute.Activation.route) {
                        popUpTo(NavRoute.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(NavRoute.Login.route) {
                        popUpTo(NavRoute.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(NavRoute.Home.route) {
                        popUpTo(NavRoute.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // Activation Screen - Enter 6-char activation code
        composable(NavRoute.Activation.route) {
            val viewModel: ActivationViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            // Navigate to Login after successful activation
            LaunchedEffect(state) {
                if (state is ActivationState.Success) {
                    navController.navigate(NavRoute.Login.route) {
                        popUpTo(NavRoute.Activation.route) { inclusive = true }
                    }
                }
            }

            // Render UI
            ActivationScreen(
                serialNumber = viewModel.serialNumber,
                onActivate = viewModel::activate,
                isLoading = state is ActivationState.Loading,
                errorMessage = (state as? ActivationState.Error)?.message
            )
        }

        // Login Screen - PIN authentication
        composable(NavRoute.Login.route) {
            val context = LocalContext.current
            // Get venueId from DeviceInfoManager (saved during activation)
            val venueId = deviceInfoManager.getVenueId() ?: ""

            // 🚨 SECURITY: Monitor activation status (Square/Toast pattern)
            // If terminal gets RETIRED by admin, HeartbeatWorker clears venueId
            // This forces immediate navigation back to activation screen
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2000) // Check every 2 seconds
                    if (deviceInfoManager.getVenueId() == null) {
                        Timber.e("🚨 Terminal activation cleared - redirecting to activation")
                        navController.navigate(NavRoute.Activation.route) {
                            popUpTo(0) { inclusive = true } // Clear entire back stack
                        }
                        break
                    }
                }
            }

            LoginScreen(
                venueId = venueId,
                onLoginSuccess = {
                    // Start heartbeat after successful login
                    Timber.d("🔑 Login successful - Starting heartbeat")
                    HeartbeatScheduler.start(context)

                    // Start payment sync worker (offline payment queue)
                    Timber.d("💾 Login successful - Starting payment sync")
                    PaymentSyncScheduler.start(context)

                    // Navigate to home
                    navController.navigate(NavRoute.Home.route) {
                        popUpTo(NavRoute.Login.route) { inclusive = true }
                    }
                },
                onNavigateToActivation = {
                    // Navigate to activation when terminal is not activated
                    Timber.d("🔐 Terminal not activated - Navigating to activation screen")
                    navController.navigate(NavRoute.Activation.route) {
                        popUpTo(NavRoute.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Home Screen - Main dashboard
        composable(NavRoute.Home.route) {
            val context = LocalContext.current
            val homeViewModel: com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel = hiltViewModel()

            // 🚨 SECURITY: Monitor activation status (Square/Toast pattern)
            // If terminal gets RETIRED by admin, HeartbeatWorker clears venueId
            // This forces immediate logout and navigation back to activation screen
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2000) // Check every 2 seconds
                    if (deviceInfoManager.getVenueId() == null) {
                        Timber.e("🚨 Terminal activation cleared - forcing logout and redirecting to activation")
                        navController.navigate(NavRoute.Activation.route) {
                            popUpTo(0) { inclusive = true } // Clear entire back stack
                        }
                        break
                    }
                }
            }

            WelcomeScreen(
                onStartPaymentWithAmount = { amount ->
                    // ✅ Modal flow: Navigate to PaymentScreen with amount
                    navController.currentBackStackEntry?.savedStateHandle?.set("initialAmount", amount)
                    navController.navigate(NavRoute.Payment.route)
                },
                onNavigateToShifts = {
                    // Navigate to Shifts screen
                    navController.navigate(NavRoute.Shifts.route)
                },
                onNavigateToOrdering = {
                    // Navigate to Ordering Welcome screen
                    navController.navigate(NavRoute.OrderingWelcome.route)
                },
                onNavigateToSuperAdmin = {
                    // Navigate to SuperAdmin screen
                    navController.navigate(NavRoute.SuperAdmin.route)
                },
                onLogout = {
                    // ✅ Square/Toast Pattern: DO NOT stop heartbeat on logout
                    //
                    // Why? This prevents deadlock:
                    // 1. If we stop heartbeat here → No heartbeats sent after logout
                    // 2. Backend marks terminal INACTIVE after 2 min without heartbeats
                    // 3. User tries to login → Would fail if backend blocked INACTIVE
                    // 4. Terminal can't recover because heartbeat doesn't start until login
                    //
                    // Solution: Keep heartbeat running even after logout
                    // - Backend can still monitor terminal health (online/offline)
                    // - User can login anytime (terminal recovers from INACTIVE automatically)
                    // - Matches Square/Toast pattern: heartbeat independent of login state
                    //
                    // HeartbeatScheduler.stop(context) ← REMOVED (was causing deadlock)

                    // Clear session
                    homeViewModel.logout()

                    // Navigate back to login
                    navController.navigate(NavRoute.Login.route) {
                        popUpTo(NavRoute.Home.route) { inclusive = true }
                    }
                }
            )
        }

        // Fast Payment Entry Screen - Dedicated screen for amount input
        composable(NavRoute.FastPaymentEntry.route) {
            FastPaymentEntryScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onAmountSubmit = { amount ->
                    // Navigate to PaymentScreen with amount
                    navController.currentBackStackEntry?.savedStateHandle?.set("initialAmount", amount)
                    navController.navigate(NavRoute.Payment.route)
                }
            )
        }

        // Ordering Welcome Screen - Entry point for ordering system
        composable(NavRoute.OrderingWelcome.route) {
            OrderingWelcomeScreen(
                onQuickOrderClick = {
                    // Quick Order flow (retail/QSR) - No table assignment
                    // MenuViewModel will create order via backend and get CUID
                    Timber.d("🛒 Quick Order clicked - Creating new order via backend")
                    navController.navigate(NavRoute.Menu.createRoute("CREATE_QUICK_ORDER"))
                },
                onTableServiceClick = {
                    // Navigate to Floor Plan screen (visual canvas)
                    navController.navigate(NavRoute.FloorPlan.route)
                },
                onViewOrdersClick = {
                    // Navigate to Order List screen
                    navController.navigate(NavRoute.OrderList.route)
                    Timber.d("📋 Navigating to Order List")
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Table Service Screen - Floor plan with table status
        composable(NavRoute.TableService.route) {
            TableServiceScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onTableAssigned = { orderId ->
                    // Navigate to Menu screen with orderId
                    Timber.d("🍽️ Table assigned - Navigating to Menu with Order ID: $orderId")
                    navController.navigate(NavRoute.Menu.createRoute(orderId))
                }
            )
        }

        // Floor Plan Canvas Screen - Visual floor plan editor with zoom/pan
        composable(NavRoute.FloorPlan.route) {
            FloorPlanCanvasScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onTableAssigned = { orderId ->
                    // Navigate to Menu screen with orderId
                    Timber.d("🪑 Table assigned - Navigating to Menu with Order ID: $orderId")
                    navController.navigate(NavRoute.Menu.createRoute(orderId))
                }
            )
        }

        // Menu Screen - Product selection + Order check (Hybrid Toast + Square pattern)
        composable(
            route = NavRoute.Menu.route,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId") ?: ""

            MenuScreen(
                orderId = orderId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onProcessPayment = { order ->
                    // Pass order total and orderId to PaymentScreen via savedStateHandle
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("initialAmount", order.total.toString())
                        set("orderId", order.id)
                        set("orderNumber", order.orderNumber)
                    }
                    navController.navigate(NavRoute.Payment.route)
                    Timber.d("💳 Navigating to payment: ${order.orderNumber} - Total: $${order.total}")
                }
            )
        }

        // Order List Screen - List of all orders with filters
        composable(NavRoute.OrderList.route) {
            OrderListScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onOrderClick = { order ->
                    // Navigate to MenuScreen to view/edit order
                    navController.navigate(NavRoute.Menu.createRoute(order.id))
                    Timber.d("📋 Viewing order: ${order.orderNumber}")
                }
            )
        }

        // Shifts Screen - Shift management (open/close shifts)
        composable(NavRoute.Shifts.route) {
            com.jaac.avoqado_tpv.features.shift.presentation.ShiftScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Payment Screen - EMV chip card payment with online authorization
        composable(NavRoute.Payment.route) {
            // 🔐 SECURITY: Require authentication before processing payments
            // This prevents payments without backend recording (Blumon succeeds, but no receipt)
            LaunchedEffect(Unit) {
                if (!secureStorage.isAuthenticated()) {
                    Timber.w("⚠️ [Payment] User not authenticated - redirecting to login")
                    navController.navigate(NavRoute.Login.route) {
                        popUpTo(NavRoute.Home.route) { inclusive = false }
                    }
                }
            }

            // Get initial amount from previous screen (if coming from Home with amount)
            val initialAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("initialAmount")

            // 🧪 Get skipReview flag (test payment from SuperAdmin)
            val skipReview = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("skipReview") ?: false

            // 🆕 Get order details (if coming from MenuScreen with order)
            val orderId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("orderId")
            val orderNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("orderNumber")

            PaymentScreen(
                initialAmount = initialAmount,
                orderId = orderId,
                orderNumber = orderNumber,
                skipReview = skipReview,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToShifts = {
                    // 🆕 Navigate to Shifts screen (for "No shift open" errors)
                    navController.navigate(NavRoute.Shifts.route)
                },
                onNavigateToNewOrder = {
                    // 🔄 Toast/Square Pattern: Navigate FORWARD to fresh MenuScreen instance
                    // This creates a new order with fresh ViewModel (no state reuse/patching)
                    navController.navigate(NavRoute.Menu.createRoute("CREATE_QUICK_ORDER")) {
                        // Pop back to OrderingWelcome but don't include it
                        // Stack: OrderingWelcome → MenuScreen (NEW)
                        popUpTo(NavRoute.OrderingWelcome.route) { inclusive = false }
                    }
                    Timber.d("🔄 [Navigation] Toast/Square pattern: Navigated to NEW quick order")
                },
                onNavigateToNewFastPayment = {
                    // 🔄 Navigate to FastPaymentEntryScreen for new fast payment
                    navController.navigate(NavRoute.FastPaymentEntry.route) {
                        // Pop back to Home but don't include it (keep Home in backstack)
                        popUpTo(NavRoute.Home.route) { inclusive = false }
                    }
                    Timber.d("🔄 [Navigation] Fast payment: Navigated to FastPaymentEntryScreen")
                }
            )
        }

        // Settings Screen
        composable(NavRoute.Settings.route) {
            // Placeholder for settings
            WelcomeScreen()
        }

        // SuperAdmin Screen - Testing and debugging tools
        composable(NavRoute.SuperAdmin.route) {
            // State to track pending test payment
            var pendingTestPayment by remember { mutableStateOf(false) }

            // Navigate to payment when test payment is triggered
            LaunchedEffect(pendingTestPayment) {
                if (pendingTestPayment) {
                    // 🧪 Navigate with test amount ($10.00) and skipReview flag
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("initialAmount", "10.00")
                        set("skipReview", true)  // Skip rating/tip for test payments
                    }
                    navController.navigate(NavRoute.Payment.route)
                    pendingTestPayment = false // Reset after navigation
                }
            }

            com.jaac.avoqado_tpv.core.presentation.screens.SuperAdminScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onTestPayment = {
                    // Trigger test payment of $10.00
                    pendingTestPayment = true
                }
            )
        }
    }

        // 🌐 CONNECTION BANNER (overlay on top of all screens)
        // Square/Toast pattern: Discrete warning banner that doesn't block operations
        com.jaac.avoqado_tpv.core.presentation.components.ConnectionBanner(
            state = connectionState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Splash Screen
 *
 * Initial screen that determines navigation flow based on:
 * 1. Activation status (via SecureStorage)
 * 2. Authentication status (via session token)
 *
 * Displays Avoqado logo with loading indicator while checking.
 *
 * Pattern: Square POS / Toast POS - Simple logo + loading, no buttons or complex UI
 */
@Composable
private fun SplashScreen(
    deviceInfoManager: DeviceInfoManager,
    secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
    onNavigateToActivation: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    LaunchedEffect(Unit) {
        // ✅ Square/Toast Pattern: Check activation status with BACKEND first
        // This prevents routing to LoginScreen when terminal has venueId locally
        // but activatedAt = NULL on backend (happens after DB reset)
        val backendStatusResult = deviceInfoManager.checkActivationStatusWithBackend()

        when (backendStatusResult) {
            is com.jaac.avoqado_tpv.core.domain.models.Result.Success -> {
                val status = backendStatusResult.data

                if (!status.isActivated) {
                    // Backend says not activated → force re-activation
                    Timber.w("🔐 Backend reports not activated - navigating to activation")
                    onNavigateToActivation()
                } else {
                    // ✅ Backend confirms activation → check session
                    Timber.d("✅ Backend confirms activation - checking session")
                    val hasValidSession = secureStorage.isAuthenticated()
                    if (hasValidSession) {
                        Timber.d("🔑 Valid session found - navigating to Home")
                        onNavigateToHome()
                    } else {
                        Timber.d("🔐 No valid session - navigating to Login")
                        onNavigateToLogin()
                    }
                }
            }
            is com.jaac.avoqado_tpv.core.domain.models.Result.Error -> {
                // Network error → trust local venueId (offline support)
                Timber.w(backendStatusResult.exception, "⚠️ Network error checking backend - using local venueId")

                val localActivated = deviceInfoManager.isDeviceActivated()
                if (!localActivated) {
                    Timber.d("🔐 No local venueId - navigating to activation")
                    onNavigateToActivation()
                } else {
                    // Trust local venueId when offline
                    Timber.d("📱 Offline mode - trusting local venueId")
                    val hasValidSession = secureStorage.isAuthenticated()
                    if (hasValidSession) {
                        Timber.d("🔑 Valid session found - navigating to Home")
                        onNavigateToHome()
                    } else {
                        Timber.d("🔐 No valid session - navigating to Login")
                        onNavigateToLogin()
                    }
                }
            }
        }
    }

    // ✅ TRUE Splash Screen - Only logo + loading (Square/Toast pattern)
    SplashScreenContent()
}

/**
 * Splash Screen Content
 *
 * Professional animated splash screen with Avoqado branding.
 * Displayed while checking activation and authentication status.
 *
 * Design Features:
 * - Logo appears with smooth scale animation (0.5 → 1.0, 800ms)
 * - Text fades in sequentially after logo (400ms delay)
 * - Clean, centered layout with professional spacing
 * - Light gray background (not pure white/black)
 *
 * Timing:
 * - 0ms: Logo starts scaling up
 * - 400ms: Text fades in
 * - Total animation: ~1200ms
 *
 * Pattern: Square POS, Toast POS - Polished, branded splash experience
 */
@Composable
private fun SplashScreenContent() {
    // Animation states
    var showLogo by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }

    // Logo scale animation (smooth ease-in-out)
    val logoScale by animateFloatAsState(
        targetValue = if (showLogo) 1f else 0.5f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "logoScale"
    )

    // Launch animation sequence
    LaunchedEffect(Unit) {
        showLogo = true
        delay(400)  // Wait 400ms before showing text
        showText = true
    }

    // Splash UI with animations
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5)),  // Light gray background (professional)
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo with scale animation - Real Avoqado avocado logo
            Image(
                painter = painterResource(R.drawable.isotipo),
                contentDescription = "Avoqado Logo",
                modifier = Modifier
                    .size(200.dp)  // Professional size for splash
                    .scale(logoScale)  // Smooth scale animation (0.5 → 1.0)
            )

            Spacer(modifier = Modifier.height(32.dp))  // More spacing (was 24.dp)

            // Text with fade-in animation
            AnimatedVisibility(
                visible = showText,
                enter = fadeIn(animationSpec = tween(800))
            ) {
                Text(
                    text = "Avoqado TPV",
                    fontSize = 35.sp,  // Larger text (was headlineLarge)
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(48.dp))  // More spacing before loading

            // Loading indicator (visible immediately)
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

