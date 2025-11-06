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
import com.jaac.avoqado_tpv.core.presentation.screens.WelcomeScreen
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationScreen
import com.jaac.avoqado_tpv.features.authentication.presentation.LoginScreen
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationState
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationViewModel
import com.jaac.avoqado_tpv.features.payment.presentation.PaymentScreen
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
    navController: NavHostController = rememberNavController(),
    startDestination: String = NavRoute.Splash.route
) {
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

            LoginScreen(
                venueId = venueId,
                onLoginSuccess = {
                    // Start heartbeat after successful login
                    Timber.d("🔑 Login successful - Starting heartbeat")
                    HeartbeatScheduler.start(context)

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

            WelcomeScreen(
                onNavigateToPayment = {
                    navController.navigate(NavRoute.Payment.route)
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

        // Payment Screen - EMV chip card payment with online authorization
        composable(NavRoute.Payment.route) {
            PaymentScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Settings Screen
        composable(NavRoute.Settings.route) {
            // Placeholder for settings
            WelcomeScreen()
        }
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
        // Check activation status from SecureStorage
        val isActivated = deviceInfoManager.isDeviceActivated()

        if (!isActivated) {
            // Device not activated → go to activation screen
            Timber.d("🔐 Device not activated - navigating to activation")
            onNavigateToActivation()
        } else {
            // ✅ Check if user is logged in (check session token)
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

