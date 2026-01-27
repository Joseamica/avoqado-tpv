# PaymentSession (Read-Only Snapshot)

## Purpose
PaymentSession is an immutable snapshot of payment state created by `PaymentViewModel`.
It is **read-only for now** (no behavior changes) and exists to make the refactor safe and incremental.

### Why
- Avoid regressions while we migrate a 5k+ line ViewModel.
- Provide a single, consistent snapshot for debugging and tests.
- Make invalid state combinations visible before we switch the logic to it.

## Current Status (2026-01)
- **Read-only snapshot** with minimal usage.
- Used to build `RetryContext` (smart retry), `FastPayment`/`OrderPayment` contexts (card + cash, including kiosk cash confirmation), `RefundPayment` context for refund recording, and order/split flow decisions (order sync).
- Updated on key user actions: submit amount, tip, rating, kiosk toggle, order context, refund start, pre-payment verification.
- Stored inside `PaymentViewModel` as `sessionSnapshot`.

## What It Contains
- `PaymentMode`: FAST / ORDER / REFUND
- `PaymentFeature` flags: KIOSK, SERIALIZED_INVENTORY, SPLIT, TIP_COLLECTION, RATING_COLLECTION, PRE_VERIFICATION, PROOF_OF_SALE
- Amount, tip, rating
- Order context (orderId / orderNumber / skipLocalOrderValidation)
- Split context (type / equal parts / paid items)
- Pre-payment verification context (photos / barcodes / orderReference)
- Merchant account IDs (backend + local)

## Rules
- **Do NOT** replace existing ViewModel logic yet.
- Keep snapshot updates **side-effect free**.
- If you add new payment inputs, update `updateSessionSnapshot()`.

## Where to Update
`PaymentViewModel` (both sandbox + production):
- `updateSessionSnapshot()`
- Called from:
  - `submitAmount`, `submitAmountDirectToMerchant`
  - `submitTip`, `skipTip`
  - `submitRating`, `skipRating`
  - `setOrderContext`, `setFlowOrigin`, `setKioskPaymentMode`
  - `startRefund`, `resetPayment`
  - `completePrePaymentVerification`, `skipPrePaymentVerification`
  - `processCashPayment`, `confirmCashPayment`

## Next Steps (Planned)
- Introduce `PaymentSession` as the single source of truth.
- Remove scattered `current*` vars once feature-by-feature migration is complete.
- Move flow validation into `PaymentSession` init rules.
