# Release Pipeline: Test, Build, Sign, Deploy, Push

You are executing the full Avoqado TPV release pipeline. Follow each phase strictly. If ANY phase fails, STOP and report the error — do NOT continue to the next phase.

**IMPORTANT**: Before starting, ask the user for the new version number (e.g., "1.8.0") if not provided as argument. If the user provides it as $ARGUMENTS, use that.

Version argument: $ARGUMENTS

---

## Phase 0: Pre-flight Checks

1. Read `app/build.gradle.kts` to get the CURRENT `versionCode` and `versionName`
2. Determine the NEW version:
   - If `$ARGUMENTS` is provided, use it as the new `versionName`
   - If not provided, ask the user what the new version should be
   - New `versionCode` = current versionCode + 1
3. Show the user:
   ```
   Current: versionCode=XX, versionName="X.Y.Z"
   New:     versionCode=XX+1, versionName="NEW_VERSION"
   ```
4. Check `git status` — warn if there are uncommitted changes that should be committed first
5. Check `CHANGELOG.md` — verify there are entries under `## [Unreleased]`. If empty, STOP and tell the user to add changelog entries first

---

## Phase 1: Testing

Run these in parallel:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew testSandboxDebugUnitTest --rerun-tasks
```
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew lint --continue
```

**Gate**: ALL tests must pass (0 failures). Lint must pass. If either fails, STOP and report.

---

## Phase 2: Version Bump

1. Edit `app/build.gradle.kts`:
   - Increment `versionCode` by 1
   - Set `versionName` to the new version
2. Update `CHANGELOG.md`:
   - Rename `## [Unreleased]` to `## [NEW_VERSION] - YYYY-MM-DD` (today's date)
   - Add a fresh empty `## [Unreleased]` section above it with empty Added/Changed/Fixed subsections
3. Compile check to verify version bump doesn't break anything:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 23)
   ./gradlew compileSandboxDebugKotlin
   ```

**Gate**: Compilation must succeed.

---

## Phase 3: Build Production APK

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew assembleProductionRelease
```

**Gate**: BUILD SUCCESSFUL. The unsigned APK must exist at:
`app/build/outputs/apk/production/release/app-production-release-unsigned.apk`

---

## Phase 4: Sign APK

Sign with apksigner (v2 scheme REQUIRED for targetSdk 34+):

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out /tmp/avoqado-tpv-NEW_VERSION-production-signed.apk \
  app/build/outputs/apk/production/release/app-production-release-unsigned.apk
```

Then verify the signature:
```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose /tmp/avoqado-tpv-NEW_VERSION-production-signed.apk
```

**Gate**: Must show `Verified using v2 scheme (APK Signature Scheme v2): true`

---

## Phase 5: Copy to iCloud

Create the version folder structure and copy:

```bash
ICLOUD_BASE="/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK"
VERSION="NEW_VERSION"

mkdir -p "${ICLOUD_BASE}/${VERSION}/production"
mkdir -p "${ICLOUD_BASE}/${VERSION}/sandbox"
mkdir -p "${ICLOUD_BASE}/${VERSION}/PAXFIRMADO"

cp /tmp/avoqado-tpv-${VERSION}-production-signed.apk \
   "${ICLOUD_BASE}/${VERSION}/production/avoqado-tpv-${VERSION}-production.apk"
```

**Gate**: File exists in iCloud folder. Verify with `ls -lh`.

---

## Phase 6: Git Commit + Tag + Push

1. Stage the changed files:
   ```bash
   git add app/build.gradle.kts CHANGELOG.md
   ```
   Also stage any other modified tracked files that are part of this release (check `git status`).
   Do NOT stage `.idea/` or `.serena/` files.

2. Create release commit (do NOT include Co-Authored-By):
   ```
   release(vNEW_VERSION): <brief summary of what's in the release>
   ```
   The commit message body should summarize the key changes from the CHANGELOG entries.

3. Create annotated tag:
   ```bash
   git tag -a vNEW_VERSION -m "vNEW_VERSION: <brief summary>"
   ```

4. Push to origin:
   ```bash
   git push origin main --tags
   ```

**Gate**: Push succeeds. Verify with `git log --oneline -3` and `git tag -l -n1 vNEW_VERSION`.

---

## Phase 7: Summary

Print a final summary:

```
=== RELEASE vNEW_VERSION COMPLETE ===

Version:    versionCode=XX, versionName="NEW_VERSION"
Commit:     <hash> release(vNEW_VERSION): ...
Tag:        vNEW_VERSION
APK:        iCloud/.../NEW_VERSION/production/avoqado-tpv-NEW_VERSION-production.apk
APK size:   XX MB
Tests:      XXX passed, 0 failed
Pushed:     origin/main + tags

Next steps:
1. Send APK to Blumon for PAX re-signing
2. Wait 3-5 days for signed APK
3. Save PAX-signed APK to iCloud/.../NEW_VERSION/PAXFIRMADO/
4. Use INSTALL_VERSION from dashboard to deploy to terminals
```
