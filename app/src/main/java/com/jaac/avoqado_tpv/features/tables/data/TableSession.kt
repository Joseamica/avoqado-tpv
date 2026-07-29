package com.jaac.avoqado_tpv.features.tables.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
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
        /** Total del cheque en PESOS (major units, mismo tipo que OrderDetail.total). */
        val total: BigDecimal = BigDecimal.ZERO,
        val mode: Mode = Mode.ORDERING,
        /**
         * Offline-first: la mesa se abrió SIN red — [orderId] es un UUID
         * generado en el dispositivo hasta que el ack de OPEN_TABLE lo
         * promueva al id real ([promoteProvisional], vía
         * [com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator]).
         */
        val isProvisional: Boolean = false,
    ) {
        val label: String get() = "Mesa ${tableNumber ?: tableId}" + (areaName?.let { " · $it" } ?: "")
    }

    private val gson = Gson()

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    fun current(): Active? = _active.value

    /** Arranca la sesión directo con un [Active] ya completo (camino ONLINE feliz — id real desde el inicio). */
    fun start(session: Active) {
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
        _active.value = Active(
            tableId = tableId,
            tableNumber = tableNumber,
            areaName = areaName,
            orderId = localOrderId,
            mode = mode,
            isProvisional = true,
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
        _active.value = _active.value?.copy(total = total)
    }

    fun clear() {
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
