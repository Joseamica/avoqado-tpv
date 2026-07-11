package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.repository.PromoterLocationRepository
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result as AvoqadoResult
import com.jaac.avoqado_tpv.core.location.LocationService
import com.jaac.avoqado_tpv.core.location.PromoterLocationGate
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Promoter Location Worker ("cambaceo")
 *
 * Hourly location ping for field promoters selling without a fixed store
 * (spec: docs/superpowers/specs/2026-06-29-live-promoter-tracking-design.md).
 *
 * Self-gates on every run (PromoterLocationGate):
 * - terminal activated + session active (promoter logged in)
 * - venue opted in (tpvSettings.trackPromoterLocation)
 * - within the venue-configured capture window (tpvSettings.
 *   promoterLocationStartHour/EndHour, default [11:00, 18:00)) VENUE-local
 *   time (VenueTimeZone, never device tz)
 *
 * A null/failed capture is silently skipped — it must NEVER block or crash
 * the terminal. Only transient network errors retry (WorkManager backoff);
 * HTTP rejections (e.g. 403 = venue flag off server-side) are quiet no-ops.
 */
@HiltWorker
class PromoterLocationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val locationService: LocationService,
    private val promoterLocationRepository: PromoterLocationRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Refresh settings so a dashboard toggle applies within the hour
            // (offline-first: falls back to the cached settings on failure).
            secureStorage.getSerialNumber()?.let { tpvSettingsRepository.refreshFromTerminalConfig(it) }
            val settings = tpvSettingsRepository.getCurrentSettings()
            val now = ZonedDateTime.now(VenueTimeZone.get(secureStorage))

            if (!PromoterLocationGate.shouldCapture(
                    isTerminalActivated = secureStorage.isTerminalActivated(),
                    isAuthenticated = authRepository.isAuthenticated(),
                    trackPromoterLocation = settings.trackPromoterLocation,
                    now = now,
                    startHour = settings.promoterLocationStartHour,
                    endHour = settings.promoterLocationEndHour,
                )
            ) {
                return Result.success() // out of window / flag off / no session — quiet no-op
            }

            val location = locationService.getCurrentLocation()
            if (location == null) {
                Timber.w("📍 Promoter ping skipped: location unresolved (cell/WiFi/GPS)")
                return Result.success() // spec: omit the ping, never block
            }

            when (val result = promoterLocationRepository.sendPing(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                capturedAt = Instant.now(),
            )) {
                is AvoqadoResult.Success -> {
                    Timber.i("📍 Promoter ping sent (±${location.accuracy}m)")
                    Result.success()
                }
                is AvoqadoResult.Error ->
                    if (result.exception is ApiException.NetworkError) {
                        Result.retry() // transient — WorkManager backoff
                    } else {
                        // 4xx (e.g. 403 venue flag off server-side): don't hammer
                        Result.success()
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ PromoterLocationWorker crashed — skipping this cycle")
            Result.success()
        }
    }
}
