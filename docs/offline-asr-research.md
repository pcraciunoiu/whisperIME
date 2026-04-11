# Offline ASR research (Part A)

**Automatic Speech Recognition (ASR)** here means on-device speech-to-text after models are downloaded—no audio sent to a server for recognition.

**Constraint for SpeechToText (whisperIME):** transcription must run **fully offline** (no cloud STT). Optional ML Kit or other network features are out of scope for *this* document.

---

## What this app ships today

| Engine | Runtime / assets | English | Notes |
|--------|------------------|---------|--------|
| **Whisper** | TFLite ([DocWolle / whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)) | Yes (`.en` models) + multilingual | Main path: [`WhisperEngineJava`](../app/src/main/java/com/whispertflite/engine/WhisperEngineJava.java); model names in [`MainActivity`](../app/src/main/java/com/whispertflite/MainActivity.java). |
| **Parakeet** | ONNX + Microsoft ORT | English (streaming) | [`ParakeetStreamingEngine`](../app/src/main/java/com/whispertflite/parakeet/ParakeetStreamingEngine.kt); downloads from [smcleod HF](https://huggingface.co/smcleod/multitalker-parakeet-streaming-0.6b-v1-onnx-int8) per [`ParakeetConstants`](../app/src/main/java/com/whispertflite/parakeet/ParakeetConstants.kt). |
| **Moonshine Base** | `moonshine-voice` AAR + ORT | English | [`MoonshineHoldRecorder`](../app/src/main/java/com/whispertflite/moonshine/MoonshineHoldRecorder.kt); shares native `libonnxruntime.so` with Parakeet—see [`app/build.gradle`](../app/build.gradle). |

---

## Reference project: android-offline-transcribe

**Repo:** [voiceping-ai/android-offline-transcribe](https://github.com/voiceping-ai/android-offline-transcribe) (Apache-2.0)

**Purpose for us:** Benchmark and model catalog—not an IME. It demonstrates **six** backends (sherpa-onnx, whisper.cpp JNI, Qwen ONNX/NEON, Android `SpeechRecognizer`, etc.) and documents **~16 models** with sample metrics (e.g. Galaxy S10, RTF, keyword pass/fail). Many models come from **`csukuangfj/sherpa-onnx-*`** on Hugging Face.

**Relevance:**

- **Shortlists** Whisper sizes, Moonshine, Parakeet variants, Zipformer streaming (English), SenseVoice (multilingual), etc., with **download sizes** and **relative speed**.
- **Does not** replace current integrations: whisperIME uses **TFLite Whisper** and **custom** Parakeet/Moonshine wiring; sherpa-onnx would be a **large** architectural change if adopted wholesale.
- **Optional online** Android Speech path in that app sends audio to Google—ignore for offline-only parity.

---

## Other offline families (for comparison / future work)

| Family | Typical use | Notes |
|--------|-------------|--------|
| **sherpa-onnx** | Unified ONNX ASR on mobile | Strong ecosystem; may reduce ORT fragmentation vs mixing multiple AARs—needs evaluation. |
| **whisper.cpp** (GGML/GGUF) | Whisper weights, many quantizations | Often faster than naive TFLite on some devices; JNI/size tradeoffs. |
| **Vosk** | Lightweight embedded | Good for constrained English; different accuracy profile than Whisper. |

Cloud-only offerings (e.g. **MAI-Transcribe-1** / Foundry) are **not** candidates for offline-first product requirements.

---

## Accuracy comparison (same constraint)

1. Fixed **test audio** (WAV, 16 kHz mono typical) + **reference transcript**.
2. Normalize text and compute **Word Error Rate (WER)** (e.g. Python `jiwer`).
3. Run the **same file** through each **local** engine build—no upload.

Community or marketing pages (e.g. “best offline apps”) are **not** substitutes for measured WER on your clips.

---

## Parakeet + Moonshine ORT conflict (fixed)

See [parakeet-onnxruntime.md](parakeet-onnxruntime.md) — duplicate `libonnxruntime.so` from Moonshine’s AAR overwrote Microsoft’s runtime while JNI stayed on 1.19.x, which broke Parakeet.

## Decisions (for `offline-agents-feature-list` todo)

Fill in as the team converges:

- [ ] Keep README/engine picker to **Whisper + Parakeet + Moonshine** only for now.
- [ ] Mention sherpa-onnx / reference repo under “Related / research” (optional).
- [ ] Plan a spike: sherpa-onnx Zipformer or Whisper-small vs current TFLite (size/latency/WER).

**Last updated:** 2026-04-10
