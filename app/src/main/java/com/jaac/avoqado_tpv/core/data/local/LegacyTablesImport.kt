package com.jaac.avoqado_tpv.core.data.local

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Sólo la actualización fría 43→44: el original nunca entra a una API SQLite. No es restore/replay. */
internal class LegacyTablesImport(
    private val source: File,
    private val temporaryRoot: File,
    private val copyFile: (File, File) -> Unit = ::copyBounded,
) {
    fun into(destination: SupportSQLiteDatabase) {
        check(destination.inTransaction()) { "La importación de Mesas requiere la transacción de migración." }
        check(source.canonicalPath != File(requireNotNull(destination.path)).canonicalPath) { "Origen y destino de Mesas deben ser distintos." }
        val original = sourceSnapshot()
        if (original.isEmpty()) return
        check(source.isFile && original.containsKey("")) { "No se encontró el archivo principal del legado de Mesas." }
        check(!File(source.path + "-journal").exists()) { "Mesas tiene un journal sin acreditar; requiere revisión." }
        temporaryRoot.mkdirs()
        val directory = Files.createTempDirectory(temporaryRoot.toPath(), "tables-v1-").toFile()
        try {
            for (suffix in original.keys) copyFile(File(source.path + suffix), File(directory, SOURCE_NAME + suffix))
            check(sourceSnapshot() == original) { "El legado de Mesas cambió durante la copia; no se importó." }
            val copy = File(directory, SOURCE_NAME)
            val nonDestructive = DatabaseErrorHandler { throw IllegalStateException("El legado de Mesas está dañado; se conservó el original.") }
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE, nonDestructive).use { legacy ->
                validate(legacy)
                legacy.rawQuery("SELECT ${COLUMNS.joinToString { "`$it`" }} FROM sync_intents ORDER BY seq, id", null).use { cursor ->
                    destination.compileStatement("INSERT INTO sync_intents (${COLUMNS.joinToString { "`$it`" }}) VALUES (${COLUMNS.joinToString { "?" }})").use { insert ->
                        while (cursor.moveToNext()) {
                            insert.clearBindings()
                            for (column in COLUMNS.indices) when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> insert.bindNull(column + 1)
                                Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(column + 1, cursor.getLong(column))
                                Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(column + 1, cursor.getDouble(column))
                                Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(column + 1, cursor.getBlob(column))
                                else -> insert.bindString(column + 1, cursor.getString(column))
                            }
                            check(insert.executeInsert() != -1L) { "No se pudo importar una operación de Mesas." }
                        }
                    }
                }
            }
            check(sourceSnapshot() == original) { "El legado de Mesas cambió durante la importación; no se confirmó." }
        } finally {
            check(directory.deleteRecursively()) { "No se pudo retirar la copia temporal privada de Mesas." }
        }
    }

    private fun sourceSnapshot(): Map<String, String> {
        check(!File(source.path + "-journal").exists()) { "Mesas tiene un journal sin acreditar; requiere revisión." }
        return listOf("", "-wal", "-shm").mapNotNull { suffix ->
            val file = File(source.path + suffix)
            if (!file.exists()) null else {
                check(file.isFile && file.canRead()) { "No se puede leer el legado de Mesas; se conservó sin cambios." }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                suffix to digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.toMap()
    }

    private fun validate(db: SQLiteDatabase) {
        check(db.version == 1) { "Versión desconocida del legado de Mesas; no se importó." }
        db.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) { "El legado de Mesas está dañado." }
        }
        db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == LEGACY_IDENTITY && !cursor.moveToNext()) { "Identidad desconocida del legado de Mesas." }
        }
        db.rawQuery("PRAGMA table_info(sync_intents)", null).use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                val pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                val default = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                val expectedType = if (name in listOf("seq", "created_at")) "INTEGER" else "TEXT"
                val expectedNull = if (name in listOf("error_code", "message", "result_json")) 0 else 1
                check(type == expectedType && notNull == expectedNull && pk == if (name == "id") 1 else 0) { "Forma desconocida del legado de Mesas." }
                check(default == if (name == "status") "'PENDING'" else null) { "Defaults desconocidos del legado de Mesas." }
                columns += name
            }
            check(columns == COLUMNS) { "Columnas desconocidas del legado de Mesas." }
        }
    }

    companion object {
        const val SOURCE_NAME = "avoqado_tables_database"
        private const val LEGACY_IDENTITY = "7c8402dc1b77b9a3664106abc69b2345"
        private val COLUMNS = listOf("id", "venue_id", "seq", "type", "payload_json", "status", "error_code", "message", "result_json", "created_at")

        fun forContext(context: Context) = LegacyTablesImport(context.getDatabasePath(SOURCE_NAME), context.noBackupFilesDir)

        fun rejectAmbiguousCreation(context: Context) {
            if (context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME).let { it.exists() && it.length() > 0 }) return
            val source = context.getDatabasePath(SOURCE_NAME)
            check(listOf("", "-wal", "-shm", "-journal").none { File(source.path + it).exists() }) {
                "La base principal falta y hay un legado de Mesas retenido. Requiere revisión antes de recuperar operaciones."
            }
        }

        fun rejectLegacyOnCreate(context: Context) {
            val source = context.getDatabasePath(SOURCE_NAME)
            check(listOf("", "-wal", "-shm", "-journal").none { File(source.path + it).exists() }) {
                "No se puede recrear una base vacía sobre un legado de Mesas retenido. Requiere revisión."
            }
        }

        private fun copyBounded(source: File, target: File) {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() }
            }
        }
    }
}
