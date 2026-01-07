package com.jaac.avoqado_tpv.features.kiosk.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskCartItem
import com.jaac.avoqado_tpv.features.kiosk.presentation.KioskViewModel
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminAuth
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminBottomSheet
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminPinDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskExitPinDialog
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * Kiosk Cart Screen - Order Summary
 *
 * Shows cart contents with quantity controls and total.
 * "Pay Now" button creates order and navigates to payment.
 *
 * @param onBack Go back to menu
 * @param onPayNow Callback with orderId, amount, and orderNumber to navigate to payment
 * @param onExitKiosk Callback when staff exits kiosk mode
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskCartScreen(
    viewModel: KioskViewModel,  // Required - shared from KioskNavigation
    onBack: () -> Unit,
    onPayNow: (orderId: String, amount: Long, orderNumber: String) -> Unit,
    onExitKiosk: () -> Unit
) {
    val cartItems by viewModel.cartItems.collectAsStateWithLifecycle()
    val cartTotal by viewModel.cartTotal.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Secret exit gesture state (emergency fallback)
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Admin mode state
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var showAdminBottomSheet by remember { mutableStateOf(false) }
    var adminAuth by remember { mutableStateOf<KioskAdminAuth?>(null) }

    // Order creation state
    var isCreatingOrder by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    }

    Timber.d("🥝 [KIOSK-CART] Composing CartScreen (items: ${cartItems.size}, total: $cartTotal, isEmpty: ${cartItems.isEmpty()})")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface, // Use light background for kiosk
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tu Orden",
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime > 3000) {
                                tapCount = 1
                                Timber.d("🥝 [KIOSK-CART] Title tap reset (timeout) - count: 1")
                            } else {
                                tapCount++
                                Timber.d("🥝 [KIOSK-CART] Title tap - count: $tapCount")
                            }
                            lastTapTime = now

                            if (tapCount >= 5) {
                                Timber.i("🥝 [KIOSK-CART] Secret exit gesture detected! (5 taps in 3 seconds)")
                                tapCount = 0
                                showExitDialog = true
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        Timber.i("🥝 [KIOSK-CART] Back button pressed - returning to menu")
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Volver al menu"
                        )
                    }
                },
                actions = {
                    // Admin gear icon
                    IconButton(
                        onClick = {
                            Timber.i("🥝 [KIOSK-CART] Admin gear icon tapped")
                            showAdminPinDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Administrador",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                // Use light colors like KioskMenuScreen (consistent design)
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface) // Ensure background is light
        ) {
            if (cartItems.isEmpty()) {
                // Empty cart
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Tu carrito esta vacio",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Agrega productos desde el menu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Cart items list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = cartItems,
                        key = { it.productId }
                    ) { item ->
                        CartItemRow(
                            item = item,
                            onIncrease = { viewModel.increaseQuantity(item.productId) },
                            onDecrease = { viewModel.decreaseQuantity(item.productId) },
                            currencyFormat = currencyFormat
                        )
                    }
                }

                // Order summary and pay button
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Subtotal
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Subtotal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = currencyFormat.format(cartTotal),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        HorizontalDivider()

                        Spacer(modifier = Modifier.height(6.dp))

                        // Total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Total",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = currencyFormat.format(cartTotal),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Pay Now button
                        Button(
                            onClick = {
                                if (isCreatingOrder) return@Button // Prevent double-click
                                Timber.i("🥝 [KIOSK-CART] Pay Now clicked - creating order (items: ${cartItems.size}, total: $cartTotal)")
                                isCreatingOrder = true
                                scope.launch {
                                    try {
                                        // Create order and navigate to payment
                                        val orderResult = viewModel.createOrder()
                                        if (orderResult != null) {
                                            val (orderId, orderNumber) = orderResult
                                            // Amount in pesos (Long) - DB stores pesos
                                            val amountPesos = cartTotal.toLong()
                                            Timber.i("🥝 [KIOSK-CART] Order created successfully! orderId=$orderId, orderNumber=$orderNumber, amount=\$${amountPesos} - navigating to payment")
                                            onPayNow(orderId, amountPesos, orderNumber)
                                        } else {
                                            Timber.e("🥝 [KIOSK-CART] Failed to create order!")
                                            snackbarHostState.showSnackbar(
                                                message = "Error al crear la orden. Por favor intenta de nuevo.",
                                                withDismissAction = true
                                            )
                                        }
                                    } finally {
                                        isCreatingOrder = false
                                    }
                                }
                            },
                            enabled = !isCreatingOrder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                            )
                        ) {
                            if (isCreatingOrder) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Creando orden...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "Pagar Ahora",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Admin PIN Dialog
    if (showAdminPinDialog) {
        Timber.d("🥝 [KIOSK-CART] Showing Admin PIN Dialog")
        KioskAdminPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-CART] Admin PIN dialog dismissed")
                showAdminPinDialog = false
            },
            onAuthSuccess = { auth ->
                Timber.i("🥝 [KIOSK-CART] Admin authenticated: ${auth.staffName} (role=${auth.role}, staffId=${auth.staffId})")
                showAdminPinDialog = false
                adminAuth = auth
                showAdminBottomSheet = true
            }
        )
    }

    // Admin Bottom Sheet
    if (showAdminBottomSheet && adminAuth != null) {
        Timber.d("🥝 [KIOSK-CART] Showing Admin Bottom Sheet for: ${adminAuth!!.staffName}")
        KioskAdminBottomSheet(
            adminAuth = adminAuth!!,
            onDismiss = {
                Timber.d("🥝 [KIOSK-CART] Admin bottom sheet dismissed")
                showAdminBottomSheet = false
                adminAuth = null
            },
            onExitKiosk = {
                Timber.i("🥝 [KIOSK-CART] Exit kiosk from admin bottom sheet - calling onExitKiosk()")
                showAdminBottomSheet = false
                adminAuth = null
                onExitKiosk()
            }
        )
    }

    // Legacy Exit PIN Dialog (emergency fallback via 5-tap gesture)
    if (showExitDialog) {
        Timber.d("🥝 [KIOSK-CART] Showing legacy Exit PIN Dialog (5-tap gesture)")
        KioskExitPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-CART] Exit dialog dismissed")
                showExitDialog = false
            },
            onExitSuccess = {
                Timber.i("🥝 [KIOSK-CART] Exit PIN validated - calling onExitKiosk()")
                showExitDialog = false
                onExitKiosk()
            }
        )
    }
}

@Composable
private fun CartItemRow(
    item: KioskCartItem,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    currencyFormat: NumberFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Product name and price
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.productName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = currencyFormat.format(item.unitPrice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quantity controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Decrease button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(onClick = onDecrease),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Quitar",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                // Quantity
                Text(
                    text = item.quantity.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Increase button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onIncrease),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Agregar",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Line total
            Text(
                text = currencyFormat.format(item.lineTotal),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
