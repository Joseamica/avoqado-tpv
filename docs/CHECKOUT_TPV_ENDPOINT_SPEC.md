# Spec: New TPV endpoint to create orders with mixed items (catalog + custom amounts)

> **Audience:** avoqado-server backend team + avoqado-tpv Android team
> **Status:** Spec — needs backend implementation before TPV can wire it up
> **Related:** `docs/CHECKOUT_REFACTOR_PLAN.md` (the unified Cart/Checkout refactor)
> `docs/CHECKOUT_BACKEND_INTEGRATION_PLAN.md` (implementation plan)

## Context — what avoqado-tpv actually is

`avoqado-tpv` is a **standalone POS** that runs on PAX payment terminals
(A910S, A80) and Nexgo terminals. It does both order management and card
processing in a single device: the operator builds the cart, taps "Cobrar",
the same hardware swipes/inserts/taps the card and prints the receipt.

This is **different** from the older bipartite architecture (avoqado-android
iPad as the cash register, BLE'd payment requests to a TPV terminal). That
BLE bridge is no longer in use — TPV operates fully standalone now. So any
data the receipt or dashboard needs (cortesía, item discounts, custom
amounts) must travel inside the order TPV creates with the backend; there is
no upstream client to pre-populate it.

## Problem

The new "Cobrar" (unified Cart) flow on TPV lets the operator mix catalog
products + manual amounts ("Otro importe", "Servicio", etc.) in a single
transaction. When that mixed cart goes to PaymentScreen the receipt is wrong:

- Order in backend is created from products only → subtotal $756.50
- PaymentScreen charges the cart total → $869.98
- Backend interprets the diff ($113.48) as **Propina** on the receipt

The user expected `Otro importe $113.48` to appear as a line item, not as a
tip.

The same problem extends to **cortesía per item** and **per-item discount**:
the cart math is correct (customer is charged the right amount), but the
order in the backend records products at catalog price, leaving a gap that
shows up as phantom propina or unexplained difference in dashboard
"Pagos"/"Órdenes" detail.

Since TPV is standalone, **there is no upstream client to fix this** — the
endpoint TPV calls must accept all the structured data the cart already has.

## Root cause

TPV's existing `/tpv/...` order endpoints require `productId` for every item:

```
POST /api/v1/tpv/venues/:venueId/orders         → creates an empty order shell
PATCH /api/v1/tpv/venues/:venueId/orders/:id/items
  body: { items: [{ productId, quantity, ... }], ... }   ← productId is required
```

So `CartItemType.CustomAmount` entries (no `productId`, just a `name` +
`unitPrice`) have to be dropped at the boundary, and the diff between cart
total and order total becomes phantom tip.

## Backend already has the core capability — needs extension

The backend service `services/mobile/order.mobile.service.ts` already
handles items where `productId` is optional (custom line items with `name` +
`unitPrice`). That logic was originally written for a different client class
(see "Backwards compatibility" section below for details).

What it currently lacks for TPV's standalone use case:

1. **Per-item `isCortesia` + `cortesiaReason`** — cart marks an item as
   courtesy, the backend item should be created with that flag and audit
   reason, the receipt should label it "Cortesía", inventory should still
   deduct (the venue gave away the product).
2. **Per-item `itemDiscountId`** — link the line to a venue-configured
   `Discount` entity so the receipt shows the discount name + amount, not
   just a phantom price drop.
3. **Order-level `orderDiscountId`** — preserve the discount identity (e.g.
   "Happy Hour 10%") instead of just an amount in cents, so receipts and
   dashboard show the human-readable name.
4. **Subtotal mismatch detection** — verify the client's claimed `subtotal`
   (GROSS — sum of `items[].lineGross` at catalog/operator-typed prices,
   BEFORE any cortesía or discount reduction) matches backend's recomputed
   gross subtotal from the same items; AND verify `total = subtotal -
   discountAmount`. Reject (400) if either invariant breaks. This catches
   money math errors before the customer is charged. See "Subtotal
   semantics — gross vs net" for the full math definition.

The new TPV endpoint must do all of the above, and must do it in a single
DB transaction so the order is never half-built (no orphan items, no
partial discounts) if any step fails.

## Proposal

### Why TPV can't use `/mobile/...` directly

The `/mobile/...` namespace is reserved for other client classes (different
apps with their own auth context, permissions, and conventions). TPV traffic
must stay under `/tpv/...` for:

- Auth middleware: TPV uses terminal-scoped tokens with venue isolation rules
  that differ from the generic mobile auth.
- Permission checks: TPV permissions live under `tpv-*:*` (e.g.
  `orders:create`); reusing mobile routes bypasses them.
- Observability: dashboards, log filters, and rate limits assume
  `/tpv/...` = TPV traffic; mixing breaks attribution.
- Future TPV-specific behavior (validation rules, side effects, terminal
  health correlation) needs a route that's clearly TPV-owned.

### New endpoint

Add a TPV controller backed by a **dedicated TPV service** (Option A2 —
see "Suggested backend implementation" below). The service must NOT
import or wrap the existing `services/mobile/order.mobile.service.ts`
in V1; it duplicates the Prisma transaction logic in TPV-owned files
to keep the mobile contract untouched.

```
POST /api/v1/tpv/venues/:venueId/orders/with-items
```

Suggested path because `POST /tpv/venues/:venueId/orders` already exists with
different semantics (creates an empty shell). The `/with-items` suffix makes
the create-with-everything semantics obvious. Open to alternatives —
`/tpv/venues/:venueId/orders/bundled` or `/tpv/venues/:venueId/orders/full`
both work too.

### Request body

```jsonc
{
  "items": [
    // Catalog product item — full price
    {
      "productId": "cmi3k125i00079kp0",
      "quantity": 2,
      "modifierIds": ["mod_1", "mod_2"],   // optional
      "notes": "sin cebolla"               // optional
    },
    // Catalog product item — courtesy (free)
    {
      "productId": "cmi3k125i0007abcd",
      "quantity": 1,
      "isCortesia": true,                   // ⭐ See "OrderItem.total semantics" below. Mutually exclusive with itemDiscountId — backend rejects 400.
      "cortesiaReason": "Cliente VIP"       // required when isCortesia=true (audit trail)
    },
    // Catalog product item — with per-item discount
    {
      "productId": "cmi3k125i0007efgh",
      "quantity": 1,
      "itemDiscountId": "disc_xyz"          // ⭐ id of a venue-configured Discount entity. Mutually exclusive with isCortesia.
    },
    // Custom line item (no productId)
    {
      "name": "Otro importe",
      "quantity": 1,
      "unitPrice": 113.48,                  // pesos (decimal)
      "notes": null
    }
  ],
  "staffId": "staff_abc",
  "orderType": "TAKEOUT",                  // DINE_IN | TAKEOUT | DELIVERY | PICKUP
  "source": "TPV",                          // string, free-form. Default "TPV".
  "tableId": null,                          // optional
  "customerId": null,                       // optional, used for pay-later
  "discount": 0,                            // pesos — order-level discount amount (legacy field)
  "orderDiscountId": null,                  // ⭐ optional id of a venue Discount applied at order level (preserves discount identity for receipt)
  "taxAmount": 0,                            // pesos — V1 must be 0. New Cobrar hides order-level tax until payment service tax handling is fixed.
  "tip": 0,                                 // pesos (TPV adds tip in PaymentScreen, this is usually 0)
  "subtotal": 950.00,                       // pesos — GROSS sum of items at catalog prices, BEFORE cortesía / discount. See "Subtotal semantics" section
  "total": 869.98,                          // pesos — NET = subtotal - discountAmount. What the operator charges, excl. tip
  "note": "Cliente prefiere bolsa"         // optional, order-level
}
```

> **Wire format = pesos as decimals.** All monetary fields in this body
> are pesos (e.g. `25.45`, not `2545` cents), matching the rest of the
> `/tpv/*` API. Backend validates `subtotal`/`discount`/`total` with
> ±$0.01 tolerance to absorb client-side rounding. Cart math in TPV is
> internally integer cents for precision; conversion to pesos happens
> at the wire boundary inside `OrderRepositoryImpl.createOrderWithItems`.

⚠️ **`subtotal` is GROSS, `total` is NET** — see the "Subtotal semantics —
gross vs net" section further down for the full definition. Sending `subtotal`
already discounted will cause the existing payment formula
(`subtotal - discountAmount + tip` in `payment.tpv.service.ts:368`) to
double-deduct the cortesía. Backend rejects with 400 if `subtotal != Σ items.lineGross`.

Validation rules:

1. `items` must be a non-empty array.
2. Each item: `quantity >= 1` AND (`productId` non-empty string OR `name`+`unitPrice` set).
3. `staffId` required.
4. `subtotal`, `taxAmount`, and `total` required (pesos as decimals). `taxAmount` MUST be 0 in V1. See rule 8 for math semantics.
5. ⭐ When `isCortesia: true`, `cortesiaReason` is required (string, min 3 chars). Audit trail.
6. ⭐ When `itemDiscountId` is set, the discount must exist in the venue's catalog and be active. Backend resolves the type/value/amount and rejects with 400 if the line subtotal doesn't match within ±$0.01.
7. ⭐ When `orderDiscountId` is set, same rule — must be a valid active discount; backend computes the amount and validates it matches `discount` in the request body within ±$0.01 (pesos).
8. ⭐ **Subtotal mismatch detection (gross/net)**: `subtotal` MUST equal `Σ items[].lineGross` where `lineGross = unitPrice × quantity` at catalog prices (or operator-typed `unitPrice` for custom items), BEFORE applying cortesía/discount. For V1, `taxAmount` MUST equal 0 and `total` MUST equal `subtotal - discountAmount`. Backend recomputes from items as a check; mismatch → 400 with the diff (in pesos) in the error body. This catches cart/server math drift before the customer is charged. See "Subtotal semantics — gross vs net" section for the full math definition.
9. ⭐ **Cortesía/discount mutex**: a single line cannot have both `isCortesia=true` and `itemDiscountId` set. Backend rejects with 400 and TPV's `CartItem` model enforces the same invariant at construction time.

### Why the per-item flags matter (vs. multi-call alternatives)

The legacy TPV path achieves the same effect via 3 separate endpoints:
1. `POST /tpv/venues/:venueId/orders` (creates shell)
2. `PATCH /tpv/venues/:venueId/orders/:id/items` (adds items)
3. `POST /tpv/venues/:venueId/orders/:id/comp` (marks cortesía) — for each cortesía item
4. `POST /tpv/venues/:venueId/orders/:id/discount` (per-item discount) — for each item discount

This is brittle: any of those 4 calls can fail mid-flow leaving the order in a half-built state, the customer waiting at the terminal, and the operator having to re-do everything. The single-call `with-items` endpoint moves the whole transaction server-side where it can be wrapped in one DB transaction — atomic, fast, no half-built orders.

### Response

⚠️ **Critical**: Must return the **full** TPV `OrderDto` shape (the same one
the existing `POST /api/v1/tpv/venues/:venueId/orders` returns), NOT the
reduced `CreatedOrderResponse` shape from
`services/mobile/order.mobile.service.ts`. TPV's Retrofit deserializer
(`OrderDto` in `features/ordering/data/dto/TableDto.kt:185`) requires fields
like `venueId`, `tableId`, `servedById`, `updatedAt`, `version`,
`remainingBalance`, `paymentStatus`, `kitchenStatus`, etc. that the mobile
response strips.

The TPV-owned service (V1 = Option A2) must use a TPV-owned mapper —
either an extracted `mapToTpvOrderDto(order)` from the
`flattenOrderModifiers` helper inside `services/tpv/order.tpv.service.ts`,
or an inlined Prisma `include` shape that matches what TPV's `OrderDto`
deserializer expects. Do not call into mobile mappers or services. If the
response shape diverges from the legacy `POST /tpv/venues/:venueId/orders`
output, TPV will fail to parse or silently drop fields, breaking
downstream payment / cache flows.

Wrap in the standard envelope:

```jsonc
{
  "success": true,
  "data": {
    "id": "ord_xyz",
    "orderNumber": "ORD-1234567890",
    "venueId": "ven_abc",
    "tableId": null,
    "status": "OPEN",
    "paymentStatus": "PENDING",
    "kitchenStatus": "PENDING",
    "type": "TAKEOUT",
    "items": [
      {
        "id": "item_1",
        "productId": "cmi3k125i00079kp0",
        "productName": "Hamburguesa",
        "quantity": 2,
        "unitPrice": 89.00,
        "total": 178.00
      },
      {
        "id": "item_2",
        "productId": null,                 // ← custom line item
        "productName": "Otro importe",
        "quantity": 1,
        "unitPrice": 113.48,
        "total": 113.48
      }
    ],
    "subtotal": 869.98,
    "discountAmount": 0,
    "taxAmount": 0,
    "total": 869.98,
    "version": 1,
    "createdAt": "2026-05-11T20:30:00Z",
    "updatedAt": "2026-05-11T20:30:00Z"
    // ... other fields TPV's OrderDto already maps
  }
}
```

### Suggested backend implementation

⚠️ **V1 SCOPE LOCKED (user decision)**: Do NOT modify `/mobile` routes,
controllers, service behavior, request validation, or response shape in
any way for V1. Shared-service extraction is **explicitly deferred to
post-V1**. The TPV endpoint must be implemented in a TPV-owned controller
+ TPV-owned service, even if it duplicates logic.

**SELECTED for V1: Option A2** — Dedicated TPV service with duplicated
Prisma transaction logic. See "Selected implementation" below.

**Deferred (post-V1): Option A1** — Extract the core to a shared service.
The detail of A1 stays documented below as future work, but for V1 it is
**not on the table** because it would require touching `/mobile` callers
(test coverage, regression risk) which is explicitly out of scope.

#### Selected implementation (A2 — dedicated TPV service)

```ts
// avoqado-server/src/services/tpv/order.tpv.service.ts (modify — add new function)
// Implements the full Prisma transaction: create Order, create OrderItems
// (catalog + custom), apply per-item cortesía/discounts, validate gross
// subtotal vs items, etc. Same business rules as the mobile service has,
// but lives entirely under services/tpv — zero risk to mobile clients.

export async function createOrderWithItems(
  venueId: string,
  input: CreateOrderWithItemsInput,
): Promise<FullOrderRecord> {
  // Validate request body shape
  // Recompute gross subtotal from items, reject 400 if mismatch
  // V1 requires taxAmount=0, then recompute total = subtotal - discountAmount; reject 400 if mismatch
  // In a single Prisma transaction:
  //   - Create Order (subtotal=GROSS, discountAmount, total)
  //   - Create OrderItems (each with productId OR name+unitPrice;
  //     OrderItem.total = GROSS, discountAmount per line)
  //   - Schema A (SELECTED): write isCortesia, cortesiaReason,
  //     appliedDiscountId per item. (Schema B fallback only if backend
  //     team flags migration risk with PO buy-in.)
  //   - Link customerId if provided (V1 ignores pay-later — block in TPV UI)
  //   - If total === 0 (Free-flow A — ALWAYS, regardless of whether the
  //     cart has tracked products): mark COMPLETED + PAID, create
  //     Payment{amount=0, paymentMethod=OTHER, processorData={type:
  //     'FREE_CART', reason}}. Inventory deduction is per-item: only
  //     items with productId whose inventory method resolves (trackInventory=true
  //     and inventoryMethod set) get deducted; custom items and untracked products are skipped.
  //     A cart of 100% custom items at total=$0 still closes cleanly
  //     with Payment $0 and PAID status — no inventory movement.
  // Return full order with all relations loaded for the TPV mapper.
}
```

```ts
// avoqado-server/src/controllers/tpv/order.tpv.controller.ts (add)
import * as orderTpvService from '@services/tpv/order.tpv.service'
// NOTE: there is no exported `mapToTpvOrderDto` today. The closest is
// `flattenOrderModifiers` inside services/tpv/order.tpv.service.ts. Backend
// team needs to either:
// (a) extract the existing TPV-shape serialization out of order.tpv.service
//     into a reusable mapper module (e.g. mappers/order.tpv.mapper.ts), or
// (b) inline the same Prisma `include` shape this controller uses for the
//     legacy `createOrder` route so the response matches what TPV's
//     OrderDto deserializer expects.
// Pseudocode below uses (a) for clarity.
import { mapToTpvOrderDto } from '@services/tpv/order.tpv.mapper'  // ← TO CREATE

export const createOrderWithItems = async (req, res, next) => {
  try {
    const { venueId } = req.params
    const order = await orderTpvService.createOrderWithItems(venueId, {
      ...req.body,
      source: req.body.source || 'TPV',
      staffId: req.body.staffId || req.authContext?.userId,
    })
    res.status(201).json({ success: true, data: mapToTpvOrderDto(order) })
  } catch (err) {
    next(err)
  }
}
```

#### Future work: Option A1 (deferred to post-V1)

Extract the create-order-with-items core to
`services/shared/order.create.service.ts` so both `/mobile/...` and
`/tpv/.../with-items` callers can import it. This is the right
long-term architecture but requires:
- `/mobile` regression test coverage (verify zero contract change for
  iOS/Android callers)
- Migration of `/mobile` controller to call the shared service with
  default flags
- Coordination with mobile teams

None of that is V1 scope. After V1 ships and the TPV `/with-items` flow
is validated in production, a follow-up ticket should consolidate the
duplicated logic into a shared service (sketch: a new
`services/shared/order.create.service.ts` exporting
`createOrderWithItems(venueId, input)` that takes explicit feature
flags like `validateSubtotal: boolean` and `isCortesiaPerItem: boolean`,
with both `/mobile/...` and `/tpv/.../with-items` controllers importing
it and mapping to their own response shapes). Until that ticket is
sized and shipped, accept the duplication between the SELECTED V1
implementation above and the existing mobile service as a temporary
cost of safe shipping.

**Option A2** — Build a dedicated `services/tpv/order.tpv.service.ts`
function that duplicates the Prisma transaction logic. Zero risk to mobile
but ~200 lines of duplicated money math. Easier to ship, harder to
maintain. Pick this if release pressure is high and the team can't get
mobile regression coverage in time for A1.

```ts
// avoqado-server/src/routes/tpv.routes.ts (add)
router.post(
  '/venues/:venueId/orders/with-items',
  authenticateTokenMiddleware,
  checkPermission('orders:create'),
  orderController.createOrderWithItems,
)
```

### Permissions

Use the existing `orders:create` permission. The legacy
`POST /tpv/venues/:venueId/orders` route at `tpv.routes.ts:672` already
uses it. No new permission needed.

⚠️ Do NOT use `tpv-orders:create` — that permission does not exist in
`lib/permissions.ts`. The `tpv-orders:*` namespace only contains `comp`,
`void`, and `discount` (lines 347-349).

### Observability

Same logger / Crashlytics tags as the existing TPV order routes
(`order.tpv.service.ts` legacy `createOrder` path). The TPV structured
logger middleware applied to all `/tpv/*` routes picks the new route
up automatically. Do not import or call the mobile logger.

## Subtotal semantics — gross vs net (CLOSED: GROSS)

**Decision: subtotal is GROSS (pre-discount).** The existing payment
service computes the customer charge as:

```
amount_to_charge = order.subtotal - order.discountAmount + tip
                                                            ↑
                          (payment.tpv.service.ts:368)
```

If the spec's request body sends `subtotal = cart.totalAfterCortesia`
(i.e. net of cortesía / discounts) AND backend separately writes
`order.discountAmount = sumOfCortesias`, the formula double-deducts the
cortesía. Customer pays the right cart-side amount but `payment_records`
disagrees with `order_records` and conciliation breaks.

### Required definition (locks the wire format)

| Field | Definition | Example: 1× $200 product, 1× $50 cortesia, 10% order discount |
|---|---|---|
| `Order.subtotal` (DB) | **GROSS** — sum of all item line `unitPrice × quantity` at catalog prices, BEFORE any cortesía or discount | `$250` |
| `Order.discountAmount` (DB) | Sum of all cortesía line totals + sum of all per-item discount amounts + order-level discount amount | `$50 (cortesia) + $20 (10% of remaining $200) = $70` |
| `Order.taxAmount` (DB) | V1: always `0`. Order-level tax in new Cobrar is deferred until `payment.tpv.service.ts` includes tax in its fully-paid math | `$0` |
| `Order.total` (DB) | V1: `subtotal - discountAmount` | `$180` |
| Request body `subtotal` | GROSS — same as `Order.subtotal`. Backend validates `subtotal == Σ items.lineGross` |
| Request body `taxAmount` | V1: must be `0`. Backend rejects non-zero tax in this endpoint |
| Request body `total` | NET — same as `Order.total`. Backend validates `total == subtotal - discountAmount` while tax is disabled |
| Payment service charge | `total + tip` (the existing formula stays correct) |

Why GROSS for subtotal: keeps the payment formula
(`subtotal - discountAmount + tip`) consistent with how it's been computed
for years. Changing that formula is a much higher-risk migration than
defining new fields cleanly.

### Validation rules added to the spec body

- `subtotal` MUST equal the sum of `items[].lineGross` where `lineGross =
  unitPrice × quantity` at catalog prices (or operator-typed `unitPrice`
  for custom items). Backend rejects 400 if mismatch.
- `taxAmount` MUST equal `0` in V1. TPV hides the "Agregar impuesto" control
  in the new Cobrar flow; backend still rejects non-zero tax defensively.
- `total` MUST equal `subtotal - discountAmount`. Backend rejects 400 if
  mismatch.
- TPV computes both client-side from `CartState`; mismatch with backend
  recomputation indicates client/server math drift — caught before the
  customer sees the wrong number on PaymentScreen.

## `OrderItem.total` semantics — gross or net? (CLOSED: GROSS)

This question is parallel to the order-level gross/net definition above
but applies to each line. The legacy backend reads `OrderItem.total` in
several places (`compItems`, `applyDiscount`, dashboard "Sales by Item")
and treats it as the LINE-LEVEL TOTAL — but the docs are not consistent
about whether that means GROSS (catalog × qty) or NET (after the line's
own discount/cortesía).

### What today's code does

- `OrderItem.total` is set at item-create time as `unitPrice × quantity`.
- When `applyDiscount` runs (POST `/orders/:id/discount` with itemIds),
  it writes to `OrderItem.discountAmount` but does NOT mutate
  `OrderItem.total`. The line's net is therefore implicit:
  `lineNet = total - discountAmount`.
- When `compItems` runs, similar — `discountAmount` carries the comp,
  `total` stays as the gross.

### Decision for the new endpoint

To stay consistent with the legacy reads (so dashboard reports and
existing services don't need rewrites), the new endpoint must also
write `OrderItem.total` as **GROSS**:

| Field | Value for a $200 hamburguesa with cortesía |
|---|---|
| `OrderItem.unitPrice` | 200 |
| `OrderItem.quantity` | 1 |
| `OrderItem.total` | **200 (GROSS)** — `unitPrice × quantity`, NOT 0 |
| `OrderItem.discountAmount` | 200 (the cortesía amount) |
| `OrderItem.isCortesia` (Schema A) | true |
| Implicit `lineNet` | 0 |

| Field | Value for a $200 burger with 20% per-item discount |
|---|---|
| `OrderItem.unitPrice` | 200 |
| `OrderItem.quantity` | 1 |
| `OrderItem.total` | **200 (GROSS)** |
| `OrderItem.discountAmount` | 40 |
| Implicit `lineNet` | 160 |

Receipts and dashboards that need to show the NET line value compute it
inline: `total - discountAmount`. Same pattern the legacy comp/discount
routes already establish.

⚠️ Setting `OrderItem.total = 0` for cortesía would break:
- Dashboard "Sales by Item" (sums `OrderItem.total` for gross sales)
- `compItems` math (assumes `total` is the un-comp'd amount)
- Any future report that sums line-level revenue

Backend team must confirm this assumption matches actual legacy reads
(or correct the spec if `OrderItem.total` is read as NET anywhere).

## Custom line items — display fields and schema

Backend already persists custom line items with `productName` set from the
request `name`, but two downstream pieces don't carry that field through:

1. **TPV `OrderItemDetailDto`** (`features/ordering/data/dto/TableDto.kt:57`)
   doesn't currently declare `productName`, so when an order is fetched
   back to TPV (e.g. opening it from "Pedidos") the field is dropped.
2. **TPV `OrderMappers.kt:63`** falls back to
   `product?.name ?: "Producto eliminado"` when no Product entity is
   linked. For custom line items, this renders as **"Producto eliminado"**
   — wrong and confusing.

### Required end-to-end change

| Layer | Change |
|---|---|
| Backend response | Always include `productName` (and ideally `productSku`, `categoryName`) in each order item, including custom items where `productId` is null. |
| TPV `OrderItemDetailDto` | Add `productName: String?`, `productSku: String?`, `categoryName: String?` fields with `@SerializedName`. |
| TPV `OrderMappers.kt` | Mapping precedence: `item.product?.name ?: item.productName ?: "(producto sin nombre)"`. The "Producto eliminado" string should ONLY appear when both are missing — that's a real data integrity issue, distinguishable from intentional custom items. |

This change benefits the legacy flows too — even today, an item from a
deleted product can show as "Producto eliminado" without context. With
`productName` preserved at OrderItem creation time, the historical name
survives product deletion.

## Cortesía and discounts — schema implications

The current `Order` / `OrderItem` schema is partially-supported:

- `OrderItem` has `discountAmount: Decimal` but **no** `isCortesia`, no
  `cortesiaReason`, no link to a `Discount` entity per item.
  See `prisma/schema.prisma:2181`.
- `OrderDiscount` already has `isComp: Boolean` + `compReason: String?` —
  but does **NOT** persist `appliedToItemIds`. The current discount engine
  computes which items a discount applies to at apply-time and updates
  each `OrderItem.discountAmount`, but the per-item linkage isn't stored
  in `OrderDiscount` for later auditing.
  See `prisma/schema.prisma:4457`.

Reporting consequence: "Sales by Item"
(`sales-by-item.dashboard.service.ts:154`) and "Sales Summary"
(`sales-summary.dashboard.service.ts:235`) both aggregate
`OrderItem.discountAmount`. Whatever solution we pick MUST keep that
column populated correctly — otherwise the daily reports under-count.

### Two paths

**A) Schema migration (clean, recommended)**
   - Add to `OrderItem`: `isCortesia: Boolean`, `cortesiaReason: String?`,
     `appliedDiscountId: String?` (nullable FK to `Discount`)
   - Add to `OrderDiscount`: `appliedToItemIds: String[]` OR new
     `OrderDiscountItem` join table (cleaner)
   - Update reports to read the new fields where richer aggregation
     helps (e.g. "comps this week by reason"); existing aggregations on
     `discountAmount` keep working
   - Pros: full fidelity, future-proof, audit trail is queryable directly
   - Cons: live-table migration. `OrderItem` and `OrderDiscount` are
     hot tables. Migration must be backfill-safe (default values),
     deploy in maintenance window or with online migration tooling

**B) Map to existing fields + use OrderAction.metadata for richness**
   - For cortesía:
     - `OrderItem.discountAmount = lineGross` (so line net collapses
       to 0, reports keep adding up)
     - Use existing `OrderDiscount.isComp = true` + `compReason` at the
       order level for the cortesía
     - Per-item linkage: write `OrderAction` records with
       `metadata = { itemId, isCortesia: true, reason }` for each
       cortesía item (this is where receipts/dashboards must read from
       to get the per-item identity)
   - For per-item discount:
     - `OrderItem.discountAmount = discountAmountForLine`
     - Write `OrderDiscount` with the discount metadata; `OrderAction`
       carries the `appliedToItemIds` in its metadata blob
   - Pros: no schema migration, reports keep working
   - Cons:
     - Per-item linkage discoverable only via `OrderAction.metadata`
       reads — receipt template + "Pagos" detail in dashboard need to
       JOIN/query OrderAction to render the per-line attribution
     - `OrderAction.metadata` is `Json` — no schema-level enforcement,
       easy to drift
     - Dashboard report devs have to learn this convention

### Decision (closed for V1)

**SELECTED: Schema A — Prisma migration adding the per-item flags.**

- Add to `OrderItem`: `isCortesia: Boolean (default false)`,
  `cortesiaReason: String?`, `appliedDiscountId: String? FK to Discount`
- Add to `OrderDiscount`: `appliedToItemIds: String[]` (or new
  `OrderDiscountItem` join table — backend team picks the cleaner
  Prisma representation)
- Update affected reports (`sales-summary.dashboard.service.ts:235`,
  `sales-by-item.dashboard.service.ts:154`) to read the new fields
  where richer aggregation helps; existing `discountAmount`
  aggregations keep working unchanged.

**Rationale**: V1 product owner stance is "cortesía y todo debe
funcionar bien — esto es dinero real". Schema A gives:
- Clean audit trail directly queryable (no JOIN-on-Json gymnastics)
- Reports that distinguish "10 cortesías this week by reason" from
  "10 generic discounts"
- Schema-level enforcement of the cortesía contract (no drift via
  `OrderAction.metadata` Json blob)
- Future-proof — receipt templates and dashboards read structured
  fields, not JSON metadata

**Trade-off accepted**: live-table migration on `OrderItem` /
`OrderDiscount`. Backend team must execute it with backfill-safe
defaults (`isCortesia: false`, `cortesiaReason: null`,
`appliedDiscountId: null`) and online migration tooling if needed.
This is the only scope expansion outside the additive endpoint, and
it's intentional — the alternative (B) leaves dashboard + receipt
teams permanently dependent on a Json blob convention.

**Fallback path**: if backend team determines the migration risk is
unacceptable for V1 (e.g. table size + downtime window), fall back to
Schema B (map cortesía to existing `discountAmount` + use
`OrderAction.metadata` for per-item linkage). But this MUST be flagged
to product owner before implementation, because dashboard + receipt
teams need to commit to the Json read convention.

## Free-cart (100% cortesía) flow — explicit completion required

The existing inventory deduction trigger fires only when an order becomes
`isFullyPaid` inside `payment.tpv.service.ts:506-588`. For a cart that is
100% cortesía (`total = 0`), there's no payment record created and the
trigger never fires — so inventory is silently NOT deducted even though
the venue gave away real product.

### Decision (closed for V1)

**SELECTED: Free-flow A — atomic completion in the new endpoint.**

**When `total = 0` — ALWAYS, regardless of cart contents** (catalog
products tracked or untracked, custom items, mix of any of these), the
new endpoint, in the same DB transaction as the create:
1. Sets `Order.status = COMPLETED` and `paymentStatus = PAID`.
2. Creates a `Payment` record with `amount = 0`, `paymentMethod = OTHER`
   (the existing `PaymentMethod` enum has only `CASH`, `CREDIT_CARD`,
   `DEBIT_CARD`, `DIGITAL_WALLET`, `BANK_TRANSFER`, `CRYPTOCURRENCY`,
   `OTHER` — no `COMP` value), and writes a marker into `Payment`
   metadata or `Payment.processorData` (e.g. `{ type: "FREE_CART",
   reason: "all_items_cortesia" }`) so reports can distinguish a comp'd
   $0 sale from a true zero-amount sale. Alternative if reports need
   clear typing: backend team adds `COMP` to the enum via migration —
   that's a deliberate scope expansion they should size separately.
3. Triggers `deductInventoryForProduct` **per item, conditionally**:
   only items that have a `productId` AND whose product has inventory
   tracking enabled (`Product.trackInventory = true` and a resolvable
   `inventoryMethod` via `getProductInventoryMethod(productId)`) get
   deducted. Custom items (`productId === null`), untracked products
   (`trackInventory = false` or no inventory method), and zero-quantity
   items are skipped silently. A cart of 100% custom items at `total = 0`
   still closes cleanly with Payment $0 and PAID status — no inventory
   movement, no error.

> **Why unconditional close**: gating completion on "at least one
> tracked product" would leave free-carts of only custom items (or only
> untracked products) stuck in PENDING with no payment record. That's
> the exact half-built-order state this endpoint exists to eliminate.
> Closing is a function of `total = 0`; inventory deduction is a
> separate per-item question.

