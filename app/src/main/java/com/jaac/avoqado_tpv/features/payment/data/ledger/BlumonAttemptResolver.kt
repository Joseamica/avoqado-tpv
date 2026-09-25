package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    enum class State { PENDING, RELEASED, RECORDED, MONEY_EVIDENCE, PERMISSION_REQUIRED, UNAVAILABLE }
    data class Result(
        val state: State,
        val row: PaymentAttemptEntity? = null,
        val serverAnswered: Boolean = false,
        val resultJson: String? = null,
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

    private suspend fun consultLocked(venueId: String, attemptId: String): Result {
        if (scopedRow(venueId, attemptId) == null) return Result(State.UNAVAILABLE)
        val reading = try {
            recovery.recoverOne(venueId, attemptId, System.currentTimeMillis(), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return inspect(venueId, attemptId)
        }
        if (reading.vetoDelServidor != null && ledger.leerIntento(attemptId)?.serverVeto == null) unsavedVetoes.add(attemptId)
        val result = inspect(venueId, attemptId).copy(serverAnswered = reading.servidorContesto, resultJson = reading.bandejaResueltaJson)
        // The response wins even when a durable write failed and the row still says RELEASED.
        return if (reading.evidenciaPositivaSinRegistro || reading.vetoDelServidor != null)
            result.copy(state = State.MONEY_EVIDENCE)
        else result
    }

    suspend fun declare(venueId: String, attemptId: String): Result = mutex.withLock {
        if (!hasPermission()) return@withLock Result(State.PERMISSION_REQUIRED)
        val before = inspect(venueId, attemptId)
        if (before.state != State.PENDING) return@withLock before
        val row = before.row ?: return@withLock Result(State.UNAVAILABLE)
        if (!ledger.puedeConciliarBlumon(row, venueId)) return@withLock before.copy(state = State.UNAVAILABLE)
        val staffId = storage.getStaffId()
        // One logical declaration across retries, screen recreation and process death. No extra database migration.
        val resolutionId = UUID.nameUUIDFromBytes("pax:no-instrument:$venueId:$attemptId".toByteArray(Charsets.UTF_8)).toString()
        val response = try {
            api.resolveNoInstrument(venueId, attemptId, NoInstrumentResolutionRequest(row.terminalPaymentRequestId, resolutionId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock inspect(venueId, attemptId)
        }
        if (!response.isSuccessful) {
            val code = runCatching {
                com.google.gson.JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject.get("code")?.asString
            }.getOrNull()
            if (response.code() == 409 && code == "POSITIVE_EVIDENCE_EXISTS") {
                saveMoney(venueId, attemptId)
                return@withLock inspect(venueId, attemptId).copy(state = State.MONEY_EVIDENCE, serverAnswered = true)
            }
            if (response.code() == 401 || response.code() == 403) {
                PermissionsRepository.invalidarListaTrasRechazo(storage, venueId, staffId)
                return@withLock before.copy(state = State.PERMISSION_REQUIRED, serverAnswered = true)
            }
            return@withLock before.copy(serverAnswered = true)
        }
        val body = response.body()
        // A 2xx alone is not evidence for this row. Check both identities and the original POS request.
        if (body?.success != true || body.attemptId != attemptId || body.attempt?.attemptId != attemptId ||
            body.requestId != row.terminalPaymentRequestId) return@withLock before.copy(serverAnswered = true)
        val money = LiberacionDelServidor.acreditaDinero(body.attempt)
        val veto = LiberacionDelServidor.motivoDelVeto(body.attempt)
        var resultJson: String? = null
        withContext(NonCancellable) {
            // Save all evidence from THIS response before any possible release or cancelled-screen return.
            val moneySaved = !money || saveMoney(venueId, attemptId)
            if (veto != null && !ledger.marcarVetoDelServidor(venueId, attemptId, veto).getOrDefault(false)) unsavedVetoes.add(attemptId)
            if (moneySaved) VeredictoDeIntento.desdeConsultaS6(venueId, attemptId, body)?.let {
                resultJson = ledger.aplicarVeredictoDelServidor(it).getOrNull()?.bandejaResueltaJson
            }
            if (!money && veto == null) {
                val kind = runCatching { body.attempt?.resolution?.get("kind")?.asString }.getOrNull()
                if (kind == null || kind == "NO_INSTRUMENT_PRESENTED") {
                    ledger.aplicarLiberacionDelServidor(LiberacionDelServidor(venueId, attemptId, row.terminalPaymentRequestId, "OPERATOR_RECONCILED"))
                }
            }
        }
        val after = inspect(venueId, attemptId).copy(serverAnswered = true, resultJson = resultJson)
        // A successful POST is not a successful local CAS. Only the freshly read row can announce release.
        if (veto != null || (money && after.state != State.RECORDED)) after.copy(state = State.MONEY_EVIDENCE) else after
    }

    /**
     * El cajero deja constancia de que revisó la terminal y no se cobró, con o sin red (founder, 25-sep; antes sólo sin red
     * y sólo gerencia). Sólo cobros LOCALES: los del POS tienen su propio camino. Primero se consulta a Avoqado; con
     * evidencia de dinero no se declara, y la libreta exige que Avoqado haya contestado alguna vez por ese cobro. Queda
     * quién y cuándo; si después aparece el cobro, la terminal avisa.
     */
    suspend fun declareChecked(venueId: String, attemptId: String): Result = mutex.withLock {
        if (!hasCheckPermission()) return@withLock Result(State.PERMISSION_REQUIRED)
        val reading = consultLocked(venueId, attemptId)
        if (reading.state != State.PENDING) return@withLock reading
        val row = reading.row ?: return@withLock Result(State.UNAVAILABLE)
        if (row.terminalPaymentRequestId != null || row.serverAnsweredAt == null || !ledger.puedeConciliarBlumon(row, venueId)) return@withLock reading
        // Recheck session and effective permission AFTER the network suspension as well as in the UI.
        if (!hasCheckPermission()) return@withLock reading.copy(state = State.PERMISSION_REQUIRED)
        ledger.declararSinCobroLocal(attemptId, venueId, storage.getStaffId())
        inspect(venueId, attemptId).copy(resultJson = reading.resultJson)
    }

    private suspend fun saveMoney(venueId: String, attemptId: String): Boolean {
        val saved = ledger.marcarEvidenciaPositivaDelServidor(venueId, attemptId).getOrDefault(false)
        if (!saved) unsavedMoney.add(attemptId)
        return saved
    }

    private suspend fun scopedRow(venueId: String, attemptId: String): PaymentAttemptEntity? =
        ledger.leerIntento(attemptId)?.takeIf {
            storage.getVenueId() == venueId && it.venueId == venueId && it.attemptId == attemptId &&
                it.processor == PaymentAttemptEntity.PROCESSOR_BLUMON && it.kind == PaymentAttemptEntity.KIND_SALE && !it.legacyShadow
        }

    private suspend fun inspect(venueId: String, attemptId: String): Result =
        classify(venueId, attemptId, scopedRow(venueId, attemptId))

    private fun classify(venueId: String, attemptId: String, row: PaymentAttemptEntity?): Result {
        if (row == null || storage.getVenueId() != venueId || row.venueId != venueId || row.attemptId != attemptId ||
            row.processor != PaymentAttemptEntity.PROCESSOR_BLUMON || row.kind != PaymentAttemptEntity.KIND_SALE || row.legacyShadow)
            return Result(State.UNAVAILABLE)
        val state = when {
            attemptId in unsavedVetoes || ledger.tieneEvidenciaSinGuardar(attemptId) || row.serverVeto != null -> State.MONEY_EVIDENCE
            row.state == PaymentAttemptEntity.STATE_REGISTRADO && row.serverPaymentId != null -> {
                unsavedMoney.remove(attemptId)
                State.RECORDED
            }
            attemptId in unsavedMoney -> State.MONEY_EVIDENCE
            row.hostApproved == true || row.serverProcessorEvidence == "APPROVED" || row.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO -> State.MONEY_EVIDENCE
            row.state == PaymentAttemptEntity.STATE_DESCARTADA &&
                (row.serverOutcome == PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT || row.lastError?.startsWith("declarado_sin_cobro:") == true) -> State.RELEASED
            row.state == PaymentAttemptEntity.STATE_INDETERMINADO -> State.PENDING
            else -> State.UNAVAILABLE
        }
        return Result(state, row)
    }
}
