package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for venue-scoped shortcut tiles in the Checkout screen's
 * Shortcuts tab. Backs `MosaicRepository`.
 *
 * Indexed by `(venue_id, position)` so the grid query stays fast and so we
 * can enforce one product per slot per venue without a runtime check.
 */
@Entity(
    tableName = "mosaic_shortcut",
    indices = [
        Index(value = ["venue_id"]),
        Index(value = ["venue_id", "position"], unique = true),
    ],
)
data class MosaicShortcutEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "color_hex") val colorHex: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)
