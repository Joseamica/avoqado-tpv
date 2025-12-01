package com.jaac.avoqado_tpv.features.timeclock.domain.model

/**
 * Status of a time entry (clock in/out record)
 */
enum class TimeEntryStatus {
    CLOCKED_IN,
    ON_BREAK,
    CLOCKED_OUT,
    ADMIN_EDITED
}
