package com.jaac.avoqado_tpv.features.verification.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionableSaleCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ActionableSaleCacheEntity>)

    @Query("SELECT * FROM actionable_sale_cache WHERE staff_id = :staffId ORDER BY cached_at DESC")
    fun observeStaff(staffId: String): Flow<List<ActionableSaleCacheEntity>>

    @Query("SELECT * FROM actionable_sale_cache WHERE payment_id = :paymentId LIMIT 1")
    suspend fun get(paymentId: String): ActionableSaleCacheEntity?

    /** La corrección sin señal: el detalle guardado de esa verificación. */
    @Query("SELECT * FROM actionable_sale_cache WHERE verification_id = :verificationId LIMIT 1")
    suspend fun getByVerification(verificationId: String): ActionableSaleCacheEntity?

    @Query("UPDATE actionable_sale_cache SET delivered_slots = :slots WHERE payment_id = :paymentId")
    suspend fun setDeliveredSlots(paymentId: String, slots: String): Int

    @Query("DELETE FROM actionable_sale_cache WHERE payment_id = :paymentId")
    suspend fun delete(paymentId: String): Int

    /** Reemplazo de la lista del servidor: borra lo no local de ese promotor que ya no vino. */
    @Query("DELETE FROM actionable_sale_cache WHERE staff_id = :staffId AND local_only = 0")
    suspend fun deleteServerRows(staffId: String): Int
}
