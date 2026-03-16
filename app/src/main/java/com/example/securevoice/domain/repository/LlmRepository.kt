package com.example.securevoice.domain.repository

import com.example.securevoice.domain.model.StreamEvent
import kotlinx.coroutines.flow.Flow

interface LlmRepository {
    fun streamResponse(prompt: String): Flow<StreamEvent>
}
