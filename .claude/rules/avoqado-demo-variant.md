# "Demo Prod" build variant (`gymDemo`) — INTENTIONAL, do NOT "fix" it

⚠️ **Before you "clean up" anything that looks wrong in the `gymDemo` flavor, the `.demo`
google-services client, or the serial override in `DeviceInfoManager` — read this. It's all
deliberate.** It exists so the founder can demo a **real card swipe on a PAX without charging
real money**, while everything still reflects in the **production** backend (the gym demo venue
`avoqado-fitness`). Created 2026-07-07/08. Currently **UNCOMMITTED on `main`**.

## What looks weird but is on purpose

1. **`gymDemo` flavor sets `BLUMON_ENV="SAND"` but overrides `API_BASE_URL_DEV`/`SOCKET_URL_DEV`
   to `https://api.avoqado.io`** (`app/build.gradle.kts`). This is NOT a mistake. It's a
   deliberate decouple: `NetworkModule.provideBaseUrl()` returns `API_BASE_URL_DEV` when
   `BLUMON_ENV != "PROD"`, so pointing that field at prod makes the build hit the **PROD backend
   with the Blumon SANDBOX processor** (test cards, $0 real). Done this way precisely so
   `NetworkModule` and the other flavors are **untouched**. Do NOT "correct" it to use the ngrok
   URL or to `BLUMON_ENV="PROD"`.

2. **`applicationIdSuffix=".demo"` + a `com.jaac.avoqado_tpv.demo` client in
   `app/google-services.json` cloned from the `.sandbox` client (same `mobilesdk_app_id`).** The
   duplicate app_id is intentional — it lets the `.demo` build pass the Google Services plugin and
   coexist as its own installable app (3 icons: production, `.sandbox` dev→ngrok, `.demo` "Demo
   Prod"→prod) without registering a new Firebase app. Firebase metrics for the demo report under
   the sandbox app — fine for a demo. Do NOT delete the `.demo` client.

3. **`DeviceInfoManager.getSerialNumber()` has a `BuildConfig.DEBUG`-gated override** returning
   `BuildConfig.OVERRIDE_TERMINAL_SERIAL` when non-blank. This is how the demo build reports serial
   `AVQD-2841548418` (→ the prod gym terminal) instead of the hardware serial. It is **inert on
   every normal flavor** (`OVERRIDE_TERMINAL_SERIAL=""`) and **dead code in release**
   (`DEBUG=false`). Do NOT remove it as a "hardcoded serial" smell.

4. **`gymDemo` reuses `src/sandbox/java`+`res`** via `sourceSets` + `gymDemoImplementation` deps —
   same pattern as `tutorialEmu`/`nexgo`. Expected.

## Guarantees (so you don't worry about the normal builds)

- `production` / `sandbox` / `nexgo*` variants are **byte-identical** to before (verified via
  generated `BuildConfig`). The only shared-code touch is the DEBUG-gated hook above (a no-op for them).
- **`:app:assembleProductionRelease` was verified → BUILD SUCCESSFUL** (full R8/proguard/minify/lint),
  2026-07-08. `productionRelease` compiles exactly as before.
- **NEVER sign or ship `gymDemo`.** It's an internal demo build only (debug-signed). Build/install
  with `:app:assembleGymDemoDebug`.

## Backend counterpart (server side)

The prod backend deliberately holds a **SANDBOX** Blumon `MerchantAccount` (posId 387, serial
2841548418) + an ACTIVE `Terminal AVQD-2841548418` on venue `avoqado-fitness`. Sandbox transactions
there will **not reconcile** with Blumon PROD — that is EXPECTED, not a bug. Full detail + the
"do not fix" guarantees: `avoqado-server/docs/guides/VENUE_CREATION_GUIDE.md` §7b and the server
memory note `avoqado-fitness-prod-sandbox-tpv`.