**Rejected: Free-flow B** — separate
`POST /tpv/venues/:venueId/orders/:orderId/complete-free` endpoint.
Reintroduces the half-built-order problem the single-call endpoint is
designed to eliminate.

Free-flow A is consistent with the V1 product owner stance that
"cortesía should work properly" (real product was given away → stock
must move, audit trail must capture who/why).

## Pay-later settlement (gap to address before shipping)

Existing `settleOrder` (`payment.tpv.service.ts:561`) is what dashboard
calls when a pay-later order is settled later (operator marks it as paid
without going through the TPV again). It currently:
- Marks `paymentStatus = PAID`
- Creates a `Payment` record
- Does **NOT** complete the order status (`status` stays as it was)
- Does **NOT** trigger inventory deduction

If TPV's new Cobrar flow allows pay-later (operator builds cart, taps
"Pagar después", order is created with PENDING) — and the dashboard later
settles it — the inventory deduction never happens. Real product was
given away, real money was eventually collected, but stock numbers in
the dashboard stay wrong.

### Decision (closed for v1)

**SELECTED: Option 2 — Block pay-later in the new Cobrar flow for v1.**

The other options were:
1. ~~Fix `settleOrder` to deduct inventory~~ — right thing long-term but
   expands scope materially: existing pay-later orders created via the
   legacy flow have been silently building stock drift. Fixing
   `settleOrder` will start surfacing those discrepancies all at once,
   needs its own backfill / reconciliation plan. Park as separate
   ticket.
