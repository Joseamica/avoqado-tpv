package com.jaac.avoqado_tpv.core.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * PromoterLocationGate — pure decision for the "cambaceo" hourly capture.
 * Window [11:00, 18:00) VENUE-local; requires activated terminal + active
 * session + venue flag. `now` is passed in, so the window is testable
 * without a Clock abstraction or Robolectric.
 */
class PromoterLocationGateTest {

    private val mx: ZoneId = ZoneId.of("America/Mexico_City")

    private fun at(hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 7, 2, hour, minute, 0, 0, mx)

    private fun capture(
        activated: Boolean = true,
        authenticated: Boolean = true,
        flag: Boolean = true,
        now: ZonedDateTime = at(12),
        startHour: Int = PromoterLocationGate.WINDOW_START_HOUR,
        endHour: Int = PromoterLocationGate.WINDOW_END_HOUR,
    ) = PromoterLocationGate.shouldCapture(
        isTerminalActivated = activated,
        isAuthenticated = authenticated,
        trackPromoterLocation = flag,
        now = now,
        startHour = startHour,
        endHour = endHour,
    )

    // ── window ──────────────────────────────────────────────────────────

    @Test
    fun `captures at the window start (11am)`() {
        assertThat(capture(now = at(11, 0))).isTrue()
    }

    @Test
    fun `captures during the last window hour (5-59pm)`() {
        assertThat(capture(now = at(17, 59))).isTrue()
    }

    @Test
    fun `no-op before 11am venue time`() {
        assertThat(capture(now = at(10, 59))).isFalse()
    }

    @Test
    fun `no-op at 6pm venue time (window end is exclusive)`() {
        assertThat(capture(now = at(18, 0))).isFalse()
    }

    // ── other gates ─────────────────────────────────────────────────────

    @Test
    fun `no-op when the venue flag is off`() {
        assertThat(capture(flag = false)).isFalse()
    }

    @Test
    fun `no-op when nobody is logged in`() {
        assertThat(capture(authenticated = false)).isFalse()
    }

    @Test
    fun `no-op when the terminal is not activated`() {
        assertThat(capture(activated = false)).isFalse()
    }

    // ── timezone semantics (regression guard) ───────────────────────────

    @Test
    fun `window is evaluated on the zoned hour it receives, not UTC`() {
        // 17:00 Mexico == 23:00 UTC — must still capture (the caller builds
        // `now` with VenueTimeZone, this asserts the gate trusts the zone).
        val fivePmMexico = ZonedDateTime.of(2026, 7, 2, 17, 0, 0, 0, mx)
        assertThat(capture(now = fivePmMexico)).isTrue()
        assertThat(fivePmMexico.withZoneSameInstant(ZoneId.of("UTC")).hour).isEqualTo(23)
    }

    // ── configurable window (server-driven) ────────────────────────────

    @Test
    fun `0-24 window captures at any hour, including 3am`() {
        assertThat(capture(now = at(3), startHour = 0, endHour = 24)).isTrue()
    }

    @Test
    fun `0-24 window captures at midnight`() {
        assertThat(capture(now = at(0), startHour = 0, endHour = 24)).isTrue()
    }

    @Test
    fun `0-24 window captures at the last hour of the day (11pm)`() {
        assertThat(capture(now = at(23), startHour = 0, endHour = 24)).isTrue()
    }

    @Test
    fun `custom window 9-21 captures at 9am (start inclusive)`() {
        assertThat(capture(now = at(9), startHour = 9, endHour = 21)).isTrue()
    }

    @Test
    fun `custom window 9-21 does not capture at 8am`() {
        assertThat(capture(now = at(8), startHour = 9, endHour = 21)).isFalse()
    }

    @Test
    fun `custom window 9-21 captures at 8pm`() {
        assertThat(capture(now = at(20), startHour = 9, endHour = 21)).isTrue()
    }

    @Test
    fun `custom window 9-21 does not capture at 9pm (end exclusive)`() {
        assertThat(capture(now = at(21), startHour = 9, endHour = 21)).isFalse()
    }

    @Test
    fun `absent window params default to the legacy 11-18 behavior`() {
        // capture()'s defaults are WINDOW_START_HOUR/WINDOW_END_HOUR — mirrors
        // what the DTO mapping does when the server omits the new fields.
        assertThat(capture(now = at(11, 0))).isTrue()
        assertThat(capture(now = at(17, 59))).isTrue()
        assertThat(capture(now = at(10, 59))).isFalse()
        assertThat(capture(now = at(18, 0))).isFalse()
    }

    @Test
    fun `invalid window (start greater than or equal to end) falls back to legacy 11-18`() {
        assertThat(capture(now = at(12), startHour = 15, endHour = 10)).isTrue()
        assertThat(capture(now = at(9), startHour = 15, endHour = 10)).isFalse()
    }

    @Test
    fun `invalid window (start out of range) falls back to legacy 11-18`() {
        assertThat(capture(now = at(12), startHour = -1, endHour = 18)).isTrue()
        assertThat(capture(now = at(9), startHour = -1, endHour = 18)).isFalse()
    }

    @Test
    fun `invalid window (end out of range) falls back to legacy 11-18`() {
        assertThat(capture(now = at(12), startHour = 11, endHour = 25)).isTrue()
        assertThat(capture(now = at(9), startHour = 11, endHour = 25)).isFalse()
    }

    @Test
    fun `invalid window (end zero, not the 24h sentinel) falls back to legacy 11-18`() {
        // end=0 is out of the 1..24 valid range (0/24 both mean "24h", but
        // only 24 is accepted as the sentinel — 0 as an end hour is invalid).
        assertThat(capture(now = at(12), startHour = 0, endHour = 0)).isTrue()
        assertThat(capture(now = at(9), startHour = 0, endHour = 0)).isFalse()
    }
}
