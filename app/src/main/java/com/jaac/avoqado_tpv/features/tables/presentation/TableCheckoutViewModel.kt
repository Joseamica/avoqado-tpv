package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.features.tables.data.SIN_CUENTA_SIN_RED
import com.jaac.avoqado_tpv.features.tables.data.COBRO_CON_LO_DE_ESTA_TERMINAL
import com.jaac.avoqado_tpv.features.tables.data.SALDO_NO_RECUPERADO
import com.jaac.avoqado_tpv.features.tables.data.CUENTA_SE_ACTUALIZA
import com.jaac.avoqado_tpv.features.tables.data.DineroDeTarjetaDeLaCuenta
import com.jaac.avoqado_tpv.features.tables.data.SaldoDeMesaSinRed
import com.jaac.avoqado_tpv.features.tables.data.SaldoSinRed
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableDiscount
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableServiceCharge
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /**
     * D9 (12-nov): la propina que el mesero escribió en «Propina (opcional)», en pesos. Antes no
     * viajaba y «Cobrar» la volvía a preguntar (o la perdía si la pregunta de propina estaba
     * apagada). `AppNavigation.kt` la pasa como `propinaYaElegidaCents` cuando es > 0.
     */
    val tipAmount: BigDecimal = BigDecimal.ZERO,
) {
    /**
     * La propina en centavos para «Cobrar» (`propinaYaElegidaCents`), redondeada a 2 decimales como
     * cualquier monto en pesos; `null` si no hay propina (cero o negativa): entonces «Cobrar» la
     * pregunta como siempre.
     */
    val propinaEnCentavos: Long?
        get() = tipAmount.setScale(2, RoundingMode.HALF_UP)
            .takeIf { it > BigDecimal.ZERO }
            ?.movePointRight(2)
            ?.longValueExact()
}

/**
 * Resultado a mostrar tras un cobro en EFECTIVO — cierra la asimetría con
 * TARJETA (que hoy SÍ termina en la pantalla de éxito de "Cobrar", con
 * recibo). Ver KDoc de [TableCheckoutViewModel.payCash].
 *
 * - `receiptUrl`/`autofacturaAvailable` vienen del `digitalReceipt` que
 *   `applyPayCash` (server) YA regresaba en el ack y que este cliente
 *   descartaba — ver KDoc de [TablesRepository.PayCashOutcome]. Nulo cuando
 *   [queued] es true (el intent sigue PENDING, el server no ha generado
 *   recibo todavía) — se sabe igual que el cobro se guardó por el `notice`
 *   compartido ("Sin conexión — cobro guardado…").
 * - `isSettlingPayment` = ESTE cobro dejó el restante en cero (mismo cálculo
 *   que decide si la mesa se libera en [TableCheckoutViewModel.onPaymentCompleted]).
 *   Es la ÚNICA señal que debe controlar cualquier invitación a calificar la
 *   visita — pedirlo en cada pago de una cuenta dividida crearía un review
 *   por split para la MISMA visita (`Review.paymentId` es único pero
 *   `Review.venueId` promedia todos, así que 4 reviews de una sola mesa
 *   sesgan el promedio del venue — verificado contra
 *   `avoqado-server/prisma/schema.prisma:3716`).
 */
data class CashReceiptSummary(
    val amount: BigDecimal,
    val tipAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val orderNumber: String?,
    val receiptUrl: String?,
    val autofacturaAvailable: Boolean,
    val queued: Boolean,
    val isSettlingPayment: Boolean,
)

data class TableCheckoutState(
    val isLoading: Boolean = true,
    val check: OrderDetail? = null,
    val errorMessage: String? = null,
    val notice: String? = null,

    /**
     * No-nulo justo después de un cobro en EFECTIVO exitoso/encolado — la UI
     * lo muestra como confirmación con recibo antes de que la pantalla se
     * cierre (o inmediatamente, si la cuenta sigue abierta). Se limpia con
     * [TableCheckoutViewModel.consumeCashReceipt].
     */
    val cashReceipt: CashReceiptSummary? = null,

    val splitType: CheckoutSplitType = CheckoutSplitType.FULLPAYMENT,
    val customAmountInput: String = "",
    val equalParts: Int = 2,
    val equalPartsPayingFor: Int = 1,
    val selectedItemIds: Set<String> = emptySet(),
    val tipAmount: BigDecimal = BigDecimal.ZERO,

    val isOnline: Boolean = true,
    /**
     * D90 (PAX, 16-nov): `false` mientras el letrero «Sin conexión a internet» está puesto ([ConnectionStateManager]). Al volver
     * la red, el aparato avisa (→ [isOnline]) ~2 s antes de que la comprobación de conexión quite el letrero: la tarjeta espera
     * al letrero para no verse habilitada debajo de él.
     */
    val letreroConInternet: Boolean = true,
    val isProvisional: Boolean = false,
    val isProcessingCash: Boolean = false,
    /**
     * D9 (Codex r1 #2): «Tarjeta» ya se tocó y la navegación está abriendo «Cobrar» (puede esperar a que la PAX esté
     * lista). Mientras tanto ni efectivo ni otra tarjeta: una sola reserva de cobro, para que la misma propina no
     * salga en dos pagos. Lo apagan [TableCheckoutViewModel.cobroConTarjetaEntregado] o
     * [TableCheckoutViewModel.cobroConTarjetaNoSalio].
     */
    val isHandingOffToCard: Boolean = false,
    /**
     * D9 (encargo 49276c, 15-nov): esta pantalla ya le ENTREGÓ un cobro con tarjeta a «Cobrar» y todavía no ha vuelto a
     * leer la cuenta del servidor. Mientras sea `true`, [remaining] puede ser el de ANTES de la tarjeta (esta pantalla
     * sigue viva en la pila y, sin esto, no se recargaba al volver): ni efectivo ni otra tarjeta hasta que una lectura
     * que EMPEZÓ después de esa entrega lo confirme. Lo prende [TableCheckoutViewModel.cobroConTarjetaEntregado] y lo
     * apaga [TableCheckoutViewModel.loadCheck] al recibir esa lectura.
     */
    val saldoPorConfirmar: Boolean = false,
    /** D9: la lectura que confirma el saldo tras la tarjeta va en camino (el aviso dice «Revisando…»). */
    val revisandoSaldo: Boolean = false,

    /**
     * Restante a cobrar — pesos. Mesa del SERVIDOR: arranca en `TableSession.total` (sólo para pintar mientras carga) y manda
     * el cheque real al cargar. Mesa abierta sin red aquí: su saldo sin red (D86).
     */
    val remaining: BigDecimal = BigDecimal.ZERO,
    /**
     * D86: saldo sin red de una mesa que ESTA terminal abrió sin red (`null` mientras se calcula o si no es provisional).
     * Conocido = se cobra con él; en gris = el efectivo espera con su razón.
     */
    val saldoSinRed: SaldoSinRed? = null,
    /**
     * D86 (Codex r0 #1): esta terminal tiene algo sin subir de esta cuenta del SERVIDOR (un cobro, una ronda…): el saldo leído
     * no lo cuenta. Mientras no sea `null`, ni efectivo ni tarjeta.
     */
    val pendienteSinSubir: String? = null,
    /** D92: descuentos o cargos que van en camino al SERVIDOR: mientras no sea 0, ni efectivo ni tarjeta (la cuenta va a cambiar). */
    val cambiosEnCamino: Int = 0,
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
    val cardPaymentEnabled: Boolean get() = cardPaymentDisabledReason == null

    /**
     * D90 (PAX, 16-nov): además, sin la cuenta leída la tarjeta espera. En «Cargando cuenta…» se veía habilitada, y con
     * [remaining] = `TableSession.total` (el TOTAL del plano, no lo que falta): un toque ahí podía cobrar de más.
     */
    val cardPaymentDisabledReason: String?
        get() = when {
            isProvisional -> "La mesa aún no se sincroniza"
            !isOnline || !letreroConInternet -> "Necesita conexión"
            pendienteSinSubir != null -> pendienteSinSubir
            cambiosEnCamino > 0 -> CUENTA_SE_ACTUALIZA
            check == null -> if (isLoading) "Cargando la cuenta…" else "No se pudo cargar la cuenta"
            else -> null
        }

    /** La cuenta sigue abierta salvo que ya se haya liberado la mesa — nunca un estado de error. */
    val checkStaysOpen: Boolean get() = !tableReleased

    /** D9: por qué los dos botones esperan tras un cobro con tarjeta; `null` si no esperan por eso. */
    val avisoDeSaldoPorConfirmar: String?
        get() = when {
            !saldoPorConfirmar -> null
            revisandoSaldo -> "Revisando cuánto queda por pagar después de la tarjeta…"
            else -> "No se pudo confirmar cuánto queda por pagar después de la tarjeta. Revisa la conexión y toca Reintentar."
        }

    /**
     * D86: por qué el EFECTIVO no se puede cobrar todavía; `null` = se puede. Antes, sin cuenta leída, «Cobrar» cobraba (o
     * enseñaba en $0) con `TableSession.total`, que es 0 en una mesa abierta con «Abrir mesa» y el TOTAL del plano (no el
     * saldo) en una abierta con «Ver cuenta».
     * - Mesa abierta sin red AQUÍ: sólo con su saldo sin red conocido (rondas propias menos efectivo propio).
     * - Mesa del SERVIDOR: sólo con la cuenta leída (si «Cobrar» la leyó con red y luego se fue, sigue cobrando como hoy).
     *   Sin ella y sin red, en gris: el piloto `MESAS_SIN_RED` (cobrar con la foto) todavía no está en la TPV.
     */
    val efectivoBloqueadoPor: String?
        get() = when {
            isProvisional -> when (val saldo = saldoSinRed) {
                null -> "Calculando lo que debe la mesa…"
                is SaldoSinRed.EnGris -> saldo.motivo
                is SaldoSinRed.Conocido -> null
            }
            check != null -> pendienteSinSubir ?: CUENTA_SE_ACTUALIZA.takeIf { cambiosEnCamino > 0 }
            isLoading -> "Cargando la cuenta…"
            !isOnline -> SIN_CUENTA_SIN_RED
            else -> "No se pudo cargar la cuenta: toca Reintentar."
        }

    /** D86: aviso de «Cobrar» en una mesa abierta sin red aquí (se cobra con lo que esta terminal le mandó). */
    val avisoSinRed: String?
        get() = if (isProvisional && saldoSinRed is SaldoSinRed.Conocido) COBRO_CON_LO_DE_ESTA_TERMINAL else null

    val error: String? get() = errorMessage
}

