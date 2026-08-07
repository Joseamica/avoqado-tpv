package com.jaac.avoqado_tpv.features.payment.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database DEDICADA a telemetría local de intentos de autorización (Task 6,
 * plan `2026-08-04-event-loop-no-bloqueante-reportes`) — deliberadamente separada de
 * [com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase] y de
 * [com.jaac.avoqado_tpv.features.tables.data.local.TablesDatabase].
 *
 * **Por qué una base aparte y no una tabla más en AvoqadoDatabase o en TablesDatabase**
 * — mismo razonamiento que el KDoc de `TablesDatabase` aplicado a un dominio distinto:
 * 1. `AvoqadoDatabase` es dinero real (`pending_payments`) y está en migración activa
 *    en paralelo (rama `codex/payment-safety-phase-0`, ya en v31 ahí). Añadir una tabla
 *    aquí arriesga una colisión de versión de schema al mergear — para un dato que ni
 *    siquiera es dinero (código de resultado + duración, nada más).
 * 2. `TablesDatabase` es el outbox de Mesas (`sync_intents`) — un dominio acotado
 *    aparte a propósito (Mesas no importa nada de `features/payment/`). Acoplar la
 *    telemetría de autorización ahí forzaría a que un cambio de schema de Mesas
 *    arrastre una migración de telemetría de pagos, y viceversa — exactamente el
 *    acoplamiento que el propio KDoc de `TablesDatabase` advierte evitar, aplicado
 *    ahora entre dos dominios de `features/payment/` que tampoco deberían compartir
 *    ciclo de vida de schema (la libreta de pagos es dinero; esto es analítica
 *    descartable).
 * 3. Esta tabla es best-effort y descartable (telemetría, NO dinero): si su schema se
 *    corrompe o hay que resetearla, NUNCA debe poder arrastrar consigo
 *    `pending_payments` (dinero) ni `sync_intents` (outbox de Mesas). Aislar la base es
 *    la misma garantía de blast-radius que las otras dos ya usan — un `DROP TABLE`
 *    accidental aquí es, en el peor caso, unos días de reportes de red incompletos;
 *    en `AvoqadoDatabase` sería dinero perdido.
 *
 * **Versión:** empieza en 1 — base nueva, sin historial que migrar.
 *
 * **Migration Strategy:** mientras solo exista [AuthAttemptEntity], cualquier cambio de
 * shape debe llegar con una migración explícita (mismo criterio que `AvoqadoDatabase` y
 * `TablesDatabase` — nunca `fallbackToDestructiveMigration` en producción, ver
 * `core/di/DatabaseModule.kt`). En la práctica esta tabla es descartable, así que una
 * migración destructiva aquí es un riesgo mucho menor que en las otras dos bases — pero
 * el criterio se mantiene por consistencia y porque Room no distingue "importante" de
 * "descartable" al fallar silenciosamente un `fallbackToDestructiveMigration`.
 */
@Database(
    entities = [
        AuthAttemptEntity::class,
    ],
    version = 1,
    exportSchema = true, // Schema JSON en app/schemas/ — DDL canónico para futuras migraciones
)
abstract class AuthAttemptDatabase : RoomDatabase() {

    /** DAO de la telemetría de intentos de autorización — ver [AuthAttemptDao]. */
    abstract fun authAttemptDao(): AuthAttemptDao

    companion object {
        const val DATABASE_NAME = "avoqado_auth_attempts_database"
    }
}
