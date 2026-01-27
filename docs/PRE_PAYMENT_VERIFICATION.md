# Pre-Payment Verification (TPV Settings)

**Purpose:** Capture photos and/or barcodes **before** payment processing. This is controlled by
`TpvSettings` (not by the Serialized Inventory module).

---

## 1. Source of Truth

Pre-payment verification is enabled purely by TPV Settings:

- `showVerificationScreen`
- `requireVerificationPhoto`
- `requireVerificationBarcode`

If `showVerificationScreen = true`, the flow **must** insert the pre-payment verification step
before merchant selection, regardless of whether rating/tip screens are shown.

---

## 2. Flow Placement

**Pre-payment flow (simplified):**

1. Amount
2. Rating (optional, per settings)
3. Tip (optional, per settings)
4. **Pre-payment Verification** (if enabled)
5. Merchant selection
6. Payment processing

If rating/tip are disabled, the flow goes directly to **pre-payment verification** (if enabled)
or to merchant selection (if disabled).

---

## 3. Required Inputs

- **Photo requirement**: enforced when `requireVerificationPhoto = true`
- **Barcode requirement**: enforced when `requireVerificationBarcode = true`
- The UI hides the "Saltar" button when a requirement is mandatory.

---

## 4. Order Reference Consistency

When verification is enabled, an `orderReference` is generated **once** and reused:

- Order payment: uses the last 8 digits of `orderNumber` (numeric portion)
- Fast payment: uses the last 8 digits of the current timestamp

This guarantees photo filenames match the eventual order reference used in backend.

---

## 5. Independence From Serialized Inventory

**Pre-payment verification is NOT controlled by the Serialized Inventory module.**
Serialized Inventory only controls **post-payment proof-of-sale** capture.

---

## 6. Code Locations

- `PaymentViewModel.kt` (sandbox + production): flow gating and verification state
- `PaymentState.VerifyingPrePayment`: state model for pre-payment capture
- `PaymentFlowGate.kt`: centralized decision logic for rating/tip/verification/merchant

---

**Last Updated:** 2026-01-22
**Owner:** Android Team
