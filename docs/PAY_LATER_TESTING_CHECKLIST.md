# Pay Later (Pagar Después) - Manual Testing Checklist

**Feature**: Pay Later Orders
**Sprint**: 2025-12
**Tester**: _____________
**Date**: _____________

---

## ✅ Automated Testing Results

### Backend Tests (Jest)
- **File**: `avoqado-server/tests/unit/services/tpv/pay-later-orders.test.ts`
- **Status**: ✅ **11/11 PASSING**
- **Coverage**:
  - Default behavior (exclude pay-later)
  - Pay-later only filter
  - Include pay-later filter
  - Edge cases
  - Business logic validation

### Android Tests (Kotlin)
- **File**: `avoqado-tpv/app/src/test/.../OrderPayLaterTest.kt`
- **Status**: ✅ **11/11 COMPILED**
- **Coverage**:
  - `isPayLater` property validation
  - PENDING + customer scenarios
  - PARTIAL + customer scenarios
  - Edge cases

---

## 📋 Manual Test Cases

### 1️⃣ Backend API Tests

#### Test 1.1: Get Regular Orders (Exclude Pay-Later by Default)
**Endpoint**: `GET /api/v1/tpv/venues/{venueId}/orders`

**Steps**:
1. Create 2 orders:
   - Order A: PENDING status, NO customer
   - Order B: PENDING status, WITH customer (pay-later)
2. Call `GET /api/v1/tpv/venues/{venueId}/orders`

**Expected**:
- ✅ Returns ONLY Order A
- ✅ Order B is excluded (has customer)

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 1.2: Get Pay-Later Orders Only
**Endpoint**: `GET /api/v1/tpv/venues/{venueId}/orders` with `onlyPayLater=true`

**Steps**:
1. Create 3 orders:
   - Order A: PENDING, NO customer
   - Order B: PENDING, WITH customer
   - Order C: PAID, WITH customer
2. Call endpoint with `onlyPayLater=true`

**Expected**:
- ✅ Returns ONLY Order B
- ✅ Order A excluded (no customer)
- ✅ Order C excluded (PAID status)

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 1.3: Customer Pending Stats
**Endpoint**: `GET /api/v1/dashboard/venues/{venueId}/customers`

**Steps**:
1. Create customer "Juan Pérez"
2. Create 2 pay-later orders for Juan:
   - Order A: $100 remaining
   - Order B: $50 remaining
3. Call customers endpoint

**Expected**:
- ✅ Juan shows `pendingOrderCount: 2`
- ✅ Juan shows `pendingBalance: 150`

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 1.4: Aging Report Endpoint
**Endpoint**: `GET /api/v1/dashboard/reports/pay-later-aging`

**Steps**:
1. Create pay-later orders with different ages:
   - Order A: 10 days old, $100 remaining
   - Order B: 45 days old, $200 remaining
   - Order C: 100 days old, $300 remaining
2. Call aging report endpoint

**Expected**:
```json
{
  "summary": {
    "aging_0_30_total": 100,
    "aging_0_30_count": 1,
    "aging_31_60_total": 200,
    "aging_31_60_count": 1,
    "aging_90_plus_total": 300,
    "aging_90_plus_count": 1
  }
}
```

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

### 2️⃣ TPV Android Tests

#### Test 2.1: Create Pay-Later Order
**Screen**: MenuScreen → ActionsTab

**Steps**:
1. Open TPV app (sandbox build)
2. Create new order with items (e.g., "Pizza $100")
3. Click "Pagar Después" button
4. Select customer from dialog
5. Confirm order creation

**Expected**:
- ✅ "Pagar Después" button is enabled when order has items
- ✅ Dialog shows customer search
- ✅ Order is created with `paymentStatus: PENDING`
- ✅ Order has `orderCustomers` linkage
- ✅ `order.isPayLater` returns `true`

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 2.2: Pay-Later Filter in Order List
**Screen**: OrderListScreen

**Steps**:
1. Create 3 orders:
   - Regular DINE_IN (PENDING, no customer)
   - Pay-later DINE_IN (PENDING, with customer)
   - Regular TAKEOUT (PENDING, no customer)
2. Navigate to Order List
3. Select "Pendientes de Pago" filter

**Expected**:
- ✅ Filter chip shows "Pendientes de Pago"
- ✅ List shows ONLY pay-later order
- ✅ Regular orders are hidden
- ✅ Empty state shows when no pay-later orders

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 2.3: Order.isPayLater Property
**Test**: Domain Model Validation

