package com.jaac.avoqado_tpv.core.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * BluetoothCapabilityChecker
 *
 * Utility to verify Bluetooth hardware and software capabilities on PAX terminals.
 *
 * **Purpose**: Before investing in full Bluetooth implementation, verify:
 * 1. Hardware has Bluetooth adapter
 * 2. Bluetooth is enabled
 * 3. App has necessary permissions
 * 4. Can scan for nearby devices
 *
 * **Usage**:
 * ```kotlin
 * val checker = BluetoothCapabilityChecker(context)
 * val report = checker.generateCapabilityReport()
 * Timber.d("Bluetooth Report: $report")
 * ```
 *
 * Based on Stripe Terminal SDK Bluetooth discovery pattern.
 */
class BluetoothCapabilityChecker(private val context: Context) {

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    /**
     * Capability Report
     *
     * Complete status of Bluetooth capabilities on device
     */
    data class BluetoothCapabilityReport(
        val hasBluetoothHardware: Boolean,
        val isBluetoothEnabled: Boolean,
        val bluetoothVersion: String?,
        val hasBleScanPermission: Boolean,
        val hasBleConnectPermission: Boolean,
        val hasLegacyBluetoothPermission: Boolean,
        val hasLegacyBluetoothAdminPermission: Boolean,
        val canScanDevices: Boolean,
        val supportedFeatures: List<String>,
        val recommendations: List<String>
    ) {
        override fun toString(): String {
            return """
                |📱 Bluetooth Capability Report
                |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                |Hardware:
                |  • Has Bluetooth: ${if (hasBluetoothHardware) "✅ YES" else "❌ NO"}
                |  • Bluetooth Enabled: ${if (isBluetoothEnabled) "✅ YES" else "❌ NO"}
                |  • Version: ${bluetoothVersion ?: "Unknown"}
                |
                |Permissions:
                |  • BLUETOOTH_SCAN (Android 12+): ${if (hasBleScanPermission) "✅ Granted" else "❌ Missing"}
                |  • BLUETOOTH_CONNECT (Android 12+): ${if (hasBleConnectPermission) "✅ Granted" else "❌ Missing"}
                |  • BLUETOOTH (Legacy): ${if (hasLegacyBluetoothPermission) "✅ Granted" else "❌ Missing"}
                |  • BLUETOOTH_ADMIN (Legacy): ${if (hasLegacyBluetoothAdminPermission) "✅ Granted" else "❌ Missing"}
                |
                |Capabilities:
                |  • Can Scan Devices: ${if (canScanDevices) "✅ YES" else "❌ NO"}
                |
                |Supported Features:
                |${supportedFeatures.joinToString("\n") { "  • $it" }}
                |
                |${if (recommendations.isNotEmpty()) "⚠️ Recommendations:\n${recommendations.joinToString("\n") { "  • $it" }}" else "✅ All checks passed!"}
                |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """.trimMargin()
        }
    }

    /**
     * Generate complete capability report
     */
    fun generateCapabilityReport(): BluetoothCapabilityReport {
        val hasHardware = hasBluetoothHardware()
        val isEnabled = isBluetoothEnabled()
        val version = getBluetoothVersion()
        val hasScan = hasBluetoothScanPermission()
        val hasConnect = hasBluetoothConnectPermission()
        val hasLegacy = hasLegacyBluetoothPermission()
        val hasAdmin = hasLegacyBluetoothAdminPermission()
        val canScan = hasHardware && isEnabled && (hasScan || hasLegacy)
        val features = getSupportedFeatures()
        val recommendations = generateRecommendations(
            hasHardware, isEnabled, hasScan, hasConnect, hasLegacy, hasAdmin
        )

        return BluetoothCapabilityReport(
            hasBluetoothHardware = hasHardware,
            isBluetoothEnabled = isEnabled,
            bluetoothVersion = version,
            hasBleScanPermission = hasScan,
            hasBleConnectPermission = hasConnect,
            hasLegacyBluetoothPermission = hasLegacy,
            hasLegacyBluetoothAdminPermission = hasAdmin,
            canScanDevices = canScan,
            supportedFeatures = features,
            recommendations = recommendations
        )
    }

