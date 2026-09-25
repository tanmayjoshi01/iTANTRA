"""
Android STT readiness investigation - Step 2: controlled desktop comparison
of PyTorch/NeMo vs FP32 ONNX vs INT8 ONNX (both existing quantization
variants) on the same three real Hindi WAV fixtures used for the Gate 1
NeMo baseline (audio/manifest.json).

This is the version-controlled copy. It reads the external, non-versioned
validation workspace ~/itantra-stt-validation/ (NeMo checkpoint, audio
fixtures, ONNX exports - hundreds of MB, deliberately not in this
repository) and must be run with that workspace's venv. Environment used
for the committed results (2026-09-25): Python 3.12, AI4Bharat NeMo fork
(editable install at ~/itantra-stt-validation/ai4bharat-nemo), torch
2.14.0+cpu, onnxruntime 1.30.0, onnx 1.16.2, numpy 1.26.4, jiwer 4.0.0,
soundfile 0.14.0. Committed output:
speech-engine/validation-results/desktop/2026-09-25-compare_onnx_variants_results.json

Split into stages run as SEPARATE processes, because this machine has
7.6 GB RAM, no swap, and ~2-3 GB free with a normal desktop session open;
holding the NeMo model and all three ONNX sessions in one process is not
safe (an earlier single-process version of this script never completed).

  stage A:  python -u compare_onnx_variants.py nemo
      Loads the NeMo checkpoint once. For each clip: computes real mel
      features with model.preprocessor (the model's own feature extractor,
      same method as test_onnx_real_audio.py) and saves them as .npy under
      compare_work/; times the preprocessor alone; times
      model.transcribe(..., language_id="hi") with the CTC decoder selected
      (1 warm-up + MEASURED_RUNS measured runs). Writes compare_work/nemo.json.

  stage B:  python -u compare_onnx_variants.py onnx <fp32|int8_full|int8_matmul>
      One process per ONNX variant, so only one model is resident and the
      process's peak RSS (resource.getrusage ru_maxrss) is attributable to
      that variant. Loads the saved features, runs each clip (1 warm-up +
      MEASURED_RUNS measured runs), greedy-CTC decodes with tokens.txt
      exactly as CtcGreedyDecoder.kt does. Writes compare_work/onnx_<v>.json.

  stage C:  python -u compare_onnx_variants.py report
      Merges the JSONs, computes WER/CER (jiwer) of every engine against
      both the human reference and the NeMo hypothesis, writes
      compare_onnx_variants_results.json and prints a summary table.

Timing caveats, stated so the numbers are not over-read:
  - Desktop x86_64 CPU (4 cores), CPUExecutionProvider, default session
    options. This is NOT a measurement of Android ARM64 performance.
  - NeMo transcribe() timing is end-to-end (audio file -> text, including
    preprocessing and decoding). ONNX timing is model-only (features ->
    logprobs); preprocessor time is reported separately so the two can be
    compared like-for-like by adding it.
"""
import json
import os
import resource
import sys
import time

VALIDATION_DIR = os.path.expanduser("~/itantra-stt-validation")
CKPT = os.path.join(VALIDATION_DIR, "models/indicconformer_stt_hi_hybrid_rnnt_large.nemo")
AUDIO_DIR = os.path.join(VALIDATION_DIR, "audio")
EXPORT_DIR = os.path.join(VALIDATION_DIR, "export")
TOKENS_PATH = os.path.join(EXPORT_DIR, "tokens.txt")
WORK_DIR = os.path.join(VALIDATION_DIR, "compare_work")
OUT_PATH = os.path.join(VALIDATION_DIR, "compare_onnx_variants_results.json")

ONNX_VARIANTS = {
    "fp32": "indicconformer_hi.onnx",
    "int8_full": "indicconformer_hi_int8.onnx",
    "int8_matmul": "indicconformer_hi_int8_matmul.onnx",
}

WARMUP_RUNS = 1
MEASURED_RUNS = 3


