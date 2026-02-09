package com.jaac.avoqado_tpv.core.data.realtime

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import io.mockk.*
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URI

/**
 * SocketManagerTest
 *
 * Unit tests for SocketManager core functionality.
 *
 * Tests:
 * - Connection lifecycle (connect, disconnect, reconnect)
 * - Event parsing (JSON → SocketEvent)
 * - Room management (join, leave)
 * - Error handling
 * - StateFlow emissions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocketManagerTest {

    // Mock Socket.IO objects
    private lateinit var mockSocket: Socket
    private lateinit var mockSecureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage
    private lateinit var socketManager: SocketManager

    // Captured listeners for simulating server events
    private val capturedListeners = mutableMapOf<String, Emitter.Listener>()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        // Clear all mocks
        clearAllMocks()

        // Mock Socket.IO Socket
        mockSocket = mockk(relaxed = true)
        mockSecureStorage = mockk(relaxed = true)

        // Capture all event listeners when socket.on() is called
        every { mockSocket.on(any(), any()) } answers {
            val eventName = firstArg<String>()
            val listener = secondArg<Emitter.Listener>()
            capturedListeners[eventName] = listener
            mockSocket // Return socket for chaining
        }

        // Mock socket.connected() to return false initially
        every { mockSocket.connected() } returns false

        // Mock socket.emit() for room operations
        every { mockSocket.emit(any(), any<JSONObject>()) } returns mockSocket

        // Mock IO.socket() static to return our mock socket
        mockkStatic(IO::class)
        every { IO.socket(any<URI>(), any<IO.Options>()) } returns mockSocket

        // Create SocketManager and call connect() to register all event listeners
        socketManager = SocketManager(mockSecureStorage)
        socketManager.connect("https://test.socket.io", "test-token")
    }

    @After
    fun tearDown() {
        capturedListeners.clear()
        unmockkAll()
    }

    // ========================================
    // CONNECTION TESTS
    // ========================================

    @Test
    fun `connect() should setup socket with correct URL and token`() = runTest(testDispatcher) {
        // Given
        val url = "https://test.socket.io"
        val token = "test-jwt-token"
        val capturedOptions = slot<IO.Options>()

        // Re-mock IO.socket with capture to inspect options
        every { IO.socket(any<URI>(), capture(capturedOptions)) } returns mockSocket

        // When
        socketManager.connect(url, token)

        // Then - Verify socket was created with correct options
        verify { IO.socket(any<URI>(), any<IO.Options>()) }

        // Verify auth token was set correctly
        val authMap = capturedOptions.captured.auth as? Map<*, *>
        assertThat(authMap).isNotNull()
        assertThat(authMap?.get("token")).isEqualTo(token)
    }

    @Test
    fun `connect() should emit Connected event on successful connection`() = runTest(testDispatcher) {
        // Given
        every { mockSocket.connected() } returns true

        // When - Simulate server connection
        capturedListeners[Socket.EVENT_CONNECT]?.call()

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.Connected::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnect() should emit Disconnected event`() = runTest(testDispatcher) {
        // Given
        every { mockSocket.connected() } returns false

        // When - Simulate server disconnection
        capturedListeners[Socket.EVENT_DISCONNECT]?.call("io client disconnect")

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.Disconnected::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connect() should emit ConnectionError on failure`() = runTest(testDispatcher) {
        // Given
        val errorMessage = "Connection timeout"

        // When - Simulate connection error
        capturedListeners[Socket.EVENT_CONNECT_ERROR]?.call(errorMessage)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.ConnectionError::class.java)
            assertThat((event as SocketEvent.ConnectionError).message).contains(errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isConnected() should return socket connection state`() {
        // Given
        every { mockSocket.connected() } returns true

        // When
        val result = socketManager.isConnected()

        // Then
        assertThat(result).isTrue()
    }

    // ========================================
    // EVENT PARSING TESTS
    // ========================================

    @Test
    fun `should parse payment_completed event correctly`() = runTest(testDispatcher) {
        // Given
        val paymentJson = JSONObject().apply {
            put("paymentId", "pay_123")
            put("amount", 5000) // $50.00 in cents
            put("currency", "USD")
            put("tableId", "table_1")
            put("orderId", "order_456")
            put("venueId", "venue_789")
            put("timestamp", "2025-01-15T10:30:00Z")
        }

        // When - Simulate server sending payment_completed event
        capturedListeners["payment_completed"]?.call(paymentJson)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.PaymentCompleted::class.java)

            val paymentEvent = event as SocketEvent.PaymentCompleted
            assertThat(paymentEvent.paymentId).isEqualTo("pay_123")
            assertThat(paymentEvent.amount).isEqualTo(5000)
            assertThat(paymentEvent.currency).isEqualTo("USD")
            assertThat(paymentEvent.tableId).isEqualTo("table_1")
            assertThat(paymentEvent.orderId).isEqualTo("order_456")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should parse system_alert event correctly`() = runTest(testDispatcher) {
        // Given
        val alertJson = JSONObject().apply {
            put("level", "critical")
            put("title", "Server Maintenance")
            put("message", "Server will restart in 5 minutes")
            put("targetRoles", org.json.JSONArray(listOf("ADMIN", "MANAGER")))
            put("venueId", "venue_123")
            put("timestamp", "2025-01-15T10:30:00Z")
        }

        // When
        capturedListeners["system_alert"]?.call(alertJson)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.SystemAlert::class.java)

            val alertEvent = event as SocketEvent.SystemAlert
            assertThat(alertEvent.level).isEqualTo("critical")
            assertThat(alertEvent.title).isEqualTo("Server Maintenance")
            assertThat(alertEvent.message).isEqualTo("Server will restart in 5 minutes")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should parse tpv_command event correctly`() = runTest(testDispatcher) {
        // Given
        val commandJson = JSONObject().apply {
            put("terminalId", "terminal_123")
            put("type", "MAINTENANCE_MODE")
            put("payload", JSONObject().apply {
                put("enabled", true)
            })
            put("requestedBy", "admin@example.com")
            put("venueId", "venue_123")
            put("timestamp", "2025-01-15T10:30:00Z")
        }

        // When
        capturedListeners["tpv_command"]?.call(commandJson)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.TPVCommand::class.java)

            val commandEvent = event as SocketEvent.TPVCommand
            assertThat(commandEvent.terminalId).isEqualTo("terminal_123")
            assertThat(commandEvent.commandType).isEqualTo("MAINTENANCE_MODE")
            assertThat(commandEvent.requestedBy).isEqualTo("admin@example.com")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should parse inventory_low_stock event correctly`() = runTest(testDispatcher) {
        // Given
        val inventoryJson = JSONObject().apply {
            put("rawMaterialId", "rm_123")
            put("rawMaterialName", "Tomatoes")
            put("currentStock", 5.0)
            put("unit", "kg")
            put("threshold", 10.0)
            put("venueId", "venue_123")
            put("timestamp", "2025-01-15T10:30:00Z")
        }

        // When
        capturedListeners["inventory_low_stock"]?.call(inventoryJson)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.InventoryLowStock::class.java)

            val inventoryEvent = event as SocketEvent.InventoryLowStock
            assertThat(inventoryEvent.rawMaterialName).isEqualTo("Tomatoes")
            assertThat(inventoryEvent.currentStock).isEqualTo(5.0)
            assertThat(inventoryEvent.threshold).isEqualTo(10.0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should parse printer_status event correctly`() = runTest(testDispatcher) {
        // Given
        val printerJson = JSONObject().apply {
            put("terminalId", "terminal_123")
            put("printerType", "THERMAL")
            put("status", "PAPER_LOW")
            put("errorMessage", "Paper running low")
            put("venueId", "venue_123")
            put("timestamp", "2025-01-15T10:30:00Z")
        }

        // When
        capturedListeners["printer_status"]?.call(printerJson)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.PrinterStatus::class.java)

            val printerEvent = event as SocketEvent.PrinterStatus
            assertThat(printerEvent.printerType).isEqualTo("THERMAL")
            assertThat(printerEvent.status).isEqualTo("PAPER_LOW")
            assertThat(printerEvent.errorMessage).isEqualTo("Paper running low")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // ROOM MANAGEMENT TESTS
    // ========================================

    @Test
    fun `joinVenueRoom() should emit correct room_join event`() {
        // Given
        val venueId = "venue_123"
        val capturedData = slot<JSONObject>()

        every { mockSocket.emit("join_room", capture(capturedData)) } returns mockSocket

        // When
        socketManager.joinVenueRoom(venueId)

        // Then
        verify { mockSocket.emit("join_room", any<JSONObject>()) }
        assertThat(capturedData.captured.getString("roomType")).isEqualTo("venue")
        assertThat(capturedData.captured.getString("venueId")).isEqualTo(venueId)
    }

    @Test
    fun `joinTableRoom() should emit correct room_join event with tableId`() {
        // Given
        val venueId = "venue_123"
        val tableId = "table_5"
        val capturedData = slot<JSONObject>()

        every { mockSocket.emit("join_room", capture(capturedData)) } returns mockSocket

        // When
        socketManager.joinTableRoom(venueId, tableId)

        // Then
        verify { mockSocket.emit("join_room", any<JSONObject>()) }
        assertThat(capturedData.captured.getString("roomType")).isEqualTo("table")
        assertThat(capturedData.captured.getString("venueId")).isEqualTo(venueId)
        assertThat(capturedData.captured.getString("tableId")).isEqualTo(tableId)
    }

    @Test
    fun `joinOrderRoom() should emit correct room_join event with orderId`() {
        // Given
        val venueId = "venue_123"
        val orderId = "order_456"
        val capturedData = slot<JSONObject>()

        every { mockSocket.emit("join_room", capture(capturedData)) } returns mockSocket

        // When
        socketManager.joinOrderRoom(venueId, orderId)

        // Then
        verify { mockSocket.emit("join_room", any<JSONObject>()) }
        assertThat(capturedData.captured.getString("roomType")).isEqualTo("order")
        assertThat(capturedData.captured.getString("venueId")).isEqualTo(venueId)
        assertThat(capturedData.captured.getString("orderId")).isEqualTo(orderId)
    }

    @Test
    fun `leaveRoom() should emit correct leave_room event`() {
        // Given
        val roomType = "venue"
        val venueId = "venue_123"
        val capturedData = slot<JSONObject>()

        every { mockSocket.emit("leave_room", capture(capturedData)) } returns mockSocket

        // When
        socketManager.leaveRoom(roomType, venueId)

        // Then
        verify { mockSocket.emit("leave_room", any<JSONObject>()) }
        assertThat(capturedData.captured.getString("roomType")).isEqualTo(roomType)
        assertThat(capturedData.captured.getString("venueId")).isEqualTo(venueId)
    }

    // ========================================
    // ERROR HANDLING TESTS
    // ========================================

    @Test
    fun `should handle malformed JSON gracefully without crashing`() = runTest(testDispatcher) {
        // Given - Invalid JSON (missing required fields)
        val malformedJson = JSONObject().apply {
            put("random_field", "invalid")
            // Missing paymentId, amount, etc.
        }

        // When - Try to parse as payment_completed
        capturedListeners["payment_completed"]?.call(malformedJson)

        // Then - Should not crash. Event is emitted with default/empty values
        // (parsePaymentEvent uses optString/optInt which return defaults for missing fields)
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.PaymentCompleted::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should handle authentication_error event`() = runTest(testDispatcher) {
        // Given
        val authErrorJson = JSONObject().apply {
            put("error", "INVALID_TOKEN")
            put("message", "JWT token is invalid or expired")
        }

        // When
        capturedListeners["authentication_error"]?.call(authErrorJson)

        // Then
        socketManager.events.test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(SocketEvent.AuthError::class.java)

            val authError = event as SocketEvent.AuthError
            assertThat(authError.error).isEqualTo("INVALID_TOKEN")
            assertThat(authError.message).isEqualTo("JWT token is invalid or expired")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // STATE FLOW TESTS
    // ========================================

    @Test
    fun `isConnected StateFlow should update on connection changes`() = runTest(testDispatcher) {
        // Given - setup's connect() called disconnect() which emitted false
        every { mockSocket.connected() } returns false

        socketManager.isConnected.test {
            // Initial state (replayed from setup)
            assertThat(awaitItem()).isFalse()

            // When - Connect
            every { mockSocket.connected() } returns true
            capturedListeners[Socket.EVENT_CONNECT]?.call()

            // Then - Should emit true
            assertThat(awaitItem()).isTrue()

            // When - Disconnect
            every { mockSocket.connected() } returns false
            capturedListeners[Socket.EVENT_DISCONNECT]?.call("test disconnect")

            // Then - Should emit false
            assertThat(awaitItem()).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `events SharedFlow should emit multiple events in order`() = runTest(testDispatcher) {
        // Given
        val payment1 = JSONObject().apply {
            put("paymentId", "pay_1")
            put("amount", 1000)
            put("currency", "USD")
            put("venueId", "venue_1")
            put("timestamp", "2025-01-15T10:30:00Z")
        }

        val payment2 = JSONObject().apply {
            put("paymentId", "pay_2")
            put("amount", 2000)
            put("currency", "USD")
            put("venueId", "venue_1")
            put("timestamp", "2025-01-15T10:31:00Z")
        }

        socketManager.events.test {
            // When - Emit multiple events
            capturedListeners["payment_completed"]?.call(payment1)
            capturedListeners["payment_completed"]?.call(payment2)

            // Then - Should receive both in order
            val event1 = awaitItem()
            assertThat(event1).isInstanceOf(SocketEvent.PaymentCompleted::class.java)
            assertThat((event1 as SocketEvent.PaymentCompleted).paymentId).isEqualTo("pay_1")

            val event2 = awaitItem()
            assertThat(event2).isInstanceOf(SocketEvent.PaymentCompleted::class.java)
            assertThat((event2 as SocketEvent.PaymentCompleted).paymentId).isEqualTo("pay_2")

            cancelAndIgnoreRemainingEvents()
        }
    }
}
