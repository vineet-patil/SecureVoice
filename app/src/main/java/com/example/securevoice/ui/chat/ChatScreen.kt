package com.example.securevoice.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.securevoice.ui.chat.components.ChatBubble
import com.example.securevoice.ui.chat.components.EmptyState
import com.example.securevoice.ui.chat.components.MicButton
import com.example.securevoice.ui.chat.components.StreamingBubble
import com.example.securevoice.ui.permission.AudioPermissionHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Request audio permission on first composition
    AudioPermissionHandler { granted ->
        viewModel.handleIntent(ChatIntent.PermissionResult(granted))
    }

    // Auto-scroll to bottom when new messages or streaming text arrives
    LaunchedEffect(state.messages.size, state.partialResponse) {
        val totalItems = state.messages.size + (if (state.partialResponse.isNotEmpty()) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    // Show error snackbar
    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.handleIntent(ChatIntent.DismissError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SecureVoice",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        bottomBar = {
            BottomBar(
                isRecording = state.isRecording,
                isProcessing = state.isProcessing,
                isStreaming = state.isStreaming,
                onMicClick = {
                    if (state.isRecording) {
                        viewModel.handleIntent(ChatIntent.StopRecording)
                    } else {
                        viewModel.handleIntent(ChatIntent.StartRecording)
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            if (state.messages.isEmpty() && state.partialResponse.isEmpty() && !state.isRecording && !state.isProcessing) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        items = state.messages,
                        key = { it.id }
                    ) { message ->
                        ChatBubble(message = message)
                    }

                    // Streaming response
                    if (state.partialResponse.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingBubble(text = state.partialResponse)
                        }
                    }

                    // Processing indicator
                    if (state.isProcessing) {
                        item(key = "processing") {
                            ProcessingIndicator()
                        }
                    }

                    // Bottom spacing
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    isRecording: Boolean,
    isProcessing: Boolean,
    isStreaming: Boolean,
    onMicClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status text
        AnimatedVisibility(
            visible = isRecording || isProcessing || isStreaming,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = when {
                    isRecording -> "Listening..."
                    isProcessing -> "Processing..."
                    isStreaming -> "Responding..."
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        MicButton(
            isRecording = isRecording,
            enabled = !isProcessing && !isStreaming,
            onClick = onMicClick
        )
    }
}

@Composable
private fun ProcessingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "  Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
