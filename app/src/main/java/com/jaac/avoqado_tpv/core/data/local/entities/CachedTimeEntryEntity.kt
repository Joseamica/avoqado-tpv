package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * El checador guardado (v43): la última entrada de reloj conocida de UNA persona en UN negocio, para que sin red la terminal siga
 * sabiendo si esa persona ya checó entrada (igual que el turno guardado en [CachedShiftEntity]). Una fila por negocio y persona:
 * nunca se usa la entrada de otra persona.
 */
@Entity(tableName = "cached_time_entry", primaryKeys = ["venue_id", "staff_id"])
data class CachedTimeEntryEntity(
    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "staff_id")
    val staffId: String,

    @ColumnInfo(name = "entry_id")
    val entryId: String,

    /** El estado del servidor tal cual (p. ej. CLOCKED_IN, ON_BREAK). */
    @ColumnInfo(name = "status")
    val status: String,

    /** ISO del servidor. */
    @ColumnInfo(name = "clock_in_time")
    val clockInTime: String?,

    /** `System.currentTimeMillis()` al guardarla. */
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long,
)
