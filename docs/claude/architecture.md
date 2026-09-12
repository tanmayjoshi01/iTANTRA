# iTANTRA Architecture — Source of Truth

This document describes the target system architecture and, separately, what is actually implemented today. Where the two differ, this file says so explicitly. Do not treat anything marked "Planned / Proposed" as existing code.

## Target end-to-end pipeline (design goal, not fully implemented)

```
Microphone
    |
    v
Audio Capture
    |
    v
VAD
    |
    v
Speech Segmentation
    |
    v
Offline STT
    |
    v
Text / Message
    |
    v
Transport
    |
    v
Text / Message
    |
    v
Offline TTS
    |
    v
Speaker
```

This mirrors the diagram already in `README.md` (System Architecture section); it is not restated here in more detail than the README's own status. Only Audio Capture → VAD → Speech Segmentation exist as implemented code today (see below). Offline STT, Transport, and Offline TTS are Planned.

## Major conceptual components

| Component | Purpose | Status |
|---|---|---|
| `speech-engine` | On-device audio capture, VAD, speech segmentation; future STT/TTS integration point | Implemented (audio/VAD/segmentation only) |
| `app` | Android application module: UI, lifecycle, permission requests, wiring speech-engine to transport | Does not exist yet — Planned (Paras) |
| `transport` | Wi-Fi Direct / Bluetooth device-to-device link abstraction | Planned / Proposed (Paras) |
| `protocol` | Message format/serialization exchanged over transport | Planned / Proposed (Paras) |
| `metrics` | Benchmarking/measurement harness (latency, CPU, RAM, WER, network) | Planned / Proposed (shared) |
| `models` | STT/TTS model files and associated metadata (license, source) | Planned / Proposed — no models selected or downloaded (see `decisions.md`) |

No `transport`, `protocol`, `metrics`, or `models` module/directory exists in the repository at the time of writing. Their names above are working labels for planning purposes, not committed module names.

## Phase 1 architecture — actually implemented

This is the real pipeline in `speech-engine/src/main/kotlin/com/itantra/speechengine/`, verified against source:

```
AudioRecord (Android)
    |
    v
AudioRecorder.start(onFrame, onError)
    |
    v
AudioFrame
    |
    v
VoiceActivityDetector.processFrame(frame) -> VadState
    |  (EnergyZcrVoiceActivityDetector is the sole implementation)
    v
SpeechSegmenter.processFrame(frame) -> SpeechSegment?
    |
    v
SpeechSegment  (terminal output of Phase 1; intended STT integration point)
```

### Package `com.itantra.speechengine.audio`

- **`AudioConfig`** — immutable PCM format descriptor (sample rate, channel count, bits per sample, frame duration). No Android dependency; usable identically on JVM and device. Current default: 16 kHz, mono, 16-bit, 20 ms frames (see `decisions.md`, Decision 002 — an engineering baseline, not a benchmarked-optimal value).
- **`AudioFrame`** — one chunk of PCM audio (`ShortArray` samples) tagged with `AudioConfig` and a capture timestamp. No Android dependency.
- **`AudioRecorder`** — the only class in this module with an Android framework dependency. Wraps `android.media.AudioRecord`, capturing via `MediaRecorder.AudioSource.VOICE_RECOGNITION` on a background coroutine dispatcher (`Dispatchers.IO`). Exposes `start(onFrame, onError)`, `stop()`, `release()`. Does not request the `RECORD_AUDIO` runtime permission itself — that is the application layer's responsibility (see `ownership.md`); reports `AudioCaptureException.PermissionDenied` instead of crashing if the permission is missing. Not thread-safe by design (single calling thread); callbacks fire on the internal background dispatcher.
- **`AudioCaptureException`** — sealed class: `PermissionDenied`, `InitializationFailed`, `ReadFailed`.

### Package `com.itantra.speechengine.vad`

