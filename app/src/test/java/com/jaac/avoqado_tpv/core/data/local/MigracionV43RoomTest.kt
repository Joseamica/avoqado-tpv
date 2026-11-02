package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
 * Migración 42 → 43 (B1, «ninguna duda de cobro sin salida», 21-oct): `remote_payment_requests.execution_closed_at` y
 * `payment_attempts.sdk_returned_at`. Aditiva y nullable.
 *
 * Arranca de una base v42 REAL (el esquema exportado de la versión en la calle, 2.13.1: `42.json`) con una solicitud del POS
 * reclamada y dinero en la libreta, y la abre el MISMO builder de producción: Room valida el esquema y el identity hash como lo
 * hará en una terminal. Nada se da por cerrado ni por regresado al actualizar, y ninguna fila de dinero cambia.
 * (El caso instrumentado gemelo vive en `AvoqadoDatabaseMigrationTest`, que corre en aparato.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV43RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `P1 la v43 conserva la bandeja y la libreta y deja las columnas nuevas en null`() = runTest {
        crearBaseV42 { db ->
            db.execSQL(
                "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, rating, skip_review, order_id, " +
                    "processed_by_staff_id, sender_device_name, source_timestamp, status, final_result_json, created_at, updated_at, " +
                    "cancel_accepted_at, execution_started_at, final_emitted_at, attempt_link_version) VALUES " +
                    "('req-v42','v1',47500,4750,5,0,NULL,'staff-pos','Android POS','2026-10-21T14:00:00Z','PROCESSING',NULL,100,100," +
                    "NULL,NULL,NULL,1)",
            )
            intento(db, "a-aprobado", "HOST_RESPONDIO", hostApproved = 1)
            intento(db, "a-duda", "INDETERMINADO", hostApproved = null)
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val bandeja = db.remotePaymentRequestDao().getById("req-v42")!!
            assertThat(bandeja.status).isEqualTo("PROCESSING")
            assertThat(bandeja.amountCents).isEqualTo(47_500L)
            assertThat(bandeja.tipCents).isEqualTo(4_750L)
            assertThat(bandeja.attemptLinkVersion).isEqualTo(1)
            assertWithMessage("nada se da por cerrado al actualizar").that(bandeja.executionClosedAt).isNull()
            assertThat(db.remotePaymentRequestDao().cerradaParaEjecucionEn("req-v42")).isNull()

            val aprobado = db.paymentAttemptDao().getById("a-aprobado")!!
            assertThat(aprobado.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
            assertThat(aprobado.hostApproved).isTrue()
            assertThat(aprobado.amountCents).isEqualTo(47_500L)
            assertThat(aprobado.processToken).isEqualTo("proceso-v42")
            assertWithMessage("ningún SDK se da por regresado al actualizar").that(aprobado.sdkReturnedAt).isNull()
            val duda = db.paymentAttemptDao().getById("a-duda")!!
            assertThat(duda.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
            assertThat(duda.sdkReturnedAt).isNull()
        } finally {
            db.close()
        }
    }

    @Test fun `P2 la migracion 42 a 43 es idempotente`() = runTest {
        crearBaseV42 { db -> intento(db, "a-duda", "INDETERMINADO", hostApproved = null) }

        val db = DatabaseModule.provideDatabase(context)
        try {
            // Room ya migró al abrir; correrla otra vez (instalación a medias, reintento) no truena ni toca lo guardado.
            AvoqadoDatabase.MIGRATION_42_43.migrate(db.openHelper.writableDatabase)
            AvoqadoDatabase.MIGRATION_42_43.migrate(db.openHelper.writableDatabase)
            assertThat(db.paymentAttemptDao().getById("a-duda")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
            assertThat(columnas(db, "payment_attempts").filter { it == "sdk_returned_at" }).containsExactly("sdk_returned_at")
            assertThat(columnas(db, "remote_payment_requests").filter { it == "execution_closed_at" })
                .containsExactly("execution_closed_at")
        } finally {
            db.close()
        }
    }

    private fun columnas(db: AvoqadoDatabase, tabla: String): List<String> {
        val nombres = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info($tabla)").use { c ->
            val nombre = c.getColumnIndex("name")
            while (c.moveToNext()) nombres += c.getString(nombre)
        }
        return nombres
    }

    /** Una fila de `payment_attempts` con la forma de la v42 (sin `sdk_returned_at`), de la solicitud `req-v42`. */
    private fun intento(db: SQLiteDatabase, attemptId: String, estado: String, hostApproved: Int?) {
        db.execSQL(
            "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, amount_cents, tip_cents, " +
                "currency, recording_route, context_schema_version, payment_context_json, host_approved, verify_attempts, " +
                "created_at, updated_at, legacy_shadow, terminal_payment_request_id, server_check_count, process_token) VALUES " +
                "(?,'v1','BLUMON','SALE',?,2,47500,4750,'MXN','FAST',1,'{\"terminalPaymentRequestId\":\"req-v42\"}',?,0," +
                "100,100,0,'req-v42',0,'proceso-v42')",
            arrayOf<Any?>(attemptId, estado, hostApproved),
        )
    }

    /** Crea en disco la base v42 con el esquema exportado (app/schemas/…/42.json) y siembra filas. */
    private fun crearBaseV42(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("42.json").readText()).getJSONObject("database")
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
