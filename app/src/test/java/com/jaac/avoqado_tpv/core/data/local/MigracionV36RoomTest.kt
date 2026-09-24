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
 * Migración 35 → 36 (Task 7 · fix 4: evidencia positiva del servidor DURABLE) sobre una base v35 REAL (el esquema
 * exportado que corre en las terminales del árbol anterior), abierta por el MISMO builder de producción
 * ([DatabaseModule.provideDatabase]): migración registrada + validación de esquema de Room contra las entidades.
 *
 * Lo que fija: (a) las dos columnas nacen NULAS — la migración NO infiere evidencia para filas existentes, ni para una
 * INDETERMINADO ni para una DESCARTADA liberada; (b) el resto de la fila (heredada, solicitud, veredicto) no se toca;
 * (c) la migración es idempotente (guarda `PRAGMA table_info`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV36RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v36 anade la evidencia positiva del servidor en NULL sin inferirla para filas existentes y es idempotente`() = runTest {
        crearBaseV35 { db ->
            fila(db, "indeterminada", "INDETERMINADO", serverOutcome = null)
            fila(db, "liberada", "DESCARTADA", serverOutcome = "RELEASED_NO_EVIDENCE")
        }

        val db = DatabaseModule.provideDatabase(context)
        // La versión a la que dejó la base el builder de producción (36 cuando nació esta prueba; hoy la cadena sigue: 37, 38…).
        val versionTrasMigrar: Int
        try {
            versionTrasMigrar = db.openHelper.readableDatabase.version
            val dao = db.paymentAttemptDao()
            for (id in listOf("indeterminada", "liberada")) {
                val tras = dao.getById(id)!!
                assertThat(tras.serverProcessorEvidence).isNull()
                assertThat(tras.serverProcessorEvidenceAt).isNull()
                assertThat(tras.terminalPaymentRequestId).isEqualTo("req-abc") // la 34→35 ya lo tenía; la 35→36 no lo toca
            }
            assertThat(dao.getById("liberada")!!.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE")
            assertThat(dao.getById("liberada")!!.state).isEqualTo("DESCARTADA")
            assertThat(dao.getById("indeterminada")!!.serverOutcome).isNull()

            // Idempotente: volver a correrla sobre una base ya migrada no revienta ni borra nada.
            val cruda = db.openHelper.writableDatabase
            cruda.execSQL("UPDATE payment_attempts SET server_processor_evidence = 'APPROVED', server_processor_evidence_at = 777 WHERE attempt_id = 'liberada'")
            AvoqadoDatabase.MIGRATION_35_36.migrate(cruda)
            assertThat(dao.getById("liberada")!!.serverProcessorEvidence).isEqualTo("APPROVED")
            assertThat(dao.getById("liberada")!!.serverProcessorEvidenceAt).isEqualTo(777L)
        } finally {
            db.close()
        }

        // Fix 5 (Codex r5, H): cerrar y REABRIR con el builder de producción — la base ya en v36 abre sin migrar otra vez y
        // conserva las filas sembradas y la marca escrita (WAL incluido).
        val reabierta = DatabaseModule.provideDatabase(context)
        try {
            val dao = reabierta.paymentAttemptDao()
            // 🔴 Antes decía `isEqualTo(36)` a secas: quedó en ROJO el día que la cadena subió a 37 (r5-4) y nadie corría
            // esta clase. Lo que fija es que REABRIR no vuelve a migrar: la versión es la misma que dejó la primera apertura.
            assertThat(versionTrasMigrar).isAtLeast(36)
            assertThat(reabierta.openHelper.readableDatabase.version).isEqualTo(versionTrasMigrar)
            assertThat(dao.getById("indeterminada")!!.state).isEqualTo("INDETERMINADO")
            assertThat(dao.getById("indeterminada")!!.serverProcessorEvidence).isNull()
            val liberada = dao.getById("liberada")!!
            assertThat(liberada.state).isEqualTo("DESCARTADA")
            assertThat(liberada.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE")
            assertThat(liberada.serverProcessorEvidence).isEqualTo("APPROVED")
            assertThat(liberada.serverProcessorEvidenceAt).isEqualTo(777L)
            assertThat(liberada.terminalPaymentRequestId).isEqualTo("req-abc")
        } finally {
            reabierta.close()
        }
    }

    private fun fila(db: SQLiteDatabase, attemptId: String, state: String, serverOutcome: String?) {
        db.execSQL(
            "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, " +
                "amount_cents, tip_cents, currency, recording_route, context_schema_version, payment_context_json, " +
                "verify_attempts, created_at, updated_at, legacy_shadow, terminal_payment_request_id, server_outcome, server_check_count) VALUES " +
                "(?,'v1','ANGELPAY','SALE',?,1,10000,0,'MXN','FAST',1,?,0,100,100,0,'req-abc',?,0)",
            arrayOf(attemptId, state, """{"venueId":"v1","terminalPaymentRequestId":"req-abc","orderId":"o1"}""", serverOutcome),
        )
    }

    /** Crea en disco la base v35 con el esquema exportado (app/schemas/…/35.json) y siembra filas. */
    private fun crearBaseV35(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("35.json").readText()).getJSONObject("database")
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
