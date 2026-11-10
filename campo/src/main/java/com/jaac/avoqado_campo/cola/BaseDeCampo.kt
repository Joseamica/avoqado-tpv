package com.jaac.avoqado_campo.cola

import androidx.room.Database
import androidx.room.RoomDatabase

/** v1. Sin `fallbackToDestructiveMigration`: la cola nunca se tira; cada versión nueva trae su migración. */
@Database(entities = [Pieza::class, TurnoLocal::class], version = 1, exportSchema = true)
abstract class BaseDeCampo : RoomDatabase() {
    abstract fun piezaDao(): PiezaDao
}
