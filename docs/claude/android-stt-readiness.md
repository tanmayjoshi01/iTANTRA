# Android STT Readiness — Hindi IndicConformer ONNX (target: Samsung SM-T225)

Record of the investigation into whether the existing ONNX-based Hindi STT pipeline can run on Android, targeting the Samsung SM-T225 (Android 14, arm64-v8a). Recorded 2026-09-25 on branch `Tanmay-iTarntra`. Every number below was measured in that session, or is cited with its source. Where something was not measured, this file says so.

## Validation level reached (see `testing-and-validation.md`)

| Level | Status |
|---|---|
| 1 — Static inspection | Done: ONNX graphs, `speech-engine` STT sources and instrumented tests, the shipped `onnxruntime-android` native library |
| 2 — JVM unit tests | 21/21 pass, re-run with `--rerun` on 2026-09-25T09:15Z (includes `MelSpectrogramFeatureExtractorTest`) |
| 3 — Android build | `assembleDebugAndroidTest` / `compileDebugAndroidTestKotlin` succeed with the changes below |
| Desktop ONNX comparison (outside the Level 1–6 scale) | Done: NeMo vs FP32 vs both INT8 variants, 3 real Hindi clips (section 2) |
| 4 — Device, **OnePlus CPH2613** | **Reached** for the known-WAV milestone (FP32) and for load/timing of all three variants (section 4). Results committed in `speech-engine/validation-results/device/20260925T091736Z/`. |
| 4 — Device, **Samsung SM-T225 (target)** | **Not reached.** On 2026-09-25 the SM-T225 was not attached. On 2026-09-26 it was attached, and the test harness built, installed and ran on it, but no model file was available to push (section 6). No inference has run on the SM-T225. |

## 1. Model interface (Step 1)

All three existing exports in `~/itantra-stt-validation/export/` share one interface, inspected with `onnx` 1.16.2 and `onnxruntime` 1.30.0:

| | Name | Shape | dtype |
|---|---|---|---|
| Input | `audio_signal` | `[batch, 80, frames]` | float32 |
| Input | `length` | `[batch]` (valid frame count) | int64 |
| Output | `logprobs` | `[batch, ceil(frames/4), 5633]` | float32 |

Measured frame counts: 1819 → 455, 937 → 235, 667 → 167. The encoder subsamples time by 4.

| Artifact | Bytes | MiB | Nodes | Quantized ops | sha256 (prefix) |
|---|---|---|---|---|---|
| `indicconformer_hi.onnx` (FP32) | 493,060,285 | 470.2 | 4,222 | none (Conv 54, MatMul 205) | `fd2a2d26d6af` |
| `indicconformer_hi_int8.onnx` (MatMul+Conv dynamic INT8) | 140,451,331 | 133.9 | 5,112 | ConvInteger 54 (uint8 activations × **int8** weights), MatMulInteger 154, DynamicQuantizeLinear 158 | `7a7b20e37abf` |
| `indicconformer_hi_int8_matmul.onnx` (MatMul-only dynamic INT8) | 197,021,597 | 187.9 | 4,788 | MatMulInteger 154, DynamicQuantizeLinear 104; Conv 54 left FP32 | `5ace85a89f78` |

All three: ONNX IR 8, opset `ai.onnx:16`.

**Preprocessing is not in the graph.** The model expects precomputed, normalized log-mel features, not PCM. The required front end is the one already implemented and JVM-tested in `MelSpectrogramFeatureExtractor`, derived from the checkpoint's live preprocessor (`~/itantra-stt-validation/full_preprocessor_cfg.log`):

- 16 kHz mono input. `AudioConfig` defaults already match; the recognizer rejects other rates.
- Pre-emphasis 0.97.
- STFT: n_fft 512, window 400 (symmetric Hann), hop 160, centered with reflect padding. The window is zero-padded symmetrically to 512.
- Power spectrum, then the exact 80×257 Slaney mel matrix from the checkpoint (bundled `mel_filterbank_80x257.f32`).
- `ln(x + 2^-24)`.
- Per-feature normalization using Bessel-corrected std, with `+1e-5` added to the std.
- No dither (the checkpoint is in eval mode).
- `frames = 1 + samples/160`.

**Tokens and decoding:**

- `tokens.txt` has 5,633 entries: 5,632 SentencePiece pieces from AI4Bharat's 22-language aggregate tokenizer, plus blank at index 5632.
- Decoding is greedy CTC: per-frame argmax, collapse repeats, drop blank, join pieces, map `▁` to a space, trim. This is `CtcGreedyDecoder`.
- No language-id input or masking is needed. On a real Hindi clip, 108/108 non-blank frames fell in Hindi's `[1536, 1792)` range (`~/itantra-stt-validation/test_onnx_real_audio_output.log`, 2026-09-13).

