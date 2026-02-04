package com.jaac.avoqado_tpv.core.data.local.mappers

import com.jaac.avoqado_tpv.core.data.local.entities.FloorElementEntity
import com.jaac.avoqado_tpv.core.data.local.entities.TableEntity
import com.jaac.avoqado_tpv.features.ordering.domain.CurrentOrderInfo
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElement
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementType
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableShape
import com.jaac.avoqado_tpv.features.ordering.domain.TableStatus
import com.jaac.avoqado_tpv.features.ordering.domain.WaiterInfo
import java.math.BigDecimal

/**
 * Cache mappers for Tables and Floor Elements.
 */

fun List<TableEntity>.toTableDomain(): List<Table> {
    return map { entity ->
        val currentOrder = if (entity.currentOrderId != null) {
            CurrentOrderInfo(
                id = entity.currentOrderId,
                orderNumber = entity.currentOrderNumber ?: "",
                covers = entity.currentOrderCovers,
                total = entity.currentOrderTotal?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                itemCount = entity.currentOrderItemCount ?: 0,
                waiter = if (entity.currentOrderWaiterId != null || entity.currentOrderWaiterName != null) {
                    WaiterInfo(
                        id = entity.currentOrderWaiterId ?: "",
                        name = entity.currentOrderWaiterName ?: ""
                    )
                } else {
                    null
                },
                createdAt = entity.currentOrderCreatedAt ?: ""
            )
        } else {
            null
        }

        Table(
            id = entity.tableId,
            number = entity.number,
            capacity = entity.capacity,
            positionX = entity.positionX,
            positionY = entity.positionY,
            shape = TableShape.valueOf(entity.shape),
            rotation = entity.rotation,
            status = TableStatus.valueOf(entity.status),
            currentOrder = currentOrder,
            areaId = entity.areaId,
            areaName = entity.areaName
        )
    }
}

fun List<Table>.toTableEntities(venueId: String, cachedAt: Long): List<TableEntity> {
    return map { table ->
        val currentOrder = table.currentOrder
        TableEntity(
            tableId = table.id,
            venueId = venueId,
            number = table.number,
            capacity = table.capacity,
            positionX = table.positionX,
            positionY = table.positionY,
            shape = table.shape.name,
            rotation = table.rotation,
            status = table.status.name,
            currentOrderId = currentOrder?.id,
            currentOrderNumber = currentOrder?.orderNumber,
            currentOrderCovers = currentOrder?.covers,
            currentOrderTotal = currentOrder?.total?.toString(),
            currentOrderItemCount = currentOrder?.itemCount,
            currentOrderWaiterId = currentOrder?.waiter?.id,
            currentOrderWaiterName = currentOrder?.waiter?.name,
            currentOrderCreatedAt = currentOrder?.createdAt,
            areaId = table.areaId,
            areaName = table.areaName,
            cachedAt = cachedAt
        )
    }
}

fun List<FloorElementEntity>.toFloorElementDomain(): List<FloorElement> {
    return map { entity ->
        FloorElement(
            id = entity.elementId,
            type = FloorElementType.valueOf(entity.type),
            positionX = entity.positionX,
            positionY = entity.positionY,
            width = entity.width,
            height = entity.height,
            rotation = entity.rotation,
            endX = entity.endX,
            endY = entity.endY,
            label = entity.label,
            color = entity.color,
            areaId = entity.areaId
        )
    }
}

fun List<FloorElement>.toFloorElementEntities(venueId: String, cachedAt: Long): List<FloorElementEntity> {
    return map { element ->
        FloorElementEntity(
            elementId = element.id,
            venueId = venueId,
            type = element.type.name,
            positionX = element.positionX,
            positionY = element.positionY,
            width = element.width,
            height = element.height,
            rotation = element.rotation,
            endX = element.endX,
            endY = element.endY,
            label = element.label,
            color = element.color,
            areaId = element.areaId,
            cachedAt = cachedAt
        )
    }
}
