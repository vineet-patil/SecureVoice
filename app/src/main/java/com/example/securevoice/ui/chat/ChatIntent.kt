package com.example.securevoice.ui.chat

sealed interface ChatIntent {
    data object StartRecording : ChatIntent
    data object StopRecording : ChatIntent
    data object DismissError : ChatIntent
    data class PermissionResult(val granted: Boolean) : ChatIntent
}
