# iTANTRA Engineering Standards

## Architecture

- Prefer simple designs over unnecessarily complex ones. A component should do one thing, with a boundary that is easy to state in one sentence (see `architecture.md` for examples already followed: `AudioRecorder` only captures, `VoiceActivityDetector` only classifies, `SpeechSegmenter` only groups frames into utterances).
- Maintain modular boundaries. Cross-boundary knowledge (e.g. `speech-engine` knowing about transport) is not acceptable — see `architecture.md`, decoupling principle.
- Separate concerns: capture, classification, segmentation, (future) recognition, (future) transport, and (future) synthesis are distinct responsibilities and should stay in distinct components.
- Favor testability: keep logic that does not need Android/hardware access free of Android framework dependencies, as already done for everything in `speech-engine` except `AudioRecorder`.
- Avoid premature abstraction. Do not introduce an interface, plugin system, or configuration layer for a variation that does not exist yet. `VoiceActivityDetector` is an interface today because a second implementation (e.g. a neural VAD) is a realistic near-term possibility already noted in `decisions.md`; do not apply the same reasoning speculatively elsewhere without a concrete reason.
- Avoid premature optimization. Do not restructure code for performance without a measurement showing the current approach is a problem (see Performance, below).

## Offline-first

- Runtime speech processing (VAD, segmentation, STT, TTS) must function with no internet access.
- No cloud STT or TTS API may be used in the runtime speech pipeline, ever, for any language.
- The speech pipeline must not have an internet dependency of any kind at runtime. (Initial app/model provisioning may require one-time internet access — see README, Offline-First Design — but this is distinct from runtime operation.)

## Open-source

- Use open-source technologies for the speech pipeline and, where practical, elsewhere.
- Verify the license of any third-party library or model before adoption.
- Document the license of every adopted dependency and model.
- Distinguish a model's own license from the license of the runtime/inference engine used to run it — they are separate and both must be recorded (see `instructions.md`, Model rules).

## Android

- Target low- and mid-range Android devices as the baseline, not high-end flagship hardware.
- Minimize CPU usage, especially for anything that could run continuously (e.g. continuous-mode listening in Stage 7).
- Minimize RAM usage.
- Minimize storage footprint, particularly for model files.
- Avoid unnecessary background work.
- Avoid main-thread blocking; I/O and processing that can block must run off the main thread, as `AudioRecorder` already does via a background coroutine dispatcher.

## Performance

Do not optimize based on assumptions. Every performance claim or optimization must be backed by an actual measurement of:

- Latency (per-stage and end-to-end)
- CPU usage
- RAM usage
- Model size
- APK / application size
- Audio processing time
- STT latency
- TTS latency
- End-to-end latency
- Real-time factor (RTF)
- Network payload size
- Effective bitrate

See `testing-and-validation.md` for how and when these are measured (Level 6).

## Code quality

- Use meaningful, descriptive names for classes, functions, and variables.
- Keep components small and focused on a single responsibility.
- Put tunable values in configuration classes (e.g. `AudioConfig`, `VadConfig`, `SegmenterConfig`) rather than scattering magic numbers through implementation code — this pattern is already established in `speech-engine` and should be continued.
- Handle errors explicitly rather than silently swallowing them; `AudioCaptureException`'s sealed hierarchy is the established pattern for reporting failure without crashing.
- Clean up resources deterministically (see `AudioRecorder.release()` for the established pattern: stop capture, then cancel the coroutine scope).
- Use bounded buffers for anything that accumulates data over time (see `SpeechSegmenter`'s bounded pre-roll buffer and `maxSegmentDurationMs` cap).
- Prefer deterministic tests where possible; the existing JVM test suite (`speech-engine/src/test`) uses synthetic, deterministic PCM signals rather than real recordings for exactly this reason.

## Documentation

- No emojis.
- No fake badges (build-status badges, coverage badges, etc. that do not reflect an actual, currently running CI/measurement system).
- No marketing language ("cutting-edge," "revolutionary," "seamless," etc.).
- No unsupported claims — every factual statement about status, performance, or capability must be verifiable against the actual repository state or an actual measurement.
- No fabricated benchmark numbers, ever, under any circumstance, including as a placeholder — use "To be benchmarked," "Under Evaluation," or "Pending Validation" instead.
