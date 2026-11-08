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
import java.io.IOException

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
 * 5. 🌐 Network error / timeout / 5xx during refresh → KEEPS the session and returns null
 *    (sin red el cajero sigue vendiendo; sólo un 401/403 explícito del refresh cierra la sesión)
 * 6. 🧩 ValidationError local (sin refresh token / sin venue) → cierra y avisa: no hay red de por
 *    medio y nunca podrá renovarse, así que cuenta como sesión muerta
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
        verify(exactly = 1) { sessionManager.notifySessionExpired() }
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
        verify(exactly = 1) { sessionManager.notifySessionExpired() }
    }

    /**
     * Test 5: la sesión sobrevive cuando el refresh NO es un 401/403 explícito
     *
     * **Given:** 401 en una petición normal y el refresh falla por la red (NetworkError,
     *            HttpError 5xx o una excepción)
     * **When:** Authenticate es llamado
     * **Then:** Devuelve null SIN borrar la sesión ni avisar "sesión expirada": sin red el
     *           cajero no puede volver a entrar con PIN (el login necesita red), así que
     *           borrarla dejaría el aparato sin poder vender. El letrero "Verificando
     *           sesión..." sí se apaga.
     */
    @Test
    fun `authenticate conserva la sesion si el refresh devuelve NetworkError`() = runBlocking {
        every { secureStorage.getToken() } returns oldToken
        coEvery { authRepository.refreshAccessToken() } returns Result.Error(ApiException.NetworkError(IOException("sin red")))

        val newRequest = authenticator.authenticate(null, create401Response(createRequest(oldToken)))

        assertNull("Expected null, got request", newRequest)
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }
        verify(exactly = 0) { secureStorage.clearSession() }
        verify(exactly = 0) { sessionManager.notifySessionExpired() }
        verify(atLeast = 1) { sessionManager.resetSessionExpiringState() }
    }

    @Test
    fun `authenticate conserva la sesion si el refresh devuelve HttpError 503`() = runBlocking {
        every { secureStorage.getToken() } returns oldToken
        coEvery { authRepository.refreshAccessToken() } returns Result.Error(ApiException.HttpError(503, "Service Unavailable"))

        val newRequest = authenticator.authenticate(null, create401Response(createRequest(oldToken)))

        assertNull("Expected null, got request", newRequest)
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }
        verify(exactly = 0) { secureStorage.clearSession() }
        verify(exactly = 0) { sessionManager.notifySessionExpired() }
        verify(atLeast = 1) { sessionManager.resetSessionExpiringState() }
    }

    @Test
    fun `authenticate conserva la sesion si el refresh lanza`() = runBlocking {
        every { secureStorage.getToken() } returns oldToken
        coEvery { authRepository.refreshAccessToken() } throws Exception("Network error")

        val newRequest = authenticator.authenticate(null, create401Response(createRequest(oldToken)))

        assertNull("Expected null, got request", newRequest)
        verify(exactly = 0) { secureStorage.clearSession() }
        verify(exactly = 0) { sessionManager.notifySessionExpired() }
        verify(atLeast = 1) { sessionManager.resetSessionExpiringState() }
    }

    /**
     * Test 6: No token in storage (not authenticated)
     *
     * **Given:** 401 response but no token in storage
     * **When:** Authenticate is called
     * **Then:** Returns null — refresh is attempted but fails, session is cleared
     *
     * Note: The actual code does NOT short-circuit when token is null.
     * It still attempts refresh; AuthRepository answers with a LOCAL ValidationError
     * ("No refresh token available") — never an HttpError — and that counts as a dead
     * session: the session is cleared and the UI is told.
     */
    @Test
    fun `authenticate returns null when no token in storage`() = runBlocking {
        // Given: No token in storage
        every { secureStorage.getToken() } returns null

        // Given: Refresh will fail (relaxed mock returns non-Success Result)
        val error = ApiException.ValidationError("No refresh token available. Please login again.")
        coEvery { authRepository.refreshAccessToken() } returns Result.Error(error)

        // Given: 401 response
        val failedRequest = createRequest("")
        val failedResponse = create401Response(failedRequest)

        // When: Authenticate is called
        val newRequest = authenticator.authenticate(null, failedResponse)

        // Then: Null is returned (refresh failed)
        assertNull("Expected null, got request", newRequest)

        // Then: Token refresh WAS called (code doesn't short-circuit on null token)
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }

        // Then: Session was cleared and the UI told (refresh failed → force logout)
        verify(exactly = 1) { secureStorage.clearSession() }
        verify(exactly = 1) { sessionManager.notifySessionExpired() }
    }

    /**
     * ValidationError local (token vencido pero sin venue ID guardado): no hay red de por medio y
     * el refresh nunca podrá resolverse, así que cierra la sesión y avisa. Si no, el aparato
     * quedaría con el token vencido, cada llamada daría 401 y nunca llegaría al PIN.
     */
    @Test
    fun `authenticate cierra y avisa si el refresh devuelve ValidationError`() = runBlocking {
        every { secureStorage.getToken() } returns oldToken
        coEvery { authRepository.refreshAccessToken() } returns
            Result.Error(ApiException.ValidationError("No venue ID found. Please login again."))

        val newRequest = authenticator.authenticate(null, create401Response(createRequest(oldToken)))

        assertNull("Expected null, got request", newRequest)
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }
        verify(exactly = 1) { secureStorage.clearSession() }
        verify(exactly = 1) { sessionManager.notifySessionExpired() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helper functions
    // ═════════════════════════════════════════════════════════════════════════

    // ===== La llave de órdenes rechazada no es una sesión vencida =====

    private fun rechazoDeLaLlave(ruta: String, conLlave: Boolean = true): Response {
        val request = Request.Builder()
            .url("https://api.avoqado.io/api/v1/$ruta")
            .header("Authorization", "Bearer $oldToken")
            .apply { if (conLlave) header("X-TPV-Command-Token", "a".repeat(64)) }
            .build()
        return create401Response(request)
    }

    @Test
    fun `una llave de ordenes rechazada no renueva la sesion ni enciende el letrero`() {
        every { secureStorage.getToken() } returns oldToken

        listOf("tpv/commands/ready", "tpv/commands/permit", "tpv/command-ack").forEach { ruta ->
            assertNull(ruta, authenticator.authenticate(null, rechazoDeLaLlave(ruta)))
        }

        coVerify(exactly = 0) { authRepository.refreshAccessToken() }
        verify(exactly = 0) { sessionManager.notifySessionVerifying() }
        verify(exactly = 0) { secureStorage.clearSession() }
    }

    @Test
    fun `sin llave de ordenes el 401 de esas rutas si es la sesion del cajero y se renueva`() {
        coEvery { authRepository.refreshAccessToken() } returns
            Result.Success(RefreshTokenResponse(accessToken = newToken, expiresIn = 3600, tokenType = "Bearer"))
        every { secureStorage.getToken() } returnsMany listOf(oldToken, newToken, newToken)

        val reintento = authenticator.authenticate(null, rechazoDeLaLlave("tpv/commands/ready", conLlave = false))

        assertEquals("Bearer $newToken", reintento?.header("Authorization"))
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }
    }

    /**
     * Create a test request with Authorization header
     */
    private val overridesUrl = "https://api.avoqado.io/api/v1/mobile/venues/venue-123/permission-overrides"

    private fun response401(body: String) = Response.Builder()
        .request(Request.Builder().url(overridesUrl).header("Authorization", "Bearer $oldToken").build())
        .protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
        .body(body.toResponseBody("application/json".toMediaTypeOrNull())).build()

    @Test
    fun `401 OVERRIDE_INVALID_PIN de permission-overrides no renueva ni reenvia`() {
        every { secureStorage.getToken() } returns oldToken
        val resp = response401("""{"code":"OVERRIDE_INVALID_PIN","message":"x"}""")
        assertNull(authenticator.authenticate(null, resp))
        coVerify(exactly = 0) { authRepository.refreshAccessToken() }
        verify(exactly = 0) { sessionManager.notifySessionVerifying() }
        assertEquals("""{"code":"OVERRIDE_INVALID_PIN","message":"x"}""", resp.body!!.string())  // el repositorio aun lo puede leer
    }

    @Test
    fun `401 sin code de permission-overrides si renueva`() {
        every { secureStorage.getToken() } returnsMany listOf(oldToken, newToken, newToken)
        coEvery { authRepository.refreshAccessToken() } returns Result.Success(RefreshTokenResponse(accessToken = newToken, expiresIn = 3600, tokenType = "Bearer"))
        val nueva = authenticator.authenticate(null, response401("""{"message":"jwt expired"}"""))
        assertEquals("Bearer $newToken", nueva!!.header("Authorization"))
        coVerify(exactly = 1) { authRepository.refreshAccessToken() }
    }

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
