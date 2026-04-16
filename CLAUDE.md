# CLAUDE.md - Avoqado TPV (Android POS)

## How This Configuration Works

| Layer | Path | Loaded | Purpose |
|-------|------|--------|---------|
| This file | `CLAUDE.md` | Always | Router + essentials |
| Rules | `.claude/rules/*.md` | Auto, every session | Mandatory coding rules |
| Docs | `docs/` | On demand | Detailed guides and references |

**Rules auto-load** — you don't need to read them manually.

When rules conflict: `.claude/rules/` wins > this file > `docs/`

**Maintaining this file:** Short rules (1-3 lines) go directly here. Detailed content (code examples, tables, >10 lines) goes in `docs/` or `.claude/rules/`. Keep this file under ~200 lines — it loads every session.

---

## Identity & Tech Stack

**Avoqado TPV** — Android POS app for PAX payment terminals. Multi-tenant restaurant/retail management.

Kotlin + Jetpack Compose | Clean Architecture (Presentation -> Domain -> Data) | Hilt DI | Room DB | Blumon PAX SDK | Socket.IO | EncryptedSharedPreferences | 1GB RAM target (PAX A80)

## Firebase Crashlytics MCP (Direct Access)

**ALWAYS check Crashlytics when:** payment errors are reported, app crashes, connectivity issues, Blumon SDK failures, or any production incident.

Firebase MCP tools are available via `mcp__plugin_firebase_firebase__crashlytics_*`. No screenshots needed.

```
# App IDs
Production: 1:219752736783:android:d09cd5eb6162e7ee52db7a
Sandbox:    1:219752736783:android:aa8d57cc3022eb9c52db7a
Project:    avoqado-d0a24

# Quick queries
crashlytics_get_report(appId, report="topIssues")           # Top crashes
crashlytics_list_events(appId, filter={issueErrorTypes:["FATAL"]})  # Recent crashes
crashlytics_get_issue(appId, issueId="<hex>")               # Issue detail
crashlytics_list_events(appId, filter={issueId:"<hex>"})    # Events for issue
```

**Proactive rule**: When investigating ANY payment bug, query Crashlytics FIRST before asking the user for screenshots. Check both FATAL and NON_FATAL events filtered by the relevant time window.

## Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)  # Required

./gradlew installSandboxDebug       # Dev build (90% of the time)
./gradlew assembleProductionRelease  # Production APK
./gradlew compileDebugKotlin         # Quick compile check
./gradlew lint --continue            # Lint (must pass before commit)

# Unit tests (220 tests — run before commits and after refactors)
./gradlew testSandboxDebugUnitTest                    # All tests
./gradlew testSandboxDebugUnitTest --tests "com.jaac.avoqado_tpv.ClassName"  # Single class

# ADB monitoring (mandatory after every change)
adb logcat -c && adb logcat -s PaymentViewModel,MenuViewModel | grep -iE "keyword"

# Log capture for testing
./scripts/capture-logs.sh <feature> start|stop|status|read
```

## Project Structure

```
app/src/main/java/com/jaac/avoqado_tpv/
├── presentation/   # ViewModels + Compose screens
├── domain/         # Use cases + business logic
├── data/           # Repositories, Room, API clients
├── di/             # Hilt modules
└── util/           # Extensions, helpers

