# Camera & Verification System

## Overview

CameraX + ML Kit barcode scanning + Firebase Storage upload. Used for pre/post-payment verification (retail), attendance clock-in/out, and proof-of-sale photos. Offline queue for network resilience.

## Architecture

| Component | Technology | Purpose |
|-----------|------------|---------|
| **CameraPreviewScreen** | CameraX (Camera2 API) | Photo capture with flash, volume button trigger |
| **BarcodeScannerScreen** | ZXing (Camera1 API) | Barcode scanning (EAN-13, UPC-A, Code 128, QR) |
| **VerificationUploadManager** | Firebase Storage SDK | Photo compression + upload to Firebase |
| **VerificationQueueEntity** | Room DB | Offline queue for failed uploads |

**Why ZXing instead of ML Kit for barcodes?**
ML Kit requires armeabi-v7a, but PAX devices only support armeabi (required by Blumon SDK).

## CameraX Setup (Photo Capture)

**File:** `CameraPreviewScreen.kt` (630 lines)

### Initialization

```kotlin
CameraPreviewScreen(
    onPhotoCaptured: (String) -> Unit,  // Local file path
    onClose: () -> Unit,
    outputDirectory: File               // Context.cacheDir or filesDir
)
```

### Features

| Feature | Implementation |
|---------|---------------|
| **Camera** | `CameraSelector.DEFAULT_BACK_CAMERA` |
| **Flash** | `ImageCapture.FLASH_MODE_ON/OFF` toggle |
| **Capture Mode** | `CAPTURE_MODE_MINIMIZE_LATENCY` |
| **Permissions** | Camera + Location (for GPS coordinates in EXIF) |
| **Output** | `VERIFICATION_{timestamp}.jpg` |

### Volume Button Capture Pattern

**Broadcast Receiver (Lines 138-160):**
```kotlin
// MainActivity broadcasts ACTION_CAPTURE_PHOTO when volume up is pressed
const val ACTION_CAPTURE_PHOTO = "com.jaac.avoqado_tpv.CAPTURE_PHOTO"

// Track if camera is active
object CameraState {
    var isActive: Boolean = false
}

// Register receiver when camera opens
DisposableEffect(context) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CAPTURE_PHOTO) {
                performCapture()  // Same function as capture button
            }
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_CAPTURE_PHOTO), ...)
    onDispose { context.unregisterReceiver(receiver) }
}
```

**MainActivity Volume Key Handler:**
```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && CameraState.isActive) {
        sendBroadcast(Intent(ACTION_CAPTURE_PHOTO))
        return true  // Consume event (prevent volume change)
    }
    return super.onKeyDown(keyCode, event)
}
```

**UI Hint (Lines 453-525):**
- Arrow pointing UP-LEFT toward physical volume button location on PAX
- Badge: "Botón de Volumen = Foto"
- Positioned at 25% from top on LEFT side

### Camera Cleanup

```kotlin
// CRITICAL: Unbind camera before navigating to barcode scanner
DisposableEffect(Unit) {
    onDispose {
        cameraProvider?.unbindAll()  // Prevents CameraX/ZXing conflict
    }
}
```

## ML Kit Barcode Scanning (ZXing)

**File:** `BarcodeScannerScreen.kt` (562 lines)

### Supported Formats

```kotlin
EAN_13, EAN_8, UPC_A, UPC_E,           // Retail barcodes
CODE_128, CODE_39, CODE_93, CODABAR,   // Industrial barcodes
QR_CODE, DATA_MATRIX, PDF_417, AZTEC   // 2D codes
```

### PAX A910S Camera Fixes

**Problem:** PAX has fixed-focus camera, 1D barcodes need extra scanning help.

**Solution (Lines 241-246):**
```kotlin
val hints = mapOf(
    DecodeHintType.TRY_HARDER to true,       // Spend more time decoding
    DecodeHintType.POSSIBLE_FORMATS to formats,
    DecodeHintType.ALSO_INVERTED to true     // White on black barcodes
)
barcodeView.decoderFactory = DefaultDecoderFactory(formats, hints, null, 0)
```

