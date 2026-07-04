# Promoter Location Fase 2 — TPV Worker + Flag Plumbing + Dashboard Track View

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Fase 2 of `docs/superpowers/specs/2026-06-29-live-promoter-tracking-design.md` — the TPV captures the promoter's location hourly (11:00–18:00 venue-local) and POSTs it to the already-deployed backend ingest; ADMIN/OWNER can turn the capture on per venue from the dashboard and see the day's route in the Promotores screen.

**Architecture:** The backend model (`PromoterLocationPing`), ingest endpoint (`POST /api/v1/tpv/geolocation/promoter-ping`, 403 when venue flag off) and read endpoint (`GET /api/v1/dashboard/venues/:venueId/promoters/:promoterId/track`) are ALREADY on `main`/prod. This plan adds the three missing pieces: (A) expose the `VenueSettings.trackPromoterLocation` flag to the TPV via the terminal-config payload + let the dashboard save it, plus a customer-MCP read tool; (B) a `PromoterLocationWorker` (Hilt + WorkManager) in the TPV that self-gates by flag/window/session; (C) the dashboard toggle + the day-route section in `PromoterLocationModal`.

**Tech Stack:** avoqado-server (Express/TS/Prisma/Jest), avoqado-tpv (Kotlin, Hilt 2.57, WorkManager + hilt-work, Retrofit/Gson, JUnit4 + MockK + Truth), avoqado-web-dashboard (React 18, TanStack Query, shadcn, react-i18next).

## Global Constraints

- **Three repos.** Each task states its working directory. Server: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server` (branch `develop`). TPV: `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv` (branch `main`). Dashboard: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard` (branch `develop`).
- **NEVER remove or rename an API response field.** Every server change here is ADDITIVE with a safe default (`trackPromoterLocation` defaults `false` everywhere).
- **NO git commits without the user's explicit authorization** (repo rule overrides the usual commit-per-task flow). Each task ends with a *suggested* commit message; STAGE NOTHING — at the end, ask: "¿Quieres que haga commit de estos cambios?".
- Zod messages in Spanish. Dates venue-local (`venueStartOfDay`/`formatInTimeZone`; frontend NEVER `timeZone: 'UTC'`).
- TPV timezone: ALWAYS `VenueTimeZone.get(secureStorage)`, never `ZoneId.systemDefault()`.
- Capture window is `[11:00, 18:00)` venue-local, interval 60 min (backend-configurable window is YAGNI — spec fixed these values).
- Gating decision (closed by founder): inside existing white-label (`PROMOTERS_AUDIT` / `WHITE_LABEL_DASHBOARD` module), NO new tier. Do not ask again.
- After editing server/dashboard TS: `npm run format && npm run lint:fix` in that repo.

---

## Part A — avoqado-server (`cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server`)

### Task 1: Expose `trackPromoterLocation` in the terminal-config payload

**Files:**
- Modify: `src/controllers/tpv/terminal.tpv.controller.ts` (interface ~line 26, defaults ~line 71, venueSettings select ~line 382, merge ~line 401)
- Test (create): `tests/unit/controllers/tpv/terminal.tpv.trackPromoterLocation.test.ts`

**Interfaces:**
- Produces: `GET /api/v1/tpv/terminals/:serialNumber/config` → `data.tpvSettings.trackPromoterLocation: boolean` (venue-level, from `VenueSettings`, default `false`). Task 4 (TPV DTO) consumes this exact JSON field name.

- [ ] **Step 1: Write the failing test**

Create `tests/unit/controllers/tpv/terminal.tpv.trackPromoterLocation.test.ts` (mock set copied from `terminal.tpv.planInfo.test.ts`):

```typescript
/**
 * Terminal TPV controller — venue-level trackPromoterLocation flag on tpvSettings.
 * ADDITIVE: the TPV PromoterLocationWorker self-gates on this flag ("cambaceo").
 */
import type { NextFunction, Request, Response } from 'express'
import { prismaMock } from '@tests/__helpers__/setup'
import { getTerminalConfig } from '@/controllers/tpv/terminal.tpv.controller'

jest.mock('@/lib/providerDeviceCompatibility', () => ({
  isProviderCompatibleWithBrand: jest.fn().mockReturnValue(true),
}))
jest.mock('@/services/superadmin/merchantAccount.service', () => ({ decryptCredentials: jest.fn() }))
jest.mock('@/services/superadmin/angelpayUserAccount.service', () => ({
  getAngelPayUserAccountForTerminal: jest.fn().mockResolvedValue(null),
  getAngelPayUserAccountsForTerminal: jest.fn().mockResolvedValue([]),
}))
jest.mock('@/services/organization-payment-config.service', () => ({
  getEffectivePaymentConfig: jest.fn().mockResolvedValue(null),
}))
jest.mock('@/services/modules/module.service', () => ({
  __esModule: true,
  moduleService: { isModuleEnabled: jest.fn().mockResolvedValue(false) },
  MODULE_CODES: { SERIALIZED_INVENTORY: 'SERIALIZED_INVENTORY' },
}))

const venueId = 'venue-123'
const serialNumber = 'SN-PAX-1'
const paxTerminal = {
  id: 'term-1',
  serialNumber,
  brand: 'PAX',
  model: 'A910S',
  status: 'ACTIVE',
  venueId,
  assignedMerchantIds: [],
  config: {},
  venue: { id: venueId, name: 'V', type: 'RESTAURANT', timezone: 'America/Mexico_City' },
}

function makeRes(): Response & { __status: number; __body: any } {
  const res: any = { __status: 0, __body: null }
  res.status = jest.fn((code: number) => ((res.__status = code), res))
  res.json = jest.fn((body: any) => ((res.__body = body), res))
  return res
}
const makeReq = () => ({ params: { serialNumber } }) as unknown as Request

describe('GET /tpv/terminals/:serialNumber/config — trackPromoterLocation', () => {
  beforeEach(() => {
    prismaMock.terminal.findFirst.mockResolvedValue(paxTerminal)
    prismaMock.venueFeature.findMany.mockResolvedValue([])
    prismaMock.venue.findUnique.mockResolvedValue({ seatCapExempt: false, status: 'ACTIVE' })
  })

  it('is true when the venue enabled it in VenueSettings', async () => {
    prismaMock.venueSettings.findUnique.mockResolvedValue({ enableShifts: true, trackPromoterLocation: true })
    const res = makeRes()
    await getTerminalConfig(makeReq(), res, jest.fn() as NextFunction)
    expect(res.__status).toBe(200)
    expect(res.__body.data.tpvSettings.trackPromoterLocation).toBe(true)
  })

  it('defaults to false when VenueSettings has it off or the row is missing (REGRESSION: additive)', async () => {
    prismaMock.venueSettings.findUnique.mockResolvedValue(null)
    const res = makeRes()
    await getTerminalConfig(makeReq(), res, jest.fn() as NextFunction)
    expect(res.__status).toBe(200)
    expect(res.__body.data.tpvSettings.trackPromoterLocation).toBe(false)
    // Existing contract fields untouched
    expect(res.__body.data.tpvSettings.enableShifts).toBeDefined()
    expect(res.__body.data.terminal.serialNumber).toBe(serialNumber)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx jest tests/unit/controllers/tpv/terminal.tpv.trackPromoterLocation.test.ts`
