# Self-Update System

## Overview

APK self-update mechanism using Avoqado backend + Firebase Storage. Download APK, verify checksum, install via PAX SDK. Separate from Force Update (version gate).

## Difference from Force Update

| System | Trigger | UI | Blocking | APK Source |
|--------|---------|----|---------| ----------|
| **Self-Update** | Manual check (SelfUpdateScreen) | Full screen with version info | NO | Firebase Storage (Avoqado managed) |
| **Force Update** | Auto-check on app start (version gate) | Blocking dialog | YES | Backend reports latest version, downloads from Firebase |

**Force Update** enforces minimum version via `X-App-Version-Code` header. User MUST update to continue.
**Self-Update** allows user to manually check/download updates at their convenience.

See `docs/FORCE_UPDATE_SYSTEM.md` for Force Update implementation.

## Flow

| Step | Component | Action |
|------|-----------|--------|
| 1 | User | Navigates to Settings → "Actualizaciones" |
| 2 | SelfUpdateScreen | Shows "Buscar (Proveedor)" / "Buscar (Avoqado)" buttons |
| 3 | User | Taps "Buscar (Avoqado)" |
| 4 | SelfUpdateViewModel | Calls `checkForUpdate()` |
| 5 | AvoqadoUpdateRepository | `GET /tpv/app-versions/check?currentVersion=X&environment=SANDBOX` |
| 6 | Backend | Compares with latest active version in database |
| 7 | Backend | Returns `hasUpdate: true/false`, update metadata |
| 8 | SelfUpdateViewModel | Emits `AvoqadoUpdateAvailable` state |
| 9 | UI | Shows version card + "Descargar" button |
| 10 | User | Taps "Descargar" |
| 11 | AvoqadoUpdateRepository | Downloads APK from Firebase Storage URL |
| 12 | UI | Shows progress (0-100%) |
| 13 | Repository | Verifies SHA-256 checksum |
| 14 | UI | Emits `ReadyToInstall` state |
| 15 | User | Taps "Instalar Ahora" |
| 16 | PAX SDK | `InstallerAppUseCase.run(apkPath)` |
| 17 | Terminal | Reboots to PAX home menu |
| 18 | User | Manually opens app |

## How Self-Update Works

### 1. Check for Update

**Repository:** `AvoqadoUpdateRepository.kt:49-107`

```kotlin
suspend fun checkForUpdate(): UpdateCheckResult {
    val environment = if (BuildConfig.BLUMON_ENV == "SAND") "SANDBOX" else "PRODUCTION"
    val currentVersionCode = BuildConfig.VERSION_CODE

    val response = apiService.checkForAvoqadoUpdate(
        currentVersion = currentVersionCode,
        environment = environment
    )

    return when {
        !response.body()?.hasUpdate -> UpdateCheckResult.UpToDate(...)
        response.body()?.update != null -> UpdateCheckResult.UpdateAvailable(update)
        else -> UpdateCheckResult.Error(...)
    }
}
```

**API Endpoint:** `GET /tpv/app-versions/check`

**Response:**
```kotlin
{
  "success": true,
  "hasUpdate": true,
  "update": {
    "versionCode": 6,
    "versionName": "1.2.0",
    "downloadUrl": "https://storage.googleapis.com/avoqado-d0a24.appspot.com/...",
    "fileSize": "45123456",
    "checksum": "abc123...",
    "releaseNotes": "Bug fixes and improvements",
    "isRequired": false
  }
}
```

### 2. Download APK

**Repository:** `AvoqadoUpdateRepository.kt:184-276`

```kotlin
suspend fun downloadApk(
    updateInfo: AvoqadoUpdateInfo,
    onProgress: (Int) -> Unit
): DownloadResult {
    // 1. Download from Firebase Storage URL
    val response = downloadClient.newCall(request).execute()

    // 2. Save to /Downloads/avoqado-tpv-{versionName}.apk
    val downloadDir = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
    val outputFile = File(downloadDir, "avoqado-tpv-${updateInfo.versionName}.apk")

    // 3. Stream download with progress tracking
    FileOutputStream(outputFile).use { output ->
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                onProgress((downloadedBytes * 100 / totalBytes).toInt())
            }
        }
    }

    // 4. Verify SHA-256 checksum
    val actualChecksum = calculateSha256(outputFile)
    if (actualChecksum != expectedChecksum) {
        outputFile.delete()
        return DownloadResult.Error("Verificación de integridad falló")
    }

    return DownloadResult.Success(outputFile.absolutePath)
}
```

