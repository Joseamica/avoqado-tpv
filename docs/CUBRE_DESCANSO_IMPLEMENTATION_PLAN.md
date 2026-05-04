# Plan de implementación — Cubre-descanso (multi-venue clock-in)

> **Estado:** Draft v2 — incorpora hallazgos de auditoría externa (2026-04-30)
> **Repos involucrados:** `avoqado-server` (backend), `avoqado-tpv` (Android POS). Sin cambios de código en `avoqado-web-dashboard`.
> **Principio rector:** *No romper lo que ya funciona.* El POS core queda intacto. Toda la feature vive aislada detrás de doble flag, gateada al subárbol Playtelecom/serialized inventory.

---

## 1. Contexto y problema

**Playtelecom** opera con la feature de **inventario serializado** (alta y custodia de SIMs). Tiene una posición llamada **"cubre-descanso"** — un promotor sin tienda fija que cubre dinámicamente las tiendas cuando otro promotor toma su día de descanso. Hoy hay 3 cubre-descansos; pueden crecer si crecen las tiendas.

**Requerimiento (de Asana):** que cada vez que un cubre-descanso vaya a una tienda, la actividad (clock-in/clock-out, ventas, alta de SIMs) quede atribuida correctamente a esa tienda. Trazabilidad por persona Y por tienda.

**Restricciones operativas confirmadas:**
- El cubre-descanso lleva su propia TPV (PAX A910S) entre tiendas.
- La asignación de a qué tienda va es **manual** (no hay calendario sistematizado).
- El cubre-descanso es funcionalmente igual a un promotor base — no debe tener un proceso distinto, solo una pequeña diferencia al hacer clock-in.
- No hay solapamiento de cubre-descansos en una misma tienda.
- Playtelecom usa el modelo **`TimeEntry` (clock-in con foto)**, no `Shift` (corte de caja restaurante).
- **`TimeEntry` actualmente es exclusivo de Playtelecom** — ningún otro tenant lo usa en producción.

---

## 2. Decisiones de arquitectura (definitivas tras auditoría)

Estas son las decisiones tomadas durante el planning + las correcciones del auditor externo. Cada una tiene justificación y alternativa descartada.

### 2.1. **`Terminal.venueId` no se muta. `TimeEntry.venueId` ES la sesión operativa.**

**Decisión:** el campo `Terminal.venueId` queda **estable** representando el "home venue" del terminal en el POS core. Cuando el cubre-descanso hace clock-in en otra tienda, se crea un `TimeEntry` con `venueId` = venue operativo. Toda lectura del "venue actual" para flujos Playtelecom resuelve por una función:

```ts
// src/utils/playtelecom-context.util.ts (NUEVO)
export async function getEffectivePlaytelecomVenueId(
  staffId: string,
  terminalId: string
): Promise<string> {
  const activeEntry = await prisma.timeEntry.findFirst({
    where: { staffId, status: { in: ['CLOCKED_IN', 'ON_BREAK'] } },
    orderBy: { clockInTime: 'desc' },
    select: { venueId: true },
  });
  if (activeEntry) return activeEntry.venueId;
  // Fallback: terminal home venue
  const terminal = await prisma.terminal.findUnique({
    where: { id: terminalId },
    select: { venueId: true },
  });
  return terminal!.venueId;
}
```

**Justificación (corrección del audit):** mutar `Terminal.venueId` mete cambios en una entidad central del POS core con efectos laterales potenciales en pagos, sockets, config, heartbeat, merchants y reportes. El auditor recomendó usar una sesión operativa separada. Como `TimeEntry` es exclusivo de Playtelecom y ya tiene `venueId`, **`TimeEntry` cumple el rol de sesión operativa sin agregar un modelo nuevo**.

**Alternativas descartadas:**
- Mutar `Terminal.venueId` (plan v1 original) — riesgoso para el POS core.
- Crear `PlaytelecomOperatingSession` modelo nuevo (recomendación del auditor) — innecesario dado que `TimeEntry` ya tiene la información.

### 2.2. Identificación implícita: ningún rol/permiso/campo nuevo

**Decisión:** "ser cubre-descanso" se infiere de tener `> 1` fila activa en `StaffVenue`. Si tiene una sola, va al flujo normal (auto-selección). Si tiene varias, se le pregunta en cuál está al hacer clock-in.

La M:N `StaffVenue` ya existe (`avoqado-server/prisma/schema.prisma:631`). Cero migration, cero rol/permiso/campo nuevo en `Staff`.

### 2.3. Doble feature flag — aislamiento total

**Decisión:** la feature está gateada por **dos** condiciones simultáneas:

```ts
if (venue.modules.has('SERIALIZED_INVENTORY') && flags.PLAYTELECOM_FLOATER_ENABLED) {
  // flujo cubre-descanso
} else {
  // flujo POS estándar — bit-a-bit idéntico a hoy
}
```

- El módulo `SERIALIZED_INVENTORY` ya existe (`Module.code = 'SERIALIZED_INVENTORY'` en schema).
- `PLAYTELECOM_FLOATER_ENABLED` es un sub-flag nuevo. **Decisión pendiente:** dónde vive (campo en `Venue.flags Json?`, o sistema de feature flags si ya existe). Granularidad por venue.

**Por qué doble flag:** ningún otro tenant ni venue de Playtelecom sin cubre-descansos toca el código nuevo. Apagable en frío en segundos.

### 2.4. Cero modelos nuevos. Cero migration sobre tablas existentes.

