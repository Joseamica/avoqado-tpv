package com.jaac.avoqado_tpv.features.timeclock.domain

import com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsDto
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attendance Verification Tests
 *
 * Tests the TpvSettings attendance verification fields and their behavior.
 *
 * Architecture Decision:
 * - Attendance verification is configured via TpvSettings (NOT VenueModule)
 * - When photo is required, GPS is captured automatically (implicit)
 * - Any venue can enable photo verification regardless of modules
 *
 * Fields tested:
 * - requireClockInPhoto: Whether selfie + GPS is required at clock-in
 * - requireClockOutPhoto: Whether selfie + GPS is required at clock-out
 */
class AttendanceVerificationTest {

    // ========== TpvSettings Domain Model Tests ==========

    @Test
    fun `default TpvSettings has attendance verification disabled`() {
        val settings = TpvSettings.DEFAULT

        assertFalse(
            "Default settings should NOT require clock-in photo",
            settings.requireClockInPhoto
        )
        assertFalse(
            "Default settings should NOT require clock-out photo",
            settings.requireClockOutPhoto
        )
    }

    @Test
    fun `TpvSettings can enable clock-in photo verification`() {
        val settings = TpvSettings(
            requireClockInPhoto = true,
            requireClockOutPhoto = false
        )

        assertTrue(
            "Settings should require clock-in photo when enabled",
            settings.requireClockInPhoto
        )
        assertFalse(
            "Clock-out photo should remain disabled",
            settings.requireClockOutPhoto
        )
    }

    @Test
    fun `TpvSettings can enable clock-out photo verification`() {
        val settings = TpvSettings(
            requireClockInPhoto = false,
            requireClockOutPhoto = true
        )

        assertFalse(
            "Clock-in photo should remain disabled",
            settings.requireClockInPhoto
        )
        assertTrue(
            "Settings should require clock-out photo when enabled",
            settings.requireClockOutPhoto
        )
    }

    @Test
    fun `TpvSettings can enable both clock-in and clock-out photo verification`() {
        val settings = TpvSettings(
            requireClockInPhoto = true,
            requireClockOutPhoto = true
        )

        assertTrue(
            "Settings should require clock-in photo",
            settings.requireClockInPhoto
        )
        assertTrue(
            "Settings should require clock-out photo",
            settings.requireClockOutPhoto
        )
    }

    // ========== DTO Mapping Tests ==========

    @Test
    fun `TpvSettingsDto with null attendance fields defaults to disabled`() {
        val dto = TpvSettingsDto(
            showReviewScreen = true,
            showTipScreen = true,
            showReceiptScreen = true,
            defaultTipPercentage = null,
            tipSuggestions = null,
            requirePinLogin = true,
            showVerificationScreen = null,
            requireVerificationPhoto = null,
            requireVerificationBarcode = null,
            enableShifts = null,
            requireClockInPhoto = null,  // Null from backend
            requireClockOutPhoto = null,  // Null from backend
            requireFacadePhoto = null,
            requireDepositPhoto = null,
            requireClockInToLogin = null,
            kioskModeEnabled = null,
            kioskDefaultMerchantId = null,
            showQuickPayment = null,
            showOrderManagement = null,
            showCryptoOption = null,
            showReports = null,
            showPayments = null,
            showSupport = null,
            showGoals = null,
            showMessages = null,
            showTrainings = null
        )

        val domain = dto.toDomain()

        assertFalse(
            "Null requireClockInPhoto should default to false",
            domain.requireClockInPhoto
        )
        assertFalse(
            "Null requireClockOutPhoto should default to false",
            domain.requireClockOutPhoto
        )
    }

    @Test
    fun `TpvSettingsDto maps attendance fields correctly when true`() {
        val dto = TpvSettingsDto(
            showReviewScreen = true,
            showTipScreen = true,
            showReceiptScreen = true,
            defaultTipPercentage = null,
            tipSuggestions = null,
            requirePinLogin = true,
            showVerificationScreen = null,
            requireVerificationPhoto = null,
            requireVerificationBarcode = null,
            enableShifts = null,
            requireClockInPhoto = true,
            requireClockOutPhoto = true,
            requireFacadePhoto = null,
            requireDepositPhoto = null,
            requireClockInToLogin = null,
            kioskModeEnabled = null,
            kioskDefaultMerchantId = null,
            showQuickPayment = null,
            showOrderManagement = null,
            showCryptoOption = null,
            showReports = null,
            showPayments = null,
            showSupport = null,
            showGoals = null,
            showMessages = null,
            showTrainings = null
        )

        val domain = dto.toDomain()

        assertTrue(
            "requireClockInPhoto should map to true",
            domain.requireClockInPhoto
        )
        assertTrue(
            "requireClockOutPhoto should map to true",
            domain.requireClockOutPhoto
        )
    }

    @Test
    fun `TpvSettingsDto maps attendance fields correctly when false`() {
        val dto = TpvSettingsDto(
            showReviewScreen = true,
            showTipScreen = true,
            showReceiptScreen = true,
            defaultTipPercentage = null,
            tipSuggestions = null,
            requirePinLogin = true,
            showVerificationScreen = null,
            requireVerificationPhoto = null,
            requireVerificationBarcode = null,
            enableShifts = null,
            requireClockInPhoto = false,
            requireClockOutPhoto = false,
            requireFacadePhoto = null,
            requireDepositPhoto = null,
            requireClockInToLogin = null,
            kioskModeEnabled = null,
            kioskDefaultMerchantId = null,
            showQuickPayment = null,
            showOrderManagement = null,
            showCryptoOption = null,
            showReports = null,
            showPayments = null,
            showSupport = null,
            showGoals = null,
            showMessages = null,
            showTrainings = null
        )

        val domain = dto.toDomain()

        assertFalse(
            "requireClockInPhoto should map to false",
            domain.requireClockInPhoto
        )
        assertFalse(
            "requireClockOutPhoto should map to false",
            domain.requireClockOutPhoto
        )
    }

    // ========== Business Logic Tests ==========

    @Test
    fun `attendance verification is independent of other TpvSettings`() {
        // Verify that attendance settings work independently of payment flow settings
        val settings = TpvSettings(
            showReviewScreen = false,
            showTipScreen = false,
            showReceiptScreen = false,
            requireClockInPhoto = true,
            requireClockOutPhoto = true
        )

        // Payment flow disabled
        assertFalse(settings.showReviewScreen)
        assertFalse(settings.showTipScreen)
        assertFalse(settings.showReceiptScreen)

        // Attendance verification enabled
        assertTrue(settings.requireClockInPhoto)
        assertTrue(settings.requireClockOutPhoto)
    }

    @Test
    fun `attendance verification works with enableShifts setting`() {
        // Shifts enabled + attendance verification
        val settingsWithShifts = TpvSettings(
            enableShifts = true,
            requireClockInPhoto = true,
            requireClockOutPhoto = true
        )

        assertTrue(settingsWithShifts.enableShifts)
        assertTrue(settingsWithShifts.requireClockInPhoto)
        assertTrue(settingsWithShifts.requireClockOutPhoto)

        // Shifts disabled + attendance verification (edge case - unusual but valid)
        val settingsWithoutShifts = TpvSettings(
            enableShifts = false,
            requireClockInPhoto = true,
            requireClockOutPhoto = true
        )

        assertFalse(settingsWithoutShifts.enableShifts)
        assertTrue(settingsWithoutShifts.requireClockInPhoto)
        assertTrue(settingsWithoutShifts.requireClockOutPhoto)
    }
}