/**
 * `TableCheckoutScreen` (Plan C, Task 8) — la costura hacia el pago. Pantalla
 * PROPIA de Mesas: NO importa nada de `features/checkout/` ni
 * `features/payment/`. Dos caminos de cobro — distintos en la EJECUCIÓN
 * (verificado contra el server, no asumido), pero ya NO en el resultado que
 * ve el mesero/cliente (propina, recibo, invitación a calificar sólo al
 * saldar — fix 2026-08-07 de la asimetría original):
 *
 * - **EFECTIVO**: [payCash] — SIEMPRE pasa por [TablesRepository.payCash],
 *   que encola un intent `PAY_CASH` (write-ahead) y lo intenta reproducir de
 *   inmediato. Funciona online Y offline; nunca crea una orden nueva; nunca
 *   se muestra como error cuando solo quedó "guardado" (regla de oro
 *   offline-first). Termina en [CashReceiptSummary] (recibo + invitación a
 *   calificar sólo si salda la cuenta) — ver [TableCheckoutScreen]'s
 *   `CashReceiptDialog`.
 * - **TARJETA**: [buildPaymentPayload] arma un [CardChargeRequest] — quien
 *   llama esta pantalla (`AppNavigation.kt`) es responsable de traducirlo a
 *   la ruta de pago EXISTENTE (`getPaymentRoute()`), este ViewModel nunca
 *   navega ni conoce esa ruta. Deshabilitado sin red — es un ESTADO
 *   ([TableCheckoutState.cardPaymentDisabledReason]), nunca un error; la
 *   cuenta se queda abierta ([TableCheckoutState.checkStaysOpen]).
 *
 * 🔴 **Por qué EFECTIVO NO navega literalmente a `getPaymentRoute()` como
 * TARJETA** (evaluado y descartado a propósito, no un olvido): el botón
 * "Efectivo" de esa pantalla (`MerchantSelectionContent.onStartCashPayment`
 * → `PaymentViewModel.processCashPayment`, ambos flavors) SÍ ofrece
 * propina+recibo+rating, pero graba el cobro con un mecanismo DISTINTO —
 * intenta el HTTP primero y solo encola en `PaymentQueueRepositoryImpl` si
 * ESE intento falla (`onFailure { paymentQueueRepository.enqueue(...) }`).
 * Eso NO es write-ahead: un crash entre el tap y la respuesta pierde el
 * cobro en silencio, sin fila en ningún outbox — justo lo que la regla dura
 * del founder prohíbe ("PAY_CASH carries an idempotencyKey; the server
 * dedupes on [venueId, idempotencyKey]... The outbox is write-ahead: the key
 * exists before the first attempt"). Enrutar EFECTIVO de Mesas por ahí
 * habría cambiado el mecanismo de cobro, no solo la pantalla. [payCash] se
 * queda como la única fuente de verdad del dinero; lo que se agregó aquí es
 * la experiencia que faltaba alrededor, sin tocar `features/payment/`.
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
    /** D86: saldo sin red de una mesa que esta terminal abrió sin red. */
    private val saldoDeMesaSinRed: SaldoDeMesaSinRed,
    /** D90: el estado que pinta el letrero «Sin conexión a internet». */
    private val connectionStateManager: ConnectionStateManager,
    /** D9 (Codex r1 de la unión): dinero de TARJETA de esta cuenta que el SERVIDOR todavía no confirma. */
    private val dineroDeTarjeta: DineroDeTarjetaDeLaCuenta,
) : ViewModel() {

    private val _state = MutableStateFlow(TableCheckoutState())
    val state: StateFlow<TableCheckoutState> = _state.asStateFlow()

    private val venueId: String? get() = deviceInfoManager.getVenueId()

    /**
     * D9 (encargo 49276c): sube al entregar una tarjeta a «Cobrar» y otra vez cuando la pantalla vuelve al frente tras
     * esa entrega. Una lectura de la cuenta sólo se aplica si la marca no cambió entre que salió y que llegó: una que
     * salió antes de la entrega, o mientras «Cobrar» seguía abierto y llega ya de vuelta, trae un saldo viejo (Codex r2).
     */
    private var marcaDeSaldo = 0L

    /**
     * D9 (encargo 49276c, Codex r2): la pantalla ya volvió al frente después de la última entrega a «Cobrar». Sólo una
     * lectura que EMPEZÓ con esto en `true` confirma el saldo: una que empieza mientras «Cobrar» sigue abierto (p. ej. la
     * que sigue a «Quitar descuento») llega antes de que la tarjeta cobre y su saldo ya no vale al volver.
     */
    private var alFrenteDesdeLaEntrega = true

    /**
     * D86 (Codex r5): una sola fila para leer la cuenta, calcular el saldo sin red y cobrar en efectivo. Así una lectura nunca
     * se cruza con un cobro (ni la que salió antes ni la que nace durante él): la que espera turno ve el resultado del cobro.
     */
    private val fila = Mutex()

    // D86 (Codex r3): estos campos van ANTES de `init`: Kotlin inicializa en el orden del texto, y `init` ya lanza la primera
    // lectura; declarados después, se reiniciaban a media lectura y la primera respuesta se tiraba («Cargando cuenta…» sin fin).
    /** D86 (Codex r2 #1): cuántas lecturas de la cuenta y cuántos cobros en efectivo han empezado (para tirar lecturas viejas). */
    private var lecturasIniciadas = 0L
    private var cobrosIniciados = 0L

    /** D86 (Codex r1 #1): la sesión se promovió durante un cobro en efectivo: se aplica al terminarlo. */
    private var promocionPendiente = false

    /** D86 (Codex r1 #1): una lectura de la cuenta llegó durante un cobro en efectivo y se tiró: se repite al terminarlo. */
    private var releerAlTerminarElCobro = false

    /** D86 (Codex r1 #4): la relectura periódica mientras hay algo propio sin subir. */
    private var relectura: kotlinx.coroutines.Job? = null

    /** D86 (Codex r4): sólo el cálculo más nuevo del saldo sin red se aplica. */
    private var calculosDeSaldo = 0L

    /** D86: relecturas automáticas seguidas sin desbloquearse; con el tope, espera «Reintentar» o a que vuelva la red. */
    private var relecturasSeguidas = 0


    init {
        // Entrar a checkout marca la sesión como PAYING (el carrito de
        // TableOrderScreen deja de escribir contra ella mientras se cobra) —
        // NUNCA se queda así al salir sin completar, ver KDoc de onExit().
        tableSession.current()?.let { session ->
            if (session.mode != TableSession.Mode.PAYING) {
                tableSession.start(session.copy(mode = TableSession.Mode.PAYING))
            }
        }
        val provisional = tableSession.current()?.isProvisional ?: false
        _state.update {
            it.copy(
                // D86: una mesa abierta sin red aquí NUNCA arranca con `TableSession.total` (0 o un total viejo): espera su saldo.
                remaining = if (provisional) BigDecimal.ZERO else tableSession.current()?.total ?: BigDecimal.ZERO,
                isProvisional = provisional,
                isOnline = networkMonitor.isConnected(),
            )
        }
        viewModelScope.launch {
            connectionStateManager.connectionState.collect { conexion ->
                _state.update { it.copy(letreroConInternet = conexion.hasInternet) }
            }
        }
        viewModelScope.launch {
            networkMonitor.networkStateFlow.collect { info ->
                val volvio = info.isConnected && !_state.value.isOnline
                onConnectivityChanged(info.isConnected)
                // D86: volvió la red sin cuenta leída, o con algo propio sin subir (mesa del SERVIDOR): se lee sola. En una mesa
                // abierta sin red aquí, cualquier cambio de red vuelve a calcular su saldo de la cola (Codex r2 #2: un rechazo
                // de su apertura la deja en gris).
                if (_state.value.isProvisional) {
                    tableSession.current()?.takeIf { it.isProvisional }?.let { calcularSaldoSinRed(it) }
                } else if (volvio && (_state.value.check == null || _state.value.pendienteSinSubir != null)) {
                    reintentar()
                }
            }
        }
        viewModelScope.launch {
            // D86: el ACK de OPEN_TABLE promueve la sesión a media pantalla: la cuenta ya existe en el SERVIDOR y desde ahí manda
            // su cheque (con el efectivo propio ya sumado, porque la cola sube en orden), no el saldo sin red.
            var antes = tableSession.current()
            tableSession.active.collect { ahora ->
                val previa = antes
                val promovida = previa != null && previa.isProvisional && ahora != null && !ahora.isProvisional && previa.tableId == ahora.tableId
                antes = ahora
                if (promovida && _state.value.isProvisional) {
                    // D86 (Codex r1 #1): con un cobro en curso no se toca el saldo: se aplica al terminar ese cobro.
                    if (_state.value.isProcessingCash) promocionPendiente = true else aplicarPromocion()
                }
            }
        }
        venueId?.let { vId ->
            viewModelScope.launch {
                // D86 (Codex r7 H3): cada cambio de la cola recalcula: el saldo sin red de una mesa provisional (un rechazo la
                // deja en gris) o la cuenta del SERVIDOR que esperaba a que algo propio subiera.
                saldoDeMesaSinRed.cambiosDeLaCola(vId).collect {
                    val actual = tableSession.current() ?: return@collect
                    // r8: sin condiciones y sin reproducir la cola (ver `cargar`).
                    if (actual.isProvisional && _state.value.isProvisional) {
                        calcularSaldoSinRed(actual)
                    } else if (!_state.value.isProvisional) {
                        cargar(reproducir = false)
                    }
                }
            }
        }
        // D86: después de los colectores: si la carga promueve la sesión en el acto (apertura ya confirmada), alguien la ve.
        loadCheck()
    }

    /** Carga (o recarga) el cheque desde el server — mismo criterio que `TableOrderViewModel.loadCheck`. */
    fun loadCheck() = cargar(reproducir = true)

    /** D86 (Codex r8 R1): con [reproducir] en `false` sólo lee, sin reproducir la cola (la del aviso de la cola). */
    private fun cargar(reproducir: Boolean) {
        val session = tableSession.current() ?: return
        if (session.isProvisional) {
            // La orden aún no existe en el server — nada que cargar todavía.
            // cardPaymentEnabled ya queda en false por isProvisional; EFECTIVO
            // sigue disponible (PAY_CASH resuelve por localOrderId) con el saldo sin red (D86).
            _state.update { it.copy(isLoading = false) }
            calcularSaldoSinRed(session)
            return
        }
        val vId = venueId ?: run {
            _state.update { it.copy(isLoading = false, errorMessage = "No hay venue configurado en este terminal") }
            return
        }
        val marcaDeEntrega = marcaDeSaldo
        val confirmaElSaldo = alFrenteDesdeLaEntrega
        viewModelScope.launch {
            // D9 (encargo 49276c): tras una tarjeta, la lectura que sale con la pantalla ya de vuelta dice «Revisando…» desde
            // que se pide, aunque espere turno en la fila detrás de otra lectura (D86); no «No se pudo confirmar».
            if (confirmaElSaldo) _state.update { it.copy(revisandoSaldo = it.revisandoSaldo || it.saldoPorConfirmar) }
            // D86 (Codex r5): leer la cuenta, calcular el saldo y cobrar van EN FILA, nunca a la vez.
            fila.withLock {
            // D86 (Codex r2 #1): sólo la lectura más nueva, y sólo si ningún cobro empezó mientras viajaba, cambia el saldo.
            val miLectura = ++lecturasIniciadas
            val cobrosAlSalir = cobrosIniciados
            _state.update { it.copy(isLoading = it.check == null) }
            if (reproducir) {
                try {
                    tableSyncCoordinator.replay(vId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [TableCheckout] replay() del outbox falló antes de cargar la cuenta")
                }
            }

            // D86 (Codex r0 #1, r1 #3): lo que esta terminal todavía no sube de esta cuenta no está en el saldo que lee el
            // SERVIDOR. Se revisa ANTES y DESPUÉS de leer: si no había nada propio pendiente antes, todo lo propio ya confirmado
            // está en esa lectura; si algo propio estaba pendiente en cualquiera de los dos momentos, la lectura no se usa
            // para cobrar (ni efectivo ni tarjeta) y se vuelve a leer sola hasta que suba.
            val pendienteAntes = pendienteDeSubirSeguro(vId, session)
            val leida = repository.getOrder(vId, session.orderId)
            val pendiente = if (leida.isSuccess) pendienteAntes ?: pendienteDeSubirSeguro(vId, session) else null
            leida.fold(
                onSuccess = { detail ->
                    if (miLectura != lecturasIniciadas) {
                        // D86 (Codex r2 #1): salió otra lectura después de ésta: la nueva manda.
                        return@fold
                    }
                    if (marcaDeEntrega != marcaDeSaldo) {
                        // D9: salió antes de entregar una tarjeta, o mientras «Cobrar» seguía abierto — su saldo puede
                        // ser el de antes del abono. No se aplica; la lectura de la pantalla ya de vuelta sí vale.
                        _state.update { it.copy(isLoading = false) }
                        return@fold
                    }
                    if (_state.value.isProcessingCash || cobrosIniciados != cobrosAlSalir) {
                        // D86 (Codex r1 #1, r2 #1): un cobro en efectivo empezó mientras esta lectura viajaba: su saldo puede ser
                        // de ANTES de ese cobro. Se tira y se vuelve a leer al terminar el cobro (o ya, si ya terminó).
                        _state.update { it.copy(isLoading = false) }
                        if (_state.value.isProcessingCash) releerAlTerminarElCobro = true else loadCheck()
                        return@fold
                    }
                    tableSession.updateVersion(detail.version)
                    val freshRemaining = detail.amountLeft ?: detail.total
                    tableSession.updateTotal(freshRemaining)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            check = detail,
                            remaining = freshRemaining,
                            errorMessage = null,
                            pendienteSinSubir = pendiente,
                            // D9: sólo una lectura que empezó con la pantalla ya de vuelta confirma (Codex r2).
                            saldoPorConfirmar = it.saldoPorConfirmar && !confirmaElSaldo,
                            revisandoSaldo = if (confirmaElSaldo) false else it.revisandoSaldo,
                        )
                    }
                    // D86 (Codex r1 #4): con algo propio sin subir, se vuelve a leer sola hasta que suba (no se queda trabada).
                    if (pendiente != null) {
                        programarRelectura()
                    } else {
                        relectura?.cancel()
                        relecturasSeguidas = 0
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update { current ->
                        // D9: tras una tarjeta, el aviso pasa de «Revisando…» a «No se pudo confirmar…» y los botones
                        // siguen esperando (sólo si es la lectura vigente; una vieja, o una que ya relevó otra más nueva, no
                        // toca el aviso).
                        val vigente = miLectura == lecturasIniciadas && marcaDeEntrega == marcaDeSaldo && confirmaElSaldo
                        val revisando = if (vigente) false else current.revisandoSaldo
                        // Offline-first de lectura: si ya había un cheque cargado, se
                        // sigue mostrando — nunca se tapa una pantalla que funcionaba.
                        if (current.check != null) {
                            current.copy(isLoading = false, revisandoSaldo = revisando)
                        } else {
                            // D90 (PAX, 16-nov, caso 6 del D86): sin cuenta y con un cobro propio sin subir, la pantalla dice ESO
                            // (no sólo «no se pudo cargar») y se relee sola hasta que suba. El efectivo ya espera por la cuenta.
                            current.copy(
                                isLoading = false,
                                revisandoSaldo = revisando,
                                errorMessage = "No se pudo cargar la cuenta",
                                pendienteSinSubir = pendienteAntes ?: current.pendienteSinSubir,
                            )
                        }
                    }
                    // D86 (Codex r2 #3): una cuenta bloqueada por algo propio sin subir se sigue releyendo aunque una lectura falle.
                    if (miLectura == lecturasIniciadas && _state.value.pendienteSinSubir != null) programarRelectura()
                },
            )
        }
        }
    }

    /**
     * D86: el saldo de la mesa abierta sin red aquí, de la cola y la libreta ([SaldoDeMesaSinRed]). Conocido = se cobra con él;
     * en gris = el efectivo espera con su razón. Sólo se aplica si la sesión sigue siendo esa misma cuenta provisional.
     */
    private fun calcularSaldoSinRed(session: TableSession.Active) {
        val vId = venueId ?: return
        viewModelScope.launch {
            // D86 (Codex r5): leer la cuenta, calcular el saldo y cobrar van EN FILA, nunca a la vez.
            fila.withLock {
            val cobrosAlSalir = cobrosIniciados
            val miCalculo = ++calculosDeSaldo
            val saldo = try {
                // D86 (Codex r0 #2): la apertura ya confirmada por otro camino promueve la sesión (y el colector relee la cuenta).
                if (promoverSiYaSeConfirmo(vId, session)) return@launch
                saldoDeMesaSinRed.saldo(vId, session.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [TableCheckout] No se pudo leer la cola para el saldo sin red")
                SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
            }
            val actual = tableSession.current()
            if (actual == null || actual.orderId != session.orderId || !actual.isProvisional || !_state.value.isProvisional) return@launch
            // D86 (Codex r2 #1): un cobro empezó mientras se calculaba: este saldo puede ser de antes de él (el cobro lo vuelve a
            // revisar al cobrar y [onPaymentCompleted] descuenta lo suyo).
            if (_state.value.isProcessingCash || cobrosIniciados != cobrosAlSalir || miCalculo != calculosDeSaldo) return@launch
            val restante = (saldo as? SaldoSinRed.Conocido)?.porPagar ?: BigDecimal.ZERO
            tableSession.updateTotal(restante)
            _state.update { it.copy(saldoSinRed = saldo, remaining = restante) }
        }
        }
    }

    /** La cuenta ya existe en el SERVIDOR (ACK de OPEN_TABLE): desde aquí manda su cheque, no el saldo sin red. */
    private fun aplicarPromocion() {
        promocionPendiente = false
        _state.update { it.copy(isProvisional = false, saldoSinRed = null, remaining = BigDecimal.ZERO, isLoading = true) }
        loadCheck()
    }

    /** Al terminar un cobro en efectivo: lo que se pospuso durante él (Codex r1 #1). */
    private fun alTerminarElCobro() {
        if (tableSession.current() == null) {
            promocionPendiente = false
            releerAlTerminarElCobro = false
            return
        }
        when {
            promocionPendiente -> {
                releerAlTerminarElCobro = false
                aplicarPromocion()
            }
            releerAlTerminarElCobro -> {
                releerAlTerminarElCobro = false
                loadCheck()
            }
        }
    }

    /**
     * D92: se mandó un cambio de dinero a esta cuenta (descuento o cargo) y pudo aplicarse en el SERVIDOR aunque la respuesta
     * no llegara: la cuenta en pantalla ya no es de fiar. Ni efectivo ni tarjeta hasta que una lectura que salga DESPUÉS lo
     * confirme; las que ya viajaban se descartan. [releerYa] en éxito; tras un fallo, a los [RELECTURA_MS] (deja ver el aviso).
     * También tras un «no» del SERVIDOR (Codex r1 P1): puede ser el reenvío automático de OkHttp de un cambio que sí pasó
     * («ese descuento ya no está»).
     */
    private fun cuentaPorConfirmar(releerYa: Boolean) {
        lecturasIniciadas++
        relecturasSeguidas = 0
        _state.update { it.copy(pendienteSinSubir = it.pendienteSinSubir ?: CUENTA_POR_CONFIRMAR) }
        if (releerYa) loadCheck() else programarRelectura()
    }

    /** D92: un descuento o cargo sale al SERVIDOR / ya regresó: mientras va en camino, ni efectivo ni tarjeta. */
    private fun empiezaUnCambio() = _state.update { it.copy(cambiosEnCamino = it.cambiosEnCamino + 1) }
    private fun terminaUnCambio() = _state.update { it.copy(cambiosEnCamino = (it.cambiosEnCamino - 1).coerceAtLeast(0)) }

    /** D92: con un cobro en curso no se cambia la cuenta (el cobro ya salió con el importe de antes). */
    private fun cobroEnCurso(): Boolean = _state.value.isProcessingCash || _state.value.isHandingOffToCard

    private fun programarRelectura() {
        if (relectura?.isActive == true) return
        if (relecturasSeguidas >= MAX_RELECTURAS) return
        relecturasSeguidas++
        relectura = viewModelScope.launch {
            kotlinx.coroutines.delay(RELECTURA_MS)
            relectura = null
            if (_state.value.pendienteSinSubir != null && !_state.value.isProcessingCash) loadCheck()
        }
    }

    /**
     * D86 (Codex r2 #1–#2): la última revisión, justo antes de encolar el efectivo. `null` = se cobra.
     * - Mesa abierta sin red aquí: su apertura ya confirmada (se promueve y se relee) o su saldo de la cola, que debe seguir
     *   siendo [restanteMostrado] (un rechazo, otro cambio o un cobro propio lo cambian: se pinta el nuevo y no se cobra).
     * - Mesa del SERVIDOR: nada propio sin subir de esa cuenta.
     */
    private suspend fun motivoParaNoCobrar(venueId: String, session: TableSession.Active, restanteMostrado: BigDecimal): String? = try {
        if (_state.value.remaining.compareTo(restanteMostrado) != 0) {
            // D86 (Codex r5): mientras este cobro esperaba su turno en la fila, una lectura cambió lo que falta.
            "La cuenta cambió: ahora falta ${moneyEnTexto(_state.value.remaining)}. Revisa antes de cobrar."
        } else if (session.isProvisional) {
            if (promoverSiYaSeConfirmo(venueId, session)) {
                CUENTA_SE_ACTUALIZA
            } else {
                when (val saldo = saldoDeMesaSinRed.saldo(venueId, session.orderId)) {
                    is SaldoSinRed.EnGris -> {
                        _state.update { it.copy(saldoSinRed = saldo, remaining = BigDecimal.ZERO) }
                        saldo.motivo
                    }
                    is SaldoSinRed.Conocido -> if (saldo.porPagar.compareTo(restanteMostrado) != 0) {
                        _state.update { it.copy(saldoSinRed = saldo, remaining = saldo.porPagar) }
                        tableSession.updateTotal(saldo.porPagar)
                        "La cuenta cambió: ahora falta ${moneyEnTexto(saldo.porPagar)}. Revisa antes de cobrar."
                    } else {
                        null
                    }
                }
            }
        } else {
            pendienteDeSubirSeguro(venueId, session)?.also { motivo ->
                _state.update { it.copy(pendienteSinSubir = motivo) }
                programarRelectura()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "⚠️ [TableCheckout] No se pudo revisar la cuenta antes de cobrar")
        SALDO_NO_RECUPERADO
    }

    /** [SaldoDeMesaSinRed.pendienteDeSubir] sin lanzar: si la cola no se puede leer, cuenta como «no se sabe» (en gris). */
    private suspend fun pendienteDeSubirSeguro(venueId: String, session: TableSession.Active): String? = try {
        saldoDeMesaSinRed.pendienteDeSubir(venueId, session.orderId, listOfNotNull(session.localOrderId))
            // D9 (Codex r1 de la unión): una tarjeta de esta cuenta aprobada que el SERVIDOR todavía no tiene (la PAX enseña
            // «Aprobado» antes de registrarla) tampoco está en la cuenta leída: ni efectivo ni otra tarjeta hasta que suba.
            ?: dineroDeTarjeta.motivo(venueId, session.orderId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "⚠️ [TableCheckout] No se pudo revisar la cola de esta cuenta")
        SALDO_NO_RECUPERADO
    }

    /** D86 (Codex r0 #2): `true` si la apertura sin red de [session] ya está confirmada en la cola y se promovió la sesión. */
    private suspend fun promoverSiYaSeConfirmo(venueId: String, session: TableSession.Active): Boolean {
        val apertura = saldoDeMesaSinRed.aperturaConfirmada(venueId, session.orderId) ?: return false
        tableSyncCoordinator.onAck(SyncIntentTypes.OPEN_TABLE, session.orderId, apertura.orderId, apertura.orderNumber, apertura.version)
        return true
    }

    /** D86: «Reintentar» de la pantalla: vuelve a leer y rearma las relecturas automáticas. */
    fun reintentar() {
        relecturasSeguidas = 0
        loadCheck()
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
            tipAmount = s.tipAmount,
        )
    }

    /**
     * D9: el botón «Tarjeta». Reserva el cobro y arma la solicitud CON la propina escrita. `null` si ya
     * hay un cobro en curso (efectivo esperando respuesta, o una tarjeta que se está abriendo): la
     * misma propina no sale en dos pagos (Codex r1 #2). La navegación avisa después cómo terminó:
     * [cobroConTarjetaEntregado] o [cobroConTarjetaNoSalio].
     */
    fun prepararCobroConTarjeta(amount: BigDecimal): CardChargeRequest? {
        val s = _state.value
        // D90: se revalida al tocar, no sólo al pintar el botón (un toque que llegó en el cuadro anterior a la gris).
        // D9 (encargo 49276c): tras entregar una tarjeta, no sale otra con el saldo de antes de releer.
        if (s.isProcessingCash || s.isHandingOffToCard || s.saldoPorConfirmar || !s.cardPaymentEnabled) return null
        // D92: con un descuento o cargo en camino, o la cuenta por confirmar, no sale con el importe de antes (ya va en lo de arriba).
        if (s.pendienteSinSubir != null || s.cambiosEnCamino > 0) return null
        val request = buildPaymentPayload(amount)
        _state.update { it.copy(isHandingOffToCard = true) }
        return request
    }

    /**
     * «Cobrar» ya recibió la solicitud: la propina viajó con ella y la casilla se vacía (una propina,
     * un cobro). Si esta pantalla vuelve a cobrar otra parte de la cuenta (la pila de navegación la
     * conserva), no la repite.
     */
    fun cobroConTarjetaEntregado() {
        // D9 (encargo 49276c): desde aquí el saldo de esta pantalla puede ser el de ANTES de la tarjeta.
        marcaDeSaldo++
        alFrenteDesdeLaEntrega = false
        _state.update {
            it.copy(isHandingOffToCard = false, tipAmount = BigDecimal.ZERO, saldoPorConfirmar = true, revisandoSaldo = false)
        }
    }

    /**
     * D9 (encargo 49276c): la pantalla volvió a quedar al frente (p. ej. con el «atrás» del sistema desde «Aprobado»,
     * que no pasa por «Continuar pagando»). Si entregó una tarjeta, relee la cuenta antes de dejar cobrar otra vez.
     */
    fun alVolverAlFrente() {
        if (!alFrenteDesdeLaEntrega) {
            // Primera vez al frente tras la entrega: lo que salió mientras «Cobrar» estaba abierto ya no vale.
            marcaDeSaldo++
            alFrenteDesdeLaEntrega = true
        }
        if (_state.value.saldoPorConfirmar) loadCheck()
    }

    /** «Cobrar» no se abrió (la terminal no estaba lista): la casilla CONSERVA la propina (Codex r1 #1). */
    fun cobroConTarjetaNoSalio() {
        _state.update { it.copy(isHandingOffToCard = false) }
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
        // D86: sin cuenta leída (o sin saldo sin red seguro) no se cobra con un número que no es el de la cuenta.
        _state.value.efectivoBloqueadoPor?.let { motivo ->
            _state.update { it.copy(errorMessage = motivo) }
            return
        }
        // D9: una sola reserva de cobro — con una tarjeta abriéndose, el efectivo espera.
        if (_state.value.isProcessingCash || _state.value.isHandingOffToCard) return
        // D9 (encargo 49276c): tras entregar una tarjeta, el saldo de aquí puede ser el de antes: no se cobra hasta releerlo.
        if (_state.value.saldoPorConfirmar) return

        // 🧾 Capturado ANTES de mutar el estado: [onPaymentCompleted] (más abajo)
        // recalcula `remaining`, así que "¿este cobro salda la cuenta?" solo se
        // puede leer AQUÍ contra el restante todavía vigente — es la MISMA
        // condición que decide si la mesa se libera sola, ver KDoc de
        // [CashReceiptSummary.isSettlingPayment].
        val remainingBeforeThisPayment = _state.value.remaining
        val tipAtChargeTime = _state.value.tipAmount
        val willSettle = amount >= remainingBeforeThisPayment

        _state.update { it.copy(isProcessingCash = true, errorMessage = null) }
        cobrosIniciados++
        viewModelScope.launch {
            // D86 (Codex r5): leer la cuenta, calcular el saldo y cobrar van EN FILA, nunca a la vez.
            fila.withLock {
            // D86 (Codex r2): el saldo se valida AL COBRAR, no sólo cuando se pintó: la cola pudo cambiar después (una apertura
            // rechazada o confirmada, un cobro propio que no ha subido). Si ya no cuadra, no se cobra y se dice por qué.
            val motivo = motivoParaNoCobrar(vId, session, remainingBeforeThisPayment)
            if (motivo != null) {
                _state.update { it.copy(isProcessingCash = false, errorMessage = motivo) }
                alTerminarElCobro()
                return@launch
            }
            repository.payCash(
                venueId = vId,
                orderId = session.orderId,
                isProvisional = session.isProvisional,
                amount = amount,
                tip = tipAtChargeTime,
                // D86: lo que esta pantalla creía que debía la cuenta antes del cobro (rastro en el SERVIDOR si no cuadra).
                saldoEsperado = if (session.isProvisional) null else remainingBeforeThisPayment,
            ).fold(
                onSuccess = { outcome ->
                    _state.update {
                        it.copy(
                            isProcessingCash = false,
                            // D9: la propina ya se cobró con ESTE pago; la siguiente parte de la cuenta no la repite.
                            tipAmount = BigDecimal.ZERO,
                            notice = if (outcome.queued) {
                                "Sin conexión — cobro guardado; se sincronizará solo"
                            } else {
                                "Cobro registrado"
                            },
                            // 🧾 Receipt: on EVERY cash payment (queued or not) — closes
                            // the asymmetry with TARJETA (which already ends on
                            // "Cobrar"'s success screen with a receipt). See KDoc of
                            // [CashReceiptSummary].
                            cashReceipt = CashReceiptSummary(
                                amount = amount,
                                tipAmount = tipAtChargeTime,
                                totalAmount = amount + tipAtChargeTime,
                                orderNumber = outcome.orderNumber ?: session.orderNumber,
                                receiptUrl = outcome.receiptUrl,
                                autofacturaAvailable = outcome.autofacturaAvailable,
                                queued = outcome.queued,
                                isSettlingPayment = willSettle,
                            ),
                        )
                    }
                    onPaymentCompleted(amount)
                    // D86 (Codex r0 #2): reproducir la cola para este cobro pudo confirmar la apertura sin avisar a la sesión. Va
                    // DESPUÉS de descontar el cobro: así la liberación (si saldó) ya tomó su cuenta local y el restante de un
                    // cobro parcial lo vuelve a leer el SERVIDOR.
                    if (session.isProvisional) {
                        viewModelScope.launch {
                            try {
                                promoverSiYaSeConfirmo(vId, session)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "⚠️ [TableCheckout] No se pudo revisar la apertura tras el cobro")
                            }
                        }
                    }
                    alTerminarElCobro()
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            isProcessingCash = false,
                            errorMessage = (e as? TablesRepository.PayCashRejectedException)?.let { rechazo ->
                                // D6: la cola ya reintenta sola con un número nuevo cuando es seguro; si aun así llega
                                // aquí, el texto del servidor («La secuencia del dispositivo…») no le dice nada a la cajera.
                                if (rechazo.errorCode == "STALE_DEVICE_SEQUENCE") RECHAZO_POR_NUMERO else rechazo.message
                            } ?: "No se pudo registrar el cobro",
                        )
                    }
                    alTerminarElCobro()
                },
            )
        }
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
        // Lo que llega tarde sólo toca la MISMA sesión que se cobró (start/open/clear suben la época).
        val epocaDelCobro = tableSession.epoch
        viewModelScope.launch {
            // Termina aunque el cajero cierre el recibo y la pantalla se vaya (Codex r2 #2, D85): antes, cerrar el recibo
            // rápido cancelaba la liberación y dejaba la sesión pegada a una cuenta ya pagada.
            withContext(NonCancellable) {
                repository.clearTable(vId, session.tableId, session.orderId, session.isProvisional).fold(
                    onSuccess = {
                        if (tableSession.epoch == epocaDelCobro) tableSession.clear()
                    },
                    onFailure = { e ->
                        // El server rechaza si queda saldo (p.ej. la propina de un cargo con
                        // tarjeta aún no se refleja) — no es un error para el mesero, es el
                        // server protegiendo contra liberar una mesa que en realidad debe.
                        Timber.w(e, "⚠️ [TableCheckout] clearTable no liberó la mesa todavía (venue=%s, table=%s)", vId, session.tableId)
                        // na286b5f: también tras un 500 o un error inesperado (antes sólo tras un 4xx): si no, «Tu mesa» quedaba
                        // pegada a una cuenta ya pagada. Un fallo de red nunca llega aquí (`clearTable` lo encola y contesta
                        // éxito). Una sola pregunta, de inmediato: reintentar más tarde podía soltar la sesión mientras el mesero
                        // ya abre la siguiente mesa (Codex r3 #5).
                        if (e !is java.io.IOException) {
                            soltarSesionSiSuCuentaYaNoDebe(vId, session, epocaDelCobro)
                        }
                    },
                )
            }
        }
    }

    /**
     * La mesa tiene OTRA cuenta que debe (D85: la que la terminal abrió sin red queda aparte junto a la de otro mesero)
     * y el SERVIDOR no libera la mesa entera. Si ESTA cuenta ya no debe, la sesión se suelta: quedaba pegada a una
     * cuenta pagada («Tu mesa», total $0) aunque la mesa sólo siguiera ocupada por la otra (Codex r1 #2). Se pregunta al
     * SERVIDOR, que manda: si su cuenta todavía debe (p. ej. la propina de una tarjeta aún no se refleja) o no se puede
     * preguntar, la sesión se queda como antes. Sólo suelta la MISMA sesión que se cobró.
     */
    private suspend fun soltarSesionSiSuCuentaYaNoDebe(vId: String, session: TableSession.Active, epocaDelCobro: Long) {
        val detalle = repository.getOrder(vId, session.orderId).getOrNull() ?: return
        val yaNoDebe = detalle.paymentStatus == "PAID" || detalle.status == "COMPLETED"
        if (!yaNoDebe) return
        if (tableSession.epoch != epocaDelCobro || tableSession.current()?.orderId != session.orderId) return
        Timber.i("🪑 [TableCheckout] %s ya no debe; la mesa sigue con otra cuenta — se suelta la sesión", session.orderId)
        tableSession.clear()
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Cierra la confirmación de recibo — la pantalla la llama tras "Cerrar"/
     * "Compartir". Cuando el cobro SALDÓ la cuenta, [TableCheckoutScreen]
     * retrasa el salto atómico a Tables (ver su KDoc del bug de pantalla en
     * blanco) hasta que esto se llama, para que el mesero SIEMPRE alcance a
     * ver/compartir el recibo antes de que la pantalla se destruya.
     */
    fun consumeCashReceipt() {
        _state.update { it.copy(cashReceipt = null) }
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
     * D2 (17-nov): por qué la ÚLTIMA carga del catálogo falló, para que [DiscountPickerSheet] no diga «No hay descuentos» cuando
     * en realidad no se pudo preguntar. `null` = la última carga salió bien (o todavía no hay). El «sin conexión» sale de ESA
     * petición ([esFallaDeRedEnMesas]), nunca de una bandera de red.
     */
    private val _discountsLoadError = MutableStateFlow<String?>(null)
    val discountsLoadError: StateFlow<String?> = _discountsLoadError.asStateFlow()

    /** D2 (Codex r1): número de la carga vigente; sólo ella escribe lista, aviso y «cargando» (abrir, cerrar y reabrir deja dos en vuelo). */
    private var cargaDeDescuentos = 0L

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
        val miCarga = ++cargaDeDescuentos
        viewModelScope.launch {
            _isLoadingDiscounts.value = true
            repository.getAvailableDiscounts(vId, session.orderId).fold(
                onSuccess = { discounts ->
                    if (miCarga != cargaDeDescuentos) return@fold
                    _availableDiscounts.value = discounts
                    _discountsLoadError.value = null
                    _isLoadingDiscounts.value = false
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    if (miCarga != cargaDeDescuentos) return@fold
                    _isLoadingDiscounts.value = false
                    // Antes iba a `errorMessage`, que con la cuenta ya cargada no se pinta: sólo quedaba el «No hay» de la hoja.
                    _discountsLoadError.value = if (esFallaDeRedEnMesas(e)) {
                        "Sin conexión: la lista de descuentos necesita internet."
                    } else {
                        "No se pudieron cargar los descuentos. Cierra y vuelve a intentar."
                    }
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
        if (cobroEnCurso()) return // D92
        if (_isApplyingDiscount.value) return

        _isApplyingDiscount.value = true
        empiezaUnCambio()
        viewModelScope.launch {
            repository.applyDiscount(vId, session.orderId, discountId).fold(
                onSuccess = { outcome ->
                    _isApplyingDiscount.value = false
                    terminaUnCambio()
                    _state.update {
                        it.copy(
                            notice = if (outcome.queued) {
                                "Sin conexión — descuento guardado; se sincronizará solo"
                            } else {
                                "Descuento aplicado"
                            },
                        )
                    }
                    cuentaPorConfirmar(releerYa = !outcome.queued)
                },
                onFailure = { e ->
                    _isApplyingDiscount.value = false
                    terminaUnCambio()
                    if (e is CancellationException) throw e
                    _state.update { it.copy(errorMessage = (e as? BackendHttpException)?.message ?: "No se pudo aplicar el descuento") }
                    cuentaPorConfirmar(releerYa = false)
                },
            )
        }
    }

    private val _isRemovingDiscount = MutableStateFlow(false)
    val isRemovingDiscount: StateFlow<Boolean> = _isRemovingDiscount.asStateFlow()

    /**
     * Quita un descuento YA aplicado — paridad Android 2026-08-06
     * (`paridad-android-tpv.md`, Hallazgo #4). SIEMPRE online, NUNCA se
     * encola (ver KDoc de [TablesRepository.removeDiscount]) — a diferencia
     * de [applyDiscount], que sí tiene equivalente offline. Un intento sin
     * red no falla en silencio ni se disfraza de "guardado": se propaga
     * [TablesRepository.RemoveDiscountRequiresConnectionException] con un
     * mensaje explícito por el snackbar compartido, la misma superficie que
     * ya usa la cortesía de artículo puntual (mismo tipo de asimetría).
     */
    fun removeDiscount(orderDiscountId: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (cobroEnCurso()) return // D92
        if (_isRemovingDiscount.value) return

        _isRemovingDiscount.value = true
        empiezaUnCambio()
        viewModelScope.launch {
            repository.removeDiscount(vId, session.orderId, orderDiscountId).fold(
                onSuccess = {
                    _isRemovingDiscount.value = false
                    terminaUnCambio()
                    _state.update { it.copy(notice = "Descuento quitado") }
                    cuentaPorConfirmar(releerYa = true)
                },
                onFailure = { e ->
                    _isRemovingDiscount.value = false
                    terminaUnCambio()
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            errorMessage = when (e) {
                                is TablesRepository.RemoveDiscountRequiresConnectionException -> e.message
                                is BackendHttpException -> e.message
                                else -> "No se pudo quitar el descuento"
                            },
                        )
                    }
                    cuentaPorConfirmar(releerYa = false)
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

    /** D2: igual que [discountsLoadError], para [ServiceChargePickerSheet] («Este venue no tiene cargos…» sólo si de verdad no hay). */
    private val _serviceChargesLoadError = MutableStateFlow<String?>(null)
    val serviceChargesLoadError: StateFlow<String?> = _serviceChargesLoadError.asStateFlow()

    /** D2 (Codex r1): como [cargaDeDescuentos], para los cargos por servicio. */
    private var cargaDeCargos = 0L

    /**
     * Catálogo del venue — lectura ONLINE pura (ver KDoc de
     * [TablesRepository.getAvailableServiceCharges]). Mismo guard que
     * [loadAvailableDiscounts]: solo alcanzable con el cheque ya cargado.
     */
    fun loadAvailableServiceCharges() {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (session.isProvisional) return
        val miCarga = ++cargaDeCargos
        viewModelScope.launch {
            _isLoadingServiceCharges.value = true
            repository.getAvailableServiceCharges(vId).fold(
                onSuccess = { charges ->
                    if (miCarga != cargaDeCargos) return@fold
                    _availableServiceCharges.value = charges
                    _serviceChargesLoadError.value = null
                    _isLoadingServiceCharges.value = false
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    if (miCarga != cargaDeCargos) return@fold
                    _isLoadingServiceCharges.value = false
                    _serviceChargesLoadError.value = if (esFallaDeRedEnMesas(e)) {
                        "Sin conexión: la lista de cargos por servicio necesita internet."
                    } else {
                        "No se pudieron cargar los cargos por servicio. Cierra y vuelve a intentar."
                    }
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
        if (cobroEnCurso()) return // D92
        if (_isApplyingServiceCharge.value) return

        _isApplyingServiceCharge.value = true
        empiezaUnCambio()
        viewModelScope.launch {
            repository.applyServiceCharge(vId, session.orderId, serviceChargeId).fold(
                onSuccess = { outcome ->
                    _isApplyingServiceCharge.value = false
                    terminaUnCambio()
                    _state.update {
                        it.copy(
                            notice = if (outcome.queued) {
                                "Sin conexión — cargo por servicio guardado; se sincronizará solo"
                            } else {
                                "Cargo por servicio aplicado"
                            },
                        )
                    }
                    cuentaPorConfirmar(releerYa = !outcome.queued)
                },
                onFailure = { e ->
                    _isApplyingServiceCharge.value = false
                    terminaUnCambio()
                    if (e is CancellationException) throw e
                    _state.update { it.copy(errorMessage = (e as? BackendHttpException)?.message ?: "No se pudo aplicar el cargo por servicio") }
                    cuentaPorConfirmar(releerYa = false)
                },
            )
        }
    }

    private val _isRemovingServiceCharge = MutableStateFlow(false)
    val isRemovingServiceCharge: StateFlow<Boolean> = _isRemovingServiceCharge.asStateFlow()

    /**
     * Quita un cargo por servicio YA aplicado — cierra el ÚLTIMO hueco de
     * paridad con Android (`paridad-android-tpv.md`, Hallazgo #4,
     * 2026-08-06; avoqado-server commit `a0470a74` montó la ruta DELETE bajo
     * `/tpv`). SIEMPRE online, NUNCA se encola (ver KDoc de
     * [TablesRepository.removeServiceCharge]) — mismo patrón exacto que
     * [removeDiscount] arriba: no hay intent `REMOVE_SERVICE_CHARGE` en el
     * contrato de 14 tipos, así que un intento sin red no falla en silencio
     * ni se disfraza de "guardado" — se propaga
     * [TablesRepository.RemoveServiceChargeRequiresConnectionException] con
     * un mensaje explícito por el snackbar compartido.
     */
    fun removeServiceCharge(orderServiceChargeId: String) {
        val session = tableSession.current() ?: return
        val vId = venueId ?: return
        if (cobroEnCurso()) return // D92
        if (_isRemovingServiceCharge.value) return

        _isRemovingServiceCharge.value = true
        empiezaUnCambio()
        viewModelScope.launch {
            repository.removeServiceCharge(vId, session.orderId, orderServiceChargeId).fold(
                onSuccess = {
                    _isRemovingServiceCharge.value = false
                    terminaUnCambio()
                    _state.update { it.copy(notice = "Cargo por servicio quitado") }
                    cuentaPorConfirmar(releerYa = true)
                },
                onFailure = { e ->
                    _isRemovingServiceCharge.value = false
                    terminaUnCambio()
                    if (e is CancellationException) throw e
                    _state.update {
                        it.copy(
                            errorMessage = when (e) {
                                is TablesRepository.RemoveServiceChargeRequiresConnectionException -> e.message
                                is BackendHttpException -> e.message
                                else -> "No se pudo quitar el cargo por servicio"
                            },
                        )
                    }
                    cuentaPorConfirmar(releerYa = false)
                },
            )
        }
    }
}

/** D6: lo que ve la cajera si el efectivo de Mesas se rechaza por número (la cola no pudo renumerarlo con seguridad). */
internal const val RECHAZO_POR_NUMERO =
    "No se registró el cobro: la terminal perdió su numeración (¿se reinstaló la app?). Avisa al gerente: queda en «Acciones rechazadas» de Mesas."

/** D92: se mandó un cambio de dinero (descuento o cargo) y la cuenta todavía no se vuelve a leer: no se cobra con la de antes. */
internal const val CUENTA_POR_CONFIRMAR =
    "Falta confirmar el último cambio de esta cuenta: se cobra en cuanto la terminal la vuelva a leer."

/** D86 (Codex r1 #4): cada cuánto se vuelve a leer una cuenta con algo propio sin subir. */
internal const val RELECTURA_MS = 5_000L

/** D86: tope de relecturas automáticas seguidas (2 min); después, «Reintentar» o la vuelta de la red. */
internal const val MAX_RELECTURAS = 24

/** Pesos con dos decimales y signo, para los avisos (`$45.00`). */
private fun moneyEnTexto(monto: BigDecimal): String = "$" + monto.setScale(2, RoundingMode.HALF_UP).toPlainString()