Expected: FAIL — `trackPromoterLocation` is `undefined` in `tpvSettings`.

- [ ] **Step 3: Implement (4 edits in `terminal.tpv.controller.ts`)**

1. `TpvSettings` interface (after `enableShifts: boolean`):
```typescript
  // Venue-level "cambaceo" flag (from VenueSettings): TPV emits hourly promoter
  // location pings 11:00–18:00 venue-local when true. Additive, default false.
  trackPromoterLocation: boolean
```
2. `DEFAULT_TPV_SETTINGS` (after `enableShifts: true,`):
```typescript
  trackPromoterLocation: false,
```
3. The `venueSettings` select (~line 385):
```typescript
        select: {
          enableShifts: true,
          trackPromoterLocation: true,
        },
```
4. The merge (~line 401, after the `enableShifts` line):
```typescript
      trackPromoterLocation: venueSettings?.trackPromoterLocation ?? DEFAULT_TPV_SETTINGS.trackPromoterLocation,
```

- [ ] **Step 4: Run tests to verify they pass + no regressions**

Run: `npx jest tests/unit/controllers/tpv/`
Expected: ALL PASS (new file + planInfo + angelpay suites).

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(promoter-location): expose trackPromoterLocation to the TPV via terminal config`

### Task 2: Dashboard save-path for the flag (`/settings/tpv`)

**Files:**
- Modify: `src/schemas/dashboard/venueSettings.schema.ts` (`UpdateTpvSettingsSchema.body`, ~line 13)
- Modify: `src/services/dashboard/tpv.dashboard.service.ts` (`VenueTpvSettings` ~line 849, `DEFAULT_VENUE_TPV_SETTINGS` ~line 863, `getVenueTpvSettings` ~line 883, `updateVenueTpvSettings` ~line 972)
- Test (modify): `tests/unit/services/dashboard/tpv.dashboard.service.test.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET/PUT /api/v1/dashboard/venues/:venueId/settings/tpv` now round-trips `trackPromoterLocation: boolean`. It is **venue-level** (stored ONLY in `VenueSettings` via upsert, like `geofenceRadiusMeters`) — it must NOT be written into per-terminal `Terminal.config.settings`. Task 9 (dashboard UI) consumes this field name.

- [ ] **Step 1: Write the failing tests** (append to the existing describe blocks in `tpv.dashboard.service.test.ts`; import `updateVenueTpvSettings` too):

```typescript
describe('trackPromoterLocation (cambaceo)', () => {
  it('getVenueTpvSettings returns the VenueSettings value (default false)', async () => {
    prismaMock.terminal.findFirst.mockResolvedValue(null)
    prismaMock.venueSettings.findFirst.mockResolvedValue({
      expectedCheckInTime: null, latenessThresholdMinutes: null, geofenceRadiusMeters: null,
      trackPromoterLocation: true,
    })
    prismaMock.venue.findUnique.mockResolvedValue({ organizationId: 'org-1' })
    prismaMock.organizationAttendanceConfig.findUnique.mockResolvedValue(null)

    const result = await getVenueTpvSettings('venue-1')
    expect(result.trackPromoterLocation).toBe(true)
  })

  it('updateVenueTpvSettings writes the flag to VenueSettings and NOT into terminal configs', async () => {
    prismaMock.terminal.findMany.mockResolvedValue([{ id: 't1', config: {}, configOverrides: {} }])
    prismaMock.venueSettings.upsert.mockResolvedValue({})
    // return-path read
    prismaMock.terminal.findFirst.mockResolvedValue(null)
    prismaMock.venueSettings.findFirst.mockResolvedValue({ trackPromoterLocation: true })
    prismaMock.venue.findUnique.mockResolvedValue({ organizationId: 'org-1' })
    prismaMock.organizationAttendanceConfig.findUnique.mockResolvedValue(null)

    await updateVenueTpvSettings('venue-1', { trackPromoterLocation: true })

    expect(prismaMock.venueSettings.upsert).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { venueId: 'venue-1' },
        update: expect.objectContaining({ trackPromoterLocation: true }),
        create: expect.objectContaining({ venueId: 'venue-1', trackPromoterLocation: true }),
      }),
    )
    // flag-only update must not touch Terminal.config
    expect(prismaMock.$transaction).not.toHaveBeenCalled()
  })
})
```

(Check the top of the test file for how `$transaction` is mocked in `prismaMock`; adapt the last assertion to `prismaMock.terminal.update` if `$transaction` executes its array.)

- [ ] **Step 2: Run to verify it fails**

Run: `npx jest tests/unit/services/dashboard/tpv.dashboard.service.test.ts`
Expected: FAIL — `trackPromoterLocation` undefined / upsert not called with it.

- [ ] **Step 3: Implement**

1. `UpdateTpvSettingsSchema.body` add:
```typescript
    trackPromoterLocation: z.boolean().optional(), // "Cambaceo": captura horaria de ubicación del promotor
