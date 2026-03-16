package com.example.securevoice.domain.model

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val role: Role,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Role {
    USER,
    ASSISTANT
}
