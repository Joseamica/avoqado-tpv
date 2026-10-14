package com.jaac.avoqado_tpv.features.payments.domain.models

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules

class PaymentTimeZoneTest {
    private fun assertDate(expected: String, actual: String) {
        // CLDR de Java 17 y 23 difiere sólo en el punto de estas abreviaturas.
        assertEquals(expected, actual.replace("oct.", "oct").replace("jun.", "jun"))
    }

    private fun staleIcu(): ZoneId {
        // Reproduce ICU con horario de verano antiguo y tzdata del sistema actualizado.
        val zone = mockk<ZoneId>(relaxed = true)
        every { zone.id } returns "America/Mexico_City"
        every { zone.rules } returns ZoneRules.of(ZoneOffset.ofHours(-5))
        return zone
    }

    private fun payment(at: String) = Payment(
        id = "clock-payment", orderId = null, orderNumber = null, venueId = "clock-venue",
        amount = BigDecimal("12"), tipAmount = BigDecimal.ZERO, totalAmount = BigDecimal("12"),
        method = PaymentMethod.CARD, processedBy = null, createdAt = Instant.parse(at),
        status = PaymentStatus.COMPLETED, tableName = null,
    )

    @Test fun `history uses updated Mexico rules despite stale ICU`() {
        assertDate("13 oct 2026, 07:08", payment("2026-10-13T13:08:00Z").formatTimestamp(staleIcu()))
    }

    @Test fun `grouped date keeps the business day across Mexico midnight`() {
        assertDate("12 oct 2026", payment("2026-10-13T05:30:00Z").formatDate(staleIcu()))
    }

    // 14-oct: la hora de México va por la ley vigente (desfase fijo −06:00, VenueTimeZone.MEXICO), así que un instante del
    // verano de 2019 ya NO se pinta con el horario de verano de entonces. Avoqado no tiene cobros anteriores a 2022 (el más
    // viejo en producción es de abril de 2026); a cambio, todo cobro real sale bien aunque el aparato traiga tzdata 2021a.
    @Test fun `por default la hora de Mexico sigue la ley vigente, tambien en verano`() {
        assertDate("13 jun 2026, 07:08", payment("2026-06-13T13:08:00Z").formatTimestamp())
    }

    @Test fun `border venue uses its own timezone`() {
        assertDate("13 oct 2026, 06:08", payment("2026-10-13T13:08:00Z").formatTimestamp(ZoneId.of("America/Tijuana")))
    }

    @Test fun `explicit UTC and offset zones keep their offsets`() {
        val p = payment("2026-10-13T13:08:00Z")
        for ((id, expected) in listOf("Z" to "13:08", "UTC+01:00" to "14:08", "UT-02:00" to "11:08", "-03:00" to "10:08")) {
            assertDate("13 oct 2026, $expected", p.formatTimestamp(ZoneId.of(id)))
        }
    }
}
