# iTANTRA Current State

This is the single most important file to keep accurate: it records what is actually true about the repository right now, distinct from what is planned. If this file goes stale relative to the actual repository, treat the repository as the source of truth and flag the discrepancy rather than trusting this file blindly.

**Recorded as of:** September 2026. Updated 2026-09-23 by a documentation-accuracy audit (in preparation for Paras beginning work on this branch) that compared this file against the actual repository source, git history, and local build/test output. Every change made in that pass is reflected below and is traceable to specific source files, commits, or local build artifacts named inline — nothing here was assumed or estimated.

## Identity

- **Developer:** Tanmay
- **Branch:** `Tanmay-iTarntra`
- **Repository:** iTANTRA

## Current stage

**Stage 1 — Audio + VAD Foundation** (see `stages.md`): implementation complete.
**Stage 2 — Offline STT** (see `stages.md`): in progress, Hindi only. This is a change from what this file previously said ("Not implemented") — see "Note on stage-gating" below.

## Implemented (verified against `speech-engine/src/main/kotlin/com/itantra/speechengine/`)

### Stage 1 — audio capture, VAD, segmentation

- Android library module `speech-engine` (Gradle module `:speech-engine`)
- `audio/AudioConfig` — PCM format configuration
- `audio/AudioFrame` — one captured PCM chunk
- `audio/AudioRecorder` — `AudioRecord`-based microphone capture
- `audio/AudioCaptureException` — sealed capture-error hierarchy
- `vad/VoiceActivityDetector` — VAD abstraction (interface)
- `vad/EnergyZcrVoiceActivityDetector` — energy + zero-crossing-rate VAD (the sole implementation)
- `vad/VadConfig`, `vad/VadState`
- `segmentation/SpeechSegmenter`, `segmentation/SegmenterConfig`, `segmentation/SpeechSegment`

### Stage 2 — offline STT, Hindi only (package `stt/`)

