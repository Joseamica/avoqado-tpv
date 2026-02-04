package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Floor Element cache (floor plan decorations).
 *
 * **Purpose:** Cache floor elements from backend for offline/slow-network use.
 */
@Entity(
    tableName = "floor_elements_cache",
    indices = [
        Index(value = ["venue_id", "element_id"], unique = true),
        Index(value = ["venue_id"]),
        Index(value = ["cached_at"])
    ]
)
data class FloorElementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "element_id")
    val elementId: String,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "position_x")
    val positionX: Float,

    @ColumnInfo(name = "position_y")
    val positionY: Float,

    @ColumnInfo(name = "width")
    val width: Float?,

    @ColumnInfo(name = "height")
    val height: Float?,

    @ColumnInfo(name = "rotation")
    val rotation: Int,

    @ColumnInfo(name = "end_x")
    val endX: Float?,

    @ColumnInfo(name = "end_y")
    val endY: Float?,

    @ColumnInfo(name = "label")
    val label: String?,

    @ColumnInfo(name = "color")
    val color: String?,

    @ColumnInfo(name = "area_id")
    val areaId: String?,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long
)
