# Room Database Schema Reference

**Current Version:** 20 | **Database Name:** `avoqado_database` | **Location:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/AvoqadoDatabase.kt`

## Entity Overview (10 Tables)

| Table | Purpose | Cache Strategy | TTL |
|-------|---------|----------------|-----|
| `pending_payments` | Offline payment queue for failed backend recordings | Retry queue (15min sync) | 7 days after success |
| `draft_orders` | Local-first order storage with hybrid sync | Debounced auto-save (5s) | N/A (persistent) |
| `draft_order_items` | Order items with soft delete | Foreign key cascade | N/A (persistent) |
| `historical_periods` | Historical sales data cache | Network-first + fallback | 24 hours |
| `products` | Product catalog cache | Cache-first | 24 hours |
| `product_categories` | Category cache | Cache-first | 24 hours |
| `tables_cache` | Floor plan tables | Cache-first + background refresh | No expiration |
| `floor_elements_cache` | Floor decorations | Cache-first + background refresh | No expiration |
| `cached_shift` | Last known shift status | Prevention pattern (show offline, block ops) | No expiration |
| `verification_queue` | Step 4 sale verification (photos + barcodes) | Offline-first upload queue | Delete after sync |

## 1. pending_payments

**Purpose:** Queue for payments that succeeded with Blumon SDK but failed to record to backend.

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | INTEGER (PK) | No | Auto-generated primary key |
| `reference_number` | TEXT (unique) | No | Blumon idempotency key (e.g., "000000188231") |
| `venue_id` | TEXT | No | Tenant isolation |
| `staff_id` | TEXT | No | Staff who processed payment |
| `amount` | TEXT | No | Payment amount (BigDecimal as String) |
| `tip` | TEXT | No | Tip amount (BigDecimal as String) |
| `rating` | INTEGER | Yes | User rating (1-5 stars, null if skipped) |
| `merchant_account_id` | TEXT | No | Structured merchant FK (e.g., "cuid_abc123") |
| `blumon_serial_number` | TEXT | No | Legacy Blumon serial (fallback) |
| `device_serial_number` | TEXT | Yes | Terminal attribution (e.g., "AVQD-2841548417") |
| `masked_pan` | TEXT | Yes | Masked card number (e.g., "411111******1111") |
| `card_brand` | TEXT | Yes | Card brand (VISA, MASTERCARD, etc.) |
| `entry_mode` | TEXT | No | Entry mode (CHIP, CONTACTLESS, SWIPE) |
| `is_international` | INTEGER | No | Card origin flag (0=domestic, 1=international) |
| `authorization_number` | TEXT | Yes | Blumon auth code (e.g., "502511") |
| `created_at` | INTEGER | No | Unix timestamp (when payment processed) |
| `retry_count` | INTEGER | No | Retry attempts (max 3) |
| `last_error` | TEXT | Yes | Last sync error message |
| `sync_status` | TEXT | No | PENDING, SYNCING, SUCCESS, FAILED |

**Indexes:** `reference_number` (unique), `sync_status`, `created_at`

**Relationships:** None

## 2. draft_orders

**Purpose:** Local-first order storage with hybrid sync (Toast POS approach).

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | TEXT (PK) | No | Local UUID → Backend CUID after sync |
| `venue_id` | TEXT | No | Tenant isolation |
| `order_number` | TEXT | No | "LOCAL-123456" → "ORD-0001234" after sync |
| `table_id` | TEXT | Yes | Table FK (null for quick orders) |
| `table_name` | TEXT | Yes | Table display name |
| `covers` | INTEGER | No | Number of diners |
| `waiter_id` | TEXT | Yes | Waiter FK |
| `waiter_name` | TEXT | Yes | Waiter display name |
| `customer_name` | TEXT | Yes | Customer name (optional) |
| `customer_phone` | TEXT | Yes | Customer phone (optional) |
| `special_requests` | TEXT | Yes | Special requests text |
| `status` | TEXT | No | OrderStatus enum (DRAFT, CONFIRMED, etc.) |
| `kitchen_status` | TEXT | No | KitchenStatus enum (PENDING, SENT, etc.) |
| `payment_status` | TEXT | No | PaymentStatus enum (UNPAID, PARTIAL, PAID) |
| `order_type` | TEXT | No | OrderType enum (DINE_IN, TAKEOUT, etc.) |
| `subtotal` | TEXT | No | Subtotal (BigDecimal as String) |
| `discount_amount` | TEXT | No | Discount amount |
| `tax` | TEXT | No | Tax amount |
| `total` | TEXT | No | Total amount |
| `paid_amount` | TEXT | No | Amount already paid (split payments) |
| `remaining_balance` | TEXT | No | Amount left to pay |
| `notes` | TEXT | Yes | Order notes |
| `created_at` | INTEGER | No | Unix timestamp |
| `updated_at` | INTEGER | No | Unix timestamp (incremented on change) |
| `version` | INTEGER | No | Optimistic concurrency control |
| `sync_status` | TEXT | No | SYNCED, PENDING, SYNCING, CONFLICT |
| `is_server_created` | INTEGER | No | 0=local-only, 1=has backend CUID |
| `last_sync_at` | INTEGER | Yes | Last successful sync timestamp |
| `conflict_data` | TEXT | Yes | JSON of server state on 409 conflict |
| `merchant_account_id` | TEXT | Yes | Last merchant used (informational) |
| `merchant_account_name` | TEXT | Yes | Merchant display name |
| `last_split_type` | TEXT | Yes | PERPRODUCT, EQUALPARTS, CUSTOMAMOUNT, FULLPAYMENT |

**Indexes:** `(venue_id, order_number)` (unique), `table_id`, `sync_status`, `updated_at`

**Relationships:** Parent of `draft_order_items` (CASCADE DELETE, CASCADE UPDATE)

## 3. draft_order_items

**Purpose:** Order items with soft delete and foreign key cascade.

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | TEXT (PK) | No | Local UUID → Backend CUID after sync |
| `external_id` | TEXT | Yes | Idempotency line ID (defaults to `id` if null) |
| `line_position` | INTEGER | No | Stable ordering (defaults to `created_at`) |
| `order_id` | TEXT (FK) | No | FK to `draft_orders.id` (CASCADE DELETE/UPDATE) |
| `product_id` | TEXT | No | Product FK |
| `product_name` | TEXT | No | Product display name |
| `product_sku` | TEXT | Yes | Product SKU |
| `quantity` | INTEGER | No | Quantity ordered |
| `unit_price` | TEXT | No | Price per unit (BigDecimal as String) |
| `total_price` | TEXT | No | Total price (quantity × unit_price + modifiers) |
| `modifiers` | TEXT | No | JSON serialized List<ProductModifier> |
| `notes` | TEXT | Yes | Item-specific notes |
| `kitchen_status` | TEXT | No | KitchenStatus enum |
| `created_at` | INTEGER | No | Unix timestamp |
| `sent_to_kitchen_at` | INTEGER | Yes | Null if not sent yet |
| `sync_status` | TEXT | No | SYNCED, PENDING, SYNCING, DELETED (soft delete) |
| `is_server_created` | INTEGER | No | 0=local-only, 1=has backend CUID |

**Indexes:** `order_id`, `product_id`, `sync_status`

**Relationships:** Child of `draft_orders` (CASCADE DELETE, CASCADE UPDATE)

## 4. historical_periods

**Purpose:** Offline cache for historical sales data (network-first + cache-fallback).

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | INTEGER (PK) | No | Auto-generated primary key |
| `venue_id` | TEXT | No | Tenant isolation |
| `grouping` | TEXT | No | DAILY, WEEKLY, MONTHLY |
| `period_start` | INTEGER | No | Period start (Unix milliseconds) |
| `period_end` | INTEGER | No | Period end (Unix milliseconds) |
| `label` | TEXT | No | Display label ("15 Enero 2025") |
| `subtitle` | TEXT | No | Display subtitle ("Martes") |
| `total_sales` | TEXT | No | Total sales (BigDecimal as String) |
| `total_orders` | INTEGER | No | Order count |
| `total_products` | INTEGER | No | Product count |
| `average_order_value` | TEXT | No | Average order value |
| `sales_change` | TEXT | Yes | Percentage change vs previous period |
| `orders_change` | TEXT | Yes | Percentage change in orders |
| `cached_at` | INTEGER | No | Cache timestamp (for staleness detection) |

**Indexes:** `(venue_id, grouping, period_start)` (unique), `cached_at`

**Relationships:** None

**TTL:** 24 hours (stale data auto-expires)

## 5. products

**Purpose:** Product catalog cache for instant offline access (cache-first).

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | INTEGER (PK) | No | Auto-generated primary key |
| `product_id` | TEXT | No | Backend product ID |
| `venue_id` | TEXT | No | Tenant isolation |
| `name` | TEXT | No | Product name |
| `sku` | TEXT | No | Product SKU |
| `price` | TEXT | No | Price (BigDecimal as String) |
| `category_id` | TEXT | No | Category FK |
| `category_name` | TEXT | No | Category display name |
| `description` | TEXT | Yes | Product description |
| `emoji` | TEXT | No | Product emoji icon |
| `image_url` | TEXT | Yes | Product image URL |
| `available` | INTEGER | No | Availability flag (1=available, 0=unavailable) |
| `display_order` | INTEGER | No | Display order |
| `track_inventory` | INTEGER | No | 1=track inventory, 0=don't track |
| `inventory_method` | TEXT | Yes | QUANTITY, RECIPE, or null |
| `available_quantity` | INTEGER | Yes | Available quantity (if tracked) |
| `modifier_groups_json` | TEXT | No | JSON serialized List<ModifierGroup> |
| `category_color` | TEXT | Yes | Category color (hex, e.g., "#4CAF50") |
| `category_is_active` | INTEGER | No | Category active status (1=active, 0=inactive) |
| `cached_at` | INTEGER | No | Cache timestamp |

**Indexes:** `(venue_id, product_id)` (unique), `(venue_id, category_id)`, `(venue_id, available)`, `cached_at`

**Relationships:** None (category denormalized for performance)

**TTL:** 24 hours

## 6. product_categories

**Purpose:** Category cache for instant offline access.

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | INTEGER (PK) | No | Auto-generated primary key |
| `category_id` | TEXT | No | Backend category ID |
| `venue_id` | TEXT | No | Tenant isolation |
| `name` | TEXT | No | Category name |
| `display_order` | INTEGER | No | Display order |
| `product_count` | INTEGER | No | Number of products in category |
| `emoji` | TEXT | No | Category emoji icon |
| `color` | TEXT | Yes | Hex color (e.g., "#4CAF50") |
| `is_active` | INTEGER | No | 1=active, 0=inactive (dashboard toggle) |
| `cached_at` | INTEGER | No | Cache timestamp |

**Indexes:** `(venue_id, category_id)` (unique), `cached_at`

**Relationships:** None

**TTL:** 24 hours

## 7. tables_cache

**Purpose:** Floor plan tables cache for offline/slow-network use.

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | INTEGER (PK) | No | Auto-generated primary key |
| `table_id` | TEXT | No | Backend table ID |
| `venue_id` | TEXT | No | Tenant isolation |
| `number` | TEXT | No | Table number ("1", "A2", etc.) |
| `capacity` | INTEGER | No | Max diners |
| `position_x` | REAL | Yes | Floor plan X coordinate |
| `position_y` | REAL | Yes | Floor plan Y coordinate |
| `shape` | TEXT | No | SQUARE, CIRCLE, RECTANGLE |
| `rotation` | INTEGER | No | Rotation angle (degrees) |
| `status` | TEXT | No | AVAILABLE, OCCUPIED, RESERVED |
| `current_order_id` | TEXT | Yes | Current order FK |
| `current_order_number` | TEXT | Yes | Current order number |
| `current_order_covers` | INTEGER | Yes | Current order covers |
| `current_order_total` | TEXT | Yes | Current order total |
| `current_order_item_count` | INTEGER | Yes | Current order item count |
| `current_order_waiter_id` | TEXT | Yes | Current order waiter FK |
| `current_order_waiter_name` | TEXT | Yes | Current order waiter name |
| `current_order_created_at` | TEXT | Yes | Current order creation timestamp |
| `area_id` | TEXT | Yes | Area FK |
| `area_name` | TEXT | Yes | Area name |
| `cached_at` | INTEGER | No | Cache timestamp |

**Indexes:** `(venue_id, table_id)` (unique), `venue_id`, `cached_at`

**Relationships:** None (order info denormalized)

**TTL:** No expiration (show "last known" state when offline)

## 8. floor_elements_cache

**Purpose:** Floor plan decorations cache (walls, text labels, etc.).

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | INTEGER (PK) | No | Auto-generated primary key |
| `element_id` | TEXT | No | Backend element ID |
| `venue_id` | TEXT | No | Tenant isolation |
| `type` | TEXT | No | WALL, TEXT_LABEL, DECORATION |
| `position_x` | REAL | No | Floor plan X coordinate |
| `position_y` | REAL | No | Floor plan Y coordinate |
| `width` | REAL | Yes | Element width (for rectangles) |
| `height` | REAL | Yes | Element height (for rectangles) |
| `rotation` | INTEGER | No | Rotation angle (degrees) |
| `end_x` | REAL | Yes | End X (for lines/walls) |
| `end_y` | REAL | Yes | End Y (for lines/walls) |
| `label` | TEXT | Yes | Text label content |
| `color` | TEXT | Yes | Hex color |
| `area_id` | TEXT | Yes | Area FK |
| `cached_at` | INTEGER | No | Cache timestamp |

**Indexes:** `(venue_id, element_id)` (unique), `venue_id`, `cached_at`

**Relationships:** None

**TTL:** No expiration

## 9. cached_shift

**Purpose:** Last known shift status cache (Square/Toast prevention pattern).

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | TEXT (PK) | No | Shift ID (from server) |
| `venue_id` | TEXT | No | Tenant isolation (unique index) |
| `status` | TEXT | No | OPEN, CLOSED |
| `staff_id` | TEXT | No | Staff who opened shift |
| `staff_name` | TEXT | No | Staff display name |
| `start_time` | TEXT | No | Shift start (ISO 8601) |
| `total_sales` | TEXT | No | Total sales (BigDecimal as String) |
| `total_orders` | INTEGER | No | Order count |
| `duration_minutes` | INTEGER | Yes | Duration (null if just opened) |
| `cached_at` | INTEGER | No | Cache timestamp |

**Indexes:** `venue_id` (unique)

**Relationships:** None

**TTL:** No expiration (always show last known state, block ops when offline)

## 10. verification_queue

**Purpose:** Step 4 sale verification queue (photos + barcodes, offline-first upload).

| Column | Type | Nullable | Purpose |
|--------|------|----------|---------|
| `id` | TEXT (PK) | No | Local UUID |
| `venueId` | TEXT | No | Tenant isolation |
| `staffId` | TEXT | No | Promoter/staff who made sale |
| `paymentId` | TEXT | No | Associated payment FK |
| `orderId` | TEXT | Yes | Associated order FK |
| `photoLocalPaths` | TEXT | No | JSON array of local file paths |
| `photoUrls` | TEXT | No | JSON array of Firebase Storage URLs |
| `scannedBarcodes` | TEXT | No | JSON array of scanned barcodes |
| `syncStatus` | TEXT | No | PENDING, UPLOADING_PHOTOS, SYNCING, SYNCED, FAILED |
| `createdAt` | INTEGER | No | Unix timestamp |
| `syncAttempts` | INTEGER | No | Retry count (for backoff) |
| `lastSyncError` | TEXT | Yes | Last error message |

**Indexes:** `venueId`, `paymentId`, `syncStatus`

**Relationships:** None

**TTL:** Delete after successful sync

## Migration History (v1 → v20)

| Version | Date | Description | Columns Added/Changed |
|---------|------|-------------|-----------------------|
| v1 → v2 | 2025-01-10 | Add Blumon serial tracking | `pending_payments.blumon_serial_number` |
| v2 → v3 | 2025-01-10 | Provider-agnostic merchant tracking | `pending_payments.merchant_account_id` |
| v3 → v4 | 2025-01-11 | User rating feature | `pending_payments.rating` (nullable) |
| v4 → v5 | 2025-01-19 | Local-first order management | Create `draft_orders` + `draft_order_items` tables |
| v5 → v6 | 2025-01-19 | Historical reports cache | Create `historical_periods` table |
| v6 → v7 | 2025-11-20 | Fix FOREIGN KEY with ON UPDATE CASCADE | Recreate `draft_order_items` with `ON UPDATE CASCADE` |
| v7 → v8 | 2025-11-20 | Merchant account tracking for orders | `draft_orders.merchant_account_id`, `merchant_account_name` |
| v8 → v9 | 2025-11-24 | Cache-first product loading | Create `products` + `product_categories` tables |
| v9 → v10 | 2025-11-25 | Offline shift status display | Create `cached_shift` table |
| v10 → v11 | 2025-11-26 | Split payment tracking | `draft_orders.paid_amount`, `remaining_balance` |
| v11 → v12 | 2025-11-28 | Split payment type restriction | `draft_orders.last_split_type` |
| v12 → v13 | 2025-12-01 | Color support for products/categories | `products.color`, `product_categories.color` |
| v13 → v14 | 2025-12-11 | Step 4 verification queue | Create `verification_queue` table |
| v14 → v15 | 2025-01-13 | Filter inactive categories | `product_categories.is_active` |
| v15 → v16 | 2025-01-13 | Category active status in products | `products.category_is_active` |
| v16 → v17 | 2026-01-19 | **CRITICAL FIX** - Pending payments missing columns | `pending_payments.venue_id`, `blumon_serial_number`, `is_international`, `authorization_number`, `device_serial_number` |
| v17 → v18 | 2026-02-03 | Floor plan cache | Create `tables_cache` + `floor_elements_cache` tables, add `draft_order_items.external_id` |
| v18 → v19 | 2026-02-03 | Schema hash fix (idempotent) | Idempotent recreation of v17→v18 changes |
| v19 → v20 | 2026-02-03 | Stable item ordering | `draft_order_items.line_position` |

## How to Add a New Entity Field

**Scenario:** Add a `notes` field to `ProductEntity`.

### Step 1: Add @ColumnInfo to Entity

**File:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/entities/ProductEntity.kt`

