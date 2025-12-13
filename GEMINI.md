# GEMINI.md - Avoqado TPV (Android POS)

This file is the **index** for Gemini. It provides quick context and points to detailed documentation in `docs/`.

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

### Development & Operations

| Document                                 | Description                                              |
| ---------------------------------------- | -------------------------------------------------------- |
| `docs/DEVELOPMENT_WORKFLOW.md`           | Build variants, testing, commits, CHANGELOG policy       |
| `PERFORMANCE_GUIDE.md`                   | 1GB RAM optimization, pagination, caching                |
| `UI_RESPONSIVE_GUIDE.md`                 | Responsive patterns for TPV devices (PAX A80, A920)      |
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

---

## 7. Documentation Policy

### What goes in GEMINI.md (this file)

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

**Full troubleshooting**: See individual guides in `docs/`

---

## Quick Links

| Need to...                    | Go to...                                 |
| ----------------------------- | ---------------------------------------- |
| Understand Kotlin patterns    | `docs/KOTLIN_BEST_PRACTICES.md`          |
| Make decisions on UI/errors   | `docs/DECISION_MATRIX.md`                |
| Work on Blumon payments       | `PAYMENT_RECONCILIATION.md`              |
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

**Last Updated:** 2025-12-12
**Maintainer:** Development Team
**Version:** 3.0 (Refactored: GEMINI.md as index + detailed docs/)
