package com.jaac.avoqado_tpv.features.payment.domain.usecase

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates AngelPay refund / cancellation flows end-to-end.
 *
 * **Why a dedicated UseCase instead of using RecordRefundUseCase:**
 * [RecordRefundUseCase] enforces Blumon-only invariants (`originalOperationNumber > 0`,
 * non-blank `blumonSerialNumber`) that do not apply to AngelPay refunds.
 * This UseCase bypasses those checks and goes straight to [RefundRecorder] which only
 * validates `merchantAccountId`. Processor tag `"angelpay"` is sent so the backend
 * persists the refund row with the correct processor — keeping reports/reconciliation accurate.
 *
 * **Extracted from:** `AppNavigation.kt` (was private functions `recordAngelPayRefundInBackend`
 * + `processAngelPayPostOperationDirect` + ~12 private helper functions, lines 2655–3491).
 * Moving them here keeps business logic out of the navigation graph.
 *
 * **Audit-trail caveat**: [processSdkRefund] currently returns only the user-facing result
 * String, not the raw SDK authorizationCode/reference. As a placeholder [recordInBackend]
 * reuses the original payment's reference and stamps auth as `"ANGELPAY_SDK"`.
 * TODO: plumb the SDK result back up for exact audit data.
 */
@Singleton
class RecordAngelPayRefundUseCase @Inject constructor(
    private val refundRecorder: RefundRecorder,
    private val authRepository: AuthRepository,
    private val postOperationsAdapterFactory: PostOperationsAdapterFactory,
    /** 💸 La cola durable de REEMBOLSOS (Fase 1, Task 7): un fallo del registro ya no se pierde. */
    private val refundQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository,
    /** 📒 La libreta write-ahead, ABIERTA antes del SDK (auditoría de Codex F1): igual que en la PAX. */
    private val paymentAttemptLedger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger,
) {

    /** Lo que devuelve el SDK cuando aprueba: el mensaje para el cajero y la llave que ya nació para este intento. */
    data class AngelPayRefundApproval(val message: String, val idempotencyKey: String)

    /**
     * 🛡️ Se llama ANTES del SDK (auditoría de Codex F2). Devuelve el motivo si el reembolso no podría
     * registrarse después — porque cuando `recordInBackend` corre, el dinero YA salió y ya no hay
     * forma buena de rechazarlo. Null = adelante.
     */
    fun validateBeforeSdk(paymentVenueId: String, merchantAccountId: String): String? = when {
        paymentVenueId.isBlank() -> "Este pago no trae sucursal y no podría registrarse la devolución."
        merchantAccountId.isBlank() -> "Este pago no trae cuenta de cobro y no podría registrarse la devolución."
        else -> null
    }

    /**
     * Executes the AngelPay SDK cancellation or refund post-operation.
     *
     * Resolves the target transaction from history (using [paymentReference]), decides
     * cancellation vs refund based on whether the transaction date is today, then
     * executes the operation with multi-reference fallback retries.
     *
     * ⚠️ **FULL refunds only.** The SDK post-operations execute against
     * `transaction.amount` — the ORIGINAL full sale — and ignore any smaller
     * amount the operator typed. Without the guard below, a "partial" refund
     * returns the FULL amount to the cardholder while Avoqado books only the
     * partial ([recordInBackend]), silently misrouting the difference.
     *
     * @param paymentReference Original payment reference number stored at charge time.
     * @param createdAt Instant when the original payment was created (determines cancel vs refund).
     * @param requestedReason Refund reason from the operator.
     * @param appContext Application context (needed to access the AngelPay SDK local SQLite DB).
     * @param requestedAmount Amount the operator asked to refund (sale portion, no tip).
     * @param originalAmount Original payment sale amount (no tip).
     * @param alreadyRefundedAmount Amount already refunded on this payment.
     * @return Success with a user-facing result message, or Failure with a localized error.
     */
    suspend fun processSdkRefund(
        paymentReference: String,
        createdAt: Instant,
        requestedReason: RefundReason,
        appContext: Context,
        requestedAmount: BigDecimal,
        originalAmount: BigDecimal,
        alreadyRefundedAmount: BigDecimal,
        originalPaymentId: String,
        paymentVenueId: String,
    ): Result<AngelPayRefundApproval> {
        if (alreadyRefundedAmount > BigDecimal.ZERO || requestedAmount.compareTo(originalAmount) != 0) {
            Timber.w(
                "⛔ [AngelPay Direct Refund] Partial refund blocked | requested=%s original=%s alreadyRefunded=%s",
                requestedAmount.toPlainString(),
                originalAmount.toPlainString(),
                alreadyRefundedAmount.toPlainString(),
            )
            return Result.failure(IllegalStateException(PARTIAL_REFUND_UNSUPPORTED_MESSAGE))
        }

        // 💸 CANDADO (auditoría de Codex F7): si este pago ya tiene una devolución aprobada por el SDK y sin
        // registrar en Avoqado, NO se lanza otra — el servidor aún lo ve reembolsable y una segunda llave
        // devolvería el dinero dos veces.
        val sinRegistrar = runCatching { refundQueueRepository.unresolvedForPayment(originalPaymentId) }.getOrDefault(emptyList())
        if (sinRegistrar.isNotEmpty()) {
            Timber.w("⛔ [AngelPay Direct Refund] Bloqueado: el pago %s ya tiene %s devolución(es) sin registrar", originalPaymentId, sinRegistrar.size)
            return Result.failure(IllegalStateException(mensajeDelCandado(sinRegistrar.first())))
        }

        // 🔑 La llave nace AQUÍ, ANTES del SDK, y viaja con la aprobación hasta `recordInBackend`: es la
        // misma en la libreta, en la fila write-ahead y en el POST (Fase 0 del servidor deduplica con ella).
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        // 📒 [Libreta] write-ahead ANTES de que corra el SDK (auditoría F1): si el proceso muere entre la
        // aprobación y la fila de la cola, queda evidencia con la MISMA llave. Nunca bloquea el reembolso.
        runCatching {
            paymentAttemptLedger.openAttempt(
                attemptId = idempotencyKey,
                venueId = paymentVenueId,
                processor = "angelpay",
                amountCents = requestedAmount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                tipCents = 0L,
                recordingRoute = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.ROUTE_REFUND,
                contextJson = "{\"originalPaymentId\":\"$originalPaymentId\",\"reference\":\"$paymentReference\",\"processor\":\"angelpay\"}",
                kind = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.KIND_REFUND,
            )
        }.onFailure { Timber.w(it, "📒 [AngelPay Direct Refund] La libreta no pudo abrir el intento (no bloquea)") }

        val adapter = postOperationsAdapterFactory.get(ProcessorType.ANGELPAY)
        val zone = ZoneId.of("America/Mexico_City")
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val operationDate = createdAt.atZone(zone).toLocalDate()
        val startDate = operationDate.minusDays(1).format(formatter)
        val endDate = LocalDate.now(zone).plusDays(1).format(formatter)

        Timber.i(
            "🔶 [AngelPay Direct Refund] Resolving reference=%s reason=%s window=%s..%s",
            paymentReference,
            requestedReason.name,
            startDate,
            endDate,
        )

        val history = adapter.getTransactionHistory(
            TransactionHistoryQuery(
                startDate = startDate,
                endDate = endDate,
                reference = paymentReference,
            )
        ).getOrElse { error ->
            return Result.failure(
                IllegalStateException(
                    "No se pudo consultar la transacción AngelPay: ${error.message}",
                    error,
                )
            )
        }

        val candidates = history.filter {
            it.reference.equals(paymentReference, ignoreCase = true)
        }
        Timber.i(
            "🔶 [AngelPay Direct Refund] Candidate count for ref=%s: %s",
            paymentReference,
            candidates.size,
        )

        val target = candidates
            .sortedWith(
                compareByDescending<UnifiedTransaction> {
                    it.operationType?.contains("SALE", ignoreCase = true) == true
                }.thenByDescending {
                    isAngelPayApprovedStatus(it.status)
                }
            )
            .firstOrNull()
            ?: return Result.failure(
                IllegalStateException("No se encontró la transacción AngelPay con referencia $paymentReference")
            )
        Timber.i(
            "🔶 [AngelPay Direct Refund] Selected target ref=%s folio=%s postOpRef=%s opType=%s status=%s creation=%s date=%s auth=%s",
            target.reference,
            target.folio,
            target.postOperationReference ?: "-",
            target.operationType ?: "-",
            target.status,
            target.creationDate,
            target.date ?: "-",
            target.authorizationCode,
        )
        val sdkReferenceCandidates = resolveAngelPaySdkReferenceCandidates(
            appContext = appContext,
            transaction = target,
        )
        val historyReferenceCandidates = resolveAngelPayHistoryReferenceCandidates(
            adapterFactory = postOperationsAdapterFactory,
            target = target,
            startDate = startDate,
            endDate = endDate,
            zone = zone,
        )

        val targetDate = parseAngelPayTransactionDate(target.creationDate, zone) ?: operationDate
        val today = LocalDate.now(zone)
        val useCancellation = targetDate == today
        val operationLabel = if (useCancellation) "cancelación" else "devolución"

        Timber.i(
            "🔶 [AngelPay Direct Refund] Executing %s ref=%s txDate=%s today=%s",
            operationLabel,
            target.reference,
            targetDate,
            today,
        )

        suspend fun runCancel(tx: UnifiedTransaction, isManual: Boolean): Result<PostOperationResult> {
            return adapter.cancelTransaction(transaction = tx, isManual = isManual)
        }

        data class CancellationResolution(
            val result: PostOperationResult,
            val attemptedReferences: List<String>,
            val hadInvalidReference: Boolean,
        )

        suspend fun attemptCancellationWithFallbacks(
            tx: UnifiedTransaction,
            sdkCandidates: List<String>,
            historyCandidates: List<String>,
        ): Result<CancellationResolution> {
            val attempts = mutableListOf<String>()
            var hadInvalidReference = false

            suspend fun attempt(reference: String, isManual: Boolean): PostOperationResult {
                attempts += "$reference|manual=$isManual"
                val txToUse = if (reference.equals(tx.reference, ignoreCase = true)) {
                    tx
                } else {
                    tx.copy(reference = reference)
                }
                val result = runCancel(tx = txToUse, isManual = isManual).getOrElse { error ->
                    throw error
                }
                if (looksLikeInvalidReference(result.message)) {
                    hadInvalidReference = true
                }
                return result
            }

            val first = runCatching { attempt(reference = tx.reference, isManual = false) }
                .getOrElse { error -> return Result.failure(error) }
            if (first.approved) {
                return Result.success(
                    CancellationResolution(
                        result = first,
                        attemptedReferences = attempts.toList(),
                        hadInvalidReference = hadInvalidReference,
                    )
                )
            }

            if (!looksLikeInvalidReference(first.message)) {
                return Result.success(
                    CancellationResolution(
                        result = first,
                        attemptedReferences = attempts.toList(),
                        hadInvalidReference = hadInvalidReference,
                    )
                )
            }

            Timber.w(
                "⚠️ [AngelPay Direct Refund] Retrying cancel as manual with same reference=%s",
                tx.reference,
            )
            val manualSameRef = runCatching { attempt(reference = tx.reference, isManual = true) }
                .getOrElse { error -> return Result.failure(error) }
            if (manualSameRef.approved) {
                return Result.success(
                    CancellationResolution(
                        result = manualSameRef,
                        attemptedReferences = attempts.toList(),
                        hadInvalidReference = hadInvalidReference,
                    )
                )
            }

            val alternativeReferences = distinctCaseInsensitive(
                buildList<String> {
                    addAll(sdkCandidates)
                    addAll(historyCandidates)
                    tx.folio.takeIf { it.isNotBlank() }?.let(::add)
                    tx.postOperationReference?.takeIf { it.isNotBlank() }?.let(::add)
                    tx.authorizationCode.takeIf { it.isNotBlank() }?.let(::add)
                    tx.postOperationAuthorization?.takeIf { it.isNotBlank() }?.let(::add)
                }.filterNot { it.equals(tx.reference, ignoreCase = true) }
                    .flatMap(::expandAngelPayReferenceVariants)
            )

            for (altReference in alternativeReferences) {
                Timber.w(
                    "⚠️ [AngelPay Direct Refund] Retrying cancel with alternate reference=%s (orig=%s)",
                    altReference,
                    tx.reference,
                )

                val altAttempt = runCatching { attempt(reference = altReference, isManual = false) }
                    .getOrElse { error -> return Result.failure(error) }
                if (altAttempt.approved) {
                    return Result.success(
                        CancellationResolution(
                            result = altAttempt,
                            attemptedReferences = attempts.toList(),
                            hadInvalidReference = hadInvalidReference,
                        )
                    )
                }

                Timber.w(
                    "⚠️ [AngelPay Direct Refund] Retrying manual cancel with alternate reference=%s",
                    altReference,
                )
                val altManualAttempt = runCatching { attempt(reference = altReference, isManual = true) }
                    .getOrElse { error -> return Result.failure(error) }
                if (altManualAttempt.approved) {
                    return Result.success(
                        CancellationResolution(
                            result = altManualAttempt,
                            attemptedReferences = attempts.toList(),
                            hadInvalidReference = hadInvalidReference,
                        )
                    )
                }
            }

            return Result.success(
                CancellationResolution(
                    result = manualSameRef,
                    attemptedReferences = attempts.toList(),
                    hadInvalidReference = hadInvalidReference,
                )
            )
        }

        data class RefundResolution(
            val result: PostOperationResult,
            val attemptedReferences: List<String>,
            val hadInvalidReference: Boolean,
        )

        suspend fun attemptRefundWithFallbacks(
            tx: UnifiedTransaction,
            sdkCandidates: List<String>,
            historyCandidates: List<String>,
        ): Result<RefundResolution> {
            val attempts = mutableListOf<String>()
            var hadInvalidReference = false

            suspend fun attempt(reference: String, isManual: Boolean): PostOperationResult {
                attempts += "$reference|manual=$isManual"
                val txToUse = if (reference.equals(tx.reference, ignoreCase = true)) {
                    tx
                } else {
                    tx.copy(reference = reference)
                }
                val result = adapter.refundTransaction(transaction = txToUse, isManual = isManual)
                    .getOrElse { error -> throw error }
                if (looksLikeInvalidReference(result.message)) {
                    hadInvalidReference = true
                }
                return result
            }

            val first = runCatching { attempt(reference = tx.reference, isManual = false) }
                .getOrElse { error -> return Result.failure(error) }
            if (first.approved) {
                return Result.success(
                    RefundResolution(
                        result = first,
                        attemptedReferences = attempts.toList(),
                        hadInvalidReference = hadInvalidReference,
                    )
                )
            }

            if (!looksLikeInvalidReference(first.message)) {
                return Result.success(
                    RefundResolution(
                        result = first,
                        attemptedReferences = attempts.toList(),
                        hadInvalidReference = hadInvalidReference,
                    )
                )
            }

            Timber.w(
                "⚠️ [AngelPay Direct Refund] Retrying refund as manual with same reference=%s",
                tx.reference,
            )
            val manualSameRef = runCatching { attempt(reference = tx.reference, isManual = true) }
                .getOrElse { error -> return Result.failure(error) }
            if (manualSameRef.approved) {
                return Result.success(
                    RefundResolution(
                        result = manualSameRef,
                        attemptedReferences = attempts.toList(),
                        hadInvalidReference = hadInvalidReference,
                    )
                )
            }

            val alternativeReferences = distinctCaseInsensitive(
                buildList<String> {
                    addAll(sdkCandidates)
                    addAll(historyCandidates)
                    tx.folio.takeIf { it.isNotBlank() }?.let(::add)
                    tx.postOperationReference?.takeIf { it.isNotBlank() }?.let(::add)
                    tx.authorizationCode.takeIf { it.isNotBlank() }?.let(::add)
                    tx.postOperationAuthorization?.takeIf { it.isNotBlank() }?.let(::add)
                }.filterNot { it.equals(tx.reference, ignoreCase = true) }
                    .flatMap(::expandAngelPayReferenceVariants)
            )

            for (altReference in alternativeReferences) {
                Timber.w(
                    "⚠️ [AngelPay Direct Refund] Retrying refund with alternate reference=%s (orig=%s)",
                    altReference,
                    tx.reference,
                )

                val altAttempt = runCatching { attempt(reference = altReference, isManual = false) }
                    .getOrElse { error -> return Result.failure(error) }
                if (altAttempt.approved) {
                    return Result.success(
                        RefundResolution(
                            result = altAttempt,
                            attemptedReferences = attempts.toList(),
                            hadInvalidReference = hadInvalidReference,
                        )
                    )
                }

                Timber.w(
                    "⚠️ [AngelPay Direct Refund] Retrying manual refund with alternate reference=%s",
                    altReference,
                )
                val altManualAttempt = runCatching { attempt(reference = altReference, isManual = true) }
                    .getOrElse { error -> return Result.failure(error) }
                if (altManualAttempt.approved) {
                    return Result.success(
                        RefundResolution(
                            result = altManualAttempt,
                            attemptedReferences = attempts.toList(),
                            hadInvalidReference = hadInvalidReference,
                        )
                    )
                }
            }

            return Result.success(
                RefundResolution(
                    result = manualSameRef,
                    attemptedReferences = attempts.toList(),
                    hadInvalidReference = hadInvalidReference,
                )
            )
        }

        // 📒 AUTORIZANDO justo antes de tocar el SDK: a partir de aquí el dinero puede moverse.
        runCatching { paymentAttemptLedger.markAuthorizing(idempotencyKey) }

        val cancellationResolution = if (useCancellation) {
            attemptCancellationWithFallbacks(
                tx = target,
                sdkCandidates = sdkReferenceCandidates,
                historyCandidates = historyReferenceCandidates,
            )
        } else null

        val refundResolution = if (useCancellation) {
            null
        } else {
            attemptRefundWithFallbacks(
                tx = target,
                sdkCandidates = sdkReferenceCandidates,
                historyCandidates = historyReferenceCandidates,
            )
        }

        val firstAttempt = if (useCancellation) {
            cancellationResolution?.map { it.result } ?: Result.failure(
                IllegalStateException("No se pudo preparar cancelación AngelPay")
            )
        } else {
            refundResolution?.map { it.result } ?: Result.failure(
                IllegalStateException("No se pudo preparar devolución AngelPay")
            )
        }

        val firstResult = firstAttempt.getOrElse { error ->
            return Result.failure(
                IllegalStateException(
                    "Falló $operationLabel AngelPay: ${error.message}",
                    error,
                )
            )
        }

        if (firstResult.approved) {
            return aprobada(idempotencyKey, "$operationLabel aprobada${firstResult.reference?.let { " (ref: $it)" } ?: ""}", firstResult.reference ?: paymentReference)
        }

        // Same-day cancellation may fail for some processor responses.
        // If cancellation is rejected, try refund before surfacing error.
        if (useCancellation) {
            val cancelMeta = cancellationResolution?.getOrNull()
            if (cancelMeta?.hadInvalidReference == true) {
                val attempted = cancelMeta.attemptedReferences.joinToString(separator = ", ")
                Timber.w(
                    "⚠️ [AngelPay Direct Refund] Invalid reference after attempts=%s",
                    attempted,
                )
                return Result.failure(
                    IllegalStateException(
                        "Cancelación rechazada (${firstResult.message ?: "sin detalle"}). " +
                            "No se encontró una referencia válida en AngelPay para este pago."
                    )
                )
            }

            Timber.w(
                "⚠️ [AngelPay Direct Refund] Cancellation rejected ref=%s msg=%s. Trying refund fallback...",
                target.reference,
                firstResult.message ?: "-",
            )

            val fallback = adapter.refundTransaction(target).getOrElse { error ->
                return Result.failure(
                    IllegalStateException(
                        "Cancelación rechazada (${firstResult.message ?: "sin detalle"}) y devolución falló: ${error.message}",
                        error,
                    )
                )
            }

            if (fallback.approved) {
                return aprobada(idempotencyKey, "devolución aprobada${fallback.reference?.let { " (ref: $it)" } ?: ""}", fallback.reference ?: paymentReference)
            }

            return Result.failure(
                IllegalStateException(
                    "Cancelación rechazada (${firstResult.message ?: "sin detalle"}) y devolución rechazada (${fallback.message ?: "sin detalle"})"
                )
            )
        }

        val refundMeta = refundResolution?.getOrNull()
        if (refundMeta?.hadInvalidReference == true) {
            val attempted = refundMeta.attemptedReferences.joinToString(separator = ", ")
            Timber.w(
                "⚠️ [AngelPay Direct Refund] Invalid reference after refund attempts=%s",
                attempted,
            )
            return Result.failure(
                IllegalStateException(
                    "Devolución rechazada (${firstResult.message ?: "sin detalle"}). " +
                        "No se encontró una referencia válida en AngelPay para este pago."
                )
            )
        }

        return Result.failure(
            IllegalStateException("$operationLabel rechazada: ${firstResult.message ?: "sin detalle"}")
        )
    }

    /** El SDK aprobó: la libreta lo anota (HOST_RESPONDIO) y la llave viaja con la aprobación. */
    private suspend fun aprobada(idempotencyKey: String, message: String, reference: String): Result<AngelPayRefundApproval> {
        runCatching {
            paymentAttemptLedger.markHostResponded(
                attemptId = idempotencyKey,
                approved = true,
                operationId = null,
                referenceNumber = reference,
                authCode = null,
            )
        }.onFailure { Timber.w(it, "📒 [AngelPay Direct Refund] La libreta no pudo anotar la aprobación (no bloquea)") }
        return Result.success(AngelPayRefundApproval(message = message, idempotencyKey = idempotencyKey))
    }

    /**
     * Records an AngelPay refund in the Avoqado backend after the SDK cancellation/refund
     * returned success. Mirrors what Blumon/PAX refunds do automatically via
     * `RecordRefundUseCase` from `PaymentScreen` — AngelPay's refund flow lives outside
     * `PaymentScreen` so the backend POST is wired here instead.
     *
     * **Important**: this is best-effort — if the backend call fails, the SDK refund is NOT
     * rolled back (cardholder already got their money). The caller should surface a warning
     * toast so the operator can follow up.
     *
     * Sends `processor = "angelpay"` so the backend persists the refund row with
     * `Payment.processor = "angelpay"` (not the legacy default `"blumon"`), keeping
     * reports/reconciliation accurate.
     *
     * Bypasses [RecordRefundUseCase] because that use case enforces Blumon-only invariants
     * (`originalOperationNumber > 0`, non-blank `blumonSerialNumber`) that don't apply to
     * AngelPay. Goes straight to [RefundRecorder] which only validates `merchantAccountId`.
     *
     * @return Success with Unit, or Failure with a localized error message.
     */
    suspend fun recordInBackend(
        paymentId: String,
        orderId: String?,
        paymentVenueId: String,
        merchantAccountId: String,
        originalTotalAmount: BigDecimal,
        refundAmount: BigDecimal,
        refundReason: RefundReason,
        sdkReferenceNumber: String,
        tipRefundCents: Int?,
        refundedAmount: BigDecimal,
        idempotencyKey: String,
    ): Result<Unit> {
        // 🔴 Sin sesión NO se descarta: cuando esto corre, el SDK YA devolvió el dinero. Antes se
        // hacía `return failure` y el reembolso se perdía sin rastro. Ahora se encola igual (con el
        // staff en blanco: al reproducir, el servidor atribuye al actor autenticado) y sólo se omite la red.
        val staffId = authRepository.getStaffId().orEmpty()
        val hasSession = staffId.isNotBlank()

        // Inalcanzables tras `validateBeforeSdk` (se comprueba ANTES del SDK); si aun así llegan aquí, se
        // grita: el dinero ya salió y esto es una devolución sin registro.
        if (paymentVenueId.isBlank() || merchantAccountId.isBlank()) {
            Timber.e("💸🔴 [AngelPay Direct Refund] Devolución aprobada SIN datos para registrarla | venue=%s merchant=%s key=%s", paymentVenueId, merchantAccountId, idempotencyKey)
            return Result.failure(RefundLostException(idempotencyKey, IllegalStateException("datos del pago incompletos"), IllegalStateException("sin venue/merchant no hay fila que encolar")))
        }

        // Determine whether this refund covers the whole remaining balance or
        // a portion of it (drives the backend's `isPartialRefund` flag).
        val remainingRefundable = (originalTotalAmount - refundedAmount).coerceAtLeast(BigDecimal.ZERO)
        val isPartial = refundAmount < remainingRefundable

        // 🔑 La llave llegó con la aprobación del SDK (nació ANTES de tocarlo): es la MISMA en la libreta,
        // en la fila write-ahead y en el contexto que viaja al servidor.

        val context = PaymentContext.RefundPayment(
            venueId = paymentVenueId,
            staffId = staffId,
            shiftId = null, // AngelPay flow does not currently use shifts (Blumon does).
            amount = refundAmount,
            tip = BigDecimal.ZERO, // Tip-split is conveyed via `tipRefundCents` below.
            // Blumon-specific fields — backend tolerates blank for processor="angelpay".
            blumonSerialNumber = "",
            merchantAccountId = merchantAccountId,
            originalPaymentId = paymentId,
            originalOrderId = orderId,
            originalTotalAmount = originalTotalAmount,
            refundReason = refundReason,
            isPartialRefund = isPartial,
            idempotencyKey = idempotencyKey,
            originalOperationNumber = 0, // AngelPay does not use Blumon's CancelIcc opNumber.
        )

        // Stub card details — the refund row's audit fields populate from the
        // ORIGINAL payment server-side; what we send here is just informational.
        val cardDetails = CardDetails(
            maskedPan = "",
            cardBrand = CardBrand.UNKNOWN,
            entryMode = CardEntryMode.OTHER,
        )

        // 🔴 Todo el desenlace corre en NonCancellable (auditoría de Codex F2): el llamador es un
        // `rememberCoroutineScope` de la pantalla, que muere si el cajero sale — y el dinero ya salió.
        return withContext(NonCancellable) {
            // 🔴 El payload se guarda VERBATIM: en AngelPay la autorización es un marcador constante y la
            // referencia es la del pago ORIGINAL — los dos entran en la huella del servidor, así que
            // deben reproducirse idénticos.
            val queued = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund(
                idempotencyKey = idempotencyKey,
                venueId = paymentVenueId,
                staffId = staffId,
                processor = com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.ANGELPAY,
                originalPaymentId = paymentId,
                originalOrderId = orderId,
                amount = refundAmount,
                originalTotalAmount = originalTotalAmount,
                tipRefundCents = tipRefundCents,
                isPartialRefund = isPartial,
                refundReason = refundReason,
                merchantAccountId = merchantAccountId,
                blumonSerialNumber = "",
                originalOperationNumber = 0,
                authorizationNumber = "ANGELPAY_SDK",
                referenceNumber = sdkReferenceNumber,
                maskedPan = null,
                cardBrand = null,
                entryMode = "OTHER",
                createdAt = System.currentTimeMillis(),
            )

            // 1️⃣ WRITE-AHEAD (auditoría F1): la fila existe ANTES del POST, ya reclamada por este intento.
            val claimToken = java.util.UUID.randomUUID().toString()
            val writeAhead = refundQueueRepository.enqueueClaimed(queued, claimToken)
            writeAhead.onFailure { Timber.e(it, "💸🔴 [AngelPay Direct Refund] No se pudo escribir la fila write-ahead | key=%s", idempotencyKey) }

            // 2️⃣ El POST
            val recorded = if (!hasSession) {
                Result.failure(IllegalStateException("Sin sesión de staff: el reembolso quedó en cola"))
            } else {
                refundRecorder.recordRefund(
                    context = context,
                    cardDetails = cardDetails,
                    authorizationNumber = "ANGELPAY_SDK", // Placeholder until we expose SDK result.
                    referenceNumber = sdkReferenceNumber,  // Reuse original ref — sufficient for backend booking.
                    tipRefundCents = tipRefundCents,
                    processor = "angelpay",
                ).map { } // Discard the RefundReceipt — caller only cares about success/failure.
            }
            val recordError = recorded.exceptionOrNull()
            if (recordError == null) {
                runCatching { paymentAttemptLedger.markRecorded(idempotencyKey) }
                if (writeAhead.isSuccess) {
                    val affected = refundQueueRepository.markSuccess(idempotencyKey, claimToken)
                    if (affected == 0) Timber.w("💸 [AngelPay Direct Refund] markSuccess no afectó filas (otro dueño del claim) | key=%s", idempotencyKey)
                }
                return@withContext Result.success(Unit)
            }
            runCatching { paymentAttemptLedger.markRecordFailed(idempotencyKey, recordError.message) }

            // 3️⃣ Desenlace, clasificado con la MISMA función que los cobros: 400/404/422 = rechazo permanente
            // (bloquea el cierre hasta que alguien lo vea); 401/408/429/5xx/red = se reintenta.
            val outcome = com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure(recordError)
            val permanent = outcome is com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome.Permanent
            val reason = (outcome as? com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome.Permanent)?.reason ?: (recordError.message ?: "fallo transitorio")
            val respaldo: Result<Unit> = if (writeAhead.isSuccess) {
                val affected = if (permanent) {
                    refundQueueRepository.markPermanentlyFailed(idempotencyKey, claimToken, reason)
                } else {
                    refundQueueRepository.release(idempotencyKey, claimToken, retryCount = 1, error = reason)
                }
                if (affected == 0) Timber.w("💸 [AngelPay Direct Refund] La fila ya no es de este intento; existe igual | key=%s", idempotencyKey)
                Result.success(Unit)
            } else {
                val row = if (permanent) queued.copy(syncStatus = com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity.SYNC_STATUS_FAILED, permanent = true, lastError = reason) else queued
                refundQueueRepository.enqueue(row)
            }
            respaldo.fold(
                onSuccess = {
                    runCatching { paymentAttemptLedger.markDeliveredToQueue(idempotencyKey) }
                    Result.failure(RefundQueuedException(idempotencyKey, permanent, reason))
                },
                onFailure = { queueError -> Result.failure(RefundLostException(idempotencyKey, recordError, queueError)) },
            )
        }
    }

    companion object {
        /**
         * Texto del candado contra un SEGUNDO reembolso del mismo pago. Distingue lo que puede
         * pasar con la devolución que ya existe (founder, N86, 7-sep-2026): una fila PENDING «se
         * registra sola»; una RECHAZADA por el servidor (`permanent`) **no se reintenta nunca** —
         * decirle al cajero «espera a que se registre» sería una espera infinita. Ahí lo honesto es
         * el motivo del rechazo y a quién avisar.
         */
        fun mensajeDelCandado(fila: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund): String {
            val monto = fila.amount.setScale(2).toPlainString()
            return if (fila.permanent) {
                val motivo = fila.lastError?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                "Este pago ya tiene una devolución de $$monto hecha en la terminal que Avoqado RECHAZÓ registrar$motivo. " +
                    "No se hizo otra y no se reintenta sola: avisa al supervisor para registrarla en Avoqado."
            } else {
                "Este pago ya tiene una devolución de $$monto hecha en la terminal y todavía sin registrar en Avoqado. " +
                    "No se hizo otra. Espera a que se registre (o revísala en Caja)."
            }
        }

        /**
         * Shown when the operator asks for a partial AngelPay refund. Kept as a
         * constant so tests can distinguish the guard from downstream failures.
         */
        const val PARTIAL_REFUND_UNSUPPORTED_MESSAGE =
            "El reembolso parcial no está disponible en terminales AngelPay: la terminal " +
                "devolvería el monto COMPLETO de la venta original. Realiza el reembolso " +
                "total o gestiona el parcial con soporte."
    }

    // ─── Private helpers (extracted from AppNavigation.kt) ────────────────────

    private fun isAngelPayApprovedStatus(status: String?): Boolean {
        val normalized = status?.trim()?.uppercase() ?: return false
        return normalized in setOf("APPROVED", "APROBADA", "COMPLETED", "SUCCESS")
    }

    private fun looksLikeInvalidReference(message: String?): Boolean {
        val normalized = normalizeAngelPayMessage(message)
        return normalized.contains("REFERENCIA INVALID")
    }

    private fun normalizeAngelPayMessage(message: String?): String {
        val value = message?.trim().orEmpty()
        if (value.isBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .uppercase()
    }

    private fun parseAngelPayTransactionDate(
        creationDate: String,
        zone: ZoneId,
    ): LocalDate? {
        if (creationDate.isBlank()) return null

        return runCatching {
            Instant.parse(creationDate).atZone(zone).toLocalDate()
        }.recoverCatching {
            LocalDate.parse(creationDate.take(10))
        }.getOrNull()
    }

    private data class AngelPaySdkReferenceHint(
        val currentReference: String?,
        val previousReference: String?,
        val cancelPreviousReference: String?,
        val reversePreviousReference: String?,
        val idOperation: Int?,
    )

    private suspend fun resolveAngelPaySdkReferenceCandidates(
        appContext: Context,
        transaction: UnifiedTransaction,
    ): List<String> = withContext(Dispatchers.IO) {
        val dbFile = appContext.getDatabasePath("angelpay_sdk_db")
        if (!dbFile.exists()) {
            Timber.w("⚠️ [AngelPay Direct Refund] SDK DB not found at %s", dbFile.absolutePath)
            return@withContext emptyList()
        }

        val hint = runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                queryAngelPayHint(db, transaction)
            }
        }.onFailure { error ->
            Timber.w(error, "⚠️ [AngelPay Direct Refund] Failed reading AngelPay SDK DB")
        }.getOrNull()

        if (hint == null) {
            Timber.w(
                "⚠️ [AngelPay Direct Refund] No SDK local row found for ref=%s folio=%s auth=%s",
                transaction.reference,
                transaction.folio,
                transaction.authorizationCode,
            )
            return@withContext emptyList()
        }

        Timber.i(
            "🔶 [AngelPay Direct Refund] SDK hint current=%s previous=%s cancelPrevious=%s reversePrevious=%s idOperation=%s",
            hint.currentReference ?: "-",
            hint.previousReference ?: "-",
            hint.cancelPreviousReference ?: "-",
            hint.reversePreviousReference ?: "-",
            hint.idOperation ?: -1,
        )

        return@withContext distinctCaseInsensitive(
            listOfNotNull(
                hint.currentReference?.takeIf { it.isNotBlank() },
                hint.previousReference?.takeIf { it.isNotBlank() },
                hint.cancelPreviousReference?.takeIf { it.isNotBlank() },
                hint.reversePreviousReference?.takeIf { it.isNotBlank() },
            )
        )
    }

    private fun queryAngelPayHint(
        db: SQLiteDatabase,
        transaction: UnifiedTransaction,
    ): AngelPaySdkReferenceHint? {
        val lookups = buildList {
            if (transaction.reference.isNotBlank() && !transaction.folio.isBlank() && !transaction.terminal.isNullOrBlank()) {
                add(
                    Triple(
                        "referenceNumber = ? AND folio = ? AND terminal = ?",
                        arrayOf(transaction.reference, transaction.folio, transaction.terminal),
                        "reference+folio+terminal",
                    )
                )
            }
            if (transaction.reference.isNotBlank() && !transaction.folio.isBlank()) {
                add(
                    Triple(
                        "referenceNumber = ? AND folio = ?",
                        arrayOf(transaction.reference, transaction.folio),
                        "reference+folio",
                    )
                )
            }
            if (transaction.reference.isNotBlank()) {
                add(
                    Triple(
                        "referenceNumber = ?",
                        arrayOf(transaction.reference),
                        "reference",
                    )
                )
                add(
                    Triple(
                        "referenceNumberAnt = ?",
                        arrayOf(transaction.reference),
                        "previousReference",
                    )
                )
            }
            if (!transaction.folio.isBlank() && !transaction.authorizationCode.isBlank()) {
                add(
                    Triple(
                        "folio = ? AND authorization = ?",
                        arrayOf(transaction.folio, transaction.authorizationCode),
                        "folio+authorization",
                    )
                )
            }
            if (!transaction.authorizationCode.isBlank() && !transaction.terminal.isNullOrBlank()) {
                add(
                    Triple(
                        "authorization = ? AND terminal = ?",
                        arrayOf(transaction.authorizationCode, transaction.terminal),
                        "authorization+terminal",
                    )
                )
            }
        }

        for ((whereClause, args, label) in lookups) {
            val hint = db.querySingleAngelPayHint(whereClause, args)
            if (hint != null) {
                Timber.i(
                    "🔶 [AngelPay Direct Refund] SDK hint source=%s for ref=%s",
                    label,
                    transaction.reference,
                )
                return hint
            }
        }

        return null
    }

    private fun SQLiteDatabase.querySingleAngelPayHint(
        whereClause: String,
        whereArgs: Array<String>,
    ): AngelPaySdkReferenceHint? {
        val sql = """
            SELECT referenceNumber, referenceNumberAnt, cancelreferenceNumberAnt, reversereferenceNumberAnt, idOperation
            FROM TransactionEntity
            WHERE $whereClause
            ORDER BY creationDate DESC
            LIMIT 1
        """.trimIndent()

        rawQuery(sql, whereArgs).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AngelPaySdkReferenceHint(
                currentReference = cursor.getStringOrNull("referenceNumber"),
                previousReference = cursor.getStringOrNull("referenceNumberAnt"),
                cancelPreviousReference = cursor.getStringOrNull("cancelreferenceNumberAnt"),
                reversePreviousReference = cursor.getStringOrNull("reversereferenceNumberAnt"),
                idOperation = cursor.getIntOrNull("idOperation"),
            )
        }
    }

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index == -1 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        if (index == -1 || isNull(index)) return null
        return getInt(index)
    }

    private fun distinctCaseInsensitive(values: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        for (rawValue in values) {
            val trimmed = rawValue.trim()
            if (trimmed.isBlank()) continue
            val key = trimmed.uppercase()
            if (seen.add(key)) {
                result += trimmed
            }
        }
        return result
    }

    private fun expandAngelPayReferenceVariants(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()

        val candidates = linkedSetOf(trimmed)
        val numeric = trimmed.all(Char::isDigit)
        if (numeric) {
            val noLeadingZero = trimmed.trimStart('0')
            if (noLeadingZero.isNotBlank()) candidates += noLeadingZero
            if (trimmed.length > 12) candidates += trimmed.takeLast(12)
            if (trimmed.length > 10) candidates += trimmed.takeLast(10)
        }
        return candidates.toList()
    }

    private suspend fun resolveAngelPayHistoryReferenceCandidates(
        adapterFactory: PostOperationsAdapterFactory,
        target: UnifiedTransaction,
        startDate: String,
        endDate: String,
        zone: ZoneId,
    ): List<String> {
        val adapter = adapterFactory.get(ProcessorType.ANGELPAY)
        val history = adapter.getTransactionHistory(
            TransactionHistoryQuery(
                startDate = startDate,
                endDate = endDate,
                terminal = target.terminal?.takeIf { it.isNotBlank() },
            )
        ).getOrElse { error ->
            Timber.w(error, "⚠️ [AngelPay Direct Refund] Failed loading broad history candidates")
            return emptyList()
        }

        val targetDate = parseAngelPayTransactionDate(target.creationDate, zone)

        val scored = history
            .map { candidate ->
                var score = 0
                if (candidate.reference.equals(target.reference, ignoreCase = true)) score += 50
                if (candidate.authorizationCode.isNotBlank() &&
                    candidate.authorizationCode.equals(target.authorizationCode, ignoreCase = true)
                ) score += 20
                if (candidate.folio.isNotBlank() &&
                    candidate.folio.equals(target.folio, ignoreCase = true)
                ) score += 12
                if (kotlin.math.abs(candidate.amount - target.amount) < 0.01) score += 8
                if (isAngelPayApprovedStatus(candidate.status)) score += 4
                if ((candidate.operationType ?: "").contains("VENTA", ignoreCase = true) ||
                    (candidate.operationType ?: "").contains("SALE", ignoreCase = true)
                ) score += 3
                if (targetDate != null &&
                    parseAngelPayTransactionDate(candidate.creationDate, zone) == targetDate
                ) score += 2
                candidate to score
            }
            .filter { (_, score) -> score >= 8 }
            .sortedByDescending { (_, score) -> score }
            .take(25)

        val references = scored.flatMap { (candidate, _) ->
            listOfNotNull(
                candidate.reference.takeIf { it.isNotBlank() },
                candidate.postOperationReference?.takeIf { it.isNotBlank() },
            )
        }

        val unique = distinctCaseInsensitive(references)
        Timber.i(
            "🔶 [AngelPay Direct Refund] Broad history candidate refs=%s",
            unique.joinToString(separator = ",").ifBlank { "-" },
        )
        return unique
    }
}

/**
 * Hilt EntryPoint so [AppNavigation] (a Composable, not a Hilt-injected class) can
 * obtain [RecordAngelPayRefundUseCase] via [dagger.hilt.android.EntryPointAccessors].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordAngelPayRefundUseCaseEntryPoint {
    fun recordAngelPayRefundUseCase(): RecordAngelPayRefundUseCase
}

/**
 * El registro falló pero el reembolso quedó ENCOLADO con [idempotencyKey]. Es un `failure` sólo
 * para el llamador inmediato (que aún no puede confirmar el registro): el dinero está a salvo.
 * [permanent] = el servidor lo rechazó de forma definitiva — la fila bloquea el cierre de turno
 * hasta que alguien la reconozca.
 */
class RefundQueuedException(val idempotencyKey: String, val permanent: Boolean, val reason: String) : Exception(reason)

/** Ni el servidor ni la cola local: lo peor que puede pasar. Requiere conciliación manual con la referencia. */
class RefundLostException(val idempotencyKey: String, val recordError: Throwable, val queueError: Throwable) :
    Exception("Reembolso sin registro (backend y cola fallaron)", queueError)
