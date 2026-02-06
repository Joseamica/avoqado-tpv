# Performance Guidelines - Avoqado TPV (1GB RAM Devices)

> **Main Context:** See [CLAUDE.md](./CLAUDE.md) for core principles and quick reference

---

## Overview

**CRITICAL**: Target devices (PAX A80, A920, Sunmi T2s) have **1GB RAM**. ALWAYS optimize for low memory footprint.

**Optimization Priority**: PAX A80 (smallest RAM/CPU) → If it works on A80, it works on all devices.

---

## Target Device Specs

| Device | RAM | Storage | CPU | Screen |
|--------|-----|---------|-----|--------|
| **PAX A910s** | 1GB | 16GB | Quad-core 1.5GHz | 1280x720 (medium) |

---

## Mandatory Performance Rules

### 1. Pagination ALWAYS (Never load all data at once)

```kotlin
// ❌ WRONG: Loads all orders in memory (OOM risk on 1GB RAM)
val orders = orderRepository.getAllOrders(venueId)  // Could be 10,000+ orders!

// ✅ CORRECT: Pagination with reasonable limits
val orders = orderRepository.getOrders(
    venueId = venueId,
    limit = 20,  // ← Limit per page
    cursor = cursor  // ← Cursor-based pagination
)
```

**Pagination Limits**:
- **Lists**: 20 items per page (max 50)
- **Historical data**: 20 periods per page
- **Images**: 10 items per page (heavy memory)
- **Search results**: 15 items per page

### 2. Cache Cleanup (Prevent memory leaks)

```kotlin
// ✅ CORRECT: Auto-cleanup old cache
suspend fun cleanupOldCache() {
    val cutoffTime = System.currentTimeMillis() - CACHE_TTL_MILLIS
    historicalPeriodDao.deleteOldPeriods(cutoffTime)  // Delete stale data

    Timber.d("🧹 [Cache Cleanup] Freed memory from old cache")
}
```

**Cleanup Rules**:
- **TTL-based**: Delete data older than 24h (historical cache)
- **Size-based**: Limit cache to max 500 entries
- **On logout**: Clear all venue-specific cache
- **On low memory**: Android system triggers `onTrimMemory()`

### 3. Lazy Loading (Load data only when needed)

```kotlin
// ❌ WRONG: Loads all data upfront
LaunchedEffect(Unit) {
    val allProducts = productRepository.getAllProducts()  // 1000+ products!
    val allOrders = orderRepository.getAllOrders()        // 5000+ orders!
}

// ✅ CORRECT: Lazy load on demand
LaunchedEffect(selectedCategory) {
    val products = productRepository.getProductsByCategory(
        categoryId = selectedCategory,
        limit = 20
    )
}
```

### 4. StateFlow Instead of State (Memory efficient)

```kotlin
// ❌ WRONG: State creates recomposition for every field change
data class UiState(
    val orders: List<Order> = emptyList(),  // Entire list recomposed on change
    val isLoading: Boolean = false,
    val error: String? = null
)

// ✅ CORRECT: StateFlow with immutable data
private val _orders = MutableStateFlow<List<Order>>(emptyList())
val orders: StateFlow<List<Order>> = _orders.asStateFlow()

private val _isLoading = MutableStateFlow(false)
val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
```

### 5. Avoid Heavy Composables (No complex animations)

```kotlin
// ❌ WRONG: Heavy animation on 1GB RAM device
AnimatedVisibility(
    visible = isVisible,
    enter = slideInVertically() + fadeIn() + scaleIn(),  // ← Too heavy!
    exit = slideOutVertically() + fadeOut() + scaleOut()
) {
    ComplexContent()
}

// ✅ CORRECT: Simple fade (lightweight)
AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(),  // ← Simple, performant
    exit = fadeOut()
) {
    Content()
}

// ✅ BETTER: No animation (instant)
if (isVisible) {
    Content()
}
```

**Animation Rules**:
- **Avoid**: slideIn, scaleIn, expandVertically (allocate memory for transitions)
- **Use sparingly**: fadeIn, fadeOut (lightweight)
- **Prefer**: Instant show/hide (no memory overhead)

### 6. Efficient Data Structures

