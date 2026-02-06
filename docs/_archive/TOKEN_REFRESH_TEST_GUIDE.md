# Token Refresh Testing Guide

> **Complete guide for testing automatic token refresh on 401 Unauthorized**

---

## 📋 Table of Contents

1. [Prerequisites](#prerequisites)
2. [Backend Configuration](#backend-configuration)
3. [Running the Test Script](#running-the-test-script)
4. [Manual Testing](#manual-testing)
5. [Unit Tests](#unit-tests)
6. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Android

- ✅ Android device or emulator connected (`adb devices`)
- ✅ App installed (`./gradlew installDebug`)
- ✅ Logcat accessible

### Backend

- ✅ Backend running (`npm run dev` in `avoqado-server`)
- ✅ Backend accessible from Android device (same network or ngrok)
- ✅ Test venue and staff with PIN created

---

## Backend Configuration

### Option 1: Short Token Expiration (Recommended for Testing)

**File:** `avoqado-server/src/services/auth/auth.service.ts`

**Change token expiration from 24 hours to 30 seconds:**

```typescript
// BEFORE (Production)
const accessToken = jwt.sign(
  {
    sub: staff.id,
    venueId: venue.id,
    role: staff.role,
    permissions: permissions,
  },
  process.env.JWT_SECRET!,
  { expiresIn: '24h' }  // ← 24 hours
);

// AFTER (Testing)
const accessToken = jwt.sign(
  {
    sub: staff.id,
    venueId: venue.id,
    role: staff.role,
    permissions: permissions,
  },
  process.env.JWT_SECRET!,
  { expiresIn: '30s' }  // ← 30 seconds for testing
);
```

**⚠️ IMPORTANT:** Restart backend after changing:
```bash
cd avoqado-server
npm run dev
```

### Option 2: Invalidate Token Manually (Alternative)

**Force token expiration by clearing SecureStorage:**

```bash
# Clear encrypted session (forces re-login)
adb shell run-as com.jaac.avoqado_tpv rm -rf /data/data/com.jaac.avoqado_tpv/shared_prefs/
```

---

## Running the Test Script

### Automated Test (Bash Script)

**1. Make script executable:**
```bash
chmod +x test_token_refresh.sh
```

**2. Run the test:**
```bash
./test_token_refresh.sh
```

**3. Follow on-screen instructions:**
- Enter PIN when prompted
- Attempt payment after 35 seconds
- Script analyzes logs automatically

**4. Expected output:**
```
╔════════════════════════════════════════════════════════════╗
║                    ✅ TEST PASSED                         ║
╔════════════════════════════════════════════════════════════╗

✅ Token expired (401 received)
✅ Token refresh triggered
✅ Token refreshed successfully
✅ Original request retried
✅ Payment recorded to backend
✅ QR code should be displayed
```

---

## Manual Testing

### Step-by-Step

**1. Configure backend with 30s token expiration** (see above)

**2. Clear app data:**
```bash
adb shell pm clear com.jaac.avoqado_tpv
```

**3. Launch app:**
```bash
adb shell am start -n com.jaac.avoqado_tpv/.MainActivity
```

**4. Start monitoring logs:**
```bash
adb logcat -c
adb logcat | grep -E "(Auth|Token|Refresh|401|Backend Recording)"
```

**5. Login with PIN:**
- Select venue
- Enter PIN: `1234`
- Tap "Iniciar Sesión"

**6. Wait for token to expire (35 seconds):**
```bash
# In another terminal, monitor countdown
for i in {35..1}; do echo "Token expires in: ${i}s"; sleep 1; done
```

**7. Attempt payment:**
- Tap "Cobrar Rápido"
- Enter amount: `$50.00`
- Skip rating (or rate 5 stars)
- Skip tip (or add tip)
- Select "Pagar en Efectivo" (cash payment - no card needed)

**8. Verify in logs:**

**Expected logs (SUCCESS):**
```
⚠️ [Auth] Received 401 Unauthorized - Token expired, attempting refresh...
✅ [Auth] Token refreshed successfully, retrying original request
✅ [Backend Recording] Payment recorded successfully | paymentId=...
✅ [Receipt] Updated Success state with receipt | URL=...
```

**Failed logs (FAILURE):**
```
❌ [Auth] Token refresh failed: ...
🚪 [Auth] Session cleared due to refresh failure - User must re-login
```

---

## Unit Tests

### Running Unit Tests

**1. Run all network tests:**
```bash
./gradlew test --tests "*TokenAuthenticatorTest"
```

**2. Run specific test:**
```bash
./gradlew test --tests "*TokenAuthenticatorTest.authenticate returns new request when token refresh succeeds"
```

**3. View test results:**
```bash
open app/build/reports/tests/testDebugUnitTest/index.html
```

### Test Coverage

**TokenAuthenticatorTest covers:**

| Test Case | Description |
|-----------|-------------|
| ✅ `authenticate returns new request when token refresh succeeds` | Token refresh succeeds → Returns new request with updated token |
| ✅ `authenticate returns null when token refresh fails` | Token refresh fails → Returns null and clears session |
| ✅ `authenticate reuses token when already refreshed by another thread` | Another thread refreshed → Reuses new token without refresh |
| ✅ `authenticate clears session when refresh token is invalid` | Refresh token expired (403) → Clears session |
| ✅ `authenticate handles network error during refresh` | Network error → Clears session and returns null |
| ✅ `authenticate returns null when no token in storage` | Not authenticated → Returns null |

---

## Troubleshooting

### Problem: No 401 error in logs

**Possible causes:**
- Backend token expiration NOT configured to 30s
- Backend not running
- App using cached token that hasn't expired yet

**Solution:**
```bash
# 1. Verify backend configuration
grep "expiresIn" avoqado-server/src/services/auth/auth.service.ts
# Should show: expiresIn: '30s'

# 2. Restart backend
cd avoqado-server
npm run dev

# 3. Clear app data and retry
adb shell pm clear com.jaac.avoqado_tpv
```

---

### Problem: Token refresh fails (refresh token also expired)

**Logs:**
```
❌ [Auth] Token refresh failed: Refresh token expired
🚪 [Auth] Session cleared due to refresh failure
```

**Expected behavior:**
- Session is cleared
- User redirected to LoginScreen
- This is CORRECT behavior (refresh token has 7-day expiration)

---

### Problem: Payment succeeds but no QR code

**Possible causes:**
- Token refresh succeeded but backend recording still failed (different issue)
- Network timeout
- Backend validation error

**Solution:**
```bash
# Check full backend recording logs
adb logcat | grep -A 10 "Backend Recording"

# Check for validation errors
adb logcat | grep "400\|422\|500"
```

---

### Problem: Script shows "TEST INCONCLUSIVE"

**Logs:**
```
⚠️  TEST INCONCLUSIVE
⚠️  No 401 error detected
```

**Solution:**
1. Verify backend token expiration is set to `30s`
2. Restart backend
3. Clear app data
4. Run script again

---

## Monitoring in Production

### Recommended Logs for Production Monitoring

**1. Track token refresh rate:**
```bash
adb logcat | grep "Token refreshed successfully" | wc -l
```

**2. Track refresh failures:**
```bash
adb logcat | grep "Token refresh failed" | wc -l
```

**3. Track session clears (forced logout):**
```bash
adb logcat | grep "Session cleared due to refresh failure" | wc -l
```

### Expected Behavior in Production

**Normal operation:**
- Token expires after 24 hours
- Users actively using app will have tokens auto-refreshed
- Users idle for >24 hours will need to re-login (expected)

**Abnormal operation (investigate):**
- High refresh failure rate (>5%)
- Frequent session clears during active use
- Repeated 401 errors after successful refresh

---

## Performance Impact

### Token Refresh Performance Metrics

| Metric | Expected Value |
|--------|----------------|
| **Refresh latency** | 200-500ms (network dependent) |
| **User-visible delay** | None (silent background refresh) |
| **CPU impact** | Negligible (<1% spike during refresh) |
| **Battery impact** | Negligible (infrequent operation) |
| **Network usage** | ~1KB per refresh |

### Thread Safety

**TokenAuthenticator is thread-safe:**
- Uses `synchronized` block to prevent race conditions
- If 5 requests fail with 401 simultaneously, only 1 refresh executes
- Other threads wait and reuse the new token
- No duplicate refresh requests

---

## Best Practices

### DO ✅

- ✅ Monitor token refresh rate in production
- ✅ Set backend token expiration to 24 hours (production)
- ✅ Use 30s tokens ONLY for testing
- ✅ Test refresh logic before major releases
- ✅ Log refresh failures for monitoring

### DON'T ❌

- ❌ Use short token expiration in production
- ❌ Implement custom retry logic (OkHttp Authenticator handles it)
- ❌ Clear session on network errors (only on refresh failure)
- ❌ Block UI during token refresh (it's automatic)
- ❌ Show error messages to user during refresh (silent operation)

---

## Additional Resources

### Code References

- **TokenAuthenticator:** `app/src/main/java/.../TokenAuthenticator.kt`
- **AuthInterceptor:** `app/src/main/java/.../AuthInterceptor.kt`
- **AuthRepository:** `app/src/main/java/.../AuthRepository.kt`
- **NetworkModule:** `app/src/main/java/.../NetworkModule.kt`

### Documentation

- **OkHttp Authenticator:** https://square.github.io/okhttp/features/authentication/
- **JWT Refresh Tokens:** https://auth0.com/docs/secure/tokens/refresh-tokens
- **Hilt Lazy Injection:** https://dagger.dev/hilt/lazy.html

---

## Support

**Issues?**
- Check logs: `adb logcat | grep -E "(Auth|Token|Refresh)"`
- Review CHANGELOG.md for recent changes
- Contact backend team for refresh endpoint issues

**Questions?**
- Review code comments in TokenAuthenticator.kt
- Check unit tests for expected behavior
- Consult OkHttp Authenticator documentation

---

**Last Updated:** 2025-01-11
**Maintainer:** Development Team
