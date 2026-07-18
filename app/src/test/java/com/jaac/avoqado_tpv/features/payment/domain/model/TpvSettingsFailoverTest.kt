package com.jaac.avoqado_tpv.features.payment.domain.model

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsDto
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import org.junit.Test

class TpvSettingsFailoverTest {

    @Test
    fun `toDomain uses safe defaults when failover fields are null`() {
        val dto = baseDto(
            cellularFailoverMode = null,
            cellularFailoverBadReadingsThreshold = null,
            cellularFailoverCooldownSeconds = null,
            cellularFailoverMinCellHoldSeconds = null
        )

        val domain = dto.toDomain()

        assertThat(domain.cellularFailoverMode).isEqualTo(CellularFailoverMode.OFF)
        assertThat(domain.cellularFailoverBadReadingsThreshold).isEqualTo(3)
        assertThat(domain.cellularFailoverCooldownSeconds).isEqualTo(60)
        assertThat(domain.cellularFailoverMinCellHoldSeconds).isEqualTo(120)
    }

    @Test
    fun `toDto maps failover fields from domain`() {
        val settings = TpvSettings(
            cellularFailoverMode = CellularFailoverMode.AUTO_SHADOW,
            cellularFailoverBadReadingsThreshold = 5,
            cellularFailoverCooldownSeconds = 90,
            cellularFailoverMinCellHoldSeconds = 180
        )

        val dto = settings.toDto()

        assertThat(dto.cellularFailoverMode).isEqualTo("AUTO_SHADOW")
        assertThat(dto.cellularFailoverBadReadingsThreshold).isEqualTo(5)
        assertThat(dto.cellularFailoverCooldownSeconds).isEqualTo(90)
        assertThat(dto.cellularFailoverMinCellHoldSeconds).isEqualTo(180)
    }

    @Test
    fun `toDomain falls back to OFF for unknown failover mode`() {
        val dto = baseDto(cellularFailoverMode = "NOT_A_MODE")

        val domain = dto.toDomain()

        assertThat(domain.cellularFailoverMode).isEqualTo(CellularFailoverMode.OFF)
    }

    // La libreta (write-ahead payment ledger) rollout flag — mirrors the failover cases above

    @Test
    fun `toDomain maps payment ledger mode from raw value`() {
        val dto = baseDto(paymentLedgerMode = "SHADOW")

        val domain = dto.toDomain()

        assertThat(domain.paymentLedgerMode).isEqualTo(PaymentLedgerMode.SHADOW)
    }

    @Test
    fun `toDomain defaults payment ledger mode to OFF when null`() {
        val dto = baseDto(paymentLedgerMode = null)

        val domain = dto.toDomain()

        assertThat(domain.paymentLedgerMode).isEqualTo(PaymentLedgerMode.OFF)
    }

    @Test
    fun `toDomain falls back to OFF for unknown payment ledger mode`() {
        val dto = baseDto(paymentLedgerMode = "GARBAGE")

        val domain = dto.toDomain()

        assertThat(domain.paymentLedgerMode).isEqualTo(PaymentLedgerMode.OFF)
    }

    @Test
    fun `toDto maps payment ledger mode from domain`() {
        val settings = TpvSettings(paymentLedgerMode = PaymentLedgerMode.ACTIVE)

        val dto = settings.toDto()

        assertThat(dto.paymentLedgerMode).isEqualTo("ACTIVE")
    }

    private fun baseDto(
        cellularFailoverMode: String? = "OFF",
        cellularFailoverBadReadingsThreshold: Int? = 3,
        cellularFailoverCooldownSeconds: Int? = 60,
        cellularFailoverMinCellHoldSeconds: Int? = 120,
        paymentLedgerMode: String? = null
    ): TpvSettingsDto = TpvSettingsDto(
        showReviewScreen = null,
        showTipScreen = null,
        showReceiptScreen = null,
        defaultTipPercentage = null,
        tipSuggestions = null,
        requirePinLogin = null,
        showVerificationScreen = null,
        requireVerificationPhoto = null,
        requireVerificationBarcode = null,
        enableShifts = null,
        requireClockInPhoto = null,
        requireClockOutPhoto = null,
        requireFacadePhoto = null,
        requireDepositPhoto = null,
        requireClockInToLogin = null,
        kioskModeEnabled = null,
        kioskDefaultMerchantId = null,
        showQuickPayment = null,
        showOrderManagement = null,
        showReports = null,
        showPayments = null,
        showSupport = null,
        showGoals = null,
        showMessages = null,
        showTrainings = null,
        showCryptoOption = null,
        cellularFailoverMode = cellularFailoverMode,
        cellularFailoverBadReadingsThreshold = cellularFailoverBadReadingsThreshold,
        cellularFailoverCooldownSeconds = cellularFailoverCooldownSeconds,
        cellularFailoverMinCellHoldSeconds = cellularFailoverMinCellHoldSeconds,
        paymentLedgerMode = paymentLedgerMode
    )
}
