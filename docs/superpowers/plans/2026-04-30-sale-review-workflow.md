# Sale Review Workflow (PlayTelecom Walmart) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable PlayTelecom back-office to formally accept/reject SIM-sale documentation from the dashboard and propagate the status to the promoter's TPV in real time.

**Architecture:** Add 4 fields + 1 enum to `SaleVerification` (Prisma). Reuse the existing pattern of dashboard service+controller+route. Reuse the KYCReview UI pattern (Approve/Reject + reasons) for `SalesReport.tsx`. Reuse Socket.IO event pattern (`OrderStatusChanged` mold) for `sale:review-status-changed` listened in `MySalesScreen`.

**Tech Stack:** Prisma + Postgres, Express + TypeScript, React + TanStack Query + Tailwind/Shadcn, Kotlin + Jetpack Compose, Socket.IO, JWT auth.

**Assumptions** (defaults — flag if user disagrees):
- Back-office reviewer = ADMIN/MANAGER/OWNER (use existing roles, gate with new permission `sale-verifications:review`).
- Rejection reasons start as fixed enum: `REVIEW_PORTABILIDAD`, `REVIEW_DUPLICATE_VINCULACION`, `OTHER`. Free-text `reviewNotes` always allowed.
- Existing `proofOfSale` photo upload covers vinculación + portabilidad (no new image-capture work).
- Backend remains backwards-compatible with old TPV: new fields are nullable in `MySaleItem` response.

---

## File Map

### Backend (`avoqado-server`)
- **Modify** `prisma/schema.prisma` — add fields + enum
- **Create** `prisma/migrations/<ts>_sale_review_workflow/migration.sql` (auto)
- **Modify** `src/lib/permissions.ts` — add `sale-verifications:review`
- **Modify** `src/services/dashboard/sale-verification.dashboard.service.ts` — add `reviewSaleVerification()` + extend response shape
- **Modify** `src/controllers/dashboard/sale-verification.dashboard.controller.ts` — add `reviewSaleVerification` handler
- **Modify** `src/routes/dashboard.routes.ts` — register PATCH route
- **Modify** `src/services/tpv/serializedSale.service.ts` (or wherever `my-sales` lives) — include verification fields in response
- **Modify** `src/sockets/*` — emit `sale:review-status-changed` to staff room
- **Create** `src/services/dashboard/__tests__/sale-verification.review.test.ts`

### Dashboard (`avoqado-web-dashboard`)
- **Modify** `src/services/saleVerification.service.ts` — add `reviewSaleVerification()` + extend `SaleVerification` type
- **Create** `src/pages/playtelecom/Sales/components/ReviewSaleDialog.tsx`
- **Modify** `src/pages/playtelecom/Sales/SalesReport.tsx` — wire action buttons + dialog

### TPV (`avoqado-tpv`)
- **Modify** `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/ApiService.kt` — extend `MySaleItem`
- **Modify** `app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesScreen.kt` — status badge + reviewNotes
- **Modify** `app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesViewModel.kt` — socket listener
- **Modify** `app/src/main/java/com/jaac/avoqado_tpv/core/data/realtime/events/SocketEvent.kt` — add event
- **Modify** `app/src/main/java/com/jaac/avoqado_tpv/core/data/realtime/SocketManager.kt` — register handler
- **Create** `app/src/test/java/.../MySalesViewModelReviewTest.kt`
- **Modify** `CHANGELOG.md` (always — see `changelog-policy.md`)

---

## Phase 1 — Backend (avoqado-server)

### Task 1.1: Schema migration

**Files:** `prisma/schema.prisma`

- [ ] Add enum `SaleVerificationRejectionReason` near other Sale enums:

```prisma
enum SaleVerificationRejectionReason {
  REVIEW_PORTABILIDAD
  REVIEW_DUPLICATE_VINCULACION
  OTHER
}
```

- [ ] Modify `SaleVerification` model — add 4 review fields after `notes`:

