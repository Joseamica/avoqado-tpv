# Pay Later (Pagar Después) - Documentation Index

**Feature**: Pay Later Orders
**Status**: ✅ Production Ready
**Last Updated**: 2025-12-22

---

## 📚 Documentation Map

### 🎯 Quick Start

**New to Pay Later?** Start here:

1. **Concept Overview** → Read `avoqado-server/docs/PAY_LATER_ORDER_CLASSIFICATION.md`
   - Understand how orders are classified as pay-later
   - Learn business logic and rules
   - See why it doesn't interfere with existing functionality

2. **Android Implementation** → Read `avoqado-tpv/docs/PAY_LATER_IMPLEMENTATION.md`
   - Bug fix details (orderCustomers DTO mapping)
   - Banner implementation (Cuentas por Cobrar)
   - UNPAID_TAKEOUT vs PAY_LATER differentiation

3. **Testing Guide** → Use `avoqado-tpv/docs/PAY_LATER_TESTING_CHECKLIST.md`
   - Manual test cases
   - Automated test coverage
   - QA sign-off template

---

## 📖 Full Documentation

### Core Documentation

| Document | Location | Description |
|----------|----------|-------------|
| **Order Classification** | `avoqado-server/docs/PAY_LATER_ORDER_CLASSIFICATION.md` | Business logic, backend implementation |
| **Android Implementation** | `avoqado-tpv/docs/PAY_LATER_IMPLEMENTATION.md` | Bug fix, banner, client-side logic |
| **Testing Checklist** | `avoqado-tpv/docs/PAY_LATER_TESTING_CHECKLIST.md` | QA manual + automated tests |

### Code References

| Component | File | Line | Description |
|-----------|------|------|-------------|
| **isPayLater property** | `app/.../domain/Order.kt` | 113-115 | Computed property for classification |
| **PAY_LATER filter** | `app/.../OrderListScreen.kt` | 308 | Filter implementation |
| **UNPAID_TAKEOUT filter** | `app/.../OrderListScreen.kt` | 303-307 | Counter-filter for anonymous orders |
| **PayLaterBanner** | `app/.../components/PayLaterBanner.kt` | Full file | Blue banner component |
| **UnpaidTakeoutBanner** | `app/.../components/UnpaidTakeoutBanner.kt` | Full file | Red banner component |
| **ViewModel logic** | `app/.../OrderingWelcomeViewModel.kt` | 96-130 | Counting logic |
| **OrderDto fix** | `app/.../data/dto/TableDto.kt` | 211 | orderCustomers field |
| **Mapper fix** | `app/.../data/mappers/OrderMappers.kt` | 68 | orderCustomers mapping |

### Backend References

| Component | File | Description |
|-----------|------|-------------|
| **Order Service** | `avoqado-server/src/services/tpv/order.tpv.service.ts` | Backend filtering logic |
| **Customer Service** | `avoqado-server/src/services/tpv/customer.tpv.service.ts` | Customer linking |
| **Tests** | `avoqado-server/tests/unit/services/tpv/pay-later-orders.test.ts` | Backend unit tests (11/11 passing) |

---

## 🔍 Key Concepts

### What is a Pay-Later Order?

An order is **pay-later** if:

```
PENDING/PARTIAL payment + Customer linked + remainingBalance > 0 = Pay-Later
```

**Example**:
```json
{
  "orderNumber": "ORD-001",
  "paymentStatus": "PENDING",
  "remainingBalance": 100.00,
  "orderCustomers": [
    { "customer": { "firstName": "Juan" } }
  ]
}
```
→ ✅ **Pay-Later** (has customer + unpaid)

---

### UNPAID_TAKEOUT vs PAY_LATER

| Aspect | UNPAID_TAKEOUT | PAY_LATER |
|--------|----------------|-----------|
| **Order Type** | ONLY TAKEOUT | Any type |
| **Customer** | ❌ Anonymous | ✅ Identified |
| **Label** | "Órdenes rápidas sin pagar" | "Cuentas por cobrar" |
| **Color** | 🔴 Red (urgent) | 🔵 Blue (tracking) |
| **Risk** | High (anonymous) | Medium (trackable) |

**Key Point**: These are **mutually exclusive** categories. An order cannot be both.

---

## 🐛 Bug Fix (2025-12-22)

### Problem

- Filter "Pendientes de Pago" showed **empty**
- Database had **3 pay-later orders**
- Backend returned correct data

