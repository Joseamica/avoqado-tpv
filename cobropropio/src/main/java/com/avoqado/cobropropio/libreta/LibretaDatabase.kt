package com.avoqado.cobropropio.libreta

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * La libreta del cobro propio: base APARTE (dinero), como `AuthAttemptDatabase` en la app. Versión 1 con el esquema
 * exportado a `cobropropio/schemas/`; todo cambio de forma llega con una migración explícita, nunca destructiva.
 * Journal `TRUNCATE` y `synchronous=FULL`: lo que regresa de una transacción ya está en el disco (r3 R3-05).
 */
@Database(entities = [IntentoEntity::class, ContadorEntity::class], version = 1, exportSchema = true)
internal abstract class LibretaDatabase : RoomDatabase() {

    abstract fun dao(): LibretaDao

    internal companion object {
        /** El archivo va donde diga el llamador; [Libreta.abrir] lo pone en `noBackupFilesDir`. */
        fun constructor(context: Context, archivo: File): Builder<LibretaDatabase> =
            Room.databaseBuilder(context.applicationContext, LibretaDatabase::class.java, archivo.absolutePath)
                .setJournalMode(JournalMode.TRUNCATE)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Explícitos: no se deja al default del aparato (ni al de Robolectric). Un cursor que no se
                        // recorre no ejecuta nada: por eso el moveToFirst.
                        for (pragma in listOf("PRAGMA journal_mode=TRUNCATE", "PRAGMA synchronous=FULL")) {
                            db.query(pragma).use { it.moveToFirst() }
                        }
                    }
                })
    }
}
