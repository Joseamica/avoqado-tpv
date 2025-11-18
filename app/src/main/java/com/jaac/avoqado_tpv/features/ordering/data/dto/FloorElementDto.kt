package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.gson.annotations.SerializedName

/**
 * Floor Element DTO
 *
 * Represents floor element data from backend API (walls, bars, service areas, labels).
 */
data class FloorElementDto(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String, // "WALL", "BAR_COUNTER", "SERVICE_AREA", "LABEL", "DOOR"
    @SerializedName("positionX") val positionX: Double,
    @SerializedName("positionY") val positionY: Double,
    @SerializedName("width") val width: Double?,
    @SerializedName("height") val height: Double?,
    @SerializedName("rotation") val rotation: Int,
    @SerializedName("endX") val endX: Double?,
    @SerializedName("endY") val endY: Double?,
    @SerializedName("label") val label: String?,
    @SerializedName("color") val color: String?,
    @SerializedName("areaId") val areaId: String?,
    @SerializedName("active") val active: Boolean
)

/**
 * Create Floor Element Request
 *
 * Request body for creating a new floor element.
 */
data class CreateFloorElementRequest(
    @SerializedName("type") val type: String,
    @SerializedName("positionX") val positionX: Double,
    @SerializedName("positionY") val positionY: Double,
    @SerializedName("width") val width: Double? = null,
    @SerializedName("height") val height: Double? = null,
    @SerializedName("rotation") val rotation: Int? = 0,
    @SerializedName("endX") val endX: Double? = null,
    @SerializedName("endY") val endY: Double? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("areaId") val areaId: String? = null
)

/**
 * Update Floor Element Request
 *
 * Request body for updating an existing floor element.
 * All fields are optional - only send fields that need to be updated.
 */
data class UpdateFloorElementRequest(
    @SerializedName("positionX") val positionX: Double? = null,
    @SerializedName("positionY") val positionY: Double? = null,
    @SerializedName("width") val width: Double? = null,
    @SerializedName("height") val height: Double? = null,
    @SerializedName("rotation") val rotation: Int? = null,
    @SerializedName("endX") val endX: Double? = null,
    @SerializedName("endY") val endY: Double? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("areaId") val areaId: String? = null
)
