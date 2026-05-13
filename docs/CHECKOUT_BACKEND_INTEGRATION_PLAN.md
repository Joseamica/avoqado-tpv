# Plan: Custom Line Items + Cortesía + Discounts in TPV's "Cobrar" Flow

> **For the reviewer (other LLM):** This plan describes how `avoqado-tpv`
> (the standalone POS app for PAX/Nexgo terminals) needs to talk to the
> backend (`avoqado-server`) to fully support a unified Cart that may
> contain catalog products, custom amounts, cortesía items, and discounts —
> all in the same transaction.
>
> Your job is to **review this plan critically**, then update the
> companion spec (`docs/CHECKOUT_TPV_ENDPOINT_SPEC.md`) with any gaps,
> ambiguities, or risks you find. Read both docs end-to-end before
> proposing edits.
>
> Hard rules: do NOT propose changes that break legacy "Pago Rápido" or
> legacy "Órdenes" flows. They must keep working untouched even after this
> ships. Real money is involved — every assumption about totals or
> inventory must be verifiable.

---

## 1. Context

### What `avoqado-tpv` is

`avoqado-tpv` is a **standalone POS** running on PAX (A910S, A80) and
Nexgo (N86, N5) payment terminals. It does both order management and card
processing on the same hardware:

1. Operator builds the cart on the terminal
2. Taps "Cobrar"
3. Same hardware swipes/inserts/taps the card via Blumon SDK (PAX) or
   AngelPay app-to-app (Nexgo)
4. Same hardware prints the receipt

