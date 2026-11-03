package com.jaac.avoqado_tpv.features.shift.data.repository

import androidx.room.withTransaction
import com.jaac.avoqado_tpv.features.payment.domain.model.withShiftBelonging
import com.jaac.avoqado_tpv.features.payment.data.ledger.checkHandoff
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.core.util.CuidV1
import com.jaac.avoqado_tpv.features.shift.data.dto.LocalCloseShiftRequest
import com.jaac.avoqado_tpv.features.shift.domain.ShiftBelonging
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligation
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary
import kotlinx.coroutines.ensureActive
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.OpenShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.toDomain
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Singleton

/**
 * Shift Repository
 *
 * Handles shift management operations (Toast/Square POS pattern).
 * Provides automatic calculation of sales, payments, and inventory.
 *
 * **Responsibilities:**
 * - Open new shifts with starting cash
 * - Close shifts with automatic calculations
 * - Get current active shift
 * - Handle network errors gracefully
 *
 * **Backend Automatic Calculations:**
 * - Payment breakdown (cash, card, voucher, other)
 * - Products sold count
 * - Inventory consumed (FIFO batches)
 * - Total sales, tips, orders
 *
 * **Usage:**
 * ```kotlin
 * // Open shift
 * val result = shiftRepository.openShift(venueId, staffId, startingCash)
 * when (result) {
 *     is Result.Success -> {
 *         val shift = result.data
 *         Timber.i("Shift opened: ${shift.id}")
 *     }
 *     is Result.Error -> {
 *         Timber.e("Failed to open shift: ${result.exception.message}")
 *     }
 * }
 *
 * // Close shift
 * val result = shiftRepository.closeShift(venueId, shiftId)
 * when (result) {
 *     is Result.Success -> {
 *         val shift = result.data
 *         Timber.i("Shift closed. Sales: ${shift.totalSales}")
 *     }
 *     is Result.Error -> {
 *         Timber.e("Failed to close shift: ${result.exception.message}")
 *     }
 * }
 * ```
 */
