package com.jaac.avoqado_tpv.features.tables.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TABLE_SERVICE — la mesa activa que envuelve el flujo normal de venta
 * (patrón Square: una mesa no es un registro aparte, es una cuenta sobre la
 * que trabaja el registro normal). Puerto deliberado de
 * `avoqado-android/tables/data/TableSession.kt` (Plan C, Task 5), adaptado a
 * los modelos y convenciones YA establecidos por Tasks 2-4 de este repo:
 *
 * - Dinero en `BigDecimal` pesos (major units) — igual que [OrderDetail]/[TableOrder]
 *   ([com.jaac.avoqado_tpv.features.tables.domain.model]), NO cents (android
 *   usa `totalCents: Int`; este módulo no introduce una tercera convención) y
 *   NUNCA `Double` (ver `avoqado-server/.claude/rules/critical-warnings.md`,
 *   "Money = Decimal, Never Float").
 * - `AddOrderItemRequest`/payload en `com.google.gson.JsonObject` — Retrofit +
 *   Gson, no kotlinx.serialization.
 *
 * Singleton: la Mesas screen (Task 6) escribe, Checkout/Payment (Task 7/8)
 * leen. Sin sesión activa, todo flujo existente queda byte-idéntico.
 *
 * Modos:
 * - [Mode.ORDERING]: el CTA del carrito se vuelve "Enviar a cocina" — los
 *   items se agregan a la orden abierta de la mesa (ADD_ITEMS/ronda).
 * - [Mode.PAYING]: el flujo de pago cobra la orden EXISTENTE de la mesa (no
 *   crea una nueva) y la mesa se libera al completar.
 */
@Singleton
class TableSession @Inject constructor() {

    enum class Mode { ORDERING, PAYING }

    data class Active(
        val tableId: String,
        val tableNumber: String? = null,
        val areaName: String? = null,
        /** Id real del server, O un UUID local mientras [isProvisional]. */
        val orderId: String,
        val orderNumber: String? = null,
        /** `Order.version` — CAS optimista para el siguiente ADD_ITEMS. */
        val version: Int = 1,
        /**
         * Lo que la terminal cree que se debe, en PESOS (major units, mismo tipo que OrderDetail.total): lo que falta de la
         * última [cuenta] del SERVIDOR, más las rondas mandadas desde entonces ([porSincronizar]), menos lo cobrado aquí. Es
         * con lo que arranca el cobro, y lo único que tiene sin red (D8).
         */
        val total: BigDecimal = BigDecimal.ZERO,
        val mode: Mode = Mode.ORDERING,
        /**
         * Offline-first: la mesa se abrió SIN red — [orderId] es un UUID
         * generado en el dispositivo hasta que el ack de OPEN_TABLE lo
         * promueva al id real ([promoteProvisional], vía
         * [com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator]).
         */
        val isProvisional: Boolean = false,
        /**
         * D8: la última cuenta que contestó el SERVIDOR para [orderId] en esta visita (la de la pantalla de la mesa o la del
         * cobro). Sin red, el cobro enseña este desglose en vez de «abierta local» y no arranca en $0.
         */
        val cuenta: OrderDetail? = null,
        /** D8: rondas mandadas después de [cuenta] que el SERVIDOR todavía no confirma; ya van sumadas en [total]. */
        val porSincronizar: BigDecimal = BigDecimal.ZERO,
        /**
         * D8: el UUID con que se abrió sin red ([open]); se conserva al promoverla. Lo encolado antes de la promoción todavía lo
         * nombra, así que cuenta como «de esta cuenta» al preguntarle a la cola.
         */
        val idLocal: String? = null,
        /**
         * D8: [total] es un saldo que la terminal puede cobrar. Lo vuelve cierto una lectura del SERVIDOR que se tomó
         * ([cuentaDelServidor]) o abrir la cuenta vacía sin red ([open]). Lo vuelve falso cualquier cambio que esta terminal
         * no sabe calcular ([saldoPorConfirmar]: separar, unir, cortesía, descuento, cargo, comensales). Sin él, [total] es 0,
         * nada se le suma y el cobro espera a la siguiente lectura (Codex r2 #2, r3 #1). «Ver cuenta» y «Abrir mesa» nacen sin él.
         */
        val saldoConfirmado: Boolean = false,
    ) {
        /** D8: los ids con que la cola puede nombrar esta cuenta. */
        val idsDeLaCuenta: Set<String> get() = setOfNotNull(orderId, idLocal)

        val label: String get() = "Mesa ${tableNumber ?: tableId}" + (areaName?.let { " · $it" } ?: "")
    }

    private val gson = Gson()

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    private val _epoch = AtomicLong()

