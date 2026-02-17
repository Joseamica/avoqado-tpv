package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * TPV Message DTO
 *
 * Matches backend TpvMessage model in:
 * `avoqado-server/prisma/schema.prisma` → TpvMessage
 */
data class TpvMessageDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("type")
    val type: String, // ANNOUNCEMENT | SURVEY | ACTION

    @SerializedName("title")
    val title: String,

    @SerializedName("body")
    val body: String,

    @SerializedName("priority")
    val priority: String, // LOW | NORMAL | HIGH | URGENT

    @SerializedName("requiresAck")
    val requiresAck: Boolean,

    @SerializedName("surveyOptions")
    val surveyOptions: List<String>?,

    @SerializedName("surveyMultiSelect")
    val surveyMultiSelect: Boolean,

    @SerializedName("actionLabel")
    val actionLabel: String?,

    @SerializedName("actionType")
    val actionType: String?,

    @SerializedName("expiresAt")
    val expiresAt: String?,

    @SerializedName("createdBy")
    val createdBy: String,

    @SerializedName("createdByName")
    val createdByName: String,

    @SerializedName("status")
    val status: String, // ACTIVE | EXPIRED | CANCELLED

    @SerializedName("createdAt")
    val createdAt: String,

    // History-only fields (present when fetching from /messages/history)
    @SerializedName("deliveryStatus")
    val deliveryStatus: String? = null, // PENDING | DELIVERED | ACKNOWLEDGED | DISMISSED

    @SerializedName("acknowledgedAt")
    val acknowledgedAt: String? = null
)

/**
 * Request body for acknowledging a message
 */
data class AckMessageRequest(
    @SerializedName("staffId")
    val staffId: String? = null
)

/**
 * Request body for submitting a survey response
 */
data class SurveyResponseRequest(
    @SerializedName("selectedOptions")
    val selectedOptions: List<String>,

    @SerializedName("staffId")
    val staffId: String? = null,

    @SerializedName("staffName")
    val staffName: String? = null
)

/**
 * Generic API response wrapper
 */
data class TpvMessageListResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<TpvMessageDto>
)

/**
 * Paginated response for message history endpoint
 */
data class TpvMessageHistoryResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<TpvMessageDto>,

    @SerializedName("total")
    val total: Int,

    @SerializedName("limit")
    val limit: Int,

    @SerializedName("offset")
    val offset: Int
)
