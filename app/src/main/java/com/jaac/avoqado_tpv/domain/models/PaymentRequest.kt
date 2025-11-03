package com.jaac.avoqado_tpv.domain.models

import java.math.BigDecimal

/**
 * Modelo de dominio que representa una solicitud de pago
 *
 * @param amount Monto principal de la transacción
 * @param otherAmount Monto adicional (propina, etc.)
 * @param transactionType Tipo de transacción (venta, devolución, etc.)
 * @param country País de la transacción
 */
data class PaymentRequest(
    val amount: BigDecimal,
    val otherAmount: BigDecimal = BigDecimal.ZERO,
    val transactionType: TransactionType = TransactionType.SALE,
    val country: Country = Country.MEXICO
) {
    /**
     * Convierte el monto a String en el formato requerido por el SDK (centavos)
     * Ejemplo: 150.50 -> "15050"
     */
    fun getAmountForSDK(): String {
        return (amount.multiply(BigDecimal(100))).toInt().toString()
    }

    /**
     * Convierte el monto adicional a String en el formato requerido por el SDK
     */
    fun getOtherAmountForSDK(): String {
        return (otherAmount.multiply(BigDecimal(100))).toInt().toString()
    }

    /**
     * Valida que el monto sea mayor a cero
     */
    fun isValid(): Boolean {
        return amount > BigDecimal.ZERO
    }
}

/**
 * Tipos de transacción soportados
 */
enum class TransactionType {
    SALE,           // Venta
    REFUND,         // Devolución
    VOID,           // Anulación
    PRE_AUTH,       // Pre-autorización
    COMPLETION      // Completar pre-autorización
}

/**
 * Países soportados
 */
enum class Country {
    MEXICO,
    USA,
    COLOMBIA,
    PERU
}
