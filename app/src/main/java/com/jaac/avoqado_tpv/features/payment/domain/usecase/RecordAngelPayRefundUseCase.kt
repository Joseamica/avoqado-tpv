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
) {

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
    ): Result<String> {
        if (alreadyRefundedAmount > BigDecimal.ZERO || requestedAmount.compareTo(originalAmount) != 0) {
            Timber.w(
                "⛔ [AngelPay Direct Refund] Partial refund blocked | requested=%s original=%s alreadyRefunded=%s",
                requestedAmount.toPlainString(),
                originalAmount.toPlainString(),
                alreadyRefundedAmount.toPlainString(),
            )
            return Result.failure(IllegalStateException(PARTIAL_REFUND_UNSUPPORTED_MESSAGE))
        }

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
            return Result.success(
                "$operationLabel aprobada${firstResult.reference?.let { " (ref: $it)" } ?: ""}"
            )
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
                return Result.success(
                    "devolución aprobada${fallback.reference?.let { " (ref: $it)" } ?: ""}"
                )
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
    ): Result<Unit> {
        val staffId = authRepository.getStaffId()
            ?: return Result.failure(IllegalStateException("Sin sesión de staff para registrar refund"))

        if (paymentVenueId.isBlank()) {
            return Result.failure(IllegalStateException("paymentVenueId vacío — no se puede registrar el refund"))
        }
        if (merchantAccountId.isBlank()) {
            return Result.failure(IllegalStateException("merchantAccountId vacío — no se puede registrar el refund"))
        }

        // Determine whether this refund covers the whole remaining balance or
        // a portion of it (drives the backend's `isPartialRefund` flag).
        val remainingRefundable = (originalTotalAmount - refundedAmount).coerceAtLeast(BigDecimal.ZERO)
        val isPartial = refundAmount < remainingRefundable

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
            originalOperationNumber = 0, // AngelPay does not use Blumon's CancelIcc opNumber.
        )

        // Stub card details — the refund row's audit fields populate from the
        // ORIGINAL payment server-side; what we send here is just informational.
        val cardDetails = CardDetails(
            maskedPan = "",
            cardBrand = CardBrand.UNKNOWN,
            entryMode = CardEntryMode.OTHER,
        )

        return refundRecorder.recordRefund(
            context = context,
            cardDetails = cardDetails,
            authorizationNumber = "ANGELPAY_SDK", // Placeholder until we expose SDK result.
            referenceNumber = sdkReferenceNumber,  // Reuse original ref — sufficient for backend booking.
            tipRefundCents = tipRefundCents,
            processor = "angelpay",
        ).map { } // Discard the RefundReceipt — caller only cares about success/failure.
    }

    companion object {
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
