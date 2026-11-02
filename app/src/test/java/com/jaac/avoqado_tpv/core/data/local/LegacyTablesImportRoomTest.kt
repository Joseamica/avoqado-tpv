package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [27, 28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyTablesImportRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val source get() = context.getDatabasePath("avoqado_tables_database")
    private val main get() = context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)

    @After fun cleanup() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
        context.deleteDatabase("avoqado_tables_database")
    }

    @Test fun `upgrade copia todos los estados y seq terminal sin cambiar bytes origen ni duplicar al reabrir`() {
        createMain43()
        createLegacy()
        val before = sourceBytes()
        val db = DatabaseModule.provideDatabase(context)
        try {
            val sql = db.openHelper.readableDatabase
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertThat(tables).contains("sync_intents")
            sql.query("SELECT id,venue_id,seq,type,payload_json,status,error_code,message,result_json,created_at,shift_membership,local_shift_session_id,cash_drawer_session_id FROM sync_intents ORDER BY seq").use { c ->
                val statuses = mutableListOf<String>()
                while (c.moveToNext()) {
                    val i = statuses.size
                    assertThat(c.getString(0)).isEqualTo("legacy-$i")
                    assertThat(c.getString(1)).isEqualTo(if (i == 5) "v2" else "v1")
                    assertThat(c.getLong(2)).isEqualTo(if (i == 5) 999L else i + 1L)
                    assertThat(c.getString(3)).isEqualTo("PAY_CASH")
                    assertThat(c.getLong(9)).isEqualTo(100L + i)
                    statuses += c.getString(5)
                    assertThat(c.getString(4)).isEqualTo("{\"amount\":150.25,\"shiftIdDeLaVenta\":null}")
                    assertThat(c.getString(6)).isEqualTo("original-code")
                    assertThat(c.isNull(7)).isTrue()
                    assertThat(c.getString(8)).isEqualTo("{\"original\":true}")
                    assertThat(c.getString(10)).isEqualTo("UNKNOWN")
                    assertThat(c.isNull(11)).isTrue()
                    assertThat(c.isNull(12)).isTrue()
                }
                assertThat(statuses).containsExactly("PENDING", "ACKED", "REJECTED", "DISMISSED", "DISCARDED", "ACKED").inOrder()
            }
            assertThat(sourceBytes()).isEqualTo(before)
        } finally { db.close() }
        val reopened = DatabaseModule.provideDatabase(context)
        try {
            reopened.openHelper.readableDatabase.query("SELECT COUNT(*),MAX(seq) FROM sync_intents").use { c ->
                c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(6); assertThat(c.getLong(1)).isEqualTo(999L)
            }
            assertThat(sourceBytes()).isEqualTo(before)
        } finally { reopened.close() }
    }

    @Test fun `legado corrupto no se ignora ni se borra ni permite confirmar v44`() {
        createMain43()
        source.parentFile!!.mkdirs()
        source.writeText("SQLite format 3\u0000corrupt and truncated")
        val before = sourceBytes()
        val result = runCatching {
            val db = DatabaseModule.provideDatabase(context)
            try { db.openHelper.readableDatabase.version } finally { db.close() }
        }
        assertThat(result.isFailure).isTrue()
        assertThat(sourceBytes()).isEqualTo(before)
        SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READONLY).use { assertThat(it.version).isEqualTo(43) }
    }

    @Test fun `origen ausente legitimo crea outbox vacio sin crear archivo viejo`() {
        createMain43()
        val db = DatabaseModule.provideDatabase(context)
        try {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_intents").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0) }
            assertThat(source.exists()).isFalse()
        } finally { db.close() }
    }

    @Test fun `principal ausente mas legado retenido no adopta ni reproduce pendientes`() {
        createLegacy()
        val before = sourceBytes()
        assertThat(runCatching { DatabaseModule.provideDatabase(context) }.isFailure).isTrue()
        assertThat(main.exists()).isFalse()
        assertThat(sourceBytes()).isEqualTo(before)
    }

    @Test fun `version o shape o journal desconocidos impiden confirmar sin alterar origen`() {
        for (invalid in listOf("version", "shape", "journal")) {
            cleanup(); createMain43(); createLegacy()
            if (invalid == "journal") File(source.path + "-journal").writeText("unaccredited")
            else SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                if (invalid == "version") it.version = 2 else it.execSQL("ALTER TABLE sync_intents ADD COLUMN unknown_column TEXT")
            }
            val before = sourceBytes()
            assertThat(openFails()).isTrue()
            assertThat(sourceBytes()).isEqualTo(before)
            assertStill43()
        }
    }

    @Test fun `fallo de copia por espacio simulado revierte destino y retry importa exactamente una vez`() {
        createMain43(); createLegacy()
        val before = sourceBytes()
        val failing = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AvoqadoDatabase.MIGRATION_43_44.migrate(db)
                LegacyTablesImport(source, context.noBackupFilesDir) { from, to ->
                    from.inputStream().use { input -> to.outputStream().use { output -> output.write(input.read()) } }
                    throw IOException("No space left on device (simulated after partial copy)")
                }.into(db)
            }
        }
        assertMigrationFails(failing)
        assertThat(sourceBytes()).isEqualTo(before); assertStill43()
        assertImportedOnce()
    }

    @Test fun `fallo SQL despues de dos INSERT revierte todo y reintenta desde origen intacto`() {
        createMain43(); createLegacy()
        val before = sourceBytes()
        val failing = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AvoqadoDatabase.MIGRATION_43_44.migrate(db)
                db.execSQL("CREATE TRIGGER task4_fail_copy BEFORE INSERT ON sync_intents WHEN NEW.seq = 3 BEGIN SELECT RAISE(ABORT, 'disk full at third insert'); END")
                LegacyTablesImport.forContext(context).into(db)
            }
        }
        assertMigrationFails(failing)
        assertThat(sourceBytes()).isEqualTo(before); assertStill43()
        assertImportedOnce()
    }

    @Test fun `origen existente ilegible no se considera vacio`() {
        createMain43(); createLegacy()
        val before = sourceBytes()
        assertThat(source.setReadable(false, false)).isTrue()
        try { assertThat(openFails()).isTrue() } finally { source.setReadable(true, true) }
        assertThat(sourceBytes()).isEqualTo(before); assertStill43()
    }

    @Test fun `commit en WAL tras muerte con SHM se recupera solo desde copia`() = walAfterDeath(false)

    @Test fun `commit en WAL tras muerte sin SHM se recupera solo desde copia`() = walAfterDeath(true)

    private fun walAfterDeath(removeShm: Boolean) = runTest {
        createMain43(); createLegacy()
        // Proceso SQLite real que termina SIN close/checkpoint; no es una sombra de muerte del SDK.
        val child = ProcessBuilder("python3", "-c", """
            import sqlite3, os, sys
            d=sqlite3.connect(sys.argv[1]); d.execute('PRAGMA journal_mode=WAL'); d.execute('PRAGMA wal_autocheckpoint=0')
            d.execute("INSERT INTO sync_intents VALUES ('wal-row','v1',1000,'PAY_CASH','{}','PENDING',NULL,NULL,NULL,999)")
            d.commit(); os._exit(0)
        """.trimIndent(), source.path).redirectErrorStream(true).start()
        assertThat(child.waitFor()).isEqualTo(0)
        assertThat(File(source.path + "-wal").length()).isGreaterThan(0L)
        if (removeShm) assertThat(File(source.path + "-shm").delete()).isTrue()
        val before = sourceBytes()
        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.syncIntentDao()
            assertThat(dao.maxSeq("v1")).isEqualTo(1000L)
            assertThat(dao.discardPending("wal-row")).isEqualTo(1)
            val seq = dao.insertWithSeq(com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity(
                "new-row", "v1", 0, "PAY_CASH", "{}"))
            assertThat(seq).isEqualTo(1001L)
            assertThat(sourceBytes()).isEqualTo(before)
        } finally { db.close() }
        val reopened = DatabaseModule.provideDatabase(context)
        try {
            assertThat(reopened.syncIntentDao().allForVenue("v1").single { it.id == "wal-row" }.status).isEqualTo("DISCARDED")
            assertThat(reopened.syncIntentDao().maxSeq("v1")).isEqualTo(1001L)
            assertThat(sourceBytes()).isEqualTo(before)
        } finally { reopened.close() }
    }

    private fun assertMigrationFails(migration: Migration) {
        val db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, AvoqadoDatabase.DATABASE_NAME)
            .allowMainThreadQueries().addMigrations(migration).build()
        try { assertThat(runCatching { db.openHelper.writableDatabase }.isFailure).isTrue() } finally { db.close() }
    }

    private fun openFails(): Boolean = runCatching {
        val db = DatabaseModule.provideDatabase(context)
        try { db.openHelper.readableDatabase.version } finally { db.close() }
    }.isFailure

    private fun assertStill43() {
        SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READONLY).use { assertThat(it.version).isEqualTo(43) }
    }

    private fun assertImportedOnce() {
        val db = DatabaseModule.provideDatabase(context)
        try {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_intents").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(6) }
        } finally { db.close() }
    }

    private fun createMain43() = schema(main, "com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase", 43)

    private fun createLegacy() {
        schema(source, "com.jaac.avoqado_tpv.features.tables.data.local.TablesDatabase", 1)
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            listOf("PENDING", "ACKED", "REJECTED", "DISMISSED", "DISCARDED", "ACKED").forEachIndexed { i, status ->
                db.execSQL("INSERT INTO sync_intents VALUES (?,?,?,?,?,?,?,?,?,?)", arrayOf("legacy-$i", if (i == 5) "v2" else "v1",
                    if (i == 5) 999L else i + 1L, "PAY_CASH", "{\"amount\":150.25,\"shiftIdDeLaVenta\":null}", status,
                    "original-code", null, "{\"original\":true}", 100L + i))
            }
        }
    }

    private fun schema(file: File, name: String, version: Int) {
        val relative = "schemas/$name/$version.json"
        val json = JSONObject(listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()).getJSONObject("database")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = json.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42,?)", arrayOf(json.getString("identityHash")))
            db.version = version
        }
    }

    private fun sourceBytes() = listOf("", "-wal", "-shm", "-journal").mapNotNull { suffix ->
        File(source.path + suffix).takeIf { it.exists() }?.let {
            suffix to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
    }.toMap()
}
