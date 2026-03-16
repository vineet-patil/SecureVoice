package com.example.securevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.securevoice.ui.chat.ChatScreen
import com.example.securevoice.ui.chat.ChatViewModel
import com.example.securevoice.ui.theme.SecureVoiceTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SecureVoiceTheme {
                val viewModel: ChatViewModel = hiltViewModel()
                ChatScreen(viewModel = viewModel)
            }
        }
    }
}
