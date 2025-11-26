package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.domain.repository.TerminalInfo
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import timber.log.Timber
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
    private val apiService: ApiService
) : TerminalConfigRepository {

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
