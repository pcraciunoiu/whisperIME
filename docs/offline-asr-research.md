# Offline ASR research (Part A)

**Automatic Speech Recognition (ASR)** here means on-device speech-to-text after models are downloaded—no audio sent to a server for recognition.

**Constraint for SpeechToText (whisperIME):** transcription must run **fully offline** (no cloud STT). Optional ML Kit or other network features are out of scope for *this* document.

**Where to integrate first:** [asr-session-architecture.md](asr-session-architecture.md) (RecognitionService-first).

---

## What this app ships today

| Engine | Runtime / assets | English | Notes |
|--------|------------------|---------|--------|
| **Whisper** | **TFLite** ([DocWolle / whisper_tflite_models](https://huggingface.co/DocWolle/whisper_tflite_models)) *or* **whisper.cpp** (GGML, JNI [`WhisperEngineCpp`](../app/src/main/java/com/whispertflite/engine/WhisperEngineCpp.java); vendored [`third_party/whisper.cpp`](../../third_party/whisper.cpp)) | Yes (`.en` / `ggml-*.en.*`) + multilingual | Routing: [`Whisper`](../app/src/main/java/com/whispertflite/asr/Whisper.java) picks [`WhisperEngineJava`](../app/src/main/java/com/whispertflite/engine/WhisperEngineJava.java) vs [`WhisperEngineCpp`](../app/src/main/java/com/whispertflite/engine/WhisperEngineCpp.java) from the selected file (`*.tflite` vs `ggml-*.bin` / `*.gguf` per [`WhisperGgmlModels`](../app/src/main/java/com/whispertflite/asr/WhisperGgmlModels.kt)). Quantized English tiny ships from setup download ([ggerganov/whisper.cpp on HF](https://huggingface.co/ggerganov/whisper.cpp), e.g. `ggml-tiny.en-q5_1.bin`). Optional **live** partials: [`WhisperLivePreviewLoop`](../app/src/main/java/com/whispertflite/asr/WhisperLivePreviewLoop.java). Model basename prefs: [`WhisperModelSelection`](../app/src/main/java/com/whispertflite/asr/WhisperModelSelection.kt). |
| **Parakeet** | ONNX + Microsoft ORT | English (streaming) | [`ParakeetStreamingEngine`](../app/src/main/java/com/whispertflite/parakeet/ParakeetStreamingEngine.kt); downloads from [smcleod HF](https://huggingface.co/smcleod/multitalker-parakeet-streaming-0.6b-v1-onnx-int8) per [`ParakeetConstants`](../app/src/main/java/com/whispertflite/parakeet/ParakeetConstants.kt). |
| **Moonshine Base** | `moonshine-voice` AAR + ORT | English | [`MoonshineHoldRecorder`](../app/src/main/java/com/whispertflite/moonshine/MoonshineHoldRecorder.kt); shares native `libonnxruntime.so` with Parakeet—see [`app/build.gradle`](../app/build.gradle). |
| **Sherpa-ONNX** | k2-fsa sherpa-onnx AAR + ORT (overlaid `libonnxruntime.so`) | Streaming catalog (English Zipformer + NeMo CTC + Nemotron; `getModelConfig` indices) | [`SherpaStreamingRecorder`](../app/src/main/java/com/whispertflite/sherpa/SherpaStreamingRecorder.kt); models under `<files>/sherpa-onnx-models/<HF dir>/` per [`SherpaModelCatalog`](../app/src/main/java/com/whispertflite/sherpa/SherpaModelCatalog.kt) / `getModelConfig` types. Optional offline final punctuation polish: [`SherpaPunctuationPostProcessor`](../app/src/main/java/com/whispertflite/sherpa/SherpaPunctuationPostProcessor.kt). |

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
| **whisper.cpp** (GGML/GGUF) | Whisper weights, many quantizations | **Integrated** as [`WhisperEngineCpp`](../app/src/main/java/com/whispertflite/engine/WhisperEngineCpp.java) + `libwhisper_jni.so`; still optional vs TFLite for A/B. |
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

- [x] README/engine picker includes **Whisper + Parakeet + Moonshine + Sherpa-ONNX** (streaming catalog).
- [x] Reference benchmark app (android-offline-transcribe) documented above under **Reference project**.
- [x] Sherpa-onnx: fourth engine with RecognitionService-first routing; JNI validated via the main catalog download path.

**Integration priority:** see [asr-session-architecture.md](asr-session-architecture.md) (RecognitionService-first; IME secondary).

---

## Sherpa-ONNX (fourth engine)

**Purpose:** **Streaming** ASR via k2-fsa **sherpa-onnx** with the same offline constraint as other engines. User selects **Sherpa-ONNX** in [`AsrEnginePreferences`](../app/src/main/java/com/whispertflite/AsrEnginePreferences.kt) and a **catalog variant** ([`SherpaModelCatalog`](../app/src/main/java/com/whispertflite/sherpa/SherpaModelCatalog.kt)); models must match `getModelConfig(type)` relative paths under `…/files/sherpa-onnx-models/`.

**Punctuation strategy:** Streaming Zipformer/LSTM checkpoints often emit **little or no** punctuation. The app applies **offline, on-device** final-text polish ([`SherpaPunctuationPostProcessor`](../app/src/main/java/com/whispertflite/sherpa/SherpaPunctuationPostProcessor.kt))—no cloud—controlled by `SherpaPreferences.KEY_PUNCT_ENHANCE` (default on). Partials stay raw for latency.

**Downloads:** The setup screen **downloads** ONNX/token files over HTTPS. Hub URLs use files at the **repo root** (e.g. `…/resolve/main/encoder-….onnx`); on disk the app still mirrors sherpa’s `modelDir/file` layout under `sherpa-onnx-models/`. Manual `adb` copy uses the same layout (see below).

**AAR / JNI:** Gradle downloads `sherpa-onnx-*.aar` from [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases) (`downloadSherpaOnnxAar` in [`app/build.gradle`](../app/build.gradle)). Kotlin/API code comes from `classes.jar` extracted from that AAR; native code is merged separately. Sherpa’s JNI is linked against a different ONNX Runtime than Moonshine’s `OrtGetApiBase@VERS_1.23.0` line, so the app ships **Microsoft’s** `libonnxruntime.so` (for Moonshine + Parakeet + `libonnxruntime4j_jni.so`) **and** Sherpa’s runtime as **`libonnxruntime_sherpa.so`**, with **`prepareSherpaJniWithRenamedOrt`** patching `libsherpa-onnx-jni.so` to load the renamed library (host `patchelf` from the build).

### Manual copy (example: Zipformer EN Jun 2023, `getModelConfig(6)`)

| | |
|--|--|
| **Hugging Face** | [csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) |
| **On-device path** | `<getExternalFilesDir>/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26/` |
| **Required files** | Filenames from `getModelConfig` for that catalog type (e.g. `encoder-…`, `decoder-…`, `joiner-…`, `tokens.txt`); same names as the HF repo root. |

For a debug APK (`applicationId` `org.speechtotext.input.debug`), push into:

`/storage/emulated/0/Android/data/org.speechtotext.input.debug/files/sherpa-onnx-models/<modelDir>/`

Use `huggingface-cli download` or Git LFS, then `adb push` the files. For release builds, replace `org.speechtotext.input.debug` with `org.speechtotext.input`. After copying, pick the matching variant in the app download screen or main-screen Sherpa settings.

**Last updated:** 2026-04-10
