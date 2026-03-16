package com.example.securevoice.data.ml

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages TFLite Whisper Tiny encoder and decoder interpreters.
 *
 * Encoder: raw audio [480000] → hidden states [1, 1500, 384]
 *   (mel spectrogram computation is baked into the encoder model)
 * Decoder: hidden states [1, 1500, 384] + token IDs [1, 128] → logits [1, 128, 51865]
 *
 * Greedy decoding loop runs in Kotlin, calling the decoder iteratively.
 *
 * Buffers are pre-allocated once during initialize() and reused across calls
 * to avoid repeated ~26 MB allocations in the decoder step.
 */
@Singleton
class TfliteManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TfliteManager"
        private const val ENCODER_FILE = "whisper-tiny-int8.tflite"
        private const val DECODER_FILE = "whisper-tiny-decoder-int8.tflite"
        private const val ENCODER_HIDDEN_DIM = 384
        private const val ENCODER_SEQ_LEN = 1500
        private const val MAX_DECODER_LEN = 128
        private const val AUDIO_SAMPLES = 480_000 // 30s @ 16kHz
        private const val VOCAB_SIZE = 51865
    }

    private var encoder: Interpreter? = null
    private var decoder: Interpreter? = null
    private var initError: String? = null

    // Pre-allocated buffers (created once in initialize(), reused every call)
    private var encoderInputBuffer: ByteBuffer? = null
    private var encoderOutputBuffer: ByteBuffer? = null
    private var decoderEncoderBuffer: ByteBuffer? = null
    private var decoderIdsBuffer: ByteBuffer? = null
    private var decoderOutputBuffer: ByteBuffer? = null

    val isModelLoaded: Boolean get() = encoder != null && decoder != null

    @Synchronized
    fun initialize() {
        if (encoder != null && decoder != null) return

        try {
            val encoderModel = loadModelFile(ENCODER_FILE)
            encoder = Interpreter(encoderModel, Interpreter.Options().apply {
                setNumThreads(4)
            })
            Log.i(TAG, "Encoder loaded successfully")

            val decoderModel = loadModelFile(DECODER_FILE)
            decoder = Interpreter(decoderModel, Interpreter.Options().apply {
                setNumThreads(4)
            })
            Log.i(TAG, "Decoder loaded successfully")

            allocateBuffers()
            Log.i(TAG, "Inference buffers pre-allocated")
        } catch (e: Exception) {
            initError = e.message
            Log.e(TAG, "Failed to load TFLite models", e)
        }
    }

    private fun allocateBuffers() {
        val encoderOutputSize = ENCODER_SEQ_LEN * ENCODER_HIDDEN_DIM

        encoderInputBuffer = ByteBuffer.allocateDirect(AUDIO_SAMPLES * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        encoderOutputBuffer = ByteBuffer.allocateDirect(encoderOutputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        decoderEncoderBuffer = ByteBuffer.allocateDirect(encoderOutputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        decoderIdsBuffer = ByteBuffer.allocateDirect(MAX_DECODER_LEN * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        decoderOutputBuffer = ByteBuffer.allocateDirect(MAX_DECODER_LEN * VOCAB_SIZE * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        return FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /**
     * Runs the encoder on raw audio (mel computation is baked into the model).
     * BLOCKING: call from Dispatchers.Default.
     *
     * @param audioData float array of 480,000 samples (30s @ 16kHz), zero-padded if shorter
     * @return flattened [1, 1500, 384] float array of encoder hidden states
     */
    fun runEncoder(audioData: FloatArray): FloatArray {
        val enc = encoder
            ?: throw IllegalStateException("Encoder not loaded. Error: ${initError ?: "call initialize() first"}")
        val inBuf = encoderInputBuffer
            ?: throw IllegalStateException("Buffers not allocated")
        val outBuf = encoderOutputBuffer
            ?: throw IllegalStateException("Buffers not allocated")

        // Pad or trim to exactly 480,000 samples
        val input = if (audioData.size == AUDIO_SAMPLES) audioData
                    else FloatArray(AUDIO_SAMPLES).also { audioData.copyInto(it, endIndex = minOf(audioData.size, AUDIO_SAMPLES)) }

        inBuf.clear()
        inBuf.asFloatBuffer().put(input)

        outBuf.clear()

        enc.run(inBuf, outBuf)

        outBuf.rewind()
        val outputSize = ENCODER_SEQ_LEN * ENCODER_HIDDEN_DIM
        val output = FloatArray(outputSize)
        outBuf.asFloatBuffer().get(output)
        return output
    }

    /**
     * Runs one decoder step: given encoder hidden states and token IDs, returns logits.
     * BLOCKING: call from Dispatchers.Default.
     *
     * @param encoderOutput flattened [1, 1500, 384] from runEncoder
     * @param decoderInputIds int array of length MAX_DECODER_LEN (zero-padded)
     * @param activeLength number of valid tokens in decoderInputIds (for extracting logits at the right position)
     * @return int token ID predicted at position [activeLength - 1]
     */
    fun runDecoderStep(
        encoderOutput: FloatArray,
        decoderInputIds: IntArray,
        activeLength: Int
    ): Int {
        val dec = decoder
            ?: throw IllegalStateException("Decoder not loaded. Error: ${initError ?: "call initialize() first"}")
        val encBuf = decoderEncoderBuffer
            ?: throw IllegalStateException("Buffers not allocated")
        val idsBuf = decoderIdsBuffer
            ?: throw IllegalStateException("Buffers not allocated")
        val outBuf = decoderOutputBuffer
            ?: throw IllegalStateException("Buffers not allocated")

        // Input 0: encoder hidden states [1, 1500, 384]
        encBuf.clear()
        encBuf.asFloatBuffer().put(encoderOutput)

        // Input 1: decoder input IDs [1, 128]
        idsBuf.clear()
        idsBuf.asIntBuffer().put(decoderInputIds)

        // Output: logits [1, 128, vocab_size]
        outBuf.clear()

        // TFLite multi-input: use runForMultipleInputsOutputs
        val inputs = arrayOf(encBuf, idsBuf)
        val outputs = mutableMapOf<Int, Any>(0 to outBuf)
        dec.runForMultipleInputsOutputs(inputs, outputs)

        // Extract logits at position (activeLength - 1) and find argmax
        outBuf.rewind()
        val floatBuf = outBuf.asFloatBuffer()
        val logitsOffset = (activeLength - 1) * VOCAB_SIZE
        var maxVal = -Float.MAX_VALUE
        var maxIdx = 0
        for (v in 0 until VOCAB_SIZE) {
            val logit = floatBuf.get(logitsOffset + v)
            if (logit > maxVal) {
                maxVal = logit
                maxIdx = v
            }
        }
        return maxIdx
    }

    @Synchronized
    fun release() {
        encoder?.close()
        encoder = null
        decoder?.close()
        decoder = null
        encoderInputBuffer = null
        encoderOutputBuffer = null
        decoderEncoderBuffer = null
        decoderIdsBuffer = null
        decoderOutputBuffer = null
    }
}