def load_manifest():
    with open(os.path.join(AUDIO_DIR, "manifest.json"), encoding="utf-8") as f:
        return json.load(f)


def peak_rss_mb():
    # Linux reports ru_maxrss in kilobytes.
    return round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1)


def stage_nemo():
    import numpy as np
    import soundfile as sf
    import torch
    import nemo.collections.asr as nemo_asr

    os.makedirs(WORK_DIR, exist_ok=True)
    manifest = load_manifest()

    t0 = time.perf_counter()
    model = nemo_asr.models.EncDecHybridRNNTCTCModel.restore_from(
        restore_path=CKPT, map_location=torch.device("cpu")
    )
    load_time_s = time.perf_counter() - t0
    model.eval()
    model.cur_decoder = "ctc"
    print(f"NeMo model loaded in {load_time_s:.2f}s, peak RSS so far {peak_rss_mb()} MB", flush=True)

    clips = []
    for entry in manifest:
        fname = entry["file"]
        wav_path = os.path.join(AUDIO_DIR, fname)
        audio, sr = sf.read(wav_path, dtype="float32")
        assert sr == 16000, f"{fname}: expected 16 kHz, got {sr}"
        duration_s = len(audio) / sr

        audio_t = torch.tensor(audio).unsqueeze(0)
        length_t = torch.tensor([len(audio)])
        pre_times = []
        with torch.no_grad():
            model.preprocessor(input_signal=audio_t, length=length_t)  # warm-up
            for _ in range(MEASURED_RUNS):
                t = time.perf_counter()
                features, feat_length = model.preprocessor(input_signal=audio_t, length=length_t)
                pre_times.append(time.perf_counter() - t)
        feat_np = features.numpy().astype(np.float32)
        len_np = feat_length.numpy().astype(np.int64)
        stem = os.path.splitext(fname)[0]
        np.save(os.path.join(WORK_DIR, f"{stem}_features.npy"), feat_np)
        np.save(os.path.join(WORK_DIR, f"{stem}_length.npy"), len_np)

        texts = []
        times = []
        for i in range(WARMUP_RUNS + MEASURED_RUNS):
            t = time.perf_counter()
            with torch.inference_mode():
                hyp = model.transcribe([wav_path], batch_size=1, language_id="hi", verbose=False)
            elapsed = time.perf_counter() - t
            first = hyp[0]
            if isinstance(first, list):
                first = first[0]
            text = first.text if hasattr(first, "text") else first
            texts.append(text.strip())
            if i >= WARMUP_RUNS:
                times.append(elapsed)

        mean_s = sum(times) / len(times)
        clip = {
            "file": fname,
            "duration_s": round(duration_s, 3),
            "feature_shape": list(feat_np.shape),
            "feature_length": int(len_np[0]),
            "preprocessor_times_s": [round(x, 4) for x in pre_times],
            "preprocessor_mean_s": round(sum(pre_times) / len(pre_times), 4),
            "text": texts[-1],
            "all_runs_identical_text": len(set(texts)) == 1,
            "transcribe_times_s": [round(x, 4) for x in times],
            "transcribe_mean_s": round(mean_s, 4),
            "rtf": round(mean_s / duration_s, 4),
        }
        clips.append(clip)
        print(f"[nemo] {fname} dur={duration_s:.2f}s feats={feat_np.shape} "
              f"pre={clip['preprocessor_mean_s']}s transcribe={clip['transcribe_mean_s']}s "
              f"rtf={clip['rtf']} text={clip['text']!r}", flush=True)

    out = {
        "engine": "nemo_pytorch_ctc",
        "load_time_s": round(load_time_s, 3),
        "peak_rss_mb": peak_rss_mb(),
        "torch_version": torch.__version__,
        "clips": clips,
    }
    with open(os.path.join(WORK_DIR, "nemo.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"[nemo] peak RSS {out['peak_rss_mb']} MB; wrote {WORK_DIR}/nemo.json", flush=True)


def load_tokens():
    tokens = []
    with open(TOKENS_PATH, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line:
                tokens.append(line[: line.rfind(" ")])
    return tokens


def ctc_greedy_decode(logprobs, tokens, blank_id):
    # Same algorithm as CtcGreedyDecoder.kt: per-frame argmax, collapse
    # repeats, drop blank, join pieces, U+2581 -> space, trim.
    collapsed = []
    prev = -1
    for i in logprobs[0].argmax(axis=-1):
        i = int(i)
        if i != prev and i != blank_id:
            collapsed.append(i)
        prev = i
    return "".join(tokens[i] for i in collapsed).replace("▁", " ").strip()


def stage_onnx(variant):
    import numpy as np
    import onnxruntime as ort

    fname_model = ONNX_VARIANTS[variant]
    path = os.path.join(EXPORT_DIR, fname_model)
    tokens = load_tokens()
    blank_id = len(tokens) - 1
    rss_before_load = peak_rss_mb()

    t0 = time.perf_counter()
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    load_time_s = time.perf_counter() - t0
    rss_after_load = peak_rss_mb()
    print(f"[{variant}] {fname_model} loaded in {load_time_s:.2f}s, "
          f"peak RSS {rss_after_load} MB", flush=True)

    clips = []
    for entry in load_manifest():
        stem = os.path.splitext(entry["file"])[0]
        feats = np.load(os.path.join(WORK_DIR, f"{stem}_features.npy"))
        length = np.load(os.path.join(WORK_DIR, f"{stem}_length.npy"))
        inputs = {"audio_signal": feats, "length": length}

        for _ in range(WARMUP_RUNS):
            sess.run(None, inputs)
        times = []
        texts = []
        out_frames = None
        for _ in range(MEASURED_RUNS):
            t = time.perf_counter()
            logprobs = sess.run(None, inputs)[0]
            times.append(time.perf_counter() - t)
            texts.append(ctc_greedy_decode(logprobs, tokens, blank_id))
            out_frames = int(logprobs.shape[1])

        clip = {
            "file": entry["file"],
            "output_frames": out_frames,
            "text": texts[-1],
            "all_runs_identical_text": len(set(texts)) == 1,
            "inference_times_s": [round(x, 4) for x in times],
            "inference_mean_s": round(sum(times) / len(times), 4),
        }
        clips.append(clip)
        print(f"[{variant}] {entry['file']} mean={clip['inference_mean_s']}s "
              f"frames_out={out_frames} text={clip['text']!r}", flush=True)

    out = {
        "engine": f"onnx_{variant}",
        "model_file": fname_model,
        "model_file_bytes": os.path.getsize(path),
        "onnxruntime_version": ort.__version__,
        "session_load_time_s": round(load_time_s, 3),
        "peak_rss_mb_before_load": rss_before_load,
        "peak_rss_mb_after_load": rss_after_load,
        "peak_rss_mb_after_inference": peak_rss_mb(),
        "clips": clips,
    }
    with open(os.path.join(WORK_DIR, f"onnx_{variant}.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"[{variant}] peak RSS after inference {out['peak_rss_mb_after_inference']} MB; "
          f"wrote {WORK_DIR}/onnx_{variant}.json", flush=True)


def stage_report():
    import jiwer

    manifest = {m["file"]: m for m in load_manifest()}
    with open(os.path.join(WORK_DIR, "nemo.json"), encoding="utf-8") as f:
        nemo = json.load(f)
    onnx = {}
    for v in ONNX_VARIANTS:
        with open(os.path.join(WORK_DIR, f"onnx_{v}.json"), encoding="utf-8") as f:
            onnx[v] = json.load(f)

    def err(ref, hyp):
        return round(jiwer.wer(ref, hyp), 4), round(jiwer.cer(ref, hyp), 4)

    clips = []
    for nclip in nemo["clips"]:
        fname = nclip["file"]
        ref = manifest[fname]["reference_transcript"]
        dur = nclip["duration_s"]
        row = {
            "file": fname,
            "duration_s": dur,
            "reference_transcript": ref,
            "nemo_pytorch": dict(nclip),
        }
        w, c = err(ref, nclip["text"])
        row["nemo_pytorch"].update({"wer_vs_reference": w, "cer_vs_reference": c})
        for v, data in onnx.items():
            oclip = next(x for x in data["clips"] if x["file"] == fname)
            wr, cr = err(ref, oclip["text"])
            wn, cn = err(nclip["text"], oclip["text"])
            model_only = oclip["inference_mean_s"]
            with_pre = model_only + nclip["preprocessor_mean_s"]
            row[f"onnx_{v}"] = dict(oclip)
            row[f"onnx_{v}"].update({
                "rtf_model_only": round(model_only / dur, 4),
                "rtf_incl_nemo_preprocessor": round(with_pre / dur, 4),
                "wer_vs_reference": wr,
                "cer_vs_reference": cr,
                "wer_vs_nemo": wn,
                "cer_vs_nemo": cn,
                "exact_match_vs_nemo": oclip["text"] == nclip["text"],
            })
        clips.append(row)

    summary = {
        "nemo_pytorch": {
            "load_time_s": nemo["load_time_s"],
            "peak_rss_mb": nemo["peak_rss_mb"],
        },
    }
    for v, data in onnx.items():
        summary[f"onnx_{v}"] = {
            "model_file": data["model_file"],
            "model_file_mb": round(data["model_file_bytes"] / (1024**2), 1),
            "session_load_time_s": data["session_load_time_s"],
            "peak_rss_mb_after_inference": data["peak_rss_mb_after_inference"],
        }

    results = {
        "methodology": {
            "host": "desktop x86_64, 4 cores, 7.6 GB RAM - NOT Android ARM64",
            "warmup_runs": WARMUP_RUNS,
            "measured_runs": MEASURED_RUNS,
            "onnx_provider": "CPUExecutionProvider, default SessionOptions",
            "onnxruntime_version": onnx["fp32"]["onnxruntime_version"],
            "torch_version": nemo["torch_version"],
            "features": "model.preprocessor output, computed once per clip and shared by all ONNX variants",
            "nemo_timing": "model.transcribe() end-to-end (includes preprocessing + CTC decode)",
            "onnx_timing": "sess.run() only (features -> logprobs); decode excluded",
        },
        "summary": summary,
        "clips": clips,
    }
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("\n=== Summary ===")
    for k, s in summary.items():
        print(k, s)
    print("\n=== Per clip ===")
    for row in clips:
        print(f"\n{row['file']} ({row['duration_s']}s)")
        print(f"  REF          : {row['reference_transcript']}")
        n = row["nemo_pytorch"]
        print(f"  nemo_pytorch : {n['text']}")
        print(f"                 e2e={n['transcribe_mean_s']}s rtf={n['rtf']} "
              f"WER/CER vs ref={n['wer_vs_reference']}/{n['cer_vs_reference']}")
        for v in ONNX_VARIANTS:
            o = row[f"onnx_{v}"]
            print(f"  onnx_{v:<11}: {o['text']}")
            print(f"                 model={o['inference_mean_s']}s rtf_model={o['rtf_model_only']} "
                  f"rtf_with_pre={o['rtf_incl_nemo_preprocessor']} "
                  f"WER/CER vs ref={o['wer_vs_reference']}/{o['cer_vs_reference']} "
                  f"vs nemo={o['wer_vs_nemo']}/{o['cer_vs_nemo']} exact_vs_nemo={o['exact_match_vs_nemo']}")
    print(f"\nWrote {OUT_PATH}")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("nemo", "onnx", "report"):
        sys.exit("usage: compare_onnx_variants.py nemo | onnx <fp32|int8_full|int8_matmul> | report")
    if sys.argv[1] == "nemo":
        stage_nemo()
    elif sys.argv[1] == "onnx":
        if len(sys.argv) != 3 or sys.argv[2] not in ONNX_VARIANTS:
            sys.exit(f"onnx stage needs one of {list(ONNX_VARIANTS)}")
        stage_onnx(sys.argv[2])
    else:
        stage_report()
