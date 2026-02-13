package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import timber.log.Timber
import java.time.ZoneId

/**
 * Centralized venue timezone provider.
 *
 * Uses the venue's IANA timezone (stored in SecureStorage) instead of
 * the device's system timezone. Falls back to "America/Mexico_City"
 * if no timezone is stored or if parsing fails.
 *
 * **Usage in ViewModels/Repositories (have SecureStorage via DI):**
 * ```kotlin
 * val zoneId = VenueTimeZone.get(secureStorage)
 * ```
 *
 * **Usage in Composables (pass ZoneId as parameter):**
 * ```kotlin
 * @Composable fun MyScreen(venueZoneId: ZoneId) { ... }
 * ```
 *
 * **Cache invalidation:**
 * Call [invalidateCache] when the venue changes (e.g., REMOTE_ACTIVATE).
 */
object VenueTimeZone {

    private val FALLBACK = ZoneId.of("America/Mexico_City")

    @Volatile
    private var cachedZoneId: ZoneId? = null

    /**
     * Get the venue's ZoneId, with in-memory cache.
     *
     * @param secureStorage Storage to read venue timezone from
     * @return Venue ZoneId, or America/Mexico_City fallback
     */
    fun get(secureStorage: SecureStorage): ZoneId {
        cachedZoneId?.let { return it }

        val tz = secureStorage.getVenueTimezone()
        val zoneId = if (!tz.isNullOrEmpty()) {
            try {
                ZoneId.of(tz)
            } catch (e: Exception) {
                Timber.w("Invalid venue timezone '$tz', using fallback")
                FALLBACK
            }
        } else {
            FALLBACK
        }
        cachedZoneId = zoneId
        return zoneId
    }

    /**
     * Clear cached timezone. Call when venue changes (REMOTE_ACTIVATE).
     */
    fun invalidateCache() {
        cachedZoneId = null
    }
}
