# Target-safe TPV release design

## Objective

Make it fail closed to build, version, tag, upload, force-update, or remotely install a PAX/Blumon APK in the Nexgo/AngelPay channel, or the reverse.

Claude may infer a release target from an unambiguous diff, but no production action may rely on inference alone. Every mutable release operation must carry a machine-validated target.

## Current risks

- All Android flavors inherit one `versionCode` and `versionName` from `defaultConfig`.
- `production` and `nexgoProd` currently share `com.jaac.avoqado_tpv`.
- The backend update catalog distinguishes `ANDROID_TPV` from Windows, but not PAX from Nexgo.
- Update lookup, specific-version lookup, heartbeat force updates, and HTTP 426 version gating can select an APK using only environment and version.
- The dashboard upload flow does not require a PAX/Nexgo target and only warns when supplied version metadata differs from the APK.
- The release instructions document PAX production but do not define a complete Nexgo production release.

## Non-negotiable invariants

1. Every Android build declares exactly one immutable app target: `PAX` or `NEXGO`.
2. Every production APK has a target-specific version track, artifact path, tag namespace, update channel, and signing policy.
3. A missing, unknown, shared, or mixed target stops release automation before version or Git mutation.
4. The backend never falls back from one modern Android target to another.
5. APK metadata supplied by an operator is never trusted over metadata extracted from the APK.
6. A tag is created only after the committed artifact has compiled and passed target verification.
7. Push, upload, activation, `FORCE`, and terminal installation remain separate explicit actions.
8. Legacy clients that do not report a target can receive only legacy-channel updates, never a modern PAX or Nexgo update.

## Chosen architecture

### 1. Android build identity

Add `BuildConfig.APP_TARGET` and corresponding non-secret manifest metadata to every flavor.

| Flavor | Target | Processor | Environment | Package ID |
|---|---|---|---|---|
| `sandbox` | `PAX` | `BLUMON` | `SANDBOX` | `com.jaac.avoqado_tpv.sandbox` |
| `production` | `PAX` | `BLUMON` | `PRODUCTION` | `com.jaac.avoqado_tpv` |
| `nexgo` | `NEXGO` | `ANGELPAY` | `SANDBOX` | `com.jaac.avoqado_tpv.sandbox` initially |
| `nexgoProd` | `NEXGO` | `ANGELPAY` | `PRODUCTION` | `com.jaac.avoqado_tpv` initially |

The first transition deliberately preserves the current package IDs. Changing a package ID creates a second Android application with isolated `SecureStorage`, Room databases, WorkManager jobs, authentication state, and local payment queues. Running old and new packages together could duplicate heartbeats, socket connections, remote commands, and offline synchronization. A separate Nexgo package is a later defense-in-depth change only after a dedicated data/coexistence migration, Firebase registration, and confirmation of AngelPay/Nexgo package and signature allowlists.

While package IDs remain shared, defense in depth comes from three independent checks: immutable build target metadata, target-aware backend channels, and a runtime compatibility gate. Activation and terminal-config responses compare the build's `APP_TARGET` with the existing normalized `Terminal.brand` (`PAX` or `NEXGO`). A mismatch blocks payments, workers, update installation, and remote commands and shows an incompatible-build error.

PAX and Nexgo versions move to separate properties:

- `PAX_VERSION_CODE`, `PAX_VERSION_NAME`
- `NEXGO_VERSION_CODE`, `NEXGO_VERSION_NAME`

PAX sandbox/production share the PAX track. Nexgo QA/production share the Nexgo track. Bumping one track must leave the other byte-for-byte unchanged.

### 2. Backend update identity

Retain `ANDROID_TPV` as a legacy-only platform and add:

- `ANDROID_PAX`
- `ANDROID_NEXGO`

Modern Android clients send `X-App-Target` on normal API traffic and send the matching platform on update requests. Heartbeats include the same target as an optional backward-compatible field.

The existing normalized `Terminal.brand` remains the device source of truth; no duplicate terminal-target column is introduced. A target-aware build reports `APP_TARGET`, and the backend cross-checks it against `Terminal.brand` whenever terminal identity is available. A conflict is rejected and logged for investigation instead of silently changing either value.

`AppUpdate` uniqueness becomes `(versionCode, environment, platform)`. Queries and storage paths include platform. A specific-version lookup requires platform. Cache keys for forced updates become `(environment, platform)`.

The following paths must all filter by the same resolved platform:

- latest update check;
- specific-version lookup;
- heartbeat `FORCE` update;
- HTTP 426 version gate;
- organization and superadmin version lists;
- remote `REQUEST_UPDATE` / `INSTALL_VERSION` selection.

Resolution is fail closed:

- explicit valid modern target -> matching modern channel only;
- no target -> `ANDROID_TPV` legacy channel only;
- conflicting header, query, terminal record, or APK metadata -> reject and audit;
- no cross-target fallback under any condition.

### 3. Dashboard upload and remote installation

The upload screen requires an app target. Package ID, target, environment, version, minimum SDK, checksum, and signing certificate are extracted from the APK. Only a distributable APK signed by the approved target certificate may be uploaded; locally compiled or vendor-pending candidates remain clearly marked as non-distributable.

