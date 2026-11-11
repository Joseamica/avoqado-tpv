package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaGuardadaVista
import com.jaac.avoqado_tpv.core.data.network.PermisoRequeridoException
import com.jaac.avoqado_tpv.core.data.network.textoDePermiso
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.mappers.toCategoryDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toCategoryEntities
import com.jaac.avoqado_tpv.core.data.local.mappers.toDomain
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.data.ImpresoraDeVentaGuardada
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalYaGuardadaException
import com.jaac.avoqado_tpv.features.checkout.domain.CaminoDelCobro
import com.jaac.avoqado_tpv.features.checkout.domain.armarPedidoParaMobile
import com.jaac.avoqado_tpv.features.checkout.domain.caminoDelCobro
import com.jaac.avoqado_tpv.features.checkout.domain.pasaALaVentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.renglonesDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.grossLineCents
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCart
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedDiscount
import com.jaac.avoqado_tpv.features.checkout.domain.model.SelectedModifier
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.model.hasProductItems
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.VentaSinRedRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DescuentoDelCliente
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.comoDescuento
import com.jaac.avoqado_tpv.features.ordering.domain.llaveDelDescuentoDelCliente
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsItem
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_CUPON_SIN_RED_AL_VALIDAR
import com.jaac.avoqado_tpv.features.ordering.domain.TpvCreateOrderWithItemsRequest
import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult as ReferralValidationResult
import com.jaac.avoqado_tpv.features.referrals.domain.repository.ReferralValidationException
import com.jaac.avoqado_tpv.features.referrals.domain.usecase.CaptureReferralUseCase
import com.jaac.avoqado_tpv.features.referrals.domain.usecase.ValidateReferralUseCase
import com.jaac.avoqado_tpv.features.referrals.presentation.ReferralCaptureUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

/**
 * State machine for the unified Checkout screen.
 *
 * Phase 1 scope: in-memory cart manipulation, product cache, saved carts, and
 * a bridge to [ActiveCartState] so the home screen knows there's an active
 * cart. Customer + staff selection arrive in Phase 4. Order creation and
 * `PaymentFlowOrigin` routing arrive in Phase 5.
 */
data class AvisoSinConexion(val sinRed: Boolean, val guardadas: Int, val conProblema: Int) {
    val texto: String get() {
        val ventas = if (guardadas == 1) "1 venta guardada" else "$guardadas ventas guardadas"
        val principal = when {
            sinRed -> "Sin conexión · $ventas"
            guardadas > 0 -> "Subiendo $ventas"
            else -> null
        }
        return listOfNotNull(principal, "$conProblema con problema".takeIf { conProblema > 0 }).joinToString(" · ")
    }
}

/** D1 · por qué el descuento que el negocio le asignó al cliente no está puesto en la cuenta (la pantalla lo pinta bajo el cliente). */
sealed interface AvisoDescuentoDelCliente {
    /** «<Nombre> tiene <valor> de descuento. Tu puesto no puede aplicarlo.» + «Pedir autorización» si [puedePedirPin]. */
    data class SinPermiso(val nombre: String, val valor: String, val puedePedirPin: Boolean) : AvisoDescuentoDelCliente
    /** «<Nombre> tiene <valor> de descuento.» + «Usar el de <Nombre>»: la cuenta ya trae otro descuento y no se pisa. */
    data class YaHayOtro(val nombre: String, val valor: String) : AvisoDescuentoDelCliente
    /** «Sin conexión: no se puede pedir autorización ahora.» */
    data object SinRedParaPin : AvisoDescuentoDelCliente
}

/** `<Nombre>` de los textos del descuento del cliente: su nombre de pila; si está en blanco, su apellido, teléfono o correo; si nada, «El cliente». */
internal fun nombreParaElDescuento(c: Customer?): String =
    listOfNotNull(c?.firstName, c?.lastName, c?.phone, c?.email).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "El cliente"