```kotlin
@Entity(tableName = "products", ...)
data class ProductEntity(
    // ... existing fields ...

    @ColumnInfo(name = "notes")
    val notes: String? = null,  // ALWAYS add default value for migration

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long
)
```

### Step 2: Create MIGRATION_X_Y

**File:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/AvoqadoDatabase.kt`

Add migration in `companion object`:

```kotlin
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add notes column with default NULL
        database.execSQL(
            "ALTER TABLE products ADD COLUMN notes TEXT DEFAULT NULL"
        )
    }
}
```

### Step 3: Add to DatabaseModule.addMigrations()

**File:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/di/DatabaseModule.kt`

```kotlin
.addMigrations(
    AvoqadoDatabase.MIGRATION_2_3,
    // ... existing migrations ...
    AvoqadoDatabase.MIGRATION_19_20,
    AvoqadoDatabase.MIGRATION_20_21   // ADD HERE
)
```

### Step 4: Increment @Database version

**File:** `AvoqadoDatabase.kt`

```kotlin
@Database(
    entities = [...],
    version = 21,  // INCREMENT (was 20)
    exportSchema = false
)
```

Update header comment:

```kotlin
/**
 * **Current Version:** 21
 * - v20 → v21: Added notes field to ProductEntity (2026-02-05)
 * ...
 */
```

