package com.jaac.avoqado_tpv.features.kiosk.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.kiosk.presentation.KioskViewModel
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminAuth
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminBottomSheet
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskAdminPinDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskExitPinDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskModifierDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskSearchDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskStaffAssignDialog
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskStaffButton
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.KioskStaffInfoDialog
import timber.log.Timber
import java.math.BigDecimal
import com.jaac.avoqado_tpv.core.util.rememberCurrencyFormat
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.TextosVariantes
import com.jaac.avoqado_tpv.core.presentation.components.variantes.HojaDeVariantes
import com.jaac.avoqado_tpv.core.presentation.components.variantes.textoDePrecio
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.kiosk.presentation.accionAlElegirEnKiosco
import com.jaac.avoqado_tpv.features.kiosk.presentation.claveDeEntradaDeKiosco
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.kioskCatalogoDeEjemplo
import com.jaac.avoqado_tpv.features.kiosk.presentation.hojaDeVariantesPermitidaEnKiosco
import com.jaac.avoqado_tpv.features.kiosk.presentation.reglasDeKiosco

/**
 * Kiosk Menu Screen - Product Selection
 *
 * Self-service product browsing with:
 * - Category filter bar at top
 * - Product grid with large cards
 * - Floating cart button with item count
 *
 * Pattern: McDonald's kiosk style
 *
 * @param onCartClick Callback when cart FAB is clicked
 * @param onBack Callback when back/cancel is pressed
 * @param onExitKiosk Callback when staff exits kiosk mode
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskMenuScreen(
    viewModel: KioskViewModel,  // Required - shared from KioskNavigation
    onCartClick: () -> Unit,
    onBack: () -> Unit,
    onExitKiosk: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cartItemCount by viewModel.cartItemCount.collectAsStateWithLifecycle()
    val staffSession by viewModel.staffSession.collectAsStateWithLifecycle()

    // Secret exit gesture state (emergency fallback)
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Admin mode state
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var showAdminBottomSheet by remember { mutableStateOf(false) }
    var adminAuth by remember { mutableStateOf<KioskAdminAuth?>(null) }

    // 🔧 Modifier selection dialog state
    var showModifierDialog by remember { mutableStateOf(false) }
    var selectedProductForModifiers by remember { mutableStateOf<KioskProduct?>(null) }

    // 🔍 Search dialog state
    var showSearchDialog by remember { mutableStateOf(false) }

    // 🧩 Familia de variantes (tallas) abierta: se guarda su groupId y la hoja la busca en el catálogo de AHORA
    var familiaAbierta by remember { mutableStateOf<String?>(null) }
    val currencyFormat = rememberCurrencyFormat()

    // Lo que pinta la cuadrícula: las familias (armadas sobre TODO lo disponible) ocupan el lugar de su primera variante en la lista
    // ya filtrada por categoría, así que una categoría con una sola talla igual muestra el botón de la familia.
    val entradas = remember(state.products, state.selectedCategoryId, state.familias) {
        reglasDeKiosco.agruparCatalogo(state.filteredProducts, state.familias)
    }

    // 🥝 Staff assignment dialog state
    var showStaffAssignDialog by remember { mutableStateOf(false) }
    var showStaffInfoDialog by remember { mutableStateOf(false) }

    Timber.d("🥝 [KIOSK-MENU] Composing MenuScreen (categories: ${state.categories.size}, products: ${state.filteredProducts.size}, cartItems: $cartItemCount, isLoading: ${state.isLoading})")

    // Load products on first composition
    LaunchedEffect(Unit) {
        Timber.i("🥝 [KIOSK-MENU] Screen launched - loading products")
        viewModel.loadProducts()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface, // Use light background for kiosk menu
        topBar = {
            TopAppBar(
                title = {
                    // Title with secret exit gesture (5 taps)
                    Text(
                        text = "Menu",
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime > 3000) {
                                tapCount = 1
                                Timber.d("🥝 [KIOSK-MENU] Title tap reset (timeout) - count: 1")
                            } else {
                                tapCount++
                                Timber.d("🥝 [KIOSK-MENU] Title tap - count: $tapCount")
                            }
                            lastTapTime = now

                            if (tapCount >= 5) {
                                Timber.i("🥝 [KIOSK-MENU] Secret exit gesture detected! (5 taps in 3 seconds)")
                                tapCount = 0
                                showExitDialog = true
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        Timber.i("🥝 [KIOSK-MENU] Back button pressed - clearing cart and returning to welcome")
                        viewModel.clearCart()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Cancelar"
                        )
                    }
                },
                actions = {
                    // 🥝 Staff assignment button
                    KioskStaffButton(
                        staffSession = staffSession,
                        onClick = {
                            if (staffSession != null) {
                                // Staff assigned - show info dialog
                                Timber.i("🥝 [KIOSK-MENU] Staff button clicked - showing info for ${staffSession?.staffName}")
                                showStaffInfoDialog = true
                            } else {
                                // No staff - show assign dialog
                                Timber.i("🥝 [KIOSK-MENU] Staff button clicked - showing assign dialog")
                                showStaffAssignDialog = true
                            }
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    // Admin gear icon
                    IconButton(
                        onClick = {
                            Timber.i("🥝 [KIOSK-MENU] Admin gear icon tapped")
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (cartItemCount > 0) {
                FloatingActionButton(
                    onClick = {
                        Timber.i("🥝 [KIOSK-MENU] Cart FAB clicked (cartItems: $cartItemCount) - navigating to cart")
                        onCartClick()
                    },
                    containerColor = MaterialTheme.avoqadoColors.statusSuccess,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = "Ver carrito",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        // Badge with count
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.error,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (cartItemCount > 9) "9+" else cartItemCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface) // Ensure background is light
        ) {
            // Category filter bar with search button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🔍 Search button (fixed left) - same height as FilterChips (32.dp)
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .height(32.dp)
                        .width(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            Timber.i("🔍 [KIOSK-MENU] Search button clicked")
                            showSearchDialog = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar producto",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Category chips
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedCategoryId == null,
                            onClick = { viewModel.selectCategory(null) },
                            label = { Text("Todo") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }

                    items(state.categories) { category ->
                        FilterChip(
                            selected = state.selectedCategoryId == category.id,
                            onClick = { viewModel.selectCategory(category.id) },
                            label = { Text(category.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            // Product grid
            when {
                state.isLoading -> {
                    AvoqadoFullScreenLoading(message = "Cargando productos...")
                }

                state.filteredProducts.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No hay productos disponibles",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    KioskCatalogoGrid(
                        entradas = entradas,
                        onProductClick = { product ->
                            // 🔧 Check if product has modifiers
                            if (product.hasModifiers) {
                                // Show modifier selection dialog
                                Timber.d("🥝 [KIOSK-MENU] Product '${product.name}' has modifiers - showing dialog")
                                selectedProductForModifiers = product
                                showModifierDialog = true
                            } else {
                                // Add directly to cart (no modifiers)
                                viewModel.addToCart(product)
                            }
                        },
                        onFamiliaClick = { familia ->
                            Timber.d("🧩 [KIOSK-MENU] Family '${familia.nombre}' tapped - showing variant sheet")
                            familiaAbierta = familia.groupId
                        }
                    )
                }
            }
        }
    }

    // Admin PIN Dialog
    if (showAdminPinDialog) {
        Timber.d("🥝 [KIOSK-MENU] Showing Admin PIN Dialog")
        KioskAdminPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-MENU] Admin PIN dialog dismissed")
                showAdminPinDialog = false
            },
            onAuthSuccess = { auth ->
                Timber.i("🥝 [KIOSK-MENU] Admin authenticated: ${auth.staffName} (role=${auth.role}, staffId=${auth.staffId})")
                showAdminPinDialog = false
                adminAuth = auth
                showAdminBottomSheet = true
            }
        )
    }

    // Admin Bottom Sheet
    if (showAdminBottomSheet && adminAuth != null) {
        Timber.d("🥝 [KIOSK-MENU] Showing Admin Bottom Sheet for: ${adminAuth!!.staffName}")
        KioskAdminBottomSheet(
            adminAuth = adminAuth!!,
            onDismiss = {
                Timber.d("🥝 [KIOSK-MENU] Admin bottom sheet dismissed")
                showAdminBottomSheet = false
                adminAuth = null
            },
            onExitKiosk = {
                Timber.i("🥝 [KIOSK-MENU] Exit kiosk from admin bottom sheet - calling onExitKiosk()")
                showAdminBottomSheet = false
                adminAuth = null
                onExitKiosk()
            }
        )
    }

    // Legacy Exit PIN Dialog (emergency fallback via 5-tap gesture)
    if (showExitDialog) {
        Timber.d("🥝 [KIOSK-MENU] Showing legacy Exit PIN Dialog (5-tap gesture)")
        KioskExitPinDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-MENU] Exit dialog dismissed")
                showExitDialog = false
            },
            onExitSuccess = {
                Timber.i("🥝 [KIOSK-MENU] Exit PIN validated - calling onExitKiosk()")
                showExitDialog = false
                onExitKiosk()
            }
        )
    }

    // 🔧 Modifier Selection Dialog
    if (showModifierDialog && selectedProductForModifiers != null) {
        Timber.d("🥝 [KIOSK-MENU] Showing Modifier Dialog for: ${selectedProductForModifiers!!.name}")
        KioskModifierDialog(
            product = selectedProductForModifiers!!,
            onDismiss = {
                Timber.d("🥝 [KIOSK-MENU] Modifier dialog dismissed")
                showModifierDialog = false
                selectedProductForModifiers = null
            },
            onConfirm = { selectedModifiers, totalPrice ->
                Timber.i("🥝 [KIOSK-MENU] Modifiers confirmed for ${selectedProductForModifiers!!.name}: " +
                        "${selectedModifiers.joinToString { it.name }}, price: $totalPrice")
                viewModel.addToCartWithModifiers(
                    product = selectedProductForModifiers!!,
                    selectedModifiers = selectedModifiers,
                    unitPriceWithModifiers = totalPrice
                )
                showModifierDialog = false
                selectedProductForModifiers = null
            }
        )
    }

    // 🔍 Search Dialog
    KioskSearchDialog(
        visible = showSearchDialog,
        products = state.allProducts,  // Search across ALL products (not filtered)
        familias = state.familias,
        onDismiss = {
            Timber.d("🔍 [KIOSK-MENU] Search dialog dismissed")
            showSearchDialog = false
        },
        onProductClick = { product ->
            Timber.i("🔍 [KIOSK-MENU] Product selected from search: ${product.name}")
            showSearchDialog = false
            // Check if product has modifiers
            if (product.hasModifiers) {
                selectedProductForModifiers = product
                showModifierDialog = true
            } else {
                viewModel.addToCart(product)
            }
        },
        onFamiliaClick = { familia ->
            // La búsqueda sigue abierta debajo: al cerrar la hoja con la X se vuelve a los resultados.
            Timber.i("🔍 [KIOSK-MENU] Family selected from search: ${familia.nombre}")
            familiaAbierta = familia.groupId
        }
    )

    // 🧩 Hoja de tallas/colores de la familia tocada (en la cuadrícula o en la búsqueda). Va DESPUÉS de la búsqueda, para pintarse encima
    // de ella (tocar una familia ahí la abre), y ANTES del aviso del sistema de pagos, que se pinta sobre todo; los diálogos de abajo son
    // ventanas propias. Cubre toda la pantalla y consume los toques (nada de lo de abajo los recibe).
    // El menú no cobra (la orden se crea en `KioskCartScreen`, otra ruta): su único freno es ese aviso. Con él puesto la hoja NO se pinta y se cierra.
    val hojaPermitida = hojaDeVariantesPermitidaEnKiosco(state)
    val familiaDeLaHoja = if (hojaPermitida) familiaAbierta?.let { state.familias[it] } else null
    if (familiaAbierta != null && familiaDeLaHoja == null) {
        // La familia ya no es válida (catálogo refrescado) o no se puede mostrar ahora: la hoja se cierra sola.
        LaunchedEffect(familiaAbierta) { familiaAbierta = null }
    }
    if (familiaDeLaHoja != null) {
        // El Box aísla el zIndex(100f) de la hoja: el aviso del sistema de pagos, que se monta DESPUÉS, queda encima de ella.
        Box(modifier = Modifier.fillMaxSize()) {
            HojaDeVariantes(
                familia = familiaDeLaHoja,
                reglas = reglasDeKiosco,
                precioTexto = { currencyFormat.format(it.price) },
                // Una talla agotada ni siquiera está en la familia (el kiosco no muestra lo agotado): no hay aviso que dar.
                aviso = { null },
                onCerrar = { familiaAbierta = null },
                onElegir = { elegida ->
                    // 1º cerrar la hoja (la entrega es única por apertura); 2º resolver la variante por id en el catálogo de AHORA;
                    // 3º el camino de hoy: con extras ⇒ KioskModifierDialog de ESA variante; si no ⇒ agregar.
                    familiaAbierta = null
                    val accion = accionAlElegirEnKiosco(viewModel.productoVigente(elegida.id))
                    // Si venía de la búsqueda, se cierra como al tocar un producto de ahí.
                    if (accion != AccionAlElegir.Cerrar) showSearchDialog = false
                    when (accion) {
                        is AccionAlElegir.Agregar -> {
                            Timber.i("🧩 [KIOSK-MENU] Variant selected: ${accion.variante.name}")
                            viewModel.addToCart(accion.variante)
                        }
                        is AccionAlElegir.AbrirExtras -> {
                            Timber.d("🧩 [KIOSK-MENU] Variant '${accion.variante.name}' has modifiers - showing dialog")
                            selectedProductForModifiers = accion.variante
                            showModifierDialog = true
                        }
                        is AccionAlElegir.NoDisponible, AccionAlElegir.Cerrar -> Unit
                    }
                },
            )
        }
    }

    // 🥝 Staff Assignment Dialog
    if (showStaffAssignDialog) {
        Timber.d("🥝 [KIOSK-MENU] Showing Staff Assign Dialog")
        KioskStaffAssignDialog(
            onDismiss = {
                Timber.d("🥝 [KIOSK-MENU] Staff assign dialog dismissed")
                showStaffAssignDialog = false
            },
            onStaffAssigned = { session ->
                Timber.i("🥝 [KIOSK-MENU] Staff assigned: ${session.staffName} (${session.role})")
                viewModel.setStaffSession(session)
                showStaffAssignDialog = false
            }
        )
    }

    // 🥝 Staff Info Dialog
    if (showStaffInfoDialog && staffSession != null) {
        Timber.d("🥝 [KIOSK-MENU] Showing Staff Info Dialog for: ${staffSession?.staffName}")
        KioskStaffInfoDialog(
            staffSession = staffSession!!,
            onDismiss = {
                Timber.d("🥝 [KIOSK-MENU] Staff info dialog dismissed")
                showStaffInfoDialog = false
            },
            onChangeStaff = {
                Timber.i("🥝 [KIOSK-MENU] Change staff requested from info dialog")
                showStaffInfoDialog = false
                showStaffAssignDialog = true
            }
        )
    }

    // 🔧 Blumon SDK Initialization Overlay
    // Shows loader while SDK initializes, blocks user from proceeding to payment
    if (state.avisoDePagosVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* Block clicks while SDK is not ready */ },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(32.dp)
                    .width(300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    // 🩹 Long SDK init error text no longer pushes "Reintentar" past the card edge.
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state.isBlumonInitializing || state.blumonInitError == null) {
                        // Loading state
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Preparando sistema de pagos...",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Por favor espere",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else if (state.blumonInitError != null) {
                        // Error state
                        Text(
                            text = "⚠️",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = "Error de Inicialización",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = state.blumonInitError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { viewModel.retryBlumonInit() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reintentar")
                        }
                    }
                }
            }
        }
    }
}

