package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Body for POST tpv/geolocation/promoter-ping ("cambaceo" hourly location ping).
 * venueId + staffId travel in the JWT (auth/tenant interceptors) — never in the body.
 */
data class PromoterLocationPingRequestDto(
    @SerializedName("latitude")
    val latitude: Double,

    @SerializedName("longitude")
    val longitude: Double,

    @SerializedName("accuracy")
    val accuracy: Float?,

    // ISO-8601 UTC instant (backend zod: z.string().datetime())
    @SerializedName("capturedAt")
    val capturedAt: String,

    @SerializedName("source")
    val source: String = "PERIODIC",
)

/**
 * Response from POST tpv/geolocation/promoter-ping (201 -> {success, data: {id}}).
 */
data class PromoterLocationPingResponseDto(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: PingIdDto?,
) {
    data class PingIdDto(
        @SerializedName("id")
        val id: String,
    )
}