### Step 5: Test Migration

```bash
# Install OLD version (v20)
./gradlew installSandboxDebug

# Use app, generate data (create orders with products)

# Install NEW version (v21)
./gradlew installSandboxDebug

# Verify no crash
adb logcat -s "RoomDatabase:*" | grep -i "migration"
# Should show: "Migration from 20 to 21 successful"
```

## How to Add a New Entity

**Scenario:** Add `CachedStaffEntity` for offline staff directory.

### Step 1: Create Entity File

**File:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/entities/CachedStaffEntity.kt`

```kotlin
@Entity(
    tableName = "cached_staff",
    indices = [
        Index(value = ["venue_id", "staff_id"], unique = true),
        Index(value = ["cached_at"])
    ]
)
data class CachedStaffEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "staff_id")
    val staffId: String,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long
)
```

### Step 2: Create DAO

**File:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/dao/CachedStaffDao.kt`

```kotlin
@Dao
interface CachedStaffDao {
    @Query("SELECT * FROM cached_staff WHERE venue_id = :venueId")
    suspend fun getStaff(venueId: String): List<CachedStaffEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(staff: List<CachedStaffEntity>)

    @Query("DELETE FROM cached_staff WHERE venue_id = :venueId")
    suspend fun clearCache(venueId: String)
}
```

