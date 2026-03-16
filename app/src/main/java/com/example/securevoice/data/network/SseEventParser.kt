package com.example.securevoice.data.network

import android.util.Log
import com.example.securevoice.domain.model.StreamEvent
import com.google.gson.JsonParser

/**
 * Parses Anthropic API SSE event data into StreamEvent objects.
 *
 * Expected event formats:
 *   data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 *   data: {"type":"message_stop"}
 */
object SseEventParser {

    private const val TAG = "SseEventParser"

    fun parse(data: String): StreamEvent? {
        if (data.isBlank() || data == "[DONE]") return StreamEvent.Done

        return try {
            val json = JsonParser.parseString(data).asJsonObject
            when (json.get("type")?.asString) {
                "content_block_delta" -> {
                    val delta = json.getAsJsonObject("delta")
                    val text = delta?.get("text")?.asString
                    if (text != null) StreamEvent.Token(text) else null
                }
                "message_stop" -> StreamEvent.Done
                "message_start", "content_block_start", "content_block_stop", "ping" -> null // Control events, skip
                "error" -> {
                    val error = json.getAsJsonObject("error")
                    val message = error?.get("message")?.asString ?: "Unknown API error"
                    StreamEvent.Error(message)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE event: $data", e)
            null
        }
    }
}
