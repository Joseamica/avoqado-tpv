package com.jaac.avoqado_tpv.features.payment.data.processor.blumon

import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlumonPostOperationsAdapter @Inject constructor() : PaymentPostOperationsAdapter {
    override val processorType: ProcessorType = ProcessorType.BLUMON

    override suspend fun getTransactionHistory(query: TransactionHistoryQuery): Result<List<UnifiedTransaction>> {
        return Result.failure(UnsupportedOperationException("Historial Blumon no implementado en esta vista unificada"))
    }

    override suspend fun cancelTransaction(
        transaction: UnifiedTransaction,
        latitude: Double,
        longitude: Double,
        isManual: Boolean,
    ): Result<PostOperationResult> {
        return Result.failure(UnsupportedOperationException("Cancelación Blumon no implementada en esta vista unificada"))
    }

    override suspend fun refundTransaction(
        transaction: UnifiedTransaction,
        latitude: Double,
        longitude: Double,
        isManual: Boolean,
    ): Result<PostOperationResult> {
        return Result.failure(UnsupportedOperationException("Devolución Blumon no implementada en esta vista unificada"))
    }

    override suspend fun sendTicketEmail(transaction: UnifiedTransaction, email: String): Result<String> {
        return Result.failure(UnsupportedOperationException("Envío de ticket Blumon no implementado en esta vista unificada"))
    }

    override suspend fun getTicketUrl(transaction: UnifiedTransaction): Result<String> {
        return Result.failure(UnsupportedOperationException("URL de ticket Blumon no implementada en esta vista unificada"))
    }

    override fun printTicket(transaction: UnifiedTransaction): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Impresión Blumon no implementada en esta vista unificada"))
    }
}
