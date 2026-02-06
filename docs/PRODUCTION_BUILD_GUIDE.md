# Production Build Guide

## ✅ Problem Solved

**Issue**: Production and Sandbox Blumon SDKs have different API signatures:
- **Sandbox SDK**: Has `qpsAmount` and `arpcResponseCode` parameters
- **Production SDK**: Does NOT have these parameters

**Solution**: Created flavor-specific source sets that compile different code for each environment.

---

## 🏗️ Build Variants

The app now has **2 build flavors**:

### 1. Sandbox (Development)
- **Environment**: `BLUMON_ENV = "SAND"`
- **Endpoints**:
  - Token Server: `https://sandbox-tokener.blumonpay.net`
  - Core Server: `https://sandbox-core.blumonpay.net`
- **SDK**: `blumon_sdk-debug.aar` + `lib-services-BP-SAND_1601.aar`
- **App ID**: `com.jaac.avoqado_tpv.sandbox`
- **UI Indicator**: Yellow border on TopBar (debug mode)

### 2. Production
- **Environment**: `BLUMON_ENV = "PROD"`
- **Endpoints**:
  - Token Server: `https://tokener.blumonpay.net`
  - Core Server: `https://core.blumonpay.net`
- **SDK**: `blumon_sdk-prod.aar` + `lib_services-1.2.0.0-PROD.aar`
- **App ID**: `com.jaac.avoqado_tpv`
- **UI Indicator**: Red border on TopBar (debug mode)

---

## 🔄 Switching Environments in Android Studio

### Method 1: Build Variants Panel (Recommended)

1. Open **View** → **Tool Windows** → **Build Variants**
2. In the **Build Variants** panel, click the dropdown under **Active Build Variant**
3. Select one of:
   - **sandboxDebug** - Development mode with sandbox SDK
   - **productionDebug** - Production mode with production SDK
   - **sandboxRelease** - Sandbox release build (signed)
   - **productionRelease** - Production release build (signed)

### Method 2: Command Line

```bash
# Build Sandbox Debug
./gradlew assembleSandboxDebug

# Build Production Debug
./gradlew assembleProductionDebug

# Install Sandbox on device
./gradlew installSandboxDebug

# Install Production on device
./gradlew installProductionDebug
```

---

## 📦 APK Outputs

After building, APKs are located at:

```
app/build/outputs/apk/
├── sandbox/debug/app-sandbox-debug.apk       (33MB)
└── production/debug/app-production-debug.apk (32MB)
```

---

## 🔧 Flavor-Specific Source Sets

The following files have flavor-specific versions:

### Files in `app/src/sandbox/`:
```
app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/
├── data/
│   ├── BlumonInitializer.kt (WITH qpsAmount parameter)
│   └── InitializationManager.kt (WITH qpsAmount parameter)
└── presentation/
    └── PaymentViewModel.kt (WITH arpcResponseCode parameter)
```

### Files in `app/src/production/`:
```
app/src/production/java/com/jaac/avoqado_tpv/features/payment/
├── data/
│   ├── BlumonInitializer.kt (WITHOUT qpsAmount parameter)
│   └── InitializationManager.kt (WITHOUT qpsAmount parameter)
└── presentation/
    └── PaymentViewModel.kt (WITHOUT arpcResponseCode parameter)
```

**How it works**:
- Gradle uses the **flavor-specific version** when available
- All other files remain in `app/src/main/` (shared between flavors)
- Changes to shared logic should be made in `app/src/main/`
- Changes to SDK initialization MUST be made in BOTH flavors

---

## 🚀 Deployment Checklist

### Before Deploying to Production:

- [ ] Serial number is registered in Blumon production backend
- [ ] Backend has production merchant account configuration
- [ ] Test OAuth flow with production credentials
- [ ] Verify DUKPT encryption works (production-only feature)
- [ ] Test with small transaction amounts first
- [ ] Confirm commission rates with Blumon (production has different rates than sandbox)
- [ ] Update merchant selection UI (if multi-merchant is configured)

### Testing Production Build:

```bash
# 1. Build production APK
./gradlew assembleProductionDebug

# 2. Install on PAX device
adb install app/build/outputs/apk/production/debug/app-production-debug.apk

# 3. Open app - verify RED border appears on TopBar (confirms production mode)

# 4. Check logs for initialization
adb logcat -s "BlumonInitializer"

# Expected output:
# I/BlumonInitializer: 🔧 Initializing Blumon SDK...
# I/BlumonInitializer: Environment: PROD
# I/BlumonInitializer: Token Server: https://tokener.blumonpay.net
# I/BlumonInitializer: ✅ SDK initialized successfully
```

---

## ⚠️ Important Notes

### SDK API Differences

| Feature | Sandbox SDK | Production SDK |
|---------|-------------|----------------|
| `qpsAmount` parameter | ✅ Required | ❌ Not available |
| `arpcResponseCode` parameter | ✅ Required | ❌ Not available |
| DUKPT Encryption | ❌ Uses KUSHKY cipher | ✅ Full DUKPT |
| OAuth Flow | ✅ Same | ✅ Same |
| Multi-merchant | ✅ Same | ✅ Same |

### Making Changes

**When editing payment logic:**

1. **If changing SHARED code** (business logic, UI):
   - Edit files in `app/src/main/`
   - Changes apply to BOTH sandbox and production

2. **If changing SDK initialization**:
   - Edit files in `app/src/sandbox/` AND `app/src/production/`
   - MUST maintain API compatibility with each SDK

3. **Adding new SDK calls**:
   - Check if parameter exists in BOTH SDKs
   - If parameter is sandbox-only, create flavor-specific versions

---

## 🐛 Troubleshooting

### Build Error: "No parameter with name 'qpsAmount'"
- **Cause**: Production SDK doesn't have this parameter
- **Fix**: Ensure you're editing the correct flavor file (`app/src/production/`)

### Build Error: "Duplicate class"
- **Cause**: File exists in both `main/` and `flavor/`
- **Fix**: Delete from `main/` (flavor takes precedence)

### UI Shows Wrong Environment Indicator
- **Cause**: BuildConfig not refreshed
- **Fix**: Clean and rebuild: `./gradlew clean assembleSandboxDebug`

### OAuth Fails in Production
- **Cause**: Serial number not registered in Blumon production backend
- **Fix**: Contact Blumon to register your device serial number

---

## 📝 References

- **Blumon Integration**: See `BLUMON_INTEGRATION_COMPLETE.md`
- **Multi-merchant Setup**: See `PAYMENT_RECONCILIATION.md`
- **Credential Management**: Per Edgardo, only serial number is needed - everything else auto-fetches via OAuth

---

**Last Updated**: 2025-11-19
**Production SDK**: `lib_services-1.2.0.0-PROD.aar`
**Sandbox SDK**: `lib-services-BP-SAND_1601.aar`