/**
 * La cuadrícula del menú: productos sueltos y botones de familia (tallas). Cada familia ocupa el lugar de su primera variante.
 */
@Composable
private fun KioskCatalogoGrid(
    entradas: List<EntradaDeCatalogo<KioskProduct>>,
    onProductClick: (KioskProduct) -> Unit,
    onFamiliaClick: (Familia<KioskProduct>) -> Unit
) {
    val currencyFormat = rememberCurrencyFormat()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = entradas,
            key = { claveDeEntradaDeKiosco(it) }
        ) { entrada ->
            when (entrada) {
                is EntradaDeCatalogo.Suelto -> {
                    val product = entrada.producto
                    KioskProductCard(
                        name = product.name,
                        price = product.price,
                        imageUrl = product.imageUrl,
                        hasModifiers = product.hasModifiers,  // 🔧 Show indicator if has modifiers
                        onAdd = { onProductClick(product) }
                    )
                }
                is EntradaDeCatalogo.DeFamilia -> {
                    // La misma tarjeta que sus vecinos (foto, nombre, precio), con «desde …» y «N variantes»; tocarla abre la hoja.
                    val familia = entrada.familia
                    val precio = reglasDeKiosco.textoDePrecio(familia) { currencyFormat.format(it) }
                    KioskProductCard(
                        name = familia.nombre,
                        price = reglasDeKiosco.precioMinimo(familia),
                        imageUrl = familia.imagenUrl,
                        priceText = precio,
                        badgeText = TextosVariantes.variantes(familia.variantes.size),
                        // «Playera, 3 variantes, desde $199.00»: igual que el botón de familia de Órdenes y Cobro rápido.
                        modifier = Modifier.semantics {
                            contentDescription = TextosVariantes.accesibleFamilia(familia.nombre, familia.variantes.size, precio)
                        },
                        onAdd = { onFamiliaClick(familia) }
                    )
                }
            }
        }
    }
}

