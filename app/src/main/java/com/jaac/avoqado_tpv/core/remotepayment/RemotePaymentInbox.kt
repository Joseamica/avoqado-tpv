package com.jaac.avoqado_tpv.core.remotepayment

import kotlinx.coroutines.flow.map
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemotePaymentReceiveDecision {
    data class Deliver(val request: RemotePaymentRequest) : RemotePaymentReceiveDecision
    data object AckOnly : RemotePaymentReceiveDecision
    data class ReplayResult(val finalResultJson: String) : RemotePaymentReceiveDecision
    data class Reject(val reason: String) : RemotePaymentReceiveDecision
}

enum class RemotePaymentCancelDisposition { ACTIVE, ACCEPTED, ALREADY_RESOLVED }

/**
 * Respuesta a la SONDA del servidor (`terminal:payment_probe`). Sale de lo DURABLE y nunca entrega:
 *   RESOLVED           ⇒ hay resultado final guardado; se reproduce tal cual (con su `outcomeEvidence`).
 *   ACTIVE             ⇒ la solicitud está reclamada (PROCESSING) o alguien la reclamó en carrera: se conserva.
 *   RECEIVED_CANCELLED ⇒ estaba RECIBIDA y nadie la reclamó: se cancela durablemente ANTES de cualquier
 *                        autorización (evidencia `PRE_AUTHORIZATION`) y se reproduce ese resultado.
 *   NOT_FOUND          ⇒ esta bandeja nunca la persistió (y persistir precede al ACK). Deja una LÁPIDA durable
 *                        (`STATUS_NOT_FOUND_ANSWERED`): la misma solicitud, si llega después, se rechaza y no se ejecuta.
 */
enum class RemotePaymentProbeDisposition { RESOLVED, ACTIVE, RECEIVED_CANCELLED, NOT_FOUND }

/**
 * B2 (22-oct, «ninguna duda de cobro sin salida»): además de la disposición, la sonda y el cancel dicen si la ejecución
 * puede seguir viva. La EVIDENCIA que el SERVIDOR usa para soltar SÓLO la ranura de la terminal es
 * `ACTIVE` + [executionAlive] = false + [closedForExecution] = true; nunca dice «no se cobró» (eso lo deciden el banco o
 * una persona). Los defaults son los de hoy: «puede seguir viva» y «no cerrada». Un servidor viejo ignora los campos.
 */
data class RemotePaymentProbeAnswer(
    val disposition: RemotePaymentProbeDisposition,
    val finalResultJson: String? = null,
    /** false sólo cuando la libreta CERTIFICÓ en esta llamada que ningún intento de la solicitud puede seguir vivo. */
    val executionAlive: Boolean = true,
    /** true sólo si la solicitud quedó (o ya estaba) cerrada para ejecución: `execution_closed_at`. */
    val closedForExecution: Boolean = false,
)

data class RemotePaymentCancelDecision(
    val disposition: RemotePaymentCancelDisposition,
    val finalResultJson: String? = null,
    /** Igual que en [RemotePaymentProbeAnswer.executionAlive]. */
    val executionAlive: Boolean = true,
    /** Igual que en [RemotePaymentProbeAnswer.closedForExecution]. */
    val closedForExecution: Boolean = false,
)

/**
 * Persistir primero; ACK/navegación después.
 *
 * B2: [cerrarSiCeso] es la pregunta «¿ningún intento de esta solicitud puede seguir vivo?» que la libreta contesta
 * escribiendo el cierre para ejecución (`PaymentAttemptLedger.cerrarSolicitudSiCeso`, B1). Sin cablear, el default
 * `{ false }` NUNCA certifica: la respuesta sigue siendo «puede seguir viva», como antes de B2.
 *
 * Codex TPV r1, ronda 2 (N1): [laTerminalRechazariaUnaVentaNueva] es la pregunta «¿la terminal rechazaría AHORA una venta nueva
 * del POS?» (`CobroRemotoDelPos.laTerminalRechazariaUnaVentaNueva`, la MISMA regla de la admisión). Mientras diga true no se
 * certifica el cese. Sin cablear, el default `{ true }` tampoco certifica. Va ANTES de [cerrarSiCeso] a propósito: una lambda al
 * final de la llamada sigue siendo la de la libreta.
 */