3. ~~Document the gap and proceed~~ — rejected. Operations explicitly
   does not want to ship a flow that knowingly under-deducts inventory.

Option 2 wins because it has the narrowest scope and zero ambiguity:
TPV's CheckoutScreen will hide the "Pagar después" button (or fail with
a clear error message). The legacy `OrderingWelcome` → `MenuScreen`
pay-later flow is unaffected — it stays available, with the same
inventory drift behavior it has today (which operations already knows
about and absorbs).

**Implementation impact:**
- TPV: remove the `onPayLater` callback from `CartDetailsSheet` when
  the source is the new Cobrar flow. Tests get a regression check that
  the kebab menu doesn't expose "Pagar después" in this surface.
- Backend: no work needed for v1.
- Follow-up ticket (separate, post-Cobrar release): "Fix `settleOrder`
  to deduct inventory + reconcile historical pay-later drift". Sized by
  backend separately.

## Inventory behavior

The product owner has been explicit: **custom line items must NOT be treated
as products for inventory purposes.** They're informational/billing entries
only — a custom amount is a charge, not a SKU.

The existing payment service already does the right thing in
`services/tpv/payment.tpv.service.ts:518-558`:

```ts
for (const item of updatedOrder.items) {
  if (!item.productId) {
    if (item.productSku) {
      // serialized SIM → mark as SOLD
    } else {
      logger.info('⏭️ Skipping inventory deduction for deleted product', { ... })
    }
    continue
  }
  // Only items WITH productId hit deductInventoryForProduct(...)
}
```

