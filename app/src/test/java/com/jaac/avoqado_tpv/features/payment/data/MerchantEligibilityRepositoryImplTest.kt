package com.jaac.avoqado_tpv.features.payment.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantCircuitBreakerDto
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantEligibilityData
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantEligibilityItem
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantEligibilityResponse
import com.jaac.avoqado_tpv.core.location.LocationResult
import com.jaac.avoqado_tpv.core.location.LocationService
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.math.BigDecimal

/**
 * Unit tests for [MerchantEligibilityRepositoryImpl] (MERCHANT_ROUTING_RULES).
 *
 * Covers: server gating (feature off → all), eligible filtering, fallback, the local circuit
 * breaker (trip after threshold, reset on success/cooldown, breaker-empties-set → fallback),
 * and fail-open on any network/HTTP failure. Money asserted in PESOS (major units).
 */
class MerchantEligibilityRepositoryImplTest {

    private lateinit var apiService: ApiService
    private lateinit var locationService: LocationService
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var repo: MerchantEligibilityRepositoryImpl

    @Before
    fun setup() {
        apiService = mockk()
        locationService = mockk()
        deviceInfoManager = mockk()
        every { deviceInfoManager.getVenueId() } returns "venue_1"
        every { deviceInfoManager.getSerialNumber() } returns "AVQD-1"
        // Default: no location (geofence rules then simply fail — not under test here)
        coEvery { locationService.getCurrentLocation(any()) } returns null
        repo = MerchantEligibilityRepositoryImpl(apiService, locationService, deviceInfoManager)
    }

    private fun ok(data: MerchantEligibilityData) =
        Response.success(MerchantEligibilityResponse(success = true, data = data))

    private fun item(id: String, eligible: Boolean, reasons: List<String> = emptyList(), cb: MerchantCircuitBreakerDto? = null) =
        MerchantEligibilityItem(merchantAccountId = id, eligible = eligible, reasons = reasons, circuitBreaker = cb)

    @Test
    fun `no venueId - fail-open disabled`() = runTest {
        every { deviceInfoManager.getVenueId() } returns null
        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(result.shouldShowAll).isTrue()
        assertThat(result.evaluated).isFalse()
        assertThat(result.routingFeatureActive).isFalse()
    }

    @Test
    fun `feature off - all eligible, no filtering, no banner`() = runTest {
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns ok(
            MerchantEligibilityData(
                routingFeatureActive = false,
                merchants = listOf(item("ma_A", true), item("ma_B", true)),
            )
        )
        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(result.routingFeatureActive).isFalse()
        assertThat(result.shouldShowAll).isTrue()
        assertThat(result.showFallbackBanner).isFalse()
        assertThat(result.eligibleMerchantAccountIds).containsExactly("ma_A", "ma_B")
    }

    @Test
    fun `feature on - filters to eligible and passes through auto-select`() = runTest {
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns ok(
            MerchantEligibilityData(
                routingFeatureActive = true,
                merchants = listOf(item("ma_A", true), item("ma_B", false, listOf("SCHEDULE"))),
                autoSelectMerchantAccountId = "ma_A",
            )
        )
        val result = repo.evaluate(BigDecimal("250.00"), staffId = "staff_1")
        assertThat(result.shouldShowAll).isFalse()
        assertThat(result.eligibleMerchantAccountIds).containsExactly("ma_A")
        assertThat(result.autoSelectMerchantAccountId).isEqualTo("ma_A")
        assertThat(result.fallbackAll).isFalse()
    }

    @Test
    fun `server fallbackAll - show all with banner`() = runTest {
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns ok(
            MerchantEligibilityData(
                routingFeatureActive = true,
                merchants = listOf(item("ma_A", true), item("ma_B", true)), // eligible=true in fallback (server marks all)
                fallbackAll = true,
            )
        )
        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(result.fallbackAll).isTrue()
        assertThat(result.shouldShowAll).isTrue()
        assertThat(result.showFallbackBanner).isTrue()
        assertThat(result.autoSelectMerchantAccountId).isNull()
    }

    @Test
    fun `network exception - fail-open with banner`() = runTest {
        coEvery { apiService.getMerchantEligibility(any(), any()) } throws java.io.IOException("offline")
        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(result.evaluated).isFalse()
        assertThat(result.shouldShowAll).isTrue()
        assertThat(result.showFallbackBanner).isTrue() // routingFeatureActive=true on a failed real call
    }

