package com.jaac.avoqado_tpv.features.checkout.domain.model

import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Ported from `avoqado-android/.../cart/CartStateTest.kt`. The numeric
 * assertions are byte-identical to the source — verifies that the
 * cents-↔-BigDecimal bridge used by TPV's Discount type produces the same
 * results as avoqado-android's integer-only math.
 */
class CartStateTest {

    private fun percentageDiscount(percent: Int) = Discount(
        id = "d-$percent",
        name = "$percent por ciento",
        type = DiscountType.PERCENTAGE,
        value = BigDecimal(percent),
        scope = DiscountScope.ORDER,
        conditions = null,
        active = true,
        requiresAuthorization = false,
    )

    @Test
    fun `tax applies only to product items`() {
        val productItem = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Hamburguesa",
            unitPriceCents = 1000,
        )
        val customAmount = CartItem(
            type = CartItemType.CustomAmount,
            name = "Importe personalizado",
            unitPriceCents = 500,
        )

        val state = CartState(
            items = listOf(productItem, customAmount),
            orderTaxPercent = 16,
        )

        assertEquals(1500, state.subtotalCents)
        assertEquals(1000, state.taxableSubtotalCents)
        assertEquals(160, state.taxCents)
        assertEquals(1660, state.totalCents)
    }

    @Test
    fun `taxable discount is proportional and reduces tax base`() {
        val productItem = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Pizza",
            unitPriceCents = 1000,
        )
        val customAmount = CartItem(
            type = CartItemType.CustomAmount,
            name = "Servicio",
            unitPriceCents = 500,
        )

        val state = CartState(
            items = listOf(productItem, customAmount),
            orderDiscount = percentageDiscount(10),
            orderTaxPercent = 16,
        )

        assertEquals(150, state.discountCents)
        assertEquals(100, state.taxableDiscountCents)
        assertEquals(900, state.taxableAmountAfterDiscountCents)
        assertEquals(144, state.taxCents)
        assertEquals(1494, state.totalCents)
    }

    @Test
    fun `tax is zero when cart has only custom amounts`() {
        val customAmount = CartItem(
            type = CartItemType.CustomAmount,
            name = "Importe personalizado",
            unitPriceCents = 750,
        )

        val state = CartState(
            items = listOf(customAmount),
            orderTaxPercent = 16,
        )

        assertEquals(0, state.taxableSubtotalCents)
        assertEquals(0, state.taxCents)
        assertEquals(750, state.totalCents)
    }

    @Test
    fun `cortesia line collapses to zero in subtotal`() {
        val regular = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Coca-Cola",
            unitPriceCents = 300,
        )
        val cortesia = CartItem(
            type = CartItemType.ProductItem("prod-2"),
            name = "Limonada (Cortesía)",
            unitPriceCents = 400,
            isCortesia = true,
            cortesiaReason = "Cliente VIP",
        )

        val state = CartState(items = listOf(regular, cortesia))

        assertEquals(300, state.subtotalCents)
        assertEquals(300, state.totalCents)
        assertEquals(2, state.itemCount)
    }

    @Test
    fun `modifiers add to line total per unit`() {
        val itemWithModifiers = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Café",
            unitPriceCents = 500,
            quantity = 2,
            selectedModifiers = listOf(
                SelectedModifier(
                    groupId = "g1",
                    groupName = "Tamaño",
                    modifierId = "m1",
                    modifierName = "Grande",
                    priceInCents = 100,
                ),
                SelectedModifier(
                    groupId = "g2",
                    groupName = "Leche",
                    modifierId = "m2",
                    modifierName = "Almendra",
                    priceInCents = 50,
                ),
            ),
        )

        // (500 + 100 + 50) * 2 = 1300
        assertEquals(1300, itemWithModifiers.totalPriceCents)
        assertEquals(300, itemWithModifiers.modifiersPriceCents)
        assertEquals("Grande, Almendra", itemWithModifiers.modifiersSummary)
        assertTrue(itemWithModifiers.hasCustomizations)
    }

    @Test
    fun `price adjustment overrides unit price`() {
        val item = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Producto",
            unitPriceCents = 1000,
            quantity = 1,
            priceAdjustmentCents = 750, // operator typed in a custom price
        )

        assertEquals(750, item.effectiveUnitPriceCents)
        assertEquals(750, item.totalPriceCents)
        assertTrue(item.hasCustomizations)
    }

    @Test
    fun `empty cart reports isEmpty and zero totals`() {
        val state = CartState()

        assertTrue(state.isEmpty)
        assertEquals(0, state.itemCount)
        assertEquals(0, state.subtotalCents)
        assertEquals(0, state.totalCents)
        assertEquals(0, state.discountCents)
        assertEquals(0, state.taxCents)
    }

    @Test
    fun `discount never exceeds subtotal`() {
        val item = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Item barato",
            unitPriceCents = 100,
        )
        // 200% discount — should clamp to subtotal
        val state = CartState(
            items = listOf(item),
            orderDiscount = percentageDiscount(200),
        )

        assertEquals(100, state.discountCents)
        assertEquals(0, state.totalCents)
    }

    @Test
    fun `hasOnlyCustomAmounts is true when cart has only manual amounts`() {
        val custom = CartItem(
            type = CartItemType.CustomAmount,
            name = "Servicio",
            unitPriceCents = 500,
        )
        val state = CartState(items = listOf(custom))

        assertTrue(state.hasOnlyCustomAmounts)
        assertFalse(state.hasProductItems)
    }

    @Test
    fun `hasProductItems is true when at least one catalog item exists`() {
        val product = CartItem(
            type = CartItemType.ProductItem("prod-1"),
            name = "Producto",
            unitPriceCents = 500,
        )
        val custom = CartItem(
            type = CartItemType.CustomAmount,
            name = "Servicio",
            unitPriceCents = 200,
        )
        val state = CartState(items = listOf(product, custom))

        assertFalse(state.hasOnlyCustomAmounts)
        assertTrue(state.hasProductItems)
    }
}
