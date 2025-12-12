package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.Embedded
import androidx.room.Relation

/**
 * DraftOrderWithItems - Room relation class for Order with Items
 *
 * This is a data class used by Room to automatically join DraftOrderEntity
 * with its DraftOrderItemEntity children in a single query.
 *
 * ## Usage with Flow (Single Source of Truth Pattern)
 * ```kotlin
 * // In DraftOrderDao
 * @Transaction
 * @Query("SELECT * FROM draft_orders WHERE id = :orderId")
 * fun observeOrderWithItems(orderId: String): Flow<DraftOrderWithItems?>
 *
 * // In OrderSyncCoordinator
 * fun observeOrder(orderId: String): Flow<Order> {
 *     return draftOrderDao.observeOrderWithItems(orderId)
 *         .filterNotNull()
 *         .map { it.toDomain() }
 * }
 * ```
 *
 * ## Benefits
 * - Single query loads order + items (Room handles JOIN automatically)
 * - Flow emits whenever order OR items change in Room DB
 * - Eliminates race conditions between separate order/items queries
 *
 * @property order The parent DraftOrderEntity
 * @property items List of DraftOrderItemEntity children (FK: order_id)
 */
data class DraftOrderWithItems(
    @Embedded
    val order: DraftOrderEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "order_id"
    )
    val items: List<DraftOrderItemEntity>
)