**Decisión:** la feature se construye solo con:
- Función nueva `getEffectivePlaytelecomVenueId()` (utility).
- Endpoint nuevo `GET /tpv/staff/me/venues` (consulta).
- Modificaciones aditivas al endpoint de clock-in (no rompe shape de response).
- Endpoints nuevos de recuperación (force-close, ver sección 5).
- Sub-flag (campo `Venue.flags` o similar — única migration menor, aditiva).

### 2.5. Re-bootstrap del TPV disparado por TimeEntry, no por mutación

**Decisión:** cuando el response del clock-in indica que el venueId del nuevo TimeEntry difiere del que tiene cargado el TPV en memoria, el orchestrator ejecuta:
1. Cancelar coroutines venue-scoped en vuelo.
2. Desconectar socket del room viejo.
3. Persistir tokens nuevos (que vienen en el response).
4. `SecureStorage.clearVenueData()` — **whitelist**, preserva solo claves de auth + preferencias usuario.
5. Cargar nuevo terminal config (también viene en el response, evita round-trip al endpoint público).
6. `MultiMerchantSDKManager.switchToVenueMerchants(newMerchants)`.
7. Reconectar socket al nuevo room.
8. Refresh módulos/permisos.

Si **cualquier paso falla** → forzar logout completo. Estado limpio garantizado.

### 2.6. PIN policy: mismo PIN en todos los `StaffVenue` del cubre-descanso

**Decisión operativa (no técnica):** los 3 cubre-descansos se configuran con el **mismo PIN en todas sus filas de `StaffVenue`**. Esto evita re-prompt de PIN tras seleccionar venue. Convención de configuración en dashboard, no enforcement en código.

**Por qué no enforce en código:** agregar validación cross-venue del PIN duplica complejidad para un caso operativo manejable. Se documenta como precondición de configuración.

---

## 3. Implementación por olas

Para minimizar riesgo, la implementación se divide en dos olas. **Ola 1** ships la feature; **Ola 2** endurece después de validar v1 en producción.

### Ola 1 (v1) — Cubre-descanso funcional, gateado, sin tocar global

| Cambio | Scope |
|---|---|
| Endpoint clock-in extendido (con flag) | Solo path de switch |
| Endpoint nuevo `/me/venues` | Solo staff con flag |
| `getEffectivePlaytelecomVenueId()` helper | Solo en endpoints Playtelecom |
| Selector en TPV | Solo si `myVenues > 1` |
| Re-bootstrap en TPV | Solo si `venueChanged` en response |
| JWT re-emit | Solo en clock-in con switch |
| Endpoints recuperación (force-close) | Solo invocados por TPV en errores conocidos |

### Ola 2 (v1.5) — Hardening, después de v1 estable en producción

| Cambio | Por qué se difiere |
|---|---|
| Auditoría de duplicados en `TimeEntry` | Necesaria antes de agregar índice único |
| Índice único parcial sobre `TimeEntry.staffId` cuando status activo | Migration falla si hay duplicados; requiere data limpia |
| Validación global de TimeEntry (cambiar línea 177 a filtrar solo por staffId) | Cambio de comportamiento global; hacer solo cuando v1 demostró que la validación adicional gateada cubre lo necesario |
| Endurecer endpoint público `/terminals/:serial/config` | Out of scope original; aprovechar el momento |
| Enforce `staffId === authContext.userId` (hoy: warn mode) | Recolectar logs de mismatches durante v1, ajustar sin sorpresas |

---

## 4. Fase 1 — Backend (`avoqado-server`)

**Tiempo estimado:** 2-3 días. Deploy independiente. Retrocompatible.

### 4.1. Helper `getEffectivePlaytelecomVenueId()`

**Archivo nuevo:** `src/utils/playtelecom-context.util.ts`

Único punto de verdad para "qué venue está operando este staff/terminal en este momento". Llamado desde todos los endpoints Playtelecom que hoy leen `req.authContext.venueId` o `Terminal.venueId` directamente. (Ver sección 4.6 para inventario de touchpoints.)

### 4.2. Modificar el service de clock-in

**Archivo:** `src/services/tpv/time-entry.tpv.service.ts`
**Función actual:** `clockIn(params: ClockInParams)` — línea 167

**Cambios (todos gateados por doble flag):**

1. **Validación adicional gateada al path de switch:**
   ```ts
   const isVenueSwitch = await detectVenueSwitch(staffId, terminalId, params.venueId);
   if (isVenueSwitch && featureFlags.playtelecomFloaterEnabled(venueId)) {
     // Validación global solo aquí — fuera del path de switch, validación per-venue de hoy intacta
     const otherActiveEntry = await prisma.timeEntry.findFirst({
       where: { staffId, status: { in: ['CLOCKED_IN', 'ON_BREAK'] } }
     });
     if (otherActiveEntry && otherActiveEntry.venueId !== params.venueId) {
       throw new BadRequestErrorWithRecovery('TURN_ACTIVE_ELSEWHERE', {
         activeEntryId: otherActiveEntry.id,
         activeVenueId: otherActiveEntry.venueId,
         recoveryEndpoint: `/tpv/time-entries/${otherActiveEntry.id}/force-close`,
       });
     }
   }
   ```

2. **Validar `StaffVenue` antes de proceder** usando el utility existente (`src/utils/staff-venue.util.ts:12`):
   ```ts
   await validateStaffVenue(params.staffId, params.venueId);
   // 403 si no existe — UI muestra "No tienes acceso a esta tienda"
   ```

3. **NO mutar `Terminal.venueId`.** El terminal queda como está. El TimeEntry registra el venue operativo.

