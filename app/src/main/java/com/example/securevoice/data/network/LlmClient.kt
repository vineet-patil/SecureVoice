package com.example.securevoice.data.network

import android.util.Log
import com.example.securevoice.domain.model.StreamEvent
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class LlmClient(
    private val httpClient: OkHttpClient,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "LlmClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MODEL = "claude-sonnet-4-5-20250929"
        private const val MAX_TOKENS = 1024
    }

    private val gson = Gson()

    fun stream(prompt: String): Flow<StreamEvent> = callbackFlow {
        val requestMap = mapOf(
            "model" to MODEL,
            "max_tokens" to MAX_TOKENS,
            "stream" to true,
            "messages" to listOf(
                mapOf("role" to "user", "content" to prompt)
            )
        )
        val jsonBody = gson.toJson(requestMap)

        Log.d(TAG, "Sending request to $API_URL with model=$MODEL")

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val eventSourceFactory = EventSources.createFactory(httpClient)

        val eventSource = eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.d(TAG, "SSE connection opened, HTTP ${response.code}")
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    val event = SseEventParser.parse(data) ?: return
                    trySend(event)
                    if (event is StreamEvent.Done) {
                        close()
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    Log.e(TAG, "SSE failure: code=${response?.code}, message=${response?.message}", t)
                    var errorBody: String? = null
                    if (response != null) {
                        try {
                            errorBody = response.body?.string()
                            Log.e(TAG, "Response body: $errorBody")
                        } catch (_: Exception) {}
                    }

                    val message = when (response?.code) {
                        400 -> parseApiError(errorBody) ?: "Bad request (check API key and model)."
                        401 -> "Invalid API key. Add ANTHROPIC_API_KEY to local.properties."
                        403 -> parseApiError(errorBody) ?: "Access denied."
                        429 -> "Rate limited. Please try again in a moment."
                        500, 502, 503 -> "Anthropic API is temporarily unavailable."
                        else -> t?.message ?: "Connection failed (code=${response?.code})"
                    }
                    trySend(StreamEvent.Error(message))
                    close()
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.d(TAG, "SSE connection closed")
                    close()
                }
            }
        )

        awaitClose {
            eventSource.cancel()
        }
    }

    /**
     * Extracts the human-readable error message from Anthropic's JSON error response.
     * Format: {"type":"error","error":{"type":"...","message":"..."}}
     */
    private fun parseApiError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("error")?.get("message")?.asString
        } catch (_: Exception) {
            null
        }
    }
}
