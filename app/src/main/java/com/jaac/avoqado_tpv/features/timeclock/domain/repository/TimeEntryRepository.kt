package com.jaac.avoqado_tpv.features.timeclock.domain.repository

import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry

/**
 * Repository interface for time entry (clock in/out) operations
 */
interface TimeEntryRepository {

    /**
     * Clock in a staff member
     *
     * @param venueId Venue identifier
     * @param staffId Staff member identifier
     * @param pin Staff PIN for verification
     * @param jobRole Optional job role for this shift
     * @param checkInPhotoUrl Optional Firebase Storage URL of clock-in photo (anti-fraud)
     * @param clockInLatitude Optional GPS latitude for location verification
     * @param clockInLongitude Optional GPS longitude for location verification
     * @param clockInAccuracy Optional GPS accuracy in meters
     * @return Result with created TimeEntry or error
     */
    suspend fun clockIn(
        venueId: String,
        staffId: String,
        pin: String,
        jobRole: String? = null,
        checkInPhotoUrl: String? = null,
        clockInLatitude: Double? = null,
        clockInLongitude: Double? = null,
        clockInAccuracy: Float? = null
    ): Result<TimeEntry>

    /**
     * Clock out a staff member
     *
     * @param venueId Venue identifier
     * @param staffId Staff member identifier
     * @param pin Staff PIN for verification
     * @param checkOutPhotoUrl Optional Firebase Storage URL of clock-out photo (anti-fraud)
     * @param clockOutLatitude Optional GPS latitude for location verification
     * @param clockOutLongitude Optional GPS longitude for location verification
     * @param clockOutAccuracy Optional GPS accuracy in meters
     * @return Result with updated TimeEntry or error
     */
    suspend fun clockOut(
        venueId: String,
        staffId: String,
        pin: String,
        checkOutPhotoUrl: String? = null,
        clockOutLatitude: Double? = null,
        clockOutLongitude: Double? = null,
        clockOutAccuracy: Float? = null
    ): Result<TimeEntry>

    /**
     * Start a break for an active time entry
     *
     * @param timeEntryId Time entry identifier
     * @return Result with updated TimeEntry or error
     */
    suspend fun startBreak(timeEntryId: String): Result<TimeEntry>

    /**
     * End a break for an active time entry
     *
     * @param timeEntryId Time entry identifier
     * @return Result with updated TimeEntry or error
     */
    suspend fun endBreak(timeEntryId: String): Result<TimeEntry>

    /**
     * Get time entries for a venue
     *
     * @param venueId Venue identifier
     * @param staffId Optional filter by staff
     * @param startDate Optional start date filter (ISO 8601)
     * @param endDate Optional end date filter (ISO 8601)
     * @param limit Number of entries to fetch
     * @param offset Pagination offset
     * @return Result with list of TimeEntry or error
     */
    suspend fun getTimeEntries(
        venueId: String,
        staffId: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Result<List<TimeEntry>>

    /**
     * Get currently clocked-in staff for a venue
     *
     * @param venueId Venue identifier
     * @return Result with list of active TimeEntry or error
     */
    suspend fun getActiveTimeEntries(venueId: String): Result<List<TimeEntry>>

    /**
     * Find active time entry for a specific staff member
     *
     * @param venueId Venue identifier
     * @param staffId Staff member identifier
     * @return Result with TimeEntry if clocked in, null if not
     */
    suspend fun findActiveEntryForStaff(
        venueId: String,
        staffId: String
    ): Result<TimeEntry?>
}
