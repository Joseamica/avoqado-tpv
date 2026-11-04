package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import com.jaac.avoqado_tpv.features.verification.data.local.ActionableSaleCacheEntity
import com.jaac.avoqado_tpv.features.verification.data.local.ProofOfSaleQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v45 → v46 (número de vinculación): crea `proof_of_sale_queue` y `actionable_sale_cache` y borra `verification_queue`.
 * Era la v45 de la rama; se renumeró a 46 el 3-nov al integrar la 2.15.0, que ya publicó SU v45 («ninguna duda»).
 * Arranca de la v45 REAL de la calle (`45.json` de la 2.15.0) con datos y la abre el builder de producción.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV46RoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() { context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME) }

    @Test fun `v46 desde la v45 de la 2_15_0 conserva la bandeja y los reembolsos y estrena las dos tablas`() = runTest {
        crearBase("45.json") { old ->
            old.execSQL(
                "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, rating, skip_review, order_id, " +
                    "processed_by_staff_id, sender_device_name, source_timestamp, status, final_result_json, created_at, updated_at, " +
                    "cancel_accepted_at, execution_started_at, final_emitted_at, attempt_link_version, execution_closed_at, " +
                    "durable_barrier_version) VALUES ('req-v45','v1',47500,4750,5,0,NULL,'staff-pos','Android POS'," +
                    "'2026-11-03T14:00:00Z','PROCESSING',NULL,100,100,NULL,NULL,NULL,1,555,1)",
            )
            old.execSQL(filaGenerica(old, "verification_queue", "id")!!) // la v45 aún la trae; la v46 la borra
            old.execSQL(filaGenerica(old, "pending_refunds", "idempotency_key")!!)
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val sql = db.openHelper.readableDatabase
            assertThat(sql.version).isEqualTo(46)
            assertThat(tablas(db)).containsAtLeast("proof_of_sale_queue", "actionable_sale_cache", "pending_refunds")
            assertThat(tablas(db)).doesNotContain("verification_queue")

            val bandeja = db.remotePaymentRequestDao().getById("req-v45")!!
            assertThat(bandeja.status).isEqualTo("PROCESSING")
            assertThat(bandeja.amountCents).isEqualTo(47_500L)
            assertThat(bandeja.executionClosedAt).isEqualTo(555L)
            assertThat(bandeja.durableBarrierVersion).isEqualTo(1)
            sql.query("SELECT COUNT(*) FROM pending_refunds WHERE idempotency_key = 'k1'").use { c ->
                c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(1)
            }

            // Las dos tablas nuevas funcionan por sus DAO.
            val fila = ProofOfSaleQueueEntity(
                idempotencyKey = "p1", kind = "PHOTO", venueId = "v1", staffId = "s1", paymentId = "pay-1", verificationId = null,
                slot = "VINCULACION", localPath = "/x.jpg", linkingNumber = "5512345678", portedNumber = null, capturedAt = 1,
                captureSeq = 1, captureDeviceId = "d1", createdAt = 1, venueSlug = "tienda-a",
            )
            assertThat(db.proofOfSaleQueueDao().insertRow(fila)).isNotEqualTo(-1L)
            assertThat(db.proofOfSaleQueueDao().observeKey("p1").first()).isEqualTo(fila)
            val guardada = ActionableSaleCacheEntity("pay-1", "v1", "s1", null, "{}", "VINCULACION", false, 1)
            db.actionableSaleCacheDao().upsert(listOf(guardada))
            assertThat(db.actionableSaleCacheDao().get("pay-1")).isEqualTo(guardada)
        } finally { db.close() }

        val reabierta = DatabaseModule.provideDatabase(context)
        try { assertThat(reabierta.openHelper.readableDatabase.version).isEqualTo(46) } finally { reabierta.close() }
    }

    @Test fun `cadena 44 a 45 a 46 por DatabaseModule conserva pending_refunds`() {
        crearBase("44.json") { old -> old.execSQL(filaGenerica(old, "pending_refunds", "idempotency_key")!!) }
        val db = DatabaseModule.provideDatabase(context)
        try {
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(46)
            assertThat(tablas(db)).containsAtLeast("proof_of_sale_queue", "actionable_sale_cache", "pending_refunds")
            assertThat(tablas(db)).doesNotContain("verification_queue")
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM pending_refunds WHERE idempotency_key = 'k1'").use { c ->
                c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(1)
            }
            // La columna de la v45 de main llegó por la cadena.
            db.openHelper.readableDatabase.query("SELECT durable_barrier_version FROM remote_payment_requests").close()
        } finally { db.close() }
    }

    @Test fun `la migracion 45 a 46 es idempotente`() = runTest {
        crearBase("45.json") {}
        val db = DatabaseModule.provideDatabase(context)
        try {
            AvoqadoDatabase.MIGRATION_45_46.migrate(db.openHelper.writableDatabase)
            AvoqadoDatabase.MIGRATION_45_46.migrate(db.openHelper.writableDatabase)
            assertThat(tablas(db)).containsAtLeast("proof_of_sale_queue", "actionable_sale_cache")
        } finally { db.close() }
    }

    private fun tablas(db: AvoqadoDatabase): Set<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** INSERT con todas las columnas NOT NULL sin default llenas; la llave primaria vale 'k1'. */
    private fun filaGenerica(db: SQLiteDatabase, tabla: String, llave: String): String? {
        val cols = mutableListOf<Pair<String, Boolean>>()
        db.rawQuery("PRAGMA table_info($tabla)", null).use { c ->
            while (c.moveToNext()) if (c.getInt(3) == 1 || c.getInt(5) == 1) cols += c.getString(1) to c.getString(2).uppercase().contains("INT")
        }
        if (cols.isEmpty()) return null
        val values = cols.map { (n, isInt) -> if (n == llave) "'k1'" else if (isInt) "1" else "'x'" }
        return "INSERT INTO $tabla (${cols.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString()})"
    }

    /** Crea en disco la base con el esquema exportado (app/schemas/…/<archivo>) y siembra filas. */
    private fun crearBase(archivo: String, sembrar: (SQLiteDatabase) -> Unit) {
        val schema = File("schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/$archivo").let {
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
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(definition.getString("identityHash")))
            old.version = definition.getInt("version")
            sembrar(old)
        }
    }
}
