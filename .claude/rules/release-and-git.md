# Release Build & Git Policy

## Release Build Checklist

When user asks to build a production APK:

### 1. Version Bump

Ask about version bump. Location: `app/build.gradle.kts` -> `android.defaultConfig`

- `versionCode` += 1 (ALWAYS increment for release)
- `versionName` = semver (MAJOR.MINOR.PATCH)
- Dev/testing: don't change version. Production release: ALWAYS bump.

### 2. Build APK

```bash
./gradlew assembleProductionRelease
```

### 3. Sign with apksigner (v2 scheme REQUIRED)

targetSdk 34+ requires APK Signature Scheme v2. Use `apksigner`, NOT `jarsigner`.

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out ~/Desktop/avoqado-tpv-VERSION-production-signed.apk \
  app/build/outputs/apk/production/release/app-production-release-unsigned.apk
```

### 4. Verify Signature

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose APK_FILE.apk
# Must show: "Verified using v2 scheme: true"
```

### 5. Save to iCloud (MANDATORY)

**El APK se archiva bajo la carpeta del PROCESADOR de esa variante, no en una sola.**

PAX / Blumon (variantes `sandbox`, `production`):

```
/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK/
  <version>/sandbox/avoqado-tpv-<version>-sandbox.apk
  <version>/production/avoqado-tpv-<version>-production.apk
  <version>/PAXFIRMADO/  (manually added after PAX signs)
```

Nexgo / AngelPay (variantes `nexgo`, `nexgoProd`) — **NUNCA bajo `Blumon/`**:

```
/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/APK/
  <version>/nexgoProd/avoqado-tpv-<version>-nexgoProd.apk
```

Nexgo no pasa por el firmado de PAX (no hay `PAXFIRMADO/`): se firma con apksigner
y se entrega al equipo de AngelPay.

### 🔴 Dos canales de distribución distintos — no los cruces

| Flota | Cómo llega el APK a la terminal |
|---|---|
| **PAX / Blumon** | Sistema propio de Avoqado: se sube el APK → fila `AppUpdate` (Firebase Storage) → `check-update` / comando `INSTALL_VERSION` desde el dashboard |
| **Nexgo / AngelPay** | **Se le ENTREGA el APK firmado al equipo de AngelPay y ELLOS lo despliegan por su TMS.** El founder NO sube builds de Nexgo al sistema de Avoqado |

Por eso la tabla `AppUpdate` no tiene ninguna versión `-nexgo-prod` (2.6.3, 2.6.5, 2.7.0…):
esas nunca pasaron por ahí. Las terminales Nexgo reportan su versión por heartbeat
igual que las PAX, pero se actualizan por el TMS de AngelPay (se ve en logcat:
`tmsVersion`, `otaVersion=v1.4.x_Angelpay…`).

**⚠️ La mina si algún día se sube un APK Nexgo al sistema de Avoqado:** `AppUpdate`
NO tiene campo de procesador/ABI y `check-update` solo filtra por `environment` +
`platform` — PAX y Nexgo son ambas `PRODUCTION` + `ANDROID_TPV`. Con
`targetType=ALL` (el default y lo que usan TODOS los releases PAX), **cada PAX
vería el APK Nexgo como actualización** → `armeabi-v7a` + `ENABLE_PAX_SDK=false`
+ SDK AngelPay = PAX sin poder cobrar. Targetear por venue tampoco basta: hay
venues con AMBAS marcas (Amaena, Testarudo Café). El único targeting seguro sería
`targetType=TERMINALS`, que hoy es inerte en `check-update` (ningún APK manda
`X-Terminal-Serial`) pero sí funciona vía `INSTALL_VERSION`
(`getSpecificVersion` no filtra por audiencia).

Never save APKs to Desktop.

### 6. Send to Blumon -> PAX re-signs -> final APK for terminals

## Version Bump Recommendations

**THE KEY QUESTION: Can the user do something they COULDN'T before?**

| Answer | Bump | Examples |
|--------|------|---------|
| Yes, new capability | **MINOR** | BLE payments, kiosk mode, new reports |
| No, improvement only | **PATCH** | Bug fix, UX improvement, refactor, performance |
| Breaks compatibility | **MAJOR** | Incompatible DB migration, API redesign |
| Docs/tests only | **No bump** | CLAUDE.md update, test additions |

Common mistake: seeing "lots of new code" and assuming MINOR. Code volume doesn't matter — user IMPACT does.

After each implementation, proactively recommend bump type with justification.

## Permission System for New Features

Every new TPV feature MUST have permissions:

1. **Backend**: Add to `avoqado-server/src/lib/permissions.ts` (`PERMISSION_CATEGORIES` + `DEFAULT_PERMISSIONS`)
2. **Backend**: Use `checkPermission()` middleware on endpoint
3. **TPV**: Validate with `PermissionsRepository.hasPermission()` before showing UI
4. **Dashboard**: Permission appears automatically in RolePermissions.tsx

Naming: `tpv-{resource}:{action}` (e.g., `tpv-payments:refund`, `tpv-shifts:create`)

**Critical**: EXACT same permission name in backend `checkPermission()` AND TPV `hasPermission()`. Name mismatches cause silent failures.

## Cross-Repo Consistency

TPV takes 3-5 days to update (Blumon/PAX signing). Backend/Dashboard deploy in minutes.

**Before generating production APK:**

```bash
./scripts/check-cross-repo.sh  # Exit 0 = ready, 1 = errors, 2 = warnings
```

| Principle | Rule |
|-----------|------|
| Backend supports old versions | Use `X-App-Version-Code` header for conditional behavior |
| Never remove API response fields | Add new fields, don't delete old ones |
| New fields must be optional | Include defaults for backwards compat |
| Deploy order | Backend first -> wait stable -> send APK |

Timeline: Day 1 deploy backend+dashboard, Day 1 send APK to Blumon, Day 3-5 APK on terminals. Backend must support old AND new TPV for ~1 week.

## Git Policy

**Never commit, push, or make git changes without explicit user permission.**

### Commit Format

```
feat(area): description    # New feature
fix(area): description     # Bug fix
release(vX.Y.Z): title     # Release
```

**Do NOT add `Co-Authored-By: Claude`** — commits should look like developer's work.

### Post-Implementation Flow

After completing any task, ask:
- **Commit** — normal commit with descriptive message
- **Release** — bump version + commit + tag + push + build instructions
- **WIP** — leave changes uncommitted

### Release Checklist (when user says "release")

1. Verify no compilation errors
2. Bump `versionCode` (+1) and `versionName` in build.gradle.kts
3. Commit with detailed release message
4. Create annotated tag `vX.Y.Z`
5. Push to origin (main + tags)
6. Give build + signing instructions
