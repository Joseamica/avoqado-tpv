package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.authentication.domain.models.RefreshTokenResponse
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TokenAuthenticator
 *
 * Tests automatic token refresh when 401 Unauthorized is received.
 *
 * **Test Scenarios:**
 * 1. ✅ Token refresh succeeds → Returns new request with updated token
 * 2. ❌ Token refresh fails → Returns null (gives up)
 * 3. 🔄 Token already refreshed by another thread → Reuses new token
 * 4. 🔐 Refresh token expired → Clears session and returns null
 * 5. 🌐 Network error during refresh → Clears session and returns null
 */
class TokenAuthenticatorTest {

    // Mocks
    private lateinit var secureStorage: SecureStorage
    private lateinit var authRepository: AuthRepository
    private lateinit var authRepositoryLazy: Lazy<AuthRepository>
    private lateinit var sessionManager: SessionManager
    private lateinit var authenticator: TokenAuthenticator

    // Test data
    private val oldToken = "old_token_123"
    private val newToken = "new_token_456"
    private val testUrl = "https://api.avoqado.io/api/v1/tpv/venues/venue-123/fast"

    @Before
    fun setup() {
        // Create mocks
        secureStorage = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        authRepositoryLazy = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)

        // Configure lazy to return mocked repository
        every { authRepositoryLazy.get() } returns authRepository

        // Create authenticator
        authenticator = TokenAuthenticator(secureStorage, authRepositoryLazy, sessionManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test 1: Token refresh succeeds
     *
     * **Given:** 401 response with expired token
     * **When:** Token refresh succeeds with new token
     * **Then:** Returns new request with updated Authorization header
     */
    @Test
    fun `authenticate returns new request when token refresh succeeds`() = runBlocking {
        // Given: Old token in storage
        every { secureStorage.getToken() } returns oldToken

        // Given: Token refresh succeeds
        val refreshResponse = RefreshTokenResponse(
            accessToken = newToken,
            expiresIn = 3600,
            tokenType = "Bearer"
        )
        coEvery { authRepository.refreshAccessToken() } returns Result.Success(refreshResponse)

        // Given: New token saved to storage
        every { secureStorage.getToken() } returnsMany listOf(oldToken, newToken, newToken)

        // Given: 401 response
        val failedRequest = createRequest(oldToken)
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: New request is returned
        assertNotNull("Expected new request, got null", newRequest)

        // Then: New request has updated token
        val authHeader = newRequest!!.header("Authorization")
        assertEquals("Expected new token in Authorization header", "Bearer $newToken", authHeader)

        // Then: Token refresh was called
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }

        // Then: Session was NOT cleared (refresh succeeded)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    /**
     * Test 2: Token refresh fails
     *
     * **Given:** 401 response with expired token
     * **When:** Token refresh fails (refresh token expired)
     * **Then:** Returns null and clears session
     */
    @Test
    fun `authenticate returns null when token refresh fails`() = runBlocking {
        // Given: Old token in storage
        every { secureStorage.getToken() } returns oldToken

        // Given: Token refresh fails
        val error = ApiException.HttpError(403, "Refresh token expired")
        coEvery { authRepository.refreshAccessToken() } returns Result.Error(error)

        // Given: 401 response
        val failedRequest = createRequest(oldToken)
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: Null is returned (give up)
        assertNull("Expected null, got request", newRequest)

        // Then: Token refresh was called
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }

        // Then: Session was cleared (force logout)
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    /**
     * Test 3: Token already refreshed by another thread
     *
     * **Given:** 401 response with expired token
     * **When:** Another thread already refreshed the token
     * **Then:** Returns new request with updated token WITHOUT calling refresh again
     */
    @Test
    fun `authenticate reuses token when already refreshed by another thread`() = runBlocking {
        // Given: Request has old token, but storage has new token (another thread refreshed)
        every { secureStorage.getToken() } returns newToken

        // Given: 401 response with old token
        val failedRequest = createRequest(oldToken)
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: New request is returned
        assertNotNull("Expected new request, got null", newRequest)

        // Then: New request has updated token
        val authHeader = newRequest!!.header("Authorization")
        assertEquals("Expected new token in Authorization header", "Bearer $newToken", authHeader)

        // Then: Token refresh was NOT called (reused existing token)
        coVerify(exactly = 0) { authRepository.refreshAccessToken() }

        // Then: Session was NOT cleared
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    /**
     * Test 4: Refresh token expired (403 from refresh endpoint)
     *
     * **Given:** 401 response with expired token
     * **When:** Token refresh fails with 403 (refresh token invalid)
     * **Then:** Returns null and clears session
     */
    @Test
    fun `authenticate clears session when refresh token is invalid`() = runBlocking {
        // Given: Old token in storage
        every { secureStorage.getToken() } returns oldToken

        // Given: Refresh token is invalid (403)
        val error = ApiException.HttpError(403, "Refresh token invalid")
        coEvery { authRepository.refreshAccessToken() } returns Result.Error(error)

        // Given: 401 response
        val failedRequest = createRequest(oldToken)
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: Null is returned (force logout)
        assertNull("Expected null, got request", newRequest)

        // Then: Session was cleared
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    /**
     * Test 5: Network error during token refresh
     *
     * **Given:** 401 response with expired token
     * **When:** Token refresh throws network exception
     * **Then:** Returns null and clears session
     */
    @Test
    fun `authenticate handles network error during refresh`() = runBlocking {
        // Given: Old token in storage
        every { secureStorage.getToken() } returns oldToken

        // Given: Network error during refresh
        coEvery { authRepository.refreshAccessToken() } throws Exception("Network error")

        // Given: 401 response
        val failedRequest = createRequest(oldToken)
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: Null is returned (give up)
        assertNull("Expected null, got request", newRequest)

        // Then: Session was cleared
        verify(exactly = 1) { secureStorage.clearSession() }
    }

    /**
     * Test 6: No token in storage (not authenticated)
     *
     * **Given:** 401 response but no token in storage
     * **When:** Authenticate is called
     * **Then:** Returns null (not authenticated, can't refresh)
     */
    @Test
    fun `authenticate returns null when no token in storage`() = runBlocking {
        // Given: No token in storage
        every { secureStorage.getToken() } returns null

        // Given: 401 response
        val failedRequest = createRequest("")
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: Null is returned (can't refresh without token)
        assertNull("Expected null, got request", newRequest)

        // Then: Token refresh was NOT called (no token to refresh)
        coVerify(exactly = 0) { authRepository.refreshAccessToken() }

        // Then: Session was NOT cleared (nothing to clear)
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helper functions
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Create a test request with Authorization header
     */
    private fun createRequest(token: String): Request {
        return Request.Builder()
            .url(testUrl)
            .header("Authorization", "Bearer $token")
            .build()
    }

    /**
     * Create a 401 Unauthorized response
     */
    private fun create401Response(request: Request): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody("text/plain".toMediaTypeOrNull()))
            .build()
    }
}
