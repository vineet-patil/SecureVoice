package com.example.securevoice.ui.chat

import com.example.securevoice.domain.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val partialResponse: String = "",
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val hasAudioPermission: Boolean = false
)
