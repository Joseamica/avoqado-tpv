package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Product cache.
 *
 * **Purpose:** Cache products from backend for instant offline access.
 *
 * **Cache Strategy (Toast POS Pattern):**
 * - Network-first: Try API, cache response, serve cached on offline
 * - TTL: 24 hours (stale data auto-expires)
 * - Invalidation: Clear on venue switch or explicit refresh
 * - Upsert: REPLACE on conflict (product_id is unique per venue)
 *
 * **Performance:**
 * - Indexed by (venue_id, category_id) for fast category filtering
 * - Indexed by (venue_id, available) for fast available product queries
 * - Indexed by cached_at for TTL-based cleanup
 *
 * **Data Types:**
 * - BigDecimal stored as TEXT (Room limitation)
 * - ModifierGroups stored as JSON TEXT (TypeConverter)
 * - Timestamps stored as INTEGER (Unix milliseconds)
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["venue_id", "product_id"], unique = true),  // Unique per venue
        Index(value = ["venue_id", "category_id"]),  // Fast category queries
        Index(value = ["venue_id", "available"]),  // Fast available products
        Index(value = ["cached_at"])  // TTL cleanup
    ]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "product_id")
    val productId: String,  // Backend product ID

    @ColumnInfo(name = "venue_id")
    val venueId: String,  // Tenant isolation

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "sku")
    val sku: String,

    @ColumnInfo(name = "price")
    val price: String,  // BigDecimal as TEXT

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "category_name")
    val categoryName: String,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "emoji")
    val emoji: String,

    @ColumnInfo(name = "image_url")
    val imageUrl: String?,

    @ColumnInfo(name = "available")
    val available: Boolean,

    @ColumnInfo(name = "display_order")
    val displayOrder: Int,

    @ColumnInfo(name = "track_inventory")
    val trackInventory: Boolean,

    @ColumnInfo(name = "inventory_method")
    val inventoryMethod: String?,  // QUANTITY | RECIPE | null

    @ColumnInfo(name = "available_quantity")
    val availableQuantity: Int?,

    @ColumnInfo(name = "modifier_groups_json")
    val modifierGroupsJson: String,  // JSON serialized List<ModifierGroup>

    // Category color for visual distinction (Square/Toast pattern)
    // Nullable - if null, auto-generated from categoryId in domain model
    @ColumnInfo(name = "category_color")
    val categoryColor: String? = null,

    // Category active status from dashboard toggle
    // Used to filter inactive categories when extracting from products
    @ColumnInfo(name = "category_is_active")
    val categoryIsActive: Boolean = true,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long  // Unix timestamp (for TTL expiration)
)
