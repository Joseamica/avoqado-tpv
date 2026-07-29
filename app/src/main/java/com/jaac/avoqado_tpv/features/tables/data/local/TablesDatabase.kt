package com.jaac.avoqado_tpv.features.tables.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database DEDICADA del outbox de Mesas (Plan C, Task 3) — deliberadamente
 * separada de [com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase].
 *
 * **Por qué una base aparte y no una tabla más en `AvoqadoDatabase`:**
 * 1. `AvoqadoDatabase` guarda `pending_payments` — dinero real. Otra sesión
 *    (rama `codex/payment-safety-phase-0`) está migrando esa tabla en paralelo
 *    y ya va en v31 ahí; agregar `sync_intents` a `AvoqadoDatabase` aquí
 *    garantizaba una colisión de versión de schema contra esa rama al mergear.
 * 2. El plan original (`docs/superpowers/plans/2026-07-24-tpv-plan-c-modulo-mesas.md`,
 *    Task 3 "Files") ya pedía `TablesDatabase` como archivo propio — Mesas es un
 *    módulo aislado a propósito (no importa nada de `features/ordering/` ni
 *    `features/checkout/`), y una base de datos propia es la misma regla
 *    aplicada a persistencia: un bug en el schema de Mesas nunca puede tumbar
 *    ni bloquear el arranque de la cola de pagos.
 *
 * **Versión:** empieza en 1 — es una base de datos nueva, sin historial que migrar.
 *
 * **Migration Strategy:** mientras solo exista `sync_intents`, cualquier cambio de
 * shape debe llegar con una migración explícita (mismo criterio que
 * `AvoqadoDatabase` — nunca `fallbackToDestructiveMigration` en producción, ver
 * `core/di/DatabaseModule.kt`).
 */
@Database(
    entities = [
        SyncIntentEntity::class,
    ],
    version = 1,
    exportSchema = true, // Schema JSON en app/schemas/ — DDL canónico para futuras migraciones
)
abstract class TablesDatabase : RoomDatabase() {

    /** DAO del outbox de Mesas — ver [SyncIntentDao]. */
    abstract fun syncIntentDao(): SyncIntentDao

    companion object {
        const val DATABASE_NAME = "avoqado_tables_database"
    }
}
