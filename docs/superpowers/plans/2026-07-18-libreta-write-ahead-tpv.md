# La Libreta (write-ahead ledger de cobros) — Plan 2: TPV en modo sombra

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que TODO intento de cobro con tarjeta (Blumon/PAX y AngelPay/Nexgo) deje evidencia durable ANTES de invocar al SDK y en cada frontera del flujo — cerrando la ventana Mindform ("money moved, no record, no queue") — desplegado en modo SOMBRA (observa/loguea, cero cambio de comportamiento para el cajero).

**Architecture:** Tabla Room nueva `payment_attempts` (v26→v27) + helper único `PaymentAttemptLedger` en `main/` (compartido por las 4 variantes) + hooks mínimos en los 3 ViewModels de pago + flag por venue `paymentLedgerMode` (OFF|SHADOW|ACTIVE, default OFF) con plumbing espejo de `cellularFailoverMode` + sweep de sombra con WorkManager. El recovery con auto-resolución y la UI (badge/bandeja/"Verificar ahora") son el **Plan 3** — dependen de respuestas de vendors (spec §8/§10).

**Tech Stack:** Kotlin, Room 2.7.0 (KSP, exportSchema), Hilt, WorkManager, Gson, MockK+JUnit (JVM) / MigrationTestHelper+Truth (androidTest).

**Spec:** `docs/superpowers/specs/2026-07-17-libreta-write-ahead-design.md` (v2). Este plan implementa §4.1–§4.4 + sombra de §4.5 + §6 (flag/rollout). NO implementa: §4.5 auto-resolución, §4.6 UI, §5.3/5.4 backend recovery+fingerprint (Plan 3).

## Global Constraints

