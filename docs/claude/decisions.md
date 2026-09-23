# iTANTRA Engineering Decision Log

This log records architectural and engineering decisions actually made for this project. Do not add a decision here that has not actually been made and confirmed by the developer(s) — record open questions in `stages.md` (as "Under Evaluation") instead.

Format for every entry: ID, Date (if known), Decision, Context, Alternatives, Reason, Status, Revisit condition.

---

## Decision 001 — Phase 1 VAD baseline

- **Date:** Not recorded.
- **Decision:** Use energy + zero-crossing-rate (ZCR) VAD as the initial Phase 1 baseline (`EnergyZcrVoiceActivityDetector`).
- **Context:** Stage 1 needed a working voice-activity detector to build and test the segmentation pipeline against, without pulling in a model-based dependency this early.
- **Alternatives considered:** WebRTC VAD; Silero VAD.
- **Reason:** Lightweight; dependency-free; offline; easy to test with synthetic PCM; sufficient for establishing speech segmentation.
- **Status:** Accepted for Phase 1.
- **Revisit condition:** During STT integration or noise benchmarking, if baseline detection performance proves insufficient against real recordings.

## Decision 002 — Audio baseline

- **Date:** Not recorded.
- **Decision:** 16 kHz, mono, 16-bit PCM, 20 ms frames (`AudioConfig` defaults).
- **Context:** A concrete audio format was needed to implement capture, VAD, and segmentation.
- **Alternatives considered:** Not recorded.
- **Reason:** 16 kHz mono is a common baseline for offline speech recognition (many Kaldi- and Whisper-derived models expect it); 20 ms is a conventional speech-frame size (also used by WebRTC's own VAD).
- **Status:** Engineering baseline / Under Evaluation. This is explicitly **not** claimed to be optimal.
- **Revisit condition:** Must be benchmarked against whichever offline STT model is selected in Stage 2 — a mismatched sample rate generally requires resampling or degrades recognition accuracy.

## Decision 003 — Speech engine architecture

- **Date:** Not recorded.
- **Decision:** Implement speech processing as a separate `speech-engine` Android library module, rather than inline within the application module.
- **Context:** The application module (`app`) does not exist yet and will be built by a different developer (Paras).
- **Alternatives considered:** Not recorded.
- **Reason:** Separation from the application; testability (most of `speech-engine` has no Android dependency and runs on the JVM); ownership isolation between Tanmay and Paras; enables future integration without restructuring.
- **Status:** Accepted.
- **Revisit condition:** None identified.

## Decision 004 — Application ownership

- **Date:** Not recorded.
- **Decision:** Tanmay owns `speech-engine`; Paras owns the Android application module (`app`).
- **Context:** Two-developer project; module boundaries needed to be assigned to avoid overlapping work.
- **Alternatives considered:** Not recorded.
- **Reason:** Matches each developer's primary responsibility area (see `ownership.md`).
- **Status:** Accepted.
- **Revisit condition:** None identified.

## Decision 005 — Git responsibility

- **Date:** Not recorded.
- **Decision:** Developers manually control Git commits, pushes, branches, and pull requests. Claude Code must not perform Git write operations.
- **Context:** Establishing a permanent project-control system for Claude Code (this documentation set).
- **Alternatives considered:** Not recorded.
- **Reason:** Keeps version-control history and branch/PR state under direct developer control.
- **Status:** Accepted.
- **Revisit condition:** None identified — this is a standing rule, not expected to change per-task.

## Decision 006 — Hindi STT model and inference runtime

- **Date:** Not recorded (implemented 2026-09-13, per git history; not previously logged here).
- **Decision:** Use the AI4Bharat IndicConformer Hindi model (`indicconformer_stt_hi_hybrid_rnnt_large`, FP32 ONNX export) as the first offline STT model, executed via ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`) called directly from `speech-engine`, rather than through sherpa-onnx.
- **Context:** Stage 2 needed a working, on-device, offline Hindi speech recognizer. This decision and its supporting evaluation are recorded in code comments (`speech-engine/build.gradle.kts`, `IndicConformerRecognizer.kt`) referencing an informal internal "Stage 3 / Stage 3B" validation process and external artifacts under `~/itantra-stt-validation/` on the development machine — that external process and its artifacts are outside this repository and were not independently verified by this documentation audit; this entry only records the decision and integration as they exist in the committed source.
- **Alternatives considered:** sherpa-onnx (evaluated per the source comments, not adopted: the direct ONNX Runtime path plus this module's own `MelSpectrogramFeatureExtractor`/`CtcGreedyDecoder` was already working end-to-end and needed no additional native dependency). A dynamically-quantized INT8 export of the same model was also tried and rejected — per source comments, it fails to load on ONNX Runtime Android (`ConvInteger` op unimplemented) — so only the FP32 export is used.
- **Reason:** Working, offline, on-device Hindi recognition without an additional runtime dependency beyond ONNX Runtime; per source comments, verified against a NeMo-computed reference feature set and a known-correct reference transcript before being adopted.
- **Status:** Accepted (evidenced by production code, `build.gradle.kts` dependency promotion from test-only to production `implementation`, and one physical-device pass — see `current-state.md`). **License gap:** the ONNX Runtime license is recorded (MIT, per `build.gradle.kts` comment); the IndicConformer model's own license has not been recorded anywhere in this repository. Per `instructions.md`'s model rules, the model license is distinct from the runtime license and must be recorded separately before this can be considered a fully documented adoption — this is an open item, not a completed one.
- **Revisit condition:** When the model license is confirmed and recorded; when a model-file distribution mechanism is decided (the file is not committed to the repository — see `architecture.md`); when WER is actually measured; when additional languages are evaluated for Stage 8.

---

## Adding future decisions

Use this exact template:

```
## Decision NNN — <short title>

- **Date:** <date, or "Not recorded">
- **Decision:** <what was decided>
- **Context:** <why this decision was needed>
- **Alternatives considered:** <what else was considered, or "Not recorded">
- **Reason:** <why this option was chosen>
- **Status:** <Accepted | Under Evaluation | Superseded by Decision NNN | Rejected>
- **Revisit condition:** <what would trigger reconsidering this>
```

Do not record a decision here on Claude's own initiative — only decisions the developer(s) have actually confirmed.