/** `10 %`, `12.5 %` (sin ceros de más) o `$50.00`, como lo leen los avisos del descuento del cliente. */
fun textoDelValor(d: DescuentoDelCliente): String = when (d.tipo) {
    DiscountType.PERCENTAGE -> BigDecimal.valueOf(d.valor).stripTrailingZeros().toPlainString() + " %"
    DiscountType.FIXED -> "$" + BigDecimal.valueOf(d.valor).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val customerRepository: CustomerRepository,
    private val orderRepository: OrderRepository,
    private val discountRepository: DiscountRepository,
    private val savedCartsRepository: SavedCartsRepository,
    private val mosaicRepository: MosaicRepository,
    private val activeCartState: ActiveCartState,
    private val secureStorage: SecureStorage,
    private val validateReferralUseCase: ValidateReferralUseCase,
    private val captureReferralUseCase: CaptureReferralUseCase,
    private val productDao: ProductDao,
    private val productCategoryDao: ProductCategoryDao,
    private val catalogoGuardado: CatalogoGuardado,
    private val connectionStateManager: ConnectionStateManager,
    /** 💵 «Cobrar» sin red: el carrito a medias, la venta en $0 y el turno guardado, en el aparato. */
    private val ventaSinRed: VentaSinRedRepository,
    private val impresoraDeVentaGuardada: ImpresoraDeVentaGuardada,
    private val pendingPaymentDao: PendingPaymentDao,
    private val permissionsRepository: PermissionsRepository,
    private val autorizacionDeGerente: AutorizacionDeGerente,
) : ViewModel() {

    /**
     * 🔒 «Venta en preparación»: `true` mientras corren [prepareForPayment] o [createPayLaterOrder]. Ahí el ViewModel rechaza toda
     * mutación del carrito y la pantalla no responde (pestañas, cuadrícula, «Preparando…»), así que al terminar sólo se vacía lo que viajó.
     */
    private val _avisoSinConexion = MutableStateFlow<AvisoSinConexion?>(null)
    val avisoSinConexion: StateFlow<AvisoSinConexion?> = _avisoSinConexion.asStateFlow()
    private val _ventaSinMostrar = MutableStateFlow<VentaGuardadaVista?>(null)
    val ventaSinMostrar: StateFlow<VentaGuardadaVista?> = _ventaSinMostrar.asStateFlow()
    private val _canCompItems = MutableStateFlow(false)
    val canCompItems: StateFlow<Boolean> = _canCompItems.asStateFlow()
    private val _canApplyDiscount = MutableStateFlow(false)
    val canApplyDiscount: StateFlow<Boolean> = _canApplyDiscount.asStateFlow()
    private val _errorRecuperacion = MutableStateFlow<String?>(null)
    val errorRecuperacion: StateFlow<String?> = _errorRecuperacion.asStateFlow()
    val sinRed: StateFlow<Boolean> = connectionStateManager.connectionState.map { !it.hasInternet }
        .stateIn(viewModelScope, SharingStarted.Eagerly, !connectionStateManager.hasInternet())
    private var permisosDe: Pair<String?, String?>? = null
    private var permisosJob: Job? = null
    private var contadoresJob: Job? = null
    private var recuperacionJob: Job? = null
    private var venueObservado: String? = null
    private var recuperacionVerificada = false

    private val candado = CandadoDeVenta()
    val ventaEnPreparacion: StateFlow<Boolean> = candado.activo

    /** Un aviso para el cajero (p. ej. «Recuperamos la venta que estaba a medias»); la pantalla lo muestra y llama [avisoMostrado]. */
    private val _aviso = MutableStateFlow<String?>(null)
    val aviso: StateFlow<String?> = _aviso.asStateFlow()

    private val _ventaEnCeroGuardada = MutableStateFlow<VentaLocal?>(null)
    val ventaEnCeroGuardada: StateFlow<VentaLocal?> = _ventaEnCeroGuardada.asStateFlow()

    fun cerrarVentaEnCero() {
        _ventaEnCeroGuardada.value = null
    }

    fun avisoMostrado() {
        _aviso.value = null
    }

    private val _cartState = MutableStateFlow(
        // Pre-seed the staff who logged in so the cart panel shows a real name
        // instead of "Staff" placeholder. PIN-based reassignment is out of
        // scope for this ola.
        CartState(
            selectedStaffId = secureStorage.getStaffId().orEmpty(),
            selectedStaffName = secureStorage.getStaffName().orEmpty().ifBlank { "Staff" },
        ),
    )
    val cartState: StateFlow<CartState> = _cartState.asStateFlow()

    private val _selectedCustomer = MutableStateFlow<Customer?>(null)
    val selectedCustomer: StateFlow<Customer?> = _selectedCustomer.asStateFlow()

    private val _avisoDescuentoDelCliente = MutableStateFlow<AvisoDescuentoDelCliente?>(null)
    val avisoDescuentoDelCliente: StateFlow<AvisoDescuentoDelCliente?> = _avisoDescuentoDelCliente.asStateFlow()

    /** «Quitar»: vale para esta venta y este cliente; aunque la cajera lo vuelva a elegir, el suyo no se pone solo (el de otro sí). */
    fun quitarDescuentoDelCliente() {
        if (candado.rechaza("quitar el descuento del cliente")) return
        _cartState.update { c ->
            c.copy(
                orderDiscount = c.orderDiscount.takeUnless { c.descuentoEsDelCliente },
                descuentoEsDelCliente = false,
                descuentoDelClienteQuitadoDe = _selectedCustomer.value?.id,
            )
        }
        _avisoDescuentoDelCliente.value = null
    }

    /** «Usar el de <Nombre>»: reemplaza al otro descuento de cuenta (manual, cupón o referido), si el puesto puede. */
    fun usarDescuentoDelCliente() {
        if (candado.rechaza("usar el descuento del cliente") || necesitaInternet()) return
        viewModelScope.launch { revisarDescuentoDelCliente(usar = true) }
    }

    private var pinDelDescuento: Job? = null

    /**
     * «Pedir autorización»: el PIN de un gerente para `discounts:apply`, atado a ESTE cliente y a ESTE descuento (el servidor guarda la
     * autorización con esa llave). El token sólo vive en el carrito: viaja con la venta y se va al cambiar de cliente. Necesita red.
     */
    fun pedirAutorizacionDescuentoDelCliente() {
        if (candado.rechaza("pedir autorización del descuento")) return
        if (!connectionStateManager.hasInternet()) {
            _avisoDescuentoDelCliente.value = AvisoDescuentoDelCliente.SinRedParaPin
            return
        }
        if (pinDelDescuento?.isActive == true) return
        val cliente = _selectedCustomer.value ?: return
        val d = cliente.descuentoDelCliente ?: return
        val venueId = secureStorage.getVenueId() ?: return
        pinDelDescuento = viewModelScope.launch {
            val auth = try {
                autorizacionDeGerente.paraPermiso(
                    venueId, PERMISO_DESCUENTO, PERMISO_DESCUENTO, "Descuento de ${nombreParaElDescuento(cliente)}",
                    llaveDelDescuentoDelCliente(cliente.id, d.discountId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "No se pudo pedir la autorización del descuento del cliente")
                _aviso.value = "No se pudo pedir la autorización. Intenta otra vez"
                null
            }
            // Llegó tarde: la autorización es de ese cliente y ese descuento, no del que esté elegido ahora.
            val ahora = _selectedCustomer.value
            if (ahora?.id != cliente.id || ahora.descuentoDelCliente?.discountId != d.discountId) return@launch
            // Con token se pone; sin él (cancelado, PIN apagado, error) se vuelve a revisar: el aviso queda como deba (sin el botón si
            // el PIN se apagó), o se pone si mientras tanto le dieron el permiso.
            revisarDescuentoDelCliente(usar = true, autorizacion = (auth as? Autorizacion.ConPin)?.token)
        }
    }

    /**
     * D1: pone el descuento del cliente elegido o deja el aviso de por qué no. No pisa otro descuento de cuenta salvo con [usar], ni
     * vuelve a ponerlo tras «Quitar». Con [soloAviso] (al restaurar el carrito) no cambia lo que viaja: la llave de la venta sale de ahí.
     * [autorizacion] es el token del PIN del gerente: se guarda en el carrito junto con el descuento, en el mismo cambio.
     */
    private suspend fun revisarDescuentoDelCliente(usar: Boolean = false, soloAviso: Boolean = false, autorizacion: String? = null) {
        val cliente = _selectedCustomer.value
        val d = cliente?.descuentoDelCliente
        if (cliente == null || d == null || (_cartState.value.descuentoDelClienteQuitadoDe == cliente.id && !usar)) {
            _avisoDescuentoDelCliente.value = null
            return
        }
        val nombre = nombreParaElDescuento(cliente)
        val valor = textoDelValor(d)
        val puede = autorizacion != null || _cartState.value.autorizacionDelDescuento != null ||
            permissionsRepository.hasPermission(PERMISO_DESCUENTO)
        // Llegó tarde: otro cliente, o la venta ya se está preparando (lo que viaja no cambia).
        if (_selectedCustomer.value?.id != cliente.id || (!soloAviso && candado.rechaza("poner el descuento del cliente"))) return
        val cart = _cartState.value
        if (!usar && !cart.descuentoEsDelCliente && (cart.orderDiscount != null || cart.manualDiscountCents > 0)) {
            _avisoDescuentoDelCliente.value = AvisoDescuentoDelCliente.YaHayOtro(nombre, valor)
            return
        }
        if (!puede) {
            val pin = secureStorage.getVenueId()?.let { autorizacionDeGerente.pinPrendido(it) }
            if (_selectedCustomer.value?.id != cliente.id) return
            _avisoDescuentoDelCliente.value = AvisoDescuentoDelCliente.SinPermiso(nombre, valor, puedePedirPin = pin != false)
            return
        }
        _avisoDescuentoDelCliente.value = null
        if (soloAviso) return
        _cartState.update {
            it.copy(
                orderDiscount = d.comoDescuento(),
                descuentoEsDelCliente = true,
                autorizacionDelDescuento = autorizacion ?: it.autorizacionDelDescuento,
                descuentoDelClienteQuitadoDe = it.descuentoDelClienteQuitadoDe.takeUnless { q -> q == cliente.id },
                manualDiscountCents = 0,
                manualDiscountReason = null,
                manualDiscountSource = null,
            )
        }
    }

    private val _customerSearchQuery = MutableStateFlow("")
    val customerSearchQuery: StateFlow<String> = _customerSearchQuery.asStateFlow()

    private val _customerResults = MutableStateFlow<List<Customer>>(emptyList())
    val customerResults: StateFlow<List<Customer>> = _customerResults.asStateFlow()

    private val _isSearchingCustomers = MutableStateFlow(false)
    val isSearchingCustomers: StateFlow<Boolean> = _isSearchingCustomers.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────
    // Referral capture (Plan 5A)
    // ─────────────────────────────────────────────────────────────────────

    private val _referralCode = MutableStateFlow("")
    val referralCode: StateFlow<String> = _referralCode.asStateFlow()

    private val _referralValidation = MutableStateFlow<ReferralCaptureUiState>(ReferralCaptureUiState.Idle)
    val referralValidation: StateFlow<ReferralCaptureUiState> = _referralValidation.asStateFlow()

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<ProductCategory>>(emptyList())
    val categories: StateFlow<List<ProductCategory>> = _categories.asStateFlow()

    private val _errorCatalogo = MutableStateFlow<String?>(null)
    val errorCatalogo: StateFlow<String?> = _errorCatalogo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Las familias de variantes (talla/color) del catálogo que Cobro rápido muestra hoy ([products], con lo agotado incluido: un
     * agotado se pinta y al tocarlo avisa), por `groupId`. Se recalculan SOLO cuando cambia [products], no con cada letra de la
     * búsqueda. Cobro rápido pinta primero el catálogo guardado del negocio y luego el de la red: sin red salen de lo guardado.
     */
    val familias: StateFlow<Map<String, Familia<Product>>> = _products
        .map { reglasDeCobro.familiasDe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Lo que pinta la búsqueda, reactivo y sin llamar a la red por letra. Case- AND accent-insensitive sobre nombre y SKU (no la
     * descripción), con `trim` ("datiles" encuentra "Dátiles", "cafe" encuentra "Café"): [reglasDeCobro] con [normalizeForSearch]. Un
     * nombre (o el de una familia) da la fila de la familia una vez; un SKU da la talla. Texto en blanco: todo el catálogo AGRUPADO (hoy
     * lista todos los productos; con familias, la familia sale en UNA fila y no cada talla suelta), como la cuadrícula.
     */
    val searchResults: StateFlow<List<EntradaDeCatalogo<Product>>> =
        combine(_products, familias, _searchQuery) { all, grupos, query -> reglasDeCobro.entradasParaMostrar(all, grupos, query) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val savedCarts: StateFlow<List<SavedCart>> = savedCartsRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Shortcuts configured for the current venue. Emits an empty list when no
     * venue is selected — the Shortcuts tab then shows its onboarding empty
     * state.
     */
    val shortcuts: StateFlow<List<MosaicShortcut>> = run {
        val venueId = secureStorage.getVenueId()
        if (venueId.isNullOrBlank()) {
            MutableStateFlow<List<MosaicShortcut>>(emptyList()).asStateFlow()
        } else {
            mosaicRepository.observe(venueId)
                .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        }
    }

    init {
        // Keep ActiveCartState in sync so other screens can check the cart status.
        viewModelScope.launch {
            _cartState.collect { state ->
                activeCartState.update(state.itemCount, state.totalDisplay)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cart mutations
    // ─────────────────────────────────────────────────────────────────────

    fun addCustomAmount(name: String, amountCents: Int) {
        if (candado.rechaza("agregar un importe")) return
        if (amountCents <= 0) return
        val item = CartItem(
            type = CartItemType.CustomAmount,
            name = name.ifBlank { "Importe personalizado" },
            unitPriceCents = amountCents,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    fun addProduct(
        product: Product,
        quantity: Int = 1,
        modifiers: List<SelectedModifier> = emptyList(),
        itemNote: String? = null,
    ) {
        // Cubre la cuadrícula, la búsqueda, la talla/familia, el atajo y el escaneo: todos agregan por aquí.
        if (candado.rechaza("agregar un producto")) return
        if (quantity <= 0) return
        val unitPriceCents = product.price.multiply(BigDecimal(100)).toInt()
        val item = CartItem(
            type = CartItemType.ProductItem(product.id),
            name = product.name,
            subtitle = product.categoryName,
            unitPriceCents = unitPriceCents,
            quantity = quantity,
            imageUrl = product.imageUrl,
            colorHex = product.categoryColor,
            categoryId = product.categoryId,
            selectedModifiers = modifiers,
            itemNote = itemNote,
            // Se congela al agregar: un refresco posterior del catálogo no mueve lo que el cliente vio.
            catalogAt = product.catalogAt,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    fun removeItem(itemId: String) {
        if (candado.rechaza("quitar un renglón")) return
        _cartState.update { current ->
            current.copy(items = current.items.filterNot { it.id == itemId })
        }
    }

    fun incrementQuantity(itemId: String) {
        if (candado.rechaza("cambiar la cantidad")) return
        _cartState.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) it.copy(quantity = it.quantity + 1) else it
                },
            )
        }
    }

    fun decrementQuantity(itemId: String) {
        if (candado.rechaza("cambiar la cantidad")) return
        _cartState.update { current ->
            current.copy(
                items = current.items.mapNotNull {
                    when {
                        it.id != itemId -> it
                        it.quantity > 1 -> it.copy(quantity = it.quantity - 1)
                        else -> null // remove when reaching zero
                    }
                },
            )
        }
    }

    fun setItemNote(itemId: String, note: String?) {
        if (candado.rechaza("cambiar la nota del renglón")) return
        _cartState.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) it.copy(itemNote = note?.takeIf { n -> n.isNotBlank() }) else it
                },
            )
        }
    }

    fun setOrderNote(note: String?) {
        if (candado.rechaza("cambiar la nota de la cuenta")) return
        _cartState.update { it.copy(orderNote = note?.takeIf { n -> n.isNotBlank() }) }
    }

    fun applyOrderDiscount(discount: Discount?) {
        if (candado.rechaza("cambiar el descuento")) return
        _cartState.update {
            it.copy(
                orderDiscount = discount,
                descuentoEsDelCliente = false,
                // «Quitar descuento» de los atajos también quita el del cliente: cuenta como su «Quitar».
                descuentoDelClienteQuitadoDe = if (discount == null && it.descuentoEsDelCliente) {
                    _selectedCustomer.value?.id
                } else {
                    it.descuentoDelClienteQuitadoDe
                },
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shortcuts actions: cortesía, manual discount, coupon, void
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toggles cortesía on a single cart line. Cortesía wipes the line price
     * to $0 (see `CartItem.totalPriceCents`) and writes `isCortesia=true`
     * plus a required `reason` for the backend audit trail.
     *
     * Trying to mark an item that already has `itemDiscountId` set raises an
     * IllegalArgumentException from CartItem.init — backend rejects the
     * combination too. UI must guard so the operator can't even attempt it.
     */
    fun applyCortesiaToItem(itemId: String, reason: String) {
        if (candado.rechaza("dar una cortesía")) return
        val trimmed = reason.trim()
        require(trimmed.isNotEmpty()) { "Cortesía reason cannot be blank" }
        _cartState.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(
                            isCortesia = true,
                            cortesiaReason = trimmed,
                            itemDiscountId = null,
                        )
                    } else item
                },
            )
        }
    }

    /** Reverts a cortesía back to a billable item. Keeps everything else intact. */
    fun removeCortesiaFromItem(itemId: String) {
        if (candado.rechaza("quitar una cortesía")) return
        _cartState.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(isCortesia = false, cortesiaReason = null)
                    } else item
                },
            )
        }
    }

    /**
     * Applies a manual (operator-typed) order-level discount. The amount is
     * stored locally on the cart; when the cart is submitted via
     * `createOrderWithItems`, it travels as `discount` (no `orderDiscountId`).
     * Backend accepts freeform manual discounts when no Discount entity is
     * referenced.
     *
     * @param amountCents Positive cents to deduct from the cart total. Must
     *   not exceed the post-item-discount subtotal (UI clamps the input).
     * @param reason Optional reason. Kept on the cart for receipt rendering
     *   in a future iteration; not currently sent to the backend (the
     *   endpoint stores it on OrderDiscount.compReason via the discount entity
     *   only — manual discounts skip that path).
     */
    fun applyManualOrderDiscount(amountCents: Int, reason: String?) {
        if (candado.rechaza("aplicar un descuento")) return
        applyManualOrderDiscountInternal(amountCents, reason, source = null)
    }

    /** Internal hook used by both the manual subview and the referral flow. */
    private fun applyManualOrderDiscountInternal(
        amountCents: Int,
        reason: String?,
        source: String?,
    ) {
        if (amountCents > 0 && (!_canApplyDiscount.value || permisosDe != sesionActual())) {
            _aviso.value = "No tienes permiso para aplicar descuentos. Pide apoyo al gerente."
            return
        }
        require(amountCents >= 0) { "Manual discount must be non-negative" }
        _cartState.update { current ->
            current.copy(
                manualDiscountCents = amountCents,
                manualDiscountReason = reason?.trim()?.takeIf { it.isNotEmpty() },
                manualDiscountSource = source,
                // Manual + predefined are mutually exclusive — clear the
                // predefined discount so subtotals stay consistent.
                orderDiscount = null,
                descuentoEsDelCliente = false,
            )
        }
    }

    /** Clears any manual order discount currently applied. */
    fun clearManualOrderDiscount() {
        if (candado.rechaza("quitar el descuento")) return
        _cartState.update {
            it.copy(
                manualDiscountCents = 0,
                manualDiscountReason = null,
                manualDiscountSource = null,
            )
        }
    }

    /**
     * Validates a coupon code against the backend (no order required — the
     * validation is venue-scoped). On success, the resolved coupon is
     * converted into a synthetic `Discount` and applied at order level so
     * the rest of the cart math + the `createOrderWithItems` request works
     * the same as for predefined discounts. Returns the validation outcome
     * so UI can show an inline error.
     */
    suspend fun validateAndApplyCoupon(code: String): Result<Unit> {
        if (candado.rechaza("validar un cupón")) return Result.failure(VentaEnPreparacionException())
        if (necesitaInternet()) return Result.failure(IllegalStateException(TEXTO_CUPON_SIN_RED_AL_VALIDAR))
        val trimmed = code.trim().uppercase()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Código requerido"))
        }
        val venueId = secureStorage.getVenueId()
            ?: return Result.failure(IllegalStateException("No venueId in secure storage"))
        if (!permissionsRepository.hasPermission("coupons:redeem")) {
            return Result.failure(IllegalStateException("Tu sesión no tiene permiso para canjear cupones. Pide apoyo al gerente."))
        }
        // Contra lo que vale la venta sin el descuento de cuenta: la misma base que el monto. Y con su cliente: sin él no se mira su límite.
        val cartTotal = _cartState.value.subtotalCents / 100.0
        return discountRepository.validateCoupon(venueId, trimmed, cartTotal, _selectedCustomer.value?.id).fold(
            onSuccess = { coupon ->
                if (necesitaInternet()) {
                    Result.failure(IllegalStateException(TEXTO_CUPON_SIN_RED_AL_VALIDAR))
                } else if (!coupon.isValid) {
                    Result.failure(IllegalStateException(coupon.invalidReason ?: "Este cupón no es válido."))
                } else if (coupon.scope != null && coupon.scope != "ORDER") {
                    Result.failure(IllegalStateException("Este cupón sólo aplica a ciertos productos y todavía no se puede usar en caja."))
                } else if (candado.rechaza("aplicar el cupón")) {
                    // La respuesta llegó con la venta ya preparándose: aplicarlo ahora cambiaría un carrito que ya viajó.
                    Result.failure(VentaEnPreparacionException())
                } else {
                    // El código de la respuesta; un servidor anterior a D5 no lo manda y sirve el que se escribió.
                    val codigo = coupon.code.ifBlank { trimmed }
                    val asDiscount = Discount(
                        id = coupon.id,
                        // El mismo nombre que le pone el SERVIDOR a la fila de la orden: lo que ve la cajera es lo que sale en el reporte.
                        name = "${coupon.name} ($codigo)",
                        type = coupon.type,
                        value = coupon.value,
                        scope = com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope.ORDER,
                        conditions = null,
                        active = true,
                        requiresAuthorization = false,
                        maxDiscountAmount = coupon.maxDiscountAmount,
                        couponCode = codigo,
                    )
                    _cartState.update {
                        it.copy(
                            orderDiscount = asDiscount,
                            descuentoEsDelCliente = false,
                            manualDiscountCents = 0,
                            manualDiscountReason = null,
                        )
                    }
                    Result.success(Unit)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    fun applyOrderTaxPercent(percent: Int?) {
        if (candado.rechaza("cambiar el impuesto")) return
        // New Cobrar V1 intentionally disables order tax. Backend rejects
        // taxAmount != 0 until payment math, receipts, and dashboard totals
        // are updated together. Keep this no-op so stale UI/previews cannot
        // accidentally create a taxable checkout request.
        if (percent != null) {
            Timber.w("🛒 Ignoring tax selection in new Cobrar V1")
        }
        _cartState.update { it.copy(orderTaxPercent = null) }
    }

    // Llave de idempotencia del intento de creación de orden EN CURSO (retry-safety).
    // Vive de un intento fallido a su reintento IDÉNTICO; se limpia al crear la
    // orden y al limpiar el carrito. Ver createOrderWithCurrentItems.
    private var orderAttemptExternalId: String? = null
    private var orderAttemptFingerprint: String? = null

    /** El borrado del carrito a medias que lanzó el último «vaciar»: la siguiente venta lo espera antes de escribir el suyo. */
    private var borradoDelCarrito: Job? = null

    fun clearCart() {
        if (candado.rechaza("vaciar el carrito")) return
        reiniciarCarrito()
    }

    private fun reiniciarCarrito() {
        _cartState.value = CartState()
        _avisoDescuentoDelCliente.value = null
        // Also reset the referral capture state — the cart is a per-order
        // surface so anything tied to it (referral code, validation) must
        // go too. We do this in two steps so the cart collector emits its
        // empty state before the referral StateFlow flips.
        _referralCode.value = ""
        _referralValidation.value = ReferralCaptureUiState.Idle
        // ActiveCartState is updated by the collector in init.
        // Venta nueva = llave nueva (la huella ya lo garantizaría; esto es higiene).
        olvidarLlave()
        // D1: el cliente sigue elegido; la venta que sigue trae su descuento (o su aviso), como al elegirlo. Sólo con red, como al
        // elegirlo. La venta en $0 reinicia con el candado puesto: se espera a que se suelte.
        if (_selectedCustomer.value?.descuentoDelCliente != null && connectionStateManager.hasInternet()) {
            viewModelScope.launch {
                candado.activo.first { !it }
                revisarDescuentoDelCliente()
            }
        }
        // El cajero vació (o la venta terminó): el carrito a medias del aparato ya no es una venta pendiente.
        secureStorage.getVenueId()?.let { venueId ->
            borradoDelCarrito = viewModelScope.launch { ventaSinRed.borrarCarrito(venueId) }
        }
    }

    /**
     * La venta actual pudo llegar al servidor si su llave es la última que salió a la red en esta corrida (con o sin token) o la de un
     * carrito que volvió de disco (pudo salir antes de morir el proceso). Una marca de cada una, sólo en memoria: con la llave actual
     * en una de ellas, cancelar o negar el PIN no es «sin autorizar».
     */
    private var llaveMandada: String? = null
    private var llaveRecuperada: String? = null

    private fun olvidarLlave() {
        llaveMandada = null
        llaveRecuperada = null
        orderAttemptExternalId = null
        orderAttemptFingerprint = null
    }

    fun selectStaff(staffId: String, staffName: String) {
        if (candado.rechaza("cambiar quién vende")) return
        _cartState.update {
            it.copy(selectedStaffId = staffId, selectedStaffName = staffName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Customer selection (used by the cart panel header)
    // ─────────────────────────────────────────────────────────────────────

    fun selectCustomer(customer: Customer?) {
        // El cliente viaja en la orden (y cambiarlo limpia el referido, que es un descuento del carrito).
        if (candado.rechaza("cambiar el cliente") || necesitaInternet()) return
        val previous = _selectedCustomer.value
        _selectedCustomer.value = customer
        // Switching customer invalidates any cached referral validation
        // (the discount was computed for the previous customer's order).
        if (previous?.id != customer?.id) {
            clearReferral()
            // D1: el descuento y la autorización eran del cliente anterior; un descuento que puso la cajera se queda.
            _cartState.update { c ->
                c.copy(
                    orderDiscount = c.orderDiscount.takeUnless { c.descuentoEsDelCliente },
                    descuentoEsDelCliente = false,
                    autorizacionDelDescuento = null,
                )
            }
        }
        viewModelScope.launch { revisarDescuentoDelCliente() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Referral capture (Plan 5A)
    // ─────────────────────────────────────────────────────────────────────

    /** Constant tag for the manual discount when sourced from a referral. */
    private val REFERRAL_DISCOUNT_SOURCE = "REFERRAL_NEW_CUSTOMER"

    fun onReferralCodeChange(code: String) {
        if (candado.rechaza("cambiar el código de referido") || necesitaInternet()) return
        _referralCode.value = code
        // Reset validation if the operator edits the code after a previous
        // validation result. Forces them to re-validate.
        if (_referralValidation.value !is ReferralCaptureUiState.Idle &&
            _referralValidation.value !is ReferralCaptureUiState.Validating
        ) {
            _referralValidation.value = ReferralCaptureUiState.Idle
        }
    }

    /**
     * Validates the typed code against the backend. Requires a selected
     * customer; without one, the UI shows a hint and never calls this.
     *
     * When the validation succeeds, the discount is applied to the cart
     * immediately so the totals update in real time before the operator
     * confirms the charge.
     */
    fun validateReferralCode() {
        if (necesitaInternet()) return
        val venueId = secureStorage.getVenueId() ?: run {
            Timber.w("🎁 validateReferralCode skipped: no venueId")
            return
        }
        val customer = _selectedCustomer.value ?: run {
            Timber.w("🎁 validateReferralCode skipped: no customer selected")
            return
        }
        val code = _referralCode.value.trim()
        if (code.isEmpty()) return
        if (candado.rechaza("validar el referido")) return

        viewModelScope.launch {
            _referralValidation.value = ReferralCaptureUiState.Validating
            val validacion = validateReferralUseCase(
                venueId = venueId,
                referralCode = code,
                newCustomerId = customer.id,
            )
            if (candado.rechaza("aplicar el referido") || necesitaInternet()) {
                // La respuesta llegó con la venta ya preparándose: su descuento no viajó. Se descarta y el cajero vuelve a validar.
                _referralValidation.value = ReferralCaptureUiState.Idle
                return@launch
            }
            validacion.fold(
                onSuccess = { result ->
                    when (result) {
                        is ReferralValidationResult.Valid -> {
                            _referralValidation.value = ReferralCaptureUiState.Valid(
                                referrerName = result.referrerName,
                                discountPercent = result.discountPercent,
                            )
                            applyReferralDiscount(result.discountPercent, result.referrerName)
                        }
                        is ReferralValidationResult.Invalid -> {
                            _referralValidation.value = ReferralCaptureUiState.Invalid(result.reason)
                            clearReferralDiscountOnly()
                        }
                    }
                },
                onFailure = { e ->
                    Timber.e(e, "🎁 Referral validate request failed")
                    _referralValidation.value = ReferralCaptureUiState.Invalid(
                        ReferralValidationResult.Reason.UNKNOWN,
                    )
                    clearReferralDiscountOnly()
                },
            )
        }
    }

    /** Resets referral state and any discount it applied. */
    fun clearReferral() {
        if (candado.rechaza("quitar el referido") || necesitaInternet()) return
        _referralCode.value = ""
        _referralValidation.value = ReferralCaptureUiState.Idle
        clearReferralDiscountOnly()
    }

    /** Clears the cart discount only if it originated from a referral. */
    private fun clearReferralDiscountOnly() {
        _cartState.update { current ->
            if (current.manualDiscountSource == REFERRAL_DISCOUNT_SOURCE) {
                current.copy(
                    manualDiscountCents = 0,
                    manualDiscountReason = null,
                    manualDiscountSource = null,
                )
            } else {
                current
            }
        }
    }

    /**
     * Applies the referral percent discount as a manual order-level discount
     * tagged with [REFERRAL_DISCOUNT_SOURCE]. Computed against the current
     * gross subtotal (matching what `ManualDiscountSubView` does for PERCENT
     * mode).
     */
    private fun applyReferralDiscount(discountPercent: Int, referrerName: String) {
        val pct = discountPercent.coerceIn(0, 100)
        if (pct == 0) return
        val subtotal = _cartState.value.subtotalCents
        if (subtotal <= 0) return
        val amount = (subtotal * pct / 100).coerceAtMost(subtotal)
        applyManualOrderDiscountInternal(
            amountCents = amount,
            reason = "Referido por $referrerName ($pct%)",
            source = REFERRAL_DISCOUNT_SOURCE,
        )
    }

    /**
     * Persists the PENDING Referral row right before the payment is
     * initiated. Idempotent: no-op when the validation state isn't Valid.
     *
     * Returns true on success (or no-op), false if the backend rejected
     * persistence. The caller can decide whether to block payment on a
     * failed capture; the recommendation is NOT to block (record locally
     * and let the user finish the charge), since the discount was already
     * applied to the cart.
     */
    suspend fun captureReferralOnPayment(orderId: String? = null): Boolean {
        if (!connectionStateManager.hasInternet()) return false
        val state = _referralValidation.value
        if (state !is ReferralCaptureUiState.Valid) return true
        val venueId = secureStorage.getVenueId() ?: return false
        val customer = _selectedCustomer.value ?: return false
        val staffVenueId = secureStorage.getStaffId() ?: return false
        val code = _referralCode.value.trim()
        if (code.isEmpty()) return false

        val result = captureReferralUseCase(
            venueId = venueId,
            referralCode = code,
            newCustomerId = customer.id,
            capturedByStaffVenueId = staffVenueId,
            intendedOrderId = orderId,
        )
        result.onFailure { e ->
            // If the validation reason changed between validate + capture,
            // surface it in the UI so the next attempt sees the new reason.
            (e as? ReferralValidationException)?.let { ex ->
                _referralValidation.value = ReferralCaptureUiState.Invalid(ex.reason)
                clearReferralDiscountOnly()
            }
            Timber.e(e, "🎁 captureReferralOnPayment failed")
        }
        return result.isSuccess
    }

    fun updateCustomerSearchQuery(query: String) {
        _customerSearchQuery.value = query
        searchCustomers(query)
    }

    fun clearCustomerSearch() {
        _customerSearchQuery.value = ""
        _customerResults.value = emptyList()
    }

    /**
     * Fires off a search against [CustomerRepository.searchCustomers]. The repo
     * requires at least 2 characters for a general query; below that we
     * fall back to recent customers so the sheet always shows something useful
     * when it opens. Cancels any in-flight search on each call.
     */
    private var customerSearchJob: kotlinx.coroutines.Job? = null
    private fun searchCustomers(query: String) {
        if (necesitaInternet()) return
        val venueId = secureStorage.getVenueId() ?: return
        customerSearchJob?.cancel()
        customerSearchJob = viewModelScope.launch {
            _isSearchingCustomers.value = true
            val trimmed = query.trim()
            val result = if (trimmed.length < 2) {
                customerRepository.getRecentCustomers(venueId)
            } else {
                customerRepository.searchCustomers(venueId, trimmed)
            }
            result.fold(
                onSuccess = { if (connectionStateManager.hasInternet()) _customerResults.value = it },
                onFailure = { e ->
                    Timber.e(e, "🛒 Customer search failed")
                    _customerResults.value = emptyList()
                },
            )
            _isSearchingCustomers.value = false
        }
    }

    /**
     * Eagerly load recent customers so the sheet has content when it opens.
     * Called by the UI when the sheet appears.
     */
    fun loadRecentCustomers() {
        if (_customerResults.value.isEmpty() && _customerSearchQuery.value.isBlank()) {
            searchCustomers("")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Search and product cache
    // ─────────────────────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private var refreshJob: Job? = null
    private var refreshVenueId: String? = null
    private var contextoDelCatalogo: Pair<String?, String?>? = null
    private var versionDelCatalogo = 0
    private var generacionDelContexto = 0

    companion object {
        /**
         * Dónde se lee y se mapea el catálogo guardado (Gson de extras y variantes): fuera del hilo principal, como Órdenes. Vive en el
         * companion porque el primer refresco sale del `init`; las pruebas lo ponen en `Dispatchers.Unconfined` en `@Before`.
         */
        internal var despachadorDelCatalogo: CoroutineDispatcher = Dispatchers.Default

        /** D1: el permiso del descuento del cliente; el mismo nombre en la sesión y en el PIN del gerente. */
        private const val PERMISO_DESCUENTO = "discounts:apply"
    }

    /**
     * Catálogo guardado primero, luego la red (como `MenuViewModel.performProductsRefresh`). Pinta lo guardado del negocio ACTUAL
     * (Room filtra por `venue_id`) aunque venga vacío: tras cambiar de negocio nunca se queda pintado el anterior. Con red lo
     * refresca [CatalogoGuardado] (que también lo guarda); sin red no se pregunta, y si la red falla lo pintado se queda. Mientras
     * lee y pregunta, [isLoading] gira (el botón de refrescar; la cuadrícula sólo tapa la pantalla si no hay nada pintado).
     * Seguro de llamar varias veces: con el mismo negocio refrescando no lo reinicia; con otro negocio cancela el anterior.
     */
    fun refreshProducts() {
        if (contextoDelCatalogo != sesionActual()) actualizarEstadoDeCobrar()
        val contexto = sesionActual()
        val venueId = contexto.first ?: return
        if (refreshJob?.isActive == true && refreshVenueId == venueId) return
        refreshJob?.cancel()
        refreshVenueId = venueId
        val version = ++versionDelCatalogo
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                try {
                    val (guardados, categoriasGuardadas) = withContext(despachadorDelCatalogo) {
                        productDao.getAllProducts(venueId).toDomain() to productCategoryDao.getAllCategories(venueId).toCategoryDomain()
                    }
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto) return@launch
                    _products.value = guardados
                    _categories.value = categoriasGuardadas
                    _errorCatalogo.value = null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto || versionDelCatalogo != version) return@launch
                    Timber.e(e, "🛒 No se pudo leer el catálogo guardado")
                    _errorCatalogo.value = "No se pudo leer el catálogo guardado. Se conserva lo anterior; vuelve a intentar."
                    _aviso.value = _errorCatalogo.value
                }
                if (!connectionStateManager.hasInternet()) return@launch
                var productosRecuperados = false
                try {
                    val list = catalogoGuardado.refrescar(venueId).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto) return@launch
                    _products.value = list
                    productosRecuperados = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto || versionDelCatalogo != version) return@launch
                    Timber.w(e, "🛒 Failed to refresh products (se queda el catálogo guardado)")
                    if (_products.value.isEmpty() && _errorCatalogo.value == null) {
                        _errorCatalogo.value = "No se pudo actualizar el catálogo. Se conserva lo guardado; vuelve a intentar."
                    }
                }
                try {
                    val list = productRepository.getCategories(venueId).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto) return@launch
                    productCategoryDao.upsertCategories(list.toCategoryEntities(venueId, System.currentTimeMillis()))
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto) return@launch
                    _categories.value = list
                    if (productosRecuperados) {
                        if (_aviso.value == _errorCatalogo.value) _aviso.value = null
                        _errorCatalogo.value = null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto || versionDelCatalogo != version) return@launch
                    Timber.w(e, "🛒 Failed to refresh categories (se quedan las guardadas)")
                }
            } finally {
                // Una lectura cancelada no apaga el giro de la que la reemplazó.
                if (versionDelCatalogo == version) _isLoading.value = false
            }
        }
    }

    /**
     * La variante `id` tal como está en el catálogo de AHORA, o `null` si ya no está. La hoja de variantes entrega la copia con la que se
     * abrió; antes de actuar se vuelve a leer aquí, para que un refresco con la hoja abierta (extras nuevos, talla agotada) cuente.
     * A diferencia de Órdenes, una talla agotada SÍ se devuelve: Cobro rápido la muestra y al elegirla avisa que no está disponible.
     */
    fun productoVigente(id: String): Product? = _products.value.firstOrNull { it.id == id }

    /**
     * Busca un código escaneado. Con red pregunta al servidor (el producto trae `catalogAt` de la raíz de la respuesta); sin red, o si
     * la red falla, lo busca por SKU o GTIN en el catálogo guardado del negocio actual (con su `catalog_at` guardado).
     * [onResult] recibe el producto (o `null`) y si se buscó en el catálogo guardado: así la pantalla no dice «no encontrado» cuando
     * sólo miró lo guardado. Un fallo de lectura publica un aviso y no llama a [onResult]; una cancelación se propaga.
     */
    fun findProductByBarcode(barcode: String, onResult: (producto: Product?, delCatalogoGuardado: Boolean) -> Unit) {
        val contexto = sesionActual()
        val venueId = contexto.first ?: return
        val generacion = generacionDelContexto
        viewModelScope.launch {
            if (connectionStateManager.hasInternet()) {
                try {
                    val producto = productRepository.getProductByBarcode(venueId, barcode).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() == contexto && generacionDelContexto == generacion) onResult(producto, false)
                    return@launch
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    if (sesionActual() != contexto || generacionDelContexto != generacion) return@launch
                    Timber.w(e, "🛒 Barcode lookup failed; se busca en el catálogo guardado")
                }
            }
            try {
                val guardado = withContext(despachadorDelCatalogo) { productDao.getProductByCodigo(venueId, barcode)?.toDomain() }
                currentCoroutineContext().ensureActive()
                if (sesionActual() == contexto && generacionDelContexto == generacion) onResult(guardado, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                if (sesionActual() != contexto || generacionDelContexto != generacion) return@launch
                Timber.e(e, "🛒 Barcode lookup in saved catalog failed")
                _aviso.value = "No se pudo leer el catálogo guardado para buscar el código. Vuelve a escanear."
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Saved carts (set aside / resume)
    // ─────────────────────────────────────────────────────────────────────

    /** Persists the current cart for later resumption. Returns true if saved. */
    suspend fun saveCurrentCart(name: String? = null): Boolean {
        val current = _cartState.value
        if (current.isEmpty) return false
        val snapshot = SavedCart(
            id = UUID.randomUUID().toString(),
            name = name?.takeIf { it.isNotBlank() }
                ?: "Carrito ${current.itemCount} item${if (current.itemCount == 1) "" else "s"}",
            items = current.items.map { it.toSavedCartItem() },
            orderDiscount = current.orderDiscount?.toSaved(),
            manualDiscountCents = current.manualDiscountCents,
            manualDiscountReason = current.manualDiscountReason,
            orderNote = current.orderNote,
            orderTaxPercent = current.orderTaxPercent,
        )
        savedCartsRepository.save(snapshot)
        return true
    }

    suspend fun deleteSavedCart(id: String) = savedCartsRepository.delete(id)

    // ─────────────────────────────────────────────────────────────────────
    // Shortcuts (Mosaico) — venue-scoped, persisted in Room
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Assign or reassign a shortcut at [position]. If a shortcut already exists
     * at that position for the current venue, it is replaced (the unique index
     * on `(venue_id, position)` enforces this at the DB level).
     */
    fun assignShortcut(product: Product, position: Int) {
        val venueId = secureStorage.getVenueId() ?: run {
            Timber.w("🛒 assignShortcut skipped: no venueId")
            return
        }
        viewModelScope.launch {
            // Query the repo directly instead of `shortcuts.value`. The cached
            // StateFlow uses `SharingStarted.Lazily` so its value may be stale
            // when the UI isn't actively collecting (e.g. during the brief
            // window the user is on the Configurar tab without having opened
            // the Shortcuts tab first).
            val existing = mosaicRepository.get(venueId).firstOrNull { it.position == position }
            mosaicRepository.upsert(
                MosaicShortcut(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    venueId = venueId,
                    productId = product.id,
                    position = position,
                    label = product.name,
                    colorHex = product.categoryColor,
                ),
            )
        }
    }

    fun removeShortcut(id: String) {
        viewModelScope.launch { mosaicRepository.delete(id) }
    }

    /**
     * Toque de un atajo: busca el producto en el catálogo pintado (guardado o de la red) y lo agrega. Si tiene algún grupo de extras
     * OBLIGATORIO no lo agrega: lo devuelve para que la pantalla abra la misma hoja de extras que la cuadrícula (sin sus obligatorios
     * se vendería incompleto). `null` = ya se agregó, o el producto ya no está en el catálogo.
     */
    fun addShortcutToCart(shortcut: MosaicShortcut): Product? {
        // Tampoco abre la hoja de extras: lo que entrara ahí saldría fuera de la venta que se está preparando.
        if (candado.rechaza("agregar un atajo")) return null
        val product = _products.value.firstOrNull { it.id == shortcut.productId } ?: run {
            Timber.w("🛒 Shortcut ${shortcut.id} references missing product ${shortcut.productId}")
            return null
        }
        if (product.modifierGroups.any { it.effectiveMinSelections > 0 }) return product
        addProduct(product)
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Payment / order handoff (Phase 5)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inspects the current cart and produces the payload the navigation layer
     * needs to open `PaymentScreen`.
     *
     * Routing ([caminoDelCobro], D3 del spec «Cobrar sin red»):
     * - Sólo importes con red → [PaymentNavigationPayload.Fast] (no order is created, mirrors today's "Pago Rápido" flow exactly).
     * - Con productos y red → la orden completa en `/orders/with-items`, como hoy. La llave (`externalId`) y el carrito quedan en el
     *   aparato ANTES del POST ([VentaSinRedRepository.guardarCarrito]); si no se pudieron escribir, no se sale a la red.
     *   Si el servidor no contesta ([pasaALaVentaLocal]) ⇒ venta local con la MISMA llave; otro error (4xx) ⇒ como hoy.
     * - Sin red (o sólo importes en $0) → venta local: [PaymentNavigationPayload.VentaLocal] (sólo efectivo) o, en $0, la fila ya
     *   guardada sin abrir la pantalla de cobro ([PaymentNavigationPayload.VentaEnCeroGuardada]).
     *
     * Mientras corre, el carrito no cambia ([ventaEnPreparacion]).
     */
    suspend fun prepareForPayment(): Result<PaymentNavigationPayload> = candado.mientras { prepararCobro() }

    private suspend fun prepararCobro(): Result<PaymentNavigationPayload> {
        val current = _cartState.value
        errorParaCobrar(current)?.let { return Result.failure(IllegalStateException(it)) }
        if (current.isEmpty) {
            return Result.failure(IllegalStateException("Cart is empty"))
        }
        if (current.taxCents != 0) {
            return Result.failure(IllegalStateException("El impuesto no está disponible en Cobrar V1"))
        }
        // Un «vaciar» de hace un instante termina de borrar el carrito a medias antes de que esta venta escriba el suyo.
        borradoDelCarrito?.join()

        val amountPesos = (current.totalCents / 100.0).toString()
        val hayRed = connectionStateManager.hasInternet()
        // 🔴 D5: el cupón necesita al SERVIDOR: sin red no se guarda como venta local (se subiría como un descuento libre), se dice. Y un
        // cobro rápido no tiene orden donde ligarlo: con cupón siempre hay orden, aunque sean puros importes. Igual con el descuento del
        // cliente (D1): se registra como lo que es, en su orden.
        if (current.couponCode != null && !hayRed) return Result.failure(IllegalStateException(TEXTO_CUPON_SIN_RED))
        val llevaOrden = current.hasProductItems || current.couponCode != null || current.descuentoEsDelCliente
        return when (caminoDelCobro(llevaOrden, current.totalCents, hayRed)) {
            CaminoDelCobro.RAPIDO -> {
                // Sin orden y sin carrito durable (como hoy). Un carrito a medias de un intento anterior ya no es esta venta.
                secureStorage.getVenueId()?.let { ventaSinRed.borrarCarrito(it) }
                // No order is created here, but the referral can still be captured with no `intendedOrderId`. The qualifying-order
                // webhook on the backend will pick it up via the customerId attached to the fast-payment Order shadow row.
                capturarReferido(orderId = null)
                Result.success(PaymentNavigationPayload.Fast(amountPesos))
            }
            CaminoDelCobro.EN_LINEA -> cobrarEnLinea(current, amountPesos)
            CaminoDelCobro.LOCAL -> {
                // Una venta local se reproduce por /mobile SIN token: una cortesía sin permiso la rechazaría el servidor. El PIN necesita red.
                if (llevaCortesia(current) && !permissionsRepository.hasPermission("tpv-orders:comp")) {
                    return Result.failure(IllegalStateException(
                        (if (hayRed) "" else "Sin conexión: ") + "el PIN del gerente necesita internet. Quita la cortesía o cobra cuando vuelva la red",
                    ))
                }
                // Sólo importes en $0 con red: hasta hoy salía como Fast y capturaba el referido sin orden; eso se conserva.
                if (hayRed && !current.hasProductItems) capturarReferido(orderId = null)
                ventaLocal(current, amountPesos, llaveYaUsada = null)
            }
        }
    }

    private suspend fun cobrarEnLinea(current: CartState, amountPesos: String): Result<PaymentNavigationPayload> {
        val venueId = secureStorage.getVenueId()
            ?: return Result.failure(IllegalStateException("No venueId in secure storage"))
        val staffId = staffDe(current) ?: return Result.failure(IllegalStateException("No staffId in secure storage"))
        val cliente = _selectedCustomer.value
        val pedido = pedidoEnLinea(current, staffId, cliente?.id)
        val llave = llaveDelIntento(pedido)
        // ANTES del POST: si el proceso muere esperando la respuesta, al reabrir Cobrar vuelve este carrito con ESTA llave y el reintento
        // encuentra la misma orden por `externalId`. Si no quedó escrito, no se sale a la red.
        if (!ventaSinRed.guardarCarrito(venueId, llave, current, cliente)) {
            return Result.failure(IllegalStateException(TEXTO_NO_SE_GUARDO_LA_VENTA))
        }
        val creada = crearOrdenEnLinea(venueId, pedido, llave)
        val fallo = creada.exceptionOrNull()
        if (fallo != null) {
            if (!pasaALaVentaLocal(fallo)) return Result.failure(fallo) // 4xx, 500…: como hoy (el carrito y su llave se quedan)
            // D5: el carrito y su llave se quedan: si la creación sí llegó, el reintento encuentra la misma orden.
            if (current.couponCode != null) return Result.failure(IllegalStateException(TEXTO_CUPON_SIN_RED))
            Timber.w(fallo, "🛒 El servidor no contestó al crear la orden: sigue como venta local con la misma llave")
            return ventaLocal(current, amountPesos, llaveYaUsada = llave)
        }
        val order = creada.getOrThrow()
        // La venta en línea terminó de prepararse: la orden ya existe y el cobro sigue en la pantalla de pago.
        ventaSinRed.borrarCarrito(venueId)
        // Persist the referral right before we hand off to PaymentScreen. Failures are swallowed: the cart already has the discount
        // applied, so blocking the charge over a transient backend failure would punish the customer for a backend hiccup.
        capturarReferido(orderId = order.id)
        // D5 (Codex r1 #7): con cupón manda el total del SERVIDOR, que acepta un centavo de redondeo: ése se cobra, y sólo es cortesía si
        // allá quedó en $0 (si no, la cuenta se quedaba debiendo $0.01 con la pantalla diciendo «completada»).
        val totalDelServidor = order.total.takeIf { current.couponCode != null }
        val gratis = totalDelServidor?.let { it.signum() == 0 } ?: (current.totalCents == 0)
        val importe = totalDelServidor?.toPlainString() ?: amountPesos
        return Result.success(
            if (gratis) {
                PaymentNavigationPayload.CompletedFreeCart(
                    amountPesosString = importe,
                    orderId = order.id,
                    orderNumber = order.orderNumber,
                )
            } else {
                PaymentNavigationPayload.Order(
                    amountPesosString = importe,
                    orderId = order.id,
                    orderNumber = order.orderNumber,
                    wasPayLaterOrder = false,
                )
            },
        )
    }

    /**
     * 💵 La venta local: el pedido para mobile (centavos, `isOfflineReplay`) y los renglones del ticket, con [llaveYaUsada] si ya
     * hubo un intento en línea (o la del carrito si no). En $0 se guarda YA como fila y no abre la pantalla de cobro; si no, el carrito a
     * medias se guarda antes de navegar con la venta (sólo efectivo) y la pantalla de cobro inserta la fila (C3).
     */
    private suspend fun ventaLocal(current: CartState, amountPesos: String, llaveYaUsada: String?): Result<PaymentNavigationPayload> {
        // D1 (diseño v5): el descuento del cliente nunca entra a una venta local. Ésta es la única puerta (sin red, y la caída de
        // `with-items` por red), como la cortesía sin red: se dice y la cajera lo quita o espera la red.
        if (current.descuentoEsDelCliente) {
            return Result.failure(IllegalStateException(textoDescuentoSinRed()))
        }
        val venueId = secureStorage.getVenueId()
            ?: return Result.failure(IllegalStateException("No venueId in secure storage"))
        val staffId = staffDe(current) ?: return Result.failure(IllegalStateException("No staffId in secure storage"))
        val cliente = _selectedCustomer.value
        val llave = llaveYaUsada ?: llaveDelIntento(pedidoEnLinea(current, staffId, cliente?.id))
        if (!ventaSinRed.guardarCarrito(venueId, llave, current, cliente)) {
            return Result.failure(IllegalStateException(TEXTO_NO_SE_GUARDO_LA_VENTA))
        }
        val shiftId = try {
            ventaSinRed.turnoDeLaVenta(venueId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "🛒 No se pudo leer la caja guardada para preparar la venta")
            return Result.failure(IllegalStateException("No se pudo leer la caja guardada. La venta se conserva; vuelve a intentar.", e))
        }
        val productos = _products.value.associateBy { it.id }
        val ahora = System.currentTimeMillis()
        val venta = VentaLocal(
            llave = llave,
            venueId = venueId,
            staffId = staffId,
            shiftId = shiftId,
            createdAtLocal = ahora,
            totalCents = current.totalCents,
            pedidoJson = armarPedidoParaMobile(current, llave, staffId, shiftId, ahora, productos, customerId = cliente?.id),
            renglones = renglonesDeTicket(current, llave, productos),
        )
        if (current.totalCents == 0) {
            return ventaSinRed.guardarVentaEnCero(venta).fold(
                onSuccess = {
                    // El carrito ya terminó: una recreación de la pantalla conserva SU ticket, nunca permite cobrarlo otra vez.
                    _ventaEnCeroGuardada.value = venta
                    reiniciarCarrito()
                    Result.success(PaymentNavigationPayload.VentaEnCeroGuardada(amountPesos, venta))
                },
                onFailure = { e ->
                    Timber.e(e, "🛒 No se pudo guardar la venta en \$0")
                    Result.failure(if (e is VentaLocalYaGuardadaException) e else IllegalStateException(TEXTO_NO_SE_GUARDO_LA_VENTA, e))
                },
            )
        }
        return Result.success(PaymentNavigationPayload.VentaLocal(amountPesos, Gson().toJson(venta)))
    }

    private suspend fun capturarReferido(orderId: String?) {
        runCatching { captureReferralOnPayment(orderId = orderId) }
            .onFailure { e -> Timber.e(e, "🎁 captureReferralOnPayment threw (orderId=$orderId)") }
    }

    /**
     * Creates an order with the current cart items linked to the selected
     * customer, leaving it `paymentStatus = PENDING`. The order shows up in
     * the dashboard's "Cuentas por Cobrar" view and in the operator's
     * pay-later list. No PaymentScreen navigation — operator goes back to a
     * fresh cart after creation.
     *
     * Requires `_selectedCustomer != null` (the whole point is binding the
     * debt to a customer). Cart must have items (no $0 free-cart in this path).
     * Mientras corre, el carrito no cambia ([ventaEnPreparacion]).
     *
     * **Known drift**: when the dashboard later settles this order via the
     * existing `settleOrder` endpoint, inventory is NOT deducted (the
     * inventory hook only fires on the regular fully-paid path). Operations
     * accepts this until `settleOrder` is fixed in a separate ticket. The
     * order itself is faithful — items, discounts, cortesía all persisted as
     * with regular Cobrar orders.
     */
    suspend fun createPayLaterOrder(): Result<Order> = candado.mientras {
        val current = _cartState.value
        val error = if (!connectionStateManager.hasInternet()) {
            if (current.couponCode != null) TEXTO_CUPON_SIN_RED else "Necesita internet"
        } else {
            errorParaCobrar(current)
        }
        when {
            error != null -> Result.failure(IllegalStateException(error))
            current.isEmpty -> Result.failure(IllegalStateException("El carrito está vacío"))
            _selectedCustomer.value == null -> Result.failure(IllegalStateException("Selecciona un cliente para Pagar después"))
            current.totalCents == 0 -> Result.failure(
                IllegalStateException("El total es \$0 — usa cortesía/cobrar normal en lugar de Pagar después"),
            )
            // The backend createOrderWithItems endpoint already accepts customerId
            // and leaves the order in PENDING + PENDING when total > 0. That's
            // exactly the pay-later shape. No separate "leave open" flag needed.
            // D5 (Codex r2 #4): si el servidor no contestó con un cupón, se dice lo del cupón, como en Cobrar.
            else -> createOrderWithCurrentItems().recoverCatching { fallo ->
                if (fallo.message == TEXTO_NO_CONFIRMADA_COBRAR) throw Exception("No pudimos confirmar la venta. Inténtalo otra vez")
                throw if (current.couponCode != null && pasaALaVentaLocal(fallo)) IllegalStateException(TEXTO_CUPON_SIN_RED, fallo) else fallo
            }
        }
    }

    /**
     * Creates the full order in one backend call. This is intentionally only
     * used by the new Cobrar flow; legacy order screens keep their existing
     * create-order + add-items path.
     */
    private suspend fun createOrderWithCurrentItems(): Result<Order> {
        val venueId = secureStorage.getVenueId() ?: return Result.failure(
            IllegalStateException("No venueId in secure storage"),
        )
        val current = _cartState.value
        val staffId = staffDe(current) ?: return Result.failure(IllegalStateException("No staffId in secure storage"))
        val pedido = pedidoEnLinea(current, staffId, _selectedCustomer.value?.id)
        return crearOrdenEnLinea(venueId, pedido, llaveDelIntento(pedido))
    }

    private fun staffDe(current: CartState): String? =
        current.selectedStaffId.takeIf { it.isNotBlank() } ?: secureStorage.getStaffId()

    /** El cuerpo de `/tpv/.../orders/with-items` del carrito, SIN `externalId` (es también la huella de la venta). */
    private fun pedidoEnLinea(current: CartState, staffId: String, customerId: String?): TpvCreateOrderWithItemsRequest {
        val requestItems = current.items.map { item ->
            val productId = (item.type as? CartItemType.ProductItem)?.productId
            TpvCreateOrderWithItemsItem(
                productId = productId,
                name = if (productId == null) item.name else null,
                quantity = item.quantity,
                unitPrice = if (productId == null) item.effectiveUnitPriceCents.centsToPesos() else null,
                modifierIds = item.selectedModifiers.map { it.modifierId },
                notes = item.itemNote,
                isCortesia = item.isCortesia,
                cortesiaReason = item.cortesiaReason,
                itemDiscountId = item.itemDiscountId,
            )
        }

        val grossSubtotalCents = current.items.sumOf { it.grossLineCents() }

        // When `orderDiscount` is null but `manualDiscountCents` > 0, the
        // freeform manual discount travels as `discount`; backend treats it
        // as an unbacked reduction with no Discount entity. When predefined
        // is set, `discount` may stay 0 — the service recomputes from the
        // discount's value/type against the gross subtotal.
        val cupon = current.couponCode
        val discountForWire = when {
            cupon != null -> current.discountCents // el SERVIDOR lo recalcula y lo compara: si el cupón cambió, lo dice
            current.orderDiscount != null -> 0 // backend recomputes from id
            else -> current.manualDiscountCents
        }

        return TpvCreateOrderWithItemsRequest(
            items = requestItems,
            staffId = staffId,
            orderType = OrderType.TAKEOUT,
            source = "TPV",
            customerId = customerId,
            discount = discountForWire.centsToPesos(),
            // El id de un cupón no es de un descuento del catálogo: un id de cupón aquí era el 400 que no dejaba cobrar (D5).
            orderDiscountId = current.orderDiscount?.id?.takeIf { cupon == null && it.isNotBlank() },
            couponCode = cupon,
            taxAmount = BigDecimal.ZERO,
            tip = BigDecimal.ZERO,
            subtotal = grossSubtotalCents.centsToPesos(),
            total = current.totalCents.centsToPesos(),
            note = current.orderNote,
        )
    }

    /**
     * La llave de la venta (= `externalId` de la orden = llave de la venta local). Idempotencia por venta (retry-safety): si el pedido
     * es IDÉNTICO al del intento anterior (la respuesta se perdió y el cajero volvió a picar, o el carrito volvió tras morir el proceso),
     * se reusa la misma llave y el backend devuelve la orden original en vez de crear otra — que en free-cart ($0) significaba segunda
     * deducción de stock. Si el carrito CAMBIÓ, la huella difiere y nace llave nueva: jamás se puede recibir de vuelta una orden con los
     * items viejos. Al CREARSE la orden (o guardarse la venta) la llave se olvida — reusarla entre dos ventas devolvería la anterior.
     */
    private fun llaveDelIntento(pedido: TpvCreateOrderWithItemsRequest): String {
        val huella = pedido.toString()
        return orderAttemptExternalId.takeIf { orderAttemptFingerprint == huella }
            ?: UUID.randomUUID().toString().also {
                orderAttemptExternalId = it
                orderAttemptFingerprint = huella
            }
    }

    /**
     * Crea la orden. Si la venta lleva cortesía (una línea, o un descuento manual que deja los productos en $0) y la cajera no tiene
     * el permiso, pide el PIN de un gerente y manda su token de un solo uso en `X-Permission-Override`. Un 403 `orders:comp` pide el
     * PIN forzado y reintenta UNA vez con la MISMA petición (mismo `externalId`: si la primera sí creó la orden, el servidor la devuelve).
     */
    private val TEXTO_NO_CONFIRMADA_COBRAR = "No pudimos confirmar la venta. Toca Cobrar otra vez"
    private val TEXTO_VENTA_EN_DUDA = "No pudimos confirmar la venta. Revisa las órdenes antes de volver a cobrar"

    private suspend fun crearOrdenEnLinea(venueId: String, pedido: TpvCreateOrderWithItemsRequest, llave: String): Result<Order> {
        val peticion = pedido.copy(externalId = llave)
        var token: String? = null
        // «Sin autorizar» sólo si sabemos que esta llave nunca salió. Si ya salió (o volvió de disco), la orden pudo crearse y se dice.
        fun sinAutorizar(texto: String) =
            Result.failure<Order>(Exception(if (llave == llaveMandada || llave == llaveRecuperada) TEXTO_VENTA_EN_DUDA else texto))
        suspend fun enviar(): Result<Order> {
            llaveMandada = llave // ANTES de la red
            return orderRepository.createOrderWithItems(venueId = venueId, request = peticion, overrideToken = token)
        }
        if (llevaCortesia(_cartState.value)) {
            token = when (val auth = pedirPinDeCortesia(venueId, forzar = false)) {
                is Autorizacion.ConPin -> auth.token
                Autorizacion.NoHaceFalta -> null
                Autorizacion.Cancelada -> return sinAutorizar("Cortesía sin autorizar")
                is Autorizacion.Negada -> return sinAutorizar(auth.texto)
                null -> return sinAutorizar("No se pudo autorizar la cortesía")
            }
        }
        var resultado = enviar()
        val rechazo = resultado.exceptionOrNull() as? PermisoRequeridoException
        if (rechazo?.required == "orders:comp") {
            token = when (val auth = pedirPinDeCortesia(venueId, forzar = true)) {
                is Autorizacion.ConPin -> auth.token
                Autorizacion.NoHaceFalta -> return Result.failure(Exception(textoDePermiso(rechazo.required, rechazo.message.orEmpty())))
                Autorizacion.Cancelada -> return sinAutorizar("Cortesía sin autorizar")
                is Autorizacion.Negada -> return sinAutorizar(auth.texto)
                null -> return sinAutorizar("No se pudo autorizar la cortesía")
            }
            resultado = enviar()
        }
        (resultado.exceptionOrNull() as? PermisoRequeridoException)?.let {
            return Result.failure(Exception(textoDePermiso(it.required, it.message.orEmpty())))
        }
        // Con token, nada se encola: la reproducción local va por /mobile sin token y daría 403 con el efectivo ya cobrado.
        // Volver a tocar Cobrar es seguro (misma llave: el servidor devuelve la orden si sí se creó).
        if (token != null && resultado.exceptionOrNull()?.let { pasaALaVentaLocal(it) } == true) {
            return Result.failure(Exception(TEXTO_NO_CONFIRMADA_COBRAR))
        }
        return resultado.onSuccess { olvidarLlave() }
    }

    /** `null` = el coordinador falló (no se pudo autorizar). */
    private suspend fun pedirPinDeCortesia(venueId: String, forzar: Boolean): Autorizacion? = try {
        autorizacionDeGerente.paraCortesia(venueId, "Para cobrar con cortesía", forzar)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "No se pudo autorizar la cortesía")
        null
    }

    /** Una línea de cortesía, o un descuento manual que deja los productos en $0 (en centavos, del carrito). */
    private fun llevaCortesia(c: CartState): Boolean =
        c.items.any { it.isCortesia } ||
            (c.manualDiscountCents > 0 && c.subtotalCents - c.manualDiscountCents <= 0)

    // ─────────────────────────────────────────────────────────────────────
    // 💵 «Cobrar» sin red: el carrito a medias y la venta en $0
    // ─────────────────────────────────────────────────────────────────────

    /** «Imprimir ticket» del diálogo «Venta en $0 guardada»: el impresor de la pantalla de éxito, con los renglones de la venta. */
    suspend fun imprimirVentaGuardada(venta: VentaLocal): Result<Unit> = impresoraDeVentaGuardada.imprimir(venta)

    /** El cajero ya vio que la venta guardada terminó (`exito_mostrado = 1`): al reabrir no se le vuelve a avisar. */
    fun ventaGuardadaVista(llave: String) {
        val mostrada = _ventaSinMostrar.value?.venta ?: _ventaEnCeroGuardada.value
        if (mostrada?.llave != llave || mostrada.venueId != secureStorage.getVenueId()) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            candado.mientras {
                try {
                    ventaSinRed.marcarVentaVista(llave)
                    if (secureStorage.getVenueId() == mostrada.venueId) {
                        val siguiente = ventaSinRed.ultimaVentaSinMostrar(mostrada.venueId)
                        if (secureStorage.getVenueId() == mostrada.venueId) _ventaSinMostrar.value = siguiente
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (secureStorage.getVenueId() == mostrada.venueId) {
                        _errorRecuperacion.value = "No se pudo confirmar la venta guardada. Se conserva; vuelve a intentar."
                    }
                    Result.failure(e)
                }
            }
            // Cambiar de sucursal pudo esperar este candado. Ahora se recupera la que sigue activa.
            if (secureStorage.getVenueId() != mostrada.venueId) actualizarEstadoDeCobrar()
        }
    }

    /**
     * Al abrir Cobrar: si quedó un carrito a medias (guardado antes de la red, sin fila de pago, de menos de 24 h), vuelve con SU llave
     * y se avisa. Queda a nombre de quien tiene la sesión ahora (precedente del founder, 20-oct: la venta es de quien la termina). Si
     * ya hay otra venta en el carrito, no se pisa.
     */
    private suspend fun restaurarCarritoAMedias() {
        val venueId = secureStorage.getVenueId() ?: return
        // Al volver con el MISMO ViewModel (Atrás desde pago), su copia en memoria tampoco se puede cobrar otra vez.
        // Sólo se consume si sigue siendo el mismo intento: un carrito nuevo o editado conserva sus artículos.
        val llave = orderAttemptExternalId
        val actual = _cartState.value
        val staff = staffDe(actual)
        if (llave != null && staff != null && orderAttemptFingerprint == pedidoEnLinea(actual, staff, _selectedCustomer.value?.id).toString() &&
            pendingPaymentDao.existeFilaConLlave(llave) && secureStorage.getVenueId() == venueId) {
            _cartState.value = CartState()
            _selectedCustomer.value = null
            _avisoDescuentoDelCliente.value = null
            _referralCode.value = ""
            _referralValidation.value = ReferralCaptureUiState.Idle
            olvidarLlave()
        }
        val aMedias = ventaSinRed.carritoParaRestaurar(venueId) ?: return
        if (secureStorage.getVenueId() != venueId) return
        if (!_cartState.value.isEmpty) {
            Timber.w("🛒 La venta a medias no se restaura: ya hay otra venta en el carrito")
            return
        }
        val sesion = secureStorage.getStaffId()?.takeIf { it.isNotBlank() }
        val cart = if (sesion == null) aMedias.cart else aMedias.cart.copy(
            selectedStaffId = sesion,
            selectedStaffName = secureStorage.getStaffName().orEmpty().ifBlank { "Staff" },
        )
        val staffId = staffDe(cart) ?: return
        _cartState.value = cart
        _selectedCustomer.value = aMedias.cliente
        // Su llave para su carrito: el siguiente «Cobrar» sin cambios reusa la misma (ver [llaveDelIntento]).
        orderAttemptExternalId = aMedias.llave
        llaveRecuperada = aMedias.llave // pudo salir antes de morir el proceso: no sabemos
        orderAttemptFingerprint = pedidoEnLinea(cart, staffId, aMedias.cliente?.id).toString()
        // D1: quien la retoma puede no tener el permiso; sólo el aviso (el carrito y su llave no cambian), y «Cobrar» lo vuelve a revisar.
        revisarDescuentoDelCliente(soloAviso = true)
        _aviso.value = AVISO_VENTA_RECUPERADA
        Timber.i("🛒 Venta a medias restaurada (${cart.itemCount} artículos)")
    }

    // Va AL FINAL de la clase a propósito: las propiedades de arriba (la llave del intento, el candado) ya están inicializadas cuando
    // esto corre, aunque la lectura no espere nada (en las pruebas el aparato está en memoria).
    init {
        actualizarEstadoDeCobrar()
        refreshProducts()
    }

    private fun sesionActual() = secureStorage.getVenueId() to secureStorage.getStaffId()

    private fun necesitaInternet(): Boolean {
        if (connectionStateManager.hasInternet()) return false
        _aviso.value = "Necesita internet"
        return true
    }

    private suspend fun errorParaCobrar(cart: CartState): String? {
        if (!recuperacionVerificada || _errorRecuperacion.value != null || venueObservado != secureStorage.getVenueId()) {
            return "No se ha podido recuperar la venta anterior. Vuelve a intentar antes de cobrar."
        }
        if (_ventaSinMostrar.value != null) return "Revisa primero la venta que ya quedó cobrada y guardada."
        val sesion = sesionActual()
        if (cart.orderDiscount == null && cart.manualDiscountCents > 0 && !permissionsRepository.hasPermission("discounts:apply")) {
            return "La venta tiene un descuento y tu sesión no tiene permiso. Pide apoyo al gerente."
        }
        // D1: un carrito restaurado (otra cajera, permiso revocado) no se cobra rebajado sin permiso ni PIN. Sin red el PIN no se
        // puede pedir: se dice lo de la red (el freno de la venta local diría lo mismo).
        if (cart.descuentoEsDelCliente && cart.autorizacionDelDescuento == null && !permissionsRepository.hasPermission("discounts:apply")) {
            return if (connectionStateManager.hasInternet()) {
                "Tu puesto no puede aplicar el descuento de ${nombreDelCliente()}. Pide autorización o quítalo."
            } else {
                textoDescuentoSinRed()
            }
        }
        return if (sesion == sesionActual()) null else "La sesión cambió. Vuelve a Cobrar para continuar."
    }

    private fun nombreDelCliente(): String = nombreParaElDescuento(_selectedCustomer.value)

    private fun textoDescuentoSinRed() =
        "Sin conexión: el descuento de ${nombreDelCliente()} necesita internet. Quítalo o cobra cuando vuelva la red"

    /** Al volver de un cobro o cambiar de negocio se vuelve a leer Room; nunca se toma una lectura fallida por ausencia. */
    fun actualizarEstadoDeCobrar() {
        val contexto = sesionActual()
        if (contextoDelCatalogo != contexto) {
            contextoDelCatalogo = contexto
            versionDelCatalogo++
            refreshJob?.cancel()
            generacionDelContexto++
            refreshVenueId = null
            _isLoading.value = false
            _products.value = emptyList()
            _categories.value = emptyList()
            _errorCatalogo.value = null
            _aviso.value = null
        }
        val venueId = contexto.first
        if (venueObservado != venueId) {
            val anterior = venueObservado
            venueObservado = venueId
            recuperacionVerificada = false
            _ventaSinMostrar.value = null
            _ventaEnCeroGuardada.value = null
            _errorRecuperacion.value = null
            if (anterior != null) {
                _cartState.value = CartState(selectedStaffId = secureStorage.getStaffId().orEmpty(), selectedStaffName = secureStorage.getStaffName().orEmpty())
                _selectedCustomer.value = null
                _avisoDescuentoDelCliente.value = null
                olvidarLlave()
            }
            contadoresJob?.cancel()
            _avisoSinConexion.value = null
            if (venueId != null) contadoresJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    combine(pendingPaymentDao.contarVentasGuardadas(venueId), pendingPaymentDao.contarConProblema(venueId),
                        connectionStateManager.connectionState) { guardadas, problemas, conexion ->
                        AvisoSinConexion(!conexion.hasInternet, guardadas, problemas)
                            .takeIf { it.sinRed || it.guardadas > 0 || it.conProblema > 0 }
                    }.collect { if (secureStorage.getVenueId() == venueId) _avisoSinConexion.value = it }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "No se pudieron leer los contadores de ventas guardadas")
                    _aviso.value = "No se pudieron leer las ventas guardadas. Vuelve a intentar."
                }
            }
        }
        permisosJob?.cancel()
        _canCompItems.value = false
        _canApplyDiscount.value = false
        permisosDe = null
        val dueno = sesionActual()
        permisosJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val descuento = permissionsRepository.hasPermission("discounts:apply")
                val cortesia = permissionsRepository.hasPermission("tpv-orders:comp")
                if (sesionActual() == dueno) {
                    permisosDe = dueno
                    _canApplyDiscount.value = descuento
                    _canCompItems.value = cortesia
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "No se pudieron leer los permisos de Cobrar")
            }
        }
        if (recuperacionJob?.isActive == true || candado.activo.value) return
        recuperacionJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            candado.mientras {
                var recuperada = false
                while (!recuperada) {
                    val negocio = secureStorage.getVenueId() ?: return@mientras Result.success(Unit)
                    recuperacionVerificada = false
                    try {
                        restaurarCarritoAMedias()
                        val pendiente = ventaSinRed.ultimaVentaSinMostrar(negocio)
                        if (secureStorage.getVenueId() != negocio) continue
                        _ventaSinMostrar.value = pendiente
                        _errorRecuperacion.value = null
                        recuperacionVerificada = true
                        recuperada = true
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (secureStorage.getVenueId() != negocio) continue
                        Timber.e(e, "No se pudo recuperar la venta guardada")
                        _errorRecuperacion.value = "No se pudo leer la venta anterior. Se conserva en la terminal; vuelve a intentar antes de cobrar."
                        return@mientras Result.failure(e)
                    }
                }
                Result.success(Unit)
            }
        }
    }
}

