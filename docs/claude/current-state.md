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

### Application module `app` — Paras, Stage 1 foundation (added 2026-09-25)

Paras's "Stage 1" is the application foundation. It is separate from `stages.md`'s Stage 1 (Audio + VAD Foundation, Tanmay); `stages.md` does not yet list the application foundation as its own stage.

- Gradle module `:app` (`com.android.application`), namespace and `applicationId` `com.itantra.app`, `compileSdk` 36, `minSdk` 24 (both match `speech-engine`), `targetSdk` 36, Java 17. The package name and `targetSdk` were chosen during Stage 1 by following the existing `com.itantra.*` convention and `compileSdk`; they are not yet recorded in `decisions.md`.
- `MainActivity`: a single Jetpack Compose screen. As of the speech-integration step (below) it has a state line, Start/Stop buttons, last recognized text, and an error line. No transport, protocol, or TTS logic.
- Depends on `:speech-engine` (`implementation(project(":speech-engine"))`). `RECORD_AUDIO` reaches the app only through the manifest merge from `speech-engine`.
- Dependencies added (`app` only): Compose BOM `2026.06.01` (Compose 1.11.4; `ui`, `material3`), `androidx.activity:activity-compose:1.13.0`; test-only: `androidx.compose.ui:ui-test-junit4`, `androidx.test:runner:1.7.0`, `androidx.test.ext:junit:1.3.0`, `junit:junit:4.13.2`. Newer Compose BOMs (2026.08.00+, Compose 1.12) were rejected because their AAR metadata requires `compileSdk` 37 and AGP 9.1+. That failure was observed in `checkDebugAarMetadata`.
- Root build changes: `include(":app")`; `com.android.application` 8.13.2 and `org.jetbrains.kotlin.plugin.compose` 2.4.20 added to the root `plugins { }` block (`apply false`), matching the existing AGP and Kotlin versions. No existing entry was changed.
- Instrumented test source: `app/src/androidTest` — `MainActivityInstrumentedTest` (updated in the speech-integration step, below).
- APK size (measured 2026-09-25, not optimized): `app-debug.apk` 86.9 MB, `app-release-unsigned.apk` 83.8 MB. Most of this is ONNX Runtime native libraries for four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64), inherited from `speech-engine`'s `onnxruntime-android` dependency. ABI filtering or splitting is an open packaging question, not decided here.

