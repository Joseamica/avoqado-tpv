package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.PrintAlignmentRequest
import com.angelpay.angelpaysdk.models.PrintFontSizeRequest
import com.angelpay.angelpaysdk.models.PrintStyleRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemType
import com.angelpay.angelpaysdk.models.PrintTicketRequest
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AngelPaySdkPostOperationsAdapter @Inject constructor() : PaymentPostOperationsAdapter {
    override val processorType: ProcessorType = ProcessorType.ANGELPAY

    override suspend fun getTransactionHistory(query: TransactionHistoryQuery): Result<List<UnifiedTransaction>> {
        return AngelPaySDK.getTransactionHistory(
            startDate = query.startDate,
            endDate = query.endDate,
            reference = query.reference,
            terminal = query.terminal,
        ).map { response ->
            response.items.map { item ->
                UnifiedTransaction(
                    processorType = ProcessorType.ANGELPAY,
                    reference = item.reference ?: "",
                    amount = parseDecimal(item.amount),
                    authorizationCode = item.authorization ?: "",
                    status = item.status ?: "",
                    cardType = item.cardType,
                    last4 = item.last4,
                    cardBin = item.bin ?: "",
                    entryMode = item.entryMode ?: "",
                    date = item.date,
                    time = item.time,
                    operationType = item.operationType,
                    merchantName = item.merchantName,
                    affiliation = item.affiliation,
                    terminal = item.terminal,
                    folio = item.folio ?: "",
                    waiter = item.waiter,
                    tip = item.tip?.let { parseDecimal(it) },
                    issuingBank = item.issuingBank,
                    cardMethod = item.cardMethod,
                    creationDate = item.creationDate ?: item.date ?: "",
                    aid = item.aid,
                    arqc = item.arqc,
                    postOperationType = item.postOperation?.type,
                    postOperationReference = item.postOperation?.reference,
                    postOperationAuthorization = item.postOperation?.authorization,
                    postOperationStatus = item.postOperation?.status,
                )
            }
        }
    }

    override suspend fun cancelTransaction(
        transaction: UnifiedTransaction,
        latitude: Double,
        longitude: Double,
        isManual: Boolean,
    ): Result<PostOperationResult> {
        val validation = validatePostOperationFields(transaction)
        if (validation != null) return Result.failure(IllegalArgumentException(validation))

        return AngelPaySDK.cancelTransaction(
            originalReference = transaction.reference,
            amount = transaction.amount,
            authCode = transaction.authorizationCode,
            entryMode = transaction.entryMode,
            cardBin = transaction.cardBin,
            transactionDate = transaction.creationDate,
            isManual = isManual,
            latitude = latitude,
            longitude = longitude,
        ).map { result ->
            PostOperationResult(
                approved = result.approved,
                status = result.status.name,
                message = result.message ?: result.callResult?.message,
                authorizationCode = result.authCode,
                reference = result.reference,
                errorCode = result.callResult?.code ?: result.respCode,
            )
        }
    }

    override suspend fun refundTransaction(
        transaction: UnifiedTransaction,
        latitude: Double,
        longitude: Double,
        isManual: Boolean,
    ): Result<PostOperationResult> {
        val validation = validatePostOperationFields(transaction)
        if (validation != null) return Result.failure(IllegalArgumentException(validation))

        return AngelPaySDK.refundTransaction(
            originalReference = transaction.reference,
            amount = transaction.amount,
            authCode = transaction.authorizationCode,
            entryMode = transaction.entryMode,
            cardBin = transaction.cardBin,
            transactionDate = transaction.creationDate,
            isManual = isManual,
            latitude = latitude,
            longitude = longitude,
        ).map { result ->
            PostOperationResult(
                approved = result.approved,
                status = result.status.name,
                message = result.message ?: result.callResult?.message,
                authorizationCode = result.authCode,
                reference = result.reference,
                errorCode = result.callResult?.code ?: result.respCode,
            )
        }
    }

    override suspend fun sendTicketEmail(transaction: UnifiedTransaction, email: String): Result<String> {
        if (transaction.reference.isBlank()) {
            return Result.failure(IllegalArgumentException("La transacción no tiene referencia"))
        }
        if (transaction.folio.isBlank()) {
            return Result.failure(IllegalArgumentException("La transacción no tiene folio"))
        }
        if (transaction.authorizationCode.isBlank()) {
            return Result.failure(IllegalArgumentException("La transacción no tiene código de autorización"))
        }

        return AngelPaySDK.sendTicketEmail(
            email = email,
            reference = transaction.reference,
            folio = transaction.folio,
            authorization = transaction.authorizationCode,
            aid = transaction.aid,
            arqc = transaction.arqc,
            cardBin = transaction.cardBin.ifBlank { null },
            tip = transaction.tip,
        )
    }

    override suspend fun getTicketUrl(transaction: UnifiedTransaction): Result<String> {
        if (transaction.reference.isBlank()) {
            return Result.failure(IllegalArgumentException("La transacción no tiene referencia"))
        }
        if (transaction.folio.isBlank()) {
            return Result.failure(IllegalArgumentException("La transacción no tiene folio"))
        }

        return AngelPaySDK.getTicketUrl(
            reference = transaction.reference,
            folio = transaction.folio,
            aid = transaction.aid,
            arqc = transaction.arqc,
            cardBin = transaction.cardBin.ifBlank { null },
            tip = transaction.tip,
        )
    }

    override fun printTicket(transaction: UnifiedTransaction): Result<Unit> {
        return try {
            val centerBold = PrintStyleRequest(
                alignment = PrintAlignmentRequest.CENTER,
                isBold = true,
                fontSize = PrintFontSizeRequest.LARGE,
            )
            val normal = PrintStyleRequest()

            val ticket = PrintTicketRequest(
                header = listOf(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TEXT,
                        text = "AVOQADO TPV",
                        style = centerBold,
                    ),
                    PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER),
                ),
                body = listOf(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Referencia",
                        right = transaction.reference,
                        style = normal,
                    ),
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Monto",
                        right = formatMoney(transaction.amount),
                    ),
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Autorización",
                        right = transaction.authorizationCode.ifBlank { "-" },
                    ),
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Estatus",
                        right = transaction.status.ifBlank { "-" },
                    ),
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Tarjeta",
                        right = listOfNotNull(transaction.cardType, transaction.last4?.let { "****$it" })
                            .joinToString(" ")
                            .ifBlank { "-" },
                    ),
                    PrintTicketItemRequest(type = PrintTicketItemType.SPACER, lines = 3),
                ),
                footer = emptyList(),
            )

            AngelPaySDK.printTicket(ticket)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validatePostOperationFields(transaction: UnifiedTransaction): String? {
        return when {
            transaction.reference.isBlank() -> "La transacción no tiene referencia"
            transaction.authorizationCode.isBlank() -> "La transacción no tiene código de autorización"
            transaction.entryMode.isBlank() -> "La transacción no tiene entry mode"
            transaction.cardBin.isBlank() -> "La transacción no tiene BIN"
            transaction.creationDate.isBlank() -> "La transacción no tiene fecha de operación"
            transaction.amount <= 0.0 -> "La transacción tiene monto inválido"
            else -> null
        }
    }

    private fun parseDecimal(raw: String?): Double {
        val cleaned = raw.orEmpty().trim().replace(",", "")
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun formatMoney(amount: Double): String {
        return "$" + String.format(Locale.US, "%.2f", amount)
    }
}
