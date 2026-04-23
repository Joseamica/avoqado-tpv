package com.jaac.avoqado_tpv.features.payment.domain.processor

/**
 * Unified post-operation contract across processors.
 *
 * This keeps the UI processor-agnostic while each gateway handles provider-specific fields.
 */
interface PaymentPostOperationsAdapter {
    val processorType: ProcessorType

    suspend fun getTransactionHistory(query: TransactionHistoryQuery): Result<List<UnifiedTransaction>>

    suspend fun cancelTransaction(
        transaction: UnifiedTransaction,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        isManual: Boolean = false,
    ): Result<PostOperationResult>

    suspend fun refundTransaction(
        transaction: UnifiedTransaction,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        isManual: Boolean = false,
    ): Result<PostOperationResult>

    suspend fun sendTicketEmail(
        transaction: UnifiedTransaction,
        email: String,
    ): Result<String>

    suspend fun getTicketUrl(transaction: UnifiedTransaction): Result<String>

    fun printTicket(transaction: UnifiedTransaction): Result<Unit>
}

data class TransactionHistoryQuery(
    val startDate: String,
    val endDate: String,
    val reference: String? = null,
    val terminal: String? = null,
)

data class UnifiedTransaction(
    val processorType: ProcessorType,
    val reference: String,
    val amount: Double,
    val authorizationCode: String,
    val status: String,
    val cardType: String?,
    val last4: String?,
    val cardBin: String,
    val entryMode: String,
    val date: String?,
    val time: String?,
    val operationType: String?,
    val merchantName: String?,
    val affiliation: String?,
    val terminal: String?,
    val folio: String,
    val waiter: String?,
    val tip: Double?,
    val issuingBank: String?,
    val cardMethod: String?,
    val creationDate: String,
    val aid: String?,
    val arqc: String?,
    val postOperationType: String?,
    val postOperationReference: String?,
    val postOperationAuthorization: String?,
    val postOperationStatus: String?,
)

data class PostOperationResult(
    val approved: Boolean,
    val status: String?,
    val message: String?,
    val authorizationCode: String?,
    val reference: String?,
    val errorCode: String?,
)
