package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves a saved PAX attempt. Never invokes the SDK or treats missing bank evidence as a decline. */
@Singleton
class BlumonAttemptResolver @Inject constructor(
    private val ledger: PaymentAttemptLedger,
    private val recovery: LedgerServerRecovery,
    private val api: TerminalAttemptApiService,
    private val storage: SecureStorage,
) {
    /** F1 (tarea 3): [SIGNATURE_REQUIRED] = falta el PIN del gerente, o Avoqado dijo que ese PIN no firma (403). */
    enum class State { PENDING, RELEASED, RECORDED, MONEY_EVIDENCE, PERMISSION_REQUIRED, UNAVAILABLE, SIGNATURE_REQUIRED }
    data class Result(
        val state: State,
        val row: PaymentAttemptEntity? = null,
        val serverAnswered: Boolean = false,
        val resultJson: String? = null,
        /**
         * F1 (Codex código r2 #5): la duda ya estaba declarada en Avoqado ANTES de esta firma (la consulta trajo el acuse, o el replay
         * devolvió el de otra persona, p. ej. el de la cajera por la sesión): la pantalla no dice «la firma del gerente».
         */
        val yaEstabaDeclarado: Boolean = false,
    )

    private val mutex = Mutex()
    private val unsavedVetoes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val unsavedMoney = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun hasPermission(): Boolean = runCatching {
        PermissionsRepository.enLaUltimaListaEfectiva(storage, "payments:resolve-no-instrument")
    }.getOrDefault(false)

    /** «Revisé la terminal y no se cobró»: la afirmación del CAJERO (founder, 18 y 25-sep). No es «no presentó tarjeta». */
    fun hasCheckPermission(): Boolean = runCatching {
        PermissionsRepository.enLaUltimaListaEfectiva(storage, "payments:reconcile-uncharged")
    }.getOrDefault(false)

    fun observe(venueId: String, attemptId: String) = ledger.observarIntento(attemptId, venueId).map {
        classify(venueId, attemptId, it)
    }

    /** Fresh durable state plus any evidence that could not be saved. Does not contact the bank. */
    suspend fun current(venueId: String, attemptId: String): Result = mutex.withLock {
        inspect(venueId, attemptId)
    }

    suspend fun consult(venueId: String, attemptId: String): Result = mutex.withLock {
        consultLocked(venueId, attemptId)
    }

    private suspend fun consultLocked(venueId: String, attemptId: String, procesadores: Set<String> = SOLO_PAX): Result {
        if (scopedRow(venueId, attemptId, procesadores) == null) return Result(State.UNAVAILABLE)
        // D5 (533d77, Codex r2 #3): si la duda «el chip rechazó tras aprobar» no se pudo escribir, se reintenta aquí (es local:
        // también sin red) antes de consultar.
        ledger.reintentarChipRechazo(attemptId)
        val reading = try {
            recovery.recoverOne(venueId, attemptId, System.currentTimeMillis(), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return inspect(venueId, attemptId, procesadores)
        }
        recordarVetoSinGuardar(attemptId, reading)
        val result = inspect(venueId, attemptId, procesadores).copy(serverAnswered = reading.servidorContesto, resultJson = reading.bandejaResueltaJson)
        // The response wins even when a durable write failed and the row still says RELEASED.
        return if (reading.evidenciaPositivaSinRegistro || reading.vetoDelServidor != null)
            result.copy(state = State.MONEY_EVIDENCE)
        else result
    }

    /**
     * El veto que trajo esta lectura y no se pudo escribir se recuerda aquí, venga de quien venga la consulta: si no, la fila se ve limpia y
     * se podría declarar «no se cobró» sin red (Codex código r1: la consulta de cada 15 s del banner).
     */
    suspend fun recordarVetoSinGuardar(attemptId: String, lectura: LedgerServerRecovery.LecturaDelIntento) {
        if (lectura.vetoDelServidor == null) return
        // Si ni se puede leer la fila, tampoco consta que se escribió: se recuerda igual.
        if (runCatching { ledger.leerIntento(attemptId)?.serverVeto }.getOrNull() == null) unsavedVetoes.add(attemptId)
    }

    /**
     * 🔴 F1 (17-nov, plan mínimo tarea 3): el GERENTE firma con su PIN que revisó la terminal y este cobro LOCAL no aparece. Ya no
     * decide el permiso de la sesión (la cajera ya no lo trae): decide Avoqado con el PIN. Primero se consulta a Avoqado; con
     * evidencia de dinero no se declara. La libreta sólo se cierra con el acuse FIRMADO de Avoqado (D22: `UNCHARGED_VERIFIED`, ahora
     * con `by = SUPERVISOR_PIN`), a nombre del gerente; sin respuesta A sigue en duda y repetir manda la MISMA declaración. Nunca
     * se cierra sólo en esta terminal. El PIN viaja en el cuerpo y no se guarda. PAX y Nexgo; los cobros del POS tienen su camino.
     */
    suspend fun declareChecked(venueId: String, attemptId: String, supervisorPin: String? = null): Result = mutex.withLock {
        if (supervisorPin.isNullOrBlank()) return@withLock Result(State.SIGNATURE_REQUIRED)
        val reading = consultLocked(venueId, attemptId, LOS_DOS)
        if (reading.state != State.PENDING) return@withLock reading.copy(yaEstabaDeclarado = reading.state == State.RELEASED)
        val row = reading.row ?: return@withLock Result(State.UNAVAILABLE)
        if (row.terminalPaymentRequestId != null || row.serverAnsweredAt == null || !ledger.puedeDeclararseEnDuda(row, venueId)) return@withLock reading
        // F1: la firma necesita conexión. Si la consulta de ESTA llamada no tuvo respuesta, ni se intenta: A se conserva.
        if (!reading.serverAnswered) return@withLock reading
        val staffId = storage.getStaffId()
        // D22: la declaración llega a Avoqado con el monto de la libreta y el dueño la ve en la Bitácora. El CAS local va DESPUÉS del
        // envío: la evidencia que llegue en medio gana (salidaNegativa).
        run {
            // 🔴 hz:nube0000:na1bd1b1 (Codex r1 #1): justo antes de mandarla, la libreta se vuelve a leer. El historial de AngelPay
            // escribe la aprobación directo en Room (no pasa por el candado): si ya quedó escrita, no sale «no se cobró».
            val justoAntes = inspect(venueId, attemptId, LOS_DOS)
            val filaJustoAntes = justoAntes.row
            if (justoAntes.state != State.PENDING || filaJustoAntes == null || filaJustoAntes.terminalPaymentRequestId != null ||
                !ledger.puedeDeclararseEnDuda(filaJustoAntes, venueId))
                return@withLock justoAntes.copy(
                    serverAnswered = true, resultJson = reading.resultJson, yaEstabaDeclarado = justoAntes.state == State.RELEASED,
                )
        }
        val envio = enviarDeclaracionRevisada(venueId, attemptId, row, staffId, supervisorPin)
        val gerente = when (envio) {
            is EnvioRevisado.Firmado -> envio.gerente
            // Sin respuesta: A se conserva y la pantalla dice que la firma necesita conexión.
            EnvioRevisado.SIN_RESPUESTA -> return@withLock reading.copy(serverAnswered = false)
            // Contestó, pero no es el acuse firmado de ESTA declaración (otro rechazo, un servidor anterior, sin firmante).
            EnvioRevisado.NO_FIRMADO -> return@withLock inspect(venueId, attemptId, LOS_DOS).copy(serverAnswered = true)
            EnvioRevisado.PIN_RECHAZADO -> return@withLock reading.copy(state = State.SIGNATURE_REQUIRED, serverAnswered = true)
            EnvioRevisado.HAY_DINERO -> return@withLock inspect(venueId, attemptId, LOS_DOS).copy(state = State.MONEY_EVIDENCE, serverAnswered = true)
            EnvioRevisado.SIN_PERMISO -> return@withLock reading.copy(state = State.PERMISSION_REQUIRED, serverAnswered = true)
            is EnvioRevisado.ConVeto -> return@withLock envio.resultado
        }
        val cerrada = ledger.declararSinCobroLocal(attemptId, venueId, gerente, enAvoqado = true)
        // Avoqado sí contestó (firmó): si el CAS no aplicó, la pantalla no debe decir «sin conexión».
        val despues = inspect(venueId, attemptId, LOS_DOS).copy(
            serverAnswered = true, resultJson = reading.resultJson,
            // Codex código r2 #5: el replay devolvió el acuse de OTRA persona (la sesión de la cajera): no firmó este gerente.
            yaEstabaDeclarado = (envio as? EnvioRevisado.Firmado)?.porElGerente == false,
        )
        // na1bd1b1: el único hueco que la terminal no puede cerrar sin arriesgar el dinero — la aprobación llegó con el envío en
        // camino. La libreta la conserva (el cierre no aplicó) y Avoqado se quedó con la declaración: se deja el rastro para medirlo.
        // El arreglo de fondo es del SERVIDOR (marcar la declaración contradicha cuando llega el cobro).
        // Codex r2 #3: sólo con dinero de ESTE intento (aprobación del banco, evidencia del servidor o el cobro ya registrado); un veto
        // por evidencia de otra terminal no es «llegó dinero de este intento».
        val fila = despues.row
        val dineroPropio = fila != null && (fila.hostApproved == true || fila.serverPaymentId != null ||
            fila.serverProcessorEvidence == PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED ||
            fila.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO)
        if (!cerrada && dineroPropio)
            Timber.e("🚨 [Libreta] Avoqado guardó «no se cobró» y en medio llegó dinero de este intento: la declaración quedó contradicha | attemptId=%s", attemptId)
        despues
    }

    /** D22: lo que pasó con el envío de la declaración revisada. */
    private sealed interface EnvioRevisado {
        /** F1: Avoqado la guardó FIRMADA: 2xx de ESTE intento, sin dinero ni veto, con el testimonio que se mandó y su gerente. */
        data class Firmado(val gerente: String, val porElGerente: Boolean = true) : EnvioRevisado
        /** Sin red, sin respuesta o 5xx: no se sabe si llegó. A se conserva; repetir manda la MISMA declaración. */
        data object SIN_RESPUESTA : EnvioRevisado
        /** Contestó sin el acuse firmado de ESTA declaración (otro 4xx, un servidor anterior, sin firmante): A se conserva. */
        data object NO_FIRMADO : EnvioRevisado
        /** 403 SUPERVISOR_AUTHORIZATION_REQUIRED: ese PIN no firma (de otro local, de un cajero o sin el permiso). */
        data object PIN_RECHAZADO : EnvioRevisado
        /** 409 POSITIVE_EVIDENCE_EXISTS: Avoqado tiene algo que impide liberarlo (genérico: no dice que sea dinero de ESTE intento). */
        data object HAY_DINERO : EnvioRevisado
        /** 401/403: Avoqado dice que esta sesión no puede declararlo. No se anota en ningún lado. */
        data object SIN_PERMISO : EnvioRevisado
        /** El 2xx trajo dinero o un veto: ya se guardó lo que trae; no se declara. */
        data class ConVeto(val resultado: Result) : EnvioRevisado
    }

    private suspend fun enviarDeclaracionRevisada(
        venueId: String, attemptId: String, row: PaymentAttemptEntity, staffId: String?, supervisorPin: String,
    ): EnvioRevisado {
        // UNA declaración lógica por intento, en reintentos, pantalla recreada y muerte del proceso; el monto sale de la libreta, así
        // que el cuerpo es idéntico cada vez y el servidor contesta el replay sin escribir otra vez.
        val resolutionId = UUID.nameUUIDFromBytes("tpv:checked:$venueId:$attemptId".toByteArray(Charsets.UTF_8)).toString()
        val response = try {
            api.resolveNoInstrument(
                venueId, attemptId,
                NoInstrumentResolutionRequest(
                    requestId = null, resolutionId = resolutionId, statement = "UNCHARGED_VERIFIED",
                    amountCents = row.amountCents, tipCents = row.tipCents, supervisorPin = supervisorPin,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EnvioRevisado.SIN_RESPUESTA
        }
        if (!response.isSuccessful) {
            val code = runCatching {
                com.google.gson.JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject.get("code")?.asString
            }.getOrNull()
            return when {
                // F1 bloque 7 (I4): el 409 es genérico (puede ser evidencia de OTRA terminal): impedimento, nunca «aprobado». Si no se
                // pudo escribir, la libreta lo recuerda y frena (como la evidencia).
                response.code() == 409 && code == "POSITIVE_EVIDENCE_EXISTS" -> {
                    if (ledger.marcarImpedimentoDelServidor(venueId, attemptId).isFailure) unsavedVetoes.add(attemptId)
                    EnvioRevisado.HAY_DINERO
                }
                // F1: con PIN, este 403 habla del PIN, no de la sesión: no se toca la lista de permisos de quien opera.
                response.code() == 403 && code == "SUPERVISOR_AUTHORIZATION_REQUIRED" -> EnvioRevisado.PIN_RECHAZADO
                response.code() == 401 || response.code() == 403 -> {
                    PermissionsRepository.invalidarListaTrasRechazo(storage, venueId, staffId)
                    EnvioRevisado.SIN_PERMISO
                }
                response.code() >= 500 -> EnvioRevisado.SIN_RESPUESTA
                else -> EnvioRevisado.NO_FIRMADO
            }
        }
        val body = response.body()
        // Un 2xx no basta: tiene que ser de ESTE intento, sin solicitud, y publicar el testimonio que se mandó.
        val declaracion = body?.attempt?.resolution
        val esLaNuestra = body?.success == true && body.attemptId == attemptId && body.attempt?.attemptId == attemptId &&
            body.requestId.isNullOrBlank() && declaracion != null &&
            runCatching { declaracion.get("kind")?.asString == "UNCHARGED_VERIFIED" && declaracion.get("id")?.asString == resolutionId }.getOrDefault(false)
        val money = LiberacionDelServidor.acreditaDinero(body?.attempt)
        val veto = LiberacionDelServidor.motivoDelVeto(body?.attempt)
        if (body?.attemptId == attemptId && body.attempt?.attemptId == attemptId && (money || veto != null)) {
            // Lo que trae la respuesta se guarda ANTES de devolver, aunque la pantalla se cierre.
            withContext(NonCancellable) {
                val moneySaved = !money || saveMoney(venueId, attemptId)
                if (veto != null && !ledger.marcarVetoDelServidor(venueId, attemptId, veto).getOrDefault(false)) unsavedVetoes.add(attemptId)
                if (moneySaved) VeredictoDeIntento.desdeConsultaS6(venueId, attemptId, body)?.let { ledger.aplicarVeredictoDelServidor(it) }
            }
            val after = inspect(venueId, attemptId, LOS_DOS).copy(serverAnswered = true)
            return EnvioRevisado.ConVeto(if (after.state == State.RECORDED && veto == null) after else after.copy(state = State.MONEY_EVIDENCE))
        }
        // F1 (Codex código r1 #3): además de ser la nuestra (intento, negocio en el id, declaración e importe), con quién la declaró:
        // el PIN del gerente, o la sesión de una declaración vieja que Avoqado ya guardó y repite tal cual.
        val mismoImporte = runCatching {
            declaracion?.get("amountCents")?.takeUnless { it.isJsonNull }?.asLong.let { it == null || it == row.amountCents } &&
                declaracion?.get("tipCents")?.takeUnless { it.isJsonNull }?.asLong.let { it == null || it == row.tipCents }
        }.getOrDefault(false)
        val quien = LiberacionDelServidor.quienDeclaroElAcuse(declaracion)
        // F1 bloque 7: con el comprobante del SERVIDOR, manda él: tiene que acreditar a una persona en ESTE intento, y la firma es de
        // ESTE gerente sólo si este POST la escribió (`alreadyStored = false`). Un replay (otro gerente con el mismo id, respuesta
        // perdida) es la declaración guardada, con su firmante y su hora: nunca «quedó tu firma».
        val comprobante = body?.declarationReceipt
        if (comprobante != null && !comprobante.acreditaPersona(attemptId, null)) return EnvioRevisado.NO_FIRMADO
        val porElGerente = if (comprobante != null) comprobante.alreadyStored == false && comprobante.by == "SUPERVISOR_PIN"
            else runCatching { declaracion?.get("by")?.asString == "SUPERVISOR_PIN" }.getOrDefault(false)
        return if (esLaNuestra && mismoImporte && quien != null) EnvioRevisado.Firmado(quien, porElGerente) else EnvioRevisado.NO_FIRMADO
    }

    /**
     * La misma consulta de [declareChecked], ANTES de ofrecer confirmar (Codex plan r1 #2): el banner no ofrece lo que no se puede cumplir.
     * PENDING = se puede ofrecer si [PaymentAttemptEntity.serverAnsweredAt] ya existe; UNAVAILABLE = la llamada puede seguir viva en este proceso.
     */
    // F1: sin el gate del permiso de la sesión — la firma la decide Avoqado con el PIN del gerente.
    suspend fun prepareChecked(venueId: String, attemptId: String): Result = mutex.withLock {
        val reading = consultLocked(venueId, attemptId, LOS_DOS)
        val row = reading.row
        if (reading.state == State.PENDING && (row == null || !ledger.puedeDeclararseEnDuda(row, venueId))) reading.copy(state = State.UNAVAILABLE)
        else reading
    }

    private suspend fun saveMoney(venueId: String, attemptId: String): Boolean {
        val saved = ledger.marcarEvidenciaPositivaDelServidor(venueId, attemptId).getOrDefault(false)
        if (!saved) unsavedMoney.add(attemptId)
        return saved
    }

    private suspend fun scopedRow(venueId: String, attemptId: String, procesadores: Set<String> = SOLO_PAX): PaymentAttemptEntity? =
        ledger.leerIntento(attemptId)?.takeIf {
            storage.getVenueId() == venueId && it.venueId == venueId && it.attemptId == attemptId &&
                it.processor in procesadores && it.kind == PaymentAttemptEntity.KIND_SALE && !it.legacyShadow
        }

    private suspend fun inspect(venueId: String, attemptId: String, procesadores: Set<String> = SOLO_PAX): Result =
        classify(venueId, attemptId, scopedRow(venueId, attemptId, procesadores), procesadores)

    private fun classify(venueId: String, attemptId: String, row: PaymentAttemptEntity?, procesadores: Set<String> = SOLO_PAX): Result {
        if (row == null || storage.getVenueId() != venueId || row.venueId != venueId || row.attemptId != attemptId ||
            row.processor !in procesadores || row.kind != PaymentAttemptEntity.KIND_SALE || row.legacyShadow)
            return Result(State.UNAVAILABLE)
        val state = when {
            attemptId in unsavedVetoes || ledger.tieneEvidenciaSinGuardar(attemptId) || row.serverVeto != null -> State.MONEY_EVIDENCE
            row.state == PaymentAttemptEntity.STATE_REGISTRADO && row.serverPaymentId != null -> {
                unsavedMoney.remove(attemptId)
                State.RECORDED
            }
            attemptId in unsavedMoney -> State.MONEY_EVIDENCE
            // D5 (Codex r2 #3): el banco aprobó y el chip rechazó el cierre, pero la duda no se pudo escribir: la fila todavía dice
            // «aprobada». No es evidencia de cobro: es la misma duda. Las declaraciones siguen exigiendo INDETERMINADO en su CAS.
            // Codex r3 #2: con el MOTIVO, para que la pantalla diga lo que pasó y no ofrezca «no presentó tarjeta» (copia sólo para mostrar).
            ledger.chipRechazoPendienteDeEscribir(attemptId) && row.serverProcessorEvidence == null &&
                row.serverOutcome == null && row.serverPaymentId == null ->
                return Result(State.PENDING, row.copy(lastError = PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION))
            row.hostApproved == true || row.serverProcessorEvidence == "APPROVED" || row.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO -> State.MONEY_EVIDENCE
            // RELEASED_NO_EVIDENCE sólo lo tiene la Nexgo (su CAS lo exige): la pantalla de la PAX no cambia.
            row.state == PaymentAttemptEntity.STATE_DESCARTADA &&
                (row.serverOutcome == PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT || row.serverOutcome == PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE ||
                    row.lastError?.startsWith("declarado_sin_cobro:") == true) -> State.RELEASED
            row.state == PaymentAttemptEntity.STATE_INDETERMINADO -> State.PENDING
            else -> State.UNAVAILABLE
        }
        return Result(state, row)
    }

    private companion object {
        val SOLO_PAX = setOf(PaymentAttemptEntity.PROCESSOR_BLUMON)
        val LOS_DOS = setOf(PaymentAttemptEntity.PROCESSOR_BLUMON, PaymentAttemptEntity.PROCESSOR_ANGELPAY)
    }
}