    // D8: todo lo que escribe la sesión corre en el hilo principal (pantallas y `TableSyncCoordinator.handleAck`, que salta a
    // `Dispatchers.Main.immediate`). Por eso no lleva candados: uno alrededor de `_active.value = …` despertaría a quien la
    // escucha DENTRO del candado, y una prueba con Room se quedó trabada así (15-nov).

    /**
     * D8: sube con cada cambio LOCAL del saldo (una ronda, un cobro) y con cada sesión que arranca, se abre o se limpia. Una
     * lectura de la cuenta la captura ANTES de salir a la red; si al volver cambió, la respuesta ya es vieja y no se toma
     * ([cuentaDelServidor]): una cuenta pedida antes de un abono regresaría el saldo de antes y se cobraría dos veces.
     */
    private val _marcaDeSaldo = AtomicLong()

    fun marcaDeSaldo(): Long = _marcaDeSaldo.get()

    /**
     * D8: rondas y cobros en efectivo que salieron y todavía no contestan. Mientras haya uno, una lectura del SERVIDOR puede
     * traerlo o no (Codex r1 #1 con la ronda, r2 #1 con el abono): no se toma. No se reinicia con la sesión: lo que está en
     * camino es de la terminal, no de la pantalla.
     */
    private var cambiosEnVuelo = 0

    /**
     * D8 (Codex r3 #3): sube cada vez que la cola sube o rechaza algo (ACK o REJECTED, nunca RETRY). El cobro con el saldo por
     * confirmar lo escucha para volver a leer la cuenta en cuanto lo que esperaba ya subió, sin que el cajero salga y entre.
     */
    private val _sincronizaciones = MutableStateFlow(0L)
    val sincronizaciones: StateFlow<Long> = _sincronizaciones.asStateFlow()

    fun algoSeSincronizo() {
        _sincronizaciones.value = _sincronizaciones.value + 1
    }

    /**
     * Sube cada vez que alguien ARRANCA, abre o limpia una sesión (no al promoverla ni al refrescar su versión o total). Una
     * operación que espera a la red la captura al empezar: si al volver cambió, el mesero ya entró a otra cuenta —o a la
     * misma otra vez— y la respuesta tardía no debe reemplazarla (Codex r3/r4: comparar el orderId no basta).
     */
    val epoch: Long get() = _epoch.get()

    fun current(): Active? = _active.value

    /** Arranca la sesión directo con un [Active] ya completo (camino ONLINE feliz — id real desde el inicio). */
    fun start(session: Active) {
        _epoch.incrementAndGet()
        _marcaDeSaldo.incrementAndGet()
        _active.value = session
    }

    /**
     * Abre una mesa de forma PROVISIONAL con un UUID generado en el
     * dispositivo — la UI sigue de inmediato (offline-first) sin esperar la
     * respuesta del server. [localOrderId] se promueve al id real cuando
     * llega el ack de OPEN_TABLE ([promoteProvisional]).
     */
    fun open(
        tableId: String,
        localOrderId: String,
        tableNumber: String? = null,
        areaName: String? = null,
        mode: Mode = Mode.ORDERING,
    ) {
        _epoch.incrementAndGet()
        _marcaDeSaldo.incrementAndGet()
        _active.value = Active(
            tableId = tableId,
            tableNumber = tableNumber,
            areaName = areaName,
            orderId = localOrderId,
            mode = mode,
            isProvisional = true,
            idLocal = localOrderId,
            saldoConfirmado = true, // una cuenta que nace aquí sin red está vacía: su saldo es lo que se le mande
        )
    }

    /** Refresca la `version` (CAS) tras una ronda/ack exitosos. */
    fun updateVersion(version: Int) {
        _active.value = _active.value?.copy(version = version)
    }

    /**
     * "Dividir la cuenta": tras un pago PARCIAL el objetivo de cobro pasa a
     * ser el restante — cualquier re-siembra del carrito cobra lo que
     * realmente se debe, no el total original.
     */
    fun updateTotal(total: BigDecimal) {
        _marcaDeSaldo.incrementAndGet()
        _active.value = _active.value?.copy(total = total)
    }

    /**
     * D8: el SERVIDOR contestó la cuenta de [orderId], pedida con [marca] (ver [marcaDeSaldo]). Se toma sólo si sigue siendo la
     * cuenta de la sesión y nada local cambió el saldo desde que se pidió: entonces el saldo es lo que falta según el SERVIDOR
     * y lo por sincronizar vuelve a cero (la lectura ya trae las rondas que la cola alcanzó a mandar). Devuelve si la tomó.
     */
    fun cuentaDelServidor(orderId: String, detail: OrderDetail, marca: Long): Boolean {
        val actual = _active.value ?: return false
        if (actual.orderId != orderId || marca != _marcaDeSaldo.get() || cambiosEnVuelo > 0) return false
        _active.value = actual.copy(
            total = detail.amountLeft ?: detail.total,
            cuenta = detail,
            porSincronizar = BigDecimal.ZERO,
            saldoConfirmado = true,
        )
        return true
    }

