#!/usr/bin/env python3
"""iTANTRA offline translation node (runs on the laptop, not the phone).

ONE model (IndicTrans2 indic-indic distilled 320M), ONE direction: Hindi -> Odia.
Loaded once at start-up and reused. No internet is needed after the model is
in the local Hugging Face cache (HF_HUB_OFFLINE=1 is set below).

Protocol: one UTF-8 line per request over TCP, one line back.
  request:  "<src>\t<tgt>\t<text>"      e.g. "hi\tor\tमेरा नाम पारस है"
  health:   "HEALTH"
  reply:    "OK\t<ms>\t<translated text>" | "ERR\t<reason>" | "HEALTH\t<directions>"

Run:   ~/itantra-translation/venv/bin/python app/scripts/translation_node.py [--port 8765]
Phone: adb -s <serial> reverse tcp:8765 tcp:8765   (then the app uses 127.0.0.1:8765),
       or put the laptop on the phones' Wi-Fi and enter its IP in the app.
"""
import argparse
import os
import socketserver
import sys
import time

os.environ.setdefault("HF_HUB_OFFLINE", "1")  # never reach the internet at run time

import torch  # noqa: E402
from IndicTransToolkit.processor import IndicProcessor  # noqa: E402
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer  # noqa: E402

MODEL = "ai4bharat/indictrans2-indic-indic-dist-320M"
# The only direction this node offers (app code -> IndicTrans2 code).
DIRECTIONS = {("hi", "or"): ("hin_Deva", "ory_Orya")}


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Translator:
    def __init__(self):
        t0 = time.time()
        torch.set_num_threads(max(1, (os.cpu_count() or 2) // 2))
        self.tok = AutoTokenizer.from_pretrained(MODEL, trust_remote_code=True)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(MODEL, trust_remote_code=True).eval()
        self.ip = IndicProcessor(inference=True)
        log(f"model loaded in {time.time() - t0:.1f}s: {MODEL}")
        # Warm-up: the first generate() is several times slower than later ones.
        t0 = time.time()
        self.translate("नमस्ते", "hi", "or")
        log(f"warm-up translation done in {(time.time() - t0) * 1000:.0f} ms")

    def translate(self, text, src, tgt):
        s, t = DIRECTIONS[(src, tgt)]
        batch = self.ip.preprocess_batch([text], src_lang=s, tgt_lang=t)
        inputs = self.tok(batch, truncation=True, padding="longest", return_tensors="pt")
        with torch.inference_mode():
            out = self.model.generate(**inputs, num_beams=1, do_sample=False, max_length=256, use_cache=True)
        decoded = self.tok.batch_decode(out, skip_special_tokens=True, clean_up_tokenization_spaces=True)
        return self.ip.postprocess_batch(decoded, lang=t)[0]


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        for raw in self.rfile:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if not line:
                continue
            self.wfile.write((self.reply(line) + "\n").encode("utf-8"))
            self.wfile.flush()

    def reply(self, line):
        if line == "HEALTH":
            return "HEALTH\t" + ",".join(f"{s}-{t}" for s, t in DIRECTIONS)
        parts = line.split("\t", 2)
        if len(parts) != 3 or not parts[2].strip():
            return "ERR\tmalformed request"
        src, tgt, text = parts
        if (src, tgt) not in DIRECTIONS:
            return f"ERR\tunsupported direction {src}-{tgt}"
        t0 = time.time()
        try:
            out = TRANSLATOR.translate(text, src, tgt).replace("\t", " ").replace("\n", " ").strip()
        except Exception as e:  # never crash the node
            log(f"translation failed: {e!r}")
            return f"ERR\ttranslation failed: {type(e).__name__}"
        ms = int((time.time() - t0) * 1000)
        log(f"{src}->{tgt} {ms}ms: {text!r} -> {out!r}")
        return f"OK\t{ms}\t{out}" if out else "ERR\tempty translation"


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--selftest", action="store_true", help="translate one sentence and exit")
    args = ap.parse_args()
    TRANSLATOR = Translator()
    if args.selftest:
        for s in ["मेरा नाम पारस है", "मुझे पानी चाहिए", "यहाँ आग लगी है"]:
            t0 = time.time()
            print(s, "->", TRANSLATOR.translate(s, "hi", "or"), f"({(time.time() - t0) * 1000:.0f} ms)")
        sys.exit(0)
    log(f"listening on {args.host}:{args.port}, directions: {list(DIRECTIONS)}")
    Server((args.host, args.port), Handler).serve_forever()
