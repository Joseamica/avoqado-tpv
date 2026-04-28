package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import android.content.Intent
import com.jaac.avoqado_tpv.BuildConfig
import org.json.JSONObject
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Builds Android Intents for AngelPay app-to-app integration.
 *
 * AngelPay processes payments via an external app on Nexgo N86 terminals.
 * Communication is done through standard Android Intents with JSON extras.
 *
 * **Intent Actions:**
 * - DO_SALE: Process a card sale
 * - SEE_HISTORY: View transaction history
 * - REPORTS: View reports
 *
 * **Package:**
 * - QA: mx.angel_pay_prod.app.qa
 * - Production: mx.angel_pay_prod.app
 *
 * @see <a href="AngelPay Manual de Integracion App to App v1.0">AngelPay docs</a>
 */
class AngelPayIntentBuilder @Inject constructor() {

    companion object {
        // Intent actions
        private const val ACTION_DO_SALE = "mx.angel_pay_prod.app.intent.action.DO_SALE"
        private const val ACTION_SEE_HISTORY = "mx.angel_pay_prod.app.intent.action.SEE_HISTORY"
        private const val ACTION_REPORTS = "mx.angel_pay_prod.app.intent.action.REPORTS"

        // Extra keys
        private const val EXTRA_TRANSACTION_REQUEST = "mx.angel_pay_prod.app.extra.TRANSACTION_REQUEST"
        private const val EXTRA_AUTH = "mx.angel_pay_prod.app.extra.EXTRA_AUTH"
        private const val EXTRA_CUSTOM_SPLASH = "mx.angel_pay_prod.app.extra.CUSTOM_SPLASH"
        private const val EXTRA_VERSION = "mx.angel_pay_prod.app.extra.VERSION"

        // Package names
        private const val PACKAGE_PRODUCTION = "mx.angel_pay_prod.app"
        private const val PACKAGE_QA = "mx.angel_pay_prod.app.qa"

        // Entry activity (explicit intent target, from AngelPay example app)
        private const val ENTRY_ACTIVITY = "mx.angel_pay_prod.app.ExternalEntryActivity"

        // Protocol version
        private const val PROTOCOL_VERSION = 1
    }

    /**
     * Get the AngelPay package name based on current build variant.
     * Sandbox/tutorialEmu → QA package, Production → Production package.
     */
    private fun getPackageName(): String {
        return if (BuildConfig.BLUMON_ENV == "PROD") PACKAGE_PRODUCTION else PACKAGE_QA
    }

    /**
     * Build a DO_SALE Intent for processing a card payment.
     *
     * @param amount Payment amount (in pesos, e.g., BigDecimal("150.00"))
     * @param tip Tip amount (in pesos, e.g., BigDecimal("15.00"))
     * @param credentials AngelPay authentication credentials
     * @param waiter Optional waiter identifier
     * @return Intent ready to be launched via startActivityForResult
     */
    fun buildSaleIntent(
        amount: BigDecimal,
        tip: BigDecimal = BigDecimal.ZERO,
        credentials: AngelPayCredentials,
        waiter: String? = null,
        integratorReference: String? = null,
    ): Intent {
        // Workaround: AngelPay QA merchant returns C208 "Propina no soportada"
        // when tip > 0. Send total as subtotal with tip=0 to AngelPay.
        // Our backend records the real tip/subtotal split correctly.
        // TODO: Revert when AngelPay enables tip for our merchant
        val totalForProcessor = amount.add(tip)
        val transactionRequest = JSONObject().apply {
            put("operationType", "SALE")
            put("subtotal", toCentavos(totalForProcessor))
            // tip intentionally omitted — sent as part of subtotal
            if (waiter != null) {
                put("waiter", waiter)
            }
            // installments: null/0 = no MSI
            if (!integratorReference.isNullOrBlank()) {
                put("integratorReference", integratorReference)
            }
        }

        val authExternal = buildAuthJson(credentials)

        // Use explicit intent with setClassName (matching AngelPay example app)
        // setPackage() alone creates an implicit intent which may route differently
        val intent = Intent(ACTION_DO_SALE).apply {
            setClassName(getPackageName(), ENTRY_ACTIVITY)
            putExtra(EXTRA_TRANSACTION_REQUEST, transactionRequest.toString())
            putExtra(EXTRA_AUTH, authExternal.toString())
            putExtra(EXTRA_VERSION, PROTOCOL_VERSION)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        Timber.d("🔶 [AngelPay] Built SALE intent | subtotal=$amount, tip=$tip, totalToProcessor=$totalForProcessor, package=${getPackageName()}, integratorReference=$integratorReference, tx=$transactionRequest")
        return intent
    }

    /**
     * Build a SEE_HISTORY Intent for viewing transaction history in AngelPay.
     *
     * @param credentials AngelPay authentication credentials
     * @return Intent ready to be launched
     */
    fun buildHistoryIntent(credentials: AngelPayCredentials): Intent {
        val authExternal = buildAuthJson(credentials)

        return Intent(ACTION_SEE_HISTORY).apply {
            setClassName(getPackageName(), ENTRY_ACTIVITY)
            putExtra(EXTRA_AUTH, authExternal.toString())
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }

    /**
     * Build a REPORTS Intent for viewing reports in AngelPay.
     *
     * @param credentials AngelPay authentication credentials
     * @return Intent ready to be launched
     */
    fun buildReportsIntent(credentials: AngelPayCredentials): Intent {
        val authExternal = buildAuthJson(credentials)

        return Intent(ACTION_REPORTS).apply {
            setClassName(getPackageName(), ENTRY_ACTIVITY)
            putExtra(EXTRA_AUTH, authExternal.toString())
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }

    /**
     * Build the AuthExternal JSON object.
     */
    private fun buildAuthJson(credentials: AngelPayCredentials): JSONObject {
        return JSONObject().apply {
            put("email", credentials.email)
            put("password", credentials.password)
            put("affiliation", credentials.affiliation)
            put("commerceToken", credentials.commerceToken)
        }
    }

    /**
     * Convert pesos to centavos (Long).
     * AngelPay expects amounts in centavos: $1,500.00 → 150000L
     */
    private fun toCentavos(amount: BigDecimal): Long {
        return amount.multiply(BigDecimal(100)).toLong()
    }
}