- `stt/SpeechRecognizer` — the Stage 2 integration interface (`recognize(segment): SpeechRecognitionResult`, `release()`)
- `stt/SpeechRecognitionResult`, `stt/SpeechRecognitionException` — result and sealed error types
- `stt/IndicConformerRecognizer` — the sole implementation: AI4Bharat IndicConformer Hindi model (FP32 ONNX export) run via ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`, a real production dependency of `speech-engine`, not test-only — see `decisions.md`, Decision 006)
- `stt/MelSpectrogramFeatureExtractor` — hand-written log-mel feature extraction (own FFT; precomputed Hann-window and mel-filterbank constants bundled as small resource files under `speech-engine/src/main/resources/com/itantra/speechengine/stt/`)
- `stt/CtcGreedyDecoder` — greedy CTC decoding over the model's 5,633-entry token vocabulary (`tokens.txt`, bundled as a small text resource)

**Explicitly not implemented within Stage 2:** any language other than Hindi; a measured Word Error Rate; on-device raw-PCM→feature-extraction validation (the device tests exercise either the real extractor with live microphone audio, or pre-computed desktop features, per each test's own docstring — not both combined and cross-checked); a decided model-file distribution mechanism (see `architecture.md`, `models` row) — the ~470 MB model file is not, and must not be, committed to this repository; it is loaded by `IndicConformerRecognizer` from a caller-supplied external path.

### Test sources (all stages)

- JVM unit tests: `speech-engine/src/test` — `EnergyZcrVoiceActivityDetectorTest`, `SpeechSegmenterTest`, `MelSpectrogramFeatureExtractorTest`.
- Android instrumented test sources: `speech-engine/src/androidTest` — `AudioRecorderInstrumentedTest`, `IndicConformerOnnxDeviceValidationTest`, `IndicConformerRecognizerInstrumentedTest`, `MicrophoneToHindiTextInstrumentedTest`. See Validation status below for exactly which of these have confirmed on-device execution evidence — writing a test and running it are tracked separately in this file.

See `architecture.md` for how all of these fit together.

## Validation status

### Level 2 — JVM unit tests: PASS

**21 JVM tests pass**: 9 in `EnergyZcrVoiceActivityDetectorTest`, 11 in `SpeechSegmenterTest`, 1 in `MelSpectrogramFeatureExtractorTest` (the last checks the Stage 2 feature extractor's output against a NeMo-computed reference for a known Hindi clip). Confirmed from `speech-engine/build/test-results/testDebugUnitTest/TEST-*.xml`, timestamped 2026-09-13T04:47 UTC. This local build output is gitignored (`build/` is excluded via `.gitignore`) — it is evidence from this development machine, not something committed to the repository or guaranteed to exist after a fresh clone.

### Level 3 — Android build: confirmed working

This supersedes the prior version of this file, which said the Android build was pending because SDK tooling was unconfirmed. Local (gitignored) build output at `speech-engine/build/outputs/androidTest-results/connected/debug/` and `speech-engine/build/reports/androidTests/connected/debug/` shows a debug instrumented-test APK was successfully compiled, installed, and run against a physical device on 2026-09-13. The Android SDK and build tooling are therefore confirmed present and working in the environment that produced this build output — though, again, that build output itself is local and gitignored, not part of the committed repository state.

### Level 4 — Physical-device validation: partially evidenced, not complete

Exactly one instrumented test has confirmed on-device execution evidence:

- **`MicrophoneToHindiTextInstrumentedTest.realMicrophoneUtterance_isSegmentedAndRecognized`** — **PASSED**, device model `CPH2613` (OnePlus; this matches the OnePlus Nord CE4 reference test device named in `README.md`), 2026-09-13T05:06:31Z, from `speech-engine/build/outputs/androidTest-results/connected/debug/CPH2613 - 16/test-result.textproto` and the corresponding JUnit XML. This one test exercises `AudioRecorder` (real microphone), `SpeechSegmenter` (real segmentation), and `IndicConformerRecognizer` (real ONNX inference) together, end to end, from an actual spoken utterance — not synthetic input. Logged measurements, from the run's own logcat capture (`.../CPH2613 - 16/logcat-...-realMicrophoneUtterance_isSegmentedAndRecognized.txt`):
  - Captured utterance duration: 6,879 ms (110,720 samples at 16 kHz)
  - Inference time: 2,327 ms (real-time factor ≈ 0.34, i.e. faster than real time on this device)
  - Total PSS: ≈ 990,879 KB before inference, ≈ 1,005,987 KB after
  - Recognized text: "चाहता हूं कि मैं अभी आयत रख के पहले पड़़ाव पर काम करता हूं"

  Per the test's own docstring and assertions, this is a manual-inspection-only pass: it asserts that a non-empty segment was captured and recognized without crashing, **not** that the recognized text is correct. No transcription-accuracy or WER claim should be drawn from this single run.

No confirmed execution evidence (in local build output, or anywhere else in the repository) was found for:

- `AudioRecorderInstrumentedTest` — Stage 1's own dedicated device test for `AudioRecorder` in isolation. (Real microphone capture *was* exercised on-device, but only indirectly, as part of the STT pipeline test above — not through this dedicated test.)
- `IndicConformerOnnxDeviceValidationTest` — on-device ONNX Runtime timing/RAM feasibility checks.
- `IndicConformerRecognizerInstrumentedTest` — the test that asserts exact-match recognition against a known reference transcript, and repeated-inference stability.

These three must be treated as **written, not confirmed executed** — not as passing and not as failing — until they are actually run on a device and their output is observed and recorded here.

**Important caveat for handoff:** all of the Level 3/4 evidence above lives only in `speech-engine/build/`, which is gitignored. None of it will be present when Paras (or anyone else) clones this branch or `main` fresh. If this validation needs to be relied on going forward, it should be re-run and its output captured somewhere version-controlled, rather than assumed to still exist or to transfer with the repository.

### Level 5 / Level 6

Not applicable yet — no transport exists (Level 5) and no benchmarking harness exists (Level 6). No WER, latency, CPU, or RAM figure beyond the single-run numbers logged above (which are one observation, not a benchmark) has been produced.

**Neither Stage 1 nor Stage 2 should be represented as fully device-validated.** Stage 1: Level 2 fully reached; Level 3 confirmed working; Level 4 reached for the combined pipeline via the STT test above, but not via Stage 1's own dedicated instrumented test. Stage 2: Level 2 reached for the feature extractor; Level 4 reached once, for one utterance, without an accuracy assertion; WER (Level 6) not measured.

## Note on stage-gating

Before this audit, `stages.md` recorded Stage 2 as "Next (not started)" and this file recorded STT as "Not implemented." Neither was accurate against the actual repository: Stage 2 (Hindi-only) work — `SpeechRecognizer`, `IndicConformerRecognizer`, `MelSpectrogramFeatureExtractor`, `CtcGreedyDecoder`, plus JVM and instrumented tests — already existed in this branch's git history, added in commits `3573b89` through `f5eefc5` (2026-09-13), all after the Stage 1 validation-status commit (`6ea213b`, 2026-09-12). This audit (2026-09-23) brings the documentation in line with the repository; it does not itself authorize or begin any further Stage 2 work or any later stage, and it takes no position on why Stage 2 implementation began before Stage 1's own device validation was complete and documented — that is a process question for the developer(s), not something resolved here. Source comments in the STT code also reference an informal internal "Stage 2 Gate 1/2", "Stage 3", "Stage 3B" validation process and external artifacts under `~/itantra-stt-validation/` on the development machine; that numbering does not correspond to anything in `stages.md`, and those external artifacts are outside this repository and were not independently verified by this audit.

## Parked / open tasks

1. ~~Android SDK setup~~ — evidenced as working (see Level 3 above); still worth the developer explicitly confirming the SDK/build-tools versions in use, since this file cannot verify a colleague's separate machine.
2. ~~Android build validation~~ — evidenced (see Level 3 above), with the local/gitignored caveat noted there.
3. AudioRecord validation — exercised indirectly on-device via the STT pipeline test; **Stage 1's own dedicated `AudioRecorderInstrumentedTest` still has no confirmed run.**
4. OnePlus Nord CE4 microphone test — one PASS evidenced (see Level 4 above), for one utterance, without an accuracy claim; the two other STT device tests remain unconfirmed.
5. Record the IndicConformer model's own license (distinct from ONNX Runtime's, which is recorded as MIT) — see `decisions.md`, Decision 006. Not yet done.
6. Decide and document a model-file distribution mechanism (the ~470 MB model is not committed to the repository) — see `architecture.md`.
7. Measure WER on a defined Hindi test set — not yet done.
8. Git checkpoint / history cleanup
9. Manual commit / push

Items 8–9 are Git write operations: Claude Code must not perform them under any circumstance without explicit instruction (see `instructions.md`). Items 1–7 remain the developer's to resume or resolve; this audit did not perform any of them (no SDK install, no build, no device test, no model download, no Git write operation) and is documentation-only.

## Next stage

**Stage 3 — Offline TTS** and **Stage 8 — 10-Language Expansion** (for STT) both remain **Planned / not started**, and must not be started without explicit instruction (see `stages.md`). Stage 2 itself is not complete — see Validation status above for exactly what remains (WER measurement, license recording, model distribution decision, confirming the two unconfirmed device tests).
