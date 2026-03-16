package com.example.securevoice.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioRingBufferTest {

    private lateinit var buffer: AudioRingBuffer

    @Before
    fun setup() {
        buffer = AudioRingBuffer()
    }

    @Test
    fun `new buffer is empty`() {
        assertEquals(0, buffer.size())
        assertFalse(buffer.isFull())
    }

    @Test
    fun `write and read single sample`() {
        // 16-bit PCM: 0x7FFF = 32767 -> normalized to ~1.0
        val chunk = byteArrayOf(0xFF.toByte(), 0x7F)
        buffer.write(chunk, 2)

        assertEquals(1, buffer.size())
        val data = buffer.read()
        assertEquals(32767.0f / 32768.0f, data[0], 0.0001f)
    }

    @Test
    fun `write silence produces zero samples`() {
        val chunk = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        buffer.write(chunk, 4)

        assertEquals(2, buffer.size())
        val data = buffer.read()
        assertEquals(0.0f, data[0], 0.0001f)
        assertEquals(0.0f, data[1], 0.0001f)
    }

    @Test
    fun `write negative sample converts correctly`() {
        // 16-bit PCM: 0x8000 = -32768 -> normalized to -1.0
        val chunk = byteArrayOf(0x00, 0x80.toByte())
        buffer.write(chunk, 2)

        val data = buffer.read()
        assertEquals(-1.0f, data[0], 0.0001f)
    }

    @Test
    fun `read returns chronological order before wrap-around`() {
        val sample1 = byteArrayOf(0x00, 0x10) // positive
        val sample2 = byteArrayOf(0x00, 0x20) // larger positive

        buffer.write(sample1, 2)
        buffer.write(sample2, 2)

        val data = buffer.read()
        assertTrue(data[0] < data[1]) // First sample < second sample
    }

    @Test
    fun `buffer reports full after capacity exceeded`() {
        // Fill the buffer completely
        val largeChunk = ByteArray(AudioRingBuffer.CAPACITY * 2) // Each sample is 2 bytes
        buffer.write(largeChunk, largeChunk.size)

        assertTrue(buffer.isFull())
        assertEquals(AudioRingBuffer.CAPACITY, buffer.size())
    }

    @Test
    fun `read returns chronological order after wrap-around`() {
        // Create a small buffer for testing wrap-around behavior
        // We'll use the full buffer but write known patterns
        val capacity = AudioRingBuffer.CAPACITY

        // Fill the buffer completely with zeros
        val zeros = ByteArray(capacity * 2)
        buffer.write(zeros, zeros.size)
        assertTrue(buffer.isFull())

        // Write a few more samples - these should overwrite the oldest
        // PCM value 0x0100 = 256 -> normalized to 256/32768 ~ 0.0078
        val overwrite = byteArrayOf(0x00, 0x01)
        buffer.write(overwrite, 2)

        val data = buffer.read()
        // The overwritten sample should be at the END (newest), not the beginning
        assertEquals(256.0f / 32768.0f, data[capacity - 1], 0.0001f)
        // The oldest samples should still be zero
        assertEquals(0.0f, data[0], 0.0001f)
    }

    @Test
    fun `clear resets buffer to empty state`() {
        val chunk = ByteArray(100)
        buffer.write(chunk, chunk.size)
        assertTrue(buffer.size() > 0)

        buffer.clear()

        assertEquals(0, buffer.size())
        assertFalse(buffer.isFull())
    }

    @Test
    fun `odd byte count ignores trailing byte`() {
        // Write 3 bytes — should only process the first 2
        val chunk = byteArrayOf(0x00, 0x10, 0xFF.toByte())
        buffer.write(chunk, 3)

        assertEquals(1, buffer.size())
    }

    @Test
    fun `read on empty buffer returns all zeros`() {
        val data = buffer.read()
        assertEquals(AudioRingBuffer.CAPACITY, data.size)
        assertArrayEquals(FloatArray(AudioRingBuffer.CAPACITY), data, 0.0f)
    }

    @Test
    fun `concurrent write and read do not throw`() {
        val writeThread = Thread {
            val chunk = ByteArray(1024)
            repeat(1000) {
                buffer.write(chunk, chunk.size)
            }
        }
        val readThread = Thread {
            repeat(1000) {
                buffer.read()
            }
        }

        writeThread.start()
        readThread.start()
        writeThread.join(5000)
        readThread.join(5000)

        // No exception = pass
    }
}