4. **Si hubo switch, emitir tokens nuevos con venueId destino:**
   ```ts
   if (isVenueSwitch) {
     const newAccessToken = generateAccessToken({
       sub: staffId, venueId: params.venueId, /* otros claims */
     });
     const newRefreshToken = generateRefreshToken({
       sub: staffId, venueId: params.venueId,
     });
     response.tokens = { accessToken: newAccessToken, refreshToken: newRefreshToken };
   }
   ```

5. **Si hubo switch, devolver terminal config del venue destino en el response** para evitar que el TPV re-llame al endpoint público:
   ```ts
   if (isVenueSwitch) {
     response.terminalConfig = await buildTerminalConfigForVenue(terminalId, params.venueId);
     response.venueChanged = true;
   }
   ```

6. **Audit log si hubo switch:**
   ```ts
   await logAction({
     venueId: params.venueId,
     action: 'TIME_ENTRY_VENUE_SWITCH',
     entity: 'TimeEntry',
     entityId: timeEntry.id,
     data: {
       fromVenueId: previousVenueId,
       toVenueId: params.venueId,
       staffId, terminalId, triggeredBy: 'CLOCK_IN',
     }
   });
   ```
   Servicio existente: `src/services/dashboard/activity-log.service.ts:33`.

7. **`req.authContext.userId === req.body.staffId` — modo WARN en v1:**
   ```ts
   if (req.authContext?.userId !== params.staffId) {
     logger.warn('clock-in staffId mismatch', {
       authUserId: req.authContext?.userId,
       bodyStaffId: params.staffId,
     });
     // No reject en v1. Recolectar 1 semana de logs antes de enforcement.
   }
   ```

### 4.3. Endpoint nuevo `GET /tpv/staff/me/venues`

**Solo accesible si flag activo.** Devuelve las venues activas del staff autenticado:
```ts
{
  venues: [
    { id, name, role, isCurrent: boolean }  // isCurrent = matches Terminal.venueId
  ]
}
```

Si el flag está apagado o el staff tiene un solo venue, el TPV podría seguir usando el endpoint sin problemas (devuelve un solo elemento), pero idealmente el TPV ni lo llama si el flag está apagado para esta venue.

### 4.4. Endpoints de recuperación (lock-bug mitigation)

**Tres endpoints nuevos para los escenarios de lock identificados (sección 7):**

#### 4.4.1. `POST /tpv/time-entries/:id/force-close` (TPV — autoservicio del cubre-descanso)

Permite cerrar un TimeEntry desde un terminal que NO está en el venue del TimeEntry. Requisitos:
- Auth válida del staff dueño del TimeEntry.
- `staff.id === timeEntry.staffId`.
- Razón obligatoria: `auto_close_for_venue_switch`.

Cierra el TimeEntry con `clockOutTime = now`, `autoClockOut = true`, `autoClockOutNote = "Cierre automático por cambio a otra tienda"`.

#### 4.4.2. `POST /admin/time-entries/:id/force-close` (Dashboard — emergencia)

Para SUPERADMIN únicamente. Salida de emergencia si todo lo demás falla (cubre-descanso atorado y TPV no puede recuperar).

#### 4.4.3. Hook en desactivación de `StaffVenue`

**Archivo:** servicio de `StaffVenue` en backend (ubicación depende del repo, probablemente `src/services/dashboard/staff.service.ts`).

Cuando un admin desactiva una `StaffVenue`, antes de aplicar el cambio:
```ts
const activeEntry = await prisma.timeEntry.findFirst({
  where: { staffId, venueId, status: { in: ['CLOCKED_IN', 'ON_BREAK'] } }
});
if (activeEntry) {
  await prisma.timeEntry.update({
    where: { id: activeEntry.id },
    data: {
      clockOutTime: new Date(),
      status: 'CLOCKED_OUT',
      autoClockOut: true,
      autoClockOutNote: 'StaffVenue desactivada por admin',
    }
  });
}
```

### 4.5. Refresh token: usar venueId del TimeEntry activo si difiere del JWT

**Archivo:** `src/services/tpv/auth.tpv.service.ts:235`

Hoy el refresh re-emite con el `venueId` del JWT viejo. Para resistencia ante crashes mid-bootstrap (escenario 2 de lock bugs):

```ts
// Después de extraer venueId del decoded JWT
let effectiveVenueId = decoded.venueId;
if (featureFlags.playtelecomFloaterEnabled(decoded.venueId)) {
  const activeEntry = await prisma.timeEntry.findFirst({
    where: { staffId: decoded.sub, status: { in: ['CLOCKED_IN', 'ON_BREAK'] } },
  });
  if (activeEntry && activeEntry.venueId !== decoded.venueId) {
    effectiveVenueId = activeEntry.venueId;  // self-healing
  }
}
// Validar StaffVenue contra effectiveVenueId, emitir token con effectiveVenueId
```

Solo se ejecuta si flag activo. Para refresh tokens de tenants no-Playtelecom, código intacto.

### 4.6. Inventario de touchpoints en `src/services/playtelecom/` (o equivalente)

Cada endpoint Playtelecom que hoy lee `req.authContext.venueId` o `Terminal.venueId` debe migrarse a `getEffectivePlaytelecomVenueId()`. Lista a auditar durante implementación:

- Alta de SerializedItem (SIM upload).
- Venta de SerializedItem (cuando se asocia a OrderItem).
- Custodia / chain-of-custody events.
- Reportes Playtelecom (si los hay en backend).
- Cualquier endpoint que cree `Order` con `venueId` desde TPV Playtelecom.

