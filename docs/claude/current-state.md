# iTANTRA Current State

This is the single most important file to keep accurate: it records what is actually true about the repository right now, distinct from what is planned. If this file goes stale relative to the actual repository, treat the repository as the source of truth and flag the discrepancy rather than trusting this file blindly.

**Recorded as of:** September 2026. Updated 2026-09-25 with the Android STT readiness investigation (`android-stt-readiness.md`); the sections touched are "Test sources", Level 2, Level 4, "Android STT readiness" and open items 10–11. Updated 2026-09-23 by a documentation-accuracy audit (in preparation for Paras beginning work on this branch) that compared this file against the actual repository source, git history, and local build/test output. Every change made in that pass is reflected below and is traceable to specific source files, commits, or local build artifacts named inline — nothing here was assumed or estimated.

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
- Device-run script: `speech-engine/scripts/run_stt_device_validation.sh` (added 2026-09-25). It pushes the models and fixtures, runs the STT instrumented tests one method at a time, and writes results to the non-gitignored `speech-engine/validation-results/device/`. Not yet executed on any device. See `android-stt-readiness.md`.

See `architecture.md` for how all of these fit together.

## Validation status

### Level 2 — JVM unit tests: PASS

**21 JVM tests pass**: 9 in `EnergyZcrVoiceActivityDetectorTest`, 11 in `SpeechSegmenterTest`, 1 in `MelSpectrogramFeatureExtractorTest` (the last checks the Stage 2 feature extractor's output against a NeMo-computed reference for a known Hindi clip). Confirmed from `speech-engine/build/test-results/testDebugUnitTest/TEST-*.xml`, timestamped 2026-09-13T04:47 UTC. Re-run on 2026-09-25 with `./gradlew :speech-engine:testDebugUnitTest --rerun`: 21/21 pass (report timestamp 2026-09-25T09:15 UTC). This local build output is gitignored (`build/` is excluded via `.gitignore`) — it is evidence from this development machine, not something committed to the repository or guaranteed to exist after a fresh clone.

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

**2026-09-25, same OnePlus CPH2613** (Android 16), run via `speech-engine/scripts/run_stt_device_validation.sh`. Unlike the run above, this evidence is **committed** in `speech-engine/validation-results/device/20260925T091736Z/` (`summary.txt`, per-test instrumentation output, test-process logcat). Details: `android-stt-readiness.md`, section 3.

- **`IndicConformerRecognizerInstrumentedTest.knownPcmClip_recognizesCorrectHindiText`** — **PASSED**, FP32. This is the known-WAV milestone: bundled 18.18 s PCM16 clip → Kotlin features → ORT → CTC → text, **exact match** with the reference.
  - Load 4,829 ms; recognize 6,029 ms (RTF 0.33).
  - PSS 1.02 GB after load, 1.14 GB after inference.
  - The low-memory killer terminated 4 cached background apps during this test; the test process itself was not killed.
- **`IndicConformerRecognizerInstrumentedTest.knownPcmClip_repeatedInferenceIsStable`** — **PASSED**, FP32. 3 identical results.
- **`IndicConformerOnnxDeviceValidationTest`:**
  - `fp32_realHindiFeatures_ctcGreedyDecode_onDevice` **PASSED**: exact match, 4,763 ms.
  - `fp32_loadsAndRunsSyntheticTensorOnDevice` **PASSED**: 569 ms mean, PSS ≈ 1.01 GB.
  - `int8_loadsAndRunsSyntheticTensorOnDevice` **FAILED** at session creation: `ORT_NOT_IMPLEMENTED ... ConvInteger(10)`.
  - `int8Matmul_loadsAndRunsSyntheticTensorOnDevice` (added 2026-09-25) **PASSED**: 319 ms mean, PSS 0.47 GB.

No confirmed execution evidence (in local build output, or anywhere else in the repository) was found for:

- `AudioRecorderInstrumentedTest` — Stage 1's own dedicated device test for `AudioRecorder` in isolation. (Real microphone capture *was* exercised on-device, but only indirectly, as part of the STT pipeline test above — not through this dedicated test.)
- `IndicConformerRecognizerInstrumentedTest` run with the INT8 MatMul-only model (`-e modelFile indicconformer_hi_int8_matmul.onnx`, parameter added 2026-09-25; the device was disconnected before it could run).

**Important caveat for handoff:** the 2026-09-13 Level 3/4 evidence above lives only in `speech-engine/build/`, which is gitignored (the 2026-09-25 run is committed, see above). None of it will be present when Paras (or anyone else) clones this branch or `main` fresh. If this validation needs to be relied on going forward, it should be re-run and its output captured somewhere version-controlled, rather than assumed to still exist or to transfer with the repository.

### Android STT readiness for the Samsung SM-T225 (2026-09-25)

The target device is a Samsung SM-T225 (Android 14, arm64-v8a). All device evidence so far is from the OnePlus CPH2613 (7.4 GB RAM, dot-product/i8mm-capable CPU). The SM-T225's published spec is 3–4 GB RAM and 8× Cortex-A53 (ARMv8.0-A), so the OnePlus timing and memory figures above do not transfer to it. Full record: `android-stt-readiness.md`. In summary:

- **Desktop comparison, measured on 3 real Hindi clips (34.2 s, 63 words):**
  - FP32 ONNX output is identical to NeMo/PyTorch on 3/3 clips.
  - Both INT8 exports change 2 of 63 words, in words NeMo already got wrong. WER vs reference is 6.35% for every engine.
  - INT8 MatMul-only was the fastest on desktop (RTF 0.074 vs FP32 0.086), with the lowest peak RSS (573 MB vs 973 MB).
  - INT8 MatMul+Conv is 2.6× slower than FP32. Desktop x86 numbers only.
- **INT8 MatMul+Conv on Android: confirmed not loadable** on ORT Android 1.22.0 (`ORT_NOT_IMPLEMENTED` for `ConvInteger` with uint8 × int8 inputs, observed on device). Decision 006, `architecture.md` and the `IndicConformerRecognizer` KDoc are therefore accurate and unchanged. This is a runtime-build property, so it applies to the SM-T225 too.
- **INT8 MatMul-only on Android: loads and runs** (OnePlus). It was 1.78× faster than FP32 at 2.1× less PSS on synthetic input. Its real-audio transcription on-device has not been run yet.
- **Device status:** nothing has run on the SM-T225. It was never attached during the 2026-09-25 session.

### Level 5 / Level 6

Not applicable yet — no transport exists (Level 5) and no benchmarking harness exists (Level 6). No WER, latency, CPU, or RAM figure beyond the single-run numbers logged above (which are one observation, not a benchmark) has been produced.

**Neither Stage 1 nor Stage 2 should be represented as fully device-validated.** Stage 1: Level 2 fully reached; Level 3 confirmed working; Level 4 reached for the combined pipeline via the STT test above, but not via Stage 1's own dedicated instrumented test. Stage 2: Level 2 reached for the feature extractor. Level 4 reached on the OnePlus CPH2613: once for a spoken utterance without an accuracy assertion (2026-09-13), and once for a known WAV clip with an exact-match assertion (2026-09-25, FP32). Level 4 is not reached on the SM-T225 target device. WER (Level 6) not measured.

## Note on stage-gating

Before this audit, `stages.md` recorded Stage 2 as "Next (not started)" and this file recorded STT as "Not implemented." Neither was accurate against the actual repository: Stage 2 (Hindi-only) work — `SpeechRecognizer`, `IndicConformerRecognizer`, `MelSpectrogramFeatureExtractor`, `CtcGreedyDecoder`, plus JVM and instrumented tests — already existed in this branch's git history, added in commits `3573b89` through `f5eefc5` (2026-09-13), all after the Stage 1 validation-status commit (`6ea213b`, 2026-09-12). This audit (2026-09-23) brings the documentation in line with the repository; it does not itself authorize or begin any further Stage 2 work or any later stage, and it takes no position on why Stage 2 implementation began before Stage 1's own device validation was complete and documented — that is a process question for the developer(s), not something resolved here. Source comments in the STT code also reference an informal internal "Stage 2 Gate 1/2", "Stage 3", "Stage 3B" validation process and external artifacts under `~/itantra-stt-validation/` on the development machine; that numbering does not correspond to anything in `stages.md`, and those external artifacts are outside this repository and were not independently verified by this audit.

## Parked / open tasks

1. ~~Android SDK setup~~ — evidenced as working (see Level 3 above); still worth the developer explicitly confirming the SDK/build-tools versions in use, since this file cannot verify a colleague's separate machine.
2. ~~Android build validation~~ — evidenced (see Level 3 above), with the local/gitignored caveat noted there.
3. AudioRecord validation — exercised indirectly on-device via the STT pipeline test; **Stage 1's own dedicated `AudioRecorderInstrumentedTest` still has no confirmed run.**
4. OnePlus Nord CE4 microphone test — one PASS evidenced (see Level 4 above), for one utterance, without an accuracy claim. The two other STT device tests were run on the same phone on 2026-09-25 (see Level 4).
5. Record the IndicConformer model's own license (distinct from ONNX Runtime's, which is recorded as MIT) — see `decisions.md`, Decision 006. Not yet done.
6. Decide and document a model-file distribution mechanism (the ~470 MB model is not committed to the repository) — see `architecture.md`.
7. Measure WER on a defined Hindi test set — not yet done.
8. Git checkpoint / history cleanup
9. Manual commit / push
10. Run `speech-engine/scripts/run_stt_device_validation.sh` on the Samsung SM-T225 (target device). This covers the known-WAV milestone with FP32 and with INT8 MatMul-only, plus load, timing and memory for all three exports. Commit the resulting `speech-engine/validation-results/device/<stamp>/` directory. See `android-stt-readiness.md`.
11. ~~Resolve the INT8 `ConvInteger` discrepancy~~ — resolved 2026-09-25 on-device: the INT8 MatMul+Conv export does not load on ORT Android 1.22.0, confirming the existing documentation. See "Android STT readiness" above.

Items 8–9 are Git write operations: Claude Code must not perform them under any circumstance without explicit instruction (see `instructions.md`). Items 1–7 and 10 remain the developer's to resume or resolve; this audit did not perform any of them (no SDK install, no build, no device test, no model download, no Git write operation) and is documentation-only.

## Next stage

**Stage 3 — Offline TTS** and **Stage 8 — 10-Language Expansion** (for STT) both remain **Planned / not started**, and must not be started without explicit instruction (see `stages.md`). Stage 2 itself is not complete — see Validation status above for exactly what remains (WER measurement, license recording, model distribution decision, confirming the two unconfirmed device tests).
