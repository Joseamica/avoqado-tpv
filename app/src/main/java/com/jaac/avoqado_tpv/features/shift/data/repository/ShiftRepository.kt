package com.jaac.avoqado_tpv.features.shift.data.repository

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
class ShiftRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
    private val cachedShiftDao: com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao,
    /** 💸 La cola de reembolsos: BARRERA del cierre por debajo de TODAS las pantallas (auditoría de Codex F8). */
    private val refundQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository,
) {

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
        return try {
            Timber.d("🟢 Opening shift for venue: $venueId, staff: $staffId, cash: $$startingCash")

            val request = OpenShiftRequest(
                venueId = venueId,
                staffId = staffId,
                startingCash = startingCash,
                stationId = stationId
            )

            val response = apiService.openShift(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                // Extract shift from wrapper response: {"success": true, "data": ShiftDto}
                val shiftDto = response.body()!!.data
                val shift = shiftDto.toDomain()
                Timber.i("✅ Shift opened successfully: ${shift.id}")
                _shiftChanges.tryEmit(Unit)
                Result.Success(shift)
            } else {
                Timber.w("⚠️ Failed to open shift: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.message()))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error opening shift")
            Result.Error(ApiException.NetworkError(e))
        }
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
        val canonicalCount = when {
            action == null && countedCash != null -> {
                return Result.Error(ApiException.ValidationError("El conteo requiere una acción de conciliación."))
            }
            action == CashReconciliationAction.COUNTED && countedCash == null -> {
                return Result.Error(ApiException.ValidationError("Ingresa el efectivo total contado."))
            }
            action == CashReconciliationAction.SKIPPED && countedCash != null -> {
                return Result.Error(ApiException.ValidationError("Cerrar sin conteo no acepta un monto."))
            }
            action == CashReconciliationAction.COUNTED -> canonicalCashCount(countedCash!!)
                ?: return Result.Error(ApiException.ValidationError("El conteo de efectivo no es válido."))
            else -> null
        }

        // 💸 BARRERA (auditoría de Codex F8, 4-sep-2026): `ShiftViewModel` ya la aplica con su pantalla,
        // pero el kiosco (`KioskAdminBottomSheet`) llama a este repositorio DIRECTO. La regla vive aquí,
        // debajo de todas las UI: con una devolución sin registrar (o rechazada sin reconocer) no se
        // cierra — cerrar firmaría un corte que cuadra de más por dinero que sí salió del cajón. Y si la
        // cola no se puede leer, tampoco (fail-closed): no saber si hay dinero sin anotar no es «no hay».
        val bloqueantes = try {
            refundQueueRepository.blockingForVenue(venueId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "💸 [Shift] No se pudo leer la cola de reembolsos antes de cerrar")
            return Result.Error(ApiException.ValidationError("No se pudo verificar si hay devoluciones sin registrar. Vuelve a intentar cerrar la caja."))
        }
        if (bloqueantes.isNotEmpty()) {
            Timber.w("💸 [Shift] Cierre BLOQUEADO en el repositorio: ${bloqueantes.size} devolución(es) sin registrar o sin reconocer")
            return Result.Error(
                ApiException.ValidationError(
                    "Hay ${bloqueantes.size} devolución(es) hechas en la terminal y sin registrar en Avoqado. " +
                        "La caja no puede cerrarse hasta que se registren o un gerente las revise en Caja."
                )
            )
        }

        return try {
            Timber.d("🔴 Closing shift: $shiftId for venue: $venueId")

            val request = CloseShiftRequest(
                venueId = venueId,
                shiftId = shiftId,
                closeData = null,
                cashReconciliationAction = action,
                countedCash = canonicalCount
            )

            val response = apiService.closeShift(venueId, shiftId, request)

            if (response.isSuccessful && response.body() != null) {
                // Extract shift from wrapper response: {"success": true, "data": ShiftDto}
                val body = response.body()!!
                val shift = body.data.toDomain().copy(
                    reconciliation = body.reconciliation?.toDomain()
                )
                Timber.i("✅ Shift closed successfully. Sales: $${shift.totalSales}, Products: ${shift.totalProductsSold}")
                _shiftChanges.tryEmit(Unit)
                Result.Success(shift)
            } else if (response.code() == 400 || response.code() == 409) {
                // A close POST is never safe to replay. One bounded read resolves the common case
                // where the original request committed but its response was lost, or another caller
                // won the close claim first. The backend uses 409 while a claim is in progress and
                // its existing 400 contract once that competing close is already committed.
                val alreadyClosed = findRecentlyClosedShift(venueId, shiftId)
                if (alreadyClosed != null) {
                    Timber.i("✅ Shift was already closed: $shiftId")
                    _shiftChanges.tryEmit(Unit)
                    Result.Success(alreadyClosed)
                } else {
                    Timber.w("⚠️ Shift close HTTP ${response.code()} remains unresolved: $shiftId")
                    Result.Error(ApiException.HttpError(response.code(), response.message()))
                }
            } else {
                Timber.w("⚠️ Failed to close shift: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.message()))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error closing shift")
            Result.Error(ApiException.NetworkError(e))
        }
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
        return try {
            Timber.d("🔍 Fetching current shift for venue: $venueId")

            val response = apiService.getCurrentShift(venueId)

            if (response.isSuccessful) {
                // Extract shift from wrapper response
                val shiftDto = response.body()?.shift
                val shift = shiftDto?.toDomain()

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
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error fetching current shift")
            Result.Error(ApiException.NetworkError(e))
        }
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
        val cached = cachedShiftDao.getCachedShift(venueId)
        return if (cached != null && cached.isOpen()) {
            Timber.d("📦 Using cached OPEN shift ${cached.id} (cached ${cached.minutesSinceCached()} min ago)")
            cached.toDomainPartial()
        } else {
            null
        }
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
