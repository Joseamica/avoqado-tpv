package com.jaac.avoqado_tpv.core.remotepayment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemotePaymentRequestDao {
    /** -1 = requestId ya existe; nunca reemplazar porque podría cambiar el dinero. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(request: RemotePaymentRequestEntity): Long

    @Query("SELECT * FROM remote_payment_requests WHERE request_id = :requestId")
    suspend fun getById(requestId: String): RemotePaymentRequestEntity?

    @Query(
        """UPDATE remote_payment_requests
           SET status = 'PROCESSING', updated_at = :now
           WHERE request_id = :requestId AND status = 'RECEIVED'""",
    )
    suspend fun markProcessing(requestId: String, now: Long): Int

    @Query(
        """UPDATE remote_payment_requests
           SET status = 'RESOLVED', final_result_json = :finalResultJson, updated_at = :now
           WHERE request_id = :requestId AND status != 'RESOLVED'""",
    )
    suspend fun markResolved(requestId: String, finalResultJson: String, now: Long): Int
}
