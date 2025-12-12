package com.jaac.avoqado_tpv.features.ordering.data.mappers

import com.jaac.avoqado_tpv.features.ordering.data.dto.CustomerDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.CustomerGroupDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderCustomerDto
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerGroup
import com.jaac.avoqado_tpv.features.ordering.domain.OrderCustomer
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Customer DTO to Domain Mappers
 *
 * Converts between backend DTOs and domain models.
 * Handles null safety and type conversions (Double → BigDecimal).
 */

// ==========================================
// DTO → DOMAIN
// ==========================================

/**
 * Convert CustomerDto to Customer domain model
 */
fun CustomerDto.toCustomer(): Customer {
    return Customer(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        loyaltyPoints = loyaltyPoints,
        totalVisits = totalVisits,
        totalSpent = BigDecimal.valueOf(totalSpent),
        customerGroup = customerGroup?.toCustomerGroup()
    )
}

/**
 * Convert CustomerGroupDto to CustomerGroup domain model
 */
fun CustomerGroupDto.toCustomerGroup(): CustomerGroup {
    return CustomerGroup(
        id = id,
        name = name,
        color = color
    )
}

/**
 * Convert list of CustomerDto to list of Customer domain models
 */
fun List<CustomerDto>.toCustomers(): List<Customer> {
    return map { it.toCustomer() }
}

// ==========================================
// DOMAIN → DTO (for requests)
// ==========================================

/**
 * Convert Customer to CustomerDto
 * Used when sending customer data to backend.
 */
fun Customer.toDto(): CustomerDto {
    return CustomerDto(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        loyaltyPoints = loyaltyPoints,
        totalVisits = totalVisits,
        totalSpent = totalSpent.toDouble(),
        customerGroup = customerGroup?.toDto()
    )
}

/**
 * Convert CustomerGroup to CustomerGroupDto
 */
fun CustomerGroup.toDto(): CustomerGroupDto {
    return CustomerGroupDto(
        id = id,
        name = name,
        color = color
    )
}

// ==========================================
// ORDER-CUSTOMER MAPPERS
// ==========================================

/**
 * Convert OrderCustomerDto to OrderCustomer domain model
 * Parses ISO datetime string to LocalDateTime
 */
fun OrderCustomerDto.toOrderCustomer(): OrderCustomer {
    return OrderCustomer(
        id = id,
        orderId = orderId,
        customerId = customerId,
        customer = customer.toCustomer(),
        isPrimary = isPrimary,
        addedAt = try {
            LocalDateTime.parse(addedAt, DateTimeFormatter.ISO_DATE_TIME)
        } catch (e: Exception) {
            LocalDateTime.now()
        }
    )
}

/**
 * Convert list of OrderCustomerDto to list of OrderCustomer domain models
 */
fun List<OrderCustomerDto>.toOrderCustomers(): List<OrderCustomer> {
    return map { it.toOrderCustomer() }
}
