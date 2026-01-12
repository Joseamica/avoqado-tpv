# CLAUDE.md - Avoqado TPV (Android POS)

This file is the **index** for Claude Code. It provides quick context and points to detailed documentation in `docs/`.

---

## 🔴 MANDATORY: Documentation Update Rule (READ FIRST)

**When implementing or modifying ANY feature, you MUST:**

1. **Check if documentation exists** for the feature/area you're modifying
2. **Update the documentation** if your changes affect documented behavior
3. **Create new documentation** if implementing a new significant feature
4. **Update references in this CLAUDE.md** if you create new docs
5. **Cross-repo features** → Update `avoqado-server/docs/` (central hub)

**This is NOT optional.** Documentation debt causes confusion and bugs.

```
✅ DO: Implement feature → Update docs → Commit both together
❌ DON'T: Implement feature → "I'll document it later" → Never document it
```

**Central hub:** `avoqado-server/docs/README.md` is the master index for ALL cross-repo documentation.

---

## 1. CRITICAL: Blumon Has TWO Separate Integrations

**BEFORE working on anything Blumon**, identify which integration:

|                        | **TPV (Android SDK)**                    | **E-commerce (Web Payments)**                  |
| ---------------------- | ---------------------------------------- | ---------------------------------------------- |
| **What is it?**        | Physical PAX terminals                   | Web SDK for online payments                    |
| **Where does it run?** | APK connects DIRECTLY to Blumon          | BACKEND calls Blumon API                       |
| **Environment config** | **APK build variant** (sandbox/prod)     | **`USE_BLUMON_MOCK`** env var                  |
| **Database model**     | `MerchantAccount` + `Terminal`           | `EcommerceMerchant` + `CheckoutSession`        |
| **Service file**       | (Android app - this repo)                | `avoqado-server` backend                       |

**Rule**: Always say "Blumon TPV" or "Blumon E-commerce". Just "Blumon" is ambiguous.

**Full context**: See avoqado-server `docs/BLUMON_TWO_INTEGRATIONS.md`

### ⚠️ MANDATORY: Before Working on Blumon Payments

**ALWAYS read this document first:**

```
avoqado-server/docs/blumon-tpv/BLUMON_MULTI_MERCHANT_ANALYSIS.md
```

This is the **complete technical deep dive** on multi-merchant architecture:
- Virtual vs Physical serial numbers
- Multi-merchant credential routing & selection logic
- Payment flow with merchant account resolution
- Cost structures and pricing per merchant

---

## 2. Role & Identity

Always assume the role of a world-class, battle-tested Android engineer with experience at Toast and Square. You have elite mastery of
Kotlin, Jetpack Compose, POS terminals, payments, offline-first architecture, and merchant experience end-to-end.

---

## 3. Documentation Map

### Architecture & Core

| Document                                   | Description                                                      |
| ------------------------------------------ | ---------------------------------------------------------------- |
| `docs/KOTLIN_BEST_PRACTICES.md`            | Anti-patterns, naming conventions, Clean Architecture            |
| `docs/DECISION_MATRIX.md`                  | Decision trees for error handling, UI patterns, Socket.IO events |
| `GREENFIELD_BLUEPRINT.md`                  | Complete architecture & 28-day implementation plan               |

### Payments & Integration

| Document                             | Description                                       |
| ------------------------------------ | ------------------------------------------------- |
| `PAYMENT_RECONCILIATION.md`          | Blumon multi-merchant system, payment flow        |
| `docs/PRODUCTION_DEPLOYMENT.md`      | Complete production deployment flow with Blumon   |
| `docs/DOMAIN_RULES.md`               | Backend integration, Socket.IO, security patterns |
| `docs/TPV_COMMAND_FLOW.md`           | Remote command system (lock, maintenance, ACK)    |

### Features & Business Logic

| Document                                 | Description                                              |
| ---------------------------------------- | -------------------------------------------------------- |
| `docs/MODULES_SYSTEM.md`                 | **Modules**: VenueModule config, StateFlow pattern       |
| `docs/ATTENDANCE_VERIFICATION.md`        | **Timeclock**: Clock-in/out photo + GPS verification     |
| `docs/PAY_LATER_README.md`               | **Pay Later Overview**: Index of all pay-later docs      |
| `docs/PAY_LATER_IMPLEMENTATION.md`       | **Pay Later (Android)**: Bug fix + banner implementation |
| `docs/PAY_LATER_TESTING_CHECKLIST.md`    | Pay Later QA manual + automated tests                    |
| `avoqado-server/docs/PAY_LATER_ORDER_CLASSIFICATION.md` | **Pay Later (Backend)**: Classification logic |

