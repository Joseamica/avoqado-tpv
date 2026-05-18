# AngelPay SDK 1.0.5 Upgrade + Multi-Merchant Runtime Switching — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish AngelPay SDK 1.0.5 multi-merchant integration across `avoqado-server` + `avoqado-web-dashboard` + `avoqado-tpv` so a Nexgo terminal operator can authenticate once and switch between merchants per transaction, while leaving the production Blumon/PAX flow untouched.

**Architecture:** Backend stores per-venue `AngelPayUserAccount` with encrypted PIN and exposes it via the existing `/tpv/terminals/:serial/config` endpoint. TPV reuses the already-integrated `AngelPaySdkGateway`, extends it with `getUserMerchants()` + `switchMerchant()`, and adds `AngelPayAuthRepository` + `AngelPayMerchantRepository` as the single source of truth for auth state and active merchant. Dashboard adds CRUD for `AngelPayUserAccount` and AngelPay-specific `MerchantAccount` fields. Device-processor coupling is enforced at 4 backend points + a TPV runtime filter. The PIN never lands on disk or in logs.

**Tech Stack:** Prisma (PostgreSQL) + Express + TypeScript (backend); React + TypeScript + Tailwind (dashboard); Kotlin + Jetpack Compose + Hilt + Room (TPV); AngelPay SDK 1.0.5 AAR (Nexgo).

**Source spec:** `docs/superpowers/specs/2026-05-14-angelpay-sdk-1.0.5-migration-design.md` (v2.5)

---

## File Structure (binding inventory)

### Backend (`avoqado-server/`)

**Create:**
- `prisma/migrations/2026XXXXXXX_add_angelpay_user_account/migration.sql`
- `prisma/migrations/2026XXXXXXX_add_angelpay_display_fields/migration.sql`
- `prisma/migrations/2026XXXXXXX_normalize_terminal_brand/migration.sql`
- `src/lib/providerDeviceCompatibility.ts`
- `src/services/superadmin/angelpayUserAccount.service.ts`
- `src/controllers/superadmin/angelpayUserAccount.controller.ts`
- `src/controllers/tpv/angelpayValidation.tpv.controller.ts` (`/tpv/angelpay/report-validation`, `/tpv/angelpay/report-merchant-switch`)
- `tests/services/superadmin/angelpayUserAccount.service.test.ts`
- `tests/services/superadmin/merchantAccount.deviceCompatibility.test.ts`
- `tests/controllers/tpv/terminal.tpv.controller.angelpay.test.ts`

**Modify:**
- `prisma/schema.prisma` (add `AngelPayUserAccount` model, `AngelPayAccountStatus` enum, `MerchantAccount.angelpayAffiliation` + `angelpayMerchantName`, `Venue.angelpayUserAccount` relation)
- `prisma/seed.ts` (add `PaymentProvider.ANGELPAY` row with empty configSchema)
- `src/services/superadmin/merchantAccount.service.ts` (AngelPay-aware create + 4-point device compatibility checks)
- `src/controllers/tpv/terminal.tpv.controller.ts` (filter merchants by brand compatibility; include `angelpayAuth` payload when applicable)
- `src/controllers/superadmin/terminal.controller.ts` (validate brand enum at create/update; brand-change warning path)
- `src/routes/superadmin.routes.ts` (mount new AngelPay user account routes)
- `src/routes/tpv.routes.ts` (mount new TPV validation report routes)

### Dashboard (`avoqado-web-dashboard/`)

**Create:**
- `src/pages/Superadmin/Venues/[venueId]/AngelPayAccount.tsx`
- `src/components/MerchantAccountForm/AngelPayFields.tsx`
- `src/components/MerchantAccountForm/DeviceCompatibilityBanner.tsx`
- `src/api/angelpayUserAccount.api.ts`
- `tests/pages/Superadmin/AngelPayAccount.test.tsx`

**Modify:**
- `src/pages/Superadmin/MerchantAccounts.tsx` (provider dropdown shows AngelPayFields when ANGELPAY; banner with compatible terminal count; reject UI if 0 compatible terminals)
- `src/pages/Superadmin/Terminals.tsx` (brand becomes dropdown; brand-change warning dialog)
- `src/pages/Superadmin/Terminals/[id]/Assignments.tsx` (filter merchant dropdown by terminal.brand)
- `src/lib/providerDeviceCompatibility.ts` (duplicate of backend constant — kept in sync via test)

### TPV (`avoqado-tpv/`)

**Create:**
- `app/libs/angelpaySDK-v1.0.5-fat-release.aar` (binary, swap in)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayAuthRepository.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayMerchantRepository.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayAuthState.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayCredentialResolver.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayConfigValidator.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayMerchantCacheDao.kt` (Room)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayMerchantCacheEntity.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayErrorMapper.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayAuthBanner.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayMerchantSwitcherSheet.kt`
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/PaymentStateProvider.kt` (interface for D2 cross-cutting access)
- `app/src/test/java/.../AngelPayAuthRepositoryTest.kt`
- `app/src/test/java/.../AngelPayMerchantRepositoryTest.kt`
- `app/src/test/java/.../AngelPaySdkGatewayTest.kt`
- `app/src/test/java/.../AngelPayConfigValidationTest.kt`
- `app/src/test/java/.../AngelPayCredentialResolverTest.kt`
- `app/src/test/java/.../AngelPayPinHandlingTest.kt`
- `app/src/test/java/.../NexgoFlavorHiltGraphTest.kt`
- `app/src/test/java/.../PaxStartupSmokeTest.kt`
- `app/src/test/java/.../AngelPayErrorMapperTest.kt`

**Modify:**
- `app/build.gradle.kts` (swap AAR 1.0.4 → 1.0.5; remove `ANGELPAY_QA_*` BuildConfig fields from `nexgo` + `nexgoProd`; add `SUPPORTED_PROCESSOR` + `ANGELPAY_ENV` BuildConfig per flavor; `packagingOptions` for Nexgo to exclude PAX `.so`; comment on stub Blumon AAR)
- `app/src/main/java/com/jaac/avoqado_tpv/AvoqadoTPVApplication.kt` (delete `secureStorage.saveAngelPayCredentials(...)` block at lines 153-160; replace inline `AngelPaySDK.initialize(...)` with `angelPaySdkGatewayProvider.get().ensureInitialized(...)` per D4 Pattern A)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPaySdkGateway.kt` (add `getUserMerchants()`, `switchMerchant()`, source creds from `TerminalConfigRepository`)
- `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/TerminalConfigDto.kt` (additive fields on `MerchantAccountDto`: `externalMerchantId`, `isActive`, `angelpayAffiliation`, `angelpayMerchantName`; add `TerminalConfigDto.angelpayAuth: AngelPayAuthDto?`; add `AngelPayAuthDto` data class)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/MerchantAccount.kt` (additive: `externalMerchantId`, `isActive`, `angelpayAffiliation`, `angelpayMerchantName`, `requireAngelpayMerchantId()` helper)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` (extend `AngelPayPaymentState` with `Switching` + `Charging`; inject `AngelPayAuthRepository` + `AngelPayMerchantRepository`; rewrite `selectMerchant()` to call `completeInitialSelection` or `switchActiveMerchant`; add payment-time guard in `startPayment()`)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt` (mount `AngelPayAuthBanner` + `AngelPayMerchantSwitcherSheet`)
- `app/src/main/java/com/jaac/avoqado_tpv/core/data/storage/SecureStorage.kt` (delete `getAngelPayCredentials()`, `saveAngelPayCredentials()`)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayCredentials.kt` (delete file once refactor lands)
- `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/RedactingLoggingInterceptor.kt` (add `pin` field redaction regex; create file if not present)
- `app/src/main/java/com/jaac/avoqado_tpv/core/data/repository/TerminalConfigRepository.kt` (strip `angelpayAuth` from cached-to-disk copy; keep in-memory copy)
- `app/src/main/java/com/jaac/avoqado_tpv/di/AppModule.kt` (provide `AngelPayMerchantCacheDao`, bind `PaymentStateProvider`, gated by `SUPPORTED_PROCESSOR`)
- `app/src/main/java/com/jaac/avoqado_tpv/core/data/database/AvoqadoDatabase.kt` (add `AngelPayMerchantCacheEntity` + migration)

**Touch (CHANGELOG):**
- `CHANGELOG.md` (entry per Task per `.claude/rules/changelog-policy.md`)

---

## Phase Map

```
Phase 0 — Prep                 (Tasks 1–3)    ~2 days
Phase 1 — Backend              (Tasks 4–14)   ~3 days
Phase 2 — Dashboard            (Tasks 15–20)  ~2 days
Phase 3 — TPV                  (Tasks 21–38)  ~5 days
Phase 4 — QA on real Nexgo     (Task 39)      ~3 days
Phase 5 — Production rollout   (Task 40)      ~1 day + 3-5 day Nexgo signing wait
```

**Cross-repo deploy order (per CLAUDE.md):** Backend deployed first → wait stable 24h → Dashboard deployed → wait stable 24h → TPV APK signed + deployed.

---

# Phase 0 — Prep

## Task 1: Swap AngelPay AAR 1.0.4 → 1.0.5 and verify Blumon regression

**Files:**
- Place: `app/libs/angelpaySDK-v1.0.5-fat-release.aar` (already at `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/dev/sdk_propio/1.0.5/angelpaySDK-v1.0.5-fat-release.aar`)
- Modify: `app/build.gradle.kts:396-398`
- Delete: `app/libs/angelpaySDK-v1.0.4-fat-release.aar` (after verification)

- [ ] **Step 1: Copy the new AAR into the project**

```bash
cp "$HOME/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/dev/sdk_propio/1.0.5/angelpaySDK-v1.0.5-fat-release.aar" \
   /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/libs/
ls -la /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/libs/angelpaySDK*
```

Expected: both `angelpaySDK-v1.0.4-fat-release.aar` and `angelpaySDK-v1.0.5-fat-release.aar` listed.

- [ ] **Step 2: Update Gradle references to point at 1.0.5**

In `app/build.gradle.kts`, replace lines 396-398:

```kotlin
compileOnly(files("libs/angelpaySDK-v1.0.5-fat-release.aar"))
"nexgoImplementation"(files("libs/angelpaySDK-v1.0.5-fat-release.aar"))
"nexgoProdImplementation"(files("libs/angelpaySDK-v1.0.5-fat-release.aar"))
```

- [ ] **Step 3: Compile Nexgo flavor to verify 1.0.5 API surface still satisfies the gateway**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew compileNexgoDebugKotlin
```

Expected: BUILD SUCCESSFUL. If symbols moved (vendor claims 1.0.5 is backwards-compatible), surface the exact symbol error and stop.

- [ ] **Step 4: Run the existing Blumon regression suite (must stay green per §17.6)**

```bash
./gradlew testSandboxDebugUnitTest --rerun-tasks
```

Expected: 220 tests, 0 failures, 0 errors.

If anything regresses, the AAR swap is the root cause until proven otherwise. Investigate; do not proceed.

- [ ] **Step 5: Delete the old AAR and commit**

```bash
rm app/libs/angelpaySDK-v1.0.4-fat-release.aar
git add app/libs/ app/build.gradle.kts
git commit -m "chore(tpv): swap AngelPay SDK 1.0.4 → 1.0.5, regression suite green"
```

Update `CHANGELOG.md` under `[Unreleased]` → `### Changed`:

```markdown
- **AngelPay SDK**: upgraded embedded AAR from 1.0.4 to 1.0.5 (vendor release May 2026). Existing entry points (initialize, authenticateSimple, selectMerchant, createPaymentIntent, history/cancel/refund/print/ticket) remain wire-compatible. New entry points `getUserMerchants()` and `switchMerchant()` wired in later tasks.
```

---

## Task 2: Rotate compromised AngelPay QA credentials (vendor coordination)

**Files:** none (operational task)

- [ ] **Step 1: Email AngelPay technical contact**

```
To: AngelPay Soporte Técnico
Subject: Rotación de credenciales QA — usuario `ventas@avoqado.io`

Hola equipo,

Las credenciales QA del usuario ventas@avoqado.io estuvieron embebidas en el código
fuente del cliente TPV (Avoqado) por error. Solicitamos rotación del PIN a la
brevedad para mitigar exposición.

El cambio no afecta su backend; solo necesitamos un PIN nuevo de 6 dígitos.

Saludos,
```

- [ ] **Step 2: Note the rotation in the credentials vault**

Update `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/dev/credentials.md` (create if missing — never commit) with the new PIN and a dated rotation note.

- [ ] **Step 3: Confirm sandbox/Nexgo still authenticates after rotation**

Quick smoke before continuing:

```bash
./gradlew installNexgoDebug
adb logcat -c && adb logcat -s AngelPaySdkGateway,AngelPayAuthRepository | grep -iE "auth|merchant" &
```

Launch the app, trigger any code path that today calls `authenticateSimple`. Expected: success log; if fail, the rotation is the cause until proven otherwise.

---

## Task 3: Create the empty Phase 1 branch and document baseline

**Files:**
- Modify: `CHANGELOG.md` (note Phase 1 starting)

- [ ] **Step 1: Cut a feature branch off `main` in each repo**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server && git checkout -b feat/angelpay-multimerchant-backend
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard && git checkout -b feat/angelpay-multimerchant-dashboard
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv && git checkout -b feat/angelpay-multimerchant-tpv
```

- [ ] **Step 2: Run baseline tests in each repo**

Backend:
```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server && npm test
```

Dashboard:
```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard && npm test -- --run
```

TPV (already done in Task 1):
```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv && ./gradlew testSandboxDebugUnitTest --rerun-tasks
```

Record baselines:
- Backend: ___ tests, ___ failures
- Dashboard: ___ tests, ___ failures
- TPV: 220 tests, 0 failures (per CLAUDE.md)

Any pre-existing failures are tracked separately and not introduced by this work.

- [ ] **Step 3: Commit Task 1 + Task 2 notes**

The repo state should reflect the AAR swap (TPV) but no other change yet. The Phase 1 branch in backend is empty. We start Phase 1 in Task 4.

---

# Phase 1 — Backend

## Task 4: Add `AngelPayUserAccount` schema + status enum (D3)

**Files:**
- Modify: `avoqado-server/prisma/schema.prisma`
- Create: `avoqado-server/prisma/migrations/2026XXXXXXX_add_angelpay_user_account/migration.sql` (generated by Prisma)

- [ ] **Step 1: Write the schema definition**

Add to `prisma/schema.prisma`:

```prisma
enum AngelPayAccountStatus {
  PENDING_PIN
  ACTIVE
  PIN_ROTATION_REQUIRED
  SUSPENDED
  DELETED
}

model AngelPayUserAccount {
  id                String                  @id @default(cuid())
  venueId           String                  @unique
  venue             Venue                   @relation(fields: [venueId], references: [id], onDelete: Cascade)

  email             String
  pinEncrypted      Json                    // { encrypted: hex, iv: hex } AES-256-CBC, same shape as MerchantAccount.credentialsEncrypted
  environment       String                  @default("QA")

  status            AngelPayAccountStatus   @default(PENDING_PIN)
  statusChangedAt   DateTime?
  statusChangedBy   String?
  statusReason      String?

  externalUserId    Int?
  lastValidatedAt   DateTime?
  lastValidationErr String?

  createdAt         DateTime                @default(now())
  updatedAt         DateTime                @updatedAt
  createdBy         String?

  @@index([venueId])
  @@index([status])
}
```

Add the back-relation on `Venue`:

```prisma
model Venue {
  // ...existing fields...
  angelpayUserAccount AngelPayUserAccount?
}
```

- [ ] **Step 2: Generate the migration**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npx prisma migrate dev --name add_angelpay_user_account
```

Expected: migration file created under `prisma/migrations/`. Inspect for unexpected drops; should only add the new table, enum, and FK.