## 2. Desktop comparison (Step 2)

**Method** (`speech-engine/scripts/desktop/compare_onnx_variants.py`, full output in `speech-engine/validation-results/desktop/2026-09-25-compare_onnx_variants_results.json`):

- **Clips:** the three real Hindi WAVs in `~/itantra-stt-validation/audio/`: 16 kHz, 34.20 s total, 63 reference words.
- **Features:** computed once per clip with NeMo's own `model.preprocessor` and shared by all ONNX variants.
- **Timing:** 1 warm-up plus 3 measured runs per engine and clip. ONNX uses `CPUExecutionProvider` with default session options.
- **Isolation:** each engine ran in its own process, so the peak RSS figures are per engine.
- **Host:** x86_64, 4 cores, 7.6 GB RAM, onnxruntime 1.30.0.

**Timing scope:**

- NeMo figures are end-to-end `transcribe()` (file → text).
- ONNX figures are `sess.run` only. NeMo preprocessing measured 0.005–0.010 s per clip.

| Engine | File size | Session load | Peak RSS | RTF (aggregate) | WER vs ref | CER vs ref | Transcript identical to NeMo |
|---|---|---|---|---|---|---|---|
| NeMo PyTorch, CTC decoder | 499 MiB `.nemo` | 90.2 s | 1,531 MB | 0.103 | 6.35% | 2.58% | — |
| ONNX FP32 | 470.2 MiB | 8.0 s | 973 MB | 0.086 | 6.35% | 2.58% | **3/3** |
| ONNX INT8 MatMul+Conv | 133.9 MiB | 3.7 s | 666 MB | 0.227 | 6.35% | 2.01% | 1/3 |
| ONNX INT8 MatMul-only | 187.9 MiB | 4.6 s | 573 MB | 0.074 | 6.35% | 2.58% | 1/3 |

**Differences, exactly:**

- **FP32 ONNX vs NeMo:** identical output on all 3 clips. This is measured parity for these clips under greedy CTC. It is not a general equivalence claim.
- **Both INT8 variants vs NeMo:** each changes one word on each of two clips (2 of 63 words). In every case the word was already wrong in NeMo's output:

  | Clip | Reference | NeMo / FP32 | INT8 MatMul+Conv | INT8 MatMul-only |
  |---|---|---|---|---|
  | 9.36 s | हाईकिंग | हाइकम | हाइकिम | हाइकिन |
  | 6.66 s | अरस्तू | अतूस्त | अस्तूत | अस्तूस्त |
  | 18.18 s | — | exact | exact | exact |

- WER against the reference is unchanged at 6.35% for every engine.
- **Scale caveat:** 63 words is a smoke test, not a WER benchmark.

## 3. On-device results (Step 5) — OnePlus CPH2613, not the target

**Setup:**

