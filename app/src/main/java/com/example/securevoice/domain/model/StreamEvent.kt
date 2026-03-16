package com.example.securevoice.domain.model

sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data object Done : StreamEvent
    data class Error(val message: String) : StreamEvent
}
