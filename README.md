# SecureVoice

A privacy-first voice assistant for Android. Speak into your phone, and SecureVoice transcribes your speech entirely on-device using Whisper, scrubs any personal information from the transcript, and then sends only the sanitized text to Claude for a streamed AI response.

**Your raw audio never leaves the device.** Only redacted text crosses the network.

## How It Works

```
Microphone → AudioRecorder → Ring Buffer (30s window)
                                    │
                              [User taps stop]
                                    │
                              Whisper TFLite
                            (on-device inference)
                                    │
                           "My SSN is 123-45-6789"
                                    │
                            Redaction Service
                                    │
                         "My SSN is [SSN_REDACTED]"
                                    │
                            Claude API (SSE)
                                    │
                          Streamed chat response
```

The redaction pipeline catches phone numbers, email addresses, SSNs, credit card numbers, street addresses, and IPv4 addresses before anything is transmitted.

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3
- **Architecture:** Clean Architecture + MVI (Model-View-Intent)
- **Speech-to-Text:** Whisper Tiny via TensorFlow Lite (on-device, quantized INT8)
- **LLM:** Claude (Anthropic API) with Server-Sent Events streaming
- **DI:** Hilt (Dagger-backed, compile-time validated)
- **Networking:** OkHttp + okhttp-sse
- **Testing:** JUnit 4, MockK, Turbine

## Prerequisites