So for a mixed cart, the inventory side is correct out of the box:
- Catalog products → `deductInventoryForProduct` (recipe + modifiers honored)
- Serialized items → `markAsSold`
- Custom line items → silently skipped, **no inventory touched** ✅

### Small observability fix requested

The log line `"Skipping inventory deduction for deleted product"` is
misleading when the cause is a custom line item, not a deleted product.
Both have `productId = null` but the semantics are different. Tightening the
log helps the operations team distinguish them in production:

```ts
} else if (!item.productId) {
  // Distinguish custom line items from genuinely deleted products. Both
  // skip inventory deduction (correct), but they have different ops
  // implications: deleted products may indicate a data integrity issue,
  // custom line items are intentional.
  const reason: 'CUSTOM_LINE_ITEM' | 'DELETED_PRODUCT' =
    item.productName ? 'CUSTOM_LINE_ITEM' : 'DELETED_PRODUCT'
  logger.info('⏭️ Skipping inventory deduction', {
    orderId,
    reason,
    productName: item.productName,
    unitPrice: Number(item.unitPrice ?? 0),
  })
}
```

Optional but recommended — makes pay-later / mixed-cart issues much easier
to debug from logs alone.

## Backwards compatibility

| Audience | Behavior |
|---|---|
| **Old TPV versions** (≤ 1.14.0) | Unaffected — they only call legacy `POST /tpv/.../orders` + `PATCH /orders/:id/items`. Both endpoints stay |
| **Other clients on `/mobile/...`** | Unaffected — keep using `/mobile/...` (different client class, different namespace) |
| **New TPV "Cobrar" flow** | Calls the new `/tpv/.../orders/with-items` endpoint. Catalog products + custom items + cortesía + discounts all persisted correctly. Receipt + dashboard show them. |
| **TPV legacy "Pago Rápido"** | Unaffected — it doesn't create orders at all (just amount → Payment). Stays exactly as today, including in production for venues that haven't enabled the new Cobrar flow yet. |
| **TPV legacy "Órdenes" (MenuScreen, FloorPlan, KioskCart)** | Unaffected — they keep the 2-call `createOrder + addItemsToOrder` pattern. Their use cases don't have custom items. Stays in production as-is. |