**Why plain OkHttpClient?**
Injected OkHttpClient has auth interceptors that add `Authorization` header. Firebase Storage URLs don't need auth and reject requests with headers.

### 3. Install APK (PackageInstaller Session API + PAX SDK fallback)

**Installer:** `ApkInstaller.kt` (v1.7.8+)

```
Primary: PackageInstaller Session API (Android 5+)
  → Streams APK bytes via IPC (no cross-process file access)
  → Works on Android 10+ with FUSE/SELinux
  → Shows confirmation dialog (user taps "Install")

Fallback: PAX SDK ISys.installApp() (Android 9 only)
  → Direct path install via NeptuneService
  → Fails on Android 10+ due to FUSE blocking cross-process reads
```

**Result handling:** `InstallResultReceiver.kt` (BroadcastReceiver, dynamic registration)
- Receives `PackageInstaller.STATUS_*` results
- Handles `STATUS_PENDING_USER_ACTION` (shows system install dialog)
- Reports to `ApkInstaller` via `CompletableDeferred`

**Observability:** Every install attempt logged to:
- Firebase Crashlytics (via ObservabilityManager)
- Backend API: `POST /tpv/report-install-attempt`
- FileLogger + Socket.IO RemoteLogger

**Post-install behavior:**
- Terminal reboots to PAX home menu
- App is NOT auto-launched after install
- User must tap Avoqado icon to reopen

### 4. INSTALL_VERSION Command (Remote deployment to old terminals)

**For deploying updates to terminals v1.6.0+ that have old installer code:**

```
Dashboard sends INSTALL_VERSION (versionCode: N)
  → Terminal calls GET /tpv/get-version (public, no auth required)
  → Downloads APK from Firebase Storage
  → Tries pm install via shell → likely fails
  → Fallback: Intent(ACTION_VIEW) + FileProvider → Android system installer
  → User confirms → installs
```

Works offline: command queued by backend, delivered via heartbeat when terminal reconnects.
Terminals v1.2-v1.5.x: require PAXSTORE distribution (no INSTALL_VERSION command).

## Trigger Mechanisms

### 1. Manual Check (SelfUpdateScreen)

**Navigation:** Settings → "Actualizaciones"

**UI:** Two buttons:
- **"Buscar (Proveedor)"** → Blumon provider updates (via PAX infrastructure)
- **"Buscar (Avoqado)"** → Self-managed updates (this system)

### 2. Remote Command (REQUEST_UPDATE)

**Flow:**
```
Dashboard → POST /tpv/terminals/{terminalId}/commands → Socket.IO event
→ TPV receives tpv_command → UpdateRequestManager.handleUpdateRequest()
→ Dialog shown to user → User accepts → Download + Install
```

**Manager:** `UpdateRequestManager.kt`

```kotlin
suspend fun handleUpdateRequest(commandId: String): UpdateRequestResult {
    _updateRequestState.value = UpdateRequestState.Checking

    when (val result = avoqadoUpdateRepository.checkForUpdate()) {
        is UpdateCheckResult.UpdateAvailable -> {
            _updateRequestState.value = UpdateRequestState.ShowDialog(...)
            return UpdateRequestResult.DialogShown(...)
        }
        is UpdateCheckResult.UpToDate -> {
            return UpdateRequestResult.AlreadyUpToDate(...)
        }
        is UpdateCheckResult.Error -> {
            return UpdateRequestResult.Error(...)
        }
    }
}
```

**Dialog:** `UpdateRequestDialog.kt`
- Shows version info
- "Aceptar" → download + install
- "Rechazar" → dismiss dialog

### 3. Force Update (Version Gate)

