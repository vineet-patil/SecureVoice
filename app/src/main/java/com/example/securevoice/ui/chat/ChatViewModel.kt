package com.example.securevoice.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.securevoice.domain.model.ChatMessage
import com.example.securevoice.domain.model.Role
import com.example.securevoice.domain.model.StreamEvent
import com.example.securevoice.domain.repository.AudioRepository
import com.example.securevoice.domain.repository.TranscriptionRepository
import com.example.securevoice.domain.usecase.StreamLlmResponseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val streamLlmResponseUseCase: StreamLlmResponseUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.StartRecording -> startRecording()
            is ChatIntent.StopRecording -> stopRecording()
            is ChatIntent.DismissError -> dismissError()
            is ChatIntent.PermissionResult -> handlePermissionResult(intent.granted)
        }
    }

    private fun startRecording() {
        if (!_state.value.hasAudioPermission) {
            _state.update { it.copy(error = "Audio permission required") }
            return
        }
        if (_state.value.isRecording || _state.value.isProcessing || _state.value.isStreaming) return

        val started = audioRepository.startRecording()
        if (started) {
            _state.update { it.copy(isRecording = true, error = null) }
        } else {
            _state.update { it.copy(error = "Failed to access microphone") }
        }
    }

    private fun stopRecording() {
        if (!_state.value.isRecording) return

        audioRepository.stopRecording()
        _state.update { it.copy(isRecording = false, isProcessing = true) }

        viewModelScope.launch {
            processAudioAndStream()
        }
    }

    private suspend fun processAudioAndStream() {
        try {
            // Step 1: Get audio snapshot and transcribe
            val audioData = audioRepository.getAudioSnapshot()
            val transcript = transcriptionRepository.transcribe(audioData)

            if (transcript.isBlank()) {
                _state.update { it.copy(isProcessing = false, error = "No speech detected") }
                return
            }

            // Step 2: Add user message optimistically
            val userMessage = ChatMessage(content = transcript, role = Role.USER)
            _state.update {
                it.copy(
                    messages = it.messages + userMessage,
                    isProcessing = false,
                    isStreaming = true,
                    partialResponse = ""
                )
            }

            // Step 3: Stream LLM response (sanitization happens inside the use case)
            val responseBuilder = StringBuilder()
            streamLlmResponseUseCase(transcript).collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        responseBuilder.append(event.text)
                        _state.update { it.copy(partialResponse = responseBuilder.toString()) }
                    }
                    is StreamEvent.Done -> {
                        val assistantMessage = ChatMessage(
                            content = responseBuilder.toString(),
                            role = Role.ASSISTANT
                        )
                        _state.update {
                            it.copy(
                                messages = it.messages + assistantMessage,
                                partialResponse = "",
                                isStreaming = false
                            )
                        }
                    }
                    is StreamEvent.Error -> {
                        _state.update { current ->
                            val finalMessages = if (responseBuilder.isNotEmpty()) {
                                val partialMessage = ChatMessage(
                                    content = responseBuilder.toString(),
                                    role = Role.ASSISTANT
                                )
                                current.messages + partialMessage
                            } else {
                                current.messages
                            }
                            current.copy(
                                messages = finalMessages,
                                partialResponse = "",
                                isStreaming = false,
                                error = event.message
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isProcessing = false,
                    isStreaming = false,
                    partialResponse = "",
                    error = e.message ?: "An unexpected error occurred"
                )
            }
        }
    }

    private fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun handlePermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                hasAudioPermission = granted,
                error = if (!granted) "Microphone permission is required for voice input" else it.error
            )
        }
    }
}
