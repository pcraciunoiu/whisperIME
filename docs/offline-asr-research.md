# Offline ASR research (Part A)

**Automatic Speech Recognition (ASR)** here means on-device speech-to-text after models are downloaded—no audio sent to a server for recognition.

**Constraint for SpeechToText (whisperIME):** transcription must run **fully offline** (no cloud STT). Optional ML Kit or other network features are out of scope for *this* document.

**Where to integrate first:** [asr-session-architecture.md](asr-session-architecture.md) (RecognitionService-first).

---

## What this app ships today

| Engine | Runtime / assets | English | Notes |
|--------|------------------|---------|--------|
| **Whisper** | TFLite ([DocWolle / whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)) | Yes (`.en` models) + multilingual | Main path: [`WhisperEngineJava`](../app/src/main/java/com/whispertflite/engine/WhisperEngineJava.java); optional **live** partials via [`WhisperLivePreviewLoop`](../app/src/main/java/com/whispertflite/asr/WhisperLivePreviewLoop.java). Model basename prefs: [`WhisperModelSelection`](../app/src/main/java/com/whispertflite/asr/WhisperModelSelection.kt) (main vs RecognitionService). |
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

**Concrete workflow:** [wer-benchmark.md](wer-benchmark.md) and `scripts/wer_benchmark.py`.

Community or marketing pages (e.g. “best offline apps”) are **not** substitutes for measured WER on your clips.

---

## Parakeet + Moonshine ORT conflict (fixed)

See [parakeet-onnxruntime.md](parakeet-onnxruntime.md) — duplicate `libonnxruntime.so` paths must resolve to **one** Microsoft ORT version that matches both Parakeet’s Java JNI and Moonshine’s `libmoonshine.so` (bump `onnxruntime-android` and `ortJniUnpack` together).

## Decisions (for `offline-agents-feature-list` todo)

- [x] Keep README/engine picker to **Whisper + Parakeet + Moonshine** only for now.
- [x] Reference benchmark app (android-offline-transcribe) documented above under **Reference project**.
- [x] Optional spike: sherpa-onnx English streaming Zipformer (dev-only activity; not a fourth engine).

**Integration priority:** see [asr-session-architecture.md](asr-session-architecture.md) (RecognitionService-first; IME secondary).

---

## Sherpa-onnx spike (dev-only, not a product engine)

**Purpose:** Evaluate k2-fsa **sherpa-onnx** JNI + Kotlin API beside existing TFLite Whisper and custom Parakeet/Moonshine ONNX, without adding a user-facing engine or touching `AsrEnginePreferences`.

**AAR / JNI:** Gradle downloads `sherpa-onnx-1.12.36.aar` from [release v1.12.37](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.37) (`downloadSherpaOnnxAar` in [`app/build.gradle`](../app/build.gradle)). The APK merges **one** `libonnxruntime.so` with Parakeet/Moonshine (Microsoft ORT 1.23.0 via `unpackMicrosoftOrtJni`); sherpa ships its own ORT inside the AAR—`packaging.jniLibs.pickFirst` applies. **Validate on a physical arm64 device** if anything misbehaves at load time.

**Spike model (English streaming Zipformer, `getModelConfig(6)`):**

| | |
|--|--|
| **Hugging Face** | [csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) |
| **On-device path** | `<getExternalFilesDir>/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26/` |
| **Required files** | `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx`, `decoder-epoch-99-avg-1-chunk-16-left-128.onnx`, `joiner-epoch-99-avg-1-chunk-16-left-128.onnx`, `tokens.txt` (same layout as HF repo root; use Git LFS or `huggingface-cli download`). |
| **Approx. size** | ~350 MB total (large encoders on LFS). |

**Code:** [`SherpaOnnxSpikeActivity`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxSpikeActivity.kt), [`SherpaOnnxSpikePaths`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxSpikePaths.kt). Debug builds expose a **Sherpa spike** entry on the main screen; release builds hide it.

**Measurements:** Fill in stability/latency/WER after on-device runs (see [wer-benchmark.md](wer-benchmark.md)).

**Last updated:** 2026-04-10