```prisma
  // Back-office review (Walmart documentation acceptance)
  reviewedById     String?
  reviewedBy       Staff?                            @relation("SaleVerificationReviewer", fields: [reviewedById], references: [id], onDelete: SetNull)
  reviewedAt       DateTime?
  reviewNotes      String?
  rejectionReasons SaleVerificationRejectionReason[] @default([])
```

- [ ] Add inverse relation in `Staff` model:

```prisma
  reviewedSaleVerifications SaleVerification[] @relation("SaleVerificationReviewer")
```

- [ ] Generate migration:

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npx prisma migrate dev --name sale_review_workflow
```

Expected: migration applied, prisma client regenerated.

- [ ] Commit migration files (do not commit yet — wait until Phase 1 complete).

### Task 1.2: Add `sale-verifications:review` permission

**Files:** `src/lib/permissions.ts`

- [ ] Add new resource entry to `INDIVIDUAL_PERMISSIONS_BY_RESOURCE` (around line 1163, near `'serialized-inventory'`):

```ts
  'sale-verifications': ['sale-verifications:review'],
```

- [ ] Add `'sale-verifications:review'` to DEFAULT_PERMISSIONS for `OWNER`, `ADMIN`, and `MANAGER` roles.

### Task 1.3: Service — `reviewSaleVerification()`

**Files:** `src/services/dashboard/sale-verification.dashboard.service.ts`

- [ ] Add types at top of file:

```ts
import { SaleVerificationRejectionReason } from '@prisma/client'

interface ReviewSaleVerificationParams {
  saleVerificationId: string
  reviewedById: string
  decision: 'APPROVE' | 'REJECT'
  rejectionReasons?: SaleVerificationRejectionReason[]
  reviewNotes?: string
}
```

- [ ] Add function (export at end of file):

```ts
export async function reviewSaleVerification(venueId: string, params: ReviewSaleVerificationParams) {
  logger.info(`[SALE VERIFICATION REVIEW] Verification ${params.saleVerificationId} ${params.decision} by ${params.reviewedById}`)

  const existing = await prisma.saleVerification.findUnique({
    where: { id: params.saleVerificationId },
    select: { id: true, venueId: true, staffId: true, status: true },
  })

  if (!existing) {
    const err: any = new Error('Sale verification not found')
    err.statusCode = 404
    throw err
  }

  if (existing.venueId !== venueId) {
    const err: any = new Error('Sale verification does not belong to this venue')
    err.statusCode = 403
    throw err
  }

  if (existing.status !== 'PENDING') {
    const err: any = new Error(`Sale verification already reviewed (status=${existing.status})`)
    err.statusCode = 409
    throw err
  }

  if (params.decision === 'REJECT' && (!params.rejectionReasons || params.rejectionReasons.length === 0) && !params.reviewNotes?.trim()) {
    const err: any = new Error('Rejection requires at least one reason or notes')
    err.statusCode = 400
    throw err
  }

  const newStatus: 'COMPLETED' | 'FAILED' = params.decision === 'APPROVE' ? 'COMPLETED' : 'FAILED'

  const updated = await prisma.saleVerification.update({
    where: { id: params.saleVerificationId },
    data: {
      status: newStatus,
      reviewedById: params.reviewedById,
      reviewedAt: new Date(),
      reviewNotes: params.reviewNotes?.trim() || null,
      rejectionReasons: params.decision === 'REJECT' ? params.rejectionReasons ?? [] : [],
    },
    include: {
      payment: { select: { id: true } },
      reviewedBy: { select: { id: true, firstName: true, lastName: true } },
    },
  })

  return { updated, staffId: existing.staffId }
}
```

### Task 1.4: Controller handler

**Files:** `src/controllers/dashboard/sale-verification.dashboard.controller.ts`

- [ ] Import socket helper at top (use existing pattern from another controller — search `emitToStaff` or `emitToRoom`).

- [ ] Add handler at end of file:

```ts
export async function reviewSaleVerification(req: Request, res: Response): Promise<void> {
  try {
    const { venueId, id } = req.params
    const { decision, rejectionReasons, reviewNotes } = req.body
    const reviewedById = (req as any).user?.staffId || (req as any).user?.id

    if (!reviewedById) {
      res.status(401).json({ success: false, message: 'No reviewer staff context' })
      return
    }

    if (decision !== 'APPROVE' && decision !== 'REJECT') {
      res.status(400).json({ success: false, message: "decision must be 'APPROVE' or 'REJECT'" })
      return
    }

    const { updated, staffId } = await saleVerificationDashboardService.reviewSaleVerification(venueId, {
      saleVerificationId: id,
      reviewedById,
      decision,
      rejectionReasons,
      reviewNotes,
    })

    // Emit socket event to the promoter's staff room
    try {
      const io = req.app.get('io')
      if (io && staffId) {
        io.to(`staff:${staffId}`).emit('sale:review-status-changed', {
          saleVerificationId: updated.id,
          paymentId: updated.payment?.id,
          status: updated.status,
          reviewedAt: updated.reviewedAt,
          reviewNotes: updated.reviewNotes,
          rejectionReasons: updated.rejectionReasons,
          reviewedBy: updated.reviewedBy ? `${updated.reviewedBy.firstName} ${updated.reviewedBy.lastName}` : null,
        })
      }
    } catch (socketErr: any) {
      logger.warn(`[SALE VERIFICATION REVIEW] Socket emit failed: ${socketErr.message}`)
      // Don't fail the request — socket is best-effort
    }

    res.status(200).json({ success: true, data: updated })
  } catch (error: any) {
    logger.error(`[SALE VERIFICATION REVIEW] Error: ${error.message}`)
    res.status(error.statusCode || 500).json({
      success: false,
      message: error.message || 'Internal server error',
    })
  }
}
```

### Task 1.5: Route registration

**Files:** `src/routes/dashboard.routes.ts` (after line 10370)

- [ ] Add route after `getStaffWithVerifications`:

```ts
/**
 * @openapi
 * /api/v1/dashboard/venues/{venueId}/sale-verifications/{id}/review:
 *   patch:
 *     tags: [Sale Verifications]
 *     summary: Approve or reject a sale verification (back-office)
 */