- **`VoiceActivityDetector`** (interface) — `processFrame(frame: AudioFrame): VadState`, `reset()`. Implementations are stateful but must have no I/O or Android dependency, so they are unit-testable on the JVM with synthetic PCM data.
- **`VadState`** (enum) — `SILENCE`, `SPEECH_START`, `SPEECH`, `SPEECH_END`. `SPEECH_START`/`SPEECH_END` are one-frame edge events.
- **`VadConfig`** — tunable thresholds: `energyThreshold`, `zeroCrossingRateRange`, `speechStartFrameCount`, `speechEndFrameCount`. Defaults are an engineering starting point (see `decisions.md`, Decision 001), not benchmarked against real noise conditions.
- **`EnergyZcrVoiceActivityDetector`** — the sole current implementation. Classifies frames using short-term RMS energy plus zero-crossing rate, with consecutive-frame hysteresis to avoid flapping at the threshold. Not thread-safe; driven from a single processing thread/coroutine.

### Package `com.itantra.speechengine.segmentation`

- **`SegmenterConfig`** — tunable parameters: `silenceDurationMs` (pause length before finalizing an utterance), `maxSegmentDurationMs` (hard cap so buffering stays bounded), `minSegmentDurationMs` (discards too-short noise bursts), `preRollFrameCount` (frames prepended before a confirmed speech start, to avoid clipping the utterance's beginning).
- **`SpeechSegmenter`** — drives a `VoiceActivityDetector` frame-by-frame and applies the above policy to emit finalized `SpeechSegment`s. `processFrame(frame)` returns a `SpeechSegment?` — non-null exactly on the frame that completes a segment. Owns its `VoiceActivityDetector` instance exclusively (`reset()` resets both). Audio is buffered only while a segment is actively being collected, plus a small pre-roll ring buffer while idle, so memory use is bounded regardless of stream length.
- **`SpeechSegment`** — terminal Phase 1 output: `samples` (`ShortArray`), `config` (`AudioConfig`), `startTimestampMs`, `endTimestampMs`. Carries no text, language, or transport concern by design — those belong to later stages. This is the intended integration point for Stage 2 (Offline STT).

## Architectural principle: decoupled speech processing and transport

Speech processing (`speech-engine`) must remain independent of the communication transport mechanism. `speech-engine` code has no knowledge of Wi-Fi Direct, Bluetooth, or any message protocol, and must not gain any as STT/TTS are added in Stages 2–3. This lets the transport layer (Paras's ownership area, see `ownership.md`) change without requiring changes to speech processing, and vice versa. The `SpeechSegment` → text boundary (Stage 2) and the text → TTS boundary (Stage 3) are the intended seams; transport consumes and produces plain text/messages only.

## Other implemented design properties

- **Platform-independent processing where practical.** `AudioConfig`, `AudioFrame`, `VadConfig`, `VadState`, `VoiceActivityDetector`, `EnergyZcrVoiceActivityDetector`, `SegmenterConfig`, `SpeechSegmenter`, and `SpeechSegment` have no Android framework dependency and are unit-tested on the JVM. This is deliberate, not incidental, and should be preserved as STT/TTS are integrated where feasible.
- **Android-specific I/O boundary.** `AudioRecorder` is the sole class with an Android dependency in `speech-engine`'s Phase 1 code; it is the isolation boundary between the platform and the platform-independent processing logic above.
- **Testability.** JVM-testable logic is covered by unit tests (`speech-engine/src/test`); Android-specific behavior has instrumented test source (`speech-engine/src/androidTest`) not yet executed on a device (see `testing-and-validation.md`).
- **Bounded buffering.** `SpeechSegmenter` bounds memory via `maxSegmentDurationMs` and a fixed-size pre-roll ring buffer; it does not retain audio indefinitely.
- **Lifecycle/resource safety.** `AudioRecorder.release()` stops capture and cancels its coroutine scope; `stop()` lets an in-flight blocking read finish before releasing the microphone rather than releasing concurrently with a read.

## What is explicitly not yet designed

STT integration interface, TTS integration interface, message protocol shape, and transport abstraction are not yet designed in this repository. Do not invent interfaces for these; when Stage 2 begins, a real design decision is required and should be recorded in `decisions.md`.
