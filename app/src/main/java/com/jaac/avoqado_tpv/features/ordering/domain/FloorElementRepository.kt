package com.jaac.avoqado_tpv.features.ordering.domain

import com.jaac.avoqado_tpv.core.domain.models.Result

/**
 * Floor Element Repository Interface
 *
 * Defines operations for floor element management (walls, bars, service areas, labels).
 * Follows Clean Architecture - domain layer defines the contract.
 *
 * Pattern: Similar to Square POS / Toast POS floor plan editor
 */
interface FloorElementRepository {
    /**
     * Get all floor elements for a venue.
     *
     * Returns decorative elements for floor plan display.
     *
     * @param venueId ID of the venue
     * @return Result with list of floor elements or error
     */
    suspend fun getFloorElements(venueId: String): Result<List<FloorElement>>

    /**
     * Create a new floor element.
     *
     * @param venueId ID of the venue
     * @param type Type of element (WALL, BAR_COUNTER, etc.)
     * @param positionX X coordinate (0-1)
     * @param positionY Y coordinate (0-1)
     * @param width Width for rectangular elements (0-1), optional
     * @param height Height for rectangular elements (0-1), optional
     * @param rotation Rotation in degrees (0, 90, 180, 270)
     * @param endX End X coordinate for WALL type (0-1), optional
     * @param endY End Y coordinate for WALL type (0-1), optional
     * @param label Text label (e.g., "Cocina", "Baño"), optional
     * @param color Hex color (e.g., "#424242"), optional
     * @param areaId Area ID for filtering, optional
     * @return Result with created floor element or error
     */
    suspend fun createFloorElement(
        venueId: String,
        type: FloorElementType,
        positionX: Float,
        positionY: Float,
        width: Float? = null,
        height: Float? = null,
        rotation: Int = 0,
        endX: Float? = null,
        endY: Float? = null,
        label: String? = null,
        color: String? = null,
        areaId: String? = null
    ): Result<FloorElement>

    /**
     * Update an existing floor element.
     *
     * @param venueId ID of the venue
     * @param elementId ID of the element to update
     * @param positionX New X coordinate (0-1), optional
     * @param positionY New Y coordinate (0-1), optional
     * @param width New width (0-1), optional
     * @param height New height (0-1), optional
     * @param rotation New rotation in degrees, optional
     * @param endX New end X coordinate (0-1), optional
     * @param endY New end Y coordinate (0-1), optional
     * @param label New label, optional
     * @param color New color, optional
     * @param areaId New area ID, optional
     * @return Result with updated floor element or error
     */
    suspend fun updateFloorElement(
        venueId: String,
        elementId: String,
        positionX: Float? = null,
        positionY: Float? = null,
        width: Float? = null,
        height: Float? = null,
        rotation: Int? = null,
        endX: Float? = null,
        endY: Float? = null,
        label: String? = null,
        color: String? = null,
        areaId: String? = null
    ): Result<FloorElement>

    /**
     * Delete a floor element.
     *
     * @param venueId ID of the venue
     * @param elementId ID of the element to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteFloorElement(
        venueId: String,
        elementId: String
    ): Result<Unit>
}
