package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.CachedTimeEntryEntity

/** El checador guardado: una fila por negocio y persona (ver [CachedTimeEntryEntity]). */
@Dao
interface CachedTimeEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(e: CachedTimeEntryEntity)

    @Query("SELECT * FROM cached_time_entry WHERE venue_id = :venueId AND staff_id = :staffId")
    suspend fun leer(venueId: String, staffId: String): CachedTimeEntryEntity?

    @Query("DELETE FROM cached_time_entry WHERE venue_id = :venueId AND staff_id = :staffId")
    suspend fun borrar(venueId: String, staffId: String)
}