**Mitigación de drift futuro:** lint rule sobre `req.authContext.venueId` en archivos bajo `src/services/playtelecom/` o `src/services/tpv/serialized-inventory/` que warne si no pasa por el helper.

### 4.7. Tests

**Crear:** `tests/unit/services/tpv/time-entry.tpv.service.test.ts` con casos:
- Clock-in al mismo venue del JWT → no es switch, no re-emite tokens, comportamiento idéntico a hoy.
- Clock-in a venue distinto con flag ON → es switch, valida StaffVenue, valida no-otro-TimeEntry-activo, emite tokens, devuelve terminalConfig.
- Clock-in a venue distinto con flag OFF → 403 (no debería poder llegar al endpoint con venueId distinto si el flag está OFF).
- Clock-in con `staffId` body distinto a authContext → log de warning, NO reject (v1).
- Clock-in con TimeEntry activo en otro venue → response estructurado con `recoveryEndpoint`.
- Force-close endpoint: cierra TimeEntry con autoClockOut=true.
- Refresh token con TimeEntry activo en venue distinto → re-emite con venue del TimeEntry.

**Crítico:** test específico verificando que con flag OFF el endpoint clock-in se comporta byte-a-byte igual que hoy (mismo response shape, mismas validaciones).

### 4.8. Retrocompatibilidad

| Aspecto | Comportamiento para tenants no-Playtelecom / single-venue |
|---|---|
| URL del endpoint | Idéntica (`POST /api/v1/tpv/venues/:venueId/time-entries/clock-in`). |
| Body request | Idéntico. No se agregan campos. |
| Response | Campos extra (`terminalConfig`, `venueChanged`, `tokens`) son opcionales. TPVs viejas y tenants sin flag los ignoran. |
| Validación global de TimeEntry | NO se aplica fuera del path de switch. Validación per-venue (línea 177) intacta. |
| `Terminal.venueId` | NUNCA se muta. POS core invariante preservada. |
| Refresh token | Solo añade self-healing si flag activo. Sin flag, idéntico a hoy. |

---

## 5. Fase 2 — TPV (`avoqado-tpv`)

**Tiempo estimado:** 3-4 días + ciclo de firmado PAX (3-5 días).

### 5.1. Modelo de domain

**Archivo nuevo:** `app/src/main/java/com/jaac/avoqado_tpv/features/auth/domain/model/StaffVenueAccess.kt`

```kotlin
data class StaffVenueAccess(
    val venueId: String,
    val venueName: String,
    val role: String,
    val isCurrent: Boolean
)
```

### 5.2. Repository: `getMyVenues()`

Extender `AuthRepository` con método que consume `GET /tpv/staff/me/venues`. Cache en memoria (no SecureStorage). Invalidar al logout.

### 5.3. Selector en flujo de clock-in

**Archivo:** `features/timeclock/presentation/TimeclockViewModel.kt:227` (función `clockIn()`).

Pseudocódigo:
```kotlin
fun clockIn() = viewModelScope.launch {
    if (!flagRepository.isFloaterEnabled()) {
        proceedWithClockIn(currentVenueId)  // flujo idéntico al actual
        return@launch
    }

    val readiness = readinessChecker.check()
    if (!readiness.isReady) {
        _uiState.update { it.copy(error = readiness.reason) }
        return@launch
    }

    val venues = authRepository.getMyVenues().getOrNull() ?: emptyList()
    if (venues.size <= 1) {
        proceedWithClockIn(venues.firstOrNull()?.venueId ?: currentVenueId)
    } else {
        _uiState.update { it.copy(showVenueSelector = true, availableVenues = venues) }
    }
}

fun onVenueSelected(venueId: String) {
    _uiState.update { it.copy(showVenueSelector = false) }
    proceedWithClockIn(venueId)
}
```

**Pantalla nueva:** `VenueSelectorScreen` composable. Lista de tarjetas con cada venue. Pre-selección del `isCurrent`. Solo se renderiza si `uiState.showVenueSelector == true`.

**Manejo del error de TimeEntry activo en otro venue:**
```kotlin
catch (e: TurnActiveElsewhereException) {
    _uiState.update { it.copy(
        showRecoveryDialog = true,
        recoveryAction = "Cerrar turno anterior en ${e.activeVenueName} y continuar",
        onRecoveryConfirm = { forceCloseAndRetry(e.activeEntryId, venueId) }
    )}
}
```

### 5.4. `VenueSwitchOrchestrator`

**Archivo nuevo:** `features/auth/data/VenueSwitchOrchestrator.kt`

Patrón a reusar: `MultiMerchantSDKManager.switchMerchant()` línea 128 (Mutex + try/catch + rollback).

