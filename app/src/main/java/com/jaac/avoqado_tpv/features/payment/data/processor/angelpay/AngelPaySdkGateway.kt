package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import android.content.Context
import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.PaymentRequest
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AngelPaySdkGateway @Inject constructor() {

    fun isInitialized(): Boolean = AngelPaySDK.isInitialized()

    fun isAuthenticated(): Boolean = AngelPaySDK.isAuthenticated()

    fun ensureInitialized(context: Context, env: String): Result<Unit> {
        return runCatching {
            if (!AngelPaySDK.isInitialized()) {
                AngelPaySDK.initialize(context = context, env = env)
            }
            check(AngelPaySDK.isInitialized()) { "AngelPay SDK no quedó inicializado" }
        }
    }

    suspend fun ensureAuthenticated(credentials: AngelPayCredentials): Result<Unit> {
        if (AngelPaySDK.isAuthenticated()) return Result.success(Unit)
        return AngelPaySDK.authenticate(
            email = credentials.email,
            password = credentials.password,
            affiliation = credentials.affiliation,
            merchantToken = credentials.commerceToken,
        )
    }

    fun validatePaymentIntent(context: Context, request: PaymentRequest): Result<Unit> {
        return AngelPaySDK.createPaymentIntent(context, request)
            .map { Unit }
    }

    fun buildPaymentRequest(
        subtotal: BigDecimal,
        tip: BigDecimal,
        waiter: String?,
        reference: String?,
    ): PaymentRequest {
        return PaymentRequest(
            amountCents = toCents(subtotal),
            latitude = 0.0,
            longitude = 0.0,
            reference = reference,
            tipCents = toCents(tip),
            waiter = waiter,
            msi = null,
            isCheckIn = false,
            checkInId = null,
            allowSwipe = true,
            allowChip = true,
            allowContactless = true,
        )
    }

    fun buildQaTipFallbackRequest(
        subtotal: BigDecimal,
        tip: BigDecimal,
        waiter: String?,
        reference: String?,
    ): PaymentRequest {
        val total = subtotal.add(tip)
        return PaymentRequest(
            amountCents = toCents(total),
            latitude = 0.0,
            longitude = 0.0,
            reference = reference,
            tipCents = 0L,
            waiter = waiter,
            msi = null,
            isCheckIn = false,
            checkInId = null,
            allowSwipe = true,
            allowChip = true,
            allowContactless = true,
        )
    }

    fun isTipUnsupportedError(throwable: Throwable): Boolean {
        val msg = throwable.message?.lowercase().orEmpty()
        return msg.contains("c208") || msg.contains("propina no soportada")
    }

    private fun toCents(amount: BigDecimal): Long {
        return amount
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toLong()
    }
}
