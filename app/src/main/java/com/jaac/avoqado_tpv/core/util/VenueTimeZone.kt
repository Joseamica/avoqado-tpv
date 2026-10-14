package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import timber.log.Timber
import java.time.ZoneId
import java.time.ZoneOffset

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

    /**
     * Hora de México por la LEY, no por el aparato. México abolió el horario de verano (decreto del 28-oct-2022), pero la PAX
     * A910S trae tzdata 2021a y la Nexgo N86 un ICU de 2017: los dos siguen aplicándolo y la terminal pintaba e imprimía la
     * hora +1 h de abril a octubre (QA física del 14-oct: ticket «10:44:25» de un cobro de las 09:44). Por eso las zonas de
     * México se traducen aquí a su desfase fijo, y la frontera a la zona de EE. UU. cuyo horario sigue (reglas sin cambio
     * desde 2007, así que un aparato viejo las tiene bien).
     * ponytail: tabla fija por ley; si México vuelve a cambiar su horario, se actualiza AQUÍ (y en AngelPayCorte.ZONA_MEXICO).
     */
    val MEXICO: ZoneId = ZoneOffset.ofHours(-6)
    private val FALLBACK = MEXICO
    private val ZONAS_DE_MEXICO: Map<String, ZoneId> by lazy {
        val pacifico = ZoneOffset.ofHours(-7)
        val tijuana = ZoneId.of("America/Los_Angeles")
        mapOf(
            "America/Mexico_City" to MEXICO, "America/Monterrey" to MEXICO, "America/Merida" to MEXICO,
            "America/Bahia_Banderas" to MEXICO, "America/Chihuahua" to MEXICO, "Mexico/General" to MEXICO,
            "America/Cancun" to ZoneOffset.ofHours(-5),
            "America/Mazatlan" to pacifico, "America/Hermosillo" to pacifico, "Mexico/BajaSur" to pacifico,
            "America/Matamoros" to ZoneId.of("America/Chicago"), "America/Ojinaga" to ZoneId.of("America/Chicago"),
            "America/Ciudad_Juarez" to ZoneId.of("America/Denver"),
            "America/Tijuana" to tijuana, "America/Ensenada" to tijuana, "America/Santa_Isabel" to tijuana, "Mexico/BajaNorte" to tijuana,
        )
    }

    /** La zona IANA del negocio traducida a reglas que no dependen del aparato (México por ley; el resto, tal cual). */
    fun zona(id: String?): ZoneId {
        if (id.isNullOrBlank()) return FALLBACK
        ZONAS_DE_MEXICO[id]?.let { return it }
        return try {
            ZoneId.of(id)
        } catch (e: Exception) {
            Timber.w("Invalid venue timezone '$id', using fallback")
            FALLBACK
        }
    }

    @Volatile
    private var cachedZoneId: ZoneId? = null

    /**
     * Get the venue's ZoneId, with in-memory cache.
     *
     * @param secureStorage Storage to read venue timezone from
     * @return Venue ZoneId (México por ley: desfase fijo), or MEXICO fallback
     */
    fun get(secureStorage: SecureStorage): ZoneId {
        cachedZoneId?.let { return it }

        val zoneId = zona(secureStorage.getVenueTimezone())
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
