package com.jaac.avoqado_tpv.features.kiosk.domain.model

import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole

/**
 * Kiosk Staff Session
 *
 * Represents a staff member currently assigned to the kiosk.
 * Used for attributing sales/payments for commissions and tips tracking.
 *
 * Lifecycle:
 * - Created when staff enters PIN to assign themselves
 * - Cleared when staff changes or app restarts
 * - NOT persisted to storage (each shift starts fresh)
 *
 * Usage:
 * - Card payments: processedById = staffSession.staffId
 * - Cash payments: processedById = PIN staff (always requires confirmation)
 *
 * @property staffId The unique identifier of the staff member
 * @property staffName Display name for UI
 * @property staffInitials Initials for compact display (e.g., "JP" for "Juan Pérez")
 * @property role Staff role for display purposes
 */
data class KioskStaffSession(
    val staffId: String,
    val staffName: String,
    val staffInitials: String,
    val role: StaffRole
) {
    companion object {
        /**
         * Generate initials from a full name
         * "Juan Pérez" → "JP"
         * "María" → "MA"
         * "Ana García López" → "AG"
         */
        fun generateInitials(fullName: String): String {
            val parts = fullName.trim().split("\\s+".toRegex())
            return when {
                parts.isEmpty() -> "??"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> "${parts[0].firstOrNull() ?: ""}${parts[1].firstOrNull() ?: ""}".uppercase()
            }
        }
    }
}

/**
 * Roles authorized to be assigned to kiosk for sales attribution
 */
val KIOSK_ASSIGNABLE_ROLES = setOf(
    StaffRole.SUPERADMIN,
    StaffRole.OWNER,
    StaffRole.ADMIN,
    StaffRole.MANAGER,
    StaffRole.CASHIER,
    StaffRole.WAITER
)
