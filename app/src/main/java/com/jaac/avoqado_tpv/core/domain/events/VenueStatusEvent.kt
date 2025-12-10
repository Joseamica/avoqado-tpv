package com.jaac.avoqado_tpv.core.domain.events

import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus

/**
 * Venue Status Events
 *
 * Emitted when venue operational status changes during a session.
 * Used to trigger UI updates and force logout when necessary.
 *
 * Flow:
 * 1. Token refresh returns updated venue data
 * 2. HomeViewModel compares new status vs stored status
 * 3. If changed, emits appropriate VenueStatusEvent
 * 4. UI observes and reacts (show banner, force logout, etc.)
 */
sealed class VenueStatusEvent {

    /**
     * Status Changed Event
     *
     * Emitted when venue status changes to a different value.
     * UI should show notification but not necessarily force logout.
     *
     * Examples:
     * - ACTIVE → TRIAL (demo mode enabled)
     * - TRIAL → ACTIVE (converted to production)
     * - PENDING_ACTIVATION → ACTIVE (KYC approved)
     */
    data class StatusChanged(
        val oldStatus: VenueStatus,
        val newStatus: VenueStatus
    ) : VenueStatusEvent()

    /**
     * Venue Suspended Event
     *
     * Emitted when venue becomes SUSPENDED or ADMIN_SUSPENDED.
     * UI should:
     * - Show modal explaining suspension
     * - Force logout to login screen
     * - Clear session data
     */
    data object VenueSuspended : VenueStatusEvent()

    /**
     * Venue Closed Event
     *
     * Emitted when venue becomes CLOSED.
     * UI should:
     * - Show modal explaining permanent closure
     * - Force logout to login screen
     * - Consider clearing terminal activation
     */
    data object VenueClosed : VenueStatusEvent()

    /**
     * Venue Activated Event
     *
     * Emitted when venue transitions to ACTIVE status.
     * UI should:
     * - Show success notification
     * - Enable all features
     */
    data object VenueActivated : VenueStatusEvent()
}
