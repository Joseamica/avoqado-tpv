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
 * Migración 37 → 38 (Codex r8, P2-4): `server_answered_at`, «el servidor CONTESTÓ (2xx)», separada de
 * `server_checked_at`, que es el turno de la recuperación y también se estampa tras un 401/403/404/5xx.
 *
 * Arranca de una base v36 REAL (el esquema exportado) y la abre el MISMO builder de producción, así que corre la cadena
 * 36 → 37 → 38 que va a correr en una terminal de la calle, más la validación de esquema de Room contra las entidades.
 *
 * Lo que fija: (a) la columna nace NULL aunque la fila tenga `server_checked_at` — no se sabe si aquella consulta fue un
 * 2xx, así que la migración NO la infiere; (b) el turno de la recuperación se conserva; (c) es idempotente; (d) reabrir
 * no vuelve a migrar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV38RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v38 anade server_answered_at en NULL sin inferirla de server_checked_at, y es idempotente`() = runTest {
        crearBaseV36 { db ->
            fila(db, "consultada", serverCheckedAt = 555L)
            fila(db, "nunca-consultada", serverCheckedAt = null)
        }

        val db = DatabaseModule.provideDatabase(context)
        val version: Int
        try {
            version = db.openHelper.readableDatabase.version
            assertThat(version).isAtLeast(38)
            val dao = db.paymentAttemptDao()
            val consultada = dao.getById("consultada")!!
            assertThat(consultada.serverAnsweredAt).isNull()      // un turno viejo NO es una respuesta
            assertThat(consultada.serverCheckedAt).isEqualTo(555L)  // el turno se conserva
            assertThat(consultada.serverCheckCount).isEqualTo(3)
            assertThat(consultada.serverVeto).isNull()              // la 36 → 37 tampoco inventa nada
            assertThat(dao.getById("nunca-consultada")!!.serverAnsweredAt).isNull()

            // Idempotente: otra pasada sobre la base ya migrada no revienta ni borra lo escrito.
            val cruda = db.openHelper.writableDatabase
            cruda.execSQL("UPDATE payment_attempts SET server_answered_at = 999 WHERE attempt_id = 'consultada'")
            AvoqadoDatabase.MIGRATION_37_38.migrate(cruda)
            assertThat(dao.getById("consultada")!!.serverAnsweredAt).isEqualTo(999L)
        } finally {
            db.close()
        }

        val reabierta = DatabaseModule.provideDatabase(context)
        try {
            assertThat(reabierta.openHelper.readableDatabase.version).isEqualTo(version)
            assertThat(reabierta.paymentAttemptDao().getById("consultada")!!.serverAnsweredAt).isEqualTo(999L)
        } finally {
            reabierta.close()
        }
    }

    private fun fila(db: SQLiteDatabase, attemptId: String, serverCheckedAt: Long?) {
        db.execSQL(
            "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, " +
                "amount_cents, tip_cents, currency, recording_route, context_schema_version, payment_context_json, " +
                "verify_attempts, created_at, updated_at, legacy_shadow, server_checked_at, server_check_count) VALUES " +
                "(?,'v1','ANGELPAY','SALE','INDETERMINADO',1,10000,0,'MXN','FAST',1,'{}',0,100,100,0,?,3)",
            arrayOf(attemptId, serverCheckedAt),
        )
    }

    /** Crea en disco la base v36 con el esquema exportado (app/schemas/…/36.json) y siembra filas. */
    private fun crearBaseV36(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("36.json").readText()).getJSONObject("database")
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
