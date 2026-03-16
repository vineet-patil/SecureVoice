package com.example.securevoice.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures 16kHz mono PCM audio and feeds it into the AudioRingBuffer.
 *
 * Uses a dedicated HandlerThread to avoid competing with IO/Default dispatchers
 * for thread time. Audio recording is real-time — delayed reads cause buffer
 * overruns and lost samples.
 */
@Singleton
class AudioRecorder @Inject constructor(
    private val ringBuffer: AudioRingBuffer
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var isActive = false

    val minBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isActive) return true

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2) // At least 1 second of buffer
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        ringBuffer.clear()
        isActive = true

        recordingThread = HandlerThread("AudioRecorderThread").also { it.start() }
        handler = Handler(recordingThread!!.looper)

        audioRecord?.startRecording()
        handler?.post(::readLoop)
        return true
    }

    fun stop() {
        isActive = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop() failed", e)
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread?.quitSafely()
        recordingThread = null
        handler = null
    }

    private fun readLoop() {
        val chunk = ByteArray(minBufferSize)
        while (isActive) {
            val bytesRead = audioRecord?.read(chunk, 0, chunk.size) ?: -1
            if (bytesRead > 0) {
                ringBuffer.write(chunk, bytesRead)
            } else if (bytesRead < 0) {
                Log.e(TAG, "AudioRecord.read() returned error: $bytesRead")
                break
            }
        }
    }
}