### Development & Operations

| Document                                 | Description                                              |
| ---------------------------------------- | -------------------------------------------------------- |
| `docs/DEVELOPMENT_WORKFLOW.md`           | Build variants, testing, commits, CHANGELOG policy       |
| `PERFORMANCE_GUIDE.md`                   | 1GB RAM optimization, pagination, caching                |
| `UI_RESPONSIVE_GUIDE.md`                 | Responsive patterns for TPV devices (PAX A80, A920)      |
| `docs/COMPOSE_KEYBOARD_HANDLING.md`      | **FIX: TextField keyboard issues in Dialog (common bug)**|
| `SOCKET_IO_IMPLEMENTATION.md`            | Real-time events architecture & integration              |
| `SOCKET_IO_TESTING.md`                   | Socket.IO testing strategies & examples                  |
| `LOCAL_FIRST_SYNC_PATTERNS.md`           | **CRITICAL: Preserve local-only fields when syncing**    |
| `TESTING_GUIDE.md`                       | Unit tests, integration tests, debugging                 |
| `SECURITY_CHECKLIST.md`                  | Encryption, tenant isolation, certificate pinning        |
| `PRODUCTION_BUILD_GUIDE.md`              | Build variants, deployment, troubleshooting              |
| `ADB_MONITORING_GUIDE.md`                | **MANDATORY: ADB commands after every implementation**   |

---

## 4. Development Commands

### Essential Commands

```bash
# Set Java version (REQUIRED)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)

# Sandbox build (development/testing)
./gradlew installSandboxDebug

# Production build (real terminals)
./gradlew assembleProductionRelease

# Lint (MUST pass before commit)
./gradlew lint --continue

# Compile only (faster check)
./gradlew compileDebugKotlin
```

### ADB Monitoring (MANDATORY After Every Change)

```bash
# Clear logs and monitor specific tags
adb logcat -c && adb logcat -s PaymentViewModel,MenuViewModel | grep -iE "keyword"
```

**Full guide**: `ADB_MONITORING_GUIDE.md`

---

## 5. Stack Quick Reference

```
• Architecture: Clean Architecture (Presentation → Domain → Data)
• UI: 100% Jetpack Compose (NO XML)
• DI: Hilt 2.57
• Security: EncryptedSharedPreferences, Certificate Pinning
• Backend: https://api.avoqado.io/api/v1/
• Real-time: Socket.IO (room-based events)
• Payments: Blumon PAX SDK (multi-merchant support)
• Performance: 1GB RAM target (PAX A80)
```

---

## 6. Critical Patterns (MUST Follow)

### Build Variants: Sandbox vs Production

**⚠️ CRITICAL**: This project uses Android build variants.

| Variant       | Package ID                      | Blumon Server                 | AAR File              |
| ------------- | ------------------------------- | ----------------------------- | --------------------- |
| **Sandbox**   | `com.jaac.avoqado_tpv.sandbox`  | `sandbox-tokener.blumonpay.net` | `blumon_sdk-debug.aar` |
| **Production** | `com.jaac.avoqado_tpv`          | `tokener.blumonpay.net`       | `blumon_sdk-prod.aar`  |

**Files in `sandbox/` and `production/` folders:**
- `PaymentViewModel.kt`
- `InitializationManager.kt`
- `BlumonInitializer.kt`

**Rule**: Changes to these files MUST be synced between sandbox and production (except SDK URLs).

**Full guide**: `docs/DEVELOPMENT_WORKFLOW.md`

### Authentication

```kotlin
// CORRECT - Use authContext
val authContext = authRepository.getAuthContext()
val venueId = authContext.venueId

// WRONG - req.user does NOT exist in Android
val user = request.user // This is backend pattern!
```

### Tenant Isolation

```kotlin
// EVERY database query MUST filter by venueId
val orders = orderRepository.getOrders(venueId = authContext.venueId)

// WRONG - Security risk!
val orders = orderRepository.getAllOrders()
```

### Money Handling

```kotlin
// CORRECT - Use BigDecimal
val amount = BigDecimal("100.50")

// WRONG - Float precision loss
val amount = 100.5
```

### Performance (1GB RAM Devices)

