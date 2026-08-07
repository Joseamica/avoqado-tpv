package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.angelpay.angelpaysdk.AngelPayPaymentContract
import com.angelpay.angelpaysdk.models.PaymentResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoBrandLoader
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.util.ForegroundRecoveryGate
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
import com.jaac.avoqado_tpv.features.payment.presentation.MerchantSelectionContent
import com.jaac.avoqado_tpv.features.payment.presentation.components.CryptoPaymentLoadingScreen
import com.jaac.avoqado_tpv.features.payment.presentation.components.CryptoPaymentQrScreen
import com.jaac.avoqado_tpv.features.payment.presentation.ReviewScreen
import com.jaac.avoqado_tpv.features.payment.presentation.TipScreen
import com.jaac.avoqado_tpv.features.payment.presentation.components.PaymentApprovedScreen
import com.jaac.avoqado_tpv.features.payment.presentation.watchdogMessage
import timber.log.Timber

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

/**
 * AngelPay Payment Screen — full pre-payment UX matching Blumon flow.
 *
 * **COMPLETELY ISOLATED from Blumon PaymentScreen.**
 * Uses its own ViewModel (AngelPayPaymentViewModel) and state machine.
 *
 * **Flow:**
 * Idle → [Rating] → [Tip] → Merchant Selection → Card/Cash → Success
 *
 * Reuses: ReviewScreen, TipScreen, MerchantSelectionContent, PaymentApprovedScreen.
 */
