package com.jaac.avoqado_campo.cola

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jaac.avoqado_campo.gps.Ping
import com.jaac.avoqado_campo.gps.PingDao

/** v2 (pings del GPS). Sin `fallbackToDestructiveMigration`: la cola nunca se tira; cada versión nueva trae su migración. */
@Database(entities = [Pieza::class, TurnoLocal::class, Ping::class], version = 2, exportSchema = true)
abstract class BaseDeCampo : RoomDatabase() {
    abstract fun piezaDao(): PiezaDao
    abstract fun pingDao(): PingDao
}

/** Escrita a mano contra `schemas/…/2.json` (la valida `MigracionTest`). */
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `venueId` TEXT NOT NULL, " +
                "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `precision` REAL NOT NULL, `capturadoAt` INTEGER NOT NULL)",
        )
    }
}
