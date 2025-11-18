package com.jaac.avoqado_tpv.features.ordering.domain

import java.math.BigDecimal

/**
 * Table Status
 *
 * Represents the current state of a table in the restaurant.
 * Matches backend Prisma TableStatus enum.
 */
enum class TableStatus {
    AVAILABLE,   // Table is free and ready for customers
    OCCUPIED,    // Table has active order
    RESERVED     // Table is reserved for future booking
}

/**
 * Table Shape
 *
 * Visual shape of the table for floor plan rendering.
 */
enum class TableShape {
    ROUND,
    SQUARE,
    RECTANGLE
}

/**
 * Table Position
 *
 * X/Y coordinates for floor plan positioning.
 * Null values mean table has no assigned position (list view only).
 */
data class TablePosition(
    val x: Float?,
    val y: Float?
)

/**
 * Waiter Info
 *
 * Staff member serving the table.
 */
data class WaiterInfo(
    val id: String,
    val name: String
)

/**
 * Current Order Info
 *
 * Summary of the active order on a table.
 */
data class CurrentOrderInfo(
    val id: String,
    val orderNumber: String,
    val covers: Int?,
    val total: BigDecimal,
    val itemCount: Int,
    val waiter: WaiterInfo?,
    val createdAt: String
)

/**
 * Table
 *
 * Represents a physical table in the restaurant.
 * Includes current order information for real-time floor plan updates.
 *
 * Pattern: Similar to Square POS table management
 */
data class Table(
    val id: String,
    val number: String,
    val capacity: Int,
    val positionX: Float?,
    val positionY: Float?,
    val shape: TableShape,
    val rotation: Int,
    val status: TableStatus,
    val currentOrder: CurrentOrderInfo?,

    // Area relationship (for filtering in floor plan)
    val areaId: String? = null,
    val areaName: String? = null
) {
    /**
     * Is table available for new customers?
     */
    val isAvailable: Boolean
        get() = status == TableStatus.AVAILABLE

    /**
     * Is table currently occupied with active order?
     */
    val isOccupied: Boolean
        get() = status == TableStatus.OCCUPIED

    /**
     * Is table reserved for future booking?
     */
    val isReserved: Boolean
        get() = status == TableStatus.RESERVED

    /**
     * Display name for UI (e.g., "Mesa 1")
     */
    val displayName: String
        get() = "Mesa $number"

    /**
     * Has assigned position for floor plan?
     */
    val hasPosition: Boolean
        get() = positionX != null && positionY != null
}
