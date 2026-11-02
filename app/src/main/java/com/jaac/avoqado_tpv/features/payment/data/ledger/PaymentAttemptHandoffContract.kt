package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext

internal const val HANDOFF_CONTRACT_CONFLICT = -2L
internal class PaymentOwnerContractConflictException : IllegalStateException(
    "La devolución no coincide con el registro original. La evidencia permanece guardada para revisión.")

internal fun PendingRefundEntity.sameFinancialContract(other: PendingRefundEntity): Boolean =
    idempotencyKey == other.idempotencyKey && venueId == other.venueId && staffId == other.staffId && processor == other.processor &&
    originalPaymentId == other.originalPaymentId && originalOrderId == other.originalOrderId &&
    amount.toBigDecimal().compareTo(other.amount.toBigDecimal()) == 0 &&
    originalTotalAmount.toBigDecimal().compareTo(other.originalTotalAmount.toBigDecimal()) == 0 &&
    tipRefundCents == other.tipRefundCents && isPartialRefund == other.isPartialRefund && refundReason == other.refundReason &&
    merchantAccountId == other.merchantAccountId && blumonSerialNumber == other.blumonSerialNumber &&
    originalOperationNumber == other.originalOperationNumber && referenceNumber == other.referenceNumber &&
    authorizationNumber == other.authorizationNumber && shiftMembership == other.shiftMembership &&
    localShiftSessionId == other.localShiftSessionId && cashDrawerSessionId == other.cashDrawerSessionId

internal data class HandoffContractCheck(val proven: Boolean, val conflict: String? = null)
private fun PaymentAttemptEntity.snapshot(): JsonObject? = runCatching { JsonParser.parseString(paymentContextJson).asJsonObject }.getOrNull()
private fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
private fun cents(value: String): Long? = runCatching { value.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()

/** Sólo valida el traspaso del contrato guardado; no autoriza ni registra dinero. */
internal fun PaymentAttemptEntity.checkHandoff(row: PendingPaymentEntity) =
    checkCapturedOwner(checkSale(row.amount, row.tip, row.orderId), row.staffId,
        row.shiftMembership, row.localShiftSessionId, row.cashDrawerSessionId)
private fun PaymentAttemptEntity.checkSale(amount: String, tip: String, orderId: String?): HandoffContractCheck {
    if (kind != PaymentAttemptEntity.KIND_SALE) return HandoffContractCheck(false, "kind")
    if (cents(amount) != amountCents) return HandoffContractCheck(false, "amount")
    if (cents(tip) != tipCents) return HandoffContractCheck(false, "tip")
    val json = snapshot()
    val orderKnown = recordingRoute == PaymentAttemptEntity.ROUTE_FAST || json?.has("orderId") == true
    val order = runCatching { json?.text("orderId") }.getOrNull()
    if (orderKnown && order != orderId) return HandoffContractCheck(false, "order")
    val actorKnown = runCatching { !json?.text("staffId").isNullOrBlank() }.getOrDefault(false)
    return HandoffContractCheck(!legacyShadow && orderKnown && actorKnown)
}

/** El desglose opcional de propina no es tipCents: null conserva la decisión del servidor. */
internal fun PaymentAttemptEntity.checkHandoff(row: PendingRefundEntity) =
    checkCapturedOwner(checkRefund(row.amount, row.originalPaymentId, row.originalOrderId, row.tipRefundCents), row.staffId,
        row.shiftMembership, row.localShiftSessionId, row.cashDrawerSessionId)
private fun PaymentAttemptEntity.checkRefund(amount: String, paymentId: String, orderId: String?, tipRefundCents: Int?): HandoffContractCheck {
    if (kind != PaymentAttemptEntity.KIND_REFUND) return HandoffContractCheck(false, "kind")
    if (cents(amount) != amountCents) return HandoffContractCheck(false, "amount")
    val json = snapshot()
    val paymentKnown = json?.has("originalPaymentId") == true
    val orderKnown = json?.has("originalOrderId") == true
    val splitKnown = json?.has("tipRefundCents") == true
    return runCatching {
        if (paymentKnown && json!!.text("originalPaymentId") != paymentId) return HandoffContractCheck(false, "payment")
        if (orderKnown && json!!.text("originalOrderId") != orderId) return HandoffContractCheck(false, "order")
        if (splitKnown) {
            val split = json!!.get("tipRefundCents").takeUnless { it.isJsonNull }?.asBigDecimal?.intValueExact()
            if (split != tipRefundCents) return HandoffContractCheck(false, "tip_refund")
        }
        val actorKnown = !json?.text("staffId").isNullOrBlank()
        HandoffContractCheck(!legacyShadow && paymentKnown && orderKnown && splitKnown && actorKnown)
    }.getOrElse { HandoffContractCheck(false, "snapshot") }
}

private fun PaymentAttemptEntity.checkCapturedOwner(
    contract: HandoffContractCheck, staffId: String, membership: String, sessionId: String?, drawerId: String?,
): HandoffContractCheck {
    if (contract.conflict != null) return contract
    val originalStaff = runCatching { snapshot()?.text("staffId") }.getOrNull()
    if (originalStaff != null && staffId != originalStaff) return HandoffContractCheck(false, "staff")
    if (membership != shiftMembership || sessionId != localShiftSessionId || drawerId != cashDrawerSessionId)
        return HandoffContractCheck(false, "membership")
    return contract
}

internal fun PaymentAttemptEntity.checkHandoff(context: PaymentContext): HandoffContractCheck = when (context) {
    is PaymentContext.RefundPayment -> checkRefund(context.amount.toPlainString(), context.originalPaymentId, context.originalOrderId, context.tipRefundCents)
    is PaymentContext.OrderPayment -> checkSale(context.amount.toPlainString(), context.tip.toPlainString(), context.orderId)
    is PaymentContext.AngelPayPayment -> checkSale(context.amount.toPlainString(), context.tip.toPlainString(), context.orderId)
    is PaymentContext.FastPayment -> checkSale(context.amount.toPlainString(), context.tip.toPlainString(), null)
}
