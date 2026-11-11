package com.jaac.avoqado_campo.cola

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigracionTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BaseDeCampo::class.java)

    @Test fun `una base v1 con una pieza y su turno migra a v2 sin perderlos`() {
        helper.createDatabase(NOMBRE, 1).apply {
            execSQL(
                "INSERT INTO piezas (tipo, llave, staffId, venueId, cuerpoJson, fotosJson, estado, intentos, siguienteIntentoAt, creadaAt, resumen) " +
                    "VALUES ('ENTRADA', 'e1', 's1', 'v1', '{}', '[]', 'PENDIENTE', 0, 0, 1, 'Entrada')",
            )
            execSQL("INSERT INTO turno (id, staffId, llaveEntrada, timeEntryId, venueId, abiertoAt) VALUES (1, 's1', 'e1', NULL, 'v1', 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(NOMBRE, 2, true, MIGRACION_1_2)

        db.query("SELECT llave, estado FROM piezas").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals("e1", it.getString(0)); assertEquals("PENDIENTE", it.getString(1))
        }
        db.query("SELECT llaveEntrada, venueId FROM turno WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst()
            assertEquals("e1", it.getString(0)); assertEquals("v1", it.getString(1))
        }
        db.query("SELECT COUNT(*) FROM pings").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }

    private companion object { const val NOMBRE = "migracion-test.db" }
}
