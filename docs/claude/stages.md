# iTANTRA Development Stages

This is the controlled-scope roadmap Claude Code must use to gate work. Claude must not begin a stage beyond the current one (see `current-state.md` for the current stage) without explicit instruction from the developer, even if a later stage seems like a natural continuation.

**Note on numbering:** this document uses "Stage 0–15" as the current, agreed development sequence for Claude Code control purposes. `README.md`'s own "Roadmap" section uses "Phase 0–13" terminology with a similar but not identical breakdown (in particular, Stage 13 "Alert / Priority Messaging" and Stage 14 "UI/UX Polish" are not broken out as separate phases in the README). This file is the canonical source for Claude's stage-gating; reconciling the two documents' terminology is left to the developers and is not something Claude should do unprompted.

Status values used below follow `CLAUDE.md`'s vocabulary: **Completed**, **Implemented**, **Planned**, **Under Evaluation**, **Pending Validation**.

---

## Stage 0 — Environment / Foundation

**Status:** Complete

- **Objective:** Establish the repository, build tooling, and initial module layout needed for all later work.
- **Major deliverables:** Git repository; root Gradle build (`settings.gradle.kts`, `build.gradle.kts`, wrapper); `speech-engine` module scaffold; initial README.
- **Expected input:** None (project start).
- **Expected output:** A buildable Gradle project skeleton with one library module.
- **Acceptance criteria:** Repository exists with version control; Gradle project structure present; module boundaries declared.
- **Dependencies:** None.
- **Explicitly out of scope:** Any speech processing, application UI, or transport code.

## Stage 1 — Audio + VAD Foundation

**Status:** Implementation Complete; Validation Pending (Android/device)

- **Objective:** Capture microphone audio on Android and segment it into discrete speech utterances using an offline voice-activity detector, independent of any transport or STT concern.
- **Major deliverables:** `AudioConfig`, `AudioFrame`, `AudioRecorder`, `AudioCaptureException`; `VoiceActivityDetector` interface, `VadState`, `VadConfig`, `EnergyZcrVoiceActivityDetector`; `SegmenterConfig`, `SpeechSegmenter`, `SpeechSegment`; JVM unit tests; Android instrumented test source.
- **Expected input:** Raw microphone audio (via Android `AudioRecord`).
- **Expected output:** A stream of `SpeechSegment` objects, each a bounded span of PCM audio corresponding to one detected utterance.
- **Acceptance criteria:** JVM unit tests pass for VAD and segmentation logic (done — see `current-state.md`); Android Gradle build succeeds; instrumented tests pass on a physical device with a real microphone (pending).
- **Dependencies:** Stage 0.
- **Explicitly out of scope:** Any STT, TTS, transport, or UI code; noise-robustness benchmarking; accuracy tuning of VAD thresholds against real recordings (parked for a later evaluation pass — see `decisions.md`, Decision 001).

## Stage 2 — Offline STT

**Status:** Next (not started)

- **Objective:** Convert a `SpeechSegment` into recognized text entirely on-device, without any network dependency.
- **Major deliverables (proposed, not finalized):** STT engine/runtime integration; a model or set of models covering the target language scope; an interface boundary that accepts a `SpeechSegment`-shaped input and produces recognized text plus, where available, confidence/timing metadata.
- **Expected input:** `SpeechSegment` (Stage 1 output).
- **Expected output:** Recognized text (and associated metadata) per segment.
- **Acceptance criteria:** Under Evaluation — will be defined when the STT runtime/model is selected. Expected to include at minimum: successful offline recognition of test utterances, and a measured WER on a defined test set (see `testing-and-validation.md`).
- **Dependencies:** Stage 1 (a working segment source). Does not require Stage 4 (transport) or Stage 3 (TTS).
- **Explicitly out of scope:** Transport, TTS, UI, multilingual completeness (single-language proof of concept is acceptable before expanding — see Stage 8). Model selection itself is Under Evaluation and must not be finalized or downloaded without explicit instruction.

## Stage 3 — Offline TTS

**Status:** Planned

