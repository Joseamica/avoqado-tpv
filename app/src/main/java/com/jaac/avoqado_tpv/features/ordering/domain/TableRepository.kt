package com.jaac.avoqado_tpv.features.ordering.domain

import com.jaac.avoqado_tpv.core.domain.models.Result

/**
 * Table Repository Interface
 *
 * Defines operations for table management in the ordering system.
 * Follows Clean Architecture - domain layer defines the contract.
 *
 * Pattern: Similar to Square POS table management
 */
interface TableRepository {
    /**
     * Get all tables for a venue with their current status.
     *
     * Returns table layout data with real-time order information.
     *
     * @param venueId ID of the venue
     * @return Result with list of tables or error
     */
    suspend fun getTables(venueId: String): Result<List<Table>>

    /**
     * Create a new table.
     *
     * @param venueId ID of the venue
     * @param number Table number
     * @param capacity Table capacity (number of people)
     * @param shape Table shape (ROUND, SQUARE, RECTANGLE)
     * @param rotation Rotation angle in degrees
     * @param positionX X coordinate (0.0 - 1.0), defaults to center if null
     * @param positionY Y coordinate (0.0 - 1.0), defaults to center if null
     * @param areaId ID of the area (optional)
     * @return Result with created table or error
     */
    suspend fun createTable(
        venueId: String,
        number: String,
        capacity: Int,
        shape: TableShape,
        rotation: Int = 0,
        positionX: Float? = null,
        positionY: Float? = null,
        areaId: String? = null
    ): Result<Table>

    /**
     * Assign a table to start a new order or return existing order.
     *
     * **Behavior:**
     * - If table has existing order → Returns that order (isNewOrder: false)
     * - If table is free → Creates new order and marks table OCCUPIED (isNewOrder: true)
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to assign
     * @param staffId ID of the staff member serving
     * @param covers Number of people at the table
     * @return Result with order ID and flag indicating if it's a new order
     */
    suspend fun assignTable(
        venueId: String,
        tableId: String,
        staffId: String,
        covers: Int
    ): Result<AssignTableResult>

    /**
     * Update table position on floor plan.
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to update
     * @param positionX New X coordinate (0.0 - 1.0)
     * @param positionY New Y coordinate (0.0 - 1.0)
     * @return Result indicating success or failure
     */
    suspend fun updateTablePosition(
        venueId: String,
        tableId: String,
        positionX: Float,
        positionY: Float
    ): Result<Unit>

    /**
     * Update table properties (number, capacity, shape, rotation, areaId).
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to update
     * @param number New table number (optional)
     * @param capacity New capacity (optional)
     * @param shape New shape (optional)
     * @param rotation New rotation angle (optional)
     * @param areaId New area ID (optional)
     * @return Result with updated table or error
     */
    suspend fun updateTable(
        venueId: String,
        tableId: String,
        number: String? = null,
        capacity: Int? = null,
        shape: TableShape? = null,
        rotation: Int? = null,
        areaId: String? = null
    ): Result<Table>

    /**
     * Delete a table (soft delete).
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteTable(
        venueId: String,
        tableId: String
    ): Result<Unit>

    /**
     * Clear table after payment completion.
     *
     * **Behavior:**
     * - Verifies current order is paid
     * - Sets table status to AVAILABLE
     * - Removes currentOrderId link
     * - Emits Socket.IO event for real-time updates
     *
     * **Use Case:** After completing payment for a table order, clear the table
     * so it becomes available for new customers (Square/Toast pattern).
     *
     * @param venueId ID of the venue
     * @param tableId ID of the table to clear
     * @return Result indicating success or failure
     */
    suspend fun clearTable(
        venueId: String,
        tableId: String
    ): Result<Unit>
}

/**
 * Assign Table Result
 *
 * Result of assigning a table.
 * Contains the order ID and a flag indicating if it's a new order.
 */
data class AssignTableResult(
    val orderId: String,
    val orderNumber: String,
    val isNewOrder: Boolean
)
