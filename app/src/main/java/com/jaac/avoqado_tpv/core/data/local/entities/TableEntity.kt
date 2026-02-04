package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Table cache (floor plan / table service).
 *
 * **Purpose:** Cache tables from backend for offline/slow-network use.
 *
 * **Cache Strategy:**
 * - Cache-first: show cached data instantly
 * - Background refresh: update cache from backend when available
 * - No hard TTL: show "last known" state when offline
 */
@Entity(
    tableName = "tables_cache",
    indices = [
        Index(value = ["venue_id", "table_id"], unique = true),
        Index(value = ["venue_id"]),
        Index(value = ["cached_at"])
    ]
)
data class TableEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "table_id")
    val tableId: String,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "number")
    val number: String,

    @ColumnInfo(name = "capacity")
    val capacity: Int,

    @ColumnInfo(name = "position_x")
    val positionX: Float?,

    @ColumnInfo(name = "position_y")
    val positionY: Float?,

    @ColumnInfo(name = "shape")
    val shape: String,

    @ColumnInfo(name = "rotation")
    val rotation: Int,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "current_order_id")
    val currentOrderId: String?,

    @ColumnInfo(name = "current_order_number")
    val currentOrderNumber: String?,

    @ColumnInfo(name = "current_order_covers")
    val currentOrderCovers: Int?,

    @ColumnInfo(name = "current_order_total")
    val currentOrderTotal: String?,

    @ColumnInfo(name = "current_order_item_count")
    val currentOrderItemCount: Int?,

    @ColumnInfo(name = "current_order_waiter_id")
    val currentOrderWaiterId: String?,

    @ColumnInfo(name = "current_order_waiter_name")
    val currentOrderWaiterName: String?,

    @ColumnInfo(name = "current_order_created_at")
    val currentOrderCreatedAt: String?,

    @ColumnInfo(name = "area_id")
    val areaId: String?,

    @ColumnInfo(name = "area_name")
    val areaName: String?,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long
)
