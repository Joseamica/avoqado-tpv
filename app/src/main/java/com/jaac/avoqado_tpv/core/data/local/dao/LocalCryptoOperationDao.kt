package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entity.LocalCryptoOperationEntity

@Dao
interface LocalCryptoOperationDao {
    @Query("SELECT * FROM local_crypto_operations WHERE venue_id = :venueId AND (financial_recorded IS NOT 1 OR financial_status IS NOT 'COMPLETED') ORDER BY created_at, idempotency_key LIMIT 20")
    suspend fun pendientes(venueId: String): List<LocalCryptoOperationEntity>

    /**
     * hz:nube0000:naaa0db5: cobros cripto cuyo desenlace financiero la terminal todavía no tiene del SERVIDOR (sin respuesta,
     * PENDING o PROCESSING; un estado desconocido también cuenta). Uno vencido (FAILED) o acreditado (COMPLETED) ya está en el
     * SERVIDOR y no frena (Codex r1 #6). Lo lee [com.jaac.avoqado_tpv.features.remote_command.domain.hayAlgoQueBajarDeVersionBorraria].
     */
    @Query("SELECT COUNT(*) FROM local_crypto_operations WHERE financial_status IS NULL OR financial_status NOT IN ('COMPLETED','FAILED','REFUNDED')")
    suspend fun contarQueBajarDeVersionBorraria(): Int

    @Insert suspend fun insertar(row: LocalCryptoOperationEntity)

    @Query("SELECT * FROM local_crypto_operations WHERE venue_id = :venueId AND idempotency_key = :key LIMIT 1")
    suspend fun leer(venueId: String, key: String): LocalCryptoOperationEntity?

    @Query("""UPDATE local_crypto_operations SET emission_status = :emission, financial_status = :financial,
        financial_recorded = :recorded, operation_result_json = :result, request_id = COALESCE(:requestId, request_id), last_error = :error, updated_at = :now
        WHERE venue_id = :venueId AND idempotency_key = :key AND (:requestId IS NULL OR request_id IS NULL OR request_id = :requestId)""")
    suspend fun guardarResultado(venueId: String, key: String, emission: String, financial: String?, recorded: Boolean?,
        result: String?, error: String?, requestId: String?, now: Long): Int

    @Query("""SELECT * FROM local_crypto_operations WHERE venue_id = :venueId
        AND request_id = :requestId LIMIT 1""")
    suspend fun porSolicitud(venueId: String, requestId: String): LocalCryptoOperationEntity?

    @Query("SELECT COUNT(*) FROM local_crypto_operations WHERE venue_id = :venueId")
    suspend fun contar(venueId: String): Int

    @Query("SELECT * FROM local_crypto_operations WHERE venue_id = :venueId ORDER BY created_at, idempotency_key LIMIT 20 OFFSET :offset")
    suspend fun pagina(venueId: String, offset: Int): List<LocalCryptoOperationEntity>
}
