package com.jaac.avoqado_tpv.features.payment.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

class QueuedPaymentTest {

    @Test
    fun `toPaymentContext maps cash queued payment merchant to null`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-1712660400",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("10.00"),
            tip = BigDecimal("1.50"),
            rating = 5,
            merchantAccountId = "",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO",
            createdAt = System.currentTimeMillis()
        )

        val context = queued.toPaymentContext()

        assertThat(context.merchantAccountId).isNull()
    }

    @Test
    fun `toCardDetails returns CASH details for cash queued payment`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-KIOSK-1712660400",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("25.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO-CONFIRMADO",
            createdAt = System.currentTimeMillis()
        )

        val card = queued.toCardDetails()

        assertThat(card.isCash).isTrue()
        assertThat(card.toPaymentMethod()).isEqualTo("CASH")
        assertThat(card.entryMode).isEqualTo(CardEntryMode.MANUAL)
    }

    @Test
    fun `toPaymentContext keeps merchant for non-cash queued payment`() {
        val queued = QueuedPayment(
            referenceNumber = "195978383755",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("100.00"),
            tip = BigDecimal("10.00"),
            rating = 4,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "123456",
            createdAt = System.currentTimeMillis()
        )

        val context = queued.toPaymentContext()
        val card = queued.toCardDetails()

        assertThat(context.merchantAccountId).isEqualTo("merchant_cuid_123")
        assertThat(card.isCash).isFalse()
        assertThat(card.cardBrand).isEqualTo(CardBrand.VISA)
        assertThat(card.entryMode).isEqualTo(CardEntryMode.CHIP)
    }
}
