package com.jaac.avoqado_tpv.core.location

import java.time.ZonedDateTime

/**
 * Pure decision: should the TPV capture a promoter location ping right now?
 *
 * "Cambaceo" tracking runs ONLY within the venue-configured capture window
 * VENUE-local (privacy: work window; server fields tpvSettings.
 * promoterLocationStartHour/EndHour, default [11:00, 18:00) — same as the
 * legacy hardcoded window), only while a session is active, and only when
 * the venue opted in (tpvSettings.trackPromoterLocation).
 *
 * `now` MUST be built with `VenueTimeZone.get(secureStorage)` — never the
 * device zone (rule #18). Kept pure (no clock/DI) so the window logic is
 * unit-testable without Robolectric or work-testing.
 */
object PromoterLocationGate {
    // Legacy default / fallback window when the server doesn't send a
    // configured window, or sends an invalid one.
    const val WINDOW_START_HOUR = 11
    const val WINDOW_END_HOUR = 18 // exclusive

    fun shouldCapture(
        isTerminalActivated: Boolean,
        isAuthenticated: Boolean,
        trackPromoterLocation: Boolean,
        now: ZonedDateTime,
        startHour: Int = WINDOW_START_HOUR,
        endHour: Int = WINDOW_END_HOUR,
    ): Boolean {
        val (effectiveStart, effectiveEnd) = resolveWindow(startHour, endHour)
        return isTerminalActivated &&
            isAuthenticated &&
            trackPromoterLocation &&
            now.hour >= effectiveStart &&
            now.hour < effectiveEnd
    }

    /**
     * Defensive clamp: an invalid configured window (out of range, or
     * start >= end) falls back to the legacy 11-18 window rather than
     * silently never capturing (start >= end) or crashing (out of range).
     */
    private fun resolveWindow(startHour: Int, endHour: Int): Pair<Int, Int> {
        val validStart = startHour in 0..23
        val validEnd = endHour in 1..24
        return if (validStart && validEnd && startHour < endHour) {
            startHour to endHour
        } else {
            WINDOW_START_HOUR to WINDOW_END_HOUR
        }
    }
}
