package com.jaac.avoqado_tpv.core.presentation.navigation

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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.rememberCoroutineScope
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
import android.content.Context
import android.widget.Toast
import com.blumonpay.pax.utils.AppManager
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.screens.FastPaymentEntryScreen
import com.jaac.avoqado_tpv.core.presentation.screens.WelcomeScreen
import com.jaac.avoqado_tpv.features.checkout.presentation.CheckoutScreen
import com.jaac.avoqado_tpv.core.session.SessionEvent
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.core.util.PromoterLocationScheduler
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationScreen
import com.jaac.avoqado_tpv.features.authentication.presentation.LoginScreen
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationState
import com.jaac.avoqado_tpv.features.activation.presentation.ActivationViewModel
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.presentation.PaymentScreen
import com.jaac.avoqado_tpv.features.ordering.domain.TableRepository
import com.jaac.avoqado_tpv.features.ordering.presentation.FloorPlanCanvasScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderingWelcomeScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderListScreen
import com.jaac.avoqado_tpv.features.ordering.presentation.OrderStatusFilter
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.MenuScreen
import com.jaac.avoqado_tpv.features.timeclock.presentation.TimeclockScreen
import com.jaac.avoqado_tpv.features.serialized_sale.presentation.SerializedSaleScreen
import com.jaac.avoqado_tpv.features.serialized_sale.presentation.MySalesScreen
import com.jaac.avoqado_tpv.features.serialized_inventory.presentation.SerializedInventoryScreen
import com.jaac.avoqado_tpv.features.self_update.domain.UpdateRequestManager
import com.jaac.avoqado_tpv.features.self_update.domain.UpdateRequestState
import com.jaac.avoqado_tpv.features.self_update.presentation.UpdateRequestDialog
import com.jaac.avoqado_tpv.features.messaging.presentation.TpvMessageViewModel
import com.jaac.avoqado_tpv.features.messaging.presentation.components.TpvMessageDialog
import com.jaac.avoqado_tpv.features.payment.presentation.split.SplitByProductScreen
import com.jaac.avoqado_tpv.features.payment.presentation.split.SplitByPersonScreen
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.refund.presentation.RefundConfirmationScreen
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import androidx.navigation.NavType
import androidx.navigation.navArgument
import timber.log.Timber
import java.time.Instant
import java.math.BigDecimal
import java.math.RoundingMode
// SafeNavigationHelper for preventing black screen from rapid back clicks
import com.jaac.avoqado_tpv.core.presentation.navigation.safePopBackStack

