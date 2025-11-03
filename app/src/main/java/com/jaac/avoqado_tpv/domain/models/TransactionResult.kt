package com.jaac.avoqado_tpv.domain.models

import java.math.BigDecimal
import java.util.Date

/**
 * Resultado de una transacción procesada
 *
 * @param transactionId ID único de la transacción
 * @param amount Monto de la transacción
 * @param cardType Tipo de tarjeta utilizada
 * @param cardNumber Últimos 4 dígitos de la tarjeta (masked)
 * @param authorizationCode Código de autorización del banco
 * @param timestamp Fecha y hora de la transacción
 * @param cvmUsed Método de verificación del tarjetahabiente utilizado
 * @param emvData Datos EMV de la transacción
 * @param success Indica si la transacción fue exitosa
 */
data class TransactionResult(
    val transactionId: String,
    val amount: BigDecimal,
    val cardType: CardType,
    val cardNumber: String, // Masked: "****1234"
    val authorizationCode: String,
    val timestamp: Date,
    val cvmUsed: CardholderVerificationMethod,
    val emvData: EmvData? = null,
    val success: Boolean = true
)

/**
 * Tipos de tarjeta soportados
 */
enum class CardType {
    VISA,
    MASTERCARD,
    AMERICAN_EXPRESS,
    DISCOVER,
    JCB,
    UNIONPAY,
    CARNET,
    UNKNOWN
}

/**
 * Métodos de verificación del tarjetahabiente (CVM)
 */
enum class CardholderVerificationMethod {
    NO_CVM,                     // Sin verificación
    OFFLINE_PIN,                // PIN offline (verificado en terminal)
    ONLINE_PIN,                 // PIN online (verificado por emisor)
    SIGNATURE,                  // Firma del tarjetahabiente
    ONLINE_PIN_AND_SIGNATURE,   // PIN online + firma
    CONSUMER_DEVICE             // Verificación vía dispositivo del consumidor
}

/**
 * Datos EMV de la transacción
 */
data class EmvData(
    val applicationId: String,          // AID
    val applicationLabel: String,       // Nombre de la aplicación
    val terminalVerificationResults: String, // TVR
    val transactionStatusInformation: String, // TSI
    val cryptogram: String,              // ARQC/ARPC
    val cryptogramType: CryptogramType
)

/**
 * Tipos de criptograma EMV
 */
enum class CryptogramType {
    ARQC,   // Authorization Request Cryptogram (online)
    TC,     // Transaction Certificate (offline approved)
    AAC     // Application Authentication Cryptogram (declined)
}
