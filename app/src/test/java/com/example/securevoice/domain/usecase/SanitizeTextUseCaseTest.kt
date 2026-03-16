package com.example.securevoice.domain.usecase

import com.example.securevoice.data.privacy.RedactionService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SanitizeTextUseCaseTest {

    private lateinit var useCase: SanitizeTextUseCase

    @Before
    fun setup() {
        useCase = SanitizeTextUseCase(RedactionService())
    }

    @Test
    fun `delegates to RedactionService for phone redaction`() {
        val result = useCase("Call 555-123-4567")
        assertEquals("Call [PHONE_REDACTED]", result)
    }

    @Test
    fun `delegates to RedactionService for email redaction`() {
        val result = useCase("user@test.com")
        assertEquals("[EMAIL_REDACTED]", result)
    }

    @Test
    fun `delegates to RedactionService for SSN redaction`() {
        val result = useCase("SSN is 123-45-6789")
        assertEquals("SSN is [SSN_REDACTED]", result)
    }

    @Test
    fun `delegates to RedactionService for credit card redaction`() {
        val result = useCase("Card 4111-1111-1111-1111")
        assertEquals("Card [CC_REDACTED]", result)
    }

    @Test
    fun `delegates to RedactionService for address redaction`() {
        val result = useCase("I live at 123 Main Street")
        assertEquals("I live at [ADDRESS_REDACTED]", result)
    }

    @Test
    fun `delegates to RedactionService for IP redaction`() {
        val result = useCase("Server at 192.168.1.1")
        assertEquals("Server at [IP_REDACTED]", result)
    }

    @Test
    fun `passes through clean text unchanged`() {
        val input = "Tell me about the weather"
        assertEquals(input, useCase(input))
    }

    @Test
    fun `handles empty input`() {
        assertEquals("", useCase(""))
    }

    @Test
    fun `redacts all PII from realistic voice transcript`() {
        val transcript = "Hey can you email john@company.com and tell them my number is 555-867-5309 " +
            "my social is 123-45-6789 and I live at 42 Oak Lane"
        val result = useCase(transcript)
        assert(!result.contains("john@company.com"))
        assert(!result.contains("555-867-5309"))
        assert(!result.contains("123-45-6789"))
        assert(!result.contains("42 Oak Lane"))
    }
}
