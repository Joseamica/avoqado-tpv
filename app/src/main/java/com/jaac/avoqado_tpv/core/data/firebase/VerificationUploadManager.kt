package com.jaac.avoqado_tpv.core.data.firebase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.jaac.avoqado_tpv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages verification photo uploads to Firebase Storage.
 *
 * **Architecture:**
 * - Photos are compressed before upload (max 1920px, 80% JPEG quality)
 * - Uploaded to: gs://avoqado-d0a24.appspot.com/{env}/venues/{venueSlug}/verifications/{date}/{orderRef}.jpg
 * - Returns public download URL for backend storage
 *
 * **Path Structure:**
 * {env}/venues/{venueSlug}/verifications/{YYYY-MM-DD}/{orderRef}_{index}.jpg
 * Where {env} is "dev" (sandbox) or "prod" (production)
 *
 * Examples:
 * - dev/venues/avoqado-full/verifications/2025-12-12/ORDER-12345_1.jpg
 * - prod/venues/avoqado-full/verifications/2025-12-12/CASH-1765547922_1.jpg
 *
 * **Why Firebase Storage?**
 * 1. Native Android SDK with resumable uploads
 * 2. Direct URL access from dashboard (no backend proxy)
 * 3. Easy email integration (include URL or download)
 * 4. Scalable, cost-effective (5GB free tier)
 *
 * @see PaymentState.VerifyingPrePayment for state management
 */
