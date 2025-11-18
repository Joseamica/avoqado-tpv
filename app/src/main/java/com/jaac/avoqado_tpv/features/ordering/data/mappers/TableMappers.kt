package com.jaac.avoqado_tpv.features.ordering.data.mappers

import com.jaac.avoqado_tpv.features.ordering.data.dto.CurrentOrderInfoDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.TableDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.WaiterInfoDto
import com.jaac.avoqado_tpv.features.ordering.domain.CurrentOrderInfo
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableShape
import com.jaac.avoqado_tpv.features.ordering.domain.TableStatus
import com.jaac.avoqado_tpv.features.ordering.domain.WaiterInfo
import java.math.BigDecimal

/**
 * Map TableDto to domain Table model
 */
fun TableDto.toDomain(): Table {
    return Table(
        id = id,
        number = number,
        capacity = capacity,
        positionX = positionX?.toFloat(),
        positionY = positionY?.toFloat(),
        shape = shape.toTableShape(),
        rotation = rotation,
        status = status.toTableStatus(),
        currentOrder = currentOrder?.toDomain(),
        areaId = areaId,
        areaName = areaName
    )
}

/**
 * Map string to TableStatus enum
 */
fun String.toTableStatus(): TableStatus {
    return when (this.uppercase()) {
        "AVAILABLE" -> TableStatus.AVAILABLE
        "OCCUPIED" -> TableStatus.OCCUPIED
        "RESERVED" -> TableStatus.RESERVED
        else -> TableStatus.AVAILABLE  // Default fallback
    }
}

/**
 * Map string to TableShape enum
 */
fun String.toTableShape(): TableShape {
    return when (this.uppercase()) {
        "ROUND" -> TableShape.ROUND
        "SQUARE" -> TableShape.SQUARE
        "RECTANGLE" -> TableShape.RECTANGLE
        else -> TableShape.ROUND  // Default fallback
    }
}

/**
 * Map CurrentOrderInfoDto to domain CurrentOrderInfo
 */
fun CurrentOrderInfoDto.toDomain(): CurrentOrderInfo {
    return CurrentOrderInfo(
        id = id,
        orderNumber = orderNumber,
        covers = covers,
        total = BigDecimal(total.toString()),
        itemCount = itemCount,
        waiter = waiter?.toDomain(),
        createdAt = createdAt
    )
}

/**
 * Map WaiterInfoDto to domain WaiterInfo
 */
fun WaiterInfoDto.toDomain(): WaiterInfo {
    return WaiterInfo(
        id = id,
        name = name
    )
}
