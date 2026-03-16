package com.example.securevoice.data.network

import com.example.securevoice.domain.model.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseEventParserTest {

    @Test
    fun `parses content_block_delta with text`() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        val event = SseEventParser.parse(data)
        assertTrue(event is StreamEvent.Token)
        assertEquals("Hello", (event as StreamEvent.Token).text)
    }

    @Test
    fun `parses message_stop as Done`() {
        val data = """{"type":"message_stop"}"""
        val event = SseEventParser.parse(data)
        assertTrue(event is StreamEvent.Done)
    }

    @Test
    fun `parses DONE marker as Done`() {
        val event = SseEventParser.parse("[DONE]")
        assertTrue(event is StreamEvent.Done)
    }

    @Test
    fun `parses blank data as Done`() {
        val event = SseEventParser.parse("")
        assertTrue(event is StreamEvent.Done)
    }

    @Test
    fun `parses error event`() {
        val data = """{"type":"error","error":{"type":"rate_limit_error","message":"Rate limited"}}"""
        val event = SseEventParser.parse(data)
        assertTrue(event is StreamEvent.Error)
        assertEquals("Rate limited", (event as StreamEvent.Error).message)
    }

    @Test
    fun `skips message_start event`() {
        val data = """{"type":"message_start","message":{"id":"msg_123","type":"message"}}"""
        val event = SseEventParser.parse(data)
        assertNull(event)
    }

    @Test
    fun `skips content_block_start event`() {
        val data = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        val event = SseEventParser.parse(data)
        assertNull(event)
    }

    @Test
    fun `skips ping event`() {
        val data = """{"type":"ping"}"""
        val event = SseEventParser.parse(data)
        assertNull(event)
    }

    @Test
    fun `handles malformed JSON gracefully`() {
        val data = """not valid json{{{"""
        val event = SseEventParser.parse(data)
        assertNull(event)
    }

    @Test
    fun `handles delta with missing text field`() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta"}}"""
        val event = SseEventParser.parse(data)
        assertNull(event)
    }

    @Test
    fun `parses multi-word token`() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello world!"}}"""
        val event = SseEventParser.parse(data)
        assertTrue(event is StreamEvent.Token)
        assertEquals("Hello world!", (event as StreamEvent.Token).text)
    }

    @Test
    fun `parses token with special characters`() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"line1\nline2"}}"""
        val event = SseEventParser.parse(data)
        assertTrue(event is StreamEvent.Token)
    }
}
