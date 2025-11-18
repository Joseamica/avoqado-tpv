package com.jaac.avoqado_tpv.features.ordering.data.mappers

import com.jaac.avoqado_tpv.features.ordering.data.dto.FloorElementDto
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElement
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementType

/**
 * Map FloorElementDto to Domain Model
 */
fun FloorElementDto.toDomain(): FloorElement {
    return FloorElement(
        id = this.id,
        type = when (this.type) {
            "WALL" -> FloorElementType.WALL
            "BAR_COUNTER" -> FloorElementType.BAR_COUNTER
            "SERVICE_AREA" -> FloorElementType.SERVICE_AREA
            "LABEL" -> FloorElementType.LABEL
            "DOOR" -> FloorElementType.DOOR
            else -> throw IllegalArgumentException("Unknown FloorElementType: ${this.type}")
        },
        positionX = this.positionX.toFloat(),
        positionY = this.positionY.toFloat(),
        width = this.width?.toFloat(),
        height = this.height?.toFloat(),
        rotation = this.rotation,
        endX = this.endX?.toFloat(),
        endY = this.endY?.toFloat(),
        label = this.label,
        color = this.color,
        areaId = this.areaId
    )
}

/**
 * Map FloorElementType to API String
 */
fun FloorElementType.toApiString(): String {
    return when (this) {
        FloorElementType.WALL -> "WALL"
        FloorElementType.BAR_COUNTER -> "BAR_COUNTER"
        FloorElementType.SERVICE_AREA -> "SERVICE_AREA"
        FloorElementType.LABEL -> "LABEL"
        FloorElementType.DOOR -> "DOOR"
    }
}
