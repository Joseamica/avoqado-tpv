package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * `PendingRoundCart` (Plan C, Task 7) — el carrito de la ronda en construcción.
 * Dinero SIEMPRE en `BigDecimal` — NUNCA `Double` (P1 del 2026-07-29, ver
 * `avoqado-server/.claude/rules/critical-warnings.md`, "Money = Decimal, Never
 * Float"). Los tests de suma/multiplicación de abajo usan valores que
 * DIVERGEN visiblemente en punto flotante — no son arbitrarios.
 */
class PendingRoundCartTest {

    private lateinit var cart: PendingRoundCart

    @Before
    fun setUp() {
        cart = PendingRoundCart()
    }

    // region — alta simple: agrupa por producto sin modificadores

    @Test
    fun addSimple_agrupa_el_mismo_producto_sumando_cantidad() {
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))

        assertThat(cart.lines.value).hasSize(1)
        assertThat(cart.lines.value.first().quantity).isEqualTo(2)
        assertThat(cart.itemCount).isEqualTo(2)
    }

    @Test
    fun addSimple_de_productos_distintos_crea_lineas_distintas() {
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))
        cart.addSimple(productId = "p2", name = "Té", unitPrice = BigDecimal("40.00"))

        assertThat(cart.lines.value).hasSize(2)
    }

    // endregion

    // region — dinero: BigDecimal exacto donde Double diverge (P1 del 2026-07-29)

    @Test
    fun el_total_del_carrito_con_montos_que_divergen_en_double_es_exacto_en_bigdecimal() {
        // Mismo trío que OrderDetailMoneyTest (0.10 + 0.20 + 0.05): en Double
        // da 0.35000000000000003, NO 0.35 exacto.
        cart.addSimple(productId = "p1", name = "A", unitPrice = BigDecimal("0.10"))
        cart.addSimple(productId = "p2", name = "B", unitPrice = BigDecimal("0.20"))
        cart.addSimple(productId = "p3", name = "C", unitPrice = BigDecimal("0.05"))

        assertThat(cart.total).isEqualTo(BigDecimal("0.35"))
        assertThat(cart.total.toPlainString()).isEqualTo("0.35")
    }

    @Test
    fun el_mismo_calculo_en_double_diverge_del_resultado_exacto() {
        // Documenta el bug que este carrito NO tiene: la misma suma en punto
        // flotante no da 0.35 exacto — por eso el carrito nunca usa Double.
        val doubleSum = 0.10 + 0.20 + 0.05
        assertThat(doubleSum).isNotEqualTo(0.35)
    }

    @Test
    fun lineTotal_con_modificador_y_cantidad_es_exacto_donde_double_divergiria() {
        // unitPrice 0.10 + modificador 0.20 = 0.30 por unidad; × 3 = 0.90.
        // En Double: (0.1 + 0.2) * 3 = 0.9000000000000001, NO 0.9 exacto.
        cart.addWithModifiers(
            productId = "p1",
            name = "Agua",
            unitPrice = BigDecimal("0.10"),
            quantity = 3,
            modifiers = listOf(PendingRoundCart.Modifier(id = "m1", name = "Grande", price = BigDecimal("0.20"))),
        )

        val line = cart.lines.value.single()
        assertThat(line.effectiveUnitPrice).isEqualTo(BigDecimal("0.30"))
        assertThat(line.lineTotal).isEqualTo(BigDecimal("0.90"))
        assertThat(line.lineTotal.toPlainString()).isEqualTo("0.90")

        val doubleEquivalent = (0.10 + 0.20) * 3
        assertThat(doubleEquivalent).isNotEqualTo(0.9)
    }

    @Test
    fun addWithModifiers_siempre_crea_su_propia_linea_nunca_se_agrupa() {
        cart.addWithModifiers(
            productId = "p1",
            name = "Agua",
            unitPrice = BigDecimal("30.00"),
            quantity = 1,
            modifiers = listOf(PendingRoundCart.Modifier(id = "m1", name = "Grande", price = BigDecimal("15.00"))),
        )
        cart.addWithModifiers(
            productId = "p1",
            name = "Agua",
            unitPrice = BigDecimal("30.00"),
            quantity = 1,
            modifiers = listOf(PendingRoundCart.Modifier(id = "m1", name = "Grande", price = BigDecimal("15.00"))),
        )

        // A diferencia de addSimple, dos altas idénticas CON modificadores
        // quedan como 2 líneas — el mesero puede querer notas/asientos
        // distintos por unidad más adelante (Task 8/checkout).
        assertThat(cart.lines.value).hasSize(2)
    }

    // endregion

    // region — cantidad / remove / clear

    @Test
    fun updateQuantity_a_cero_o_menos_elimina_la_linea() {
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))
        val lineId = cart.lines.value.first().lineId

        cart.updateQuantity(lineId, 0)

        assertThat(cart.lines.value).isEmpty()
    }

    @Test
    fun remove_quita_solo_la_linea_indicada() {
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))
        cart.addSimple(productId = "p2", name = "Té", unitPrice = BigDecimal("40.00"))
        val toRemove = cart.lines.value.first { it.productId == "p1" }.lineId

        cart.remove(toRemove)

        assertThat(cart.lines.value).hasSize(1)
        assertThat(cart.lines.value.first().productId).isEqualTo("p2")
    }

    @Test
    fun clear_vacia_el_carrito() {
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))

        cart.clear()

        assertThat(cart.lines.value).isEmpty()
        assertThat(cart.total).isEqualTo(BigDecimal.ZERO)
    }

    // endregion

    // region — ensureOrder: aislamiento entre mesas

    @Test
    fun ensureOrder_con_la_misma_orden_no_toca_el_carrito() {
        // Orden real de uso (TableOrderViewModel/TableMenuViewModel.init):
        // ensureOrder SIEMPRE se llama antes de que el mesero pueda tocar el
        // grid — un segundo ensureOrder con la MISMA orden (p.ej. reentrar a
        // la pantalla) no debe pisar lo que ya está en el carrito.
        cart.ensureOrder("order-1")
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))

        cart.ensureOrder("order-1")

        assertThat(cart.lines.value).hasSize(1)
    }

    @Test
    fun ensureOrder_con_OTRA_orden_limpia_el_carrito() {
        // El mesero salió de la mesa 3 sin enviar y abrió la mesa 7 — sin
        // esto, los productos de la 3 aparecerían en la cuenta de la 7.
        cart.ensureOrder("order-3")
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))

        cart.ensureOrder("order-7")

        assertThat(cart.lines.value).isEmpty()
    }

    // endregion

    // region — toAddItemsRequests: shape que espera TablesRepository.addItems

    @Test
    fun toAddItemsRequests_mapea_productId_cantidad_y_modificadores() {
        cart.addWithModifiers(
            productId = "p1",
            name = "Agua",
            unitPrice = BigDecimal("30.00"),
            quantity = 2,
            modifiers = listOf(PendingRoundCart.Modifier(id = "m1", name = "Grande", price = BigDecimal("15.00"))),
            notes = "sin hielo",
        )

        val requests = cart.toAddItemsRequests()

        assertThat(requests).hasSize(1)
        val request = requests.single()
        assertThat(request.productId).isEqualTo("p1")
        assertThat(request.quantity).isEqualTo(2)
        assertThat(request.modifierIds).containsExactly("m1")
        assertThat(request.notes).isEqualTo("sin hielo")
    }

    @Test
    fun toAddItemsRequests_sin_modificadores_manda_modifierIds_null() {
        cart.addSimple(productId = "p1", name = "Café", unitPrice = BigDecimal("45.00"))

        val request = cart.toAddItemsRequests().single()

        assertThat(request.modifierIds).isNull()
    }

    // endregion
}
