# iTANTRA Ownership Map — Tanmay / Paras

This document defines who owns which part of the system. Neither developer should unnecessarily modify the other developer's ownership area, and Claude Code must respect this boundary the same way it would respect a human code-owner boundary: modifying another owner's area requires the change to actually be in scope for the current task and, when in doubt, explicit instruction.

## Tanmay

- `speech-engine` (the module as a whole)
- Audio capture
- Audio processing
- Voice activity detection (VAD)
- Speech segmentation
- Speech-to-text (STT), once Stage 2 begins
- Text-to-speech (TTS), once Stage 3 begins
- Speech models (selection, integration, licensing records)
- Model management
- ML benchmarking
- Speech latency measurement
- Speech CPU/RAM analysis
- Speech-side optimization

## Paras

- Android application module (`app` — does not exist yet, see below)
- UI (Jetpack Compose)
- Communication layer
- Message protocol
- Wi-Fi Direct
- Bluetooth
- Push-to-talk (PTT)
- Continuous communication mode (interaction/UI side; segmentation itself is Tanmay's, in `speech-engine`)
- Constrained-link simulator
- Networking reliability

## Shared

- Integration (wiring `speech-engine` into `app` and transport)
- Shared domain contracts (interfaces/data shapes that cross the Tanmay/Paras boundary, e.g. the eventual STT-output-to-message-protocol shape)
- End-to-end testing (Level 5 in `testing-and-validation.md`)
- System-level benchmarking (Level 6 in `testing-and-validation.md`)
- Final documentation
- Demo preparation

## Root Gradle infrastructure

`settings.gradle.kts` and root `build.gradle.kts` are shared infrastructure, not exclusively Tanmay's even though Tanmay created them as part of Stage 0/1 setup. They declare plugin versions (AGP, Kotlin) once so every module — `speech-engine` today, and Paras's future `app` module — stays on consistent versions.

## Current structural situation (verified against the repository)

- `speech-engine` exists and is implemented for Stage 1 (audio capture, VAD, segmentation) and, additionally, Stage 2 for Hindi only (offline STT via `IndicConformerRecognizer`, ONNX Runtime Android — see `current-state.md`, `architecture.md`). This is Tanmay's module; the STT work falls under the same ownership entry above ("Speech-to-text (STT), once Stage 2 begins" — Stage 2 has, in fact, begun).
- `app` (the Android application module) **does not exist yet**. `settings.gradle.kts` contains only a comment reserving its future inclusion (`include(":app")`).
- Paras will create the `app` module later.

## Rule for Paras's future work

When the `app` module is created, it must **extend** the existing root Gradle configuration (the shared `plugins { ... }` block in root `build.gradle.kts` and the `include(...)` list in `settings.gradle.kts`) rather than replacing or restructuring what Tanmay has already set up. If the existing root configuration is genuinely insufficient for the application module's needs, that is a decision to raise explicitly (see `decisions.md`), not to resolve by silently rewriting shared build files.

## Rule for Claude Code

- Before modifying any file under `speech-engine/`, no ownership check beyond the normal "inspect before modifying" rule is needed — this is Tanmay's area and the active branch (`Tanmay-iTarntra`) is Tanmay's.
- Before modifying anything that would fall under Paras's list above (once it exists), stop and confirm this is actually intended, since such a change crosses an ownership boundary the two developers have agreed to.
- Root Gradle files may be extended (e.g. adding a new module include, adding a shared plugin version) but should not be restructured or have existing entries removed without explicit instruction, since both developers depend on them.
