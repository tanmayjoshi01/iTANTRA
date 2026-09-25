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

This mirrors the diagram already in `README.md` (System Architecture section); it is not restated here in more detail than the README's own status. Audio Capture → VAD → Speech Segmentation → Offline STT (Hindi only) exist as implemented code today (see below). Transport and Offline TTS are Planned; Offline STT is implemented only for Hindi and only up to a single ONNX Runtime-based recognizer — it is not complete for Stage 2 (no WER measurement yet, no other language, no finalized model-distribution mechanism).

## Major conceptual components

| Component | Purpose | Status |
|---|---|---|
| `speech-engine` | On-device audio capture, VAD, speech segmentation, offline STT; future TTS integration point | Implemented: audio/VAD/segmentation complete; STT implemented for Hindi only (see Phase 2 section below) |
| `app` | Android application module: UI, lifecycle, permission requests, wiring speech-engine to transport | Stage 1 foundation implemented (Paras): Compose `MainActivity` placeholder screen; depends on `:speech-engine` but does not call it yet — see `current-state.md` |
| `transport` | Wi-Fi Direct / Bluetooth device-to-device link abstraction | Planned / Proposed (Paras) |
| `protocol` | Message format/serialization exchanged over transport | Planned / Proposed (Paras) |
| `metrics` | Benchmarking/measurement harness (latency, CPU, RAM, WER, network) | Planned / Proposed (shared) |
| `models` | STT/TTS model files and associated metadata (license, source) | Partially decided for STT/Hindi: AI4Bharat IndicConformer (FP32 ONNX export) selected and in use (see `decisions.md`, Decision 006); no dedicated `models` directory/module exists — the model file itself is not committed to the repository (see below) |

No `transport`, `protocol`, or `metrics` module/directory exists in the repository at the time of writing. There is likewise no dedicated `models` module/directory; a model has been selected and integrated for Hindi STT (see Decision 006), but the model binary itself is intentionally kept out of version control (see `instructions.md`, Model rules) and is loaded by `IndicConformerRecognizer` from an external, caller-supplied file path. Where that file should live for a real build is still an open, not-yet-decided question.

## Phase 1 + Phase 2 architecture — actually implemented

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
SpeechSegment  (terminal output of Phase 1; the STT integration point named below)
    |
    v
SpeechRecognizer.recognize(segment) -> SpeechRecognitionResult
    |  (IndicConformerRecognizer is the sole implementation; Hindi only)
    v