- **Objective:** Synthesize speech audio from received text entirely on-device.
- **Major deliverables (proposed):** TTS engine/runtime integration; a model or set of models covering the target language scope; playback of synthesized audio.
- **Expected input:** Text message (from transport, or directly from Stage 2 for local testing).
- **Expected output:** Audible synthesized speech.
- **Acceptance criteria:** Under Evaluation — expected to include successful offline synthesis and a subjective/measured intelligibility check (see `testing-and-validation.md`).
- **Dependencies:** Can be developed in parallel with Stage 2 using synthetic text input; full pipeline validation requires Stage 2.
- **Explicitly out of scope:** Transport, UI, multilingual completeness, voice naturalness tuning beyond an initial working baseline.

## Stage 4 — Wi-Fi Direct

**Status:** Planned

- **Objective:** Establish a device-to-device transport over Wi-Fi Direct capable of carrying compact text messages between two Android devices without internet access.
- **Major deliverables (proposed):** Device discovery/pairing flow; connection establishment; message send/receive over the established link; basic connection-loss handling.
- **Expected input:** A text message ready to transmit (from Stage 2, or a test harness).
- **Expected output:** The same text message received on a second device.
- **Acceptance criteria:** Under Evaluation — expected to include successful message delivery between two physical devices and documented behavior on connection loss.
- **Dependencies:** Stage 0 (build infrastructure); independent of Stages 2/3 for initial transport-only testing.
- **Explicitly out of scope:** Bluetooth (Stage 9), constrained-link simulation (Stage 10), message protocol versioning beyond what is needed for a working link. This is Paras's ownership area — see `ownership.md`.

## Stage 5 — First End-to-End MVP

**Status:** Planned

- **Objective:** Demonstrate the full pipeline (speech in on Device A → STT → transport → TTS → speech out on Device B) for one language and one transport mechanism.
- **Major deliverables (proposed):** Integration of Stages 1–4 into a single working flow on two physical devices.
- **Expected input:** Spoken utterance on Device A.
- **Expected output:** Synthesized speech on Device B.
- **Acceptance criteria:** Under Evaluation — expected to require a successful, observed two-device demonstration, not merely component-level tests.
- **Dependencies:** Stages 1, 2, 3, 4.
- **Explicitly out of scope:** Push-to-talk/continuous mode polish (Stages 6–7), multilingual support (Stage 8), Bluetooth (Stage 9), performance optimization (Stage 11).

## Stage 6 — Push-to-Talk

**Status:** Planned

- **Objective:** Implement explicit press-to-transmit half-duplex interaction on top of the Stage 5 MVP.
- **Major deliverables (proposed):** UI control for press-to-talk; recording/segmentation gated by the control rather than by continuous VAD-driven segmentation.
- **Expected input:** User press/release interaction plus microphone audio.
- **Expected output:** One transmitted message per press-and-release cycle.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stage 5.
- **Explicitly out of scope:** Continuous mode (Stage 7). This is primarily Paras's (UI/interaction) ownership area with a Tanmay-side dependency on segment boundaries — see `ownership.md`.

## Stage 7 — Continuous Communication Mode

**Status:** Planned

- **Objective:** Support pause-based segmentation without an explicit transmit action, using Stage 1's VAD/segmentation continuously rather than per-press.
- **Major deliverables (proposed):** Continuous listening mode; segment-triggered transmission; handling of overlapping/rapid utterances.
- **Expected input:** Continuous microphone audio.
- **Expected output:** A message transmitted automatically per detected utterance.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stage 5; benefits from Stage 6 being in place for comparison.
- **Explicitly out of scope:** Multilingual support, Bluetooth, benchmarking.

## Stage 8 — 10-Language Expansion

**Status:** Planned

- **Objective:** Extend STT and TTS coverage from the initial single-language MVP to the full target set (English, Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali — see README, Language Support).
- **Major deliverables (proposed):** Per-language model integration or a routing mechanism across models; per-language status tracking.
- **Expected input:** Speech/text in each target language.
- **Expected output:** Correct-language recognition/synthesis per language, tracked independently.
- **Acceptance criteria:** Under Evaluation. The README already notes that no single permissively licensed, mobile-sized model is known to cover all ten languages for either STT or TTS; the approach (routed models vs. phased rollout vs. other) is itself an open decision, not yet finalized.
- **Dependencies:** Stages 2, 3.
- **Explicitly out of scope:** Transport, UI polish, benchmarking beyond per-language functional correctness.

## Stage 9 — Bluetooth

**Status:** Planned

