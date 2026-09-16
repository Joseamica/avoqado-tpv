package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migración 34 → 35 (checkpoint 2 del webhook como primer confirmador) sobre una base v34 REAL (el esquema
 * exportado que corre en las terminales del árbol anterior), abierta por el MISMO builder de producción
 * ([DatabaseModule.provideDatabase]): migración registrada + validación de esquema de Room contra las entidades.
 *
 * Lo que fija: (a) las columnas nuevas nacen nulas/0 — nada se inventa sobre intentos que resolvió una versión
 * anterior; (b) `terminal_payment_request_id` se RELLENA desde `payment_context_json` para las filas que ya
 * existían, y sólo cuando la llave trae valor; (c) la migración es idempotente (guarda `PRAGMA table_info`);
 * (d) `attempt_link_version` de la bandeja sólo SUBE y sólo en solicitudes vivas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV35RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v35 rellena la solicitud desde el contexto, deja el veredicto del servidor nulo y es idempotente`() = runTest {
        crearBaseV34 { db ->
            fila(db, "con-solicitud", """{"venueId":"v1","terminalPaymentRequestId":"req-abc","orderId":"o1"}""")
            fila(db, "solicitud-vacia", """{"venueId":"v1","terminalPaymentRequestId":"","orderId":"o1"}""")
            fila(db, "sin-solicitud", """{"venueId":"v1","orderId":"o1"}""")
            fila(db, "solicitud-al-final", """{"venueId":"v1","terminalPaymentRequestId":"ultimo"}""")
            db.execSQL(
                "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, rating, " +
                    "skip_review, order_id, processed_by_staff_id, sender_device_name, source_timestamp, status, " +
                    "final_result_json, created_at, updated_at) VALUES " +
                    "('req-viva','v1',10000,0,NULL,1,NULL,NULL,NULL,'2026-09-16T10:00:00Z','PROCESSING',NULL,100,100)," +
                    "('req-cerrada','v1',10000,0,NULL,1,NULL,NULL,NULL,'2026-09-16T10:00:00Z','RESOLVED','{}',100,100)",
            )
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.paymentAttemptDao()
            assertThat(dao.getById("con-solicitud")!!.terminalPaymentRequestId).isEqualTo("req-abc")
            assertThat(dao.getById("solicitud-al-final")!!.terminalPaymentRequestId).isEqualTo("ultimo")
            assertThat(dao.getById("solicitud-vacia")!!.terminalPaymentRequestId).isNull()
            assertThat(dao.getById("sin-solicitud")!!.terminalPaymentRequestId).isNull()

            val heredada = dao.getById("con-solicitud")!!
            assertThat(heredada.legacyShadow).isTrue() // la puso la 33→34; la 34→35 no la toca
            assertThat(heredada.serverPaymentId).isNull()
            assertThat(heredada.serverOutcome).isNull()
            assertThat(heredada.serverRecordedVia).isNull()
            assertThat(heredada.serverAmountCents).isNull()
            assertThat(heredada.serverTipCents).isNull()
            assertThat(heredada.serverVerdictAt).isNull()
            assertThat(heredada.serverCheckedAt).isNull()
            assertThat(heredada.serverCheckCount).isEqualTo(0)

            val bandeja = db.remotePaymentRequestDao()
            assertThat(bandeja.getById("req-viva")!!.attemptLinkVersion).isEqualTo(0)
            assertThat(bandeja.raiseAttemptLinkVersion("req-viva", 1, 200)).isEqualTo(1)
            assertThat(bandeja.getById("req-viva")!!.attemptLinkVersion).isEqualTo(1)
            assertThat(bandeja.raiseAttemptLinkVersion("req-viva", 1, 201)).isEqualTo(0) // nunca baja ni repite
            assertThat(bandeja.raiseAttemptLinkVersion("req-viva", 0, 202)).isEqualTo(0)
            assertThat(bandeja.raiseAttemptLinkVersion("req-cerrada", 1, 203)).isEqualTo(0) // sólo solicitudes vivas
            assertThat(bandeja.getById("req-cerrada")!!.attemptLinkVersion).isEqualTo(0)

            // Idempotente: volver a correrla sobre una base ya migrada no revienta ni vuelve a rellenar.
            val cruda = db.openHelper.writableDatabase
            cruda.execSQL("""UPDATE payment_attempts SET payment_context_json = '{"terminalPaymentRequestId":"otro"}' WHERE attempt_id = 'con-solicitud'""")
            AvoqadoDatabase.MIGRATION_34_35.migrate(cruda)
            assertThat(dao.getById("con-solicitud")!!.terminalPaymentRequestId).isEqualTo("req-abc")
        } finally {
            db.close()
        }
    }

    private fun fila(db: SQLiteDatabase, attemptId: String, contextJson: String) {
        db.execSQL(
            "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, " +
                "amount_cents, tip_cents, currency, recording_route, context_schema_version, payment_context_json, " +
                "verify_attempts, created_at, updated_at, legacy_shadow) VALUES " +
                "(?,'v1','ANGELPAY','SALE','AUTORIZANDO',1,10000,0,'MXN','FAST',1,?,0,100,100,1)",
            arrayOf(attemptId, contextJson),
        )
    }

    /** Crea en disco la base v34 con el esquema exportado (app/schemas/…/34.json) y siembra filas. */
    private fun crearBaseV34(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("34.json").readText()).getJSONObject("database")
        val ruta = context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        ruta.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(ruta, null)
        try {
            val entidades = esquema.getJSONArray("entities")
            for (i in 0 until entidades.length()) {
                val entidad = entidades.getJSONObject(i)
                val tabla = entidad.getString("tableName")
                db.execSQL(entidad.getString("createSql").replace("\${TABLE_NAME}", tabla))
                val indices = entidad.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tabla))
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(esquema.getString("identityHash")),
            )
            db.version = esquema.getInt("version")
            sembrar(db)
        } finally {
            db.close()
        }
    }

    private fun archivoDeEsquema(nombre: String): File {
        val carpeta = "schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase"
        return listOf(File("$carpeta/$nombre"), File("app/$carpeta/$nombre"), File("../app/$carpeta/$nombre"))
            .firstOrNull { it.exists() }
            ?: error("No se encontró el esquema exportado $nombre (cwd=${File(".").absolutePath})")
    }
}