The new endpoint is purely additive. Zero risk to existing clients.

**Important**: the product owner has been explicit that legacy "Pago Rápido"
and "Órdenes" must keep working untouched even after the new Cobrar flow
ships. The Checkout refactor (see `CHECKOUT_REFACTOR_PLAN.md`) is intentionally
**additive** — both flows coexist during the validation period. The new
endpoint must not introduce changes to the legacy `/tpv/.../orders` or
`PATCH /orders/:id/items` endpoints.

## TPV-side changes (after backend lands)

Tracked separately in `CHECKOUT_REFACTOR_PLAN.md`. Summary:

1. Add to `OrderApiService.kt`:
   ```kotlin
   @POST("tpv/venues/{venueId}/orders/with-items")
   suspend fun createOrderWithItems(
       @Path("venueId") venueId: String,
       @Body request: TpvCreateOrderWithItemsRequest,
   ): Response<ApiResponse<OrderDto>>
   ```
2. New DTO `TpvCreateOrderWithItemsRequest` mirroring the body above (Gson-annotated, `productId: String?` nullable).
3. New domain method `OrderRepository.createTpvOrderWithItems(...)` + impl.
4. `CheckoutViewModel.createOrderWithCurrentItems()` switches from
   `createOrder + addItemsToOrder` (2 calls, drops custom items) to
   `createTpvOrderWithItems` (1 call, all items persisted).
