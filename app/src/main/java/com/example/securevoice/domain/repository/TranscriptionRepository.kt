package com.example.securevoice.domain.repository

interface TranscriptionRepository {
    suspend fun transcribe(audio: FloatArray): String
}
