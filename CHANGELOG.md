# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-1.md`

---

## [Unreleased]

### **Added**

### **Changed**

### **Fixed**

---

## [1.4.0] - 2026-02-04

### **Added**

- **Ordering offline cache (Table Service)**: Table + floor element cache with cache-first UI and background refresh
- **Ordering resync on reconnect**: `syncPendingOrders()` with dirty tracking to resync pending local orders
- **High-signal logs**: Sync timeline logs (`syncRunId`, versions, dirty state) + floor plan cache hits/misses
- **Slow network testing tool**: `SlowNetworkInterceptor` toggle from SuperAdmin for simulated poor connectivity

### **Changed**

- **Order sync debounce**: Debounce no longer cancels in-flight sync; dirty changes trigger follow-up sync
- **Stable item ordering**: `line_position` added for draft order items to avoid UI reordering
- **Refund flow guards**: Refunds use original payment venue, backfill missing serial, and require `payments:refund` permission

### **Fixed**

- **Ordering count drift**: Prevents version conflicts caused by canceling sync mid-flight
- **Table Service flicker**: Cache-first rendering prevents constant refresh under slow networks
- **Refund 404**: Correct venueId used for refund recording