- [ ] **Step 3: Run the generated migration against test DB**

```bash
npx prisma migrate reset --force  # in dev/test env only
```

Expected: all migrations apply cleanly; seeds run; no errors.

- [ ] **Step 4: Regenerate Prisma client**

```bash
npx prisma generate
```

- [ ] **Step 5: Commit**

```bash
git add prisma/schema.prisma prisma/migrations/
git commit -m "feat(backend): add AngelPayUserAccount model with status enum (D3)"
```

Update `CHANGELOG.md`:

```markdown
### **Added**
- **Backend schema**: `AngelPayUserAccount` model + `AngelPayAccountStatus` enum (`PENDING_PIN | ACTIVE | PIN_ROTATION_REQUIRED | SUSPENDED | DELETED`) for per-venue AngelPay credential storage.
```

---

## Task 5: Add `MerchantAccount` AngelPay display fields (additive)

**Files:**
- Modify: `avoqado-server/prisma/schema.prisma`
- Create: `avoqado-server/prisma/migrations/2026XXXXXXX_add_angelpay_display_fields/migration.sql`

- [ ] **Step 1: Add fields to MerchantAccount**

In `prisma/schema.prisma`, find `model MerchantAccount` and add:

```prisma
model MerchantAccount {
  // ...existing fields...
  angelpayAffiliation  String?
  angelpayMerchantName String?
}
```

Do NOT touch `externalMerchantId` or `credentialsEncrypted`. Those are reused per spec §3.2.

- [ ] **Step 2: Generate migration**

```bash
npx prisma migrate dev --name add_angelpay_display_fields
```

- [ ] **Step 3: Verify migration is additive only**

```bash
cat prisma/migrations/*add_angelpay_display_fields*/migration.sql
```

Expected: two `ALTER TABLE ... ADD COLUMN` statements only. No `DROP` or `ALTER COLUMN` on existing fields.

- [ ] **Step 4: Apply and regenerate**

```bash
npx prisma migrate reset --force
npx prisma generate
```

- [ ] **Step 5: Commit**

```bash
git add prisma/schema.prisma prisma/migrations/
git commit -m "feat(backend): add optional AngelPay display fields to MerchantAccount"
```

---

## Task 6: Normalize `Terminal.brand` values via data migration

**Files:**
- Create: `avoqado-server/prisma/migrations/2026XXXXXXX_normalize_terminal_brand/migration.sql` (handwritten data migration)

- [ ] **Step 1: Inspect current brand values**

```bash
psql "$DATABASE_URL" -c "SELECT brand, COUNT(*) FROM \"Terminal\" WHERE brand IS NOT NULL GROUP BY brand ORDER BY COUNT(*) DESC;"
```

Record the result so the migration handles every case.

- [ ] **Step 2: Generate the migration directory and write SQL**

```bash
npx prisma migrate dev --create-only --name normalize_terminal_brand
```

Replace the generated empty `migration.sql` with:

```sql
-- Normalize Terminal.brand to canonical values: PAX | NEXGO | INGENICO | VERIFONE
-- Any unknown brand is preserved as-is (and surfaces in app logs for manual cleanup).

UPDATE "Terminal" SET "brand" = 'PAX'        WHERE LOWER(TRIM("brand")) IN ('pax', 'pax mobile', 'paxa910s', 'pax a910s', 'pax a80', 'pax a90');
UPDATE "Terminal" SET "brand" = 'NEXGO'      WHERE LOWER(TRIM("brand")) IN ('nexgo', 'nexgo n86', 'nexgo n62', 'n86', 'n62');
UPDATE "Terminal" SET "brand" = 'INGENICO'   WHERE LOWER(TRIM("brand")) IN ('ingenico');
UPDATE "Terminal" SET "brand" = 'VERIFONE'   WHERE LOWER(TRIM("brand")) IN ('verifone');
```

- [ ] **Step 3: Apply migration**

```bash
npx prisma migrate dev
```

- [ ] **Step 4: Verify no orphan values remain**

```bash
psql "$DATABASE_URL" -c "SELECT brand, COUNT(*) FROM \"Terminal\" WHERE brand IS NOT NULL AND brand NOT IN ('PAX','NEXGO','INGENICO','VERIFONE') GROUP BY brand;"
```

Expected: 0 rows. If any rows appear, add to the migration UPDATE statements and re-run.

- [ ] **Step 5: Commit**

```bash
git add prisma/migrations/
git commit -m "data(backend): normalize Terminal.brand to PAX|NEXGO|INGENICO|VERIFONE"
```

---

## Task 7: Seed `PaymentProvider.ANGELPAY` row

**Files:**
- Modify: `avoqado-server/prisma/seed.ts`

- [ ] **Step 1: Add ANGELPAY upsert next to existing providers**

In `prisma/seed.ts`, find the block that creates `MENTA`, `CLIP`, `BLUMON`, `BANORTE_DIRECT` and append:

```typescript
await prisma.paymentProvider.upsert({
  where: { code: 'ANGELPAY' },
  create: {
    code: 'ANGELPAY',
    name: 'Angel Pay',
    type: 'PAYMENT_PROCESSOR',
    countryCode: ['MX'],
    configSchema: {
      type: 'object',
      required: [],
      properties: {
        angelpayAffiliation: { type: 'string', description: 'Display affiliation number (from MerchantOption.afiliationNumber)' },
        angelpayMerchantName: { type: 'string', description: 'Display merchant name (from MerchantOption.name)' },
      },
    },
  },
  update: {
    name: 'Angel Pay',
    countryCode: ['MX'],
  },
})
```

- [ ] **Step 2: Run seed against fresh DB**

```bash
npx prisma migrate reset --force
```

Expected: seed completes; no errors; `ANGELPAY` row visible.

- [ ] **Step 3: Verify**

```bash
psql "$DATABASE_URL" -c "SELECT code, name, type FROM \"PaymentProvider\" WHERE code='ANGELPAY';"
```

Expected: 1 row.

- [ ] **Step 4: Commit**

```bash
git add prisma/seed.ts
git commit -m "feat(backend): seed PaymentProvider row for ANGELPAY"
```

---

## Task 8: Implement `providerDeviceCompatibility` helper

**Files:**
- Create: `avoqado-server/src/lib/providerDeviceCompatibility.ts`
- Create: `avoqado-server/tests/lib/providerDeviceCompatibility.test.ts`

- [ ] **Step 1: Write the failing test**

`tests/lib/providerDeviceCompatibility.test.ts`:

```typescript
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { prisma } from '../../src/lib/prisma'
import {
  PROVIDER_DEVICE_COMPATIBILITY,
  isProviderCompatibleWithBrand,
  assertVenueHasCompatibleTerminal,
} from '../../src/lib/providerDeviceCompatibility'
import { IncompatibleDeviceError } from '../../src/lib/errors'

describe('providerDeviceCompatibility', () => {
  it('PROVIDER_DEVICE_COMPATIBILITY matches expected catalog', () => {
    expect(PROVIDER_DEVICE_COMPATIBILITY).toEqual({
      BLUMON: ['PAX'],
      ANGELPAY: ['NEXGO'],
    })
  })

  it('isProviderCompatibleWithBrand: BLUMON+PAX → true', () => {
    expect(isProviderCompatibleWithBrand('BLUMON', 'PAX')).toBe(true)
  })

  it('isProviderCompatibleWithBrand: ANGELPAY+PAX → false', () => {
    expect(isProviderCompatibleWithBrand('ANGELPAY', 'PAX')).toBe(false)
  })

  it('isProviderCompatibleWithBrand: unknown provider → true (permissive)', () => {
    expect(isProviderCompatibleWithBrand('UNKNOWN', 'PAX')).toBe(true)
  })

  it('isProviderCompatibleWithBrand: null brand → true (permissive)', () => {
    expect(isProviderCompatibleWithBrand('ANGELPAY', null)).toBe(true)
  })

  it('assertVenueHasCompatibleTerminal: rejects ANGELPAY when venue has only PAX', async () => {
    const venue = await prisma.venue.create({ data: { /* minimal venue fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'PAX', status: 'ACTIVE', serialNumber: 'PAX-001' } })

    await expect(
      assertVenueHasCompatibleTerminal(venue.id, 'ANGELPAY')
    ).rejects.toThrow(IncompatibleDeviceError)
  })

  it('assertVenueHasCompatibleTerminal: accepts ANGELPAY when venue has NEXGO', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'ACTIVE', serialNumber: 'NEXGO-001' } })

    await expect(assertVenueHasCompatibleTerminal(venue.id, 'ANGELPAY')).resolves.not.toThrow()
  })

  it('assertVenueHasCompatibleTerminal: ignores INACTIVE terminals', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'INACTIVE', serialNumber: 'NEXGO-002' } })

    await expect(
      assertVenueHasCompatibleTerminal(venue.id, 'ANGELPAY')
    ).rejects.toThrow(IncompatibleDeviceError)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npm test -- tests/lib/providerDeviceCompatibility.test.ts
```

Expected: FAIL with "Cannot find module './providerDeviceCompatibility'".

- [ ] **Step 3: Implement the helper**

`src/lib/providerDeviceCompatibility.ts`:

```typescript
import { prisma } from './prisma'
import type { PrismaClient } from '@prisma/client'
import { IncompatibleDeviceError } from './errors'

type Tx = PrismaClient | Parameters<PrismaClient['$transaction']>[0]

export const PROVIDER_DEVICE_COMPATIBILITY: Record<string, string[]> = {
  BLUMON: ['PAX'],
  ANGELPAY: ['NEXGO'],
}

export function isProviderCompatibleWithBrand(providerCode: string, brand: string | null): boolean {
  const compatible = PROVIDER_DEVICE_COMPATIBILITY[providerCode]
  if (!compatible?.length || !brand) return true
  return compatible.includes(brand)
}

export async function assertVenueHasCompatibleTerminal(
  venueId: string,
  providerCode: string,
  tx: Tx = prisma,
): Promise<void> {
  const compatible = PROVIDER_DEVICE_COMPATIBILITY[providerCode]
  if (!compatible?.length) return

  const client = tx as PrismaClient
  const count = await client.terminal.count({
    where: { venueId, brand: { in: compatible }, status: 'ACTIVE' },
  })
  if (count === 0) {
    throw new IncompatibleDeviceError(
      `Provider ${providerCode} requires at least one ${compatible.join(' or ')} terminal in this venue`,
    )
  }
}
```

If `IncompatibleDeviceError` does not exist yet, add it to `src/lib/errors.ts`:

```typescript
export class IncompatibleDeviceError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'IncompatibleDeviceError'
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
npm test -- tests/lib/providerDeviceCompatibility.test.ts
```

Expected: 7 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/lib/providerDeviceCompatibility.ts src/lib/errors.ts tests/lib/providerDeviceCompatibility.test.ts
git commit -m "feat(backend): provider-device compatibility helper + tests"
```

---

## Task 9: Implement `AngelPayUserAccountService` (D3 lifecycle)

**Files:**
- Create: `avoqado-server/src/services/superadmin/angelpayUserAccount.service.ts`
- Create: `avoqado-server/tests/services/superadmin/angelpayUserAccount.service.test.ts`

- [ ] **Step 1: Write the failing tests**

`tests/services/superadmin/angelpayUserAccount.service.test.ts` — write tests for all 6 service methods identified in spec §18.2:

```typescript
import { describe, it, expect, beforeEach } from 'vitest'
import { prisma } from '../../../src/lib/prisma'
import { AngelPayUserAccountService } from '../../../src/services/superadmin/angelpayUserAccount.service'
import { ValidationError, ConflictError } from '../../../src/lib/errors'

const svc = new AngelPayUserAccountService()

async function makeVenue() {
  return prisma.venue.create({ data: { /* minimal venue fixture */ } })
}

describe('AngelPayUserAccountService', () => {
  describe('create()', () => {
    it('rejects PIN that is not 6 numeric digits', async () => {
      const venue = await makeVenue()
      await expect(svc.create({ venueId: venue.id, email: 'a@b.co', pin: 'abc123', environment: 'QA' })).rejects.toThrow(ValidationError)
      await expect(svc.create({ venueId: venue.id, email: 'a@b.co', pin: '12345', environment: 'QA' })).rejects.toThrow(ValidationError)
    })

    it('rejects invalid email', async () => {
      const venue = await makeVenue()
      await expect(svc.create({ venueId: venue.id, email: 'not-an-email', pin: '123456', environment: 'QA' })).rejects.toThrow(ValidationError)
    })

    it('creates with status PENDING_PIN when no PIN provided initially', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', environment: 'QA' })
      expect(acc.status).toBe('PENDING_PIN')
      expect(acc.pinEncrypted).toEqual({})
    })

    it('creates with status ACTIVE when PIN provided', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      expect(acc.status).toBe('ACTIVE')
      expect(acc.pinEncrypted).toHaveProperty('encrypted')
      expect(acc.pinEncrypted).toHaveProperty('iv')
    })

    it('rejects duplicate per venue', async () => {
      const venue = await makeVenue()
      await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      await expect(svc.create({ venueId: venue.id, email: 'b@c.co', pin: '654321', environment: 'QA' })).rejects.toThrow(ConflictError)
    })
  })

  describe('setPin()', () => {
    it('transitions PENDING_PIN → ACTIVE', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', environment: 'QA' })
      const updated = await svc.setPin(acc.id, '123456')
      expect(updated.status).toBe('ACTIVE')
      expect(updated.pinEncrypted).toHaveProperty('encrypted')
    })

    it('rejects non-6-digit PIN', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', environment: 'QA' })
      await expect(svc.setPin(acc.id, 'abc')).rejects.toThrow(ValidationError)
    })
  })

  describe('status transitions', () => {
    it('markRotationRequired → PIN_ROTATION_REQUIRED', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      const updated = await svc.markRotationRequired(acc.id, 'Vendor 90-day policy', 'staff-cuid')
      expect(updated.status).toBe('PIN_ROTATION_REQUIRED')
      expect(updated.statusReason).toBe('Vendor 90-day policy')
    })

    it('suspend → SUSPENDED', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      const updated = await svc.suspend(acc.id, 'Compliance hold', 'staff-cuid')
      expect(updated.status).toBe('SUSPENDED')
    })

    it('softDelete → DELETED', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      const updated = await svc.softDelete(acc.id, 'staff-cuid')
      expect(updated.status).toBe('DELETED')
    })
  })

  describe('markValidated()', () => {
    it('updates lastValidatedAt + externalUserId, clears error', async () => {
      const venue = await makeVenue()
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      await svc.recordError(acc.id, 'previous fail')
      const updated = await svc.markValidated(acc.id, 42)
      expect(updated.externalUserId).toBe(42)
      expect(updated.lastValidationErr).toBeNull()
      expect(updated.lastValidatedAt).toBeInstanceOf(Date)
    })
  })

  describe('getForTerminal()', () => {
    it('returns the venue account joined via terminal serial', async () => {
      const venue = await makeVenue()
      await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'ACTIVE', serialNumber: 'TEST-SERIAL' } })
      const acc = await svc.create({ venueId: venue.id, email: 'a@b.co', pin: '123456', environment: 'QA' })
      const found = await svc.getForTerminal('TEST-SERIAL')
      expect(found?.id).toBe(acc.id)
    })

    it('returns null when no account exists for the venue', async () => {
      const venue = await makeVenue()
      await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'ACTIVE', serialNumber: 'TEST-SERIAL-2' } })
      const found = await svc.getForTerminal('TEST-SERIAL-2')
      expect(found).toBeNull()
    })
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
npm test -- tests/services/superadmin/angelpayUserAccount.service.test.ts
```

Expected: FAIL with module-not-found.

- [ ] **Step 3: Implement the service**

`src/services/superadmin/angelpayUserAccount.service.ts`:

```typescript
import { prisma } from '../../lib/prisma'
import { ValidationError, ConflictError } from '../../lib/errors'
import { encryptCredentials } from './merchantAccount.service'  // reuse existing helper