```kotlin
@Singleton
class VenueSwitchOrchestrator @Inject constructor(
    private val secureStorage: SecureStorage,
    private val multiMerchantSDKManager: MultiMerchantSDKManager,
    private val socketManager: SocketManager,
    private val permissionsRepository: PermissionsRepository,
    private val modulesRepository: ModulesRepository,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val crashlytics: CrashlyticsLogger,
    private val authRepository: AuthRepository,
) {
    private val mutex = Mutex()

    suspend fun switchToVenue(
        newVenueId: String,
        terminalConfig: TerminalConfigDto,
        newAccessToken: String,
        newRefreshToken: String,
    ): Result<Unit> = mutex.withLock {
        try {
            cancelVenueScopedJobs()
            socketManager.disconnect()

            // Persistir tokens nuevos ANTES de borrar storage (para no perderlos)
            secureStorage.saveSessionToken(newAccessToken)
            secureStorage.saveRefreshToken(newRefreshToken)

            secureStorage.clearVenueData()  // whitelist (sección 5.5)
            secureStorage.persistTerminalConfig(terminalConfig)

            multiMerchantSDKManager.switchToVenueMerchants(terminalConfig.merchantAccounts)
            socketManager.connect(token = newAccessToken, terminalId = terminalConfig.terminal.id)

            permissionsRepository.refresh()
            modulesRepository.refresh()
            tpvSettingsRepository.refresh()

            crashlytics.log("VenueSwitch success: $newVenueId")
            Result.success(Unit)
        } catch (e: Exception) {
            crashlytics.recordException(e, "VenueSwitch failed at venue=$newVenueId")
            forceLogoutAndReturnToLogin()
            Result.failure(e)
        }
    }
}
```

### 5.5. `SecureStorage.clearVenueData()` — WHITELIST (no blacklist)

**Archivo:** `core/data/local/SecureStorage.kt`

Implementación con whitelist de claves a **preservar**:
```kotlin
fun clearVenueData() {
    val keysToPreserve = setOf(
        KEY_SESSION_TOKEN,      // línea 58
        KEY_REFRESH_TOKEN,      // línea 59
        KEY_IS_DARK_MODE,       // línea 91
        KEY_SELECTED_LANGUAGE,  // línea 90
    )

    val allKeys = encryptedPrefs.all.keys.toList()
    allKeys.forEach { key ->
        if (key !in keysToPreserve) {
            encryptedPrefs.edit { remove(key) }
        }
    }

    VenueTimeZone.invalidateCache()
}
```

**Por qué whitelist:** resistente a futuras claves nuevas. Si alguien agrega `KEY_VENUE_NUEVA_COSA`, queda automáticamente cubierto. Una blacklist se queda corta silenciosamente.

### 5.6. `VenueSwitchReadinessChecker`

**Archivo nuevo:** `features/auth/data/VenueSwitchReadinessChecker.kt`

Consulta todas las fuentes de pendientes antes de permitir clock-in con cambio de venue:
```kotlin
class VenueSwitchReadinessChecker @Inject constructor(
    private val paymentSyncQueue: PaymentSyncQueue,
    private val orderSyncQueue: OrderSyncQueue,
    private val serializedItemSyncQueue: SerializedItemSyncQueue,
    private val custodyEventSyncQueue: CustodyEventSyncQueue,
    private val verificationQueue: VerificationQueue,
    private val activePaymentTracker: ActivePaymentTracker,
) {
    suspend fun check(): ReadinessResult {
        if (activePaymentTracker.hasInFlight()) return ReadinessResult.NotReady("Hay un cobro en proceso.")
        if (paymentSyncQueue.pendingCount() > 0) return ReadinessResult.NotReady("Hay pagos sin sincronizar.")
        if (orderSyncQueue.pendingCount() > 0) return ReadinessResult.NotReady("Hay órdenes sin sincronizar.")
        if (serializedItemSyncQueue.pendingCount() > 0) return ReadinessResult.NotReady("Hay SIMs sin sincronizar.")
        if (custodyEventSyncQueue.pendingCount() > 0) return ReadinessResult.NotReady("Hay eventos de custodia sin sincronizar.")
        if (verificationQueue.pendingCount() > 0) return ReadinessResult.NotReady("Hay verificaciones pendientes.")
        return ReadinessResult.Ready
    }
}
```

Nombres de las colas son tentativos — durante implementación, hacer un grep por `*SyncQueue` y `*Queue` en el repo y mapear las que apliquen.

### 5.7. Self-healing al arrancar la app

**Archivo:** orquestador de bootstrap del TPV (probablemente `MainActivity.kt` o `AvoqadoApp.kt` `onCreate`).

Al iniciar, después de cargar tokens de SecureStorage:
```kotlin
// Si hay flag activo + TimeEntry activo, validar que el JWT venueId calza
if (flagRepository.isFloaterEnabled() && authRepository.getSessionToken() != null) {
    val activeEntry = timeEntryRepository.getActiveEntry()
    if (activeEntry != null && activeEntry.venueId != jwtPayload.venueId) {
        // Mismatch detectado: forzar refresh del JWT
        authRepository.refreshToken()  // backend devuelve token con venueId del TimeEntry (sección 4.5)
        // Re-bootstrap usando venueId del TimeEntry
    }
}
```

Esto cubre el escenario 2 de lock bugs (crash mid-bootstrap).

### 5.8. `MultiMerchantSDKManager.switchToVenueMerchants()` — método nuevo

**Archivo:** `features/payment/data/MultiMerchantSDKManager.kt`

Nuevo método aditivo. Métodos existentes (`switchMerchant`, `getCurrentMerchant`, `resetToDefault`) **intactos**:

```kotlin
suspend fun switchToVenueMerchants(newMerchants: List<MerchantAccount>): Result<Unit> = mutex.withLock {
    teardownCurrentSdk()
    clearMerchantCache()
    val defaultMerchant = newMerchants.firstOrNull()
        ?: return Result.failure(IllegalStateException("Venue sin merchants"))
    initializeWith(defaultMerchant)
}
```

### 5.9. Banner "Cubriendo: X"

Reusar patrón de `VenueStatusBanner.kt:52-95`. Componente nuevo `CurrentVenueBanner` o extensión. Visible solo si:
- Flag activo
- `myVenues.size > 1`
- TimeEntry activo
- `timeEntry.venueId !== Terminal.venueId` (el cubre-descanso está cubriendo, no en su home)

