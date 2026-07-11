package com.jaac.avoqado_tpv.features.payment.domain.model

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
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

    // ------------------------------------------------------------------
    // Processor-aware queue (2026-07-09) — AngelPay rows must rebuild the
    // EXACT context shape so RecordPaymentUseCase routes them like the
    // online path did (order vs fast recorder, processor tag, SIM metadata).
    // ------------------------------------------------------------------

    @Test
    fun `toPaymentContext rebuilds AngelPayPayment for ANGELPAY rows`() {
        val queued = QueuedPayment(
            referenceNumber = "195978383755",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("150.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "merchant_cuid_ap",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "OTHER",
            isInternational = false,
            authorizationNumber = "AUTH99",
            idempotencyKey = "uuid-123",
            processor = ProcessorType.ANGELPAY,
            orderId = "order-9",
            orderNumber = "SN00042",
            shiftId = "shift-7",
            isPortabilidad = true,
            serialNumbers = listOf("8952000000000000001"),
            createdAt = System.currentTimeMillis(),
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.AngelPayPayment::class.java)
        val angelPay = context as PaymentContext.AngelPayPayment
        assertThat(angelPay.orderId).isEqualTo("order-9")
        assertThat(angelPay.orderNumber).isEqualTo("SN00042")
        assertThat(angelPay.shiftId).isEqualTo("shift-7")
        assertThat(angelPay.referenceNumber).isEqualTo("195978383755")
        assertThat(angelPay.authorizationCode).isEqualTo("AUTH99")
        assertThat(angelPay.idempotencyKey).isEqualTo("uuid-123")
        assertThat(angelPay.isPortabilidad).isTrue()
        assertThat(angelPay.serialNumbers).containsExactly("8952000000000000001")
        // AngelPay entry mode must survive the round-trip as OTHER (not inventado CHIP)
        assertThat(angelPay.cardDetails?.entryMode).isEqualTo(CardEntryMode.OTHER)
    }

    @Test
    fun `toPaymentContext keeps FastPayment shape for legacy rows without processor`() {
        val queued = QueuedPayment(
            referenceNumber = "000000188231",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("50.00"),
            tip = BigDecimal("5.00"),
            rating = null,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "502511",
            createdAt = System.currentTimeMillis(),
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.FastPayment::class.java)
    }
}
