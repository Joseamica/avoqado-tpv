package com.jaac.avoqado_tpv.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * 1.  **Increased Timeout:** The service uses a 20-second timeout, as GPS acquisition on PAX devices can be slow.
 * 2.  **Last Known Location First:** It first attempts to retrieve the `lastKnownLocation`. If this location is recent enough, it's used immediately to save time and battery.
 * 3.  **Fallback to LocationManager:** If the `FusedLocationProviderClient` (Google's API) fails or is unavailable, it automatically falls back to the standard Android `LocationManager`.
 * 4.  **Provider Racing:** The `LocationManager` attempts to get a location from all available providers (`gps`, `network`) simultaneously and uses the first one that returns a result.
 */
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /**
     * Get current GPS location with an aggressive strategy for PAX terminals.
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

        if (!isLocationEnabled()) {
            Timber.w("📍 Location services disabled on device")
            return null
        }

        return withTimeoutOrNull(timeoutMs) {
            // Priority 1: Use FusedLocationProvider if available
            if (isGooglePlayServicesAvailable()) {
                Timber.d("📍 Using FusedLocationProviderClient (Google Play Services available)")

                // First, try to get a recent last known location
                getLastKnownLocation()?.let {
                    Timber.d("📍 Got last known location via Fused: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy})")
                    return@withTimeoutOrNull it
                }

                // If no recent location, request a fresh one
                getFreshLocation()?.let {
                    Timber.d("📍 Got fresh location via Fused: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy})")
                    return@withTimeoutOrNull it
                }
            }

            // Priority 2: Fallback to standard Android LocationManager for PAX or if Fused fails
            Timber.d("📍 Fused provider failed or unavailable. Falling back to Android LocationManager.")
            getLocationFromAndroidManager(timeoutMs)?.let {
                Timber.d("📍 Got location via legacy manager: ${it.latitude}, ${it.longitude} (acc: ${it.accuracy})")
                return@withTimeoutOrNull it
            }

            Timber.w("📍 Failed to get location from any provider within the timeout.")
            null
        }
    }

    /**
     * Fetches location using the standard Android `LocationManager`.
     * This is the fallback for devices without Google Play Services (like PAX).
     */
    @SuppressLint("MissingPermission")
    private suspend fun getLocationFromAndroidManager(timeoutMs: Long): LocationResult? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val providers = locationManager.getProviders(true)
                if (providers.isEmpty()) {
                    Timber.w("📍 No enabled location providers found for LocationManager.")
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                // First, check last known location from all providers
                val bestLastLocation = providers.mapNotNull { locationManager.getLastKnownLocation(it) }
                    .maxByOrNull { it.time }

                if (bestLastLocation != null) {
                    val ageMinutes = (System.currentTimeMillis() - bestLastLocation.time) / 60000
                    if (ageMinutes < 5) { // If location is less than 5 minutes old, use it
                        Timber.d("📍 Using recent last known location from provider: ${bestLastLocation.provider}")
                        continuation.resume(bestLastLocation.toLocationResult())
                        return@suspendCancellableCoroutine
                    }
                }

                // If no recent location, request fresh updates
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) {
                            Timber.d("📍 Fresh location received from ${location.provider}")
                            locationManager.removeUpdates(this)
                            continuation.resume(location.toLocationResult())
                        }
                    }

                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                }

                continuation.invokeOnCancellation {
                    Timber.d("📍 LocationManager request cancelled.")
                    locationManager.removeUpdates(listener)
                }

                try {
                    providers.forEach { provider ->
                        Timber.d("📍 Requesting location updates from $provider")
                        locationManager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "📍 Failed to request location updates from LocationManager.")
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

    private fun Location.toLocationResult() = LocationResult(
        latitude = this.latitude,
        longitude = this.longitude,
        accuracy = this.accuracy
    )
}

data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)