@Composable
fun AngelPayPaymentScreen(
    initialAmount: String?,
    orderId: String? = null,
    orderNumber: String? = null,
    // 📸 Serialized inventory (SIM) sale — all default off; a normal payment passes nothing.
    // Proof-of-sale photos are NOT captured here — they're taken AFTER the charge, on
    // the success screen (mirrors Blumon's post-payment "Vinculación" flow exactly).
    serialNumber: String? = null,
    isPortabilidad: Boolean = false,
    skipReview: Boolean = false,
    // 📡 POS→TPV terminal arbitration: set only when this charge was initiated by the POS over
    // Socket.IO (source "SOCKET" + the request id the caller long-polls on). Null for a normal
    // device-initiated charge. Passed straight to the VM so the terminal outcome reports back.
    paymentSource: String? = null,
    socketRequestId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /**
     * Deep-link to the unified Pagos list with the just-made payment's
     * detail bottom sheet auto-opened. Triggered from the success screen's
     * "Ver en Pagos" button. Receives the recorded paymentId from the
     * success state's receipt (null-safe — button hides if no paymentId).
     */
    onViewInPayments: (paymentId: String) -> Unit = {},
    onNavigateToShifts: () -> Unit = {},
    /**
     * Optional override for the "Nuevo Cobro" success-screen button. When
     * null (default), the button pops back to wherever brought us here
     * (legacy Pago Rápido / Órdenes flow). When set, takes over to route
     * the operator to the unified Cobrar Checkout — used by the Cobrar
     * flow so the success screen returns to the new cart with a fresh
     * `CheckoutViewModel` instead of the legacy entry points.
     */
    onStartNewPaymentOverride: (() -> Unit)? = null,
    viewModel: AngelPayPaymentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val currentMerchant by viewModel.currentMerchant.collectAsStateWithLifecycle()
    // 🧭 MERCHANT_ROUTING_RULES: eligibility for the charge in progress (drives filter + banner)
    val merchantRouting by viewModel.merchantRouting.collectAsStateWithLifecycle()
    val isSendingReceipt by viewModel.isSendingReceipt.collectAsStateWithLifecycle()
    val sendReceiptMessage by viewModel.sendReceiptMessage.collectAsStateWithLifecycle()

    // Task 34 — D2 / D6 banner + switcher state (spec §6.8, §18.1)
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val activeAngelPayMerchantId by viewModel.activeAngelPayMerchantId.collectAsStateWithLifecycle()
    val cachedMerchants by viewModel.cachedMerchants.collectAsStateWithLifecycle(initialValue = emptyList())
    val inFlightSwitch by viewModel.inFlightSwitch.collectAsStateWithLifecycle()
    val selectionInProgress by viewModel.selectionInProgress.collectAsStateWithLifecycle()
    val activeMerchantName = cachedMerchants.firstOrNull { it.id == activeAngelPayMerchantId }?.name

    // Toast for receipt send result
    val context = LocalContext.current
    LaunchedEffect(sendReceiptMessage) {
        sendReceiptMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearSendReceiptMessage()
        }
    }

    // ActivityResultLauncher for AngelPay intent
    val angelPayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.d("🔶 [AngelPay] ActivityResult received | resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            ForegroundRecoveryGate.arm(
                durationMs = 4500L,
                reason = "angelpay_app_to_app_result_ok",
            )
        }
        viewModel.onAngelPayResult(result.resultCode, result.data)
    }
    val angelPaySdkLauncher = rememberLauncherForActivityResult(
        contract = AngelPayPaymentContract(),
    ) { result: PaymentResult ->
        Timber.d("🔶 [AngelPay SDK] ActivityResult received | approved=${result.approved}")
        if (result.approved) {
            ForegroundRecoveryGate.arm(
                durationMs = 4500L,
                reason = "angelpay_sdk_approved",
            )
        }
        viewModel.onAngelPaySdkResult(result)
    }

    // 📡 POS→TPV arbitration: tag the source UNCONDITIONALLY, keyed on the values themselves —
    // exactly the Blumon pattern (PaymentScreen.kt). It used to live inside the
    // `LaunchedEffect(initialAmount) { if (... && state is Idle) }` block below, which made the
    // tag hostage to that block's key AND its Idle guard. When the AngelPay SDK Activity holds the
    // foreground, Android can destroy MainActivity; the NavController then restores the
    // savedStateHandle but the VM is NEW with null fields (it has no SavedStateHandle — see the
    // VM's own backing). If the SDK result lands first, state != Idle, the gated block never
    // re-runs, and the recorded payment carries terminalPaymentRequestId = null while the socket
    // result is never emitted → the server's arbitration row says CANCELLED/FAILED for a charge
    // whose money actually moved, and the 🚨 reconcile alert never fires.
    // Keyed on (paymentSource, socketRequestId) so it re-tags on every recomposition/recreation,
    // regardless of payment state. The VM-side SavedStateHandle backing is the other half.
    LaunchedEffect(paymentSource, socketRequestId) {
        viewModel.setSocketPaymentSource(paymentSource, socketRequestId)
    }

    // Auto-start payment when screen opens with amount
    LaunchedEffect(initialAmount) {
        if (initialAmount != null && state is AngelPayPaymentState.Idle) {
            // 📸 Serialized inventory (SIM) proof-of-sale — no-op for a normal payment
            // (all args default off), so this doesn't change the normal charge flow.
            viewModel.setSerializedSaleInfo(
                serialNumber = serialNumber,
                isPortabilidad = isPortabilidad,
            )
            viewModel.initPayment(
                amount = initialAmount,
                orderId = orderId,
                orderNumber = orderNumber,
                skipReview = skipReview,
            )
        }
    }

    // Launch AngelPay when intent is ready
    LaunchedEffect(state) {
        val currentState = state
        when (currentState) {
            is AngelPayPaymentState.LaunchingAngelPaySdk -> {
                try {
                    angelPaySdkLauncher.launch(currentState.request)
                    viewModel.onIntentLaunched()
                } catch (e: Exception) {
                    Timber.e(e, "🔶 [AngelPay SDK] Failed to launch SDK payment activity")
                    viewModel.onAngelPayResult(Activity.RESULT_CANCELED, null)
                }
            }
            is AngelPayPaymentState.LaunchingAngelPay -> {
                try {
                    angelPayLauncher.launch(currentState.intent)
                    viewModel.onIntentLaunched()
                } catch (e: Exception) {
                    Timber.e(e, "🔶 [AngelPay] Failed to launch AngelPay app")
                    viewModel.onAngelPayResult(Activity.RESULT_CANCELED, null)
                }
            }
            else -> Unit
        }
    }

    // Track whether to show success animation or success content
    var showApprovedAnimation by remember { mutableStateOf(false) }
    var showSuccessContent by remember { mutableStateOf(false) }

    // Trigger approved animation when entering Success state
    LaunchedEffect(state) {
        if (state is AngelPayPaymentState.Success && !showSuccessContent) {
            showApprovedAnimation = true
        }
    }

    // F-1 (spec §4.2): card charged, backend record still queued for offline sync.
    // Computed directly from `state` (not a remembered/LaunchedEffect-driven flag like
    // the Success animation above) so the bypass below activates on the SAME
    // composition pass `state` becomes Queued — no transient frame through the
    // Scaffold. See AngelPayPaymentState.Queued kdoc for why this must never be Error.
    val queuedState = state as? AngelPayPaymentState.Queued

    // ── Full-screen overlays (no scaffold) ───────────────────────────
    when {
        // Approved animation overlay
        showApprovedAnimation && !showSuccessContent -> {
            val successState = state as? AngelPayPaymentState.Success
            val formattedTotal = remember(successState?.amount, successState?.tipAmount) {
                val subtotal = successState?.amount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                val tip = successState?.tipAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                "$${String.format(java.util.Locale.US, "%.2f", subtotal.add(tip))}"
            }

            PaymentApprovedScreen(
                amount = formattedTotal,
                onAnimationComplete = {
                    showApprovedAnimation = false
                    showSuccessContent = true
                },
            )
            return
        }

        // Queued content — a SUCCESS with a caveat, not an error. No confetti (this
        // isn't a normal charge): straight to the same full-bleed chrome as a real
        // Success, with an amber "don't recharge" note under the amount. Rendered
        // pre-Scaffold like Success (AngelPaySuccessContent applies its own
        // statusBarsPadding; nesting it inside the Scaffold's paddingValues would
        // double the top inset AND duplicate the Home/Nuevo-Cobro header under the
        // AvoqadoTopBar). Do NOT reuse ErrorContent here — the whole point is no red.
        queuedState != null -> {
            AngelPaySuccessContent(
                state = AngelPayPaymentState.Success(
                    authCode = queuedState.authCode,
                    amount = queuedState.amount,
                    tipAmount = queuedState.tipAmount,
                    receipt = null, // Not recorded yet — QR/print/email/WhatsApp stay hidden.
                    referenceNumber = queuedState.referenceNumber,
                    orderId = queuedState.orderId,
                    orderNumber = queuedState.orderNumber,
                ),
                showReceiptScreen = false,
                onPrintReceipt = {},
                pendingSyncMessage = queuedState.message,
                onNavigateHome = {
                    viewModel.resetPayment()
                    onNavigateHome()
                },
                onStartNewPayment = {
                    viewModel.resetPayment()
                    if (onStartNewPaymentOverride != null) {
                        onStartNewPaymentOverride()
                    } else {
                        onNavigateBack()
                    }
                },
            )
            return
        }

        // Success content (after animation)
        showSuccessContent -> {
            val successState = state as? AngelPayPaymentState.Success
            if (successState != null) {
                val isUploadingProofOfSale by viewModel.isUploadingProofOfSale.collectAsStateWithLifecycle()
                val proofOfSaleComplete by viewModel.proofOfSaleComplete.collectAsStateWithLifecycle()
                AngelPaySuccessContent(
                    state = successState,
                    showReceiptScreen = viewModel.showReceiptScreen,
                    showPrintButton = viewModel.canPrintReceipt,
                    onPrintReceipt = { viewModel.printReceipt(successState.receipt) },
                    onSendReceiptEmail = viewModel::sendReceiptByEmail,
                    onSendReceiptWhatsApp = viewModel::sendReceiptByWhatsApp,
                    isSendingReceipt = isSendingReceipt,
                    // 📸 Serialized (SIM) sale — post-payment proof-of-sale, mirrors Blumon.
                    // isSerializedFlow false for a normal payment → success screen is
                    // byte-identical to before (QR always shown).
                    isSerializedFlow = viewModel.isSerializedSale,
                    isPortabilidad = isPortabilidad,
                    isUploadingProofOfSale = isUploadingProofOfSale,
                    proofOfSaleComplete = proofOfSaleComplete,
                    onProofOfSalePhotoTaken = { photoPath, photoLabel ->
                        val paymentId = successState.receipt?.paymentId
                        if (paymentId != null) {
                            viewModel.uploadProofOfSale(
                                photoPath = photoPath,
                                paymentId = paymentId,
                                orderNumber = successState.orderNumber ?: "",
                                amount = successState.amount,
                                photoLabel = photoLabel,
                            )
                        }
                    },
                    onRetakeProofOfSalePhoto = viewModel::retakeProofOfSalePhoto,
                    onNavigateHome = {
                        viewModel.resetPayment()
                        onNavigateHome()
                    },
                    onViewInPayments = { paymentId ->
                        onViewInPayments(paymentId)
                    },
                    onStartNewPayment = {
                        viewModel.resetPayment()
                        showSuccessContent = false
                        showApprovedAnimation = false
                        // If the caller provided an override (Cobrar flow), use
                        // it so we navigate to the unified Checkout with a
                        // fresh ViewModel. Otherwise fall back to popping the
                        // backstack (legacy behavior).
                        if (onStartNewPaymentOverride != null) {
                            onStartNewPaymentOverride()
                        } else {
                            onNavigateBack()
                        }
                    },
                )
            }
            return
        }
    }

    // ── Top bar title based on state ─────────────────────────────────
    val topBarTitle = when (state) {
        is AngelPayPaymentState.CollectingRating -> "Calificacion"
        is AngelPayPaymentState.CollectingTip -> "Propina"
        is AngelPayPaymentState.SelectingMerchant -> "Metodo de Pago"
        is AngelPayPaymentState.WaitingForResult,
        is AngelPayPaymentState.LaunchingAngelPay,
        is AngelPayPaymentState.LaunchingAngelPaySdk -> "Procesando"
        is AngelPayPaymentState.RecordingPayment,
        is AngelPayPaymentState.ProcessingCash -> "Registrando"
        is AngelPayPaymentState.Error -> "Error"
        is AngelPayPaymentState.Cancelled -> "Cancelado"
        else -> "Cobro AngelPay"
    }

    // Show top bar for pre-payment states and error/cancelled. Queued is excluded
    // alongside Success (fix round 1, minor finding): the overlay bypass above renders it
    // full-bleed with no Scaffold, and the defensive in-Scaffold fallback below supplies
    // its own exit — an AvoqadoTopBar here would show a title that falls through to
    // "Cobro AngelPay" and a back arrow wired to nothing (`onNavigationClick`'s `when`
    // has no Queued branch, so it silently no-ops).
    val showTopBar = state !is AngelPayPaymentState.Success && state !is AngelPayPaymentState.Queued

    // ── Scaffold with AvoqadoTopBar (matching Blumon) ────────────────
    Scaffold(
        topBar = {
            if (showTopBar) {
                AvoqadoTopBar(
                    title = topBarTitle,
                    onNavigationClick = {
                        when (state) {
                            is AngelPayPaymentState.CollectingRating,
                            is AngelPayPaymentState.CollectingTip,
                            is AngelPayPaymentState.SelectingMerchant -> {
                                if (!viewModel.goBackOneStep()) {
                                    onNavigateBack()
                                }
                            }
                            is AngelPayPaymentState.Error,
                            is AngelPayPaymentState.Cancelled,
                            is AngelPayPaymentState.Idle -> {
                                viewModel.resetPayment()
                                onNavigateBack()
                            }
                            else -> { /* No back during processing */ }
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Auth banner + merchant context show ONLY on the "Método de Pago"
            // (SelectingMerchant) step. On the Rating/Tip steps they were
            // confusing and redundant: the cashier picks/switches the merchant on
            // the payment-method step via MerchantSelectionContent's full picker
            // (account selector + per-merchant radios). The quick-switch chip was
            // removed for the same reason — the picker is the canonical switcher,
            // so a chip on Rating/Tip just added noise. (User feedback 2026-05-27.)
            if (state is AngelPayPaymentState.SelectingMerchant) {
                AngelPayAuthBanner(
                    state = authState,
                    activeMerchantName = activeMerchantName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
            when (val currentState = state) {
                is AngelPayPaymentState.Idle -> {
                    if (initialAmount == null) {
                        Text(
                            text = "No se recibio monto para cobrar",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        AvoqadoBrandLoader(size = 72.dp)
                    }
                }

                is AngelPayPaymentState.CollectingRating -> {
                    ReviewScreen(
                        currentReview = currentState.rating,
                        amount = currentState.amount,
                        onReviewChange = { rating ->
                            // Auto-advance on star tap (matching Blumon behavior)
                            viewModel.selectRatingAndProceed(currentState.amount, rating)
                        },
                        onContinue = {
                            viewModel.selectRatingAndProceed(currentState.amount, currentState.rating)
                        },
                        onSkip = {
                            viewModel.skipRating(currentState.amount)
                        },
                        onNavigateBack = null, // Back handled by Scaffold top bar
                    )
                }

                is AngelPayPaymentState.CollectingTip -> {
                    TipScreen(
                        subtotal = currentState.amount,
                        selectedTipPercentage = currentState.selectedTipPercentage,
                        customTipAmount = if (currentState.selectedTipPercentage == null &&
                            currentState.tipAmount != "0") currentState.tipAmount else null,
                        tipSuggestions = viewModel.tipSuggestions,
                        defaultTipPercentage = viewModel.defaultTipPercentage,
                        // Update display only (no advance)
                        onTipSelectionChanged = { percentage ->
                            viewModel.updateTipSelection(percentage)
                        },
                        onCustomTipChanged = { customTip ->
                            viewModel.updateCustomTip(customTip)
                        },
                        // Advance on "Continuar" press
                        onTipPercentageSelected = { percentage ->
                            viewModel.updateTipSelection(percentage)
                            viewModel.selectTipAndProceed(
                                amount = currentState.amount,
                                rating = currentState.rating,
                                tipAmount = currentState.tipAmount,
                            )
                        },
                        onCustomTipSelected = { customTip ->
                            viewModel.updateCustomTip(customTip)
                            viewModel.selectTipAndProceed(
                                amount = currentState.amount,
                                rating = currentState.rating,
                                tipAmount = customTip,
                            )
                        },
                        onContinue = {
                            viewModel.selectTipAndProceed(
                                amount = currentState.amount,
                                rating = currentState.rating,
                                tipAmount = currentState.tipAmount,
                            )
                        },
                        onSkipTip = {
                            viewModel.skipTip(currentState.amount, currentState.rating)
                        },
                        onNavigateBack = null, // Back handled by Scaffold top bar
                    )
                }

                is AngelPayPaymentState.SelectingMerchant -> {
                    // 🧭 MERCHANT_ROUTING_RULES: show only eligible accounts (unless show-all) + banner.
                    val routing = merchantRouting
                    val visibleMerchants = if (routing == null || routing.shouldShowAll) {
                        merchants
                    } else {
                        merchants.filter { routing.eligibleMerchantAccountIds.contains(it.merchantAccountId) }
                            .ifEmpty { merchants }
                    }
                    val routingBanner = if (routing?.showFallbackBanner == true) {
                        "Mostrando todas las cuentas: ninguna regla de cobro aplica a esta venta."
                    } else null
                    MerchantSelectionContent(
                        subtotalAmount = currentState.subtotal,
                        totalAmount = currentState.totalAmount,
                        tipAmount = currentState.tipAmount,
                        rating = currentState.rating,
                        merchants = visibleMerchants,
                        currentMerchant = currentMerchant,
                        // Multi-AngelPay accounts per venue (2026-05-19): block
                        // Tarjeta/Efectivo/Cripto while a merchant switch is in
                        // flight. Two distinct flight sources, both need to be
                        // covered or `startCardPayment` can beat the in-flight
                        // `selectMerchant` and trigger SwitchBlockedDuringCharge:
                        //   1. `inFlightSwitch != null` — SDK `switchMerchant`
                        //      is mid-call (the merchant-level switch, ≤ 8s)
                        //   2. `authState is Authenticating` — `switchAccount`
                        //      is mid-call (the account-level swap, can take
                        //      30-60s under AngelPay QA timeouts with the
                        //      5-attempt backoff). selectMerchant chains:
                        //      switchAccount → switchActiveMerchant — if the
                        //      operator taps Tarjeta during the first phase,
                        //      `_currentMerchant` is reverted to null and the
                        //      record-payment call goes out without a
                        //      merchantAccountId → backend 400. Reproduced
                        //      2026-05-19 with contacto@avoqado.io pick.
                        // Three sources, all need to be `false` for the
                        // payment buttons to be enabled. The third one
                        // (`selectionInProgress`) closes the race window
                        // BEFORE either of the other two fires — set
                        // synchronously at the start of `selectMerchant`.
                        merchantSwitchingLoading = selectionInProgress ||
                            inFlightSwitch != null ||
                            authState is AngelPayAuthState.Authenticating,
                        onSelectMerchant = { viewModel.selectMerchant(it) },
                        onStartPayment = { viewModel.startCardPayment() },
                        onStartCashPayment = { viewModel.startCashPayment() },
                        onStartCryptoPayment = { viewModel.processCryptoPayment(currentState.totalAmount) },
                        onNavigateBack = null, // Back handled by Scaffold top bar
                        showCashOption = true,
                        showCryptoOption = viewModel.showCryptoOption,
                        hideAccountSelector = visibleMerchants.size <= 1,
                        // 🧭 MERCHANT_ROUTING_RULES: "showing all accounts" notice when no rule matched
                        routingBannerMessage = routingBanner,
                    )
                }

                // 🪙 CRYPTO: Generating QR (loading from backend)
                is AngelPayPaymentState.GeneratingCryptoQR -> {
                    CryptoPaymentLoadingScreen(totalAmount = currentState.totalAmount)
                }

                // 🪙 CRYPTO: Awaiting customer payment (showing QR)
                is AngelPayPaymentState.AwaitingCryptoPayment -> {
                    CryptoPaymentQrScreen(
                        paymentUrl = currentState.paymentUrl,
                        totalAmount = currentState.totalAmount,
                        expiresInSeconds = currentState.expiresInSeconds,
                        onCancel = { viewModel.cancelCryptoPayment() },
                        onTimeout = { viewModel.handleCryptoTimeout() },
                    )
                }

                is AngelPayPaymentState.LaunchingAngelPay -> {
                    LoadingContent(message = "Abriendo AngelPay...")
                }
                is AngelPayPaymentState.LaunchingAngelPaySdk -> {
                    LoadingContent(message = "Abriendo AngelPay SDK...")
                }

                is AngelPayPaymentState.WaitingForResult -> {
                    LoadingContent(
                        message = currentState.message,
                        subtitle = "No cierres esta pantalla",
                        largeSpinner = true,
                        // 🔴 Vigilante de autorizacion (riel AngelPay/NEXGO): banda no
                        // bloqueante, NONE en el camino feliz -> sin cambio visual.
                        watchdogLevel = currentState.watchdogLevel,
                    )
                }

                // Task 32 — D2 transient states. UI polish (banner / merchant
                // pill flip) lives in Task 33; for now render the same loading
                // skeleton as WaitingForResult so the screen stays responsive.
                is AngelPayPaymentState.Switching -> {
                    LoadingContent(
                        message = "Cambiando de merchant…",
                        subtitle = "No cierres esta pantalla",
                        largeSpinner = true,
                    )
                }

                is AngelPayPaymentState.Charging -> {
                    LoadingContent(
                        message = "Procesando cobro…",
                        subtitle = "No cierres esta pantalla",
                        largeSpinner = true,
                    )
                }

                is AngelPayPaymentState.ProcessingCash -> {
                    LoadingContent(message = currentState.message)
                }

                is AngelPayPaymentState.RecordingPayment -> {
                    LoadingContent(message = currentState.message)
                }

                is AngelPayPaymentState.Success -> {
                    LoadingContent(message = "Pago exitoso")
                }

                is AngelPayPaymentState.Queued -> {
                    // Defensive only — the overlay `when` above (bypassing this Scaffold)
                    // intercepts Queued on the same composition pass, same as it does for
                    // Success; this should never actually render. Fix round 1: it used to be
                    // LoadingContent — an indeterminate spinner on a FINISHED payment, with no
                    // top bar exit (title falls through, back arrow no-ops) and no in-content
                    // exit either — a stuck screen if the bypass regresses. QueuedFallbackContent
                    // is a self-contained, working exit instead. NEVER ErrorContent — no red.
                    QueuedFallbackContent(
                        message = currentState.message,
                        onGoHome = {
                            viewModel.resetPayment()
                            onNavigateHome()
                        },
                    )
                }

                is AngelPayPaymentState.Error -> {
                    ErrorContent(
                        state = currentState,
                        // 🔁 Retry re-attempts the SAME charge (card → relaunch
                        // card; crypto → new QR) instead of restarting the flow
                        // at rating/tip. Pre-flight errors fall back to the
                        // payment-method step. (User feedback 2026-05-30.)
                        onRetry = { viewModel.retryAfterError() },
                        onGoBack = onNavigateBack,
                        onOpenShift = onNavigateToShifts,
                    )
                }

                is AngelPayPaymentState.Cancelled -> {
                    CancelledContent(onNavigateBack = onNavigateBack)
                }
            }
            }
        }
    }

}

// ── Shared composables ───────────────────────────────────────────────

@Composable
private fun LoadingContent(
    message: String,
    subtitle: String? = null,
    largeSpinner: Boolean = false,
    // 🔴 Vigilante de autorizacion: NONE por defecto -> el camino feliz no renderiza
    // nada nuevo y queda byte-identico. Ver AuthorizationWatchdog.kt / WatchdogCopy.kt.
    watchdogLevel: AuthWatchdogLevel = AuthWatchdogLevel.NONE,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AvoqadoBrandLoader(
            size = if (largeSpinner) 96.dp else 72.dp,
        )
        Spacer(modifier = Modifier.height(if (largeSpinner) 24.dp else 16.dp))
        Text(
            text = message,
            style = if (largeSpinner) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 🔴 Vigilante de autorizacion — banda NO bloqueante bajo el indicador. NUNCA
        // cancela la autorizacion (ver AuthorizationWatchdog.kt): solo avisa. Mismo
        // patron de contraste que el banner ambar de AngelPaySuccessContent/PaymentScreen
        // (statusWarning/statusError al 12% de fondo, texto SIEMPRE onSurface — full-
        // strength sobre fondo claro quedo en ~1.96:1, subido a 16.17:1). Sin colores nuevos.
        val watchdogText = watchdogMessage(watchdogLevel)
        if (watchdogText != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val watchdogColor = if (watchdogLevel == AuthWatchdogLevel.VERY_SLOW) {
                MaterialTheme.avoqadoColors.statusError
            } else {
                MaterialTheme.avoqadoColors.statusWarning
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(watchdogColor.copy(alpha = 0.12f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = watchdogColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = watchdogText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    state: AngelPayPaymentState.Error,
    onRetry: () -> Unit,
    onGoBack: () -> Unit,
    onOpenShift: () -> Unit,
) {
    // 🩹 Same fix as Blumon's PaymentErrorContent — center when short, scroll when the
    // AngelPay SDK error message + up to 3 stacked buttons don't fit the viewport.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minContentHeight = maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minContentHeight)
                .padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.avoqadoColors.statusError,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Error en el pago",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (state.showOpenShiftButton) {
                Button(
                    onClick = onOpenShift,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Abrir Turno")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.canRetry) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reintentar")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = onGoBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Regresar")
            }
        }
    }
}

@Composable
private fun CancelledContent(onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Pago cancelado",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Regresar")
        }
    }
}

/**
 * F-1 fix round 1 (minor finding): defensive fallback ONLY — see the `Queued` branch
 * comment in the main `when` above. If the pre-Scaffold bypass ever regresses, this is
 * what actually shows: a self-contained, working screen (own exit button), not a stuck
 * spinner. Same sizing idiom as [ErrorContent] (scrolls on the small PAX screen instead
 * of clipping the ~150-char message) but success-toned — green check, no red, no retry
 * (retrying here would double-charge).
 */
@Composable
private fun QueuedFallbackContent(
    message: String,
    onGoHome: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minContentHeight = maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minContentHeight)
                .padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.avoqadoColors.statusSuccess,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Cobro procesado",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onGoHome,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Ir a Inicio")
            }
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun AngelPayErrorPreview() {
    AvoqadoTheme {
        ErrorContent(
            state = AngelPayPaymentState.Error(
                message = "Debes abrir un turno antes de cobrar",
                canRetry = false,
                showOpenShiftButton = true,
            ),
            onRetry = {},
            onGoBack = {},
            onOpenShift = {},
        )
    }
}

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun AngelPayCancelledPreview() {
    AvoqadoTheme {
        CancelledContent(onNavigateBack = {})
    }
}