const PIN_REGEX = /^\d{6}$/
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

interface CreateInput {
  venueId: string
  email: string
  pin?: string
  environment: 'QA' | 'PROD'
  createdBy?: string
}

export class AngelPayUserAccountService {
  async create(input: CreateInput) {
    if (!EMAIL_REGEX.test(input.email)) throw new ValidationError('Invalid email format')
    if (input.pin !== undefined && !PIN_REGEX.test(input.pin)) {
      throw new ValidationError('PIN must be 6 numeric digits')
    }

    const existing = await prisma.angelPayUserAccount.findUnique({ where: { venueId: input.venueId } })
    if (existing) throw new ConflictError('Venue already has an AngelPay user account')

    const pinEncrypted = input.pin ? encryptCredentials(input.pin) : {}
    const status = input.pin ? 'ACTIVE' : 'PENDING_PIN'

    return prisma.angelPayUserAccount.create({
      data: {
        venueId: input.venueId,
        email: input.email,
        environment: input.environment,
        pinEncrypted: pinEncrypted as any,
        status,
        statusChangedAt: new Date(),
        statusChangedBy: input.createdBy,
        createdBy: input.createdBy,
      },
    })
  }

  async setPin(id: string, newPin: string) {
    if (!PIN_REGEX.test(newPin)) throw new ValidationError('PIN must be 6 numeric digits')
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: {
        pinEncrypted: encryptCredentials(newPin) as any,
        status: 'ACTIVE',
        statusChangedAt: new Date(),
        statusReason: null,
        lastValidationErr: null,
      },
    })
  }

  async markRotationRequired(id: string, reason: string, changedBy: string) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: {
        status: 'PIN_ROTATION_REQUIRED',
        statusChangedAt: new Date(),
        statusChangedBy: changedBy,
        statusReason: reason,
      },
    })
  }

  async suspend(id: string, reason: string, changedBy: string) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: {
        status: 'SUSPENDED',
        statusChangedAt: new Date(),
        statusChangedBy: changedBy,
        statusReason: reason,
      },
    })
  }

  async softDelete(id: string, changedBy: string) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: {
        status: 'DELETED',
        statusChangedAt: new Date(),
        statusChangedBy: changedBy,
      },
    })
  }

  async markValidated(id: string, externalUserId: number) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: {
        lastValidatedAt: new Date(),
        externalUserId,
        lastValidationErr: null,
      },
    })
  }

  async recordError(id: string, message: string) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: { lastValidationErr: message },
    })
  }

  async getForTerminal(serialNumber: string) {
    const terminal = await prisma.terminal.findUnique({
      where: { serialNumber },
      include: { venue: { include: { angelpayUserAccount: true } } },
    })
    return terminal?.venue.angelpayUserAccount ?? null
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
npm test -- tests/services/superadmin/angelpayUserAccount.service.test.ts
```

Expected: all PASS (~13 tests).

- [ ] **Step 5: Commit**

```bash
git add src/services/superadmin/angelpayUserAccount.service.ts tests/services/superadmin/angelpayUserAccount.service.test.ts src/lib/errors.ts
git commit -m "feat(backend): AngelPayUserAccountService with status lifecycle (D3)"
```

---

## Task 10: Wire device-compatibility into `merchantAccount.service.ts`

**Files:**
- Modify: `avoqado-server/src/services/superadmin/merchantAccount.service.ts`
- Create: `avoqado-server/tests/services/superadmin/merchantAccount.deviceCompatibility.test.ts`

- [ ] **Step 1: Write failing tests**

`tests/services/superadmin/merchantAccount.deviceCompatibility.test.ts`:

```typescript
import { describe, it, expect } from 'vitest'
import { prisma } from '../../../src/lib/prisma'
import { createMerchantAccount } from '../../../src/services/superadmin/merchantAccount.service'
import { IncompatibleDeviceError } from '../../../src/lib/errors'

describe('createMerchantAccount device compatibility', () => {
  it('rejects ANGELPAY when venue has no NEXGO terminals', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'PAX', status: 'ACTIVE', serialNumber: 'PAX-Z' } })

    const provider = await prisma.paymentProvider.findUnique({ where: { code: 'ANGELPAY' } })
    await expect(
      createMerchantAccount({
        venueId: venue.id,
        providerId: provider!.id,
        externalMerchantId: '42',
        // ANGELPAY: empty credentials placeholder, requires AngelPayUserAccount separately
      } as any),
    ).rejects.toThrow(IncompatibleDeviceError)
  })

  it('rejects ANGELPAY when AngelPayUserAccount status is PENDING_PIN', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'ACTIVE', serialNumber: 'NEXGO-Z' } })
    await prisma.angelPayUserAccount.create({ data: { venueId: venue.id, email: 'a@b.co', pinEncrypted: {}, environment: 'QA', status: 'PENDING_PIN' } })

    const provider = await prisma.paymentProvider.findUnique({ where: { code: 'ANGELPAY' } })
    await expect(
      createMerchantAccount({ venueId: venue.id, providerId: provider!.id, externalMerchantId: '42' } as any),
    ).rejects.toThrow(/ACTIVE/)
  })

  it('accepts ANGELPAY when venue has NEXGO + account is ACTIVE', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'ACTIVE', serialNumber: 'NEXGO-OK' } })
    await prisma.angelPayUserAccount.create({ data: { venueId: venue.id, email: 'a@b.co', pinEncrypted: { encrypted: 'x', iv: 'y' }, environment: 'QA', status: 'ACTIVE' } })

    const provider = await prisma.paymentProvider.findUnique({ where: { code: 'ANGELPAY' } })
    const merchant = await createMerchantAccount({
      venueId: venue.id,
      providerId: provider!.id,
      externalMerchantId: '42',
      angelpayAffiliation: '9814275',
      angelpayMerchantName: 'Madre Cafe Rooftop',
    } as any)
    expect(merchant.externalMerchantId).toBe('42')
    expect(merchant.credentialsEncrypted).toBeDefined()
  })

  it('rejects ANGELPAY with non-numeric externalMerchantId', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'NEXGO', status: 'ACTIVE', serialNumber: 'NEXGO-N' } })
    await prisma.angelPayUserAccount.create({ data: { venueId: venue.id, email: 'a@b.co', pinEncrypted: { encrypted: 'x', iv: 'y' }, environment: 'QA', status: 'ACTIVE' } })

    const provider = await prisma.paymentProvider.findUnique({ where: { code: 'ANGELPAY' } })
    await expect(
      createMerchantAccount({ venueId: venue.id, providerId: provider!.id, externalMerchantId: 'not-a-number' } as any),
    ).rejects.toThrow(/numeric/)
  })

  it('Blumon path remains unchanged', async () => {
    const venue = await prisma.venue.create({ data: { /* fixture */ } })
    await prisma.terminal.create({ data: { venueId: venue.id, brand: 'PAX', status: 'ACTIVE', serialNumber: 'PAX-OK' } })

    const provider = await prisma.paymentProvider.findUnique({ where: { code: 'BLUMON' } })
    const merchant = await createMerchantAccount({
      venueId: venue.id,
      providerId: provider!.id,
      externalMerchantId: 'BLUMON-123',
      credentials: { serialNumber: 'X1', posId: 'Y1', environment: 'sandbox' },
    } as any)
    expect(merchant.externalMerchantId).toBe('BLUMON-123')
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
npm test -- tests/services/superadmin/merchantAccount.deviceCompatibility.test.ts
```

Expected: most FAIL because validations don't exist yet.

- [ ] **Step 3: Modify `merchantAccount.service.ts` — `createMerchantAccount()`**

In `src/services/superadmin/merchantAccount.service.ts`, find `createMerchantAccount()` and add at the top (after `provider` is loaded):

```typescript
import { assertVenueHasCompatibleTerminal } from '../../lib/providerDeviceCompatibility'

async function createMerchantAccount(input: CreateMerchantAccountInput) {
  const provider = await prisma.paymentProvider.findUnique({ where: { id: input.providerId } })
  if (!provider) throw new NotFoundError('Provider not found')

  // NEW: device compatibility gate
  await assertVenueHasCompatibleTerminal(input.venueId, provider.code)

  // NEW: AngelPay-specific path — requires ACTIVE AngelPayUserAccount + numeric externalMerchantId
  if (provider.code === 'ANGELPAY') {
    if (!/^\d+$/.test(input.externalMerchantId)) {
      throw new ValidationError('AngelPay externalMerchantId must be a numeric string')
    }
    const account = await prisma.angelPayUserAccount.findUnique({ where: { venueId: input.venueId } })
    if (!account || account.status !== 'ACTIVE') {
      throw new ValidationError(
        `AngelPay account is in state ${account?.status ?? 'NONE'}; cannot create merchant accounts until ACTIVE`,
      )
    }
    // Placeholder credentials — auth lives on AngelPayUserAccount
    input.credentials = {}
  }

  // ...rest of existing logic (encrypt credentials, persist) — unchanged for BLUMON path
}
```

- [ ] **Step 4: Verify Blumon-creating tests still pass**

```bash
npm test -- src/services/superadmin/merchantAccount.service
```

Expected: all existing tests pass; new ones pass.

- [ ] **Step 5: Commit**

```bash
git add src/services/superadmin/merchantAccount.service.ts tests/services/superadmin/merchantAccount.deviceCompatibility.test.ts
git commit -m "feat(backend): device-compatibility + AngelPay state gates in createMerchantAccount"
```

---

## Task 11: Add validation point #2 — assign/unassign merchant to terminal

**Files:**
- Modify: `avoqado-server/src/services/superadmin/terminal.service.ts` (or wherever `assignedMerchantIds` is mutated)

- [ ] **Step 1: Locate the assign function**

```bash
grep -n "assignedMerchantIds" /Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/services/**/*.ts
```

Note the file + function name. Add tests in the matching test file.

- [ ] **Step 2: Write failing test** — `assignMerchantToTerminal` rejects ANGELPAY merchant for PAX terminal

```typescript
it('rejects assigning ANGELPAY merchant to PAX terminal', async () => {
  const venue = await /* fixture with NEXGO + PAX terminals + ANGELPAY merchant */
  const paxTerminal = /* fixture */
  const angelpayMerchant = /* fixture */
  await expect(
    assignMerchantToTerminal(paxTerminal.id, angelpayMerchant.id),
  ).rejects.toThrow(IncompatibleDeviceError)
})
```

- [ ] **Step 3: Add the guard inside `assignMerchantToTerminal`**

```typescript
import { isProviderCompatibleWithBrand } from '../../lib/providerDeviceCompatibility'

export async function assignMerchantToTerminal(terminalId: string, merchantAccountId: string) {
  const terminal = await prisma.terminal.findUnique({ where: { id: terminalId } })
  if (!terminal) throw new NotFoundError('Terminal not found')

  const merchant = await prisma.merchantAccount.findUnique({
    where: { id: merchantAccountId },
    include: { provider: true },
  })
  if (!merchant) throw new NotFoundError('MerchantAccount not found')

  if (!isProviderCompatibleWithBrand(merchant.provider.code, terminal.brand)) {
    throw new IncompatibleDeviceError(
      `Cannot assign ${merchant.provider.code} merchant to ${terminal.brand} terminal`,
    )
  }

  // ...existing mutation logic (push merchantAccountId onto terminal.assignedMerchantIds)
}
```

Apply the same guard to any other path that mutates `assignedMerchantIds` (bulk update, venue-level inheritance setup).

- [ ] **Step 4: Run tests**

```bash
npm test -- src/services/superadmin/terminal
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(backend): block incompatible merchant assignment (validation point #2)"
```

---

## Task 12: Add validation point #3 — terminal brand change warning

**Files:**
- Modify: `avoqado-server/src/controllers/superadmin/terminal.controller.ts`

- [ ] **Step 1: Write failing test**

```typescript
it('PATCH /terminals/:id changing brand from NEXGO to PAX returns warning with incompatible merchants', async () => {
  const terminal = /* fixture: NEXGO terminal with ANGELPAY merchant assigned */
  const response = await request(app)
    .patch(`/api/v1/superadmin/terminals/${terminal.id}`)
    .send({ brand: 'PAX' })
    .set('Authorization', superadminToken)

  expect(response.status).toBe(200)
  expect(response.body.warning).toBe(true)
  expect(response.body.incompatibleMerchants).toHaveLength(1)
  expect(response.body.incompatibleMerchants[0].code).toBe('ANGELPAY')
})
```

- [ ] **Step 2: Modify the controller**

```typescript
import { isProviderCompatibleWithBrand } from '../../lib/providerDeviceCompatibility'

export async function updateTerminal(req: Request, res: Response) {
  const { id } = req.params
  const updates = req.body

  if (updates.brand) {
    const oldTerminal = await prisma.terminal.findUnique({ where: { id } })
    if (!oldTerminal) throw new NotFoundError('Terminal not found')

    if (oldTerminal.brand !== updates.brand && oldTerminal.assignedMerchantIds.length > 0) {
      const assignedMerchants = await prisma.merchantAccount.findMany({
        where: { id: { in: oldTerminal.assignedMerchantIds } },
        include: { provider: true },
      })
      const incompatible = assignedMerchants.filter(
        (m) => !isProviderCompatibleWithBrand(m.provider.code, updates.brand),
      )
      if (incompatible.length > 0 && !req.body.forceUnassign) {
        return res.json({
          warning: true,
          incompatibleMerchants: incompatible.map((m) => ({
            id: m.id,
            name: m.displayName ?? m.externalMerchantId,
            code: m.provider.code,
          })),
        })
      }

      // If forceUnassign=true OR no incompatible, proceed in transaction
      await prisma.$transaction([
        prisma.terminal.update({
          where: { id },
          data: {
            brand: updates.brand,
            assignedMerchantIds: oldTerminal.assignedMerchantIds.filter(
              (mid) => !incompatible.some((m) => m.id === mid),
            ),
          },
        }),
      ])
    }
  }

  // ...rest of existing update logic
}
```

- [ ] **Step 3: Run tests**

```bash
npm test -- src/controllers/superadmin/terminal
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(backend): brand-change warning lists incompatible merchants (validation point #3)"
```

---

## Task 13: Add validation point #4 — filter merchants in `/tpv/terminals/:serial/config` + include `angelpayAuth`

**Files:**
- Modify: `avoqado-server/src/controllers/tpv/terminal.tpv.controller.ts`
- Create: `avoqado-server/tests/controllers/tpv/terminal.tpv.controller.angelpay.test.ts`

- [ ] **Step 1: Write failing tests**

```typescript
import { describe, it, expect } from 'vitest'
import request from 'supertest'
import { app } from '../../../src/app'
import { prisma } from '../../../src/lib/prisma'

describe('GET /api/v1/tpv/terminals/:serial/config — AngelPay payload', () => {
  it('filters merchants by terminal brand compatibility', async () => {
    const venue = await /* fixture */
    const nexgoTerminal = await /* fixture NEXGO */
    // Add both ANGELPAY and BLUMON merchants to the venue
    const angelpayMerchant = /* fixture */
    const blumonMerchant = /* fixture */

    const res = await request(app)
      .get(`/api/v1/tpv/terminals/${nexgoTerminal.serialNumber}/config`)
      .set('X-Terminal-Token', nexgoTerminal.token)

    expect(res.status).toBe(200)
    const codes = res.body.merchants.map((m: any) => m.providerCode)
    expect(codes).toContain('ANGELPAY')
    expect(codes).not.toContain('BLUMON')  // filtered out for NEXGO brand
  })

  it('includes angelpayAuth when terminal is NEXGO and venue has ACTIVE AngelPayUserAccount', async () => {
    const venue = /* fixture */
    const terminal = /* NEXGO fixture */
    const account = await prisma.angelPayUserAccount.create({
      data: { venueId: venue.id, email: 'a@b.co', pinEncrypted: encryptCredentials('123456'), environment: 'QA', status: 'ACTIVE' },
    })

    const res = await request(app)
      .get(`/api/v1/tpv/terminals/${terminal.serialNumber}/config`)
      .set('X-Terminal-Token', terminal.token)

    expect(res.body.angelpayAuth).toBeDefined()
    expect(res.body.angelpayAuth.email).toBe('a@b.co')
    expect(res.body.angelpayAuth.pin).toBe('123456')  // decrypted server-side
    expect(res.body.angelpayAuth.environment).toBe('QA')
    expect(res.body.angelpayAuth.accountId).toBe(account.id)
  })

  it('omits angelpayAuth when status is not ACTIVE', async () => {
    const venue = /* fixture */
    const terminal = /* NEXGO fixture */
    await prisma.angelPayUserAccount.create({
      data: { venueId: venue.id, email: 'a@b.co', pinEncrypted: {}, environment: 'QA', status: 'PENDING_PIN' },
    })

    const res = await request(app)
      .get(`/api/v1/tpv/terminals/${terminal.serialNumber}/config`)
      .set('X-Terminal-Token', terminal.token)

    expect(res.body.angelpayAuth).toBeNull()
  })

  it('omits angelpayAuth for PAX terminals', async () => {
    const venue = /* fixture with PAX terminal */
    const terminal = /* PAX fixture */
    await prisma.angelPayUserAccount.create({
      data: { venueId: venue.id, email: 'a@b.co', pinEncrypted: { encrypted: 'x', iv: 'y' }, environment: 'QA', status: 'ACTIVE' },
    })

    const res = await request(app)
      .get(`/api/v1/tpv/terminals/${terminal.serialNumber}/config`)
      .set('X-Terminal-Token', terminal.token)

    expect(res.body.angelpayAuth).toBeNull()
  })
})
```

- [ ] **Step 2: Modify the controller**

In `src/controllers/tpv/terminal.tpv.controller.ts`, find the response-building block (around line 110-260) and:

```typescript
import { isProviderCompatibleWithBrand } from '../../lib/providerDeviceCompatibility'
import { decryptCredentials } from '../../services/superadmin/merchantAccount.service'

// ...inside the handler, after resolving terminal + merchants...

const filteredMerchants = merchants.filter((m) =>
  isProviderCompatibleWithBrand(m.provider.code, terminal.brand),
)

let angelpayAuth = null
if (terminal.brand === 'NEXGO' && filteredMerchants.some((m) => m.provider.code === 'ANGELPAY')) {
  const account = await prisma.angelPayUserAccount.findUnique({
    where: { venueId: terminal.venueId },
  })
  if (account && account.status === 'ACTIVE') {
    angelpayAuth = {
      accountId: account.id,
      email: account.email,
      pin: decryptCredentials(account.pinEncrypted as any),  // decrypt for TLS transport
      environment: account.environment,
    }
  }
}

return res.json({
  terminal: terminalDto,
  venue: venueDto,
  merchants: filteredMerchants.map(toMerchantAccountDto),
  angelpayAuth,
  // ...other existing fields
})
```

Also update `toMerchantAccountDto` (or equivalent) to include `externalMerchantId`, `isActive`, `angelpayAffiliation`, `angelpayMerchantName`.

- [ ] **Step 3: Run tests**

```bash
npm test -- tests/controllers/tpv/terminal.tpv.controller.angelpay.test.ts
```

Expected: 4 PASS.

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(backend): filter merchants by brand + include angelpayAuth in TPV config (validation point #4)"
```

---

## Task 14: Add new TPV report endpoints

**Files:**
- Create: `avoqado-server/src/controllers/tpv/angelpayValidation.tpv.controller.ts`
- Modify: `avoqado-server/src/routes/tpv.routes.ts`

- [ ] **Step 1: Write failing tests**

```typescript
describe('POST /api/v1/tpv/angelpay/report-validation', () => {
  it('marks AngelPayUserAccount as validated on success', async () => {
    const account = /* fixture ACTIVE */
    const res = await request(app)
      .post('/api/v1/tpv/angelpay/report-validation')
      .set('X-Terminal-Token', terminalToken)
      .send({ accountId: account.id, state: 'AUTHENTICATED', externalUserId: 42 })
    expect(res.status).toBe(204)
    const updated = await prisma.angelPayUserAccount.findUnique({ where: { id: account.id } })
    expect(updated?.lastValidatedAt).toBeInstanceOf(Date)
    expect(updated?.externalUserId).toBe(42)
  })

  it('records error on failure', async () => {
    const account = /* fixture */
    await request(app)
      .post('/api/v1/tpv/angelpay/report-validation')
      .set('X-Terminal-Token', terminalToken)
      .send({ accountId: account.id, state: 'AUTH_ERROR', error: 'Invalid PIN' })
    const updated = await prisma.angelPayUserAccount.findUnique({ where: { id: account.id } })
    expect(updated?.lastValidationErr).toBe('Invalid PIN')
  })

  it('handles CONFIG_MISMATCH state', async () => {
    const account = /* fixture */
    await request(app)
      .post('/api/v1/tpv/angelpay/report-validation')
      .set('X-Terminal-Token', terminalToken)
      .send({
        accountId: account.id,
        state: 'CONFIG_MISMATCH',
        missingInAvoqado: [99],
        missingInSdk: [42],
      })
    const updated = await prisma.angelPayUserAccount.findUnique({ where: { id: account.id } })
    expect(updated?.lastValidationErr).toContain('CONFIG_MISMATCH')
  })
})

describe('POST /api/v1/tpv/angelpay/report-merchant-switch', () => {
  it('logs a switch event with terminal serial + merchant IDs', async () => {
    const res = await request(app)
      .post('/api/v1/tpv/angelpay/report-merchant-switch')
      .set('X-Terminal-Token', terminalToken)
      .send({ fromMerchantId: 42, toMerchantId: 99, durationMs: 1234 })
    expect(res.status).toBe(204)
    // optionally check audit table if one is added
  })
})
```

- [ ] **Step 2: Implement the controller**

```typescript
import { Request, Response } from 'express'
import { AngelPayUserAccountService } from '../../services/superadmin/angelpayUserAccount.service'

const svc = new AngelPayUserAccountService()

export async function reportValidation(req: Request, res: Response) {
  const { accountId, state, externalUserId, error, missingInAvoqado, missingInSdk } = req.body
  switch (state) {
    case 'AUTHENTICATED':
      await svc.markValidated(accountId, externalUserId)
      break
    case 'AUTH_ERROR':
      await svc.recordError(accountId, error ?? 'Unknown auth error')
      break
    case 'CONFIG_MISMATCH':
      await svc.recordError(
        accountId,
        `CONFIG_MISMATCH: missingInAvoqado=${JSON.stringify(missingInAvoqado)}, missingInSdk=${JSON.stringify(missingInSdk)}`,
      )
      break
    default:
      return res.status(400).json({ error: `Unknown state: ${state}` })
  }
  return res.status(204).end()
}

export async function reportMerchantSwitch(req: Request, res: Response) {
  const { fromMerchantId, toMerchantId, durationMs } = req.body
  // Log via existing logger; if an audit table is desired later, add here.
  req.log.info({ fromMerchantId, toMerchantId, durationMs }, 'angelpay.merchant_switch')
  return res.status(204).end()
}
```

- [ ] **Step 3: Mount routes**

In `src/routes/tpv.routes.ts`:

```typescript
import { reportValidation, reportMerchantSwitch } from '../controllers/tpv/angelpayValidation.tpv.controller'

router.post('/angelpay/report-validation', terminalAuthMiddleware, reportValidation)
router.post('/angelpay/report-merchant-switch', terminalAuthMiddleware, reportMerchantSwitch)
```

- [ ] **Step 4: Run tests**

```bash
npm test -- tests/controllers/tpv/
```

Expected: PASS.

- [ ] **Step 5: Commit + deploy backend to staging**

```bash
git commit -am "feat(backend): TPV endpoints for AngelPay validation + merchant switch reporting"
git push origin feat/angelpay-multimerchant-backend
# Open PR; merge after review; deploy to staging
```

Phase 1 complete. Backend supports the full flow. Wait 24h for stability before starting Phase 2 (dashboard).

---

# Phase 2 — Dashboard

## Task 15: Add `AngelPay` API client + `Terminal.brand` dropdown

**Files:**
- Create: `avoqado-web-dashboard/src/api/angelpayUserAccount.api.ts`
- Modify: `avoqado-web-dashboard/src/pages/Superadmin/Terminals.tsx`

- [ ] **Step 1: API client**

`src/api/angelpayUserAccount.api.ts`:

```typescript
import { apiClient } from './client'

export interface AngelPayUserAccount {
  id: string
  venueId: string
  email: string
  environment: 'QA' | 'PROD'
  status: 'PENDING_PIN' | 'ACTIVE' | 'PIN_ROTATION_REQUIRED' | 'SUSPENDED' | 'DELETED'
  statusChangedAt: string | null
  statusReason: string | null
  externalUserId: number | null
  lastValidatedAt: string | null
  lastValidationErr: string | null
}

export const angelpayUserAccountApi = {
  get: (venueId: string) =>
    apiClient.get<AngelPayUserAccount | null>(`/api/v1/superadmin/venues/${venueId}/angelpay-account`),
  create: (venueId: string, body: { email: string; pin?: string; environment: 'QA' | 'PROD' }) =>
    apiClient.post<AngelPayUserAccount>(`/api/v1/superadmin/venues/${venueId}/angelpay-account`, body),
  setPin: (id: string, pin: string) =>
    apiClient.patch<AngelPayUserAccount>(`/api/v1/superadmin/angelpay-accounts/${id}/pin`, { pin }),
  markRotationRequired: (id: string, reason: string) =>
    apiClient.patch<AngelPayUserAccount>(`/api/v1/superadmin/angelpay-accounts/${id}/status`, {
      status: 'PIN_ROTATION_REQUIRED',
      reason,
    }),
  suspend: (id: string, reason: string) =>
    apiClient.patch<AngelPayUserAccount>(`/api/v1/superadmin/angelpay-accounts/${id}/status`, {
      status: 'SUSPENDED',
      reason,
    }),
  delete: (id: string) =>
    apiClient.delete(`/api/v1/superadmin/angelpay-accounts/${id}`),
}
```

- [ ] **Step 2: Add brand dropdown to Terminal form**

In `src/pages/Superadmin/Terminals.tsx`, find the `brand` input. Replace free-text with:

```tsx
<Select
  value={brand}
  onChange={setBrand}
  options={[
    { value: 'PAX', label: 'PAX' },
    { value: 'NEXGO', label: 'Nexgo' },
    { value: 'INGENICO', label: 'Ingenico' },
    { value: 'VERIFONE', label: 'Verifone' },
  ]}
  placeholder="Select manufacturer"
/>
```

If brand change triggers warning from backend (Task 12), show a confirm dialog:

```tsx
{warning?.incompatibleMerchants && (
  <ConfirmDialog
    title="Estos merchants quedarán sin asignar"
    body={
      <ul>{warning.incompatibleMerchants.map((m) => <li key={m.id}>{m.name} ({m.code})</li>)}</ul>
    }
    confirmLabel="Continuar y desasignar"
    onConfirm={() => submitWithForceUnassign(true)}
    onCancel={() => setBrand(originalBrand)}
  />
)}
```

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(dashboard): brand dropdown + brand-change warning dialog"
```

---

## Task 16: AngelPay account management page

**Files:**
- Create: `avoqado-web-dashboard/src/pages/Superadmin/Venues/[venueId]/AngelPayAccount.tsx`
- Modify: `avoqado-web-dashboard/src/routes.tsx` (add the route)

- [ ] **Step 1: Build the page**

```tsx
import { useParams } from 'react-router-dom'
import { useQuery, useMutation } from '@tanstack/react-query'
import { angelpayUserAccountApi } from '@/api/angelpayUserAccount.api'

export default function AngelPayAccountPage() {
  const { venueId } = useParams<{ venueId: string }>()
  const { data: account, refetch } = useQuery({
    queryKey: ['angelpayAccount', venueId],
    queryFn: () => angelpayUserAccountApi.get(venueId!),
  })

  if (!account) {
    return <EmptyStateCard cta="Crear cuenta AngelPay" onClick={() => openCreateModal(venueId!)} />
  }

  return (
    <Card>
      <Header>AngelPay Account — {account.email}</Header>
      <StatusChip status={account.status} />
      <DetailRow label="Environment" value={account.environment} />
      <DetailRow label="PIN" value="••••••" />
      <DetailRow label="Last validated" value={account.lastValidatedAt ?? 'Never'} />
      <DetailRow label="Last error" value={account.lastValidationErr ?? '—'} />
      <Actions>
        <Button onClick={() => openRotatePinModal(account.id)}>Rotate PIN</Button>
        <Button onClick={() => openSuspendModal(account.id)} variant="warning">Suspend</Button>
        <Button onClick={() => openDeleteConfirm(account.id)} variant="danger">Delete</Button>
      </Actions>
      <AuditLog accountId={account.id} />
    </Card>
  )
}
```

- [ ] **Step 2: Wire route**

```tsx
// src/routes.tsx
<Route path="/superadmin/venues/:venueId/angelpay-account" element={<AngelPayAccountPage />} />
```

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(dashboard): AngelPay account management page (D3 status + rotate/suspend/delete)"
```

---

## Task 17: MerchantAccount form — AngelPay fields + compatibility banner

**Files:**
- Modify: `avoqado-web-dashboard/src/pages/Superadmin/MerchantAccounts.tsx`
- Create: `avoqado-web-dashboard/src/components/MerchantAccountForm/AngelPayFields.tsx`
- Create: `avoqado-web-dashboard/src/components/MerchantAccountForm/DeviceCompatibilityBanner.tsx`

- [ ] **Step 1: AngelPayFields component**

```tsx
interface AngelPayFieldsProps {
  externalMerchantId: string
  setExternalMerchantId: (v: string) => void
  angelpayAffiliation: string
  setAngelpayAffiliation: (v: string) => void
  angelpayMerchantName: string
  setAngelpayMerchantName: (v: string) => void
}

export function AngelPayFields(props: AngelPayFieldsProps) {
  return (
    <>
      <FormField
        label="AngelPay Merchant ID"
        helperText="Numeric integer from MerchantOption.id (lookup at portal.angelpay-qa.com.mx)"
        value={props.externalMerchantId}
        onChange={(v) => {
          if (/^\d*$/.test(v)) props.setExternalMerchantId(v)  // numeric only
        }}
        required
      />
      <FormField
        label="Affiliation Number"
        value={props.angelpayAffiliation}
        onChange={props.setAngelpayAffiliation}
        required
      />
      <FormField
        label="Display Name (optional)"
        value={props.angelpayMerchantName}
        onChange={props.setAngelpayMerchantName}
      />
    </>
  )
}
```

- [ ] **Step 2: DeviceCompatibilityBanner**

```tsx
import { PROVIDER_DEVICE_COMPATIBILITY } from '@/lib/providerDeviceCompatibility'

interface BannerProps {
  providerCode: string
  venueId: string
}

export function DeviceCompatibilityBanner({ providerCode, venueId }: BannerProps) {
  const compatible = PROVIDER_DEVICE_COMPATIBILITY[providerCode]
  const { data: terminals } = useQuery({
    queryKey: ['terminals', venueId],
    queryFn: () => terminalApi.listByVenue(venueId),
  })

  if (!compatible) return null
  const matching = (terminals ?? []).filter((t) => compatible.includes(t.brand) && t.status === 'ACTIVE')

  if (matching.length === 0) {
    return (
      <Alert variant="error">
        {providerCode} requires at least one {compatible.join(' or ')} terminal.
        <Button asChild>
          <Link to={`/superadmin/venues/${venueId}/terminals/new?brand=${compatible[0]}`}>
            Register a {compatible[0]} terminal first →
          </Link>
        </Button>
      </Alert>
    )
  }
  return (
    <Alert variant="info">
      {providerCode} only operates on {compatible.join(' or ')} terminals. This venue has{' '}
      <strong>{matching.length}</strong> compatible active terminal(s).
    </Alert>
  )
}
```

- [ ] **Step 3: Wire into MerchantAccounts.tsx**

When provider selected is `ANGELPAY`:
- Show `<DeviceCompatibilityBanner />` above the form
- Show `<AngelPayFields />` instead of Blumon-specific fields
- Disable submit if banner is in error state
- Check `AngelPayUserAccount.status === 'ACTIVE'` via API before allowing submit; otherwise show prerequisite alert with link to AngelPay account page

- [ ] **Step 4: Add the constants file (duplicated for dashboard)**

`src/lib/providerDeviceCompatibility.ts`:

```typescript
export const PROVIDER_DEVICE_COMPATIBILITY: Record<string, string[]> = {
  BLUMON: ['PAX'],
  ANGELPAY: ['NEXGO'],
}
```

Comment at top:

```typescript
// MIRROR of avoqado-server/src/lib/providerDeviceCompatibility.ts
// Kept in sync manually + via integration test in /tests/lib/providerDeviceCompatibility.sync.test.ts (Task 20)
```

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(dashboard): MerchantAccount form — AngelPay-specific fields + compatibility banner"
```

---

## Task 18: Terminal assignment view — filter by brand

**Files:**
- Modify: `avoqado-web-dashboard/src/pages/Superadmin/Terminals/[id]/Assignments.tsx`

- [ ] **Step 1: Filter merchant dropdown**

```tsx
import { PROVIDER_DEVICE_COMPATIBILITY } from '@/lib/providerDeviceCompatibility'

const compatibleProviders = Object.entries(PROVIDER_DEVICE_COMPATIBILITY)
  .filter(([_, brands]) => brands.includes(terminal.brand))
  .map(([code]) => code)

const eligibleMerchants = allMerchants.filter((m) =>
  compatibleProviders.length === 0 || compatibleProviders.includes(m.providerCode),
)
```

- [ ] **Step 2: Commit**

```bash
git commit -am "feat(dashboard): filter merchant dropdown by terminal brand in assignment view"
```

---

## Task 19: Wire AngelPay account routes in dashboard nav

**Files:**
- Modify: `avoqado-web-dashboard/src/components/Sidebar.tsx` (or equivalent nav)

- [ ] **Step 1: Add link in Venue detail nav**

Wherever the venue detail page shows tabs (Overview, Terminals, MerchantAccounts), add an "AngelPay Account" tab:

```tsx
<NavTab to={`/superadmin/venues/${venueId}/angelpay-account`}>AngelPay Account</NavTab>
```

- [ ] **Step 2: Commit**

```bash
git commit -am "feat(dashboard): expose AngelPay account tab in venue detail nav"
```

---

## Task 20: Cross-repo sync test for `PROVIDER_DEVICE_COMPATIBILITY`

**Files:**
- Create: `avoqado-web-dashboard/tests/lib/providerDeviceCompatibility.sync.test.ts`

- [ ] **Step 1: Write a test that fails if the constant ever diverges**

```typescript
import { describe, it, expect } from 'vitest'
import fs from 'fs'
import path from 'path'
import { PROVIDER_DEVICE_COMPATIBILITY as dashboardCompat } from '../../src/lib/providerDeviceCompatibility'

describe('PROVIDER_DEVICE_COMPATIBILITY sync with backend', () => {
  it('matches backend definition (manual sync — update both)', () => {
    // Read backend source file
    const backendPath = path.resolve(__dirname, '../../../avoqado-server/src/lib/providerDeviceCompatibility.ts')
    const source = fs.readFileSync(backendPath, 'utf-8')
    const match = source.match(/PROVIDER_DEVICE_COMPATIBILITY[^=]*=\s*({[^}]+})/s)
    expect(match).not.toBeNull()
    // eslint-disable-next-line no-eval
    const backendCompat = eval('(' + match![1] + ')')
    expect(dashboardCompat).toEqual(backendCompat)
  })
})
```

- [ ] **Step 2: Run**

```bash
npm test -- tests/lib/providerDeviceCompatibility.sync.test.ts
```

Expected: PASS.

- [ ] **Step 3: Commit + deploy to staging**

```bash
git commit -am "test(dashboard): pin sync between dashboard + backend PROVIDER_DEVICE_COMPATIBILITY"
git push origin feat/angelpay-multimerchant-dashboard
# Open PR; merge after review; deploy
```

Phase 2 complete. Wait 24h for stability before Phase 3 (TPV).

---

# Phase 3 — TPV

## Task 21: BuildConfig cleanup — remove hardcoded creds, add SUPPORTED_PROCESSOR

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Locate the offending lines**

```bash
grep -n "ANGELPAY_QA_" app/build.gradle.kts
```

Expected: lines like `buildConfigField("String", "ANGELPAY_QA_EMAIL", ...)`.

- [ ] **Step 2: Delete the QA cred fields and add the new ones**

In `app/build.gradle.kts`, for each productFlavor:

```kotlin
android {
    productFlavors {
        create("sandboxDebug") {
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")
        }
        create("productionRelease") {
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")
        }
        create("nexgo") {
            // DELETED: ANGELPAY_QA_EMAIL / PASSWORD / AFFILIATION / COMMERCE_TOKEN
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"ANGELPAY\"")
            buildConfigField("String", "ANGELPAY_ENV", "\"QA\"")
        }
        create("nexgoProd") {
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"ANGELPAY\"")
            buildConfigField("String", "ANGELPAY_ENV", "\"PROD\"")
        }
        create("tutorialEmuDebug") {
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")  // emulator only
        }
    }
}
```

- [ ] **Step 3: Add `packagingOptions` for Nexgo flavors to exclude PAX native artifacts**

```kotlin
android {
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/*.kotlin_module",
            )
        }
    }
}
```

Plus, conditionally for nexgo/nexgoProd flavors (verify exact PAX native libs to exclude after first Nexgo build):

```kotlin
android.applicationVariants.all {
    if (name.startsWith("nexgo")) {
        packagingOptions {
            jniLibs {
                excludes += setOf(
                    "lib/armeabi/libcyber*.so",
                    "lib/armeabi-v7a/libcyber*.so",
                )
            }
        }
    }
}
```

- [ ] **Step 4: Add stub-AAR documentation comment**

Above line 379 (`nexgoImplementation(files("libs/blumon_sdk-debug.aar"))`):

```kotlin
// STUB ONLY: Blumon AAR is packaged for Nexgo flavors to satisfy compile-time symbol resolution
// in shared code. No Blumon entry-point may be invoked at runtime in Nexgo builds —
// enforce via BuildConfig.SUPPORTED_PROCESSOR == "BLUMON" guards. See spec §17.5.
"nexgoImplementation"(files("libs/blumon_sdk-debug.aar"))
```

- [ ] **Step 5: Compile both PAX and Nexgo flavors**

```bash
./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin
```

Expected: both succeed. AvoqadoTPVApplication.kt may have compile errors now because `ANGELPAY_QA_*` references are dangling — that's expected and fixed in Task 22.

- [ ] **Step 6: Commit**

```bash
git commit -am "chore(tpv): remove hardcoded AngelPay QA creds; add SUPPORTED_PROCESSOR + ANGELPAY_ENV BuildConfig"
```

---

## Task 22: Remove auto-provisioning block from `AvoqadoTPVApplication`

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/AvoqadoTPVApplication.kt`

- [ ] **Step 1: Delete lines 153-160 (auto-provisioning block)**

Remove the `if (...) { secureStorage.saveAngelPayCredentials(...) }` block.

- [ ] **Step 2: Switch initialization to use Provider injection (D4 Pattern A)**

Replace direct `@Inject` with:

```kotlin
@HiltAndroidApp
class AvoqadoTPVApplication : Application() {

    @Inject lateinit var angelPaySdkGatewayProvider: javax.inject.Provider<AngelPaySdkGateway>

    override fun onCreate() {
        super.onCreate()
        // ...existing init for Blumon (untouched)...

        if (BuildConfig.SUPPORTED_PROCESSOR == "ANGELPAY") {
            angelPaySdkGatewayProvider.get()
                .ensureInitialized(applicationContext, BuildConfig.ANGELPAY_ENV)
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin
```

Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git commit -am "refactor(tpv): replace hardcoded cred provisioning with lazy Hilt Provider (D4 Pattern A)"
```

---

## Task 23: Extend `MerchantAccountDto` + `TerminalConfigDto` (additive)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/TerminalConfigDto.kt`

- [ ] **Step 1: Add new fields to existing data classes**

Inside the existing file (do NOT create a new file):

```kotlin
data class MerchantAccountDto(
    @SerializedName("merchantAccountId") val merchantAccountId: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("providerCode") val providerCode: String? = "BLUMON",
    // ...existing Blumon fields...

    // NEW — additive
    @SerializedName("externalMerchantId") val externalMerchantId: String? = null,
    @SerializedName("isActive") val isActive: Boolean = true,
    @SerializedName("angelpayAffiliation") val angelpayAffiliation: String? = null,
    @SerializedName("angelpayMerchantName") val angelpayMerchantName: String? = null,
)

data class TerminalConfigDto(
    // ...existing fields...
    @SerializedName("angelpayAuth") val angelpayAuth: AngelPayAuthDto? = null,
)

data class AngelPayAuthDto(
    @SerializedName("accountId") val accountId: String,
    @SerializedName("email") val email: String,
    @SerializedName("pin") val pin: String,  // never persisted; see §4.5b PIN rules
    @SerializedName("environment") val environment: String,
)
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin
```

Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(tpv): extend MerchantAccountDto + add AngelPayAuthDto (additive)"
```

---

## Task 24: Extend `MerchantAccount` domain model + add helper

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/MerchantAccount.kt`

- [ ] **Step 1: Add fields + helper**

```kotlin
data class MerchantAccount(
    val merchantAccountId: String? = null,
    val processorType: ProcessorType = ProcessorType.BLUMON,
    // ...existing fields kept unchanged...

    // NEW — additive
    val externalMerchantId: String? = null,
    val isActive: Boolean = true,
    val angelpayAffiliation: String? = null,
    val angelpayMerchantName: String? = null,
) {
    /** AngelPay merchant ID as Int — only call when processorType == ANGELPAY. */
    fun requireAngelpayMerchantId(): Int =
        externalMerchantId?.toIntOrNull()
            ?: error("Malformed externalMerchantId for ANGELPAY merchant $merchantAccountId: '$externalMerchantId'")
}
```

Also update the existing `MerchantAccountDto.toDomain()` extension (in the same file or adjacent) to wire the new fields.

- [ ] **Step 2: Compile + run existing tests (must still pass)**

```bash
./gradlew testSandboxDebugUnitTest
```

Expected: 220 tests, 0 failures (no regression).

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(tpv): extend MerchantAccount domain with AngelPay fields + helper"
```

---

## Task 25: Implement `AngelPayCredentialResolver` (D4)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayCredentialResolver.kt`
- Create: `app/src/test/java/.../AngelPayCredentialResolverTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class AngelPayCredentialResolverTest {
    private val terminalConfigRepo = mockk<TerminalConfigRepository>()
    private val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
    private lateinit var resolver: AngelPayCredentialResolver

    @Before
    fun setup() { resolver = AngelPayCredentialResolver(terminalConfigRepo, crashlytics) }

    @Test
    fun `prefers backend angelpayAuth when present`() = runTest {
        every { terminalConfigRepo.getCachedConfig() } returns TerminalConfigDto(
            angelpayAuth = AngelPayAuthDto("acc-1", "a@b.co", "123456", "QA"),
        )
        val result = resolver.resolve()
        assertEquals("a@b.co", result.getOrThrow().email)
        assertEquals("backend", result.getOrThrow().source)
        verify(exactly = 0) { crashlytics.recordException(any()) }
    }

    @Test
    fun `falls back to BuildConfig with warning`() = runTest {
        every { terminalConfigRepo.getCachedConfig() } returns TerminalConfigDto(angelpayAuth = null)
        mockkStatic(BuildConfig::class)
        every { BuildConfig.ANGELPAY_QA_EMAIL } returns "legacy@x.co"
        every { BuildConfig.ANGELPAY_QA_PASSWORD } returns "999999"
        every { BuildConfig.ANGELPAY_ENV } returns "QA"

        val result = resolver.resolve()
        assertEquals("legacy@x.co", result.getOrThrow().email)
        assertEquals("buildconfig-fallback", result.getOrThrow().source)
        verify { crashlytics.recordException(any<DeprecatedBuildConfigCredsWarning>()) }
    }

    @Test
    fun `errors when both sources null`() = runTest {
        every { terminalConfigRepo.getCachedConfig() } returns TerminalConfigDto(angelpayAuth = null)
        mockkStatic(BuildConfig::class)
        every { BuildConfig.ANGELPAY_QA_EMAIL } returns ""
        every { BuildConfig.ANGELPAY_QA_PASSWORD } returns ""

        val result = resolver.resolve()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MissingAngelPayCredsError)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayCredentialResolverTest*"
```

Expected: FAIL with module-not-found.

- [ ] **Step 3: Implement the resolver**

```kotlin
package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.repository.TerminalConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

data class AngelPayCreds(
    val email: String,
    val pin: String,
    val environment: String,
    val source: String,  // "backend" | "buildconfig-fallback"
    val accountId: String? = null,
)

object DeprecatedBuildConfigCredsWarning : RuntimeException("AngelPay using deprecated hardcoded creds — delete in v3")
object MissingAngelPayCredsError : RuntimeException("AngelPay credentials missing from both backend config and BuildConfig")

@Singleton
class AngelPayCredentialResolver @Inject constructor(
    private val terminalConfigRepo: TerminalConfigRepository,
    private val crashlytics: FirebaseCrashlytics,
) {
    fun resolve(): Result<AngelPayCreds> {
        val backendCreds = terminalConfigRepo.getCachedConfig()?.angelpayAuth?.let {
            AngelPayCreds(
                email = it.email,
                pin = it.pin,
                environment = it.environment,
                source = "backend",
                accountId = it.accountId,
            )
        }
        if (backendCreds != null) return Result.success(backendCreds)

        val legacyEmail = BuildConfig.ANGELPAY_QA_EMAIL.takeIf { it.isNotBlank() }
        val legacyPassword = BuildConfig.ANGELPAY_QA_PASSWORD.takeIf { it.isNotBlank() }
        if (legacyEmail != null && legacyPassword != null) {
            crashlytics.log("AngelPay: using deprecated BuildConfig creds (delete in v3)")
            crashlytics.recordException(DeprecatedBuildConfigCredsWarning)
            return Result.success(
                AngelPayCreds(
                    email = legacyEmail,
                    pin = legacyPassword,
                    environment = BuildConfig.ANGELPAY_ENV,
                    source = "buildconfig-fallback",
                ),
            )
        }
        return Result.failure(MissingAngelPayCredsError)
    }
}
```

Note: this requires `BuildConfig.ANGELPAY_QA_EMAIL/PASSWORD` to still exist as empty strings during the 30-day grace period. Re-add them with empty defaults in `app/build.gradle.kts` for the `nexgo` flavor:

```kotlin
create("nexgo") {
    // Transition fallback — empty defaults; remove in v3 (post 30-day grace).
    buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
    buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
    buildConfigField("String", "SUPPORTED_PROCESSOR", "\"ANGELPAY\"")
    buildConfigField("String", "ANGELPAY_ENV", "\"QA\"")
}
```

Same for `nexgoProd`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayCredentialResolverTest*"
```

Expected: 3 PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(tpv): AngelPayCredentialResolver with backend-preferred + BuildConfig fallback (D4)"
```

---

## Task 26: Extend `AngelPaySdkGateway` with `getUserMerchants()` + `switchMerchant()`

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPaySdkGateway.kt`
- Create: `app/src/test/java/.../AngelPaySdkGatewayTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class AngelPaySdkGatewayTest {
    @Test fun `ensureInitialized succeeds on first call, no-op on subsequent`() { ... }
    @Test fun `getUserMerchants returns SDK list`() { ... }
    @Test fun `getUserMerchants offline returns Room cache`() { ... }
    @Test fun `switchMerchant SDK success returns Result success`() { ... }
    @Test fun `switchMerchant auth error returns categorized failure`() { ... }
    @Test fun `switchMerchant network error returns categorized failure`() { ... }
}
```

(Use `mockkStatic(AngelPaySDK::class)` to mock the SDK static calls.)

- [ ] **Step 2: Extend the gateway**

Add to existing `AngelPaySdkGateway.kt`:

```kotlin
suspend fun getUserMerchants(): Result<List<MerchantSummary>> = runCatching {
    val result = AngelPaySDK.getUserMerchants()
    result.fold(
        onSuccess = { it.map { m -> MerchantSummary(m.id, m.name, m.affiliationNumber, m.isActive) } },
        onFailure = { throw it },
    )
}

suspend fun switchMerchant(merchantId: Int): Result<Unit> = runCatching {
    val result = AngelPaySDK.switchMerchant(merchantId)
    result.fold(
        onSuccess = { Unit },
        onFailure = { throw mapSdkError(it) },
    )
}

private fun mapSdkError(error: Throwable): Throwable = when {
    error.message?.contains("auth", ignoreCase = true) == true -> AngelPayAuthExpiredError(error)
    error.message?.contains("network", ignoreCase = true) == true -> AngelPayNetworkError(error)
    else -> error
}
```

Define `MerchantSummary` data class in `AngelPayMerchantRepository.kt` (Task 28).

- [ ] **Step 3: Run tests**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPaySdkGatewayTest*"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(tpv): extend AngelPaySdkGateway with getUserMerchants + switchMerchant"
```

---

## Task 27: Implement `AngelPayMerchantCacheDao` + entity (Room)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayMerchantCacheEntity.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayMerchantCacheDao.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/database/AvoqadoDatabase.kt`

- [ ] **Step 1: Entity**

```kotlin
@Entity(tableName = "angelpay_merchant_cache")
data class AngelPayMerchantCacheEntity(
    @PrimaryKey val merchantId: Int,
    val name: String,
    val affiliationNumber: String,
    val isActive: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 2: DAO**

```kotlin
@Dao
interface AngelPayMerchantCacheDao {
    @Query("SELECT * FROM angelpay_merchant_cache ORDER BY name")
    fun observeAll(): Flow<List<AngelPayMerchantCacheEntity>>

    @Query("SELECT * FROM angelpay_merchant_cache ORDER BY name")
    suspend fun getAll(): List<AngelPayMerchantCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AngelPayMerchantCacheEntity>)

    @Query("DELETE FROM angelpay_merchant_cache")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(items: List<AngelPayMerchantCacheEntity>) {
        deleteAll()
        insertAll(items)
    }

    @Query("UPDATE angelpay_merchant_cache SET isActive = (merchantId = :merchantId)")
    suspend fun markActive(merchantId: Int)
}
```

- [ ] **Step 3: Register in `AvoqadoDatabase` + Migration**

```kotlin
@Database(
    version = N + 1,  // bump from current
    entities = [
        // ...existing entities...
        AngelPayMerchantCacheEntity::class,
    ],
)
abstract class AvoqadoDatabase : RoomDatabase() {
    abstract fun angelPayMerchantCacheDao(): AngelPayMerchantCacheDao
}

