package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** v44 → v45: crea las dos tablas nuevas, borra `verification_queue` y conserva `pending_refunds`. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV45RoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() { context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME) }

    @Test fun `v45 crea la cola y la lista guardada, borra verification_queue y conserva pending_refunds`() {
        val schema = File("schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/44.json").let {
            if (it.exists()) it else File("app", it.path)
        }
        val definition = JSONObject(schema.readText()).getJSONObject("database")
        val file = context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = definition.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            // El 44.json aún lista verification_queue (la entidad existía); se prueba que v45 la borra.
            old.execSQL("CREATE TABLE IF NOT EXISTS verification_queue (id TEXT NOT NULL PRIMARY KEY)")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(definition.getString("identityHash")))
            old.version = 44
        }
        // Una fila real en pending_refunds, con todas las columnas NOT NULL llenas, para probar que se conserva.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            val cols = mutableListOf<Pair<String, Boolean>>() // nombre, ¿entero?
            old.rawQuery("PRAGMA table_info(pending_refunds)", null).use { c ->
                while (c.moveToNext()) if (c.getInt(3) == 1 || c.getInt(5) == 1) cols += c.getString(1) to c.getString(2).uppercase().contains("INT")
            }
            val names = cols.joinToString { "`${it.first}`" }
            val values = cols.map { (n, isInt) -> if (n == "idempotency_key") "'k1'" else if (isInt) "1" else "'x'" }
            old.execSQL("INSERT INTO pending_refunds ($names) VALUES (${values.joinToString()})")
        }
        val migrated = DatabaseModule.provideDatabase(context)
        try {
            val sql = migrated.openHelper.readableDatabase
            assertThat(sql.version).isEqualTo(45)
            val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertThat(tables).containsAtLeast("proof_of_sale_queue", "actionable_sale_cache", "pending_refunds")
            assertThat(tables).doesNotContain("verification_queue")
            sql.query("SELECT COUNT(*) FROM pending_refunds WHERE idempotency_key = 'k1'").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(1) }
        } finally { migrated.close() }
        val reopened = DatabaseModule.provideDatabase(context)
        try { assertThat(reopened.openHelper.readableDatabase.version).isEqualTo(45) } finally { reopened.close() }
    }
}
