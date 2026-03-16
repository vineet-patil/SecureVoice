package com.example.securevoice.data.audio

import com.example.securevoice.domain.repository.AudioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepositoryImpl @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val ringBuffer: AudioRingBuffer
) : AudioRepository {

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    override fun startRecording(): Boolean {
        val started = audioRecorder.start()
        _isRecording.value = started
        return started
    }

    override fun stopRecording() {
        audioRecorder.stop()
        _isRecording.value = false
    }

    override fun getAudioSnapshot(): FloatArray {
        return ringBuffer.read()
    }
}
