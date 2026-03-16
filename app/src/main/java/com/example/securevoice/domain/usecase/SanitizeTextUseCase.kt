package com.example.securevoice.domain.usecase

import com.example.securevoice.data.privacy.RedactionService
import javax.inject.Inject

class SanitizeTextUseCase @Inject constructor(
    private val redactionService: RedactionService
) {
    operator fun invoke(text: String): String {
        return redactionService.sanitize(text)
    }
}
