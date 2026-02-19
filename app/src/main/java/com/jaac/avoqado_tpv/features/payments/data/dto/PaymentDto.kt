package com.jaac.avoqado_tpv.features.payments.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.payments.domain.models.CardBrand
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentStatus
import com.jaac.avoqado_tpv.features.payments.domain.models.StaffSummary
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant

/**
 * Payment DTO (Data Transfer Object)
 *
 * Maps backend payment response to Android domain model.
 * Matches backend Payment model structure from Prisma.
 */
data class PaymentDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("orderId")
    val orderId: String?,

    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("amount")
    val amount: String,  // Backend sends as string for precision

    @SerializedName("tipAmount")
    val tipAmount: String?,

    @SerializedName("method")
    val method: String,

    @SerializedName("status")
    val status: String?,

    @SerializedName("createdAt")
    val createdAt: String,  // ISO 8601 timestamp

    @SerializedName("processedBy")
    val processedBy: StaffDto?,

    @SerializedName("order")
    val order: OrderSummaryDto?,

    // ⭐ MULTI-MERCHANT REFUND SUPPORT (2025-01-XX)
    // Backend needs to return these fields for refund routing
    @SerializedName("merchantAccountId")
    val merchantAccountId: String? = null, // Backend MerchantAccount CUID

    @SerializedName("blumonSerialNumber")
    val blumonSerialNumber: String? = null, // Terminal serial used for payment

    // 🔐 REFUND STATUS TRACKING
    @SerializedName("refundedAmount")
    val refundedAmount: String? = null, // Amount already refunded (string for precision)

    @SerializedName("isFullyRefunded")
    val isFullyRefunded: Boolean? = null, // True if fully refunded

    // 🎫 BLUMON TRANSACTION REFERENCE (for audit/tracking)
    // This is the original transaction reference from Blumon SDK SaleData
    // NOTE: This is NOT the operationID for CancelIcc! Use blumonOperationNumber instead
    @SerializedName("referenceNumber")
    val referenceNumber: String? = null, // e.g., "195978383755"

    // 💳 BLUMON PROCESSOR DATA (contains blumonOperationNumber)
    // Backend returns this as JSONB, we extract blumonOperationNumber for refunds
    @SerializedName("processorData")
    val processorData: ProcessorDataDto? = null,

    // 💳 CARD BRAND (Prisma enum: VISA, MASTERCARD, AMERICAN_EXPRESS, etc.)
    // Backend returns via `...payment` spread in history endpoint
    @SerializedName("cardBrand")
    val cardBrand: String? = null
)

/**
 * Processor Data DTO
 *
 * Nested JSON object from Payment.processorData field.
 * Contains Blumon-specific data including the critical blumonOperationNumber.
 *
 * ⚠️ CRITICAL: blumonOperationNumber is required for CancelIcc refunds!
 * The referenceNumber (12-digit) is too large for Integer - use operationNumber instead.
 */
data class ProcessorDataDto(
    // 🎫 CRITICAL: This is the operationID for CancelIcc refunds!
    // Format: small integer (e.g., 75656) that fits in Blumon's Integer type
    // This comes from Blumon webhook, NOT from SDK SaleData.reference
    @SerializedName("blumonOperationNumber")
    val blumonOperationNumber: Int? = null,

    // Other fields for reference (not currently used)
    @SerializedName("referenceNumber")
    val referenceNumber: String? = null,

    @SerializedName("blumonMembership")
    val blumonMembership: String? = null,

    @SerializedName("blumonSerialNumber")
    val blumonSerialNumber: String? = null
)

/**
 * Staff DTO
 *
 * Lightweight staff info included in payment response.
 */
data class StaffDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("firstName")
    val firstName: String,

    @SerializedName("lastName")
    val lastName: String
)

/**
 * Order Summary DTO
 *
 * Minimal order info included in payment response.
 */
data class OrderSummaryDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("orderNumber")
    val orderNumber: String,

    @SerializedName("status")
    val status: String,

    @SerializedName("total")
    val total: String,

    @SerializedName("table")
    val table: TableDto?
)

/**
 * Table DTO
 *
 * Minimal table info for payment source display.
 */
data class TableDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String
)

/**
 * Payment History Request Body
 *
 * Filter parameters sent in POST body.
 * Pagination params (pageSize, pageNumber) are sent as query params.
 */
data class PaymentHistoryRequestBody(
    @SerializedName("fromDate")
    val fromDate: String? = null,  // ISO 8601 format

    @SerializedName("toDate")
    val toDate: String? = null,  // ISO 8601 format

    @SerializedName("staffId")
    val staffId: String? = null
)

/**
 * Payment History Response
 *
 * Wrapper for paginated payment list from backend.
 */
data class PaymentHistoryResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<PaymentDto>,

    @SerializedName("meta")
    val meta: PaginationMeta
)

/**
 * Pagination Meta
 *
 * Metadata for pagination (total count, page info).
 */
data class PaginationMeta(
    @SerializedName("total")
    val total: Int,

    @SerializedName("pageSize")
    val pageSize: Int,

    @SerializedName("pageNumber")
    val pageNumber: Int
)

/**
 * Map DTO to Domain Model
 *
 * Converts network response to domain model with proper type safety.
 */
fun PaymentDto.toDomain(): Payment {
    // Parse amounts (backend sends as string for precision)
    val amountDecimal = try {
        BigDecimal(amount)
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse payment amount: $amount")
        BigDecimal.ZERO
    }

    val tipDecimal = try {
        BigDecimal(tipAmount ?: "0")
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse tip amount: $tipAmount")
        BigDecimal.ZERO
    }

    // Parse timestamp
    val timestamp = try {
        Instant.parse(createdAt)
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse timestamp: $createdAt")
        Instant.now()
    }

    // Map payment method
    val paymentMethod = PaymentMethod.fromString(method)

    // Map payment status
    val paymentStatus = PaymentStatus.fromString(status ?: "COMPLETED")

    // Map staff
    val staff = processedBy?.let {
        StaffSummary(
            id = it.id,
            firstName = it.firstName,
            lastName = it.lastName
        )
    }

    // Calculate total
    val total = amountDecimal + tipDecimal

    // Parse refunded amount (if present)
    val refundedDecimal = try {
        refundedAmount?.let { BigDecimal(it) }
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse refunded amount: $refundedAmount")
        null
    }

    // Extract blumonOperationNumber from processorData (CRITICAL for CancelIcc refunds!)
    // This is the operationID that Blumon's backend expects as Integer
    val operationNumber = processorData?.blumonOperationNumber

    return Payment(
        id = id,
        orderId = orderId,
        orderNumber = order?.orderNumber,
        venueId = venueId,
        amount = amountDecimal,
        tipAmount = tipDecimal,
        totalAmount = total,
        method = paymentMethod,
        processedBy = staff,
        createdAt = timestamp,
        status = paymentStatus,
        tableName = order?.table?.name,
        // Multi-merchant refund fields
        merchantAccountId = merchantAccountId,
        blumonSerialNumber = blumonSerialNumber,
        refundedAmount = refundedDecimal,
        isFullyRefunded = isFullyRefunded ?: false,
        // Blumon transaction reference (for audit - NOT for CancelIcc operationID!)
        referenceNumber = referenceNumber,
        // 🎫 CRITICAL: Blumon operationNumber for CancelIcc refunds
        // This is the small integer from Blumon webhook, NOT the SDK's large reference
        blumonOperationNumber = operationNumber,
        // Card brand (Visa, Mastercard, etc.) for display in payment history
        cardBrand = CardBrand.fromString(cardBrand)
    )
}
