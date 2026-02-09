package com.jaac.avoqado_tpv.features.authentication.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.data.dto.AuthResponseDto
import com.jaac.avoqado_tpv.features.authentication.data.dto.RefreshTokenResponseDto
import com.jaac.avoqado_tpv.features.authentication.data.dto.StaffMemberDto
import com.jaac.avoqado_tpv.features.authentication.data.dto.VenueInfoDto
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for AuthRepository
 *
 * Tests PIN login, token refresh, and session management.
 *
 * **Test Scenarios:**
 * 1. ✅ Login with valid PIN → Success, saves session
 * 2. ❌ Login with invalid PIN → Error 401
 * 3. ❌ Login with rate limit → Error 429
 * 4. ❌ Login without device activation → Validation error
 * 5. 🔐 Master TOTP login (8-digit code) → Uses master endpoint
 * 6. 🔄 Token refresh success → Saves new token
 * 7. ❌ Token refresh failure → Clears session
 * 8. 🚪 Logout → Clears all session data
 * 9. ✅ isAuthenticated → Returns true when token exists
 */
class AuthRepositoryTest {

    // Mocks
    private lateinit var apiService: ApiService
    private lateinit var secureStorage: SecureStorage
    private lateinit var tpvSettingsRepository: TpvSettingsRepository
    private lateinit var permissionsRepository: PermissionsRepository
    private lateinit var modulesRepository: ModulesRepository
    private lateinit var authRepository: AuthRepository

    // Test data
    private val testVenueId = "venue-123"
    private val testPin = "1234"
    private val testSerialNumber = "PAX-A910-001"
    private val testAccessToken = "access_token_abc"
    private val testRefreshToken = "refresh_token_xyz"
    private val testStaffId = "staff-456"
    private val testStaffName = "Juan Pérez"