### 5.10. Tests

- `TimeclockViewModelTest` — extender con casos de selector y readiness.
- `VenueSwitchOrchestratorTest` — sequencing + rollback.
- `VenueSwitchReadinessCheckerTest` — cada cola, cada combinación.
- Extender `MultiMerchantSDKManagerTest.kt` — `switchToVenueMerchants`.
- **E2E test crítico:** clock-in flow para Staff con 1 sola venue → debe ser indistinguible del flujo actual (no toca código nuevo).

---

## 6. Fase 3 — Dashboard

**Cero código.** Solo data:
1. Crear el `Staff` del cubre-descanso vía la UI existente.
2. Asignarle filas en `StaffVenue` para cada tienda que cubre, **con el mismo PIN** en todas (precondición de la sección 2.6).
3. Activar el flag `PLAYTELECOM_FLOATER_ENABLED` en los venues correspondientes.

---

## 7. Lock bugs identificados y mitigaciones

Tres escenarios realistas donde un cubre-descanso podría quedar atorado, todos con recovery path.

### Escenario 1: Olvidó clock-out en Tienda A, ya está físicamente en Tienda B

**Síntoma:** intenta clock-in en Tienda B, backend rechaza con "tienes turno activo en otra tienda".

**Recovery:** el response del rechazo incluye `recoveryEndpoint`. El TPV muestra un diálogo: *"Tienes un turno activo en Tienda A desde [fecha]. ¿Cerrar turno anterior y continuar?"*. Si confirma, el TPV llama a `POST /tpv/time-entries/:id/force-close` (sección 4.4.1) → backend cierra el TimeEntry de Tienda A con `autoClockOut=true` → procede el clock-in en Tienda B.

**Trade-off aceptado:** el TimeEntry de A queda con horas inflables (incluyendo la noche). El supervisor puede ajustarlo desde dashboard. El `autoClockOutNote` lo deja auditable.

### Escenario 2: TPV crashea/red falla a media re-bootstrap

**Síntoma:** backend persistió cambios pero el TPV no alcanzó a guardar tokens nuevos. Al reabrir, JWT viejo apunta a Tienda A pero TimeEntry está en Tienda B.

**Recovery:** self-healing al arrancar la app (sección 5.7). El TPV detecta el mismatch, fuerza refresh del JWT (que con el cambio en `auth.tpv.service.ts` de la sección 4.5 devuelve el venueId del TimeEntry), y re-bootstrap usando el venueId correcto. Sin intervención del usuario.

### Escenario 3: Admin desactivó `StaffVenue` mientras había TimeEntry activo

**Síntoma:** cubre-descanso queda con TimeEntry abierto en una venue a la que ya no tiene acceso. No puede clock-out (falla PIN check) ni clock-in en otro venue.

**Recovery automático:** hook en el endpoint de desactivación de StaffVenue (sección 4.4.3) cierra el TimeEntry abierto antes de aplicar el cambio.

**Recovery manual (red de seguridad):** endpoint admin `POST /admin/time-entries/:id/force-close` (sección 4.4.2) accesible para SUPERADMIN desde dashboard.

### Escenarios cubiertos por sistema actual (no requieren código nuevo)

- TimeEntry crónico abierto: el job `auto-clockout` ya existe (`tests/unit/jobs/auto-clockout.job.test.ts`). Validar durante implementación que cubre cubre-descansos.
- Doble clock-in concurrente: transacción Prisma + validación gateada lo previenen. Partial unique index en v1.5 como defensa en profundidad.
- Network failure durante clock-in: transacción atómica. O todo o nada. TPV reintenta o refresca estado.

---

## 8. Verificación explícita: cero impacto a usuarios no cubre-descanso

Esta sección existe para que el auditor pueda validar punto por punto que la feature está aislada.

### Backend

| Cambio | ¿Afecta a usuario normal? | Por qué |
|---|---|---|
| `getEffectivePlaytelecomVenueId()` helper | ❌ No | Solo se llama desde código bajo flag. Tenants sin `SERIALIZED_INVENTORY` ni se acercan. |
| Validación global de TimeEntry | ❌ No | Solo se ejecuta cuando `isVenueSwitch && flag`. Para single-venue, `isVenueSwitch=false`. La validación per-venue (línea 177) sigue intacta. |
| Re-emisión de JWT | ❌ No | Solo si hay switch + flag. Para single-venue nunca pasa. |
| Refresh token self-healing | ❌ No | Solo activo con flag. Sin flag, comportamiento intacto. |
| Endpoint `/me/venues` | ❌ No | Endpoint nuevo. Si nadie lo llama, está muerto. |
| Endpoints force-close | ❌ No | Endpoints nuevos, solo invocados por el TPV en errores conocidos. |
| Hook desactivación StaffVenue | ⚠️ Universal pero benigno | Aplica a todos los tenants. Solo cierra TimeEntry abierto al desactivar StaffVenue — comportamiento que cualquier sistema sano debería tener (hoy no lo hace, lo cual es un bug latente para todos). |
| `Terminal.venueId` | ❌ No | NUNCA se muta. Invariante del POS core preservada. |
| `Terminal.assignedMerchantIds` | ❌ No | NUNCA se modifica por esta feature. |

### TPV

