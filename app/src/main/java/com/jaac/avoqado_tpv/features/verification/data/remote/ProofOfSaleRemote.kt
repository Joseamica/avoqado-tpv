package com.jaac.avoqado_tpv.features.verification.data.remote

import com.jaac.avoqado_tpv.core.data.network.ActionableVerificationsResponse
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.ProofOfSaleRequest
import com.jaac.avoqado_tpv.core.data.network.ProofOfSaleResponse
import com.jaac.avoqado_tpv.core.data.network.SetVerificationNumberRequest
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorCode
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorMessage
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import java.time.Instant
import javax.inject.Inject

/**
 * Las llamadas de la cola de comprobantes (foto y número de vinculación). Un no-2xx lanza
 * [BackendHttpException] con el `code` del cuerpo: quien clasifica (el worker) decide por ese `code`, nunca
 * por el status solo ni por el texto. Los fallos de red (IOException) se dejan pasar tal cual.
 */
class ProofOfSaleRemote @Inject constructor(private val api: ApiService) {

    /** Plazo de [isDuplicate]: una pregunta de cortesía no puede frenar la captura (se puede mover en pruebas). */
    internal var duplicateTimeoutMs: Long = 3_000L

    suspend fun submitPhoto(row: ProofOfSaleQueueEntity, photoUrl: String): ProofOfSaleResponse {
        val portabilidad = row.slot == SLOT_PORTABILIDAD
        val request = ProofOfSaleRequest(
            paymentId = row.paymentId,
            photoUrls = listOf(photoUrl),
            verificationId = row.verificationId,
            photoLabel = if (portabilidad) "Portabilidad" else "Vinculacion",
            // Cada fila lleva el número de SU ranura.
            linkingNumber = row.linkingNumber.takeUnless { portabilidad },
            portedNumber = row.portedNumber.takeIf { portabilidad },
            idempotencyKey = row.idempotencyKey,
            captureSeq = row.captureSeq,
            captureDeviceId = row.captureDeviceId,
            capturedAt = Instant.ofEpochMilli(row.capturedAt).toString(),
        )
        return body(api.uploadProofOfSale(request))
    }

    suspend fun submitNumber(row: ProofOfSaleQueueEntity): ProofOfSaleResponse {
        val verificationId = requireNotNull(row.verificationId) { "Un número suelto necesita su verificationId" }
        val portabilidad = row.slot == SLOT_PORTABILIDAD
        val number = requireNotNull(if (portabilidad) row.portedNumber else row.linkingNumber) { "Fila sin número en su ranura" }
        val request = SetVerificationNumberRequest(
            slot = if (portabilidad) SLOT_PORTABILIDAD else SLOT_VINCULACION,
            number = number,
            idempotencyKey = row.idempotencyKey,
            captureSeq = row.captureSeq,
            captureDeviceId = row.captureDeviceId,
            capturedAt = Instant.ofEpochMilli(row.capturedAt).toString(),
        )
        return body(api.setVerificationNumber(verificationId, request))
    }

    /**
     * true/false si el servidor contesta a tiempo; null sin red, con cualquier error o pasados [duplicateTimeoutMs].
     * Nunca le lanza a quien pregunta (es una pregunta de cortesía), salvo la cancelación, que también es la del plazo.
     */
    suspend fun isDuplicate(number: String, kind: String, verificationId: String?): Boolean? =
        withTimeoutOrNull(duplicateTimeoutMs) {
            try {
                val response = api.checkLinkingNumber(number, kind, verificationId)
                if (response.isSuccessful) response.body()?.duplicate else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    suspend fun actionable(cursor: String?): ActionableVerificationsResponse = body(api.getActionableVerifications(cursor))

    /** paymentId -> estado (COMPLETED, FAILED, PENDING… o NONE si el servidor no lo conoce). De 100 en 100. */
    suspend fun statuses(paymentIds: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        paymentIds.distinct().chunked(100).forEach { chunk ->
            body(api.getVerificationStatuses(chunk.joinToString(","))).data.orEmpty().forEach { result[it.paymentId] = it.status }
        }
        return result
    }

    private fun <T> body(response: Response<T>): T {
        if (response.isSuccessful) {
            return response.body() ?: throw BackendHttpException(response.code(), "Respuesta vacía del servidor")
        }
        val raw = response.errorBody()?.string()
        throw BackendHttpException(
            statusCode = response.code(),
            message = parseBackendErrorMessage(raw, "Error ${response.code()}"),
            errorCode = parseBackendErrorCode(raw),
        )
    }

    private companion object {
        const val SLOT_VINCULACION = "VINCULACION"
        const val SLOT_PORTABILIDAD = "PORTABILIDAD"
    }
}
