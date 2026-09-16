package com.jaac.avoqado_tpv.features.payments.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payments.data.dto.PaymentHistoryRequestBody
import com.jaac.avoqado_tpv.features.payments.data.dto.toDomain
import com.jaac.avoqado_tpv.features.payments.domain.models.PaginatedPayments
import com.jaac.avoqado_tpv.features.payments.domain.models.ResultadoLigaRecibo
import com.jaac.avoqado_tpv.features.payments.domain.repository.PaymentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment Repository Implementation
 *
 * Fetches payment history from backend API.
 * Handles pagination, filtering, and error handling.
 *
 * **Data Flow:**
 * 1. Build request with pagination params and filters
 * 2. Call ApiService.getPaymentHistory()
 * 3. Map DTOs to domain models
 * 4. Return Result<PaginatedPayments>
 *
 * **Error Handling:**
 * - Network errors → ApiException.NetworkError
 * - HTTP errors → ApiException.HttpError
 * - Parse errors → ApiException.ParseError
 *
 * @param apiService Retrofit API service for network calls
 */
@Singleton
class PaymentRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : PaymentRepository {

    override suspend fun getPaymentHistory(
        venueId: String,
        pageNumber: Int,
        pageSize: Int,
        fromDate: Instant?,
        toDate: Instant?,
        staffId: String?
    ): Result<PaginatedPayments> = withContext(Dispatchers.IO) {
        try {
            Timber.d("💳 [PaymentRepository] Fetching payments (page=$pageNumber, size=$pageSize)")

            // Build request body (only filters)
            val requestBody = PaymentHistoryRequestBody(
                fromDate = fromDate?.let { formatInstant(it) },
                toDate = toDate?.let { formatInstant(it) },
                staffId = staffId
            )

            Timber.d("📤 [PaymentRepository] Request: pageSize=$pageSize, pageNumber=$pageNumber, fromDate=${requestBody.fromDate}, toDate=${requestBody.toDate}, staffId=$staffId")

            // Call API (pagination as query params, filters in body)
            val response = apiService.getPaymentHistory(
                venueId = venueId,
                pageSize = pageSize,
                pageNumber = pageNumber,
                request = requestBody
            )

            // Handle response
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!

                Timber.d("📥 [PaymentRepository] Response: success=${body.success}, count=${body.data.size}, total=${body.meta.total}")

                // Map DTOs to domain models
                val payments = body.data.map { it.toDomain() }

                val result = PaginatedPayments(
                    payments = payments,
                    total = body.meta.total,
                    page = body.meta.pageNumber,
                    pageSize = body.meta.pageSize
                )

                Timber.i("✅ [PaymentRepository] Fetched ${payments.size} payments (total: ${body.meta.total})")

                Result.Success(result)
            } else {
                val errorCode = response.code()
                val errorMessage = response.message()

                Timber.e("❌ [PaymentRepository] HTTP error: $errorCode - $errorMessage")

                Result.Error(ApiException.HttpError(errorCode, errorMessage))
            }
        } catch (e: java.io.IOException) {
            Timber.e(e, "❌ [PaymentRepository] Network error")
            Result.Error(ApiException.NetworkError(e))
        } catch (e: Exception) {
            Timber.e(e, "❌ [PaymentRepository] Unknown error")
            Result.Error(ApiException.Unknown(e))
        }
    }

    /**
     * La liga del recibo digital de un cobro, para el QR de la reimpresión.
     *
     * 🔴 Nunca lanza y nunca bloquea: devuelve cuál de los tres desenlaces ocurrió. Si no se puede
     * traer, el ticket se imprime igual sin QR — y el cajero se entera de por qué.
     */
    override suspend fun getReceiptLink(venueId: String, paymentId: String): ResultadoLigaRecibo {
        // 🔴 TOPE PROPIO de 5 s (QA de hardware, 12-sep-2026): sin él, la llamada usaba el cliente
        // compartido, cuyo `callTimeout` es de 25 s — con red mala la PAX esperaba hasta 25 segundos
        // antes de imprimir, con el cliente enfrente. Android e iOS ya tenían los 5 s; faltaba aquí.
        // El tope agotado cuenta como «sin red»: nunca hubo respuesta del servidor.
        val resultado = withTimeoutOrNull(TOPE_LIGA_RECIBO_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val response = apiService.getReceiptLink(venueId, paymentId)
                    val liga = response.body()?.receipt

                    if (response.isSuccessful && !liga?.receiptUrl.isNullOrBlank()) {
                        ResultadoLigaRecibo.Obtenida(
                            receiptUrl = liga!!.receiptUrl!!,
                            autofacturaAvailable = liga.autofacturaAvailable,
                        )
                    } else if (response.isSuccessful) {
                        // 200 con cuerpo inservible: el servidor SÍ respondió, no es falta de red.
                        Timber.e("🧾 [PaymentRepository] Liga del recibo vacía para $paymentId")
                        ResultadoLigaRecibo.FalloDelServidor(response.code())
                    } else {
                        Timber.e("🧾 [PaymentRepository] Liga del recibo: HTTP ${response.code()}")
                        ResultadoLigaRecibo.FalloDelServidor(response.code())
                    }
                } catch (e: CancellationException) {
                    // Se relanza para que el `catch (Exception)` de abajo no se trague la cancelación
                    // del tope. ⚠️ Declarado, no fingido: HOY es REDUNDANTE — `withContext` vuelve a
                    // comprobar la cancelación al terminar y descarta el valor igual. Rompiéndolo a
                    // propósito (12-sep) ninguna prueba cae. Se conserva como defensa por si alguien
                    // quita el `withContext`, no porque una prueba lo proteja.
                    throw e
                } catch (e: java.io.IOException) {
                    Timber.e(e, "🧾 [PaymentRepository] Liga del recibo sin conexión")
                    ResultadoLigaRecibo.SinRed
                } catch (e: Exception) {
                    Timber.e(e, "🧾 [PaymentRepository] Liga del recibo ilegible")
                    ResultadoLigaRecibo.FalloDelServidor(0)
                }
            }
        }

        return resultado ?: ResultadoLigaRecibo.SinRed.also {
            Timber.w("🧾 [PaymentRepository] Liga del recibo: se agotó el tope de ${TOPE_LIGA_RECIBO_MS} ms — el ticket sale sin QR")
        }
    }

    companion object {
        /** Lo máximo que la reimpresión espera la liga antes de imprimir sin QR. Igual que android e iOS. */
        const val TOPE_LIGA_RECIBO_MS = 5_000L
    }

    /**
     * Format Instant to ISO 8601 string for API
     */
    private fun formatInstant(instant: Instant): String {
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