| Cambio | ¿Afecta a usuario normal? | Por qué |
|---|---|---|
| Selector de venue | ❌ No | Solo se muestra si `myVenues.size > 1`. Promotor base tiene una sola fila → autoselección silenciosa. UI bit-a-bit idéntica. |
| `VenueSwitchOrchestrator` | ❌ No | Solo se invoca si response tiene `venueChanged: true`. Para single-venue nunca lo dice. |
| `clearVenueData()` | ❌ No | Método nuevo, solo invocado por orchestrator. |
| `VenueSwitchReadinessChecker` | ❌ No | Solo invocado en clock-in con multi-venue. |
| `switchToVenueMerchants()` | ❌ No | Método nuevo en clase existente. Métodos viejos intactos. |
| Banner "Cubriendo: X" | ❌ No | Solo si `myVenues.size > 1` Y TimeEntry activo Y venue ≠ home. |
| Self-healing al arrancar | ❌ No | Solo se ejecuta si flag activo. |

### Garantía con tests

Test específico verificando que con flag OFF el endpoint clock-in se comporta byte-a-byte igual que hoy (mismo response shape, mismas validaciones, misma latencia ±10%). Test E2E del TPV con un Staff de un solo venue confirmando flujo de clock-in indistinguible del actual.

---

## 9. Secuencia detallada del re-bootstrap

Cuando el TPV recibe respuesta del clock-in con `venueChanged: true`:

```
T+0ms     [clock-in success — backend ya creó TimeEntry y emitió tokens]
T+10ms    VenueSwitchOrchestrator.switchToVenue() invocado
T+10ms    Mutex.lock()
T+20ms    Cancela coroutines venue-scoped (módulos, permisos, sync, etc.)
T+30ms    socketManager.disconnect() — leave del room viejo
T+50ms    secureStorage.saveSessionToken(new) + saveRefreshToken(new)
T+80ms    secureStorage.clearVenueData() — whitelist preserve
T+150ms   secureStorage.persistTerminalConfig(newConfig)
T+200ms   multiMerchantSDKManager.switchToVenueMerchants(newMerchants)
T+200ms     └─ teardownCurrentSdk()
T+500ms     └─ initializeWith(defaultMerchant) ← Blumon SDK reinit (~3-5s en PAX)
T+5500ms  socketManager.connect(token, terminalId) — join nuevo room
T+5800ms  permissionsRepository.refresh() / modulesRepository.refresh()
T+6500ms  Mutex.unlock()
T+6500ms  UI: dismiss loader, mostrar "Cubriendo: <nuevoVenue>" banner
```

**Tiempo total estimado:** 6-7 segundos en PAX A910S. Aceptable para evento que ocurre 1 vez/día.

**Si cualquier paso falla:** `catch` → Crashlytics log → `forceLogoutAndReturnToLogin()`. Estado limpio garantizado.

---

## 10. Riesgos y mitigaciones (versión final)

| # | Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|---|
| 1 | Re-bootstrap deja SDK Blumon medio inicializado | Media | Alto | Mutex + try/catch que cae a logout. Crashlytics tagging del step. |
| 2 | Cubre-descanso clock-in offline | Baja | Medio | Bloquear si no hay conectividad. Mensaje explícito. |
| 3 | Cubre-descanso elige tienda equivocada | Media | Bajo | Banner permanente. Audit log permite rastreo y reasignación manual. |
| 4 | Validación global rompe TPVs viejas | Baja | Alto | La validación global solo aplica con flag ON. Sin flag, código intacto. Test específico. |
| 5 | Ventas pendientes offline atribuidas mal tras switch | Media | Alto | `VenueSwitchReadinessChecker` bloquea si hay pendientes. |
| 6 | Concurrencia: dos clock-ins simultáneos del mismo Staff | Baja | Medio | Validación gateada + transacción atómica. v1.5 agrega partial unique index. |
| 7 | Socket reconnect falla después de bootstrap exitoso | Media | Medio | Si falla, cae al catch general → logout. Sin estado parcial. |
| 8 | Re-bootstrap toma >10s | Baja | Bajo | Loading screen con mensaje "Configurando para Tienda X...". |
| 9 | Manager con multi-venue ve el selector | Alta | Bajo | Aceptado. Comportamiento correcto. |
| 10 | TPV cacheada con permisos del venue viejo | Media | Medio | Refresh dentro del orchestrator. Regla del repo: `collectAsStateWithLifecycle()` sobre StateFlow. |
| 11 | Lock bug: forgot clock-out + cambió de tienda | Media | Alto | Force-close endpoint + UX de recovery (escenario 1). |
| 12 | Lock bug: crash mid-bootstrap | Baja | Alto | Self-healing al arrancar la app (escenario 2). |
| 13 | Lock bug: admin desactiva StaffVenue con TimeEntry abierto | Baja | Alto | Hook automático + admin force-close (escenario 3). |
| 14 | PIN distinto entre StaffVenues bloquea clock-in | Baja | Medio | Precondición operativa: mismo PIN en todas las StaffVenues del cubre-descanso. Documentado. |
| 15 | Algún endpoint Playtelecom olvida usar el helper | Media | Medio | Lint rule en CI sobre `req.authContext.venueId` en archivos Playtelecom. |
| 16 | Public endpoint `/terminals/:serial/config` queda explotable | Baja | Medio | NO se usa en el flujo nuevo (config viene en clock-in response). Hardening del endpoint es out-of-scope (Ola 2). |

---

## 11. Fuera de alcance v1 (consciente)