private const val AVISO_VENTA_RECUPERADA = "Recuperamos la venta que estaba a medias"
private const val TEXTO_NO_SE_GUARDO_LA_VENTA = "No se pudo guardar la venta en la terminal. Intenta de nuevo."

// Converts internal integer-cents math to BigDecimal pesos for wire format.
// The new TPV /tpv/.../orders/with-items endpoint accepts pesos as decimals
// matching the rest of the /tpv API.
private fun Int.centsToPesos(): BigDecimal =
    BigDecimal(this).divide(BigDecimal(100), 2, java.math.RoundingMode.HALF_UP)

// Strips diacritics (acentos, ñ→n) and lowercases so search treats
// "datiles" and "Dátiles" as the same string. Uses NFD normalization +
// removes the combining mark range (\\p{M}) to peel off accents without
// touching base letters. Cheap enough to run per-keystroke against the
// product catalog (typically <500 items).
internal fun String.normalizeForSearch(): String =
    java.text.Normalizer.normalize(this.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()

// ─────────────────────────────────────────────────────────────────────────
// Mapping helpers (CartItem ↔ SavedCartItem)
// ─────────────────────────────────────────────────────────────────────────

private fun CartItem.toSavedCartItem(): SavedCartItem = SavedCartItem(
    productId = (type as? CartItemType.ProductItem)?.productId,
    name = name,
    unitPriceCents = unitPriceCents,
    quantity = quantity,
    modifiers = selectedModifiers,
    note = itemNote,
    isCortesia = isCortesia,
    cortesiaReason = cortesiaReason,
    priceAdjustmentCents = priceAdjustmentCents,
    itemDiscountId = itemDiscountId,
)

private fun Discount.toSaved(): SavedDiscount = SavedDiscount(
    id = id,
    name = name,
    typeName = type.name,
    value = value.toPlainString(),
)

/** 🔴 El cupón es online a propósito: necesita validarse. Sin red se dice, y se reintenta o se quita. El mismo texto que **ANDROID**. */
const val TEXTO_CUPON_SIN_RED = "Sin conexión: el cupón necesita internet. Reintenta cuando vuelva la conexión, o quita el cupón para cobrar sin él."