**Steps**:
1. Create order with PENDING status + customer → `isPayLater` should be `true`
2. Create order with PENDING status, NO customer → `isPayLater` should be `false`
3. Create order with PAID status + customer → `isPayLater` should be `false`
4. Create order with PARTIAL status + customer → `isPayLater` should be `true`

**Expected**:
- ✅ All scenarios return correct boolean value

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

### 3️⃣ Dashboard Tests

#### Test 3.1: Pay-Later Filter in Orders Page
**Page**: `/dashboard/orders`

**Steps**:
1. Create 2 orders:
   - Regular order (PENDING, no customer)
   - Pay-later order (PENDING, with customer)
2. Navigate to Dashboard → Orders page
3. Click "Pagar Después" filter button

**Expected**:
- ✅ Button toggles between outline/default variant
- ✅ Table shows ONLY pay-later order
- ✅ Regular order is hidden
- ✅ Filter persists on page refresh

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 3.2: Customers Pending Columns
**Page**: `/dashboard/customers`

**Steps**:
1. Create customer "María García"
2. Create 2 pay-later orders for María:
   - Order A: $150 remaining
   - Order B: $75 remaining
3. Navigate to Customers page

**Expected**:
- ✅ "Órdenes Pendientes" column shows "2 órdenes"
- ✅ Badge variant is "warning"
- ✅ "Saldo Pendiente" column shows "$225.00"
- ✅ Text color is orange-600
- ✅ Customers without pending show "—" and "$0.00"

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

### 4️⃣ Permission Tests

#### Test 4.1: WAITER Can Create Pay-Later
**Role**: WAITER

**Steps**:
1. Login as WAITER role
2. Create order with items
3. Click "Pagar Después"

**Expected**:
- ✅ Button is visible and enabled
- ✅ Can select customer and create pay-later order

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 4.2: ADMIN Can View Aging Report
**Role**: ADMIN

**Steps**:
1. Login as ADMIN role
2. Navigate to `/dashboard/reports/pay-later-aging`

**Expected**:
- ✅ Page loads successfully
- ✅ Shows 4 aging buckets
- ✅ Can view detailed order list

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 4.3: GUEST Cannot Access Pay-Later
**Role**: GUEST (no permissions)

**Steps**:
1. Login as GUEST role (if exists)
2. Try to access pay-later features

**Expected**:
- ✅ "Pagar Después" button is disabled/hidden
- ✅ Aging report returns 403 Forbidden
- ✅ Backend rejects unauthorized requests

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

### 5️⃣ Edge Cases & Error Handling

#### Test 5.1: Empty Order (No Items)
**Steps**:
1. Create new order
2. Do NOT add any items
3. Try to click "Pagar Después"

**Expected**:
- ✅ Button is disabled when order is empty

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 5.2: Multiple Customers per Order
**Steps**:
1. Create pay-later order
2. Link 2 customers to the same order
3. Verify `isPayLater` property

**Expected**:
- ✅ Order still identified as pay-later
- ✅ First customer is marked as `isPrimary`

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 5.3: Large Balance (Unlimited Trust)
**Steps**:
1. Create pay-later order with $10,000 total
2. Verify order is created without credit limit check

**Expected**:
- ✅ Order is created successfully
- ✅ No balance validation errors
- ✅ Shows in aging report with correct amount

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

#### Test 5.4: TAKEOUT vs Pay-Later Differentiation
**Steps**:
1. Create TAKEOUT order, PENDING, NO customer (regular)
2. Create TAKEOUT order, PENDING, WITH customer (pay-later)
3. Apply filters

**Expected**:
- ✅ Default filter shows ONLY regular TAKEOUT
- ✅ Pay-later filter shows ONLY pay-later TAKEOUT
- ✅ Both are clearly differentiated

**Result**: [ ] PASS / [ ] FAIL
**Notes**: ___________

---

## 📊 Test Summary

**Total Test Cases**: 17
**Passed**: ___
**Failed**: ___
**Blocked**: ___

### Critical Issues Found
1. ___________
2. ___________
3. ___________

### Minor Issues Found
1. ___________
2. ___________

### Notes & Observations
___________
___________
___________

---

## ✅ Sign-Off

**Tester Signature**: _______________
**Date**: _______________
**Status**: [ ] APPROVED / [ ] REJECTED

**Reviewer**: _______________
**Date**: _______________
