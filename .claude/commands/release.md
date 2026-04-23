# Release Pipeline: Test, Build, Sign, Deploy, Push

Executes the full Avoqado TPV release pipeline for **both PAX (Blumon) and Nexgo (AngelPay)** APKs from a single commit. Follow each phase strictly. If ANY phase fails, STOP and report — do NOT continue.

**IMPORTANT**: Before starting, ask the user for the new version number (e.g., "1.8.0") if not provided as argument. If the user provides it as $ARGUMENTS, use that.

Version argument: $ARGUMENTS

---

## Build Variant Reference

| Variant | Hardware | Processor | ABI | Deploy path |
|---------|----------|-----------|-----|-------------|
| `productionRelease` | PAX A910S | Blumon (real money) | armeabi | Blumon → PAX re-sign → 3-5 days |
| `nexgoRelease` | Nexgo N86 | AngelPay | arm64-v8a | Direct install (no re-sign needed) |

Both APKs share the same `versionCode`/`versionName`. Nexgo APK shows `X.Y.Z-nexgo` internally (`versionNameSuffix`).

### CHANGELOG labels for platform-specific changes
- `[PAX]` — only affects PAX/Blumon terminals
- `[Nexgo]` — only affects Nexgo/AngelPay terminals
- No label — affects both

---

## Phase 0: Pre-flight Checks

1. Read `app/build.gradle.kts` to get CURRENT `versionCode` and `versionName`
2. Determine NEW version:
   - Use `$ARGUMENTS` if provided, otherwise ask
   - New `versionCode` = current + 1
3. Show:
   ```
   Current: versionCode=XX, versionName="X.Y.Z"
   New:     versionCode=XX+1, versionName="NEW_VERSION"
              nexgo internal: "NEW_VERSION-nexgo"
   ```
4. Check `git status` — warn if there are uncommitted changes that should be committed first
5. Check `CHANGELOG.md` — verify entries exist under `## [Unreleased]`. If empty, STOP.

---

## Phase 1: Testing

Run in parallel:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew testSandboxDebugUnitTest --rerun-tasks
```
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew lint --continue
```

**Gate**: ALL tests pass (0 failures). Lint passes. If either fails, STOP.

---

## Phase 2: Version Bump

1. Edit `app/build.gradle.kts`:
   - Increment `versionCode` by 1
   - Set `versionName` to the new version
2. Update `CHANGELOG.md`:
   - Rename `## [Unreleased]` → `## [NEW_VERSION] - YYYY-MM-DD`
   - Add fresh empty `## [Unreleased]` section above with Added/Changed/Fixed subsections
3. Compile check:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 23)
   ./gradlew compileSandboxDebugKotlin
   ```

**Gate**: Compilation must succeed.

---

## Phase 3: Build Both APKs

Run in parallel:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew assembleProductionRelease
```
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew assembleNexgoRelease
```

**Gate**: Both BUILD SUCCESSFUL. Verify APKs exist:
- `app/build/outputs/apk/production/release/app-production-release-unsigned.apk`
- `app/build/outputs/apk/nexgo/release/app-nexgo-release-unsigned.apk`

---

## Phase 4: Sign Both APKs

Run in parallel:

```bash
# PAX / Blumon
~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out /tmp/avoqado-tpv-NEW_VERSION-production-signed.apk \
  app/build/outputs/apk/production/release/app-production-release-unsigned.apk
```
```bash
# Nexgo / AngelPay
~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out /tmp/avoqado-tpv-NEW_VERSION-nexgo-signed.apk \
  app/build/outputs/apk/nexgo/release/app-nexgo-release-unsigned.apk
```

Verify both signatures:
```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose /tmp/avoqado-tpv-NEW_VERSION-production-signed.apk
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose /tmp/avoqado-tpv-NEW_VERSION-nexgo-signed.apk
```

**Gate**: Both must show `Verified using v2 scheme (APK Signature Scheme v2): true`

---

## Phase 5: Copy to iCloud

```bash
ICLOUD_BASE="/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK"
VERSION="NEW_VERSION"

mkdir -p "${ICLOUD_BASE}/${VERSION}/production"
mkdir -p "${ICLOUD_BASE}/${VERSION}/nexgo"
mkdir -p "${ICLOUD_BASE}/${VERSION}/sandbox"
mkdir -p "${ICLOUD_BASE}/${VERSION}/PAXFIRMADO"

# PAX APK
cp /tmp/avoqado-tpv-${VERSION}-production-signed.apk \
   "${ICLOUD_BASE}/${VERSION}/production/avoqado-tpv-${VERSION}-production.apk"

# Nexgo APK
cp /tmp/avoqado-tpv-${VERSION}-nexgo-signed.apk \
   "${ICLOUD_BASE}/${VERSION}/nexgo/avoqado-tpv-${VERSION}-nexgo.apk"
```

**Gate**: Both files exist. Verify with `ls -lh "${ICLOUD_BASE}/${VERSION}/production/" "${ICLOUD_BASE}/${VERSION}/nexgo/"`.

---

## Phase 6: Git Commit + Tag + Push

1. Stage files:
   ```bash
   git add app/build.gradle.kts CHANGELOG.md
   ```
   Also stage any other modified tracked files that are part of this release.
   Do NOT stage `.idea/` or `.serena/` files.

2. Release commit (no Co-Authored-By):
   ```
   release(vNEW_VERSION): <brief summary>
   ```
   Body: summarize key changes from CHANGELOG. Note if changes are PAX-only, Nexgo-only, or shared.

3. Annotated tag:
   ```bash
   git tag -a vNEW_VERSION -m "vNEW_VERSION: <brief summary>"
   ```

4. Push:
   ```bash
   git push origin main --tags
   ```

**Gate**: Push succeeds. Verify with `git log --oneline -3`.

---

## Phase 7: Summary

```
=== RELEASE vNEW_VERSION COMPLETE ===

Version:      versionCode=XX, versionName="NEW_VERSION"
              Nexgo internal: "NEW_VERSION-nexgo"
Commit:       <hash> release(vNEW_VERSION): ...
Tag:          vNEW_VERSION

APKs:
  PAX:        iCloud/.../NEW_VERSION/production/avoqado-tpv-NEW_VERSION-production.apk  (XX MB)
  Nexgo:      iCloud/.../NEW_VERSION/nexgo/avoqado-tpv-NEW_VERSION-nexgo.apk            (XX MB)

Tests:        XXX passed, 0 failed
Pushed:       origin/main + tags

Next steps — PAX (Blumon):
1. Send production APK to Blumon for PAX re-signing
2. Wait 3-5 days for signed APK
3. Save PAX-signed APK to iCloud/.../NEW_VERSION/PAXFIRMADO/
4. Use INSTALL_VERSION from dashboard to deploy to PAX terminals

Next steps — Nexgo (AngelPay):
1. Nexgo APK can be installed directly (no re-sign needed)
2. Use ADB or INSTALL_VERSION to deploy to Nexgo terminals
```
