package com.example.securevoice.domain.usecase

import com.example.securevoice.domain.model.StreamEvent
import com.example.securevoice.domain.repository.LlmRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class StreamLlmResponseUseCase @Inject constructor(
    private val llmRepository: LlmRepository,
    private val sanitizeTextUseCase: SanitizeTextUseCase
) {
    operator fun invoke(rawText: String): Flow<StreamEvent> {
        val safeText = sanitizeTextUseCase(rawText)
        return llmRepository.streamResponse(safeText)
    }
}
