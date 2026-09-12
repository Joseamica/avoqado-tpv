package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migración 33 → 34 sobre una base v33 REAL (el esquema exportado que corre en las terminales), abierta
 * por el MISMO builder de producción ([DatabaseModule.provideDatabase]).
 *
 * 🔴 Por qué así y no con un Room en memoria: lo que revienta en una terminal es exactamente esto — la
 * migración registrada + la validación de esquema de Room contra las entidades nuevas. Si la migración no
 * estuviera en `addMigrations`, o dejara el esquema distinto del que Room espera, esta prueba falla igual
 * que el arranque de una PAX (crash-loop sobre `pending_payments`, que es dinero cobrado sin registrar).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV34RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v34 conserva las filas heredadas, las marca legacy_shadow y deja las columnas nuevas nulas`() = runTest {
        crearBaseV33 { db ->
            db.execSQL(
                "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, " +
                    "amount_cents, tip_cents, currency, recording_route, context_schema_version, payment_context_json, " +
                    "verify_attempts, created_at, updated_at) VALUES " +
                    "('viejo','v1','ANGELPAY','SALE','AUTORIZANDO',1,10000,0,'MXN','FAST',1,'{}',0,100,100)",
            )
            db.execSQL(
                "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, rating, " +
                    "skip_review, order_id, processed_by_staff_id, sender_device_name, source_timestamp, status, " +
                    "final_result_json, created_at, updated_at) VALUES " +
                    "('req-viejo','v1',10000,0,NULL,1,NULL,NULL,NULL,'2026-09-01T10:00:00Z','PROCESSING',NULL,100,100)",
            )
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val heredada = db.paymentAttemptDao().getById("viejo")
            assertThat(heredada).isNotNull()
            assertThat(heredada!!.state).isEqualTo("AUTORIZANDO")
            assertThat(heredada.legacyShadow).isTrue()

            val solicitud = db.remotePaymentRequestDao().getById("req-viejo")
            assertThat(solicitud).isNotNull()
            assertThat(solicitud!!.status).isEqualTo("PROCESSING")
            assertThat(solicitud.cancelAcceptedAt).isNull()
            assertThat(solicitud.executionStartedAt).isNull()
            assertThat(solicitud.finalEmittedAt).isNull()

            // Lo que escribe el APK NUEVO nunca nace heredado.
            db.paymentAttemptDao().insert(
                PaymentAttemptEntity(
                    attemptId = "nueva", venueId = "v1", processor = "BLUMON", state = "PREPARANDO",
                    amountCents = 100, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
                    createdAt = 200, updatedAt = 200,
                ),
            )
            assertThat(db.paymentAttemptDao().getById("nueva")!!.legacyShadow).isFalse()
        } finally {
            db.close()
        }
    }

    /** Crea en disco la base v33 con el esquema exportado (app/schemas/…/33.json) y siembra filas. */
    private fun crearBaseV33(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("33.json").readText()).getJSONObject("database")
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
