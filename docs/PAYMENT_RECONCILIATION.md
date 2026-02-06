# Payment Reconciliation & Blumon Multi-Merchant System

> **Main Context:** See [CLAUDE.md](./CLAUDE.md) for core principles and quick reference

---

## 📋 Table of Contents

1. [Reconciliation Overview](#reconciliation-overview)
2. [Blumon Multi-Merchant System](#blumon-multi-merchant-system)
3. [Payment Source Separation](#payment-source-separation)
4. [Backend Schema Requirements](#backend-schema-requirements)
5. [App-Side Implementation](#app-side-implementation)
6. [Migration Strategy](#migration-strategy)

---

## Reconciliation Overview

### Why Reconciliation Matters

**Problem Statement**: At end of day/month, businesses need to know EXACTLY:
- How much cash was collected?
- How much was processed through Merchant Account A (Terminal 1)?
- How much was processed through Merchant Account B (Terminal 2)?
- Total tips collected per payment method?
- Commission fees owed to payment processors?

**Real-World Scenario**:
```
End of day report (Restaurant):
├─ Cash: $1,250.00 (25 transactions)
├─ Merchant A (Terminal 1): $8,450.00 (120 transactions) → -2.5% commission = $8,238.75 net
├─ Merchant B (Terminal 2): $3,200.00 (45 transactions) → -2.5% commission = $3,120.00 net
└─ Total: $12,900.00 gross | $12,608.75 net (after commissions)
```

**If we mix cash with merchant payments**: Reconciliation becomes IMPOSSIBLE. You can't separate cash (0% commission) from card payments (2.5% commission).

---

## Blumon Multi-Merchant System

### Core Concept

**One Sentence Summary**: One physical PAX device can process payments for multiple merchants by registering different "virtual serial numbers" with Blumon, routing each to a different Momentum API account.

### Physical vs Virtual Serial Numbers

#### Physical Device
- **PAX A910S Terminal**: Serial number `AVQD-2841548417` (fixed, built-in)
- **Asset**: Single hardware device sitting on the restaurant counter
- **Represents**: One physical payment terminal

#### Virtual Serial Numbers (Blumon Workaround)

Blumon allows registering the same physical device **twice** with different credential sets:

| Virtual Serial | Device ID | Purpose | Merchant | Momentum API posId |
|---|---|---|---|---|
| `2841548417` | First registration | Main restaurant | Merchant Account A | `376` |
| `2841548418` | Second registration | Ghost kitchen | Merchant Account B | `378` |

**Key Insight**: These are NOT separate devices—they're the **same physical device registered twice with different credentials**.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                Physical PAX Device                               │
│                Serial: AVQD-2841548417                           │
└────────────────────────────┬──────────────────────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
        ┌──────────┐   ┌──────────┐   ┌──────────┐
        │Merchant A│   │Merchant B│   │Merchant C│
        │          │   │          │   │          │
        │Virtual:  │   │Virtual:  │   │Virtual:  │
        │2841548417│   │2841548418│   │2841548419│
        │          │   │          │   │          │
        │PosId:376 │   │PosId:378 │   │PosId:380 │
        │          │   │          │   │          │
        │Creds: A  │   │Creds: B  │   │Creds: C  │
        │Rate:1.5% │   │Rate:1.8% │   │Rate:2.0% │
        └────┬─────┘   └────┬─────┘   └────┬─────┘
             │              │              │
             └──────────────┴──────────────┘
                  All route to different
               Momentum API positions on
                  same physical device
```

### Payment Flow (5 Steps)

#### 1. Fetch Terminal Config (App Startup)
```
GET /api/v1/tpv/terminals/AVQD-2841548417/config
    ↓
Backend returns:
{
  terminal: { serialNumber: "AVQD-2841548417", ... },
  merchantAccounts: [
    { id: "merchant_001", serialNumber: "2841548417", posId: "376", ... },
    { id: "merchant_002", serialNumber: "2841548418", posId: "378", ... }
  ]
}
```

**Backend file**: `terminal.tpv.controller.ts:83`

#### 2. Select Merchant (User Taps Button)
```
User: "Selecting Cuenta B"
    ↓
viewModel.selectMerchant(merchantB)
```

**Android files**:
- `PaymentViewModel.kt:113`
- `MerchantSelectionContent.kt:32`

#### 3. SDK Reinitializes (3-5 seconds)
```
MultiMerchantSDKManager.switchMerchant(merchantB)
    ├─ Decrypt merchant B credentials
    ├─ Call BlumonService.getAccessToken(serial="2841548418")
    ├─ Call BlumonService.getRSAKeys(posId="378")
    ├─ Call BlumonService.getDUKPTKeys(serial="2841548418")
    └─ SDK ready for payment
```

**Why 3-5 seconds?** The SDK must:
- Download new DUKPT keys for the virtual serial
- Re-initialize EMV kernel
- Update OAuth tokens

#### 4. Process Payment
```
User taps card
    ↓
SDK knows: "Use Merchant B (Serial 2841548418, PosId 378)"
    ↓
Encrypt card data with DUKPT keys for serial 2841548418
    ↓
Send to Blumon Momentum API with:
  - posId: 378
  - OAuth token: merchantB's token
```

#### 5. Record Payment (Backend)
```
POST /api/v1/tpv/venues/{venueId}/orders/{orderId}/payment
Body: {
  amount: 10000,
  method: "CARD",
  merchantAccountId: "merchant_002",  // ✅ MUST include this
  cardBrand: "VISA",
  ...
}
```

### Routing Logic

**How Blumon Routes Based on Virtual Serial**:

1. **Virtual Serial Number** → OAuth username
2. **PosId** → Momentum API position
3. **Credentials** → Access token for that merchant
4. **DUKPT Keys** → Card encryption for that serial

```
Android App (Selects "Cuenta B")
    ↓
Blumon SDK Context:
  - Serial: 2841548418
  - PosId: 378
  - Credentials: Merchant B
  - DUKPT: For Serial 2841548418
    ↓
Blumon Momentum API
  POST /sale
  Headers: posId=378, OAuth=Merchant B token
    ↓
Merchant B's Bank Account (Santander)
```

### Cost Structure (Per Merchant)

**CRITICAL**: Costs are **PER MERCHANT ACCOUNT**, not per device.

```
Terminal AVQD-2841548417
│
├── MerchantAccount A (Serial 2841548417)
│   └── ProviderCostStructure
│       ├── debitRate: 1.5%
│       ├── creditRate: 2.5%
│       ├── fixedCostPerTransaction: 0.50 MXN
│       └── effectiveFrom: 2025-01-01
│
└── MerchantAccount B (Serial 2841548418)
    └── ProviderCostStructure
        ├── debitRate: 1.8%        ← DIFFERENT!
        ├── creditRate: 2.8%       ← DIFFERENT!
        ├── fixedCostPerTransaction: 0.75 MXN  ← DIFFERENT!
        └── effectiveFrom: 2025-01-01
```

**Why Different Costs?**
- Merchant A: 100 transactions/month → 1.5% rate
- Merchant B: 10,000 transactions/month → 1.8% rate (volume discount)
- Blumon negotiates **per posId**, not per device

---

## Payment Source Separation

### MANDATORY Rules

#### ❌ WRONG: Assigning cash to a merchant account

```typescript
// ❌ BAD: Cash payment assigned to Merchant A
{
  method: "CASH",
  merchantAccountId: "cm123_merchant_a",  // WRONG!
  amount: 5000
}

// Result: Merchant A report shows $50 that was actually cash
// Problem: Can't separate merchant commissions from cash receipts
```

#### ✅ CORRECT: Cash as separate payment source

```typescript
// ✅ GOOD: Cash payment with no merchant
{
  method: "CASH",
  merchantAccountId: null,  // ← Cash has no merchant
  amount: 5000
}

// Result: Clear separation in reports:
// - Merchant A: $0 (no transactions)
// - Cash: $50 (1 transaction)
```

---

## Backend Schema Requirements

### Field: merchantAccountId

**Rule**: `merchantAccountId` MUST be:
- **REQUIRED** for card payments (`method: "CREDIT_CARD" | "DEBIT_CARD"`)
- **NULL/Optional** for cash payments (`method: "CASH"`)
- **NULL/Optional** for online payments (`method: "ONLINE"`)

### Backend Validation (TypeScript + Zod)

```typescript
const PaymentSchema = z.object({
  method: z.enum(["CASH", "CREDIT_CARD", "DEBIT_CARD", "ONLINE"]),
  merchantAccountId: z.string().cuid().nullable().optional(),
  amount: z.number().int().positive(),
  // ... other fields
}).refine((data) => {
  // Card payments MUST have merchant account
  if (["CREDIT_CARD", "DEBIT_CARD"].includes(data.method)) {
    return data.merchantAccountId != null;
  }
  // Cash/Online payments MUST NOT have merchant account
  if (["CASH", "ONLINE"].includes(data.method)) {
    return data.merchantAccountId == null;
  }
  return true;
}, {
  message: "Card payments require merchantAccountId, cash/online must not have it"
});
```

### Database Models

#### MerchantAccount (Prisma)
```prisma
model MerchantAccount {
  id                String @id

  // Core routing fields
  providerId        String
  externalMerchantId String

  // Blumon-Specific Multi-Merchant Fields
  blumonSerialNumber String?   // Virtual serial: "2841548417"
  blumonPosId        String?   // Momentum API: "376"
  blumonEnvironment  String?   // "SANDBOX" or "PRODUCTION"
  blumonMerchantId   String?   // Blumon internal ID

  // Encrypted credentials (per merchant)
  credentialsEncrypted Json    // OAuth tokens + DUKPT keys

  // UI/Business
  displayName        String?   // "Main Account", "Ghost Kitchen"
  active             Boolean @default(true)
  displayOrder       Int

  // Relations
  costStructures     ProviderCostStructure[]
}
```

#### ProviderCostStructure (Per Merchant)
```prisma
model ProviderCostStructure {
  id                String @id

  // ⭐ CRITICAL: Linked to MERCHANT, not TERMINAL
  merchantAccountId String
  merchantAccount   MerchantAccount @relation(...)

  // Cost breakdown
  debitRate         Decimal    // e.g., 0.015 (1.5%)
  creditRate        Decimal    // e.g., 0.025 (2.5%)
  amexRate          Decimal
  internationalRate Decimal
  fixedCostPerTransaction Decimal?

  // Period
  effectiveFrom     DateTime
  effectiveTo       DateTime?
  active            Boolean

  @@unique([merchantAccountId, effectiveFrom])
}
```

---

## App-Side Implementation

### 1. Queries Must Handle NULL merchant accounts

```kotlin
// ✅ CORRECT: Group payments by source
val paymentsBySource = database.paymentDao().groupBySource()
// Returns:
// - merchantAccountId: "cm123_merchant_a", method: "CREDIT_CARD" → $8,450
// - merchantAccountId: "cm123_merchant_b", method: "DEBIT_CARD" → $3,200
// - merchantAccountId: null, method: "CASH" → $1,250

// ❌ WRONG: Filter by merchant without considering null
val merchantPayments = database.paymentDao()
    .getByMerchant(merchantId) // This excludes cash!
```

### 2. UI Must Display Payment Source Clearly

```kotlin
// Display payment source in UI
fun PaymentReceipt.displaySource(): String {
    return when {
        method == "CASH" -> "Efectivo 💵"
        merchantAccountId != null -> {
            val merchant = getMerchant(merchantAccountId)
            "${merchant.displayName} 💳"
        }
        else -> "Online 🌐"
    }
}

// Example output:
// - "Efectivo 💵"
// - "Cuenta Blumon A (Sandbox) 💳"
// - "Cuenta Blumon B (Producción) 💳"
```

### 3. Reports Must Separate Sources

```kotlin
// Daily reconciliation report
data class DailyReconciliation(
    val date: LocalDate,
    val cashTotal: BigDecimal,           // merchantAccountId = null
    val merchantATotalGross: BigDecimal, // merchantAccountId = A
    val merchantACommission: BigDecimal,
    val merchantATotalNet: BigDecimal,
    val merchantBTotalGross: BigDecimal,
    val merchantBCommission: BigDecimal,
    val merchantBTotalNet: BigDecimal,
    val grandTotal: BigDecimal
)

// Query example
fun getDailyReconciliation(date: LocalDate): DailyReconciliation {
    val payments = database.paymentDao().getByDate(date)

    val cashTotal = payments
        .filter { it.method == "CASH" && it.merchantAccountId == null }
        .sumOf { it.amount }

    val merchantAPayments = payments
        .filter { it.merchantAccountId == MERCHANT_A_ID }

    val merchantAGross = merchantAPayments.sumOf { it.amount }
    val merchantACommission = merchantAGross * 0.025 // 2.5%
    val merchantANet = merchantAGross - merchantACommission

    // ... repeat for merchant B

    return DailyReconciliation(
        date = date,
        cashTotal = cashTotal,
        merchantATotalGross = merchantAGross,
        merchantACommission = merchantACommission,
        merchantATotalNet = merchantANet,
        // ...
    )
}
```

---

## Migration Strategy

### If Changing Existing System

If you already have payments in database and want to make `merchantAccountId` optional:

```sql
-- Step 1: Identify cash payments (manual review needed)
SELECT * FROM payments
WHERE method = 'CASH'
  AND merchantAccountId IS NOT NULL;

-- Step 2: Set merchantAccountId to NULL for confirmed cash payments
UPDATE payments
SET merchantAccountId = NULL
WHERE method = 'CASH';

-- Step 3: Add database constraint (PostgreSQL example)
ALTER TABLE payments
ADD CONSTRAINT check_merchant_by_method
CHECK (
  (method IN ('CREDIT_CARD', 'DEBIT_CARD') AND merchantAccountId IS NOT NULL)
  OR
  (method IN ('CASH', 'ONLINE') AND merchantAccountId IS NULL)
);
```

---

## Risks & Mitigations

### Potential Issues with Optional merchantAccountId

#### 1. Query Complexity
Every query filtering by merchant must explicitly handle `NULL` case:

```sql
-- ❌ WRONG: Excludes cash
SELECT * FROM payments WHERE merchantAccountId = 'cm123_merchant_a';

-- ✅ CORRECT: Include or exclude null explicitly
SELECT * FROM payments
WHERE merchantAccountId = 'cm123_merchant_a'
   OR (merchantAccountId IS NULL AND method = 'CASH');
```

#### 2. Joins Can Fail
LEFT JOIN on merchantAccount table will return null rows for cash:

```sql
-- Must handle null merchant
SELECT p.*, m.displayName
FROM payments p
LEFT JOIN merchantAccounts m ON p.merchantAccountId = m.id
-- m.displayName will be NULL for cash payments
```

#### 3. GROUP BY Behavior
NULL is treated as a distinct group (which is what we want):

```sql
SELECT merchantAccountId, SUM(amount)
FROM payments
GROUP BY merchantAccountId;
-- Results:
-- merchantAccountId | sum
-- cm123_merchant_a  | 8450
-- cm123_merchant_b  | 3200
-- NULL              | 1250  ← Cash total
```

#### 4. UI Null Safety
Every place showing merchant name must handle null:

```kotlin
// ❌ WRONG: Crashes on null
Text(payment.merchantAccount.displayName)

// ✅ CORRECT: Handle null case
Text(payment.merchantAccount?.displayName ?: "Efectivo")
```

### Mitigations

1. **Helper Functions**: Create utility to get payment source display name
2. **Database Views**: Create view that pre-joins merchant data with null handling
3. **Type Safety**: Use sealed class to represent payment sources:
   ```kotlin
   sealed class PaymentSource {
       data class Merchant(val account: MerchantAccount) : PaymentSource()
       data object Cash : PaymentSource()
       data object Online : PaymentSource()
   }
   ```
4. **Backend Validation**: Enforce conditional requirement at API level
5. **Documentation**: Clearly document that null = cash in all schemas

---

## Real Example: Multi-Merchant Restaurant

### Business Setup
- **Restaurant**: "Casa Maria"
- **Main Location**: Main dining room (Merchant A)
- **Ghost Kitchen**: Off-premises delivery kitchen (Merchant B)

### Terminal Configuration

```
┌──────────────────────────────────┐
│ Terminal: AVQD-2841548417        │
│ Location: Casa Maria Main        │
│                                  │
│ Assigned Merchants:              │
│ 1. Merchant Account A            │
│    Display: "Casa Maria Dine-In" │
│    Serial: 2841548417            │
│    PosId: 376                    │
│    Rate: 1.5% + 0.50 MXN fee     │
│                                  │
│ 2. Merchant Account B            │
│    Display: "Casa Maria Delivery"│
│    Serial: 2841548418            │
│    PosId: 378                    │
│    Rate: 1.8% + 0.75 MXN fee     │
└──────────────────────────────────┘
```

### Payment Scenarios

**Scenario 1: Dine-in Customer**
1. Cashier enters amount: $500
2. Shows rating/tip screens
3. Before payment: "¿Cuál cuenta?" → Selects "Casa Maria Dine-In"
4. SDK reinitializes (3-5 seconds) with Serial 2841548417
5. Customer taps card
6. Payment routes to Merchant A's CLABE account
7. Fee calculated: $500 × 1.5% + $0.50 = $8.00

**Scenario 2: Delivery Order (Ghost Kitchen)**
1. Cashier enters amount: $300
2. Shows rating/tip screens
3. Before payment: "¿Cuál cuenta?" → Selects "Casa Maria Delivery"
4. SDK reinitializes (3-5 seconds) with Serial 2841548418
5. Customer taps card
6. Payment routes to Merchant B's CLABE account
7. Fee calculated: $300 × 1.8% + $0.75 = $6.15

---

## Decision Matrix

| Approach | Pros | Cons | Recommendation |
|----------|------|------|----------------|
| **Optional (null for cash)** | ✅ Correct business logic<br>✅ Clean reconciliation<br>✅ Extensible | ⚠️ More null checks<br>⚠️ Conditional validation | **✅ RECOMMENDED** |
| **Always required (fake merchant)** | ✅ No null handling<br>✅ Simpler queries | ❌ Incorrect logic<br>❌ Confuses reports | ❌ NOT RECOMMENDED |
| **Separate field (paymentSource enum)** | ✅ Very explicit<br>✅ Type-safe | ⚠️ Schema migration<br>⚠️ Redundant | 🤔 Consider for V2 |

---

## Key Takeaways

1. **Cash payments**: `merchantAccountId = null` (ALWAYS)
2. **Card payments**: `merchantAccountId = required` (ALWAYS)
3. **Blumon multi-merchant**: 1 physical device → N virtual serials → N merchants
4. **Cost structure**: Per merchant, NOT per device
5. **Switching merchants**: 3-5 seconds SDK reinitialization
6. **Payment routing**: Determined by virtual serial + posId + credentials

---

## Additional Resources

### Documentation
- [Blumon Multi-Merchant Analysis](../avoqado-server/docs/BLUMON_MULTI_MERCHANT_ANALYSIS.md) - Complete technical details
- [Blumon Quick Reference](../avoqado-server/docs/BLUMON_QUICK_REFERENCE.md) - Key file locations

### Backend Files
- Terminal config: `terminal.tpv.controller.ts:83`
- Blumon service: `blumon.service.ts:1`
- Merchant account service: `merchantAccount.service.ts:70`

### Android Files
- Domain model: `MerchantAccount.kt:41`
- UI selection: `MerchantSelectionContent.kt:32`
- Payment VM: `PaymentViewModel.kt:113`
- SDK manager: `MultiMerchantSDKManager.kt`

---

**Last Updated:** 2025-01-11
**Maintainer:** Development Team
