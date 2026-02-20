package com.jaac.avoqado_tpv.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * LocationService - Handles GPS location capture for clock-in/out.
 *
 * **Purpose:**
 * Captures GPS coordinates when employees clock in/out at venues that
 * require location tracking (configured via VenueModule.config.attendance.requireClockInGps).
 *
 * **Strategy for PAX Devices (No Google Play Services):**
 * 1.  **FusedLocationProvider:** Try Google's API first (if available)
 * 2.  **Android LocationManager:** Fall back to standard GPS (requires outdoor/satellite visibility)
 * 3.  **Cell ID Location:** Fall back to cell tower triangulation (works INDOORS with SIM card)
 *      - Gets MCC, MNC, LAC, CID from TelephonyManager
 *      - Calls backend API to convert to coordinates (uses Google Geolocation API)
 *      - Accuracy: ~100-1000m (enough to verify "at store")
 */
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cellLocationApi: CellLocationApi
) {
    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    companion object {
        /**
         * Maximum acceptable accuracy in meters.
         * Locations with accuracy worse than this are rejected.
         * - GPS: 5-20m
         * - Cell + WiFi: 20-50m
         * - Cell only: 100-1000m
         * - IP-based fallback: 2,000,000+ meters (GARBAGE — must reject!)
         */
        private const val MAX_ACCURACY_METERS = 1000f
    }

    /**
     * Get current location using the best available strategy for PAX terminals.
     *
     * **Strategy for PAX (No Google Play Services, often indoors):**
     * - Priority 1: Network Location API (Cell ID + WiFi) - INSTANT, works INDOORS
     * - Priority 2: GPS fallback (only if network location fails)
     *
     * All results are validated against [MAX_ACCURACY_METERS] threshold.
     *
     * @param timeoutMs Timeout for the entire operation.
     * @return A [LocationResult] or null if no location could be obtained.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(timeoutMs: Long = 20_000): LocationResult? {
        if (!hasLocationPermission()) {
            Timber.w("📍 Location permission not granted")
            return null
        }

        return withTimeoutOrNull(timeoutMs) {
            // Priority 1: Network Location API (Cell ID + WiFi) - INSTANT, works INDOORS
            // This is the primary method for PAX devices since:
            // - PAX has no Google Play Services
            // - Clock-in usually happens INDOORS where GPS doesn't work
            // - Cell ID + WiFi is instant (no 30-60s GPS cold start wait)
            Timber.d("📍 Using Network Location API (Cell ID + WiFi)...")
            getLocationFromCellId()?.let {
                if (it.accuracy <= MAX_ACCURACY_METERS) {
                    Timber.i("📍 ✅ Got location via Network API: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy}m)")
                    return@withTimeoutOrNull it
                } else {
                    Timber.w("📍 ❌ Network API accuracy too poor: ${it.accuracy}m > ${MAX_ACCURACY_METERS}m threshold — falling back to GPS")
                }
            }

            // Priority 2: GPS fallback (if network location fails or accuracy too poor)
            Timber.d("📍 Attempting GPS fallback...")

            if (!isLocationEnabled()) {
                Timber.w("📍 Location services disabled on device")
                return@withTimeoutOrNull null
            }

            // Try FusedLocationProvider if available
            if (isGooglePlayServicesAvailable()) {
                getLastKnownLocation()?.let {
                    if (it.accuracy <= MAX_ACCURACY_METERS) {
                        Timber.d("📍 Got last known location via Fused: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy}m)")
                        return@withTimeoutOrNull it
                    } else {
                        Timber.w("📍 ❌ Fused last known accuracy too poor: ${it.accuracy}m — skipping")
                    }
                }
                getFreshLocation()?.let {
                    if (it.accuracy <= MAX_ACCURACY_METERS) {
                        Timber.d("📍 Got fresh location via Fused: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy}m)")
                        return@withTimeoutOrNull it
                    } else {
                        Timber.w("📍 ❌ Fused fresh accuracy too poor: ${it.accuracy}m — skipping")
                    }
                }
            }

            // Try Android LocationManager (GPS)
            getLocationFromAndroidManager(timeoutMs / 2)?.let {
                if (it.accuracy <= MAX_ACCURACY_METERS) {
                    Timber.d("📍 Got location via GPS: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy}m)")
                    return@withTimeoutOrNull it
                } else {
                    Timber.w("📍 ❌ GPS accuracy too poor: ${it.accuracy}m — rejecting")
                }
            }

            Timber.w("📍 Failed to get location with acceptable accuracy from any provider within the timeout.")
            null
        }
    }

    /**
     * Fetches location using the standard Android `LocationManager`.
     * This is the fallback for devices without Google Play Services (like PAX).
     *
     * **IMPORTANT for PAX devices:**
     * - Use ONLY GPS_PROVIDER and NETWORK_PROVIDER explicitly
     * - Do NOT use getProviders(true) which returns phantom providers like "fused"
     * - NETWORK_PROVIDER uses cell towers/WiFi and works indoors
     * - GPS_PROVIDER needs satellite visibility (outdoors)
     */
    @SuppressLint("MissingPermission")
    private suspend fun getLocationFromAndroidManager(timeoutMs: Long): LocationResult? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                // CRITICAL: Use explicit providers, NOT getProviders(true)
                // getProviders(true) returns phantom providers like "fused" on PAX
                val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                Timber.d("📍 Provider status: GPS=$gpsEnabled, NETWORK=$networkEnabled")

                val providers = buildList {
                    if (gpsEnabled) add(LocationManager.GPS_PROVIDER)
                    if (networkEnabled) add(LocationManager.NETWORK_PROVIDER)
                }

                if (providers.isEmpty()) {
                    Timber.w("📍 No real location providers enabled (GPS and NETWORK both disabled)")
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                // First, check last known location from real providers
                Timber.d("📍 Checking last known location from ${providers.size} providers...")
                providers.forEach { provider ->
                    val lastLoc = locationManager.getLastKnownLocation(provider)
                    if (lastLoc != null) {
                        val ageMinutes = (System.currentTimeMillis() - lastLoc.time) / 60000
                        Timber.d("📍 Last known from $provider: age=${ageMinutes}min, lat=${lastLoc.latitude}, lon=${lastLoc.longitude}")
                    } else {
                        Timber.d("📍 No last known location from $provider")
                    }
                }

                val bestLastLocation = providers.mapNotNull { locationManager.getLastKnownLocation(it) }
                    .maxByOrNull { it.time }

                if (bestLastLocation != null) {
                    val ageMinutes = (System.currentTimeMillis() - bestLastLocation.time) / 60000
                    if (ageMinutes < 5) { // If location is less than 5 minutes old, use it
                        Timber.d("📍 Using recent last known location from provider: ${bestLastLocation.provider} (age: ${ageMinutes}min)")
                        continuation.resume(bestLastLocation.toLocationResult())
                        return@suspendCancellableCoroutine
                    } else {
                        Timber.d("📍 Last known location too old (${ageMinutes}min), requesting fresh location...")
                    }
                }

                // If no recent location, request fresh updates from real providers
                Timber.d("📍 Requesting fresh location updates from ${providers.joinToString()}...")

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) {
                            Timber.i("📍 ✅ Fresh location received from ${location.provider}: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
                            locationManager.removeUpdates(this)
                            continuation.resume(location.toLocationResult())
                        } else {
                            Timber.w("📍 Location received but continuation already cancelled: ${location.provider}")
                        }
                    }

                    override fun onProviderDisabled(provider: String) {
                        Timber.w("📍 Provider disabled during location request: $provider")
                    }

                    override fun onProviderEnabled(provider: String) {
                        Timber.d("📍 Provider enabled during location request: $provider")
                    }

                    @Deprecated("Deprecated in API 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                        Timber.d("📍 Provider status changed: $provider -> $status")
                    }
                }

                continuation.invokeOnCancellation {
                    Timber.d("📍 LocationManager request cancelled (timeout reached)")
                    locationManager.removeUpdates(listener)
                }

                try {
                    providers.forEach { provider ->
                        Timber.d("📍 Registering location listener for $provider")
                        locationManager.requestLocationUpdates(
                            provider,
                            0L,        // minTimeMs - no minimum time between updates
                            0f,        // minDistanceM - no minimum distance
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                    Timber.d("📍 Location listeners registered. Waiting for GPS fix (this may take 10-60 seconds outdoors)...")
                } catch (e: Exception) {
                    Timber.e(e, "📍 Failed to request location updates from LocationManager")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): LocationResult? {
        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()

            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }

            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    continuation.resume(task.result.toLocationResult())
                } else {
                    Timber.e(task.exception, "📍 Fused fresh location failed.")
                    continuation.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): LocationResult? {
        return suspendCancellableCoroutine { continuation ->
            fusedClient.lastLocation.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    val location = task.result
                    val ageMinutes = (System.currentTimeMillis() - location.time) / 60000
                    if (ageMinutes < 5) { // Only accept locations less than 5 mins old
                        continuation.resume(location.toLocationResult())
                    } else {
                        continuation.resume(null)
                    }
                } else {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            val resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            resultCode == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Get location from Cell ID + WiFi (network triangulation).
     * Works INDOORS with SIM card and/or WiFi - no GPS satellite needed!
     *
     * Process:
     * 1. Read cell tower info from TelephonyManager (MCC, MNC, LAC, CID)
     * 2. Scan nearby WiFi access points (BSSID, signal strength)
     * 3. Send both to backend API which calls Google Geolocation API
     * 4. Returns location:
     *    - Cell only: ~100-1000m accuracy
     *    - WiFi + Cell: ~20-50m accuracy (MUCH BETTER!)
     */
    @SuppressLint("MissingPermission")
    private suspend fun getLocationFromCellId(): LocationResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Check if we have location permission
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    Timber.w("📍 Cell ID: Missing location permission")
                    return@withContext null
                }

                val cellTowers = getCellTowerInfo()
                val wifiAccessPoints = getWifiAccessPoints()

                if (cellTowers.isEmpty() && wifiAccessPoints.isEmpty()) {
                    Timber.w("📍 Network location: No cell tower or WiFi info available")
                    return@withContext null
                }

                Timber.d("📍 Network location: Found ${cellTowers.size} cell tower(s) and ${wifiAccessPoints.size} WiFi AP(s), calling API...")

                // Call backend API to convert cell + WiFi info to coordinates
                val result = cellLocationApi.getLocationFromCellTowers(cellTowers, wifiAccessPoints)

                if (result != null) {
                    Timber.i("📍 Network location: API returned: ${result.latitude}, ${result.longitude} (acc: ${result.accuracy}m)")
                }

                result
            } catch (e: Exception) {
                Timber.e(e, "📍 Network location: Failed to get location")
                null
            }
        }
    }

    /**
     * Scan nearby WiFi access points for geolocation.
     * WiFi dramatically improves accuracy (from km to ~20-50m).
     */
    @SuppressLint("MissingPermission")
    private fun getWifiAccessPoints(): List<WifiAccessPointInfo> {
        val wifiAPs = mutableListOf<WifiAccessPointInfo>()

        try {
            if (!wifiManager.isWifiEnabled) {
                Timber.d("📍 WiFi: WiFi is disabled, skipping WiFi scan")
                return emptyList()
            }

            // Get scan results (uses cached results, doesn't trigger new scan)
            val scanResults = wifiManager.scanResults ?: return emptyList()

            for (result in scanResults) {
                // BSSID is the MAC address of the access point
                val macAddress = result.BSSID
                if (macAddress.isNullOrEmpty()) continue

                wifiAPs.add(WifiAccessPointInfo(
                    macAddress = macAddress,
                    signalStrength = result.level, // dBm (e.g., -70)
                    channel = frequencyToChannel(result.frequency)
                ))

                if (wifiAPs.size >= 10) break // Limit to 10 APs (more than enough for accuracy)
            }

            if (wifiAPs.isNotEmpty()) {
                Timber.d("📍 WiFi: Found ${wifiAPs.size} access point(s)")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "📍 WiFi: Security exception - missing permission")
        } catch (e: Exception) {
            Timber.e(e, "📍 WiFi: Failed to get WiFi scan results")
        }

        return wifiAPs
    }

    /**
     * Convert WiFi frequency (MHz) to channel number.
     */
    private fun frequencyToChannel(frequency: Int): Int {
        return when {
            frequency in 2412..2484 -> (frequency - 2412) / 5 + 1  // 2.4 GHz (channels 1-14)
            frequency in 5170..5825 -> (frequency - 5170) / 5 + 34 // 5 GHz
            else -> 0
        }
    }

    /**
     * Extract cell tower information from TelephonyManager.
     * Supports GSM, WCDMA (3G), and LTE (4G) networks.
     */
    @SuppressLint("MissingPermission")
    private fun getCellTowerInfo(): List<CellTowerInfo> {
        val cellTowers = mutableListOf<CellTowerInfo>()

        try {
            val allCellInfo = telephonyManager.allCellInfo ?: return emptyList()

            for (cellInfo in allCellInfo) {
                if (!cellInfo.isRegistered) continue // Only use registered (connected) cells

                when (cellInfo) {
                    is CellInfoLte -> {
                        val identity = cellInfo.cellIdentity
                        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            identity.mccString?.toIntOrNull() ?: identity.mcc
                        } else {
                            @Suppress("DEPRECATION")
                            identity.mcc
                        }
                        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            identity.mncString?.toIntOrNull() ?: identity.mnc
                        } else {
                            @Suppress("DEPRECATION")
                            identity.mnc
                        }

                        if (mcc != Int.MAX_VALUE && mnc != Int.MAX_VALUE &&
                            identity.tac != Int.MAX_VALUE && identity.ci != Int.MAX_VALUE) {
                            cellTowers.add(CellTowerInfo(
                                radioType = "lte",
                                mobileCountryCode = mcc,
                                mobileNetworkCode = mnc,
                                locationAreaCode = identity.tac,
                                cellId = identity.ci.toLong()
                            ))
                            Timber.d("📍 Cell ID: LTE tower - MCC=$mcc, MNC=$mnc, TAC=${identity.tac}, CI=${identity.ci}")
                        }
                    }
                    is CellInfoWcdma -> {
                        val identity = cellInfo.cellIdentity
                        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            identity.mccString?.toIntOrNull() ?: identity.mcc
                        } else {
                            @Suppress("DEPRECATION")
                            identity.mcc
                        }
                        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            identity.mncString?.toIntOrNull() ?: identity.mnc
                        } else {
                            @Suppress("DEPRECATION")
                            identity.mnc
                        }

                        if (mcc != Int.MAX_VALUE && mnc != Int.MAX_VALUE &&
                            identity.lac != Int.MAX_VALUE && identity.cid != Int.MAX_VALUE) {
                            cellTowers.add(CellTowerInfo(
                                radioType = "wcdma",
                                mobileCountryCode = mcc,
                                mobileNetworkCode = mnc,
                                locationAreaCode = identity.lac,
                                cellId = identity.cid.toLong()
                            ))
                            Timber.d("📍 Cell ID: WCDMA tower - MCC=$mcc, MNC=$mnc, LAC=${identity.lac}, CID=${identity.cid}")
                        }
                    }
                    is CellInfoGsm -> {
                        val identity = cellInfo.cellIdentity
                        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            identity.mccString?.toIntOrNull() ?: identity.mcc
                        } else {
                            @Suppress("DEPRECATION")
                            identity.mcc
                        }
                        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            identity.mncString?.toIntOrNull() ?: identity.mnc
                        } else {
                            @Suppress("DEPRECATION")
                            identity.mnc
                        }

                        if (mcc != Int.MAX_VALUE && mnc != Int.MAX_VALUE &&
                            identity.lac != Int.MAX_VALUE && identity.cid != Int.MAX_VALUE) {
                            cellTowers.add(CellTowerInfo(
                                radioType = "gsm",
                                mobileCountryCode = mcc,
                                mobileNetworkCode = mnc,
                                locationAreaCode = identity.lac,
                                cellId = identity.cid.toLong()
                            ))
                            Timber.d("📍 Cell ID: GSM tower - MCC=$mcc, MNC=$mnc, LAC=${identity.lac}, CID=${identity.cid}")
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "📍 Cell ID: Security exception - missing permission")
        } catch (e: Exception) {
            Timber.e(e, "📍 Cell ID: Failed to get cell info")
        }

        return cellTowers
    }

    private fun Location.toLocationResult() = LocationResult(
        latitude = this.latitude,
        longitude = this.longitude,
        accuracy = this.accuracy
    )
}

/**
 * Cell tower information for geolocation API.
 */
data class CellTowerInfo(
    val radioType: String,        // "gsm", "wcdma", "lte"
    val mobileCountryCode: Int,   // MCC (e.g., 334 for Mexico)
    val mobileNetworkCode: Int,   // MNC (e.g., 020 for Telcel)
    val locationAreaCode: Int,    // LAC (GSM/WCDMA) or TAC (LTE)
    val cellId: Long              // Cell ID
)

/**
 * WiFi access point information for geolocation API.
 */
data class WifiAccessPointInfo(
    val macAddress: String,     // BSSID (e.g., "00:11:22:33:44:55")
    val signalStrength: Int,    // Signal strength in dBm (e.g., -70)
    val channel: Int            // WiFi channel number
)

/**
 * Interface for Cell ID + WiFi to coordinates API.
 * Implementation should call backend which uses Google Geolocation API.
 */
interface CellLocationApi {
    suspend fun getLocationFromCellTowers(
        cellTowers: List<CellTowerInfo>,
        wifiAccessPoints: List<WifiAccessPointInfo> = emptyList()
    ): LocationResult?
}

data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)
