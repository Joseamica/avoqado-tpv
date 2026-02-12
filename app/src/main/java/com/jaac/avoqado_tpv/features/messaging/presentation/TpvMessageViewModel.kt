package com.jaac.avoqado_tpv.features.messaging.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.AckMessageRequest
import com.jaac.avoqado_tpv.core.data.network.dto.SurveyResponseRequest
import com.jaac.avoqado_tpv.core.data.network.dto.TpvMessageDto
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

data class TpvMessageUiState(
    val currentMessage: TpvMessageUiModel? = null,
    val messageQueue: List<TpvMessageUiModel> = emptyList(),
    val selectedSurveyOptions: Set<Int> = emptySet(),
    val isSubmitting: Boolean = false
)

/**
 * UI model that can come from either Socket.IO events or REST API responses
 */
data class TpvMessageUiModel(
    val messageId: String,
    val type: String,
    val title: String,
    val body: String,
    val priority: String,
    val requiresAck: Boolean,
    val surveyOptions: List<String>?,
    val surveyMultiSelect: Boolean,
    val actionLabel: String?,
    val actionType: String?,
    val expiresAt: String?,
    val createdByName: String
)

@HiltViewModel
class TpvMessageViewModel @Inject constructor(
    private val socketManager: SocketManager,
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(TpvMessageUiState())
    val uiState: StateFlow<TpvMessageUiState> = _uiState.asStateFlow()

    // Track locally dismissed/acknowledged message IDs to prevent re-showing
    // after socket reconnect fetches them as "pending" (server may not have received the dismiss)
    private val handledMessageIds = mutableSetOf<String>()

    init {
        observeSocketEvents()
    }

    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.TpvMessageReceived -> onMessageReceived(event)
                    is SocketEvent.TpvMessageCancelled -> onMessageCancelled(event.messageId)
                    is SocketEvent.Connected -> fetchPendingMessages()
                    else -> {}
                }
            }
        }
    }

    private fun onMessageReceived(event: SocketEvent.TpvMessageReceived) {
        val model = TpvMessageUiModel(
            messageId = event.messageId,
            type = event.type,
            title = event.title,
            body = event.body,
            priority = event.priority,
            requiresAck = event.requiresAck,
            surveyOptions = event.surveyOptions,
            surveyMultiSelect = event.surveyMultiSelect,
            actionLabel = event.actionLabel,
            actionType = event.actionType,
            expiresAt = event.expiresAt,
            createdByName = event.createdByName
        )

        // Skip expired messages
        if (isExpired(model.expiresAt)) {
            Timber.d("📨 Skipping expired message: ${model.messageId}")
            return
        }

        // Skip messages already dismissed/acknowledged locally
        if (model.messageId in handledMessageIds) {
            Timber.d("📨 Skipping already handled message: ${model.messageId}")
            return
        }

        // Skip duplicate messages
        _uiState.update { state ->
            if (state.messageQueue.any { it.messageId == model.messageId } ||
                state.currentMessage?.messageId == model.messageId
            ) {
                return@update state
            }

            if (state.currentMessage == null) {
                state.copy(currentMessage = model, selectedSurveyOptions = emptySet())
            } else {
                state.copy(messageQueue = state.messageQueue + model)
            }
        }
    }

    private fun onMessageCancelled(messageId: String) {
        _uiState.update { state ->
            if (state.currentMessage?.messageId == messageId) {
                showNextMessage(state.copy(currentMessage = null, selectedSurveyOptions = emptySet()))
            } else {
                state.copy(messageQueue = state.messageQueue.filter { it.messageId != messageId })
            }
        }
    }

    /**
     * Fetch pending messages via REST API (used on reconnect for offline recovery)
     */
    fun fetchPendingMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.getPendingMessages()
                if (response.isSuccessful) {
                    val messages = response.body()?.data ?: emptyList()
                    Timber.d("📨 Fetched ${messages.size} pending messages via REST")
                    messages.forEach { dto ->
                        onMessageReceived(dto.toSocketEvent())
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to fetch pending messages")
            }
        }
    }

    /**
     * Acknowledge the current message
     */
    fun acknowledgeMessage() {
        val message = _uiState.value.currentMessage ?: return

        // Mark as handled locally to prevent re-showing on socket reconnect
        handledMessageIds.add(message.messageId)

        // Move to next message FIRST (always close dialog regardless of network)
        _uiState.update { state ->
            showNextMessage(state.copy(isSubmitting = false, selectedSurveyOptions = emptySet()))
        }

        // Best-effort: notify server about acknowledgment
        val terminalId = secureStorage.getTerminalId()
        if (terminalId != null) {
            socketManager.emitMessageAck(message.messageId, terminalId, "ACKNOWLEDGED")
        } else {
            Timber.w("⚠️ [TpvMessage] No terminalId — skipping Socket.IO ACK")
        }

        // Also call REST API as backup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.acknowledgeMessage(message.messageId, AckMessageRequest())
            } catch (e: Exception) {
                Timber.e(e, "❌ REST acknowledge failed (Socket.IO was primary)")
            }
        }
    }

    /**
     * Dismiss the current message
     */
    fun dismissMessage() {
        val message = _uiState.value.currentMessage ?: return

        // Mark as handled locally to prevent re-showing on socket reconnect
        handledMessageIds.add(message.messageId)

        // Move to next message FIRST (always close dialog regardless of network)
        _uiState.update { state ->
            showNextMessage(state.copy(selectedSurveyOptions = emptySet()))
        }

        // Best-effort: notify server about dismissal
        val terminalId = secureStorage.getTerminalId()
        if (terminalId != null) {
            socketManager.emitMessageAck(message.messageId, terminalId, "DISMISSED")
        } else {
            Timber.w("⚠️ [TpvMessage] No terminalId — skipping Socket.IO dismiss ACK")
        }

        // REST backup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.dismissMessage(message.messageId)
            } catch (e: Exception) {
                Timber.e(e, "❌ REST dismiss failed (Socket.IO was primary)")
            }
        }
    }

    /**
     * Toggle a survey option selection
     */
    fun toggleSurveyOption(index: Int) {
        val message = _uiState.value.currentMessage ?: return

        _uiState.update { state ->
            val newSelection = if (message.surveyMultiSelect) {
                if (index in state.selectedSurveyOptions) {
                    state.selectedSurveyOptions - index
                } else {
                    state.selectedSurveyOptions + index
                }
            } else {
                setOf(index) // Single select: replace
            }
            state.copy(selectedSurveyOptions = newSelection)
        }
    }

    /**
     * Submit the survey response
     */
    fun submitSurveyResponse() {
        val state = _uiState.value
        val message = state.currentMessage ?: return
        val surveyOptions = message.surveyOptions ?: return

        if (state.selectedSurveyOptions.isEmpty()) return

        val selectedOptionTexts = state.selectedSurveyOptions
            .sorted()
            .mapNotNull { surveyOptions.getOrNull(it) }

        // Mark as handled locally to prevent re-showing on socket reconnect
        handledMessageIds.add(message.messageId)

        // Move to next message FIRST (always close dialog regardless of network)
        _uiState.update { s ->
            showNextMessage(s.copy(isSubmitting = false, selectedSurveyOptions = emptySet()))
        }

        // Best-effort: notify server about survey response
        val terminalId = secureStorage.getTerminalId()
        if (terminalId != null) {
            socketManager.emitMessageResponse(
                messageId = message.messageId,
                terminalId = terminalId,
                selectedOptions = selectedOptionTexts
            )
        } else {
            Timber.w("⚠️ [TpvMessage] No terminalId — skipping Socket.IO survey response")
        }

        // REST backup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.respondToMessage(
                    message.messageId,
                    SurveyResponseRequest(selectedOptions = selectedOptionTexts)
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ REST survey response failed (Socket.IO was primary)")
            }
        }
    }

    /**
     * Execute the action for an ACTION type message, then acknowledge
     */
    fun executeAction() {
        val message = _uiState.value.currentMessage ?: return

        when (message.actionType) {
            "REFRESH_MENU" -> {
                Timber.i("📨 Action: REFRESH_MENU triggered by message ${message.messageId}")
                // The menu will be refreshed by the HomeViewModel which listens to menu events
            }
            "SYNC_DATA" -> {
                Timber.i("📨 Action: SYNC_DATA triggered by message ${message.messageId}")
            }
            else -> {
                Timber.i("📨 Action: ${message.actionType} (custom) triggered by message ${message.messageId}")
            }
        }

        // Acknowledge after executing
        acknowledgeMessage()
    }

    private fun showNextMessage(state: TpvMessageUiState): TpvMessageUiState {
        val queue = state.messageQueue
        return if (queue.isNotEmpty()) {
            val next = queue.first()
            // Skip expired messages
            if (isExpired(next.expiresAt)) {
                showNextMessage(state.copy(
                    currentMessage = null,
                    messageQueue = queue.drop(1)
                ))
            } else {
                state.copy(
                    currentMessage = next,
                    messageQueue = queue.drop(1),
                    selectedSurveyOptions = emptySet()
                )
            }
        } else {
            state.copy(currentMessage = null)
        }
    }

    private fun isExpired(expiresAt: String?): Boolean {
        if (expiresAt.isNullOrBlank()) return false
        return try {
            Instant.parse(expiresAt).isBefore(Instant.now())
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Convert REST DTO to SocketEvent for unified processing
 */
private fun TpvMessageDto.toSocketEvent() = SocketEvent.TpvMessageReceived(
    messageId = id,
    type = type,
    title = title,
    body = body,
    priority = priority,
    requiresAck = requiresAck,
    surveyOptions = surveyOptions,
    surveyMultiSelect = surveyMultiSelect,
    actionLabel = actionLabel,
    actionType = actionType,
    expiresAt = expiresAt,
    createdByName = createdByName,
    venueId = "",
    timestamp = createdAt
)
