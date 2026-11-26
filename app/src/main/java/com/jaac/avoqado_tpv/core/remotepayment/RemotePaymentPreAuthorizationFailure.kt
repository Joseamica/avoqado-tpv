package com.jaac.avoqado_tpv.core.remotepayment

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager

/** Lo que oye el POS cuando el lector de la terminal no está listo para su cobro (admisión o espera de la ruta, D3). */
internal const val LECTOR_NO_LISTO_EN_LA_TERMINAL = "Sistema de pagos no inicializado en el terminal"

/** Navigation owns the request and calls this only before opening its payment screen/SDK. */
internal fun rejectRemotePaymentBeforeAuthorization(
    socketManager: SocketManager,
    requestId: String,
    errorMessage: String,
) {
    socketManager.emitTerminalPaymentResult(requestId, "failed", errorMessage = errorMessage, outcomeEvidence = "PRE_AUTHORIZATION")
}

/**
 * 📡 hz:1450a1e9:D3: la ruta de cobro se fue sin abrir su pantalla. Pasa cuando el lector se cae entre la admisión y el primer
 * dibujo, y la segunda preparación falla. Sin ViewModel nadie contestaba: el POS esperaba hasta que su reloj se rendía, y la sonda
 * seguía diciendo «viva» por la propiedad de este proceso.
 *
 * Ahora se contesta como la admisión con el lector no listo, y se suelta la propiedad como al morir una pantalla (D26). El «no se
 * cobró» sólo se guarda y sale si la libreta acredita que no hubo intento (`RemotePaymentInbox.persistResult`). Si no lo acredita,
 * no sale nada y contesta la siguiente sonda. La propiedad se suelta DESPUÉS de guardar (Codex r1 #1): una sonda que llegara entre
 * las dos se quedaba con «Cancelado por conciliación» y la tablet perdía la razón.
 */
internal fun contestarCobroDelPosSinPantalla(
    socketManager: SocketManager,
    coordinador: RemotePaymentCoordinator,
    requestId: String,
) {
    socketManager.emitTerminalPaymentResult(
        requestId, "failed", errorMessage = LECTOR_NO_LISTO_EN_LA_TERMINAL, outcomeEvidence = "PRE_AUTHORIZATION",
        alGuardar = { coordinador.soltarPropiedad(requestId) },
    )
}