### Step 3: Add to @Database entities

**File:** `AvoqadoDatabase.kt`

```kotlin
@Database(
    entities = [
        PendingPaymentEntity::class,
        // ... existing entities ...
        CachedStaffEntity::class  // ADD HERE
    ],
    version = 21,  // INCREMENT
    exportSchema = false
)
abstract class AvoqadoDatabase : RoomDatabase() {
    // ... existing DAOs ...

    abstract fun cachedStaffDao(): CachedStaffDao  // ADD DAO
}
```

### Step 4: Create Migration

```kotlin
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create cached_staff table
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_staff (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                staff_id TEXT NOT NULL,
                venue_id TEXT NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                cached_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Create indexes
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_cached_staff_venue_staff ON cached_staff(venue_id, staff_id)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cached_staff_cached_at ON cached_staff(cached_at)"
        )
    }
}
```

### Step 5: Add DAO Provider to DatabaseModule

**File:** `DatabaseModule.kt`

```kotlin
@Provides
fun provideCachedStaffDao(database: AvoqadoDatabase): CachedStaffDao {
    return database.cachedStaffDao()
}
```

## Common Migration Mistakes

| Mistake | Consequence | Solution |
|---------|-------------|----------|
| Forgot to add migration to `DatabaseModule.addMigrations()` | 100% crash on app update | Always add to `DatabaseModule` after creating migration |
| Wrong SQL syntax (e.g., missing `IF NOT EXISTS`) | Crash if migration runs twice | Use `IF NOT EXISTS`, `IF NOT EXISTS`, etc. |
| Missing DEFAULT value for new column | Crash on existing rows | Always add `DEFAULT` for new columns |
| Foreign key without ON DELETE CASCADE | Orphaned rows, query errors | Always specify `ON DELETE CASCADE` for child tables |
| Incrementing version without migration | Room schema mismatch error | ALWAYS create migration when incrementing version |
| Missing `@ColumnInfo(name = "snake_case")` | Column name mismatch | Use `@ColumnInfo` for all fields |
| Using Float for money | Precision loss, rounding errors | Use `BigDecimal` stored as TEXT |
| Not filtering by `venueId` | Tenant isolation violation | ALWAYS filter by `venueId` in DAO queries |
| Missing indexes on foreign keys | Slow queries (1000+ rows) | Add indexes for all FK columns |
| Hard-coding table names in queries | Refactoring errors | Use entity `tableName` constant |

