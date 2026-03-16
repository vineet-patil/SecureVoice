package com.example.securevoice.data.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes Whisper token IDs into text using vocab.json.
 *
 * vocab.json maps string tokens to integer IDs: {"hello": 1234, ...}
 * This class inverts the map to decode output: {1234: "hello", ...}
 */
@Singleton
class TokenDecoder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TokenDecoder"
        private const val VOCAB_FILE = "vocab.json"

        // Whisper special tokens to strip from output
        private val SPECIAL_TOKENS = setOf(
            "<|startoftranscript|>",
            "<|endoftext|>",
            "<|notimestamps|>",
            "<|transcribe|>",
            "<|translate|>",
            "<|en|>",
            "<|startoflm|>",
            "<|startofprev|>"
        )
    }

    private val idToToken: Map<Int, String> by lazy { loadVocab() }

    private fun loadVocab(): Map<Int, String> {
        return try {
            val json = context.assets.open(VOCAB_FILE).bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Int>>() {}.type
            val tokenToId: Map<String, Int> = Gson().fromJson(json, type)
            tokenToId.entries.associate { (token, id) -> id to token }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab.json", e)
            emptyMap()
        }
    }

    // Whisper noise/non-speech tokens to strip from final output
    private val NOISE_PATTERN = Regex(
        """\[[\w\s]*\]""" // matches [Music], [BLANK_AUDIO], [bell], [applause], etc.
    )

    /**
     * Decodes a list of token IDs into text.
     * Strips special tokens, noise markers, and joins with Whisper's Ġ→space convention.
     */
    fun decode(tokenIds: List<Int>): String {
        if (idToToken.isEmpty()) return "[vocab not loaded]"

        val tokens = mutableListOf<String>()
        for (id in tokenIds) {
            val token = idToToken[id] ?: continue
            if (token in SPECIAL_TOKENS) continue
            if (token.startsWith("<|") && token.endsWith("|>")) continue
            tokens.add(token)
        }
        return tokens.joinToString("")
            .replace("Ġ", " ") // Whisper uses Ġ for space prefix
            .replace(NOISE_PATTERN, "") // strip [Music], [BLANK_AUDIO], etc.
            .trim()
    }
}