app/src/sandbox/    # Sandbox-specific (PaymentViewModel, BlumonInitializer)
app/src/production/ # Production-specific (same files, different SDK URLs)
```

## Critical Rules (brief — details in `.claude/rules/`)

1. **Changelog**: EVERY modification must be logged in `CHANGELOG.md` under `[Unreleased]`. No exceptions. Rotate at ~50KB. -> `changelog-policy.md`
2. **PaymentViewModel safety**: 8 features share payment code. Test ALL flows, sync both variants. -> `critical-warnings.md`
3. **Blumon distinction**: Always "Blumon TPV" or "Blumon E-commerce", never just "Blumon". -> `critical-warnings.md`
4. **Build variants**: Sync sandbox/ and production/ files. Production uses REAL money. -> `critical-warnings.md`
5. **Room migrations**: ALWAYS create migration when adding @Entity fields. Missing = 100% crash. -> `critical-warnings.md`
6. **Auth**: Use `authRepository.getAuthContext()`. No `req.user` — that's backend. -> `critical-warnings.md`
7. **Tenant isolation**: Every DB query filters by `venueId`. No exceptions. -> `critical-warnings.md`
8. **Money**: `BigDecimal`, never Float. -> `critical-warnings.md`
9. **Performance**: Paginate everything. 1GB RAM target. -> `critical-warnings.md`
10. **API paths**: Base URL already has `/api/v1/`. Don't add `/v1/` again. -> `critical-warnings.md`
11. **Permissions**: New features need backend + TPV + dashboard permissions. Exact name match. -> `release-and-git.md`
12. **Release build**: apksigner v2 (not jarsigner), save to iCloud, send to Blumon. -> `release-and-git.md`
13. **Version bumps**: "Can user do something new?" Yes=MINOR, No=PATCH. -> `release-and-git.md`
14. **Cross-repo**: Backend deploys in minutes, TPV takes 3-5 days. Backend ALWAYS supports old versions. -> `release-and-git.md`
15. **Git**: Never commit without permission. No `Co-Authored-By`. -> `release-and-git.md`
16. **ADB monitoring**: Mandatory after every change. Log capture for testing. -> `testing-and-adb.md`
17. **Unit tests**: 220 tests, 0 failures. Run before commits and after refactors. -> [unit testing guide](docs/UNIT_TESTING_GUIDE.md)
18. **Timezone**: Never use `ZoneId.systemDefault()`. Use `VenueTimeZone.get(secureStorage)` or `ZoneId.of("America/Mexico_City")`. -> `critical-warnings.md`
19. **PAX A910S target device**: 720x1280px, 320dpi (xhdpi), 360x640dp. Always add `@Preview(widthDp=360, heightDp=640)` with banners when creating/modifying screens. No fixed sizes >80dp for buttons. -> `critical-warnings.md`

## Build Variants

Same branch (`main`), different Gradle configurations:

| Variant | Command | Use | Processor |
|---------|---------|-----|-----------|
| sandboxDebug | `./gradlew installSandboxDebug` | Daily dev — PAX (90%) | Blumon sandbox |
| productionDebug | `./gradlew installProductionDebug` | Debug prod issues — PAX | Blumon REAL money |
| productionRelease | `./gradlew assembleProductionRelease` | Final APK — PAX | Blumon REAL money |
| **nexgoDebug** | **`./gradlew installNexgoDebug`** | **Nexgo N86/N5 dev** | **AngelPay QA** |
| tutorialEmuDebug | `./gradlew installTutorialEmuDebug` | Emulator/screenshots | None |

## Documentation Router

### Auto-loaded rules (`.claude/rules/`)
- `critical-warnings.md` — PaymentVM safety, Blumon, variants, Room migrations, auth, money, performance, API paths, timezone
- `release-and-git.md` — APK signing, version bumps, permissions, cross-repo, git workflow
- `testing-and-adb.md` — Regression prevention, ADB monitoring, log capture

### On-demand docs (`docs/`)

**Architecture & Patterns:**
[theme & color system](docs/THEME_COLOR_SYSTEM.md) | [Kotlin best practices](docs/KOTLIN_BEST_PRACTICES.md) | [decision matrix](docs/DECISION_MATRIX.md) | [domain rules](docs/DOMAIN_RULES.md)

**Payments & Processors (READ THESE before touching payment code):**
[Blumon SDK integration](docs/BLUMON_INTEGRATION_COMPLETE.md) | [AngelPay app-to-app](docs/ANGELPAY_INTEGRATION.md) | [multi-merchant reconciliation](docs/PAYMENT_RECONCILIATION.md) | [payment state machine](docs/PAYMENT_STATE_MACHINE.md) | [payment flow origin](docs/PAYMENT_FLOW_ORIGIN.md) | [payment session](docs/PAYMENT_SESSION.md) | [crypto payments](docs/CRYPTO_PAYMENTS.md) | [production deployment](docs/PRODUCTION_DEPLOYMENT.md) | [production build](docs/PRODUCTION_BUILD_GUIDE.md) | [TPV commands](docs/TPV_COMMAND_FLOW.md)

**Core Architecture:**
[navigation](docs/NAVIGATION_ARCHITECTURE.md) | [SecureStorage](docs/SECURE_STORAGE_GUIDE.md) | [offline sync](docs/OFFLINE_SYNC_ARCHITECTURE.md) | [Room schema](docs/ROOM_DATABASE_SCHEMA.md) | [network interceptors](docs/NETWORK_INTERCEPTORS.md) | [session lifecycle](docs/SESSION_LIFECYCLE.md) | [Hilt DI modules](docs/HILT_DI_MODULES.md) | [error handling](docs/ERROR_HANDLING_PATTERN.md) | [connection management](docs/CONNECTION_MANAGEMENT.md) | [heartbeat & network](docs/HEARTBEAT_AND_NETWORK_MONITORING.md) | [device health](docs/DEVICE_HEALTH_MONITORING.md) | [device identification](docs/DEVICE_IDENTIFICATION.md) | [ProGuard/R8](docs/PROGUARD_AND_OBFUSCATION.md)

**Features:**
[modules system](docs/MODULES_SYSTEM.md) | [force update](docs/FORCE_UPDATE_SYSTEM.md) | [self-update](docs/SELF_UPDATE_SYSTEM.md) | [cross-repo release](docs/CROSS_REPO_RELEASE_FLOW.md) | [attendance](docs/ATTENDANCE_VERIFICATION.md) | [location services](docs/LOCATION_SERVICES.md) | [pre-payment verification](docs/PRE_PAYMENT_VERIFICATION.md) | [camera/verification](docs/CAMERA_VERIFICATION_SYSTEM.md) | [master TOTP](docs/MASTER_TOTP_LOGIN.md) | [receipt printing](docs/RECEIPT_PRINTING.md) | [ordering offline](docs/ORDERING_OFFLINE.md) | [pay later](docs/PAY_LATER_README.md) | [pay later implementation](docs/PAY_LATER_IMPLEMENTATION.md) | [pay later testing](docs/PAY_LATER_TESTING_CHECKLIST.md) | [BLE payments](docs/BLE_PAYMENT_IOS_APP.md) | [BLE queue](docs/BLE_PAYMENT_QUEUE.md) | [kiosk staff session](docs/KIOSK_STAFF_SESSION.md)

**Development:**
[development workflow](docs/DEVELOPMENT_WORKFLOW.md) | [performance](docs/PERFORMANCE_GUIDE.md) | [UI responsive](docs/UI_RESPONSIVE_GUIDE.md) | [testing](docs/TESTING_GUIDE.md) | [unit testing](docs/UNIT_TESTING_GUIDE.md) | [ADB monitoring](docs/ADB_MONITORING_GUIDE.md) | [security](docs/SECURITY_CHECKLIST.md) | [Compose keyboard](docs/COMPOSE_KEYBOARD_HANDLING.md) | [Socket.IO](docs/SOCKET_IO_IMPLEMENTATION.md) | [Socket.IO testing](docs/SOCKET_IO_TESTING.md) | [local-first sync](docs/LOCAL_FIRST_SYNC_PATTERNS.md) | [observability](docs/OBSERVABILITY_GUIDE.md) | [observability testing](docs/OBSERVABILITY_TESTING.md)

**Cross-repo:** [avoqado-server/docs/README.md](../avoqado-server/docs/README.md) — central hub for architecture, DB, payments, inventory backend

## Cross-Repo Compatibility

| Repo | Deploy Time |
|------|-------------|
| Backend (avoqado-server) | Minutes |
| Dashboard (avoqado-web-dashboard) | Minutes |
| **TPV (this repo)** | **3-5 days** (PAX signing) |

Backend ALWAYS supports old TPV versions. Never remove API fields. New fields optional with defaults. Deploy backend first, wait stable, then APK. Run `./scripts/check-cross-repo.sh` before production APK.

## Blumon SDK Vendor Documentation (iCloud)

Official Blumon/PAX SDK docs and AARs are stored in iCloud:

```
~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/
├── production/
│   ├── SDK-PAX-1.11.0.2-DocV4.docx       # Official SDK API reference
│   ├── NeptuneLiteApi_V4.10.00.zip        # PAX Neptune API
│   └── BP_BIBLIOTECAS_MEX_PROD/           # Production libraries
└── dev/
    ├── SDK-PAX-1.11.0.2-DocV4.pdf         # Same docs in PDF
    ├── lib-services-BP-SAND_1601.aar       # Sandbox AAR
    ├── ActualizacionSDK_PAX/              # SDK updates
    └── demo-pax_blumon.apk               # Blumon demo app