### Camera Initialization Pattern

```kotlin
// Wait for view layout before starting camera (prevents surface errors)
viewTreeObserver.addOnGlobalLayoutListener {
    viewTreeObserver.removeOnGlobalLayoutListener(this)
    Handler(Looper.getMainLooper()).postDelayed({
        resume()  // Start camera
    }, 500)  // 500ms delay ensures SurfaceView is ready
}
```

### Scan Area Overlay

- 80% screen width, 35% height (wider than tall for barcodes)
- Dark overlay (60% black) around scan area
- Visual barcode hint (alternating bars pattern, 15% opacity)
- Corner accents (40dp green lines)

### Barcode Detection

```kotlin
barcodeView.decodeContinuous(object : BarcodeCallback {
    override fun barcodeResult(result: BarcodeResult?) {
        result?.let {
            val barcode = it.text
            val format = it.barcodeFormat?.name ?: "UNKNOWN"
            onBarcodeDetected(barcode, format)
        }
    }
})
```

### Camera Cleanup (Critical)

```kotlin
DisposableEffect(Unit) {
    onDispose {
        barcodeView.setTorchOff()
        barcodeView.barcodeView.pauseAndWait()  // Blocks until camera released
    }
}
```

**Why `pauseAndWait()`?**
Prevents conflict when navigating back to CameraX photo capture. Without blocking, CameraX may try to initialize while ZXing still holds the camera.

## Firebase Upload Flow

**File:** `VerificationUploadManager.kt` (557 lines)

### Path Structure

```
{env}/venues/{venueSlug}/verifications/{YYYY-MM-DD}/{orderRef}_{index}.jpg
```

**Examples:**
- `dev/venues/avoqado-full/verifications/2025-12-12/ORDER-12345_1.jpg`
- `prod/venues/avoqado-full/verifications/2025-12-12/CASH-1765547922_1.jpg`

### Upload Process

| Step | Action |
|------|--------|
| 1 | **Compress:** Max 1920px, 80% JPEG quality, EXIF rotation correction |
| 2 | **Upload:** Firebase Storage `putBytes()` with progress callback |
| 3 | **Get URL:** `downloadUrl.await()` returns public download URL |
| 4 | **Return:** URL stored in Payment/Order for backend sync |

### Compression Strategy

```kotlin
// 1. Decode with inSampleSize (memory efficient)
val options = BitmapFactory.Options().apply {
    inSampleSize = calculateInSampleSize(width, height, MAX_DIMENSION)
}
var bitmap = BitmapFactory.decodeFile(localPath, options)

// 2. Resize if still > 1920px
bitmap = resizeBitmap(bitmap, MAX_DIMENSION)

// 3. Correct EXIF rotation
bitmap = correctRotation(bitmap, localPath)

// 4. Compress to JPEG (80% quality)
bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
```

**Result:** Original 5MB photo → ~500KB (10x reduction).

### Upload Methods

| Method | Path | Use Case |
|--------|------|----------|
| `uploadPhoto()` | `verifications/{date}/{orderRef}_{index}.jpg` | Pre/post-payment verification |
| `uploadPhotos()` | (Batch upload) | Multiple verification photos |
| `uploadProofOfSale()` | `proof-of-sale/{date}/{orderNumber}_{amount}.jpg` | SERIALIZED_INVENTORY mode |
| `uploadClockInPhoto()` | `clockin/{date}/{staffId}_{timestamp}.jpg` | Attendance verification |
| `uploadClockOutPhoto()` | `clockout/{date}/{staffId}_{timestamp}.jpg` | Attendance verification |

### Progress Tracking

```kotlin
uploadTask.addOnProgressListener { snapshot ->
    val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
    onProgress?.invoke(progress)  // 0.0 to 1.0
}
```

## VerificationQueueEntity (Offline Queue)

**File:** `VerificationQueueEntity.kt`