SpeechRecognitionResult  (terminal output of Phase 2 today; intended transport integration point)
```

Phase 2 (STT) is implemented only for Hindi, only as far as a single recognizer class, and has not been benchmarked for WER — see "Package `com.itantra.speechengine.stt`" below and `current-state.md` for the exact validation level reached.

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

### Package `com.itantra.speechengine.stt`

- **`SpeechRecognizer`** (interface) — `recognize(segment: SpeechSegment): SpeechRecognitionResult`, `release()`. This is the Stage 2 integration seam `SpeechSegment` was designed toward.
- **`SpeechRecognitionResult`** — terminal Phase 2 output: `text`, `language`, `startTimestampMs`, `endTimestampMs`, `inferenceTimeMs`. Carries no transport/UI concern, mirroring `SpeechSegment`'s own boundary.
- **`SpeechRecognitionException`** — sealed class: `ModelNotFound`, `ModelLoadFailed`, `InferenceFailed`. Mirrors `AudioCaptureException`'s pattern.
- **`IndicConformerRecognizer`** — the sole current `SpeechRecognizer` implementation. Wraps the AI4Bharat IndicConformer Hindi model (FP32 ONNX export, `indicconformer_stt_hi_hybrid_rnnt_large`) via ONNX Runtime Android (`ai.onnxruntime`), calling `MelSpectrogramFeatureExtractor` and `CtcGreedyDecoder` internally. Constructor takes a `File` for the model and a `File` for the tokens list — **the model file is not bundled in the module or committed to the repository**; it is loaded from an external, caller-supplied path (the source comment records it as a ~470 MB validated artifact kept outside the repository, consistent with `instructions.md`'s rule against committing large model binaries without a documented distribution decision — see `architecture.md`'s `models` row, above, and `decisions.md`, Decision 006). FP32 only: the source comments record that the dynamically-quantized INT8 export of this model does not load on ONNX Runtime Android (unimplemented `ConvInteger` op) and must not be used.
- **`MelSpectrogramFeatureExtractor`** — hand-written, Android-independent log-mel feature extraction (own radix-2 FFT, precomputed Hann-window and mel-filterbank resources bundled under `src/main/resources/com/itantra/speechengine/stt/`) reproducing NeMo's `AudioToMelSpectrogramPreprocessor` as configured for the specific IndicConformer checkpoint in use. JVM-testable; no Android dependency.
- **`CtcGreedyDecoder`** — greedy CTC decoding (per-frame argmax, repeat/blank collapsing) over the model's flat 5,633-entry token vocabulary (`tokens.txt`, bundled as a small text resource — not a model binary). JVM-testable; no Android dependency.

As with Phase 1, everything in this package except the ONNX Runtime Android dependency itself is plain Kotlin with no Android framework dependency, and `MelSpectrogramFeatureExtractorTest` (JVM) checks the feature extractor's output against a NeMo-computed reference for a known Hindi clip. `IndicConformerRecognizer` does have an Android-relevant native dependency (`onnxruntime-android`) but no `android.*` framework import itself. See `current-state.md` for which of the `androidTest` sources for this package have actual confirmed on-device execution evidence versus being written but unconfirmed.

## Architectural principle: decoupled speech processing and transport

Speech processing (`speech-engine`) must remain independent of the communication transport mechanism. `speech-engine` code has no knowledge of Wi-Fi Direct, Bluetooth, or any message protocol, and must not gain any as STT/TTS are added in Stages 2–3. This lets the transport layer (Paras's ownership area, see `ownership.md`) change without requiring changes to speech processing, and vice versa. The `SpeechSegment` → text boundary (Stage 2) and the text → TTS boundary (Stage 3) are the intended seams; transport consumes and produces plain text/messages only.

## Other implemented design properties

- **Platform-independent processing where practical.** `AudioConfig`, `AudioFrame`, `VadConfig`, `VadState`, `VoiceActivityDetector`, `EnergyZcrVoiceActivityDetector`, `SegmenterConfig`, `SpeechSegmenter`, `SpeechSegment`, `SpeechRecognitionResult`, `SpeechRecognitionException`, `MelSpectrogramFeatureExtractor`, and `CtcGreedyDecoder` have no Android framework dependency and are unit-tested on the JVM. This principle has in fact been preserved into Phase 2 (STT), not just stated as a goal for it.
- **Android/native I/O boundary.** `AudioRecorder` (Android framework) and `IndicConformerRecognizer` (native ONNX Runtime dependency, though no `android.*` import) are the classes with a platform-specific dependency in `speech-engine`; they are the isolation boundary between the platform and the platform-independent processing logic above.
- **Testability.** JVM-testable logic is covered by unit tests (`speech-engine/src/test`), including the Phase 2 feature extractor. Android-specific behavior has instrumented test source (`speech-engine/src/androidTest`); as of this writing, one such test (`MicrophoneToHindiTextInstrumentedTest`) has confirmed on-device execution evidence, while the others have been written but have no confirmed execution evidence — see `current-state.md` and `testing-and-validation.md` for the precise, per-test breakdown.
- **Bounded buffering.** `SpeechSegmenter` bounds memory via `maxSegmentDurationMs` and a fixed-size pre-roll ring buffer; it does not retain audio indefinitely.
- **Lifecycle/resource safety.** `AudioRecorder.release()` stops capture and cancels its coroutine scope; `stop()` lets an in-flight blocking read finish before releasing the microphone rather than releasing concurrently with a read.

## What is explicitly not yet designed

The STT integration interface is now implemented (`SpeechRecognizer`, see above) — this is no longer an open design question, though it has only one implementation and covers only Hindi. TTS integration interface, message protocol shape, and transport abstraction are still not yet designed in this repository. Do not invent interfaces for these; when Stage 3/4 begin, a real design decision is required and should be recorded in `decisions.md`.