@Singleton
class RemotePaymentInbox(
    private val dao: RemotePaymentRequestDao,
    private val shiftRepository: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository,
    private val laTerminalRechazariaUnaVentaNueva: () -> Boolean = { true },
    private val cerrarSiCeso: suspend (requestId: String) -> Boolean = { false },
) {
    /**
     * B2: Hilt cablea la pregunta a la libreta real. Constructor SECUNDARIO a propósito (mismo patrón que
     * `AngelPaySdkGateway` y `AuthAttemptTelemetryStore`): un default en el constructor `@Inject` genera una sobrecarga
     * sintética que Dagger rechaza («may only contain one injected constructor»). N1: también la regla de admisión, con las
     * banderas que publican las pantallas de cobro.
     */
    @Inject constructor(
        dao: RemotePaymentRequestDao,
        shiftRepository: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository,
        libreta: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger,
        estadoDeLasPantallas: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider,
    ) : this(
        dao = dao,
        shiftRepository = shiftRepository,
        laTerminalRechazariaUnaVentaNueva = {
            com.jaac.avoqado_tpv.features.payment.presentation.CobroRemotoDelPos.laTerminalRechazariaUnaVentaNueva(estadoDeLasPantallas)
        },
        cerrarSiCeso = { requestId -> libreta.cerrarSolicitudSiCeso(requestId) },
    )

    suspend fun receive(event: SocketEvent.TerminalPaymentRequest): RemotePaymentReceiveDecision {
        if (event.requestId.isBlank() || event.venueId.isBlank() || event.amountCents <= 0L || event.tipCents < 0L) {
            return RemotePaymentReceiveDecision.Reject("Solicitud remota inválida")
        }
        if (event.rating != null && event.rating !in 1..5) {
            return RemotePaymentReceiveDecision.Reject("Calificación remota inválida")
        }

        return try {
            val incoming = RemotePaymentRequestEntity.from(event)
            if (dao.insert(incoming) != -1L) {
                RemotePaymentReceiveDecision.Deliver(incoming.toRemoteRequest())
            } else {
                val existing = dao.getById(event.requestId)
                    ?: return RemotePaymentReceiveDecision.Reject("No se pudo releer la solicitud persistida")
                if (existing.status == RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED) {
                    // Codex 11-sep: la declaración «no recibida» es DURABLE. Esta solicitud llega DESPUÉS de haberle
                    // dicho al servidor que nunca se recibió (entrega retrasada, replay sin procedencia): no se ejecuta.
                    Timber.e("🪦 [RemotePaymentInbox] ${event.requestId} llegó DESPUÉS de contestar NOT_FOUND a la sonda: se rechaza sin ejecutar")
                    return RemotePaymentReceiveDecision.Reject("La terminal ya declaró al servidor que nunca recibió esta solicitud; no se ejecuta")
                }
                if (!existing.sameMoneyContract(event)) {
                    Timber.e("🛑 [RemotePaymentInbox] requestId repetido con contrato distinto: ${event.requestId}")
                    return RemotePaymentReceiveDecision.Reject("requestId ya existe con otro importe o contexto")
                }
                // N0: un duplicado con versión de capacidad MAYOR sube la columna (la entidad es lo que se entrega, no el
                // evento); nunca la baja — un replay de un servidor anterior no borra lo que otro ya declaró.
                val entregable = if (event.attemptLinkVersion > existing.attemptLinkVersion &&
                    dao.raiseAttemptLinkVersion(event.requestId, event.attemptLinkVersion, System.currentTimeMillis()) == 1
                ) existing.copy(attemptLinkVersion = event.attemptLinkVersion) else existing
                when (existing.status) {
                    RemotePaymentRequestEntity.STATUS_RECEIVED -> RemotePaymentReceiveDecision.Deliver(entregable.toRemoteRequest())
                    RemotePaymentRequestEntity.STATUS_PROCESSING -> RemotePaymentReceiveDecision.AckOnly
                    RemotePaymentRequestEntity.STATUS_RESOLVED -> existing.finalResultJson
                        ?.let(RemotePaymentReceiveDecision::ReplayResult)
                        ?: RemotePaymentReceiveDecision.Reject("Solicitud resuelta sin resultado durable")
                    else -> RemotePaymentReceiveDecision.Reject("Estado local de solicitud desconocido")
                }
            }
        } catch (error: Exception) {
            Timber.e(error, "❌ [RemotePaymentInbox] No se pudo persistir ${event.requestId}; no se confirma al servidor")
            RemotePaymentReceiveDecision.Reject("No se pudo guardar la solicitud en la terminal")
        }
    }

    /** Lo que la pantalla necesita para avisar con nombre y apellido: cuánto y desde cuándo. */
    fun observePendingObligations(venueId: String) = dao.observePendingObligations(venueId)

    /**
     * El conteo sale de la MISMA lista, no de una consulta hermana: dos consultas con el mismo
     * propósito se separan en cuanto alguien toca una — y entonces el número del aviso deja de
     * corresponder con lo que el aviso enumera.
     */
    fun observePendingObligationCount(venueId: String) =
        dao.observePendingObligations(venueId).map { it.size }

    suspend fun markProcessingForVenue(requestId: String, venueId: String): Boolean = try {
        shiftRepository.guardarNuevaObligacionDeCaja(venueId) { belonging ->
            dao.claimWithBelonging(requestId, venueId, belonging.kind, belonging.localSessionId,
                belonging.cashDrawerSessionId, System.currentTimeMillis()) == 1
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (error: Exception) { Timber.w(error, "No se admitió el cobro remoto en Caja"); false }

    suspend fun markProcessing(requestId: String): Boolean {
        val row = dao.getById(requestId) ?: return false
        return markProcessingForVenue(requestId, row.venueId)
    }

    suspend fun markResolved(requestId: String, finalResultJson: String): Boolean {
        val status = runCatching { JSONObject(finalResultJson).optString("status") }.getOrNull()
        // A timeout says nothing about authorization. Keep the durable request open.
        if (status !in setOf("success", "failed", "cancelled")) return false
        return dao.markResolved(requestId, finalResultJson, System.currentTimeMillis()) == 1
    }

    /**
     * No UI snapshot: this CAS and markProcessing are the only admission decision.
     *
     * C.5 (11-sep): una solicitud YA reclamada (PROCESSING) también se puede cancelar, pero sólo por el CAS
     * de [RemotePaymentRequestDao.cancelarTrasReclamar] — en una transacción con la libreta y dejando la
     * CERCA puesta. [propiedadEnEsteProceso] = este proceso reclamó el `requestId` (lo sabe el coordinador):
     * es lo único que permite aceptar un cancel cuando todavía no hay ningún intento en la libreta.
     *
     * B2 (22-oct): si ese CAS no acepta y la solicitud sigue reclamada sin efectivo/cripto arrancado, se contesta como la
     * sonda ([respuestaEnProceso]): con propiedad y la pantalla todavía capaz de seguir (cero intentos, o sólo rechazos/descartes
     * sin dinero), ACTIVE viva; sin propiedad, `resolverDesenlaceNegativo` (T3: un reclamo sin dueño y sin intentos lo dejó
     * un proceso muerto que nunca tocó el SDK ⇒ ACCEPTED, sólo si la fila nació bajo la barrera durable: D6); si no, la libreta
     * certifica o no el cese ([certificarCese]).
     */
    suspend fun cancel(requestId: String, propiedadEnEsteProceso: Boolean = false): RemotePaymentCancelDecision {
        val cancelled = JSONObject().put("requestId", requestId).put("status", "cancelled")
            .put("outcomeEvidence", "PRE_AUTHORIZATION")
            .put("errorMessage", "Cancelado por el POS antes de iniciar el cobro").toString()
        if (dao.resolveReceived(requestId, cancelled, System.currentTimeMillis()) == 1) {
            return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACCEPTED, cancelled)
        }
        val existing = dao.getById(requestId)
        if (existing?.status == RemotePaymentRequestEntity.STATUS_RESOLVED && existing.finalResultJson != null) {
            return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ALREADY_RESOLVED, existing.finalResultJson)
        }
        if (existing?.status == RemotePaymentRequestEntity.STATUS_PROCESSING) {
            val trasReclamar = JSONObject().put("requestId", requestId).put("status", "cancelled")
                .put("outcomeEvidence", "PRE_AUTHORIZATION")
                .put("errorMessage", "Cancelado por el POS; la terminal no inició ni iniciará otro intento").toString()
            val aceptado = runCatching {
                dao.cancelarTrasReclamar(requestId, trasReclamar, System.currentTimeMillis(), propiedadEnEsteProceso)
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Sin escritura durable no hay aceptación: el servidor conserva la reserva.
                if (error !is RemotePaymentRequestDao.CancelNoAceptado) {
                    Timber.e(error, "❌ [RemotePaymentInbox] CAS del cancel tras reclamar falló para $requestId")
                }
                false
            }
            if (aceptado) {
                Timber.i("🛑 [RemotePaymentInbox] Cancel ACEPTADO tras reclamar: $requestId queda cercada")
                return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACCEPTED, trasReclamar)
            }
            // Una carrera pudo resolverla entre la lectura y el CAS: se contesta por lo que quedó.
            val despues = dao.getById(requestId)
            if (despues?.status == RemotePaymentRequestEntity.STATUS_RESOLVED && despues.finalResultJson != null) {
                return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ALREADY_RESOLVED, despues.finalResultJson)
            }
            if (despues?.status == RemotePaymentRequestEntity.STATUS_PROCESSING && despues.executionStartedAt == null) {
                val sinIntentos = JSONObject().put("requestId", requestId).put("status", "cancelled")
                    .put("errorMessage", "Cancelado por el POS: la terminal nunca inició este cobro").toString()
                val respuesta = respuestaEnProceso(despues, propiedadEnEsteProceso, resolverSinPropiedad = {
                    runCatching { dao.resolverDesenlaceNegativo(requestId, sinIntentos, System.currentTimeMillis()) }.getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        // Sin escritura durable no hay aceptación: se sigue con la pregunta del cese.
                        if (error !is RemotePaymentRequestDao.CancelNoAceptado) {
                            Timber.e(error, "❌ [RemotePaymentInbox] No se pudo resolver el cancel de $requestId sin propiedad")
                        }
                        null
                    }
                })
                return when (respuesta) {
                    is RespuestaEnProceso.Resuelta -> {
                        Timber.w("🧹 [RemotePaymentInbox] Cancel ACEPTADO de $requestId: la reclamó un proceso muerto y NUNCA se ejecutó")
                        RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACCEPTED, respuesta.resultadoJson)
                    }
                    is RespuestaEnProceso.Activa -> RemotePaymentCancelDecision(
                        RemotePaymentCancelDisposition.ACTIVE,
                        executionAlive = respuesta.ejecucionViva,
                        closedForExecution = respuesta.cerradaParaEjecucion,
                    )
                }
            }
        }
        // Claimed with execution in flight, not found or tombstoned: no evidence that cancelling is safe.
        return RemotePaymentCancelDecision(RemotePaymentCancelDisposition.ACTIVE)
    }

    /** B2: lo que se contesta de una solicitud reclamada (PROCESSING) sin efectivo/cripto arrancado. */
    private sealed interface RespuestaEnProceso {
        /** Caso 2: `resolverDesenlaceNegativo` escribió el desenlace (la libreta acredita que nadie tocó el SDK). */
        data class Resuelta(val resultadoJson: String) : RespuestaEnProceso
        /** Casos 1 y 3: sigue ACTIVE; con o sin ejecución viva, cerrada o no para ejecución. */
        data class Activa(val ejecucionViva: Boolean, val cerradaParaEjecucion: Boolean) : RespuestaEnProceso
    }

    /**
     * 🔒 B2 (22-oct, «ninguna duda de cobro sin salida», T1) — la MISMA pregunta para la sonda y el cancel, en este orden:
     *  1. con propiedad en ESTE proceso, la solicitud SIN cerrar para ejecución y la pantalla todavía capaz de seguir
     *     ([laPantallaPuedeReintentar]: cero intentos —espera el toque de «Tarjeta»—, o sólo un rechazo final del banco,
     *     descartes o una reserva PREPARANDO, sin dinero ni contradicción —ofrece «Reintentar»—) ⇒ ACTIVE, ejecución viva y
     *     no cerrada. NUNCA se le pregunta a la libreta:
     *     `cerrarSolicitudSiCeso` no ve la pantalla y cerraría, y «cerrada para ejecución» tiene que ser verdad (decisión del
     *     controlador, 22-oct, duda 2 de B2).
     *  2. sin propiedad ⇒ [resolverSinPropiedad] (`resolverDesenlaceNegativo`, el ÚNICO camino que escribe un «no se cobró»:
     *     exige cero intentos bloqueadores y, D6, una fila nacida bajo la barrera durable). Si escribe ⇒ [RespuestaEnProceso.Resuelta].
     *  3. si no resolvió (o con propiedad y algún intento pendiente, con dinero o contradicción) ⇒ [certificarCese], SALVO que
     *     la terminal rechace ahora una venta nueva del POS ([laTerminalRechazariaUnaVentaNueva], Codex TPV r1 ronda 2, N1: la
     *     PAX en «Cobrado. Sincronización pendiente» con la bandera de cobro activo arriba) ⇒ ACTIVE viva, sin preguntarle a la
     *     libreta y sin escribir nada: «libre» en el SERVIDOR tiene que significar que la terminal SÍ aceptaría otra venta.
     * Una reserva PREPARANDO de este proceso «puede seguir viva» (Codex plan-r2-1): tiene propiedad, así que nunca pasa por el
     * caso 2 (cae en el 1) — la sonda nunca la aborta.
     *
     * ⚠️ Residuo aceptado (caso 1): si la pantalla muere sin escribir su final y el proceso sigue vivo, la propiedad sigue en
     * memoria y la respuesta sigue siendo «viva»: la terminal queda apartada COMO HOY (los 20 min del servidor, la declaración
     * del cajero o soporte). Sin dinero de por medio, contestar «viva» nunca libera nada.
     * ⚠️ Salvedad (D6, 2-nov; M2 de su revisión): en una RECEIVED HEREDADA que el APK nuevo reclama (ventana de milisegundos: el
     * proceso viejo murió entre el `insert` y el reclamo, y el servidor reentregó antes de sondear) la pantalla no escribe su
     * negativo (`persistResult` ⇒ null) y la propiedad queda en memoria, así que cada sonda contesta «viva» y el servidor sella
     * `probeActiveAt`: ahí la declaración del cajero TAMBIÉN se veta. La salida es REINICIAR la app de la terminal: sin dueño,
     * el caso 3 certifica el cese. Un cobro aprobado no se afecta.
     */
    private suspend fun respuestaEnProceso(
        fila: RemotePaymentRequestEntity,
        propiedadEnEsteProceso: Boolean,
        resolverSinPropiedad: suspend () -> String?,
    ): RespuestaEnProceso {
        // Fix round 1 (M2): ya CERRADA para ejecución, la pantalla dueña tampoco puede reintentar (la cerca rige): no hay caso 1,
        // va al 3 y la libreta confirma el cierre sin volver a escribir. Así una sonda posterior no contradice a la que cerró.
        if (propiedadEnEsteProceso && fila.executionClosedAt == null && laPantallaPuedeReintentar(fila.requestId)) {
            return RespuestaEnProceso.Activa(ejecucionViva = true, cerradaParaEjecucion = false)
        }
        if (!propiedadEnEsteProceso) {
            resolverSinPropiedad()?.let { return RespuestaEnProceso.Resuelta(it) }
        }
        // Codex TPV r1, ronda 2 (N1): si la terminal rechazaría AHORA una venta nueva del POS (la misma regla de la admisión), el
        // certificado haría que el SERVIDOR dijera «libre» sin serlo. Sin preguntarle a la libreta y sin escribir nada.
        if (laTerminalRechazariaAhora()) {
            Timber.i("🔒 [RemotePaymentInbox] ${fila.requestId}: la pantalla tiene un cobro activo; no se certifica el cese (sigue viva)")
            return RespuestaEnProceso.Activa(ejecucionViva = true, cerradaParaEjecucion = fila.executionClosedAt != null)
        }
        return certificarCese(fila)
    }

    /** N1: [laTerminalRechazariaUnaVentaNueva]; si no se puede saber (lanza), se supone que sí — el lado que no certifica. */
    private fun laTerminalRechazariaAhora(): Boolean = runCatching { laTerminalRechazariaUnaVentaNueva() }.getOrElse { error ->
        Timber.e(error, "❌ [RemotePaymentInbox] No se pudo saber si la pantalla tiene un cobro activo: no se certifica")
        true
    }

    /**
     * 🔒 Fix round 0 de B2 (decisión del controlador, 22-oct): ¿la pantalla DUEÑA puede seguir con esta solicitud sin que nada
     * suyo esté pendiente? Sí cuando NINGÚN intento de la solicitud es bloqueador
     * ([RemotePaymentRequestDao.contarIntentosBloqueadores] == 0) — el MISMO y único predicado del cancel seguro y de H.3, a
     * propósito: un intento NO bloqueador es exactamente uno que la pantalla deja atrás para ofrecer «Reintentar»:
     *  - (a) el RECHAZO FINAL del banco sin dinero: `state = 'DESCARTADA' AND host_approved IS 0`, que escribe sólo
     *    `PaymentAttemptDao.casHostResponded` (`markHostResponded(approved = false)`);
     *  - (b) cualquier otra DESCARTADA (rechazo del kernel, descarte antes de cobrar, «sin autorización» de AngelPay 1.0.19,
     *    liberada por el servidor) o una reserva PREPARANDO;
     *  - y en los dos, sin dinero ni contradicción: `legacy_shadow = 0`, `server_payment_id IS NULL`,
     *    `server_processor_evidence IS NOT 'APPROVED'` y fuera de [com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.SQL_CONTRADICCION]
     *    (que incluye `server_veto`).
     * Cero intentos también cumple (la pantalla espera «Tarjeta»). Cualquier otro estado (KERNEL_ACTIVO, AUTORIZANDO,
     * HOST_RESPONDIO, AUTORIZADO, REGISTRO_FALLIDO, INDETERMINADO, REGISTRADO…) o el dinero/contradicción lo hacen bloqueador ⇒
     * caso 3. Mientras la bandeja siga PROCESSING no hay final emitido, así que la cerca deja reservar el reintento. Una
     * solicitud ya cerrada para ejecución no la deja (la cerca rige): por eso [respuestaEnProceso] sólo pregunta esto sin cierre.
     */
    private suspend fun laPantallaPuedeReintentar(requestId: String): Boolean =
        dao.contarIntentosBloqueadores(requestId) == 0

    /**
     * B2, caso 3: le pregunta a la libreta si ningún intento de la solicitud puede seguir vivo ([cerrarSiCeso], que escribe
     * el cierre para ejecución o confirma el que ya había, sin volver a escribir). «Sin ejecución viva» sólo cuando la
     * libreta lo certificó en ESTA llamada; «cerrada» también si la bandeja ya lo estaba (un hecho durable: la cerca rige).
     * Nunca lanza (salvo cancelación): un fallo es «puede seguir viva», el lado seguro.
     */
    private suspend fun certificarCese(fila: RemotePaymentRequestEntity): RespuestaEnProceso.Activa {
        val certificado = runCatching { cerrarSiCeso(fila.requestId) }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Timber.e(error, "❌ [RemotePaymentInbox] La libreta no pudo contestar si ${fila.requestId} cesó: sigue viva")
            false
        }
        if (certificado) {
            // `i` y no `w`: la libreta ya avisa con `w` al escribir el cierre; esto sale en cada sonda repetida.
            Timber.i("🔒 [RemotePaymentInbox] ${fila.requestId}: sin ejecución viva y CERRADA para ejecución (el dinero lo decide el servidor)")
        }
        return RespuestaEnProceso.Activa(
            ejecucionViva = !certificado,
            cerradaParaEjecucion = certificado || fila.executionClosedAt != null,
        )
    }

    /**
     * SONDA. Nunca marca PROCESSING, nunca entrega. Dos escrituras posibles, las dos durables y las dos sin
     * fabricar evidencia: cancelar una solicitud RECIBIDA que nadie reclamó (el mismo CAS que `cancel`, así que un
     * reclamo concurrente gana y la respuesta baja a ACTIVE), y dejar una LÁPIDA cuando la bandeja no la tiene.
     * El `venueId` es el del evento de la sonda (el servidor sólo sonda filas del venue de esta terminal): es el
     * tenant de la lápida. Sin venue no hay lápida posible, y sin lápida escrita no se contesta NOT_FOUND: se lanza
     * y el servidor conserva la reserva (una respuesta que no consta no es una respuesta).
     */
    suspend fun probe(requestId: String, venueId: String, propiedadEnEsteProceso: Boolean = false): RemotePaymentProbeAnswer {
        require(venueId.isNotBlank()) { "La sonda no puede dejar lápida sin venue" }
        val existing = dao.getById(requestId) ?: return answerAbsent(requestId, venueId)
        return answerFor(existing, propiedadEnEsteProceso)
    }

    /**
     * Codex 11-sep: «una declaración “no recibido” impide DURABLEMENTE ejecutar ese intento». La lápida es lo que
     * convierte NOT_FOUND en una promesa: si la misma solicitud llega después (entrega retrasada, replay de un servidor
     * sin procedencia), `receive` la rechaza en vez de ejecutarla. Una lápida que no se pudo escribir propaga la
     * excepción: sin escritura durable no hay NOT_FOUND.
     */
    private suspend fun answerAbsent(requestId: String, venueId: String): RemotePaymentProbeAnswer {
        if (dao.insert(RemotePaymentRequestEntity.tombstone(requestId, venueId)) != -1L) {
            Timber.w("🪦 [RemotePaymentInbox] NOT_FOUND con lápida: $requestId no se ejecutará aunque llegue después")
            return RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.NOT_FOUND)
        }
        // Carrera: la solicitud se persistió entre la lectura y la lápida. Se contesta por lo que hay; nunca NOT_FOUND.
        val raced = dao.getById(requestId) ?: return RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        return answerFor(raced)
    }

    private suspend fun answerFor(
        existing: RemotePaymentRequestEntity,
        propiedadEnEsteProceso: Boolean = false,
    ): RemotePaymentProbeAnswer = when (existing.status) {
        RemotePaymentRequestEntity.STATUS_RESOLVED -> existing.finalResultJson
            ?.let { RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.RESOLVED, it) }
            ?: RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        // 🔴 MEDIDO EN UNA NEXGO (21-sep): una fila reclamada por un proceso que MURIÓ se quedaba en
        // `PROCESSING` para siempre, y la sonda contestaba ACTIVE indefinidamente. Medido en la bandeja
        // real: `PROCESSING` desde el 17-sep con `execution_started_at` VACÍO — nunca llegó a tocar el
        // SDK. El servidor le creía, conservaba la reserva, y el cajero se quedaba sin poder cobrar NI
        // declarar: el callejón sin salida del 18-sep por otra puerta. Es el defecto que la regla del
        // repo nombra: «describe falsamente una ejecución».
        //
        // «Activo» es propiedad de un proceso VIVO, no de una fila en disco. Si este proceso no la
        // reclamó, quien lo hizo ya no existe — y si además nunca empezó, es el MISMO caso que
        // `STATUS_RECEIVED`: la tarjeta jamás se pasó. Se resuelve por el ÚNICO camino que ya protege el
        // dinero (`resolverDesenlaceNegativo` exige `executionStartedAt == null` y cero intentos
        // bloqueadores); si algo de eso no se cumple, devuelve null y se conserva ACTIVE.
        // 🔒 D6 (2-nov): y sólo si la fila nació bajo la barrera durable. En una heredada, «nunca empezó» es justo lo que su
        // libreta no puede probar: una versión vieja pudo cobrar sin anotarlo. Ahí sólo se certifica el cese.
        //
        // B2 (22-oct): ACTIVE ya no basta. Si no se resolvió, la libreta dice si la ejecución puede seguir viva y, si no
        // puede, deja la solicitud CERRADA PARA EJECUCIÓN ([respuestaEnProceso]). Con propiedad y la pantalla todavía capaz
        // de seguir (espera «Tarjeta» u ofrece «Reintentar») sigue siendo ACTIVE viva, como antes; con efectivo/cripto
        // arrancado, también.
        RemotePaymentRequestEntity.STATUS_PROCESSING -> if (existing.executionStartedAt != null) {
            RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
        } else {
            val huerfana = JSONObject().put("requestId", existing.requestId).put("status", "cancelled")
                .put("errorMessage", "Cancelado por conciliación: la terminal nunca inició este cobro").toString()
            val respuesta = respuestaEnProceso(existing, propiedadEnEsteProceso, resolverSinPropiedad = {
                dao.resolverDesenlaceNegativo(existing.requestId, huerfana, System.currentTimeMillis())
            })
            when (respuesta) {
                is RespuestaEnProceso.Resuelta -> {
                    Timber.w("🧹 [RemotePaymentInbox] ${existing.requestId} quedó reclamada por un proceso muerto y NUNCA se ejecutó: se resuelve")
                    RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.RECEIVED_CANCELLED, respuesta.resultadoJson)
                }
                is RespuestaEnProceso.Activa -> RemotePaymentProbeAnswer(
                    RemotePaymentProbeDisposition.ACTIVE,
                    executionAlive = respuesta.ejecucionViva,
                    closedForExecution = respuesta.cerradaParaEjecucion,
                )
            }
        }
        RemotePaymentRequestEntity.STATUS_RECEIVED -> {
            val cancelled = JSONObject().put("requestId", existing.requestId).put("status", "cancelled")
                .put("outcomeEvidence", "PRE_AUTHORIZATION")
                .put("errorMessage", "Cancelado por conciliación: la terminal nunca inició este cobro").toString()
            if (dao.resolveReceived(existing.requestId, cancelled, System.currentTimeMillis()) == 1) {
                RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.RECEIVED_CANCELLED, cancelled)
            } else {
                RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
            }
        }
        // La lápida contesta lo mismo cada vez: esta bandeja nunca la persistió como solicitud y no la ejecutará.
        RemotePaymentRequestEntity.STATUS_NOT_FOUND_ANSWERED -> RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.NOT_FOUND)
        else -> RemotePaymentProbeAnswer(RemotePaymentProbeDisposition.ACTIVE)
    }

    /** A full navigation queue may reject ONLY a command that has never been claimed. */
    suspend fun rejectUnclaimed(requestId: String): String? {
        val result = JSONObject().put("requestId", requestId).put("status", "failed")
            .put("outcomeEvidence", "PRE_AUTHORIZATION")
            .put("errorMessage", "La terminal no pudo abrir otra solicitud de cobro").toString()
        return result.takeIf { dao.resolveReceived(requestId, it, System.currentTimeMillis()) == 1 }
    }

    /**
     * Return the actual durable winner so duplicate callbacks replay rather than disappear.
     *
     * 🛑 H.3 + evidencia POR INTENTO (11-sep): un desenlace NEGATIVO (failed/cancelled) no se escribe con la
     * evidencia que trae la pantalla, sino con la que acredita la LIBRETA del último intento de la solicitud
     * ([RemotePaymentRequestDao.resolverDesenlaceNegativo]). Si la libreta no acredita ningún «no se cobró»
     * (intento incierto o en curso), no se escribe nada y la solicitud se conserva sin desenlace: el servidor
     * la deja sin resolver en vez de certificar un «no» que no consta.
     */
    suspend fun persistResult(requestId: String, resultJson: String): String? {
        val status = runCatching { JSONObject(resultJson).optString("status") }.getOrNull()
        if (status == "failed" || status == "cancelled") {
            val escrito = runCatching {
                dao.resolverDesenlaceNegativo(requestId, resultJson, System.currentTimeMillis())
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error !is RemotePaymentRequestDao.CancelNoAceptado) {
                    Timber.e(error, "❌ [RemotePaymentInbox] No se pudo escribir el desenlace negativo de $requestId")
                }
                null
            }
            if (escrito != null) return escrito
            val existing = dao.getById(requestId) ?: return null
            if (existing.status == RemotePaymentRequestEntity.STATUS_PROCESSING) {
                Timber.w("⚠️ [RemotePaymentInbox] $requestId: la libreta no acredita «no se cobró» — no se escribe desenlace negativo")
                return null
            }
            // Ya resuelta: se reproduce el ganador durable (nunca se degrada un éxito).
            if (existing.status != RemotePaymentRequestEntity.STATUS_RESOLVED) return null
            return existing.finalResultJson
        }
        if (markResolved(requestId, resultJson)) return resultJson
        val existing = dao.getById(requestId) ?: return null
        val previous = existing.finalResultJson ?: return null
        if (existing.status != RemotePaymentRequestEntity.STATUS_RESOLVED) return null
        // A real approval outranks historical cancellation/failed results, including old APK
        // false cancellations. Never allow cancellation or failure to overwrite approval.
        if (JSONObject(resultJson).optString("status") == "success" &&
            JSONObject(previous).optString("status") != "success"
        ) {
            if (dao.replaceResolvedResult(requestId, previous, resultJson, System.currentTimeMillis()) == 1) return resultJson
            return dao.getById(requestId)?.finalResultJson
        }
        return previous
    }

}
