package com.example.securevoice.di

import com.example.securevoice.BuildConfig
import com.example.securevoice.data.network.LlmClient
import com.example.securevoice.data.network.LlmRepositoryImpl
import com.example.securevoice.domain.repository.LlmRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for SSE streams
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideLlmClient(httpClient: OkHttpClient): LlmClient {
        return LlmClient(httpClient, BuildConfig.ANTHROPIC_API_KEY)
    }

    @Provides
    @Singleton
    fun provideLlmRepository(llmClient: LlmClient): LlmRepository {
        return LlmRepositoryImpl(llmClient)
    }
}
