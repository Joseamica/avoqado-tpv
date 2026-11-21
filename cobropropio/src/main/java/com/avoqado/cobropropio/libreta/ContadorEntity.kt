package com.avoqado.cobropropio.libreta

import androidx.room.Entity
import androidx.room.PrimaryKey

/** El contador DUKPT por base canónica: `siguiente` es el próximo que se puede usar. Nunca baja. */
@Entity(tableName = "contadores")
data class ContadorEntity(
    @PrimaryKey val ksnBaseHex: String,
    val siguiente: Long,
)
