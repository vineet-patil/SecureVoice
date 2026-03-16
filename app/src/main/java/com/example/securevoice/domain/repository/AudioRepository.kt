package com.example.securevoice.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface AudioRepository {
    val isRecording: StateFlow<Boolean>
    fun startRecording(): Boolean
    fun stopRecording()
    fun getAudioSnapshot(): FloatArray
}
