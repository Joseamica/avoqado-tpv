package com.jaac.avoqado_tpv.core.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * BluetoothPermissionHelper
 *
 * Helper class to request Bluetooth permissions at runtime (Android 12+).
 *
 * **Usage**:
 * ```kotlin
 * val permissionHelper = BluetoothPermissionHelper(activity)
 * permissionHelper.requestPermissions { granted ->
 *     if (granted) {
 *         // Proceed with Bluetooth operations
 *     } else {
 *         // Show error message
 *     }
 * }
 * ```
 *
 * Android 12+ (API 31+) requires:
 * - BLUETOOTH_SCAN (for discovering nearby devices)
 * - BLUETOOTH_CONNECT (for connecting to paired devices)
 *
 * Android 11- (API 30-) requires:
 * - BLUETOOTH (legacy)
 * - BLUETOOTH_ADMIN (legacy)
 */
class BluetoothPermissionHelper(private val activity: ComponentActivity) {

    private var permissionCallback: ((Boolean) -> Unit)? = null

    /**
     * Permission launcher for Android 12+
     */
    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            Timber.i("✅ [BluetoothPermission] All Bluetooth permissions granted")
        } else {
            val denied = permissions.filterValues { !it }.keys
            Timber.w("❌ [BluetoothPermission] Permissions denied: $denied")
        }

        permissionCallback?.invoke(allGranted)
        permissionCallback = null
    }

    /**
     * Request Bluetooth permissions
     *
     * @param callback Called with true if all permissions granted, false otherwise
     */
    fun requestPermissions(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            // Check if already granted
            if (hasPermissions(permissions)) {
                Timber.d("✅ [BluetoothPermission] Bluetooth permissions already granted (Android 12+)")
                callback(true)
                permissionCallback = null
                return
            }

            Timber.d("📱 [BluetoothPermission] Requesting Bluetooth permissions (Android 12+)...")
            permissionLauncher.launch(permissions)
        } else {
            // Android 11- (API 30-)
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )

            // Check if already granted
            if (hasPermissions(permissions)) {
                Timber.d("✅ [BluetoothPermission] Bluetooth permissions already granted (Legacy)")
                callback(true)
                permissionCallback = null
                return
            }

            Timber.d("📱 [BluetoothPermission] Requesting Bluetooth permissions (Legacy)...")
            permissionLauncher.launch(permissions)
        }
    }

    /**
     * Check if Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            hasPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        } else {
            // Android 11- (API 30-)
            hasPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            )
        }
    }

    /**
     * Check if all permissions are granted
     */
    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