## Testing Migrations Guide

### Manual Testing (Recommended)

```bash
# 1. Install OLD version
./gradlew installSandboxDebug  # Or pull from iCloud: avoqado-tpv-1.2.0-sandbox.apk

# 2. Generate realistic data
# - Create draft orders (10+)
# - Process payments (5+)
# - Cache products/categories (full catalog)
# - Queue verifications (if retail venue)

# 3. Install NEW version
./gradlew installSandboxDebug  # New version with migration

# 4. Verify migration success
adb logcat -c && adb logcat -s "RoomDatabase:*" | grep -iE "migration|error"

# 5. Verify data integrity
# - Open draft orders (should all be present)
# - Check pending payments (should all be queued)
# - Browse products (should show cached data)
# - Open verification queue (should show pending items)
```

### Automated Testing (Future)

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AvoqadoDatabase::class.java
    )

    @Test
    fun migrate20To21_preservesData() {
        // Create v20 database
        val db = helper.createDatabase(TEST_DB, 20)

        // Insert data into v20 schema
        db.execSQL("INSERT INTO products (...) VALUES (...)")
        db.close()

        // Migrate to v21
        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB, 21, true,
            AvoqadoDatabase.MIGRATION_20_21
        )

        // Verify data preserved and new column exists
        val cursor = migratedDb.query("SELECT notes FROM products WHERE product_id = 'test_id'")
        assertTrue(cursor.moveToFirst())
        assertNull(cursor.getString(0))  // New column should be NULL
    }
}
```

## Migration Rollback Strategy

Room does NOT support automatic rollback. If migration fails:

1. **Development:** Use `.fallbackToDestructiveMigration()` (data loss acceptable)
2. **Production:** Users MUST wait for hotfix APK with corrected migration
3. **Prevention:** Test migrations thoroughly before production release

**Downgrade Strategy:**

- `.fallbackToDestructiveMigrationOnDowngrade()` is enabled
- Downgrading via `INSTALL_VERSION` command will DELETE all local data
- Dashboard should warn SUPERADMIN about data loss before issuing downgrade command

## Schema Export (Production)

Currently: `exportSchema = false` (development)

For production:

```kotlin
@Database(
    entities = [...],
    version = 21,
    exportSchema = true  // Enable schema export
)
```

Creates JSON schema files in: `app/schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/`

Benefits:
- Version history tracking
- Migration validation
- Schema diffing for code review

## Performance Optimization

| Optimization | Purpose | Status |
|--------------|---------|--------|
| Write-Ahead Logging (WAL) | Allow reads during writes | Enabled |
| Indexes on FK columns | Fast JOIN queries | All FKs indexed |
| Composite unique indexes | Prevent duplicates + fast queries | All cache tables |
| TTL-based cleanup | Prevent database bloat | Manual (no auto-cleanup yet) |
| Pagination in DAOs | Avoid OOM on large datasets | Not implemented (future) |

## Database Size Monitoring

**Typical Sizes:**

- Fresh install: ~100 KB
- After 1 week of use: ~2-5 MB
- After 1 month: ~10-20 MB
- Large venues (500+ products, 1000+ orders): ~50-100 MB

**Cleanup Strategies:**

- Clear TTL-expired cache (24h for products/historical)
- Delete synced pending_payments (7 days after success)
- Delete synced verification_queue (immediate)
- Archive old draft_orders (COMPLETED + 30 days old)

**Not Yet Implemented:** Automatic cleanup worker (future optimization).
