#!/usr/bin/env python3
"""Score a controlled live-STT run of the iTANTRA app against known Hindi references.

Usage:
    adb logcat -d -v time -s SpeechViewModel AudioRecorder > run.log
    python3 app/scripts/score_live_stt.py app/scripts/live_stt_sentences_hi.tsv run.log

Protocol assumed (see docs/claude/live-stt-accuracy-experiment.md): the transcript
is cleared first, then each reference sentence gets exactly one Start -> speak ->
Stop attempt, in file order, and the next Start is pressed only after the
previous result has appeared. Each attempt is delimited in the log by
AudioRecorder's "Recording started" line; the SpeechViewModel "Transcript #n"
lines that follow it (before the next "Recording started") are that attempt's
recognized utterances.

Per attempt:
  MISSED   no transcript entry: the VAD never classified the speech as speech,
           so nothing reached the recognizer.
  DETECTED one or more entries. Several entries (the VAD split the sentence at a
           pause) are joined with a space into one hypothesis and flagged "split".

Metrics (same definitions as jiwer, used by speech-engine/scripts/desktop/):
  WER = word-level Levenshtein edits (S+D+I) / reference word count
  CER = character-level edits / reference character count (spaces included)
Reported corpus-level (total edits / total reference units), twice:
  all sentences     MISSED sentences count as all-deletion (hypothesis "")
  detected only     recognition quality where speech reached the recognizer

Normalization, applied identically to reference and hypothesis, and nothing else:
  1. Unicode NFC (so the same grapheme typed with different code point
     sequences compares equal)
  2. Strip, and collapse runs of whitespace to one space
No punctuation, nukta, anusvara/chandrabindu or spelling-variant folding is
done: an orthographic difference counts as an error.

Standard library only.
"""
import re
import sys
import unicodedata

START_RE = re.compile(r"Recording started")
ENTRY_RE = re.compile(r'Transcript #(\d+): audioDurationMs=(\d+) inferenceTimeMs=(\d+) text="(.*)"\s*$')
LOAD_RE = re.compile(r"Model loaded: .*loadTimeMs=(\d+)")


def normalize(text):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", text)).strip()


def edit_distance(ref, hyp):
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i]
        for j, h in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h)))
        prev = cur
    return prev[-1]


def words(text):
    return text.split(" ") if text else []


def read_references(path):
    refs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                sid, sentence = line.rstrip("\n").split("\t", 1)
                refs.append((sid, sentence))
    return refs


def read_attempts(path):
    attempts, load_times = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if m := LOAD_RE.search(line):
                load_times.append(int(m.group(1)))
            elif START_RE.search(line):
                attempts.append([])
            elif (m := ENTRY_RE.search(line)) and attempts:
                attempts[-1].append(
                    {"audio_ms": int(m.group(2)), "inference_ms": int(m.group(3)), "text": m.group(4)}
                )
    return attempts, load_times


def main(ref_path, log_path):
    refs = read_references(ref_path)
    attempts, load_times = read_attempts(log_path)
    if len(attempts) != len(refs):
        print(f"WARNING: {len(attempts)} Start attempts in log, {len(refs)} reference sentences; "
              f"sentences beyond the attempts are reported NOT ATTEMPTED.\n")

    totals = {"all": [0, 0, 0, 0], "detected": [0, 0, 0, 0]}  # word edits, words, char edits, chars
    print("| ID | Reference | Recognized | VAD | Audio ms | Inference ms | RTF | Word edits / words |")
    print("|---|---|---|---|---|---|---|---|")
    for k, (sid, ref_raw) in enumerate(refs):
        ref = normalize(ref_raw)
        if k >= len(attempts):
            print(f"| {sid} | {ref} | — | NOT ATTEMPTED | | | | |")
            continue
        entries = attempts[k]
        hyp = normalize(" ".join(e["text"] for e in entries))
        status = "MISSED" if not entries else ("DETECTED (split x%d)" % len(entries) if len(entries) > 1 else "DETECTED")
        audio = sum(e["audio_ms"] for e in entries)
        infer = sum(e["inference_ms"] for e in entries)
        rtf = f"{infer / audio:.2f}" if audio else ""
        we, wn = edit_distance(words(ref), words(hyp)), len(words(ref))
        ce, cn = edit_distance(list(ref), list(hyp)), len(ref)
        keys = ["all"] + (["detected"] if entries else [])
        for key in keys:
            t = totals[key]
            t[0] += we; t[1] += wn; t[2] += ce; t[3] += cn
        print(f"| {sid} | {ref} | {hyp or '(none)'} | {status} | {audio or ''} | {infer or ''} | {rtf} | {we}/{wn} |")

    detected = sum(1 for a in attempts[: len(refs)] if a)
    print()
    print(f"Sentences: {len(refs)}; attempted: {min(len(attempts), len(refs))}; "
          f"DETECTED: {detected}; MISSED: {min(len(attempts), len(refs)) - detected}")
    for key, label in (("all", "All attempted sentences"), ("detected", "DETECTED sentences only")):
        we, wn, ce, cn = totals[key]
        if wn:
            print(f"{label}: WER {we / wn:.2%} ({we}/{wn} words), CER {ce / cn:.2%} ({ce}/{cn} chars)")
    if load_times:
        print(f"Model load time(s) in this log (ms): {load_times}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
