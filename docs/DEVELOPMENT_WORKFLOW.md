# Development Workflow

**Purpose**: Complete guide for build variants, testing, commits, and deployment workflows.

---

## 1. Build Variants: Sandbox vs Production

**⚠️ CRITICAL**: This project uses Android build variants for sandbox and production environments.

### Structure

```
app/src/
├── main/                    ← 99% of code (SHARED by both environments)
│   ├── MenuViewModel.kt              ✅ Applies to both
│   ├── FloorPlanCanvasScreen.kt      ✅ Applies to both
│   ├── ReportsViewModel.kt           ✅ Applies to both
│   ├── AmountInputBottomSheet.kt     ✅ Applies to both
│   └── ... all other files           ✅ Applies to both
│
├── sandbox/                 ← ONLY 3 files (Blumon SDK config)
│   ├── PaymentViewModel.kt           ⚠️ Sandbox-specific
│   ├── InitializationManager.kt      ⚠️ Sandbox-specific
│   └── BlumonInitializer.kt          ⚠️ Sandbox-specific
│
└── production/              ← ONLY 3 files (Blumon SDK config)
    ├── PaymentViewModel.kt           ⚠️ Production-specific
    ├── InitializationManager.kt      ⚠️ Production-specific
    └── BlumonInitializer.kt          ⚠️ Production-specific
```

### Key Rules

- ✅ **Changes in `app/src/main/`** → Apply to BOTH sandbox and production automatically
- ⚠️ **Changes in `app/src/sandbox/`** → Only sandbox (must also edit `app/src/production/` manually)
- ⚠️ **Changes in `app/src/production/`** → Only production (must also edit `app/src/sandbox/` manually)

---

## 2. MANDATORY: Sync Changes Between Sandbox and Production

> **🚨 When you modify `PaymentViewModel.kt` in sandbox, you MUST apply the same changes to production (and vice versa).**

### Why?

These files share 99% of the same code. Only the Blumon SDK URLs differ:
- Sandbox: `sandbox-tokener.blumonpay.net`
- Production: `tokener.blumonpay.net`

### What to sync

- ✅ Bug fixes (Smart Retry, order context, merchant lookup)
- ✅ New features (split payments, error handling)
- ✅ Refactoring (function signatures, state management)
- ❌ SDK-specific config (URLs, AAR files, `arpcResponseCode` parameter)

### How to sync

```bash
# After modifying sandbox PaymentViewModel:
diff app/src/sandbox/.../PaymentViewModel.kt app/src/production/.../PaymentViewModel.kt
# Apply relevant changes to production (NOT the SDK URLs)

# After modifying production PaymentViewModel:
diff app/src/production/.../PaymentViewModel.kt app/src/sandbox/.../PaymentViewModel.kt
# Apply relevant changes to sandbox
```

### Recent example (2025-12-03)

Smart Retry improvements (preserving order context for retry) were added to sandbox but NOT production.
This caused production to lose order context on payment retry → "order not found" errors.
**Fix:** Manually synced `createPaymentContext()` and `retryPayment()` to production.

---

## 3. Build Configuration

| Variant       | Package ID                     | Blumon Server                   | Blumon Env | AAR Files             |
|---------------|--------------------------------|---------------------------------|------------|-----------------------|
| **Sandbox**   | `com.jaac.avoqado_tpv.sandbox` | `sandbox-tokener.blumonpay.net` | `SAND`     | `blumon_sdk-debug.aar` |
| **Production** | `com.jaac.avoqado_tpv`         | `tokener.blumonpay.net`         | `PROD`     | `blumon_sdk-prod.aar`  |

---

## 4. Build Commands

```bash
# Set Java version (REQUIRED)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)

# Sandbox (development/testing)
./gradlew installSandboxDebug

# Production (real terminals)
./gradlew assembleProductionRelease
# Output: app/build/outputs/apk/production/release/app-production-release.apk
```

### Common Issues

| Issue | Fix |
|-------|-----|
| 401 "Usuario no encontrado" | Wrong variant - use sandbox for testing |
| `Unsupported class file major version 68` | Set Java 23: `export JAVA_HOME=$(/usr/libexec/java_home -v 23)` |
| Hilt/NoSuchFile errors | Clean: `rm -rf app/build .gradle build && ./gradlew clean` |
| `NoClassDefFoundError: ..._GeneratedInjector` al arrancar (build VERDE) | APK incompleto -> ver "APK verde que crashea al arrancar" abajo |

### APK verde que crashea al arrancar (Hilt incompleto)

Sintoma exacto en logcat, justo al abrir la app:

```
FATAL EXCEPTION: main
java.lang.NoClassDefFoundError: Failed resolution of:
  Lcom/jaac/avoqado_tpv/AvoqadoTPVApplication_GeneratedInjector;
  at com.jaac.avoqado_tpv.Hilt_AvoqadoTPVApplication.<init>
Caused by: java.lang.ClassNotFoundException: Didn't find class
  "...AvoqadoTPVApplication_GeneratedInjector" on path: DexPathList[[zip file "base.apk"]]
```

**No es un bug de codigo ni de configuracion de Hilt.** `Hilt_AvoqadoTPVApplication` y
`AvoqadoTPVApplication_GeneratedInjector` los genera KSP en la MISMA ronda y al MISMO
directorio (`app/build/generated/ksp/<variant>/java/com/jaac/avoqado_tpv/`). Que una este
en el dex y la otra no es imposible en un build limpio: significa que el APK empaqueto
salidas intermedias de builds distintos. Gradle vio las tareas UP-TO-DATE y el build salio
verde igual.