/**
 * EntryPoint for accessing dependencies in AppNavigation
 *
 * Required because AppNavigation is not a ViewModel and cannot use @Inject directly.
 * EntryPoints allow access to Hilt-provided dependencies from non-injected classes.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppNavigationEntryPoint {
    fun tableRepository(): TableRepository
    fun kioskModeManager(): com.jaac.avoqado_tpv.core.data.manager.KioskModeManager
    fun printerManager(): com.jaac.avoqado_tpv.core.printer.PrinterManager
    fun paymentApiService(): com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
    fun modulesRepository(): com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
    fun terminalConfigRepository(): com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
    fun initializationManager(): com.jaac.avoqado_tpv.features.payment.data.InitializationManager
    fun updateRequestManager(): UpdateRequestManager
    fun bluetoothPaymentService(): com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentService
    fun socketManager(): com.jaac.avoqado_tpv.core.data.realtime.SocketManager
    fun recordAngelPayRefundUseCase(): com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordAngelPayRefundUseCase
}

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
    isDarkMode: Boolean = false,
    onThemeToggle: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
    startDestination: String = NavRoute.Splash.route
) {
    // 📺 Observe session expiring state for loading overlay
    val isSessionExpiring by sessionManager.isSessionExpiring.collectAsStateWithLifecycle()

    // 🌐 CONNECTION MONITORING (Square/Toast pattern)
    // Anchored to Activity lifecycle — survives navigation without recreation
    val activityOwner = LocalContext.current as ComponentActivity
    val connectionViewModel: com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionViewModel = hiltViewModel(
        viewModelStoreOwner = activityOwner
    )
    val connectionState by connectionViewModel.state.collectAsStateWithLifecycle()

    // 🏥 DEVICE HEALTH MONITORING (Square/Toast/Shopify pattern)
    // Anchored to Activity lifecycle — survives navigation without recreation
    val deviceHealthViewModel: com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceHealthViewModel = hiltViewModel(
        viewModelStoreOwner = activityOwner
    )
    val deviceAlerts by deviceHealthViewModel.activeAlerts.collectAsStateWithLifecycle()
    val deviceAlertsExpanded by deviceHealthViewModel.isExpanded.collectAsStateWithLifecycle()

    // 📨 TPV MESSAGES (Dashboard → Terminal messaging)
    // Anchored to Activity lifecycle — survives navigation without recreation
    val tpvMessageViewModel: TpvMessageViewModel = hiltViewModel(
        viewModelStoreOwner = activityOwner
    )
    val tpvMessageState by tpvMessageViewModel.uiState.collectAsStateWithLifecycle()

    // 📚 TRAINING / LMS
    // Anchored to Activity lifecycle — survives navigation without recreation
    val trainingViewModel: com.jaac.avoqado_tpv.features.training.presentation.TrainingViewModel = hiltViewModel(
        viewModelStoreOwner = activityOwner
    )

    // 🔒 FORCE UPDATE CHECK
    // If there's a forced update, show blocking dialog
    val forceUpdateAlert = deviceAlerts.filterIsInstance<com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert.UpdateAvailable>()
        .firstOrNull { it.isForced }

    // 🥝 KIOSK MODE OBSERVATION
    // When in kiosk mode, show simplified customer-facing navigation instead of staff UI
    val context = LocalContext.current
    val kioskEntryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppNavigationEntryPoint::class.java
        )
    }
    val kioskModeManager = remember { kioskEntryPoint.kioskModeManager() }
    val isKioskMode by kioskModeManager.isKioskMode.collectAsStateWithLifecycle()
    val modulesRepository = remember { kioskEntryPoint.modulesRepository() }
    val terminalConfigRepository = remember { kioskEntryPoint.terminalConfigRepository() }
    val initializationManager = remember { kioskEntryPoint.initializationManager() }
    val isBlumonSdkInitialized by initializationManager.isInitialized.collectAsStateWithLifecycle()
    val bluetoothPaymentService = remember { kioskEntryPoint.bluetoothPaymentService() }
    val socketManager = remember { kioskEntryPoint.socketManager() }
    val recordAngelPayRefundUseCase = remember { kioskEntryPoint.recordAngelPayRefundUseCase() }

    // 📥 UPDATE REQUEST OBSERVATION (Remote update commands from dashboard)
    // When dashboard sends REQUEST_UPDATE command, show dialog to user
    val updateRequestManager = remember {
        if (!BuildConfig.ENABLE_PAX_SDK) {
            null
        } else {
            try {
                // Defensive init: prevents SDK DAL race on cold start.
                AppManager.init(context.applicationContext)
                kioskEntryPoint.updateRequestManager()
            } catch (e: Throwable) {
                Timber.w(e, "⚠️ [AppNavigation] UpdateRequestManager disabled: AppManager init failed")
                null
            }
        }
    }
    val updateRequestState = updateRequestManager
        ?.updateRequestState
        ?.collectAsStateWithLifecycle()
        ?.value
        ?: UpdateRequestState.Idle
    val updateCoroutineScope = rememberCoroutineScope()

    // 🔵 EXTERNAL DEVICES (BLE) - Start payment flow from any screen
    LaunchedEffect(bluetoothPaymentService) {
        bluetoothPaymentService.paymentRequests.collect { request ->
            if (request.amountCents <= 0) {
                Timber.w("⚠️ [BLE] Ignoring non-positive amount: ${request.amountCents}")
                return@collect
            }

            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute == NavRoute.Payment.route || currentRoute == NavRoute.AngelPayPayment.route) {
                Timber.w("⚠️ [BLE] Payment already in progress - ignoring amount: ${request.amountCents}")
                // If this came via socket, send rejection back so iOS doesn't hang
                if (request.source == com.jaac.avoqado_tpv.core.bluetooth.PaymentSource.SOCKET && request.socketRequestId != null) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = request.socketRequestId!!,
                        status = "failed",
                        errorMessage = "Ya hay un pago en proceso en el terminal"
                    )
                    Timber.i("📡 [Socket] Sent rejection for requestId=${request.socketRequestId}")
                }
                return@collect
            }

            val handle = navController.currentBackStackEntry?.savedStateHandle
            if (handle == null) {
                Timber.e("❌ [BLE] No backstack entry available - cannot start payment")
                return@collect
            }

            if (!awaitPaxPaymentReady(context, initializationManager, "BLE/SOCKET payment")) {
                if (request.source == com.jaac.avoqado_tpv.core.bluetooth.PaymentSource.SOCKET && request.socketRequestId != null) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = request.socketRequestId!!,
                        status = "failed",
                        errorMessage = "Sistema de pagos no inicializado en el terminal"
                    )
                }
                return@collect
            }

            // Clear stale payment args to avoid contamination (order/refund/split)
            clearPaymentArgs(handle)

            val formattedAmount = formatAmountFromCents(request.amountCents)
            handle.set("initialAmount", formattedAmount)
            handle.set("skipReview", request.skipReview)
            handle.set("externalTipCents", request.tipCents)
            handle.set("externalRating", request.rating)
            handle.set("externalSkipReview", request.skipReview)

            // Dual-mode payment: Set orderId if present (triggers OrderPayment vs FastPayment)
            if (request.orderId != null) {
                handle.set("orderId", request.orderId)
                handle.set("skipLocalOrderValidation", true) // Order already validated by iOS
                Timber.i("📦 [BLE] ORDER PAYMENT mode | orderId=${request.orderId}")
            } else {
                Timber.i("💨 [BLE] QUICK PAYMENT mode (no orderId)")
            }

            // Pass payment source and socket request ID for result callback
            handle.set("paymentSource", request.source.name)
            handle.set("socketRequestId", request.socketRequestId)
            handle.set("socketProcessedByStaffId", request.processedByStaffId)

            val sourceLabel = if (request.source == com.jaac.avoqado_tpv.core.bluetooth.PaymentSource.SOCKET) "SOCKET" else "BLE"
            Timber.i("🔵 [$sourceLabel] Navigating to payment | amount=$formattedAmount | cents=${request.amountCents} | orderId=${request.orderId ?: "null"} | nexgo=${isAppToAppPayment()}")
            navController.navigate(getPaymentRoute()) {
                launchSingleTop = true
            }
        }
    }

    // 🚫 PAYMENT CANCEL from iOS - Navigate back to Welcome
    LaunchedEffect(bluetoothPaymentService) {
        bluetoothPaymentService.paymentCancelRequests.collect { requestId ->
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute == NavRoute.Payment.route || currentRoute == NavRoute.AngelPayPayment.route) {
                Timber.i("🚫 [Cancel] Cancelling payment (requestId=$requestId) - navigating to Home")
                // 🧹 CRITICAL: Clear stale payment args from Home's handle BEFORE navigating.
                // Without this, the next manual Fast Payment inherits paymentSource=SOCKET,
                // skipReview=true, externalTipCents=N, etc. → the TPV silently uses socket
                // values for a manual cobro (skips tip selection, applies stale amount/tip).
                // Repro: socket request → cancel from iOS → cashier taps "Cobro Rápido" →
                // tip screen is skipped using the cancelled request's values.
                navController.previousBackStackEntry?.savedStateHandle?.let { homeHandle ->
                    clearPaymentArgs(homeHandle)
                    Timber.d("🧹 [Cancel] Cleared payment args from Home savedStateHandle")
                }
                navController.navigate(NavRoute.Home.route) {
                    popUpTo(NavRoute.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            } else {
                Timber.w("⚠️ [Cancel] Not on Payment screen (current=$currentRoute) - ignoring cancel")
            }
        }
    }

    // 🔐 GLOBAL SESSION EXPIRATION LISTENER
    // Observes session events from TokenAuthenticator and navigates accordingly
    // NOTE: Placed AFTER kiosk mode observation so we can exit kiosk mode if needed
    LaunchedEffect(Unit) {
        sessionManager.sessionEvents.collect { event ->
            when (event) {
                is SessionEvent.Expired -> {
                    // 🥝 If in kiosk mode, exit it first before navigating
                    // The navController in kiosk mode uses KioskNavigation's NavHost
                    // which doesn't have Login route, causing crash
                    if (kioskModeManager.isKioskMode.value) {
                        Timber.w("🚪 [AppNavigation] Session expired in kiosk mode - exiting kiosk first")
                        kioskModeManager.exitKioskMode()
                        // After exiting kiosk mode, the staff NavHost will render
                        // and SplashScreen will navigate to Login (user not authenticated)
                    } else {
                        // ✅ Guard: Don't navigate if already on Login screen
                        // This prevents race condition where LoginViewModel is recreated
                        // and loses VenueNotOperational state (e.g., venue suspended error)
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        if (currentRoute != NavRoute.Login.route) {
                            Timber.w("🚪 [AppNavigation] Session expired - navigating to Login")
                            navController.navigate(NavRoute.Login.route) {
                                popUpTo(0) { inclusive = true } // Clear entire back stack
                            }
                        } else {
                            Timber.d("🔐 [AppNavigation] Already on Login screen - ignoring SessionEvent.Expired")
                        }
                    }
                    // Reset the expiring state after navigation completes
                    sessionManager.resetSessionExpiringState()
                }
                is SessionEvent.TerminalDeactivated -> {
                    // 🥝 If in kiosk mode, exit it first before navigating
                    // The navController in kiosk mode uses KioskNavigation's NavHost
                    // which doesn't have Activation route, causing crash
                    if (kioskModeManager.isKioskMode.value) {
                        Timber.e("🔐 [AppNavigation] Terminal deactivated in kiosk mode - exiting kiosk first")
                        kioskModeManager.exitKioskMode()
                        // After exiting kiosk mode, the staff NavHost will render
                        // and SplashScreen will navigate to Activation (terminal not activated)
                    } else {
                        Timber.e("🔐 [AppNavigation] Terminal deactivated - navigating to Activation")
                        navController.navigate(NavRoute.Activation.route) {
                            popUpTo(0) { inclusive = true } // Clear entire back stack
                        }
                    }
                    // Reset the expiring state after navigation completes
                    sessionManager.resetSessionExpiringState()
                }

                is SessionEvent.TokenRefreshed -> {
                    // Handled by HomeViewModel - socket reconnection with fresh token
                    Timber.d("🔄 [AppNavigation] Token refreshed (socket reconnect handled by HomeViewModel)")
                }
            }
        }
    }

    // 🥝 KIOSK PAYMENT STATE
    // Track when we're in the middle of a kiosk payment to show staff PaymentScreen
    var kioskPaymentOrderId by remember { mutableStateOf<String?>(null) }
    var kioskPaymentAmount by remember { mutableStateOf<Long?>(null) }
    var kioskPaymentOrderNumber by remember { mutableStateOf<String?>(null) }
    var kioskPaymentStaffId by remember { mutableStateOf<String?>(null) }  // 🥝 Staff ID for sales attribution
    val isKioskPaymentInProgress = kioskPaymentOrderId != null

    // 🥝 KIOSK SUCCESS STATE
    // Track when to show success screen after kiosk payment
    var kioskSuccessOrderNumber by remember { mutableStateOf<String?>(null) }
    var kioskSuccessReceipt by remember { mutableStateOf<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt?>(null) }
    var kioskSuccessOrderItems by remember { mutableStateOf<List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>?>(null) }
    val isKioskSuccessInProgress = kioskSuccessOrderNumber != null

    // 🥝 KIOSK CART CLEARING
    // Store clearCart function from KioskNavigation to clear cart after payment success
    var kioskClearCart by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 🥝 KIOSK SUCCESS SCREEN - Render directly when payment completed
    // This bypasses the staff NavHost to avoid navigation race conditions
    if (isKioskMode && isKioskSuccessInProgress) {
        // 🖨️ Get printer and API service from EntryPoint for receipt printing/email
        val printerManager = remember { kioskEntryPoint.printerManager() }
        val paymentApiService = remember { kioskEntryPoint.paymentApiService() }
        val coroutineScopeForKiosk = rememberCoroutineScope()

        com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskSuccessScreen(
            orderNumber = kioskSuccessOrderNumber!!,
            receipt = kioskSuccessReceipt,
            orderItems = kioskSuccessOrderItems,
            onTimeout = {
                Timber.i("🥝 [KIOSK] Success timeout - clearing cart and navigating to menu for new order")
                // Clear cart after successful payment
                kioskClearCart?.invoke()
                kioskSuccessOrderNumber = null
                kioskSuccessReceipt = null
                kioskSuccessOrderItems = null
                // 🥝 Navigate to Menu for new order (not Cart which was the previous destination)
                // Using kioskModeManager to set order in progress, then navigate
                kioskModeManager.setOrderInProgress(true)
                navController.navigate(NavRoute.KioskMenu.route) {
                    // Clear entire backstack and start fresh with Menu
                    popUpTo(0) { inclusive = true }
                }
            },
            onPrintReceipt = {
                // 🖨️ Print receipt using simplified kiosk receipt format
                kioskSuccessReceipt?.let { receipt ->
                    coroutineScopeForKiosk.launch {
                        Timber.i("🥝 [KIOSK] Printing receipt for order: $kioskSuccessOrderNumber")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            printerManager.printKioskReceipt(
                                orderNumber = kioskSuccessOrderNumber ?: "",
                                receiptUrl = receipt.receiptUrl,
                                amount = receipt.amount.toString(),
                                tipAmount = receipt.tipAmount.toString(),
                                venueName = secureStorage.getVenueName(),
                                venueLogoUrl = secureStorage.getVenueLogo(),
                                venueLegalName = secureStorage.getVenueLegalName(),
                                venueRfc = secureStorage.getVenueRfc(),
                                venueAddress = secureStorage.getVenueAddress(),
                                venueCity = secureStorage.getVenueCity(),
                                venueState = secureStorage.getVenueState(),
                                venueZipCode = secureStorage.getVenueZipCode(),
                                autofacturaAvailable = receipt.autofacturaAvailable
                            )
                        }
                    }
                } ?: Timber.w("🥝 [KIOSK] Cannot print - no receipt available")
            },
            onSendReceipt = { email ->
                // 📧 Send receipt by email
                kioskSuccessReceipt?.let { receipt ->
                    coroutineScopeForKiosk.launch {
                        Timber.i("🥝 [KIOSK] Sending receipt to: $email")
                        try {
                            val venueId = secureStorage.getVenueId()
                            if (venueId != null) {
                                val request = com.jaac.avoqado_tpv.features.payment.data.dto.SendReceiptRequest(
                                    recipientEmail = email
                                )
                                val response = paymentApiService.sendReceipt(
                                    venueId = venueId,
                                    paymentId = receipt.paymentId,
                                    request = request
                                )
                                if (response.isSuccessful && response.body()?.success == true) {
                                    Timber.i("✅ [KIOSK] Receipt sent successfully to: $email")
                                } else {
                                    Timber.e("❌ [KIOSK] Failed to send receipt: ${response.errorBody()?.string()}")
                                }
                            } else {
                                Timber.e("❌ [KIOSK] Cannot send receipt - no venueId")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "❌ [KIOSK] Error sending receipt")
                        }
                    }
                } ?: Timber.w("🥝 [KIOSK] Cannot send receipt - no receipt available")
            }
        )
        return // Don't show anything else during kiosk success
    }

    // 🥝 KIOSK PAYMENT SCREEN - Render PaymentScreen directly
    // This bypasses the staff NavHost to avoid race with SplashScreen
    if (isKioskMode && isKioskPaymentInProgress) {
        if (BuildConfig.ENABLE_PAX_SDK && !isBlumonSdkInitialized) {
            LaunchedEffect(kioskPaymentOrderId) {
                val ready = awaitPaxPaymentReady(context, initializationManager, "Kiosk payment")
                if (!ready) {
                    kioskPaymentOrderId = null
                    kioskPaymentAmount = null
                    kioskPaymentOrderNumber = null
                    kioskPaymentStaffId = null
                }
            }
            AvoqadoLoadingOverlay(message = "Preparando sistema de pagos...")
            return
        }

        // Amount is already in pesos (DB stores pesos)
        val amountPesos = remember(kioskPaymentAmount) {
            java.math.BigDecimal(kioskPaymentAmount!!)
        }

        PaymentScreen(
            initialAmount = amountPesos.toString(),
            orderId = kioskPaymentOrderId,
            orderNumber = kioskPaymentOrderNumber,
            tableId = null,  // Kiosk orders don't have tables
            skipReview = false,  // Show rating/tip if enabled
            // Split payment params (not applicable for kiosk)
            splitType = null,
            equalPartsPartySize = null,
            equalPartsPayedFor = null,
            paidProductIds = emptyList(),
            // Refund params (not applicable for kiosk)
            isRefundMode = false,
            refundAmount = null,
            refundReason = null,
            originalPaymentId = null,
            originalOrderId = null,
            originalTotalAmount = null,
            originalTipAmount = null,
            refundMerchantAccountId = null,
            refundBlumonSerialNumber = null,
            originalOperationNumber = null,
            refundVenueId = null,
            // Pay-later context (not applicable for kiosk)
            wasPayLaterOrder = false,
            payLaterOrdersCount = 0,
            // 🥝 KIOSK MODE PARAMS
            isKioskPayment = true,
            kioskStaffId = kioskPaymentStaffId,  // 🥝 Staff ID for sales attribution (commissions/tips)
            onKioskPaymentSuccess = { displayOrderNumber, receipt, orderItems ->
                // Clear kiosk payment state and show success screen with receipt
                Timber.i("🥝 [KIOSK] Payment success - showing success screen with order: $displayOrderNumber, receipt: ${receipt != null}, items: ${orderItems?.size}")
                kioskPaymentOrderId = null
                kioskPaymentAmount = null
                kioskPaymentOrderNumber = null
                kioskPaymentStaffId = null
                kioskSuccessOrderNumber = displayOrderNumber
                kioskSuccessReceipt = receipt
                kioskSuccessOrderItems = orderItems
            },
            onNavigateBack = {
                // Cancel payment - return to kiosk navigation (cart)
                Timber.i("🥝 [KIOSK] Payment cancelled - returning to kiosk")
                kioskPaymentOrderId = null
                kioskPaymentAmount = null
                kioskPaymentOrderNumber = null
                kioskPaymentStaffId = null
                // Will automatically show KioskNavigation (kiosk mode still active)
            },
            onNavigateHome = {
                // Not used in kiosk success (kiosk has its own success screen)
            },
            onNavigateToShifts = {
                // 🥝 In kiosk mode, we can't navigate to shifts (route doesn't exist in kiosk NavHost)
                // Instead, exit kiosk mode - staff will need to open shift from normal UI
                Timber.w("🥝 [KIOSK] Shift required but not available in kiosk mode - exiting kiosk")
                Timber.w("🥝 [KIOSK] → Staff should open shift from normal UI, then re-enable kiosk mode")
                kioskModeManager.exitKioskMode()
                kioskPaymentOrderId = null
                kioskPaymentAmount = null
                kioskPaymentOrderNumber = null
                kioskPaymentStaffId = null
                // After exiting kiosk mode, staff NavHost will render at Splash
                // which will navigate to Home (if authenticated) where they can open shift
            },
            // These callbacks are not applicable for kiosk mode
            onNavigateToNewOrder = { /* Not used in kiosk */ },
            onNavigateToSerializedSale = { /* Not used in kiosk */ },
            onNavigateToNewFastPayment = { /* Not used in kiosk */ },
            onClearTableAndReturnToFloorPlan = { /* Not used in kiosk */ },
            onNavigateToOrder = { _, _ -> /* Not used in kiosk */ },
            onNavigateToPayLaterOrders = { /* Not used in kiosk */ }
        )
        return // Don't show anything else during kiosk payment
    }

    // 🔒 FORCE UPDATE DIALOG (Global - Shows in ALL modes including kiosk)
    // When backend flags update as FORCE, block app usage until updated
    // Rendered AFTER navigation content so navController has routes available
    // But Dialog overlays on top, so it still blocks interaction

    // 🥝 KIOSK MODE - Separate navigation graph for self-service
    if (isKioskMode) {
        com.jaac.avoqado_tpv.features.kiosk.presentation.KioskNavigation(
            navController = navController,
            onExitKiosk = {
                // Just exit kiosk mode - the recomposition will render the main NavHost
                // No need to navigate since the kiosk graph doesn't have "home" route
                kioskModeManager.exitKioskMode()
            },
            onNavigateToPayment = { orderId, amount, orderNumber, staffId ->
                // Set kiosk payment state to trigger PaymentScreen render
                kioskPaymentOrderId = orderId
                kioskPaymentAmount = amount
                kioskPaymentOrderNumber = orderNumber
                kioskPaymentStaffId = staffId  // 🥝 Staff ID for sales attribution
                Timber.i("🥝 [KIOSK] Starting payment: orderId=$orderId, amount=$amount, orderNumber=$orderNumber, staffId=$staffId")
            },
            onClearCartRequest = { clearCartFn ->
                // Store the clearCart function from KioskNavigation's ViewModel
                // Called after payment success to clear cart items
                kioskClearCart = clearCartFn
                Timber.d("🥝 [KIOSK] Cart clear function registered from KioskNavigation")
            }
        )
        return // Don't show staff navigation when in kiosk mode
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface) // Fill status bar area with surface color
        .statusBarsPadding() // Consume status bar inset ONCE here (above banner + screens)
    ) {
        // 🏥 UNIFIED ALERT SYSTEM (Square/Toast pattern)
        // Shows ALL alerts in priority order: connection (P0, P2) + device health (P1, P3-P6)
        // Single expandable banner instead of separate ConnectionBanner + DeviceAlertBanner
        // ConnectionViewModel still handles reconnection logic, but alerts show here
        // Don't show banner if there's a FORCE update (modal handles it)
        val alertsToShow = if (forceUpdateAlert != null) {
            deviceAlerts.filterNot { it is com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert.UpdateAvailable }
        } else {
            deviceAlerts
        }

        com.jaac.avoqado_tpv.core.presentation.components.DeviceAlertBanner(
            alerts = alertsToShow,
            isExpanded = deviceAlertsExpanded,
            onToggleExpand = { deviceHealthViewModel.toggleExpanded() },
            onDismiss = { alert -> deviceHealthViewModel.dismissAlert(alert) },
            onRetry = {
                connectionViewModel.forceCheck()
                deviceHealthViewModel.retryFailedPayments() // Reset FAILED→PENDING for retry
                PaymentSyncScheduler.runNow(context) // Trigger immediate sync (don't wait 15 min)
            },
            onUpdate = {
                if (BuildConfig.ENABLE_PAX_SDK) {
                    navController.navigate(NavRoute.SelfUpdate.route)
                } else {
                    Timber.w("🧪 Self-update disabled for this flavor")
                }
            } // For update alerts
        )

        // Main navigation content (takes remaining space)
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = startDestination
            ) {
        // Splash Screen - Determines initial route based on activation status
        // Also fetches venue modules at startup so features are available from the beginning
        composable(NavRoute.Splash.route) {
            SplashScreen(
                deviceInfoManager = deviceInfoManager,
                secureStorage = secureStorage,
                modulesRepository = modulesRepository,
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

        // Activation Screen - Enter 6-char activation code or scan QR
        composable(NavRoute.Activation.route) {
            val viewModel: ActivationViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            // 📱 QR Scanner state
            var showQrScanner by remember { mutableStateOf(false) }

            // Navigate to Login after successful activation or auto-detected activation
            LaunchedEffect(state) {
                when (state) {
                    is ActivationState.Success,
                    is ActivationState.AlreadyActivated -> {
                        navController.navigate(NavRoute.Login.route) {
                            popUpTo(NavRoute.Activation.route) { inclusive = true }
                        }
                    }
                    else -> { /* Stay on activation screen */ }
                }
            }

            // Auto-retry: Check if terminal is already activated on backend every 10 seconds
            // This handles the case where user arrives here because server was down,
            // but the terminal IS activated. When server comes back, auto-navigate to Login.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(10_000) // 10 seconds
                    val isActivated = viewModel.checkAlreadyActivatedOnBackend()
                    if (isActivated) {
                        Timber.i("🔄 [Activation] Auto-retry detected activation - stopping loop")
                        break // Terminal activated, navigation will happen via state change
                    }
                }
            }

            // 📱 Show dedicated QR Scanner for activation
            if (showQrScanner) {
                com.jaac.avoqado_tpv.features.activation.presentation.components.ActivationQrScannerScreen(
                    onQrScanned = { qrData ->
                        Timber.d("📱 [Activation] QR scanned: $qrData")
                        // Parse QR data and extract activation code
                        viewModel.processQrActivation(qrData)
                        showQrScanner = false
                    },
                    onClose = {
                        showQrScanner = false
                    }
                )
            } else {
                // Render UI
                ActivationScreen(
                    serialNumber = viewModel.serialNumber,
                    onActivate = viewModel::activate,
                    isLoading = state is ActivationState.Loading,
                    errorMessage = (state as? ActivationState.Error)?.message,
                    configErrorMessage = (state as? ActivationState.ConfigError)?.message,
                    onRetryConfig = if (state is ActivationState.ConfigError) viewModel::retryConfigFetch else null,
                    onOpenQrScanner = { showQrScanner = true }
                )
            }
        }

        // Login Screen - PIN authentication
        composable(NavRoute.Login.route) {
            val context = LocalContext.current
            val loginCoroutineScope = rememberCoroutineScope()
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

                    // 📍 Cambaceo: hourly promoter location ping (self-gated by venue flag + 11-18h window)
                    PromoterLocationScheduler.start(context)

                    // 📦 Fetch modules before navigating to Home
                    // This is critical when user logs out and logs back in without app restart
                    // Modules are cleared on logout but must be reloaded for WelcomeScreen
                    loginCoroutineScope.launch {
                        Timber.d("📦 Login: Fetching modules before navigating to Home")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            modulesRepository.fetchAndCache()
                        }

                        // 🔧 Refresh terminal config so the cached `angelpayAuth`
                        // (only populated by the backend when the request is
                        // authenticated with the user session JWT) is available
                        // for AngelPayCredentialResolver. Pre-login fetch in
                        // MainActivity runs without a user session and the
                        // backend omits angelpayAuth — without this refresh,
                        // the first payment attempt fails with "AngelPay
                        // requiere autenticación" (cache stays null).
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val serialNumber = deviceInfoManager.getSerialNumber()
                            Timber.d("🔧 Login: Refreshing terminal config for serial: $serialNumber")
                            terminalConfigRepository.fetchConfig(serialNumber)
                                .onSuccess { Timber.i("✅ Login: Terminal config refreshed (angelpayAuth cache may be populated)") }
                                .onFailure { Timber.w(it, "⚠️ Login: Terminal config refresh failed — AngelPay auth may not have credentials") }
                        }

                        // Navigate to home (must be on Main thread)
                        navController.navigate(NavRoute.Home.route) {
                            popUpTo(NavRoute.Login.route) { inclusive = true }
                        }
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
        composable(NavRoute.Home.route) { homeBackStackEntry ->
            val homeViewModel: com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel = hiltViewModel()
            val homeContext = LocalContext.current
            val homeCoroutineScope = rememberCoroutineScope()

            // Refresh attendance when returning from TimeclockScreen
            val refreshAttendance = homeBackStackEntry.savedStateHandle
                .get<Boolean>("refreshAttendance") == true
            if (refreshAttendance) {
                homeBackStackEntry.savedStateHandle.remove<Boolean>("refreshAttendance")
                LaunchedEffect(Unit) {
                    homeViewModel.refreshAttendance()
                }
            }

            // 🔧 SDK Initialization for Serialized Sale ("Vender" button)
            // Get InitializationManager to await SDK init before navigating to payment flow
            val initializationManager = remember {
                val entryPoint = EntryPointAccessors.fromApplication(
                    homeContext.applicationContext,
                    AppNavigationEntryPoint::class.java
                )
                entryPoint.initializationManager()
            }
            var isInitializingSdk by remember { mutableStateOf(false) }

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

            // 📨 Fetch message history when WelcomeScreen is shown
            LaunchedEffect(Unit) {
                tpvMessageViewModel.fetchMessageHistory()
            }

            // 🔧 Loading overlay for SDK initialization
            Box(modifier = Modifier.fillMaxSize()) {
            WelcomeScreen(
                onRefreshConnection = { connectionViewModel.forceCheck() },
                onStartPaymentWithAmount = { amount ->
                    homeCoroutineScope.launch {
                        if (!awaitPaxPaymentReady(homeContext, initializationManager, "Welcome fast payment")) {
                            return@launch
                        }
                        Timber.d("[PERF] NAV: Welcome → PaymentScreen START (nexgo=${isAppToAppPayment()})")
                        navController.currentBackStackEntry?.savedStateHandle?.set("initialAmount", amount)
                        navController.navigate(getPaymentRoute())
                    }
                },
                onNavigateToShifts = {
                    Timber.d("[PERF] NAV: Welcome → Shifts START")
                    navController.navigate(NavRoute.Shifts.route)
                },
                onNavigateToOrdering = {
                    homeCoroutineScope.launch {
                        if (!awaitPaxPaymentReady(homeContext, initializationManager, "Welcome ordering")) {
                            return@launch
                        }
                        Timber.d("[PERF] NAV: Welcome → Ordering START")
                        navController.navigate(NavRoute.OrderingWelcome.route)
                    }
                },
                onNavigateToCheckout = {
                    // Phase 2: open Checkout without awaiting PAX init. The PAX
                    // gate moves to the Cobrar → PaymentScreen transition in Phase 5.
                    Timber.d("[PERF] NAV: Welcome → Checkout START")
                    navController.navigate(NavRoute.Checkout.route)
                },
                onNavigateToReports = {
                    // Navigate to Reports screen
                    navController.navigate(NavRoute.Reports.route)
                },
                onNavigateToPayments = {
                    homeCoroutineScope.launch {
                        if (!awaitPaxPaymentReady(homeContext, initializationManager, "Welcome payments/refunds")) {
                            return@launch
                        }
                        navController.navigate(NavRoute.Payments.route)
                    }
                },
                onNavigateToSupport = {
                    // Navigate to Support screen
                    navController.navigate(NavRoute.Support.route)
                },
                onNavigateToSettings = {
                    // Navigate to Settings screen
                    navController.navigate(NavRoute.Settings.route)
                },
                onNavigateToSelfUpdate = {
                    navController.navigate(NavRoute.SelfUpdate.route)
                },
                onNavigateToSuperAdmin = {
                    // Navigate to SuperAdmin screen
                    navController.navigate(NavRoute.SuperAdmin.route)
                },
                onNavigateToSerializedSale = {
                    // 📱 Vender flow: Await Blumon SDK initialization, then navigate
                    // This ensures SDK is ready before user scans barcode and proceeds to payment
                    homeCoroutineScope.launch {
                        isInitializingSdk = true
                        Timber.d("🔧 [Vender] Awaiting Blumon SDK initialization...")

                        if (!awaitPaxPaymentReady(homeContext, initializationManager, "Serialized sale entry")) {
                            isInitializingSdk = false
                            return@launch
                        }

                        Timber.d("✅ [Vender] SDK ready - navigating to SerializedSale")
                        isInitializingSdk = false
                        navController.navigate(NavRoute.SerializedSale.route)
                    }
                },
                onNavigateToInventoryRegister = {
                    // 📦 Navigate to Serialized Inventory Register screen (Alta flow)
                    navController.navigate(NavRoute.SerializedInventoryRegister.route)
                },
                onNavigateToMySales = {
                    // 📊 Navigate to My Sales screen (promoter sales history)
                    navController.navigate(NavRoute.MySales.route)
                },
                onNavigateToMyCommissions = {
                    // 💰 Navigate to My Commissions screen (promoter cash-out balance + withdrawal)
                    navController.navigate(NavRoute.MyCommissions.route)
                },
                onNavigateToMisSims = {
                    // 📱 Navigate to Mis SIMs screen (promoter SIM custody inbox)
                    navController.navigate(NavRoute.MisSims.route)
                },
                onNavigateToMessages = {
                    navController.navigate(NavRoute.Messages.route)
                },
                onNavigateToTrainings = {
                    navController.navigate(NavRoute.Trainings.route)
                },
                onNavigateToPendingVerifications = {
                    navController.navigate("pending_verifications")
                },
                onNavigateToTimeclock = {
                    // ⏱ Navigate to Timeclock from WelcomeScreen (fromHome=true, auto clock-in)
                    val pin = secureStorage.getStaffPin()
                    val vId = secureStorage.getVenueId() ?: ""
                    if (pin != null && vId.isNotEmpty()) {
                        Timber.d("⏱ WelcomeScreen → Timeclock (fromHome=true, autoAction=clockIn)")
                        navController.navigate(NavRoute.Timeclock.createRoute(vId, pin, fromHome = true, autoAction = "clockIn"))
                    } else {
                        Timber.w("⚠️ No stored PIN or venueId for timeclock navigation")
                    }
                },
                onNavigateToTimeclockForClockOut = {
                    // ⏱ Navigate to Timeclock for clock-out from WelcomeScreen (fromHome=true, auto clock-out)
                    val pin = secureStorage.getStaffPin()
                    val vId = secureStorage.getVenueId() ?: ""
                    if (pin != null && vId.isNotEmpty()) {
                        Timber.d("⏱ WelcomeScreen → Timeclock for clock-out (fromHome=true, autoAction=clockOut)")
                        navController.navigate(NavRoute.Timeclock.createRoute(vId, pin, fromHome = true, autoAction = "clockOut"))
                    } else {
                        Timber.w("⚠️ No stored PIN or venueId for timeclock navigation")
                    }
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

                    // 📍 Promoter tracking DOES stop with the session (privacy) — unlike heartbeat
                    PromoterLocationScheduler.stop(context)

                    // Clear session
                    homeViewModel.logout()

                    // Navigate back to login
                    navController.navigate(NavRoute.Login.route) {
                        popUpTo(NavRoute.Home.route) { inclusive = true }
                    }
                },
                messageHistory = tpvMessageState.messageHistory,
                isLoadingMessageHistory = tpvMessageState.isLoadingHistory,
                onMessageClick = { tpvMessageViewModel.selectMessage(it) },
                isDarkMode = isDarkMode,
                onDarkModeToggle = onThemeToggle
            )

                // 🔧 Loading overlay while awaiting Blumon SDK initialization
                if (isInitializingSdk) {
                    AvoqadoLoadingOverlay(message = "Inicializando Blumon...")
                }
            } // End Box
        }

        // Fast Payment Entry Screen - Dedicated screen for amount input
        composable(NavRoute.FastPaymentEntry.route) {
            val fastPaymentScope = rememberCoroutineScope()
            FastPaymentEntryScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onAmountSubmit = { amount ->
                    fastPaymentScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Fast payment entry")) {
                            return@launch
                        }
                        navController.currentBackStackEntry?.savedStateHandle?.set("initialAmount", amount)
                        navController.navigate(getPaymentRoute())
                    }
                }
            )
        }

        // Unified Checkout — Phase 5: routes "Cobrar" to PaymentScreen for both
        // the FAST flow (only custom amounts → just amount in savedStateHandle)
        // and the ORDER flow (catalog products → backend Order created, then
        // amount + orderId + orderNumber forwarded to Payment).
        composable(NavRoute.Checkout.route) {
            val checkoutScope = rememberCoroutineScope()
            CheckoutScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToPayment = { payload ->
                    checkoutScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Checkout payment")) {
                            return@launch
                        }
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("initialAmount", payload.amountPesosString)
                            // Tag the source so the Payment success screen knows
                            // to route "Nueva Orden" / "Nuevo Pago" back to the
                            // unified Cart instead of the legacy FastPaymentEntry
                            // or MenuScreen.
                            set("entryPoint", "checkout")
                            when (payload) {
                                is com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload.Order -> {
                                    set("orderId", payload.orderId)
                                    set("orderNumber", payload.orderNumber)
                                    set("wasPayLaterOrder", payload.wasPayLaterOrder)
                                }
                                is com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload.Fast -> {
                                    // Nothing extra — FAST flow uses just initialAmount,
                                    // matching the existing FastPaymentEntry behavior.
                                }
                                is com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload.CompletedFreeCart -> {
                                    // Unreachable: CheckoutScreen short-circuits this payload
                                    // (free cart already closed on the backend; UI shows a
                                    // snackbar and stays on Checkout instead of navigating).
                                    // Branch kept exhaustive for the compiler.
                                }
                            }
                        }
                        Timber.d("💳 Checkout → Payment: origin=${payload.origin} amount=${payload.amountPesosString}")
                        navController.navigate(getPaymentRoute())
                    }
                },
            )
        }

        // Ordering Welcome Screen - Entry point for ordering system
        composable(NavRoute.OrderingWelcome.route) {
            OrderingWelcomeScreen(
                showTableService = secureStorage.supportsTableService(),
                onQuickOrderClick = {
                    Timber.d("[PERF] NAV: Ordering → MenuScreen (QuickOrder) START")
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
                onViewUnpaidOrdersClick = {
                    // Navigate to Order List screen with UNPAID_TAKEOUT filter
                    navController.navigate(NavRoute.OrderList.createRoute("UNPAID_TAKEOUT"))
                    Timber.d("🔔 Navigating to Order List with UNPAID_TAKEOUT filter")
                },
                onViewPayLaterOrdersClick = {
                    // Navigate to Order List screen with PAY_LATER filter
                    navController.navigate(NavRoute.OrderList.createRoute("PAY_LATER"))
                    Timber.d("💳 Navigating to Order List with PAY_LATER filter")
                },
                onNavigateBack = {
                    navController.safePopBackStack()
                }
            )
        }

        // Floor Plan Canvas Screen - Visual floor plan editor with zoom/pan
        composable(NavRoute.FloorPlan.route) {
            FloorPlanCanvasScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
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
            val menuPaymentScope = rememberCoroutineScope()

            MenuScreen(
                orderId = orderId,
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onProcessPayment = { order ->
                    menuPaymentScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Order payment")) {
                            return@launch
                        }
                        // ✅ FIX: Pass remainingBalance instead of total for split payments
                        // If order is fresh: remainingBalance = total (correct)
                        // If order has partial payment: remainingBalance = total - paidAmount (correct)
                        val isPayLaterOrder = order.orderCustomers.isNotEmpty()
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("initialAmount", order.remainingBalance.toString())
                            set("orderId", order.id)
                            set("orderNumber", order.orderNumber)
                            set("tableId", order.tableId)  // 🆕 Pass tableId for post-payment clearing
                            set("splitType", SplitType.FULLPAYMENT.value)
                            // 💳 PAY-LATER CONTEXT: Detect if order has customers
                            set("wasPayLaterOrder", isPayLaterOrder)
                        }
                        navController.navigate(getPaymentRoute())
                        Timber.d("💳 Navigating to payment: ${order.orderNumber} - Remaining: $${order.remainingBalance} (Total: $${order.total}) | wasPayLater=$isPayLaterOrder | nexgo=${isAppToAppPayment()}")
                    }
                },
                onProcessPaymentWithAmount = { order, customAmount, splitType ->
                    menuPaymentScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Order custom payment")) {
                            return@launch
                        }
                        // Custom amount payment with split type (CUSTOMAMOUNT)
                        val isPayLaterOrder = order.orderCustomers.isNotEmpty()
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("initialAmount", customAmount.toString())
                            set("orderId", order.id)
                            set("orderNumber", order.orderNumber)
                            set("tableId", order.tableId)
                            set("splitType", splitType.value)
                            // 💳 PAY-LATER CONTEXT: Detect if order has customers
                            set("wasPayLaterOrder", isPayLaterOrder)
                        }
                        navController.navigate(getPaymentRoute())
                        Timber.d("💳 Navigating to custom amount payment: $customAmount with splitType=${splitType.value} | wasPayLater=$isPayLaterOrder | nexgo=${isAppToAppPayment()}")
                    }
                },
                onNavigateToSplitByProduct = { splitOrderId, hasCustomers ->
                    // 💳 Pass wasPayLaterOrder through savedStateHandle for split screen to forward
                    navController.currentBackStackEntry?.savedStateHandle?.set("wasPayLaterOrder", hasCustomers)
                    navController.navigate(NavRoute.SplitByProduct.createRoute(splitOrderId))
                    Timber.d("📦 Navigating to SplitByProduct: $splitOrderId | wasPayLater=$hasCustomers")
                },
                onNavigateToSplitByPerson = { splitOrderId, hasCustomers ->
                    // 💳 Pass wasPayLaterOrder through savedStateHandle for split screen to forward
                    navController.currentBackStackEntry?.savedStateHandle?.set("wasPayLaterOrder", hasCustomers)
                    navController.navigate(NavRoute.SplitByPerson.createRoute(splitOrderId))
                    Timber.d("👥 Navigating to SplitByPerson: $splitOrderId | wasPayLater=$hasCustomers")
                }
            )
        }

        // Order List Screen - List of all orders with filters
        composable(
            route = NavRoute.OrderList.route,
            arguments = listOf(
                navArgument("filter") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val filterParam = backStackEntry.arguments?.getString("filter")
            val initialFilter = filterParam?.let {
                try {
                    OrderStatusFilter.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    OrderStatusFilter.ALL
                }
            } ?: OrderStatusFilter.ALL

            OrderListScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onOrderClick = { order ->
                    // Navigate to MenuScreen to view/edit order
                    navController.navigate(NavRoute.Menu.createRoute(order.id))
                    Timber.d("📋 Viewing order: ${order.orderNumber}")
                },
                initialFilter = initialFilter
            )
        }

        // Shifts Screen - Shift management (open/close shifts)
        composable(NavRoute.Shifts.route) {
            com.jaac.avoqado_tpv.features.shift.presentation.ShiftScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
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

            if (BuildConfig.ENABLE_PAX_SDK && !isBlumonSdkInitialized) {
                LaunchedEffect(Unit) {
                    val ready = awaitPaxPaymentReady(context, initializationManager, "Payment route")
                    if (!ready) {
                        val popped = navController.safePopBackStack()
                        if (!popped) {
                            navController.navigate(NavRoute.Home.route) {
                                popUpTo(NavRoute.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    }
                }
                AvoqadoLoadingOverlay(message = "Preparando sistema de pagos...")
                return@composable
            }

            // Get initial amount from previous screen (if coming from Home with amount)
            val initialAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("initialAmount")

            // 🧪 Get skipReview flag (test payment from SuperAdmin)
            val skipReview = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("skipReview") ?: false
            // 🔵 External device inputs (BLE)
            val externalTipCents = navController.previousBackStackEntry?.savedStateHandle?.get<Long>("externalTipCents")
            val externalRating = navController.previousBackStackEntry?.savedStateHandle?.get<Int>("externalRating")
            val externalSkipReview = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("externalSkipReview") ?: false

            // 🆕 Get order details (if coming from MenuScreen with order)
            val orderId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("orderId")
            val orderNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("orderNumber")
            val tableId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("tableId")
            // 🛒 Source identifier — when "checkout", post-success "Nueva Orden"
            // / "Nuevo Pago" route back to the unified Cart instead of the
            // legacy MenuScreen / FastPaymentEntry.
            val entryPoint = navController.previousBackStackEntry?.savedStateHandle?.get<String>("entryPoint")
            val cameFromCheckout = entryPoint == "checkout"
            // 📱 SERIALIZED SALE: Skip local order validation (order exists only on backend)
            val skipLocalOrderValidation = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("skipLocalOrderValidation") ?: false
            // 📱 PORTABILIDAD: Controls 1 vs 2 proof-of-sale photos
            val isPortabilidad = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("isPortabilidad") ?: false
            // 📱 SERIALIZED: Serial number (ICCID) and category name for receipt
            val serialNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("serialNumber")
            val categoryName = navController.previousBackStackEntry?.savedStateHandle?.get<String>("categoryName")

            // ⭐ Split payment params (from SplitByPersonScreen or SplitByProductScreen)
            val splitType = navController.previousBackStackEntry?.savedStateHandle?.get<String>("splitType")
            val equalPartsPartySize = navController.previousBackStackEntry?.savedStateHandle?.get<Int>("equalPartsPartySize")
            val equalPartsPayedFor = navController.previousBackStackEntry?.savedStateHandle?.get<Int>("equalPartsPayedFor")
            val paidProductIds = navController.previousBackStackEntry?.savedStateHandle?.get<List<String>>("paidProductIds") ?: emptyList()

            // 💸 REFUND MODE PARAMS (from RefundConfirmationScreen)
            val isRefundMode = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("isRefundMode") ?: false
            val refundAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("refundAmount")
            val refundReason = navController.previousBackStackEntry?.savedStateHandle?.get<String>("refundReason")
            val originalPaymentId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("originalPaymentId")
            val originalOrderId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("originalOrderId")
            val originalTotalAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("originalTotalAmount")
            val originalTipAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("originalTipAmount")
            val refundMerchantAccountId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("merchantAccountId")
            val refundBlumonSerialNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("blumonSerialNumber")
            // 🎫 CRITICAL: Blumon operation number for CancelIcc (from webhook, NOT SDK referenceNumber!)
            val originalOperationNumber = navController.previousBackStackEntry?.savedStateHandle?.get<Int>("blumonOperationNumber")
            // 🏢 CRITICAL: Payment's venueId for refund API call (NOT auth context's venue!)
            val refundVenueId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("paymentVenueId")
            // 💸 Explicit tip-split override from RefundConfirmationScreen (null = backend default)
            val refundTipCents = navController.previousBackStackEntry?.savedStateHandle?.get<Int>("tipRefundCents")

            // 💳 PAY-LATER CONTEXT PARAMS (from OrderListScreen/OrderDetailsBottomSheet)
            val wasPayLaterOrder = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("wasPayLaterOrder") ?: false
            val payLaterOrdersCount = navController.previousBackStackEntry?.savedStateHandle?.get<Int>("payLaterOrdersCount") ?: 0

            // 🥝 KIOSK MODE PARAMS
            val isKioskPayment = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("isKioskPayment") ?: false

            // 📡 SOCKET PAYMENT SOURCE (for sending result back via Socket.IO)
            val paymentSourceStr = navController.previousBackStackEntry?.savedStateHandle?.get<String>("paymentSource")
            val socketRequestId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("socketRequestId")
            val socketProcessedByStaffId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("socketProcessedByStaffId")

            Timber.d("💳 [Payment] Pay-later context: wasPayLaterOrder=$wasPayLaterOrder, count=$payLaterOrdersCount, isKiosk=$isKioskPayment, source=$paymentSourceStr")

            // 🔌 Get TableRepository via Hilt EntryPoint for clearing tables post-payment
            val context = LocalContext.current
            val tableRepository = remember {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AppNavigationEntryPoint::class.java
                )
                entryPoint.tableRepository()
            }
            val coroutineScope = rememberCoroutineScope()

            PaymentScreen(
                initialAmount = initialAmount,
                orderId = orderId,
                orderNumber = orderNumber,
                tableId = tableId,  // 🆕 Pass tableId to determine post-payment flow
                skipReview = skipReview,
                externalTipCents = externalTipCents,
                externalRating = externalRating,
                externalSkipReview = externalSkipReview,
                skipLocalOrderValidation = skipLocalOrderValidation,  // 📱 SERIALIZED SALE: Order exists only on backend
                isPortabilidad = isPortabilidad,  // 📱 PORTABILIDAD: 1 vs 2 proof-of-sale photos
                serialNumber = serialNumber,  // 📱 ICCID/serial for receipt
                categoryName = categoryName,  // 📱 Category name for receipt
                // ⭐ Split payment params
                splitType = splitType,
                equalPartsPartySize = equalPartsPartySize,
                equalPartsPayedFor = equalPartsPayedFor,
                paidProductIds = paidProductIds,
                // 💸 REFUND MODE PARAMS
                isRefundMode = isRefundMode,
                refundAmount = refundAmount,
                refundReason = refundReason,
                originalPaymentId = originalPaymentId,
                originalOrderId = originalOrderId,
                originalTotalAmount = originalTotalAmount,
                originalTipAmount = originalTipAmount,
                refundMerchantAccountId = refundMerchantAccountId,
                refundBlumonSerialNumber = refundBlumonSerialNumber,
                // 🎫 CRITICAL: Blumon operation number for CancelIcc (from webhook)
                originalOperationNumber = originalOperationNumber,
                // 🏢 CRITICAL: Payment's venueId for refund API call (NOT auth context's venue!)
                refundVenueId = refundVenueId,
                // 💸 Tip-split override propagated from RefundConfirmationScreen
                refundTipCents = refundTipCents,
                // 💳 PAY-LATER CONTEXT PARAMS
                wasPayLaterOrder = wasPayLaterOrder,
                payLaterOrdersCount = payLaterOrdersCount,
                // 📡 SOCKET PAYMENT SOURCE
                paymentSource = paymentSourceStr,
                socketRequestId = socketRequestId,
                socketProcessedByStaffId = socketProcessedByStaffId,
                // 🥝 KIOSK MODE PARAMS
                isKioskPayment = isKioskPayment,
                onKioskPaymentSuccess = if (isKioskPayment) { displayOrderNumber, receipt, orderItems ->
                    // Clear kiosk payment state and show success screen
                    // NOTE: For kiosk mode, payment is rendered directly in AppNavigation (bypassing NavHost)
                    // This callback is kept for consistency but shouldn't be reached in normal kiosk flow
                    Timber.i("🥝 [KIOSK] Payment success (NavHost) - showing success screen with order: $displayOrderNumber, items: ${orderItems?.size}")
                    kioskPaymentOrderId = null
                    kioskPaymentAmount = null
                    kioskPaymentOrderNumber = null
                    kioskSuccessOrderNumber = displayOrderNumber
                    kioskSuccessReceipt = receipt
                    kioskSuccessOrderItems = orderItems
                } else null,
                onNavigateBack = {
                    // 🔙 Prefer returning to previous screen (order/menu/fast entry). Fallback to Home.
                    val popped = navController.safePopBackStack()
                    if (!popped) {
                        navController.safePopBackStack(NavRoute.Home.route, inclusive = false)
                    }
                },
                onNavigateHome = {
                    val popped = navController.safePopBackStack(NavRoute.Home.route, inclusive = false)
                    if (!popped) {
                        navController.navigate(NavRoute.Home.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onRefundComplete = {
                    // 💸 Refund success: signal Payments to refresh, then return
                    navController.getBackStackEntry(NavRoute.Payments.route)
                        .savedStateHandle["refreshAfterRefund"] = true
                    val popped = navController.safePopBackStack(NavRoute.Payments.route, inclusive = false)
                    if (!popped) {
                        navController.navigate(NavRoute.Payments.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                        }
                    }
                },
                onNavigateToShifts = {
                    // 🆕 Navigate to Shifts screen (for "No shift open" errors)
                    navController.navigate(NavRoute.Shifts.route)
                },
                onNavigateToNewOrder = {
                    if (cameFromCheckout) {
                        // 🛒 Came from unified Cart → return there with a fresh
                        // CheckoutViewModel (popping past Home creates a new
                        // backstack entry, so the cart starts empty).
                        navController.navigate(NavRoute.Checkout.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                        }
                        Timber.d("🛒 [Navigation] Nueva Orden: returning to unified Checkout")
                    } else {
                        // 🔄 Toast/Square Pattern: Navigate FORWARD to fresh MenuScreen instance
                        // This creates a new order with fresh ViewModel (no state reuse/patching)
                        navController.navigate(NavRoute.Menu.createRoute("CREATE_QUICK_ORDER")) {
                            // Pop back to OrderingWelcome but don't include it
                            // Stack: OrderingWelcome → MenuScreen (NEW)
                            popUpTo(NavRoute.OrderingWelcome.route) { inclusive = false }
                        }
                        Timber.d("🔄 [Navigation] Toast/Square pattern: Navigated to NEW quick order")
                    }
                },
                onNavigateToSerializedSale = {
                    // 📱 Serialized sale: return to scanner flow, avoid quick order menu
                    runCatching {
                        navController.getBackStackEntry(NavRoute.SerializedSale.route)
                    }.onSuccess { entry ->
                        entry.savedStateHandle["resetSerializedSale"] = true
                    }

                    val popped = navController.safePopBackStack(NavRoute.SerializedSale.route, inclusive = false)
                    if (!popped) {
                        navController.navigate(NavRoute.SerializedSale.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                        }
                        runCatching {
                            navController.getBackStackEntry(NavRoute.SerializedSale.route)
                        }.onSuccess { entry ->
                            entry.savedStateHandle["resetSerializedSale"] = true
                        }
                    }
                    Timber.d("📱 [Navigation] Serialized sale: Returned to scanner flow")
                },
                onNavigateToNewFastPayment = {
                    if (cameFromCheckout) {
                        // 🛒 Came from unified Cart → return there with a fresh
                        // CheckoutViewModel.
                        navController.navigate(NavRoute.Checkout.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                        }
                        Timber.d("🛒 [Navigation] Nuevo Pago: returning to unified Checkout")
                    } else {
                        // 🔄 Navigate to FastPaymentEntryScreen for new fast payment
                        navController.navigate(NavRoute.FastPaymentEntry.route) {
                            // Pop back to Home but don't include it (keep Home in backstack)
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                        }
                        Timber.d("🔄 [Navigation] Fast payment: Navigated to FastPaymentEntryScreen")
                    }
                },
                onClearTableAndReturnToFloorPlan = { clearedTableId ->
                    // 🪑 Square Pattern: Clear table and return to floor plan
                    // This allows table to become AVAILABLE for new customers
                    coroutineScope.launch {
                        val venueId = secureStorage.getVenueId()
                        if (venueId != null) {
                            Timber.i("🧹 [Navigation] Clearing table $clearedTableId")
                            when (val result = tableRepository.clearTable(venueId, clearedTableId)) {
                                is com.jaac.avoqado_tpv.core.domain.models.Result.Success -> {
                                    Timber.i("✅ [Navigation] Table cleared successfully - navigating to floor plan")
                                }
                                is com.jaac.avoqado_tpv.core.domain.models.Result.Error -> {
                                    Timber.e("❌ [Navigation] Failed to clear table: ${result.exception}")
                                }
                            }
                        } else {
                            Timber.e("❌ [Navigation] Cannot clear table - no venueId in session")
                        }

                        // Navigate to floor plan regardless of clear result
                        // ✅ FIX: Navigate to FloorPlan (visual canvas) not TableService (old grid)
                        navController.navigate(NavRoute.FloorPlan.route) {
                            popUpTo(NavRoute.OrderingWelcome.route) { inclusive = false }
                        }
                    }
                },
                onNavigateToOrder = { continueOrderId, continueTableId ->
                    // ⭐ Split Payment: Navigate back to order to continue paying remaining balance
                    // This is called when user taps "Continuar pagando" button after partial payment
                    Timber.i("💰 [Navigation] Continue payment - navigating back to order: $continueOrderId")
                    navController.navigate(NavRoute.Menu.createRoute(continueOrderId)) {
                        // Pop payment screen and return to menu with same order
                        popUpTo(NavRoute.Payment.route) { inclusive = true }
                    }
                },
                onNavigateToPayLaterOrders = {
                    // 💳 Navigate to OrderListScreen with PAY_LATER filter
                    Timber.i("💳 [Navigation] Navigating to pay-later orders list")
                    navController.navigate(NavRoute.OrderList.createRoute(OrderStatusFilter.PAY_LATER.name)) {
                        // Pop payment screen and go to OrderList with PAY_LATER filter
                        popUpTo(NavRoute.OrderingWelcome.route) { inclusive = false }
                    }
                }
            )
        }

        // Settings Screen
        composable(NavRoute.Settings.route) {
            com.jaac.avoqado_tpv.features.settings.presentation.SettingsScreen(
                onBack = { navController.safePopBackStack() },
                onNavigateToShifts = { navController.navigate(NavRoute.Shifts.route) },
                onNavigateToSelfUpdate = { navController.navigate(NavRoute.SelfUpdate.route) }
            )
        }

        // SuperAdmin Screen - Testing and debugging tools
        composable(NavRoute.SuperAdmin.route) {
            // State to track pending test payment
            var pendingTestPayment by remember { mutableStateOf(false) }

            // Navigate to payment when test payment is triggered
            LaunchedEffect(pendingTestPayment) {
                if (pendingTestPayment) {
                    if (!awaitPaxPaymentReady(context, initializationManager, "SuperAdmin test payment")) {
                        pendingTestPayment = false
                        return@LaunchedEffect
                    }
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
                    navController.safePopBackStack()
                },
                onTestPayment = {
                    // Trigger test payment of $10.00
                    pendingTestPayment = true
                },
                onOpenProcessorTransactions = { processor ->
                    // Reconciliation tool — opens the SDK-level
                    // transactions browser for the chosen processor.
                    // Only reachable from SuperAdmin (TOTP-gated).
                    navController.navigate(
                        NavRoute.PaymentTransactions.createRoute(processor.name)
                    )
                },
            )
        }

        // Reports Screen - Sales analytics and reporting dashboard
        composable(NavRoute.Reports.route) {
            val reportsViewModel: com.jaac.avoqado_tpv.features.reports.presentation.ReportsViewModel =
                hiltViewModel()

            com.jaac.avoqado_tpv.features.reports.presentation.ReportsScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onNavigateToProductPerformance = {
                    // TODO: Implement Product Performance screen navigation
                    Timber.d("📊 Product Performance screen not yet implemented")
                },
                onNavigateToPeriodDetail = { period ->
                    // Store period in ViewModel and navigate to detail screen
                    reportsViewModel.setSelectedPeriod(period)
                    navController.navigate(NavRoute.HistoricalPeriodDetail.route)
                }
            )
        }

        // Historical Period Detail Screen - Detailed view of a single period
        composable(NavRoute.HistoricalPeriodDetail.route) { navBackStackEntry ->
            // Get ViewModel from parent nav graph to access selectedPeriod
            // Using navBackStackEntry as key ensures we don't recreate during recomposition
            val parentEntry = remember(navBackStackEntry) {
                navController.getBackStackEntry(NavRoute.Reports.route)
            }
            val reportsViewModel: com.jaac.avoqado_tpv.features.reports.presentation.ReportsViewModel =
                hiltViewModel(viewModelStoreOwner = parentEntry)

            val selectedPeriod by reportsViewModel.selectedPeriod.collectAsStateWithLifecycle()

            if (selectedPeriod != null) {
                com.jaac.avoqado_tpv.features.reports.presentation.HistoricalPeriodDetailScreen(
                    period = selectedPeriod!!,
                    venueZoneId = reportsViewModel.venueZoneId,
                    onNavigateBack = {
                        reportsViewModel.clearSelectedPeriod()
                        navController.safePopBackStack()
                    }
                )
            } else {
                // If no period selected, navigate back to reports
                LaunchedEffect(Unit) {
                    Timber.w("⚠️ No period selected for detail screen, navigating back")
                    navController.safePopBackStack()
                }
            }
        }

        // Payments Screen - Payment history with pagination and filters
        composable(
            route = NavRoute.Payments.route,
            arguments = listOf(
                navArgument("autoOpenPaymentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            // Auto-refresh after refund completion
            val refreshAfterRefund = backStackEntry.savedStateHandle
                .get<Boolean>("refreshAfterRefund") == true
            // Optional deep-link: open detail bottom sheet for this payment
            // as soon as the list finishes loading (used from AngelPay
            // success → "Ver en Pagos").
            val autoOpenPaymentId = backStackEntry.arguments
                ?.getString("autoOpenPaymentId")
                ?.takeIf { it.isNotBlank() }
            com.jaac.avoqado_tpv.features.payments.presentation.PaymentsScreen(
                refreshAfterRefund = refreshAfterRefund,
                onRefundRefreshConsumed = {
                    backStackEntry.savedStateHandle.remove<Boolean>("refreshAfterRefund")
                },
                autoOpenPaymentId = autoOpenPaymentId,
                onBack = {
                    navController.safePopBackStack()
                },
                onNavigateToRefund = { payment: Payment ->
                    // Store payment data in savedStateHandle for RefundConfirmationScreen
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("paymentId", payment.id)
                        set("orderNumber", payment.orderNumber)
                        // 💸 CRITICAL FIX: Use totalAmount (includes tip), NOT amount (base only)
                        // Blumon rejects refunds where amount doesn't match original payment total
                        set("originalAmount", payment.totalAmount.toString())
                        set("originalTipAmount", payment.tipAmount.toString())
                        set("paymentMethod", payment.method.label) // Payment uses 'method: PaymentMethod' enum
                        set("createdAt", payment.createdAt.toString())
                        set("merchantAccountId", payment.merchantAccountId ?: "")
                        set("blumonSerialNumber", payment.blumonSerialNumber ?: "")
                        set("refundedAmount", (payment.refundedAmount ?: java.math.BigDecimal.ZERO).toString())
                        // Also store original orderId for RefundPayment context
                        set("orderId", payment.orderId)
                        // 🎫 CRITICAL: Blumon operation number for CancelIcc refunds (from webhook)
                        // NOTE: This is the small int (e.g., 75656), NOT referenceNumber (12-digit)
                        set("blumonOperationNumber", payment.blumonOperationNumber ?: 0)
                        // 🏢 CRITICAL: Pass payment's venueId for refund API call (NOT auth context's venue!)
                        // The refund MUST be recorded to the same venue as the original payment
                        set("paymentVenueId", payment.venueId)
                        // AngelPay transaction reference for post-operations auto-refund.
                        set("referenceNumber", payment.referenceNumber ?: "")
                    }
                    navController.navigate(NavRoute.RefundConfirmation.createRoute(payment.id))
                }
            )
        }

        // Refund Confirmation Screen - Review refund before processing
        composable(
            route = NavRoute.RefundConfirmation.route,
            arguments = listOf(navArgument("paymentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val paymentId = backStackEntry.arguments?.getString("paymentId") ?: ""

            // Retrieve payment data from savedStateHandle (stored in PaymentsScreen)
            val previousBackStack = navController.previousBackStackEntry
            val paymentHandle = previousBackStack?.savedStateHandle

            // Parse data from savedStateHandle
            val orderNumber = paymentHandle?.get<String>("orderNumber")
            val originalAmount = paymentHandle?.get<String>("originalAmount")?.toBigDecimalOrNull()
                ?: java.math.BigDecimal.ZERO
            val originalTipAmount = paymentHandle?.get<String>("originalTipAmount")?.toBigDecimalOrNull()
                ?: java.math.BigDecimal.ZERO
            val paymentMethod = paymentHandle?.get<String>("paymentMethod") ?: "Tarjeta"
            val createdAtStr = paymentHandle?.get<String>("createdAt")
            val createdAt = createdAtStr?.let {
                try { Instant.parse(it) } catch (e: Exception) { Instant.now() }
            } ?: Instant.now()
            val merchantAccountId = paymentHandle?.get<String>("merchantAccountId") ?: ""
            val blumonSerialNumber = paymentHandle?.get<String>("blumonSerialNumber") ?: ""
            val refundedAmount = paymentHandle?.get<String>("refundedAmount")?.toBigDecimalOrNull()
                ?: java.math.BigDecimal.ZERO
            val orderId = paymentHandle?.get<String>("orderId")
            // 🎫 CRITICAL: Blumon operation number for CancelIcc (from webhook, NOT SDK referenceNumber!)
            val blumonOperationNumber = paymentHandle?.get<Int>("blumonOperationNumber") ?: 0
            // 🏢 CRITICAL: Payment's venueId for refund API call (NOT auth context's venue!)
            val paymentVenueId = paymentHandle?.get<String>("paymentVenueId") ?: ""
            val refundScope = rememberCoroutineScope()
            var isNexgoRefundProcessing by remember(paymentId) { mutableStateOf(false) }

            RefundConfirmationScreen(
                paymentId = paymentId,
                orderNumber = orderNumber,
                originalAmount = originalAmount,
                originalTipAmount = originalTipAmount,
                paymentMethod = paymentMethod,
                createdAt = createdAt,
                merchantAccountId = merchantAccountId,
                blumonSerialNumber = blumonSerialNumber,
                refundedAmount = refundedAmount,
                isProcessing = isNexgoRefundProcessing,
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onConfirmRefund = { refundAmount, refundReason, tipRefundCents ->
                    // 💸 Navigate to PaymentScreen with refund context
                    // Store refund data for PaymentScreen to process
                    Timber.i("💸 [Refund] Confirming refund: $refundAmount for payment: $paymentId, reason: $refundReason, tipRefundCents: $tipRefundCents")

                    // Nexgo/AngelPay refund flow (no extra transactions screen).
                    if (!BuildConfig.ENABLE_PAX_SDK) {
                        val referenceNumber = paymentHandle?.get<String>("referenceNumber").orEmpty()
                        if (referenceNumber.isBlank()) {
                            Toast.makeText(
                                context,
                                "No se encontró referencia AngelPay para este pago.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@RefundConfirmationScreen
                        }
                        if (isNexgoRefundProcessing) {
                            return@RefundConfirmationScreen
                        }

                        isNexgoRefundProcessing = true
                        refundScope.launch {
                            val result = recordAngelPayRefundUseCase.processSdkRefund(
                                paymentReference = referenceNumber,
                                createdAt = createdAt,
                                requestedReason = refundReason,
                                appContext = context.applicationContext,
                            )

                            result.fold(
                                onSuccess = { message ->
                                    // SDK refund OK → now record in backend so Pagos
                                    // shows it as "Reembolsado" and reports reflect it.
                                    // Best-effort: failure here does NOT undo the SDK
                                    // refund (customer already got the money back),
                                    // we just toast a warning so the operator knows
                                    // to follow up manually.
                                    val backendResult = recordAngelPayRefundUseCase.recordInBackend(
                                        paymentId = paymentId,
                                        orderId = orderId,
                                        paymentVenueId = paymentVenueId,
                                        merchantAccountId = merchantAccountId,
                                        originalTotalAmount = originalAmount,
                                        refundAmount = refundAmount,
                                        refundReason = refundReason,
                                        sdkReferenceNumber = referenceNumber,
                                        tipRefundCents = tipRefundCents,
                                        refundedAmount = refundedAmount,
                                    )
                                    isNexgoRefundProcessing = false

                                    backendResult.fold(
                                        onSuccess = {
                                            Timber.i(
                                                "✅ [AngelPay Refund] SDK + backend recorded successfully for payment=%s",
                                                paymentId,
                                            )
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                            // Force Pagos to refresh so the "Reembolsado" badge shows up.
                                            navController.previousBackStackEntry
                                                ?.savedStateHandle
                                                ?.set("refreshAfterRefund", true)
                                            navController.safePopBackStack()
                                        },
                                        onFailure = { backendError ->
                                            Timber.e(
                                                backendError,
                                                "⚠️ [AngelPay Refund] SDK refund OK but backend recording FAILED for payment=%s",
                                                paymentId,
                                            )
                                            Toast.makeText(
                                                context,
                                                "$message\n⚠️ Backend no registró el reembolso — contacta soporte.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                            // Still pop back — the SDK cancellation
                                            // already returned money to the customer.
                                            navController.safePopBackStack()
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    isNexgoRefundProcessing = false
                                    val errorMessage = error.message ?: "No se pudo procesar el reembolso AngelPay"
                                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        return@RefundConfirmationScreen
                    }

                    refundScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Refund payment")) {
                            return@launch
                        }

                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            // Refund mode flag
                            set("isRefundMode", true)

                            // Refund-specific data
                            set("refundAmount", refundAmount.toString())
                            set("refundReason", refundReason.name)
                            set("originalPaymentId", paymentId)
                            set("originalOrderId", orderId)
                            set("originalTotalAmount", originalAmount.toString())
                            set("originalTipAmount", originalTipAmount.toString())
                            // Explicit tip-split override (null = backend default proportional)
                            set("tipRefundCents", tipRefundCents)

                            // Merchant data (CRITICAL for multi-merchant refunds)
                            set("merchantAccountId", merchantAccountId)
                            set("blumonSerialNumber", blumonSerialNumber)
                            // 🎫 CRITICAL: Blumon operation number for CancelIcc refunds (from webhook)
                            set("blumonOperationNumber", blumonOperationNumber)
                            // 🏢 CRITICAL: Payment's venueId for refund API call (NOT auth context's venue!)
                            // The refund MUST be recorded to the same venue as the original payment
                            set("paymentVenueId", paymentVenueId)
                        }

                        // Navigate to PaymentScreen (will detect refund mode and call startRefund)
                        navController.navigate(NavRoute.Payment.route)
                    }
                }
            )
        }

        // Support Screen - Help and support resources
        composable(NavRoute.Support.route) {
            com.jaac.avoqado_tpv.features.support.presentation.SupportScreen(
                onBack = {
                    navController.safePopBackStack()
                }
            )
        }

        // Messages Screen - Full message inbox with filters and pagination
        composable(NavRoute.Messages.route) {
            com.jaac.avoqado_tpv.features.messaging.presentation.MessagesScreen(
                viewModel = tpvMessageViewModel,
                onBack = {
                    navController.safePopBackStack()
                },
                onMessageClick = { tpvMessageViewModel.selectMessage(it) }
            )
        }

        // Training List Screen - Available training modules
        composable(NavRoute.Trainings.route) {
            com.jaac.avoqado_tpv.features.training.presentation.TrainingsListScreen(
                viewModel = trainingViewModel,
                onBack = {
                    navController.safePopBackStack()
                },
                onTrainingClick = { trainingId ->
                    navController.navigate(NavRoute.TrainingViewer.createRoute(trainingId))
                }
            )
        }

        // 📸 Pending Verifications - Non-blocking proof-of-sale upload
        composable("pending_verifications") {
            com.jaac.avoqado_tpv.features.payment.presentation.PendingVerificationsScreen(
                navController = navController
            )
        }

        // Training Step Viewer - Step-by-step training with media + quiz
        composable(
            NavRoute.TrainingViewer.route,
            arguments = listOf(
                androidx.navigation.navArgument("trainingId") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { backStackEntry ->
            val trainingId = backStackEntry.arguments?.getString("trainingId") ?: return@composable
            com.jaac.avoqado_tpv.features.training.presentation.TrainingStepViewer(
                viewModel = trainingViewModel,
                trainingId = trainingId,
                onBack = {
                    navController.safePopBackStack()
                }
            )
        }

        // Self-Update Screen - Check and install app updates via Blumon SDK
        composable(NavRoute.SelfUpdate.route) {
            if (BuildConfig.ENABLE_PAX_SDK) {
                com.jaac.avoqado_tpv.features.self_update.presentation.SelfUpdateScreen(
                    onNavigateBack = {
                        navController.safePopBackStack()
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Self-update no disponible en tutorialEmu",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // Split by Product Screen - Select specific products to pay
        composable(
            route = NavRoute.SplitByProduct.route,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
            val splitByProductScope = rememberCoroutineScope()

            SplitByProductScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onProceedToPayment = { amount, productIds ->
                    splitByProductScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Split by product payment")) {
                            return@launch
                        }
                        // 💳 Read wasPayLaterOrder from previous screen and forward to Payment
                        val wasPayLaterOrder = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("wasPayLaterOrder") ?: false

                        // Navigate to PaymentScreen with PERPRODUCT split params
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("initialAmount", amount.toString())
                            set("orderId", orderId)
                            set("splitType", SplitType.PERPRODUCT.value)
                            set("paidProductIds", productIds)
                            // 💳 Forward pay-later context
                            set("wasPayLaterOrder", wasPayLaterOrder)
                        }
                        navController.navigate(NavRoute.Payment.route)
                        Timber.d("💳 Split PERPRODUCT: ${productIds.size} products, amount=$amount | wasPayLater=$wasPayLaterOrder")
                    }
                }
            )
        }

        // Split by Person Screen - Split order equally among N people
        composable(
            route = NavRoute.SplitByPerson.route,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
            val splitByPersonScope = rememberCoroutineScope()

            SplitByPersonScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onProceedToPayment = { amount, partySize, payingFor ->
                    splitByPersonScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Split by person payment")) {
                            return@launch
                        }
                        // 💳 Read wasPayLaterOrder from previous screen and forward to Payment
                        val wasPayLaterOrder = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("wasPayLaterOrder") ?: false

                        // Navigate to PaymentScreen with EQUALPARTS split params
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("initialAmount", amount.toString())
                            set("orderId", orderId)
                            set("splitType", SplitType.EQUALPARTS.value)
                            set("equalPartsPartySize", partySize)
                            set("equalPartsPayedFor", payingFor)
                            // 💳 Forward pay-later context
                            set("wasPayLaterOrder", wasPayLaterOrder)
                        }
                        navController.navigate(NavRoute.Payment.route)
                        Timber.d("💳 Split EQUALPARTS: $payingFor/$partySize parts, amount=$amount | wasPayLater=$wasPayLaterOrder")
                    }
                }
            )
        }

        // Timeclock Screen - Employee clock in/out and breaks
        composable(
            route = NavRoute.Timeclock.route,
            arguments = listOf(
                navArgument("venueId") { type = NavType.StringType },
                navArgument("pin") { type = NavType.StringType },
                navArgument("fromHome") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("autoAction") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val context = LocalContext.current
            val timeclockCoroutineScope = rememberCoroutineScope()
            val fromHome = backStackEntry.arguments?.getBoolean("fromHome") ?: false

            TimeclockScreen(
                onNavigateBack = {
                    if (fromHome) {
                        // Coming from WelcomeScreen — just pop back (session already active)
                        Timber.d("🚪 Timeclock back pressed (fromHome) - Popping back to WelcomeScreen")
                        navController.getBackStackEntry(NavRoute.Home.route)
                            .savedStateHandle["refreshAttendance"] = true
                        navController.safePopBackStack()
                    } else {
                        // Coming from LoginScreen — clear session when going back
                        Timber.d("🚪 Timeclock back pressed - Clearing session")
                        secureStorage.clearSession()
                        navController.safePopBackStack()
                    }
                },
                onNavigateToLogin = {
                    if (fromHome) {
                        // Coming from WelcomeScreen — just pop back
                        Timber.d("🚪 Timeclock → back to WelcomeScreen (fromHome)")
                        navController.getBackStackEntry(NavRoute.Home.route)
                            .savedStateHandle["refreshAttendance"] = true
                        navController.safePopBackStack()
                    } else {
                        // After timeclock action, navigate back to login (session already cleared)
                        navController.navigate(NavRoute.Login.route) {
                            popUpTo(NavRoute.Timeclock.route) { inclusive = true }
                        }
                    }
                },
                onAutoLogin = {
                    if (fromHome) {
                        // Coming from WelcomeScreen — just pop back (user already logged in)
                        Timber.d("🔑 Timeclock done (fromHome) - Popping back to WelcomeScreen")
                        navController.getBackStackEntry(NavRoute.Home.route)
                            .savedStateHandle["refreshAttendance"] = true
                        navController.safePopBackStack()
                    } else {
                        // Automatic login after timeclock action (Done button)
                        // Session already saved during PIN verification, just start workers
                        Timber.d("🔑 Auto-login after timeclock - Starting heartbeat")
                        HeartbeatScheduler.start(context)

                        // Start payment sync worker (offline payment queue)
                        Timber.d("💾 Auto-login after timeclock - Starting payment sync")
                        PaymentSyncScheduler.start(context)

                        // 📍 Cambaceo: hourly promoter location ping (self-gated by venue flag + 11-18h window)
                        PromoterLocationScheduler.start(context)

                        // 📦 FIX: Ensure modules are loaded before navigating to Home
                        // Without this, WelcomeScreen shows wrong UI (full instead of simplified)
                        // because the modules StateFlow might be empty when coming from Timeclock
                        timeclockCoroutineScope.launch {
                            Timber.d("📦 Auto-login: Fetching modules before navigating to Home")
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                modulesRepository.fetchAndCache()
                            }

                            // Navigate to home (must be on Main thread)
                            navController.navigate(NavRoute.Home.route) {
                                popUpTo(NavRoute.Timeclock.route) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        // 📱 Serialized Sale Screen - Quick sell flow for serialized items (SIMs, etc.)
        composable(NavRoute.SerializedSale.route) { backStackEntry ->
            val shouldReset = backStackEntry.savedStateHandle.get<Boolean>("resetSerializedSale") == true
            val serializedPaymentScope = rememberCoroutineScope()
            if (shouldReset) {
                backStackEntry.savedStateHandle.remove<Boolean>("resetSerializedSale")
            }
            SerializedSaleScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onNavigateToPayment = { orderId, orderNumber, orderTotal, isPortabilidad, serialNumber, categoryName ->
                    serializedPaymentScope.launch {
                        if (!awaitPaxPaymentReady(context, initializationManager, "Serialized sale payment")) {
                            return@launch
                        }
                        // Navigate to PaymentScreen with order details
                        // ⚠️ SERIALIZED SALE: Order was created by backend quick-sell API
                        // and doesn't exist locally. Pass skipLocalOrderValidation flag
                        // so PaymentViewModel skips the local order lookup.
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("initialAmount", orderTotal.toString())
                            set("orderId", orderId)
                            set("orderNumber", orderNumber)  // 🆕 Pass order number for receipt FOLIO
                            set("skipReview", true)  // Skip tip/review for serialized sales
                            set("skipLocalOrderValidation", true)  // 🆕 Order exists only on backend
                            set("isPortabilidad", isPortabilidad)  // Portabilidad: 1 vs 2 proof-of-sale photos
                            set("serialNumber", serialNumber)  // 📱 ICCID/serial for receipt
                            set("categoryName", categoryName)  // 📱 Category name for receipt
                        }
                        // 📱 Serialized (SIM) sales skip pre-payment verification on
                        // BOTH hardware paths — Blumon does this too (see
                        // `submitAmountDirectToMerchant` in PaymentScreen.kt, driven by
                        // the same `skipReview` flag above). Proof-of-sale photos are
                        // captured AFTER the charge, on the success screen, not here.
                        navController.navigate(getPaymentRoute())
                        Timber.d("💳 Serialized sale → payment | order $orderId (#$orderNumber), serial=$serialNumber, portabilidad=$isPortabilidad, nexgo=${isAppToAppPayment()}")
                    }
                },
                resetOnEnter = shouldReset,
                onNavigateToMisSims = {
                    // Plan §3.3 — deep-link when backend returns SIM_NOT_ACCEPTED
                    navController.navigate(NavRoute.MisSims.route)
                }
            )
        }

        // 📦 Serialized Inventory Register Screen - Batch registration of items
        composable(NavRoute.SerializedInventoryRegister.route) {
            SerializedInventoryScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                }
            )
        }

        // 📊 My Sales Screen - Promoter's serialized item sales history
        composable(NavRoute.MySales.route) {
            MySalesScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                },
                onNavigateToCorrection = { verificationId ->
                    navController.navigate(NavRoute.SaleCorrection.createRoute(verificationId))
                }
            )
        }

        // 💰 My Commissions Screen - Promoter's cash-out balance + same-day withdrawal
        composable(NavRoute.MyCommissions.route) {
            com.jaac.avoqado_tpv.features.cash_out.presentation.MyCommissionsScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        // 🛠️ Sale Correction Screen - Re-upload rejected documentation (Mis Ventas → Revisar)
        composable(
            route = NavRoute.SaleCorrection.route,
            arguments = listOf(navArgument("verificationId") { type = NavType.StringType }),
        ) {
            com.jaac.avoqado_tpv.features.payment.presentation.SaleCorrectionScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                }
            )
        }

        // 📱 Mis SIMs — promoter's SIM custody inbox (PlayTelecom chain-of-custody, plan §3)
        composable(NavRoute.MisSims.route) {
            com.jaac.avoqado_tpv.features.sim_custody.presentation.MisSimsScreen(
                onBack = { navController.safePopBackStack() },
                onNavigateToCorrection = { verificationId ->
                    // SOLD SIM with rejected documentation → reuse the sale-correction flow
                    navController.navigate(NavRoute.SaleCorrection.createRoute(verificationId))
                },
            )
        }

        // 🥝 KIOSK SUCCESS SCREEN (accessible from staff navigation after kiosk payment)
        // This route is also in KioskNavigation, but we need it here for payment flow
        composable(
            route = NavRoute.KioskSuccess.route,
            arguments = listOf(
                navArgument("orderNumber") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val displayOrderNumber = backStackEntry.arguments?.getString("orderNumber") ?: ""

            com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskSuccessScreen(
                orderNumber = displayOrderNumber,
                onTimeout = {
                    // Clear cart and navigate to KioskWelcome
                    Timber.i("🥝 [KIOSK] Success timeout - returning to welcome")
                    navController.navigate(NavRoute.KioskWelcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // 🥝 KIOSK WELCOME SCREEN (for kiosk mode reset after success)
        composable(NavRoute.KioskWelcome.route) {
            // When we reach here, kiosk mode should be active, so show the kiosk welcome
            com.jaac.avoqado_tpv.features.kiosk.presentation.screens.KioskWelcomeScreen(
                onStart = {
                    navController.navigate(NavRoute.KioskMenu.route)
                },
                onExitKiosk = {
                    kioskModeManager.exitKioskMode()
                    navController.navigate(NavRoute.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // 🔶 ANGELPAY Payment Screen — SDK embebido con fallback app-to-app en Nexgo
        // COMPLETELY ISOLATED from Blumon PaymentScreen (separate ViewModel, state, route)
        composable(NavRoute.AngelPayPayment.route) {
            val initialAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("initialAmount")
            val orderId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("orderId")
            val orderNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("orderNumber")
            // ENTRY POINT — matches Blumon PaymentScreen pattern. When Cobrar
            // (unified Checkout) drove us here, the "Nuevo Cobro" success
            // button must return to a fresh CheckoutScreen, not pop to the
            // legacy FastPaymentEntry/MenuScreen.
            val entryPoint = navController.previousBackStackEntry?.savedStateHandle?.get<String>("entryPoint")
            val cameFromCheckout = entryPoint == "checkout"

            // 📸 Serialized inventory (SIM) sale — set by SerializedSaleScreen's
            // onNavigateToPayment. Null/false for a normal AngelPay charge.
            val serialNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("serialNumber")
            val isPortabilidad = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("isPortabilidad") ?: false
            val skipReview = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("skipReview") ?: false

            com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentScreen(
                initialAmount = initialAmount,
                orderId = orderId,
                orderNumber = orderNumber,
                serialNumber = serialNumber,
                isPortabilidad = isPortabilidad,
                skipReview = skipReview,
                onNavigateBack = {
                    val popped = navController.safePopBackStack()
                    if (!popped) {
                        navController.safePopBackStack(NavRoute.Home.route, inclusive = false)
                    }
                },
                onNavigateHome = {
                    val popped = navController.safePopBackStack(NavRoute.Home.route, inclusive = false)
                    if (!popped) {
                        navController.navigate(NavRoute.Home.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onViewInPayments = { paymentId ->
                    // Deep-link: open Pagos with this payment's detail
                    // bottom sheet auto-opened. Pops to Home first so the
                    // backstack stays shallow (otherwise: Cobrar →
                    // Success → Pagos → back-back-back to Cobrar).
                    navController.navigate(
                        NavRoute.Payments.createRoute(autoOpenPaymentId = paymentId)
                    ) {
                        popUpTo(NavRoute.Home.route) { inclusive = false }
                    }
                },
                onNavigateToShifts = {
                    navController.navigate(NavRoute.Shifts.route)
                },
                onStartNewPaymentOverride = if (cameFromCheckout) {
                    {
                        // 🛒 Return to unified Cobrar with a fresh CheckoutViewModel.
                        // popUpTo(Home, inclusive=false) + navigate(Checkout) gives
                        // us a brand new backstack entry → fresh VM → empty cart,
                        // matching the Blumon PaymentScreen Cobrar path.
                        navController.navigate(NavRoute.Checkout.route) {
                            popUpTo(NavRoute.Home.route) { inclusive = false }
                        }
                        Timber.d("🛒 [AngelPay] Nuevo Cobro: returning to unified Checkout")
                    }
                } else null,
            )
        }

        composable(
            route = NavRoute.PaymentTransactions.route,
            arguments = listOf(
                navArgument("processorType") { type = NavType.StringType },
                navArgument("autoRefundReference") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            com.jaac.avoqado_tpv.features.payment.presentation.transactions.PaymentTransactionsScreen(
                onNavigateBack = {
                    navController.safePopBackStack()
                }
            )
        }
    }

            // 🔄 SESSION EXPIRING OVERLAY
            // Shows loading overlay when session is being verified/refreshed
            // Prevents jarring transition from current screen to Login
            if (isSessionExpiring) {
                SessionExpiringOverlay()
            }

            // 📥 UPDATE REQUEST DIALOG (Remote update command from dashboard)
            // Shows dialog when dashboard sends REQUEST_UPDATE command
            // User can accept (download + install) or dismiss
            if (BuildConfig.ENABLE_PAX_SDK && updateRequestManager != null && updateRequestState !is UpdateRequestState.Idle) {
                UpdateRequestDialog(
                    state = updateRequestState,
                    onAccept = {
                        Timber.i("📥 [AppNavigation] User accepted update request")
                        updateCoroutineScope.launch {
                            updateRequestManager.acceptUpdate()
                        }
                    },
                    onDismiss = {
                        Timber.i("📥 [AppNavigation] User dismissed update request")
                        updateRequestManager.resetState()
                    },
                    onRetry = {
                        Timber.i("📥 [AppNavigation] User retrying update")
                        updateCoroutineScope.launch {
                            updateRequestManager.acceptUpdate()
                        }
                    }
                )
            }

            // 📨 TPV MESSAGE DIALOG (Dashboard → Terminal messaging)
            // Shows announcements, surveys, and action messages from dashboard
            tpvMessageState.currentMessage?.let { message ->
                TpvMessageDialog(
                    message = message,
                    selectedSurveyOptions = tpvMessageState.selectedSurveyOptions,
                    isSubmitting = tpvMessageState.isSubmitting,
                    onAcknowledge = { tpvMessageViewModel.acknowledgeMessage() },
                    onDismiss = { tpvMessageViewModel.dismissMessage() },
                    onToggleSurveyOption = { tpvMessageViewModel.toggleSurveyOption(it) },
                    onSubmitSurvey = { tpvMessageViewModel.submitSurveyResponse() },
                    onExecuteAction = { tpvMessageViewModel.executeAction() }
                )
            }
        }  // Close Box
    }  // Close Column

    // 🔒 FORCE UPDATE DIALOG (Global overlay - shown AFTER navigation content)
    // Dialog overlays on top of everything, blocking interaction until update
    // Placed here so navController has all routes available
    // Don't show on SelfUpdate screen (user is already updating)
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val isOnSelfUpdateScreen = currentRoute == NavRoute.SelfUpdate.route

    if (BuildConfig.ENABLE_PAX_SDK && forceUpdateAlert != null && !isOnSelfUpdateScreen) {
        Timber.i("🔒 [ForceUpdate] Showing force update dialog for ${forceUpdateAlert.versionName}")
        com.jaac.avoqado_tpv.core.presentation.components.ForceUpdateDialog(
            update = forceUpdateAlert,
            onUpdate = {
                Timber.i("🔒 [ForceUpdate] User clicked update - navigating to SelfUpdate")
                // If in kiosk mode, exit first then navigate
                if (isKioskMode) {
                    kioskModeManager.exitKioskMode()
                }
                navController.navigate(NavRoute.SelfUpdate.route)
            }
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
    modulesRepository: com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository,
    onNavigateToActivation: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    LaunchedEffect(Unit) {
        // ✅ Square/Toast Pattern: Check activation status with BACKEND first
        // This prevents routing to LoginScreen when terminal has venueId locally
        // but activatedAt = NULL on backend (happens after DB reset)

        // 🚀 Optimization: Run network call on IO thread to prevent freezing Splash animation
        // Previously caused ~2.6s UI freeze (160 skipped frames)
        val backendStatusResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            deviceInfoManager.checkActivationStatusWithBackend()
        }

        when (backendStatusResult) {
            is com.jaac.avoqado_tpv.core.domain.models.Result.Success -> {
                val status = backendStatusResult.data

                if (!status.isActivated) {
                    // Backend says not activated → force re-activation
                    Timber.w("🔐 Backend reports not activated - navigating to activation")
                    onNavigateToActivation()
                } else {
                    // ✅ Backend confirms activation → fetch modules BEFORE navigating
                    Timber.d("✅ Backend confirms activation - fetching modules")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        modulesRepository.fetchAndCache()
                    }

                    // Now check session and navigate
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
                    // Trust local venueId when offline - try to fetch modules anyway
                    Timber.d("📱 Offline mode - trusting local venueId, trying to fetch modules")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        modulesRepository.fetchAndCache()
                    }

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
            .background(MaterialTheme.colorScheme.surfaceVariant),
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

        // Version text at the bottom
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

/**
 * Session Expiring Overlay
 *
 * Full-screen overlay shown when session is being verified/refreshed.
 * This provides visual feedback during the 401 → refresh → login flow.
 *
 * **Design:**
 * - Semi-transparent dark background (blocks user interaction)
 * - Card with loading indicator and message
 * - Message: "Verificando sesión..." (user-friendly)
 *
 * **UX Improvement:**
 * Before: User sees abrupt transition from any screen to Login
 * After: User sees "Verificando sesión..." → Login screen appears
 */
@Composable
private fun SessionExpiringOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),  // Semi-transparent overlay
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Card(
            modifier = Modifier
                .padding(32.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                defaultElevation = 8.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Verificando sesión...",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}


private fun clearPaymentArgs(handle: SavedStateHandle) {
    handle.remove<String>("orderId")
    handle.remove<String>("orderNumber")
    handle.remove<String>("tableId")
    handle.remove<Boolean>("skipLocalOrderValidation")

    handle.remove<String>("splitType")
    handle.remove<Int>("equalPartsPartySize")
    handle.remove<Int>("equalPartsPayedFor")
    handle.remove<List<String>>("paidProductIds")

    handle.remove<Boolean>("isRefundMode")
    handle.remove<String>("refundAmount")
    handle.remove<String>("refundReason")
    handle.remove<String>("originalPaymentId")
    handle.remove<String>("originalOrderId")
    handle.remove<String>("originalTotalAmount")
    handle.remove<String>("originalTipAmount")
    handle.remove<String>("merchantAccountId")
    handle.remove<String>("blumonSerialNumber")
    handle.remove<Int>("blumonOperationNumber")
    handle.remove<String>("paymentVenueId")

    handle.remove<Boolean>("wasPayLaterOrder")
    handle.remove<Int>("payLaterOrdersCount")
    handle.remove<Boolean>("isKioskPayment")

    handle.remove<Long>("externalTipCents")
    handle.remove<Int>("externalRating")
    handle.remove<Boolean>("externalSkipReview")
    handle.remove<String>("paymentSource")
    handle.remove<String>("socketRequestId")
    handle.remove<String>("socketProcessedByStaffId")

    // 🧹 Critical: also clear inputs read by Payment screen on next manual nav.
    // Without these, a cancelled socket request leaves $50 amount + skipReview=true
    // pegado in Home savedStateHandle → next "Cobro Rápido" inherits stale values.
    handle.remove<String>("initialAmount")
    handle.remove<Boolean>("skipReview")
}

private fun formatAmountFromCents(amountCents: Long): String {
    return BigDecimal(amountCents)
        .movePointLeft(2)
        .setScale(2, RoundingMode.HALF_UP)
        .toPlainString()
}

private suspend fun awaitPaxPaymentReady(
    context: Context,
    initializationManager: InitializationManager,
    source: String,
    showPreparingToast: Boolean = true
): Boolean {
    if (!BuildConfig.ENABLE_PAX_SDK) {
        return true
    }

    if (initializationManager.isInitialized.value) {
        return true
    }

    if (showPreparingToast) {
        Toast.makeText(
            context,
            "Preparando sistema de pagos. Espera un momento.",
            Toast.LENGTH_SHORT
        ).show()
    }

    val result = withContext(Dispatchers.IO) {
        initializationManager.awaitInitialization()
    }

    if (result.isSuccess && initializationManager.isInitialized.value) {
        return true
    }

    Timber.e(
        result.exceptionOrNull(),
        "❌ [$source] Blumon SDK not ready; blocking payment navigation"
    )
    Toast.makeText(
        context,
        "Sistema de pagos no inicializado. Toca Reintentar o reinicia la terminal.",
        Toast.LENGTH_LONG
    ).show()
    return false
}

/**
 * Check if this build uses the Nexgo payment route (AngelPay flow) instead of Blumon route.
 * Determined by BuildConfig.ENABLE_PAX_SDK which is set per Gradle flavor:
 * - sandbox/production → true (Blumon SDK)
 * - nexgo → false (AngelPay flow route)
 * - tutorialEmu → false (no payment processing)
 */
private fun isAppToAppPayment(): Boolean = !com.jaac.avoqado_tpv.BuildConfig.ENABLE_PAX_SDK

/**
 * Get the appropriate payment route based on build flavor.
 * - ENABLE_PAX_SDK=false (nexgo) → AngelPayPayment (SDK/fallback flow)
 * - ENABLE_PAX_SDK=true (sandbox/production) → Payment (Blumon SDK)
 */
private fun getPaymentRoute(): String {
    return if (isAppToAppPayment()) NavRoute.AngelPayPayment.route else NavRoute.Payment.route
}

