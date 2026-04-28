# AngelPay Idempotency Contract (TPV + API)

## Scope
- Processor scope: `ANGELPAY` payment flow on Nexgo N62.
- This contract does not modify Blumon/PAX initialization behavior.
- Frontend rollout for Web/iOS/Android is deferred; this document defines the shared contract to adopt.

## Keys
- `paymentAttemptId`:
  - UUID v4 generated once per logical payment attempt.
  - Reused across retries of the same payment attempt.
- `refundAttemptId`:
  - UUID v4 generated once per logical refund attempt.
  - Reused across retries of the same refund attempt.

## Transport (TPV -> Backend)
- Payment create endpoints:
  - `POST /tpv/venues/{venueId}/fast`
  - `POST /tpv/venues/{venueId}/orders/{orderId}`
  - Field in body: `idempotencyKey` (already implemented in TPV requests).
- Refund create endpoint:
  - `POST /tpv/venues/{venueId}/refunds`
  - Body field: `idempotencyKey`.
  - Header: `Idempotency-Key` with the same value.

## Server Behavior (expected)
- Same `venueId` + same `idempotencyKey` + same payload:
  - Return deterministic replay (same logical operation result), no duplicate record creation.
- Same `venueId` + same `idempotencyKey` + different payload:
  - Return conflict (HTTP 409 recommended) with clear message indicating payload mismatch.
- Missing key:
  - Accept request, but operation is not dedupe-protected.

## Client Rules
- Do not generate a new key for retries of the same attempt.
- Generate a new key only when user starts a new logical attempt.
- Clear in-memory key on flow reset/cancel/end.
- Log key lifecycle in debug logs for support diagnosis.

## Cross-Frontend Adoption Checklist
- `avoqado-web-dashboard`:
  - Include idempotency key for refund create operation.
- `avoqado-ios`:
  - Include idempotency key for refund create operation; align payment keys across queued/online paths.
- `avoqado-android`:
  - Include idempotency key for refund create operation; keep parity with TPV semantics.

