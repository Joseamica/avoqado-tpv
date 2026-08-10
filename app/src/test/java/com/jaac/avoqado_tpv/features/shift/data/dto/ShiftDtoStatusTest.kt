package com.jaac.avoqado_tpv.features.shift.data.dto

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import org.junit.Test

class ShiftDtoStatusTest {

    @Test
    fun `CLOSING remains non-open while the server owns the close claim`() {
        assertThat(shiftDto(status = "CLOSING").toDomain().status).isEqualTo(ShiftStatus.CLOSING)
    }

    @Test
    fun `unknown future status fails closed instead of enabling shift actions`() {
        assertThat(shiftDto(status = "PAUSED_BY_SERVER").toDomain().status).isEqualTo(ShiftStatus.CLOSED)
    }

    private fun shiftDto(status: String) = ShiftDto(
        id = "shift-1",
        venueId = "venue-1",
        staffId = "staff-1",
        startTime = "2026-08-10T10:00:00Z",
        endTime = null,
        status = status,
        startingCash = "500.00",
        endingCash = null,
        totalSales = "0.00",
        totalTips = "0.00",
        totalOrders = 0,
        totalCashPayments = "0.00",
        totalCardPayments = "0.00",
        totalVoucherPayments = "0.00",
        totalOtherPayments = "0.00",
        totalProductsSold = 0,
        staff = null
    )
}
