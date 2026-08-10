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
    /**
     * Arma el JSON de la venta que viaja dentro del Intent.
     *
     * 🔴 **El monto que sale de aquí es el TOTAL A COBRAR (venta + propina). NO LO CAMBIES
     * A `amount` PELÓN.** Avoqado guarda el desglose venta/propina de su lado; al procesador
     * siempre le va el total. Es el mismo contrato que Blumon/PAX (`calculateTotal(amount, tip)`)
     * y que el fallback del SDK.
     *
     * La propina va **sumada dentro de `subtotal`** y no como campo aparte: para AngelPay la
     * propina es un DESGLOSE que se RESTA del monto, no un extra que se suma. Mandar aquí la
     * venta sin la propina haría que el cliente PAGUE DE MENOS — ese error exacto, en el
     * camino del SDK, costó 11 ventas cobradas de menos por $1,225.65 en Rest MX el
     * 2026-08-09/10.
     *
     * Vive separado de `buildSaleIntent` para poder probarlo sin Android de por medio.
     * Guardado por `AngelPayIntentBuilderTest`.
     */
    internal fun buildSaleTransactionJson(
        amount: BigDecimal,
        tip: BigDecimal = BigDecimal.ZERO,
        waiter: String? = null,
        integratorReference: String? = null,
    ): String {
        val totalForProcessor = amount.add(tip)
        val fields = linkedMapOf<String, Any>(
            "operationType" to "SALE",
            "subtotal" to toCentavos(totalForProcessor),
        )
        // La propina NO se manda por separado — ya va dentro de `subtotal`.
        if (waiter != null) {
            fields["waiter"] = waiter
        }
        // installments: null/0 = no MSI
        if (!integratorReference.isNullOrBlank()) {
            fields["integratorReference"] = integratorReference
        }
        return JSONObject(fields).toString()
    }

    fun buildSaleIntent(
        amount: BigDecimal,
        tip: BigDecimal = BigDecimal.ZERO,
        credentials: AngelPayCredentials,
        waiter: String? = null,
        integratorReference: String? = null,
    ): Intent {
        val totalForProcessor = amount.add(tip)
        val transactionRequest = buildSaleTransactionJson(
            amount = amount,
            tip = tip,
            waiter = waiter,
            integratorReference = integratorReference,
        )

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
