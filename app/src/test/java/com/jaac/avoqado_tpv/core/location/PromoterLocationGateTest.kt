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
    ) = PromoterLocationGate.shouldCapture(
        isTerminalActivated = activated,
        isAuthenticated = authenticated,
        trackPromoterLocation = flag,
        now = now,
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
}
