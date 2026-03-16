package com.example.securevoice.data.ml

import android.content.Context
import android.util.Log
import com.example.securevoice.domain.repository.TranscriptionRepository
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full Whisper Tiny transcription pipeline:
 * raw 16kHz audio → encoder (mel baked in) → decoder (greedy) → text.
 *
 * All heavy computation runs on Dispatchers.Default.
 */
@Singleton
class TranscriptionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tfliteManager: TfliteManager,
    private val tokenDecoder: TokenDecoder
) : TranscriptionRepository {

    companion object {
        private const val TAG = "Transcription"
        private const val MAX_DECODER_LEN = 128
        private const val CONFIG_FILE = "whisper_config.json"
    }

    private val config: WhisperConfig by lazy { loadConfig() }

    private data class WhisperConfig(
        val sot: Int = 50258,
        val eot: Int = 50257,
        val transcribe: Int = 50359,
        val no_timestamps: Int = 50363,
        val en: Int = 50259
    )

    private fun loadConfig(): WhisperConfig {
        return try {
            val json = context.assets.open(CONFIG_FILE).bufferedReader().use { it.readText() }
            Gson().fromJson(json, WhisperConfig::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Config not found, using defaults", e)
            WhisperConfig()
        }
    }

    override suspend fun transcribe(audio: FloatArray): String = withContext(Dispatchers.Default) {
        if (!tfliteManager.isModelLoaded) {
            tfliteManager.initialize()
        }

        if (!tfliteManager.isModelLoaded) {
            throw IllegalStateException(
                "Whisper model not available. Place encoder and decoder .tflite files in assets/"
            )
        }

        Log.d(TAG, "Step 1: Running encoder (mel + transformer)...")
        val encoderOutput = tfliteManager.runEncoder(audio)

        Log.d(TAG, "Step 2: Greedy decoding...")
        val tokenIds = greedyDecode(encoderOutput)

        Log.d(TAG, "Step 3: Decoding tokens to text (${tokenIds.size} tokens)...")
        val text = tokenDecoder.decode(tokenIds)

        Log.d(TAG, "Transcription: \"$text\"")
        text
    }

    private fun greedyDecode(encoderOutput: FloatArray): List<Int> {
        val prompt = mutableListOf(
            config.sot,
            config.en,
            config.transcribe,
            config.no_timestamps
        )

        val generatedTokens = mutableListOf<Int>()
        val maxGenerateTokens = MAX_DECODER_LEN - prompt.size

        for (step in 0 until maxGenerateTokens) {
            val decoderInput = IntArray(MAX_DECODER_LEN)
            val currentTokens = prompt + generatedTokens
            for (i in currentTokens.indices) {
                decoderInput[i] = currentTokens[i]
            }

            val nextToken = tfliteManager.runDecoderStep(
                encoderOutput = encoderOutput,
                decoderInputIds = decoderInput,
                activeLength = currentTokens.size
            )

            if (nextToken == config.eot) break

            generatedTokens.add(nextToken)
        }

        return generatedTokens
    }
}