@Singleton
class VerificationUploadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }
    private val storageRef: StorageReference by lazy { storage.reference }

    companion object {
        private const val TAG = "VerificationUpload"

        // Compression settings
        private const val MAX_DIMENSION = 1920  // Max width or height
        private const val JPEG_QUALITY = 80     // 80% quality (good balance)
        private const val TARGET_SIZE_KB = 500  // Target ~500KB per photo

        // Storage path: {env}/venues/{venueSlug}/verifications/{date}/{orderRef}_{index}.jpg
        private const val DATE_FORMAT = "yyyy-MM-dd"

        /**
         * Get the storage environment prefix based on build flavor.
         * - sandbox → "dev"
         * - production → "prod"
         */
        private val STORAGE_ENV_PREFIX: String = BuildConfig.STORAGE_ENV_PREFIX

        /**
         * Build a storage path with environment prefix.
         * @param path Path without env prefix (e.g., "venues/my-venue/verifications/...")
         * @return Path with env prefix (e.g., "dev/venues/my-venue/verifications/...")
         */
        fun buildStoragePath(path: String): String {
            return "$STORAGE_ENV_PREFIX/$path"
        }
    }

    /**
     * Upload a verification photo to Firebase Storage.
     *
     * **Path Structure:**
     * venues/{venueSlug}/verifications/{YYYY-MM-DD}/{orderRef}_{index}.jpg
     *
     * **Flow:**
     * 1. Compress image (max 1920px, 80% JPEG quality)
     * 2. Upload to Firebase Storage
     * 3. Get download URL
     * 4. Return URL for backend storage
     *
     * @param localPath Local file path of captured photo
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param orderReference Order reference for filename (e.g., "12345678")
     * @param photoIndex Index of photo (1-based) for multiple photos per order
     * @param onProgress Optional callback for upload progress (0.0 to 1.0)
     * @return Result with download URL on success, or error on failure
     */
    suspend fun uploadPhoto(
        localPath: String,
        venueSlug: String,
        orderReference: String,
        photoIndex: Int = 1,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> {
        return try {
            Timber.d("📸 [$TAG] Starting upload | path=$localPath | venue=$venueSlug | order=$orderReference")

            // Step 1: Compress image
            val compressedBytes = compressImage(localPath)
            Timber.d("📸 [$TAG] Compressed to ${compressedBytes.size / 1024}KB")

            // Step 2: Generate storage path
            // Format: {env}/venues/{venueSlug}/verifications/{date}/{orderRef}_{index}.jpg
            val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
            val fileName = "${orderReference}_${photoIndex}.jpg"
            val storagePath = buildStoragePath("venues/$venueSlug/verifications/$dateStr/$fileName")

            Timber.d("📸 [$TAG] Uploading to: $storagePath")

            // Step 3: Upload to Firebase Storage
            val photoRef = storageRef.child(storagePath)
            val uploadTask = photoRef.putBytes(compressedBytes)

            // Track progress
            uploadTask.addOnProgressListener { snapshot ->
                val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
                onProgress?.invoke(progress)
                Timber.d("📸 [$TAG] Upload progress: ${(progress * 100).toInt()}%")
            }

            // Wait for upload to complete
            uploadTask.await()

            // Step 4: Get download URL
            val downloadUrl = photoRef.downloadUrl.await().toString()

            Timber.i("📸 [$TAG] Upload complete | url=$downloadUrl")
            Result.success(downloadUrl)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Upload failed")
            Result.failure(e)
        }
    }

    /**
     * Upload multiple photos sequentially with proper indexing.
     *
     * @param localPaths List of local file paths
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param orderReference Order reference for filename (e.g., "12345678")
     * @param onProgress Callback for overall progress (0.0 to 1.0)
     * @return Result with list of download URLs, or error if any upload fails
     */
    suspend fun uploadPhotos(
        localPaths: List<String>,
        venueSlug: String,
        orderReference: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<List<String>> {
        if (localPaths.isEmpty()) {
            return Result.success(emptyList())
        }

        return try {
            val urls = mutableListOf<String>()
            var completedCount = 0

            for ((index, path) in localPaths.withIndex()) {
                val result = uploadPhoto(
                    localPath = path,
                    venueSlug = venueSlug,
                    orderReference = orderReference,
                    photoIndex = index + 1  // 1-based index
                ) { photoProgress ->
                    // Calculate overall progress
                    val overallProgress = (completedCount + photoProgress) / localPaths.size
                    onProgress?.invoke(overallProgress)
                }

                result.fold(
                    onSuccess = { url ->
                        urls.add(url)
                        completedCount++
                        onProgress?.invoke(completedCount.toFloat() / localPaths.size)
                    },
                    onFailure = { error ->
                        throw error
                    }
                )
            }

            Timber.i("📸 [$TAG] All ${urls.size} photos uploaded successfully")
            Result.success(urls)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Batch upload failed")
            Result.failure(e)
        }
    }

    /**
     * Upload a proof-of-sale photo to Firebase Storage.
     *
     * **Path Structure:**
     * venues/{venueSlug}/proof-of-sale/{YYYY-MM-DD}/{orderNumber}_{amount}.jpg
     *
     * **Flow:**
     * 1. Compress image (max 1920px, 80% JPEG quality)
     * 2. Upload to Firebase Storage
     * 3. Get download URL
     * 4. Return URL for backend storage
     *
     * @param localPath Local file path of captured photo
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param orderNumber Order number for filename (e.g., "ORD-00123")
     * @param amount Payment amount for filename (e.g., "150.50")
     * @param onProgress Optional callback for upload progress (0.0 to 1.0)
     * @return Result with download URL on success, or error on failure
     */
    suspend fun uploadProofOfSale(
        localPath: String,
        venueSlug: String,
        orderNumber: String,
        amount: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> {
        return try {
            Timber.d("📸 [$TAG] Starting proof-of-sale upload | path=$localPath | venue=$venueSlug | order=$orderNumber | amount=$amount")

            // Step 1: Compress image
            val compressedBytes = compressImage(localPath)
            Timber.d("📸 [$TAG] Compressed to ${compressedBytes.size / 1024}KB")

            // Step 2: Generate storage path
            // Format: {env}/venues/{venueSlug}/proof-of-sale/{date}/{orderNumber}_{amount}.jpg
            val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
            val fileName = "${orderNumber}_${amount}.jpg"
            val storagePath = buildStoragePath("venues/$venueSlug/proof-of-sale/$dateStr/$fileName")

            Timber.d("📸 [$TAG] Uploading proof-of-sale to: $storagePath")

            // Step 3: Upload to Firebase Storage
            val photoRef = storageRef.child(storagePath)
            val uploadTask = photoRef.putBytes(compressedBytes)

            // Track progress
            uploadTask.addOnProgressListener { snapshot ->
                val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
                onProgress?.invoke(progress)
                Timber.d("📸 [$TAG] Upload progress: ${(progress * 100).toInt()}%")
            }

            // Wait for upload to complete
            uploadTask.await()

            // Step 4: Get download URL
            val downloadUrl = photoRef.downloadUrl.await().toString()

            Timber.i("📸 [$TAG] Proof-of-sale upload complete | url=$downloadUrl")
            Result.success(downloadUrl)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Proof-of-sale upload failed")
            Result.failure(e)
        }
    }

    /**
     * Compress image to reduce upload size and storage costs.
     *
     * **Compression Strategy:**
     * 1. Decode with inSampleSize for memory efficiency
     * 2. Resize to max 1920px (preserving aspect ratio)
     * 3. Correct EXIF rotation
     * 4. Encode as JPEG at 80% quality
     *
     * @param localPath Path to original image
     * @return Compressed image as ByteArray
     */
    private fun compressImage(localPath: String): ByteArray {
        val file = File(localPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Photo file not found: $localPath")
        }

        // Step 1: Get image dimensions without loading full bitmap
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(localPath, options)

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        Timber.d("📸 [$TAG] Original size: ${originalWidth}x${originalHeight}")

        // Step 2: Calculate sample size for memory-efficient decoding
        options.inSampleSize = calculateInSampleSize(originalWidth, originalHeight, MAX_DIMENSION)
        options.inJustDecodeBounds = false

        // Step 3: Decode bitmap
        var bitmap = BitmapFactory.decodeFile(localPath, options)
            ?: throw IllegalStateException("Failed to decode image: $localPath")

        // Step 4: Resize if still larger than max dimension
        bitmap = resizeBitmap(bitmap, MAX_DIMENSION)

        // Step 5: Correct rotation based on EXIF
        bitmap = correctRotation(bitmap, localPath)

        Timber.d("📸 [$TAG] Resized to: ${bitmap.width}x${bitmap.height}")

        // Step 6: Compress to JPEG
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)

        // Clean up
        bitmap.recycle()

        return outputStream.toByteArray()
    }

    /**
     * Calculate optimal inSampleSize for memory-efficient decoding.
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val maxOriginal = maxOf(width, height)

        while (maxOriginal / inSampleSize > maxDimension * 2) {
            inSampleSize *= 2
        }

        return inSampleSize
    }

    /**
     * Resize bitmap to fit within max dimension while preserving aspect ratio.
     */
    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val scale = minOf(
            maxDimension.toFloat() / width,
            maxDimension.toFloat() / height
        )

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        if (resized != bitmap) {
            bitmap.recycle()
        }

        return resized
    }

    /**
     * Correct image rotation based on EXIF orientation.
     * Camera sensors often capture in landscape; EXIF tells us the actual orientation.
     */
    private fun correctRotation(bitmap: Bitmap, imagePath: String): Bitmap {
        return try {
            val exif = ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotation == 0f) {
                return bitmap
            }

            val matrix = Matrix().apply {
                postRotate(rotation)
            }

            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            if (rotated != bitmap) {
                bitmap.recycle()
            }

            rotated
        } catch (e: Exception) {
            Timber.w(e, "📸 [$TAG] Failed to correct rotation, using original")
            bitmap
        }
    }

    /**
     * Upload a clock-in verification photo to Firebase Storage.
     *
     * **Path Structure:**
     * venues/{venueSlug}/clockin/{YYYY-MM-DD}/{staffId}_{timestamp}.jpg
     *
     * **Anti-Fraud Features:**
     * - Timestamp in filename prevents reuse
     * - Live camera capture only (no gallery)
     * - URL stored in TimeEntry for audit trail
     *
     * @param localPath Local file path of captured photo
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param staffId Staff member ID for filename
     * @param timestamp Timestamp for uniqueness (System.currentTimeMillis())
     * @param onProgress Optional callback for upload progress (0.0 to 1.0)
     * @return Result with download URL on success, or error on failure
     */
    suspend fun uploadClockInPhoto(
        localPath: String,
        venueSlug: String,
        staffId: String,
        timestamp: Long = System.currentTimeMillis(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> {
        return try {
            Timber.d("📸 [$TAG] Starting clock-in photo upload | staff=$staffId | venue=$venueSlug")

            // Step 1: Compress image
            val compressedBytes = compressImage(localPath)
            Timber.d("📸 [$TAG] Compressed to ${compressedBytes.size / 1024}KB")

            // Step 2: Generate storage path
            // Format: {env}/venues/{venueSlug}/clockin/{date}/{staffId}_{timestamp}.jpg
            val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
            val fileName = "${staffId}_${timestamp}.jpg"
            val storagePath = buildStoragePath("venues/$venueSlug/clockin/$dateStr/$fileName")

            Timber.d("📸 [$TAG] Uploading clock-in photo to: $storagePath")

            // Step 3: Upload to Firebase Storage
            val photoRef = storageRef.child(storagePath)
            val uploadTask = photoRef.putBytes(compressedBytes)

            // Track progress
            uploadTask.addOnProgressListener { snapshot ->
                val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
                onProgress?.invoke(progress)
                Timber.d("📸 [$TAG] Upload progress: ${(progress * 100).toInt()}%")
            }

            // Wait for upload to complete
            uploadTask.await()

            // Step 4: Get download URL
            val downloadUrl = photoRef.downloadUrl.await().toString()

            Timber.i("📸 [$TAG] Clock-in photo upload complete | url=$downloadUrl")
            Result.success(downloadUrl)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Clock-in photo upload failed")
            Result.failure(e)
        }
    }

    /**
     * Upload clock-out verification photo to Firebase Storage.
     * Path: venues/{venueSlug}/clockout/{date}/{staffId}_{timestamp}.jpg
     *
     * @param localPath Path to local image file (from camera capture)
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param staffId Staff member ID for filename
     * @param timestamp Timestamp for uniqueness (System.currentTimeMillis())
     * @param onProgress Optional callback for upload progress (0.0 to 1.0)
     * @return Result with download URL on success, or error on failure
     */
    suspend fun uploadClockOutPhoto(
        localPath: String,
        venueSlug: String,
        staffId: String,
        timestamp: Long = System.currentTimeMillis(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> {
        return try {
            Timber.d("📸 [$TAG] Starting clock-out photo upload | staff=$staffId | venue=$venueSlug")

            // Step 1: Compress image
            val compressedBytes = compressImage(localPath)
            Timber.d("📸 [$TAG] Compressed to ${compressedBytes.size / 1024}KB")

            // Step 2: Generate storage path
            // Format: {env}/venues/{venueSlug}/clockout/{date}/{staffId}_{timestamp}.jpg
            val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
            val fileName = "${staffId}_${timestamp}.jpg"
            val storagePath = buildStoragePath("venues/$venueSlug/clockout/$dateStr/$fileName")

            Timber.d("📸 [$TAG] Uploading clock-out photo to: $storagePath")

            // Step 3: Upload to Firebase Storage
            val photoRef = storageRef.child(storagePath)
            val uploadTask = photoRef.putBytes(compressedBytes)

            // Track progress
            uploadTask.addOnProgressListener { snapshot ->
                val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
                onProgress?.invoke(progress)
                Timber.d("📸 [$TAG] Upload progress: ${(progress * 100).toInt()}%")
            }

            // Wait for upload to complete
            uploadTask.await()

            // Step 4: Get download URL
            val downloadUrl = photoRef.downloadUrl.await().toString()

            Timber.i("📸 [$TAG] Clock-out photo upload complete | url=$downloadUrl")
            Result.success(downloadUrl)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Clock-out photo upload failed")
            Result.failure(e)
        }
    }

    /**
     * Upload a facade/store front photo to Firebase Storage.
     * Path: venues/{venueSlug}/facade/{date}/{staffId}_{timestamp}.jpg
     *
     * @param localPath Path to local image file (from camera capture)
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param staffId Staff member ID for filename
     * @param timestamp Timestamp for uniqueness (System.currentTimeMillis())
     * @param onProgress Optional callback for upload progress (0.0 to 1.0)
     * @return Result with download URL on success, or error on failure
     */
    suspend fun uploadFacadePhoto(
        localPath: String,
        venueSlug: String,
        staffId: String,
        timestamp: Long = System.currentTimeMillis(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> {
        return try {
            Timber.d("📸 [$TAG] Starting facade photo upload | staff=$staffId | venue=$venueSlug")

            val compressedBytes = compressImage(localPath)
            Timber.d("📸 [$TAG] Compressed to ${compressedBytes.size / 1024}KB")

            val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
            val fileName = "${staffId}_${timestamp}.jpg"
            val storagePath = buildStoragePath("venues/$venueSlug/facade/$dateStr/$fileName")

            Timber.d("📸 [$TAG] Uploading facade photo to: $storagePath")

            val photoRef = storageRef.child(storagePath)
            val uploadTask = photoRef.putBytes(compressedBytes)

            uploadTask.addOnProgressListener { snapshot ->
                val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
                onProgress?.invoke(progress)
                Timber.d("📸 [$TAG] Upload progress: ${(progress * 100).toInt()}%")
            }

            uploadTask.await()

            val downloadUrl = photoRef.downloadUrl.await().toString()

            Timber.i("📸 [$TAG] Facade photo upload complete | url=$downloadUrl")
            Result.success(downloadUrl)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Facade photo upload failed")
            Result.failure(e)
        }
    }

    /**
     * Upload a bank deposit voucher photo to Firebase Storage.
     * Path: venues/{venueSlug}/deposit/{date}/{staffId}_{timestamp}.jpg
     *
     * @param localPath Path to local image file (from camera capture)
     * @param venueSlug Venue slug for storage path (e.g., "avoqado-full")
     * @param staffId Staff member ID for filename
     * @param timestamp Timestamp for uniqueness (System.currentTimeMillis())
     * @param onProgress Optional callback for upload progress (0.0 to 1.0)
     * @return Result with download URL on success, or error on failure
     */
    suspend fun uploadDepositPhoto(
        localPath: String,
        venueSlug: String,
        staffId: String,
        timestamp: Long = System.currentTimeMillis(),
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> {
        return try {
            Timber.d("📸 [$TAG] Starting deposit photo upload | staff=$staffId | venue=$venueSlug")

            val compressedBytes = compressImage(localPath)
            Timber.d("📸 [$TAG] Compressed to ${compressedBytes.size / 1024}KB")

            val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
            val fileName = "${staffId}_${timestamp}.jpg"
            val storagePath = buildStoragePath("venues/$venueSlug/deposit/$dateStr/$fileName")

            Timber.d("📸 [$TAG] Uploading deposit photo to: $storagePath")

            val photoRef = storageRef.child(storagePath)
            val uploadTask = photoRef.putBytes(compressedBytes)

            uploadTask.addOnProgressListener { snapshot ->
                val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()
                onProgress?.invoke(progress)
                Timber.d("📸 [$TAG] Upload progress: ${(progress * 100).toInt()}%")
            }

            uploadTask.await()

            val downloadUrl = photoRef.downloadUrl.await().toString()

            Timber.i("📸 [$TAG] Deposit photo upload complete | url=$downloadUrl")
            Result.success(downloadUrl)

        } catch (e: Exception) {
            Timber.e(e, "📸 [$TAG] Deposit photo upload failed")
            Result.failure(e)
        }
    }

    /**
     * Delete a photo from Firebase Storage.
     * Used for cleanup when user removes a photo before payment.
     *
     * @param downloadUrl The download URL of the photo to delete
     * @return Result indicating success or failure
     */
    suspend fun deletePhoto(downloadUrl: String): Result<Unit> {
        return try {
            val photoRef = storage.getReferenceFromUrl(downloadUrl)
            photoRef.delete().await()
            Timber.d("📸 [$TAG] Deleted photo: $downloadUrl")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.w(e, "📸 [$TAG] Failed to delete photo (may not exist)")
            Result.failure(e)
        }
    }
}
