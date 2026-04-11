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
| **Sherpa-ONNX** | k2-fsa sherpa-onnx AAR + ORT (overlaid `libonnxruntime.so`) | English (streaming catalog) | [`SherpaStreamingRecorder`](../app/src/main/java/com/whispertflite/sherpa/SherpaStreamingRecorder.kt); models under `<files>/sherpa-onnx-models/<HF dir>/` per [`SherpaModelCatalog`](../app/src/main/java/com/whispertflite/sherpa/SherpaModelCatalog.kt) / `getModelConfig` types. Optional offline final punctuation polish: [`SherpaPunctuationPostProcessor`](../app/src/main/java/com/whispertflite/sherpa/SherpaPunctuationPostProcessor.kt). |

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

- [x] README/engine picker includes **Whisper + Parakeet + Moonshine + Sherpa-ONNX** (English streaming catalog).
- [x] Reference benchmark app (android-offline-transcribe) documented above under **Reference project**.
- [x] Sherpa-onnx: fourth engine with RecognitionService-first routing; debug **Sherpa spike** activity retained for quick JNI checks.

**Integration priority:** see [asr-session-architecture.md](asr-session-architecture.md) (RecognitionService-first; IME secondary).

---

## Sherpa-ONNX (fourth engine)

**Purpose:** English **streaming** ASR via k2-fsa **sherpa-onnx** with the same offline constraint as other engines. User selects **Sherpa-ONNX** in [`AsrEnginePreferences`](../app/src/main/java/com/whispertflite/AsrEnginePreferences.kt) and a **catalog variant** ([`SherpaModelCatalog`](../app/src/main/java/com/whispertflite/sherpa/SherpaModelCatalog.kt)); models must match `getModelConfig(type)` relative paths under `…/files/sherpa-onnx-models/`.

**Punctuation strategy:** Streaming Zipformer/LSTM checkpoints often emit **little or no** punctuation. The app applies **offline, on-device** final-text polish ([`SherpaPunctuationPostProcessor`](../app/src/main/java/com/whispertflite/sherpa/SherpaPunctuationPostProcessor.kt))—no cloud—controlled by `SherpaPreferences.KEY_PUNCT_ENHANCE` (default on). Partials stay raw for latency.

**Downloads:** Manual Hugging Face / `adb` (same spirit as the spike instructions below). Optional automated downloader is future work.

---

## Sherpa-onnx spike (dev-only activity)

**Purpose:** Quick JNI / mic loop check beside production Sherpa wiring; **does not** replace the catalog-driven engine.

**AAR / JNI:** Gradle downloads `sherpa-onnx-1.12.36.aar` from [release v1.12.37](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.37) (`downloadSherpaOnnxAar` in [`app/build.gradle`](../app/build.gradle)). `libsherpa-onnx-jni.so` must load against an `libonnxruntime.so` that exports what that JNI was linked with (`OrtGetApiBase`, …). The Microsoft ORT unpack alone caused `UnsatisfiedLinkError`; the build runs **`overlaySherpaOnnxRuntime`** to copy **only** `libonnxruntime.so` from the sherpa AAR over the MS unpack (keeping `libonnxruntime4j_jni.so` from Microsoft for Parakeet). If Moonshine/Parakeet regress, revisit ORT packaging. Logcat tag **`SherpaOnnxSpike`** prints native `.so` names/sizes on spike init for debugging.

**Spike model (English streaming Zipformer, `getModelConfig(6)`):**

| | |
|--|--|
| **Hugging Face** | [csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) |
| **On-device path** | `<getExternalFilesDir>/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26/` |
| **Required files** | `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx`, `decoder-epoch-99-avg-1-chunk-16-left-128.onnx`, `joiner-epoch-99-avg-1-chunk-16-left-128.onnx`, `tokens.txt` (same layout as HF repo root; use Git LFS or `huggingface-cli download`). |
| **Approx. size** | ~350 MB total (large encoders on LFS). |

**Step-by-step: what to copy and where (debug APK)**

1. **Install the debug build** of the app once (`applicationId` is `org.speechtotext.input.debug`). Android creates the app-specific storage root the first time the app runs.
2. **Target directory on the phone** (must match exactly — names are case-sensitive):

   `/storage/emulated/0/Android/data/org.speechtotext.input.debug/files/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26/`

   That path is: **scoped storage** → **Android/data** → **your debug package** → **files** → **sherpa-onnx-models** → **model folder**. The spike screen shows this path when models are missing or ready.
3. **Create the two nested folders** if they do not exist: `sherpa-onnx-models` and inside it `sherpa-onnx-streaming-zipformer-en-2023-06-26`.
4. **Copy exactly these four files** into that inner folder (repo root on [Hugging Face](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26); large files use Git LFS):

   - `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx`
   - `decoder-epoch-99-avg-1-chunk-16-left-128.onnx`
   - `joiner-epoch-99-avg-1-chunk-16-left-128.onnx`
   - `tokens.txt`

   Do **not** rename them. You do **not** need `test_wavs/`, `README.md`, or `bpe.model` for this spike.
5. **How to get the files onto the phone**

   - **From a computer (recommended):** On your PC, download the repo with LFS (`git lfs install` then clone, or use `huggingface-cli download csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26 --local-dir ./zipformer-en`). Copy only the four files above into a local folder that mirrors the inner path name. Then push with USB or Wi‑Fi debugging:

     `adb shell mkdir -p "/storage/emulated/0/Android/data/org.speechtotext.input.debug/files/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26"`

     `adb push encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx "/storage/emulated/0/Android/data/org.speechtotext.input.debug/files/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26/"`

     (repeat `adb push` for the other three files), or push a whole directory: `adb push ./sherpa-onnx-streaming-zipformer-en-2023-06-26/. "/storage/emulated/0/Android/data/org.speechtotext.input.debug/files/sherpa-onnx-models/sherpa-onnx-streaming-zipformer-en-2023-06-26/"`

   - **Without adb:** Use Files by Google or a PC USB connection to place the same four files under that path (you may need to show hidden folders or use “Android/data” access; on Android 11+ direct access to `Android/data` from some file managers is restricted—**adb or the device’s “Files” app for that package** is often easier).
6. **Restart the app** (force-stop or swipe away), open **Sherpa spike (debug)** on the main screen, and confirm the status line says the model dir is OK. Then use **Start recording**.

**Release build note:** If you install a non-debug APK, replace `org.speechtotext.input.debug` with `org.speechtotext.input` in the paths above.

**Code:** [`SherpaOnnxSpikeActivity`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxSpikeActivity.kt), [`SherpaOnnxSpikePaths`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxSpikePaths.kt). Debug builds expose a **Sherpa spike** entry on the main screen; release builds hide it.

**Measurements (on-device, to fill in after runs):**

| Check | Result |
|-------|--------|
| Load / JNI | e.g. no `UnsatisfiedLinkError`; tag `SherpaOnnxSpike` in logcat |
| Stability | e.g. cold start + 5 record sessions without crash |
| Latency | e.g. rough RTF from log timestamps |
| WER | optional: same clips as [wer-benchmark.md](wer-benchmark.md) |

**Go / no-go (spike vs production):**

- **Go:** JNI stable with merged MS ORT (`overlaySherpaOnnxRuntime`); fourth engine uses the same packaging with catalog-driven models.
- **If ORT regresses Parakeet/Moonshine:** revisit overlay strategy; spike activity remains useful for isolating JNI issues.

**Last updated:** 2026-04-10
