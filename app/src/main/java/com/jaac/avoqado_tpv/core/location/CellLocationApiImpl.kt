package com.jaac.avoqado_tpv.core.location

import com.jaac.avoqado_tpv.core.data.network.ApiService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CellLocationApi that calls our backend.
 * The backend uses Google Geolocation API to convert cell tower + WiFi info to coordinates.
 *
 * Accuracy:
 * - Cell towers only: ~100-1000m
 * - Cell + WiFi: ~20-50m (MUCH BETTER!)
 */
@Singleton
class CellLocationApiImpl @Inject constructor(
    private val apiService: ApiService
) : CellLocationApi {

    override suspend fun getLocationFromCellTowers(
        cellTowers: List<CellTowerInfo>,
        wifiAccessPoints: List<WifiAccessPointInfo>
    ): LocationResult? {
        return try {
            val request = NetworkLocationRequest(
                cellTowers = cellTowers.map { tower ->
                    CellTowerRequest(
                        radioType = tower.radioType,
                        mobileCountryCode = tower.mobileCountryCode,
                        mobileNetworkCode = tower.mobileNetworkCode,
                        locationAreaCode = tower.locationAreaCode,
                        cellId = tower.cellId
                    )
                },
                wifiAccessPoints = wifiAccessPoints.map { wifi ->
                    WifiAccessPointRequest(
                        macAddress = wifi.macAddress,
                        signalStrength = wifi.signalStrength,
                        channel = wifi.channel
                    )
                }
            )

            val response = apiService.getLocationFromCellTowers(request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                LocationResult(
                    latitude = body.latitude,
                    longitude = body.longitude,
                    accuracy = body.accuracy
                )
            } else {
                Timber.e("📍 Network location API error: ${response.code()} - ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "📍 Network location API failed")
            null
        }
    }
}

/**
 * Request body for network location API (cell towers + WiFi).
 */
data class NetworkLocationRequest(
    val cellTowers: List<CellTowerRequest>,
    val wifiAccessPoints: List<WifiAccessPointRequest> = emptyList()
)

data class CellTowerRequest(
    val radioType: String,
    val mobileCountryCode: Int,
    val mobileNetworkCode: Int,
    val locationAreaCode: Int,
    val cellId: Long
)

data class WifiAccessPointRequest(
    val macAddress: String,
    val signalStrength: Int,
    val channel: Int
)

/**
 * Response from network location API.
 */
data class CellLocationResponse(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)
