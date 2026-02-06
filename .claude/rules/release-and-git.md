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

```
/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK/
  <version>/sandbox/avoqado-tpv-<version>-sandbox.apk
  <version>/production/avoqado-tpv-<version>-production.apk
  <version>/PAXFIRMADO/  (manually added after PAX signs)
```

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