router.patch(
  '/venues/:venueId/sale-verifications/:id/review',
  authenticateTokenMiddleware,
  checkPermission('sale-verifications:review'),
  saleVerificationController.reviewSaleVerification,
)
```

### Task 1.6: Extend list response with review fields

**Files:** `src/services/dashboard/sale-verification.dashboard.service.ts`

- [ ] Add review fields to `SaleVerificationDashboardResponse` interface:

```ts
  // Review metadata
  reviewedById: string | null
  reviewedAt: Date | null
  reviewNotes: string | null
  rejectionReasons: SaleVerificationRejectionReason[]
  reviewedBy: { id: string; firstName: string; lastName: string } | null
```

- [ ] In `listSaleVerificationsWithDetails` Prisma query include:

```ts
saleVerification: {
  include: {
    staff: { select: { id: true, firstName: true, lastName: true, email: true, photoUrl: true } },
    reviewedBy: { select: { id: true, firstName: true, lastName: true } },
  },
},
```

- [ ] In the mapper, populate the new fields:

```ts
reviewedById: v?.reviewedById ?? null,
reviewedAt: v?.reviewedAt ?? null,
reviewNotes: v?.reviewNotes ?? null,
rejectionReasons: (v?.rejectionReasons as SaleVerificationRejectionReason[]) ?? [],
reviewedBy: v?.reviewedBy ?? null,
```

### Task 1.7: Extend TPV my-sales response

**Files:** Find with `grep -rn "my-sales\|getMySalesHistory" src/` — likely `src/services/tpv/serializedSale.service.ts` or `src/controllers/tpv/serializedSale.tpv.controller.ts`.

- [ ] Locate the `my-sales` query.
- [ ] In Prisma include, add `saleVerification` join via `payment.saleVerification`.
- [ ] Map response with new fields per sale: `verificationStatus`, `reviewedAt`, `rejectionReasons`, `reviewNotes`. All nullable.

### Task 1.8: Backend tests

**Files:** `src/services/dashboard/__tests__/sale-verification.review.test.ts`

- [ ] Write Jest tests covering:
  - APPROVE on PENDING → status=COMPLETED, reviewedAt set
  - REJECT with reasons → status=FAILED, rejectionReasons stored
  - REJECT without reasons or notes → 400
  - Re-review of COMPLETED → 409
  - Wrong venue → 403
  - Non-existent ID → 404

- [ ] Run: `npm test -- sale-verification.review`. Expected: PASS.

### Task 1.9: Verify and commit Phase 1

- [ ] Run `npm run build` (TypeScript check). Expected: 0 errors.
- [ ] Run `npm test`. Expected: all passing.
- [ ] Commit: `feat(sale-verification): add back-office review workflow with rejection reasons`

---

## Phase 2 — Dashboard (avoqado-web-dashboard)

### Task 2.1: Service mutation + types

**Files:** `src/services/saleVerification.service.ts`

- [ ] Add types and mutation function:

```ts
export type SaleVerificationRejectionReason = 'REVIEW_PORTABILIDAD' | 'REVIEW_DUPLICATE_VINCULACION' | 'OTHER'

