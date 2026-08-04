package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableDiscount
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableServiceCharge
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Cómo se está dividiendo el cobro en curso — espejo de `SplitType` de
 * `features/payment/domain/model/SplitType.kt` (ese archivo vive bajo
 * `features/payment/`, así que este módulo NO lo importa — regla dura del
 * plan de que `features/tables/` no depende de `features/payment/`). El
 * `wireValue` es el MISMO string que el server espera en `splitType`
 * (`recordPaymentBodySchema`) — solo para el camino de TARJETA, que reusa el
 * contrato ya probado de `SplitByProductScreen`/`SplitByPersonScreen` vía
 * `AppNavigation.kt`. El camino de EFECTIVO no lo manda — `PAY_CASH` no
 * conoce `splitType`, solo un `amountCents` ya resuelto (ver KDoc de
 * [TablesRepository.payCash]).
 */
enum class CheckoutSplitType(val wireValue: String, val label: String) {
    FULLPAYMENT("FULLPAYMENT", "Cuenta completa"),
    CUSTOMAMOUNT("CUSTOMAMOUNT", "Monto personalizado"),
    EQUALPARTS("EQUALPARTS", "Partes iguales"),
    /**
     * "Por artículo". 🔴 Hueco de producto documentado (no arreglable desde
     * aquí sin tocar el server): `PAY_CASH` no acepta `paidProductsId`, así
     * que EFECTIVO + PERPRODUCT no existe hoy — la pantalla deshabilita ese
     * combo con la razón visible. TARJETA sí lo soporta completo (mismo
     * contrato que la ruta legacy).
     */
    PERPRODUCT("PERPRODUCT", "Por artículo"),
}

/**
 * Payload para el cargo con TARJETA — [TableCheckoutScreen] lo construye y se
 * lo entrega a `AppNavigation.kt` (dueño de la costura hacia "Cobrar"), que lo
 * traduce en las MISMAS claves de `savedStateHandle` que
 * `SplitByProductScreen`/`SplitByPersonScreen` ya usan (`initialAmount`,
 * `orderId`, `splitType`, `equalPartsPartySize`, `equalPartsPayedFor`,
 * `paidProductIds`) antes de `navController.navigate(getPaymentRoute())`.
 * Este archivo NUNCA llama `getPaymentRoute()` ni toca `PaymentScreen`/
 * `PaymentViewModel` — ver `.claude/rules` del founder: "Cobrar" es
 * intocable.
 */
data class CardChargeRequest(
    val orderId: String,
    val orderNumber: String?,
    val amount: BigDecimal,
    /**
     * SIEMPRE `false` — regla dura de la Task 8: el cargo con tarjeta apunta
     * a la orden YA EXISTENTE de la mesa, nunca crea una orden nueva. Campo
     * literal (no derivado) para que quede explícito en cada test/caller que
     * lee este payload, tal como pide el test del plan
     * (`el_cobro_apunta_a_la_orden_de_la_mesa_y_NO_crea_una_nueva`).
     */
    val createsNewOrder: Boolean = false,
    val splitType: CheckoutSplitType = CheckoutSplitType.FULLPAYMENT,
    val equalPartsPartySize: Int? = null,
    val equalPartsPayedFor: Int? = null,
    val paidProductIds: List<String> = emptyList(),
)

data class TableCheckoutState(
    val isLoading: Boolean = true,
    val check: OrderDetail? = null,
    val errorMessage: String? = null,
    val notice: String? = null,

    val splitType: CheckoutSplitType = CheckoutSplitType.FULLPAYMENT,
    val customAmountInput: String = "",
    val equalParts: Int = 2,
    val equalPartsPayingFor: Int = 1,
    val selectedItemIds: Set<String> = emptySet(),
    val tipAmount: BigDecimal = BigDecimal.ZERO,

    val isOnline: Boolean = true,
    val isProvisional: Boolean = false,
    val isProcessingCash: Boolean = false,

    /** Restante a cobrar — pesos. Arranca en `TableSession.total` y se refresca con el cheque real al cargar. */
    val remaining: BigDecimal = BigDecimal.ZERO,
    /** La mesa ya se liberó (el restante llegó a cero) — ver [TableCheckoutViewModel.onPaymentCompleted]. */
    val tableReleased: Boolean = false,
) {
    /**
     * Tarjeta = ONLINE por física (el procesador no soporta offline) Y la
     * orden debe existir de verdad en el server (mientras la sesión es
     * provisional no hay `orderId` real contra el cual cargar). Ninguna de
     * las dos condiciones es negociable — ver
     * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §5.
     */
    val cardPaymentEnabled: Boolean get() = isOnline && !isProvisional

    val cardPaymentDisabledReason: String?
        get() = when {
            isProvisional -> "La mesa aún no se sincroniza"
            !isOnline -> "Necesita conexión"
            else -> null
        }

    /** La cuenta sigue abierta salvo que ya se haya liberado la mesa — nunca un estado de error. */
    val checkStaysOpen: Boolean get() = !tableReleased

    val error: String? get() = errorMessage
}

