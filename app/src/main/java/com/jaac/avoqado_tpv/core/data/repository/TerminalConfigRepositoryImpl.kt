package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.AngelPayAuthDto
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.domain.repository.TerminalInfo
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TerminalConfigRepository
 *
 * **Responsibilities:**
 * 1. Fetch terminal config from backend on app startup
 * 2. Map DTOs to domain models
 * 3. Handle network errors with user-friendly messages
 *
 * **Error Handling:**
 * - 404: Terminal not found (serial not registered)
 * - Network errors: Connection issues
 * - Other errors: Generic failure message
 */
@Singleton
class TerminalConfigRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) : TerminalConfigRepository {

    // ──────────────────────────────────────────────────────────────────────────
    // §4.5b PIN HANDLING — IN-MEMORY ONLY (NO DISK PERSISTENCE)
    // ──────────────────────────────────────────────────────────────────────────
    // Both caches below use AtomicReference and live only in process memory.
    // There is intentionally NO Room / SharedPreferences / EncryptedSharedPreferences
    // / file path that persists `angelpayAuth.pin` to disk. Because the spec §4.5b
    // rule ("PIN must never touch persistent storage") is automatically satisfied
    // by virtue of zero persistence, no `sanitizeForDiskCache()` helper is needed
    // here. If a future revision EVER adds a disk-cache path for TerminalConfigData,
    // it MUST strip `angelpayAuth.pin` to "***" before writing (see
    // RedactingLoggingInterceptor for the equivalent network-log redaction).
    // ──────────────────────────────────────────────────────────────────────────

    // In-memory cache for the most recent `angelpayAuth` payload (Task 25 — D4 dual-source).
    // PIN is in-memory only and never persisted (§4.5b). AtomicReference so concurrent reads
    // from the resolver are safe without synchronization.
    private val cachedAngelPayAuth = AtomicReference<AngelPayAuthDto?>(null)

    // Task 30 — cache the full TerminalConfigData so AngelPayAuthRepository can run the
    // D5 intersection validator post-auth without re-fetching from backend. Same atomic
    // semantics as cachedAngelPayAuth. PIN inside angelpayAuth is in-memory only (§4.5b).
    private val cachedConfig = AtomicReference<TerminalConfigData?>(null)

    // Multi-AngelPay accounts per venue (2026-05-18). Mirrors `cachedAngelPayAuth` but
    // holds the FULL list of AngelPayUserAccount rows assigned to this venue. Used by
    // `AngelPayCredentialResolver.resolveByAccountId()` so the TPV can swap SDK sessions
    // when the operator picks a merchant owned by a different AngelPay login. Same
    // in-memory-only / never-persisted contract (§4.5b).
    private val cachedAngelPayAccounts = AtomicReference<List<AngelPayAuthDto>>(emptyList())

    override fun getCachedAngelPayAuth(): AngelPayAuthDto? = cachedAngelPayAuth.get()

    override fun getCachedConfig(): TerminalConfigData? = cachedConfig.get()

    override fun getCachedAngelPayAccounts(): List<AngelPayAuthDto> = cachedAngelPayAccounts.get()

    override suspend fun fetchConfig(serialNumber: String): Result<Pair<TerminalInfo, List<MerchantAccount>>> {
        return try {
            Timber.i("🔧 [TerminalConfig] Fetching config from backend for serial: $serialNumber")

            val response = apiService.getTerminalConfig(serialNumber)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    404 -> {
                        "Terminal no encontrado.\\n\\n" +
                        "Serial: $serialNumber\\n\\n" +
                        "Por favor, contacte a soporte para registrar este terminal."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\\n\\n" +
                        "El servidor no está disponible en este momento.\\n" +
                        "Por favor, intente nuevamente más tarde."
                    }
                    else -> {
                        "Error obteniendo configuración.\\n\\n" +
                        "Código: ${response.code()}\\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TerminalConfig] API error: ${response.code()} - ${response.message()}")
                return Result.failure(Exception(errorMessage))
            }

            val body = response.body()
            if (body == null || !body.success) {
                Timber.e("❌ [TerminalConfig] Empty or unsuccessful response")
                return Result.failure(
                    Exception(
                        "No se pudo obtener la configuración del terminal.\\n\\n" +
                        "Respuesta vacía del servidor."
                    )
                )
            }

            val data = body.data
            val terminal = data.terminal
            val venue = terminal.venue

            // Cache `angelpayAuth` for AngelPayCredentialResolver (Task 25 — spec §6.5).
            // PIN is in-memory only — never persisted to SecureStorage or disk (§4.5b).
            cachedAngelPayAuth.set(data.angelpayAuth)

            // Cache the full config for AngelPayAuthRepository's post-auth D5 validation (Task 30).
            cachedConfig.set(data)

            // Multi-AngelPay accounts per venue — cache the full list so the resolver can
            // switch SDK sessions when the operator picks a merchant tied to a different
            // AngelPay login. Falls back to empty list when backend doesn't populate it
            // (old TPV / non-NEXGO / PAX flavors). PIN inside each entry is in-memory only.
            cachedAngelPayAccounts.set(data.angelpayAccounts ?: emptyList())

            // Save venue timezone for date/time display throughout the app
            venue?.timezone?.let {
                secureStorage.saveVenueTimezone(it)
                VenueTimeZone.invalidateCache()
            }

            // Save terminal ID (needed for messaging API and other terminal-specific endpoints)
            secureStorage.saveTerminalId(terminal.id)

            // Map terminal DTO to TerminalInfo
            val terminalInfo = TerminalInfo(
                serialNumber = terminal.serialNumber,
                brand = terminal.brand ?: "PAX",  // Default to PAX if not set
                model = terminal.model ?: "A910S",  // Default to A910S if not set
                venueId = terminal.venueId,
                venueName = venue?.name ?: "Unknown Venue",
                venueType = venue?.type  // RESTAURANT, BAR, CAFE, FAST_FOOD, RETAIL_STORE, etc.
            )

            // Map merchant account DTOs to domain models
            val merchantAccounts = data.merchantAccounts.toDomain()

            Timber.i("✅ [TerminalConfig] Config fetched successfully")
            Timber.i("   Serial: ${terminalInfo.serialNumber}")
            Timber.i("   Brand: ${terminalInfo.brand}")
            Timber.i("   Model: ${terminalInfo.model}")
            Timber.i("   Venue: ${terminalInfo.venueName} (${terminalInfo.venueId})")
            Timber.i("   VenueType: ${terminalInfo.venueType ?: "N/A"}")
            Timber.i("   Merchant Accounts: ${merchantAccounts.size}")
            merchantAccounts.forEach { merchant ->
                Timber.i("      - ${merchant.displayName} (${merchant.serialNumber}, posId: ${merchant.posId})")
            }

            Result.success(Pair(terminalInfo, merchantAccounts))

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to fetch terminal config")

            val userMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> {
                    "Tiempo de espera agotado.\\n\\n" +
                    "La conexión tardó demasiado.\\n" +
                    "Verifique su internet e intente nuevamente."
                }
                e.message?.contains("no connection", ignoreCase = true) == true ||
                e.message?.contains("network", ignoreCase = true) == true -> {
                    "Sin conexión a internet.\\n\\n" +
                    "Verifique su conexión e intente nuevamente."
                }
                else -> {
                    "Error inesperado al obtener configuración.\\n\\n" +
                    "Por favor, contacte a soporte."
                }
            }

            Result.failure(Exception(userMessage))
        }
    }
}