5. Tests: keep existing tests for legacy flow (Pago Rápido / Órdenes), add
   regression test "mixed cart sends custom items as line items not as tip".

Estimate: 1-2 days of TPV work after the endpoint is live in the dev
backend.

## Rollout suggestion

1. Backend: deploy the new endpoint to dev, then prod (it's additive — no
   migration risk).
2. TPV: ship in next release with the Checkout flow already gated by
   `tpvSettings.showCheckout`. If anything is wrong with the endpoint in
   field, dashboard can flip the flag off per venue without an APK update.
3. After 1 release cycle of clean Crashlytics + correct receipts, proceed
   with Phase 8 of the Checkout refactor (delete legacy Pago Rápido +
   Órdenes screens — see `CHECKOUT_REFACTOR_PLAN.md`).

## Acceptance criteria

- [ ] `POST /api/v1/tpv/venues/:venueId/orders/with-items` returns 201 with
      the created order when given a valid mixed-items body.
- [ ] Response item `productId` is `null` for custom line items, populated
      for catalog products.
- [ ] Receipt rendering for an order with custom line items shows them as
      individual lines (not as Propina). [verify in dashboard's order
      detail view + the printed receipt]
- [ ] Auth: rejects requests without TPV bearer token (401) and without
      `orders:create` permission (403).
