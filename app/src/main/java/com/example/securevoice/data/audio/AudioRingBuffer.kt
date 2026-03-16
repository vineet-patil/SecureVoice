package com.example.securevoice.data.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe circular buffer for audio samples.
 *
 * Capacity: 480,000 floats = 30 seconds @ 16kHz mono.
 * Producer: audio recording thread (write).
 * Consumer: ML inference thread (read).
 *
 * Synchronization uses intrinsic locks. Contention is negligible because
 * reads occur infrequently (once per inference cycle, every few seconds)
 * while writes happen every ~64ms.
 */
@Singleton
class AudioRingBuffer @Inject constructor() {

    companion object {
        // 16kHz * 30 seconds = 480,000 samples
        const val CAPACITY = 480_000
    }

    private val buffer = FloatArray(CAPACITY)
    private var head = 0
    private var isFull = false

    /**
     * Writes raw 16-bit PCM bytes into the buffer as normalized floats.
     * Each sample is 2 bytes (little-endian signed 16-bit), normalized to [-1.0, 1.0].
     *
     * @param chunk raw PCM byte array from AudioRecord
     * @param size number of valid bytes in chunk (must be even)
     */
    @Synchronized
    fun write(chunk: ByteArray, size: Int) {
        var i = 0
        while (i < size - 1) {
            val low = chunk[i].toInt() and 0xFF
            val high = chunk[i + 1].toInt() shl 8
            val sampleShort = (high or low).toShort()

            buffer[head] = sampleShort / 32768.0f
            head++

            if (head >= CAPACITY) {
                head = 0
                isFull = true
            }
            i += 2
        }
    }

    /**
     * Returns a chronologically ordered copy of the buffer contents.
     * The oldest sample is at index 0, the newest at the end.
     * Safe to call from any thread.
     */
    @Synchronized
    fun read(): FloatArray {
        val output = FloatArray(CAPACITY)
        if (!isFull) {
            System.arraycopy(buffer, 0, output, 0, head)
        } else {
            val tailLength = CAPACITY - head
            System.arraycopy(buffer, head, output, 0, tailLength)
            System.arraycopy(buffer, 0, output, tailLength, head)
        }
        return output
    }

    /**
     * Resets the buffer to its initial empty state.
     */
    @Synchronized
    fun clear() {
        buffer.fill(0f)
        head = 0
        isFull = false
    }

    /**
     * Returns true if the buffer has been completely filled at least once.
     */
    @Synchronized
    fun isFull(): Boolean = isFull

    /**
     * Returns the number of valid samples currently in the buffer.
     */
    @Synchronized
    fun size(): Int = if (isFull) CAPACITY else head
}
