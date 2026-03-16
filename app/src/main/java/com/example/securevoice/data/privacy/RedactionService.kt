package com.example.securevoice.data.privacy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Regex-based PII redaction gate.
 *
 * All text passes through this service BEFORE network transmission.
 * There is no bypass path in the pipeline — RedactionService is mandatory.
 *
 * Supported PII types:
 * - Phone numbers (US 10-digit and 7-digit with various formats)
 * - Email addresses
 * - Social Security Numbers (SSN)
 * - Credit card numbers (13-19 digits, grouped or continuous)
 * - US street addresses (number + street name + street type)
 * - IPv4 addresses
 */
@Singleton
class RedactionService @Inject constructor() {

    companion object {
        // Matches 10-digit: +1-555-123-4567, 555.123.4567, (555) 123-4567, 555 123 4567
        // Matches 7-digit: 555-0199
        private val PHONE_REGEX = Regex(
            """(?<!\w)(?:\+\d{1,3}[- .]?)?\(?\d{3}\)?[- .]?\d{3}[- .]?\d{4}(?!\w)|\b\d{3}[- .]\d{4}\b"""
        )

        // Matches: user@domain.com, test.name@subdomain.co.uk
        private val EMAIL_REGEX = Regex(
            """[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""
        )

        // Matches SSN: 123-45-6789, 123 45 6789
        // Does NOT match 000-xx-xxxx, xxx-00-xxxx, xxx-xx-0000 (invalid SSNs)
        private val SSN_REGEX = Regex(
            """\b(?!000|666|9\d{2})\d{3}[- ](?!00)\d{2}[- ](?!0000)\d{4}\b"""
        )

        // Matches credit card numbers in common formats:
        // - 4 groups of 4 digits: 4111 1111 1111 1111 or 4111-1111-1111-1111
        // - Amex format: 3782 822463 10005 or 3782-822463-10005
        // - Continuous 13-19 digits: 4111111111111111
        private val CREDIT_CARD_REGEX = Regex(
            """\b\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{1,7}\b|\b\d{4}[- ]\d{6}[- ]\d{4,5}\b|\b\d{13,19}\b"""
        )

        // Matches US street addresses: "123 Main Street", "4567 Oak Ave", "89 Elm Blvd Apt 4B"
        // Pattern: 1-5 digit house number + street name words + street type suffix
        private val STREET_TYPES = listOf(
            "Street", "St", "Avenue", "Ave", "Boulevard", "Blvd",
            "Drive", "Dr", "Court", "Ct", "Road", "Rd", "Lane", "Ln",
            "Way", "Place", "Pl", "Circle", "Cir", "Trail", "Trl",
            "Parkway", "Pkwy", "Highway", "Hwy", "Terrace", "Ter"
        ).joinToString("|")

        private val ADDRESS_REGEX = Regex(
            """\b\d{1,5}\s+(?:[A-Z][a-z]+\s+){0,3}(?:$STREET_TYPES)\b\.?(?:\s+(?:Apt|Suite|Ste|Unit|#)\s*\w+)?""",
            RegexOption.IGNORE_CASE
        )

        // Matches IPv4 addresses: 192.168.1.1, 10.0.0.1
        // Each octet: 0-255
        private val IPV4_REGEX = Regex(
            """\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b"""
        )

        const val PHONE_PLACEHOLDER = "[PHONE_REDACTED]"
        const val EMAIL_PLACEHOLDER = "[EMAIL_REDACTED]"
        const val SSN_PLACEHOLDER = "[SSN_REDACTED]"
        const val CREDIT_CARD_PLACEHOLDER = "[CC_REDACTED]"
        const val ADDRESS_PLACEHOLDER = "[ADDRESS_REDACTED]"
        const val IP_PLACEHOLDER = "[IP_REDACTED]"
    }

    fun sanitize(text: String): String {
        var safe = text
        // Order matters: more specific patterns first to avoid partial matches
        safe = safe.replace(SSN_REGEX, SSN_PLACEHOLDER)
        safe = safe.replace(CREDIT_CARD_REGEX, CREDIT_CARD_PLACEHOLDER)
        safe = safe.replace(PHONE_REGEX, PHONE_PLACEHOLDER)
        safe = safe.replace(EMAIL_REGEX, EMAIL_PLACEHOLDER)
        safe = safe.replace(ADDRESS_REGEX, ADDRESS_PLACEHOLDER)
        safe = safe.replace(IPV4_REGEX, IP_PLACEHOLDER)
        return safe
    }
}