// Migration
val MIGRATION_N_NPLUS1 = object : Migration(N, N + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `angelpay_merchant_cache` (
                `merchantId` INTEGER NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `affiliationNumber` TEXT NOT NULL,
                `isActive` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

Add the migration to `DatabaseModule.addMigrations(MIGRATION_N_NPLUS1)`.

- [ ] **Step 4: Compile + test schema migration**

```bash
./gradlew compileSandboxDebugKotlin
# Manual: install old version → install new version → verify no crash (run on emulator)
```

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(tpv): Room cache for AngelPay user merchants + DB migration"
```

---

## Task 28: Implement `AngelPayMerchantRepository` (D2 race + D6 periodic + active state)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayMerchantRepository.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/PaymentStateProvider.kt`
- Create: `app/src/test/java/.../AngelPayMerchantRepositoryTest.kt`

- [ ] **Step 1: Define `MerchantSummary` + `PaymentStateProvider` interface**

```kotlin
// MerchantSummary lives at the top of AngelPayMerchantRepository.kt or in a sibling file
data class MerchantSummary(
    val id: Int,
    val name: String,
    val affiliationNumber: String,
    val isActive: Boolean,
)

// Interface to break circular dependency between Payment and Merchant repositories
interface PaymentStateProvider {
    fun isCharging(): Boolean
}
```

- [ ] **Step 2: Write failing tests** (covers D2 race, D6 periodic, multi-tap cancel, switch failure revert) — 8 tests per spec §10.1

```kotlin
class AngelPayMerchantRepositoryTest {
    @Test fun `completeInitialSelection success updates activeAngelPayMerchantId and cache`() { ... }
    @Test fun `switchActiveMerchant rejected when payment is charging`() { ... }
    @Test fun `switchActiveMerchant times out at 8 seconds`() { ... }
    @Test fun `switchActiveMerchant failure keeps previous activeId`() { ... }
    @Test fun `multi-tap cancels in-flight switch and starts fresh`() { ... }
    @Test fun `fetchAndCacheMerchants periodic refresh fires at 15min`() { ... }
    @Test fun `fetchAndCacheMerchants paused while app background`() { ... }
    @Test fun `refreshBeforeSelector invoked when switcher opens`() { ... }
}
```

- [ ] **Step 3: Implement the repository**

```kotlin
@Singleton
class AngelPayMerchantRepository @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val merchantCacheDao: AngelPayMerchantCacheDao,
    private val paymentStateProvider: PaymentStateProvider,
) {
    private val operationMutex = Mutex()
    private val _activeAngelPayMerchantId = MutableStateFlow<Int?>(null)
    val activeAngelPayMerchantId: StateFlow<Int?> = _activeAngelPayMerchantId.asStateFlow()

    private val _inFlightSwitch = MutableStateFlow<Int?>(null)
    val inFlightSwitch: StateFlow<Int?> = _inFlightSwitch.asStateFlow()

    private var currentSwitchJob: Job? = null
    private var periodicRefreshJob: Job? = null

    fun observeCachedMerchants(): Flow<List<MerchantSummary>> =
        merchantCacheDao.observeAll().map { it.map(::toDomain) }

    suspend fun fetchAndCacheMerchants(): Result<List<MerchantSummary>> = withContext(Dispatchers.IO) {
        sdkGateway.getUserMerchants().onSuccess { merchants ->
            merchantCacheDao.replaceAll(merchants.map(::toEntity))
            merchants.firstOrNull { it.isActive }?.id?.let { _activeAngelPayMerchantId.value = it }
        }
    }

    suspend fun refreshBeforeSelector(): Result<List<MerchantSummary>> = fetchAndCacheMerchants()

    suspend fun completeInitialSelection(merchantId: Int, temporaryToken: String): Result<Unit> =
        operationMutex.withLock {
            sdkGateway.selectMerchant(merchantId, temporaryToken).onSuccess {
                _activeAngelPayMerchantId.value = merchantId
                merchantCacheDao.markActive(merchantId)
            }
        }

    suspend fun switchActiveMerchant(merchantId: Int): Result<Unit> = coroutineScope {
        // Cancel any in-flight switch (multi-tap behavior)
        currentSwitchJob?.cancel()

        operationMutex.withLock {
            if (paymentStateProvider.isCharging()) {
                return@withLock Result.failure(SwitchBlockedDuringChargeError)
            }
            val previousActive = _activeAngelPayMerchantId.value
            _inFlightSwitch.value = merchantId

            val deferred = async {
                withTimeoutOrNull(8_000) {
                    sdkGateway.switchMerchant(merchantId)
                } ?: Result.failure(SwitchTimeoutError(merchantId))
            }
            currentSwitchJob = deferred

            try {
                val result = deferred.await()
                result.onSuccess {
                    _activeAngelPayMerchantId.value = merchantId
                    merchantCacheDao.markActive(merchantId)
                }.onFailure {
                    previousActive?.let { merchantCacheDao.markActive(it) }
                }
                result
            } finally {
                _inFlightSwitch.value = null
                currentSwitchJob = null
            }
        }
    }

    fun startPeriodicRefresh(scope: CoroutineScope, lifecycleOwner: LifecycleOwner) {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (isActive) {
                delay(15 * 60 * 1000L)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    fetchAndCacheMerchants()
                }
            }
        }
    }

    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    fun clearActive() {
        _activeAngelPayMerchantId.value = null
    }

    private fun toEntity(m: MerchantSummary) = AngelPayMerchantCacheEntity(m.id, m.name, m.affiliationNumber, m.isActive)
    private fun toDomain(e: AngelPayMerchantCacheEntity) = MerchantSummary(e.merchantId, e.name, e.affiliationNumber, e.isActive)
}

object SwitchBlockedDuringChargeError : RuntimeException("Cannot switch merchant while a payment is in progress")
data class SwitchTimeoutError(val targetMerchantId: Int) : RuntimeException("Merchant switch timed out after 8s")
```

- [ ] **Step 4: Run tests**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayMerchantRepositoryTest*"
```

Expected: 8 PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(tpv): AngelPayMerchantRepository with D2 Mutex + D6 periodic refresh + active StateFlow"
```

---

## Task 29: Implement `AngelPayConfigValidator` (D5 intersection)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayConfigValidator.kt`
- Create: `app/src/test/java/.../AngelPayConfigValidationTest.kt`

- [ ] **Step 1: Write failing tests** (5 tests per spec §10.1)

```kotlin
class AngelPayConfigValidationTest {
    @Test fun `all merchants match returns AllClear`() { ... }
    @Test fun `SDK has extra merchant returns PartialOperable with warning`() { ... }
    @Test fun `Avoqado has extra merchant returns PartialOperable with warning`() { ... }
    @Test fun `empty intersection returns HardBlock`() { ... }
    @Test fun `empty SDK list returns HardBlock`() { ... }
}
```

- [ ] **Step 2: Implement**

```kotlin
sealed class ValidationResult {
    object AllClear : ValidationResult()
    data class PartialOperable(
        val operableIds: Set<Int>,
        val onlyInSdk: Set<Int>,
        val onlyInAvoqado: Set<Int>,
    ) : ValidationResult()
    data class HardBlock(val message: String) : ValidationResult()
}

@Singleton
class AngelPayConfigValidator @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val crashlytics: FirebaseCrashlytics,
) {
    suspend fun validate(config: TerminalConfigDto): ValidationResult {
        val sdkMerchantIds = sdkGateway.getUserMerchants().getOrNull()
            ?.map { it.id }?.toSet().orEmpty()
        val avoqadoMerchantIds = config.merchants
            .filter { it.providerCode == "ANGELPAY" && it.isActive }
            .mapNotNull { it.externalMerchantId?.toIntOrNull() }
            .toSet()

        val intersection = sdkMerchantIds intersect avoqadoMerchantIds
        val onlyInSdk = sdkMerchantIds - avoqadoMerchantIds
        val onlyInAvoqado = avoqadoMerchantIds - sdkMerchantIds

        return when {
            intersection.isEmpty() -> ValidationResult.HardBlock(
                "Sin merchants válidos compartidos entre AngelPay y Avoqado. Contacta soporte.",
            )
            onlyInSdk.isNotEmpty() || onlyInAvoqado.isNotEmpty() -> {
                crashlytics.recordException(AngelPayConfigMismatchInfo(onlyInSdk, onlyInAvoqado))
                ValidationResult.PartialOperable(intersection, onlyInSdk, onlyInAvoqado)
            }
            else -> ValidationResult.AllClear
        }
    }
}

data class AngelPayConfigMismatchInfo(val onlyInSdk: Set<Int>, val onlyInAvoqado: Set<Int>) :
    RuntimeException("AngelPay config mismatch — SDK: $onlyInSdk, Avoqado: $onlyInAvoqado")
```

- [ ] **Step 3: Run tests**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayConfigValidationTest*"
```

Expected: 5 PASS.

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(tpv): AngelPayConfigValidator with intersection logic (D5)"
```

---

## Task 30: Implement `AngelPayAuthRepository` + `AngelPayAuthState`

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayAuthState.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayAuthRepository.kt`
- Create: `app/src/test/java/.../AngelPayAuthRepositoryTest.kt`

- [ ] **Step 1: Define state**

```kotlin
sealed class AngelPayAuthState {
    object Unauthenticated : AngelPayAuthState()
    object Authenticating : AngelPayAuthState()
    data class SelectingMerchant(val merchants: List<MerchantSummary>, val temporaryToken: String) : AngelPayAuthState()
    object Authenticated : AngelPayAuthState()
    data class AuthError(val message: String) : AngelPayAuthState()
    data class AccountSuspended(val status: AngelPayAccountStatus, val reason: String?) : AngelPayAuthState()
    data class ConfigMismatchBanner(val onlyInSdk: Set<Int>, val onlyInAvoqado: Set<Int>) : AngelPayAuthState()
}

enum class AngelPayAccountStatus { PENDING_PIN, ACTIVE, PIN_ROTATION_REQUIRED, SUSPENDED, DELETED }
```

- [ ] **Step 2: Write failing tests** (10 tests per spec §10.1)

```kotlin
class AngelPayAuthRepositoryTest {
    @Test fun `ensureAuthenticated with backend creds emits Authenticated on success`() { ... }
    @Test fun `ensureAuthenticated with backend creds emits SelectingMerchant on MerchantSelectionRequired`() { ... }
    @Test fun `ensureAuthenticated invalid PIN emits AuthError + reports failure`() { ... }
    @Test fun `ensureAuthenticated network timeout retries 3x with backoff`() { ... }
    @Test fun `ensureAuthenticated all retries exhausted surfaces error`() { ... }
    @Test fun `ensureAuthenticated refuses when account status not ACTIVE`() { ... }
    @Test fun `handleAuthExpiry re-auths and retries once`() { ... }
    @Test fun `handleAuthExpiry second failure surfaces error`() { ... }
    @Test fun `state transitions cover all enum values`() { ... }
    @Test fun `logout clears in-memory PIN and resets state`() { ... }
}
```

- [ ] **Step 3: Implement** (sketch — full ~150 lines)

```kotlin
@Singleton
class AngelPayAuthRepository @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val credentialResolver: AngelPayCredentialResolver,
    private val configValidator: AngelPayConfigValidator,
    private val terminalConfigRepo: TerminalConfigRepository,
    private val merchantRepo: AngelPayMerchantRepository,
    private val angelPayReportApi: AngelPayReportApi,
    private val crashlytics: FirebaseCrashlytics,
) {
    private val _state = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Unauthenticated)
    val state: StateFlow<AngelPayAuthState> = _state.asStateFlow()

    suspend fun ensureAuthenticated(): Result<Unit> = withContext(Dispatchers.IO) {
        if (sdkGateway.isAuthenticated()) {
            _state.value = AngelPayAuthState.Authenticated
            return@withContext Result.success(Unit)
        }

        // Check account status from backend config
        val angelpayAuth = terminalConfigRepo.getCachedConfig()?.angelpayAuth
        // Status was checked server-side before angelpayAuth was included; if angelpayAuth is null, no ACTIVE account exists

        val credsResult = credentialResolver.resolve()
        if (credsResult.isFailure) {
            _state.value = AngelPayAuthState.AuthError("AngelPay no configurado")
            return@withContext Result.failure(credsResult.exceptionOrNull()!!)
        }
        val creds = credsResult.getOrThrow()

        _state.value = AngelPayAuthState.Authenticating
        val authResult = retryWithBackoff(maxAttempts = 3) {
            sdkGateway.authenticateSimple(creds.email, creds.pin)
        }

        authResult.fold(
            onSuccess = { result ->
                when (result) {
                    is AuthenticateSimpleResult.Success -> {
                        _state.value = AngelPayAuthState.Authenticated
                        creds.accountId?.let { reportValidation(it, success = true) }
                        runConfigValidation()
                        Result.success(Unit)
                    }
                    is AuthenticateSimpleResult.MerchantSelectionRequired -> {
                        val summaries = result.merchants.map { MerchantSummary(it.id, it.name, it.affiliationNumber, false) }
                        _state.value = AngelPayAuthState.SelectingMerchant(summaries, result.temporaryToken)
                        Result.success(Unit)
                    }
                }
            },
            onFailure = { error ->
                _state.value = AngelPayAuthState.AuthError(error.message ?: "Unknown auth error")
                creds.accountId?.let { reportValidation(it, success = false, error = error.message) }
                crashlytics.recordException(error)
                Result.failure(error)
            },
        )
    }

    suspend fun completeMerchantSelection(merchantId: Int, tempToken: String): Result<Unit> {
        val result = merchantRepo.completeInitialSelection(merchantId, tempToken)
        if (result.isSuccess) {
            _state.value = AngelPayAuthState.Authenticated
            runConfigValidation()
        }
        return result
    }

    suspend fun handleAuthExpiry(): Result<Unit> {
        sdkGateway.logout()  // clear stale session
        return ensureAuthenticated()
    }

    fun logout() {
        sdkGateway.logout()
        merchantRepo.clearActive()
        _state.value = AngelPayAuthState.Unauthenticated
    }

    private suspend fun runConfigValidation() {
        val config = terminalConfigRepo.getCachedConfig() ?: return
        val result = configValidator.validate(config)
        when (result) {
            is ValidationResult.AllClear -> {} // nothing to surface
            is ValidationResult.PartialOperable -> {
                _state.value = AngelPayAuthState.ConfigMismatchBanner(result.onlyInSdk, result.onlyInAvoqado)
                runCatching {
                    angelPayReportApi.reportValidation(
                        AngelPayReportApi.ConfigMismatchRequest(
                            accountId = terminalConfigRepo.getCachedConfig()?.angelpayAuth?.accountId ?: return@runCatching,
                            state = "CONFIG_MISMATCH",
                            missingInAvoqado = result.onlyInSdk.toList(),
                            missingInSdk = result.onlyInAvoqado.toList(),
                        ),
                    )
                }
            }
            is ValidationResult.HardBlock -> {
                _state.value = AngelPayAuthState.AuthError(result.message)
            }
        }
    }

    private suspend fun <T> retryWithBackoff(maxAttempts: Int, block: suspend () -> Result<T>): Result<T> {
        repeat(maxAttempts - 1) { attempt ->
            val result = block()
            if (result.isSuccess) return result
            delay((500L * (1L shl attempt)))  // 500ms, 1s, 2s
        }
        return block()
    }

    private suspend fun reportValidation(accountId: String, success: Boolean, error: String? = null) {
        runCatching {
            angelPayReportApi.reportValidation(
                AngelPayReportApi.ValidationRequest(
                    accountId = accountId,
                    state = if (success) "AUTHENTICATED" else "AUTH_ERROR",
                    externalUserId = sdkGateway.getSessionInfo()?.userId,
                    error = error,
                ),
            )
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayAuthRepositoryTest*"
```

Expected: 10 PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(tpv): AngelPayAuthRepository with state machine + retry + D5 config validation"
```

---

## Task 31: Refactor `AngelPaySdkGateway.ensureAuthenticated` to use resolver instead of static creds

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPaySdkGateway.kt`

- [ ] **Step 1: Replace BuildConfig-direct reads**

The existing `ensureAuthenticated()` in `AngelPaySdkGateway.kt` reads creds from `BuildConfig` directly. Refactor it to call `AngelPayAuthRepository.ensureAuthenticated()` instead (or inject `AngelPayCredentialResolver` and use it).

Actually, the cleaner architecture per D2/D3 is to **delete `AngelPaySdkGateway.ensureAuthenticated`** and have callers go through `AngelPayAuthRepository.ensureAuthenticated()` instead. `AngelPaySdkGateway` becomes a pure thin wrapper over SDK static methods.

- [ ] **Step 2: Update all callers**

Find callers of `AngelPaySdkGateway.ensureAuthenticated`:

```bash
grep -rn "sdkGateway.ensureAuthenticated\|AngelPaySdkGateway.*ensureAuthenticated" app/src/main app/src/nexgo
```

Replace with `angelPayAuthRepository.ensureAuthenticated()`.

- [ ] **Step 3: Compile + run tests**

```bash
./gradlew testNexgoDebugUnitTest
```

- [ ] **Step 4: Commit**

```bash
git commit -am "refactor(tpv): route auth through AngelPayAuthRepository; gateway becomes pure SDK wrapper"
```

---

## Task 32: Modify `AngelPayPaymentViewModel` — state extension + payment-time guard (D2)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt`

- [ ] **Step 1: Extend `AngelPayPaymentState`**

In the file where `AngelPayPaymentState` is defined:

```kotlin
sealed class AngelPayPaymentState {
    object Idle : AngelPayPaymentState()
    // ...existing states (Authenticating, Approving, Approved, Error, etc.)...
    data class Switching(val targetMerchantId: Int, val previousMerchantId: Int?) : AngelPayPaymentState()
    data class Charging(val merchantId: Int, val startedAt: Long) : AngelPayPaymentState()
}
```

- [ ] **Step 2: Inject new repositories + implement `PaymentStateProvider`**

```kotlin
@HiltViewModel
class AngelPayPaymentViewModel @Inject constructor(
    // ...existing deps...
    private val angelPayAuthRepository: AngelPayAuthRepository,
    private val angelPayMerchantRepository: AngelPayMerchantRepository,
) : ViewModel(), PaymentStateProvider {

    override fun isCharging(): Boolean = _state.value is AngelPayPaymentState.Charging

    // ...existing fields...
}
```

Bind `PaymentStateProvider` in Hilt:

```kotlin
// di/AppModule.kt (Nexgo flavor source set)
@Provides
fun providePaymentStateProvider(vm: AngelPayPaymentViewModel): PaymentStateProvider = vm
```

Actually, since ViewModel scoping doesn't work for Singleton-scoped repository, use an alternative: shared `PaymentStateProvider` Singleton that ViewModel writes to.

```kotlin
@Singleton
class PaymentStateHolder @Inject constructor() : PaymentStateProvider {
    private val charging = AtomicBoolean(false)
    override fun isCharging(): Boolean = charging.get()
    fun setCharging(value: Boolean) { charging.set(value) }
}
```

ViewModel updates `paymentStateHolder.setCharging(true/false)` around its Charging state transitions.

- [ ] **Step 3: Rewrite existing `selectMerchant(merchant)` (line 357)**

```kotlin
fun selectMerchant(merchant: MerchantAccount) {
    viewModelScope.launch {
        val merchantId = merchant.requireAngelpayMerchantId()
        _currentMerchant.value = merchant

        val authState = angelPayAuthRepository.state.value
        val result = when (authState) {
            is AngelPayAuthState.SelectingMerchant -> {
                angelPayAuthRepository.completeMerchantSelection(merchantId, authState.temporaryToken)
            }
            is AngelPayAuthState.Authenticated -> {
                angelPayMerchantRepository.switchActiveMerchant(merchantId)
            }
            else -> Result.failure(IllegalStateException("Cannot switch in state $authState"))
        }

        result.onFailure { error ->
            // Revert _currentMerchant to previous
            // ...emit error toast via _state...
            _state.value = AngelPayPaymentState.Error("No se pudo cambiar de merchant: ${error.message}")
        }
    }
}
```

- [ ] **Step 4: Add payment-time guard in `startPayment()`**

```kotlin
fun startPayment(context: Context, amountCents: Long, tipCents: Long? = null, msi: Int? = null) {
    viewModelScope.launch {
        val targetMerchantId = _currentMerchant.value?.requireAngelpayMerchantId() ?: run {
            _state.value = AngelPayPaymentState.Error("Merchant no seleccionado")
            return@launch
        }
        val activeId = angelPayMerchantRepository.activeAngelPayMerchantId.value
        if (activeId != targetMerchantId) {
            _state.value = AngelPayPaymentState.Switching(targetMerchantId, activeId)
            val completed = withTimeoutOrNull(8_000) {
                angelPayMerchantRepository.activeAngelPayMerchantId
                    .first { it == targetMerchantId }
            }
            if (completed == null) {
                _state.value = AngelPayPaymentState.Error("Cambio de merchant no se completó. Reintenta.")
                return@launch
            }
        }

        _state.value = AngelPayPaymentState.Charging(targetMerchantId, System.currentTimeMillis())
        paymentStateHolder.setCharging(true)
        try {
            // ...existing createPaymentIntent + launcher flow...
        } finally {
            paymentStateHolder.setCharging(false)
        }
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Add 5 new tests per spec §10.1 (payment waits for switch, errors after 8s, intersection enforcement, etc.). Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(tpv): AngelPayPaymentViewModel — D2 payment-time guard + new states + new selectMerchant"
```

---

## Task 33: Create `AngelPayAuthBanner` + `AngelPayMerchantSwitcherSheet` (D6 refresh on open)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayAuthBanner.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayMerchantSwitcherSheet.kt`

- [ ] **Step 1: AuthBanner**

```kotlin
@Composable
fun AngelPayAuthBanner(state: AngelPayAuthState, activeMerchantName: String?) {
    val (text, color) = when (state) {
        is AngelPayAuthState.Authenticated -> "AngelPay: ${activeMerchantName ?: "—"}" to Color.Green
        is AngelPayAuthState.Authenticating -> "AngelPay: autenticando..." to Color.Yellow
        is AngelPayAuthState.SelectingMerchant -> "AngelPay: selecciona merchant" to Color.Yellow
        is AngelPayAuthState.AuthError -> "AngelPay: ${state.message}" to Color.Red
        is AngelPayAuthState.AccountSuspended -> "AngelPay: cuenta ${state.status}" to Color.Red
        is AngelPayAuthState.ConfigMismatchBanner -> "AngelPay: ${state.onlyInSdk.size + state.onlyInAvoqado.size} merchants desincronizados" to Color.Yellow
        AngelPayAuthState.Unauthenticated -> "AngelPay: requiere autenticación" to Color.Red
    }
    Row(modifier = Modifier.fillMaxWidth().background(color)) {
        Text(text, color = Color.White, modifier = Modifier.padding(8.dp))
    }
}
```

- [ ] **Step 2: SwitcherSheet (D6 refresh on open)**

```kotlin
@Composable
fun AngelPayMerchantSwitcherSheet(
    merchants: List<MerchantSummary>,
    activeId: Int?,
    inFlightSwitchId: Int?,
    onRefresh: suspend () -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }  // D6: refresh before showing
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            Text("Cambiar merchant", style = MaterialTheme.typography.titleLarge)
            merchants.forEach { merchant ->
                val isActive = merchant.id == activeId
                val isSwitching = merchant.id == inFlightSwitchId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSwitching) { onPick(merchant.id) }
                        .padding(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(merchant.name)
                        Text(merchant.affiliationNumber, style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        isSwitching -> CircularProgressIndicator(Modifier.size(20.dp))
                        isActive -> Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(tpv): AngelPayAuthBanner + AngelPayMerchantSwitcherSheet with D6 refresh"
```

---

## Task 34: Modify `AngelPayPaymentScreen` to mount banner + switcher

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt`

- [ ] **Step 1: Add banner at top + switcher chip**

```kotlin
@Composable
fun AngelPayPaymentScreen(viewModel: AngelPayPaymentViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val activeMerchantName by viewModel.activeMerchantName.collectAsStateWithLifecycle()
    val merchants by viewModel.cachedMerchants.collectAsStateWithLifecycle()
    val inFlightSwitch by viewModel.inFlightSwitch.collectAsStateWithLifecycle()
    var showSwitcher by remember { mutableStateOf(false) }

    Column {
        AngelPayAuthBanner(authState, activeMerchantName)

        // Existing payment UI...

        // Top-bar chip for switching
        ChipButton(
            text = activeMerchantName ?: "Sin merchant",
            onClick = { showSwitcher = true },
        )
    }

    if (showSwitcher) {
        AngelPayMerchantSwitcherSheet(
            merchants = merchants,
            activeId = viewModel.activeAngelPayMerchantId.value,
            inFlightSwitchId = inFlightSwitch,
            onRefresh = { viewModel.refreshMerchants() },
            onPick = { merchantId ->
                viewModel.selectMerchantById(merchantId)
                showSwitcher = false
            },
            onDismiss = { showSwitcher = false },
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git commit -am "feat(tpv): wire AuthBanner + SwitcherSheet into AngelPayPaymentScreen"
```

---

## Task 35: Implement `AngelPayPinHandlingTest` (§4.5b mandatory rules)

**Files:**
- Create: `app/src/test/java/.../AngelPayPinHandlingTest.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/RedactingLoggingInterceptor.kt` (create if missing)
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/repository/TerminalConfigRepository.kt` (strip `angelpayAuth.pin` before disk cache)

- [ ] **Step 1: Write the 3 PIN tests**

```kotlin
class AngelPayPinHandlingTest {
    @Test
    fun `TerminalConfigRepository strips angelpayAuth pin before persisting to Room`() {
        val config = TerminalConfigDto(
            angelpayAuth = AngelPayAuthDto("acc-1", "a@b.co", "123456", "QA"),
            // ...other fields
        )
        val sanitized = repo.sanitizeForDiskCache(config)
        assertNull(sanitized.angelpayAuth?.pin?.takeIf { it != "***" })
    }

    @Test
    fun `RedactingLoggingInterceptor redacts pin field in OkHttp logs`() {
        val body = """{"angelpayAuth":{"pin":"123456","email":"a@b.co"}}"""
        val redacted = interceptor.redactJsonFields(body)
        assertTrue(redacted.contains(""""pin":"***""""))
        assertFalse(redacted.contains("123456"))
    }

    @Test
    fun `Crashlytics asserter throws on 6-digit numeric string in debug builds`() {
        if (!BuildConfig.DEBUG) return  // assertion only enforced in debug
        assertThrows(IllegalArgumentException::class.java) {
            CrashlyticsAsserter.setCustomKey("note", "123456")
        }
    }
}
```

- [ ] **Step 2: Implement the redacting interceptor**

```kotlin
class RedactingLoggingInterceptor(private val logger: HttpLoggingInterceptor.Logger) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.peekBody(Long.MAX_VALUE).string()
        logger.log(redactJsonFields(body))
        return response
    }

    fun redactJsonFields(body: String): String =
        body.replace(Regex(""""pin"\s*:\s*"[^"]*""""), """"pin":"***"""")
            .replace(Regex(""""password"\s*:\s*"[^"]*""""), """"password":"***"""")
}
```

- [ ] **Step 3: Modify `TerminalConfigRepository.cache()` to strip pin**

```kotlin
private fun sanitizeForDiskCache(config: TerminalConfigDto): TerminalConfigDto =
    config.copy(angelpayAuth = config.angelpayAuth?.copy(pin = "***"))
```

- [ ] **Step 4: Add Crashlytics asserter wrapper**

```kotlin
object CrashlyticsAsserter {
    fun setCustomKey(key: String, value: String) {
        if (BuildConfig.DEBUG && value.matches(Regex("""\d{6}"""))) {
            throw IllegalArgumentException("Crashlytics key '$key' looks like a PIN: $value")
        }
        FirebaseCrashlytics.getInstance().setCustomKey(key, value)
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayPinHandlingTest*"
```

Expected: 3 PASS.

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(tpv): enforce §4.5b PIN handling rules (Room strip, OkHttp redact, Crashlytics asserter)"
```

---

## Task 36: Add Hilt graph + PAX startup smoke tests (§17.5 + §6.4 Pattern A)

**Files:**
- Create: `app/src/test/java/.../NexgoFlavorHiltGraphTest.kt`
- Create: `app/src/test/java/.../PaxStartupSmokeTest.kt`

- [ ] **Step 1: Hilt graph test (nexgo)**

```kotlin
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class NexgoFlavorHiltGraphTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Test fun `Hilt graph in nexgo flavor does not eagerly instantiate BlumonInitializer`() {
        // Use reflection or Hilt introspection to verify BlumonInitializer is not in the eager-init list
        // for the nexgo flavor
    }

    @Test fun `Hilt graph in nexgo flavor does not eagerly instantiate MultiMerchantSDKManager`() {
        // Same as above for MultiMerchantSDKManager
    }
}
```

- [ ] **Step 2: PAX startup smoke**

```kotlin
@RunWith(AndroidJUnit4::class)
class PaxStartupSmokeTest {
    @Test fun `sandboxDebug Application onCreate completes without ClassNotFoundException`() {
        val context = ApplicationProvider.getApplicationContext<AvoqadoTPVApplication>()
        // Verify lifecycle ran without throwing
        assertNotNull(context)
    }

    @Test fun `productionRelease Application onCreate does not load AngelPay symbols`() {
        // Same approach for productionRelease
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew testSandboxDebugUnitTest --tests "*PaxStartupSmokeTest*"
./gradlew testNexgoDebugUnitTest --tests "*NexgoFlavorHiltGraphTest*"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git commit -am "test(tpv): Hilt graph + PAX startup smoke tests for §17.5 + §6.4"
```

---

## Task 37: `AngelPayErrorMapper` tests

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/AngelPayErrorMapper.kt`
- Create: `app/src/test/java/.../AngelPayErrorMapperTest.kt`

- [ ] **Step 1: Write tests + impl per spec §6.10**

```kotlin
object AngelPayErrorMapper {
    fun toUserMessage(status: String?, code: String?, callResult: CallResult?): String = when {
        status == "DECLINED" -> "Tarjeta declinada (${code ?: "—"})"
        status == "CANCELLED" -> "Pago cancelado"
        status == "TIMEOUT" -> "Tiempo agotado, intenta de nuevo"
        callResult?.category == "GATEWAY" -> "Error del banco: ${callResult.message}"
        callResult?.category == "USER" -> callResult.message ?: "Operación interrumpida"
        callResult?.category == "EMV" -> "Error de chip, intenta nuevamente"
        callResult?.category == "NETWORK" -> "Sin red, reintentando..."
        else -> callResult?.message ?: "Error desconocido"
    }

    fun isAuthError(code: String?): Boolean = code?.startsWith("C2") == true
}
```

Tests cover all branches.

- [ ] **Step 2: Run + commit**

```bash
./gradlew testNexgoDebugUnitTest --tests "*AngelPayErrorMapperTest*"
git commit -am "feat(tpv): AngelPayErrorMapper with tests for known SDK code categories"
```

---

## Task 38: Full TPV test regression + commit

**Files:** none

- [ ] **Step 1: Run full TPV test suite for both flavors**

```bash
./gradlew testSandboxDebugUnitTest --rerun-tasks
./gradlew testNexgoDebugUnitTest --rerun-tasks
```

Expected:
- sandboxDebug: 220 tests, 0 failures (Blumon regression gate per §17.6)
- nexgoDebug: 46+ new tests, 0 failures

- [ ] **Step 2: APK packaging verification (§17.5)**

```bash
./gradlew assembleProductionRelease
unzip -l app/build/outputs/apk/production/release/app-production-release-unsigned.apk | grep -i angelpay
# Expected: no output

./gradlew assembleNexgoDebug
unzip -l app/build/outputs/apk/nexgo/debug/app-nexgo-debug.apk | grep -E "lib/(armeabi|arm64-v8a)/"
# Expected: only arm64-v8a entries
unzip -l app/build/outputs/apk/nexgo/debug/app-nexgo-debug.apk | grep -iE "libcyber|libpax"
# Expected: no output (or zero-byte stub)
```

- [ ] **Step 3: Push branch and open PR**

```bash
git push origin feat/angelpay-multimerchant-tpv
gh pr create --title "feat(tpv): AngelPay SDK 1.0.5 + multi-merchant runtime switching" \
  --body "$(cat <<'EOF'
## Summary
- Upgrade AngelPay SDK 1.0.4 → 1.0.5
- Wire `getUserMerchants()` + `switchMerchant()` for runtime multi-merchant switching
- Replace BuildConfig hardcoded creds with backend-sourced + D4 fallback
- Add `AngelPayAuthRepository` + `AngelPayMerchantRepository` (D2 Mutex + D6 periodic refresh)
- §4.5b PIN handling rules enforced (Room strip, OkHttp redact, Crashlytics asserter)
- §17.5 packaging: PAX APK does not include AngelPay AAR; Nexgo APK excludes PAX native `.so`

## Test plan
- [ ] sandboxDebug: 220 tests pass
- [ ] nexgoDebug: 46+ new tests pass
- [ ] productionRelease APK does not contain `angelpay` strings
- [ ] PAX A910S manual smoke: orders, Blumon payment, refund, print
- [ ] Nexgo N86 manual smoke: multi-merchant login + switch + payment + auth banner states

Implements spec `docs/superpowers/specs/2026-05-14-angelpay-sdk-1.0.5-migration-design.md` v2.5
EOF
)"
```

Phase 3 complete. Wait for code review + merge before Phase 4.

---

# Phase 4 — QA on real Nexgo N86

## Task 39: Manual QA checklist on real Nexgo N86

**Files:** none (operational)

Install `nexgoDebug` APK on a real Nexgo N86 with the QA test user provisioned in the backend (vault credentials per §16).

Run through every scenario from spec §10.3. For each, capture: screenshot, adb logcat snippet, and check.

**Shipped UX scenarios (must pass):**

- [ ] 1. Cold start → SDK initializes (logcat: `AngelPaySdkGateway: ensureInitialized OK`)
- [ ] 2. First payment trigger → auth → MerchantSelectionRequired → pick first → payment OK
- [ ] 3. Mid-shift switch via top-bar chip → second merchant active
- [ ] 4. Process payment with tip on restaurant-type merchant
- [ ] 5. Reject tip on retail-type merchant (validation error visible)
- [ ] 6. Auth banner transitions: green → yellow on WiFi off → red after timeout → green on WiFi back
- [ ] 7. Merchant switch fails offline → reverts to previous merchant + toast
- [ ] 8. Token expiry → auto re-auth + retry payment succeeds
- [ ] 9. Logout → re-login flow
- [ ] 10. PAX A910S regression (separate device): orders + Blumon payment + tip + split + refund + print

**Adapter smoke-only (not shipped UX):**

- [ ] 11. Cancel via dev menu → success
- [ ] 12. Refund via dev menu → success (or document failure; if fails, keep refund admin-only)
- [ ] 13. Transaction history sync via dev menu
- [ ] 14. MSI capability surfaced (no plan picker UI in MVP)
- [ ] 15. Digital ticket email adapter invocation

For any failure: capture logs, file an issue, fix, re-test. Block production rollout until all "must pass" scenarios are green.

---

# Phase 5 — Production Rollout

## Task 40: Production deploy sequence

**Files:** `CHANGELOG.md`, `app/build.gradle.kts` (version bump)

- [ ] **Step 1: Deploy backend to production**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git checkout main && git pull
# Backend already deployed in Phase 1 closing step. Verify health.
```

- [ ] **Step 2: Configure first production AngelPay venue**

1. AngelPay creates production user (email + PIN) for the pilot venue
2. Avoqado admin logs into superadmin dashboard
3. Navigate to Venue → AngelPay Account → Create → enter creds
4. Status transitions PENDING_PIN → ACTIVE (PIN entered immediately)
5. Create MerchantAccount(s) with AngelPay-specific fields (externalMerchantId numeric + affiliation + name)

- [ ] **Step 3: Bump TPV version to MINOR**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv
# In app/build.gradle.kts, bump versionCode (+1) and versionName (e.g., 2.0.0 → 2.1.0)
```

Per `.claude/rules/release-and-git.md`: "Can user do something new?" → YES (multi-merchant AngelPay) → MINOR.

- [ ] **Step 4: Update CHANGELOG.md release entry**

Convert `## [Unreleased]` to `## [2.1.0] - 2026-05-XX` and add a fresh `## [Unreleased]` above it.

- [ ] **Step 5: Build production APK + sign with apksigner v2**

Use the existing release ceremony (`avoqado:release-production` skill if available, else):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew assembleNexgoProdRelease

~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out ~/Desktop/avoqado-tpv-2.1.0-nexgoProd-signed.apk \
  app/build/outputs/apk/nexgoProd/release/app-nexgoProd-release-unsigned.apk

~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose ~/Desktop/avoqado-tpv-2.1.0-nexgoProd-signed.apk
# Expected: "Verified using v2 scheme: true"
```

- [ ] **Step 6: Save to iCloud + send to AngelPay for Nexgo signing**

```bash
mkdir -p "$HOME/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK/2.1.0/nexgo"
mv ~/Desktop/avoqado-tpv-2.1.0-nexgoProd-signed.apk "$HOME/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK/2.1.0/nexgo/"
```

Email AngelPay technical contact requesting Nexgo signing (3-5 day turnaround).

- [ ] **Step 7: Tag the release in git**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "release(v2.1.0): AngelPay SDK 1.0.5 + multi-merchant runtime switching"
git tag -a v2.1.0 -m "v2.1.0 — AngelPay multi-merchant"
git push origin main --tags
```

- [ ] **Step 8: Deploy signed APK via remote install command**

Once AngelPay returns the Nexgo-signed APK, use the dashboard's `INSTALL_VERSION` command (per `docs/SELF_UPDATE_SYSTEM.md`) to push it to pilot terminal(s).

- [ ] **Step 9: Monitor for 48 hours**

- Heartbeat: confirm `angelpayAuthState: AUTHENTICATED` per pilot terminal
- Crashlytics: zero AngelPay-related crashes
- Backend logs: `lastValidatedAt` recent for each pilot venue
- Dashboard health: `AngelPayUserAccount` status panel for each venue

- [ ] **Step 10: Gradual rollout**

If 48h is clean, schedule rollout to next batch of venues. Track per-venue migration in a spreadsheet (vault).

---

# Phase 6 — Post-Launch (operational, not part of MVP delivery)

After 30-day grace period:
- Delete `BuildConfig.ANGELPAY_QA_EMAIL/PASSWORD/AFFILIATION/COMMERCE_TOKEN` (and the fallback path in `AngelPayCredentialResolver`)
- Bump TPV to v3 with this cleanup
- Toggle backend config flag `angelpayDualSourceEnabled = false`
- Final cleanup commit: `chore(tpv): remove BuildConfig AngelPay cred fallback (30-day grace ended)`

Defer to later phases (Phase 2+ in product roadmap):
- MSI plan picker UI
- Digital ticket email post-payment screen
- Print receipt via SDK fallback
- Unified Card Reader UX (loyalty / membership)
- Hotel check-in support
- Sophisticated AngelPay status panel in dashboard

---

## Self-Review

**Spec coverage check** (every section from `2026-05-14-angelpay-sdk-1.0.5-migration-design.md`):
- §1 Context — informs the introduction; no tasks needed
- §2 Goals/Non-goals — G1-G10 mapped to Tasks 1, 26-34 (TPV) + 4-14 (backend) + 15-20 (dashboard) + 39 (QA) + 40 (rollout)
- §3 Architecture 3-layer — Layer 1 (coupling) Tasks 8-13; Layer 2 (AngelPayUserAccount) Task 4 + 9; Layer 3 (reliability) Tasks 28-30
- §4 Backend changes — Tasks 4-14 cover all 6 sub-sections
- §5 Dashboard changes — Tasks 15-20 cover all 5 sub-sections
- §6 TPV changes — Tasks 21-37 cover all 13 sub-sections (6.1 gradle, 6.2 refactor map, 6.3 new files, 6.4 init, 6.5 auth, 6.6 merchant repo, 6.7 payment VM, 6.8 selector hook, 6.9 mapping + D5 validation, 6.10 error mapper, 6.11 reliability, 6.12 observability, 6.13 receipt)
- §7 Reliability — embedded in Tasks 28-30 (retry/cache/state machine)
- §8 Observability — Tasks 14 (backend) + 30 (TPV Crashlytics)
- §9 Permissions — covered implicitly in TPV permission checks (no new task needed; existing system applies)
- §10 Testing — Tasks 8, 9, 10, 13, 14 (backend tests) + 25, 26, 28, 29, 30, 35, 36, 37 (TPV tests); §10.3 → Task 39
- §11 Rollout — Tasks 38-40
- §12 Phase 2 deferred — explicitly listed in Phase 6
- §13 Risks — addressed via §17 guardrails + §17.5 packaging (Task 21 + 38)
- §14 Open questions — flagged in Tasks 26 (SDK auth error semantics), 39 (token expiry detection), 40 (Nexgo signing process)
- §15 Glossary — informs naming throughout
- §16 References — vault paths used in Task 2 + Task 40
- §17 Guardrails — enforced via §17.6 regression suite in Task 38 + §17.5 in Task 21
- §18 D2-D7 — Tasks 28 (D2 + D6), 4+9 (D3), 25 (D4), 29 (D5), 32 (payment-time guard)

All spec sections covered. ✓

**Placeholder scan** — searched for: "TBD", "TODO", "implement later", "fill in details", "appropriate error handling", "similar to Task N", "etc.". One acceptable use of "/* fixture */" in test snippets (engineer fills the project-specific Venue factory). One acceptable use of "TBD" in the source spec around vendor unknowns (auth error code regex, Nexgo signing process) — those are not plan failures, they're documented open questions that get resolved as part of Task 26 + 40 with the vendor. ✓

**Type consistency check:**
- `AngelPayAuthRepository.ensureAuthenticated()` returns `Result<Unit>` — consistent in Tasks 30, 31, 32
- `AngelPayMerchantRepository.switchActiveMerchant(Int): Result<Unit>` — consistent in Tasks 28, 32
- `MerchantAccount.requireAngelpayMerchantId(): Int` — consistent in Tasks 24, 32
- `MerchantSummary(id: Int, name: String, affiliationNumber: String, isActive: Boolean)` — consistent in Tasks 26, 27, 28
- `AngelPayAuthState` sealed class names — consistent in Tasks 30, 33, 34
- `AngelPayPaymentState.Switching` + `Charging` data classes — consistent in Tasks 32, 33
- `PROVIDER_DEVICE_COMPATIBILITY` — same shape across Tasks 8 (backend), 17 + 20 (dashboard), 21 (TPV BuildConfig.SUPPORTED_PROCESSOR is the TPV-side enforcement, not the constant)

All consistent. ✓

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-17-angelpay-sdk-105-multimerchant.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per Task, review between Tasks, fast iteration. Best for a 40-Task plan because each Task is self-contained and a subagent can hold the necessary context (one repo's surface area) cleanly.

**2. Inline Execution** — Execute Tasks in this session using executing-plans, batch execution with checkpoints. Better if you want tight per-step control and are doing the work yourself with the model assisting line-by-line.

Which approach?
