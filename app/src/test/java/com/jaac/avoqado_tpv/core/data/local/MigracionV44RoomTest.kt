package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Fixtures sintéticos; no sustituyen la actualización de las 37 ventas del N86. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV44RoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() { context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME) }

    @Test fun `v43 conserva cada columna financiera y crea sesiones sin inferir pertenencia`() {
        val schema = File("schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/43.json").let {
            if (it.exists()) it else File("app", it.path)
        }
        val definition = JSONObject(schema.readText()).getJSONObject("database")
        val file = context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        file.parentFile!!.mkdirs()
        val before = mutableMapOf<String, List<List<String?>>>()
        val columns = mutableMapOf<String, List<String>>()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = definition.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                if (table !in OWNERS) continue
                val fields = entity.getJSONArray("fields")
                val names = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                columns[table] = names
                repeat(if (table == "pending_payments") 37 else 3) { row ->
                    val values = (0 until fields.length()).map { f ->
                        val field = fields.getJSONObject(f)
                        val name = field.getString("columnName")
                        when {
                            name == "venue_id" -> "v1"
                            !field.optBoolean("notNull", false) && row % 2 == 0 -> null
                            field.getString("affinity") == "INTEGER" -> row + f + 1
                            field.getString("affinity") == "REAL" -> 150.25 + row
                            else -> "$table:$name:$row:{\"importe\":150.25,\"autor\":\"original\"}"
                        }
                    }
                    old.execSQL("INSERT INTO `$table` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})", values.toTypedArray())
                }
                old.rawQuery("SELECT ${names.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid", null).use { c ->
                    val rows = mutableListOf<List<String?>>()
                    while (c.moveToNext()) rows += names.indices.map { if (c.isNull(it)) null else c.getString(it) }
                    before[table] = rows
                }
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(definition.getString("identityHash")))
            old.version = 43
        }
        val migrated = DatabaseModule.provideDatabase(context)
        try {
            val sql = migrated.openHelper.readableDatabase
            assertThat(sql.version).isEqualTo(45)
            for (table in OWNERS) {
                val names = columns.getValue(table)
                sql.query("SELECT ${names.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid").use { c ->
                    val rows = mutableListOf<List<String?>>()
                    while (c.moveToNext()) rows += names.indices.map { if (c.isNull(it)) null else c.getString(it) }
                    assertThat(rows).containsExactlyElementsIn(before.getValue(table)).inOrder()
                }
                sql.query("SELECT shift_membership, local_shift_session_id, cash_drawer_session_id FROM `$table`").use { c ->
                    while (c.moveToNext()) {
                        assertThat(c.getString(0)).isEqualTo("UNKNOWN")
                        assertThat(c.isNull(1)).isTrue()
                        assertThat(c.isNull(2)).isTrue()
                    }
                }
            }
            sql.query("SELECT order_wire_request_json FROM pending_payments").use { c ->
                while (c.moveToNext()) assertThat(c.isNull(0)).isTrue()
            }
            sql.query("PRAGMA table_info(local_shift_sessions)").use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == "starting_cash") { found = true; assertThat(c.getInt(3)).isEqualTo(0) }
                assertThat(found).isTrue()
            }
            sql.query("SELECT COUNT(*) FROM local_shift_sessions").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0) }
            sql.query("SELECT COUNT(*) FROM local_crypto_operations").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(0) }
        } finally { migrated.close() }
        val reopened = DatabaseModule.provideDatabase(context)
        try { assertThat(reopened.openHelper.readableDatabase.version).isEqualTo(45) } finally { reopened.close() }
    }

    private companion object {
        val OWNERS = listOf("pending_payments", "payment_attempts", "pending_refunds", "remote_payment_requests")
    }
}
