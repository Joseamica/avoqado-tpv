package com.jaac.avoqado_tpv.features.messaging.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.AckMessageRequest
import com.jaac.avoqado_tpv.core.data.network.dto.SurveyResponseRequest
import com.jaac.avoqado_tpv.core.data.network.dto.TpvMessageDto
import com.jaac.avoqado_tpv.core.data.network.dto.TpvMessageListResponse
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TpvMessageViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var socketManager: SocketManager
    private lateinit var apiService: ApiService
    private lateinit var secureStorage: SecureStorage

    private val fakeSocketEvents = MutableSharedFlow<SocketEvent>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        socketManager = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)

        every { socketManager.events } returns fakeSocketEvents
        every { secureStorage.getTerminalId() } returns "terminal-001"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): TpvMessageViewModel {
        return TpvMessageViewModel(
            socketManager = socketManager,
            apiService = apiService,
            secureStorage = secureStorage
        )
    }

    private fun createAnnouncementEvent(
        messageId: String = "msg-1",
        title: String = "Test Announcement",
        body: String = "This is a test",
        priority: String = "NORMAL",
        requiresAck: Boolean = false,
        expiresAt: String? = null
    ) = SocketEvent.TpvMessageReceived(
        messageId = messageId,
        type = "ANNOUNCEMENT",
        title = title,
        body = body,
        priority = priority,
        requiresAck = requiresAck,
        surveyOptions = null,
        surveyMultiSelect = false,
        actionLabel = null,
        actionType = null,
        expiresAt = expiresAt,
        createdByName = "Admin Test",
        venueId = "venue-123",
        timestamp = "2026-02-11T00:00:00Z"
    )

    private fun createSurveyEvent(
        messageId: String = "msg-survey-1",
        surveyOptions: List<String> = listOf("Option A", "Option B", "Option C"),
        surveyMultiSelect: Boolean = false
    ) = SocketEvent.TpvMessageReceived(
        messageId = messageId,
        type = "SURVEY",
        title = "Survey Question",
        body = "Please answer the survey",
        priority = "NORMAL",
        requiresAck = true,
        surveyOptions = surveyOptions,
        surveyMultiSelect = surveyMultiSelect,
        actionLabel = null,
        actionType = null,
        expiresAt = null,
        createdByName = "Admin Test",
        venueId = "venue-123",
        timestamp = "2026-02-11T00:00:00Z"
    )

    private fun createActionEvent(
        messageId: String = "msg-action-1",
        actionLabel: String = "Refresh Menu",
        actionType: String = "REFRESH_MENU"
    ) = SocketEvent.TpvMessageReceived(
        messageId = messageId,
        type = "ACTION",
        title = "Action Required",
        body = "Please perform this action",
        priority = "HIGH",
        requiresAck = true,
        surveyOptions = null,
        surveyMultiSelect = false,
        actionLabel = actionLabel,
        actionType = actionType,
        expiresAt = null,
        createdByName = "Admin Test",
        venueId = "venue-123",
        timestamp = "2026-02-11T00:00:00Z"
    )

    // =====================================================
    // Initial State
    // =====================================================

    @Test
    fun `initial state has no current message and empty queue`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        assertThat(viewModel.uiState.value.currentMessage).isNull()
        assertThat(viewModel.uiState.value.messageQueue).isEmpty()
        assertThat(viewModel.uiState.value.selectedSurveyOptions).isEmpty()
        assertThat(viewModel.uiState.value.isSubmitting).isFalse()

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Receiving Messages via Socket.IO
    // =====================================================

    @Test
    fun `receiving first message sets it as current message`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val event = createAnnouncementEvent()

        fakeSocketEvents.emit(event)

        assertThat(viewModel.uiState.value.currentMessage).isNotNull()
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")
        assertThat(viewModel.uiState.value.currentMessage!!.title).isEqualTo("Test Announcement")
        assertThat(viewModel.uiState.value.currentMessage!!.type).isEqualTo("ANNOUNCEMENT")
        assertThat(viewModel.uiState.value.messageQueue).isEmpty()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `receiving second message adds it to queue`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2", title = "Second Message"))

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")
        assertThat(viewModel.uiState.value.messageQueue).hasSize(1)
        assertThat(viewModel.uiState.value.messageQueue[0].messageId).isEqualTo("msg-2")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `duplicate message is ignored`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1")) // duplicate

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")
        assertThat(viewModel.uiState.value.messageQueue).isEmpty()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `duplicate in queue is also ignored`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2")) // duplicate in queue

        assertThat(viewModel.uiState.value.messageQueue).hasSize(1)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `expired message is skipped`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val pastExpiration = Instant.now().minusSeconds(3600).toString()

        fakeSocketEvents.emit(createAnnouncementEvent(expiresAt = pastExpiration))

        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `non-expired message is shown`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val futureExpiration = Instant.now().plusSeconds(3600).toString()

        fakeSocketEvents.emit(createAnnouncementEvent(expiresAt = futureExpiration))

        assertThat(viewModel.uiState.value.currentMessage).isNotNull()

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Acknowledge / Dismiss
    // =====================================================

    @Test
    fun `acknowledgeMessage clears current and emits socket event`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createAnnouncementEvent())

        viewModel.acknowledgeMessage()

        assertThat(viewModel.uiState.value.currentMessage).isNull()
        verify {
            socketManager.emitMessageAck("msg-1", "terminal-001", "ACKNOWLEDGED")
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `acknowledgeMessage also calls REST API as backup`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createAnnouncementEvent())

        viewModel.acknowledgeMessage()

        // Give time for the IO coroutine to execute
        Thread.sleep(200)

        coVerify {
            apiService.acknowledgeMessage("msg-1", "terminal-001", any<AckMessageRequest>())
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `dismissMessage clears current and emits DISMISSED via socket`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createAnnouncementEvent())

        viewModel.dismissMessage()

        assertThat(viewModel.uiState.value.currentMessage).isNull()
        verify {
            socketManager.emitMessageAck("msg-1", "terminal-001", "DISMISSED")
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `dismissMessage calls REST dismiss as backup`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createAnnouncementEvent())

        viewModel.dismissMessage()

        Thread.sleep(200)

        coVerify {
            apiService.dismissMessage("msg-1", "terminal-001")
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `acknowledgeMessage does nothing when no current message`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.acknowledgeMessage() // no message

        verify(exactly = 0) { socketManager.emitMessageAck(any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `acknowledgeMessage does nothing when no terminal id`() = runTest(testDispatcher) {
        every { secureStorage.getTerminalId() } returns null
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createAnnouncementEvent())

        viewModel.acknowledgeMessage()

        verify(exactly = 0) { socketManager.emitMessageAck(any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Queue Management
    // =====================================================

    @Test
    fun `after acknowledging, next message in queue becomes current`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2", title = "Second"))

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")

        viewModel.acknowledgeMessage()

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-2")
        assertThat(viewModel.uiState.value.currentMessage!!.title).isEqualTo("Second")
        assertThat(viewModel.uiState.value.messageQueue).isEmpty()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `after dismissing, next message in queue becomes current`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2", title = "Second"))

        viewModel.dismissMessage()

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-2")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `expired messages in queue are skipped when becoming current`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val pastExpiration = Instant.now().minusSeconds(1).toString() // will expire by the time we dismiss
        val futureExpiration = Instant.now().plusSeconds(3600).toString()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        // msg-2 will be expired when it comes to the front
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2", expiresAt = pastExpiration))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-3", expiresAt = futureExpiration))

        // Wait a moment so msg-2 expires
        Thread.sleep(100)

        viewModel.acknowledgeMessage()

        // msg-2 should be skipped (expired), msg-3 should be current
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-3")

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Message Cancellation
    // =====================================================

    @Test
    fun `cancelling current message shows next from queue`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2"))

        fakeSocketEvents.emit(SocketEvent.TpvMessageCancelled("msg-1", "venue-123", "2026-02-11T00:00:00Z"))

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-2")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelling current message with empty queue clears current`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))

        fakeSocketEvents.emit(SocketEvent.TpvMessageCancelled("msg-1", "venue-123", "2026-02-11T00:00:00Z"))

        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelling queued message removes it from queue`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-3"))

        assertThat(viewModel.uiState.value.messageQueue).hasSize(2)

        fakeSocketEvents.emit(SocketEvent.TpvMessageCancelled("msg-2", "venue-123", "2026-02-11T00:00:00Z"))

        // msg-2 should be removed from queue, msg-1 still current
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")
        assertThat(viewModel.uiState.value.messageQueue).hasSize(1)
        assertThat(viewModel.uiState.value.messageQueue[0].messageId).isEqualTo("msg-3")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelling non-existent message does nothing`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))

        fakeSocketEvents.emit(SocketEvent.TpvMessageCancelled("msg-nonexistent", "venue-123", "2026-02-11T00:00:00Z"))

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Survey Functionality
    // =====================================================

    @Test
    fun `toggleSurveyOption selects option in single-select mode`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(surveyMultiSelect = false))

        viewModel.toggleSurveyOption(0)

        assertThat(viewModel.uiState.value.selectedSurveyOptions).containsExactly(0)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `toggleSurveyOption replaces in single-select mode`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(surveyMultiSelect = false))

        viewModel.toggleSurveyOption(0)
        viewModel.toggleSurveyOption(2)

        // In single-select, selecting option 2 should replace option 0
        assertThat(viewModel.uiState.value.selectedSurveyOptions).containsExactly(2)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `toggleSurveyOption allows multiple in multi-select mode`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(surveyMultiSelect = true))

        viewModel.toggleSurveyOption(0)
        viewModel.toggleSurveyOption(2)

        assertThat(viewModel.uiState.value.selectedSurveyOptions).containsExactly(0, 2)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `toggleSurveyOption deselects in multi-select mode`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(surveyMultiSelect = true))

        viewModel.toggleSurveyOption(0)
        viewModel.toggleSurveyOption(1)
        viewModel.toggleSurveyOption(0) // deselect

        assertThat(viewModel.uiState.value.selectedSurveyOptions).containsExactly(1)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `toggleSurveyOption does nothing when no current message`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.toggleSurveyOption(0) // no message

        assertThat(viewModel.uiState.value.selectedSurveyOptions).isEmpty()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `submitSurveyResponse emits socket event and moves to next`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent())

        viewModel.toggleSurveyOption(0) // Select "Option A"
        viewModel.toggleSurveyOption(2) // Select "Option C" (replaces in single-select)
        viewModel.submitSurveyResponse()

        verify {
            socketManager.emitMessageResponse(
                messageId = "msg-survey-1",
                terminalId = "terminal-001",
                selectedOptions = listOf("Option C") // single-select: only last
            )
        }

        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `submitSurveyResponse with multi-select sends all options sorted`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(surveyMultiSelect = true))

        viewModel.toggleSurveyOption(2) // "Option C"
        viewModel.toggleSurveyOption(0) // "Option A"
        viewModel.submitSurveyResponse()

        verify {
            socketManager.emitMessageResponse(
                messageId = "msg-survey-1",
                terminalId = "terminal-001",
                selectedOptions = listOf("Option A", "Option C") // sorted by index
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `submitSurveyResponse calls REST as backup`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent())

        viewModel.toggleSurveyOption(1)
        viewModel.submitSurveyResponse()

        Thread.sleep(200)

        coVerify {
            apiService.respondToMessage(
                "msg-survey-1",
                "terminal-001",
                match<SurveyResponseRequest> { it.selectedOptions == listOf("Option B") }
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `submitSurveyResponse does nothing when no options selected`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent())

        viewModel.submitSurveyResponse() // no selection

        verify(exactly = 0) { socketManager.emitMessageResponse(any(), any(), any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `selectedSurveyOptions reset after acknowledge`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(messageId = "survey-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2"))

        viewModel.toggleSurveyOption(1)
        assertThat(viewModel.uiState.value.selectedSurveyOptions).isNotEmpty()

        viewModel.acknowledgeMessage() // Clear survey, show msg-2 (but as we submitted, it's a survey ack)

        assertThat(viewModel.uiState.value.selectedSurveyOptions).isEmpty()

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Action Functionality
    // =====================================================

    @Test
    fun `executeAction triggers acknowledge after action`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createActionEvent())

        viewModel.executeAction()

        verify {
            socketManager.emitMessageAck("msg-action-1", "terminal-001", "ACKNOWLEDGED")
        }
        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `executeAction does nothing when no current message`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.executeAction() // no message

        verify(exactly = 0) { socketManager.emitMessageAck(any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Fetch Pending Messages (reconnect recovery)
    // =====================================================

    @Test
    fun `fetchPendingMessages loads messages from REST API`() = runTest(testDispatcher) {
        val dto = TpvMessageDto(
            id = "msg-rest-1",
            venueId = "venue-123",
            type = "ANNOUNCEMENT",
            title = "REST Message",
            body = "Fetched from REST",
            priority = "NORMAL",
            requiresAck = false,
            surveyOptions = null,
            surveyMultiSelect = false,
            actionLabel = null,
            actionType = null,
            expiresAt = null,
            createdBy = "user-1",
            createdByName = "Admin",
            status = "ACTIVE",
            createdAt = "2026-02-11T00:00:00Z"
        )

        coEvery { apiService.getPendingMessages("terminal-001") } returns Response.success(
            TpvMessageListResponse(success = true, data = listOf(dto))
        )

        val viewModel = createViewModel()
        viewModel.fetchPendingMessages()

        Thread.sleep(300)

        assertThat(viewModel.uiState.value.currentMessage).isNotNull()
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-rest-1")
        assertThat(viewModel.uiState.value.currentMessage!!.title).isEqualTo("REST Message")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `Connected socket event triggers fetchPendingMessages`() = runTest(testDispatcher) {
        coEvery { apiService.getPendingMessages("terminal-001") } returns Response.success(
            TpvMessageListResponse(success = true, data = emptyList())
        )

        val viewModel = createViewModel()

        fakeSocketEvents.emit(SocketEvent.Connected)

        Thread.sleep(300)

        coVerify { apiService.getPendingMessages("terminal-001") }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fetchPendingMessages handles API failure gracefully`() = runTest(testDispatcher) {
        coEvery { apiService.getPendingMessages("terminal-001") } throws RuntimeException("Network error")

        val viewModel = createViewModel()
        viewModel.fetchPendingMessages()

        Thread.sleep(300)

        // Should not crash, state unchanged
        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Message Type Properties
    // =====================================================

    @Test
    fun `announcement message has correct properties`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createAnnouncementEvent(
            priority = "URGENT",
            requiresAck = true
        ))

        val msg = viewModel.uiState.value.currentMessage!!
        assertThat(msg.type).isEqualTo("ANNOUNCEMENT")
        assertThat(msg.priority).isEqualTo("URGENT")
        assertThat(msg.requiresAck).isTrue()
        assertThat(msg.surveyOptions).isNull()
        assertThat(msg.actionLabel).isNull()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `survey message has survey options`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createSurveyEvent(
            surveyOptions = listOf("Yes", "No", "Maybe")
        ))

        val msg = viewModel.uiState.value.currentMessage!!
        assertThat(msg.type).isEqualTo("SURVEY")
        assertThat(msg.surveyOptions).containsExactly("Yes", "No", "Maybe")
        assertThat(msg.surveyMultiSelect).isFalse()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `action message has action label and type`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        fakeSocketEvents.emit(createActionEvent(
            actionLabel = "Sync Now",
            actionType = "SYNC_DATA"
        ))

        val msg = viewModel.uiState.value.currentMessage!!
        assertThat(msg.type).isEqualTo("ACTION")
        assertThat(msg.actionLabel).isEqualTo("Sync Now")
        assertThat(msg.actionType).isEqualTo("SYNC_DATA")

        viewModel.viewModelScope.cancel()
    }

    // =====================================================
    // Edge Cases
    // =====================================================

    @Test
    fun `multiple messages processed in order`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        // Send 4 messages
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-1"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-2"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-3"))
        fakeSocketEvents.emit(createAnnouncementEvent(messageId = "msg-4"))

        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-1")
        assertThat(viewModel.uiState.value.messageQueue).hasSize(3)

        // Acknowledge msg-1, msg-2 becomes current
        viewModel.acknowledgeMessage()
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-2")
        assertThat(viewModel.uiState.value.messageQueue).hasSize(2)

        // Dismiss msg-2, msg-3 becomes current
        viewModel.dismissMessage()
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-3")
        assertThat(viewModel.uiState.value.messageQueue).hasSize(1)

        // Acknowledge msg-3, msg-4 becomes current
        viewModel.acknowledgeMessage()
        assertThat(viewModel.uiState.value.currentMessage!!.messageId).isEqualTo("msg-4")
        assertThat(viewModel.uiState.value.messageQueue).isEmpty()

        // Acknowledge msg-4, nothing left
        viewModel.acknowledgeMessage()
        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `unrelated socket events are ignored`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        fakeSocketEvents.emit(SocketEvent.Disconnected)

        assertThat(viewModel.uiState.value.currentMessage).isNull()

        viewModel.viewModelScope.cancel()
    }
}