- **La libreta JAMÁS bloquea un cobro** (spec §4.4): toda escritura envuelta en `runCatching`; un fallo degrada a "sin fila" = no peor que hoy. La ÚNICA espera síncrona permitida es la barrera pre-SDK (~ms de Room).
- **`attemptId === idempotencyKey`** en todo camino. PK de la tabla = ese UUID.
- **Sandbox y production byte-idénticos** en los edits de `PaymentViewModel.kt` (regla del repo #4). El helper vive en `main/` — los edits en variantes son solo llamadas. ⚠️ Los dos archivos NO son desplazables por offset constante (el bloque refund está reordenado) — verificar cada ancla por contenido, no por línea.
- **Room migración obligatoria** (regla #5): `CREATE TABLE IF NOT EXISTS`, DDL idéntico al schema JSON exportado, tests en `AvoqadoDatabaseMigrationTest`, `27.json` generado y commiteado.
- **Money = BigDecimal** en dominio; la tabla guarda **centavos como Long** (`movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()`).
- **Timezone**: la tabla usa epoch millis (`System.currentTimeMillis()`), nunca `SystemClock` (rompe unit tests — memoria del repo) ni zonas.
- **Tenant isolation**: toda query de LISTADO filtra `venue_id`. (Las transiciones CAS van por `attempt_id` — es UUID global y PK; el filtro de venue en listados es lo que impide fuga cross-venue.)
- **NO tocar `PaymentSyncWorker`** (spec §4.5). El sweep es un worker propio.
- **La tabla jamás alimenta reportes** (spec §6).
- **CHANGELOG.md** bajo `[Unreleased]` al terminar (regla #1).
- **Tier**: la libreta es integridad de dinero — NO gateada por tier (aplica a FREE incluido). Confirmado como supuesto del plan; el founder puede revertirlo.
- **Commits**: los hace el founder ("yo commiteo", 2026-07-18). Al final de cada tarea, dejar el working tree compilando + tests verdes y REPORTAR los archivos de la tarea; no ejecutar `git commit`.
- Compilar con `export JAVA_HOME=$(/usr/libexec/java_home -v 23)`. Check rápido: `./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin compileNexgoDebugKotlin`.
- Suite JVM: `./gradlew testSandboxDebugUnitTest` (544 tests, 0 fallos, 5 skip — línea base 2026-06-12).

## Hallazgos de exploración que CORRIGEN el spec (2026-07-18, verificados en código)

1. **Hay TRES sitios de autorización Blumon, no uno.** El spec ancla `HOST_RESPONDIO` en "~:3088"; el camino contactless-online llama a `performOnlineAuthorization` OTRA VEZ (sandbox `:4746-4780` / production `:4130-4164`). Escribir DENTRO de `performOnlineAuthorization` cubre ambos con un solo hook. Además el contactless **offline-aprobado** (sandbox `:4560` / production `:3946`) NUNCA pasa por ahí → la fila debe abrirse en `startPayment`, no en la autorización.
2. **AngelPay NO tiene momento "host respondió" separado**: el SDK corre su propia Activity y devuelve exactamente una vez (`result.approved` es todo el veredicto). `HOST_RESPONDIO` y `AUTORIZADO` colapsan en una sola escritura. Y `integratorReference` NUNCA se lee del resultado — no hace falta: **nosotros lo generamos** (`paymentAttemptId` se envía como `integratorReference`, `AngelPaySdkGateway.kt:124`). El P0 del spec §6 queda resuelto por construcción: la correlación es el propio attemptId.
3. **El riesgo de reuso de `ensurePaymentAttemptId` en split está CONFIRMADO estáticamente**: es get-or-create; solo `cancelPayment()`/`resetPayment()` lo limpian; un registro EXITOSO **no** lo limpia (el KDoc de `PaymentSession.kt:46` que dice lo contrario es FALSO). En split, "Continuar pagando" resetea solo si `orderId != null` (`PaymentScreen.kt:812-817`). Dos cobros split sin reset intermedio → misma key → el backend dedupea el segundo cobro dentro del primer registro → **dinero movido sin fila nueva**. El INSERT con PK=attemptId detecta esto GRATIS: colisión de PK con fila previa en estado ≥AUTORIZANDO = señal de doble cobro → log CRITICAL (instrumentación exigida por spec §6, obtenida por construcción).
4. **`AvoqadoDatabaseMigrationTest` tiene 2 defectos preexistentes que ROMPEN androidTest**: (a) líneas 367/369/370 usan `assertTrue`/`assertEquals` sin import (solo Truth está importado) — no compila; (b) línea 186 asevera `isEqualTo(25)` pero el builder real migra a 26 (se omitió en el commit f1230d1). Cualquier bump a 27 DEBE arreglar ambos o los tests de migración no corren.

---

### Task 1: Room v27 — entidad `payment_attempts` + DAO + migración + tests

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/ledger/PaymentAttemptEntity.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/ledger/PaymentAttemptDao.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/local/AvoqadoDatabase.kt` (entity list + `version = 27` + `MIGRATION_26_27` al final del companion, después de `MIGRATION_25_26` en `:1611`)
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/di/DatabaseModule.kt` (`.addMigrations(...)` `:84-109` + provider del DAO junto a `providePendingPaymentDao` `:164`)
- Modify: `app/src/androidTest/java/com/jaac/avoqado_tpv/core/data/local/AvoqadoDatabaseMigrationTest.kt` (fix 2 defectos + 2 tests nuevos)

**Interfaces:**
- Produces: `PaymentAttemptEntity` (tabla `payment_attempts`), `PaymentAttemptDao` (API abajo — Task 2 la consume con estos nombres EXACTOS), `AvoqadoDatabase.MIGRATION_26_27`, `AvoqadoDatabase.paymentAttemptDao(): PaymentAttemptDao`.

- [ ] **Step 1: Entidad** — crear `PaymentAttemptEntity.kt`:

```kotlin
package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * La Libreta — write-ahead ledger of card-charge attempts (spec 2026-07-17 §4).
 *
 * One row per attempt, keyed by the SAME UUID that travels to the backend as
 * `idempotencyKey`. Written BEFORE the SDK is invoked and at every durable
 * boundary, so a process death after bank approval (the Mindform $1,400 window:
 * "money moved, no record, no queue") always leaves evidence.
 *
 * NEVER feeds reports. NEVER blocks a charge (all writes are runCatching).
 */
@Entity(
    tableName = "payment_attempts",
    indices = [
        Index(value = ["state"]),
        Index(value = ["venue_id"]),
        Index(value = ["created_at"])
    ]
)
data class PaymentAttemptEntity(
    /** == idempotencyKey (paymentAttemptId). PK collision on a live row = attemptId reuse → double-charge signal. */
    @PrimaryKey @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    /** BLUMON | ANGELPAY */
    @ColumnInfo(name = "processor") val processor: String,
    /** SALE | REFUND (schema ready day-1; refund wiring ships later) */
    @ColumnInfo(name = "kind", defaultValue = "SALE") val kind: String = KIND_SALE,
    @ColumnInfo(name = "state") val state: String,
    /** Monotonic CAS counter — bumped on every accepted transition. */
    @ColumnInfo(name = "state_version", defaultValue = "0") val stateVersion: Int = 0,
    /** Centavos as Long — never decimal text (spec v2 correction). */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "tip_cents") val tipCents: Long,
    @ColumnInfo(name = "currency", defaultValue = "MXN") val currency: String = "MXN",
    /** FAST | ORDER | REFUND — recovery must replay the ORIGINAL route (spec §4.1). */
    @ColumnInfo(name = "recording_route") val recordingRoute: String,
    @ColumnInfo(name = "context_schema_version", defaultValue = "1") val contextSchemaVersion: Int = 1,
    /** Gson snapshot of the PaymentContext known BEFORE the charge (business data; card data lands in columns below). */
    @ColumnInfo(name = "payment_context_json") val paymentContextJson: String,
    // ── Host outcome (filled the instant the host responds) ──
    @ColumnInfo(name = "operation_id") val operationId: String? = null,
    @ColumnInfo(name = "reference_number") val referenceNumber: String? = null,
    @ColumnInfo(name = "auth_code") val authCode: String? = null,
    @ColumnInfo(name = "host_approved") val hostApproved: Boolean? = null,
    @ColumnInfo(name = "masked_pan") val maskedPan: String? = null,
    @ColumnInfo(name = "card_brand") val cardBrand: String? = null,
    @ColumnInfo(name = "entry_mode") val entryMode: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    // ── Recovery bookkeeping (Plan 3 uses these; schema ready day-1) ──
    @ColumnInfo(name = "verify_attempts", defaultValue = "0") val verifyAttempts: Int = 0,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        // States (spec §4.2). Spanish on purpose — they surface verbatim in ops tooling.
        const val STATE_PREPARANDO = "PREPARANDO"
        const val STATE_AUTORIZANDO = "AUTORIZANDO"
        const val STATE_HOST_RESPONDIO = "HOST_RESPONDIO"
        const val STATE_AUTORIZADO = "AUTORIZADO"
        const val STATE_REGISTRADO = "REGISTRADO"
        const val STATE_REGISTRO_FALLIDO = "REGISTRO_FALLIDO"
        const val STATE_ENTREGADA_A_COLA = "ENTREGADA_A_COLA"
        const val STATE_CERRADA = "CERRADA"
        const val STATE_DESCARTADA = "DESCARTADA"
        const val STATE_INDETERMINADO = "INDETERMINADO"

        const val PROCESSOR_BLUMON = "BLUMON"
        const val PROCESSOR_ANGELPAY = "ANGELPAY"
        const val KIND_SALE = "SALE"
        const val KIND_REFUND = "REFUND"
        const val ROUTE_FAST = "FAST"
        const val ROUTE_ORDER = "ORDER"
        const val ROUTE_REFUND = "REFUND"

        /** Non-terminal states = "money may have moved with no record" — the sweep watches these. */
        val OPEN_STATES = listOf(
            STATE_PREPARANDO, STATE_AUTORIZANDO, STATE_HOST_RESPONDIO,
            STATE_AUTORIZADO, STATE_REGISTRO_FALLIDO, STATE_INDETERMINADO
        )
    }
}
```

- [ ] **Step 2: DAO** — crear `PaymentAttemptDao.kt`:

```kotlin
package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PaymentAttemptDao {

    /** Returns -1 when the PK already exists (attemptId reuse — double-charge signal, never silent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attempt: PaymentAttemptEntity): Long

    @Query("SELECT * FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun getById(attemptId: String): PaymentAttemptEntity?

    /**
     * The CAS everything rides on (spec §4.4): a transition only lands if the row
     * is still in one of the expected states. Returns 0 when it didn't match —
     * caller logs and moves on (worker/callback/manual races resolve themselves).
     */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casTransition(attemptId: String, expectedStates: List<String>, newState: String, now: Long): Int

    /** CAS + host outcome in one statement — used the instant the host responds. */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               operation_id = :operationId, reference_number = :referenceNumber,
               auth_code = :authCode, host_approved = :hostApproved
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casHostResponded(
        attemptId: String, expectedStates: List<String>, newState: String, now: Long,
        operationId: String?, referenceNumber: String?, authCode: String?, hostApproved: Boolean
    ): Int

    /** CAS + card details (available at record time). */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               masked_pan = :maskedPan, card_brand = :cardBrand, entry_mode = :entryMode
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casWithCardDetails(
        attemptId: String, expectedStates: List<String>, newState: String, now: Long,
        maskedPan: String?, cardBrand: String?, entryMode: String?
    ): Int

    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now, last_error = :error
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casWithError(attemptId: String, expectedStates: List<String>, newState: String, now: Long, error: String?): Int

    // ── Sweep / shadow observability (ALWAYS venue-scoped — tenant isolation) ──

    @Query("SELECT * FROM payment_attempts WHERE venue_id = :venueId AND state IN (:states) AND created_at < :olderThan ORDER BY created_at ASC LIMIT 50")
    suspend fun getOpenOlderThan(venueId: String, states: List<String>, olderThan: Long): List<PaymentAttemptEntity>

    /** AUTORIZANDO stuck past the threshold = process died mid-auth → quarantine (spec §4.5). */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now
           WHERE venue_id = :venueId AND state = 'AUTORIZANDO' AND updated_at < :olderThan"""
    )
    suspend fun quarantineStaleAuthorizing(venueId: String, olderThan: Long, now: Long): Int

    /** Happy-path rows close silently after a day (spec §4.3). */
    @Query(
        """UPDATE payment_attempts
           SET state = 'CERRADA', state_version = state_version + 1, updated_at = :now
           WHERE venue_id = :venueId AND state = 'REGISTRADO' AND updated_at < :olderThan"""
    )
    suspend fun closeRecordedOlderThan(venueId: String, olderThan: Long, now: Long): Int

    /** Prune terminal rows at ~7 days (mirror of deleteOldSyncedPayments). INDETERMINADO is NEVER deleted. */
    @Query("DELETE FROM payment_attempts WHERE venue_id = :venueId AND state IN ('CERRADA','DESCARTADA') AND updated_at < :olderThan")
    suspend fun pruneTerminalOlderThan(venueId: String, olderThan: Long): Int
}
```

- [ ] **Step 3: Registrar en la DB** — en `AvoqadoDatabase.kt`:
  - Entity list (`:104-117`): agregar `com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity::class // ⭐ v27: payment_attempts write-ahead ledger (la libreta)`
  - `version = 27, // ⭐ Version 27: payment_attempts — write-ahead ledger de cobros (la libreta) (2026-07-18)`
  - Abstract fun junto a los demás DAOs: `abstract fun paymentAttemptDao(): com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao`
  - Migración al final del companion (estilo EXACTO de `MIGRATION_25_26` — param `database`, backticks). ⚠️ El DDL de abajo es provisional: **el DDL canónico se copia de `27.json`** tras el primer build (Step 5) — Room genera el `createSql` exacto y CUALQUIER divergencia truena `validateMigration`:

```kotlin
        /**
         * v27 (2026-07-18) — la libreta: write-ahead ledger of card-charge attempts.
         *
         * New table only — zero risk to existing rows. Closes the Mindform window
         * ("money moved, no record, no queue"): evidence now exists BEFORE the SDK
         * is invoked. DDL copied verbatim from app/schemas/.../27.json.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `payment_attempts` (" +
                        "`attempt_id` TEXT NOT NULL, `venue_id` TEXT NOT NULL, " +
                        "`processor` TEXT NOT NULL, `kind` TEXT NOT NULL DEFAULT 'SALE', " +
                        "`state` TEXT NOT NULL, `state_version` INTEGER NOT NULL DEFAULT 0, " +
                        "`amount_cents` INTEGER NOT NULL, `tip_cents` INTEGER NOT NULL, " +
                        "`currency` TEXT NOT NULL DEFAULT 'MXN', `recording_route` TEXT NOT NULL, " +
                        "`context_schema_version` INTEGER NOT NULL DEFAULT 1, " +
                        "`payment_context_json` TEXT NOT NULL, " +
                        "`operation_id` TEXT, `reference_number` TEXT, `auth_code` TEXT, " +
                        "`host_approved` INTEGER, `masked_pan` TEXT, `card_brand` TEXT, " +
                        "`entry_mode` TEXT, `last_error` TEXT, " +
                        "`verify_attempts` INTEGER NOT NULL DEFAULT 0, `lease_until` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`attempt_id`))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_state` ON `payment_attempts` (`state`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_venue_id` ON `payment_attempts` (`venue_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_created_at` ON `payment_attempts` (`created_at`)")
            }
        }
```

  - Actualizar el KDoc rancio del header (`:35` dice "Current Version: 24") a 27 y añadir la línea v27 al changelog del comentario.

- [ ] **Step 4: DatabaseModule** — `.addMigrations(...)`: coma en la línea de `MIGRATION_25_26` + `AvoqadoDatabase.MIGRATION_26_27   // 📒 La libreta — write-ahead ledger de cobros`. Actualizar el KDoc `:61` ("chain 2→24" → "2→27"). Provider nuevo junto a `providePendingPaymentDao` (`:164`):

```kotlin
    @Provides
    fun providePaymentAttemptDao(database: AvoqadoDatabase): com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao =
        database.paymentAttemptDao()
```

- [ ] **Step 5: Generar y commitear el schema** — `./gradlew compileSandboxDebugKotlin` → verifica que `app/schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/27.json` existe. **Copiar el `createSql` de `payment_attempts` de `27.json` al DDL del Step 3 verbatim** (orden de columnas incluido) si difiere.

- [ ] **Step 6: Fix de los 2 defectos preexistentes de `AvoqadoDatabaseMigrationTest.kt`** (hallazgo #4): (a) reescribir las líneas 367-370 a estilo Truth: `assertThat(c.moveToFirst()).isTrue()` / `assertThat(c.getString(1)).isEqualTo("250.00")` / `assertThat(c.getString(2)).isEqualTo("idem-1")` / `assertThat(c.isNull(3)).isTrue()`; (b) línea 186: `isEqualTo(25)` → `isEqualTo(27)` (con el bump ya aplicado).

- [ ] **Step 7: Tests de migración nuevos** (mismo archivo, plantillas de `:332-342` y `:344-372`):

```kotlin
    @Test
    fun migrate26To27_freshSchema_validatesAgainstV27() {
        helper.createDatabase(TEST_DB, 26).close()
        // Throws if MIGRATION_26_27 leaves the schema diverging from 27.json.
        helper.runMigrationsAndValidate(TEST_DB, 27, true, AvoqadoDatabase.MIGRATION_26_27)
    }

    /**
     * The money guarantee, v27 edition: queued payments (real money) and every
     * other table must survive untouched — v27 only ADDS payment_attempts.
     */
    @Test
    fun migrate26To27_preservesQueuedPaymentsAndCreatesEmptyLedger() {
        helper.createDatabase(TEST_DB, 26).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, payment_processor, retry_count, sync_status) VALUES " +
                    "('REF-V27-001','v1','s1','980.00','98.00','m1','SER1','CHIP',0,123,'idem-27','BLUMON',0,'PENDING')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 27, true, AvoqadoDatabase.MIGRATION_26_27)

        migrated.query("SELECT amount, idempotency_key FROM pending_payments WHERE reference_number = 'REF-V27-001'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("980.00")
            assertThat(c.getString(1)).isEqualTo("idem-27")
        }
        migrated.query("SELECT COUNT(*) FROM payment_attempts").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }
```

- [ ] **Step 8: Verificar** — `./gradlew compileSandboxDebugKotlin` limpio. Si hay emulador/dispositivo: `./gradlew connectedSandboxDebugAndroidTest --tests "*AvoqadoDatabaseMigrationTest*"`; si no, marcar como pendiente de dispositivo en el reporte. Reportar archivos de la tarea para commit del founder.

---

### Task 2: `PaymentAttemptLedger` — el helper único (main/) + tests JVM

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/ledger/PaymentAttemptLedger.kt`
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/data/ledger/PaymentAttemptLedgerTest.kt`

**Interfaces:**
- Consumes: `PaymentAttemptDao` (Task 1), `TpvSettingsRepository.getCurrentSettings()` (existente), `PaymentLedgerMode` (Task 3 — para compilar esta tarea primero, el enum se crea AQUÍ si Task 3 aún no corrió; ver Step 1).
- Produces (Tasks 4/5/6 usan estos nombres EXACTOS):
  - `suspend fun openAttempt(attemptId, venueId, processor, amountCents, tipCents, recordingRoute, contextJson): Boolean` — barrera: INSERT PREPARANDO **commiteado** antes de retornar; `false` = colisión de PK (reuso → log CRITICAL).
  - `suspend fun markAuthorizing(attemptId)` — barrera pre-SDK (CAS desde PREPARANDO, commiteado antes de retornar).
  - `suspend fun markHostResponded(attemptId, approved, operationId, referenceNumber, authCode)` — CAS desde {AUTORIZANDO, PREPARANDO}; approved=false ⇒ estado destino `DESCARTADA` (decline explícito), approved=true ⇒ `HOST_RESPONDIO`. `NonCancellable`.
  - `suspend fun markAuthorized(attemptId, maskedPan?, cardBrand?, entryMode?)` — CAS desde {HOST_RESPONDIO, AUTORIZANDO, PREPARANDO} (el from-PREPARANDO cubre contactless offline-aprobado). `NonCancellable`.
  - `suspend fun markRecorded(attemptId)` — CAS desde {AUTORIZADO, HOST_RESPONDIO}. `NonCancellable`.
  - `suspend fun markRecordFailed(attemptId, error)` — CAS desde {AUTORIZADO, HOST_RESPONDIO}. `NonCancellable`.
  - `suspend fun markDeliveredToQueue(attemptId)` — CAS desde {REGISTRO_FALLIDO}. `NonCancellable`.
  - `suspend fun markDiscardedBeforeCharge(attemptId, reason)` — CAS desde {PREPARANDO} ÚNICAMENTE (un cancel durante AUTORIZANDO deja la fila viva — desenlace desconocido).
  - `fun isEnabled(): Boolean` — `paymentLedgerMode != OFF`.

- [ ] **Step 1: Tests que fallan** — `PaymentAttemptLedgerTest.kt` (MockK, patrón del repo; DAO `mockk(relaxed = true)` con returns explícitos para los CAS):

```kotlin
package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PaymentAttemptLedgerTest {

    private val dao = mockk<PaymentAttemptDao>(relaxed = true)
    private val settingsRepository = mockk<TpvSettingsRepository>()
    private lateinit var ledger: PaymentAttemptLedger

    private fun settingsWith(mode: PaymentLedgerMode) =
        TpvSettings.DEFAULT.copy(paymentLedgerMode = mode)

    @Before
    fun setup() {
        every { settingsRepository.getCurrentSettings() } returns settingsWith(PaymentLedgerMode.SHADOW)
        ledger = PaymentAttemptLedger(dao, settingsRepository)
    }

    @Test
    fun `OFF mode - no writes at all`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns settingsWith(PaymentLedgerMode.OFF)
        ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 1000, PaymentAttemptEntity.ROUTE_FAST, "{}")
        ledger.markAuthorizing("a1")
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.casTransition(any(), any(), any(), any()) }
    }

    @Test
    fun `openAttempt inserts PREPARANDO and returns true`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val ok = ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 1000, PaymentAttemptEntity.ROUTE_FAST, "{}")
        assertTrue(ok)
        coVerify {
            dao.insert(match { it.attemptId == "a1" && it.state == PaymentAttemptEntity.STATE_PREPARANDO && it.amountCents == 10000L })
        }
    }

    @Test
    fun `openAttempt detects attemptId reuse (PK collision) and returns false`() = runTest {
        coEvery { dao.insert(any()) } returns -1L // OnConflictStrategy.IGNORE → row existed
        val ok = ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 0, PaymentAttemptEntity.ROUTE_FAST, "{}")
        assertFalse(ok) // the split double-charge signal — caller logs CRITICAL
    }

    @Test
    fun `markHostResponded approved=false lands DESCARTADA (explicit decline)`() = runTest {
        coEvery { dao.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ledger.markHostResponded("a1", approved = false, operationId = "123", referenceNumber = "r", authCode = null)
        coVerify {
            dao.casHostResponded("a1", any(), PaymentAttemptEntity.STATE_DESCARTADA, any(), "123", "r", null, false)
        }
    }

    @Test
    fun `markHostResponded approved=true lands HOST_RESPONDIO`() = runTest {
        coEvery { dao.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ledger.markHostResponded("a1", approved = true, operationId = "123", referenceNumber = "r", authCode = "A1")
        coVerify {
            dao.casHostResponded("a1", any(), PaymentAttemptEntity.STATE_HOST_RESPONDIO, any(), "123", "r", "A1", true)
        }
    }

    @Test
    fun `a DAO exception never propagates (never blocks the charge)`() = runTest {
        coEvery { dao.insert(any()) } throws RuntimeException("disk io")
        // must not throw:
        ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1, 0, PaymentAttemptEntity.ROUTE_FAST, "{}")
    }

    @Test
    fun `markDiscardedBeforeCharge only transitions from PREPARANDO`() = runTest {
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 0
        ledger.markDiscardedBeforeCharge("a1", "user_cancel")
        coVerify {
            dao.casWithError("a1", listOf(PaymentAttemptEntity.STATE_PREPARANDO), PaymentAttemptEntity.STATE_DESCARTADA, any(), "user_cancel")
        }
    }
}
```

- [ ] **Step 2: Correr — FALLAN** (`./gradlew testSandboxDebugUnitTest --tests "*PaymentAttemptLedgerTest*"`; la clase no existe).

- [ ] **Step 3: Implementación** — `PaymentAttemptLedger.kt`:

```kotlin
package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La Libreta — the single write-ahead API every payment path calls (spec §4.4).
 *
 * Guarantees:
 *  - Writes are committed BEFORE returning (Room suspend DAO = committed on return),
 *    so `openAttempt`+`markAuthorizing` form the pre-SDK barrier.
 *  - Post-SDK marks run inside NonCancellable + IO: they survive screen pops and
 *    ViewModel clears (the Mindform window).
 *  - A ledger failure NEVER blocks the charge: every entry point is runCatching;
 *    degradation = "no row", which is exactly today's behavior.
 *  - Gated by paymentLedgerMode (OFF = hard no-op).
 */
@Singleton
class PaymentAttemptLedger @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val settingsRepository: TpvSettingsRepository
) {

    fun isEnabled(): Boolean =
        settingsRepository.getCurrentSettings().paymentLedgerMode != PaymentLedgerMode.OFF

    suspend fun openAttempt(
        attemptId: String,
        venueId: String,
        processor: String,
        amountCents: Long,
        tipCents: Long,
        recordingRoute: String,
        contextJson: String,
        kind: String = PaymentAttemptEntity.KIND_SALE
    ): Boolean {
        if (!isEnabled()) return true
        return runCatching {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val rowId = dao.insert(
                    PaymentAttemptEntity(
                        attemptId = attemptId,
                        venueId = venueId,
                        processor = processor,
                        kind = kind,
                        state = PaymentAttemptEntity.STATE_PREPARANDO,
                        amountCents = amountCents,
                        tipCents = tipCents,
                        recordingRoute = recordingRoute,
                        paymentContextJson = contextJson,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                if (rowId == -1L) {
                    // PK collision: this attemptId already has a live row. This is the
                    // split/kiosk reuse signal (spec §6) — a second charge is about to
                    // ride an idempotency key the backend will dedupe into the FIRST
                    // record. Loud, never silent.
                    Timber.e("📒🚨 [Libreta] attemptId REUSE detected | attemptId=%s — possible dedup-swallowed charge", attemptId)
                    false
                } else {
                    Timber.d("📒 [Libreta] PREPARANDO | attemptId=%s amount=%d+%d", attemptId, amountCents, tipCents)
                    true
                }
            }
        }.getOrElse { e ->
            Timber.e(e, "📒 [Libreta] openAttempt failed — charge continues unledgered")
            true // never block the charge
        }
    }

    /** Pre-SDK barrier: committed before the SDK call is allowed to start. */
    suspend fun markAuthorizing(attemptId: String) = cas(
        attemptId, from = listOf(PaymentAttemptEntity.STATE_PREPARANDO),
        to = PaymentAttemptEntity.STATE_AUTORIZANDO, label = "AUTORIZANDO"
    )

    /**
     * The instant the host answers — BEFORE EMV completion, BEFORE publishing
     * Success. approved=false is an explicit host decline → DESCARTADA (spec §4.2).
     */
    suspend fun markHostResponded(
        attemptId: String,
        approved: Boolean,
        operationId: String?,
        referenceNumber: String?,
        authCode: String?
    ) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val to = if (approved) PaymentAttemptEntity.STATE_HOST_RESPONDIO else PaymentAttemptEntity.STATE_DESCARTADA
                val n = dao.casHostResponded(
                    attemptId,
                    listOf(PaymentAttemptEntity.STATE_AUTORIZANDO, PaymentAttemptEntity.STATE_PREPARANDO),
                    to, System.currentTimeMillis(),
                    operationId, referenceNumber, authCode, approved
                )
                logCas(n, attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markHostResponded failed") }
    }

    /** From-PREPARANDO covers contactless offline-approved (no online auth ever runs). */
    suspend fun markAuthorized(attemptId: String, maskedPan: String?, cardBrand: String?, entryMode: String?) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithCardDetails(
                    attemptId,
                    listOf(
                        PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                        PaymentAttemptEntity.STATE_AUTORIZANDO,
                        PaymentAttemptEntity.STATE_PREPARANDO
                    ),
                    PaymentAttemptEntity.STATE_AUTORIZADO, System.currentTimeMillis(),
                    maskedPan, cardBrand, entryMode
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_AUTORIZADO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markAuthorized failed") }
    }

    suspend fun markRecorded(attemptId: String) = casNonCancellable(
        attemptId,
        from = listOf(PaymentAttemptEntity.STATE_AUTORIZADO, PaymentAttemptEntity.STATE_HOST_RESPONDIO),
        to = PaymentAttemptEntity.STATE_REGISTRADO, label = "REGISTRADO"
    )

    suspend fun markRecordFailed(attemptId: String, error: String?) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                val n = dao.casWithError(
                    attemptId,
                    listOf(PaymentAttemptEntity.STATE_AUTORIZADO, PaymentAttemptEntity.STATE_HOST_RESPONDIO),
                    PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, System.currentTimeMillis(), error?.take(500)
                )
                logCas(n, attemptId, PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markRecordFailed failed") }
    }

    /** Once queued, pending_payments owns the money (its idempotency + retry) — the ledger row rests. */
    suspend fun markDeliveredToQueue(attemptId: String) = casNonCancellable(
        attemptId, from = listOf(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO),
        to = PaymentAttemptEntity.STATE_ENTREGADA_A_COLA, label = "ENTREGADA_A_COLA"
    )

    /** ONLY from PREPARANDO: a cancel during AUTORIZANDO has an unknown outcome — the row must live. */
    suspend fun markDiscardedBeforeCharge(attemptId: String, reason: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(Dispatchers.IO) {
                dao.casWithError(
                    attemptId, listOf(PaymentAttemptEntity.STATE_PREPARANDO),
                    PaymentAttemptEntity.STATE_DESCARTADA, System.currentTimeMillis(), reason
                )
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] markDiscardedBeforeCharge failed") }
    }

    private suspend fun cas(attemptId: String, from: List<String>, to: String, label: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(Dispatchers.IO) {
                logCas(dao.casTransition(attemptId, from, to, System.currentTimeMillis()), attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] mark%s failed", label) }
    }

    private suspend fun casNonCancellable(attemptId: String, from: List<String>, to: String, label: String) {
        if (!isEnabled()) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                logCas(dao.casTransition(attemptId, from, to, System.currentTimeMillis()), attemptId, to)
            }
        }.onFailure { Timber.e(it, "📒 [Libreta] mark%s failed", label) }
    }

    private fun logCas(updated: Int, attemptId: String, to: String) {
        if (updated == 1) {
            Timber.d("📒 [Libreta] %s | attemptId=%s", to, attemptId)
        } else {
            // Not an error: races (worker vs callback vs manual) resolve by CAS — the loser logs.
            Timber.w("📒 [Libreta] CAS no-match → %s ignored | attemptId=%s", to, attemptId)
        }
    }
}
```

- [ ] **Step 4: Correr los tests** — verdes. Nota: si Task 3 no ha corrido aún, crear primero el enum mínimo `PaymentLedgerMode` y el campo en `TpvSettings` (contenido exacto en Task 3 Step 1-2) para compilar — Task 3 después completa DTO/SecureStorage/server.

- [ ] **Step 5: `./gradlew compileSandboxDebugKotlin` + reportar archivos.**

---

### Task 3: Flag `paymentLedgerMode` (OFF|SHADOW|ACTIVE) — TPV + server

Plumbing espejo EXACTO de `cellularFailoverMode` (5 puntos TPV + 2 archivos server). Default OFF en TODAS partes: la app puede desplegarse antes que el backend y viceversa.

**Files (TPV):**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/TpvSettings.kt` (enum + campo, patrón `:3-15` y `:109-110`)
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/TpvSettingsDto.kt` (campo `:148-150`, `toDomain()` `:210`, `toDto()` `:253`)
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt` (key `:154`, save `:1197`, read `:1263`, clear `:1322`)
- Test: ampliar el test existente de settings (trigger map: `TpvSettingsDto.kt` → `*AttendanceVerificationTest*`; verificar con `grep -rn "cellularFailoverMode" app/src/test/` el archivo que cubre el roundtrip y añadir ahí el caso)

**Files (server, avoqado-server):**
- Modify: `src/controllers/tpv/terminal.tpv.controller.ts` (tipo `:65` zona, default `:116` zona, y el merge `getTpvSettingsFromConfig`)
- Modify: `src/services/dashboard/tpv.dashboard.service.ts` (tipo `:522` zona, default `:562` zona, y su merge/update equivalente — grep `cellularFailoverMode` dentro del archivo y replicar CADA aparición)

- [ ] **Step 1: Enum (TPV)** — en `TpvSettings.kt`, junto a `CellularFailoverMode`:

```kotlin
/**
 * La libreta (write-ahead payment ledger) rollout stage.
 * OFF = no writes at all · SHADOW = writes + observability, zero behavior change ·
 * ACTIVE = recovery + UI enabled (Plan 3). Default OFF — canary rollout per venue.
 */
enum class PaymentLedgerMode {
    OFF,
    SHADOW,
    ACTIVE;

    companion object {
        fun fromRaw(raw: String?): PaymentLedgerMode {
            if (raw.isNullOrBlank()) return OFF
            return entries.firstOrNull { it.name == raw } ?: OFF
        }
    }
}
```

- [ ] **Step 2: Campo en `TpvSettings`** (junto a `cellularFailoverMode` `:109-110`): `val paymentLedgerMode: PaymentLedgerMode = PaymentLedgerMode.OFF,` + línea KDoc.

- [ ] **Step 3: DTO + mappers** — campo `@SerializedName("paymentLedgerMode") val paymentLedgerMode: String? = null,`; `toDomain()`: `paymentLedgerMode = PaymentLedgerMode.fromRaw(paymentLedgerMode),`; `toDto()`: `paymentLedgerMode = paymentLedgerMode.name,`; import del enum.

- [ ] **Step 4: SecureStorage** — key `private const val KEY_TPV_PAYMENT_LEDGER_MODE = "tpv_payment_ledger_mode"`; en `saveTpvSettings`: `putString(KEY_TPV_PAYMENT_LEDGER_MODE, settings.paymentLedgerMode.name)`; en `getTpvSettings()`: `paymentLedgerMode = PaymentLedgerMode.fromRaw(encryptedPrefs.getString(KEY_TPV_PAYMENT_LEDGER_MODE, null)),`; en el clear de venue-switch: `remove(KEY_TPV_PAYMENT_LEDGER_MODE)`.

- [ ] **Step 5: Test roundtrip** — en el test de settings hallado por el grep, caso: DTO con `paymentLedgerMode = "SHADOW"` → domain `SHADOW`; DTO con `null` → `OFF`; DTO con `"GARBAGE"` → `OFF`.

- [ ] **Step 6: Server** — en AMBOS archivos: tipo `paymentLedgerMode: 'OFF' | 'SHADOW' | 'ACTIVE'`, default `paymentLedgerMode: 'OFF',` con comentario `// La libreta (write-ahead payment ledger) — OFF by default, canary per venue`, y agregar el campo en el/los merges que enumeran campos explícitamente (buscar TODAS las apariciones de `cellularFailoverMode` en cada archivo y replicar al lado). `npx tsc --noEmit` (heap 6144) limpio. **Nota cross-repo:** campo aditivo con default — orden de deploy indiferente.

- [ ] **Step 7: `./gradlew testSandboxDebugUnitTest --tests "*AttendanceVerification*" --tests "*PaymentAttemptLedgerTest*"` + compile. Reportar archivos (2 repos).**

---

### Task 4: Cableado Blumon — sandbox y production (edits byte-idénticos)

⚠️ **Regla dura:** cada edit se aplica DOS veces con el MISMO texto insertado. Anclas por CONTENIDO (el bloque refund está reordenado entre variantes — línea ≠ línea). Antes de editar, correr `grep -n "<ancla>" <archivo>` y confirmar.

**Files:**
- Modify: `app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt`
- Modify: `app/src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt`
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModelTest.kt` (ampliar — el helper `createViewModel()` gana el dep #34 `paymentAttemptLedger = mockk(relaxed = true)`)

**Interfaces:**
- Consumes: `PaymentAttemptLedger` (Task 2). Inyectar en el constructor de ambos ViewModels (mismo texto).

- [ ] **Step 1: Tests que fallan** — en `PaymentViewModelTest`, con el mock relajado del ledger:

```kotlin
    @Test
    fun `startPayment opens a ledger attempt before charging`() = runTest {
        // arrange igual al patrón existente de startPayment del archivo (merchant + shift mocks)
        viewModel.startPayment(amount = BigDecimal("100.00"), selectedMsiMonths = null)
        Thread.sleep(1000) // Dispatchers.IO fuera del test dispatcher — patrón del archivo
        coVerify { paymentAttemptLedger.openAttempt(any(), any(), PaymentAttemptEntity.PROCESSOR_BLUMON, any(), any(), any(), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelPayment discards the ledger row only from PREPARANDO`() = runTest {
        viewModel.cancelPayment()
        coVerify(atMost = 1) { paymentAttemptLedger.markDiscardedBeforeCharge(any(), any()) }
        viewModel.viewModelScope.cancel()
    }
```

(El resto de las transiciones se cubren por los tests del ledger — aquí solo se verifica el cableado alcanzable desde JVM; `performOnlineAuthorization`/`handlePaymentSuccess` requieren SDK real → drills de dispositivo, Task 7.)

- [ ] **Step 2: Inyección** — constructor de ambos ViewModels: `private val paymentAttemptLedger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger,` (Hilt lo provee — @Singleton @Inject).

- [ ] **Step 3: Abrir la fila en `startPayment`** — ancla: la llamada `ensurePaymentAttemptId()` dentro de `startPayment` (sandbox `:2533` / production `:2522`). Insertar INMEDIATAMENTE después (mismo texto en ambos):

```kotlin
        // 📒 [Libreta] Write-ahead: the attempt exists on disk BEFORE any SDK code runs.
        // Committed before proceeding (suspend). A ledger failure never blocks the charge.
        run {
            val venueIdForLedger = secureStorage.getVenueId()
            if (venueIdForLedger != null) {
                val ledgerContext = createPaymentContext()
                val route = if (ledgerContext is com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.OrderPayment)
                    com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.ROUTE_ORDER
                else
                    com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.ROUTE_FAST
                paymentAttemptLedger.openAttempt(
                    attemptId = paymentAttemptId,
                    venueId = venueIdForLedger,
                    processor = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.PROCESSOR_BLUMON,
                    amountCents = amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                    tipCents = sessionSnapshot.tip.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                    recordingRoute = route,
                    contextJson = com.google.gson.Gson().toJson(ledgerContext)
                )
            }
        }
```

  **Verificación in-situ obligatoria antes de este edit:** (a) `grep -n "fun createPaymentContext" PaymentViewModel.kt` — confirmar que es zero-arg y devuelve `PaymentContext` (aparece usado así en sandbox `:3095`); (b) confirmar el nombre de la variable local del attemptId en `startPayment` (el snippet asume `paymentAttemptId`; si es otra, ajustar EN AMBAS variantes igual); (c) confirmar que `sessionSnapshot.tip` ya está poblado en ese punto — si no, usar `BigDecimal.ZERO` y documentarlo (la cifra exacta la trae el registro; la libreta necesita el monto para correlación, no para contabilidad).

- [ ] **Step 4: Barrera AUTORIZANDO** — ancla: `paymentStateHolder.setCharging(true)` dentro de `performOnlineAuthorization` (sandbox `:3850` / production `:3237`). Insertar después:

```kotlin
        // 📒 [Libreta] Barrier: AUTORIZANDO is committed BEFORE the SDK may fire (spec §4.4).
        sessionSnapshot.paymentAttemptId?.let { paymentAttemptLedger.markAuthorizing(it) }
```

- [ ] **Step 5: HOST_RESPONDIO** — ancla: el decline-guard dentro de `performOnlineAuthorization`, el bloque `if (declineMessage != null) { ... } else { AuthorizationResult(response = response, userFriendlyError = null) }` (sandbox `~:4130` / production `~:3517`). Insertar JUSTO ANTES del `if (declineMessage != null)`:

```kotlin
                    // 📒 [Libreta] The instant the host answered — BEFORE EMV completion and
                    // BEFORE publishing Success. This closes the Mindform window: from here on,
                    // a process death leaves an operationId on disk to reconcile against.
                    sessionSnapshot.paymentAttemptId?.let {
                        paymentAttemptLedger.markHostResponded(
                            attemptId = it,
                            approved = declineMessage == null,
                            operationId = response.operation,
                            referenceNumber = response.saleData.reference,
                            authCode = response.saleData.authorization
                        )
                    }
```

  **Cobertura:** este único punto cubre chip Y contactless-online (ambos llaman `performOnlineAuthorization` — hallazgo #1). El branch `saleFailure` NO escribe (sin respuesta del host → la fila queda AUTORIZANDO → cuarentena por sweep si el proceso muere: exactamente la semántica del spec).

- [ ] **Step 6: AUTORIZADO + REGISTRADO/FALLIDO/COLA en `handlePaymentSuccess`** — ancla: `recordingInFlight = true` (sandbox `:6317` / production `:5718`). Insertar después:

```kotlin
        // 📒 [Libreta] Money moved (covers offline-approved contactless too, via from-PREPARANDO).
        context.idempotencyKey?.let {
            paymentAttemptLedger.markAuthorized(
                attemptId = it,
                maskedPan = cardDetails.maskedPan,
                cardBrand = cardDetails.cardBrand.name,
                entryMode = cardDetails.entryMode.name
            )
        }
```

  Ancla: el `onSuccess` del resultado de `recordPaymentUseCase` (sandbox `~:6443` / production `~:5844` — verificar por contenido `result.onSuccess`): insertar `context.idempotencyKey?.let { paymentAttemptLedger.markRecorded(it) }` como primera línea del bloque.
  Ancla: el `onFailure` (sandbox `:6469` / production `:5870`): insertar `context.idempotencyKey?.let { paymentAttemptLedger.markRecordFailed(it, error.message) }` como primera línea.
  Ancla: `queueResult.onSuccess {` del enqueue (sandbox `~:6516` / production `~:5917`): insertar `context.idempotencyKey?.let { paymentAttemptLedger.markDeliveredToQueue(it) }` como primera línea. (El `onFailure` del enqueue NO escribe — la fila queda REGISTRO_FALLIDO: es la evidencia.)

- [ ] **Step 7: Descarte en cancel** — ancla: el bloque de `cancelPayment()` que limpia `paymentAttemptIdClear = true` (sandbox `:5619` / production `:5003`). Insertar ANTES del `updateSessionSnapshot`:

```kotlin
        // 📒 [Libreta] Only a row still in PREPARANDO can be discarded by a user cancel —
        // once AUTORIZANDO, the outcome is unknown and the row must survive (spec §4.2).
        sessionSnapshot.paymentAttemptId?.let { paymentAttemptLedger.markDiscardedBeforeCharge(it, "user_cancel") }
```

  ⚠️ `resetPayment()` NO toca la libreta (se llama post-éxito y en navegación — la fila ya está en su estado final correcto).

- [ ] **Step 8: Diff de variantes** — `diff <(sed -n '/startPayment/,/^    }/p' app/src/sandbox/.../PaymentViewModel.kt) <(...production...)` por cada región tocada; los bloques insertados deben ser idénticos. Correr `./gradlew testSandboxDebugUnitTest --tests "*PaymentViewModelTest*"` + `compileSandboxDebugKotlin compileProductionDebugKotlin`. Reportar archivos.

---

### Task 5: Cableado AngelPay (main/)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt`
- Test: `app/src/test/java/.../AngelPayPaymentViewModelTest.kt` (hallar con `grep -rln "AngelPayPaymentViewModel" app/src/test/` — ampliar con el mock del ledger)

**Interfaces:** consume `PaymentAttemptLedger`. AngelPay NO tiene HOST_RESPONDIO separado (hallazgo #2): el resultado del SDK ES el veredicto → una sola escritura `markHostResponded` al recibirlo.

- [ ] **Step 1: Inyección** — constructor: `private val paymentAttemptLedger: PaymentAttemptLedger,`.

- [ ] **Step 2: Abrir fila pre-launch** — DOS sitios:
  (a) `startAppToAppCardPayment` (`:1338`), después de `val paymentAttemptId = ensurePaymentAttemptId()` (`:1340`);
  (b) el camino SDK: hallar con `grep -n "buildPaymentRequest\|startSdkCardPayment\|launchSdkPayment" AngelPayPaymentViewModel.kt` el método que arma el request del SDK embebido (el gateway recibe `reference = paymentAttemptId` — `AngelPaySdkGateway.kt:116-124`) e insertar tras su `ensurePaymentAttemptId()`.
  Texto (idéntico en ambos sitios):

```kotlin
        // 📒 [Libreta] Write-ahead before launching the AngelPay SDK/intent. The SDK returns
        // exactly once — if the process dies while it runs, this row + integratorReference
        // (== attemptId) are the only correlation evidence that a charge was in flight.
        run {
            val venueIdForLedger = cachedVenueId ?: authRepository.getVenueId() ?: secureStorage.getVenueId()
            if (venueIdForLedger != null) {
                paymentAttemptLedger.openAttempt(
                    attemptId = paymentAttemptId,
                    venueId = venueIdForLedger,
                    processor = PaymentAttemptEntity.PROCESSOR_ANGELPAY,
                    amountCents = pendingAmount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                    tipCents = pendingTip.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                    recordingRoute = if (pendingOrderId != null) PaymentAttemptEntity.ROUTE_ORDER else PaymentAttemptEntity.ROUTE_FAST,
                    contextJson = "{\"schema\":1,\"processor\":\"ANGELPAY\",\"orderId\":${pendingOrderId?.let { "\"$it\"" } ?: "null"},\"orderNumber\":${pendingOrderNumber?.let { "\"$it\"" } ?: "null"}}"
                )
                paymentAttemptLedger.markAuthorizing(paymentAttemptId)
            }
        }
```

  (Estos métodos: si no son `suspend`, envolver en `viewModelScope.launch { ... }` ANTES del launch del intent — verificar in-situ; el orden write→launch debe preservarse, así que si el launch es síncrono, convertir el flujo a suspend o usar `runBlocking` NO — mover el launch dentro del mismo `launch {}` después de las escrituras.)

- [ ] **Step 3: Veredicto del host** — en `onAngelPayResult` (`:1467`) y `onAngelPaySdkResult` (`:1512`), después de `if (!consumeResultForCurrentAttempt(...)) return@launch`:

```kotlin
            // 📒 [Libreta] AngelPay's single return IS the host verdict (no separate
            // host-response moment exists for this processor).
            sessionAttemptIdOrNull()?.let { attemptId ->
                paymentAttemptLedger.markHostResponded(
                    attemptId = attemptId,
                    approved = /* path A: result is AngelPayResult.Success  |  path B: result.approved */,
                    operationId = null,
                    referenceNumber = /* result.referenceNumber | result.reference */,
                    authCode = /* result.authorizationCode | result.authCode */
                )
            }
```

  **Verificación in-situ:** el nombre del accessor del attemptId vigente (`sessionSnapshot.paymentAttemptId`? `ensurePaymentAttemptId()`? — en el camino SDK el id ya existe pre-launch por Step 2; usar el MISMO accessor que usa `recordCardPayment:1789`). Ajustar los campos por camino (A: `AngelPayResult.Success`; B: `PaymentResult`) — los nombres exactos están en los snippets de `AngelPayResultParser.kt:50-62` y `recordCardPayment:1784-1785`.

- [ ] **Step 4: Registro** — en ambos overloads de `recordCardPayment` (`:1662` y `:1747`): tras el `onSuccess` del `recordPaymentUseCase` → `paymentContext.idempotencyKey?.let { paymentAttemptLedger.markAuthorized(it, null, cardDetails.cardBrand.name, cardDetails.entryMode.name); paymentAttemptLedger.markRecorded(it) }`; en el `onFailure` (`:1824-1829`) → `paymentContext.idempotencyKey?.let { paymentAttemptLedger.markRecordFailed(it, error.message) }`. En `handleRecordFailure` (`:486-558`), dentro del branch `enqueueResult.isSuccess` → `context.idempotencyKey?.let { paymentAttemptLedger.markDeliveredToQueue(it) }`.

- [ ] **Step 5: Tests** — ampliar el test del VM AngelPay: `handleRecordFailure` con enqueue OK → verifica `markDeliveredToQueue`; resultado declinado → verifica `markHostResponded(approved = false ...)` (que el ledger convierte en DESCARTADA). Correr `./gradlew testSandboxDebugUnitTest --tests "*AngelPay*"` → verdes. `compileNexgoDebugKotlin` limpio. Reportar archivos.

---

### Task 6: Sweep de sombra + poda (WorkManager propio)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/ledger/LedgerShadowSweepWorker.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/ledger/LedgerSweepScheduler.kt`
- Modify: el sitio donde se agenda `PaymentSyncScheduler` al iniciar sesión/app (hallar con `grep -rn "PaymentSyncScheduler.schedule\|PaymentSyncScheduler.runNow" app/src/main/ | head`) — agendar el sweep AL LADO, mismo patrón. **NO tocar `PaymentSyncWorker`.**
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/data/ledger/LedgerSweepLogicTest.kt`

**Interfaces:** `LedgerShadowSweepWorker` (HiltWorker, unique periodic ~6h + one-shot al arrancar). Lógica pura extraída a `suspend fun sweepOnce(dao, venueId, observability, now): SweepResult` para testear en JVM sin WorkManager.

- [ ] **Step 1: Test de la lógica** (rojo→verde): filas AUTORIZANDO con `updated_at` > 10 min → `quarantineStaleAuthorizing`; REGISTRADO > 24h → CERRADA; CERRADA/DESCARTADA > 7 días → prune; filas en `OPEN_STATES` → un log de observabilidad por fila (shadow = SOLO log). INDETERMINADO jamás se borra.

- [ ] **Step 2: Worker** — patrón `@HiltWorker` + `CoroutineWorker` del repo (copiar estructura de `PaymentSyncWorker` SIN tocarlo). Gating: si `paymentLedgerMode == OFF` → `Result.success()` inmediato. venueId de `secureStorage.getVenueId()` (null → success). Cada fila abierta vieja se reporta vía el observability helper existente (mismo que usa AngelPay `observability.logWarning` — `AngelPayPaymentViewModel:558` zona) con tag `LibretaShadowOpenRow` y metadata `{attemptId, state, processor, amountCents, ageMinutes}`. Los umbrales como constantes: `STALE_AUTHORIZING_MIN = 10L`, `CLOSE_RECORDED_HOURS = 24L`, `PRUNE_DAYS = 7L`.

- [ ] **Step 3: Scheduler** — `LedgerSweepScheduler.schedule(context)`: `PeriodicWorkRequest` 6h, `ExistingPeriodicWorkPolicy.KEEP`, unique name `"ledger_shadow_sweep"`, + `runOnceNow(context)` one-shot con unique name `"ledger_shadow_sweep_once"` (`ExistingWorkPolicy.KEEP` — arranques repetidos no lo duplican). Llamarlo junto al scheduling existente de `PaymentSyncScheduler`.

- [ ] **Step 4: Tests + compile + reportar.**

---

### Task 7: Verificación integral + CHANGELOG + reporte

- [ ] **Step 1: Suite completa** — `./gradlew testSandboxDebugUnitTest` → 0 fallos (línea base 544+ los nuevos).
- [ ] **Step 2: Compilan las 4 variantes** — `./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin compileNexgoDebugKotlin compileTutorialEmuDebugKotlin`.
- [ ] **Step 3: Lint** — `./gradlew lint --continue` sin errores nuevos.
- [ ] **Step 4: Diff de variantes final** — cada bloque `📒 [Libreta]` aparece idéntico en sandbox y production (`grep -c "📒 \[Libreta\]"` debe dar el MISMO número en ambos).
- [ ] **Step 5: Server** — `npx tsc --noEmit` limpio en avoqado-server (Task 3 Step 6).
- [ ] **Step 6: CHANGELOG.md** bajo `[Unreleased]`:

```markdown
### **Added**
- **La Libreta (write-ahead ledger de cobros) — modo sombra**: nueva tabla Room `payment_attempts` (v26→v27) que registra CADA intento de cobro con tarjeta (Blumon y AngelPay) ANTES de invocar al SDK y en cada frontera (respuesta del host, autorización, registro, cola). Cierra la ventana del incidente Mindform ("money moved, no record, no queue"). Gateada por venue con `paymentLedgerMode` (OFF|SHADOW|ACTIVE, default OFF); en SHADOW solo observa y loguea — cero cambio de comportamiento. Incluye detección de reuso de attemptId (señal de doble cobro en split) y sweep de sombra cada 6h con cuarentena de intentos indeterminados.
```

- [ ] **Step 7: Drills de dispositivo (pendientes de hardware — listar en el reporte, NO bloquean el merge en SHADOW):** kill del proceso en cada frontera durable (spec §9) con un PAX en sandbox + flag SHADOW; verificar por `adb shell "run-as ... sqlite3"` o log `📒` que la fila queda en el estado esperado. Los 8 flujos del state machine con la libreta en SHADOW (no debe cambiar NINGÚN comportamiento visible).
- [ ] **Step 8: Reporte al founder** — archivos por tarea para sus commits + recomendación de versión: **PATCH** (SHADOW no da capacidad nueva al usuario; regla "¿puede hacer algo que no podía?" → no) + siguiente paso: activar SHADOW en 1-2 venues piloto (Madre Cafecito/avoqado-fitness) 1 semana antes del Plan 3.

## Self-review (hecho)

- **Cobertura vs spec §4:** tabla+campos v2 ✓(T1) · CAS+state_version ✓(T1/T2) · barrera pre-SDK commiteada ✓(T2/T4-Step4) · HOST_RESPONDIO en el instante real, cubriendo los 3 sitios de auth ✓(T4-Step5, hallazgo #1) · NonCancellable post-SDK ✓(T2) · never-block-charge ✓(T2) · attemptId==idempotencyKey ✓(PK) · snapshot+route ✓(T4-Step3) · venue_id + tenant isolation ✓(T1) · estados nuevos v2 ✓(T1) · CERRADA solo desde REGISTRADO ✓(DAO closeRecordedOlderThan) · DESCARTADA solo decline explícito o cancel pre-charge ✓(T2) · INDETERMINADO por sweep, nunca borrado ✓(T6) · flag por venue ✓(T3) · sombra solo-observa ✓(T6) · reuso split instrumentado ✓(hallazgo #3, T2) · AngelPay integratorReference resuelto por construcción ✓(hallazgo #2, T5).
- **Diferido a Plan 3 (explícito):** recovery auto-resolutivo, verificación TransactionDetails/getTransactionHistory, lock por procesador (solo aplica cuando el recovery consulta al SDK — en sombra nadie consulta), UI badge/bandeja/"Verificar ahora", `recordRecoveredPayment` + fingerprint backend, refund wiring (schema listo).
- **Consistencia de nombres:** `PaymentAttemptEntity`/`PaymentAttemptDao`/`PaymentAttemptLedger`/`PaymentLedgerMode`/`paymentLedgerMode`/estados — idénticos entre tareas y tests.
- **Placeholders:** los puntos que exigen confirmación in-situ (nombre de variable del attemptId en `startPayment`, accessor en AngelPay, sitio de launch del SDK AngelPay, archivo de test de settings) están marcados con el grep exacto y la instrucción de ajuste simétrico.