- **Objective:** Add Bluetooth as a second device-to-device transport option alongside Wi-Fi Direct.
- **Major deliverables (proposed):** Bluetooth discovery/pairing; message send/receive; a transport-selection mechanism if both are supported concurrently.
- **Expected input/output:** Same as Stage 4, over Bluetooth.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stage 4 (transport abstraction should already exist).
- **Explicitly out of scope:** Constrained-link simulation. This is Paras's ownership area — see `ownership.md`.

## Stage 10 — Constrained-Link Simulator

**Status:** Planned

- **Objective:** Build a harness that artificially restricts bandwidth, latency, packet loss, and jitter on the transport link to evaluate system behavior under adverse conditions (see README, Constrained-Link Evaluation).
- **Major deliverables (proposed):** A configurable link-degradation harness; test scenarios covering the four variables above.
- **Expected input:** The working transport layer from Stages 4/9.
- **Expected output:** Observed system behavior (success/failure/degradation) under each simulated condition, with methodology recorded.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stages 4, 9.
- **Explicitly out of scope:** Fixing any reliability issues discovered — that is Stage 12.

## Stage 11 — Benchmarking + Optimization

**Status:** Planned

- **Objective:** Measure the performance metrics defined in `testing-and-validation.md` (latency, CPU, RAM, model size, APK size, RTF, WER, network metrics) against the working system and optimize where measurements show a genuine need.
- **Major deliverables (proposed):** A benchmarking harness; recorded measurements with methodology; targeted optimizations justified by those measurements.
- **Expected input:** The working end-to-end system from prior stages.
- **Expected output:** Documented, reproducible performance numbers; optimizations with before/after measurements.
- **Acceptance criteria:** Under Evaluation. No optimization in this stage may be justified by assumption alone — see `engineering-rules.md`, Performance.
- **Dependencies:** Stage 5 at minimum; more meaningful once Stages 8–10 are also in place.
- **Explicitly out of scope:** New features.

## Stage 12 — Reliability

**Status:** Planned

- **Objective:** Harden the system against failures discovered during benchmarking and constrained-link testing (dropped connections, partial messages, crashes, resource leaks).
- **Major deliverables (proposed):** Reconnection handling; error recovery paths; resource-cleanup audits.
- **Expected input:** Failure modes identified in Stages 10–11.
- **Expected output:** A system that degrades predictably rather than failing silently or crashing.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stages 10, 11.
- **Explicitly out of scope:** New features.

## Stage 13 — Alert / Priority Messaging

**Status:** Planned

- **Objective:** Support a distinct high-priority/alert message class that can preempt or be distinguished from normal conversational messages, relevant to the disaster-response use case.
- **Major deliverables (proposed):** Under Evaluation — priority marking in the message protocol; UI treatment for alerts; possible preemption of in-flight normal messages.
- **Expected input/output:** Under Evaluation.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stage 5 (working message pipeline) at minimum.
- **Explicitly out of scope:** General UI polish (Stage 14).

## Stage 14 — UI/UX Polish

**Status:** Planned

- **Objective:** Improve the application's usability and visual design once functional behavior across prior stages is in place.
- **Major deliverables (proposed):** Under Evaluation — refined Jetpack Compose UI, accessibility passes, visual design consistency.
- **Expected input/output:** Under Evaluation.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** Stages 5–7 at minimum.
- **Explicitly out of scope:** New functional features not already covered by an earlier stage. This is Paras's ownership area — see `ownership.md`.

## Stage 15 — Final Integration / Documentation / Demo

**Status:** Planned

- **Objective:** Bring all prior stages together into a coherent, demonstrable system with complete documentation for SIH submission/demonstration.
- **Major deliverables (proposed):** Final integration pass; complete documentation set; demo script/scenario; final benchmark report.
- **Expected input:** All prior stages.
- **Expected output:** A demonstrable end-to-end system and submission-ready documentation.
- **Acceptance criteria:** Under Evaluation.
- **Dependencies:** All prior stages.
- **Explicitly out of scope:** New functional development beyond fixes needed for integration/demo stability.

---

## Stage-gating rule

Claude Code must not begin implementation work on a stage later than the one recorded as current in `current-state.md`, even partially (e.g. starting Stage 2 model research while Stage 1 validation is still pending), unless the developer explicitly instructs it in that request.