### Schema

```kotlin
@Entity(tableName = "verification_queue")
data class VerificationQueueEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val localPath: String,          // Local file path
    val venueSlug: String,
    val orderReference: String,
    val photoIndex: Int,
    val status: PhotoUploadStatus,  // PENDING, UPLOADING, UPLOADED, ERROR
    val firebaseUrl: String? = null,
    val uploadProgress: Float = 0f,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

### Upload Status Flow

```
PENDING → UPLOADING → UPLOADED (firebaseUrl set)
          ↓
        ERROR (retryable)
```

### Background Sync

**Worker:** `VerificationSyncWorker` (runs every 15 minutes)

```kotlin
// 1. Query all PENDING or ERROR photos
val queue = verificationQueueDao.getPendingUploads()

// 2. Attempt upload for each
for (photo in queue) {
    when (val result = uploadManager.uploadPhoto(...)) {
        is Result.Success -> {
            verificationQueueDao.updateStatus(photo.id, UPLOADED, url)
            File(photo.localPath).delete()  // Cleanup
        }
        is Result.Failure -> {
            verificationQueueDao.updateStatus(photo.id, ERROR, error)
        }
    }
}
```

## Photo Upload Status Tracking

**State Model (PaymentState.kt:485-506):**

```kotlin
data class VerificationPhoto(
    val localPath: String,
    val status: PhotoUploadStatus,   // PENDING, UPLOADING, UPLOADED, ERROR
    val firebaseUrl: String? = null,
    val uploadProgress: Float = 0f,  // 0.0 to 1.0
    val error: String? = null
) {
    fun isUploaded(): Boolean = status == UPLOADED && firebaseUrl != null
    fun isUploading(): Boolean = status == UPLOADING
    fun hasError(): Boolean = status == ERROR
}
```

**UI Display:**
- **PENDING:** Gray placeholder
- **UPLOADING:** Circular progress indicator (uploadProgress)
- **UPLOADED:** Green checkmark, image preview
- **ERROR:** Red X, retry button

## Pre-Payment vs Post-Payment Verification

| Aspect | Pre-Payment | Post-Payment |
|--------|-------------|--------------|
| **State** | `VerifyingPrePayment` | `Verifying` |
| **When** | Before SelectingMerchant | After Success |
| **Blocking** | YES (payment won't proceed) | NO (can skip) |
| **Order Reference** | Pre-generated `FAST-{timestamp}` | Actual order number |
| **UI** | "Continuar" → merchant selection | "Confirmar" → navigation back |
| **Skip Button** | Only if both requirePhoto=false AND requireBarcode=false | Always visible |

### Pre-Payment Flow

```
EnteringAmount → CollectingTip → VerifyingPrePayment → SelectingMerchant → Payment
                                        ↓
                                 [Photos + Barcodes]
                                 Upload to Firebase
```

**Order Reference Generation (Lines 156-163):**
```kotlin
val orderReference = orderId ?: "FAST-${System.currentTimeMillis()}"
// Ensures photos match order number created in backend
```

### Post-Payment Flow (Legacy)

```
Payment → Success → Verifying → [Capture photos] → Backend sync → Done
```

## File Locations

| File | Lines | Purpose |
|------|-------|---------|
| `CameraPreviewScreen.kt` | 630 | CameraX photo capture, volume button pattern |
| `BarcodeScannerScreen.kt` | 562 | ZXing barcode scanning, PAX camera fixes |
| `VerificationUploadManager.kt` | 557 | Firebase upload, compression, path generation |
| `VerificationQueueEntity.kt` | ~100 | Room entity for offline queue |
| `VerificationQueueDao.kt` | ~150 | Room DAO for queue operations |
| `VerificationScreen.kt` | ~800 | Main verification UI (photo grid, barcode list) |
| `PaymentState.kt:112-224` | 113 | `VerifyingPrePayment` state |
| `PaymentState.kt:432-506` | 75 | `Verifying` state, `VerificationPhoto` model |