    @Before
    fun setup() {
        // Create mocks
        apiService = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        permissionsRepository = mockk(relaxed = true)
        modulesRepository = mockk(relaxed = true)

        // Default: Device is activated (has serial number)
        every { secureStorage.getSerialNumber() } returns testSerialNumber

        // Create repository
        authRepository = AuthRepository(
            apiService = apiService,
            secureStorage = secureStorage,
            tpvSettingsRepository = tpvSettingsRepository,
            permissionsRepository = permissionsRepository,
            modulesRepository = modulesRepository
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LOGIN TESTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Login with valid PIN succeeds
     *
     * **Given:** Valid PIN and activated device
     * **When:** loginWithPin is called
     * **Then:** Returns success and saves session to SecureStorage
     */
    @Test
    fun `loginWithPin returns success when credentials are valid`() = runTest {
        // Given: Valid auth response from backend
        val authResponse = createAuthResponseDto()
        coEvery { apiService.loginWithPin(testVenueId, any()) } returns Response.success(authResponse)
        coEvery { apiService.getVenueDetails(any()) } returns Response.success(mockk(relaxed = true))

        // When: Login is called
        val result = authRepository.loginWithPin(testPin, testVenueId)

        // Then: Result is success
        assertTrue("Expected success result", result is Result.Success)
        val successResult = result as Result.Success
        assertEquals(testStaffId, successResult.data.staffId)
        assertEquals(testStaffName, successResult.data.staff.displayName)

        // Then: Session was saved to SecureStorage
        verify { secureStorage.saveToken(testAccessToken) }
        verify { secureStorage.saveRefreshToken(testRefreshToken) }
        verify { secureStorage.saveVenueId(testVenueId) }
        verify { secureStorage.saveStaffId(testStaffId) }
        verify { secureStorage.saveStaffName(testStaffName) }

        // Then: TPV settings were refreshed
        coVerify { tpvSettingsRepository.refreshSettings(testVenueId) }
    }

    /**
     * Test 2: Login with invalid PIN fails with 401
     *
     * **Given:** Invalid PIN
     * **When:** loginWithPin is called
     * **Then:** Returns error with "PIN incorrecto" message
     */
    @Test
    fun `loginWithPin returns error when PIN is invalid`() = runTest {
        // Given: 401 response from backend
        val errorBody = """{"message": "PIN incorrecto"}""".toResponseBody("application/json".toMediaTypeOrNull())
        coEvery { apiService.loginWithPin(testVenueId, any()) } returns Response.error(401, errorBody)

        // When: Login is called
        val result = authRepository.loginWithPin("9999", testVenueId)

        // Then: Result is error
        assertTrue("Expected error result", result is Result.Error)

        // Then: Session was NOT saved
        verify(exactly = 0) { secureStorage.saveToken(any()) }
    }

    /**
     * Test 3: Login fails with rate limit (429)
     *
     * **Given:** Too many login attempts
     * **When:** loginWithPin is called
     * **Then:** Returns error with rate limit message
     */
    @Test
    fun `loginWithPin returns error when rate limited`() = runTest {
        // Given: 429 response from backend
        val errorBody = """{"message": "Too many requests"}""".toResponseBody("application/json".toMediaTypeOrNull())
        coEvery { apiService.loginWithPin(testVenueId, any()) } returns Response.error(429, errorBody)

        // When: Login is called
        val result = authRepository.loginWithPin(testPin, testVenueId)

        // Then: Result is error with rate limit message
        assertTrue("Expected error result", result is Result.Error)
        val errorResult = result as Result.Error
        // Backend message "Too many requests" is in customUserMessage, HTTP code in message
        val apiEx = errorResult.exception as? com.jaac.avoqado_tpv.core.domain.models.ApiException
        assertTrue(
            "Expected rate limit: got message='${errorResult.exception.message}' userMessage='${apiEx?.userMessage}'",
            errorResult.exception.message?.contains("429") == true ||
            apiEx?.userMessage?.contains("Too many") == true ||
            apiEx?.userMessage?.contains("Demasiados") == true
        )
    }

    /**
     * Test 4: Login fails when device not activated
     *
     * **Given:** Device has no serial number (not activated)
     * **When:** loginWithPin is called
     * **Then:** Returns validation error
     */
    @Test
    fun `loginWithPin returns error when device not activated`() = runTest {
        // Given: No serial number (device not activated)
        every { secureStorage.getSerialNumber() } returns null

        // When: Login is called
        val result = authRepository.loginWithPin(testPin, testVenueId)

        // Then: Result is error
        assertTrue("Expected error result", result is Result.Error)
        val errorResult = result as Result.Error
        assertTrue(
            "Expected activation error message",
            errorResult.exception.message?.contains("activarse") == true
        )

        // Then: API was NOT called
        coVerify(exactly = 0) { apiService.loginWithPin(any(), any()) }
    }

    /**
     * Test 5: Master TOTP login with 8-digit code
     *
     * **Given:** 8-digit TOTP code (master login)
     * **When:** loginWithPin is called
     * **Then:** Uses master login endpoint instead of regular PIN endpoint
     */
    @Test
    fun `loginWithPin uses master endpoint for 8-digit code`() = runTest {
        // Given: 8-digit TOTP code
        val masterCode = "12345678"
        val authResponse = createAuthResponseDto(isMasterLogin = true)
        coEvery { apiService.masterLogin(testVenueId, any()) } returns Response.success(authResponse)
        coEvery { apiService.getVenueDetails(any()) } returns Response.success(mockk(relaxed = true))

        // When: Login is called with 8-digit code
        val result = authRepository.loginWithPin(masterCode, testVenueId)

        // Then: Master endpoint was called
        coVerify(exactly = 1) { apiService.masterLogin(testVenueId, any()) }
        coVerify(exactly = 0) { apiService.loginWithPin(any(), any()) }

        // Then: Result is success
        assertTrue("Expected success result", result is Result.Success)
    }

    /**
     * Test 6: Network error during login
     *
     * **Given:** Network failure
     * **When:** loginWithPin is called
     * **Then:** Returns network error
     */
    @Test
    fun `loginWithPin returns network error on connection failure`() = runTest {
        // Given: Network error
        coEvery { apiService.loginWithPin(testVenueId, any()) } throws java.net.UnknownHostException("No internet")

        // When: Login is called
        val result = authRepository.loginWithPin(testPin, testVenueId)

        // Then: Result is network error
        assertTrue("Expected error result", result is Result.Error)

        // Then: Session was NOT saved
        verify(exactly = 0) { secureStorage.saveToken(any()) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOKEN REFRESH TESTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Test 7: Token refresh succeeds
     *
     * **Given:** Valid refresh token in storage
     * **When:** refreshAccessToken is called
     * **Then:** Returns success and saves new access token
     */
    @Test
    fun `refreshAccessToken returns success when refresh token is valid`() = runTest {
        // Given: Refresh token in storage
        every { secureStorage.getRefreshToken() } returns testRefreshToken
        every { secureStorage.getVenueId() } returns testVenueId

        // Given: Refresh succeeds
        val newAccessToken = "new_access_token_123"
        val refreshResponse = RefreshTokenResponseDto(
            accessToken = newAccessToken,
            expiresIn = 3600,
            tokenType = "Bearer"
        )
        coEvery { apiService.refreshToken(testVenueId, any()) } returns Response.success(refreshResponse)

        // When: Refresh is called
        val result = authRepository.refreshAccessToken()

        // Then: Result is success
        assertTrue("Expected success result", result is Result.Success)
        val successResult = result as Result.Success
        assertEquals(newAccessToken, successResult.data.accessToken)

        // Then: New token was saved
        verify { secureStorage.saveToken(newAccessToken) }
    }

    /**
     * Test 8: Token refresh fails with 401 (refresh token expired)
     *
     * **Given:** Expired refresh token
     * **When:** refreshAccessToken is called
     * **Then:** Returns error and clears session
     */
    @Test
    fun `refreshAccessToken clears session when refresh token expired`() = runTest {
        // Given: Refresh token in storage
        every { secureStorage.getRefreshToken() } returns testRefreshToken
        every { secureStorage.getVenueId() } returns testVenueId

        // Given: Refresh fails with 401
        val errorBody = "Unauthorized".toResponseBody("text/plain".toMediaTypeOrNull())
        coEvery { apiService.refreshToken(testVenueId, any()) } returns Response.error(401, errorBody)

        // When: Refresh is called
        val result = authRepository.refreshAccessToken()

        // Then: Result is error
        assertTrue("Expected error result", result is Result.Error)

        // Then: Session was cleared
        verify { secureStorage.clearSession() }
    }

    /**
     * Test 9: Token refresh fails when no refresh token available
     *
     * **Given:** No refresh token in storage
     * **When:** refreshAccessToken is called
     * **Then:** Returns validation error
     */
    @Test
    fun `refreshAccessToken returns error when no refresh token available`() = runTest {
        // Given: No refresh token
        every { secureStorage.getRefreshToken() } returns null

        // When: Refresh is called
        val result = authRepository.refreshAccessToken()

        // Then: Result is error
        assertTrue("Expected error result", result is Result.Error)
        val errorResult = result as Result.Error
        assertTrue(
            "Expected 'no refresh token' message",
            errorResult.exception.message?.contains("refresh token") == true
        )

        // Then: API was NOT called
        coVerify(exactly = 0) { apiService.refreshToken(any(), any()) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LOGOUT TESTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Test 10: Logout clears all session data
     *
     * **Given:** User is logged in
     * **When:** logout is called
     * **Then:** Clears session and caches
     */
    @Test
    fun `logout clears session and caches`() {
        // When: Logout is called
        authRepository.logout()

        // Then: Session was cleared
        verify(exactly = 1) { secureStorage.clearSession() }

        // Then: Permissions cache was cleared
        verify(exactly = 1) { permissionsRepository.clearCache() }

        // Then: Modules cache was cleared
        verify(exactly = 1) { modulesRepository.clearCache() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AUTHENTICATION STATE TESTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Test 11: isAuthenticated returns true when token exists
     */
    @Test
    fun `isAuthenticated returns true when token exists`() {
        // Given: Token exists in storage
        every { secureStorage.isAuthenticated() } returns true

        // When: isAuthenticated is called
        val result = authRepository.isAuthenticated()

        // Then: Returns true
        assertTrue("Expected authenticated", result)
    }

    /**
     * Test 12: isAuthenticated returns false when no token
     */
    @Test
    fun `isAuthenticated returns false when no token`() {
        // Given: No token in storage
        every { secureStorage.isAuthenticated() } returns false

        // When: isAuthenticated is called
        val result = authRepository.isAuthenticated()

        // Then: Returns false
        assertFalse("Expected not authenticated", result)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GETTER TESTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Test 13: getVenueId returns value from storage
     */
    @Test
    fun `getVenueId returns value from storage`() {
        // Given: Venue ID in storage
        every { secureStorage.getVenueId() } returns testVenueId

        // When: getVenueId is called
        val result = authRepository.getVenueId()

        // Then: Returns stored value
        assertEquals(testVenueId, result)
    }

    /**
     * Test 14: getRole returns value from storage
     */
    @Test
    fun `getRole returns value from storage`() {
        // Given: Role in storage
        every { secureStorage.getRole() } returns StaffRole.ADMIN

        // When: getRole is called
        val result = authRepository.getRole()

        // Then: Returns stored value
        assertEquals(StaffRole.ADMIN, result)
    }

    /**
     * Test 15: hasPermission delegates to storage
     */
    @Test
    fun `hasPermission returns value from storage`() {
        // Given: Permission exists
        every { secureStorage.hasPermission("payments:create") } returns true

        // When: hasPermission is called
        val result = authRepository.hasPermission("payments:create")

        // Then: Returns true
        assertTrue(result)
    }

    /**
     * Test 16: isMasterSession returns value from storage
     */
    @Test
    fun `isMasterSession returns value from storage`() {
        // Given: Master login flag is true
        every { secureStorage.isMasterLogin() } returns true

        // When: isMasterSession is called
        val result = authRepository.isMasterSession()

        // Then: Returns true
        assertTrue(result)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Create a mock AuthResponseDto for successful login
     */
    private fun createAuthResponseDto(isMasterLogin: Boolean = false): AuthResponseDto {
        return AuthResponseDto(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken,
            expiresIn = 86400,  // 24 hours
            tokenType = "Bearer",
            staffId = testStaffId,
            venueId = testVenueId,
            role = "ADMIN",
            permissions = listOf("payments:create", "orders:read"),
            staff = StaffMemberDto(
                id = testStaffId,
                firstName = "Juan",
                lastName = "Pérez",
                email = "juan@test.com",
                phone = null,
                employeeCode = null,
                photoUrl = null,
                active = true
            ),
            venue = VenueInfoDto(
                id = testVenueId,
                name = "Test Venue",
                slug = "test-venue",
                posType = "avoqado",
                posStatus = "active",
                logo = "https://example.com/logo.png",
                status = "active"
            ),
            correlationId = "corr-123",
            issuedAt = "2024-01-01T00:00:00Z",
            loyaltyActive = false,
            isMasterLogin = isMasterLogin
        )
    }
}
