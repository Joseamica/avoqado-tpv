package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La Libreta — the single write-ahead API every payment path calls (spec §4.4).
 *
 * Guarantees:
 *  - Writes are committed BEFORE returning (Room suspend DAO = committed on return),
 *    so `openAttempt`+`markAuthorizing` form the pre-SDK barrier.
 *  - Post-SDK marks run inside NonCancellable + IO: they survive screen pops and
 *    ViewModel clears (the Mindform window).
 *  - A failed pre-SDK write refuses processor entry. Post-SDK failures retain the
 *    uncertainty barrier and evidence; they never authorize another charge.
 *  - Financial durability is independent of optional shadow telemetry settings.
 */
@Singleton
class PaymentAttemptLedger @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val settingsRepository: TpvSettingsRepository
) {

    fun observeUnresolvedCount(venueId: String): kotlinx.coroutines.flow.Flow<Int> = dao.observeUnresolvedCount(venueId)

    fun isEnabled(): Boolean =
        true // Financial durability is mandatory; the setting only controls shadow observability.

    suspend fun openAttempt(
        attemptId: String,
        venueId: String,
        processor: String,
        amountCents: Long,
        tipCents: Long,
        recordingRoute: String,
        contextJson: String,
        kind: String = PaymentAttemptEntity.KIND_SALE
    ): Boolean {
        if (!isEnabled()) return true
        // Cancellation propagates; a failed write cannot become permission to charge.
        return runCatching {
            withContext(Dispatchers.IO) {
                val json = runCatching { com.google.gson.JsonParser.parseString(contextJson).asJsonObject }.getOrNull()
                val order = json?.get("orderId")?.takeUnless { it.isJsonNull }?.asString
                val fragment = if (order.isNullOrBlank()) null
                    else "\"orderId\":" + com.google.gson.Gson().toJson(order)
                // 🛑 La CERCA del cobro remoto viaja a la sentencia: una solicitud con lápida, con cancel
                // aceptado o con su desenlace final ya escrito NO admite ningún intento nuevo (C.5 / H.3).
                val solicitudRemota = json?.get("terminalPaymentRequestId")?.takeUnless { it.isJsonNull }
                    ?.asString?.takeIf { it.isNotBlank() }
                val now = System.currentTimeMillis()
                // 🔴 Comprobar-e-insertar en UNA sentencia. La versión anterior consultaba y
                // luego insertaba por separado: dos intentos simultáneos leían «terminal libre»
                // y los dos entraban (medido, 1 esperado y 2 obtenidos).
                val rowId = dao.reserveTerminal(
                    attemptId = attemptId, venueId = venueId, processor = processor, kind = kind,
                    amountCents = amountCents, tipCents = tipCents, recordingRoute = recordingRoute,
                    contextJson = contextJson, orderJsonFragment = fragment, now = now,
                    terminalPaymentRequestId = solicitudRemota
                )
                if (rowId > 0L) {
                    Timber.d("📒 [Libreta] PREPARANDO | attemptId=%s amount=%d+%d", attemptId, amountCents, tipCents)
                    true
                } else if (dao.getById(attemptId) != null) {
                    // PK collision: this attemptId already has a live row. This is the
                    // split/kiosk reuse signal (spec §6) — a second charge is about to
                    // ride an idempotency key the backend will dedupe into the FIRST
                    // record. Loud, never silent.
                    Timber.e("📒🚨 [Libreta] attemptId REUSE detected | attemptId=%s — possible dedup-swallowed charge", attemptId)
                    false
                } else {
                    // La terminal ya está reservada por otro intento (o la orden tiene un cobro
                    // sin resolver, o la solicitud del POS está cercada). No es un error: es el
                    // candado haciendo su trabajo. Se dice CUÁL, porque la pantalla lo traduce.
                    val cerca = solicitudRemota?.let { cercaDeSolicitud(it) } ?: CercaDeSolicitud.LIBRE
                    Timber.w(
                        "📒 [Libreta] entrada al procesador rechazada | attemptId=%s cerca=%s", attemptId, cerca,
                    )
                    false
                }
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "📒 [Libreta] openAttempt failed — processor entry refused")
            false // No durable attempt means the SDK must not run.
        }
    }

    /**
     * 🔴 «No me acuerdo» NO es prueba de que no se cobró.
     *
     * Un ViewModel recreado (el sistema mató la Activity, el cajero volvió atrás) nace sin
     * memoria del cobro en vuelo: sus banderas en RAM dicen «aquí no empezó nada» aunque en
     * la libreta haya una fila AUTORIZANDO. Decidir con esa memoria hacía que un callback
     * vacío se publicara como «cancelado, no se cobró» sobre un cobro que pudo ocurrir.
     * La libreta sobrevive a la muerte del ViewModel; la memoria no.
     *
     * Devuelve null SÓLO cuando la libreta afirma que no hay nada pendiente. Si la consulta
     * falla, devuelve [CobroSinResolver] con `attemptId` nulo: no se puede afirmar «no se
     * cobró» apoyándose en una libreta que no se pudo leer.
     */
    suspend fun cobroSinResolver(): CobroSinResolver? = try {
        // SIN `withContext(Dispatchers.IO)`: un DAO `suspend` de Room ya ejecuta en su propio
        // executor y suspende sin bloquear a quien llama, así que el envoltorio es redundante.
        // ⚠️ Lo COMPROBADO es sólo eso y que, con el envoltorio puesto, el test de recreación
        // observaba la corrutina detenida en `WaitingForResult`. Que el salto de dispatcher
        // FUERA la causa es una hipótesis NO demostrada — quitarlo cambió el síntoma, no se
        // probó el mecanismo. No apoyarse en esta línea para explicar otros comportamientos.
        dao.findUnresolvedCharge()?.let { CobroSinResolver(it.attemptId) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.e(error, "📒 [Libreta] no se pudo leer si hay un cobro sin resolver — se asume que SÍ")
        CobroSinResolver(null)
    }

    /** Pre-SDK barrier: committed before the SDK call is allowed to start. */
    suspend fun markAuthorizing(attemptId: String): Boolean = try {
        withContext(Dispatchers.IO) {
            // KERNEL_ACTIVO entra aquí como continuación EXPLÍCITA (kernel → autorización
            // online) y sólo por este camino: es del mismo intento, nunca de uno recreado.
            dao.casTransition(attemptId,
                listOf(PaymentAttemptEntity.STATE_PREPARANDO, PaymentAttemptEntity.STATE_KERNEL_ACTIVO),
                PaymentAttemptEntity.STATE_AUTORIZANDO, System.currentTimeMillis()) == 1
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.e(error, "Unable to persist authorization barrier")
        false
    }

    /**
     * 🔴 Write-ahead del kernel: se compromete ANTES de la llamada nativa capaz de
     * aprobar sola (contactless offline, EMV local).
     *
     * Sin esto, el contactless corría con la fila en PREPARANDO — el estado que
     * promete «ninguna llamada capaz de autorizar empezó» y que autoriza a
     * [markDiscardedBeforeCharge] a afirmar «no se cobró». Una muerte del proceso
     * justo después de un `RESULT_OFFLINE_APPROVED` dejaba entonces dinero movido
     * sobre una fila descartable.
     *
     * Devuelve false si no se pudo comprometer: quien llama NO debe entrar al kernel.
     */
    suspend fun markKernelEntered(attemptId: String): Boolean = try {
        withContext(Dispatchers.IO) {
            dao.casTransition(attemptId, listOf(PaymentAttemptEntity.STATE_PREPARANDO),
                PaymentAttemptEntity.STATE_KERNEL_ACTIVO, System.currentTimeMillis()) == 1
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.e(error, "📒 [Libreta] markKernelEntered failed — kernel entry refused")
        false
    }

    /**
     * The instant the host answers — BEFORE EMV completion, BEFORE publishing
     * Success. approved=false is an explicit host decline → DESCARTADA (spec §4.2).
     *
     * 🔴 Devuelve si la escritura QUEDÓ. Antes era `Unit` y se tragaba el fallo de
     * disco: la app publicaba `Success(OFFLINE_APPROVED)` sobre un cobro que no
     * quedó registrado en ninguna parte. Quien llama no puede declarar éxito con false.
     */
    suspend fun markHostResponded(
        attemptId: String,
        approved: Boolean,
        operationId: String?,
        referenceNumber: String?,
        authCode: String?
    ): Boolean {
        if (!isEnabled()) return true
        return runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val to = if (approved) PaymentAttemptEntity.STATE_HOST_RESPONDIO else PaymentAttemptEntity.STATE_DESCARTADA
                val n = dao.casHostResponded(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_AUTORIZANDO,
                        PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
                        PaymentAttemptEntity.STATE_PREPARANDO,
                        // The live verdict beats the sweep's guess: a charge stuck >quarantine
                        // threshold in AUTORIZANDO (hung-then-recovering SaleIcc, AngelPay D308
                        // relaunch) may get quarantined to INDETERMINADO by the sweep while still
                        // alive — the real host answer (approve OR late explicit decline) must
                        // still land, or the row is a permanent false "money moved, no record".
                        PaymentAttemptEntity.STATE_INDETERMINADO
                    ),
                    to, System.currentTimeMillis(),
                    operationId, referenceNumber, authCode, approved
                )
                logCas(n, attemptId, to)
                n == 1
            }
        }.getOrElse {
            Timber.e(it, "📒 [Libreta] markHostResponded failed — el desenlace NO quedó guardado")
            false
        }
    }

    /** From-PREPARANDO covers contactless offline-approved (no online auth ever runs). */
    suspend fun markAuthorized(attemptId: String, maskedPan: String?, cardBrand: String?, entryMode: String?) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithCardDetails(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                        PaymentAttemptEntity.STATE_AUTORIZANDO,
                        PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
                        PaymentAttemptEntity.STATE_PREPARANDO,
                        // Live verdict beats the sweep's guess: a still-alive charge quarantined
                        // to INDETERMINADO mid-auth must still be resolvable to AUTORIZADO.
                        PaymentAttemptEntity.STATE_INDETERMINADO
                    ),
                    PaymentAttemptEntity.STATE_AUTORIZADO, System.currentTimeMillis(),
                    maskedPan, cardBrand, entryMode
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_AUTORIZADO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markAuthorized failed") }
    }

    suspend fun markRecorded(attemptId: String) = casNonCancellable(
        attemptId,
        from = listOf(
            PaymentAttemptEntity.STATE_AUTORIZADO,
            PaymentAttemptEntity.STATE_HOST_RESPONDIO,
            // Live verdict beats the sweep's guess: a quarantined-but-alive attempt that
            // goes on to record successfully must close as REGISTRADO, not rot forever.
            PaymentAttemptEntity.STATE_INDETERMINADO
        ),
        to = PaymentAttemptEntity.STATE_REGISTRADO, label = "REGISTRADO"
    )

    suspend fun markRecordFailed(attemptId: String, error: String?) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithError(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_AUTORIZADO,
                        PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                        // Live verdict beats the sweep's guess: a quarantined-but-alive attempt
                        // whose recording fails must land in REGISTRO_FALLIDO so the queue
                        // handoff (markDeliveredToQueue) can still happen.
                        PaymentAttemptEntity.STATE_INDETERMINADO
                    ),
                    PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, System.currentTimeMillis(), error?.take(500)
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markRecordFailed failed") }
    }

    /** Once queued, pending_payments owns the money (its idempotency + retry) — the ledger row rests. */
    suspend fun markDeliveredToQueue(attemptId: String) = casNonCancellable(
        attemptId, from = listOf(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO),
        to = PaymentAttemptEntity.STATE_ENTREGADA_A_COLA, label = "ENTREGADA_A_COLA"
    )

    /**
     * 📒 El desenlace NO se conoce: el SDK volvió sin veredicto del procesador
     * (`U101`, `G505`, `TIMEOUT`…). Ver [DesenlaceDelCobro.INCIERTO].
     *
     * 🔴 Existe porque la alternativa era peor de las dos maneras: marcar DESCARTADA
     * afirma «no se cobró» sobre un cobro que quizá sí ocurrió (y esa fila se PODA a los
     * 7 días, así que la evidencia desaparece), y dejar la fila en AUTORIZANDO deja la
     * verdad al barrido, que tarda 10 minutos en cuarentenarla. INDETERMINADO es el
     * estado que la libreta ya reservaba para esto, y el DAO NUNCA lo poda.
     *
     * `from` deliberadamente corto: sólo desde los dos estados en que todavía no hay
     * veredicto. Un HOST_RESPONDIO, AUTORIZADO o REGISTRADO ya SABE lo que pasó y
     * degradarlo a «no sé» sería perder información, no ganarla.
     *
     * NonCancellable + IO como el resto de marcas post-SDK: se escribe aunque la
     * pantalla muera en el mismo instante.
     */
    suspend fun markIndeterminate(attemptId: String, reason: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithError(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_AUTORIZANDO,
                        PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
                        PaymentAttemptEntity.STATE_PREPARANDO
                    ),
                    PaymentAttemptEntity.STATE_INDETERMINADO, System.currentTimeMillis(), reason.take(500)
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_INDETERMINADO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markIndeterminate failed") }
    }

    /**
     * 🔴 El kernel rechazó EXPLÍCITAMENTE y sin salir a autorizar (`CtlssDenied`,
     * `CtlssUseContact`, `EmvNoApp`…): esas negativas se deciden DENTRO de la PAX y
     * nunca llegan al procesador, así que la terminal puede liberarse.
     *
     * Existe por disponibilidad, no por contabilidad: sin ella, cada tarjeta que el
     * kernel rechaza dejaría la caja sin poder cobrar hasta el barrido — y son
     * frecuentes (Testarudo, 2026-09-07: nueve rechazos en tres ventas).
     *
     * 🔴 SÓLO se llama con negativa explícita. Un TIMEOUT o un fallo desconocido pueden
     * esconder una transacción que sí avanzó: ésos se quedan retenidos como INDETERMINADO.
     */
    suspend fun markKernelRefused(attemptId: String, reason: String): Boolean {
        if (!isEnabled()) return true
        return runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithError(
                    attemptId,
                    listOf(PaymentAttemptEntity.STATE_KERNEL_ACTIVO, PaymentAttemptEntity.STATE_PREPARANDO),
                    PaymentAttemptEntity.STATE_DESCARTADA, System.currentTimeMillis(), reason.take(500)
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_DESCARTADA)
                n == 1
            }
        }.getOrElse {
            Timber.e(it, "📒 [Libreta] markKernelRefused failed — la terminal sigue retenida")
            false
        }
    }

    /** ONLY from PREPARANDO: a cancel during AUTORIZANDO has an unknown outcome — the row must live. */
    suspend fun markDiscardedBeforeCharge(attemptId: String, reason: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                dao.casWithError(
                    attemptId, listOf(PaymentAttemptEntity.STATE_PREPARANDO),
                    PaymentAttemptEntity.STATE_DESCARTADA, System.currentTimeMillis(), reason
                ) == 1
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "📒 [Libreta] markDiscardedBeforeCharge failed")
            false
        }
    }

    private suspend fun casNonCancellable(attemptId: String, from: List<String>, to: String, label: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                logCas(dao.casTransition(attemptId, from, to, System.currentTimeMillis()), attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] mark%s failed", label) }
    }

    /**
     * 🛑 Por qué una solicitud remota ya no admite otro intento (o [CercaDeSolicitud.LIBRE]).
     *
     * La pantalla la consulta cuando una barrera de la libreta falla, para decir lo que de verdad pasó
     * («El POS canceló este cobro. No se cobró.» / «Este cobro ya se cerró.») en vez de «No se pudo guardar
     * el intento». Un fallo de lectura NO se traduce como LIBRE: se contesta CERRADA, que es el lado que no
     * cobra de nuevo.
     */
    suspend fun cercaDeSolicitud(requestId: String?): CercaDeSolicitud {
        if (requestId.isNullOrBlank()) return CercaDeSolicitud.LIBRE
        return try {
            when (dao.cercaDeSolicitud(requestId)) {
                "CANCELADA_POR_EL_POS" -> CercaDeSolicitud.CANCELADA_POR_EL_POS
                "CERRADA" -> CercaDeSolicitud.CERRADA
                "LIBRE" -> CercaDeSolicitud.LIBRE
                else -> CercaDeSolicitud.LIBRE // la bandeja no tiene esa solicitud: nada que cercar
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "📒 [Libreta] no se pudo leer la cerca de %s — se asume cerrada", requestId)
            CercaDeSolicitud.CERRADA
        }
    }

    /**
     * 🛑 C.5 — CAS antes de registrar un cobro en EFECTIVO o CRIPTO de una solicitud remota.
     *
     * Devuelve [CercaDeSolicitud.LIBRE] si puede arrancar (y deja marcado `execution_started_at`, con lo
     * que un cancel posterior contesta ACTIVE en vez de afirmar «no se cobró»). Si el cancel del POS ya
     * ganó, o la solicitud ya está cerrada, devuelve el motivo y NO se registra nada. Fail-closed: una
     * lectura/escritura que falla devuelve CERRADA — quien no puede confirmar, no cobra.
     */
    suspend fun iniciarEjecucionNoTarjeta(requestId: String): CercaDeSolicitud = try {
        if (dao.iniciarEjecucionNoTarjeta(requestId, System.currentTimeMillis()) == 1) CercaDeSolicitud.LIBRE
        else when (val cerca = cercaDeSolicitud(requestId)) {
            CercaDeSolicitud.LIBRE -> CercaDeSolicitud.CERRADA // sin fila PROCESSING no hay nada que arrancar
            else -> cerca
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.e(error, "📒 [Libreta] no se pudo marcar el arranque de efectivo/cripto de %s", requestId)
        CercaDeSolicitud.CERRADA
    }

    private fun logCas(updated: Int, attemptId: String, to: String) {
        if (updated == 1) {
            Timber.d("📒 [Libreta] %s | attemptId=%s", to, attemptId)
        } else {
            // Not an error: races (worker vs callback vs manual) resolve by CAS — the loser logs.
            Timber.w("📒 [Libreta] CAS no-match → %s ignored | attemptId=%s", to, attemptId)
        }
    }
}

/**
 * Un cobro cuyo desenlace no se conoce. [attemptId] es null cuando sabemos que NO podemos
 * afirmar que no hubo cobro, pero no hay fila legible que marcar.
 */
class CobroSinResolver(val attemptId: String?)

/**
 * 🛑 Estado de la CERCA de una solicitud remota (C.5 / H.3). LIBRE va primero a propósito: es el caso
 * normal y el default seguro de un `when`.
 */
enum class CercaDeSolicitud {
    /** Admite intentos: ni cancelada, ni cerrada, ni con lápida. */
    LIBRE,

    /** El POS canceló y la terminal lo aceptó: no se cobró y no se va a cobrar. */
    CANCELADA_POR_EL_POS,

    /** Ya salió su desenlace final (o hay lápida): la terminal no la ejecuta; el POS debe reenviarla. */
    CERRADA,
}