Causas tipicas: un build interrumpido (Ctrl-C, OOM — esta maquina vive cerca del limite),
dos builds pesados en paralelo, o `app/build/intermediates` sucio tras cambiar de variante.

**Fix:**

```bash
./gradlew --stop
./gradlew clean
adb uninstall com.jaac.avoqado_tpv.sandbox   # opcional, descarta dex viejo en la terminal
./gradlew installSandboxDebug
./scripts/verify-apk-hilt.sh                 # confirma que el grafo Hilt SI quedo dentro
```

Si reincide, borra tambien el estado incremental de KSP:
`rm -rf app/build/generated/ksp app/build/kspCaches app/build/intermediates`

**Verificar ANTES de instalar** (barato, 1s — vale doble antes de mandar un APK a firmar,
que cuesta 3-5 dias):

```bash
./scripts/verify-apk-hilt.sh                     # sandboxDebug por defecto
./scripts/verify-apk-hilt.sh --variant nexgoDebug
```

Sale 0 si el grafo Hilt esta completo, 1 si falta alguna clase (no instalar). En builds
`release` R8 ofusca los nombres generados, asi que el check solo aplica a APKs debug.

---

## 5. Before Starting a Feature

- [ ] Read feature requirements
- [ ] Check existing similar features
- [ ] **Evaluate if feature needs Socket.IO** (see `DECISION_MATRIX.md` → Socket.IO Decision Tree)
- [ ] Plan architecture (ViewModel → UseCase → Repository)
- [ ] Create feature module structure

---

## 6. During Development

- [ ] Write ViewModel with StateFlow
- [ ] Create Repository interface (domain layer)
- [ ] Implement Repository (data layer)
- [ ] Build Composable UI with ResponsiveScaffold
- [ ] Add @Preview annotations
- [ ] Use stringResource for all text
- [ ] Use MaterialTheme for all colors
- [ ] Translate errors to user-friendly messages

---

## 7. Before Committing

### Code Quality

- [ ] Run `./gradlew lint --continue` (must pass)
- [ ] Add/update unit tests
- [ ] Check for orphaned files (delete unused ViewModels, Composables, resources)
- [ ] No debug code (println, hardcoded values)

### CHANGELOG.md (MANDATORY)

**Format:**
```markdown
### [Category]
- [ClassName]: [Action] [description] ([file]:[line])
  - [Optional: Additional detail]
  - [Optional: Related issue: #123]
```

**Categories:**
- **Added**: New features, files, functionality
- **Changed**: Modifications to existing features
- **Fixed**: Bug fixes
- **Removed**: Deleted features, files
- **Security**: Vulnerability fixes, security improvements

**Example:**
```markdown
### Added
- PaymentViewModel: Add credential caching mechanism (PaymentViewModel.kt:45)
  - Reduces payment time from 6s to <1s
  - Uses singleton pattern with fallback
  - Issue: #234

### Removed
- Delete PaymentFragment.kt (orphaned after Compose migration)
```

**Rotation:** If CHANGELOG.md exceeds 2000 lines, suggest rotation to `changelog/YYYY.md`.

### Orphaned Files Prevention

```bash
# Find unused files
rg "SuspiciousClassName" --type kotlin

# Check Android unused resources
./gradlew lint
# Look for: UnusedResources warnings
```

**Delete if:**
- ✅ Zero imports (`rg "import.*ClassName"`)
- ✅ Zero references (`rg "ClassName"`)
- ✅ Lint marks as unused

### Git Commit Format

```
feat(payment): add credential caching for instant payments

- Implement singleton credential manager
- Reduce payment time from 6s to <1s
- Add fallback to Constants.kt

Resolves #234
```

---

## 8. ADB Monitoring After Implementation (MANDATORY)

> **⚠️ OBLIGATORY**: After EVERY implementation, modification or fix, Claude MUST provide the ADB command to test the change.
>
> **Complete guide:** `ADB_MONITORING_GUIDE.md`

### Rule

At the end of implementing something, always include:
1. The specific `adb logcat` command to monitor
2. What patterns to look for in logs (success/error)
3. Instructions for the user to paste logs back for validation

### Example Response

```
✅ Implementation completed: [description]

To test this change, run in your terminal:
───────────────────────────────────────────────
adb logcat -c && adb logcat -s [ViewModel] | grep -iE "[keywords]"
───────────────────────────────────────────────

Look for in logs:
- ✅ Success: "[expected message]"
- ❌ Error: "[error message]"

When you have the logs, paste them here for verification.
```

### Quick Reference - Common Tags

| Area | Tags |
|------|------|
| Payments | `PaymentViewModel,InitializationManager` |
| Menu | `MenuViewModel` |
| Orders | `OrderViewModel,OrderSyncCoordinator` |
| Socket | `SocketManager` |
| Auth | `AuthViewModel,TokenAuthenticator` |
| Floor Plan | `FloorPlanViewModel` |

---

## 9. Testing

> **Complete guide:** `TESTING_GUIDE.md`

```kotlin
// Unit test example
@Test
fun `should process payment successfully`() = runTest {
    // Given
    coEvery { processPaymentUseCase(any()) } returns Result.success(payment)

    // When
    viewModel.processPayment(payment)

    // Then
    assertThat(viewModel.state.value).isInstanceOf(PaymentState.Success::class.java)
}
```

---

**Last Updated:** 2025-12-12