```kotlin
// ❌ WRONG: Stores entire order object in map (high memory)
val orderMap = mutableMapOf<String, Order>()  // Order has 20+ fields!

// ✅ CORRECT: Store only IDs, fetch on demand
val selectedOrderIds = mutableSetOf<String>()  // Just strings

// When needed:
val order = orderRepository.getOrder(selectedOrderIds.first())
```

### 7. Image Loading (CRITICAL for 1GB RAM)

```kotlin
// ❌ WRONG: Load full-size images (OOM risk)
Image(
    painter = rememberImagePainter(imageUrl),  // ← Loads full resolution!
    modifier = Modifier.size(100.dp)
)

// ✅ CORRECT: Request thumbnail/scaled version from backend
Image(
    painter = rememberImagePainter("$imageUrl?size=thumbnail"),  // ← Scaled
    modifier = Modifier.size(100.dp)
)
```

**Image Rules**:
- **Never** load images larger than display size
- **Always** request thumbnails from backend (query param: `?size=thumbnail`)
- **Limit** concurrent image loads to 3-5 at once
- **Use** Coil's built-in memory cache (max 50 images)

### 8. Room Database Queries (Index everything)

```kotlin
// ❌ WRONG: No index (slow query on large tables)
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val venueId: String,  // ← Queried often, but NO INDEX!
    val status: String
)

// ✅ CORRECT: Indexed columns for fast queries
@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["venue_id"]),      // ← Fast venue filtering
        Index(value = ["status"]),        // ← Fast status filtering
        Index(value = ["created_at"])     // ← Fast time-based queries
    ]
)
data class OrderEntity(...)
```

**Indexing Rules**:
- **Index ALL** columns used in WHERE clauses
- **Composite index** for multi-column queries (venue_id, status)
- **Unique index** to prevent duplicates

### 9. Background Work (Dispatcher.IO)

```kotlin
// ❌ WRONG: Blocking main thread (UI freeze)
fun loadData() {
    val data = database.query()  // ← Blocks UI thread!
    _state.value = State.Success(data)
}

// ✅ CORRECT: Background thread
suspend fun loadData() = withContext(Dispatchers.IO) {
    val data = database.query()  // ← Background thread
    withContext(Dispatchers.Main) {
        _state.value = State.Success(data)
    }
}
```

### 10. Avoid toString() on Large Objects

```kotlin
// ❌ WRONG: Logs entire order object (memory + performance hit)
Timber.d("Order: $order")  // ← Creates string representation of entire object!

// ✅ CORRECT: Log only relevant fields
Timber.d("Order: id=${order.id}, total=${order.total}, status=${order.status}")
```

---

## Memory Budget Guidelines

| Feature | Max Memory | Notes |
|---------|-----------|-------|
| **Cached Orders** | 100 entries | ~2MB (20KB per order) |
| **Cached Products** | 500 entries | ~5MB (10KB per product) |
| **Cached Images** | 50 images | ~20MB (400KB per image) |
| **Historical Cache** | 200 periods | ~200KB (1KB per period) |
| **ViewModel State** | <5MB | Entire app state |
| **Total App RAM** | <200MB | Peak memory usage |

---

## Performance Testing Checklist

Before committing, verify:

- [ ] **No unbounded lists** (all lists paginated with limit)
- [ ] **No memory leaks** (ViewModels cleared on destroy)
- [ ] **No blocking calls** on main thread (use `withContext(Dispatchers.IO)`)
- [ ] **Cache cleanup** implemented (TTL or size-based)
- [ ] **Indexes** on all queried columns
- [ ] **Images** scaled to display size
- [ ] **No heavy animations** (prefer instant or fade)
- [ ] **StateFlow** instead of mutable State
- [ ] **toString()** only on small objects

---

## Common Performance Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| **App crashes after 10 min** | Memory leak (cache not cleaned) | Add TTL-based cache cleanup |
| **Slow scrolling** | Loading all data at once | Implement pagination (limit 20) |
| **UI freezes on tap** | Blocking main thread | Move to `Dispatchers.IO` |
| **OutOfMemoryError** | Large images or unbounded lists | Scale images, paginate lists |
| **Slow database queries** | Missing indexes | Add `@Index` to queried columns |

---

**Last Updated:** 2025-01-19
**Referenced by:** [CLAUDE.md](./CLAUDE.md)
