package com.jaac.avoqado_tpv.features.shift.data.dto

/** Contrato seguro de S3. No contiene processorData ni interpreta emisión como registro financiero. */
data class CryptoOperationResponse(val success: Boolean, val data: CryptoOperationResultDto? = null)

data class CryptoOperationResultDto(
    val paymentId: String? = null,
    val orderId: String? = null,
    val requestId: String? = null,
    val paymentUrl: String? = null,
    val expiresAt: String? = null,
    val expiresInSeconds: Int? = null,
    val cryptoSymbol: String? = null,
    val cryptoAddress: String? = null,
    val shiftId: String? = null,
    val amount: Int? = null,
    val tipAmount: Int? = null,
    val operation: CryptoOperationDto? = null,
)

data class CryptoOperationDto(
    val idempotencyKey: String? = null,
    val emissionStatus: String? = null,
    val financialStatus: String? = null,
    val financialRecorded: Boolean? = null,
    val providerStatus: String? = null,
    val requiresReview: Boolean? = null,
    val reviewOwner: String? = null,
)
