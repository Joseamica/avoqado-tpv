package com.jaac.avoqado_tpv.features.authentication.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.authentication.domain.models.*

/**
 * PIN Login Request DTO
 *
 * Network request for PIN authentication.
 * Maps to backend schema: `{ pin: string, serialNumber: string }`
 *
 * ⚠️ BREAKING CHANGE (2025-01-03):
 * Backend now requires serialNumber to validate terminal activation status.
 * This prevents login on deactivated terminals.
 */
data class PinLoginRequestDto(
    @SerializedName("pin") val pin: String,
    @SerializedName("serialNumber") val serialNumber: String
)

/**
 * Authentication Response DTO
 *
 * Network response from successful authentication.
 * Maps to backend `staffSignIn` response.
 */
data class AuthResponseDto(
    // Auth tokens
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresIn") val expiresIn: Int,
    @SerializedName("tokenType") val tokenType: String,

    // Auth context
    @SerializedName("staffId") val staffId: String,
    @SerializedName("venueId") val venueId: String,
    @SerializedName("role") val role: String,
    @SerializedName("permissions") val permissions: List<String>?,

    // Staff details
    @SerializedName("staff") val staff: StaffMemberDto,
    @SerializedName("venue") val venue: VenueInfoDto,

    // Metadata
    @SerializedName("correlationId") val correlationId: String,
    @SerializedName("issuedAt") val issuedAt: String
)

/**
 * Staff Member DTO
 *
 * Staff information from backend.
 */
data class StaffMemberDto(
    @SerializedName("id") val id: String,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("email") val email: String?,
    @SerializedName("phone") val phone: String?,
    @SerializedName("employeeCode") val employeeCode: String?,
    @SerializedName("photoUrl") val photoUrl: String?,
    @SerializedName("active") val active: Boolean
)

/**
 * Venue Info DTO
 *
 * Venue context from backend.
 */
data class VenueInfoDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("posType") val posType: String?,
    @SerializedName("posStatus") val posStatus: String?
)

/**
 * Refresh Token Request DTO
 */
data class RefreshTokenRequestDto(
    @SerializedName("refreshToken") val refreshToken: String
)

/**
 * Refresh Token Response DTO
 */
data class RefreshTokenResponseDto(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("expiresIn") val expiresIn: Int,
    @SerializedName("tokenType") val tokenType: String
)

// ========== Domain → DTO Mappers ==========

/**
 * Convert domain PinLoginRequest to DTO
 */
fun PinLoginRequest.toDto(): PinLoginRequestDto {
    return PinLoginRequestDto(
        pin = pin,
        serialNumber = serialNumber
    )
}

/**
 * Convert domain RefreshTokenRequest to DTO
 */
fun RefreshTokenRequest.toDto(): RefreshTokenRequestDto {
    return RefreshTokenRequestDto(refreshToken = refreshToken)
}

// ========== DTO → Domain Mappers ==========

/**
 * Convert DTO AuthResponse to domain model
 */
fun AuthResponseDto.toDomain(): AuthResponse {
    return AuthResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresIn,
        tokenType = tokenType,
        staffId = staffId,
        venueId = venueId,
        role = StaffRole.fromString(role),
        permissions = permissions ?: emptyList(),
        staff = staff.toDomain(),
        venue = venue.toDomain(),
        correlationId = correlationId,
        issuedAt = issuedAt
    )
}

/**
 * Convert DTO StaffMember to domain model
 */
fun StaffMemberDto.toDomain(): StaffMember {
    return StaffMember(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        employeeCode = employeeCode,
        photoUrl = photoUrl,
        active = active
    )
}

/**
 * Convert DTO VenueInfo to domain model
 */
fun VenueInfoDto.toDomain(): VenueInfo {
    return VenueInfo(
        id = id,
        name = name,
        posType = posType,
        posStatus = posStatus
    )
}

/**
 * Convert DTO RefreshTokenResponse to domain model
 */
fun RefreshTokenResponseDto.toDomain(): RefreshTokenResponse {
    return RefreshTokenResponse(
        accessToken = accessToken,
        expiresIn = expiresIn,
        tokenType = tokenType
    )
}