@Singleton
class ShiftRepository(
    private val apiService: ApiService,
    private val database: com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase,
    private val secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
    private val cachedShiftDao: com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao,
    /** 💸 La cola de reembolsos: BARRERA del cierre por debajo de TODAS las pantallas (auditoría de Codex F8). */
    private val refundQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository,
    private val appContext: android.content.Context,
    /** 🔒 D3: [ConsultaDelCierreDeCaja.preguntar]. Sin cablear no se le pregunta nada a nadie, como antes. */
    private val consultarAntesDeCerrar: suspend (venueId: String, pendientes: List<LocalShiftObligation>) -> Boolean = { _, _ -> false },
) {
    /**
     * D3: Hilt cablea la consulta. Constructor SECUNDARIO a propósito (mismo patrón que `RemotePaymentInbox`): un default en el
     * constructor `@Inject` genera una sobrecarga sintética que Dagger rechaza. `Lazy` porque `SocketManager` ya llega a este
     * repositorio por la bandeja, y la consulta sólo hace falta si hay algo que preguntar.
     */
    @Inject constructor(
        apiService: ApiService,
        database: com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase,
        secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
        cachedShiftDao: com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao,
        refundQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository,
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
        consulta: dagger.Lazy<ConsultaDelCierreDeCaja>,
    ) : this(apiService, database, secureStorage, cachedShiftDao, refundQueueRepository, appContext,
        { venueId, pendientes -> consulta.get().preguntar(venueId, pendientes) })

    private val _shiftChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Avisa cada vez que ESTE aparato abre o cierra el turno con éxito, sin importar desde qué
     * pantalla (QA Nexgo N86, 7-sep-2026): cada pantalla tiene su propio `ShiftViewModel` —el de
     * Inicio vive en el back-stack de Home— y el de «Turnos de caja» abría la caja sin que Inicio
     * se enterara: seguía en «Sin turno de caja» y bloqueaba Cobrar hasta reiniciar la app. Los
     * ViewModels lo escuchan y recargan; es un pulso sin dato porque la verdad la tiene el servidor.
     */
    val shiftChanges: kotlinx.coroutines.flow.SharedFlow<Unit> = _shiftChanges.asSharedFlow()

    /**
     * Check if Shift System is enabled
     * Used by UI and Logic to bypass shift requirements
     */
    fun isShiftSystemEnabled(): Boolean = secureStorage.isShiftSystemEnabled()

    /**
     * Open a new shift
     *
     * Creates a new work shift with starting cash amount.
     * Backend sets status = OPEN and begins tracking payments/orders.
     *
     * @param venueId Venue identifier for tenant isolation
     * @param staffId Staff member opening the shift
     * @param startingCash Initial cash amount in drawer
     * @param stationId Optional POS station identifier
     * @return Result with created shift or error
     */
    suspend fun openShift(
        venueId: String,
        staffId: String,
        startingCash: Double,
        stationId: String? = null
    ): Result<Shift> {
        val cash = BigDecimal.valueOf(startingCash)
        val saved = resultadoLocal {
            val authority = autoridadLocal(venueId, "tpv-shifts:create")
            database.withTransaction {
                verificarAutoridad(authority, "tpv-shifts:create")
                val previous = database.localShiftSessionDao().abierta(venueId)
                val row = guardarAperturaLocal(venueId, staffId, cash, stationId).getOrThrow()
                verificarAutoridad(authority, "tpv-shifts:create")
                row to (previous == null)
            }
        }
        if (saved is Result.Error) return saved
        val (row, nuevaIntencion) = saved.getOrThrow()
        despertarReplay(venueId, row.localSessionId, nuevaIntencion)
        _shiftChanges.tryEmit(Unit)
        return Result.Success(proyectar(row))
    }

    /**
     * Close an existing shift
     *
     * Closes the open shift with automatic calculation of all metrics.
     * Backend calculates:
     * - Payment breakdown (cash, card, voucher, other)
     * - Products sold count
     * - Inventory consumed (FIFO batches)
     * - Total sales, tips, orders, duration
     *
     * @param venueId Venue identifier for tenant isolation
     * @param shiftId Shift identifier to close
     * @param action Explicit additive reconciliation action; null preserves the legacy request
     * @param countedCash Physical cash total for COUNTED, represented and validated as BigDecimal
     * @return Result with updated shift (including calculations) or error
     */
    suspend fun closeShift(
        venueId: String,
        shiftId: String,
        action: CashReconciliationAction? = null,
        countedCash: BigDecimal? = null
    ): Result<Shift> {
        val saved = resultadoLocal {
            val authority = autoridadLocal(venueId, "tpv-shifts:close")
            // Con la autoridad ya capturada: si la sesión cambia mientras se pregunta, la transacción no guarda el conteo a nombre de otro.
            val sinRespuesta = preguntarAntesDeCerrar(venueId, shiftId)
            database.withTransaction {
                verificarAutoridad(authority, "tpv-shifts:close")
                val local = cajaDelCierre(venueId, shiftId) ?: importarCacheParaCierre(venueId, shiftId)
                val nuevaIntencion = local.closeOperationId == null
                guardarCierreLocal(venueId, local.localSessionId, action, countedCash, sinRespuesta).getOrThrow() to nuevaIntencion
            }
        }
        if (saved is Result.Error) return saved
        val (row, nuevaIntencion) = saved.getOrThrow()
        despertarReplay(venueId, row.localSessionId, nuevaIntencion)
        _shiftChanges.tryEmit(Unit)
        return Result.Success(proyectar(row))
    }

    private suspend fun cajaDelCierre(venueId: String, shiftId: String): LocalShiftSessionEntity? {
        val dao = database.localShiftSessionDao()
        return dao.leer(venueId, shiftId)
            ?: dao.abierta(venueId)?.takeIf { it.serverShiftId == shiftId }
            ?: dao.porServidor(venueId, shiftId)
    }

    /**
     * 🔒 D3 («ninguna duda de cobro sin salida», QA 2-nov): antes de rechazar el cierre por una operación de tarjeta o remota sin
     * resolver, se le pregunta a Avoqado ([ConsultaDelCierreDeCaja]). Va FUERA de la transacción de Caja, porque en ella no hay red: la
     * transacción vuelve a contar y es la que decide, y qué cuenta como obligación no cambia. Sin nada de eso pendiente no se
     * pregunta nada. Caja sin red sigue igual: no cierra y lo dice.
     *
     * @return true si Avoqado no contestó. Nunca lanza, salvo que cancelen a quien llama: un fallo aquí sólo deja decidir a la
     * transacción (también la cancelación de un alcance ajeno: Room contesta así con la base cerrada).
     */
    private suspend fun preguntarAntesDeCerrar(venueId: String, shiftId: String): Boolean = try {
        val caja = cajaDelCierre(venueId, shiftId)
        val pendientes = if (caja != null && caja.localStatus != "OPEN") emptyList()
            else database.localShiftSessionDao().ejecucionesPorResolver(venueId, caja?.localSessionId ?: shiftId)
        pendientes.isNotEmpty() && consultarAntesDeCerrar(venueId, pendientes)
    } catch (error: Exception) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        Timber.w(error, "No se pudo preguntar antes de cerrar la caja; decide la transacción")
        false
    }

    private suspend fun importarCacheParaCierre(venueId: String, shiftId: String): LocalShiftSessionEntity {
        val cached = cachedShiftDao.getCachedShift(venueId)
        check(cached != null && cached.venueId == venueId && cached.id == shiftId && cached.isOpen()) {
            "No se encontró evidencia de la caja original abierta en este aparato. Consulta Caja al recuperar conexión."
        }
        java.time.Instant.parse(cached.startTime)
        val dao = database.localShiftSessionDao()
        check(dao.abierta(venueId) == null) { "Hay otra caja local abierta; no se sustituirá por una foto anterior." }
        val now = maxOf(System.currentTimeMillis(), (dao.ultima(venueId)?.createdAt ?: 0L) + 1)
        val row = LocalShiftSessionEntity(localSessionId = CuidV1.next(), venueId = venueId, openingKind = "REMOTE",
            serverShiftId = cached.id, openingStatus = "CONFIRMED", originalOpenedAt = cached.startTime,
            startingCash = null, openedByStaffId = cached.staffId, localStatus = "OPEN",
            openingEvidenceJson = localGson.toJson(cached), createdAt = now, updatedAt = now)
        dao.insertar(row)
        return row
    }

    private suspend fun despertarReplay(venueId: String, sessionId: String, nuevaIntencion: Boolean) {
        try {
            // KEEP puede perder un gesto que llegó después de la fase OPEN de un worker vivo.
            // La novedad de la llave se decidió dentro de la misma Tx; duplicados sólo solicitan retry.
            if (nuevaIntencion) com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.continuarDespuesDeEstaPasada(appContext)
            else com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(appContext)
        }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            runCatching { database.localShiftSessionDao().anotarError(venueId, sessionId,
                "La caja está guardada; no se pudo agendar ahora. Se recuperará al arrancar, reconectar o por la sincronización periódica.", System.currentTimeMillis()) }
                .onFailure { Timber.w(it, "No se pudo añadir el diagnóstico al gesto ya guardado.") }
            Timber.w(error, "El gesto local de Caja sigue durable tras fallar el agendamiento.")
        }
    }

    /** COUNT y página comparten una foto; nunca se decide cerrar con una lista recortada. */
    suspend fun pagosQueBloqueanCierre(venueId: String, offset: Int): PaginaPagosDelCierre = database.withTransaction {
        val dao = database.pendingPaymentDao()
        val total = dao.contarBloqueanCierre(venueId)
        val ultima = ((total - 1).coerceAtLeast(0) / 20) * 20
        val inicio = ((offset.coerceAtLeast(0) / 20) * 20).coerceAtMost(ultima)
        PaginaPagosDelCierre(total, dao.bloqueanCierre(venueId, inicio), inicio)
    }

    /** Una salida o relevo DURANTE el UPDATE aborta la transacción; no basta con esconder su respuesta. */
    suspend fun reconocerPago(
        id: Long,
        venueId: String,
        staffId: String,
        sessionToken: String?,
        sigueEnElCierre: () -> Boolean,
    ): Int {
        fun vigente() = staffId.isNotBlank() && secureStorage.getVenueId() == venueId &&
            secureStorage.getStaffId() == staffId && secureStorage.getToken() == sessionToken && sigueEnElCierre()
        return database.withTransaction {
            check(vigente()) { "La sesión cambió. Vuelve a abrir Caja para revisar la venta." }
            val filas = database.pendingPaymentDao().reconocer(id, venueId, staffId, System.currentTimeMillis())
            check(vigente()) { "La sesión cambió. Vuelve a abrir Caja para revisar la venta." }
            filas
        }
    }

    /** T3 conectará esta variante; sólo efectivo que C7 ya deja reconocer, nunca una ejecución incierta. */
    suspend fun reconocerPagoEnCierreLocal(
        sessionId: String, idCash: Long, venueId: String, staffId: String, sessionToken: String?,
        sigueEnElCierre: () -> Boolean,
    ): Int = database.withTransaction {
        fun vigente() = staffId.isNotBlank() && secureStorage.getVenueId() == venueId &&
            secureStorage.getStaffId() == staffId && secureStorage.getToken() == sessionToken && sigueEnElCierre()
        check(vigente()) { "La sesión cambió. Vuelve a abrir Caja para revisar la venta." }
        val shifts = database.localShiftSessionDao()
        val originalSession = shifts.leer(venueId, sessionId) ?: error("No se encontró la caja original.")
        val payments = database.pendingPaymentDao()
        val original = payments.leerParaReconocer(idCash, venueId) ?: return@withTransaction 0
        val cash = original.referenceNumber.startsWith("CASH-", ignoreCase = true) ||
            original.authorizationNumber?.startsWith("EFECTIVO", ignoreCase = true) == true
        if (!cash || shifts.perteneceAlCierre(venueId, sessionId, "PAYMENT", idCash.toString()) != 1) return@withTransaction 0
        val changed = payments.reconocer(idCash, venueId, staffId, System.currentTimeMillis())
        check(vigente() && shifts.leer(venueId, sessionId) == originalSession) {
            "La sesión cambió. Vuelve a abrir Caja para revisar la venta."
        }
        if (changed == 1) {
            // Sólo se exceptúa NUESTRO acuse recién escrito; el resto del predicado sigue siendo el común.
            check(shifts.perteneceAlCierre(venueId, sessionId, "PAYMENT", idCash.toString(), idCash) == 1) {
                "La venta dejó de pertenecer a este cierre."
            }
            val saved = payments.leerParaReconocer(idCash, venueId) ?: error("No se pudo releer la venta revisada.")
            check(saved.shiftMembership == original.shiftMembership && saved.localShiftSessionId == original.localShiftSessionId &&
                saved.cashDrawerSessionId == original.cashDrawerSessionId && saved.idempotencyKey == original.idempotencyKey) {
                "La pertenencia de la venta cambió durante su revisión."
            }
        }
        changed
    }

    private fun canonicalCashCount(value: BigDecimal): String? {
        if (value.signum() < 0 || value > MAX_CASH_COUNT) return null
        return try {
            value.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private suspend fun findRecentlyClosedShift(venueId: String, shiftId: String): Shift? {
        return try {
            val response = apiService.getShiftHistory(venueId, pageSize = CLOSE_RECOVERY_LIMIT, pageNumber = 1)
            if (!response.isSuccessful) return null

            response.body()?.data
                ?.asSequence()
                ?.map { it.toDomain() }
                ?.firstOrNull { it.id == shiftId && it.status == ShiftStatus.CLOSED }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "⚠️ Bounded shift close recovery read failed")
            null
        }
    }

    /**
     * Get shift history for venue
     *
     * Returns list of closed shifts ordered by most recent first.
     * Used to display shift history on Turnos screen (Square/Toast pattern).
     *
     * **NOTE**: Backend returns all shifts (OPEN + CLOSED), so we filter for CLOSED on Android side.
     *
     * 🔴 **PAGINA (5-sep-2026).** Antes pedía `pageSize = limit` en UNA sola llamada y leía sólo
     * `body.data`. El servidor **recorta** el `pageSize` a 50 (`TOPE_DE_TURNOS_POR_PAGINA` en
     * `shift.tpv.service.ts`) y lo dice en `meta`, que nadie leía: los reportes pedían 200 turnos,
     * recibían 50, y **comparaban periodos con una tercera parte de los datos sin avisar de nada**.
     * Un número más chico que el real se lee como una caída de ventas, no como un error.
     *
     * Ahora pide de 50 en 50 y sigue mientras el servidor diga que quedan páginas Y siga haciendo
     * falta. Tres frenos, y los tres son necesarios:
     *   • [limit] — lo que el llamador pidió.
     *   • [desde] — cuando el turno más viejo recibido ya es anterior al periodo, seguir es gastar
     *     red por turnos que se van a descartar en el filtro de arriba.
     *   • [TOPE_DE_PAGINAS] — tope duro. Un `meta` mentiroso (o un servidor que siempre diga que
     *     hay más) no puede volverse un bucle infinito contra la API en una terminal de 1 GB.
     *
     * Si `meta` viene nulo (servidor viejo) se queda en UNA página: el comportamiento de hoy.
     *
     * @param venueId Venue identifier for tenant isolation
     * @param limit Maximum number of shifts to return (default: 10)
     * @param desde Instante mínimo de interés. Los turnos se piden del más nuevo al más viejo, así
     *   que en cuanto llega uno anterior a esta marca, ya no hacen falta más páginas. `null` =
     *   sin corte por fecha.
     * @return Result with list of closed shifts or error
     */
    suspend fun getShiftHistory(
        venueId: String,
        limit: Int = 10,
        desde: java.time.Instant? = null
    ): Result<List<Shift>> {
        return try {
            Timber.d("📋 Fetching shift history for venue: $venueId (limit: $limit, desde: $desde)")

            val cerrados = mutableListOf<Shift>()
            var pagina = 1
            var recibidos = 0
            var paginasPedidas = 0

            // 🔴 Nunca MÁS de lo que el servidor va a dar (50) ni MÁS de lo que se pidió. Ese
            // segundo tope importa: la pantalla de Turnos pide 10, y subirla a 50 por página le
            // quintuplicaría el payload a una terminal de 1 GB para enseñar los mismos 10 renglones.
            val tamanoDePagina = minOf(PAGE_SIZE_TURNOS, limit.coerceAtLeast(1))

            while (true) {
                // Backend returns paginated response: {success, data: [...], meta: {...}}
                val response = apiService.getShiftHistory(
                    venueId,
                    pageSize = tamanoDePagina,
                    pageNumber = pagina
                )
                paginasPedidas++

                if (!response.isSuccessful || response.body() == null) {
                    Timber.w("⚠️ Failed to fetch shift history: HTTP ${response.code()} (página $pagina)")
                    // 🔴 Un fallo a media paginación es un ERROR, no «lo que alcancé a traer».
                    // Devolver la lista parcial como éxito produciría exactamente el defecto que este
                    // arreglo mata: totales más chicos que la realidad, presentados como buenos.
                    return Result.Error(ApiException.HttpError(response.code(), response.message()))
                }

                val body = response.body()!!
                val enEstaPagina = body.data.map { it.toDomain() }
                recibidos += enEstaPagina.size
                cerrados += enEstaPagina.filter {
                    it.status == com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.CLOSED
                }

                val hayMas = body.meta?.hayOtraPagina() == true
                val alcanzaElCorte = desde != null && enEstaPagina.any { esAnteriorA(it, desde) }
                val faltan = cerrados.size < limit

                if (!hayMas || !faltan || alcanzaElCorte || enEstaPagina.isEmpty()) {
                    Timber.d(
                        "✅ Fetched ${cerrados.size} closed shifts (out of $recibidos total) " +
                            "en $paginasPedidas página(s) | hayMas=$hayMas | corteFecha=$alcanzaElCorte"
                    )
                    break
                }

                if (paginasPedidas >= TOPE_DE_PAGINAS) {
                    // Se dice en voz alta: el resultado está truncado y quien lo lea tiene que saberlo.
                    Timber.w(
                        "⚠️ Tope de $TOPE_DE_PAGINAS páginas alcanzado con el servidor diciendo que hay más " +
                            "— la historia de turnos va TRUNCADA en ${cerrados.size} turnos"
                    )
                    break
                }

                pagina++
            }

            Result.Success(cerrados.take(limit))
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error fetching shift history")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    /** `startTime` es ISO-8601; un turno con fecha ilegible NO corta la paginación (se ignora). */
    private fun esAnteriorA(shift: Shift, marca: java.time.Instant): Boolean =
        runCatching { java.time.Instant.parse(shift.startTime).isBefore(marca) }.getOrDefault(false)

    /**
     * Get current active shift for venue
     *
     * Returns the currently open shift, or null if no shift is active.
     * Used to display shift status banner on main screen.
     *
     * @param venueId Venue identifier for tenant isolation
     * @return Result with current shift (or null if no shift open) or error
     */
    suspend fun getCurrentShift(venueId: String): Result<Shift?> {
        val local = proyeccionLocalDeCaja(venueId)
        if (local != null && (local.openingStatus != "CONFIRMED" ||
                (local.localStatus != "OPEN" && local.closeStatus != "CONFIRMED")))
            return Result.Success(proyectar(local))
        return try {
            Timber.d("🔍 Fetching current shift for venue: $venueId")

            val response = apiService.getCurrentShift(venueId)

            if (response.isSuccessful) {
                // Extract shift from wrapper response
                val shiftDto = response.body()?.shift
                check(shiftDto == null || shiftDto.venueId == venueId) { "La caja recibida pertenece a otra sucursal." }
                if (shiftDto != null && shiftDto.status in listOf("CLOSING", "CLOSED")) {
                    // La respuesta corresponde a la identidad leída ANTES de HTTP, nunca a B que apareció después.
                    if (local != null && local.serverShiftId == shiftDto.id) observarCierreRemoto(local, shiftDto.status)
                    runCatching { cachedShiftDao.cacheShift(com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(shiftDto.toDomain(), venueId)) }
                        .onFailure { Timber.w(it, "La observación de cierre sigue durable aunque no se cacheen los totales.") }
                    val current = proyeccionLocalDeCaja(venueId)
                    return Result.Success(current?.let(::proyectar) ?: shiftDto.toDomain())
                }
                if (local?.localStatus == "OPEN" && local.openingStatus == "CONFIRMED" &&
                    local.closeOperationId == null && local.serverShiftId != null && shiftDto?.id != local.serverShiftId) {
                    val authority = autoridadLocal(venueId, "shifts:read")
                    val stateResponse = try {
                        apiService.getKnownShiftState(venueId, local.serverShiftId)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (error: Exception) {
                        Timber.w(error, "No se pudo confirmar el estado de la caja identificada; se conserva la captura local.")
                        null
                    }
                    verificarAutoridad(authority, "shifts:read")
                    val known = stateResponse?.takeIf { it.isSuccessful }?.body()
                    if (known?.id == local.serverShiftId && known.venueId == venueId && known.status in listOf("CLOSING", "CLOSED"))
                        observarCierreRemoto(local, requireNotNull(known.status), authority)
                }
                if (shiftDto == null && local?.localStatus == "OPEN") {
                    // Ausencia de current no demuestra un cierre de nuestra sesión identificada.
                    return Result.Success(proyeccionLocalDeCaja(venueId)?.let(::proyectar))
                }
                if (shiftDto != null && shiftDto.status == "OPEN") {
                    val saved = registrarCajaRemotaConocida(venueId, shiftDto)
                    if (saved is Result.Error) return saved
                    val original = saved.getOrThrow()
                    // La foto remota vieja no deshace un cierre local de ESA caja.
                    if (original.localStatus != "OPEN" || original.serverShiftId != shiftDto.id)
                        return Result.Success(proyectar(original))
                }
                val shift = shiftDto?.toDomain()?.copy(localShiftSessionId = database.localShiftSessionDao().abierta(venueId)?.localSessionId)

                if (shift != null) {
                    Timber.d("✅ Found active shift: ${shift.id}, Staff: ${shift.staffName}")
                    // Cache the open shift so the payment shift check (and WelcomeScreen) can fall
                    // back to it when the backend is unreachable. runCatching so a cache write can
                    // never break the live fetch path.
                    runCatching {
                        cachedShiftDao.cacheShift(
                            com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity.fromDomain(shift, venueId)
                        )
                    }.onFailure { Timber.w(it, "⚠️ Failed to cache shift") }
                } else {
                    Timber.d("ℹ️ No active shift found")
                    // Backend authoritatively reports no open shift — drop any stale cache so the
                    // offline fallback can't later resurrect a shift that was already closed.
                    runCatching { cachedShiftDao.clearCache(venueId) }
                        .onFailure { Timber.w(it, "⚠️ Failed to clear stale shift cache") }
                }

                Result.Success(shift)
            } else {
                Timber.w("⚠️ Failed to get current shift: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.message()))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (e: Exception) {
            Timber.e(e, "❌ Network error fetching current shift")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    private suspend fun observarCierreRemoto(original: LocalShiftSessionEntity, status: String,
        authority: LocalShiftAuthority = autoridadLocal(original.venueId, "shifts:read")) {
        val changed = database.withTransaction {
            verificarAutoridad(authority, "shifts:read")
            val n = database.localShiftSessionDao().observarCierreRemoto(original.venueId, original.localSessionId,
                requireNotNull(original.serverShiftId), "Cierre observado en el servidor ($status). Revisa la caja original; no se aplicó un conteo de este aparato.", System.currentTimeMillis())
            verificarAutoridad(authority, "shifts:read")
            n
        }
        if (changed == 1) _shiftChanges.tryEmit(Unit)
    }

    /**
     * Last known OPEN shift from the local cache, or null. Reads cache only — never hits network.
     *
     * Offline fallback for the payment shift check: when [getCurrentShift] fails with a
     * NetworkError and the venue has opted into offline card payments
     * (requireAvoqadoServerForCardPayment=false), the payment flow uses this so a shifts-enabled
     * venue can still charge while the backend is unreachable. Returns null when there's no cache
     * or the cached shift is not OPEN. The cache is kept fresh by [getCurrentShift] on every
     * successful fetch (open shift cached, no-shift clears it).
     */
    suspend fun getCachedOpenShift(venueId: String): Shift? {
        proyeccionLocalDeCaja(venueId)?.let { local ->
            if (local.localStatus == "OPEN") return proyectar(local)
            if (local.openingStatus != "CONFIRMED" || local.closeStatus != "CONFIRMED") return null
        }
        val cached = cachedShiftDao.getCachedShift(venueId)
        return if (cached != null && cached.isOpen()) {
            Timber.d("📦 Using cached OPEN shift ${cached.id} (cached ${cached.minutesSinceCached()} min ago)")
            cached.toDomainPartial()
        } else {
            null
        }
    }

    /** T1 sólo guarda. T2 conecta los callers a este núcleo y a UN coordinador, antes de instalar B2. */
    suspend fun guardarAperturaLocal(
        venueId: String, staffId: String, startingCash: BigDecimal, stationId: String? = null,
    ): Result<LocalShiftSessionEntity> = resultadoLocal {
        val cash = canonicalCashCount(startingCash) ?: error("El fondo no es válido.")
        val authority = autoridadLocal(venueId, "tpv-shifts:create")
        check(authority.staffId == staffId && authority.shiftsEnabled == true) { "Verifica la sesión y la configuración de Caja." }
        database.withTransaction {
            verificarAutoridad(authority, "tpv-shifts:create")
            val dao = database.localShiftSessionDao()
            val existing = dao.abierta(venueId)
            if (existing != null) {
                verificarAutoridad(authority, "tpv-shifts:create")
                return@withTransaction existing
            }
            val id = CuidV1.next()
            val now = System.currentTimeMillis()
            val createdAt = maxOf(now, (dao.ultima(venueId)?.createdAt ?: 0L) + 1)
            val openedAt = java.time.Instant.ofEpochMilli(now).toString()
            val row = LocalShiftSessionEntity(
                localSessionId = id, venueId = venueId, openingKind = "LOCAL", openingOperationId = id,
                openingRequestJson = localGson.toJson(OpenShiftRequest(venueId, staffId, cash.toDouble(), stationId,
                    operationId = id, localId = id, openedAt = openedAt, isOfflineReplay = true)),
                openingStatus = "PENDING", originalOpenedAt = openedAt, startingCash = cash,
                openedByStaffId = staffId, localStatus = "OPEN", createdAt = createdAt, updatedAt = now,
            )
            dao.insertar(row)
            verificarAutoridad(authority, "tpv-shifts:create")
            row
        }
    }

    suspend fun guardarCierreLocal(
        venueId: String, localSessionId: String, action: CashReconciliationAction? = null, countedCash: BigDecimal? = null,
        /** D3: [preguntarAntesDeCerrar] no obtuvo respuesta de Avoqado; sólo cambia lo que dice el mensaje. */
        sinRespuesta: Boolean = false,
    ): Result<LocalShiftSessionEntity> = resultadoLocal {
        val count = conteoLocal(action, countedCash)
        val authority = autoridadLocal(venueId, "tpv-shifts:close")
        database.withTransaction {
            verificarAutoridad(authority, "tpv-shifts:close")
            val dao = database.localShiftSessionDao()
            val original = dao.leer(venueId, localSessionId) ?: error("No se encontró la caja guardada en este aparato.")
            if (original.closeOperationId != null) {
                verificarAutoridad(authority, "tpv-shifts:close")
                return@withTransaction original
            }
            check(original.localStatus == "OPEN") { "La caja ya está cerrada en este aparato." }
            // PENDING/SYNCING de la cola ya representa el dinero. Una ejecución todavía capaz de autorizar no.
            val intentos = dao.intentosSinRepresentar(venueId, localSessionId)
            val remotas = dao.ejecucionesRemotas(venueId, localSessionId)
            check(intentos == 0 && remotas == 0) {
                com.jaac.avoqado_tpv.features.shift.domain.textoDelCierreBloqueado(intentos, remotas, sinRespuesta)
            }
            val id = CuidV1.next()
            val now = System.currentTimeMillis()
            val closedAt = java.time.Instant.ofEpochMilli(now).toString()
            val request = LocalCloseShiftRequest(venueId, id, closedAt, authority.staffId, original.openingOperationId, action, count)
            check(dao.cerrar(venueId, localSessionId, id, localGson.toJson(request), closedAt, count, action?.name,
                authority.staffId, now, if (original.openingDisposition == "ADOPTED") "REVIEW" else "PENDING") == 1) {
                "La caja cambió. Vuelve a abrir Caja."
            }
            val saved = dao.leer(venueId, localSessionId) ?: error("No se pudo releer el cierre guardado.")
            verificarAutoridad(authority, "tpv-shifts:close")
            saved
        }
    }

    /** Base transaccional para T0; NO sustituye las guardas del SDK ni admite sus callbacks ya aceptados. */
    suspend fun <T> guardarNuevaObligacionDeCaja(
        venueId: String, isRefund: Boolean = false, terminalRequestId: String? = null, guardar: suspend (ShiftBelonging) -> T,
    ): T {
        val permission = if (isRefund) "payments:refund" else "payments:create"
        val authority = autoridadLocal(venueId, permission)
        return database.withTransaction {
            verificarAutoridad(authority, permission)
            val inbox = terminalRequestId?.let { database.remotePaymentRequestDao().getById(it) }
            check(inbox == null || inbox.venueId == venueId) { "La solicitud pertenece a otra sucursal." }
            val captured = inbox?.takeIf { it.status == "PROCESSING" }?.let {
                ShiftBelonging.fromStored(it.shiftMembership, it.localShiftSessionId, null, it.cashDrawerSessionId)
            }?.takeIf { it.kind != "UNKNOWN" }
            if (captured == null) check(authority.shiftsEnabled != null) { "No se pudo leer la configuración de Caja." }
            val open = database.localShiftSessionDao().abierta(venueId)
            val belonging = captured ?: when {
                authority.shiftsEnabled == false -> ShiftBelonging("NO_SHIFT_SYSTEM")
                open != null -> ShiftBelonging("MANAGED", open.localSessionId, open.serverShiftId, open.cashDrawerSessionId)
                isRefund -> ShiftBelonging("UNKNOWN") // La devolución nunca hereda la caja de su pago original.
                else -> error("Abre la caja antes de iniciar otra venta.")
            }
            val restored = if (belonging.kind == "MANAGED") {
                val session = database.localShiftSessionDao().leer(venueId, requireNotNull(belonging.localSessionId))
                    ?: error("No se encontró la caja original de la operación.")
                check(session.localStatus == "OPEN") { "La caja original ya está cerrada. No se inició otra operación." }
                belonging.copy(serverShiftId = session.serverShiftId, cashDrawerSessionId = session.cashDrawerSessionId)
            } else belonging
            val saved = guardar(restored)
            verificarAutoridad(authority, permission)
            saved
        }
    }

    /** Lookup acotado, sólo lectura: un dueño existente nunca se readmite como otro cobro. */
    suspend fun pagoTieneDueno(venueId: String, key: String?, reference: String): Boolean = database.withTransaction {
        database.pendingPaymentDao().tieneDueno(venueId, key, reference) ||
            (key?.let { database.paymentAttemptDao().getById(it) }?.venueId == venueId)
    }

    /** Compara el contrato original sin tocar el dueño ni tomar referencias de la sesión actual. */
    suspend fun contratoDelPagoGuardadoCoincide(context: com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext,
        reference: String): Boolean = database.withTransaction {
        val row = database.pendingPaymentDao().findByReference(reference, context.venueId) ?: return@withTransaction false
        val orderId = when (context) {
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.FastPayment -> null
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.OrderPayment -> context.orderId
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment -> context.orderId
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.RefundPayment -> return@withTransaction false
        }
        val processor = if (context is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment) "ANGELPAY" else "BLUMON"
        runCatching {
            !context.idempotencyKey.isNullOrBlank() && row.idempotencyKey == context.idempotencyKey &&
                row.amount.toBigDecimal().compareTo(context.amount) == 0 && row.tip.toBigDecimal().compareTo(context.tip) == 0 &&
                row.orderId == orderId && row.paymentProcessor == processor && row.terminalPaymentRequestId == context.terminalPaymentRequestId &&
                (context.shiftMembership == "UNKNOWN" || (context.shiftMembership == row.shiftMembership &&
                    context.localShiftSessionId == row.localShiftSessionId && context.cashDrawerSessionId == row.cashDrawerSessionId))
        }.getOrDefault(false)
    }

    /** Callback/recuperación: restaura el dueño ya aceptado, sin exigir OPEN ni consultar el modo actual. */
    suspend fun restaurarContextoDelIntento(context: com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext):
        com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext {
        val key = context.idempotencyKey ?: return context
        val row = database.paymentAttemptDao().getById(key) ?: return context
        check(row.venueId == context.venueId) { "La operación pertenece a otra sucursal." }
        val contract = row.checkHandoff(context)
        if (contract.conflict != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                database.pendingPaymentDao().noteHandoffConflict(row.venueId, row.attemptId, "handoff_contrato_contradictorio:${contract.conflict}")
            }
            error("El registro no coincide con su intento original. La evidencia permanece guardada para revisión.")
        }
        val snapshot = runCatching { com.google.gson.JsonParser.parseString(row.paymentContextJson).asJsonObject }.getOrNull()
        fun text(name: String): String? = runCatching { snapshot?.get(name)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()
        val captured = ShiftBelonging.fromStored(row.shiftMembership, row.localShiftSessionId, null, row.cashDrawerSessionId)
        val coherent = snapshot != null && (row.shiftMembership == "UNKNOWN" ||
            (text("shiftMembership") == row.shiftMembership && text("localShiftSessionId") == row.localShiftSessionId &&
                text("cashDrawerSessionId") == row.cashDrawerSessionId))
        if (!coherent) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                database.paymentAttemptDao().anotarProblemaDeSnapshot(row.venueId, row.attemptId, "snapshot_caja_ilegible_o_contradictorio")
            }
            Timber.e("El contexto redundante del intento %s no acredita otra pertenencia; se conserva su dueño original.", row.attemptId)
            return context.withShiftBelonging(captured)
        }
        val remote = text("shiftId")
        val originalStaff = text("staffId")?.takeIf { it.isNotBlank() }
        val originalContext = if (originalStaff == null) context else when (context) {
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.FastPayment -> context.copy(staffId = originalStaff)
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.OrderPayment -> context.copy(staffId = originalStaff)
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment -> context.copy(staffId = originalStaff)
            is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.RefundPayment -> context.copy(staffId = originalStaff)
        }
        return originalContext.withShiftBelonging(ShiftBelonging.fromStored(row.shiftMembership, row.localShiftSessionId, remote, row.cashDrawerSessionId))
    }

    /** Guarda la identidad antes del POST; una respuesta perdida se consulta, nunca se vuelve a emitir. */
    suspend fun iniciarCriptoDurable(
        venueId: String, request: com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest,
        terminalRequestId: String? = null,
    ): com.jaac.avoqado_tpv.core.data.network.CryptoPaymentData {
        val key = request.idempotencyKey ?: CuidV1.next()
        require(key.matches(Regex("c[a-z0-9]{24}"))) { "La operación cripto necesita su llave original." }
        val previous = database.localCryptoOperationDao().leer(venueId, key)
        val canonical = request.copy(idempotencyKey = key, staffId = previous?.staffId ?: request.staffId)
        val body = localGson.toJsonTree(canonical).asJsonObject.apply { addProperty("terminalPaymentRequestId", terminalRequestId) }.toString()
        val dao = database.localCryptoOperationDao()
        val fresh = if (previous != null) {
            autoridadLocal(venueId, "payments:create")
            check(previous.requestJson == body) { "La llave cripto ya identifica otro contrato." }
            false
        } else guardarNuevaObligacionDeCaja(venueId, terminalRequestId = terminalRequestId) { belonging ->
            if (belonging.kind == "MANAGED") {
                val originalShift = database.localShiftSessionDao().leer(venueId, requireNotNull(belonging.localSessionId))
                if (originalShift?.openingStatus != "CONFIRMED" || originalShift.serverShiftId.isNullOrBlank())
                    throw ShiftMappingPendingException("El pago cripto espera la apertura de su caja. Vuelve a consultar antes de generar el QR.")
            }
            val previous = dao.leer(venueId, key)
            if (previous != null) {
                check(previous.requestJson == body) { "La llave cripto ya identifica otro contrato." }
                false
            } else {
                val now = System.currentTimeMillis()
                dao.insertar(com.jaac.avoqado_tpv.core.data.local.entity.LocalCryptoOperationEntity(
                    idempotencyKey = key, venueId = venueId, staffId = request.staffId, requestJson = body, terminalPaymentRequestId = terminalRequestId,
                    shiftMembership = belonging.kind, localShiftSessionId = belonging.localSessionId,
                    cashDrawerSessionId = belonging.cashDrawerSessionId, serverShiftId = belonging.serverShiftId,
                    emissionStatus = "PROCESSING", createdAt = now, updatedAt = now))
                true
            }
        }
        val result = try {
            val response = if (fresh) apiService.initiateDurableCryptoPayment(venueId, canonical) else apiService.getCryptoOperation(venueId, key)
            check(response.isSuccessful && response.body()?.success == true) { "El inicio cripto está pendiente de consultar. No generes otro pago." }
            response.body()?.data ?: error("El inicio cripto no tiene un resultado acreditado.")
        } catch (error: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val saved = dao.leer(venueId, key)
                dao.guardarResultado(venueId, key, "UNCERTAIN", saved?.financialStatus, saved?.financialRecorded,
                    saved?.operationResultJson, error.message, saved?.requestId, System.currentTimeMillis())
            }
            throw error
        }
        guardarResultadoCripto(venueId, key, result)
        return com.jaac.avoqado_tpv.core.data.network.CryptoPaymentData(
            requestId = result.requestId?.takeIf { it.isNotBlank() } ?: error("La emisión cripto requiere revisión; no se generará otro QR."),
            paymentId = result.paymentId ?: error("Falta el pago cripto."),
            paymentUrl = result.paymentUrl ?: error("La emisión cripto requiere revisión; no se generará otro QR."),
            expiresAt = result.expiresAt ?: error("Falta la vigencia cripto."),
            expiresInSeconds = result.expiresInSeconds ?: 0, cryptoAddress = result.cryptoAddress, cryptoSymbol = result.cryptoSymbol)
    }

    /** La consulta acreditada del servidor es la única que libera la obligación contable, no cancelar la UI. */
    suspend fun consultarCriptoDurable(venueId: String, key: String): com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto {
        val row = database.localCryptoOperationDao().leer(venueId, key) ?: error("No se encontró la operación cripto.")
        val authority = autoridadLocal(venueId, "payments:create")
        val response = apiService.getCryptoOperation(venueId, row.idempotencyKey)
        check(response.isSuccessful && response.body()?.success == true) { "El pago cripto sigue pendiente de consulta." }
        val result = response.body()?.data ?: error("Falta el resultado cripto.")
        verificarAutoridad(authority, "payments:create")
        guardarResultadoCripto(venueId, key, result)
        return result
    }

    suspend fun consultarCriptoPorSolicitud(venueId: String, requestId: String):
        com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto? =
        database.localCryptoOperationDao().porSolicitud(venueId, requestId)?.let { consultarCriptoDurable(venueId, it.idempotencyKey) }

    /** Un aviso aceptado sobrevive PIN/venue/pantalla; se consulta después, sin inferir cobrado por una señal del proveedor. */
    suspend fun anotarAvisoCripto(venueId: String, paymentId: String, requestId: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val dao = database.localCryptoOperationDao()
            val row = dao.leer(venueId, paymentId) ?: return@withContext
            val old = row.operationResultJson?.let { localGson.fromJson(it,
                com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto::class.java) }
            check(old?.requestId == null || old.requestId == requestId) { "El aviso cripto contradice su identidad." }
            val snapshot = old ?: com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto(
                paymentId = paymentId, requestId = requestId)
            check(dao.guardarResultado(venueId, row.idempotencyKey, row.emissionStatus, row.financialStatus,
                row.financialRecorded, localGson.toJson(snapshot), "Aviso recibido: pendiente de consulta exacta", requestId, System.currentTimeMillis()) == 1) {
                "El aviso cripto contradice su correlación guardada."
            }
        }
    }

    private suspend fun guardarResultadoCripto(venueId: String, key: String,
        result: com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto) {
        check(result.operation?.idempotencyKey == key && result.paymentId == key) { "El resultado cripto no corresponde a esta operación." }
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            check(database.localCryptoOperationDao().guardarResultado(venueId, key, result.operation?.emissionStatus ?: "UNCERTAIN",
                result.operation?.financialStatus, result.operation?.financialRecorded, localGson.toJson(result), null, result.requestId, System.currentTimeMillis()) == 1) {
                "El resultado cripto contradice su correlación guardada."
            }
        }
    }

    /** T2 consulta dentro de su claim/transacción inmediatamente antes del POST CLOSE; C7 legacy sigue conservadora. */
    suspend fun obligacionesDelCierreLocal(venueId: String, sessionId: String, offset: Int = 0):
        com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligationPage = database.withTransaction {
        val dao = database.localShiftSessionDao()
        check(dao.leer(venueId, sessionId) != null) { "No se encontró la caja original." }
        val total = dao.contarObligaciones(venueId, sessionId)
        val last = ((total - 1).coerceAtLeast(0) / 20) * 20
        val start = (offset.coerceAtLeast(0) / 20 * 20).coerceAtMost(last)
        com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligationPage(total, dao.paginaObligaciones(venueId, sessionId, start), start)
    }

    suspend fun puedeEnviarCierreLocal(venueId: String, sessionId: String): Boolean = database.withTransaction {
        val row = database.localShiftSessionDao().leer(venueId, sessionId) ?: return@withTransaction false
        row.localStatus == "CLOSED" && database.localShiftSessionDao().contarObligaciones(venueId, sessionId) == 0
    }

    /** Sólo hechos durables: el total/esperado del negocio NO se infiere de cached_shift. */
    suspend fun proyeccionLocalDeCaja(venueId: String): LocalShiftSessionEntity? = database.withTransaction {
        val dao = database.localShiftSessionDao()
        dao.abierta(venueId) ?: dao.ultima(venueId)
    }

    /** Adaptador de lectura para pintar Room ANTES de cualquier HTTP; usa el mismo proyector. */
    suspend fun leerCajaLocal(venueId: String): CajaLocalSnapshot? = database.withTransaction {
        val row = proyeccionLocalDeCaja(venueId) ?: return@withTransaction null
        CajaLocalSnapshot(proyectar(row), row, resumirCajaLocal(row))
    }

    /** Detalle acotado de la MISMA página de barrera. Un solo SELECT, sin N+1 ni ACK genérico. */
    suspend fun efectivoRevisableDelCierre(venueId: String, page: com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligationPage): Set<Long> = database.withTransaction {
        val ids = page.rows.filter { it.owner == "PAYMENT" }.mapNotNull { it.ownerId.toLongOrNull() }
        if (ids.isEmpty()) return@withTransaction emptySet()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT id FROM pending_payments WHERE venue_id = ? AND id IN ($placeholders) " +
            "AND acknowledged = 0 AND sync_status NOT IN ('PENDING','SYNCING') " +
            "AND (sync_status = 'FAILED' OR requiere_revision = 1) " +
            "AND (UPPER(reference_number) LIKE 'CASH-%' OR UPPER(authorization_number) LIKE 'EFECTIVO%')"
        database.openHelper.readableDatabase.query(sql, (listOf<Any>(venueId) + ids).toTypedArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    }

    suspend fun resumenLocalDeCaja(venueId: String, localSessionId: String): LocalShiftSummary? =
        database.localShiftSessionDao().leer(venueId, localSessionId)?.let(::resumirCajaLocal)

    private fun resumirCajaLocal(row: LocalShiftSessionEntity) = LocalShiftSummary(row.startingCash?.let(::BigDecimal),
        row.countedCash?.let(::BigDecimal), row.localStatus, row.openingStatus, row.closeStatus, row.adoptionNotice)

    suspend fun sesionesLocales(venueId: String, offset: Int): LocalShiftPage = database.withTransaction {
        val dao = database.localShiftSessionDao()
        val total = dao.contar(venueId)
        val last = ((total - 1).coerceAtLeast(0) / 20) * 20
        val start = (offset.coerceAtLeast(0) / 20 * 20).coerceAtMost(last)
        LocalShiftPage(total, dao.pagina(venueId, start), start)
    }

    /** Una caja vista en el servidor no finge un gesto OPEN de este aparato; lo pendiente local gana. */
    suspend fun registrarCajaRemotaConocida(
        venueId: String, remote: com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto,
    ): Result<LocalShiftSessionEntity> = resultadoLocal {
        val authority = autoridadLocal(venueId, "shifts:read")
        check(remote.venueId == venueId && remote.id.isNotBlank() && remote.status == "OPEN") { "No se recibió una caja abierta de esta sucursal." }
        val cash = remote.startingCash.toBigDecimalOrNull()?.let(::canonicalCashCount) ?: error("Falta el fondo de la caja remota.")
        database.withTransaction {
            verificarAutoridad(authority, "shifts:read")
            val dao = database.localShiftSessionDao()
            val current = dao.abierta(venueId) ?: dao.ultima(venueId)
            if (current != null && (current.localStatus == "OPEN" || current.serverShiftId == remote.id ||
                    current.openingStatus in listOf("PENDING", "SYNCING", "REVIEW") || current.closeStatus in listOf("PENDING", "SYNCING", "REVIEW"))) {
                verificarAutoridad(authority, "shifts:read")
                return@withTransaction current
            }
            val now = maxOf(System.currentTimeMillis(), (current?.createdAt ?: 0L) + 1)
            val row = LocalShiftSessionEntity(localSessionId = CuidV1.next(), venueId = venueId, openingKind = "REMOTE",
                serverShiftId = remote.id, cashDrawerSessionId = remote.cashDrawerSessionId, openingStatus = "CONFIRMED",
                originalOpenedAt = remote.startTime, startingCash = cash, openedByStaffId = remote.staffId,
                localStatus = "OPEN", openingEvidenceJson = localGson.toJson(remote), createdAt = now, updatedAt = now)
            dao.insertar(row)
            verificarAutoridad(authority, "shifts:read")
            row
        }
    }

    /** T2 entrega el resultado de la MISMA petición; GET parcial no puede inventar el fondo aplicado. */
    suspend fun registrarResultadoAperturaLocal(
        venueId: String, localSessionId: String, response: com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto,
        claimToken: String? = null,
    ): Result<LocalShiftSessionEntity> = resultadoLocal {
        val authority = autoridadLocal(venueId, "shifts:read")
        check(response.venueId == venueId && response.id.isNotBlank() && !response.cashDrawerSessionId.isNullOrBlank()) { "La respuesta no identifica la pareja de esta caja." }
        val disposition = response.openingDisposition
        check(disposition in listOf("OWN", "ADOPTED")) { "Falta el resultado de la apertura." }
        val applied = response.fondoAplicado?.toBigDecimalOrNull()?.let(::canonicalCashCount) ?: error("Falta el fondo aplicado por el servidor.")
        database.withTransaction {
            verificarAutoridad(authority, "shifts:read")
            val dao = database.localShiftSessionDao()
            val original = dao.leer(venueId, localSessionId) ?: error("No se encontró la apertura local.")
            check(original.openingKind == "LOCAL" && original.openingOperationId == localSessionId) { "La respuesta no corresponde a una apertura local." }
            check(original.claimToken == claimToken) { "La operación ya tiene otro responsable de sincronización." }
            // S1 devuelve la llave de la pareja ajena al adoptar; sólo OWN debe hacer eco de K.
            if (disposition == "OWN") check(response.localId == localSessionId) { "La respuesta pertenece a otra apertura." }
            if (original.openingStatus == "CONFIRMED") {
                check(original.serverShiftId == response.id && original.cashDrawerSessionId == response.cashDrawerSessionId &&
                    original.openingDisposition == disposition && original.remoteStartingCash == applied) { "La respuesta contradice la apertura ya acreditada." }
                verificarAutoridad(authority, "shifts:read")
                return@withTransaction original // No reescribir evidencia original ni conteos por una repetición.
            }
            val notice = if (disposition == "ADOPTED") "Ya había una caja abierta con fondo $applied. Tu apertura de ${original.startingCash} no se registró como una caja nueva." else null
            check(dao.confirmarApertura(venueId, localSessionId, response.id, response.cashDrawerSessionId!!, disposition!!,
                applied, localGson.toJson(response), notice, System.currentTimeMillis(), claimToken) == 1) { "La apertura cambió. Vuelve a consultar su estado." }
            val saved = dao.leer(venueId, localSessionId)!!
            verificarAutoridad(authority, "shifts:read")
            saved
        }
    }

    /** Un gesto EXPLÍCITO nuevo sobre la pareja real; el cierre de la apertura ajena sigue en REVIEW. */
    suspend fun adoptarCajaRemotaParaNuevosGestos(venueId: String, sourceLocalId: String): Result<LocalShiftSessionEntity> = resultadoLocal {
        val authority = autoridadLocal(venueId, "tpv-shifts:create")
        database.withTransaction {
            verificarAutoridad(authority, "tpv-shifts:create")
            val dao = database.localShiftSessionDao()
            val source = dao.leer(venueId, sourceLocalId) ?: error("No se encontró la apertura que requiere revisión.")
            check(source.openingDisposition == "ADOPTED" && source.openingStatus == "CONFIRMED" &&
                source.serverShiftId != null && source.cashDrawerSessionId != null && source.remoteStartingCash != null) { "La pareja remota no está acreditada." }
            val alreadyOpen = dao.abierta(venueId)
            if (source.adoptionAcceptedAt != null) {
                check(alreadyOpen?.openingKind == "REMOTE" && alreadyOpen.serverShiftId == source.serverShiftId) { "La caja remota ya se adoptó; consulta su estado actual." }
                return@withTransaction alreadyOpen
            }
            check(alreadyOpen == null || alreadyOpen.localSessionId == sourceLocalId) { "Ya hay otra caja abierta en este aparato." }
            val now = maxOf(System.currentTimeMillis(), (dao.ultima(venueId)?.createdAt ?: 0L) + 1)
            check(dao.aceptarAdopcion(venueId, sourceLocalId, source.serverShiftId, source.cashDrawerSessionId, now) == 1)
            val row = LocalShiftSessionEntity(localSessionId = CuidV1.next(), venueId = venueId, openingKind = "REMOTE",
                serverShiftId = source.serverShiftId, cashDrawerSessionId = source.cashDrawerSessionId,
                openingStatus = "CONFIRMED", originalOpenedAt = localGson.fromJson(source.openingEvidenceJson,
                    com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto::class.java).startTime, startingCash = source.remoteStartingCash,
                openedByStaffId = localGson.fromJson(source.openingEvidenceJson,
                    com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto::class.java).staffId,
                localStatus = "OPEN", createdAt = now, updatedAt = now)
            dao.insertar(row)
            verificarAutoridad(authority, "tpv-shifts:create")
            row
        }
    }

    /** Una pasada acotada; el disparador y la cadencia siguen siendo de WorkManager. */
    suspend fun sincronizarAperturasLocales(venueId: String? = secureStorage.getVenueId()): Int {
        if (venueId.isNullOrBlank() || secureStorage.getVenueId() != venueId) return 0
        var completed = 0
        for (candidate in database.localShiftSessionDao().porReproducir(venueId)) {
            if (candidate.openingStatus !in listOf("PENDING", "SYNCING")) continue
            val row = reclamarSesion(candidate, opening = true) ?: continue
            val token = requireNotNull(row.claimToken)
            try {
                val authority = autoridadLocal(venueId, "tpv-shifts:create")
                val request = localGson.fromJson(row.openingRequestJson, OpenShiftRequest::class.java)
                check(request.operationId == row.openingOperationId && request.localId == row.localSessionId && request.staffId == row.openedByStaffId)
                verificarAutoridad(authority, "tpv-shifts:create")
                val response = apiService.openShift(venueId, request)
                if (!response.isSuccessful || response.body()?.success != true) {
                    soltarSesion(row, if (response.code() in listOf(400, 404, 422)) "REVIEW" else "PENDING", "Apertura HTTP ${response.code()}: consulta o reintenta con la misma llave.")
                    continue
                }
                verificarAutoridad(authority, "tpv-shifts:create")
                database.withTransaction {
                    registrarResultadoAperturaLocal(venueId, row.localSessionId, response.body()!!.data, token).getOrThrow()
                    check(database.localShiftSessionDao().liberar(venueId, row.localSessionId, token, "PENDING", null, System.currentTimeMillis()) == 1)
                }
                completed++
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { soltarSesion(row, "PENDING", error.message ?: "La apertura espera conexión.") }
        }
        if (completed > 0) _shiftChanges.tryEmit(Unit)
        return completed
    }

    suspend fun sincronizarCierresLocales(venueId: String? = secureStorage.getVenueId()): Int {
        if (venueId.isNullOrBlank() || secureStorage.getVenueId() != venueId) return 0
        var completed = 0
        for (candidate in database.localShiftSessionDao().porReproducir(venueId)) {
            if (candidate.openingStatus != "CONFIRMED" || candidate.closeStatus !in listOf("PENDING", "SYNCING")) continue
            val row = reclamarSesion(candidate, opening = false) ?: continue
            try {
                val authority = autoridadLocal(venueId, "tpv-shifts:close")
                val id = requireNotNull(row.closeOperationId)
                // Una respuesta perdida se consulta por SU operación, nunca en los diez turnos recientes.
                if (row.closeWireRequestJson != null) {
                    val prior = apiService.getShiftOperation(venueId, id)
                    if (prior.isSuccessful && prior.body()?.success == true &&
                        guardarCierreAcreditado(row, prior.body()!!.operation, prior.body()!!.shift?.id, prior.body()!!.shift?.status, localGson.toJson(prior.body()))) { completed++; continue }
                    if (!prior.isSuccessful && prior.code() != 404) {
                        soltarSesion(row, "PENDING", "Consulta del cierre HTTP ${prior.code()}; se conserva su conteo.")
                        continue
                    }
                }
                val wire = database.withTransaction {
                    verificarAutoridad(authority, "tpv-shifts:close")
                    val dao = database.localShiftSessionDao()
                    val actual = dao.leer(venueId, row.localSessionId) ?: error("Falta la caja original.")
                    check(actual.claimToken == row.claimToken && actual.closeOperationId == id)
                    if (dao.contarObligaciones(venueId, row.localSessionId) != 0) return@withTransaction null
                    check(actual.openingDisposition != "ADOPTED" && actual.serverShiftId != null)
                    val request = localGson.fromJson(actual.closeRequestJson, LocalCloseShiftRequest::class.java)
                        .resolve(actual.serverShiftId, actual.cashDrawerSessionId)
                    val json = localGson.toJson(request)
                    check(dao.fijarCierreDeEnvio(venueId, row.localSessionId, row.claimToken!!, actual.closeRequestJson!!, json) == 1)
                    verificarAutoridad(authority, "tpv-shifts:close")
                    request
                }
                if (wire == null) { soltarSesion(row, "PENDING", "El cierre espera los registros de su caja."); continue }
                verificarAutoridad(authority, "tpv-shifts:close")
                val response = apiService.closeShift(venueId, wire.shiftId, wire)
                if (!response.isSuccessful || response.body()?.success != true) {
                    soltarSesion(row, if (response.code() in listOf(400, 404, 422)) "REVIEW" else "PENDING", "Cierre HTTP ${response.code()}; se conserva su conteo.")
                    continue
                }
                verificarAutoridad(authority, "tpv-shifts:close")
                if (guardarCierreAcreditado(row, response.body()!!.operation, response.body()!!.data.id, response.body()!!.data.status, localGson.toJson(response.body()))) completed++
                else soltarSesion(row, "PENDING", "El servidor no acreditó la operación exacta del cierre.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { soltarSesion(row, "PENDING", error.message ?: "El cierre espera conexión.") }
        }
        if (completed > 0) _shiftChanges.tryEmit(Unit)
        return completed
    }

    suspend fun haySesionesPorReproducir(): Boolean = secureStorage.getVenueId()?.let {
        database.localShiftSessionDao().porReproducir(it).isNotEmpty()
    } ?: false

    suspend fun consultarCriptosPendientes(venueId: String? = secureStorage.getVenueId()) {
        if (venueId.isNullOrBlank() || secureStorage.getVenueId() != venueId) return
        for (row in database.localCryptoOperationDao().pendientes(venueId)) {
            try { consultarCriptoDurable(venueId, row.idempotencyKey) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Timber.w(error, "Cripto conserva su dueño y su llave; no se repite la emisión.") }
        }
    }

    private suspend fun reclamarSesion(candidate: LocalShiftSessionEntity, opening: Boolean): LocalShiftSessionEntity? = database.withTransaction {
        val dao = database.localShiftSessionDao()
        val row = dao.leer(candidate.venueId, candidate.localSessionId) ?: return@withTransaction null
        if (dao.anterioresSinResolver(row.venueId, row.createdAt, row.localSessionId) != 0) return@withTransaction null
        if (!opening && (row.openingDisposition == "ADOPTED" || dao.contarObligaciones(row.venueId, row.localSessionId) != 0)) return@withTransaction null
        val now = System.currentTimeMillis()
        if (dao.reclamar(row.venueId, row.localSessionId, java.util.UUID.randomUUID().toString(), now, now + 120_000, opening) != 1) null
        else dao.leer(row.venueId, row.localSessionId)
    }

    private suspend fun soltarSesion(row: LocalShiftSessionEntity, status: String, error: String?) {
        val changed = database.localShiftSessionDao().liberar(row.venueId, row.localSessionId, requireNotNull(row.claimToken), status, error, System.currentTimeMillis())
        if (changed == 1 && status == "REVIEW") _shiftChanges.tryEmit(Unit)
    }

    private suspend fun guardarCierreAcreditado(row: LocalShiftSessionEntity, operation: com.jaac.avoqado_tpv.features.shift.data.dto.ShiftOperationDto?, shiftId: String?, shiftStatus: String?, evidence: String): Boolean {
        if (operation == null || operation.operationId != row.closeOperationId || operation.kind != "CLOSE" || shiftId != row.serverShiftId || shiftStatus != "CLOSED" ||
            operation.state !in listOf("APPLIED", "CONFLICT") ||
            (operation.shiftId != null && operation.shiftId != shiftId) ||
            (row.cashDrawerSessionId != null && operation.cashDrawerSessionId != row.cashDrawerSessionId) ||
            operation.submittedCountedCash != row.countedCash || !mismoInstanteValido(operation.closedAtLocal, row.closedAtLocal)) return false
        return database.withTransaction {
            val dao = database.localShiftSessionDao()
            if (dao.confirmarCierre(row.venueId, row.localSessionId, requireNotNull(row.claimToken), requireNotNull(row.closeOperationId),
                if (operation.state == "CONFLICT") "REVIEW" else "CONFIRMED", evidence, System.currentTimeMillis()) != 1) return@withTransaction false
            check(dao.liberar(row.venueId, row.localSessionId, row.claimToken, "PENDING",
                if (operation.state == "CONFLICT") "Otra operación cerró esta caja; tu conteo se conserva sin aplicar." else null, System.currentTimeMillis()) == 1)
            true
        }
    }

    /** El servidor normaliza Z a .000Z; sólo dos instantes válidos e iguales acreditan la captura. */
    private fun mismoInstanteValido(received: String?, captured: String?): Boolean {
        if (received == null || captured == null) return false
        return try {
            java.time.Instant.parse(received) == java.time.Instant.parse(captured)
        } catch (_: java.time.format.DateTimeParseException) {
            false
        }
    }

    private fun proyectar(row: LocalShiftSessionEntity): Shift = Shift(
        id = row.serverShiftId ?: row.localSessionId, venueId = row.venueId, staffId = row.openedByStaffId, staffName = "",
        startTime = row.originalOpenedAt, endTime = row.closedAtLocal,
        status = if (row.localStatus == "CLOSED") com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.CLOSED else com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.OPEN,
        startingCash = row.startingCash?.let(::BigDecimal) ?: BigDecimal.ZERO, endingCash = null,
        totalSales = BigDecimal.ZERO, totalTips = BigDecimal.ZERO, totalOrders = 0, totalCashPayments = BigDecimal.ZERO,
        totalCardPayments = BigDecimal.ZERO, totalVoucherPayments = BigDecimal.ZERO, totalOtherPayments = BigDecimal.ZERO,
        totalProductsSold = 0, durationMinutes = null, cashDrawerSessionId = row.cashDrawerSessionId, localShiftSessionId = row.localSessionId,
        openingDisposition = row.openingDisposition, fondoAplicado = row.remoteStartingCash?.let(::BigDecimal), financialTotalsKnown = false,
    )

    private val localGson = com.google.gson.Gson()

    private fun autoridadLocal(venueId: String, permission: String): LocalShiftAuthority {
        val staff = secureStorage.getStaffId()
        val token = secureStorage.getToken()
        check(secureStorage.getVenueId() == venueId && !staff.isNullOrBlank() && !token.isNullOrBlank()) { "Verifica la sesión de esta sucursal antes de guardar Caja." }
        check(com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository.enLaUltimaListaEfectiva(secureStorage, permission)) { "No tienes permiso para esta operación." }
        // El wrapper isShiftSystemEnabled oculta errores. El genérico propaga fallos; ausente nunca es OFF.
        val mode = if (secureStorage.contains("enable_shifts")) secureStorage.getBoolean("enable_shifts", true) else null
        return LocalShiftAuthority(venueId, staff, token, secureStorage.getKioskVenueId(), mode)
    }

    private suspend fun verificarAutoridad(authority: LocalShiftAuthority, permission: String) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(autoridadLocal(authority.venueId, permission) == authority) { "La sesión o configuración cambió. Vuelve a abrir Caja." }
    }

    private fun conteoLocal(action: CashReconciliationAction?, value: BigDecimal?): String? {
        check((action == CashReconciliationAction.COUNTED) == (value != null)) { "El conteo requiere una acción y un importe válidos." }
        return value?.let { canonicalCashCount(it) ?: error("El conteo de efectivo no es válido.") }
    }

    private suspend fun <T> resultadoLocal(block: suspend () -> T): Result<T> = try {
        Result.Success(block())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: ApiException) {
        // Uno anidado (`guardarCierreLocal` dentro de `closeShift`) ya trae su mensaje: envolverlo otra vez en ValidationError le
        // pegaba al cajero el «Validation error: » de `ApiException.message` (D3, QA 2-nov).
        Timber.w(error, "No se pudo guardar el gesto local de Caja")
        Result.Error(error)
    } catch (error: Exception) {
        Timber.w(error, "No se pudo guardar el gesto local de Caja")
        Result.Error(ApiException.ValidationError(error.message ?: "No se pudo guardar Caja en este aparato."))
    }

    private companion object {
        val MAX_CASH_COUNT: BigDecimal = BigDecimal("99999999.99")
        const val CLOSE_RECOVERY_LIMIT = 10

        /**
         * El techo REAL del servidor (`TOPE_DE_TURNOS_POR_PAGINA` en `shift.tpv.service.ts`). Pedir
         * más no trae más: el servidor lo recorta en silencio y lo devuelve recortado en
         * `meta.pageSize`. Pedirlo ya recortado deja explícito lo que de todas formas va a pasar.
         */
        const val PAGE_SIZE_TURNOS = 50

        /**
         * Tope duro de páginas por consulta (50 × 10 = 500 turnos, ~16 meses de un negocio diario).
         * Existe para que un `meta` mentiroso no se convierta en un bucle de red en una terminal de
         * 1 GB. Al llegar aquí se AVISA en el log: el resultado va truncado.
         */
        const val TOPE_DE_PAGINAS = 10
    }
}

/** Página fija de hasta 20 pagos; total exacto independiente de lo visible. */
data class PaginaPagosDelCierre(val total: Int, val pagos: List<PendingPaymentEntity>, val offset: Int)

private data class LocalShiftAuthority(val venueId: String, val staffId: String, val token: String, val kioskVenueId: String?, val shiftsEnabled: Boolean?)
data class LocalShiftPage(val total: Int, val sessions: List<LocalShiftSessionEntity>, val offset: Int)
data class CajaLocalSnapshot(val shift: Shift, val row: LocalShiftSessionEntity, val summary: LocalShiftSummary)