/**
 * Kiosk Product Card
 *
 * @param name Product name
 * @param price Product base price
 * @param imageUrl Optional product image URL
 * @param hasModifiers Whether product has modifier groups (shows indicator)
 * @param priceText Texto del precio en lugar de [price] formateado (una familia: «desde $199.00»); null = el precio de siempre
 * @param badgeText Insignia arriba a la izquierda (una familia: «3 variantes»); si no es null la tarjeta ES una familia: no lleva el «+»
 *   (se leería como «agregar ya») y tocarla abre la hoja de tallas
 * @param onAdd Callback when user taps to add product (o a abrir la hoja de tallas, si es una familia)
 *
 * Una familia cabe en los mismos 180 dp de alto en cualquier ancho. Zona de texto: 180 − 90 (foto) − 16 (margen 8+8) = 74 dp: nombre
 * 2 renglones (2×16 = 32) + 2 + precio hasta 2 renglones (2×20 = 40) = 74 dp. Ancho: 101.3 − 16 = 85.3 dp en la PAX (360 dp); con
 * DM Sans bold 14 sp y 0.25 sp entre letras «desde $199.00» (104.0) baja a «desde» (43.9) y «$199.00» (56.5); «$1,299.00» 69.1 y
 * «$99,999.00» 82.1 caben en su renglón. La insignia («100 variantes» 76.9 + 12 de margen = 88.9 dp) cabe en los 93.3 dp de la foto.
 */
