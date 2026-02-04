# Payment Flow Origin (Navigation Guardrails)

**Goal:** Prevent cross-feature navigation leaks between payment flows
(fast payment, order payment, serialized sale, kiosk, refund).

When multiple flows share `PaymentScreen`, navigation must be based on a **single source of truth**
instead of heuristics like `orderId != null` or `skipLocalOrderValidation`.

---

## 1. Source of Truth

`PaymentFlowOrigin` is the canonical origin for navigation decisions:

```
FAST | ORDER | SERIALIZED | KIOSK | REFUND
```

It is stored in `PaymentViewModel.flowOrigin` and set whenever a flow starts:
- `submitAmount()` → FAST or ORDER
- `submitAmountDirectToMerchant()` → SERIALIZED or FAST
- `startRefund()` → REFUND
- `setKioskPaymentMode(true)` → KIOSK

---

## 2. Navigation Rules

**Success button action + label** depends on `flowOrigin`:
- **SERIALIZED** → "Nueva Venta" → return to `SerializedSale`
- **ORDER** → "Nueva Orden" → create new quick order
- **FAST** → "Nuevo Pago" → fast payment entry
- **REFUND** → "Listo" (handled separately in refund UI)

**Back/Cancel/Error** should also route by `flowOrigin`:
- **SERIALIZED** → return to `SerializedSale` (scanner)
- **ORDER / FAST / REFUND / KIOSK** → return to **previous screen** (Menu, Fast entry, Refund list, Kiosk),
  with fallback to Home if the back stack is empty.
- **DetectingCard (card payments, non-refund)** → return to **SelectingMerchant** (stops card detection).
- **Error (card payments, non-refund)** → **Cancelar** returns to **SelectingMerchant** so staff can retry or switch method.

---

## Refund Guardrails

- **Use payment venue for refunds**: Refunds must call `/tpv/venues/{paymentVenueId}/refunds`, not the auth venue.
  `paymentVenueId` is passed through navigation and used in `startRefund()`.
- **Gate by backend permission**: Refund UI should be enabled only if the user has `payments:refund`
  (matches backend `checkPermission('payments:refund')`).

---

## 3. Why This Exists

Without a single origin:
- `orderId != null` accidentally routes serialized payments into Quick Order
- Back button can send serialized flows to tip/merchant steps
- New features can break older flows by adding new heuristics

`PaymentFlowOrigin` makes these invalid paths impossible.

---

## 4. Implementation References

- `PaymentViewModel.kt` → `_flowOrigin` + `setFlowOrigin()`
- `PaymentScreen.kt` → `resolveBackNavigation()` + `resolveSuccessRouting()`

---

## 5. Testing Checklist

- **SERIALIZED**: Success → returns to scanner (state reset)
- **ORDER**: Success → new quick order
- **FAST**: Success → new fast payment
- **REFUND**: Success → back to Home
- **Back** from SelectingMerchant in SERIALIZED → returns to scanner
- **Back** from SelectingMerchant in ORDER → returns to the order screen (not Welcome)
- **Back** from DetectingCard (non-refund) → returns to SelectingMerchant

---

## 3. Fast Flow Guardrails

When `flowOrigin = FAST`, any **order/split** context is ignored:
- `orderId` **must be null**
- split parameters are cleared if accidentally provided
- `skipLocalOrderValidation` is forced to `false`

This prevents fast payments from leaking into order/split behavior.