/**
 * `TableCheckoutScreen` (Plan C, Task 8) — la costura hacia el pago. Pantalla
 * PROPIA de Mesas: NO importa nada de `features/checkout/` ni
 * `features/payment/`. Dos caminos de cobro, deliberadamente asimétricos
 * porque así es el contrato real (verificado contra el server, no asumido):
 *
 * - **EFECTIVO**: [payCash] — SIEMPRE pasa por [TablesRepository.payCash],
 *   que encola un intent `PAY_CASH` (write-ahead) y lo intenta reproducir de
 *   inmediato. Funciona online Y offline; nunca crea una orden nueva; nunca
 *   se muestra como error cuando solo quedó "guardado" (regla de oro
 *   offline-first).
 * - **TARJETA**: [buildPaymentPayload] arma un [CardChargeRequest] — quien
 *   llama esta pantalla (`AppNavigation.kt`) es responsable de traducirlo a
 *   la ruta de pago EXISTENTE (`getPaymentRoute()`), este ViewModel nunca
 *   navega ni conoce esa ruta. Deshabilitado sin red — es un ESTADO
 *   ([TableCheckoutState.cardPaymentDisabledReason]), nunca un error; la
 *   cuenta se queda abierta ([TableCheckoutState.checkStaysOpen]).
 *
 * [onPaymentCompleted] es el único punto que mueve [TableCheckoutState.remaining]
 * y decide si la mesa se libera sola — [payCash] lo llama tras un cobro
 * exitoso/encolado; el cargo con TARJETA no lo llama nunca porque control
 * sale de esta pantalla al navegar a "Cobrar" — quien reconcilia ese caso es
 * `AppNavigation.kt` al volver de un pago completado (ver su KDoc de
 * wiring). Público (no privado) para que el test del plan lo ejercite
 * directo, tal como ya hace `TableOrderViewModel.roundsSentToKitchen` con
 * funciones puras equivalentes.
 */
