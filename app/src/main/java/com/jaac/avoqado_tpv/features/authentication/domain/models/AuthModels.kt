package com.jaac.avoqado_tpv.features.authentication.domain.models

/**
 * PIN Login Request
 *
 * Request data for TPV PIN authentication.
 * Backend validates PIN and terminal activation status.
 *
 * ⚠️ BREAKING CHANGE (2025-01-03):
 * Now requires serialNumber to prevent login on deactivated terminals.
 *
 * @param pin 4-6 digit numeric PIN
 * @param serialNumber Terminal serial number (from device)
 */
data class PinLoginRequest(
    val pin: String,
    val serialNumber: String
)

/**
 * Authentication Response
 *
 * Response from successful PIN login containing:
 * - JWT tokens (access + refresh)
 * - Staff information
 * - Venue context
 * - Permissions
 *
 * This is the primary auth response used throughout the app.
 */
data class AuthResponse(
    // Auth tokens
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,           // seconds (24 hours = 86400)
    val tokenType: String,         // "Bearer"

    // Auth context
    val staffId: String,
    val venueId: String,
    val role: StaffRole,
    val permissions: List<String>,

    // Staff details
    val staff: StaffMember,
    val venue: VenueInfo,

    // Metadata
    val correlationId: String,
    val issuedAt: String
)

/**
 * Staff Member Info
 *
 * Basic staff details returned from login.
 */
data class StaffMember(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phone: String?,
    val employeeCode: String?,
    val photoUrl: String?,
    val active: Boolean
) {
    /**
     * Full display name for UI
     */
    val displayName: String
        get() = "$firstName $lastName".trim()
}

/**
 * Venue Information
 *
 * Basic venue details for context.
 */
data class VenueInfo(
    val id: String,
    val name: String,
    val posType: String?,
    val posStatus: String?
)

/**
 * Staff Role
 *
 * Hierarchical role system matching backend.
 * Higher roles inherit permissions from lower roles.
 */
enum class StaffRole {
    SUPERADMIN,  // Full system access
    OWNER,       // Organization-wide access
    ADMIN,       // Venue-level management
    MANAGER,     // Operations management
    CASHIER,     // Payment processing
    WAITER,      // Order management
    KITCHEN,     // Kitchen display
    HOST,        // Reservations
    VIEWER;      // Read-only

    companion object {
        /**
         * Parse role from backend string
         * @param value Backend role string
         * @return StaffRole enum
         */
        fun fromString(value: String): StaffRole {
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown role: $value")
        }
    }
}

/**
 * Refresh Token Request
 *
 * Request for refreshing access token without re-entering PIN.
 *
 * @param refreshToken Valid refresh token
 */
data class RefreshTokenRequest(
    val refreshToken: String
)

/**
 * Refresh Token Response
 *
 * New access token with updated expiration.
 */
data class RefreshTokenResponse(
    val accessToken: String,
    val expiresIn: Int,
    val tokenType: String
)
