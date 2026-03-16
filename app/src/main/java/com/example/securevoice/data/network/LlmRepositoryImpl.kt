package com.example.securevoice.data.network

import com.example.securevoice.domain.model.StreamEvent
import com.example.securevoice.domain.repository.LlmRepository
import kotlinx.coroutines.flow.Flow

class LlmRepositoryImpl(
    private val llmClient: LlmClient
) : LlmRepository {

    override fun streamResponse(prompt: String): Flow<StreamEvent> {
        return llmClient.stream(prompt)
    }
}