```kotlin
// ❌ WRONG: Load all orders (OOM risk!)
val orders = orderRepository.getAllOrders()

// ✅ CORRECT: Paginate
val orders = orderRepository.getOrders(limit = 20, cursor = cursor)
```

**Full guide**: `PERFORMANCE_GUIDE.md`

### API Endpoint Paths (CRITICAL)

**Base URL is already `/api/v1/`**, so TPV endpoints should NOT include `/v1/` again:

```kotlin
// ✅ CORRECT - Base URL + "tpv/modules" = /api/v1/tpv/modules
@GET("tpv/modules")
suspend fun getModules(): Response<ModulesApiResponse>

// ❌ WRONG - Creates /api/v1/tpv/v1/modules (double v1!)
@GET("tpv/v1/modules")
suspend fun getModules(): Response<ModulesApiResponse>
```

**Rule**: When backend defines route at `/tpv/something`, use `@GET("tpv/something")` NOT `@GET("tpv/v1/something")`

---

## 7. Documentation Policy

### What goes in CLAUDE.md (this file)

- Critical warnings (Blumon distinction, build variants)
- Documentation map (pointers to docs/)
- Development commands
- Quick stack reference
- Critical patterns

### What goes in docs/\*.md

- Detailed implementation guides
- Complete architecture explanations
- Troubleshooting guides
- Decision matrices

### Golden Rules

1. Document **WHY**, not **HOW** (code explains HOW)
2. Tests are living documentation
3. If code + tests explain it clearly → don't document
4. ALL new docs go in `docs/` directory, never in root

### Documentation Update Checklist

> **See "🔴 MANDATORY: Documentation Update Rule" at the top of this file.**

**Checklist before committing:**
- [ ] Does this change affect any existing documentation?
- [ ] Did I update line number references if file structure changed?
- [ ] Did I update progress percentages if completing phases?
- [ ] Did I add new documentation if this is a new feature?

**Avoid fragile line number references.** Instead of `"See file.kt lines 100-200"`, use:
- Function/class names: `"See processPayment() in PaymentProcessor.kt"`
- Section headers: `"See ## Bluetooth section in HARDWARE.md"`

**Cross-repo updates:**
- Blumon/payment changes → Update `avoqado-server/docs/blumon-tpv/`
- API contract changes → Coordinate with backend team

### Centralized Documentation (Multi-Repo)

**`avoqado-server/docs/` is the SINGLE SOURCE OF TRUTH for cross-repo documentation.**

**Master Index:** `avoqado-server/docs/README.md`

```
avoqado-server/docs/           ← CENTRAL HUB
├── README.md                  ← Master index of ALL documentation
├── features/                  ← Cross-repo features
├── blumon-tpv/               ← Blumon TPV integration
└── ...

avoqado-tpv/docs/              ← Android-specific ONLY (this repo)
├── android/                  ← Kotlin/Compose patterns
└── devices/                  ← PAX hardware guides

avoqado-web-dashboard/docs/    ← Frontend-specific ONLY
└── ...
```

| Topic | Location |
|-------|----------|
| **Browse all docs** | `avoqado-server/docs/README.md` |
| Backend architecture, APIs, DB | `avoqado-server/docs/` |
| Blumon TPV integration | `avoqado-server/docs/blumon-tpv/` |
| Blumon E-commerce | `avoqado-server/docs/blumon-ecommerce/` |
| Business Types & MCC | `avoqado-server/docs/BUSINESS_TYPES.md` |
| Database schema | `avoqado-server/docs/DATABASE_SCHEMA.md` |
| Payment architecture | `avoqado-server/docs/PAYMENT_ARCHITECTURE.md` |
| TPV Android specific | `docs/` (this repo) |

---

## 8. Common Pitfalls (Quick Reference)