- Android Studio Ladybug or newer
- Android SDK 36 (compileSdk)
- JDK 11+
- An Anthropic API key ([get one here](https://console.anthropic.com/))
- Whisper TFLite model files (see [Model Setup](#model-setup))

## Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/YOUR_USERNAME/SecureVoice.git
   cd SecureVoice
   ```

2. **Add your API key**

   Create or edit `local.properties` in the project root:
   ```properties
   sdk.dir=/path/to/your/Android/sdk
   ANTHROPIC_API_KEY=sk-ant-your-key-here
   ```
   This file is gitignored and will never be committed.

3. **Model setup**

   Place these files in `app/src/main/assets/`:
   - `whisper-tiny-int8.tflite` — Whisper Tiny encoder (INT8 quantized)
   - `whisper-tiny-decoder-int8.tflite` — Whisper Tiny decoder (INT8 quantized)
   - `vocab.json` — Whisper tokenizer vocabulary
   - `mel_filters.bin` — Mel filterbank weights (baked into the encoder)
   - `whisper_config.json` — Special token IDs

   The encoder and decoder TFLite files can be exported from OpenAI's Whisper Tiny model using standard TFLite conversion tools. The vocab and config files ship with the Whisper model.

4. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```
   Or open the project in Android Studio and hit Run.

## Project Structure

```
app/src/main/java/com/example/securevoice/
├── di/                          # Hilt dependency injection modules
│   ├── AudioModule.kt
│   ├── MlModule.kt
│   └── NetworkModule.kt
├── domain/                      # Pure Kotlin business logic
│   ├── model/
│   │   ├── ChatMessage.kt       # Message data class with role + timestamp
│   │   └── StreamEvent.kt       # Sealed interface: Token | Done | Error
│   ├── repository/
│   │   ├── AudioRepository.kt   # Recording control + audio snapshot
│   │   ├── LlmRepository.kt     # Streaming LLM responses
│   │   └── TranscriptionRepository.kt
│   └── usecase/
│       ├── SanitizeTextUseCase.kt       # PII redaction gate
│       └── StreamLlmResponseUseCase.kt  # Sanitize then stream
├── data/                        # Android-specific implementations
│   ├── audio/
│   │   ├── AudioRecorder.kt     # 16kHz PCM capture on dedicated thread
│   │   ├── AudioRingBuffer.kt   # Thread-safe circular buffer (30s)
│   │   └── AudioRepositoryImpl.kt
│   ├── ml/
│   │   ├── TfliteManager.kt     # Encoder + decoder interpreter management
│   │   ├── TokenDecoder.kt      # Token ID → text via vocab.json
│   │   └── TranscriptionRepositoryImpl.kt
│   ├── network/
│   │   ├── LlmClient.kt        # OkHttp SSE client for Claude API
│   │   ├── LlmRepositoryImpl.kt
│   │   └── SseEventParser.kt    # Anthropic SSE event → StreamEvent
│   └── privacy/
│       └── RedactionService.kt  # Regex-based PII scanner
├── ui/
│   ├── chat/
│   │   ├── ChatScreen.kt        # Main Compose screen
│   │   ├── ChatViewModel.kt     # MVI state machine
│   │   ├── ChatUiState.kt       # Single immutable state object
│   │   ├── ChatIntent.kt        # User action sealed interface
│   │   └── components/
│   │       ├── ChatBubble.kt
│   │       ├── StreamingBubble.kt
│   │       ├── MicButton.kt
│   │       └── EmptyState.kt
│   ├── permission/
│   │   └── AudioPermissionHandler.kt
│   └── theme/
├── MainActivity.kt
└── SecureVoiceApplication.kt
```

## Architecture

The app follows Clean Architecture with three layers:

- **UI Layer** — Compose screens + ViewModel. Uses MVI: user actions are modeled as sealed `ChatIntent` objects, state is a single immutable `ChatUiState` data class, and data flows in one direction.
- **Domain Layer** — Pure Kotlin interfaces, models, and use cases. No Android dependencies. `SanitizeTextUseCase` is the mandatory PII redaction gate.
- **Data Layer** — Android-specific implementations. Audio recording on a dedicated `HandlerThread`, TFLite inference on `Dispatchers.Default`, SSE streaming via OkHttp callbacks bridged to Kotlin `Flow`.

Key architectural decisions are documented in [`docs/architecture_decision_records.md`](docs/architecture_decision_records.md). Full technical documentation is in [`docs/DOCUMENTATION.md`](docs/DOCUMENTATION.md).

## Privacy Guarantees

1. **Audio stays on-device.** Raw PCM data is held in an in-memory ring buffer and never written to disk or transmitted.
2. **Transcription is local.** Whisper runs as a TFLite model on the phone's CPU. No cloud ASR service is involved.
3. **PII is redacted before transmission.** The `RedactionService` runs regex-based pattern matching on the transcript before it reaches the network layer. There is no code path that bypasses this step.
4. **The API key is kept out of source control.** It's read from `local.properties` (gitignored) and injected via `BuildConfig` at compile time.

## Testing

```bash
# Run all unit tests
./gradlew test

# Run specific test classes
./gradlew test --tests "*.AudioRingBufferTest"
./gradlew test --tests "*.SseEventParserTest"
./gradlew test --tests "*.RedactionServiceTest"
./gradlew test --tests "*.SanitizeTextUseCaseTest"
./gradlew test --tests "*.ChatViewModelTest"
```

Test coverage includes:
- **AudioRingBuffer** — byte-to-float conversion, wrap-around ordering, concurrent access
- **SseEventParser** — all Anthropic SSE event types, malformed JSON, edge cases
- **RedactionService** — phone, email, SSN, credit card, address, IPv4, mixed PII, passthrough
- **SanitizeTextUseCase** — delegation to RedactionService, end-to-end redaction
- **ChatViewModel** — permission flow, recording lifecycle, streaming, error handling, state transitions

## Supported PII Patterns

| Type | Examples | Placeholder |
|------|----------|-------------|
| Phone numbers | 555-123-4567, (555) 123-4567, +1-555-123-4567 | `[PHONE_REDACTED]` |
| Email addresses | user@example.com | `[EMAIL_REDACTED]` |
| Social Security Numbers | 123-45-6789 | `[SSN_REDACTED]` |
| Credit card numbers | 4111 1111 1111 1111, 4111111111111111 | `[CC_REDACTED]` |
| Street addresses | 123 Main Street, 456 Oak Ave Apt 5B | `[ADDRESS_REDACTED]` |
| IPv4 addresses | 192.168.1.1, 10.0.0.1 | `[IP_REDACTED]` |

## Documentation

For a deep dive into every component, see the full technical documentation:

- [**Complete Documentation**](docs/DOCUMENTATION.md) — covers the audio pipeline, Whisper ML pipeline, TFLite internals, PII redaction, SSE streaming, Compose UI, Hilt modules, and a glossary.
- [**Architecture Decision Records**](docs/architecture_decision_records.md) — explains the reasoning behind key technical choices (MVI over MVVM, HandlerThread over coroutines, synchronized ring buffer, BuildConfig for API keys, etc.).

## License

This project is provided as-is for educational and demonstration purposes.
