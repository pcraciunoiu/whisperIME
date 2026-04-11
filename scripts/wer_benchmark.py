#!/usr/bin/env python3
"""
Offline WER/CER between a reference transcript and a hypothesis (e.g. ASR output).

  pip install jiwer
  python3 scripts/wer_benchmark.py reference.txt hypothesis.txt

Use "-" as hypothesis path to read text from stdin.
"""
from __future__ import annotations

import argparse
import re
import sys


def _read_text(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    with open(path, encoding="utf-8") as f:
        return f.read()


def _normalize(s: str) -> str:
    s = s.strip().lower()
    s = re.sub(r"[^\w\s]", "", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def main() -> int:
    p = argparse.ArgumentParser(description="Compute WER/CER with jiwer (offline ASR comparison).")
    p.add_argument("reference", help="Path to reference transcript (.txt)")
    p.add_argument("hypothesis", help="Path to hypothesis transcript, or - for stdin")
    p.add_argument(
        "--no-normalize",
        action="store_true",
        help="Skip light punctuation/whitespace normalization",
    )
    args = p.parse_args()

    try:
        from jiwer import cer, wer
    except ImportError:
        print("Install jiwer: pip install jiwer", file=sys.stderr)
        return 1

    ref_raw = _read_text(args.reference)
    hyp_raw = _read_text(args.hypothesis)

    if not args.no_normalize:
        ref = _normalize(ref_raw)
        hyp = _normalize(hyp_raw)
    else:
        ref, hyp = ref_raw.strip(), hyp_raw.strip()

    if not ref:
        print("Reference is empty after load.", file=sys.stderr)
        return 1

    w = wer(ref, hyp)
    c = cer(ref, hyp)
    print(f"WER: {w:.4f}")
    print(f"CER: {c:.4f}")
    print(f"Reference chars (normalized): {len(ref)}")
    print(f"Hypothesis chars (normalized): {len(hyp)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
