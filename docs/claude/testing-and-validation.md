# iTANTRA Testing and Validation Standard

## Core principle

**"Implemented" does not mean "validated on device."**
**"Unit tests pass" does not mean "Android runtime is verified."**
**"Works locally" does not mean "SIH performance requirements are satisfied."**

Every status claim made about this project — in conversation, in commit messages (written by the developer, not Claude), or in documentation — must specify which validation level below it is actually based on. Claude Code must never report a higher level of validation than what was actually observed in the current session.

## Validation levels

### Level 1 — Static inspection

- Code review
- Architecture review
- Diff review

No execution involved. Confirms the code reads correctly and matches intended design; does not confirm it runs or behaves correctly.

### Level 2 — Unit tests

- Deterministic JVM tests, run without an Android device or emulator.
- Current status: 21 JVM tests pass for `speech-engine` (9 VAD, 11 segmentation, 1 STT feature-extraction correctness) — see `current-state.md`. Confirmed from `speech-engine/build/test-results/testDebugUnitTest/` output; this is local, gitignored build output, not something committed to the repository.

### Level 3 — Build validation

- Gradle compilation of the actual Android target (not just JVM-testable code).
- Lint / static analysis checks where configured.
- Current status: **confirmed working**, superseding the prior "pending — SDK not installed" note. Local (gitignored) build output shows a debug instrumented-test APK was built and installed on a physical device via `connectedAndroidTest` on 2026-09-13 (see `current-state.md`). This evidence is local to one development machine and is not reproducible from a fresh clone without redoing the build.

### Level 4 — Device validation

- Physical Android device (not an emulator).
- Real microphone input.
- Real audio playback.
- Real Android lifecycle behavior (backgrounding, permission dialogs, etc.).
- Current status: **partially evidenced, not complete.** One instrumented test (`MicrophoneToHindiTextInstrumentedTest`, exercising `AudioRecorder` + `SpeechSegmenter` + `IndicConformerRecognizer` together from a real spoken utterance) has a confirmed PASS on the reference test device (OnePlus Nord CE4, device model `CPH2613`), evidenced by local build output dated 2026-09-13 — see `current-state.md` for the exact measurements logged. The other two STT instrumented tests (`IndicConformerRecognizerInstrumentedTest`, `IndicConformerOnnxDeviceValidationTest`) were run on the same OnePlus on 2026-09-25, with results committed under `speech-engine/validation-results/device/`. The known-WAV milestone passed with an exact match (FP32); the INT8 MatMul+Conv export failed to load. See `current-state.md` and `android-stt-readiness.md`. No device evidence exists yet for the target Samsung SM-T225, or for `AudioRecorderInstrumentedTest` (Stage 1's own dedicated device test). Real audio playback is not applicable yet (no TTS exists).

### Level 5 — End-to-end validation

- Two physical phones.
- Full path: speech in → STT → transport → TTS → speech out.
- Not applicable yet — requires Stages 2–4 to exist.

### Level 6 — Performance benchmarking

- CPU
- RAM
- Latency
- Model size
- APK size
- Real-time factor (RTF)
- Word Error Rate (WER)
- Network metrics (see below)

Not applicable yet — requires a working implementation to measure.

## Reporting rule

When describing the state of any component, state the highest level actually reached, and explicitly name any level not yet reached that a reader might otherwise assume. Example of the required precision: "Level 2 passed (20 JVM tests); Level 3 (Android build) and Level 4 (device) are pending" — not "Phase 1 works."

## Speech evaluation metrics

### Speech-to-Text (Stage 2)

- Word Error Rate (WER)
- Sentence completion latency
- Language-wise accuracy (tracked per target language, not as a single aggregate)

### Text-to-Speech (Stage 3)

- Processing latency
- Playback start latency
- Intelligibility / quality evaluation

### End-to-end (Stage 5+)

- Speech end → received TTS audio start (the user-perceived round-trip delay)
- Real-time factor
- Total processing latency

### System (ongoing from Stage 1 device validation onward)

- CPU usage
- RAM usage
- APK size
- Model footprint (storage)
- Idle listening cost (relevant to continuous mode, Stage 7)

### Network (Stage 4+)

- Payload size per message
- Effective bitrate
- Packet count
- Latency
- Packet loss
- Jitter

## No fabricated results

No number for any metric above may be stated unless it was actually measured in this session or is cited from an existing, dated measurement already recorded in this repository's documentation. Where a metric has not been measured, write "Not yet measured" or "To be benchmarked" rather than omitting it or implying a value.