- ❌ Mutación de `Terminal.venueId` (cambio arquitectural — usamos `TimeEntry.venueId` como contexto operativo).
- ❌ Modelo nuevo `PlaytelecomOperatingSession` (innecesario — `TimeEntry` cumple el rol).
- ❌ Geofencing de validación.
- ❌ Cambio de venue mid-shift sin clock-out.
- ❌ Auto-asignación desde dashboard via socket.
- ❌ UI especial en dashboard para cubre-descanso.
- ❌ Reporte específico (los existentes ya filtran por venueId/staffId).
- ❌ Migration de schema sobre tablas existentes.
- ❌ Nuevo rol o permiso.
- ❌ Cambios en `PaymentViewModel`, `PaymentScreen`, AngelPay, BLE, kiosk, recibos.
- ❌ Endurecer el endpoint público `/terminals/:serial/config` (Ola 2).
- ❌ Partial unique index en `TimeEntry` (Ola 2, después de auditoría de duplicados).
- ❌ Cambio global de validación de TimeEntry de per-venue a global (Ola 2).
- ❌ Enforcement de `staffId === authContext.userId` (v1: warn mode; Ola 2: enforce).

---

## 12. Orden de deploy y validación

1. **Backend a sandbox.** Una semana con los 3 cubre-descansos en modo prueba.
2. **Métricas a validar en sandbox:**
   - 0 errores 5xx en clock-in con switch.
   - 0 inconsistencias entre `TimeEntry.venueId` activo y JWT venueId del cubre-descanso.
   - 0 logs de warning de `staffId mismatch` (verificar que `authContext.userId === body.staffId` siempre).
   - Lock-bug recovery flows funcionan (probar deliberadamente los 3 escenarios).
3. **Backend a producción.** Estable 24-48h. Flag OFF en producción todavía.
4. **Auditoría de data:** correr `SELECT staffId, COUNT(*) FROM TimeEntry WHERE status IN ('CLOCKED_IN','ON_BREAK') GROUP BY staffId HAVING COUNT(*) > 1` en producción. Resolver duplicados manualmente. Esto NO bloquea v1 — es prep para v1.5.
5. **APK al ciclo de firmado de Blumon.** ~3-5 días.
6. **APK en terminal del primer cubre-descanso** vía dashboard (`INSTALL_VERSION`). Flag ON solo para ese venue.
7. **Una semana de prueba real** — un cubre-descanso cambiando entre 2-3 tiendas todos los días. Monitoreo Crashlytics + audit log + métricas backend.
8. **Si OK → flag ON para los otros 2 cubre-descansos.**
9. **Después de 2 semanas estables → planning de Ola 2 (hardening).**

Backend retrocompatible + flag OFF por default → todos los demás tenants y promotores siguen funcionando bit-a-bit idéntico durante todo el rollout.

---

## 13. Plan de rollback

Si en producción algo se rompe:

| Severidad | Acción | Tiempo |
|---|---|---|
| Bug menor | Apagar flag para venue afectado | Segundos (toggle en dashboard o config) |
| Bug grave | Apagar flag globalmente | Segundos |
| Data corrupta | Script de reconciliación basado en audit log | Minutos |
| TPV broken | APK rollback via `INSTALL_VERSION` desde dashboard | Minutos |

El flag es la palanca primaria. Todo lo demás existe pero está apagado.

---

## 14. Preguntas abiertas para auditoría v2

Cosas que el plan asume y que conviene estresar antes de implementar:

1. **¿Dónde vive el flag `PLAYTELECOM_FLOATER_ENABLED`?** Mi recomendación: campo `Venue.flags Json?`. Granularidad por venue.
2. **¿Cómo identifica el backend al `Terminal` actual del request?** Asumimos JWT claim `terminalSerialNumber`. Confirmar.
3. **¿`MultiMerchantSDKManager` puede destruir limpiamente las instancias del SDK Blumon?** Asumimos sí (porque `switchMerchant()` lo hace). Auditar `BlumonInitializer` durante implementación.
4. **Refresh token rotation:** al re-emitir tokens en clock-in con switch, ¿invalidamos el refresh viejo o coexisten? Estándar de seguridad: invalidar viejos.
5. **`/me/venues` autorización:** ¿necesita permiso específico o cualquier Staff autenticado consulta sus venues? Asumimos lo segundo.
6. **Concurrencia con `MultiMerchantSDKManager.switchMerchant`:** ambos toman el mismo Mutex. Si un cobro está activo, el clock-in con switch debe esperar. Validar que el readiness checker bloquea antes de llegar al Mutex.
7. **`Order.venueId` y `SerializedItem.venueId`:** durante implementación verificar que se setean desde `getEffectivePlaytelecomVenueId()` y no desde `req.authContext.venueId`.
8. **Inventario completo de touchpoints en código Playtelecom** que leen venueId — debe hacerse en la primera tarea de implementación, no asumir que ya está mapeado.

---

## 15. Métricas de éxito (post-deploy)

- ✅ 0 crashes en Crashlytics relacionados con `VenueSwitchOrchestrator` durante la primera semana.
- ✅ Audit log muestra cambios de `TimeEntry.venueId` correlacionados 1:1 con clock-ins de cubre-descansos.
- ✅ Reportes de Playtelecom muestran ventas/SIMs atribuidas a la tienda correcta para los 3 cubre-descansos.
- ✅ Cero impacto en métricas de tenants no-Playtelecom (latencia clock-in, errores 4xx/5xx).
- ✅ Cero usuarios atorados (TimeEntry activo > 24h sin auto-clockout) durante el primer mes.
- ✅ 0 logs de `staffId mismatch` en warn mode (señal de que enforcement en Ola 2 es seguro).

---

**Fin del plan v2. Listo para tercera ronda de auditoría.**
