# iTANTRA Current State

This is the single most important file to keep accurate: it records what is actually true about the repository right now, distinct from what is planned. If this file goes stale relative to the actual repository, treat the repository as the source of truth and flag the discrepancy rather than trusting this file blindly.

**Recorded as of:** September 2026.

## Identity

- **Developer:** Tanmay
- **Branch:** `Tanmay-iTarntra`
- **Repository:** iTANTRA

## Current stage

**Stage 1 — Audio + VAD Foundation** (see `stages.md`).

**Status: Implementation complete.**

## Implemented (verified against `speech-engine/src/main/kotlin/com/itantra/speechengine/`)

- Android library module `speech-engine` (Gradle module `:speech-engine`)
- `audio/AudioConfig` — PCM format configuration
- `audio/AudioFrame` — one captured PCM chunk
- `audio/AudioRecorder` — `AudioRecord`-based microphone capture
- `audio/AudioCaptureException` — sealed capture-error hierarchy
- `vad/VoiceActivityDetector` — VAD abstraction (interface)
- `vad/EnergyZcrVoiceActivityDetector` — energy + zero-crossing-rate VAD (the sole implementation)
- `vad/VadConfig`, `vad/VadState`
- `segmentation/SpeechSegmenter`, `segmentation/SegmenterConfig`, `segmentation/SpeechSegment`
- JVM unit tests (`speech-engine/src/test`)
- Android instrumented test source (`speech-engine/src/androidTest`) — written, not yet executed on a device

See `architecture.md` for how these fit together.

## Validation status

- **20 JVM tests passed** (9 in `EnergyZcrVoiceActivityDetectorTest`, 11 in `SpeechSegmenterTest`) — Level 2 (see `testing-and-validation.md`).
- **Android Gradle build: pending.** The required Android SDK platform/build-tools are not confirmed installed in the current development environment.
- **AudioRecord Android compilation/runtime validation: pending** (Level 3/4).
- **Physical-device validation: pending** (Level 4).
- **OnePlus Nord CE4 microphone validation: pending** (Level 4, reference test device per README).

**Stage 1 must not be represented as fully device-validated.** Only Level 2 (JVM unit tests) has actually been reached.

## Parked tasks (intentionally deferred, not forgotten)

1. Android SDK setup
2. Android build validation
3. AudioRecord validation
4. OnePlus Nord CE4 microphone test
5. Git checkpoint / history cleanup
6. Manual commit / push

These are the developer's responsibility to resume; Claude Code should not attempt to perform items 5–6 (Git write operations) under any circumstance without explicit instruction, and should not attempt items 1–4 (SDK install, build, device test) without explicit instruction either — see `instructions.md`.

## Next stage

**Stage 2 — Offline STT**, to begin only after Stage 1 validation (the parked tasks above) is resolved, and only on explicit instruction.

### Current STT status

**Not implemented.**

### STT model/runtime

**Under Evaluation.** No model or runtime has been selected. Do not select or download a model without explicit instruction (see `instructions.md`, Model rules, and `decisions.md`).