The server rejects rather than warns when:

- supplied version differs from APK metadata;
- package ID does not match target/environment;
- target metadata conflicts with the selected channel;
- version already exists in that target/environment;
- certificate or ABI policy does not match the target;
- a Nexgo APK contains prohibited PAX native libraries;
- a PAX APK lacks required Blumon/PAX native libraries.

Version lists and remote-install selectors are filtered by the terminal's recorded target. An unknown-target terminal cannot receive a modern update command.

### 4. Local release guard

Introduce one mandatory entry point:

```bash
./scripts/tpv-release.sh prepare --target nexgo --bump patch
./scripts/tpv-release.sh verify --target nexgo
```

Claude determines the candidate target from the complete Git diff, then passes it explicitly. The script independently validates the diff and refuses contradictions.

Classification rules:

- AngelPay classes, Nexgo source sets, Nexgo SDK/config -> `NEXGO`;
- Blumon classes, sandbox/production payment implementations, PAX SDK/config -> `PAX`;
- shared runtime/build/database/navigation code -> `SHARED`;
- target-specific changes from both sides -> `MIXED`;
- documentation and changelog files do not establish a target.

`SHARED`, `MIXED`, unknown, unrelated dirty files, or conflicting staged/unstaged scopes abort before bumping. Claude must ask the user whether the intended release is PAX, Nexgo, or two separately validated releases.

Exact build mapping:

| Target | QA task | Production task | Tag |
|---|---|---|---|
| PAX | `assembleSandboxDebug` | `assembleProductionRelease` | `pax-v<version>` |
| Nexgo | `assembleNexgoDebug` | `assembleNexgoProdRelease` | `nexgo-v<version>` |

The verifier inspects the final APK rather than trusting the requested Gradle task. It validates package ID, `APP_TARGET`, processor, environment, version, ABI set, required/forbidden native libraries, signing state, checksum, and source commit. It writes a release manifest next to the APK.

### 5. Safe Git and release sequence

When the user explicitly requests “bump, commit, tag y compila”:

1. Inspect the complete diff and classify it.
2. Abort on ambiguity or unrelated changes.
3. Bump only the selected target's version.
4. Run target tests and a release compilation.
5. Stage only the validated release scope and target version file.
6. Commit with the target in the subject.
7. Rebuild and verify from the exact committed `HEAD`.
8. Create the target-specific annotated tag.
9. Report artifact path, SHA-256, package ID, target, version, and signature status.
10. Do not push, upload, activate, force-update, or install unless the user explicitly requests that separate action.

If compilation or verification fails, no tag is created. A failed pre-commit verification creates no commit. If the post-commit rebuild fails, the commit remains untagged and is reported as non-releasable.

## Migration and rollout

1. Inventory the deployed fleet by terminal brand, serial, app version, package ID, and signing certificate; resolve unknown/null brands.
2. Obtain and inspect one currently deployed PAX APK and one Nexgo APK, including certificate fingerprints and vendor signing flow.
3. Confirm AngelPay/Nexgo package/signature allowlist requirements and Firebase app registrations.
4. Deploy backend/schema support in shadow mode while preserving `ANDROID_TPV` legacy behavior. Shadow mode records which modern channel would be selected but never serves it.
5. Update dashboard target-aware upload/list/install behavior, with modern uploads and `FORCE` activation disabled.
6. Build target-aware bridge APKs without changing package IDs or activating modern update channels.
7. Test both bridge APKs on physical hardware, including activation compatibility, local data preservation, pending payment queues, payment, update checks, workers, and remote commands.
8. Move pilot devices to target-aware builds and confirm telemetry reports the correct target with no shadow mismatches.
9. Activate `ANDROID_PAX` and `ANDROID_NEXGO` channels separately for pilot audiences using `NONE`/`BANNER`, never `FORCE` initially.
10. Keep legacy rows non-`FORCE`; retire the legacy channel only after remaining devices are accounted for.
11. Evaluate a separate Nexgo package as a later project. It is not required for target-safe release channels.

No production modern-channel upload is permitted before steps 1-8 pass. No modern `FORCE` update is permitted until both target channels have completed a rollback-tested pilot cycle.

## Verification matrix

- AngelPay-only diff selects Nexgo, changes only Nexgo version, and cannot invoke a PAX release task.
- Blumon-only diff selects PAX, changes only PAX version, and cannot invoke a Nexgo release task.
- Shared-only and mixed diffs exit non-zero before changing versions.
- A PAX client never receives Nexgo updates through check, specific version, heartbeat, HTTP 426, or remote commands.
- A Nexgo client never receives PAX updates through the same paths.
- A legacy client never receives either modern target channel.
- A build whose `APP_TARGET` conflicts with `Terminal.brand` cannot activate, process payments, start background workers, install updates, or execute remote commands.
- Wrong package, target, environment, ABI, signature, version, or native library composition is rejected at upload.
- Dashboard version selectors contain only versions compatible with the selected terminal.
- Local dry-run and all implementation testing perform no commit, tag, push, upload, activation, or installation.

## Implementation constraint for this work session

Implementation and testing will remain uncommitted in all repositories. Existing user changes will be preserved. No tag, push, APK upload, update activation, or terminal installation will be performed.
