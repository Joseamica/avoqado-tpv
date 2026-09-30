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
import com.jaac.avoqado_tpv.features.payment.domain.processor.AngelPayCorte
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
    /** ⏰ La hora del corte de AngelPay se decide con este reloj (inyectable para las pruebas). */
    private val clock: java.time.Clock,
    /** 🔴 «Devolución en curso» mientras se habla con AngelPay (founder, 29-sep: nada detiene las ventas). */
    private val paymentStateHolder: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder,
) {

    /**
     * ¿Este pago tiene una devolución que ya salió hacia AngelPay y falta confirmar o registrar? Son los MISMOS dos candados
     * que [processSdkRefund] revisa primero (la cola de `pending_refunds` y la libreta): la lista de pagos lo pregunta para no
     * esconder tras el corte un pago que todavía tiene algo por resolver, ni decir de él «no se reembolsó nada».
     */
    suspend fun tieneDevolucionPorResolver(originalPaymentId: String): Boolean = devolucionPendiente(originalPaymentId) != null

    /** Lo que ya salió hacia AngelPay por un pago y falta confirmar o registrar. */
    private sealed interface DevolucionPendiente {
        /** Aprobada por el SDK y encolada para registrarse (`pending_refunds`). */
        data class EnCola(val fila: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund, val cuantas: Int) :
            DevolucionPendiente
        /** En la libreta: aprobada sin llegar a la cola, o en duda. */
        data class EnLibreta(val fila: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity) : DevolucionPendiente
    }

    /**
     * 🔒 Los dos candados contra devolver dos veces, en UN solo lugar (re-auditoría del 29-sep): los usan el intento de
     * devolución y la lista de pagos, así que nunca pueden discrepar. Si la cola no se puede leer se sigue con la libreta,
     * como antes; una cancelación se propaga.
     */
    private suspend fun devolucionPendiente(originalPaymentId: String): DevolucionPendiente? {
        val enCola = try {
            refundQueueRepository.unresolvedForPayment(originalPaymentId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "💸 [AngelPay Direct Refund] No se pudo leer la cola de devoluciones del pago %s", originalPaymentId)
            emptyList()
        }
        enCola.firstOrNull()?.let { return DevolucionPendiente.EnCola(it, enCola.size) }
        return paymentAttemptLedger.devolucionSinResolver(originalPaymentId)?.let { DevolucionPendiente.EnLibreta(it) }
    }

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

        val ahora = clock.instant()

        when (val pendiente = devolucionPendiente(originalPaymentId)) {
            // 💸 CANDADO DE LA COLA (auditoría de Codex F7): este pago ya tiene una devolución aprobada por el SDK y sin
            // registrar en Avoqado. NO se lanza otra — el servidor aún lo ve reembolsable y una segunda llave devolvería el
            // dinero dos veces.
            is DevolucionPendiente.EnCola -> {
                Timber.w("⛔ [AngelPay Direct Refund] Bloqueado: el pago %s ya tiene %s devolución(es) sin registrar", originalPaymentId, pendiente.cuantas)
                return Result.failure(IllegalStateException(mensajeDelCandado(pendiente.fila)))
            }
            // 📒 CANDADO DE LA LIBRETA (founder, 29-sep-2026): una devolución anterior de ESTE pago salió hacia AngelPay y no
            // se supo en qué quedó —o AngelPay la aprobó y no alcanzó a registrarse—. Otra devolvería dos veces: primero se
            // resuelve ésa. Sólo cerca ESTE pago: las ventas y las devoluciones de otros pagos siguen normales.
            is DevolucionPendiente.EnLibreta -> return resolverDevolucionAnterior(pendiente.fila, paymentReference, createdAt, ahora)
            null -> Unit
        }

        // ⏰ EL CORTE (founder y AngelPay, 29-sep-2026): AngelPay sólo CANCELA una venta completa antes de las 11 pm del día en
        // que se cobró, y tiene apagadas las devoluciones posteriores para todos los comercios. Pasado el corte no se abre la
        // libreta ni se llama a AngelPay, y el cajero sabe a quién pedírsela. 🔴 Va DESPUÉS de los dos candados (auditoría del
        // 29-sep, P1): una devolución que AngelPay ya aprobó o que quedó en duda se tiene que poder confirmar y registrar
        // aunque ya haya pasado el corte — confirmarla no le pide nada nuevo a AngelPay.
        if (!AngelPayCorte.sePuedeIntentarDesdeLaTerminal(createdAt, ahora)) {
            Timber.w(
                "⛔ [AngelPay Direct Refund] Fuera del corte | cobrado=%s corte=%s ahora=%s",
                createdAt, AngelPayCorte.corteDeLaVenta(createdAt), ahora,
            )
            return Result.failure(IllegalStateException(AngelPayCorte.MENSAJE_FUERA_DE_CORTE_SIN_INTENTO))
        }
        val useCancellation = AngelPayCorte.sePuedeCancelar(createdAt, ahora)
        val operationLabel = if (useCancellation) "cancelación" else "devolución"

        // 🔑 La llave nace AQUÍ, ANTES del SDK, y viaja con la aprobación hasta `recordInBackend`: es la
        // misma en la libreta, en la fila write-ahead y en el POST (Fase 0 del servidor deduplica con ella).
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        // 📒 [Libreta] write-ahead ANTES de que corra el SDK (auditoría F1): si el proceso muere entre la aprobación y la fila de
        // la cola, queda evidencia con la MISMA llave. 🔴 29-sep: si la fila NO queda escrita, no se llama a AngelPay — una
        // devolución sin fila no tiene quién la cierre ni quién impida repetirla.
        val abierta = paymentAttemptLedger.openAttempt(
            attemptId = idempotencyKey,
            venueId = paymentVenueId,
            processor = "angelpay",
            amountCents = requestedAmount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
            tipCents = 0L,
            recordingRoute = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.ROUTE_REFUND,
            contextJson = contextoDeLaDevolucion(originalPaymentId, paymentReference),
            kind = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.KIND_REFUND,
        )
        if (!abierta) {
            Timber.w("📒 [AngelPay Direct Refund] La libreta no abrió el intento: no se llama a AngelPay | pago=%s", originalPaymentId)
            return Result.failure(IllegalStateException(MENSAJE_NO_SE_GUARDO))
        }

        // 🔴 Founder, 29-sep-2026: «nada puede detener las ventas», «ni trabar nada», «ni reiniciar». Desde aquí la fila existe
        // y TODA salida la cierra con lo que de verdad pasó: antes de llegar a AngelPay ⇒ descartada; rechazo explícito ⇒
        // rechazada; sin veredicto ⇒ en duda. Ninguna aparta la terminal; la duda sólo impide repetir la devolución de ESTE
        // pago. El `finally` es la red para lo que salga por una excepción o una cancelación.
        var cerrada = false
        try {
            val adapter = postOperationsAdapterFactory.get(ProcessorType.ANGELPAY)
            val (startDate, endDate) = ventanaDelHistorial(createdAt, ahora)

            // Nada salió hacia AngelPay: la fila se descarta y el cajero puede volver a intentarlo.
            suspend fun noSalio(motivo: String, mensaje: String): Result<AngelPayRefundApproval> {
                cerrada = paymentAttemptLedger.markDiscardedBeforeCharge(idempotencyKey, "antes_del_sdk:$motivo")
                return Result.failure(IllegalStateException("$mensaje No se reembolsó nada."))
            }

            Timber.i(
                "🔶 [AngelPay Direct Refund] Resolving reference=%s reason=%s window=%s..%s",
                paymentReference, requestedReason.name, startDate, endDate,
            )
            val target = buscarLaVenta(adapter, paymentReference, startDate, endDate).getOrElse { error ->
                return noSalio("historial", "No se pudo consultar la venta en AngelPay (${error.message ?: "sin detalle"}).")
            } ?: return noSalio("sin_venta", "No se encontró la venta en AngelPay con la referencia $paymentReference.")
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkPostOperationsAdapter
                .validarCamposDePostOperacion(target)?.let { motivo ->
                    return noSalio("campos", "La venta en AngelPay está incompleta ($motivo).")
                }
            // ⏰ AngelPay corta por SU lote, y su hora puede ser anterior a la de Avoqado (un cobro que se registró tarde, desde
            // la cola sin red). Su hora sólo puede CERRAR el corte, nunca abrirlo (auditoría del 29-sep, P2).
            instanteEnAngelPay(target)?.let { cobradoEnAngelPay ->
                if (!AngelPayCorte.sePuedeIntentarDesdeLaTerminal(cobradoEnAngelPay, ahora)) {
                    Timber.w(
                        "⛔ [AngelPay Direct Refund] Fuera del corte según AngelPay | angelpay=%s avoqado=%s ahora=%s",
                        cobradoEnAngelPay, createdAt, ahora,
                    )
                    cerrada = paymentAttemptLedger.markDiscardedBeforeCharge(idempotencyKey, "antes_del_sdk:corte_angelpay")
                    return Result.failure(IllegalStateException(AngelPayCorte.MENSAJE_FUERA_DE_CORTE_SIN_INTENTO))
                }
            }

            val alternativas = referenciasAlternativas(
                venta = target,
                sdkCandidates = resolveAngelPaySdkReferenceCandidates(appContext = appContext, transaction = target),
                historyCandidates = resolveAngelPayHistoryReferenceCandidates(
                    adapterFactory = postOperationsAdapterFactory,
                    target = target,
                    startDate = startDate,
                    endDate = endDate,
                    zone = AngelPayCorte.ZONA_MEXICO,
                ),
            )

            // 🔴 La terminal sólo espera MIENTRAS se habla con AngelPay (unos segundos): un cobro del POS que llegue ahora se
            // rechaza con motivo en vez de arrancarle la pantalla a la devolución, y la sesión de AngelPay no se cambia a media
            // llamada (`isCharging()` la incluye). Se renueva antes de CADA llamada y caduca sola a los 120 s de la última:
            // aunque algo la dejara puesta, jamás traba las ventas. La bandera de cobro de las VENTAS no se toca: soltarla aquí
            // podía dejar sin protección a una venta que arrancó después (auditoría del 29-sep, P2).
            paymentStateHolder.marcarDevolucionEnCurso()

            // 📒 AUTORIZANDO justo antes de tocar el SDK: a partir de aquí el dinero puede moverse.
            if (!paymentAttemptLedger.markAuthorizing(idempotencyKey)) {
                Timber.w("📒 [AngelPay Direct Refund] La libreta no dejó autorizar: no se llama a AngelPay | key=%s", idempotencyKey)
                cerrada = paymentAttemptLedger.markDiscardedBeforeCharge(idempotencyKey, "antes_del_sdk:libreta")
                return Result.failure(IllegalStateException(MENSAJE_NO_SE_GUARDO))
            }

            Timber.i(
                "🔶 [AngelPay Direct Refund] Executing %s ref=%s cobrado=%s corte=%s alternativas=%s",
                operationLabel, target.reference, createdAt, AngelPayCorte.corteDeLaVenta(createdAt), alternativas.size,
            )
            var desenlace = intentarConRespaldos(target, alternativas, operationLabel) { tx, manual ->
                if (useCancellation) adapter.cancelTransaction(transaction = tx, isManual = manual)
                else adapter.refundTransaction(transaction = tx, isManual = manual)
            }
            // La devolución de respaldo sólo tras un RECHAZO explícito de la cancelación (nunca tras una duda: pudo aplicarse), y
            // sólo si AngelPay vuelve a habilitar las devoluciones — hoy las tiene apagadas para todos los comercios.
            if (useCancellation && AngelPayCorte.DEVOLUCION_POSTERIOR_HABILITADA &&
                desenlace is DesenlaceDeDevolucion.Rechazada && !desenlace.referenciaInvalida
            ) {
                Timber.w("⚠️ [AngelPay Direct Refund] Cancelación rechazada ref=%s: se prueba la devolución", target.reference)
                desenlace = intentarConRespaldos(target, emptyList(), "devolución") { tx, manual ->
                    adapter.refundTransaction(transaction = tx, isManual = manual)
                }
            }

            return when (val final = desenlace) {
                is DesenlaceDeDevolucion.Aprobada -> {
                    val ref = final.resultado.reference ?: paymentReference
                    cerrada = anotarAprobacion(idempotencyKey, ref, final.resultado.authorizationCode)
                    Result.success(
                        AngelPayRefundApproval(
                            message = "${final.operacion} aprobada${final.resultado.reference?.let { " (ref: $it)" } ?: ""}",
                            idempotencyKey = idempotencyKey,
                        )
                    )
                }
                is DesenlaceDeDevolucion.Rechazada -> {
                    cerrada = paymentAttemptLedger.markHostResponded(idempotencyKey, false, null, null, null)
                    Result.failure(IllegalStateException(mensajeDeRechazo(final)))
                }
                is DesenlaceDeDevolucion.EnDuda -> {
                    Timber.e(
                        "💸🔴 [AngelPay Direct Refund] Sin veredicto de AngelPay: la devolución queda EN DUDA | key=%s pago=%s detalle=%s",
                        idempotencyKey, originalPaymentId, final.detalle,
                    )
                    paymentAttemptLedger.markIndeterminate(idempotencyKey, "devolucion_sin_veredicto:${final.detalle}")
                    cerrada = true
                    Result.failure(IllegalStateException(MENSAJE_DEVOLUCION_EN_DUDA))
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (!cerrada) {
                    // Los dos CAS se excluyen: PREPARANDO (nada salió) ⇒ descartada; AUTORIZANDO (pudo salir) ⇒ en duda.
                    runCatching { paymentAttemptLedger.markDiscardedBeforeCharge(idempotencyKey, "devolucion_terminada_antes_del_sdk") }
                    runCatching { paymentAttemptLedger.markIndeterminate(idempotencyKey, "devolucion_terminada_sin_veredicto") }
                }
                paymentStateHolder.terminarDevolucionEnCurso()
            }
        }
    }

    /** Lo que AngelPay dijo de una cancelación o devolución, clasificado con la MISMA tabla que los cobros. */
    private sealed interface DesenlaceDeDevolucion {
        data class Aprobada(val resultado: PostOperationResult, val operacion: String) : DesenlaceDeDevolucion
        /** Rechazo explícito: nada se devolvió. [referenciaInvalida] = AngelPay no reconoce esa referencia. */
        data class Rechazada(val resultado: PostOperationResult, val operacion: String, val referenciaInvalida: Boolean) :
            DesenlaceDeDevolucion
        /** La llamada salió y no volvió un veredicto: pudo aplicarse. */
        data class EnDuda(val detalle: String) : DesenlaceDeDevolucion
    }

    /**
     * 🔴 Sólo un rechazo EXPLÍCITO dice «no se devolvió nada» (`.claude/rules/cobro-remoto-pos-a-tpv.md`): un código del
     * catálogo de rechazos, un rechazo del emisor, o «referencia inválida» (AngelPay no encontró esa venta, así que no pudo
     * cancelarla). Todo lo demás —G505/G506, un timeout, un código desconocido— queda EN DUDA.
     */
    private fun clasificarRespuesta(r: PostOperationResult, operacion: String): DesenlaceDeDevolucion {
        if (r.approved) return DesenlaceDeDevolucion.Aprobada(r, operacion)
        val codigo = r.errorCode?.trim()?.takeIf { it.isNotEmpty() }
        val desenlace = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayOutcomeClassifier.clasificar(
            aprobado = false,
            status = r.status,
            // `errorCode` junta los dos: el del catálogo de AngelPay (4 caracteres, «G500») o el del emisor (2, «05»).
            codigoSdk = codigo?.takeUnless { it.length == 2 },
            codigoGateway = codigo?.takeIf { it.length == 2 },
        )
        val referenciaInvalida = looksLikeInvalidReference(r.message)
        return when {
            desenlace == com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.DesenlaceDelCobro.RECHAZADO_CONFIRMADO ->
                DesenlaceDeDevolucion.Rechazada(r, operacion, referenciaInvalida)
            referenciaInvalida -> DesenlaceDeDevolucion.Rechazada(r, operacion, referenciaInvalida = true)
            else -> DesenlaceDeDevolucion.EnDuda("${codigo ?: "sin_codigo"}/${r.status ?: "sin_estado"}")
        }
    }

    /**
     * Intenta la operación con la referencia de la venta y, SÓLO si AngelPay contesta que no la conoce, con sus variantes
     * (manual, referencias del SDK y del historial). 🔴 Se detiene en la primera aprobación, en el primer rechazo que no es de
     * referencia y en la primera DUDA: otra llamada tras una duda podría devolver dos veces la misma venta.
     */
    private suspend fun intentarConRespaldos(
        venta: UnifiedTransaction,
        alternativas: List<String>,
        operacion: String,
        llamar: suspend (UnifiedTransaction, Boolean) -> Result<PostOperationResult>,
    ): DesenlaceDeDevolucion {
        val intentos = buildList {
            add(venta.reference to false)
            add(venta.reference to true)
            alternativas.forEach { add(it to false); add(it to true) }
        }
        var ultimoRechazo: DesenlaceDeDevolucion.Rechazada? = null
        for ((referencia, manual) in intentos) {
            val tx = if (referencia.equals(venta.reference, ignoreCase = true)) venta else venta.copy(reference = referencia)
            // Una variante que el SDK rechazaría antes de salir no se manda: no aporta nada y no es una llamada a AngelPay.
            if (com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkPostOperationsAdapter
                    .validarCamposDePostOperacion(tx) != null
            ) continue
            paymentStateHolder.marcarDevolucionEnCurso()   // la marca cubre ESTA llamada, no la primera
            val respuesta = try {
                llamar(tx, manual).getOrThrow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "💸 [AngelPay Direct Refund] %s ref=%s manual=%s: el SDK falló sin veredicto", operacion, referencia, manual)
                return DesenlaceDeDevolucion.EnDuda("excepcion:${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}")
            }
            val desenlace = clasificarRespuesta(respuesta, operacion)
            Timber.i(
                "🔶 [AngelPay Direct Refund] %s ref=%s manual=%s ⇒ %s (code=%s status=%s msg=%s)",
                operacion, referencia, manual, desenlace.javaClass.simpleName,
                respuesta.errorCode ?: "-", respuesta.status ?: "-", respuesta.message ?: "-",
            )
            when (desenlace) {
                is DesenlaceDeDevolucion.Aprobada, is DesenlaceDeDevolucion.EnDuda -> return desenlace
                is DesenlaceDeDevolucion.Rechazada -> {
                    if (!desenlace.referenciaInvalida) return desenlace
                    ultimoRechazo = desenlace
                }
            }
        }
        // Inalcanzable en la práctica: la venta se validó antes, así que el primer intento siempre sale.
        return ultimoRechazo ?: DesenlaceDeDevolucion.EnDuda("sin_intentos")
    }

    private fun mensajeDeRechazo(r: DesenlaceDeDevolucion.Rechazada): String {
        val operacion = r.operacion.replaceFirstChar { it.uppercase() }
        if (r.referenciaInvalida) {
            return "$operacion rechazada: AngelPay no encontró esta venta con ninguna de sus referencias. No se devolvió nada."
        }
        val detalle = listOfNotNull(r.resultado.errorCode?.takeIf { it.isNotBlank() }, r.resultado.message?.takeIf { it.isNotBlank() })
            .joinToString(" · ").ifBlank { "sin detalle" }
        return "$operacion rechazada por AngelPay ($detalle). No se devolvió nada."
    }

    /** El SDK aprobó: la libreta lo anota (HOST_RESPONDIO). Devuelve si quedó escrito; el dinero ya salió pase lo que pase. */
    private suspend fun anotarAprobacion(idempotencyKey: String, reference: String, authCode: String?): Boolean =
        runCatching {
            paymentAttemptLedger.markHostResponded(
                attemptId = idempotencyKey,
                approved = true,
                operationId = null,
                referenceNumber = reference,
                authCode = authCode,
            )
        }.onFailure { Timber.w(it, "📒 [AngelPay Direct Refund] La libreta no pudo anotar la aprobación (no bloquea)") }
            .getOrDefault(false)

    /**
     * 📒 Una devolución anterior de ESTE pago que la libreta no ha cerrado ([PaymentAttemptLedger.devolucionSinResolver]).
     *  - AngelPay ya la APROBÓ y no se registró (el proceso murió antes de la cola): se entrega otra vez con SU llave —el
     *    servidor deduplica— y AngelPay no se vuelve a tocar.
     *  - Quedó EN DUDA: si el historial de AngelPay ya muestra la cancelación aplicada sobre la venta, se da por aprobada con
     *    SU llave; si no, no se intenta otra.
     */
    private suspend fun resolverDevolucionAnterior(
        anterior: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity,
        paymentReference: String,
        createdAt: Instant,
        ahora: Instant,
    ): Result<AngelPayRefundApproval> {
        val aprobadaSinRegistrar = anterior.state in setOf(
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_HOST_RESPONDIO,
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_AUTORIZADO,
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_REGISTRO_FALLIDO,
        )
        if (aprobadaSinRegistrar) {
            Timber.w(
                "💸 [AngelPay Direct Refund] La devolución %s ya la aprobó AngelPay y no se registró: se entrega con SU llave, sin llamar a AngelPay",
                anterior.attemptId,
            )
            return Result.success(
                AngelPayRefundApproval(
                    message = "La devolución ya estaba aprobada por AngelPay${anterior.referenceNumber?.let { " (ref: $it)" } ?: ""}; se registra en Avoqado",
                    idempotencyKey = anterior.attemptId,
                )
            )
        }

        val (startDate, endDate) = ventanaDelHistorial(createdAt, ahora)
        val venta = buscarLaVenta(postOperationsAdapterFactory.get(ProcessorType.ANGELPAY), paymentReference, startDate, endDate).getOrNull()
        val referenciaDeLaCancelacion = venta?.postOperationReference?.takeIf { it.isNotBlank() }
        if (venta != null && referenciaDeLaCancelacion != null && isAngelPayApprovedStatus(venta.postOperationStatus)) {
            Timber.w(
                "💸 [AngelPay Direct Refund] El historial de AngelPay muestra aplicada la devolución en duda %s (ref=%s tipo=%s)",
                anterior.attemptId, referenciaDeLaCancelacion, venta.postOperationType ?: "-",
            )
            anotarAprobacion(anterior.attemptId, referenciaDeLaCancelacion, venta.postOperationAuthorization)
            return Result.success(
                AngelPayRefundApproval(
                    message = "La cancelación ya está aplicada en AngelPay (ref: $referenciaDeLaCancelacion); se registra en Avoqado",
                    idempotencyKey = anterior.attemptId,
                )
            )
        }
        Timber.w(
            "⛔ [AngelPay Direct Refund] El pago tiene una devolución en duda (%s, %s) que el historial no confirma: no se intenta otra",
            anterior.attemptId, anterior.state,
        )
        return Result.failure(IllegalStateException(MENSAJE_DEVOLUCION_EN_DUDA_PREVIA))
    }

    /**
     * La hora del cobro según AngelPay. Con zona si la trae; sin zona se lee como hora de México: si en realidad fuera UTC,
     * la hora leída sale MÁS TARDE que la real, así que el corte sólo se vuelve más permisivo y nunca cierra de más. Una
     * fecha sin hora no sirve (una venta de las 23:30 caería en el lote equivocado): `null` y manda la hora de Avoqado.
     */
    private fun instanteEnAngelPay(tx: UnifiedTransaction): Instant? {
        val crudo = tx.creationDate.trim()
        runCatching { return Instant.parse(crudo) }
        runCatching { return java.time.OffsetDateTime.parse(crudo).toInstant() }
        if (crudo.length >= 16) runCatching {
            return java.time.LocalDateTime.parse(crudo.replace(' ', 'T').take(19)).toInstant(AngelPayCorte.ZONA_MEXICO)
        }
        val fecha = tx.date?.trim().orEmpty()
        val hora = tx.time?.trim().orEmpty()
        if (fecha.length >= 10 && hora.length >= 5) runCatching {
            return java.time.LocalDateTime.parse("${fecha.take(10)}T$hora").toInstant(AngelPayCorte.ZONA_MEXICO)
        }
        return null
    }

    /** Días de la consulta al historial de AngelPay, en la hora de México (desfase fijo: el ICU de la N86 es viejo). */
    private fun ventanaDelHistorial(createdAt: Instant, ahora: Instant): Pair<String, String> {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val startDate = createdAt.atOffset(AngelPayCorte.ZONA_MEXICO).toLocalDate().minusDays(1).format(formatter)
        val endDate = ahora.atOffset(AngelPayCorte.ZONA_MEXICO).toLocalDate().plusDays(1).format(formatter)
        return startDate to endDate
    }

    /** La venta en el historial de AngelPay: `success(null)` si no aparece, `failure` si no se pudo consultar. */
    private suspend fun buscarLaVenta(
        adapter: com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter,
        paymentReference: String,
        startDate: String,
        endDate: String,
    ): Result<UnifiedTransaction?> {
        val history = try {
            adapter.getTransactionHistory(
                TransactionHistoryQuery(startDate = startDate, endDate = endDate, reference = paymentReference)
            ).getOrElse { return Result.failure(it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        }
        val candidates = history.filter { it.reference.equals(paymentReference, ignoreCase = true) }
        Timber.i("🔶 [AngelPay Direct Refund] Candidate count for ref=%s: %s", paymentReference, candidates.size)
        val target = candidates
            .sortedWith(
                compareByDescending<UnifiedTransaction> {
                    it.operationType?.contains("SALE", ignoreCase = true) == true
                }.thenByDescending {
                    isAngelPayApprovedStatus(it.status)
                }
            )
            .firstOrNull()
        target?.let {
            Timber.i(
                // Los textos crudos de fecha/hora y del estado de la post-operación van al log a propósito: su formato real no está
                // medido y de él dependen el corte por la hora de AngelPay y la confirmación por historial (re-auditoría 29-sep).
                "🔶 [AngelPay Direct Refund] Selected target ref=%s folio=%s postOpType=%s postOpRef=%s postOpStatus=%s opType=%s status=%s creation=[%s] date=[%s] time=[%s] auth=%s",
                it.reference, it.folio, it.postOperationType ?: "-", it.postOperationReference ?: "-", it.postOperationStatus ?: "-",
                it.operationType ?: "-", it.status, it.creationDate, it.date ?: "-", it.time ?: "-", it.authorizationCode,
            )
        }
        return Result.success(target)
    }

    /** Las referencias con que se reintenta si AngelPay no reconoce la de la venta (mismo orden que antes del 29-sep). */
    private fun referenciasAlternativas(
        venta: UnifiedTransaction,
        sdkCandidates: List<String>,
        historyCandidates: List<String>,
    ): List<String> = distinctCaseInsensitive(
        buildList {
            addAll(sdkCandidates)
            addAll(historyCandidates)
            venta.folio.takeIf { it.isNotBlank() }?.let(::add)
            venta.postOperationReference?.takeIf { it.isNotBlank() }?.let(::add)
            venta.authorizationCode.takeIf { it.isNotBlank() }?.let(::add)
            venta.postOperationAuthorization?.takeIf { it.isNotBlank() }?.let(::add)
        }.filterNot { it.equals(venta.reference, ignoreCase = true) }
            .flatMap(::expandAngelPayReferenceVariants)
    )

    /** El contexto de la fila: `originalPaymentId` va escapado porque [PaymentAttemptLedger.devolucionSinResolver] lo busca así. */
    private fun contextoDeLaDevolucion(originalPaymentId: String, paymentReference: String): String {
        val gson = com.google.gson.Gson()
        return "{\"originalPaymentId\":${gson.toJson(originalPaymentId)},\"reference\":${gson.toJson(paymentReference)},\"processor\":\"angelpay\"}"
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

        /** La libreta no pudo apartar el intento (una venta ocupa el lector, o la base falló): no se llama a AngelPay. */
        const val MENSAJE_NO_SE_GUARDO =
            "No se pudo iniciar la devolución en este momento. No se reembolsó nada. Vuelve a intentarlo en unos segundos."

        /** La llamada a AngelPay salió y no volvió un veredicto: pudo aplicarse. */
        const val MENSAJE_DEVOLUCION_EN_DUDA =
            "No se pudo confirmar la devolución con AngelPay. NO la repitas: pudo haberse aplicado. " +
                "Este pago queda bloqueado para otra devolución hasta confirmarlo con el historial o con soporte de AngelPay. " +
                "Las ventas siguen normales."

        /** Una devolución anterior de ESTE pago sigue sin confirmarse y el historial todavía no la muestra aplicada. */
        const val MENSAJE_DEVOLUCION_EN_DUDA_PREVIA =
            "Este pago tiene una devolución anterior que no se pudo confirmar con AngelPay. No se intentó otra para no " +
                "devolver dos veces. Confírmala con el historial o con soporte de AngelPay. Las ventas siguen normales."
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