    /**
     * Check if device has Bluetooth hardware
     */
    fun hasBluetoothHardware(): Boolean {
        return bluetoothAdapter != null
    }

    /**
     * Check if Bluetooth is currently enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Get Bluetooth version string
     */
    fun getBluetoothVersion(): String? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "5.0+" // Android 12+ supports BT 5.0+
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> "5.0"  // Android 8+ supports BT 5.0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> "4.2"  // Android 6+ supports BT 4.2
            else -> "4.0"
        }
    }

    /**
     * Check if app has BLUETOOTH_SCAN permission (Android 12+)
     */
    fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older Android versions
        }
    }

    /**
     * Check if app has BLUETOOTH_CONNECT permission (Android 12+)
     */
    fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older Android versions
        }
    }

    /**
     * Check if app has legacy BLUETOOTH permission
     */
    fun hasLegacyBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if app has legacy BLUETOOTH_ADMIN permission
     */
    fun hasLegacyBluetoothAdminPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get list of supported Bluetooth features
     */
    fun getSupportedFeatures(): List<String> {
        val features = mutableListOf<String>()
        val adapter = bluetoothAdapter ?: return features

        // Check BLE (Bluetooth Low Energy) support
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            features.add("BLE (Bluetooth Low Energy)")
        }

        // Check classic Bluetooth support
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            features.add("Classic Bluetooth")
        }

        // Check if adapter supports multiple advertisement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (adapter.isMultipleAdvertisementSupported) {
                features.add("Multiple Advertisement")
            }
        }

        // Check offloaded filtering support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (adapter.isOffloadedFilteringSupported) {
                features.add("Offloaded Filtering")
            }
        }

        // Check LE 2M PHY support (Bluetooth 5.0 feature)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (adapter.isLe2MPhySupported) {
                features.add("LE 2M PHY (Bluetooth 5.0)")
            }
        }

        // Check LE Coded PHY support (Bluetooth 5.0 long range)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (adapter.isLeCodedPhySupported) {
                features.add("LE Coded PHY (Long Range)")
            }
        }

        // Check extended advertising support (Bluetooth 5.0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (adapter.isLeExtendedAdvertisingSupported) {
                features.add("Extended Advertising (Bluetooth 5.0)")
            }
        }

        return features
    }

    /**
     * Generate recommendations based on current state
     */
    private fun generateRecommendations(
        hasHardware: Boolean,
        isEnabled: Boolean,
        hasScan: Boolean,
        hasConnect: Boolean,
        hasLegacy: Boolean,
        hasAdmin: Boolean
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (!hasHardware) {
            recommendations.add("⚠️ Device does NOT have Bluetooth hardware - Bluetooth features unavailable")
            return recommendations
        }

        if (!isEnabled) {
            recommendations.add("Enable Bluetooth in device settings")
        }

        // Android 12+ permission recommendations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasScan) {
                recommendations.add("Add <uses-permission android:name=\"android.permission.BLUETOOTH_SCAN\" /> to AndroidManifest.xml")
                recommendations.add("Request BLUETOOTH_SCAN permission at runtime")
            }
            if (!hasConnect) {
                recommendations.add("Add <uses-permission android:name=\"android.permission.BLUETOOTH_CONNECT\" /> to AndroidManifest.xml")
                recommendations.add("Request BLUETOOTH_CONNECT permission at runtime")
            }
        } else {
            // Legacy Android permissions
            if (!hasLegacy) {
                recommendations.add("Add <uses-permission android:name=\"android.permission.BLUETOOTH\" /> to AndroidManifest.xml")
            }
            if (!hasAdmin) {
                recommendations.add("Add <uses-permission android:name=\"android.permission.BLUETOOTH_ADMIN\" /> to AndroidManifest.xml")
            }
        }

        return recommendations
    }

    /**
     * Log complete capability report to Timber
     */
    fun logCapabilityReport() {
        val report = generateCapabilityReport()
        Timber.i(report.toString())
    }
}