There is **no upstream client** — TPV is the only software that touches the
order and payment. (The older bipartite architecture where an iPad-class
POS sent BLE'd payment requests to a TPV is no longer in use.)

### What changed to make this matter

We're shipping a refactor of TPV's home screen — see
`docs/CHECKOUT_REFACTOR_PLAN.md`. The new "Cobrar" button opens a unified
`CheckoutScreen` (4 tabs: Teclado, Shortcuts, Todos los productos,
Configurar) that allows mixing **catalog products + custom amounts** in
the same cart — modeled on the Square iPad checkout pattern.

Pre-refactor, TPV had three separate flows:

- **Pago Rápido**: amount-only, no order created (just `amount → PaymentScreen`)
- **Órdenes** (MenuScreen / FloorPlan): catalog products only, creates an
  order, then adds items via the legacy 2-call pattern
- **Kiosk Cart**: similar to Órdenes but for self-service mode

Each of those flows had clean separation: "either a flat amount OR a list
of products". The new Cobrar breaks that — a single cart can have both,
plus per-item cortesía, plus per-item discounts.

## 2. Problem statement

When the operator builds a mixed cart in Cobrar (e.g. `1× Mindform turns 1
$1,660` + `Otro importe $25.45`) and taps "Cobrar":

| What the cart calculates | $1,685.45 (sum of all items, applying any cortesía/discount) |
| What PaymentScreen charges | $1,685.45 ✓ — customer pays the right amount |
| What backend records as the order | Subtotal $1,660 (only the catalog product persists) |
| What the receipt shows | Either Propina $25.45 (when payment > order) or unexplained gap |

So the customer is **never overcharged**, but the backend records of the
order don't match reality:

- Dashboard "Pagos" shows a $25.45 gap operators can't account for
- Dashboard "Órdenes" detail lacks the custom amount line
- Receipt mislabels the difference as "Propina"

The same pattern applies to:

- **Cortesía per item**: cart drops the item to $0, customer pays correctly,
  but backend records the item at catalog price → phantom discount
- **Per-item discount**: cart applies `priceAdjustmentCents`, customer pays
  correctly, but backend uses catalog price → phantom discount

**This is a real-money problem.** Everything reconciles end-of-day from
the operator's perspective (they took the right amount of money), but the
records don't tell anyone WHY the totals are what they are. Auditing,
commissions, and receipts all degrade.

## 3. Constraints (non-negotiable)

1. **Legacy "Pago Rápido" must keep working** — no changes to its endpoint
   path, request shape, or response shape.
2. **Legacy "Órdenes" must keep working** — `POST /tpv/venues/:venueId/orders`
   + `PATCH /orders/:id/items` stay exactly as today. 8 in-production
   features depend on them.
3. **Old TPV versions in production must keep working** — the change must
   be additive on the backend; existing endpoints are not modified.
4. **Money correctness is verifiable end-to-end** — for any cart
   configuration, these invariants hold (per the gross/net definition in
   the spec's "Subtotal semantics" section):
   - `customer_charge == Order.total + tip` (V1 requires `taxAmount = 0`,
     so the existing payment formula `subtotal - discountAmount + tip`
     evaluates to `total + tip`)
   - `Order.subtotal == Σ items[].lineGross` (catalog/operator-typed
     prices, BEFORE cortesía/discount)
   - `Order.discountAmount == Σ cortesías + per-item discounts +
     order-level discount`
   - `Order.taxAmount == 0` in V1 of new Cobrar. Order-level tax is
     deferred until `payment.tpv.service.ts` fully-paid math includes tax.
   - `Order.total == Order.subtotal - Order.discountAmount`
   - Receipt and dashboard "Pagos" detail expose the same `subtotal`,
     `discountAmount`, `total` triple consistently.
   Cortesía/discount lines are reflected as such (not as phantom propina).
   Note: with discounts present, customer_charge ≠ Order.subtotal — that's
   expected, the gross subtotal is informational on the receipt and
   `Order.total` is the authoritative net amount.
5. **Inventory side effects are correct** — catalog products deduct stock
   (respecting modifier recipes); cortesía items still deduct (the venue
   gave away real product); custom amounts never touch inventory; serialized
   items still get marked SOLD.
6. **Single transaction** — order + items + cortesía + discounts + customer
   linkage all happen in one DB transaction. No half-built orders if any
   step fails.
7. **`avoqado-tpv` uses `/tpv/...` namespace only** — never crosses to
   `/mobile/...` (auth, permissions, observability all assume the
   namespace boundary).
8. **V1 must NOT modify `/mobile` routes, controllers, service behavior,
   request validation, or response shape** — implement
   `/tpv/.../orders/with-items` in a TPV-owned controller + TPV-owned
   service, even if it duplicates Prisma transaction logic.
   Shared-service extraction is **explicitly deferred to post-V1**.
   This is a hard scope boundary set by the product owner: TPV V1 ships
   without touching anything that other apps (avoqado-android,
   avoqado-ios, etc.) depend on. Refactoring the duplicated logic into
   a shared service is a follow-up ticket sized after V1 validates in
   production.

## 4. Solution overview

Add **one new endpoint** to the backend: `POST /api/v1/tpv/venues/:venueId/orders/with-items`.

Single-call create-order-with-items:

- Accepts items where `productId` is **optional** — when null, the item is
  a custom line (`name` + `unitPrice`, e.g. "Otro importe $25.45")
- Accepts `isCortesia` + `cortesiaReason` per item
- Accepts `itemDiscountId` per item (links to a venue-configured Discount
  entity)
- Accepts `orderDiscountId` at order level (preserves discount identity)
- Validates `subtotal` claimed by client matches the backend's recomputation
  (catches client/server math drift before customer is charged)
- Wraps everything in one DB transaction
- Returns the created order in the standard `OrderDto` shape that TPV's
  Retrofit deserializer already understands

Full request/response shapes and validation rules are in the companion
spec (`docs/CHECKOUT_TPV_ENDPOINT_SPEC.md`).

## 5. Phased rollout

### Phase A — Backend (estimated: 2-3 days)

⚠️ **V1 SCOPE LOCK** (product owner decision): do NOT touch
`services/mobile/order.mobile.service.ts`, `controllers/mobile/*`,
`routes/mobile.routes.ts`, or any shared service extraction in V1.
Implement everything in TPV-owned files only. Even if it duplicates
Prisma transaction logic. iOS / Android / other mobile clients must
not be affected by V1 changes in any way.

**SELECTED for V1: Option A2** — dedicated TPV service.

- Build a new `createOrderWithItems` function in
  `services/tpv/order.tpv.service.ts` that wraps the full Prisma DB
  transaction (create Order, create OrderItems with productId OR
  name+unitPrice, apply per-item cortesía/discounts per the schema
  decision, validate gross subtotal vs items, etc.).
- ~200 lines of duplicated money math — accepted as the cost of safe
  shipping. Future post-V1 ticket will consolidate into a shared
  service after the new flow validates in production.

**A1 (shared service) is DEFERRED to post-V1.** Do not start it now.

Steps:

1. Implement A2 in TPV-owned service.
2. **Schema migration** — Schema A is SELECTED (see spec section
   "Cortesía and discounts — schema implications"): add `isCortesia:
   Boolean (default false)`, `cortesiaReason: String?`,
   `appliedDiscountId: String? FK to Discount` to `OrderItem`; add
   `appliedToItemIds: String[]` (or `OrderDiscountItem` join table) to
   `OrderDiscount`. Backfill-safe defaults required. Update reports
   (`sales-summary.dashboard.service.ts:235`,
   `sales-by-item.dashboard.service.ts:154`) where richer aggregation
   helps. Fallback to Schema B only if migration is judged too risky —
   must flag to product owner first.
3. Implement free-cart flow A in the same transaction (see spec
   section "Free-cart (100% cortesía) flow"): when `total = 0`
   **ALWAYS** (independent of cart shape) → mark COMPLETED + PAID,
   create `Payment{amount=0, paymentMethod=OTHER, processorData=
   {type:'FREE_CART', reason}}`. Inventory deduction is a per-item
   conditional inside the loop: only items where `productId != null
   && getProductInventoryMethod(productId) != null` get deducted;
   custom items and untracked products are skipped silently. A cart of
   100% custom items at $0 still closes cleanly with Payment $0.
4. Implement the new TPV controller `controllers/tpv/order.tpv.controller.ts`
   exposing `POST /tpv/venues/:venueId/orders/with-items` that returns the
   **full TPV `OrderDto` shape** (not the reduced mobile response).
5. Wire route in `routes/tpv.routes.ts` with `authenticateTokenMiddleware`
   + `checkPermission('orders:create')`.
6. Ensure response items always include `productName` (and `productSku`,
   `categoryName`) so TPV can render custom items correctly when
   re-fetching the order. Today the TPV `OrderItemDetailDto` lacks
   `productName` and the mapper falls back to "Producto eliminado".
7. Update inventory log line in `payment.tpv.service.ts:554` to distinguish
   `CUSTOM_LINE_ITEM` from `DELETED_PRODUCT`.
8. Deploy to dev, run the acceptance criteria from the spec.
9. Deploy to prod (additive, zero risk to existing endpoints).

### Phase B — TPV (estimated: 2-3 days, after Phase A in dev)

1. Add `TpvCreateOrderWithItemsRequest` DTO to
   `features/ordering/data/dto/` mirroring the body in the spec
   (Gson-annotated, `productId: String?` nullable, all the per-item flags).
2. **Update `OrderItemDetailDto`** in `TableDto.kt` to add
   `productName: String?`, `productSku: String?`, `categoryName: String?`
   so custom line items round-trip when re-fetching the order.
3. **Update `OrderMappers.kt:63`** so the precedence is
   `item.product?.name ?: item.productName ?: "(producto sin nombre)"`.
   The "Producto eliminado" string should only appear when truly both are
   missing (real data integrity issue, not intentional custom items).
4. Add `@POST("tpv/venues/{venueId}/orders/with-items")` method to
   `OrderApiService`.
5. Add domain method
   `OrderRepository.createTpvOrderWithItems(...)` (interface) + impl in
   `OrderRepositoryImpl`. Map `CartItem` → request item with the conditional
   pattern:
   ```kotlin
   when (val type = item.type) {
     is CartItemType.ProductItem -> TpvOrderItemDto(
       productId = type.productId,
       modifierIds = item.selectedModifiers.map { it.modifierId },
       isCortesia = item.isCortesia,
       cortesiaReason = item.cortesiaReason,
       itemDiscountId = item.itemDiscountId,
       quantity = item.quantity,
       notes = item.itemNote,
     )
     CartItemType.CustomAmount -> TpvOrderItemDto(
       productId = null,
       name = item.name,
       unitPrice = item.effectiveUnitPriceCents,
       quantity = item.quantity,
       notes = item.itemNote,
     )
   }
   ```
6. Update `CheckoutViewModel.createOrderWithCurrentItems()` to call the new
   method (replaces the current `createOrder + addItemsToOrder` 2-call
   pattern that drops custom items).
7. Update `CheckoutViewModel.prepareForPayment()` — no longer needs the
   "drop custom items" warning log.
8. Hide/disable the "Agregar impuesto" action in the new Cobrar flow for
   V1, and force the new request DTO to send `taxAmount = 0`. Existing
   legacy flows keep their current tax behavior.
9. Add tests:
   - `prepareForPayment with mixed cart sends both products and custom items`
   - `cortesía item is sent with isCortesia + cortesiaReason flags`
   - `per-item discount sends itemDiscountId`
   - `order-level discount sends orderDiscountId`
   - `re-fetched order with custom items renders productName, not "Producto eliminado"`
   - `new Cobrar does not expose Agregar impuesto and request taxAmount is 0`
10. Verify legacy flows untouched by running the existing test suite.
11. QA on PAX A910S device: every cart configuration in §6.

### Phase C — Validation in production (1-2 weeks)

1. Roll out TPV with the new Cobrar flow gated by `tpvSettings.showCheckout`
   (default true; can be flipped per venue from dashboard).
2. Watch Crashlytics + dashboard for receipt anomalies.
3. Reconcile a sample of mixed carts using the gross/net invariants
   from §3 constraint #4. The check that matters is
   `PaymentScreen charged amount == Order.total + tip`, NOT
   `charged == Order.subtotal` (gross subtotal is intentionally HIGHER
   than the charge when discounts/cortesía are present).
4. Confirm dashboard "Pagos" detail shows custom items as line items
   (not as Propina). For orders with cortesía, confirm the line shows
   the gross price + cortesía marker, not $0.
5. After clean validation period, schedule Phase 8 of the broader
   refactor: delete legacy "Pago Rápido" and "Órdenes" entry points.
   See `CHECKOUT_REFACTOR_PLAN.md` Phase 8.

## 6. Test matrix (acceptance scenarios)

Every scenario must be runnable on a real PAX A910S in sandbox + verified
in dashboard.

All "subtotal/discount/tax/total" columns use the GROSS-subtotal definition
from the spec ("Subtotal semantics — gross vs net"). `subtotal = catalog
sum BEFORE cortesía/discount`, `discountAmount = sum of all reductions`,
`taxAmount = 0 in V1`, `total = subtotal - discountAmount`. The existing payment formula
`subtotal - discountAmount + tip` then produces `payment = total + tip`.

| # | Cart | Pay screen charges | Order.subtotal | Order.discountAmount | Order.total | Receipt | Inventory |
|---|---|---|---|---|---|---|---|
| 1 | 1× Hamburguesa $200 | $200 | $200 | $0 | $200 | `Hamburguesa $200` | -1 burger |
| 2 | 1× Hamburguesa $200 + Otro importe $50 | $250 | $250 | $0 | $250 | `Hamburguesa $200` + `Otro importe $50` | -1 burger, no other |
| 3 | 1× Hamburguesa $200 (cortesía, "Cliente VIP") | $0 (free-flow A: order auto-completed) | $200 | $200 | $0 | `Hamburguesa $200 — Cortesía: Cliente VIP` | -1 burger (gave it away) |
| 4 | 1× Hamburguesa $200 + 1× Pizza $300 (cortesía) | $200 | $500 | $300 | $200 | `Hamburguesa $200` + `Pizza $300 — Cortesía` | -1 burger, -1 pizza |
| 5 | 1× Hamburguesa $200 with itemDiscount "20% off" | $160 | $200 | $40 | $160 | `Hamburguesa $200 — 20% off` | -1 burger |
| 6 | 1× Pizza $300 + Otro importe $50 + orderDiscount "10% Happy Hour" | $315 | $350 | $35 | $315 | items + `Descuento Happy Hour 10%: -$35` | -1 pizza |
| 7 | 1× Pizza $300 with modifier "Extra cheese +$30" | $330 | $330 | $0 | $330 | `Pizza $300` + `Extra cheese +$30` | -1 pizza, -1 cheese unit |
| 8 | ~~Pay-later~~ | **N/A — Pay-later DISABLED in v1 of new Cobrar.** Per spec "Pay-later settlement" decision (option 2). The kebab "Pagar después" is hidden in the new CartDetailsSheet. Legacy OrderingWelcome path keeps pay-later available. |
| 9 | Empty cart | UI prevents tap | n/a | n/a | n/a | n/a | n/a |
| 10 | All custom amounts (no products) | $X | n/a (FAST flow, no order created) | n/a | n/a | flat amount receipt | no inventory move |

For each scenario, verify in 4 places:
- TPV PaymentScreen total
- Backend Order DB row (`subtotal`, `discountAmount`, `total`, `items`)
- Dashboard "Pagos" detail screen
- Printed receipt (PAX paper)

For row 3 specifically (full-cart cortesía → $0 charge): also verify
that `Payment` row was created with `amount = 0` and a marker indicating
the source was cortesía (per "Free-cart" spec section). Inventory must
show -1 burger.

## 7. Rollback plan

If the new endpoint surfaces a money correctness bug in production:

1. **Frontend kill switch** — `tpvSettings.showCheckout = false` per venue.
   Operator returns to legacy Pago Rápido + Órdenes (which keep working).
   No APK update needed.
2. **Backend kill switch** — return 503 from the new endpoint OR remove the
   route. Both legacy `/tpv/.../orders` and `/orders/:id/items` remain
   functional.
3. **Data fix** — for any orders created via the bad endpoint with wrong
   subtotals, run a backfill script that recomputes from items. This is
   only relevant if the bug is a math error, not a "wrong column" error.

Recovery time target: <15 minutes for kill switch, <2 hours for data fix.

## 8. Open questions for the reviewer

**Round 7 update**: 8.2, 8.3, 8.4, 8.5, 8.7, and 8.8 were the last
remaining product-side decisions blocking backend implementation. All
are now CLOSED below. The few remaining items in this section (8.1,
8.6, 8.9, 8.10) were already closed in earlier rounds — kept here as
audit trail with their resolutions inline.

### 8.1 Inventory rule for cortesía — CLOSED (V1)

**SELECTED: cortesía items ALWAYS deduct stock** when the product tracks
inventory (`Product.trackInventory = true` and `getProductInventoryMethod(productId)`
returns `QUANTITY` or `RECIPE`). Reason: real product was given
away. No `consumesInventory` flag for V1. Future "cortesía de
marketing" (no real consumption) would need a separate
`virtualCortesia` concept — not in V1 scope. Untracked products and
custom items naturally skip deduction (no inventory record exists).

### 8.2 Per-item discount validation — CLOSED (V1)

**SELECTED: (a) strict.** Backend resolves `itemDiscountId`, applies
its math against the line's `unitPrice × quantity`, and rejects with
400 if `body.discountAmount` doesn't match the resolved value within
±$0.01 tolerance (rounding). Reason: V1 deals in real money on real
PAX terminals — moving the trust boundary to the client (option b)
makes the operator the source of truth for discounts, which means a
buggy TPV release could silently produce wrong `Discount.usageCount`
metrics in the dashboard. Strict mode catches drift at the API
boundary. Performance cost is one extra `Discount` row read per
itemDiscountId — negligible.

### 8.3 Order discount + per-item discount stacking — CLOSED (V1)

**SELECTED: per-item discounts apply FIRST, then order-level discount
applies to the post-item-discount subtotal.** This is the convention
used by Square, Toast, and Clover. Order shape:
- Each `OrderItem.discountAmount` reflects only its per-item discount
  (not a share of the order-level discount).
- `Order.discountAmount` = sum of per-item discounts + order-level
  discount amount (computed against the post-item-discount subtotal).
- `Order.total = Order.subtotal (GROSS) - Order.discountAmount`.

Example: cart `[Pizza $300 with 20% off, Soda $40]`, order discount
"10% off entire order":
- Pizza per-item: `discountAmount = $60`, lineNet = $240
- Post-item-discount subtotal: $240 + $40 = $280
- Order-level 10% off $280 = $28
- `Order.subtotal = $340` (GROSS), `Order.discountAmount = $88`,
  `Order.total = $252`.

Rejection rule: if the resolved order-level discount would push
`Order.total < 0`, reject the cart with 400. (Real life: 100% off
order + per-item cortesía already at $0 doesn't add value.)

### 8.4 Cortesía + per-item discount on the same line — CLOSED (V1)

**SELECTED: REJECT with 400.** If a single OrderItem has both
`isCortesia = true` AND `itemDiscountId != null`, backend returns 400
`CORTESIA_AND_DISCOUNT_CONFLICT` with the offending line index in the
error body. Reason: cortesía already zeros the line; a discount on top
is meaningless and produces ambiguous receipts ("Cortesía: Cliente
VIP" + "20% off"). TPV UI must enforce the same rule (the
`ProductDetailSheet` should hide the discount selector once cortesía
toggle is on, and vice versa). Documented as TPV-side acceptance
criterion in §6.

### 8.5 Custom line items + tax — CLOSED (V1)

**SELECTED: tax disabled in new Cobrar V1.** Custom line items and catalog
items created through the new `/tpv/.../orders/with-items` endpoint must
have `taxAmount = 0`; backend rejects non-zero tax defensively.
Reason: the current TPV payment completion math in
`payment.tpv.service.ts` uses `subtotal - discountAmount + tip` and does
not include `Order.taxAmount`, so allowing tax here would create a real
conciliation bug. TPV hides the "Agregar impuesto" action in the new
Cobrar surface for V1. Existing legacy flows keep their current tax
behavior. Follow-up ticket: "Support order tax in new Cobrar by updating
payment.tpv.service.ts fully-paid math + receipts + dashboard totals."

### 8.6 Pay-later cart with custom items — RESOLVED (no longer open)

~~Open question superseded by R2.5 decision.~~

**Pay-later is BLOCKED in v1 of the new Cobrar flow** (per spec section
"Pay-later settlement (gap to address before shipping)" and plan §11
R2.5). TPV's `CartDetailsSheet` hides the "Pagar después" kebab entry in
this surface. The legacy `OrderingWelcome → MenuScreen` pay-later path
is unchanged. Therefore:

- The new endpoint `POST /tpv/.../orders/with-items` does NOT need to
  accept pay-later carts in v1. Backend can reject `paymentStatus =
  PENDING` requests as 400 if it wants stricter contract enforcement.
- Mixed carts in pay-later are out of scope until the follow-up ticket
  ("Fix `settleOrder` to deduct inventory + reconcile historical
  pay-later drift") is sized and shipped.

If a future v2 re-enables pay-later in the new Cobrar, this question
re-opens (and the answer probably IS "any shape, identical handling" —
but that's for v2, not v1).

### 8.7 Audit trail for cortesía — CLOSED (V1)

**SELECTED: write an `OrderAction` row per cortesía item.** Inside the
same Prisma transaction as the create, for each `OrderItem` with
`isCortesia = true`, insert:

```ts
OrderAction.create({
  orderId,
  actionType: 'COMP',        // existing ActionType enum value
  performedById: input.staffId,
  reason: cortesiaReason,
  metadata: {
    orderItemId,
    productId,                // null for custom line
    productName,
    lineSubtotalGivenAway,    // = unitPrice × quantity (GROSS)
    cortesiaReason,
  },
})
```

Reason: `OrderAction` already exists, is queryable by `performedById` and
`actionType`, and the metadata Json carries the cortesía-specific fields
without schema bloat. The existing legacy comp flow writes
`actionType = 'COMP'`; new per-item cortesías reuse that enum value and
distinguish item-level data through `metadata.orderItemId`.

### 8.8 Receipt template change — CLOSED (V1)

**SELECTED: receipt templates already handle `productId = null`
correctly.** Verified manually: both the dashboard's printable HTML
receipt template (`apps/web-dashboard/src/components/Receipt/...`)
and the PAX paper receipt printed by `PrinterManager.kt` render
`OrderItem` lines using `productName`, `quantity`, `total` from the
item itself — they don't dereference `productId` to look up the
product name. Custom line items pass through correctly as long as
the backend returns `productName` populated in the response (covered
by R1.3 — `OrderItemDetailDto.productName` is now mandatory in the
TPV response shape). No template change required for V1.

Risk: if any non-receipt UI surface (e.g. "Sales by Item" report on
the dashboard) reads `productId` and joins to `Product` to display
the name, custom lines could show as blank. Tracked as a follow-up
audit in §11 R7.x — backend QA must grep `OrderItem.productId` joins
in the dashboard service layer before the migration ships and add
fallback to `OrderItem.productName` where needed.

### 8.9 What's the exact name of the existing TPV permission for order
creation? The plan assumes `orders:create` — verify against
`lib/permissions.ts` in the backend.

### 8.10 Externalized service vs duplicated controller — CLOSED for V1

**SELECTED for V1: Option A2** — dedicated TPV service with duplicated
Prisma transaction logic. Decision driver: product owner explicitly
scoped V1 to NOT touch `/mobile` routes, controllers, service behavior,
request validation, or response shape. Shared-service extraction is
deferred to post-V1.

**Option A1 — DEFERRED to post-V1**: Extract create-order-with-items
core to `services/shared/order.create.service.ts` so both `/mobile/...`
and `/tpv/.../with-items` callers use it. The right long-term
architecture, but requires touching `/mobile` callers (test coverage,
regression risk) which is explicitly out of V1 scope. Schedule as a
post-V1 follow-up ticket once the new TPV flow is validated in
production.

**Option (a) — REJECTED permanently**: directly reading new fields off
the existing `services/mobile/order.mobile.service.ts` (extending it
in place). Doing so changes the mobile contract.

V1 plan accepts the maintenance cost of the duplicated money math in
exchange for zero risk to iOS/Android mobile clients and the legacy
TPV flows.

## 9. Files affected (after implementation)

### Backend (avoqado-server)

- `controllers/tpv/order.tpv.controller.ts` (modified — adds `createOrderWithItems` handler)
- `routes/tpv.routes.ts` (modified — adds `POST /venues/:venueId/orders/with-items` with `checkPermission('orders:create')` — note: NOT `tpv-orders:create`, that permission doesn't exist; `orders:create` is the existing one used by the legacy `POST /venues/:venueId/orders` route at `tpv.routes.ts:672`)
- `services/tpv/order.tpv.service.ts` (modified — adds new `createOrderWithItems` function with full Prisma transaction. **V1 scope locks**: do NOT touch `services/mobile/order.mobile.service.ts`; do NOT extract a shared service. A2 was selected over A1 for V1 — see §8.10. Shared-service extraction is a post-V1 follow-up ticket)
- `services/tpv/order.tpv.mapper.ts` (NEW — extracted from `flattenOrderModifiers` inside `order.tpv.service.ts` so the new controller can call `mapToTpvOrderDto(order)` cleanly)
- `services/tpv/payment.tpv.service.ts` (small — log fix on line 554 to distinguish `CUSTOM_LINE_ITEM` from `DELETED_PRODUCT`)
- `prisma/schema.prisma` (**required** — Schema A is SELECTED for V1): add `isCortesia: Boolean (default false)`, `cortesiaReason: String?`, `appliedDiscountId: String? FK to Discount` to `OrderItem`; add `appliedToItemIds: String[]` (or `OrderDiscountItem` join table) to `OrderDiscount`. Backfill-safe defaults. Migration mandatory. Fallback to Schema B (no migration; per-item linkage lives in `OrderAction.metadata` Json) only if backend team flags migration risk and PO signs off on the looser schema.
- Reports affected if cortesía maps to existing `discountAmount` (Option B): `services/dashboard/sales-summary.dashboard.service.ts:235`, `services/dashboard/sales-by-item.dashboard.service.ts:154` may need updates depending on how cortesía should aggregate
- Tests for the new controller + service. NOT in V1 scope: tests for `/mobile/...` (the mobile contract doesn't change)
- **NOT touched in V1** (explicit scope boundary):
  - `services/mobile/*` — anything under here
  - `controllers/mobile/*`
  - `routes/mobile.routes.ts`
  - Any shared service extraction (`services/shared/*`)

### TPV (avoqado-tpv)

- `features/ordering/data/dto/TpvCreateOrderWithItemsRequest.kt` (new)
- `features/ordering/data/dto/TableDto.kt` (modified — add `productName: String?`, `productSku: String?`, `categoryName: String?` to `OrderItemDetailDto` so custom items round-trip when the order is re-fetched)
- `features/ordering/data/api/OrderApiService.kt` (modified — adds endpoint method)
- `features/ordering/data/mappers/OrderMappers.kt` (modified — line 63: change precedence to `item.product?.name ?: item.productName ?: "(producto sin nombre)"` so custom items don't render as "Producto eliminado")
- `features/ordering/domain/OrderRepository.kt` (modified — adds interface method + new domain types if needed)
- `features/ordering/data/repository/OrderRepositoryImpl.kt` (modified — implements new method)
- `features/checkout/presentation/CheckoutViewModel.kt` (modified — switches `createOrderWithCurrentItems()` to the new endpoint)
- `app/src/test/java/.../checkout/presentation/CheckoutViewModelTest.kt` (modified — new tests for cortesía/discount transmission + custom item round-trip)
- `CHANGELOG.md` (entry)
- `docs/CHECKOUT_REFACTOR_PLAN.md` (mark Phase 5 mixed-cart limitation as resolved)
- `docs/CHECKOUT_TPV_ENDPOINT_SPEC.md` (mark as IMPLEMENTED with link to PRs)

## 10. Reviewer findings — resolved (round 1)

A first review by an external LLM surfaced 5 blocking issues. All resolved
in this revision; documented here so future reviewers can see what changed
and why, without re-discovering the same blockers.

### R1.1 — Permission name was wrong

The original spec said `tpv-orders:create`. That permission **does not
exist** in `lib/permissions.ts`. The legacy TPV order route at
`tpv.routes.ts:672` uses the existing `orders:create` permission.

**Resolution**: Both docs now use `orders:create`. No new permission
needs to be rolled out to dashboard / TPV / users.

### R1.2 — Response shape mismatch (would silently break TPV parsing)

The original spec said the new endpoint returns "the same `OrderDto` TPV's
existing endpoints return". But the existing
`services/mobile/order.mobile.service.ts:269` returns `CreatedOrderResponse`
— a reduced shape WITHOUT `venueId`, `tableId`, `servedById`, `updatedAt`,
`version`, `remainingBalance`, `paymentStatus`, `kitchenStatus`. TPV's
`OrderDto` (`features/ordering/data/dto/TableDto.kt:185`) requires those
fields.

**Resolution**: Spec now explicitly requires the **full TPV `OrderDto`
shape** in the response, with a callout that the backend must wrap or
re-shape if it reuses any internals from the mobile service. The "service
location" guidance in this plan was also updated (see R1.5 below) to make
the shared-service path explicit.

### R1.3 — Custom line items would render as "Producto eliminado" in TPV

Backend stores `productName` for custom items, but the TPV
`OrderItemDetailDto` (`TableDto.kt:57`) doesn't declare that field, and
the mapper at `OrderMappers.kt:63` falls back to `product?.name ??
"Producto eliminado"`. Result: any time a custom-item order is re-fetched
from backend (e.g. opened from "Pedidos"), the custom line shows as
"Producto eliminado" — wrong and confusing.

**Resolution**: Spec adds a "Custom line items — display fields and
schema" section requiring backend to always return `productName` (+
`productSku`, `categoryName`) per item. TPV must add those fields to
`OrderItemDetailDto` and update the mapper precedence to
`item.product?.name ?: item.productName ?: "(producto sin nombre)"`.
"Producto eliminado" string should ONLY appear when both are missing —
that's a real data integrity issue, not a custom item.

### R1.4 — Cortesía / per-item discount don't have schema fields today

The spec assumed first-class fields like `OrderItem.isCortesia`,
`OrderItem.cortesiaReason`, `OrderItem.itemDiscountId`, and
`OrderDiscount.appliedToItemIds`. Schema check shows none of these exist
(`prisma/schema.prisma:2181` for `OrderItem`, `:4457` for `OrderDiscount`).
Naive workaround would be to set `OrderItem.total = 0` for cortesía with
`discountAmount = 0` — which silently breaks the "Sales by Item" and
"Sales Summary" reports that aggregate `discountAmount`
(`sales-by-item.dashboard.service.ts:154`, `sales-summary.dashboard.service.ts:235`).

**Resolution**: Spec adds a "Cortesía and discounts — schema implications"
section presenting two paths:
- **A) Schema migration** — clean, future-proof, but live-table migration risk
- **B) Map cortesía's catalog price to `discountAmount`** so reports still
  add up; identity (cortesía vs generic discount) lives in `OrderAction`
  audit trail; receipt rendering detects "discountAmount == lineTotal" →
  "Cortesía"

Backend team picks A or B before implementation starts. The spec calls
this decision out as **blocking** for the wire format.

### R1.5 — Don't tighten the `/mobile` service contract

The original plan said "extend `services/mobile/order.mobile.service.ts`
or extract to shared". If the team picked "extend" and added required
fields like `subtotal` validation, iOS/Android mobile callers break.

**Resolution**: Phase A in this plan is explicit — do NOT modify the
`/mobile` service in a contract-breaking way. After round 5 (see below),
the V1 scope was further locked to **Option A2 only** (dedicated TPV
service); A1 (shared service) is deferred post-V1. Mobile contract is
held constant.

### Round 5 — V1 scope lock (product owner decision)

Product owner explicitly scoped V1 to NOT modify any `/mobile` routes,
controllers, service behavior, request validation, or response shape.
Specifically: do not refactor toward a shared service in V1. Deferred
post-V1.

**Decision impact:**
- §8.10 closed: A2 SELECTED for V1, A1 DEFERRED post-V1, (a) REJECTED
  permanently
- §3 constraints: added constraint #8 ("V1 must NOT modify `/mobile` …")
- §5 Phase A rewritten: A2 is the only path; do not start A1 now
- §9 Files affected: removed `services/shared/*` from V1 scope; added
  explicit "NOT touched in V1" list (mobile/* + shared/*)
- Spec section "Suggested backend implementation" rewritten: A2
  pseudocode is the SELECTED implementation; A1 documented as "future
  work (deferred to post-V1)"

**Trade-off accepted**: ~200 lines of duplicated money math between
`services/tpv/order.tpv.service.ts:createOrderWithItems` (new) and
`services/mobile/order.mobile.service.ts:createOrderWithItems`
(existing). Future post-V1 ticket consolidates them.

**Reasoning** (in product owner's words):
> "I don't want to touch the endpoints or functions related to the
> avoqado-android repo which is aside of this repo, and also I don't
> want to break Pago Rápido and Órdenes! So I don't want to unify them,
> just make sure Cobrar new function works. If a new endpoint /tpv and
> functions are necessary, do it, but I don't want to touch or break
> something."

This is the safest possible scope for V1: zero contract changes to any
existing client (avoqado-android, avoqado-ios, legacy TPV flows).

---

## 11. Reviewer findings — resolved (round 2)

The round 2 review against `avoqado-server` source confirmed all 5 of
round 1's fixes were correctly identified, but flagged 5 additional
blockers and called out that one round-1 fix was contradicted elsewhere
in the same doc. All addressed in this revision.

### R2.1 — Subtotal semantics (gross vs net) was undefined

The spec said "subtotal must reflect cortesía/discount applied
client-side" but `payment.tpv.service.ts:368` computes the customer
charge as `order.subtotal - order.discountAmount + tip`. If we shipped
"subtotal = net" AND wrote `discountAmount = sumOfCortesias`, the
formula would double-deduct → customer pays correct cart amount but
order_records and payment_records disagree → conciliation breaks.

**Resolution**: New spec section "Subtotal semantics — gross vs net
(CLOSED: GROSS)" defines:
- `Order.subtotal` = GROSS (sum of `unitPrice × quantity` at catalog
  prices, before any cortesía/discount)
- `Order.discountAmount` = sum of cortesía + per-item + order-level
  discounts
- `Order.total` = `subtotal - discountAmount`
- The existing `payment.tpv.service.ts:368` formula (`subtotal -
  discountAmount + tip`) keeps working unchanged.
- TPV computes both `subtotal` and `total` client-side; backend
  validates they match its own recomputation; mismatch → 400 before
  the customer is charged.

### R2.2 — Implementation snippet contradicted R1.2 / R1.5

The "Suggested backend implementation" code block in the spec still
showed the controller calling `orderMobileService.createOrderWithItems()`
directly and returning `{ data: order }` — i.e. the reduced mobile
shape. That contradicted both the response-shape requirement (R1.2,
must be full TPV `OrderDto`) and the don't-extend-mobile guidance
(R1.5).

**Resolution**: Code block replaced. After round 5 (V1 scope lock),
the SELECTED implementation is **A2** (dedicated TPV service). The
spec's "Suggested backend implementation" section now shows A2
pseudocode as the V1 path, with a clear note that the controller MUST
run the result through a TPV-shape mapper. The mapper does NOT exist
today — the spec calls this out explicitly (it points to
`flattenOrderModifiers` inside `order.tpv.service.ts` as the closest
existing helper, and recommends extracting a reusable
`mapToTpvOrderDto` as part of this work). Direct reuse of mobile
service is explicitly forbidden.

### R2.3 — Schema option B was too optimistic about per-item linkage

Round 1 said "Option B: map cortesía to existing `discountAmount`,
identity in `OrderAction` audit trail". But `OrderDiscount` schema check
shows it has `isComp` + `compReason` fields already (round 1 missed
that), and crucially does NOT persist `appliedToItemIds` — meaning
per-item linkage HAS to live in `OrderAction.metadata` (a `Json`
field), and receipt rendering + dashboard "Pagos" detail must JOIN
or query `OrderAction` to render per-line attribution.

**Resolution**: Spec's "Cortesía and discounts" section rewritten:
- Acknowledges existing `OrderDiscount.isComp` + `compReason`
- Spells out exactly where per-item linkage lives in option B
  (`OrderAction.metadata` `Json` blob — no schema enforcement,
  receipt/dashboard teams must commit to this convention)
- Calls the decision out as **the most material in the spec** because
  it dictates schema + reports + receipt template shape
- Notes A is recommended if there's appetite for migration; B is
  acceptable only if receipt + dashboard teams sign on to reading
  `OrderAction.metadata`

### R2.4 — Free cart (100% cortesía) wasn't covered

Inventory deduction trigger fires only when `isFullyPaid`
(`payment.tpv.service.ts:506`). For a cart that's 100% cortesía
(`total = 0`), no payment record is created, the trigger never fires,
and stock isn't deducted — even though the venue gave away real
product. Silent.

**Resolution**: New spec section "Free-cart (100% cortesía) flow —
explicit completion required" with two options:
- **Free-flow A (SELECTED, round 7 update)**: backend's new endpoint
  detects `total == 0` and **always** marks order COMPLETED + PAID,
  regardless of cart shape (tracked, untracked, or 100% custom items).
  Inventory deduction is a per-item conditional inside the loop: only
  items where `productId != null && getProductInventoryMethod(productId) != null` get
  deducted. Then creates a `Payment` row with `amount = 0` and
  `paymentMethod = OTHER` (note: the existing `PaymentMethod` enum has
  no `COMP` value — only CASH/CREDIT_CARD/DEBIT_CARD/DIGITAL_WALLET/
  BANK_TRANSFER/CRYPTOCURRENCY/OTHER). The free-cart marker lives in
  `Payment.processorData` or metadata as `{ type: "FREE_CART", reason }`.
  Backend can alternatively add `COMP` to the enum via migration if
  reports need clear typing — sized separately. All in the same DB
  transaction.
- **Free-flow B**: TPV makes a second call to a new
  `POST /tpv/.../orders/:id/complete-free` endpoint. Cleaner separation
  but reintroduces the half-built-order problem.
- Spec recommends A (atomic).

### R2.5 — Pay-later settlement gap (existing bug, exposed by Cobrar)

Existing `settleOrder` (`payment.tpv.service.ts:561`) marks
`paymentStatus = PAID` and creates a `Payment` record but doesn't
complete the order status nor trigger inventory deduction. Pay-later
orders settled from the dashboard today already have this drift; if
TPV's new Cobrar enables pay-later, the drift gets worse.

**Resolution (closed for v1)**: SELECTED option 2 — block pay-later in
the new Cobrar flow for v1. Reasoning:
- Option 1 (fix `settleOrder`) is the right long-term fix, but starts
  surfacing the stock drift that's been silently building on existing
  pay-later orders. Needs its own backfill / reconciliation plan.
  Parked as separate ticket.
- Option 3 (ship with drift) explicitly rejected by operations.

V1 impact: TPV's new `CartDetailsSheet` hides the "Pagar después" kebab
entry. The legacy `OrderingWelcome → MenuScreen` pay-later flow stays
untouched (with its existing drift behavior, which operations already
absorbs). Backend has zero v1 work for this. Follow-up ticket sized
separately by backend.

### Round 4 review — additional cleanup (5 fixes)

After round 3, a 4th LLM review caught 5 residual contradictions
that survived round 3's edits. All resolved here.

**R4.1** — Spec section "Backend already has the core capability"
listed "Subtotal mismatch detection" as verifying `subtotal` against
"items + cortesía + discounts". That phrasing implied NET subtotal,
contradicting the gross/net definition. Reworded to specify GROSS
explicitly and link to the "Subtotal semantics" section.

**R4.2** — Plan §3 constraint #4 said "customer charge, backend
order subtotal, receipt subtotal, dashboard subtotal all match
exactly." With the gross/net definition, that's false when
discounts are present (gross subtotal is intentionally HIGHER than
the customer charge). Rewritten as 5 explicit invariants:
- `customer_charge == Order.total + tip`
- `Order.subtotal == Σ items[].lineGross`
- `Order.discountAmount == Σ all reductions`
- `Order.total == Order.subtotal - Order.discountAmount`
- Receipt + dashboard expose the same triple consistently
With note that gross subtotal ≠ payment is expected, and `Order.total`
is the authoritative amount.

**R4.3** — Plan §8.6 ("Pay-later cart with custom items") still said
"any shape, leaves PENDING" — contradicting the closed v1 decision to
block pay-later in the new Cobrar. Marked as RESOLVED (no longer
open), referencing the v1 block and noting that the question reopens
only if v2 re-enables pay-later in the new flow.

**R4.4** — Spec did not specify whether `OrderItem.total` should be
GROSS or NET. The legacy backend reads it as GROSS (per `compItems`
and `applyDiscount` which mutate `discountAmount` but leave `total`
alone, plus dashboard "Sales by Item" sums `total` for gross
revenue). New spec section "OrderItem.total semantics" makes this
explicit: GROSS, with `lineNet = total - discountAmount` computed
inline by receipt + dashboard renderers. Setting `total = 0` for
cortesía would silently break legacy reports — explicit warning.

**R4.5** — AC for per-item discount said "line subtotal=$240" without
naming the gross/discount/net breakdown. Rewritten to spell out:
`unitPrice=$300, quantity=1, total=$300 (GROSS), discountAmount=$60,
implicit lineNet=$240`. Same gross/net pattern as the spec's Order
fields.

### R2.6 — §8.10 still listed "extend mobile" as acceptable

Round 1 fix R1.5 said don't break the mobile contract, but the open
question §8.10 still listed "(a) Reuse `services/mobile/order.mobile.service.ts`
after extending it" as a neutral option.

**Resolution**: §8.10 rewritten. Option (a) explicitly REJECTED. After
round 5 (V1 scope lock by product owner), only **A2 (dedicated TPV
service) is the V1 path**; A1 (shared service) is DEFERRED post-V1.
See "Round 5" subsection further down for the closed decision.

### Round 6 — residual cleanup + close Free-cart A + close Schema A

After round 5, a 6th LLM review caught 6 more issues. All resolved.

**R6.1**: Spec said "Add a TPV controller that delegates to the existing
service". Contradicts A2. Reworded to "TPV controller backed by a
dedicated TPV service. Service must NOT import or wrap the existing
mobile service in V1".

**R6.2**: Spec said "If the backend reuses internals from the mobile
service, it must wrap or re-shape the result through the same TPV DTO
mapper". Confused with V1 scope. Reworded to "TPV-owned service must
use a TPV-owned mapper. Do not call into mobile mappers or services."

**R6.3**: Plan Phase C step 3 said "compare backend order subtotal vs
PaymentScreen charged amount — must match exactly". False with
discounts present (gross subtotal is intentionally HIGHER than the
charge). Reworded: the check that matters is `PaymentScreen charged
amount == Order.total + tip`, NOT `charged == Order.subtotal`.

**R6.4**: Spec section "Free-cart (100% cortesía) flow" still listed
two options as a pending decision. Closed for V1: **Free-flow A
SELECTED** (atomic completion in the new endpoint — sets
COMPLETED+PAID, deducts inventory, creates `Payment{amount=0,
paymentMethod=OTHER, processorData={type:'FREE_CART', reason}}`). B
rejected (reintroduces half-built-order problem).

**R6.5**: Plan §11 had stale narrative wording in R2.2 and R2.6 that
sounded like A1/A2 were both still valid. Updated to reflect the round
5 closure (A2 is V1, A1 is post-V1).

**R6.6**: Schema A vs B was the last open architecture decision. Closed
for V1: **Schema A SELECTED** — Prisma migration adds `isCortesia`,
`cortesiaReason`, `appliedDiscountId` to `OrderItem` and
`appliedToItemIds` to `OrderDiscount`. Reasoning: V1 product owner
stance is "cortesía y todo debe funcionar bien — esto es dinero real".
Schema A delivers clean audit trail, structured reports, and no
permanent dashboard dependency on `OrderAction.metadata` Json reads.
Fallback to Schema B only if backend determines migration risk is
unacceptable (must flag to product owner first).

After round 6, the only architecture decisions still requiring human
input are:
- **None** for V1 functionality. All architectural choices are closed.
- Backend team retains the right to flag the Schema A migration as
  too risky and propose Schema B as a fallback (with PO buy-in).
- Free-cart A's dependency on the `PaymentMethod` enum: if backend
  team wants to add a `COMP` value via migration for cleaner reports,
  that's a deliberate scope expansion they can size separately.

### Round 7 review — final residual cleanup (CLOSED, ready for backend handoff)

The round 6 reviewer found 6 remaining items split into two priority bands:
2 P1 blockers (free-cart gate + open product questions), 2 P2 cleanups
(Schema B traces in pseudocode + §9 "conditional" wording), and 2 P3
cosmetic items (stale logger reference + stale "BLOCKING DECISION"
headers). All resolved.

**R7.1 (P1) — Free-cart "has tracked products" gate removed**: spec
section "Free-cart (100% cortesía) flow" and plan §5 Phase A step 3
previously gated COMPLETED+PAID+Payment $0 on the cart containing at
least one tracked product. That left free-carts of 100% custom items
(or 100% untracked products) stuck in PENDING with no payment record —
the exact half-built-order state this endpoint was designed to
eliminate. Fixed: `total = 0` now ALWAYS triggers atomic completion
(COMPLETED + PAID + Payment $0 with FREE_CART marker), regardless of
cart shape. Inventory deduction is a per-item conditional inside the
loop: only items where `productId != null && getProductInventoryMethod(productId) != null`
get deducted. Custom items and untracked products are skipped
silently with no error. Pseudocode in spec (around L298), prose
section "Free-cart flow", plan Phase A step 3, and §11 R2.4
description all updated consistently.

**R7.2 (P1) — Open questions 8.2, 8.3, 8.4, 8.5, 8.7, 8.8 closed**:
the round 6 reviewer flagged that §8 still listed real product
decisions as open (stacking, cortesía+discount conflict, custom-item
tax, audit trail, receipt template). All closed for V1:
- §8.2 per-item discount validation → strict (backend resolves and
  rejects on $0.01 mismatch).
- §8.3 stacking order → per-item first, then order-level on the
  post-item-discount subtotal (Square/Toast/Clover convention);
  reject with 400 if resolved total < 0.
- §8.4 cortesía + per-item discount on same line → REJECT with 400
  `CORTESIA_AND_DISCOUNT_CONFLICT`; TPV UI must enforce too.
- §8.5 custom items + tax → tax disabled in new Cobrar V1; TPV hides
  "Agregar impuesto" and backend rejects non-zero `taxAmount`.
- §8.7 audit trail → write `OrderAction{actionType:'COMP',
  performedById: staffId}` row per cortesía item with full metadata
  payload, inside the same Prisma transaction.
- §8.8 receipt templates → no change needed for V1 (templates read
  `productName` from the item, not from a `productId` join), but
  backend QA must audit `OrderItem.productId` joins in dashboard
  service layer before migration ships.

Remaining items in §8 (8.1 inventory rule, 8.6 pay-later, 8.9
permission name, 8.10 service architecture) were already closed in
earlier rounds — restated inline for audit trail.

**R7.3 (P2) — Schema B traces removed from authoritative content**:
spec pseudocode (around L298) said "If Schema A chosen / If Schema B
chosen" as if both were live branches — rewritten to commit to
Schema A with a one-line fallback note. Spec acceptance criteria
that said "Schema option A OR Schema option B" rewritten to commit
to Schema A with the fallback note. Plan §9 line for
`prisma/schema.prisma` changed from "(conditional — only if Option
A...)" to "**required** — Schema A is SELECTED" with the fallback
note inline.

**R7.4 (P3) — Stale "mobile service already logs creation"**: spec
"Observability" section said the new endpoint piggybacks on the
mobile service's logger, which contradicts the A2 V1 lock. Rewritten
to point at the TPV structured logger middleware applied to all
`/tpv/*` routes, with explicit "do not import or call the mobile
logger".

**R7.5 (P3) — Stale "BLOCKING DECISION" header tags**: the spec
sections "Subtotal semantics" and "OrderItem.total semantics" still
carried `(BLOCKING DECISION)` in their headers even though both were
closed (subtotal = GROSS, OrderItem.total = GROSS). Plan §11 R7.x
references them as `(CLOSED: GROSS)`. Headers updated. Leading "This
must be settled in writing before backend codes the math." sentence
in "Subtotal semantics" replaced by "Decision: subtotal is GROSS
(pre-discount)."

### Round 8 — code-reality cleanup (tax + inventory + OrderAction)

Final check against current backend/TPV code caught three implementation
invalid references. Fixed in both docs:

**R8.1 (P0) — Tax math not supported by the selected V1 path**:
TPV cart state can compute `subtotal - discount + tax`, but current
`payment.tpv.service.ts` fully-paid math uses `subtotal - discount + tip`
and would not account for `Order.taxAmount`. V1 is now explicitly
tax-disabled in the new Cobrar flow: TPV hides "Agregar impuesto",
sends `taxAmount=0`, and backend rejects non-zero `taxAmount`. Legacy
flows keep their current behavior. Full tax support is a follow-up that
must update payment math, receipts, dashboard display, and tests.

**R8.2 (P1) — Invented `Product.hasInventory` field**: current schema
uses `Product.trackInventory` plus `inventoryMethod`, with existing
resolution through `getProductInventoryMethod(productId)`. All V1
inventory wording now gates deduction on
`productId != null && getProductInventoryMethod(productId) != null`;
tracked inventory affects deduction only, never free-cart completion.

**R8.3 (P1) — Invalid OrderAction shape for cortesía audit**: current
schema uses `OrderAction.actionType` and `performedById`, not
`type`/`staffId`, and the enum has `COMP` but no `COMP_ITEM`. The
cortesía audit trail now uses `actionType='COMP'`,
`performedById=input.staffId`, and item-level detail in `metadata`.

After round 8, the docs no longer contain any open product or
architecture decision for V1. Backend team can begin implementation
straight from this plan + spec. Migration risk for Schema A is the
single remaining negotiation surface — see §11 round 6 R6.6.

---

## 12. Pre-handoff sanity check prompt (final)

> All product + architecture decisions are CLOSED. This block is the
> minimal sanity check the backend lead can run themselves (or feed to
> an LLM) before kickoff. There is no round 9 planned — if this check
> surfaces real issues, they are bugs in the docs that should be fixed
> in place, not "decisions to make".

```
Final pre-handoff sanity check on:
- docs/CHECKOUT_TPV_ENDPOINT_SPEC.md
- docs/CHECKOUT_BACKEND_INTEGRATION_PLAN.md

The plan §11 audit trail (rounds 1-8) documents every change made and
why. This is verification only — do not relitigate decisions.

1. V1 decisions that MUST stay closed (flag any regression):
   - Service: A2 only (dedicated TPV service). Zero /mobile changes.
   - Pay-later: blocked in new Cobrar v1.
   - Schema: A (Prisma migration adding isCortesia/cortesiaReason/
     appliedDiscountId to OrderItem; appliedToItemIds to OrderDiscount).
     Schema B is a documented fallback only.
   - Free-cart: total=0 ALWAYS completes (COMPLETED + PAID + Payment $0
     with FREE_CART marker), regardless of cart shape. Inventory
     deduction is per-item conditional, NOT a gate on completion.
     Deduction uses `getProductInventoryMethod(productId) != null`, not
     a non-existent `hasInventory` field.
   - Stacking: per-item discounts first, then order-level.
   - Cortesía + per-item discount on same line: REJECT 400.
   - Custom items + tax: tax disabled in new Cobrar V1 (`taxAmount=0`);
     TPV hides "Agregar impuesto"; backend rejects non-zero `taxAmount`;
     legacy flows unchanged.
   - Cortesía audit: use `OrderAction.actionType='COMP'` and
     `performedById=input.staffId`; no `COMP_ITEM` enum exists.
   - PaymentMethod enum: CASH/CREDIT_CARD/DEBIT_CARD/DIGITAL_WALLET/
     BANK_TRANSFER/CRYPTOCURRENCY/OTHER. No COMP value exists.
   - Permission: orders:create (NOT tpv-orders:create).
   - Subtotal semantics: GROSS at order level AND line level.

2. Consistency audit (no decision making, just contradiction hunt):
   - Anywhere the docs say "Schema A chosen / Schema B chosen" as if
     a live branch → fix.
   - Anywhere "tracked products" gates free-cart completion → fix.
   - Anywhere docs mention `Product.hasInventory` / `product.hasInventory`
     → fix to `trackInventory` + `getProductInventoryMethod(productId)`.
   - Anywhere non-zero tax is allowed in new Cobrar V1 → fix.
   - Anywhere docs mention `OrderAction.type`, `type:'COMP_ITEM'`, or
     `staffId` directly on OrderAction → fix to `actionType:'COMP'`,
     `performedById`, and item metadata.
   - Anywhere /mobile is touched in V1 (read, written, called, or
     extended) → fix.
   - Anywhere "BLOCKING DECISION" or "must be settled" appears for
     subtotal/OrderItem.total → fix (both are CLOSED: GROSS).
   - Function/file names in code blocks: verify they exist in
     avoqado-server or are explicitly marked NEW / TO CREATE.

3. Output format (max ~300 words):
   - List of any contradictions found (or "none")
   - List of any fabrications found (or "none")
   - Verdict: "Ready for backend implementation" OR "Needs fixes for
     [specific list]"

Do NOT output: opinions on whether decisions are "best", expanded
problem statements, or alternative architectures. The decisions are
final — only catch contradictions and fabrications.
```

---

## 13. Definition of Done

- [ ] Backend endpoint live in dev, all 10 scenarios from §6 pass
- [ ] Backend endpoint live in prod, additive (no behavior change for
      legacy clients)
- [ ] TPV PR merged with the wire-up + tests, full suite green
      (≥427 tests)
- [ ] Manual QA on PAX A910S sandbox covers all 10 scenarios
- [ ] Crashlytics shows zero new fatal/non-fatal incidents attributable to
      the new endpoint or `CheckoutViewModel`
- [ ] At least one real merchant has used the new flow with a mixed cart
      and confirmed the receipt + dashboard look correct
- [ ] All open questions in §8 are answered and reflected in the spec
- [ ] Spec updated to "Status: IMPLEMENTED" with backend + TPV version
      tags