    /**
     * D8 (Codex r3 #2): esta terminal cambió la cuenta de una forma cuyo efecto en el saldo no sabe calcular (separar, unir,
     * cortesía, descuento, cargo por servicio, comensales; con red o encolado). El saldo deja de ser cobrable hasta la siguiente
     * lectura tomada, y una lectura pedida antes del cambio ya no se toma (la marca sube). El desglose se queda para verse.
     */
    fun saldoPorConfirmar() {
        val actual = _active.value ?: return
        _marcaDeSaldo.incrementAndGet()
        _active.value = actual.copy(total = BigDecimal.ZERO, porSincronizar = BigDecimal.ZERO, saldoConfirmado = false)
    }

    /** D8: sale una ronda o un cobro en efectivo. Hasta [terminarCambioDeSaldo], ninguna lectura del SERVIDOR se toma. */
    fun empezarCambioDeSaldo() {
        _marcaDeSaldo.incrementAndGet()
        cambiosEnVuelo++
    }

    /**
     * D8: la ronda o el cobro de [orderId] terminó. [montoQueYaSeDebe] = lo que vale una ronda si quedó mandada o encolada
     * (null si no salió, y siempre en un cobro: el abono ya lo restó [updateTotal]). Se suma al saldo hasta que llegue la
     * cuenta real: sin red, el cobro incluye el postre que el mesero acaba de mandar y no se queda corto; con red, la recarga
     * que sigue lo reemplaza. Si la sesión ya es otra cuenta, sólo cierra el cambio.
     */
    fun terminarCambioDeSaldo(orderId: String, montoQueYaSeDebe: BigDecimal?) {
        _marcaDeSaldo.incrementAndGet()
        if (cambiosEnVuelo > 0) cambiosEnVuelo--
        if (montoQueYaSeDebe == null) return
        val actual = _active.value ?: return
        if (orderId !in actual.idsDeLaCuenta) return
        // Codex r3 #1: sobre un saldo que no se conoce, sumar la ronda daría un número cobrable pero incompleto.
        if (!actual.saldoConfirmado) return
        _active.value = actual.copy(
            total = actual.total + montoQueYaSeDebe,
            porSincronizar = actual.porSincronizar + montoQueYaSeDebe,
        )
    }

    fun clear() {
        _epoch.incrementAndGet()
        _marcaDeSaldo.incrementAndGet()
        _active.value = null
    }

    /**
     * Offline-first: el ack de OPEN_TABLE llegó — intercambia el UUID local
     * por el id real del server y la sesión deja de ser provisional. No-op
     * si la sesión activa ya es otra (se cerró o se abrió otra mesa mientras
     * el intent viajaba) — mismo guard que `avoqado-android`.
     */
    fun promoteProvisional(localOrderId: String, orderId: String, orderNumber: String?, version: Int) {
        val session = _active.value ?: return
        if (session.orderId != localOrderId) return
        _active.value = session.copy(
            orderId = orderId,
            orderNumber = orderNumber ?: session.orderNumber,
            version = version,
            isProvisional = false,
        )
    }

    /**
     * Payload de ADD_ITEMS CONSCIENTE de si la sesión sigue provisional — ver
     * `resolveOrderId` en `avoqado-server/src/services/mobile/sync.mobile.service.ts`:
     * un `orderId` con un UUID local (nunca promovido) NO resuelve ahí, el
     * server solo mapea `localOrderId` vía el `localRefMap` del batch. Caso
     * real que esto protege: OPEN_TABLE y un ADD_ITEMS inmediato, ambos
     * encolados sin red, reproducidos en el MISMO batch de replay — sin este
     * distingo, el ADD_ITEMS viajaría con `orderId = <uuid-local>` y el
     * server lo trataría como un id de server real (inexistente) en vez de
     * resolverlo contra el OPEN_TABLE que acababa de crear la orden.
     *
     * @throws IllegalStateException si no hay sesión activa — llamarlo sin
     *   mesa abierta es un bug del caller (Task 7), no un estado válido a
     *   tolerar en silencio.
     */
    fun buildAddItemsPayload(items: List<AddOrderItemRequest>): JsonObject {
        val session = checkNotNull(current()) { "buildAddItemsPayload() sin sesión de mesa activa" }
        return JsonObject().apply {
            if (session.isProvisional) {
                addProperty("localOrderId", session.orderId)
            } else {
                addProperty("orderId", session.orderId)
            }
            add("items", gson.toJsonTree(items))
        }
    }
}