Validation (observed 2026-09-25 on Paras's machine, SDK `platforms;android-36`, `build-tools;36.0.0`):

- Level 3: `./gradlew clean test assemble` BUILD SUCCESSFUL. `speech-engine`'s 21 JVM tests still pass in both debug and release variants.
- Level 4: `./gradlew :app:connectedDebugAndroidTest` passed 2 of 2 tests (`launch_rendersFoundationScreen`, `installedApp_declaresSpeechEngineMicrophonePermission`) on a Samsung SM-T225 (Galaxy Tab A7 Lite), Android 14 / API 34, arm64-v8a. Manual `installDebug` plus launcher-intent start: activity resumed, screen rendered, no crash after a background/resume cycle. This is launch-only validation. No speech-engine functionality was exercised from the app.

### Application module `app` — app-side speech integration (added 2026-09-25)

Implemented (package `com.itantra.app.speech`, plus `SpeechViewModel` and `MainActivity`). `speech-engine` source is unchanged.

- `SpeechController` wires speech-engine's public API: an `AudioFrameSource` (production: `AudioRecorderFrameSource`, a direct delegate to `AudioRecorder`) feeds `SpeechSegmenter` (with `EnergyZcrVoiceActivityDetector`), and each finished `SpeechSegment` goes to a `SpeechRecognizer`. State is exposed as an immutable `SpeechState` (`isListening`, `isRecognizing`, `lastText`, `error`) through a `StateFlow`. It uses no model, feature, or decoding internals.
- Threading: only segmentation runs on `AudioRecorder`'s capture thread. `recognize()` runs on a separate worker (`Dispatchers.Default.limitedParallelism(1)`), one recognition at a time, never inside the audio callback. The recognizer is created lazily on that worker when the first utterance finishes, because constructing it loads the model. It is released on the same worker, so never during an inference.
- Each Start creates a fresh `AudioRecorder` and `SpeechSegmenter`. Stop discards an utterance still in progress; utterances already finished are still recognized. Any recognition failure (including a missing model) stops listening and is shown as an error. Capture stops when the activity is no longer visible; a rotation does not stop it (`SpeechViewModel` owns the controller).
- `RECORD_AUDIO` is requested at runtime by `MainActivity` (`ActivityResultContracts.RequestPermission`) only when Start is pressed without it. Denial is shown as an error; the app does not crash.
- Model placement (development only, same as speech-engine's own device tests): `indicconformer_hi.onnx` and `tokens.txt` in the app's external files directory (`/sdcard/Android/data/com.itantra.app/files/`), pushed with adb. Nothing is bundled into the APK. The distribution mechanism is still undecided.
- Dependencies added: `kotlinx-coroutines-android:1.11.0` and `androidx.lifecycle:lifecycle-viewmodel:2.9.4`, both already resolved transitively at those versions and now declared because app code uses them directly. Test-only: `kotlinx-coroutines-test:1.11.0`, `androidx.test:rules:1.7.0`.

Tested (Level 2): `SpeechControllerTest`, 18 JVM tests, PASS (debug and release unit-test variants), using a fake `SpeechRecognizer` and a scripted audio source and VAD driving the real `SpeechSegmenter`. These tests prove app-side wiring, threading order, state, and error handling only; the fake recognizer is not evidence of speech recognition.

Device validated (Level 4, Samsung SM-T225, Android 14, 2026-09-25): `./gradlew :app:connectedDebugAndroidTest` passed 3 of 3 (`launch_rendersSpeechScreenInIdleState`, `startThenStop_togglesListeningState` with RECORD_AUDIO pre-granted, `installedApp_declaresSpeechEngineMicrophonePermission`). Manual adb-driven check on a fresh install: Start shows the system permission dialog; "Don't allow" shows the denial error with no crash; granting moves the app to Listening (`AudioRecorder` logged `Recording started: sampleRate=16000Hz`); Stop returns to Idle (`Recording stopped`).

**Superseded 2026-09-26:** real model inference from the app has since run on the SM-T225; see the next section.

### Application module `app` — Start/Stop utterance flow with the real recognizer (added 2026-09-26)

Implemented:

- **Stop recognizes what was said.** `SpeechSegmenter.flush()` was added to `speech-engine`. It is a small, backward-compatible addition, with 4 new JVM tests in `SpeechSegmenterTest`. `SpeechController.stop()` now flushes the utterance in progress and recognizes it instead of discarding it. Capture errors, recognition failures and teardown still discard. The capture thread (`processFrame`) and the caller of `stop()` (`flush`) are serialized on a per-session lock.
- **Model preload.** Start creates the recognizer (loads the model) on the recognition worker immediately. `SpeechState.isLoadingModel` drives a "Loading speech model…" line, and a missing or unloadable model is reported at Start rather than after the first utterance. Utterances finished during the load queue behind it. A second Start reuses the loaded recognizer.
- **Result and timing.** `SpeechState.lastText` became `lastResult` (the full `SpeechRecognitionResult`). The UI shows the text plus "Recognition took X ms for about Y ms of audio". `SpeechViewModel` logs `Model loaded: … loadTimeMs=` and one `Recognized: inferenceTimeMs=… audioSpanMs=… text=` line per result (tag `SpeechViewModel`).

Tested:

- Level 2: `SpeechControllerTest` 24/24 and `speech-engine` 25/25 (including `SpeechSegmenterTest` 15/15), both debug and release unit-test variants.
- A deliberate mutation (Stop not flushing) made exactly the two Stop tests fail.
- Level 4 instrumented: `./gradlew :app:connectedDebugAndroidTest` 3/3 on the SM-T225. This includes `start_withoutModelFile_reportsModelNotFound_andReturnsToIdle`, which drives the real `IndicConformerRecognizer` into `ModelNotFound`.

Device validated (Level 4, Samsung SM-T225, Android 14, 2026-09-26, FP32 `indicconformer_hi.onnx` sha256 `fd2a2d26…`, pushed to the app's external files directory):

- **Model load:** `loadTimeMs=12859`. App TOTAL PSS peaked at about 1.00 GB (1,004,133 KB). No low-memory-killer kills, no crash. `MemAvailable` on the tablet was 419 MB afterwards, with the app still holding the model.
- **Live speech:** a person speaking Hindi into the tablet produced visible text through microphone → `AudioRecorder` → `SpeechSegmenter` → `IndicConformerRecognizer` → UI. Recognized utterances, as logged: "पारस", "हेलो", "हेलो कौन हो तोू", "वायु", "द वपस", "कैम शो मजे में". Each utterance was 1.1–1.9 s of audio and took 955–1,446 ms to recognize. The final result arrived 1.6 s after Stop was tapped, which is consistent with the flush-on-Stop path.
- **Accuracy was not assessed.** There is no reference transcript for the live speech.
- **Playback run:** in the same session, playing `known_hindi_clip.wav` from the laptop speaker produced no speech segment at all. Nothing reached the recognizer, most likely because the mic level was below the VAD threshold (not verified). No transcription of the known clip through the microphone has been obtained.

Known limitations:

- The UI shows only the latest utterance. Earlier ones in a session are visible only in logcat. (Superseded by the session transcript, below.)
- The model stays loaded (about 1 GB) for the lifetime of the ViewModel, even when idle.
- `EnergyZcrVoiceActivityDetector` thresholds are untuned (Decision 001). Quiet input may never be detected as speech, as the playback run showed.
- Model delivery is still development-only (`adb push`). The production mechanism is undecided.

### Application module `app` — session transcript (added 2026-09-26)

Implemented:

- **One state system.** `SpeechState.lastResult` was replaced by `transcript: List<TranscriptEntry>`, oldest first. A `TranscriptEntry` holds the existing `SpeechRecognitionResult` plus `audioDurationMs`, computed from the segment's sample count.
- **Appending.** Every successful recognition appends an entry. Nothing is overwritten. Recognition and capture errors leave earlier entries intact.
- **Session semantics.** The transcript lives as long as `SpeechViewModel` and is kept across Start/Stop cycles. `SpeechController.clearTranscript()`, behind a "Clear transcript" button, starts a new session. The button is enabled only when idle (not listening, nothing recognizing), so an in-flight result cannot land in the new session.
- **UI.** A numbered, scrollable list (`LazyColumn`) that scrolls to the newest entry. Each entry shows its text and "Recognition X ms, audio Y ms". The list is under the state line, Start/Stop, the loading line and the error line.
- **Logging.** `SpeechViewModel` logs each new entry once: `Transcript #n: audioDurationMs=… inferenceTimeMs=… text="…"`. `app/scripts/score_live_stt.py` reads this.
- **Experiment tooling.** Procedure: `live-stt-accuracy-experiment.md`. Fixed references: `app/scripts/live_stt_sentences_hi.tsv` (H01–H15). WER/CER scorer: `app/scripts/score_live_stt.py` (jiwer definitions; NFC plus whitespace normalization only). The scorer was checked against a hand-made log with known answers; that check is tooling verification, not device data.

Tested:

- Level 2: `SpeechControllerTest` 30/30 (debug and release), including one entry per utterance, second appends without overwriting, failure keeps earlier entries, transcript kept across Start/Stop, clear starts a new session, Stop → flush → entry, and duration from sample count. `speech-engine` 25/25.
- A mutation that overwrote instead of appending failed exactly the two retention tests.
- Level 4 instrumented on the SM-T225: `./gradlew :app:connectedDebugAndroidTest`, 2 passed and 1 skipped. The model-not-found test skipped itself as designed, because a previously pushed model was still present.

Device observations (SM-T225, FP32, 2026-09-26):

- `loadTimeMs=9856`.
- One Start/Stop attempt produced three appended entries (`Transcript #1`–`#3`: "है हवायु", "है हवाईयाँ", "मेरा नाम पारस है"; 1.28–1.94 s of audio each, inference 1,139–2,090 ms). No crash, no low-memory kills.
- The transcript was later cleared on the device. I did not observe which control was tapped.

**Accuracy: not measured.** The controlled experiment (H01–H15) was not completed. The device log contains only one attempt, so no WER/CER exists for the live microphone path.

**Second baseline attempt, 2026-09-26 14:27–14:34 IST: invalid, not scored.**

- **Setup:** SM-T225, Android 14, FP32 `indicconformer_hi.onnx` (sha256 `fd2a2d26…`, verified on the device). `connectedDebugAndroidTest` ran first: 2 passed, 1 skipped by design.
- **Why it's invalid:** the device log has 3 `Recording started` attempts, not 15, and 9 transcript entries whose numbering restarted twice (the transcript was cleared mid-run). No entry sequence corresponds to H01–H15, so no WER/CER was computed. The experiment procedure now includes a validity check for exactly this case.
- **Facts from the log:**
  - `loadTimeMs=11852`.
  - Entries were 0.72–2.18 s of audio, with inference 689–2,280 ms.
  - Five entries were exactly 720 ms of audio (inference 714–717 ms when recognized as "वाद"). "वाद" (720 ms) also appeared as a standalone entry in an earlier session. The cause is not established.
  - No crash, no low-memory-killer kill, no ANR.
  - adb lost the device once over USB after the run; the device did not reboot.

**Third baseline attempt, 2026-09-26 14:48–15:16 IST: invalid, not scored.**

- **How it was run:** Start/Stop were driven over adb, and the speaker only spoke on each prompt. Setup: SM-T225, FP32 `fd2a2d26…` verified on the device, fresh process, transcript 0 at start.
- **Why it's invalid:**
  - Only 2 of 15 attempts happened. H01 ran 14:48:41–14:51:18. H02 ran 14:51:28–15:14:38, 23 minutes of listening.
  - Samsung's MTP "Allow access to tablet data?" dialog (USB re-enumeration, 15:16) interrupted the run.
  - The transcript later showed 0 in the same process.
  - Neither reference sentence appears in the output.
- **Observation (not a conclusion): continuous false segmentation.** Nobody was speaking in several stretches, yet the unmodified EnergyZcr VAD (default thresholds) produced:
  - 5 entries in the first ~25 s, and 46 during the H01 attempt;
  - 248 entries overall, averaging one per 5–7 s;
  - texts overwhelmingly "वाद" (76 in the H02 attempt), "है" (71), "द" (18), empty (12), "ब है" (11). Many were 720 ms of audio.

  A room sound level above the VAD's energy threshold would explain this, but it is not verified: there is no noise measurement.
- **Facts from the log:** `loadTimeMs=10730`. No crash, no low-memory-killer kill, no ANR.
- **Raw log:** kept outside the repository (session scratchpad, `run-valid-attempt3.log`).

### False speech detections: diagnosis and fix (2026-09-26, SM-T225)

**Root cause of the 720 ms entries (from the code, confirmed on the device):**

- With speech-engine defaults, two consecutive frames above RMS 0.02 (40 ms) make `EnergyZcrVoiceActivityDetector` report `SPEECH_START`.
- If the next frame is below threshold, the VAD goes straight back to `SILENCE`. The `SPEECH_START` branch has no hangover.
- `SpeechSegmenter` has already opened a segment: 5 pre-roll frames plus the confirming frame. It then closes it after 600 ms of silence (30 frames). That is 36 frames = 720 ms of near-silence.
- The segment passes `minSegmentDurationMs` (250 ms) because that minimum counts pre-roll and trailing silence, not speech. The recognizer reads it as "वाद" or "है".

**Measured on the device** (VadDiag logging; silence, nobody speaking):

- Unchanged VAD, 61 s: 9 transcript entries, 11 `SPEECH_START`, 1 `SPEECH_END`.
- 6 of the 9 entries were exactly 720 ms "वाद".
- Background RMS: per-second median 0.0046 (−47 dBFS). The per-second maximum had a median of 0.0197, the same level as the 0.02 threshold.
- 63 of about 3,050 frames were above threshold, in runs of at most 5 frames (mostly 1–2).
- There is no consistent extra background noise while the model loads.

**Changes:**

- App-only settings in `SpeechTuning`; speech-engine defaults are unchanged:
  - `speechStartFrameCount` 2 → 8. That is 160 ms, above every silent run observed.
  - `preRollFrameCount` 5 → 10, so onsets are kept.
  - `minSpeechDurationMs` = 40.
- The energy threshold, ZCR range, hangover and silence timeout are unchanged.
- speech-engine additions, both additive with no default behavior change (for Tanmay to review):
  - `SegmenterConfig.minSpeechDurationMs` (default 0) discards segments with less than that much `SPEECH_START`/`SPEECH` audio.
  - Read-only `lastFrameEnergy`/`lastFrameZcr`/`lastFrameIsCandidate` on the VAD.
- App `VadDiagnostics`: per-second energy/ZCR percentiles, above-threshold counts and runs, and start/end events. Enable with `adb shell setprop log.tag.VadDiag DEBUG`. Off by default.

**Tests:**

- app 40/40: `SpeechControllerTest` 30 plus `SpeechTuningSegmentationTest` 10. The latter uses the real VAD and segmenter with measured levels. It reproduces the 720 ms segment under the defaults, and covers silence, observed transients, the 7/8/9-frame boundary, quiet speech, one and two utterances, segment end, and flush.
- speech-engine 29/29, including 4 new `minSpeechDurationMs` tests.
- Reverting the start count to 2 fails exactly the two false-trigger tests.

**Silence check on the device:**

- With the start-count and pre-roll change only (67 s): **1** entry, down from 9. It was an 8-frame transient that again reached only `SPEECH_START`, which `minSpeechDurationMs` was then added for. The run did **not** pass (the requirement is 0).
- With both changes: **not yet measured.** The tablet's USB connection failed (`usb 3-2: device not accepting address …, error -71`, repeated re-enumeration) before the check could run.
- **Update, same day 19:38–19:41 IST.** Both changes, installed APK verified identical to the build (`c55c111d…`), `VadDiag` on. In the requested silence window (Start to 60 s after the model was ready, 76 s including the 12.4 s load):
  - **0 transcript entries, 0 `SPEECH_START`**;
  - 98/3,800 frames above threshold, longest run 4;
  - background RMS median 0.0050, per-second peak median 0.0218.

  The run was **not clean procedurally**: USB re-enumerated mid-run (device 079 → 084, port 3-2), adb dropped, and Stop was tapped late (19:41:10). After the silence window there was sustained sound of unknown origin (runs of 16–27 frames), with one proper `SPEECH_START`→`SPEECH_END` and one 4.14 s entry ("ह"). Because its origin is unknown, it is not counted either way.
- **Speech-path smoke test, 20:28–20:49 IST.**
  - **Setup.** The USB link was re-plugged at 19:56:44 (still port 3-2). GNOME's gvfs MTP/gphoto2 volume monitors were stopped for the session, after an adb drop that happened with the USB device still attached. After that: 18/18 adb checks over 3 min, and 0 re-enumerations or `error -71` for the rest of the session. The APK was verified as `c55c111d…` (start 8, pre-roll 10, `minSpeechDurationMs` 40), with `VadDiag` on.
  - **Sentence under test:** "आज बारिश हो रही है", spoken three times (normal / louder / loud at about 5 cm), Start → speak → Stop each.
  - **The detector responds to real sustained sound.** Every accepted segment had a proper `SPEECH_START`→`SPEECH_END`, with sustained runs of 8–34 frames. Peak RMS was 0.038–0.166, against silence peaks of about 0.02 and silent runs of 4–5 frames or fewer. There were no 720 ms or start-only false segments.
  - **Recognition returned text**, e.g. "हाँ", "है", "हेलो", and the multi-word "तुझा नाव काया है" (1.58 s of audio, 1,324 ms inference).
  - **The test sentence never appeared in A, B or C.** B (reported "louder") had no sustained activity at all: longest run 5, peak 0.059. C (reported "loud at about 5 cm") peaked at RMS 0.038, no louder than other room speech, and one of its two utterances came after the speaker's confirmation. Whether the controlled utterances reached the tablet is **unresolved**.
  - **Other facts.** Model load 11,994 ms. PSS about 0.99 GB (463 MB of it in swap after about 20 min listening). No crash, low-memory kill or ANR.
  - The system is **not ready** for H01–H15: the controlled sentence has not been captured and the three-sentence check has not been done.
  - Raw log: `~/itantra-stt-validation/runs/speech-smoke.log`, outside the repository.
- **USB:** 72 re-enumerations and 38 `error -71` on port 3-2 in one hour. The same port and continuous device numbering after a requested cable/port change indicate the physical connection was not actually changed. Device validation is blocked on this.

**Speech capture: open issue.** Two sentences spoken while listening, with the unchanged VAD, including one the speaker reported as 10–15 cm from the tablet, never appeared as speech. Neither produced a speech-like window: peak RMS 0.057/0.075, at most 7–8 consecutive above-threshold frames. The earlier laptop-speaker playback also produced no segment. The cause is not established: capture level of `VOICE_RECOGNITION` on this device, mic placement, or the speech not being in the window. The speech sanity check has not been done. The system is **not ready** for the H01–H15 experiment.

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

The target device is a Samsung SM-T225 (Android 14, arm64-v8a). All device evidence so far is from the OnePlus CPH2613 (7.4 GB RAM, dot-product/i8mm-capable CPU). Read from the SM-T225 unit on 2026-09-26: 2.7 GB RAM (`MemTotal` 2,823,436 kB, about 0.96 GB available at the check), MediaTek MT8768WT with Cortex-A53 cores, and no `asimddp`/`i8mm` CPU features. The OnePlus timing and memory figures above therefore do not transfer to it. Full record: `android-stt-readiness.md`. In summary:

- **Desktop comparison, measured on 3 real Hindi clips (34.2 s, 63 words):**
  - FP32 ONNX output is identical to NeMo/PyTorch on 3/3 clips.
  - Both INT8 exports change 2 of 63 words, in words NeMo already got wrong. WER vs reference is 6.35% for every engine.
  - INT8 MatMul-only was the fastest on desktop (RTF 0.074 vs FP32 0.086), with the lowest peak RSS (573 MB vs 973 MB).
  - INT8 MatMul+Conv is 2.6× slower than FP32. Desktop x86 numbers only.
- **INT8 MatMul+Conv on Android: confirmed not loadable** on ORT Android 1.22.0 (`ORT_NOT_IMPLEMENTED` for `ConvInteger` with uint8 × int8 inputs, observed on device). Decision 006, `architecture.md` and the `IndicConformerRecognizer` KDoc are therefore accurate and unchanged. This is a runtime-build property, so it applies to the SM-T225 too.
- **INT8 MatMul-only on Android: loads and runs** (OnePlus). It was 1.78× faster than FP32 at 2.1× less PSS on synthetic input. Its real-audio transcription on-device has not been run yet.
- **Device status:** no inference has run on the SM-T225. On 2026-09-26 the instrumented-test APK built, installed and ran there. The known-WAV test failed only because the model file was absent (`Expected fixture not found …/indicconformer_hi.onnx`). The model exports exist only on Tanmay's validation machine, not on Paras's machine where the tablet is attached. See `android-stt-readiness.md`, section 6.

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
