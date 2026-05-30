package com.jaac.avoqado_tpv.features.shift.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.OpenShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.toDomain
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import timber.log.Timber
import javax.inject.Inject
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
    private val cachedShiftDao: com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
) {

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
     * @return Result with updated shift (including calculations) or error
     */
    suspend fun closeShift(
        venueId: String,
        shiftId: String
    ): Result<Shift> {
        return try {
            Timber.d("🔴 Closing shift: $shiftId for venue: $venueId")

            val request = CloseShiftRequest(
                venueId = venueId,
                shiftId = shiftId,
                closeData = null  // MVP: Backend auto-calculates everything
            )

            val response = apiService.closeShift(venueId, shiftId, request)

            if (response.isSuccessful && response.body() != null) {
                // Extract shift from wrapper response: {"success": true, "data": ShiftDto}
                val shiftDto = response.body()!!.data
                val shift = shiftDto.toDomain()
                Timber.i("✅ Shift closed successfully. Sales: $${shift.totalSales}, Products: ${shift.totalProductsSold}")
                Result.Success(shift)
            } else {
                Timber.w("⚠️ Failed to close shift: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.message()))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error closing shift")
            Result.Error(ApiException.NetworkError(e))
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
     * @param venueId Venue identifier for tenant isolation
     * @param limit Maximum number of shifts to return (default: 10)
     * @return Result with list of closed shifts or error
     */
    suspend fun getShiftHistory(
        venueId: String,
        limit: Int = 10
    ): Result<List<Shift>> {
        return try {
            Timber.d("📋 Fetching shift history for venue: $venueId (limit: $limit)")

            // Backend returns paginated response: {success, data: [...], meta: {...}}
            val response = apiService.getShiftHistory(venueId, pageSize = limit, pageNumber = 1)

            if (response.isSuccessful && response.body() != null) {
                // Extract shifts from wrapper and filter for CLOSED status
                val allShifts = response.body()!!.data.map { it.toDomain() }
                val closedShifts = allShifts.filter { it.status == com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.CLOSED }

                Timber.d("✅ Fetched ${closedShifts.size} closed shifts (out of ${allShifts.size} total)")
                Result.Success(closedShifts)
            } else {
                Timber.w("⚠️ Failed to fetch shift history: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.message()))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error fetching shift history")
            Result.Error(ApiException.NetworkError(e))
        }
    }

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
}
