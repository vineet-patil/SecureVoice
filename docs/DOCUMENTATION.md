# SecureVoice — Complete Project Documentation


---

## Table of Contents

1. [What Is SecureVoice?](#1-what-is-securevoice)
2. [The Big Picture — How It All Works](#2-the-big-picture--how-it-all-works)
3. [Architecture Deep Dive](#3-architecture-deep-dive)
4. [The Audio Pipeline — From Microphone to Floats](#4-the-audio-pipeline--from-microphone-to-floats)
5. [The ML Pipeline — Whisper Speech Recognition](#5-the-ml-pipeline--whisper-speech-recognition)
6. [What Is TensorFlow, TFLite, and Why We Need Them](#6-what-is-tensorflow-tflite-and-why-we-need-them)
7. [Asset Files — Every File Explained](#7-asset-files--every-file-explained)
8. [The Privacy Pipeline — PII Redaction](#8-the-privacy-pipeline--pii-redaction)
9. [The Network Pipeline — Talking to Claude](#9-the-network-pipeline--talking-to-claude)
10. [The UI Layer — Compose + MVI](#10-the-ui-layer--compose--mvi)
11. [Dependency Injection with Hilt](#11-dependency-injection-with-hilt)
12. [Every Source File Explained](#12-every-source-file-explained)
13. [How to Set Up and Run](#13-how-to-set-up-and-run)
14. [Testing](#14-testing)
15. [Glossary](#15-glossary)

---

## 1. What Is SecureVoice?

SecureVoice is a **privacy-first voice assistant** for Android. You speak into your phone, and it:

1. **Transcribes your speech** into text — entirely on-device (no internet needed for this step)
2. **Scans the text for personal information** (phone numbers, SSNs, credit cards, addresses, etc.)
3. **Redacts (removes) any personal information** before sending anything to the internet
4. **Sends the safe text to Claude AI** (Anthropic's language model) to get a helpful response
5. **Streams the response** back to you in real-time, word by word

**The key privacy guarantee:** Your raw voice audio NEVER leaves your phone. Only cleaned-up, redacted text goes to the cloud.

```
┌──────────────────────────────────────────────────────────────────┐
│                        YOUR PHONE                                │
│                                                                  │
│   🎤 Microphone                                                  │
│      │                                                           │
│      ▼                                                           │
│   ┌──────────────────┐                                           │
│   │  Audio Recorder   │  Records 16kHz mono audio                │
│   └────────┬─────────┘                                           │
│            ▼                                                     │
│   ┌──────────────────┐                                           │
│   │  Ring Buffer      │  Stores last 30 seconds of audio         │
│   │  (480,000 floats) │                                          │
│   └────────┬─────────┘                                           │
│            ▼                                                     │
│   ┌──────────────────┐                                           │
│   │  Whisper TFLite   │  Converts audio → text (ON-DEVICE!)     │
│   │  (Encoder+Decoder)│                                          │
│   └────────┬─────────┘                                           │
│            ▼                                                     │
│   "Hey, my SSN is 123-45-6789 and call me at 555-123-4567"      │
│            │                                                     │
│            ▼                                                     │
│   ┌──────────────────┐                                           │
│   │ Redaction Service │  Scans for PII and replaces it           │
│   └────────┬─────────┘                                           │
│            ▼                                                     │
│   "Hey, my SSN is [SSN_REDACTED] and call me at [PHONE_REDACTED]"│
│            │                                                     │
└────────────┼─────────────────────────────────────────────────────┘
             │  ← Only THIS safe text crosses the network
             ▼
┌──────────────────────────────────────────────────────────────────┐
│                     ANTHROPIC CLOUD                               │
│   ┌──────────────────┐                                           │
│   │  Claude AI        │  Generates a helpful response             │
│   └────────┬─────────┘                                           │
│            │                                                     │
│     Streams response back word-by-word via SSE                   │
└────────────┼─────────────────────────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────────────────┐
│                        YOUR PHONE                                │
│   ┌──────────────────┐                                           │
│   │  Chat UI          │  Shows response in a chat bubble          │
│   └──────────────────┘                                           │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. The Big Picture — How It All Works

Here's the complete data flow when you tap the microphone and speak:

```
                    ╔═══════════════════════════════════════════╗
                    ║         USER TAPS MICROPHONE              ║
                    ╚═══════════════════╤═══════════════════════╝
                                        │
                    ┌───────────────────┐│
                    │   1. RECORDING     │◄── AudioRecorder captures raw PCM bytes
                    │   (HandlerThread)  │    at 16,000 samples/second, mono
                    └────────┬──────────┘
                             │ raw bytes
                    ┌────────▼──────────┐
                    │  2. RING BUFFER    │◄── Converts bytes to floats [-1.0, 1.0]
                    │  (30 sec window)   │    Circular buffer, oldest data overwritten
                    └────────┬──────────┘
                             │
                    ╔════════╧══════════╗
                    ║  USER TAPS STOP   ║
                    ╚════════╤══════════╝
                             │ 480,000 float samples
                    ┌────────▼──────────┐
                    │  3. ENCODER        │◄── TFLite Whisper encoder model
                    │  (audio → mel →    │    Converts audio to "hidden states"
                    │   hidden states)   │    Output: 1500 × 384 matrix
                    └────────┬──────────┘
                             │ hidden states
                    ┌────────▼──────────┐
                    │  4. DECODER        │◄── TFLite Whisper decoder model
                    │  (greedy, loop)    │    Generates tokens one at a time
                    │  token → token →   │    Stops when it generates EOT token
                    └────────┬──────────┘
                             │ list of token IDs [234, 567, 890, ...]
                    ┌────────▼──────────┐
                    │  5. TOKEN DECODER  │◄── Looks up each ID in vocab.json
                    │  (IDs → text)      │    Joins tokens into text string
                    └────────┬──────────┘
                             │ "Hello, my number is 555-123-4567"
                    ┌────────▼──────────┐
                    │  6. REDACTION      │◄── Regex patterns scan for PII
                    │  (PII removal)     │    Phone, email, SSN, CC, address, IP
                    └────────┬──────────┘
                             │ "Hello, my number is [PHONE_REDACTED]"
                    ┌────────▼──────────┐
                    │  7. LLM CLIENT     │◄── Sends POST to Anthropic API
                    │  (Claude API)      │    Streams response via SSE
                    └────────┬──────────┘
                             │ "Hi! How can I help you?"
                    ┌────────▼──────────┐
                    │  8. UI UPDATE      │◄── ViewModel updates state
                    │  (Compose)         │    ChatScreen re-renders chat bubbles
                    └────────┴──────────┘
```

---

## 3. Architecture Deep Dive

SecureVoice uses **Clean Architecture** with three layers. Think of it like a sandwich:

```
    ┌─────────────────────────────────────────────────┐
    │                   UI LAYER                       │
    │                                                  │
    │  ChatScreen ← ChatViewModel ← ChatUiState       │
    │  MicButton    ChatIntent                         │
    │  ChatBubble                                      │
    │  StreamingBubble                                 │
    │  EmptyState                                      │
    │  AudioPermissionHandler                          │
    │                                                  │
    │  ★ Knows about: Domain layer                     │
    │  ✗ Does NOT know about: Data layer               │
    ├─────────────────────────────────────────────────┤
    │                DOMAIN LAYER                      │
    │                                                  │
    │  ┌──────────┐  ┌─────────────────────────┐      │
    │  │ Models:   │  │ Repository Interfaces:   │      │
    │  │ ChatMsg   │  │ AudioRepository          │      │
    │  │ Role      │  │ TranscriptionRepository  │      │
    │  │ StreamEvt │  │ LlmRepository            │      │
    │  └──────────┘  └─────────────────────────┘      │
    │                                                  │
    │  ┌──────────────────────────────────────┐       │
    │  │ Use Cases:                             │       │
    │  │ SanitizeTextUseCase                    │       │
    │  │ StreamLlmResponseUseCase               │       │
    │  └──────────────────────────────────────┘       │
    │                                                  │
    │  ★ Pure Kotlin — no Android imports              │
    │  ★ Defines interfaces, data layer implements     │
    ├─────────────────────────────────────────────────┤
    │                 DATA LAYER                        │
    │                                                  │
    │  ┌─────────┐ ┌────────┐ ┌─────────┐ ┌────────┐ │
    │  │ audio/  │ │  ml/   │ │network/ │ │privacy/│ │
    │  │         │ │        │ │         │ │        │ │
    │  │Recorder │ │TfLite  │ │LlmClient│ │Redact  │ │
    │  │RingBuf  │ │Token   │ │SseEvent │ │Service │ │
    │  │RepoImpl │ │Decoder │ │Parser   │ │        │ │
    │  │         │ │Transcr │ │RepoImpl │ │        │ │
    │  │         │ │RepoImpl│ │         │ │        │ │
    │  └─────────┘ └────────┘ └─────────┘ └────────┘ │
    │                                                  │
    │  ★ Implements domain interfaces                  │
    │  ★ Android-specific code lives here              │
    └─────────────────────────────────────────────────┘
```

### Why Clean Architecture?

| Rule | Why |
|------|-----|
| UI doesn't know about Data | You can swap the LLM provider (e.g., use OpenAI instead of Claude) without touching the UI |
| Domain is pure Kotlin | Business logic can be tested without Android emulator |
| Data implements Domain interfaces | Multiple implementations possible (e.g., mock for testing) |

### The MVI Pattern (Model-View-Intent)

MVI is how the UI layer works. Think of it as a loop:

```
    ┌──────────────────────────────────────────────────┐
    │                                                   │
    │   ┌─────────┐    ChatIntent     ┌─────────────┐ │
    │   │         │ ────────────────► │             │  │
    │   │  VIEW   │                   │  VIEW MODEL │  │
    │   │ (Screen)│ ◄──────────────── │             │  │
    │   │         │    ChatUiState    │             │  │
    │   └─────────┘                   └─────────────┘  │
    │                                                   │
    └──────────────────────────────────────────────────┘

    Intent examples:              State contains:
    • StartRecording              • messages: List<ChatMessage>
    • StopRecording               • isRecording: Boolean
    • DismissError                • isProcessing: Boolean
    • PermissionResult(granted)   • isStreaming: Boolean
                                  • partialResponse: String
                                  • error: String?
                                  • hasAudioPermission: Boolean
```

**The key idea:** Data flows in ONE direction. User actions become Intents, which update State, which re-renders the View. No callbacks, no two-way binding, no spaghetti.

---

## 4. The Audio Pipeline — From Microphone to Floats

### What Happens When You Hit Record?

```
    ┌──────────┐     raw PCM bytes    ┌──────────────┐    float samples    ┌──────────────┐
    │  Phone   │ ──────────────────► │ AudioRecorder │ ─────────────────► │ AudioRing    │
    │  Mic     │   16,000 samples/s  │              │  converts int16    │ Buffer       │
    │          │   mono, 16-bit      │  (dedicated   │  to float [-1,1]  │              │
    │          │                     │   thread)     │                    │ (circular,    │
    └──────────┘                     └──────────────┘                    │  30 seconds)  │
                                                                         └──────────────┘
```

### What Is PCM Audio?

PCM (Pulse Code Modulation) is the rawest digital audio format:

```
    Sound wave:      ∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿

    Sampled 16,000 times per second:

    Sample #:    1     2     3     4     5     6     7     8  ...
    Value:      100   500   900  1200   800   200  -300  -700 ...

    Each sample is a 16-bit integer: range -32,768 to +32,767
    Stored as 2 bytes (little-endian): [low_byte, high_byte]
```

### Why 16kHz?

- CD quality: 44,100 Hz (overkill for speech)
- Phone calls: 8,000 Hz (too low for Whisper)
- **Whisper was trained on 16,000 Hz** — so we must match it exactly

### The Ring Buffer

A **ring buffer** (circular buffer) is a fixed-size array that wraps around:

```
    Initial state (empty):
    ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
    │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │
    └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
      ▲ head

    After writing samples A, B, C:
    ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
    │ A │ B │ C │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │
    └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
                  ▲ head

    After buffer is FULL and D, E are written (wraps around!):
    ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
    │ D │ E │ C │ . │ . │ . │ . │ . │ X │ Y │   ← A and B are gone (overwritten)
    └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
          ▲ head

    read() returns chronological order: [C, ..., X, Y, D, E]
```

**Why a ring buffer?** Because we only need the LAST 30 seconds of audio (that's what Whisper processes). Older audio is automatically discarded by overwriting.

- Size: 480,000 floats = 16,000 samples/sec × 30 seconds
- Thread-safe: `@Synchronized` on read/write/clear

### Byte-to-Float Conversion

```
    Raw bytes from microphone:    [0xFF, 0x7F]

    Step 1 — Combine into 16-bit int (little-endian):
      low  = 0xFF = 255
      high = 0x7F = 127
      value = (127 << 8) | 255 = 32512 + 255 = 32767

    Step 2 — Normalize to [-1.0, 1.0]:
      float_value = 32767 / 32768.0 = 0.99997

    Why normalize? ML models work best with small numbers near zero.
```

---

## 5. The ML Pipeline — Whisper Speech Recognition

### What Is Whisper?

Whisper is a **speech recognition model** created by OpenAI. Given audio, it outputs text. It supports 97 languages and was trained on 680,000 hours of audio from the internet.

We use **Whisper Tiny** — the smallest version:

| Model | Parameters | Size | Speed |
|-------|-----------|------|-------|
| Tiny | 39M | ~39 MB | Fastest |
| Base | 74M | ~74 MB | Fast |
| Small | 244M | ~244 MB | Medium |
| Medium | 769M | ~769 MB | Slow |
| Large | 1.5B | ~1.5 GB | Slowest |

We chose Tiny because it runs in ~2 seconds on a phone. The others would be too slow or too large.

### Encoder-Decoder Architecture

Whisper is an **encoder-decoder** model. Think of it like a translator:

```
    ┌─────────────────────────────────────────────────────────────┐
    │                        ENCODER                               │
    │                                                              │
    │  "Listens" to the audio and creates a compact                │
    │   mathematical representation (hidden states).               │
    │                                                              │
    │  Input:  480,000 audio samples (30 seconds)                  │
    │  Output: 1,500 × 384 matrix of hidden states                │
    │                                                              │
    │  Think of it as: "I understood the audio, here's             │
    │  a summary of what I heard in math form."                    │
    │                                                              │
    │  The 1,500 comes from: 30 seconds ÷ 0.02 sec/frame          │
    │  The 384 is the "embedding dimension" — how many             │
    │  numbers describe each time frame.                           │
    └─────────────────────────┬───────────────────────────────────┘
                              │
                    hidden states: [1500 × 384]
                              │
    ┌─────────────────────────▼───────────────────────────────────┐
    │                        DECODER                               │
    │                                                              │
    │  "Translates" the hidden states into text tokens,            │
    │   one token at a time (autoregressive).                      │
    │                                                              │
    │  Step 1: Given hidden states + [SOT, EN, TRANSCRIBE],       │
    │          predict first token → "Hello"                       │
    │                                                              │
    │  Step 2: Given hidden states + [SOT, EN, TRANSCRIBE, Hello],│
    │          predict next token → "world"                        │
    │                                                              │
    │  Step 3: Given hidden states + [..., Hello, world],          │
    │          predict next token → EOT (End of Text → stop!)     │
    │                                                              │
    │  This is called "autoregressive" or "greedy" decoding:       │
    │  each step uses all previous outputs as inputs.              │
    └─────────────────────────────────────────────────────────────┘
```

### What Are Tokens?

Models don't work with words directly. They work with **tokens** — chunks of text that might be words, parts of words, or single characters:

```
    Text:   "Hello, how are you?"
    Tokens: ["Hello", ",", " how", " are", " you", "?"]
    IDs:    [15947,   11,   703,   389,   345,  30]

    The vocab.json file maps between text ↔ IDs:
    {"Hello": 15947, ",": 11, " how": 703, ...}
```

Notice that spaces are included IN the tokens (Whisper uses "Ġ" to represent a leading space: `Ġhow` = `" how"`).

### What Is a Mel Spectrogram?

Before Whisper can process audio, the raw samples must be converted to a **mel spectrogram** — a visual representation of sound frequencies over time:

```
    Raw audio waveform:
    ─┬──────────┬──────────┬──────────┬──────────┬──────
     │ ∿∿∿∿∿    │ ∿∿∿∿∿    │ ∿∿∿∿∿    │ ∿∿∿∿∿    │
     │ (frame 1)│ (frame 2)│ (frame 3)│ (frame 4)│
     └──────────┴──────────┴──────────┴──────────┘

          │ FFT (Fast Fourier Transform) for each frame
          │ = breaks sound into individual frequencies
          ▼

    Frequency spectrum for each frame:
    Frame 1: [low freq: 0.8, mid freq: 0.3, high freq: 0.1, ...]
    Frame 2: [low freq: 0.2, mid freq: 0.9, high freq: 0.4, ...]
    ...

          │ Mel filterbank (groups frequencies into 80 "mel bins")
          │ = mimics how the human ear perceives pitch
          ▼

    Mel spectrogram (2D image):
    Frequency ▲
    (80 bins)  │ ░░▓▓████░░░░▓▓▓▓░░░░░░████░░
               │ ░▓▓███████▓▓████▓▓░░▓▓████▓▓
               │ ▓████████████████████████████
               │ ████████████████████████████
               └──────────────────────────────► Time (1500 frames)

    Think of it as a photograph of sound, where:
    - X-axis = time (1,500 frames for 30 seconds)
    - Y-axis = frequency/pitch (80 bands)
    - Brightness = loudness at that frequency and time
```

**In our app:** The mel spectrogram computation is **baked into the encoder TFLite model**. This means we feed raw audio samples directly to the encoder, and it does the mel conversion + encoding internally. This is much faster than computing the mel spectrogram in Kotlin.

### Greedy Decoding — Step by Step

```
    Encoder outputs hidden states: [1500 × 384]

    Decoder prompt (special tokens that tell Whisper what to do):
    ┌─────────────────────────────────────────────────────┐
    │ SOT(50258) │ EN(50259) │ TRANSCRIBE(50359) │ NO_TS(50363) │
    │ "start"    │ "English" │ "transcribe mode" │ "no stamps"  │
    └─────────────────────────────────────────────────────┘

    Decoder Step 1:
    Input:  [SOT, EN, TRANSCRIBE, NO_TIMESTAMPS]
    Output: logits for 51,865 possible tokens
    Pick highest: token 1234 = "ĠHello"                    ← "Hello" (Ġ = space)

    Decoder Step 2:
    Input:  [SOT, EN, TRANSCRIBE, NO_TIMESTAMPS, 1234]
    Output: logits for 51,865 possible tokens
    Pick highest: token 5678 = "Ġworld"                    ← " world"

    Decoder Step 3:
    Input:  [SOT, EN, TRANSCRIBE, NO_TIMESTAMPS, 1234, 5678]
    Output: logits for 51,865 possible tokens
    Pick highest: token 50257 = EOT                        ← STOP!

    Final token list: [1234, 5678]
    Decoded text: "Hello world"
```

**"Greedy"** means we always pick the single most likely next token. This is the simplest strategy. More advanced strategies (beam search) consider multiple possibilities simultaneously.

### What Are Logits?

**Logits** are raw scores that the model outputs for each possible next token:

```
    Token ID:     0       1       2    ...  1234   ...  50257  ...  51864
    Token text: "!"     "\"     "#"   ... "ĠHello" ... "EOT"  ...  "ÿ"
    Logit:      -2.3    -5.1    -4.8  ...  12.7   ...  -1.0   ...  -8.2
                                            ▲ HIGHEST! → pick this one

    Higher logit = model thinks this token is more likely to come next
    We pick the argmax (the token with the highest score)
```

---

## 6. What Is TensorFlow, TFLite, and Why We Need Them

### The ML Framework Stack

```
    ┌──────────────────────────────────────────────────────────────┐
    │                    TRAINING (on powerful GPUs)                │
    │                                                              │
    │  TensorFlow / PyTorch                                        │
    │  = Full ML framework for training models                     │
    │  = Too large and slow for phones (gigabytes of libraries)    │
    │                                                              │
    │  After training, you "export" the model to a smaller format  │
    └──────────────────────────────┬───────────────────────────────┘
                                   │ convert
                                   ▼
    ┌──────────────────────────────────────────────────────────────┐
    │              TensorFlow Lite (TFLite)                         │
    │                                                              │
    │  = Lightweight runtime for running models on phones/IoT      │
    │  = Can't train models, only run them (inference)             │
    │  = Small library (~5 MB vs TensorFlow's ~500 MB)             │
    │  = Supports quantization (smaller + faster models)           │
    │  = Runs on CPU, GPU, or dedicated AI chips (NNAPI)           │
    │                                                              │
    │  The .tflite file IS the model — contains all the math       │
    │  (weights, biases, operations) needed to make predictions    │
    └──────────────────────────────────────────────────────────────┘
```

### Why Not Just Use TensorFlow on the Phone?

| Feature | TensorFlow | TFLite |
|---------|-----------|--------|
| Size | ~500 MB | ~5 MB |
| Purpose | Train + run models | Run models only |
| Hardware | GPU servers | Phones, watches, microcontrollers |
| Speed | Optimized for batch GPU | Optimized for single-sample mobile |
| Quantization | Possible but not focus | First-class support |

### What Is Quantization?

Quantization makes models smaller and faster by using smaller numbers:

```
    Original (float32):  Each number uses 32 bits (4 bytes)
    Value: 0.123456789...   → stored as 4 bytes

    Quantized (int8):    Each number uses 8 bits (1 byte)
    Value: 0.123456789...   → rounded to ~0.12 → stored as 1 byte

    Result:
    - Model is ~4x smaller (39 MB → ~10 MB)
    - Inference is ~2-4x faster
    - Accuracy drops slightly (usually <1% for speech models)
```

Our models use **INT8 quantization** — that's what the `-int8` in the filename means:
- `whisper-tiny-int8.tflite` = Whisper Tiny encoder, INT8 quantized
- `whisper-tiny-decoder-int8.tflite` = Whisper Tiny decoder, INT8 quantized

### How TFLite Works in Our App

```
    ┌────────────────────────────────────────────────────────┐
    │                    TfliteManager.kt                     │
    │                                                        │
    │  ┌────────────────────┐   ┌──────────────────────┐    │
    │  │  Interpreter #1     │   │  Interpreter #2       │    │
    │  │  (Encoder model)    │   │  (Decoder model)      │    │
    │  │                     │   │                       │    │
    │  │  Loads .tflite file │   │  Loads .tflite file   │    │
    │  │  from assets/       │   │  from assets/         │    │
    │  │                     │   │                       │    │
    │  │  Uses 4 CPU threads │   │  Uses 4 CPU threads   │    │
    │  └─────────┬──────────┘   └──────────┬────────────┘    │
    │            │                          │                 │
    │   runEncoder(audio)          runDecoderStep(...)        │
    │   Input: FloatArray          Input: hidden states +     │
    │   Output: hidden states              token IDs          │
    │                              Output: next token ID      │
    └────────────────────────────────────────────────────────┘

    TFLite Interpreter is like a virtual machine:
    1. Loads the model file (a graph of math operations)
    2. You feed in data (ByteBuffers)
    3. It runs all the math operations in order
    4. You read out the result (ByteBuffers)
```

---

## 7. Asset Files — Every File Explained

All files live in `app/src/main/assets/`. Here's what each one does, where it came from, and how it was created:

### 7.1 `whisper-tiny-int8.tflite` (9.8 MB) — Encoder Model

**What it does:**
Takes 30 seconds of raw audio (480,000 float samples) and outputs a compact mathematical representation called "hidden states" (a 1500 × 384 matrix). This includes the mel spectrogram computation baked directly into the model.

**Where it came from:**
Converted from the HuggingFace `openai/whisper-tiny` model using a custom Python script.

**How it was created — step by step:**

```bash
# Step 1: Install required Python packages
pip install tensorflow transformers tf-keras torch numpy

# Step 2: Run the conversion script (see below)
python convert_encoder.py

# Step 3: The script outputs whisper-tiny-int8.tflite
```

**What the conversion script does:**

```python
# 1. Load the pre-trained Whisper Tiny model from HuggingFace
from transformers import WhisperForConditionalGeneration, WhisperFeatureExtractor

model = WhisperForConditionalGeneration.from_pretrained("openai/whisper-tiny")
feature_extractor = WhisperFeatureExtractor.from_pretrained("openai/whisper-tiny")

# 2. Build a TensorFlow model that:
#    a) Takes raw audio as input
#    b) Computes mel spectrogram using tf.signal.stft
#       (Short-Time Fourier Transform with 512-point FFT)
#    c) Applies Whisper's mel filterbank (80 frequency bands)
#    d) Takes log10 and normalizes
#    e) Runs the encoder transformer layers
#    f) Outputs hidden states

# 3. Use Whisper's ACTUAL mel filterbank
#    (from WhisperFeatureExtractor, NOT TensorFlow's generic one)
#    This is critical — using the wrong filterbank gives garbage output

# 4. Convert to TFLite with INT8 quantization
converter = tf.lite.TFLiteConverter.from_saved_model("saved_encoder")
converter.optimizations = [tf.lite.Optimize.DEFAULT]  # INT8 quantization
tflite_model = converter.convert()

# 5. Save the .tflite file
with open("whisper-tiny-int8.tflite", "wb") as f:
    f.write(tflite_model)
```

**Why the mel is baked in:**
Computing a mel spectrogram in pure Kotlin requires 3,000 FFTs, each of size 512. On a phone, this takes several seconds. TFLite has an optimized `tf.signal.stft` op that runs MUCH faster because it uses native C++ code. So we bake the mel computation INTO the TFLite model.

---

### 7.2 `whisper-tiny-decoder-int8.tflite` (48.2 MB) — Decoder Model

**What it does:**
Takes the encoder's hidden states plus a sequence of token IDs, and predicts the next token. Called repeatedly in a loop (autoregressive decoding).

**Input → Output:**

```
    Inputs:
    1. Encoder hidden states [1, 1500, 384] — from the encoder
    2. Token IDs [1, 128] — the prompt + previously generated tokens

    Output:
    Logits [1, 128, 51865] — probability scores for each possible next token
                              at each position
```

**Where it came from:**
Same conversion process as the encoder, but for the decoder half of the model.

**How it was created:**

```python
# 1. Extract the decoder from the same Whisper model
# 2. Create a TensorFlow model that takes:
#    - encoder_output: [1, 1500, 384]
#    - decoder_input_ids: [1, 128]
# 3. Convert with INT8 quantization
# 4. Save as whisper-tiny-decoder-int8.tflite
```

**Why it's bigger than the encoder (48 MB vs 10 MB):**
The decoder has cross-attention layers (attending to the encoder output) AND self-attention layers. It also has a large vocabulary projection layer (384 → 51,865 tokens). These extra layers add up.

---

### 7.3 `vocab.json` (816 KB) — Tokenizer Vocabulary

**What it does:**
Maps text tokens to integer IDs. The decoder outputs token IDs, and we need this file to convert those IDs back to readable text.

**Format:**
```json
{
    "!": 0,
    "\"": 1,
    "#": 2,
    ...
    "ĠHello": 15947,
    "Ġworld": 5678,
    ...
    "<|startoftranscript|>": 50258,
    "<|endoftext|>": 50257,
    ...
}
```

**Key things to know:**
- `Ġ` (Unicode character) means "this token starts with a space". `Ġhello` = `" hello"`.
- Special tokens like `<|startoftranscript|>` control the model's behavior.
- 51,865 total tokens (the model's vocabulary size).

**Where it came from:**

```bash
# Downloaded directly from HuggingFace
# URL: https://huggingface.co/openai/whisper-tiny/resolve/main/vocab.json
curl -o vocab.json https://huggingface.co/openai/whisper-tiny/resolve/main/vocab.json
```

**How the app uses it:**
`TokenDecoder.kt` loads this file, inverts the map (ID → text), and converts decoder output IDs to readable text.

---

### 7.4 `whisper_config.json` (small) — Special Token Configuration

**What it does:**
Tells the app which token IDs are special control tokens that Whisper needs.

**Contents:**
```json
{
    "sot": 50258,          // Start of Transcript
    "eot": 50257,          // End of Text (stop generating!)
    "transcribe": 50359,   // "I want you to transcribe, not translate"
    "no_timestamps": 50363,// "Don't include timestamps in output"
    "en": 50259,           // "The language is English"
    "vocab_size": 50258    // Number of base tokens (full vocab is 51,865 with special tokens)
}
```

**Why we need it:**
Before the decoder starts generating text tokens, we give it a "prompt" of special tokens that tell it what to do:

```
    Prompt: [SOT=50258, EN=50259, TRANSCRIBE=50359, NO_TIMESTAMPS=50363]

    Translation: "Start transcribing English audio without timestamps"
```

**Where it came from:**
Exported from the Whisper model configuration during the conversion process:

```python
from transformers import WhisperForConditionalGeneration
model = WhisperForConditionalGeneration.from_pretrained("openai/whisper-tiny")
config = {
    "sot": model.config.decoder_start_token_id,  # 50258
    "eot": model.config.eos_token_id,             # 50257
    # ... etc
}
```

---

### 7.5 `mel_filters.bin` (63 KB) — Mel Filterbank Weights

**What it does:**
Contains the mathematical weights for converting a frequency spectrum to a mel spectrogram. This file is a **legacy artifact** — it was used by the `MelSpectrogramExtractor.kt` class, which has been removed because the mel computation is now baked into the encoder model.

**This file is NO LONGER USED by the app.** It can be safely deleted, but is kept as reference.

**Format:** Binary file containing 201 × 80 = 16,080 float32 values (little-endian).

**Where it came from:**

```python
from transformers import WhisperFeatureExtractor
import numpy as np

feature_extractor = WhisperFeatureExtractor.from_pretrained("openai/whisper-tiny")
mel_filters = feature_extractor.mel_filters  # numpy array [80, 201]
mel_filters.T.astype(np.float32).tobytes()   # transpose to [201, 80] and save
```

---

## 8. The Privacy Pipeline — PII Redaction

### What PII Does SecureVoice Redact?

```
    ┌───────────────────────────────────────────────────────────────────┐
    │                    RedactionService                               │
    │                                                                   │
    │  Input:   "My SSN is 123-45-6789, call me at 555-123-4567,       │
    │            email john@test.com, I live at 123 Main St,            │
    │            card 4111-1111-1111-1111, server 192.168.1.1"          │
    │                                                                   │
    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
    │  │ SSN Regex     │  │ CC Regex     │  │ Phone Regex  │           │
    │  │ 123-45-6789   │  │ 4111-...     │  │ 555-123-4567 │           │
    │  │ → [SSN_RED]   │  │ → [CC_RED]   │  │ → [PHONE_RED]│           │
    │  └──────────────┘  └──────────────┘  └──────────────┘           │
    │                                                                   │
    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
    │  │ Email Regex   │  │ Address Regex│  │ IPv4 Regex   │           │
    │  │ john@test.com │  │ 123 Main St  │  │ 192.168.1.1  │           │
    │  │ → [EMAIL_RED] │  │ → [ADDR_RED] │  │ → [IP_RED]   │           │
    │  └──────────────┘  └──────────────┘  └──────────────┘           │
    │                                                                   │
    │  Output:  "My SSN is [SSN_REDACTED], call me at [PHONE_REDACTED],│
    │            email [EMAIL_REDACTED], I live at [ADDRESS_REDACTED],  │
    │            card [CC_REDACTED], server [IP_REDACTED]"              │
    └───────────────────────────────────────────────────────────────────┘
```

### PII Types and Their Regex Patterns

| PII Type | Example Matches | Placeholder |
|----------|----------------|-------------|
| **Phone** | 555-123-4567, (555) 123-4567, +1-555-123-4567, 555.123.4567, 555-0199 | `[PHONE_REDACTED]` |
| **Email** | user@domain.com, john.doe@company.co.uk | `[EMAIL_REDACTED]` |
| **SSN** | 123-45-6789, 123 45 6789 (rejects 000-xx-xxxx, xxx-00-xxxx, xxx-xx-0000) | `[SSN_REDACTED]` |
| **Credit Card** | 4111 1111 1111 1111, 4111-1111-1111-1111, 3782-822463-10005, 4111111111111111 | `[CC_REDACTED]` |
| **Address** | 123 Main Street, 456 Oak Ave Apt 5B, 789 Sunset Blvd | `[ADDRESS_REDACTED]` |
| **IPv4** | 192.168.1.1, 10.0.0.1, 8.8.8.8 | `[IP_REDACTED]` |

### Why Order Matters

Regexes are applied in this order: SSN → Credit Card → Phone → Email → Address → IP

```
    Why SSN before Phone?

    SSN:   123-45-6789  (3 digits - 2 digits - 4 digits)
    Phone: 555-123-4567 (3 digits - 3 digits - 4 digits)

    If we ran the phone regex first, it might partially match
    an SSN. By running SSN first, we catch the specific pattern
    before the broader phone pattern runs.
```

### The Mandatory Redaction Pipeline

```
    ┌──────────────────────────────────────────────────┐
    │           StreamLlmResponseUseCase                │
    │                                                   │
    │   invoke("my SSN is 123-45-6789")                 │
    │          │                                        │
    │          ▼                                        │
    │   SanitizeTextUseCase                             │
    │          │                                        │
    │          ▼                                        │
    │   RedactionService.sanitize(...)                  │
    │          │                                        │
    │          ▼                                        │
    │   "my SSN is [SSN_REDACTED]"                      │
    │          │                                        │
    │          ▼                                        │
    │   LlmRepository.streamResponse(safe_text)         │
    │                                                   │
    │  ★ There is NO way to call the LLM without        │
    │    going through redaction first.                  │
    │    This is enforced by the architecture.           │
    └──────────────────────────────────────────────────┘
```

---

## 9. The Network Pipeline — Talking to Claude

### Server-Sent Events (SSE)

SSE is a protocol for streaming data from a server. Unlike regular HTTP (send request, get full response), SSE keeps the connection open and sends data piece by piece:

```
    Regular HTTP:
    Client ──── request ────► Server
    Client ◄─── full response ─── Server
    Connection closed.

    SSE (Server-Sent Events):
    Client ──── request ────► Server
    Client ◄─── data: {"text":"Hi"} ────── Server
    Client ◄─── data: {"text":" there"} ── Server
    Client ◄─── data: {"text":"!"} ──────── Server
    Client ◄─── data: {"type":"message_stop"} Server
    Connection closed.
```

**Why SSE?** Because Claude generates text **token by token**. Waiting for the full response would take several seconds. With SSE, each word appears as soon as Claude generates it (like watching someone type).

### The Request

```
    POST https://api.anthropic.com/v1/messages

    Headers:
    x-api-key: sk-ant-...       ← Your API key
    anthropic-version: 2023-06-01
    content-type: application/json

    Body (JSON):
    {
        "model": "claude-sonnet-4-5-20250929",
        "max_tokens": 1024,
        "stream": true,          ← "Send me tokens one at a time"
        "messages": [
            {
                "role": "user",
                "content": "Hello, how can I [PHONE_REDACTED]?"
            }
        ]
    }
```

### The SSE Response Stream

```
    data: {"type":"message_start","message":{"id":"msg_123"}}       ← skip
    data: {"type":"content_block_start","index":0}                  ← skip
    data: {"type":"content_block_delta","delta":{"text":"I"}}       ← Token("I")
    data: {"type":"content_block_delta","delta":{"text":"'d"}}      ← Token("'d")
    data: {"type":"content_block_delta","delta":{"text":" be"}}     ← Token(" be")
    data: {"type":"content_block_delta","delta":{"text":" happy"}}  ← Token(" happy")
    data: {"type":"content_block_delta","delta":{"text":" to"}}     ← Token(" to")
    data: {"type":"content_block_delta","delta":{"text":" help"}}   ← Token(" help")
    data: {"type":"content_block_delta","delta":{"text":"!"}}       ← Token("!")
    data: {"type":"content_block_stop","index":0}                   ← skip
    data: {"type":"message_stop"}                                   ← Done
```

### How OkHttp SSE + callbackFlow Works

```
    ┌──────────────────────────────────────────────────────────────┐
    │                        LlmClient                             │
    │                                                              │
    │   OkHttp EventSource (callback-based)                        │
    │   ┌──────────────────────────────┐                           │
    │   │ onOpen(response)             │  → Log HTTP code          │
    │   │ onEvent(data)                │  → parse & trySend()      │
    │   │ onFailure(throwable, resp)   │  → trySend(Error) + close │
    │   │ onClosed()                   │  → close channel          │
    │   └──────────────────────────────┘                           │
    │          ▲                                                   │
    │          │ callbackFlow bridges callbacks → Flow              │
    │          ▼                                                   │
    │   Flow<StreamEvent>                                          │
    │   ┌──────────────────────────────┐                           │
    │   │ Token("I")                   │                           │
    │   │ Token("'d")                  │                           │
    │   │ Token(" be")                 │                           │
    │   │ Token(" happy")              │                           │
    │   │ Token(" to")                 │                           │
    │   │ Token(" help")               │                           │
    │   │ Token("!")                   │                           │
    │   │ Done                         │                           │
    │   └──────────────────────────────┘                           │
    │                                                              │
    │   awaitClose { eventSource.cancel() }                        │
    │   ← When the collector stops listening, cancel the SSE       │
    └──────────────────────────────────────────────────────────────┘
```

**`callbackFlow`** is a Kotlin coroutines construct that creates a Flow from callbacks:
- `trySend(value)` — pushes a value into the Flow
- `close()` — completes the Flow
- `awaitClose { ... }` — runs cleanup when the Flow is cancelled

### Error Handling

```
    HTTP 400 → Parse error body → "Your credit balance is too low..."
    HTTP 401 → "Invalid API key. Add ANTHROPIC_API_KEY to local.properties."
    HTTP 403 → Parse error body → "Access denied."
    HTTP 429 → "Rate limited. Please try again in a moment."
    HTTP 500/502/503 → "Anthropic API is temporarily unavailable."
    Other → throwable.message or "Connection failed (code=...)"
```

---

## 10. The UI Layer — Compose + MVI

### Screen Layout

```
    ┌──────────────────────────────────┐
    │  ┌────────────────────────────┐  │
    │  │     SecureVoice            │  │  ← TopAppBar
    │  └────────────────────────────┘  │
    │                                  │
    │  ┌────────────────────────────┐  │
    │  │                            │  │
    │  │     🎤                     │  │  ← EmptyState
    │  │  Tap the microphone        │  │     (when no messages)
    │  │  to start                  │  │
    │  │                            │  │
    │  │  Your voice stays on this  │  │
    │  │  device. Only redacted     │  │
    │  │  text is sent to the cloud │  │
    │  │                            │  │
    │  └────────────────────────────┘  │
    │                                  │
    │       "Listening..."             │  ← Status text (animated)
    │          ┌─────┐                 │
    │          │ 🎤  │                 │  ← MicButton (FAB)
    │          └─────┘                 │     Blue idle / Red recording
    │                                  │     Pulse animation when recording
    └──────────────────────────────────┘
```

After a conversation:

```
    ┌──────────────────────────────────┐
    │     SecureVoice                  │
    │                                  │
    │             ┌──────────────────┐ │
    │             │  What's the      │ │  ← User bubble (blue, right)
    │             │  weather today?  │ │
    │             └──────────────────┘ │
    │                                  │
    │  ┌──────────────────┐            │
    │  │  I'd be happy to │            │  ← Assistant bubble (gray, left)
    │  │  help! Today...  │            │
    │  └──────────────────┘            │
    │                                  │
    │  ┌──────────────────┐            │
    │  │  The weather is  █            │  ← StreamingBubble
    │  └──────────────────┘            │     (blinking cursor while streaming)
    │                                  │
    │          ┌─────┐                 │
    │          │ 🎤  │                 │
    │          └─────┘                 │
    └──────────────────────────────────┘
```

### MicButton Animations

```
    IDLE STATE:                    RECORDING STATE:
    ┌──────────┐                  ┌──────────────┐
    │          │                  │              │
    │   🎤     │  Blue #2196F3   │     ■        │  Red #F44336
    │          │                  │              │  Pulsing 1.0→1.15→1.0
    └──────────┘                  └──────────────┘

    Color transition: 200ms tween animation
    Pulse: 800ms infinite repeating reverse animation
```

---

## 11. Dependency Injection with Hilt

### What Is Dependency Injection (DI)?

Without DI, every class creates its own dependencies:

```kotlin
// BAD — tightly coupled
class ChatViewModel {
    private val recorder = AudioRecorder(AudioRingBuffer())  // hardcoded!
    private val tflite = TfliteManager(context)              // hardcoded!
    private val llm = LlmClient(OkHttpClient(), "api-key")   // hardcoded!
}
```

With DI, dependencies are **injected** (provided from outside):

```kotlin
// GOOD — loosely coupled
class ChatViewModel @Inject constructor(
    private val audioRepository: AudioRepository,         // interface!
    private val transcriptionRepository: TranscriptionRepository,
    private val streamLlmResponseUseCase: StreamLlmResponseUseCase
)
```

### How Hilt Works in SecureVoice

```
    ┌──────────────────────────────────────────────────────────┐
    │                    Hilt DI Container                      │
    │                                                          │
    │  AudioModule:                                            │
    │    AudioRepositoryImpl → AudioRepository (interface)     │
    │                                                          │
    │  MlModule:                                               │
    │    TranscriptionRepositoryImpl → TranscriptionRepository │
    │                                                          │
    │  NetworkModule:                                          │
    │    OkHttpClient (singleton, readTimeout=0 for SSE)       │
    │    LlmClient (with API key from BuildConfig)             │
    │    LlmRepositoryImpl → LlmRepository                    │
    │                                                          │
    │  Auto-discovered (@Inject + @Singleton):                 │
    │    AudioRecorder, AudioRingBuffer, TfliteManager,        │
    │    TokenDecoder, RedactionService, SanitizeTextUseCase,  │
    │    StreamLlmResponseUseCase                              │
    │                                                          │
    │  When ChatViewModel is created, Hilt automatically:      │
    │  1. Creates AudioRingBuffer                              │
    │  2. Creates AudioRecorder(ringBuffer)                    │
    │  3. Creates AudioRepositoryImpl(recorder, ringBuffer)    │
    │  4. Creates TfliteManager(context)                       │
    │  5. Creates TokenDecoder(context)                        │
    │  6. Creates TranscriptionRepositoryImpl(context,         │
    │          tfliteManager, tokenDecoder)                     │
    │  7. Creates RedactionService()                           │
    │  8. Creates SanitizeTextUseCase(redactionService)        │
    │  9. Creates OkHttpClient()                               │
    │  10. Creates LlmClient(httpClient, apiKey)               │
    │  11. Creates LlmRepositoryImpl(llmClient)                │
    │  12. Creates StreamLlmResponseUseCase(llmRepo, sanitize) │
    │  13. Creates ChatViewModel(audio, transcription, stream) │
    └──────────────────────────────────────────────────────────┘
```

### Key Hilt Annotations

```
    @HiltAndroidApp     ← on Application class. "Enable Hilt for this app."
    @AndroidEntryPoint  ← on Activity. "Hilt can inject here."
    @HiltViewModel      ← on ViewModel. "Hilt should create this."
    @Inject constructor ← "Hilt should inject these parameters."
    @Singleton           ← "Create only one instance, share it everywhere."
    @Module              ← "This class teaches Hilt how to create things."
    @Binds               ← "When someone asks for Interface, give them Impl."
    @Provides            ← "Here's a custom factory method."
```

---

## 12. Every Source File Explained

### Domain Layer

| File | Purpose |
|------|---------|
| `domain/model/ChatMessage.kt` | Data class: id (UUID), content, role (USER/ASSISTANT), timestamp |
| `domain/model/StreamEvent.kt` | Sealed interface: Token(text), Done, Error(message) — represents one SSE event |
| `domain/repository/AudioRepository.kt` | Interface: startRecording(), stopRecording(), getAudioSnapshot() |
| `domain/repository/TranscriptionRepository.kt` | Interface: suspend transcribe(audio) → String |
| `domain/repository/LlmRepository.kt` | Interface: streamResponse(prompt) → Flow<StreamEvent> |
| `domain/usecase/SanitizeTextUseCase.kt` | Wraps RedactionService. Operator fun invoke(text) → sanitized text |
| `domain/usecase/StreamLlmResponseUseCase.kt` | Sanitizes text THEN streams to LLM. Mandatory redaction gate. |

### Data Layer — Audio

| File | Purpose |
|------|---------|
| `data/audio/AudioRingBuffer.kt` | Thread-safe circular buffer. 480,000 floats = 30 sec. Converts 16-bit PCM → float. |
| `data/audio/AudioRecorder.kt` | Captures audio from mic at 16kHz. Dedicated HandlerThread for real-time capture. Returns boolean for success. |
| `data/audio/AudioRepositoryImpl.kt` | Implements AudioRepository. Manages recording state via StateFlow. |

### Data Layer — ML

| File | Purpose |
|------|---------|
| `data/ml/TfliteManager.kt` | Loads encoder + decoder .tflite files. runEncoder(audio) → hidden states. runDecoderStep(hidden, tokens) → next token. |
| `data/ml/TokenDecoder.kt` | Loads vocab.json (inverted: ID→text). Decodes token IDs to text. Strips special tokens and noise markers ([Music], [BLANK_AUDIO]). |
| `data/ml/TranscriptionRepositoryImpl.kt` | Full pipeline: audio → encoder → greedy decode loop → token decode → text. Loads whisper_config.json for special token IDs. |

### Data Layer — Network

| File | Purpose |
|------|---------|
| `data/network/LlmClient.kt` | Calls Anthropic API via OkHttp SSE. Uses Gson for JSON serialization. Parses error responses. |
| `data/network/SseEventParser.kt` | Parses raw SSE event JSON into StreamEvent objects. Handles content_block_delta, message_stop, error. |
| `data/network/LlmRepositoryImpl.kt` | Thin wrapper implementing LlmRepository → delegates to LlmClient. |

### Data Layer — Privacy

| File | Purpose |
|------|---------|
| `data/privacy/RedactionService.kt` | Regex-based PII detection and replacement. 6 PII types: phone, email, SSN, credit card, address, IP. |

### DI Layer

| File | Purpose |
|------|---------|
| `di/AudioModule.kt` | @Binds AudioRepositoryImpl → AudioRepository |
| `di/MlModule.kt` | @Binds TranscriptionRepositoryImpl → TranscriptionRepository |
| `di/NetworkModule.kt` | @Provides OkHttpClient, LlmClient, LlmRepository |

### UI Layer

| File | Purpose |
|------|---------|
| `SecureVoiceApplication.kt` | @HiltAndroidApp entry point |
| `MainActivity.kt` | @AndroidEntryPoint activity. Sets Compose content. |
| `ui/chat/ChatViewModel.kt` | MVI ViewModel. Handles intents, manages state, orchestrates pipeline. |
| `ui/chat/ChatUiState.kt` | Data class: messages, partialResponse, isRecording, isProcessing, isStreaming, error, hasAudioPermission |
| `ui/chat/ChatIntent.kt` | Sealed interface: StartRecording, StopRecording, DismissError, PermissionResult |
| `ui/chat/ChatScreen.kt` | Main Compose screen. Scaffold with TopBar, message list, bottom mic button. |
| `ui/chat/components/ChatBubble.kt` | Message bubble. Blue right-aligned (user) or gray left-aligned (assistant). |
| `ui/chat/components/StreamingBubble.kt` | Partial response bubble with blinking cursor animation. |
| `ui/chat/components/MicButton.kt` | FAB with color transition (blue→red) and pulse scale animation. |
| `ui/chat/components/EmptyState.kt` | Centered mic icon + instructions when no messages exist. |
| `ui/permission/AudioPermissionHandler.kt` | Composable that checks/requests RECORD_AUDIO permission. |
| `ui/theme/Color.kt` | Color palette (purple, pink, teal). |
| `ui/theme/Theme.kt` | Material3 theme with dynamic colors on Android 12+. |
| `ui/theme/Type.kt` | Typography with bodyLarge style. |

---

## 13. How to Set Up and Run

### Prerequisites

1. **Android Studio** (latest stable)
2. **Python 3.10+** (for model conversion)
3. **Anthropic API key** (from [console.anthropic.com](https://console.anthropic.com))

### Step 1: API Key

```bash
# Add to local.properties (in project root, gitignored)
echo "ANTHROPIC_API_KEY=sk-ant-your-key-here" >> local.properties
```

### Step 2: Download vocab.json

```bash
mkdir -p app/src/main/assets

curl -L -o app/src/main/assets/vocab.json \
  https://huggingface.co/openai/whisper-tiny/resolve/main/vocab.json
```

### Step 3: Convert Whisper Models

```bash
# Install Python dependencies
pip install tensorflow transformers tf-keras torch numpy

# Run the conversion scripts (see Asset Files section for details)
# This produces:
#   whisper-tiny-int8.tflite        (encoder, ~10 MB)
#   whisper-tiny-decoder-int8.tflite (decoder, ~48 MB)
#   whisper_config.json
#   mel_filters.bin (optional, no longer used)

# Copy to assets
cp whisper-tiny-int8.tflite app/src/main/assets/
cp whisper-tiny-decoder-int8.tflite app/src/main/assets/
cp whisper_config.json app/src/main/assets/
```

### Step 4: Build and Run

```bash
./gradlew assembleDebug

# Install on connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.example.securevoice/.MainActivity
```

### Step 5: Run Tests

```bash
# All unit tests
./gradlew testDebugUnitTest

# Single test class
./gradlew testDebugUnitTest --tests "com.example.securevoice.data.privacy.RedactionServiceTest"
```

---

## 14. Testing

### Test Suite Overview

```
    ┌──────────────────────────────────────────────────────────┐
    │                    98 Unit Tests Total                    │
    │                                                          │
    │  AudioRingBufferTest (11 tests)                          │
    │  ├── Empty buffer behavior                               │
    │  ├── Single sample write/read                            │
    │  ├── Silence handling (zeros)                             │
    │  ├── Negative sample conversion                          │
    │  ├── Chronological ordering (before wrap)                 │
    │  ├── Full buffer detection                               │
    │  ├── Chronological ordering (after wrap)                  │
    │  ├── Clear operation                                     │
    │  ├── Odd byte count handling                             │
    │  ├── Empty read returns zeros                            │
    │  └── Concurrent read/write thread safety                 │
    │                                                          │
    │  SseEventParserTest (12 tests)                           │
    │  ├── content_block_delta → Token                         │
    │  ├── message_stop → Done                                 │
    │  ├── [DONE] marker                                       │
    │  ├── Blank data                                          │
    │  ├── Error events                                        │
    │  ├── Skip control events (message_start, etc.)           │
    │  ├── Malformed JSON                                      │
    │  ├── Missing text field                                  │
    │  ├── Multi-word tokens                                   │
    │  └── Special characters                                  │
    │                                                          │
    │  RedactionServiceTest (55 tests)                         │
    │  ├── Phone: dashes, dots, spaces, country code,          │
    │  │         parentheses, 7-digit, multiple                │
    │  ├── Email: simple, dots, dashes, subdomain              │
    │  ├── SSN: dashes, spaces, start of text, invalid         │
    │  │       (000, 666, 9xx, 00 middle, 0000 end), multiple  │
    │  ├── CC: Visa spaces/dashes/continuous, Amex, Mastercard │
    │  ├── Address: street, ave, blvd, drive, apt, suite,      │
    │  │           multi-word, road, lane, period              │
    │  ├── IP: standard, localhost, public, max, invalid,      │
    │  │       version number, multiple                        │
    │  ├── Mixed: phone+email, all types combined,             │
    │  │         SSN vs phone distinction                      │
    │  └── Passthrough: clean text, empty, plain numbers,      │
    │                   voice transcript                        │
    │                                                          │
    │  SanitizeTextUseCaseTest (9 tests)                       │
    │  ├── Delegation for each PII type                        │
    │  ├── Clean text passthrough                              │
    │  ├── Empty input                                         │
    │  └── Realistic voice transcript                          │
    │                                                          │
    │  ChatViewModelTest (11 tests)                            │
    │  ├── Initial state                                       │
    │  ├── Permission requirement                              │
    │  ├── Recording with permission                           │
    │  ├── Stop triggers processing                            │
    │  ├── Permission denied error                             │
    │  ├── Error dismissal                                     │
    │  ├── Transcription error                                 │
    │  ├── Streaming error with partial content                │
    │  ├── Blank transcript                                    │
    │  ├── Cannot re-start while recording                     │
    │  └── Start recording failure shows error                 │
    └──────────────────────────────────────────────────────────┘
```

### Testing Libraries

| Library | Purpose | How It's Used |
|---------|---------|---------------|
| **JUnit 4** | Test framework | `@Test`, `@Before`, `@After`, `assertEquals`, etc. |
| **MockK** | Kotlin mocking | Creates fake implementations: `mockk<AudioRepository>()` |
| **Turbine** | Flow testing | Tests Kotlin Flows with `flow.test { awaitItem() }` |
| **kotlinx-coroutines-test** | Coroutine testing | `runTest`, `advanceUntilIdle`, `StandardTestDispatcher` |

---

## 15. Glossary

| Term | Definition |
|------|-----------|
| **API** | Application Programming Interface — a way for programs to talk to each other |
| **Autoregressive** | Generating output one piece at a time, where each piece depends on all previous pieces |
| **ByteBuffer** | A Java class for handling raw binary data efficiently |
| **callbackFlow** | A Kotlin construct that converts callback-based APIs into Flow streams |
| **Clean Architecture** | Software design where code is organized in layers with strict dependency rules |
| **Compose** | Android's modern UI toolkit — you describe what the UI looks like, and it handles rendering |
| **DI (Dependency Injection)** | A pattern where objects receive their dependencies from outside rather than creating them |
| **Encoder-Decoder** | A two-part model: encoder compresses input, decoder generates output |
| **FFT (Fast Fourier Transform)** | An algorithm that decomposes a signal into its frequency components |
| **Flow** | A Kotlin type that emits multiple values over time (like a stream of data) |
| **Greedy Decoding** | Always picking the single most likely next token (simplest decoding strategy) |
| **Hidden States** | The internal representation of data inside a neural network |
| **Hilt** | Google's dependency injection library for Android, built on Dagger |
| **INT8 Quantization** | Reducing model precision from 32-bit floats to 8-bit integers to save space and speed |
| **KSP** | Kotlin Symbol Processing — a code generation tool used by Hilt |
| **Logits** | Raw prediction scores output by a neural network (before softmax/probability) |
| **Mel Spectrogram** | A visual representation of audio frequencies over time, using a scale that matches human hearing |
| **MVI (Model-View-Intent)** | A UI architecture pattern with unidirectional data flow |
| **NNAPI** | Android Neural Networks API — hardware acceleration for ML on supported devices |
| **OkHttp** | A popular HTTP client library for Java/Kotlin |
| **PCM** | Pulse Code Modulation — the simplest digital audio format (raw samples) |
| **PII** | Personally Identifiable Information — data that can identify a specific person |
| **Ring Buffer** | A fixed-size buffer that wraps around, automatically discarding old data |
| **SSE (Server-Sent Events)** | A protocol for servers to push data to clients over HTTP |
| **StateFlow** | A Kotlin Flow that always has a current value and emits updates |
| **STFT** | Short-Time Fourier Transform — FFT applied to overlapping windows of audio |
| **TFLite** | TensorFlow Lite — a lightweight ML runtime for mobile and embedded devices |
| **Token** | A chunk of text (word, subword, or character) that ML models process |
| **Transformer** | A neural network architecture based on self-attention, used in Whisper and Claude |
| **Vocab** | The complete set of tokens a model can produce (Whisper has 51,865 tokens) |
| **Whisper** | OpenAI's speech recognition model family, supports 97 languages |

---

