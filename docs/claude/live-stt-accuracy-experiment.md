# Controlled Live-STT Accuracy Experiment (iTANTRA app, Hindi)

This is the procedure for measuring how the iTANTRA app itself turns live Hindi speech into text on a device. The path is: microphone → `AudioRecorder` → `SpeechSegmenter` (EnergyZcr VAD) → `IndicConformerRecognizer`, then the session transcript.

The measurement is made against sentences fixed **before** anyone speaks. Results are recorded in `current-state.md` only after a run has actually happened.

## What it measures

For each reference sentence:

- **VAD outcome.**
  - **DETECTED:** at least one transcript entry was produced.
  - **MISSED:** no entry; the speech never reached the recognizer.

  These are different problems. A MISSED sentence is a VAD/segmentation problem. A DETECTED sentence with errors is a recognition problem.
- **Recognized text.** If the VAD split the sentence at a pause, the entries are joined and flagged.
- **Audio duration.** Computed from the sample count, so it is exact.
- **Inference time.** The `recognize()` call: features, ONNX and decoding.
- **RTF** = inference time / audio duration.

Per run:

- **Model load time.** Loaded once, at the first Start. It is not reloaded per sentence.
- **WER and CER, corpus-level.** Reported twice:
  - over all sentences, where a MISSED sentence counts as all deletions;
  - over DETECTED sentences only.

## Metric

The definitions match `jiwer`, which the desktop comparison uses (`speech-engine/scripts/desktop/compare_onnx_variants.py`):

- **WER** = (S + D + I) / N over whitespace-separated words.
- **CER** = the same over characters, with spaces included.

They are computed by `app/scripts/score_live_stt.py` (standard library only).

**Normalization** is applied identically to the reference and the hypothesis, and nothing else is done:

1. Unicode NFC.
2. Strip, and collapse whitespace.

Nukta, anusvara/chandrabindu and other spelling variants are **not** folded. An orthographic difference counts as an error.

## Reference set

The reference set is `app/scripts/live_stt_sentences_hi.tsv`: 15 short sentences, H01–H15, no punctuation and no digits.

- Most are everyday phrases; several are relevant to the disaster-response use case (H06, H08, H09, H10, H13).
- Words with common spelling variants (nukta, chandrabindu) were avoided where possible.
- Edit the file **before** a run if different sentences are wanted. Never edit it after seeing the output.

## Procedure

**Setup** (FP32 `indicconformer_hi.onnx` and `tokens.txt` in `/sdcard/Android/data/com.itantra.app/files/`, microphone permission granted):

1. Run `./gradlew :app:connectedDebugAndroidTest` **before** pushing the model. It uninstalls the app, which deletes the pushed files.
2. Then run `./gradlew :app:installDebug` and push the model files.

**Preconditions** (added 2026-09-26, after run 3 failed on both):

- **USB mode.** Set the tablet's USB mode to charging only / "No data transfer", not MTP ("File transfer"). A USB re-enumeration in MTP mode shows Samsung's "Allow access to tablet data?" dialog over the app, which stops capture mid-attempt.
- **Silence check.** With the app listening and nobody speaking, 30 s must produce **no** transcript entries. If it produces entries, background sound is passing the VAD, and each such entry will be scored as an insertion. Fix the environment first, or record explicitly that the baseline includes that noise.

- **Diagnostics.** To see why a segment started, enable `adb shell setprop log.tag.VadDiag DEBUG` before Start. It logs per-second energy/ZCR statistics and VAD start/end events under tag `VadDiag`. Disable with `adb shell setprop log.tag.VadDiag ""`.

**Run:**

1. Launch the app. Note the speaker, the room noise, and the device's distance from the mouth.
2. Clear the log: `adb logcat -c`. If the transcript is not empty, tap **Clear transcript**.
3. For each sentence in file order:
   1. Tap **Start**. On the first sentence, wait for "Loading speech model…" to disappear.
   2. Speak the sentence once, at normal volume.
   3. Tap **Stop**.
   4. Wait until the state is **Idle**, meaning the entry has appeared or nothing is pending.

   If a sentence produces no entry, **do not repeat it**: it is recorded as MISSED. Exactly one attempt per sentence.
4. Save the log:

   ```
   adb logcat -d -v time -s SpeechViewModel AudioRecorder > run.log
   ```
5. **Check the run is valid before scoring.** A run is valid only if **both** of these hold:
   - the log has exactly 15 `Recording started` lines (one per sentence);
   - the `Transcript #n` numbering never restarts at `#1` after the first attempt. A restart means the transcript was cleared mid-run.

   If either fails, discard the whole run and repeat it from step 1 of Run. Never score a partial or out-of-protocol run against H01–H15: the order-based mapping would pair hypotheses with the wrong sentences.

   Added 2026-09-26, after two runs failed this check: one had 1 attempt, the other had 3 attempts and two transcript clears.

   ```
   grep -c 'Recording started' run.log   # must be 15
   grep -o 'Transcript #[0-9]*' run.log  # numbering must increase without restarting
   ```
6. Score it:

   ```
   python3 app/scripts/score_live_stt.py app/scripts/live_stt_sentences_hi.tsv run.log
   ```

**How the log is read.** The scorer maps attempts to sentences by order. Each `AudioRecorder: Recording started` line starts a new attempt, and the `SpeechViewModel: Transcript #n` lines after it belong to that attempt. That is why each Start must wait for the previous result.

## Limits of the result

- It covers one speaker, one device, one room and 15 sentences. It is a baseline, not a benchmark.
- It shows nothing about other speakers, noise conditions or languages.