| Problem                         | Cause                           | Solution                                      |
| ------------------------------- | ------------------------------- | --------------------------------------------- |
| First payment takes 30s         | SQLite connection leak          | Use single Storage instance in AvoqadoApp     |
| UI freezes during payment       | Blocking on main thread         | Use `withContext(Dispatchers.IO)`             |
| Socket events not received      | Not joined to room              | Join room before listening                    |
| Flash screens                   | Instant navigation              | Use `AvoqadoLoadingOverlay`                   |
| OutOfMemoryError                | Loading all data at once        | Paginate (limit 20)                           |
| Items lose "printed" status     | Backend overwrites local fields | Load from local DB after cache                |
| 401 "Usuario no encontrado"     | Wrong variant                   | Use `sandboxDebug` for testing                |
| Sandbox/Production sync issues  | Modified only one variant       | Sync changes between both variants            |
| Blumon rechaza APK              | APK enviado sin firmar          | Firmar con debug key antes de enviar          |
| 404 on new API endpoint         | Double `/v1/` in path           | Use `tpv/endpoint` NOT `tpv/v1/endpoint`      |
| TpvSettings field not persisted | Missing from SecureStorage      | Add key + save/load/clear in SecureStorage    |
| Module config stale after logout| UI uses `remember {}` not Flow  | Use `collectAsStateWithLifecycle()` on StateFlow |
| Feature button not appearing    | Permission not in DEFAULT_PERMISSIONS | Add permission to `permissions.ts` DEFAULT_PERMISSIONS + INDIVIDUAL_PERMISSIONS_BY_RESOURCE |
| Permission check fails silently | Name mismatch TPV vs Backend    | Verify EXACT permission name in both: backend `checkPermission()` AND TPV `hasPermission()` |

**Full troubleshooting**: See individual guides in `docs/`

---

## Quick Links

| Need to...                    | Go to...                                 |
| ----------------------------- | ---------------------------------------- |
| **Browse ALL docs**           | `avoqado-server/docs/README.md`          |
| Understand Kotlin patterns    | `docs/KOTLIN_BEST_PRACTICES.md`          |
| Make decisions on UI/errors   | `docs/DECISION_MATRIX.md`                |
| Work on Blumon payments       | `avoqado-server/docs/blumon-tpv/BLUMON_MULTI_MERCHANT_ANALYSIS.md` **(READ FIRST)** |
| Deploy to production          | `docs/PRODUCTION_DEPLOYMENT.md`          |
| Understand backend integration| `docs/DOMAIN_RULES.md`                   |
| Set up build variants         | `docs/DEVELOPMENT_WORKFLOW.md`           |
| Optimize for 1GB RAM          | `PERFORMANCE_GUIDE.md`                   |
| Work on responsive UI         | `UI_RESPONSIVE_GUIDE.md`                 |
| Add Socket.IO events          | `SOCKET_IO_IMPLEMENTATION.md`            |
| Test Socket.IO                | `SOCKET_IO_TESTING.md`                   |
| Fix local-first sync bugs     | `LOCAL_FIRST_SYNC_PATTERNS.md`           |
| Debug with ADB                | `ADB_MONITORING_GUIDE.md`                |
| Write tests                   | `TESTING_GUIDE.md`                       |

---

## 9. MANDATORY: Permission System for New Features

**⚠️ CRITICAL RULE**: Cada vez que agregues una nueva funcionalidad al TPV, DEBES seguir este proceso:

### Paso 1: Definir Permisos en Backend

1. Agrega el permiso a `avoqado-server/src/lib/permissions.ts` en `PERMISSION_CATEGORIES`
2. Agrega a `DEFAULT_PERMISSIONS` para cada rol apropiado
3. Usa `checkPermission()` middleware en el endpoint

### Paso 2: Validar en TPV Android

1. Verifica permiso antes de mostrar UI (botones, opciones, screens)
2. Usa `PermissionsRepository.hasPermission()` en composables
3. Deshabilita acciones si el usuario no tiene el permiso

### Paso 3: Dashboard UI

1. El permiso aparecerá automáticamente en RolePermissions.tsx
2. Verifica que la descripción sea clara

### Ejemplos de Permisos TPV Existentes

| Feature | Permission | Roles con Acceso por Defecto |
|---------|-----------|------------------------------|
| Ver órdenes | `tpv-orders:read` | WAITER+ |
| Procesar pagos | `tpv-payments:create` | CASHIER+ |
| Hacer reembolsos | `tpv-payments:refund` | ADMIN+ |
| Abrir turno | `tpv-shifts:create` | MANAGER+ |
| Ver reportes | `tpv-reports:read` | ADMIN+ |
| Modificar config terminal | `tpv-terminal:settings` | ADMIN+ |
| Reset de fábrica | `tpv-factory-reset:execute` | OWNER+ |

### Naming Convention

```
tpv-{resource}:{action}

Resources: orders, payments, shifts, tables, customers, reports, terminal, etc.
Actions: read, create, update, delete, refund, comp, void, settings, execute
```

### ⚠️ Si NO agregas permisos:

- Cualquier usuario podrá acceder a la funcionalidad (security risk)
- No se podrá restringir desde el dashboard
- Violación de principio de least privilege

### ✅ Proceso Correcto:

1. Nueva feature → Definir permiso
2. Agregar a DEFAULT_PERMISSIONS
3. Usar `checkPermission()` en backend
4. Validar en TPV UI con `hasPermission()`
5. Probar con diferentes roles

### 🚨 ERRORES COMUNES (Real Bug - Enero 2026)

**Bug encontrado**: El botón "Alta de Productos" no aparecía para Super Admin porque:

1. **Permiso no definido en DEFAULT_PERMISSIONS** - El endpoint usaba `checkPermission('serialized-inventory:create')` pero el permiso NUNCA se agregó a `DEFAULT_PERMISSIONS` en `permissions.ts`

2. **Nombre inconsistente** - Backend usaba `serialized-inventory:create`, TPV validaba `serialized-inventory:register`

**Checklist antes de PR:**
- [ ] Permiso agregado a `DEFAULT_PERMISSIONS` para roles apropiados
- [ ] Permiso agregado a `INDIVIDUAL_PERMISSIONS_BY_RESOURCE` si usas wildcards
- [ ] **MISMO nombre exacto** en backend (`checkPermission()`) y TPV (`hasPermission()`)
- [ ] Probado con rol que NO tiene el permiso (debe ocultar/deshabilitar)
- [ ] Probado con rol que SÍ tiene el permiso (debe funcionar)

---

## 10. MANDATORY: Release Build Checklist

**⚠️ CUANDO EL USUARIO PIDA COMPILAR APK DE PRODUCCIÓN/RELEASE**, Claude DEBE:

### Paso 1: Preguntar sobre la versión

```
🚀 Antes de compilar el APK de release:

1. ¿Cuál es la versión actual? (reviso app/build.gradle)
2. ¿Quieres hacer bump de versión?
   - PATCH (1.2.3 → 1.2.4): Bug fixes
   - MINOR (1.2.3 → 1.3.0): Nueva funcionalidad
   - MAJOR (1.2.3 → 2.0.0): Cambios breaking

¿Qué tipo de release es este?
```

### Paso 2: Si el usuario confirma bump

1. Actualizar `app/build.gradle`:
   - `versionCode` += 1 (SIEMPRE incrementar)
   - `versionName` = nueva versión
2. Preguntar si quiere actualizar CHANGELOG.md
3. Compilar APK

### Paso 3: Generar APK

```bash
./gradlew assembleProductionRelease
```

### Paso 4: ⚠️ CRÍTICO - Firmar APK con Signature Scheme v2

**IMPORTANTE**: targetSdk 34+ REQUIERE APK Signature Scheme v2. Usar `apksigner` (NO `jarsigner`).

```bash
# ✅ CORRECTO: Usar apksigner para firma v2/v3
~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out ~/Desktop/avoqado-tpv-VERSION-production-signed.apk \
  app/build/outputs/apk/production/release/app-production-release-unsigned.apk

# ❌ INCORRECTO: jarsigner solo hace v1 (falla en Android 11+)
# jarsigner ... ← NO USAR
```

### Paso 5: Verificar firma

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose APK_FILE.apk
# Debe mostrar: "Verified using v2 scheme: true"
```

### Paso 6: Enviar a Blumon

1. Enviar APK firmado a Blumon
2. Blumon lo re-firma con certificado PAX
3. Recibir APK final firmado por PAX
4. Ese APK se puede instalar en terminales de producción

### ❌ ERRORES COMUNES

| Error | Causa | Solución |
|-------|-------|----------|
| "Error análisis paquete" | Firma v1 only (jarsigner) | Usar `apksigner` |
| "Error análisis paquete" | APK corrupto | Verificar con `aapt2 dump badging` |
| Blumon rechaza APK | APK unsigned | Firmar antes de enviar |

### Versión actual en build.gradle

Ubicación: `app/build.gradle.kts` → `android.defaultConfig`
```kotlin
versionCode = 3       // Número único, siempre incrementa
versionName = "1.1.1" // Semántico: MAJOR.MINOR.PATCH
```

### Regla de Oro

- **Desarrollo/Testing**: No cambiar versión
- **Release a producción**: SIEMPRE bump versionCode + versionName

---

**Last Updated:** 2025-12-26
**Maintainer:** Development Team
**Version:** 4.3 (Fixed: APK must use apksigner for v2 signature, not jarsigner)