```
2. `VenueTpvSettings` interface add `trackPromoterLocation: boolean` (after `attendanceTracking`); `DEFAULT_VENUE_TPV_SETTINGS` add `trackPromoterLocation: false,`.
3. `getVenueTpvSettings`: add `trackPromoterLocation: true` to the `venueSettings` `findFirst` select; in the `defaults` object add:
```typescript
    trackPromoterLocation: venueSettings?.trackPromoterLocation ?? DEFAULT_VENUE_TPV_SETTINGS.trackPromoterLocation,
```
   and in the terminal-present return add `trackPromoterLocation: defaults.trackPromoterLocation,`.
4. `updateVenueTpvSettings`: destructure it OUT of the terminal fields and into the VenueSettings upsert:
```typescript
  const { expectedCheckInTime, latenessThresholdMinutes, geofenceRadiusMeters, trackPromoterLocation, ...tpvFields } = settingsUpdate
  ...
  if (trackPromoterLocation !== undefined) venueSettingsData.trackPromoterLocation = trackPromoterLocation
```
(ActivityLog: this function already logs `VENUE_TPV_SETTINGS_UPDATED` — covered, no new log needed.)

- [ ] **Step 4: Run tests**

Run: `npx jest tests/unit/services/dashboard/tpv.dashboard.service.test.ts tests/unit/controllers/tpv/ tests/unit/services/promoterLocation.service.test.ts`
Expected: ALL PASS.

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(promoter-location): venue-level trackPromoterLocation via dashboard TPV settings`

### Task 3: Customer-MCP read tool `promoter_location`

**Files:**
- Create: `src/mcp/tools/promoterLocation.ts`
- Modify: `src/mcp/server.ts` (import + register, alongside the other `register*Tools`)

