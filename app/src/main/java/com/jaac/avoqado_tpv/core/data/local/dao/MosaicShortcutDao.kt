package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.MosaicShortcutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MosaicShortcutDao {

    @Query("SELECT * FROM mosaic_shortcut WHERE venue_id = :venueId ORDER BY position ASC")
    fun observeForVenue(venueId: String): Flow<List<MosaicShortcutEntity>>

    @Query("SELECT * FROM mosaic_shortcut WHERE venue_id = :venueId ORDER BY position ASC")
    suspend fun getForVenue(venueId: String): List<MosaicShortcutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shortcut: MosaicShortcutEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shortcuts: List<MosaicShortcutEntity>)

    @Query("DELETE FROM mosaic_shortcut WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mosaic_shortcut WHERE venue_id = :venueId")
    suspend fun clearVenue(venueId: String)
}
