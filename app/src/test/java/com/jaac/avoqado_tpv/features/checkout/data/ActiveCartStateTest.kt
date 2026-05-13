package com.jaac.avoqado_tpv.features.checkout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCartStateTest {

    @Test
    fun `default state has no items and zero display`() {
        val state = ActiveCartState()

        assertEquals(0, state.itemCount.value)
        assertEquals("$0.00", state.totalDisplay.value)
        assertFalse(state.hasItems)
    }

    @Test
    fun `update emits new values`() {
        val state = ActiveCartState()

        state.update(itemCount = 3, totalDisplay = "$25.50")

        assertEquals(3, state.itemCount.value)
        assertEquals("$25.50", state.totalDisplay.value)
        assertTrue(state.hasItems)
    }

    @Test
    fun `clear resets to defaults`() {
        val state = ActiveCartState()
        state.update(itemCount = 5, totalDisplay = "$99.99")

        state.clear()

        assertEquals(0, state.itemCount.value)
        assertEquals("$0.00", state.totalDisplay.value)
        assertFalse(state.hasItems)
    }
}
