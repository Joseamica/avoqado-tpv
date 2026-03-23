package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import android.app.Activity
import android.content.Intent
import org.json.JSONObject
import timber.log.Timber

/**
 * Parses the result from AngelPay's onActivityResult callback.
 *
 * AngelPay returns 2 JSON string extras via registerForActivityResult:
 * - EXTRA_TRANSACTION_RESULT → TransactionResult (approved, authCode, reference, amount, message, code)
 * - EXTRA_CALL_RESULT → CallResult (code, status, category, message)
 *
 * **Result logic (from manual p.18-19 + example app):**
 * - RESULT_OK + tx.approved=true → Payment approved
 * - RESULT_OK + tx.approved=false → Payment declined (tx has error code/message)
 * - !RESULT_OK + tx=null && call=null → User cancelled (back button, U100/U101/U102)
 * - !RESULT_OK + call!=null → Error with details in CallResult
 *
 * @see <a href="AngelPay Manual de Integracion v1.2, p.18">Response spec</a>
 */
class AngelPayResultParser {

    companion object {
        // Extra keys for transaction result (from AngelPayResults object in example app)
        const val EXTRA_TRANSACTION_RESULT = "mx.angel_pay_prod.app.extra.RESULT_TRANSACTION"
        const val EXTRA_CALL_RESULT = "mx.angel_pay_prod.app.extra.RESULT_CALL"
    }

    /**
     * Parse the AngelPay activity result.
     *
     * @param resultCode The result code from onActivityResult
     * @param data The Intent data from onActivityResult (may be null)
     * @return Parsed AngelPayResult
     */
    fun parse(resultCode: Int, data: Intent?): AngelPayResult {
        Timber.d("🔶 [AngelPay] Parsing result | resultCode=$resultCode, hasData=${data != null}")

        val txJson = data?.getStringExtra(EXTRA_TRANSACTION_RESULT)
        val callJson = data?.getStringExtra(EXTRA_CALL_RESULT)

        Timber.d("🔶 [AngelPay] Raw extras | txJson=$txJson | callJson=$callJson")

        val tx = txJson?.let { parseTransactionResult(it) }
        val call = callJson?.let { parseCallResult(it) }

        // Logic from AngelPay example app (SaleScreen.kt)
        return when {
            // Approved: RESULT_OK + tx.approved == true
            resultCode == Activity.RESULT_OK && tx?.approved == true -> {
                Timber.i("🔶 [AngelPay] Transaction APPROVED | auth=${tx.authCode}, ref=${tx.reference}, amount=${tx.amount}")
                AngelPayResult.Success(
                    authorizationCode = tx.authCode ?: "",
                    referenceNumber = tx.reference ?: "",
                    amountCentavos = tx.amount,
                    message = tx.message,
                    transactionCode = tx.code,
                    callResult = call,
                )
            }

            // Declined: RESULT_OK but tx.approved == false, or has tx with error
            tx != null && !tx.approved -> {
                val errorMsg = tx.message ?: call?.message ?: "Transaccion declinada"
                val errorCode = tx.code ?: call?.code ?: ""
                Timber.w("🔶 [AngelPay] Transaction DECLINED | code=$errorCode, message=$errorMsg")
                AngelPayResult.Failure(
                    message = errorMsg,
                    code = errorCode,
                    category = call?.category,
                )
            }

            // Cancelled: no data at all (user pressed back)
            tx == null && call == null -> {
                Timber.w("🔶 [AngelPay] Transaction CANCELLED (no tx, no call)")
                AngelPayResult.Cancelled
            }

            // Error with call details
            call != null -> {
                val errorMsg = call.message ?: "Error en AngelPay"
                val errorCode = call.code ?: ""
                Timber.e("🔶 [AngelPay] Transaction ERROR | code=$errorCode, status=${call.status}, category=${call.category}, message=$errorMsg")
                AngelPayResult.Failure(
                    message = errorMsg,
                    code = errorCode,
                    category = call.category,
                )
            }

            // Fallback
            else -> {
                Timber.e("🔶 [AngelPay] Unexpected result state | resultCode=$resultCode")
                AngelPayResult.Failure(
                    message = "Resultado inesperado de AngelPay",
                    code = "UNKNOWN",
                    category = null,
                )
            }
        }
    }

    private fun parseTransactionResult(json: String): TransactionResultData? {
        return try {
            val obj = JSONObject(json)
            TransactionResultData(
                approved = obj.optBoolean("approved", false),
                authCode = obj.optString("authCode", null),
                reference = obj.optString("reference", null),
                amount = obj.optLong("amount", 0),
                message = obj.optString("message", null),
                code = obj.optString("code", null),
            )
        } catch (e: Exception) {
            Timber.e(e, "🔶 [AngelPay] Failed to parse TransactionResult JSON: $json")
            null
        }
    }

    private fun parseCallResult(json: String): CallResultData? {
        return try {
            val obj = JSONObject(json)
            CallResultData(
                code = obj.optString("code", null),
                status = obj.optString("status", null),
                category = obj.optString("category", null),
                message = obj.optString("message", null),
            )
        } catch (e: Exception) {
            Timber.e(e, "🔶 [AngelPay] Failed to parse CallResult JSON: $json")
            null
        }
    }
}

/**
 * Internal data class matching AngelPay's TransactionResult.
 * Fields from manual v1.2 p.18.
 */
data class TransactionResultData(
    val approved: Boolean,
    val authCode: String?,
    val reference: String?,
    val amount: Long,       // Amount in centavos
    val message: String?,
    val code: String?,      // Response code (e.g., "S000" for approved)
)

/**
 * Internal data class matching AngelPay's CallResult.
 * Fields from manual v1.2 p.18 + response catalog p.25-28.
 */
data class CallResultData(
    val code: String?,      // e.g., "S000", "U100", "E600"
    val status: String?,    // "OK", "ERROR", "CANCELLED"
    val category: String?,  // "SUCCESS", "AUTH", "USER", "CLIENT", "DEVICE", "EMV"
    val message: String?,   // Human-readable message
)

/**
 * Sealed class representing the result of an AngelPay transaction.
 */
sealed class AngelPayResult {
    /**
     * Payment approved by AngelPay.
     *
     * @param authorizationCode Authorization code from processor (e.g., "502511")
     * @param referenceNumber Reference number from processor
     * @param amountCentavos Amount in centavos as confirmed by AngelPay
     * @param message Human-readable result message
     * @param transactionCode AngelPay response code (e.g., "S000")
     * @param callResult Raw CallResult for debugging
     */
    data class Success(
        val authorizationCode: String,
        val referenceNumber: String,
        val amountCentavos: Long,
        val message: String?,
        val transactionCode: String?,
        val callResult: CallResultData?,
    ) : AngelPayResult()

    /**
     * Payment declined or error.
     *
     * @param message Human-readable error message
     * @param code AngelPay error code (see catalog p.25-28)
     * @param category Error category: SUCCESS, AUTH, USER, CLIENT, DEVICE, EMV
     */
    data class Failure(
        val message: String,
        val code: String,
        val category: String?,
    ) : AngelPayResult()

    data object Cancelled : AngelPayResult()
}
