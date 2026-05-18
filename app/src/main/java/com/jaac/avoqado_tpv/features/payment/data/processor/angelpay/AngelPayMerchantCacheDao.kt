package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the offline cache of AngelPay user merchants (SDK 1.0.5).
 *
 * Reads are exposed both as a [Flow] (for reactive UI subscribers such as the
 * merchant switcher) and as a one-shot suspend (for one-shot reads such as the
 * periodic refresh job — D6, spec §6.6 / §18.5).
 *
 * Writes are confined to bulk replace + atomic active-flag update so the cache
 * always reflects the SDK session state after `switchMerchant()`.
 */
@Dao
interface AngelPayMerchantCacheDao {

    @Query("SELECT * FROM angelpay_merchant_cache ORDER BY name")
    fun observeAll(): Flow<List<AngelPayMerchantCacheEntity>>

    @Query("SELECT * FROM angelpay_merchant_cache ORDER BY name")
    suspend fun getAll(): List<AngelPayMerchantCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AngelPayMerchantCacheEntity>)

    @Query("DELETE FROM angelpay_merchant_cache")
    suspend fun deleteAll()

    /**
     * Replace the entire cache atomically (delete + insert in one transaction).
     * Used by the periodic refresh job after a successful
     * `AngelPaySdkGateway.getUserMerchants()` call.
     */
    @Transaction
    suspend fun replaceAll(items: List<AngelPayMerchantCacheEntity>) {
        deleteAll()
        insertAll(items)
    }

    /**
     * Mark a single merchant active (and all others inactive) atomically.
     * Used after a successful `AngelPaySdkGateway.switchMerchant(id)` so the
     * cache reflects the new SDK session state.
     */
    @Query("UPDATE angelpay_merchant_cache SET isActive = (merchantId = :merchantId)")
    suspend fun markActive(merchantId: Int)
}
