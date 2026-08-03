package com.jaac.avoqado_tpv.features.tables.data

import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La ronda que se está armando — SIN enviar todavía (Plan C, Task 7). Singleton
 * compartido por [com.jaac.avoqado_tpv.features.tables.presentation.TableMenuViewModel]
 * (escribe, un tap en el grid agrega una línea) y
 * [com.jaac.avoqado_tpv.features.tables.presentation.TableOrderViewModel] (lee,
 * arma el payload de `sendRound()` y limpia al enviar) — mismo patrón que
 * [TableSession]: un singleton `@Inject` en vez de un ViewModel compartido, para
 * que dos pantallas de Compose Navigation distintas (`TableMenuScreen` empuja
 * sobre `TableOrderScreen` en el back stack, cada una con su PROPIO
 * `hiltViewModel()`) vean el MISMO estado sin acoplar sus ciclos de vida.
 *
 * Dinero en `BigDecimal` pesos — NUNCA `Double` (P1 de hoy, ver
 * `avoqado-server/.claude/rules/critical-warnings.md`).
 */
@Singleton
class PendingRoundCart @Inject constructor() {

    data class Modifier(val id: String, val name: String, val price: BigDecimal = BigDecimal.ZERO)

    data class Line(
        val lineId: String = UUID.randomUUID().toString(),
        val productId: String,
        val name: String,
        val unitPrice: BigDecimal = BigDecimal.ZERO,
        val quantity: Int = 1,
        val modifiers: List<Modifier> = emptyList(),
        val notes: String? = null,
    ) {
        /** Precio por unidad, YA incluidos los modificadores seleccionados. */
        val effectiveUnitPrice: BigDecimal get() = unitPrice + modifiers.sumOf { it.price }
        val lineTotal: BigDecimal get() = effectiveUnitPrice.multiply(BigDecimal(quantity))
    }

    /** La mesa/orden dueña del carrito actual — ver [ensureOrder]. */
    private var ownerOrderId: String? = null

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    val itemCount: Int get() = _lines.value.sumOf { it.quantity }
    val total: BigDecimal get() = _lines.value.fold(BigDecimal.ZERO) { acc, line -> acc + line.lineTotal }

    /**
     * Llamado al entrar a `TableOrderScreen`/`TableMenuScreen` con el
     * `orderId`/`localOrderId` de la sesión activa. Si el carrito quedó de
     * OTRA mesa (el mesero salió sin enviar y abrió una distinta — o cerró
     * sesión y otro mesero entró), se limpia solo. Sin esto, items de la mesa
     * 3 podrían aparecer en la cuenta de la mesa 7.
     */
    fun ensureOrder(orderId: String) {
        if (ownerOrderId != orderId) {
            ownerOrderId = orderId
            _lines.value = emptyList()
        }
    }

    /** Tap simple en el grid, sin modificadores — agrupa con una línea igual existente (mismo producto, sin modificadores). */
    fun addSimple(productId: String, name: String, unitPrice: BigDecimal) {
        val existing = _lines.value.firstOrNull { it.productId == productId && it.modifiers.isEmpty() && it.notes == null }
        _lines.value = if (existing != null) {
            _lines.value.map { if (it.lineId == existing.lineId) it.copy(quantity = it.quantity + 1) else it }
        } else {
            _lines.value + Line(productId = productId, name = name, unitPrice = unitPrice)
        }
    }

    /** Alta desde el sheet de modificadores — siempre su PROPIA línea (nunca se agrupa con otra). */
    fun addWithModifiers(
        productId: String,
        name: String,
        unitPrice: BigDecimal,
        quantity: Int,
        modifiers: List<Modifier>,
        notes: String? = null,
    ) {
        if (quantity < 1) return
        _lines.value = _lines.value + Line(
            productId = productId,
            name = name,
            unitPrice = unitPrice,
            quantity = quantity,
            modifiers = modifiers,
            notes = notes,
        )
    }

    fun updateQuantity(lineId: String, quantity: Int) {
        if (quantity < 1) {
            remove(lineId)
            return
        }
        _lines.value = _lines.value.map { if (it.lineId == lineId) it.copy(quantity = quantity) else it }
    }

    fun remove(lineId: String) {
        _lines.value = _lines.value.filterNot { it.lineId == lineId }
    }

    /** Llamado tras un `sendRound()` exitoso (online u offline-encolado) — la ronda ya viajó. */
    fun clear() {
        _lines.value = emptyList()
    }

    /** Traduce el carrito al shape que `TablesRepository.addItems` espera para `ADD_ITEMS`. */
    fun toAddItemsRequests(): List<AddOrderItemRequest> = _lines.value.map { line ->
        AddOrderItemRequest(
            productId = line.productId,
            quantity = line.quantity,
            notes = line.notes,
            modifierIds = line.modifiers.map { it.id }.ifEmpty { null },
        )
    }
}
