# Production Deployment Flow with Blumon

**Purpose**: Complete guide for deploying Avoqado TPV to production with Blumon integration.

---

## 1. How Environment Detection Works

> **⚠️ CRITICAL**: The Blumon SDK **DOES NOT auto-detect** if it's production or sandbox.
> The environment is determined by the **build variant** you compile.

```
┌─────────────────────────────────────────────────────────────────┐
│ APK Sandbox (assembleSandboxRelease)                            │
│ └── Hardcoded to use: sandbox-tokener.blumonpay.net             │
│ └── Always uses sandbox, REGARDLESS of which terminal           │
├─────────────────────────────────────────────────────────────────┤
│ APK Production (assembleProductionRelease)                      │
│ └── Hardcoded to use: tokener.blumonpay.net                     │
│ └── Always uses production, REGARDLESS of which terminal        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. How the App Gets the Terminal Serial

The app **automatically** reads the serial from the PAX terminal hardware:

```
Terminal PAX (hardware)
    │
    └─► Build.getSerial() → "2841548417"
            │
            └─► App formats: "AVQD-2841548417"
                    │
                    └─► App calls backend: GET /tpv/terminals/AVQD-2841548417/config
                            │
                            └─► Backend responds: MerchantAccounts, posId, venueId, etc.
```

**Key files:**
- `DeviceInfoManager.kt` (line 77-97): Reads `Build.getSerial()` from hardware
- `MainActivity.kt` (line 257-262): Gets config from backend using serial

---

## 3. Production Deployment Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ STEP 1: Build Production APK                                    │
├─────────────────────────────────────────────────────────────────┤
│ export JAVA_HOME=$(/usr/libexec/java_home -v 23)                │
│ ./gradlew assembleProductionRelease                             │
│ Output: app/build/outputs/apk/production/release/               │
│         app-production-release.apk                              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 2: Send APK to Blumon                                      │
├─────────────────────────────────────────────────────────────────┤
│ Blumon remotely installs the APK on the production terminals    │
│ they assign to you.                                             │
│                                                                 │
│ ⚠️ IMPORTANT: Request SERIAL NUMBERS from Blumon BEFORE they   │
│ install the APK.                                                │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 3: Configure Backend (BEFORE installation)                 │
├─────────────────────────────────────────────────────────────────┤
│ In avoqado-server, create in the database:                      │
│                                                                 │
│ 1. PaymentProvider (if it doesn't exist):                       │
│    - code: "BLUMON"                                             │
│    - active: true                                               │
│                                                                 │
│ 2. MerchantAccount (for each terminal):                         │
│    - blumonSerialNumber: "SERIAL_FROM_BLUMON" (no AVQD prefix)  │
│    - blumonEnvironment: "PRODUCTION"                            │
│    - blumonPosId: (provided by Blumon)                          │
│    - active: true                                               │
│                                                                 │
│ 3. ProviderCostStructure:                                       │
│    - debitRate, creditRate, amexRate (agreed rates)             │
│    - effectiveFrom: current date                                │
│    - effectiveTo: null (active)                                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 4: Blumon installs APK on terminal                         │
├─────────────────────────────────────────────────────────────────┤
│ The terminal is already activated in Blumon's system.            │
│ APK is remotely installed.                                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 5: App auto-initializes                                    │
├─────────────────────────────────────────────────────────────────┤
│ 1. App reads hardware serial: Build.getSerial()                 │
│ 2. App calls your backend: "Give me config for AVQD-{serial}"   │
│ 3. Backend responds with production MerchantAccount             │
│ 4. App initializes SDK with tokener.blumonpay.net (PROD)        │
│ 5. App gets OAuth token + RSA keys + DUKPT keys                 │
│ 6. ✅ Terminal ready to process REAL payments                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. CRITICAL: TPV vs E-commerce Distinction

```
┌─────────────────────────────────────────────────────────────────┐
│ BLUMON SDK ANDROID (TPV) - This project                        │
├─────────────────────────────────────────────────────────────────┤
│ • ENVIRONMENT determined by APK BUILD VARIANT                  │
│ • APK connects DIRECTLY to Blumon (sandbox or production)      │
│ • Backend ONLY provides configuration (MerchantAccount, etc.)  │
│ • DOES NOT use USE_BLUMON_MOCK - that variable does NOT apply │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ BLUMON E-COMMERCE (Payment links) - Different project          │
├─────────────────────────────────────────────────────────────────┤
│ • JavaScript SDK for web client pages                          │
│ • BACKEND makes calls to Blumon API                            │
│ • USE_BLUMON_MOCK controls if it uses mock or real API         │
│ • Completely separate from Android SDK                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. Backend Environment Variables for Production

| Variable | Applies to TPV? | Production Value | Description |
|----------|----------------|------------------|-------------|
| `NODE_ENV` | ✅ Yes | `production` | General production mode |
| `USE_BLUMON_MOCK` | ❌ NO | `false` | Only for E-commerce, not TPV |
| `MERCHANT_CREDENTIALS_ENCRYPTION_KEY` | ✅ Yes | (secure key) | Credentials encryption |
| `BLUMON_KYC_EMAILS` | ✅ Yes | Blumon emails | For KYC documents |

---

## 6. Pre-Production Checklist

### Before sending APK to Blumon:
- [ ] Build with `./gradlew assembleProductionRelease`
- [ ] Verify it uses `blumon_sdk-prod.aar` (not debug)
- [ ] Request serial numbers from Blumon

### Before Blumon installs:
- [ ] Create PaymentProvider "BLUMON" in DB (if it doesn't exist)
- [ ] Create MerchantAccount with each serial and `blumonEnvironment: "PRODUCTION"`
- [ ] Create ProviderCostStructure with agreed rates
- [ ] Verify `USE_BLUMON_MOCK=false` in backend

### After installation:
- [ ] Verify terminal connects to backend
- [ ] Verify OAuth works with tokener.blumonpay.net
- [ ] Make test transaction (small amount) to validate
- [ ] Verify payment is correctly recorded in backend

---

## 7. Technical Differences: Sandbox vs Production

| Aspect | Sandbox | Production |
|---------|---------|------------|
| **Build Variant** | `sandboxDebug/Release` | `productionRelease` |
| **Package ID** | `com.jaac.avoqado_tpv.sandbox` | `com.jaac.avoqado_tpv` |
| **Token Server** | `sandbox-tokener.blumonpay.net` | `tokener.blumonpay.net` |
| **Core Server** | `sandbox-core.blumonpay.net` | `core.blumonpay.net` |
| **SDK AAR** | `blumon_sdk-debug.aar` | `blumon_sdk-prod.aar` |
| **BLUMON_ENV** | `"SAND"` | `"PROD"` |
| **Keys** | OAuth token only | OAuth + RSA + DUKPT keys |
| **Money** | Simulated | **REAL** |

---

## 8. Production Troubleshooting

| Problem | Probable Cause | Solution |
|----------|----------------|----------|
| "Terminal not found" | Serial not configured in backend | Create MerchantAccount with correct serial |
| OAuth 401 | Terminal not activated in Blumon | Contact Blumon to verify activation |
| SDK doesn't initialize | Wrong APK (sandbox instead of prod) | Rebuild with `assembleProductionRelease` |
| Payment rejected | Wrong or expired keys | Verify RSA/DUKPT keys, reinitialize SDK |
| Backend doesn't record payment | `USE_BLUMON_MOCK=true` | Change to `false` in environment variables |

---

**Last Updated:** 2025-12-12
