package com.jaac.avoqado_tpv.features.shift.presentation

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto
import com.jaac.avoqado_tpv.features.shift.data.dto.toDomain
import org.junit.Test

/** D97: el turno abierto llega del SERVIDOR sin `staff` (a propósito); «Cerrar caja» no debe decir «Unknown». */
class D97NombreDelTurnoTest {
    private fun turno(staff: String = "") = Gson().fromJson(
        """{"id":"cshift000000000000000000001","venueId":"v","staffId":"s","startTime":"2026-11-18T10:00:00Z",
           "endTime":null,"status":"OPEN","startingCash":"500.00","endingCash":null,"totalSales":"0","totalTips":"0",
           "totalOrders":0,"totalCashPayments":"0","totalCardPayments":"0","totalVoucherPayments":"0",
           "totalOtherPayments":"0","totalProductsSold":0$staff}""",
        ShiftDto::class.java
    ).toDomain()

    @Test fun `turno abierto sin staff no muestra nombre (ni «Unknown»)`() {
        assertThat(turno().nombreDeQuienAbrio()).isNull()
    }

    @Test fun `caché vieja con «Unknown» o vacía tampoco lo muestra`() {
        assertThat(turno().copy(staffName = "Unknown").nombreDeQuienAbrio()).isNull()
        assertThat(turno().copy(staffName = "").nombreDeQuienAbrio()).isNull()
    }

    @Test fun `con staff muestra su nombre`() {
        val conStaff = turno(""","staff":{"id":"s","firstName":"Ana","lastName":"López"}""")
        assertThat(conStaff.nombreDeQuienAbrio()).isEqualTo("Ana López")
    }
}