- [ ] Validation rejects: empty items, items without quantity ≥ 1, items
      without productId AND without (name + unitPrice).
- [ ] Backwards compat: old TPV versions calling
      `POST /tpv/venues/:venueId/orders` continue to receive the same
      response shape they always did.
- [ ] **Inventory**: completing payment on a mixed-cart order deducts stock
      ONLY for the catalog products (and their modifier recipes). Custom
      line items leave inventory untouched. Verify by paying an order with
      a tracked product + an "Otro importe" line and confirming the
      product's `availableQuantity` decreases by exactly the product
      quantity, with no movement on any other SKU.
- [ ] **Logs**: when payment completes on an order containing a custom
      line item, the inventory-skip log line tags `reason: CUSTOM_LINE_ITEM`
      (not `DELETED_PRODUCT`).
- [ ] **Dashboard "Órdenes" detail**: opening an order created by the new
      TPV endpoint that contains custom items must render them as
      individual rows in the items list (e.g. `1× Otro importe — $25.45`),
      not collapse them into a fee/tip line. Verify by paying a cart with
      `1× Mindform turns 1 ($1,660)` + `Otro importe ($25.45)` and
      confirming the dashboard order detail shows BOTH rows.
- [ ] **Dashboard "Pagos" detail**: same payment detail screen (the one
      that shows "Subtotal / Propina / TOTAL") must reflect the order's
      true subtotal. With the example above, `Subtotal = $1,685.45` (sum
      of all line items including custom), `Propina = $0.00`, `TOTAL = $1,685.45`.
      Currently the legacy path produces `Subtotal = $1,685.45` (because
      the receipt shows the payment amount as subtotal) but the order
      detail only has `1× Mindform turns 1 $1,660`, creating a $25.45 gap
      that the operator can't account for.
