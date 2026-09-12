package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager

/** Navigation owns the request and calls this only before opening its payment screen/SDK. */
internal fun rejectRemotePaymentBeforeAuthorization(
    socketManager: SocketManager,
    requestId: String,
    errorMessage: String,
) {
    socketManager.emitTerminalPaymentResult(requestId, "failed", errorMessage = errorMessage, outcomeEvidence = "PRE_AUTHORIZATION")
}
