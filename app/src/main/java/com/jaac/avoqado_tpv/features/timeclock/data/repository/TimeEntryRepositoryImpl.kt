package com.jaac.avoqado_tpv.features.timeclock.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.timeclock.data.dto.ClockInRequestDto
import com.jaac.avoqado_tpv.features.timeclock.data.dto.ClockOutRequestDto
import com.jaac.avoqado_tpv.features.timeclock.data.dto.toDomain
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeEntryRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : TimeEntryRepository {

    override suspend fun clockIn(
        venueId: String,
        staffId: String,
        pin: String,
        jobRole: String?,
        checkInPhotoUrl: String?,
        clockInLatitude: Double?,
        clockInLongitude: Double?,
        clockInAccuracy: Float?,
        facadePhotoUrl: String?
    ): Result<TimeEntry> = withContext(Dispatchers.IO) {
        try {
            val hasGps = clockInLatitude != null && clockInLongitude != null
            Timber.d("⏱ Clocking in staff: $staffId at venue: $venueId, hasPhoto: ${checkInPhotoUrl != null}, hasFacade: ${facadePhotoUrl != null}, hasGps: $hasGps")

            val request = ClockInRequestDto(
                staffId = staffId,
                pin = pin,
                jobRole = jobRole,
                checkInPhotoUrl = checkInPhotoUrl,
                facadePhotoUrl = facadePhotoUrl,
                clockInLatitude = clockInLatitude,
                clockInLongitude = clockInLongitude,
                clockInAccuracy = clockInAccuracy
            )

            val response = apiService.timeEntryClockIn(venueId, request)

            if (response.isSuccessful && response.body()?.data != null) {
                val timeEntry = response.body()!!.data!!.toDomain()
                Timber.i("✅ Clock in successful: ${timeEntry.id}")
                Result.success(timeEntry)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ Clock in failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Clock in error")
            Result.failure(e)
        }
    }

    override suspend fun clockOut(
        venueId: String,
        staffId: String,
        pin: String,
        checkOutPhotoUrl: String?,
        clockOutLatitude: Double?,
        clockOutLongitude: Double?,
        clockOutAccuracy: Float?,
        depositPhotoUrl: String?
    ): Result<TimeEntry> = withContext(Dispatchers.IO) {
        try {
            val hasGps = clockOutLatitude != null && clockOutLongitude != null
            val hasPhoto = checkOutPhotoUrl != null
            Timber.d("⏱ Clocking out staff: $staffId at venue: $venueId, hasGps: $hasGps, hasPhoto: $hasPhoto, hasDeposit: ${depositPhotoUrl != null}")

            val request = ClockOutRequestDto(
                staffId = staffId,
                pin = pin,
                checkOutPhotoUrl = checkOutPhotoUrl,
                depositPhotoUrl = depositPhotoUrl,
                clockOutLatitude = clockOutLatitude,
                clockOutLongitude = clockOutLongitude,
                clockOutAccuracy = clockOutAccuracy
            )

            val response = apiService.timeEntryClockOut(venueId, request)

            if (response.isSuccessful && response.body()?.data != null) {
                val timeEntry = response.body()!!.data!!.toDomain()
                Timber.i("✅ Clock out successful: ${timeEntry.id}, hours: ${timeEntry.totalHours}")
                Result.success(timeEntry)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ Clock out failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Clock out error")
            Result.failure(e)
        }
    }

    override suspend fun startBreak(timeEntryId: String): Result<TimeEntry> = withContext(Dispatchers.IO) {
        try {
            Timber.d("⏱ Starting break for entry: $timeEntryId")

            val response = apiService.timeEntryStartBreak(timeEntryId)

            if (response.isSuccessful && response.body()?.data != null) {
                val timeEntry = response.body()!!.data!!.toDomain()
                Timber.i("✅ Break started: ${timeEntry.id}")
                Result.success(timeEntry)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ Start break failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Start break error")
            Result.failure(e)
        }
    }

    override suspend fun endBreak(timeEntryId: String): Result<TimeEntry> = withContext(Dispatchers.IO) {
        try {
            Timber.d("⏱ Ending break for entry: $timeEntryId")

            val response = apiService.timeEntryEndBreak(timeEntryId)

            if (response.isSuccessful && response.body()?.data != null) {
                val timeEntry = response.body()!!.data!!.toDomain()
                Timber.i("✅ Break ended: ${timeEntry.id}")
                Result.success(timeEntry)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ End break failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ End break error")
            Result.failure(e)
        }
    }

    override suspend fun getTimeEntries(
        venueId: String,
        staffId: String?,
        startDate: String?,
        endDate: String?,
        limit: Int,
        offset: Int
    ): Result<List<TimeEntry>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("⏱ Fetching time entries for venue: $venueId, staffId: $staffId")

            // Use self-service endpoint when staffId is provided (no shifts:manage permission required)
            // Use manager endpoint when no staffId (requires shifts:manage permission)
            val response = if (staffId != null) {
                Timber.d("⏱ Using self-service endpoint (no permission required)")
                apiService.getMyTimeEntries(
                    venueId = venueId,
                    staffId = staffId,
                    startDate = startDate,
                    endDate = endDate,
                    limit = limit
                )
            } else {
                Timber.d("⏱ Using manager endpoint (requires shifts:manage)")
                apiService.getTimeEntries(
                    venueId = venueId,
                    staffId = null,
                    startDate = startDate,
                    endDate = endDate,
                    limit = limit,
                    offset = offset
                )
            }

            if (response.isSuccessful && response.body()?.data != null) {
                val entries = response.body()!!.data!!.map { it.toDomain() }
                Timber.i("✅ Fetched ${entries.size} time entries")
                Result.success(entries)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ Get time entries failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Get time entries error")
            Result.failure(e)
        }
    }

    override suspend fun getActiveTimeEntries(venueId: String): Result<List<TimeEntry>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("⏱ Fetching active time entries for venue: $venueId")

            val response = apiService.getActiveTimeEntries(venueId)

            if (response.isSuccessful && response.body()?.data != null) {
                val entries = response.body()!!.data!!.map { it.toDomain() }
                Timber.i("✅ Found ${entries.size} active staff members")
                Result.success(entries)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ Get active entries failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Get active entries error")
            Result.failure(e)
        }
    }

    override suspend fun findActiveEntryForStaff(
        venueId: String,
        staffId: String
    ): Result<TimeEntry?> = withContext(Dispatchers.IO) {
        try {
            Timber.d("⏱ Finding active entry for staff: $staffId")

            // Use self-service endpoint - no shifts:manage permission required
            val response = apiService.getMyTimeEntries(
                venueId = venueId,
                staffId = staffId,
                limit = 10 // Get recent entries to find active one
            )

            if (response.isSuccessful) {
                val entries = response.body()?.data?.map { it.toDomain() } ?: emptyList()
                val activeEntry = entries.firstOrNull { it.status == TimeEntryStatus.CLOCKED_IN || it.status == TimeEntryStatus.ON_BREAK }
                Timber.i("✅ Active entry for staff: ${activeEntry?.id ?: "none"}")
                Result.success(activeEntry)
            } else {
                // If no entries found, that's OK - just return null
                if (response.code() == 404) {
                    Result.success(null)
                } else {
                    val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                    Timber.e("❌ Find active entry failed: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Find active entry error")
            Result.failure(e)
        }
    }

    private fun parseErrorMessage(errorBody: String?, code: Int): String {
        return when (code) {
            400 -> errorBody?.let { extractMessage(it) } ?: "Solicitud inválida"
            401 -> "PIN incorrecto"
            403 -> "No tienes permiso para esta acción"
            404 -> "No se encontró el registro"
            409 -> errorBody?.let { extractMessage(it) } ?: "Ya estás registrado"
            429 -> "Demasiados intentos. Espera un momento."
            else -> errorBody?.let { extractMessage(it) } ?: "Error del servidor"
        }
    }

    private fun extractMessage(errorBody: String): String {
        // Try to extract message from JSON error response
        return try {
            val regex = """"message"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(errorBody)?.groupValues?.get(1) ?: errorBody
        } catch (e: Exception) {
            errorBody
        }
    }
}
