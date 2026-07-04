package com.jaac.avoqado_tpv.core.location

import java.time.ZonedDateTime

/**
 * Pure decision: should the TPV capture a promoter location ping right now?
 *
 * "Cambaceo" tracking runs ONLY within [11:00, 18:00) VENUE-local (privacy:
 * work window), only while a session is active, and only when the venue opted
 * in (tpvSettings.trackPromoterLocation).
 *
 * `now` MUST be built with `VenueTimeZone.get(secureStorage)` — never the
 * device zone (rule #18). Kept pure (no clock/DI) so the window logic is
 * unit-testable without Robolectric or work-testing.
 */
object PromoterLocationGate {
    const val WINDOW_START_HOUR = 11
    const val WINDOW_END_HOUR = 18 // exclusive

    fun shouldCapture(
        isTerminalActivated: Boolean,
        isAuthenticated: Boolean,
        trackPromoterLocation: Boolean,
        now: ZonedDateTime,
    ): Boolean =
        isTerminalActivated &&
            isAuthenticated &&
            trackPromoterLocation &&
            now.hour in WINDOW_START_HOUR until WINDOW_END_HOUR
}