### Root Cause

`orderCustomers` field NOT mapped in Android DTO:
- Gson silently dropped the field
- `order.isPayLater` always returned `false`
- Filter excluded all orders

### Solution

```kotlin
// 1. Added field to OrderDto (TableDto.kt:211)
@SerializedName("orderCustomers") val orderCustomers: List<OrderCustomerDto>? = null

// 2. Added mapping (OrderMappers.kt:68)
orderCustomers = orderCustomers?.map { it.toOrderCustomer() } ?: emptyList()
```

**Impact**: ✅ PAY_LATER filter now works correctly.

---

## 🎨 UI Enhancement: Dual Banner System

### Before

```
┌─────────────────────────────────────────┐
│ 🔴 Hay 2 órdenes rápidas sin pagar     │
├─────────────────────────────────────────┤
│  Nueva Orden                            │
└─────────────────────────────────────────┘
```

### After

```
┌─────────────────────────────────────────┐
│ 🔴 Hay 2 órdenes rápidas sin pagar     │  ← UNPAID_TAKEOUT (anonymous)
├─────────────────────────────────────────┤
│ 🔵 Hay 3 cuentas por cobrar            │  ← PAY_LATER (identified)
├─────────────────────────────────────────┤
│  Nueva Orden                            │
└─────────────────────────────────────────┘
```

**Why two banners?**
- Different business contexts (anonymous vs identified)
- Different urgency levels (high risk vs trackable)
- Clear visual differentiation (red vs blue)

---

## 🧪 Testing

### Quick Test

```bash
# 1. Check DTO fix
adb logcat -s OrderListViewModel | grep "isPayLater"
# → Should show isPayLater=true for orders with customers

# 2. Check banner counts
adb logcat -s OrderingWelcomeViewModel | grep "📊"
# → Output: "Unpaid orders | TAKEOUT: 0 | PAY_LATER: 3"

# 3. Check navigation
adb logcat -s AppNavigation | grep "PAY_LATER"
# → Output: "Navigating to Order List with PAY_LATER filter"
```

### Full Test Suite

See `PAY_LATER_TESTING_CHECKLIST.md` for:
- 17 manual test cases
- Backend automated tests (11/11 passing)
- Android unit tests (11/11 compiled)
- QA sign-off template

---

## 🚀 Deployment Status

### Completed ✅

- [x] Backend implementation
- [x] Android DTO fix
- [x] PayLaterBanner component
- [x] ViewModel integration
- [x] Navigation wiring
- [x] Unit tests (backend + Android)
- [x] Documentation complete
- [x] Manual testing passed

### Pending

- [ ] QA sign-off
- [ ] Production deployment
- [ ] Monitoring setup

---

## 📞 Support

### Having Issues?

**Issue**: Filter still shows empty
- **Debug**: Check `avoqado-tpv/docs/PAY_LATER_IMPLEMENTATION.md` → Troubleshooting section
- **ADB**: `adb logcat -s OrderListViewModel | grep "PAY_LATER"`

**Issue**: Banner not showing
- **Debug**: Check ViewModel logs for count
- **ADB**: `adb logcat -s OrderingWelcomeViewModel | grep "payLaterCount"`

**Issue**: Wrong banner color
- **Fix**: Verify `primaryContainer` (blue) vs `errorContainer` (red) in PayLaterBanner.kt

---

## 📝 Changelog

### Version 1.1 (2025-12-22)
- ✅ Fixed critical DTO mapping bug (orderCustomers)
- ✅ Added PayLaterBanner (blue, "Cuentas por cobrar")
- ✅ Differentiated UNPAID_TAKEOUT (red) vs PAY_LATER (blue)
- ✅ Complete documentation

### Version 1.0 (Previous)
- ✅ Backend classification logic
- ✅ PAY_LATER filter in OrderListScreen
- ✅ Customer linking via OrderCustomer
- ✅ `isPayLater` computed property

---

## 🔗 Cross-Repository References

### Related Features

- **Customer Loyalty**: `avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md`
- **Order Classification**: Explained in this feature
- **UNPAID_TAKEOUT**: Counter-feature for anonymous orders

### Dashboard Integration

- Pay-later filter: `/dashboard/orders` page
- Pending stats: `/dashboard/customers` page
- Aging report: `/dashboard/reports/pay-later-aging`

---

**Maintainer**: Development Team
**Last Reviewed**: 2025-12-22
**Status**: ✅ Current
