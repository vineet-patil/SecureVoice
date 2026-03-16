# Architecture Decision Records (ADRs)

Each record captures a significant technical decision, its context, and the reasoning.

---

## ADR-001: MVI over MVVM for UI Architecture

**Status**: Accepted

**Context**: SecureVoice has complex, interdependent UI state: recording status, processing status, streaming status, error state, message list, and partial response text. The TDD defines a unidirectional pipeline.

**Decision**: Use MVI (Model-View-Intent) with a single `ChatUiState` data class.

**Consequences**:
- (+) Impossible to have contradictory states (e.g., recording + streaming simultaneously) because state is a single atomic object.
- (+) Every user action is modeled as a sealed `ChatIntent` — explicit, exhaustive, and documentable.
- (+) State transitions are pure functions, trivially unit-testable.
- (-) Slightly more boilerplate than MVVM (Intent sealed class, explicit state copying).

**Full analysis**: See `DOCUMENTATION.md`, section 10 (The UI Layer — Compose + MVI).

---

## ADR-002: Hilt for Dependency Injection

**Status**: Accepted

**Context**: The app has 5 layers of components with cross-cutting dependencies (e.g., `AudioRingBuffer` shared between `AudioRecorder` and `AudioRepositoryImpl`, `OkHttpClient` shared between `LlmClient` and potential future HTTP services).

**Decision**: Use Hilt (Dagger-backed) with KSP for annotation processing.

**Consequences**:
- (+) First-party Android/Google support. Integrates natively with `ViewModel`, `Activity`, `Application` lifecycles.
- (+) Compile-time dependency graph validation — misconfigured bindings fail at build, not runtime.
- (+) `@Singleton` scoping for expensive objects (TFLite interpreter, OkHttpClient).
- (-) KSP version must match Kotlin version exactly. Bleeding-edge Kotlin may require version adjustment.

---

## ADR-003: HandlerThread for Audio Recording (Not Coroutines)

**Status**: Accepted

**Context**: `AudioRecord.read()` is a blocking JNI call that fills a byte buffer in real-time. Audio arrives every ~64ms at 16kHz. Delayed reads cause buffer overruns and lost audio.

**Decision**: Use a dedicated `HandlerThread` for the audio recording loop instead of `Dispatchers.IO`.

**Consequences**:
- (+) Dedicated thread with no scheduling contention. Audio never competes with network I/O or database operations for thread time.
- (+) `HandlerThread` provides a built-in `Looper` for clean lifecycle management (quit/quitSafely).
- (-) Slightly more manual lifecycle management than coroutines.

**Why not `Dispatchers.IO`?**: The IO dispatcher is a shared thread pool (default 64 threads). Under load (SSE streaming, TFLite inference), audio reads could be delayed by other coroutines waiting for threads, causing buffer overruns.

---

## ADR-004: Synchronized Ring Buffer (Not Lock-Free)

**Status**: Accepted

**Context**: `AudioRingBuffer` has exactly one producer (audio recording thread) and one consumer (ML inference thread). The consumer reads the entire buffer infrequently (~once per inference, every 1-30 seconds).

**Decision**: Use `@Synchronized` on `write()` and `read()` methods.

**Consequences**:
- (+) Correctness is trivially verifiable. No subtle memory ordering bugs.
- (+) Lock contention is negligible: `read()` is called infrequently, and the critical section is a fast `System.arraycopy`.
- (-) Theoretically slower than a lock-free SPSC queue, but the difference is immeasurable at this read frequency.

**Why not lock-free?**: Lock-free SPSC ring buffers require careful memory barrier placement (`@Volatile` on indices, `lazySet` semantics). The correctness risk outweighs the zero measurable performance benefit for a consumer that reads once every few seconds.

---

## ADR-005: BuildConfig for API Key Management

**Status**: Accepted

**Context**: The Anthropic API key must never appear in source control. It needs to be available at runtime for HTTP requests.

**Decision**: Read the API key from `local.properties` via Gradle `buildConfigField`, accessed as `BuildConfig.ANTHROPIC_API_KEY`.

**Consequences**:
- (+) `local.properties` is already in `.gitignore` — zero risk of accidental commit.
- (+) Standard Android pattern, familiar to all Android developers.
- (+) Build fails with clear error if key is missing (empty string check at runtime).
- (-) Requires rebuild after changing the key. Acceptable for a development/demo app.

**Setup**: Add `ANTHROPIC_API_KEY=sk-ant-...` to `local.properties`.

---

## ADR-006: Gson for JSON Parsing (Not kotlinx.serialization)

**Status**: Accepted

**Context**: The only JSON parsing needed is for SSE event payloads from the Anthropic API. The structures are simple: `{"type": "content_block_delta", "delta": {"type": "text_delta", "text": "token"}}`.

**Decision**: Use Gson for JSON deserialization.

**Consequences**:
- (+) No additional Gradle plugin required (kotlinx.serialization needs the serialization compiler plugin).
- (+) Simple API for the limited parsing needed.
- (+) Well-tested, stable library.
- (-) Runtime reflection-based (slightly slower than kotlinx.serialization's compile-time codegen). Irrelevant for the tiny payloads in SSE events.

---

## ADR-007: OkHttp SSE with Flow Wrapper

**Status**: Accepted

**Context**: The Anthropic API supports Server-Sent Events for streaming token responses. The app needs to display tokens as they arrive for a real-time typing effect.

**Decision**: Use `okhttp-sse` library with a Kotlin Flow wrapper that converts callback-based SSE events into a `Flow<StreamEvent>`.

**Consequences**:
- (+) `callbackFlow` bridges the callback-based OkHttp SSE API to Kotlin's reactive `Flow` type.
- (+) Flow cancellation automatically closes the SSE connection — no resource leaks.
- (+) Backpressure handling via Flow operators if tokens arrive faster than UI renders.
- (-) Requires careful `awaitClose` handling to ensure the OkHttp call is canceled on Flow cancellation.

---

## ADR-008: Inference on Recording Stop (Not Continuous)

**Status**: Accepted

**Context**: Continuous real-time transcription (streaming ASR) requires a different model architecture (e.g., Whisper with chunked inference or a streaming model like DeepSpeech). Whisper Tiny expects a complete audio window.

**Decision**: Run TFLite inference once when the user releases the mic button, using the full ring buffer contents.

**Consequences**:
- (+) Matches Whisper Tiny's design: process a complete audio window, output complete text.
- (+) Battery efficient: inference runs once per utterance, not continuously.
- (+) Simpler pipeline: no partial transcript merging or word-level deduplication.
- (-) User must wait for inference to complete after releasing mic. Mitigated by "Processing..." UI state and the <800ms TTFT target.
