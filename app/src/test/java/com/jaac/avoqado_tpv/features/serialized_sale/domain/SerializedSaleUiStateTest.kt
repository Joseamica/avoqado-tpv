package com.jaac.avoqado_tpv.features.serialized_sale.domain

import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ItemCategory
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ItemStatus
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.SerializedItem
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.SerializedSaleUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Verifies the [SerializedSaleUiState.isPriceLocked] rule that drives the
 * read-only price field for promoter SKUs (Asana 1216097720443488).
 *
 * Rule: organization-level category (venueId == null) WITH a suggested price.
 */
class SerializedSaleUiStateTest {

    private fun category(venueId: String?, suggestedPrice: BigDecimal?): ItemCategory =
        ItemCategory(
            id = "cat-1",
            venueId = venueId,
            name = "$100 de promotor",
            description = null,
            suggestedPrice = suggestedPrice,
            sortOrder = 0,
            source = if (venueId == null) "organization" else "venue"
        )

    private fun item(categoryId: String = "cat-1"): SerializedItem =
        SerializedItem(
            id = "item-1",
            venueId = null,
            categoryId = categoryId,
            serialNumber = "8952140064323811829",
            status = ItemStatus.AVAILABLE,
            soldAt = null,
            orderItemId = null,
            createdAt = "2026-06-29T00:00:00Z",
            category = null
        )

    private fun availableState(
        venueId: String?,
        suggestedPrice: BigDecimal?
    ): SerializedSaleUiState = SerializedSaleUiState(
        scanResult = ScanResult.Available(
            item = item(),
            category = category(venueId, suggestedPrice),
            suggestedPrice = suggestedPrice
        )
    )

    @Test
    fun `org-level category with suggested price is locked`() {
        // "$100 de promotor" / "E-SIM de promotor": org-level, fixed price.
        assertTrue(availableState(venueId = null, suggestedPrice = BigDecimal("100")).isPriceLocked)
    }

    @Test
    fun `venue-level category stays editable even with suggested price`() {
        assertFalse(availableState(venueId = "venue-1", suggestedPrice = BigDecimal("100")).isPriceLocked)
    }

    @Test
    fun `org-level category without suggested price stays editable`() {
        // No fixed price to lock to → promoter enters the amount.
        assertFalse(availableState(venueId = null, suggestedPrice = null).isPriceLocked)
    }

    @Test
    fun `available scan with null category is not locked`() {
        val state = SerializedSaleUiState(
            scanResult = ScanResult.Available(
                item = item(),
                category = null,
                suggestedPrice = BigDecimal("100")
            )
        )
        assertFalse(state.isPriceLocked)
    }

    @Test
    fun `non-available scan results are never locked`() {
        assertFalse(SerializedSaleUiState(scanResult = null).isPriceLocked)
        assertFalse(
            SerializedSaleUiState(scanResult = ScanResult.NotRegistered("8952140064323811829")).isPriceLocked
        )
        assertFalse(SerializedSaleUiState(scanResult = ScanResult.ModuleDisabled).isPriceLocked)
    }
}