export interface ReviewSaleVerificationParams {
  decision: 'APPROVE' | 'REJECT'
  rejectionReasons?: SaleVerificationRejectionReason[]
  reviewNotes?: string
}

// Extend the existing SaleVerification interface with:
//   reviewedById: string | null
//   reviewedAt: string | null
//   reviewNotes: string | null
//   rejectionReasons: SaleVerificationRejectionReason[]
//   reviewedBy: { id: string; firstName: string; lastName: string } | null

export async function reviewSaleVerification(
  venueId: string,
  saleVerificationId: string,
  params: ReviewSaleVerificationParams,
): Promise<SaleVerification> {
  const url = `/api/v1/dashboard/venues/${venueId}/sale-verifications/${saleVerificationId}/review`
  const response = await api.patch(url, params)
  return response.data.data
}
```

### Task 2.2: ReviewSaleDialog component

**Files:** Create `src/pages/playtelecom/Sales/components/ReviewSaleDialog.tsx`

- [ ] Component:
  - Props: `isOpen`, `onClose`, `verification`, `onReviewed`
  - Two modes: "approve" (one-click confirm) and "reject" (checkboxes + textarea)
  - Use Shadcn `Dialog`, `Checkbox`, `Textarea`, `Button`
  - Mutation via `useMutation` calling `reviewSaleVerification`
  - On success: toast + invalidate `['sale-verifications', venueId]` query + `onReviewed()`

- [ ] Reasons:
  - `REVIEW_PORTABILIDAD` → "Revisar portabilidad"
  - `REVIEW_DUPLICATE_VINCULACION` → "Revisar número duplicado de vinculación"
  - `OTHER` → "Otro (especificar abajo)"

### Task 2.3: Wire actions into SalesReport.tsx

**Files:** `src/pages/playtelecom/Sales/SalesReport.tsx`

- [ ] Add 2 columns to existing table:
  - Action: shown only when `status === 'PENDING'` and user has `sale-verifications:review` permission. Two buttons: green ✓ Confirmar / red ✗ Revisar.
  - Reviewer: shown when `status !== 'PENDING'` — name + relative time + reasons as small badges.

- [ ] On Confirm click → open dialog in approve mode → call mutation directly.
- [ ] On Reject click → open dialog in reject mode.

### Task 2.4: Playwright test

- [ ] User running dashboard at localhost:5173. Login with ADMIN account in PlayTelecom org.
- [ ] Navigate to `/venues/<slug>/playtelecom/sales`.
- [ ] Find a PENDING sale (or seed one via DB if none). Click Confirm. Verify badge turns green and refetch shows COMPLETED.
- [ ] Find another PENDING. Click Revisar. Select REVIEW_PORTABILIDAD. Submit. Verify badge turns red and reasons displayed.
- [ ] Verify DB row matches via direct query.

### Task 2.5: Commit Phase 2

- [ ] Commit: `feat(sales-report): add back-office review actions with rejection reasons`

---

## Phase 3 — TPV (avoqado-tpv)

### Task 3.1: Extend MySaleItem DTO

**Files:** `app/src/main/java/com/jaac/avoqado_tpv/core/data/network/ApiService.kt` (around line 189)

- [ ] Add to `MySaleItem`:

```kotlin
data class MySaleItem(
    // ... existing fields ...
    val verificationStatus: String? = null,        // "PENDING" | "COMPLETED" | "FAILED" | null
    val reviewedAt: String? = null,                // ISO timestamp
    val rejectionReasons: List<String>? = null,    // ["REVIEW_PORTABILIDAD", ...]
    val reviewNotes: String? = null
)
```

### Task 3.2: Status badge in MySalesScreen

**Files:** `app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesScreen.kt`

- [ ] Add helper composable `VerificationStatusBadge(status: String?)`:
  - `null` → no badge (legacy backend)
  - `"PENDING"` → yellow "En revisión"
  - `"COMPLETED"` → green "Venta correcta"
  - `"FAILED"` → red "Revisar documentación"

- [ ] Render badge under the existing payment status row.
- [ ] When status=`"FAILED"`, expand a small section showing:
  - Each rejection reason as a chip with localized text
  - `reviewNotes` italic if present

- [ ] Localize reason codes:
  - `REVIEW_PORTABILIDAD` → "Revisar portabilidad"
  - `REVIEW_DUPLICATE_VINCULACION` → "Revisar número duplicado de vinculación"
  - `OTHER` → "Otro motivo"

- [ ] Use `MaterialTheme.avoqadoColors.statusSuccess`/`statusWarning`/`statusError` from existing theme.

### Task 3.3: Socket event + listener

**Files:** `app/src/main/java/com/jaac/avoqado_tpv/core/data/realtime/events/SocketEvent.kt`

- [ ] Add event class:

```kotlin
data class SaleReviewStatusChanged(
    val saleVerificationId: String,
    val paymentId: String?,
    val status: String,                       // "COMPLETED" | "FAILED"
    val reviewedAt: String?,
    val reviewNotes: String?,
    val rejectionReasons: List<String>?,
    val reviewedBy: String?
)
```

**Files:** `app/src/main/java/com/jaac/avoqado_tpv/core/data/realtime/SocketManager.kt`

- [ ] In `setupEventListeners()` add listener for `"sale:review-status-changed"`. Parse JSON, emit to existing event bus.

**Files:** `app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesViewModel.kt`

- [ ] Inject SocketEvent flow. Collect `SaleReviewStatusChanged` events. On receive, refetch current month's sales.

### Task 3.4: Unit tests

**Files:** `app/src/test/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesViewModelReviewTest.kt`

- [ ] Tests:
  - Sale with verificationStatus=null → no badge rendered
  - Sale with verificationStatus="FAILED" + rejectionReasons → badge red + reasons listed
  - SaleReviewStatusChanged event arriving → triggers `loadMonthSales` reload

- [ ] Run: `./gradlew testSandboxDebugUnitTest --tests "*MySalesViewModelReviewTest*"`. Expected: PASS.

### Task 3.5: ADB monitoring during manual smoke

- [ ] Install: `./gradlew installSandboxDebug`.
- [ ] Run: `adb logcat -c && adb logcat -s MySalesViewModel,SocketManager,SocketEventHandler | grep -iE "review|sale-review|verification"`.
- [ ] Trigger from dashboard via Playwright (Phase 2). Verify TPV log shows event received and list refresh happens.

### Task 3.6: Sync to production variant + CHANGELOG

- [ ] No variant-specific files in this feature (changes live in `app/src/main/`). Verify with grep.
- [ ] Update `CHANGELOG.md` under `[Unreleased]` (mandatory per `changelog-policy.md`):

```markdown
### **Added**
- **Back-office sale review**: TPV `MySalesScreen` shows verification status badges (En revisión / Venta correcta / Revisar documentación). Real-time updates via socket event `sale:review-status-changed`. Promoters see rejection reasons and reviewer notes inline when documentation is rejected.
```

### Task 3.7: Commit Phase 3

- [ ] Commit (NO release yet — version bump only when user approves): `feat(my-sales): show back-office review status with realtime updates`

---

## Phase 4 — Integration + destructive testing

### Task 4.1: Happy-path E2E

- [ ] Backend dev server running, dashboard running, TPV connected with `sandboxDebug`.
- [ ] DB query baseline: `SELECT id, status, "reviewedById", "rejectionReasons" FROM "SaleVerification" WHERE status='PENDING' LIMIT 5;`
- [ ] On TPV: complete a SIM sale that triggers `SaleVerification` (use `SerializedSaleScreen` → upload photos via post-payment flow).
- [ ] On dashboard: `SalesReport` reflects new PENDING row.
- [ ] Click Confirm → check DB row: `status='COMPLETED'`, `reviewedById=<admin-id>`, `reviewedAt` set.
- [ ] On TPV: ADB log shows `sale:review-status-changed` event received. `MySalesScreen` shows green badge.
- [ ] Repeat with Reject + reasons. Verify red badge + reasons in TPV.

### Task 4.2: Destructive

- [ ] **Permission denied**: Login dashboard as WAITER role. Action buttons must be hidden. Direct PATCH via curl with WAITER token → 403.
- [ ] **Double review**: Click Confirm twice quickly (network latency). Second call → 409 with `already reviewed`.
- [ ] **Wrong venue**: PATCH against `venueId=A` for verification belonging to venue B → 403.
- [ ] **Reject without reason or notes**: dialog must disable submit; bypass via curl → 400.
- [ ] **Mid-flow socket disconnect**: kill websocket, perform review, reconnect TPV. `MySalesScreen` should show new status on reload (not realtime), confirming pull is correct fallback.
- [ ] **Old TPV (legacy DTO)**: simulate by stripping new fields from response in interceptor — TPV must render without crash.
- [ ] **DB monitoring**: `psql ... -c "SELECT id, status, \"reviewedAt\", \"rejectionReasons\" FROM \"SaleVerification\" ORDER BY \"updatedAt\" DESC LIMIT 5;"` after each action — verify atomic state changes.

### Task 4.3: Final verification

- [ ] Backend: `npm test` all passing.
- [ ] Backend: `npm run build` 0 errors.
- [ ] Dashboard: `npm run build` 0 errors.
- [ ] TPV: `./gradlew compileSandboxDebugKotlin` 0 errors.
- [ ] TPV: `./gradlew testSandboxDebugUnitTest` 220+ tests, 0 failures.
- [ ] CHANGELOG entries present under `[Unreleased]` in TPV.

### Task 4.4: User checkpoint

- [ ] Demo to user. Confirm UX matches the Asana proposal. Get approval before suggesting version bump / release.

---

## Cross-repo deploy order

1. Deploy avoqado-server (migration runs automatically). Backend supports old TPV via nullable fields.
2. Deploy avoqado-web-dashboard (no breaking changes to existing endpoints).
3. After steady state (~1 day), build TPV release APK (separate user request).

## Self-review checklist

- [x] Migration touches only `SaleVerification` + `Staff` (inverse relation). No other models impacted.
- [x] Permission `sale-verifications:review` registered in both `INDIVIDUAL_PERMISSIONS_BY_RESOURCE` and `DEFAULT_PERMISSIONS`.
- [x] All new DTO fields nullable (backwards compat).
- [x] No PaymentViewModel changes (preserves the 8-feature payment safety rule).
- [x] CHANGELOG entry mandatory per project rule.
- [x] Socket event reuses staff-room broadcast pattern.
- [x] Validation enforced both client (dialog) and server (controller + service).