See `docs/FORCE_UPDATE_SYSTEM.md`. Different system that blocks app if `versionCode < minVersionCode`.

## UI Flow States

**ViewModel:** `SelfUpdateViewModel.kt`

```kotlin
sealed class SelfUpdateState {
    object Idle                                    // Initial screen with buttons
    object Checking                                // Loading spinner
    data class UpToDate(...)                       // Success: already latest
    data class BlumonUpdateAvailable(...)          // Provider update found
    data class AvoqadoUpdateAvailable(...)         // Self-managed update found
    data class Downloading(progress: Int)          // Download progress 0-100
    data class ReadyToInstall(...)                 // Download complete, show warning
    object Installing                              // Installing via PAX SDK
    data class InstallComplete(...)                // Success (terminal will reboot)
    data class Error(code, message, retryAction)   // Error with retry
}
```

**Screen:** `SelfUpdateScreen.kt` (892 lines)

| State | UI |
|-------|-----|
| `Idle` | Icon + "Buscar (Proveedor)" / "Buscar (Avoqado)" buttons + info card |
| `Checking` | Circular progress + "Verificando..." |
| `UpToDate` | Green checkmark + "¡Estás al día!" + version + "Volver" |
| `AvoqadoUpdateAvailable` | Card with current/new version + file size + release notes + "Descargar" |
| `Downloading` | Progress bar + percentage + "No cierres la aplicación" |
| `ReadyToInstall` | Warning card + "La terminal se reiniciará" + "Instalar Ahora" |
| `Installing` | Progress + "Terminal se reiniciará automáticamente" + "No apagues la terminal" |
| `InstallComplete` | Green checkmark + "¡Instalación completa!" |
| `Error` | Red X + error message + code + "Reintentar" / "Volver" |

## Error Handling

| Error | Cause | Recovery |
|-------|-------|----------|
| **Network Error** | No internet during check/download | Show error, retry button |
| **Checksum Mismatch** | Corrupted download | Delete APK, show error, retry |
| **Install Failed** | PAX SDK error | Show error, delete APK, retry |
| **Permission Denied** | "Install from unknown sources" disabled | Open Settings to enable |
| **Server Error** | Backend down or invalid response | Show error, retry later |

**Checksum Verification (Lines 254-263):**
```kotlin
val actualChecksum = calculateSha256(outputFile)
if (actualChecksum != expectedChecksum) {
    outputFile.delete()
    return DownloadResult.Error("Verificación de integridad falló")
}
```

**Permission Check (Lines 109-118):**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
        return  // Wait for user to enable
    }
}
```

## Version Targeting (INSTALL_VERSION Command)

**Superadmin Feature:** Install specific versionCode (rollback or upgrade).

**API Endpoint:** `GET /tpv/app-versions/specific?versionCode=5&environment=SANDBOX`

**Use Case:**
```
Dashboard → INSTALL_VERSION command (versionCode: 5)
→ Backend finds version 5 (if active=true)
→ Returns download URL + metadata
→ TPV downloads + installs
```

**Repository:** `AvoqadoUpdateRepository.kt:118-175`

```kotlin
suspend fun getSpecificVersion(versionCode: Int): UpdateCheckResult {
    val response = apiService.getSpecificVersion(versionCode, environment)

    return when {
        !response.body()?.found -> UpdateCheckResult.Error("Versión no encontrada")
        response.body()?.version != null -> UpdateCheckResult.UpdateAvailable(version)
        else -> UpdateCheckResult.Error(...)
    }
}
```

## File Locations

| File | Lines | Purpose |
|------|-------|---------|
| `AvoqadoUpdateRepository.kt` | 336 | Check for update, download APK, verify checksum |
| `UpdateRequestManager.kt` | 279 | Handle REQUEST_UPDATE command, download + install flow |
| `SelfUpdateScreen.kt` | 892 | UI for all update states |
| `SelfUpdateViewModel.kt` | ~500 | Orchestrates update flow, manages state |
| `UpdateRequestDialog.kt` | ~200 | Dialog shown for remote update requests |