```

Read `SDK-PAX-1.11.0.2-DocV4` before modifying Blumon SDK integration code.

## AngelPay Vendor Documentation

```
~/Downloads/
├── Manual de Integración App Angel Pay-v1-2.pdf   # v1.2 (17/03/2026) — latest
└── angel-pay-consumer/                            # Example integration app (Kotlin)

~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/
└── Manual de Integración App to App.pdf           # v1.0 (original)
```

Read `Manual de Integración App Angel Pay-v1-2.pdf` before modifying AngelPay integration code.
AngelPay contact: Rafael Calderon (technical), Norman Saldana (authorization).

## Self-Update System (v1.7.8)

**APK Installation** — `ApkInstaller.kt` (PackageInstaller Session API + PAX SDK fallback):
- Primary: Android PackageInstaller Session API — streams APK bytes via IPC, no FUSE issues on Android 10+
- Fallback: PAX SDK `ISys.installApp()` — only works on Android 9 and below
- Result receiver: `InstallResultReceiver.kt` (dynamic registration, Hilt EntryPoint)
- Observability: Every step logged to Crashlytics + backend `POST tpv/report-install-attempt`
- **VERIFIED on PAX A910S** (sandbox Android 10, production) — 2026-02-26

**Deploying to old terminals** (v1.6.0+): Use `INSTALL_VERSION` command from dashboard. This command uses `Intent(ACTION_VIEW)` + FileProvider as fallback (NOT `ISys.installApp()`), which works on Android 10+. Terminals v1.2-v1.5.x require PAXSTORE.

**Backend**: `GET /tpv/get-version` is public (no auth) — allows INSTALL_VERSION to work without active session. `GET /tpv/check-update` also public.

**Dashboard**: `INSTALL_VERSION` works offline — command queued, delivered when terminal reconnects via heartbeat.

## Pre-Commit Checklist

See `.claude/rules/testing-and-adb.md` for full checklist. Minimum:

- [ ] `./gradlew testSandboxDebugUnitTest` passes (220 tests, 0 failures)
- [ ] `./gradlew compileDebugKotlin` passes
- [ ] `./gradlew lint --continue` passes
- [ ] Room migrations created for @Entity changes
- [ ] Variant files synced (sandbox/ + production/)