- **Device:** OnePlus CPH2613, Android 16 (SDK 36), Qualcomm platform `crow`, 7.4 GB RAM, CPU features including `asimddp`, `i8mm`, `bf16`. 1.99 GB `MemAvailable` at the start.
- **Run:** 2026-09-25T09:17Z via `speech-engine/scripts/run_stt_device_validation.sh`.
- **Runtime:** ONNX Runtime Android 1.22.0 (the module's dependency).

| Test | Result | Measured |
|---|---|---|
| **Milestone (FP32):** bundled 18.18 s PCM16 WAV → `IndicConformerRecognizer` (Kotlin features → ORT → CTC) → text | **PASS**, exact match with reference | load 4,829 ms; recognize 6,029 ms (RTF 0.33, includes Kotlin feature extraction and decode); PSS 1,016,700 KB after load, 1,143,724 KB after inference |
| FP32, NeMo-precomputed features for the same clip | **PASS**, exact match | `session.run` 4,763 ms for 455 output frames (RTF 0.26) |
| FP32, synthetic (1, 80, 300) input | **PASS** | load 3,748 ms; mean 569 ms over 5 runs (503–646); PSS ≈ 1.01 GB |
| FP32, same clip recognized 3× | **PASS** | identical text all 3 times |
| INT8 MatMul+Conv, synthetic | **FAIL** at session creation | `ORT_NOT_IMPLEMENTED: Could not find an implementation for ConvInteger(10) node with name '/pre_encode/conv/conv.0/Conv_quant'` |
| INT8 MatMul-only, synthetic (1, 80, 300) | **PASS** | load 1,912 ms; mean 319 ms over 5 runs (281–355); PSS 471,291 KB after load, 381,159 KB after inference |

**Observations:**

- **Speed:** on this device INT8 MatMul-only was 1.78× faster than FP32 and used 2.1× less PSS on the same input. The desktop x86 speed gain was only 1.16×.
- **Memory pressure:** during the FP32 milestone test, Android's low-memory killer terminated 4 cached background apps. The test process itself was not killed. That was on a 7.4 GB phone.
- **Front-end cost:** Kotlin feature extraction plus decoding is roughly 1.3 s of the 6.0 s recognize time for the 18.18 s clip. This compares two separate test processes, so it is approximate.

**Resolved: the INT8 MatMul+Conv export does not load on ONNX Runtime Android 1.22.0.**

- The existing claim in `decisions.md` (Decision 006), `architecture.md` and the `IndicConformerRecognizer` KDoc is confirmed.
- The cause is at kernel level: the model's `ConvInteger` nodes take uint8 activations with int8 weights, and ORT Android 1.22.0 has no kernel for that combination. Desktop ORT 1.30.0 runs the same file.
- Because this is a property of the ORT build, not the phone, it applies to the SM-T225 as well.
- A `ConvInteger` symbol is present in the `.so`, but that did not mean a kernel existed for this node's input types. An earlier static reading of that symbol in this investigation was wrong for exactly this reason.
- Re-quantizing Conv with uint8 weights, or a newer ORT Android, might load. Neither was tested; both would mean producing a new model or changing the dependency.

**Not yet run on any device:** INT8 MatMul-only on real audio (the milestone path). The test now accepts `-e modelFile`, and the script includes those runs, but the device was disconnected before a second run.

## 4. Trade-offs for Android (Step 3)

No variant is selected here. The measured options:

| | FP32 | INT8 MatMul+Conv | INT8 MatMul-only |
|---|---|---|---|
| Storage | 470 MiB | 134 MiB | 188 MiB |
| Loads on ORT Android 1.22.0 | Yes | **No** (`ORT_NOT_IMPLEMENTED`) | Yes |
| Device PSS after load (OnePlus) | ~1.01 GB (1.14 GB after a real 18 s inference) | — | 0.47 GB |
| Device time, (1,80,300) input (OnePlus) | 569 ms | — | 319 ms |
| Device real-audio transcription | Exact match, 18.18 s clip | — | **Not yet run** |
| Desktop text vs NeMo (3 clips, 63 words) | identical | 2 words differ | 2 words differ (already-wrong words) |
| Desktop RTF / peak RSS | 0.086 / 973 MB | 0.227 / 666 MB | 0.074 / 573 MB |

**Why none of the OnePlus figures stand in for the SM-T225:**

| | OnePlus CPH2613 | SM-T225 (read from the unit on 2026-09-26: `speech-engine/validation-results/device/20260926T034633Z/device-info.txt`) |
|---|---|---|
| RAM | 7.4 GB (`MemTotal` 7,411,588 kB) | **2.7 GB** (`MemTotal` 2,823,436 kB); `MemAvailable` 958,976 kB at the check; 3.0 GB swap |
| SoC / cores | Snapdragon `crow` | MediaTek `mt6765` platform, `Hardware: MT8768WT`; `CPU part 0xd03` (Cortex-A53) |
| ISA / CPU features | reports `asimddp`, `i8mm`, `bf16` | `fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid`: no `asimddp`, no `i8mm`, no `bf16` |
| Android | 16 (SDK 36) | 14 (SDK 34) |

What that implies, none of it measured yet:

- **Memory:** FP32's 1.0–1.14 GB PSS on the OnePlus is more than the SM-T225's measured `MemAvailable` (about 0.94–0.96 GB across two checks on 2026-09-26). The OnePlus already had low-memory kills at that footprint. INT8 MatMul-only (0.47 GB PSS on the OnePlus) is the variant more likely to fit, but that is not measured on the SM-T225.
- **Speed:** RTF there is unmeasured.
- **INT8 benefit:** INT8's 1.78× gain on the OnePlus may be smaller on a core without dot-product instructions.

## 5. Android / speech-engine boundary (Step 4)

**Already in place:**

- `onnxruntime-android:1.22.0` as a production dependency.
- `IndicConformerRecognizer(modelFile, tokensFile)`, which chains `MelSpectrogramFeatureExtractor` → ORT → `CtcGreedyDecoder`.
- The milestone instrumented test, now passing on a real device (OnePlus).
- The recognizer does not enforce FP32. It loads whatever file it is given, so switching to INT8 MatMul-only needs no production code change.

**Still required, in order:**

1. **Run the script on the SM-T225.** It records the unit's real RAM and CPU features and covers FP32 plus INT8 MatMul-only, both synthetic and real audio.
2. **Choose FP32 or INT8 MatMul-only from those SM-T225 numbers.** Memory is the likely deciding factor on a 3–4 GB device.
3. **Model provisioning for a real build.**
   - Tests `adb push` to `/sdcard/Android/data/com.itantra.speechengine.test/files/`.
   - An app needs a documented mechanism. This is an open decision (`architecture.md`, `models` row).
4. **App-side responsibilities** (Paras's module, not changed here):
   - Call `recognize()` off the main thread; it is synchronous and CPU-bound.
   - Call `release()` on lifecycle end.
   - Package only `arm64-v8a`. The test APK carries all four ABIs of `libonnxruntime.so` (x86_64 20.9 MB, x86 20.5 MB, arm64-v8a 17.4 MB, armeabi-v7a 12.6 MB), for a 78.6 MB APK.
5. **Minor:** `src/main/resources/.../stt/tokens.txt` is bundled (byte-identical to `export/tokens.txt`), but no production code reads it. The recognizer still requires a caller-supplied tokens `File`. No change made.

## 6. SM-T225 session, 2026-09-26 (branch `Tanmay-iTarntra`, run on Paras's machine)

What ran, and what it showed:

- `./gradlew :speech-engine:testDebugUnitTest --rerun`: 21/21 JVM tests pass (Level 2, desktop).
- `speech-engine/scripts/run_stt_device_validation.sh`, unchanged:
  - It recorded the device facts in section 4 and built and installed the instrumented-test APK on the SM-T225 (`installDebugAndroidTest`, BUILD SUCCESSFUL).
  - It then stopped with `ERROR: missing local file /home/paras/itantra-stt-validation/export/indicconformer_hi.onnx`.
- `am instrument … IndicConformerRecognizerInstrumentedTest#knownPcmClip_recognizesCorrectHindiText` on the SM-T225: **FAIL**, `AssertionError: Expected fixture not found at /storage/emulated/0/Android/data/com.itantra.speechengine.test/files/indicconformer_hi.onnx`.
  - This confirms that only the model file is missing: the test APK, bundled WAV and reference run on the device.
  - It is not an inference result.

**Blocker:** Paras's machine has no `~/itantra-stt-validation/export/` directory and no `.onnx` or `.nemo` file anywhere on its filesystem. The exports exist only on Tanmay's validation machine.

**To unblock:** copy the export directory from Tanmay's machine to `~/itantra-stt-validation/export/` on Paras's machine, outside Git. Check the files against the sha256 values in `validation-results/device/20260925T091736Z/pushed-files.txt`. The script pushes every file in its list regardless of `TEST_FILTER`, so the copy must include all of these:

- `indicconformer_hi.onnx`
- `tokens.txt`
- `android_fixtures/real_hindi_features_1x80x1819.f32`
- `android_fixtures/reference.txt`
- unless `SKIP_INT8=1` is set, also `indicconformer_hi_int8.onnx` and `indicconformer_hi_int8_matmul.onnx`

Then rerun the script unchanged.

## Changes made in this investigation

- **`IndicConformerOnnxDeviceValidationTest`:** added `int8Matmul_loadsAndRunsSyntheticTensorOnDevice`.
- **`IndicConformerRecognizerInstrumentedTest`:** optional `-e modelFile <name>` instrumentation argument. The default is unchanged (FP32).
- **`speech-engine/scripts/run_stt_device_validation.sh`:**
  - Requires exactly one arm64-v8a device and records its hardware facts.
  - Uses `installDebugAndroidTest`, not `connectedAndroidTest`, which uninstalls afterwards and would delete the pushed models.
  - Pushes models (skipping identical-size copies) and records their sha256.
  - Runs each test method in its own `am instrument`, so an OOM or native crash in one model does not hide the others.
  - Writes to `speech-engine/validation-results/device/<UTC stamp>/`.
  - Only the test process's logcat lines are kept for commit; full-device logcat is gitignored because it contains other apps on a personal phone.
- **`speech-engine/scripts/desktop/compare_onnx_variants.py`** plus its committed JSON output.

## Reproduction

```bash
# Desktop comparison (needs ~/itantra-stt-validation and its venv; ~1.6 GB peak RAM per stage)
cd ~/itantra-stt-validation && source venv/bin/activate
S=/path/to/iTANTRA/speech-engine/scripts/desktop/compare_onnx_variants.py
python -u $S nemo && for v in fp32 int8_full int8_matmul; do python -u $S onnx $v; done && python -u $S report

# Build checks and JVM tests
./gradlew :speech-engine:assembleDebugAndroidTest
./gradlew :speech-engine:testDebugUnitTest --rerun

# Device run (one device attached, USB debugging on; pushes ~790 MB on first run)
speech-engine/scripts/run_stt_device_validation.sh
SKIP_INT8=1 speech-engine/scripts/run_stt_device_validation.sh      # FP32 only
TEST_FILTER=int8_matmul speech-engine/scripts/run_stt_device_validation.sh   # only the INT8 MatMul-only milestone runs
```
