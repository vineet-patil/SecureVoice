package com.example.securevoice.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RedactionServiceTest {

    private lateinit var service: RedactionService

    @Before
    fun setup() {
        service = RedactionService()
    }

    // ==================== Phone Number Tests ====================

    @Test
    fun `redacts US phone number with dashes`() {
        assertEquals(
            "Call me at [PHONE_REDACTED]",
            service.sanitize("Call me at 555-123-4567")
        )
    }

    @Test
    fun `redacts US phone number with dots`() {
        assertEquals(
            "Call [PHONE_REDACTED]",
            service.sanitize("Call 555.123.4567")
        )
    }

    @Test
    fun `redacts US phone number with spaces`() {
        assertEquals(
            "Call [PHONE_REDACTED]",
            service.sanitize("Call 555 123 4567")
        )
    }

    @Test
    fun `redacts phone number with country code`() {
        assertEquals(
            "Call [PHONE_REDACTED]",
            service.sanitize("Call +1-555-123-4567")
        )
    }

    @Test
    fun `redacts phone number with parentheses`() {
        assertEquals(
            "Call [PHONE_REDACTED]",
            service.sanitize("Call (555) 123-4567")
        )
    }

    @Test
    fun `redacts 7-digit phone number`() {
        assertEquals(
            "My number is [PHONE_REDACTED]",
            service.sanitize("My number is 555-0199")
        )
    }

    @Test
    fun `redacts multiple phone numbers`() {
        val input = "Home: 555-111-2222, Work: 555-333-4444"
        assertEquals(
            "Home: [PHONE_REDACTED], Work: [PHONE_REDACTED]",
            service.sanitize(input)
        )
    }

    @Test
    fun `does not redact plain numbers that are not phone numbers`() {
        assertEquals(
            "The answer is 42 and the year is 2024",
            service.sanitize("The answer is 42 and the year is 2024")
        )
    }

    // ==================== Email Tests ====================

    @Test
    fun `redacts simple email`() {
        assertEquals(
            "email me at [EMAIL_REDACTED]",
            service.sanitize("email me at test@example.com")
        )
    }

    @Test
    fun `redacts email with dots in username`() {
        assertEquals(
            "contact [EMAIL_REDACTED] please",
            service.sanitize("contact john.doe@company.com please")
        )
    }

    @Test
    fun `redacts email with dashes`() {
        assertEquals(
            "[EMAIL_REDACTED]",
            service.sanitize("first-last@my-domain.org")
        )
    }

    @Test
    fun `redacts email with subdomain`() {
        assertEquals(
            "[EMAIL_REDACTED]",
            service.sanitize("user@mail.company.co.uk")
        )
    }

    @Test
    fun `does not redact at-sign that is not email`() {
        assertEquals(
            "Use @mention in Slack",
            service.sanitize("Use @mention in Slack")
        )
    }

    // ==================== SSN Tests ====================

    @Test
    fun `redacts SSN with dashes`() {
        assertEquals(
            "My SSN is [SSN_REDACTED]",
            service.sanitize("My SSN is 123-45-6789")
        )
    }

    @Test
    fun `redacts SSN with spaces`() {
        assertEquals(
            "SSN: [SSN_REDACTED]",
            service.sanitize("SSN: 123 45 6789")
        )
    }

    @Test
    fun `redacts SSN at start of text`() {
        assertEquals(
            "[SSN_REDACTED] is my number",
            service.sanitize("123-45-6789 is my number")
        )
    }

    @Test
    fun `does not redact invalid SSN starting with 000`() {
        assertEquals(
            "000-12-3456",
            service.sanitize("000-12-3456")
        )
    }

    @Test
    fun `does not redact invalid SSN starting with 666`() {
        assertEquals(
            "666-12-3456",
            service.sanitize("666-12-3456")
        )
    }

    @Test
    fun `does not redact invalid SSN starting with 9xx`() {
        assertEquals(
            "900-12-3456",
            service.sanitize("900-12-3456")
        )
    }

    @Test
    fun `does not redact SSN with 00 in middle`() {
        assertEquals(
            "123-00-4567",
            service.sanitize("123-00-4567")
        )
    }

    @Test
    fun `does not redact SSN with 0000 at end`() {
        assertEquals(
            "123-45-0000",
            service.sanitize("123-45-0000")
        )
    }

    @Test
    fun `redacts multiple SSNs`() {
        assertEquals(
            "His: [SSN_REDACTED], Hers: [SSN_REDACTED]",
            service.sanitize("His: 111-22-3333, Hers: 444-55-6666")
        )
    }

    // ==================== Credit Card Tests ====================

    @Test
    fun `redacts Visa with spaces`() {
        assertEquals(
            "Card: [CC_REDACTED]",
            service.sanitize("Card: 4111 1111 1111 1111")
        )
    }

    @Test
    fun `redacts Visa with dashes`() {
        assertEquals(
            "Card: [CC_REDACTED]",
            service.sanitize("Card: 4111-1111-1111-1111")
        )
    }

    @Test
    fun `redacts continuous Visa number`() {
        assertEquals(
            "Card: [CC_REDACTED]",
            service.sanitize("Card: 4111111111111111")
        )
    }

    @Test
    fun `redacts Amex with spaces`() {
        assertEquals(
            "Amex: [CC_REDACTED]",
            service.sanitize("Amex: 3782 822463 10005")
        )
    }

    @Test
    fun `redacts Amex with dashes`() {
        assertEquals(
            "Amex: [CC_REDACTED]",
            service.sanitize("Amex: 3782-822463-10005")
        )
    }

    @Test
    fun `redacts Mastercard continuous`() {
        assertEquals(
            "MC: [CC_REDACTED]",
            service.sanitize("MC: 5500000000000004")
        )
    }

    @Test
    fun `does not redact short digit sequences as credit card`() {
        // 10 digits should NOT match credit card (phone regex may catch it)
        val input = "Order 123456"
        assertEquals(input, service.sanitize(input))
    }

    // ==================== Street Address Tests ====================

    @Test
    fun `redacts simple street address`() {
        assertEquals(
            "I live at [ADDRESS_REDACTED]",
            service.sanitize("I live at 123 Main Street")
        )
    }

    @Test
    fun `redacts address with abbreviated street type`() {
        assertEquals(
            "Office: [ADDRESS_REDACTED]",
            service.sanitize("Office: 456 Oak Ave")
        )
    }

    @Test
    fun `redacts address with boulevard`() {
        assertEquals(
            "Meet at [ADDRESS_REDACTED]",
            service.sanitize("Meet at 789 Sunset Blvd")
        )
    }

    @Test
    fun `redacts address with drive`() {
        assertEquals(
            "She lives at [ADDRESS_REDACTED]",
            service.sanitize("She lives at 1234 Maple Drive")
        )
    }

    @Test
    fun `redacts address with apartment`() {
        assertEquals(
            "Send to [ADDRESS_REDACTED]",
            service.sanitize("Send to 42 Elm St Apt 5B")
        )
    }

    @Test
    fun `redacts address with suite`() {
        assertEquals(
            "Office at [ADDRESS_REDACTED]",
            service.sanitize("Office at 100 Commerce Pkwy Suite 200")
        )
    }

    @Test
    fun `redacts address with multi-word street name`() {
        assertEquals(
            "Located at [ADDRESS_REDACTED]",
            service.sanitize("Located at 500 Martin Luther King Blvd")
        )
    }

    @Test
    fun `redacts address with road`() {
        assertEquals(
            "[ADDRESS_REDACTED] is my home",
            service.sanitize("10 Country Road is my home")
        )
    }

    @Test
    fun `redacts address with lane`() {
        assertEquals(
            "Found at [ADDRESS_REDACTED]",
            service.sanitize("Found at 7 Pine Lane")
        )
    }

    @Test
    fun `redacts address with period after street type`() {
        assertEquals(
            "At [ADDRESS_REDACTED]",
            service.sanitize("At 99 First St.")
        )
    }

    @Test
    fun `does not redact plain text without address pattern`() {
        assertEquals(
            "I went to the park today",
            service.sanitize("I went to the park today")
        )
    }

    // ==================== IPv4 Address Tests ====================

    @Test
    fun `redacts standard IPv4 address`() {
        assertEquals(
            "Server at [IP_REDACTED]",
            service.sanitize("Server at 192.168.1.1")
        )
    }

    @Test
    fun `redacts localhost IP`() {
        assertEquals(
            "Connect to [IP_REDACTED]",
            service.sanitize("Connect to 127.0.0.1")
        )
    }

    @Test
    fun `redacts public IP`() {
        assertEquals(
            "DNS: [IP_REDACTED]",
            service.sanitize("DNS: 8.8.8.8")
        )
    }

    @Test
    fun `redacts IP with max octets`() {
        assertEquals(
            "IP: [IP_REDACTED]",
            service.sanitize("IP: 255.255.255.255")
        )
    }

    @Test
    fun `does not redact invalid IP with octet over 255`() {
        assertEquals(
            "Not IP: 256.1.1.1",
            service.sanitize("Not IP: 256.1.1.1")
        )
    }

    @Test
    fun `does not redact version number that looks like IP`() {
        // "1.2.3" has only 3 octets — should not match
        assertEquals(
            "Version 1.2.3 released",
            service.sanitize("Version 1.2.3 released")
        )
    }

    @Test
    fun `redacts multiple IPs`() {
        assertEquals(
            "From [IP_REDACTED] to [IP_REDACTED]",
            service.sanitize("From 10.0.0.1 to 10.0.0.2")
        )
    }

    // ==================== Mixed PII Tests ====================

    @Test
    fun `redacts phone and email in same text`() {
        assertEquals(
            "Call [PHONE_REDACTED] or email [EMAIL_REDACTED]",
            service.sanitize("Call 555-123-4567 or email admin@test.com")
        )
    }

    @Test
    fun `redacts all PII types in one sentence`() {
        val input = "Name: John, SSN: 123-45-6789, Card: 4111-1111-1111-1111, " +
            "Phone: 555-123-4567, Email: john@test.com, " +
            "Address: 123 Main St, IP: 192.168.1.1"
        val result = service.sanitize(input)
        assert(result.contains("[SSN_REDACTED]")) { "SSN not redacted" }
        assert(result.contains("[CC_REDACTED]")) { "CC not redacted" }
        assert(result.contains("[PHONE_REDACTED]")) { "Phone not redacted" }
        assert(result.contains("[EMAIL_REDACTED]")) { "Email not redacted" }
        assert(result.contains("[ADDRESS_REDACTED]")) { "Address not redacted" }
        assert(result.contains("[IP_REDACTED]")) { "IP not redacted" }
        // Verify no raw PII remains
        assert(!result.contains("123-45-6789")) { "Raw SSN remains" }
        assert(!result.contains("4111")) { "Raw CC remains" }
        assert(!result.contains("555-123-4567")) { "Raw phone remains" }
        assert(!result.contains("john@test.com")) { "Raw email remains" }
        assert(!result.contains("192.168.1.1")) { "Raw IP remains" }
    }

    @Test
    fun `redacts SSN and phone without confusion`() {
        // SSN: 3-2-4 pattern vs Phone: 3-3-4 pattern
        val input = "SSN: 123-45-6789, Phone: 555-123-4567"
        val result = service.sanitize(input)
        assertEquals("SSN: [SSN_REDACTED], Phone: [PHONE_REDACTED]", result)
    }

    // ==================== Passthrough Tests ====================

    @Test
    fun `passes through text without PII`() {
        val input = "Hello, how are you doing today?"
        assertEquals(input, service.sanitize(input))
    }

    @Test
    fun `passes through empty string`() {
        assertEquals("", service.sanitize(""))
    }

    @Test
    fun `passes through text with plain numbers`() {
        val input = "I need 5 apples and 10 oranges"
        assertEquals(input, service.sanitize(input))
    }

    @Test
    fun `passes through realistic voice transcript without PII`() {
        val input = "Can you tell me what the weather is like tomorrow in New York"
        assertEquals(input, service.sanitize(input))
    }

    @Test
    fun `redacts PII from realistic voice transcript`() {
        val transcript = "Hey send an email to john@company.com and tell them my number is 555-867-5309 " +
            "and my social is 123-45-6789 and I live at 42 Oak Lane"
        val result = service.sanitize(transcript)
        assert(!result.contains("john@company.com"))
        assert(!result.contains("555-867-5309"))
        assert(!result.contains("123-45-6789"))
        assert(!result.contains("42 Oak Lane"))
    }
}