**Interfaces:**
- Consumes: `getPromoterTrackForVenue({ venueId, promoterId, date? })` from `@/services/promoters/promoterLocation.service` (returns `{ points: PromoterTrackPoint[], latest }`, `capturedAt: Date`).
- Produces: MCP tool `promoter_location` — read-only, gated by `WHITE_LABEL_DASHBOARD` module (mirrors the platform's `requireWhiteLabel` gate), resolve-don't-guess on promoter name.

- [ ] **Step 1: Create the tool** (pattern copied from `src/mcp/tools/inventory.ts` — guard + module gate + `text()`):

```typescript
import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { z } from 'zod'
import { formatInTimeZone } from 'date-fns-tz'
import prisma from '@/utils/prismaClient'
import type { McpScope } from '../scope'
import { createGuard } from '../guard'
import { text } from '../respond'
import { moduleService, MODULE_CODES } from '@/services/modules/module.service'
import { getPromoterTrackForVenue } from '@/services/promoters/promoterLocation.service'
import { DEFAULT_TIMEZONE } from '@/utils/datetime'

const WHITE_LABEL_OFF_MSG =
  'El seguimiento de promotores no está activo en este local (módulo WHITE_LABEL_DASHBOARD apagado).'

export function registerPromoterLocationTools(server: McpServer, scope: McpScope) {
  const guard = createGuard(scope)

  server.tool(
    'promoter_location',
    'Location track ("cambaceo") of a field promoter for ONE venue-local day: the ordered route (hourly pings 11:00–18:00) plus the latest live position. White-label venues only (PROMOTERS_AUDIT). Identify the promoter by promoterId, or by promoterName — an ambiguous name returns the candidates instead of guessing. Answers "¿dónde anda / por dónde anduvo el promotor hoy?". Pass venueId; date optional (YYYY-MM-DD, defaults to venue-local today).',
    {
      venueId: z.string().describe('Venue the promoter reports to (must be in your scope)'),
      promoterId: z.string().optional().describe('Staff id of the promoter (preferred when known)'),
      promoterName: z.string().optional().describe('Promoter name to resolve when the id is unknown'),
      date: z
        .string()
        .regex(/^\d{4}-\d{2}-\d{2}$/)
        .optional()
        .describe('Venue-local calendar day (YYYY-MM-DD); omit for today'),
    },
    async ({ venueId, promoterId, promoterName, date }) => {
      guard.venueFilter(venueId) // throws ScopeError if out of scope

      const whiteLabelActive = await moduleService.isModuleEnabled(venueId, MODULE_CODES.WHITE_LABEL_DASHBOARD)
      if (!whiteLabelActive) return text({ ok: false, moduleRequired: true, error: WHITE_LABEL_OFF_MSG })

      let staffId = promoterId ?? null
      if (!staffId) {
        if (!promoterName) return text({ ok: false, error: 'Indica promoterId o promoterName.' })
        const matches = await prisma.staffVenue.findMany({
          where: {
            venueId,
            active: true,
            staff: {
              OR: [
                { firstName: { contains: promoterName, mode: 'insensitive' } },
                { lastName: { contains: promoterName, mode: 'insensitive' } },
              ],
            },
          },
          select: { staff: { select: { id: true, firstName: true, lastName: true } } },
          take: 10,
        })
        if (matches.length === 0) return text({ ok: false, error: `No encontré un promotor "${promoterName}" en este local.` })
        if (matches.length > 1) {
          // resolve-don't-guess: return candidates, never pick one
          return text({
            ok: false,
            ambiguous: true,
            candidates: matches.map(m => ({ promoterId: m.staff.id, name: `${m.staff.firstName} ${m.staff.lastName}`.trim() })),
            error: 'Hay varios promotores con ese nombre — indica promoterId.',
          })
        }
        staffId = matches[0].staff.id
      }

      const venue = await prisma.venue.findUnique({ where: { id: venueId }, select: { timezone: true } })
      const tz = venue?.timezone ?? DEFAULT_TIMEZONE
      const track = await getPromoterTrackForVenue({ venueId, promoterId: staffId, date })

      const fmt = (d: Date) => formatInTimeZone(d, tz, 'yyyy-MM-dd HH:mm')
      return text({
        ok: true,
        promoterId: staffId,
        date: date ?? formatInTimeZone(new Date(), tz, 'yyyy-MM-dd'),
        latest: track.latest
          ? { lat: track.latest.lat, lng: track.latest.lng, accuracy: track.latest.accuracy, capturedAt: fmt(track.latest.capturedAt), source: track.latest.source }
          : null,
        points: track.points.map(p => ({ lat: p.lat, lng: p.lng, accuracy: p.accuracy, capturedAt: fmt(p.capturedAt), source: p.source })),
        note: track.points.length === 0 ? 'Sin ubicaciones registradas ese día.' : undefined,
      })
    },
  )
}
```

(Adjust the `guard.venueFilter` call and `text()` import to the exact signatures in `src/mcp/guard.ts` / `src/mcp/respond.ts` — copy how `inventory.ts` calls them. If `staffVenue`'s staff-name fields differ (e.g. single `name`), match the actual Prisma schema.)

- [ ] **Step 2: Register in `src/mcp/server.ts`**

Add `import { registerPromoterLocationTools } from './tools/promoterLocation'` with the other imports, and call `registerPromoterLocationTools(server, scope)` where the other `register*Tools(server, scope)` calls are made.

- [ ] **Step 3: Verify**

Run: `npm run build && npm run lint:fix`
Expected: clean compile, no lint errors. (No unit-test convention exists for `src/mcp/tools/` — build + read-only tool + existing service tests cover it. It's read-only: no `auditMcpWrite`/confirm gate needed.)

- [ ] **Step 4: Suggested commit (DO NOT run)**
`feat(mcp): promoter_location tool — cambaceo day track (white-label gated)`

---

## Part B — avoqado-tpv (`cd /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv`)

⚠️ The working tree has unrelated modified files (`CHANGELOG.md`, self_update/*, SerializedSaleViewModel.kt). Do NOT touch or revert them.

### Task 4: `trackPromoterLocation` in TpvSettings DTO + domain

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/TpvSettings.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/TpvSettingsDto.kt`
- Test (create): `app/src/test/java/com/jaac/avoqado_tpv/core/data/network/dto/TpvSettingsDtoTrackPromoterLocationTest.kt`

**Interfaces:**
- Consumes: server field `tpvSettings.trackPromoterLocation` (Task 1).
- Produces: `TpvSettings.trackPromoterLocation: Boolean` (default `false`) — read by the gate/worker (Tasks 6–7) via `tpvSettingsRepository.getCurrentSettings().trackPromoterLocation`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class TpvSettingsDtoTrackPromoterLocationTest {

    private val gson = Gson()

    @Test
    fun `toDomain maps trackPromoterLocation true`() {
        val dto = gson.fromJson("""{"trackPromoterLocation": true}""", TpvSettingsDto::class.java)
        assertThat(dto.toDomain().trackPromoterLocation).isTrue()
    }

    @Test
    fun `toDomain defaults to false when backend omits the field (old server)`() {
        val dto = gson.fromJson("""{"showTipScreen": true}""", TpvSettingsDto::class.java)
        assertThat(dto.toDomain().trackPromoterLocation).isFalse()
    }

    @Test
    fun `toDto round-trips the flag (regression - settings save must not drop it)`() {
        val settings = com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings(trackPromoterLocation = true)
        assertThat(settings.toDto().trackPromoterLocation).isTrue()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testSandboxDebugUnitTest --tests "com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsDtoTrackPromoterLocationTest"`
Expected: compile FAILURE (`trackPromoterLocation` unresolved).

- [ ] **Step 3: Implement**

1. `TpvSettings.kt` — add param (after `enableShifts`):
```kotlin
    // Venue-level "cambaceo" flag: capture hourly promoter location 11:00–18:00 venue-local
    val trackPromoterLocation: Boolean = false,
```
2. `TpvSettingsDto.kt` — add field (after `enableShifts`, nullable + default so tests/Gson stay compatible):
```kotlin
    // Venue-level "cambaceo" flag (from VenueSettings via terminal config)
    @SerializedName("trackPromoterLocation")
    val trackPromoterLocation: Boolean? = null,
```
   `toDomain()`: `trackPromoterLocation = trackPromoterLocation ?: false,` — `toDto()`: `trackPromoterLocation = trackPromoterLocation,`.

- [ ] **Step 4: Run test to verify it passes** (same command as Step 2). Expected: PASS.

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(promoter-location): trackPromoterLocation flag in TpvSettings (terminal config)`

### Task 5: Ping DTOs + ApiService endpoint + PromoterLocationRepository

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/PromoterLocationDto.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/ApiService.kt` (near `getLocationFromCellTowers`, ~line 1446)
- Create: `app/src/main/java/com/jaac/avoqado_tpv/core/data/repository/PromoterLocationRepository.kt`
- Test (create): `app/src/test/java/com/jaac/avoqado_tpv/core/data/repository/PromoterLocationRepositoryTest.kt`

**Interfaces:**
- Consumes: backend `POST tpv/geolocation/promoter-ping` (body `{latitude, longitude, accuracy?, capturedAt?, source?}`; venueId+staffId come from the JWT via the existing authInterceptor; 201 `{success, data:{id}}`; **403 when the venue flag is off** — that must NOT retry).
- Produces: `PromoterLocationRepository.sendPing(latitude: Double, longitude: Double, accuracy: Float?, capturedAt: java.time.Instant): Result<Unit>` where `Result` is `com.jaac.avoqado_tpv.core.domain.models.Result` (`Success`/`Error(ApiException)`) — consumed by the worker (Task 7).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.PromoterLocationPingResponseDto
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

class PromoterLocationRepositoryTest {

    private val apiService: ApiService = mockk()
    private val repository = PromoterLocationRepository(apiService)

    @Test
    fun `sendPing returns Success on 201`() = runTest {
        coEvery { apiService.sendPromoterLocationPing(any()) } returns
            Response.success(201, PromoterLocationPingResponseDto(success = true, data = null))
        val result = repository.sendPing(19.4326, -99.1332, 25f, Instant.parse("2026-07-02T17:00:00Z"))
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `sendPing returns HttpError on 403 (venue flag off - must not retry)`() = runTest {
        coEvery { apiService.sendPromoterLocationPing(any()) } returns
            Response.error(403, "{}".toResponseBody())
        val result = repository.sendPing(19.4326, -99.1332, null, Instant.now())
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(ApiException.HttpError::class.java)
    }

    @Test
    fun `sendPing returns NetworkError on IOException (worker retries)`() = runTest {
        coEvery { apiService.sendPromoterLocationPing(any()) } throws IOException("offline")
        val result = repository.sendPing(19.4326, -99.1332, null, Instant.now())
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(ApiException.NetworkError::class.java)
    }
}
```

(Match `ApiException.HttpError` / `NetworkError` constructor signatures to `core/domain/models/` — same as `HeartbeatRepository` uses.)

- [ ] **Step 2: Run to verify it fails** — same gradle `--tests` pattern. Expected: compile FAILURE.

- [ ] **Step 3: Implement**

`PromoterLocationDto.kt`:
```kotlin
package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/** Body for POST tpv/geolocation/promoter-ping (venueId/staffId travel in the JWT). */
data class PromoterLocationPingRequestDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float?,
    @SerializedName("capturedAt") val capturedAt: String, // ISO-8601 UTC
    @SerializedName("source") val source: String = "PERIODIC",
)

data class PromoterLocationPingResponseDto(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: PingIdDto?,
) { data class PingIdDto(@SerializedName("id") val id: String) }
```

`ApiService.kt` (next to the cell-towers endpoint):
```kotlin
    /**
     * Record one periodic promoter location ping ("cambaceo").
     * Backend re-validates the venue's trackPromoterLocation flag (403 when off).
     */
    @POST("tpv/geolocation/promoter-ping")
    suspend fun sendPromoterLocationPing(
        @Body request: PromoterLocationPingRequestDto
    ): Response<PromoterLocationPingResponseDto>
```

`PromoterLocationRepository.kt` (mirror `HeartbeatRepository`, minus debug noise):
```kotlin
package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.PromoterLocationPingRequestDto
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Sends periodic "cambaceo" location pings. One ping per worker run; no local queue (spec v1). */
@Singleton
class PromoterLocationRepository @Inject constructor(
    private val apiService: ApiService,
) {
    suspend fun sendPing(latitude: Double, longitude: Double, accuracy: Float?, capturedAt: Instant): Result<Unit> {
        return try {
            val response = apiService.sendPromoterLocationPing(
                PromoterLocationPingRequestDto(latitude, longitude, accuracy, capturedAt.toString()),
            )
            if (response.isSuccessful) {
                Timber.d("📍 Promoter ping accepted")
                Result.Success(Unit)
            } else {
                Timber.w("⚠️ Promoter ping rejected: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.errorBody()?.string() ?: response.message()))
            }
        } catch (e: Exception) {
            Timber.w(e, "📴 Promoter ping failed (network)")
            Result.Error(ApiException.NetworkError(e))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes.** Expected: 3 PASS.

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(promoter-location): promoter-ping endpoint + repository`

### Task 6: Pure capture gate (TDD)

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/core/location/PromoterLocationGate.kt`
- Test (create): `app/src/test/java/com/jaac/avoqado_tpv/core/location/PromoterLocationGateTest.kt`

**Interfaces:**
- Produces: `PromoterLocationGate.shouldCapture(isTerminalActivated: Boolean, isAuthenticated: Boolean, trackPromoterLocation: Boolean, now: ZonedDateTime): Boolean` — pure, no clock/DI, so the 11:00–18:00 window is unit-testable without Robolectric/work-testing (the repo has no injected-Clock convention; keeping the decision pure sidesteps that).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromoterLocationGateTest {

    private val mx: ZoneId = ZoneId.of("America/Mexico_City")
    private fun at(hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 7, 2, hour, minute, 0, 0, mx)

    private fun capture(
        activated: Boolean = true,
        authenticated: Boolean = true,
        flag: Boolean = true,
        now: ZonedDateTime = at(12),
    ) = PromoterLocationGate.shouldCapture(activated, authenticated, flag, now)

    @Test fun `captures inside the window when everything is on`() { assertThat(capture(now = at(11, 0))).isTrue() }
    @Test fun `captures at the last window hour`() { assertThat(capture(now = at(17, 59))).isTrue() }
    @Test fun `no-op before 11am venue time`() { assertThat(capture(now = at(10, 59))).isFalse() }
    @Test fun `no-op at 6pm venue time (window end is exclusive)`() { assertThat(capture(now = at(18, 0))).isFalse() }
    @Test fun `no-op when the venue flag is off`() { assertThat(capture(flag = false)).isFalse() }
    @Test fun `no-op when nobody is logged in`() { assertThat(capture(authenticated = false)).isFalse() }
    @Test fun `no-op when the terminal is not activated`() { assertThat(capture(activated = false)).isFalse() }
}
```

- [ ] **Step 2: Run to verify it fails** (compile failure). 

- [ ] **Step 3: Implement**

```kotlin
package com.jaac.avoqado_tpv.core.location

import java.time.ZonedDateTime

/**
 * Pure decision: should the TPV capture a promoter location ping right now?
 * "Cambaceo" tracking runs ONLY 11:00–18:00 venue-local (privacy: work window),
 * only while a session is active, and only when the venue opted in
 * (trackPromoterLocation). `now` must be built with VenueTimeZone.get(...),
 * never the device zone.
 */
object PromoterLocationGate {
    const val WINDOW_START_HOUR = 11
    const val WINDOW_END_HOUR = 18 // exclusive

    fun shouldCapture(
        isTerminalActivated: Boolean,
        isAuthenticated: Boolean,
        trackPromoterLocation: Boolean,
        now: ZonedDateTime,
    ): Boolean =
        isTerminalActivated &&
            isAuthenticated &&
            trackPromoterLocation &&
            now.hour in WINDOW_START_HOUR until WINDOW_END_HOUR
}
```

- [ ] **Step 4: Run test to verify 7 PASS.**

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(promoter-location): pure capture gate (window 11-18 venue-local)`

### Task 7: PromoterLocationWorker + Scheduler + login/logout wiring

**Files:**
- Create: `app/src/main/java/com/jaac/avoqado_tpv/core/data/workers/PromoterLocationWorker.kt`
- Create: `app/src/main/java/com/jaac/avoqado_tpv/core/util/PromoterLocationScheduler.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt` (login-success sites ~line 775 and ~line 2103; logout ~line 997)
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/MainActivity.kt` (~line 538 — only if that site also starts `HeartbeatScheduler` for an already-logged-in session; mirror it)

**Interfaces:**
- Consumes: `PromoterLocationGate.shouldCapture(...)` (Task 6), `PromoterLocationRepository.sendPing(...)` (Task 5), `TpvSettingsRepository.getCurrentSettings()/refreshFromTerminalConfig(serial)`, `LocationService.getCurrentLocation(timeoutMs)` → `LocationResult(latitude, longitude, accuracy)`, `VenueTimeZone.get(secureStorage)`, `SecureStorage.isTerminalActivated()/getSerialNumber()`, `AuthRepository.isAuthenticated()`.
- Produces: `PromoterLocationScheduler.start(context)` / `.stop(context)`.

- [ ] **Step 1: Worker** (template: `HeartbeatWorker` — same `@HiltWorker` shape; `Result` name clash resolved via alias import):

```kotlin
package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.repository.PromoterLocationRepository
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result as AvoqadoResult
import com.jaac.avoqado_tpv.core.location.LocationService
import com.jaac.avoqado_tpv.core.location.PromoterLocationGate
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Hourly "cambaceo" location ping (spec 2026-06-29-live-promoter-tracking-design.md).
 * Self-gates: terminal activated + session active + venue flag + 11:00–18:00 VENUE-local.
 * A failed/null capture is silently skipped (never blocks); only network errors retry.
 */
@HiltWorker
class PromoterLocationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val locationService: LocationService,
    private val promoterLocationRepository: PromoterLocationRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Refresh settings so a dashboard toggle applies within the hour
            // (offline-first: falls back to the cached settings on failure).
            secureStorage.getSerialNumber()?.let { tpvSettingsRepository.refreshFromTerminalConfig(it) }
            val settings = tpvSettingsRepository.getCurrentSettings()
            val now = ZonedDateTime.now(VenueTimeZone.get(secureStorage))

            if (!PromoterLocationGate.shouldCapture(
                    isTerminalActivated = secureStorage.isTerminalActivated(),
                    isAuthenticated = authRepository.isAuthenticated(),
                    trackPromoterLocation = settings.trackPromoterLocation,
                    now = now,
                )
            ) {
                return Result.success() // out of window / flag off / no session — quiet no-op
            }

            val location = locationService.getCurrentLocation()
            if (location == null) {
                Timber.w("📍 Promoter ping skipped: location unresolved (cell/WiFi/GPS)")
                return Result.success() // spec: omit the ping, never block
            }

            when (val result = promoterLocationRepository.sendPing(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                capturedAt = Instant.now(),
            )) {
                is AvoqadoResult.Success -> Result.success()
                is AvoqadoResult.Error ->
                    if (result.exception is ApiException.NetworkError) Result.retry()
                    else Result.success() // 4xx (e.g. 403 flag off server-side): don't hammer
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ PromoterLocationWorker crashed — skipping this cycle")
            Result.success()
        }
    }
}
```

(Verify `LocationService`'s package (`core.location`) and `LocationResult` field names before compiling; adjust imports to reality.)

- [ ] **Step 2: Scheduler** (template: `HeartbeatScheduler`):

```kotlin
package com.jaac.avoqado_tpv.core.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jaac.avoqado_tpv.core.data.workers.PromoterLocationWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Schedules the hourly "cambaceo" ping. Started on login success, stopped on
 * logout (unlike Heartbeat, tracking MUST stop with the session — privacy).
 * The worker itself no-ops outside 11:00–18:00 venue-local or when the venue
 * flag is off, so scheduling unconditionally on login is safe.
 */
object PromoterLocationScheduler {

    private const val WORK_NAME = "promoter_location_worker"
    private const val INTERVAL_MINUTES = 60L

    fun start(context: Context) {
        Timber.d("🚀 Starting promoter location scheduler (interval: ${INTERVAL_MINUTES}m)")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<PromoterLocationWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request,
        )
    }

    fun stop(context: Context) {
        Timber.d("🛑 Stopping promoter location scheduler")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
```

- [ ] **Step 3: Wire lifecycle**

In `AppNavigation.kt`, at EACH login-success site that calls `HeartbeatScheduler.start(context)` (~line 775 and ~line 2103), add right after `PaymentSyncScheduler.start(context)`:
```kotlin
                    // 📍 Cambaceo: hourly promoter location ping (self-gated by venue flag + window)
                    PromoterLocationScheduler.start(context)
```
In the `onLogout` block (~line 997, where the heartbeat is deliberately NOT stopped), add before `homeViewModel.logout()`:
```kotlin
                    // 📍 Promoter tracking stops with the session (privacy) — unlike heartbeat
                    PromoterLocationScheduler.stop(context)
```
In `MainActivity.kt` ~line 538: if that site calls `HeartbeatScheduler.start` for an already-active session, mirror with `PromoterLocationScheduler.start(this)`; otherwise skip. Add the needed imports.

- [ ] **Step 4: Compile + full unit tests**

Run: `./gradlew :app:compileSandboxDebugKotlin && ./gradlew :app:testSandboxDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests green (including Tasks 4–6 tests and the pre-existing suite — no regressions).

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(promoter-location): hourly cambaceo worker + scheduler wired to login/logout`

---

## Part C — avoqado-web-dashboard (`cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard`)

### Task 8: Toggle "Ubicación de Promotor (Cambaceo)" in TPV Config

**Files:**
- Modify: `src/services/tpv-settings.service.ts` (`VenueTpvSettings` + `DEFAULT_VENUE_TPV_SETTINGS`)
- Modify: `src/pages/playtelecom/TpvConfig/components/ModuleToggles.tsx` (`ModuleToggleState` + `MODULES` entry)
- Modify: `src/pages/playtelecom/TpvConfig/TpvConfiguration.tsx` (`DEFAULT_MODULES` + `settingsToState`)

**Interfaces:**
- Consumes: Task 2's `GET/PUT /api/v1/dashboard/venues/:venueId/settings/tpv` round-tripping `trackPromoterLocation`.
- Produces: the flag flows through the page's existing `saveMutation` (`...modules` spread) — no new mutation code.

- [ ] **Step 1: Service types** — in `tpv-settings.service.ts` add to `VenueTpvSettings`:
```typescript
  trackPromoterLocation: boolean
```
and to `DEFAULT_VENUE_TPV_SETTINGS`: `trackPromoterLocation: false,`.

- [ ] **Step 2: Toggle UI** — in `ModuleToggles.tsx`:
1. `ModuleToggleState`: add `trackPromoterLocation: boolean`.
2. Import `MapPin` from `lucide-react` (extend the existing import line).
3. Append to `MODULES` (top-level card — deliberately NOT a sub-toggle of `attendanceTracking`: cambaceo tracking is independent of clock-in photos):
```typescript
  {
    key: 'trackPromoterLocation' as const,
    icon: MapPin,
    labelKey: 'tpvConfig.modules.promoterLocation',
    labelDefault: 'Ubicación de Promotor (Cambaceo)',
    descKey: 'tpvConfig.modules.promoterLocationDesc',
    descDefault: 'Registra la ubicación cada hora (11:00–18:00)',
    colorClass: 'from-rose-500/20 to-rose-500/5 text-rose-600 dark:text-rose-400',
  },
```

- [ ] **Step 3: Page state** — in `TpvConfiguration.tsx`: `DEFAULT_MODULES` add `trackPromoterLocation: false,`; `settingsToState` add `trackPromoterLocation: settings.trackPromoterLocation,`. (The save mutation already spreads `...modules` — nothing else.)

- [ ] **Step 4: Verify**

Run: `npm run build`
Expected: clean TypeScript build. Then `npm run format && npm run lint:fix` if those scripts exist here (check `package.json`; otherwise skip).

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(playtelecom): toggle Ubicación de Promotor (Cambaceo) en TPV Config`

### Task 9: Day-route ("Recorrido del día") in PromoterLocationModal

**Files:**
- Modify: `src/services/promoters.service.ts` (add `getPromoterTrack`)
- Modify: `src/pages/playtelecom/PromotersAudit/components/PromoterLocationModal.tsx` (new props + track section)
- Modify: `src/pages/playtelecom/PromotersAudit/PromotersAuditPage.tsx` (~line 800, pass the new props)

**Interfaces:**
- Consumes: backend `GET /api/v1/dashboard/venues/:venueId/promoters/:promoterId/track?date=YYYY-MM-DD` → `{ success, data: { points: [{lat,lng,accuracy,capturedAt,source}], latest } }` (already deployed). `PromoterRow.storeId` = the promoter's venueId; `PromoterRow.id` = staff id.
- Produces: modal props gain `venueId: string` and `timezone: string`.

- [ ] **Step 1: Service** — append to `promoters.service.ts` (same style as `getPromoterDetail`):
```typescript
export interface PromoterTrackPoint {
  lat: number
  lng: number
  accuracy: number | null
  capturedAt: string
  source: string
}

export interface PromoterTrack {
  points: PromoterTrackPoint[]
  latest: PromoterTrackPoint | null
}

/** Day route ("cambaceo") of a promoter: hourly pings + latest live position. */
export const getPromoterTrack = async (venueId: string, promoterId: string, date?: string): Promise<PromoterTrack> => {
  const response = await api.get(`/api/v1/dashboard/venues/${venueId}/promoters/${promoterId}/track`, {
    params: date ? { date } : undefined,
  })
  return response.data.data
}
```
(Confirm the response envelope key (`data.data` vs `data.data.track`) by reading the route handler in avoqado-server `src/routes/dashboard/promoters.routes.ts` line ~79 — match exactly.)

- [ ] **Step 2: Modal** — in `PromoterLocationModal.tsx`:
1. Extend props:
```typescript
interface PromoterLocationModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  venueId: string
  timezone: string
  promoter: { /* unchanged */ }
}
```
2. Fetch the track while open (poll softly — near-live per spec, no socket):
```typescript
import { useQuery } from '@tanstack/react-query'
import { getPromoterTrack } from '@/services/promoters.service'

const { data: track } = useQuery({
  queryKey: ['promoter-track', venueId, promoter.id],
  queryFn: () => getPromoterTrack(venueId, promoter.id),
  enabled: open,
  refetchInterval: open ? 120_000 : false,
})
const trackPoints = track?.points ?? []
```
3. Render a "Recorrido del día (Cambaceo)" section ONLY when `trackPoints.length > 0` (venues without cambaceo keep today's modal untouched). Inside the Dialog content, after the existing check-in/check-out blocks:
```tsx
{trackPoints.length > 0 && (
  <div className="space-y-2">
    <div className="flex items-center justify-between">
      <h4 className="text-sm font-semibold flex items-center gap-1.5">
        <Navigation className="w-4 h-4" /> Recorrido del día (Cambaceo)
      </h4>
      {track?.latest && (
        <Badge variant="secondary">
          Última ubicación:{' '}
          {new Date(track.latest.capturedAt).toLocaleTimeString('es-MX', {
            hour: '2-digit',
            minute: '2-digit',
            timeZone: timezone,
          })}
        </Badge>
      )}
    </div>
    <div className="max-h-40 overflow-y-auto rounded-lg border divide-y">
      {trackPoints.map((p, i) => (
        <a
          key={`${p.capturedAt}-${i}`}
          href={`https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lng}`}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center justify-between px-3 py-1.5 text-xs hover:bg-muted/50"
        >
          <span className="flex items-center gap-1.5">
            <MapPin className="w-3 h-3 text-muted-foreground" />
            {new Date(p.capturedAt).toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit', timeZone: timezone })}
          </span>
          <span className="text-muted-foreground">
            {p.accuracy != null ? `±${Math.round(p.accuracy)} m` : ''} <ExternalLink className="w-3 h-3 inline" />
          </span>
        </a>
      ))}
    </div>
    {trackPoints.length >= 2 && (
      <Button asChild variant="outline" size="sm" className="w-full">
        <a
          href={`https://www.google.com/maps/dir/?api=1&origin=${trackPoints[0].lat},${trackPoints[0].lng}&destination=${trackPoints[trackPoints.length - 1].lat},${trackPoints[trackPoints.length - 1].lng}${
            trackPoints.length > 2
              ? `&waypoints=${trackPoints.slice(1, -1).slice(0, 9).map(p => `${p.lat},${p.lng}`).join('|')}`
              : ''
          }`}
          target="_blank"
          rel="noopener noreferrer"
        >
          <Navigation className="w-4 h-4 mr-1" /> Ver ruta completa en Google Maps
        </a>
      </Button>
    )}
  </div>
)}
```
(`MapPin`, `Navigation`, `ExternalLink` are already imported in this file; keep the hardcoded-Spanish style the modal already uses. NEVER `timeZone: 'UTC'`.)

- [ ] **Step 3: Page** — `PromotersAuditPage.tsx` ~line 800:
```tsx
<PromoterLocationModal
  open={locationModalOpen}
  onOpenChange={setLocationModalOpen}
  venueId={selectedPromoterForMap.storeId}
  timezone={venueTimezone}
  promoter={selectedPromoterForMap}
/>
```

- [ ] **Step 4: Verify**

Run: `npm run build`
Expected: clean build.

- [ ] **Step 5: Suggested commit (DO NOT run)**
`feat(playtelecom): recorrido del día (cambaceo) en el modal de ubicación de promotores`

---

## Task 10: Final verification + handoff

- [ ] **Server:** `npm run format && npm run lint:fix && npx jest tests/unit/controllers/tpv/ tests/unit/services/dashboard/tpv.dashboard.service.test.ts tests/unit/services/promoterLocation.service.test.ts && npm run build` — all green.
- [ ] **TPV:** `./gradlew :app:testSandboxDebugUnitTest` — all green.
- [ ] **Dashboard:** `npm run build` — green.
- [ ] **Report to user** (do NOT commit anything): summary of changes per repo + the pending ops steps that are OUT of code scope:
  1. Ask authorization to commit (3 repos, suggested messages above).
  2. Deploy order: backend (develop → staging → main) FIRST, then dashboard, then TPV APK release (`/release-production` ceremony) and install on terminal 2840744194.
  3. Enable the toggle for the "Cambaceo" venue (via the new dashboard toggle or psql `VenueSettings.trackPromoterLocation=true`).
  4. Reply to Isaac on Asana task 1216095149541822 once live.

## Self-review notes (spec coverage)

- Spec §Captura (worker steps 1–5): Tasks 4–7. Flag check ✔ (gate), venue-tz window ✔ (VenueTimeZone + gate), session ✔ (isAuthenticated), LocationService reuse ✔, null → omit + no block ✔ (worker), scheduled login / cancelled logout ✔ (Task 7 wiring), WorkManager retry for offline v1 ✔ (Result.retry on NetworkError only).
- Spec §Backend: already shipped (model/ingest/read); this plan adds only the flag exposure (Tasks 1–2) and the MCP sync obligation (Task 3, repo rule).
- Spec §Dashboard: route + latest pin + Google Maps links + polling + empty behavior: Task 9 (deviation: section hidden when 0 points instead of an explicit empty state — avoids noise on non-cambaceo venues; latest pin shown as badge + list, v1 links-only, no embedded map = spec's recommended option).
- Spec §Testing: TPV worker decision unit tests ✔ (gate — window/tz/flag/session), backend ingest/read/gating tests already exist ✔, terminal-config + settings plumbing tests added ✔. Manual PAX test with Telcel SIM stays a post-deploy ops step (Task 10 handoff).
- Not in scope (per spec no-objetivos): Room offline queue, embedded map, real-time socket, retention cron (spec marks optional; skip until data volume warrants).
