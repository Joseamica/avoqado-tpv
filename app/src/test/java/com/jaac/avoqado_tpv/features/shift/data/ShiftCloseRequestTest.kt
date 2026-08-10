package com.jaac.avoqado_tpv.features.shift.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.shift.data.dto.CashReconciliationDto
import com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationOutcome
import org.junit.Test

class ShiftCloseRequestTest {

    private val gson = Gson()

    @Test
    fun `default close keeps the exact legacy wire shape`() {
        val json = gson.toJson(
            CloseShiftRequest(
                venueId = "venue-1",
                shiftId = "shift-1"
            )
        )

        assertThat(json).isEqualTo("{\"venueId\":\"venue-1\",\"shiftId\":\"shift-1\"}")
    }

    @Test
    fun `counted zero sends an explicit action and canonical decimal string`() {
        val json = gson.toJson(
            CloseShiftRequest(
                venueId = "venue-1",
                shiftId = "shift-1",
                cashReconciliationAction = CashReconciliationAction.COUNTED,
                countedCash = "0.00"
            )
        )

        assertThat(json).isEqualTo(
            "{\"venueId\":\"venue-1\",\"shiftId\":\"shift-1\"," +
                "\"cashReconciliationAction\":\"COUNTED\",\"countedCash\":\"0.00\"}"
        )
    }

    @Test
    fun `intentional skip sends no synthetic count`() {
        val json = gson.toJson(
            CloseShiftRequest(
                venueId = "venue-1",
                shiftId = "shift-1",
                cashReconciliationAction = CashReconciliationAction.SKIPPED
            )
        )

        assertThat(json).isEqualTo(
            "{\"venueId\":\"venue-1\",\"shiftId\":\"shift-1\"," +
                "\"cashReconciliationAction\":\"SKIPPED\"}"
        )
    }

    @Test
    fun `response DTO supports every additive reconciliation outcome`() {
        val expected = setOf(
            CashReconciliationOutcome.APPLIED,
            CashReconciliationOutcome.SKIPPED,
            CashReconciliationOutcome.LEGACY_APPLIED,
            CashReconciliationOutcome.IGNORED_DISABLED,
            CashReconciliationOutcome.IGNORED_INVALID,
            CashReconciliationOutcome.IGNORED_OVERFLOW,
            CashReconciliationOutcome.NOT_REQUESTED
        )

        assertThat(CashReconciliationOutcome.entries.toSet()).isEqualTo(expected)
        expected.forEach { outcome ->
            val parsed = gson.fromJson(
                "{\"outcome\":\"${outcome.name}\"}",
                CashReconciliationDto::class.java
            )
            assertThat(parsed.outcome).isEqualTo(outcome)
        }
    }
}
