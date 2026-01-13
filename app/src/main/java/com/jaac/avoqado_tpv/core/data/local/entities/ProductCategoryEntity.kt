package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for ProductCategory cache.
 *
 * **Purpose:** Cache product categories from backend for instant offline access.
 *
 * **Cache Strategy:**
 * - Loaded with products (same TTL: 24 hours)
 * - Sorted by display_order
 * - Tenant-isolated by venue_id
 *
 * **Performance:**
 * - Indexed by (venue_id, category_id) for uniqueness
 * - Indexed by cached_at for TTL-based cleanup
 */
@Entity(
    tableName = "product_categories",
    indices = [
        Index(value = ["venue_id", "category_id"], unique = true),  // Unique per venue
        Index(value = ["cached_at"])  // TTL cleanup
    ]
)
data class ProductCategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "category_id")
    val categoryId: String,  // Backend category ID

    @ColumnInfo(name = "venue_id")
    val venueId: String,  // Tenant isolation

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "display_order")
    val displayOrder: Int,

    @ColumnInfo(name = "product_count")
    val productCount: Int,

    @ColumnInfo(name = "emoji")
    val emoji: String,

    // UI theming - hex color string (e.g., "#4CAF50")
    // Nullable - if null, auto-generated from categoryId in domain model
    @ColumnInfo(name = "color")
    val color: String? = null,

    // Active status - determines if category is visible in menus
    // Categories can be deactivated from dashboard toggle
    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long  // Unix timestamp (for TTL expiration)
)