@HiltViewModel
class TableCheckoutViewModel @Inject constructor(
    private val repository: TablesRepository,
    val tableSession: TableSession,
    private val tableSyncCoordinator: TableSyncCoordinator,
    private val networkMonitor: NetworkMonitor,
    private val deviceInfoManager: DeviceInfoManager,
) : ViewModel() {

    private val _state = MutableStateFlow(TableCheckoutState())
    val state: StateFlow<TableCheckoutState> = _state.asStateFlow()

    private val venueId: String? get() = deviceInfoManager.getVenueId()

    init {
        // Entrar a checkout marca la sesión como PAYING (el carrito de
        // TableOrderScreen deja de escribir contra ella mientras se cobra) —
        // NUNCA se queda así al salir sin completar, ver KDoc de onExit().
        tableSession.current()?.let { session ->
            if (session.mode != TableSession.Mode.PAYING) {
                tableSession.start(session.copy(mode = TableSession.Mode.PAYING))
            }
        }
        _state.update {
            it.copy(
                remaining = tableSession.current()?.total ?: BigDecimal.ZERO,
                isProvisional = tableSession.current()?.isProvisional ?: false,
                isOnline = networkMonitor.isConnected(),
            )
        }
        loadCheck()
        viewModelScope.launch {
            networkMonitor.networkStateFlow.collect { info -> onConnectivityChanged(info.isConnected) }
        }
    }

    /** Carga (o recarga) el cheque desde el server — mismo criterio que `TableOrderViewModel.loadCheck`. */
    fun loadCheck() {
        val session = tableSession.current() ?: return
        if (session.isProvisional) {
            // La orden aún no existe en el server — nada que cargar todavía.
            // cardPaymentEnabled ya queda en false por isProvisional; EFECTIVO
            // sigue disponible (PAY_CASH resuelve por localOrderId).
            _state.update { it.copy(isLoading = false) }
            return
        }
        val vId = venueId ?: run {
            _state.update { it.copy(isLoading = false, errorMessage = "No hay venue configurado en este terminal") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.check == null) }
            try {
                tableSyncCoordinator.replay(vId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [TableCheckout] replay() del outbox falló antes de cargar la cuenta")
            }

            repository.getOrder(vId, session.orderId).fold(
                onSuccess = { detail ->
                    tableSession.updateVersion(detail.version)
                    val freshRemaining = detail.amountLeft ?: detail.total
                    tableSession.updateTotal(freshRemaining)
                    _state.update {
                        it.copy(isLoading = false, check = detail, remaining = freshRemaining, errorMessage = null)
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update { current ->
                        // Offline-first de lectura: si ya había un cheque cargado, se
                        // sigue mostrando — nunca se tapa una pantalla que funcionaba.
                        if (current.check != null) {
                            current.copy(isLoading = false)
                        } else {
                            current.copy(isLoading = false, errorMessage = "No se pudo cargar la cuenta")
                        }
                    }
                },
            )
        }
    }

    fun onConnectivityChanged(online: Boolean) {
        _state.update { it.copy(isOnline = online) }
    }

    fun selectSplitType(type: CheckoutSplitType) {
        _state.update { it.copy(splitType = type, errorMessage = null) }
    }

    fun setCustomAmountInput(text: String) {
        _state.update { it.copy(customAmountInput = text) }
    }

    fun setEqualParts(parts: Int) {
        val p = parts.coerceIn(2, 20)
        _state.update { it.copy(equalParts = p, equalPartsPayingFor = it.equalPartsPayingFor.coerceIn(1, p)) }
    }

    fun setEqualPartsPayingFor(parts: Int) {
        _state.update { it.copy(equalPartsPayingFor = parts.coerceIn(1, it.equalParts)) }
    }

    fun toggleItemSelected(itemId: String) {
        _state.update {
            val selected = it.selectedItemIds
            it.copy(selectedItemIds = if (itemId in selected) selected - itemId else selected + itemId)
        }
    }

    fun setTip(amount: BigDecimal) {
        _state.update { it.copy(tipAmount = amount.coerceAtLeast(BigDecimal.ZERO)) }
    }

    /**
     * Monto a cobrar según el modo de división elegido — SIEMPRE acotado a
     * [TableCheckoutState.remaining] (nunca se puede cobrar más de lo que se
     * debe, sin importar lo que el mesero haya tecleado).
     */
    fun chargeableAmount(): BigDecimal {
        val s = _state.value
        val remaining = s.remaining
        if (remaining <= BigDecimal.ZERO) return BigDecimal.ZERO
        return when (s.splitType) {
            CheckoutSplitType.FULLPAYMENT -> remaining

            CheckoutSplitType.CUSTOMAMOUNT -> {
                val entered = s.customAmountInput.toBigDecimalOrNull() ?: return BigDecimal.ZERO
                if (entered <= BigDecimal.ZERO) BigDecimal.ZERO else entered.min(remaining)
            }

            CheckoutSplitType.EQUALPARTS -> {
                val perPart = remaining.divide(BigDecimal(s.equalParts), 2, RoundingMode.UP)
                (perPart * BigDecimal(s.equalPartsPayingFor)).min(remaining)
            }

            CheckoutSplitType.PERPRODUCT -> {
                val check = s.check ?: return BigDecimal.ZERO
                check.items
                    .filter { it.id in s.selectedItemIds }
                    .fold(BigDecimal.ZERO) { acc, item -> acc + item.total }
                    .min(remaining)
            }
        }
    }

    /** Payload para TARJETA — ver KDoc de la clase y de [CardChargeRequest]. */
    fun buildPaymentPayload(amount: BigDecimal): CardChargeRequest {
        val session = checkNotNull(tableSession.current()) { "buildPaymentPayload() sin sesión de mesa activa" }
        val s = _state.value
        return CardChargeRequest(
            orderId = session.orderId,
            orderNumber = session.orderNumber,
            amount = amount,
            splitType = s.splitType,
            equalPartsPartySize = if (s.splitType == CheckoutSplitType.EQUALPARTS) s.equalParts else null,
            equalPartsPayedFor = if (s.splitType == CheckoutSplitType.EQUALPARTS) s.equalPartsPayingFor else null,
            paidProductIds = if (s.splitType == CheckoutSplitType.PERPRODUCT) s.selectedItemIds.toList() else emptyList(),
        )
    }

    /**
     * "Cobro en efectivo" — ver KDoc de [TablesRepository.payCash] para el
     * mecanismo write-ahead + replay inmediato. Funciona offline a propósito
     * (regla dura de la Task 8: "cash *can* go offline; card cannot").
     */
    fun payCash(amount: BigDecimal) {
        val session = tableSession.current()
        if (session == null) {
            _state.update { it.copy(errorMessage = "No hay mesa activa") }
            return
        }
        val vId = venueId
        if (vId == null) {
            _state.update { it.copy(errorMessage = "No hay venue configurado en este terminal") }
            return
        }
        if (amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorMessage = "Ingresa un monto válido") }
            return
        }
        if (_state.value.isProcessingCash) return

        _state.update { it.copy(isProcessingCash = true, errorMessage = null) }
        viewModelScope.launch {
            repository.payCash(
                venueId = vId,
                orderId = session.orderId,
                isProvisional = session.isProvisional,
                amount = amount,
                tip = _state.value.tipAmount,
            ).fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            isProcessingCash = false,
                            notice = if (outcome.queued) {
                                "Sin conexión — cobro guardado; se sincronizará solo"
                            } else {
                                "Cobro registrado"
                            },
                        )
                    }
                    onPaymentCompleted(amount)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            isProcessingCash = false,
                            errorMessage = (e as? TablesRepository.PayCashRejectedException)?.message
                                ?: "No se pudo registrar el cobro",
                        )
                    }
                },
            )
        }
    }

    /**
     * Un pago (efectivo YA registrado/encolado, o el resultado de un cargo
     * con tarjeta que otra pantalla acaba de completar) reduce lo que falta
     * por cobrar. Al llegar a cero la mesa se libera SOLA — dispara
     * [releaseTable] (que llama al server; el server es quien manda, ver su
     * KDoc). Público — ver KDoc de la clase.
     */
    fun onPaymentCompleted(amount: BigDecimal) {
        val current = _state.value
        val newRemaining = (current.remaining - amount).let { if (it < BigDecimal.ZERO) BigDecimal.ZERO else it }
        val released = newRemaining <= BigDecimal.ZERO
        _state.update { it.copy(remaining = newRemaining, tableReleased = released) }
        tableSession.updateTotal(newRemaining)
        if (released) releaseTable()
    }

    private fun releaseTable() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        viewModelScope.launch {
            repository.clearTable(vId, session.tableId).fold(
                onSuccess = {
                    tableSession.clear()
                },
                onFailure = { e ->
                    // El server rechaza si queda saldo (p.ej. la propina de un cargo con
                    // tarjeta aún no se refleja) — no es un error para el mesero, es el
                    // server protegiendo contra liberar una mesa que en realidad debe.
                    Timber.w(e, "⚠️ [TableCheckout] clearTable no liberó la mesa todavía (venue=%s, table=%s)", vId, session.tableId)
                },
            )
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Salir del checkout SIN completar el pago — la sesión NUNCA puede
     * quedarse en PAYING (mismo candado que la referencia de
     * `avoqado-android`, `TableOrderScreen.kt` líneas 857-867): si se queda,
     * una venta retail posterior podría registrarse por error contra la
     * orden de esta mesa. Llamar siempre desde `onNavigateBack`/back físico.
     */
    fun onExit() {
        tableSession.current()?.let { session ->
            if (session.mode == TableSession.Mode.PAYING) {
                tableSession.start(session.copy(mode = TableSession.Mode.ORDERING))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 1 (2026-08-03) — APPLY_DISCOUNT. Vive aquí (no en TableOrderScreen)
    // porque afecta el TOTAL que se está por cobrar — su lugar natural es
    // "Cobrar", donde el mesero ya está viendo los números exactos.
    // ══════════════════════════════════════════════════════════════════

    private val _availableDiscounts = MutableStateFlow<List<AvailableDiscount>>(emptyList())
    val availableDiscounts: StateFlow<List<AvailableDiscount>> = _availableDiscounts.asStateFlow()

    private val _isLoadingDiscounts = MutableStateFlow(false)
    val isLoadingDiscounts: StateFlow<Boolean> = _isLoadingDiscounts.asStateFlow()

    private val _isApplyingDiscount = MutableStateFlow(false)
    val isApplyingDiscount: StateFlow<Boolean> = _isApplyingDiscount.asStateFlow()

    /**
     * Catálogo del venue — lectura ONLINE pura, sin equivalente offline (no
     * hay forma de saber qué descuentos aplican sin preguntarle al server).
     * Solo alcanzable con el cheque ya cargado (`state.isProvisional` en
     * falso) — [com.jaac.avoqado_tpv.features.tables.presentation.DiscountPickerSheet]
     * respeta esa guarda antes de llamar esto.
     */
    fun loadAvailableDiscounts() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (session.isProvisional) return
        viewModelScope.launch {
            _isLoadingDiscounts.value = true
            repository.getAvailableDiscounts(vId, session.orderId).fold(
                onSuccess = { discounts ->
                    _availableDiscounts.value = discounts
                    _isLoadingDiscounts.value = false
                },
                onFailure = { e ->
                    _isLoadingDiscounts.value = false
                    if (e is CancellationException) throw e
                    _state.update { it.copy(errorMessage = "No se pudieron cargar los descuentos disponibles") }
                },
            )
        }
    }

    /**
     * Aplica un descuento del catálogo — online-first, se encola
     * (`APPLY_DISCOUNT`) si no hay red (ver KDoc de
     * [TablesRepository.applyDiscount]). Al aplicarse online refresca el
     * cheque para que [TableCheckoutState.check]/[TableCheckoutState.remaining]
     * reflejen el nuevo total de inmediato.
     */
    fun applyDiscount(discountId: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (_isApplyingDiscount.value) return

        _isApplyingDiscount.value = true
        viewModelScope.launch {
            repository.applyDiscount(vId, session.orderId, discountId).fold(
                onSuccess = { outcome ->
                    _isApplyingDiscount.value = false
                    _state.update {
                        it.copy(
                            notice = if (outcome.queued) {
                                "Sin conexión — descuento guardado; se sincronizará solo"
                            } else {
                                "Descuento aplicado"
                            },
                        )
                    }
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isApplyingDiscount.value = false
                    if (e is CancellationException) throw e
                    _state.update { it.copy(errorMessage = (e as? BackendHttpException)?.message ?: "No se pudo aplicar el descuento") }
                },
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 3 (2026-08-03) — APPLY_SERVICE_CHARGE. Vive aquí, no en
    // TableOrderViewModel, por el MISMO motivo que APPLY_DISCOUNT arriba:
    // suma al TOTAL que se está por cobrar — su lugar natural es "Cobrar".
    // Manual override del auto-apply por comensales (ServiceCharge.autoApplyMinCovers).
    // ══════════════════════════════════════════════════════════════════

    private val _availableServiceCharges = MutableStateFlow<List<AvailableServiceCharge>>(emptyList())
    val availableServiceCharges: StateFlow<List<AvailableServiceCharge>> = _availableServiceCharges.asStateFlow()

    private val _isLoadingServiceCharges = MutableStateFlow(false)
    val isLoadingServiceCharges: StateFlow<Boolean> = _isLoadingServiceCharges.asStateFlow()

    private val _isApplyingServiceCharge = MutableStateFlow(false)
    val isApplyingServiceCharge: StateFlow<Boolean> = _isApplyingServiceCharge.asStateFlow()

    /**
     * Catálogo del venue — lectura ONLINE pura (ver KDoc de
     * [TablesRepository.getAvailableServiceCharges]). Mismo guard que
     * [loadAvailableDiscounts]: solo alcanzable con el cheque ya cargado.
     */
    fun loadAvailableServiceCharges() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (session.isProvisional) return
        viewModelScope.launch {
            _isLoadingServiceCharges.value = true
            repository.getAvailableServiceCharges(vId).fold(
                onSuccess = { charges ->
                    _availableServiceCharges.value = charges
                    _isLoadingServiceCharges.value = false
                },
                onFailure = { e ->
                    _isLoadingServiceCharges.value = false
                    if (e is CancellationException) throw e
                    _state.update { it.copy(errorMessage = "No se pudieron cargar los cargos por servicio disponibles") }
                },
            )
        }
    }

    /**
     * Aplica un cargo por servicio del catálogo — online-first, se encola
     * (`APPLY_SERVICE_CHARGE`) si no hay red (ver KDoc de
     * [TablesRepository.applyServiceCharge]). Al aplicarse online refresca el
     * cheque para que el total/restante reflejen el cargo de inmediato — mismo
     * patrón que [applyDiscount].
     */
    fun applyServiceCharge(serviceChargeId: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (_isApplyingServiceCharge.value) return

        _isApplyingServiceCharge.value = true
        viewModelScope.launch {
            repository.applyServiceCharge(vId, session.orderId, serviceChargeId).fold(
                onSuccess = { outcome ->
                    _isApplyingServiceCharge.value = false
                    _state.update {
                        it.copy(
                            notice = if (outcome.queued) {
                                "Sin conexión — cargo por servicio guardado; se sincronizará solo"
                            } else {
                                "Cargo por servicio aplicado"
                            },
                        )
                    }
                    if (!outcome.queued) loadCheck()
                },
                onFailure = { e ->
                    _isApplyingServiceCharge.value = false
                    if (e is CancellationException) throw e
                    _state.update { it.copy(errorMessage = (e as? BackendHttpException)?.message ?: "No se pudo aplicar el cargo por servicio") }
                },
            )
        }
    }
}
