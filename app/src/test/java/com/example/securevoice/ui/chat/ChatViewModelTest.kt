package com.example.securevoice.ui.chat

import app.cash.turbine.test
import com.example.securevoice.domain.model.StreamEvent
import com.example.securevoice.domain.repository.AudioRepository
import com.example.securevoice.domain.repository.TranscriptionRepository
import com.example.securevoice.domain.usecase.StreamLlmResponseUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var audioRepository: AudioRepository
    private lateinit var transcriptionRepository: TranscriptionRepository
    private lateinit var streamLlmResponseUseCase: StreamLlmResponseUseCase
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        audioRepository = mockk(relaxed = true) {
            every { isRecording } returns MutableStateFlow(false)
            every { startRecording() } returns true
        }
        transcriptionRepository = mockk()
        streamLlmResponseUseCase = mockk()

        viewModel = ChatViewModel(
            audioRepository = audioRepository,
            transcriptionRepository = transcriptionRepository,
            streamLlmResponseUseCase = streamLlmResponseUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isRecording)
            assertFalse(state.isProcessing)
            assertFalse(state.isStreaming)
            assertTrue(state.messages.isEmpty())
            assertEquals("", state.partialResponse)
            assertNull(state.error)
            assertFalse(state.hasAudioPermission)
        }
    }

    @Test
    fun `start recording requires permission`() = runTest {
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isRecording)
            assertEquals("Audio permission required", state.error)
        }
    }

    @Test
    fun `start recording with permission sets isRecording true`() = runTest {
        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()

        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isRecording)
            assertTrue(state.hasAudioPermission)
        }
    }

    @Test
    fun `stop recording triggers processing`() = runTest {
        // Grant permission and start recording
        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        // Setup transcription and streaming mocks
        every { audioRepository.getAudioSnapshot() } returns FloatArray(480_000)
        coEvery { transcriptionRepository.transcribe(any()) } returns "Hello world"
        every { streamLlmResponseUseCase("Hello world") } returns flowOf(
            StreamEvent.Token("Hi"),
            StreamEvent.Token(" there"),
            StreamEvent.Done
        )

        // Stop recording
        viewModel.handleIntent(ChatIntent.StopRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isRecording)
            assertFalse(state.isProcessing)
            assertFalse(state.isStreaming)
            assertEquals(2, state.messages.size) // user + assistant
            assertEquals("Hello world", state.messages[0].content)
            assertEquals("Hi there", state.messages[1].content)
        }
    }

    @Test
    fun `permission denied sets error`() = runTest {
        viewModel.handleIntent(ChatIntent.PermissionResult(false))
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.hasAudioPermission)
            assertEquals("Microphone permission is required for voice input", state.error)
        }
    }

    @Test
    fun `dismiss error clears error`() = runTest {
        viewModel.handleIntent(ChatIntent.StartRecording) // triggers error without permission
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.DismissError)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.error)
        }
    }

    @Test
    fun `transcription error sets error state`() = runTest {
        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        every { audioRepository.getAudioSnapshot() } returns FloatArray(480_000)
        coEvery { transcriptionRepository.transcribe(any()) } throws RuntimeException("Model not loaded")

        viewModel.handleIntent(ChatIntent.StopRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertFalse(state.isStreaming)
            assertEquals("Model not loaded", state.error)
        }
    }

    @Test
    fun `streaming error preserves partial content`() = runTest {
        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        every { audioRepository.getAudioSnapshot() } returns FloatArray(480_000)
        coEvery { transcriptionRepository.transcribe(any()) } returns "Test"
        every { streamLlmResponseUseCase("Test") } returns flowOf(
            StreamEvent.Token("Partial"),
            StreamEvent.Token(" response"),
            StreamEvent.Error("Connection lost")
        )

        viewModel.handleIntent(ChatIntent.StopRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isStreaming)
            assertEquals("Connection lost", state.error)
            // Partial content should be saved as a message
            assertEquals(2, state.messages.size) // user + partial assistant
            assertEquals("Partial response", state.messages[1].content)
        }
    }

    @Test
    fun `blank transcript sets no speech error`() = runTest {
        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        every { audioRepository.getAudioSnapshot() } returns FloatArray(480_000)
        coEvery { transcriptionRepository.transcribe(any()) } returns ""

        viewModel.handleIntent(ChatIntent.StopRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isProcessing)
            assertEquals("No speech detected", state.error)
            assertTrue(state.messages.isEmpty())
        }
    }

    @Test
    fun `cannot start recording while already recording`() = runTest {
        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        // Second start should be no-op
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        // startRecording should only be called once
        verify(exactly = 1) { audioRepository.startRecording() }
    }

    @Test
    fun `start recording failure shows microphone error`() = runTest {
        every { audioRepository.startRecording() } returns false

        viewModel.handleIntent(ChatIntent.PermissionResult(true))
        advanceUntilIdle()
        viewModel.handleIntent(ChatIntent.StartRecording)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isRecording)
            assertEquals("Failed to access microphone", state.error)
        }
    }
}