    @Test
    fun `http error - fail-open`() = runTest {
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns
            Response.error(500, "".toResponseBody(null))
        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(result.evaluated).isFalse()
        assertThat(result.shouldShowAll).isTrue()
    }

    @Test
    fun `circuit breaker trips after threshold then hides merchant`() = runTest {
        val cb = MerchantCircuitBreakerDto(consecutiveFailures = 2, cooldownMinutes = 15)
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns ok(
            MerchantEligibilityData(
                routingFeatureActive = true,
                merchants = listOf(item("ma_A", true, cb = cb), item("ma_B", true)),
            )
        )

        // First eval learns the breaker config; both eligible.
        val first = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(first.eligibleMerchantAccountIds).containsExactly("ma_A", "ma_B")

        // Two consecutive technical failures on ma_A trip its breaker.
        repo.recordChargeFailure("ma_A")
        repo.recordChargeFailure("ma_A")

        val afterTrip = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(afterTrip.eligibleMerchantAccountIds).containsExactly("ma_B")
        assertThat(afterTrip.autoSelectMerchantAccountId).isEqualTo("ma_B") // only one left → auto-select
    }

    @Test
    fun `circuit breaker resets on success`() = runTest {
        val cb = MerchantCircuitBreakerDto(consecutiveFailures = 2, cooldownMinutes = 15)
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns ok(
            MerchantEligibilityData(
                routingFeatureActive = true,
                merchants = listOf(item("ma_A", true, cb = cb), item("ma_B", true)),
            )
        )
        repo.evaluate(BigDecimal("100.00"), staffId = null) // learn config
        repo.recordChargeFailure("ma_A") // 1 fail (below threshold)
        repo.recordChargeSuccess("ma_A") // reset
        repo.recordChargeFailure("ma_A") // 1 fail again — still below threshold

        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        assertThat(result.eligibleMerchantAccountIds).contains("ma_A") // not tripped
    }

    @Test
    fun `breaker empties eligible set - becomes fallback show-all`() = runTest {
        val cb = MerchantCircuitBreakerDto(consecutiveFailures = 1, cooldownMinutes = 15)
        coEvery { apiService.getMerchantEligibility(any(), any()) } returns ok(
            MerchantEligibilityData(
                routingFeatureActive = true,
                // Only ma_A is server-eligible; ma_B excluded by a rule.
                merchants = listOf(item("ma_A", true, cb = cb), item("ma_B", false, listOf("SCHEDULE"))),
                autoSelectMerchantAccountId = "ma_A",
            )
        )
        repo.evaluate(BigDecimal("100.00"), staffId = null) // learn config
        repo.recordChargeFailure("ma_A") // trips ma_A (threshold 1)

        val result = repo.evaluate(BigDecimal("100.00"), staffId = null)
        // Breaker knocked out the only eligible merchant → never block → show all + banner.
        assertThat(result.fallbackAll).isTrue()
        assertThat(result.shouldShowAll).isTrue()
        assertThat(result.eligibleMerchantAccountIds).containsExactly("ma_A", "ma_B")
        assertThat(result.autoSelectMerchantAccountId).isNull()
    }

    @Test
    fun `sends amount in pesos and location when available`() = runTest {
        coEvery { locationService.getCurrentLocation(any()) } returns LocationResult(19.4326, -99.1332, 5f)
        val slot = mutableListOf<com.jaac.avoqado_tpv.core.data.network.dto.MerchantEligibilityRequest>()
        coEvery { apiService.getMerchantEligibility(any(), capture(slot)) } returns ok(
            MerchantEligibilityData(routingFeatureActive = false, merchants = listOf(item("ma_A", true)))
        )
        repo.evaluate(BigDecimal("250.50"), staffId = "staff_9")
        val sent = slot.first()
        assertThat(sent.amount).isEqualTo(250.50) // PESOS, not cents
        assertThat(sent.staffId).isEqualTo("staff_9")
        assertThat(sent.lat).isEqualTo(19.4326)
        assertThat(sent.lng).isEqualTo(-99.1332)
        assertThat(sent.terminalSerial).isEqualTo("AVQD-1")
    }
}
