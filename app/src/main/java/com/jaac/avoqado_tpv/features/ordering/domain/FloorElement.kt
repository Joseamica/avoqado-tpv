package com.jaac.avoqado_tpv.features.ordering.domain

/**
 * Floor Element
 *
 * Represents decorative elements in floor plan (walls, bars, service areas, labels).
 * Uses same GLOBAL coordinate system as Tables (0-1 relative to venue).
 *
 * **Coordinate System:**
 * - positionX/Y: 0.0 - 1.0 (relative to venue canvas)
 * - Same as Table.positionX/Y for unified rendering
 *
 * **Pattern:** Toast POS / Square POS floor plan decorations
 */
data class FloorElement(
    val id: String,
    val type: FloorElementType,

    // GLOBAL coordinates (0-1 relative to venue canvas)
    val positionX: Float,
    val positionY: Float,

    // For rectangular elements (BAR_COUNTER, SERVICE_AREA)
    val width: Float? = null,
    val height: Float? = null,
    val rotation: Int = 0, // 0, 90, 180, 270 degrees

    // For WALL (line from positionX/Y to endX/endY)
    val endX: Float? = null,
    val endY: Float? = null,

    // Metadata
    val label: String? = null,
    val color: String? = null,

    // Optional area assignment (for filtering)
    val areaId: String? = null
)

/**
 * Floor Element Type
 *
 * Types of decorative elements for floor plan.
 */
enum class FloorElementType {
    WALL,         // Decorative/dividing wall (line)
    BAR_COUNTER,  // Bar counter (rectangle)
    SERVICE_AREA, // Kitchen, bathroom, storage (rectangle)
    LABEL,        // Decorative text label
    DOOR          // Door/entrance
}