@Composable
private fun KioskProductCard(
    name: String,
    price: BigDecimal,
    imageUrl: String?,
    hasModifiers: Boolean = false,
    priceText: String? = null,
    badgeText: String? = null,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit
) {
    val currencyFormat = rememberCurrencyFormat()
    val esFamilia = badgeText != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)  // Increased from 150dp to fit price text
            .clickable(onClick = onAdd),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Product image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (!imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Add button overlay (una familia no lo lleva: tocarla abre la hoja de tallas, no agrega)
                if (!esFamilia) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onAdd),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Agregar",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 🔧 Badge arriba a la izquierda: «N variantes» en una familia, «Personalizable» en un producto con extras
                val textoInsignia = badgeText ?: if (hasModifiers) "Personalizable" else null
                if (textoInsignia != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(
                                color = if (esFamilia) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = textoInsignia,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (esFamilia) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium,
                            // «Personalizable» se queda como hoy; «N variantes» va en un renglón (cabe, ver el cálculo arriba)
                            maxLines = if (esFamilia) 1 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Product info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = priceText ?: currencyFormat.format(price),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    // «desde $199.00» no cabe en un renglón y baja a dos; más no hay alto (ver el cálculo arriba)
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================================
// Preview
// ============================================================================

@Preview(name = "Menú del kiosco: dos familias junto a tarjetas con foto", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun KioskCatalogoGridPreview() {
    AvoqadoTheme {
        val productos = kioskCatalogoDeEjemplo()
        KioskCatalogoGrid(
            entradas = reglasDeKiosco.agruparCatalogo(productos, reglasDeKiosco.familiasDe(productos)),
            onProductClick = {},
            onFamiliaClick = {}
        )
    }
}
