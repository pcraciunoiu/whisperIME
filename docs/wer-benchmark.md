# Word Error Rate (WER) benchmark (offline)

Use this to compare **on-device** engines (Whisper TFLite, Parakeet, Moonshine) on the **same audio** with a **reference transcript**. No cloud STT.

## What you need

1. **Reference text** — gold transcript for the clip (plain UTF-8 `.txt`).
2. **Hypothesis text** — what each engine produced (copy from the app or save from a test harness).
3. **Python 3** with [`jiwer`](https://github.com/jitsi/jiwer): `pip install jiwer`

Optional: raw **WAV** (16 kHz mono is typical for these pipelines) kept alongside the transcript so you can re-run the same clip after model or code changes.

## Normalize text before WER

- Lowercase (optional but common).
- Remove punctuation you do not care about, collapse whitespace.
- For multilingual clips, keep the same normalization for reference and hypothesis.

`jiwer` can apply transforms; the script below uses a simple default.

## Run the helper script

From the repo root (after `pip install jiwer`):

```bash
python3 scripts/wer_benchmark.py path/to/reference.txt path/to/hypothesis.txt
```

With stdin hypothesis:

```bash
pbpaste | python3 scripts/wer_benchmark.py reference.txt -
```

Outputs **WER** and **CER** (character error rate) on stderr/stdout.

## Suggested workflow

1. Record or pick a fixed clip; write `refs/clip01.txt`.
2. Run each engine on the same audio (or paste recognition into `hyps/whisper_clip01.txt`, `hyps/parakeet_clip01.txt`, …).
3. Run `wer_benchmark.py` per pair; log results in a spreadsheet or `docs/` note.

## Same constraint as the app

All recognition must be **local** after models are installed. Comparing against cloud transcripts is fine for references; engine output must remain offline.