- [ ] **Cortesía per-item — money math is correct end-to-end** (per the
      gross/net definition in "Subtotal semantics"):
      a cart containing `Hamburguesa $200 (isCortesia=true, reason="Cliente VIP")`
      results in:
      - PaymentScreen charges $0
      - Backend Order: `subtotal = $200` (GROSS, catalog price preserved),
        `discountAmount = $200` (cortesía), `total = $0` (NET).
        The existing payment formula `subtotal - discountAmount + tip`
        evaluates to $0 + $0 tip = $0, matching what was charged.
      - Item line carries `isCortesia=true`, `cortesiaReason="Cliente VIP"`
        (Schema A — SELECTED). Schema B fallback: `discountAmount = $200`
        matching the line gross + reason in `OrderAction.metadata`.
      - Receipt shows `Hamburguesa $200 — Cortesía: Cliente VIP` (the
        gross price stays visible so the customer sees the value of the
        gift).
      - Inventory STILL deducts (the venue gave away a real burger).
      - Audit log captures `cortesiaReason="Cliente VIP"` + `staffId`.
- [ ] **Per-item discount — money math is correct end-to-end** (per the
      gross/net definition in "OrderItem.total semantics"):
      a cart with `Pizza $300 (itemDiscountId="dish-of-the-day")` and the
      discount being "20% off dish of the day" results in:
      - PaymentScreen charges $240
      - Backend OrderItem: `unitPrice=$300`, `quantity=1`,
        `total=$300 (GROSS)`, `discountAmount=$60`, implicit
        `lineNet=$240`. Item is linked to the `Discount` entity via
        `appliedDiscountId` (Schema A — SELECTED). Schema B fallback:
        link lives in `OrderAction.metadata`.
      - Order: `subtotal=$300, discountAmount=$60, total=$240`. Existing
        formula `subtotal - discountAmount + tip` evaluates to $240.
      - Receipt shows the line with the GROSS price ($300) + the
        discount marker ("dish-of-the-day -$60") so the customer sees
        the value of the deal.
- [ ] **Order-level discount — preserves discount identity**: when
      `orderDiscountId` is sent, the receipt and dashboard show the
      discount NAME (e.g. "Happy Hour 10%"), not just `Descuento $X`.
- [ ] **Subtotal mismatch detection**: if the client sends `subtotal=2000`
      (claimed gross) but the backend recomputes `Σ items[].lineGross =
      2500`, the request must be rejected with a 400 and a clear error
      indicating the diff. Same check for `total != subtotal -
      discountAmount`. This catches client/server math drift BEFORE
      the customer is charged the wrong amount.
- [ ] **Tax disabled in new Cobrar V1**: TPV hides "Agregar impuesto" for
      this flow and sends `taxAmount=0`. Backend rejects any request with
      `taxAmount != 0` until the follow-up changes `payment.tpv.service.ts`
      fully-paid math to include order tax.
