# ASR session architecture (offline SpeechToText)

## RecognitionService first

The primary user story is **dictation into other apps** (e.g. notes, messaging) via Android’s
**system voice input**: [`WhisperRecognitionService`](../app/src/main/java/com/whispertflite/WhisperRecognitionService.java)
implements `RecognitionService` and delivers `partialResults` / final results to the host app.

**Maintenance priority:** when fixing bugs or adding engine behavior, validate the
**RecognitionService** path first, then the standalone [`MainActivity`](../app/src/main/java/com/whispertflite/MainActivity.java),
then the IME ([`WhisperInputMethodService`](../app/src/main/java/com/whispertflite/WhisperInputMethodService.java)).
The IME is optional for users who prefer a dedicated mic strip; it should track shared ASR logic, not
define it alone.

This matches the refactor audit: *extract shared ASR/session logic with RS as the spine; treat IME as secondary until the RS path is solid.*

## Shared building blocks (`com.whispertflite.asr`)

| Piece | Role |
|--------|------|
| [`OfflineAsrEngines`](../app/src/main/java/com/whispertflite/asr/OfflineAsrEngines.kt) | “Engine X selected **and** models present” for routing (Moonshine / Parakeet / Sherpa vs Whisper). |
| [`LiveTranscribePreferences`](../app/src/main/java/com/whispertflite/asr/LiveTranscribePreferences.kt) | Single preference key for live partials across all surfaces. |
| [`WhisperModelSelection`](../app/src/main/java/com/whispertflite/asr/WhisperModelSelection.kt) | Whisper `.tflite` basename: main screen vs RecognitionService settings. |
| [`WhisperLivePreviewLoop`](../app/src/main/java/com/whispertflite/asr/WhisperLivePreviewLoop.java) | Throttled on-device Whisper previews from [`Recorder`](../app/src/main/java/com/whispertflite/asr/Recorder.java) PCM snapshots. |

## Engines (see also)

- [offline-asr-research.md](offline-asr-research.md) — shipped engines and research notes.
- [parakeet-onnxruntime.md](parakeet-onnxruntime.md) — ORT merge with Moonshine.

### Sherpa-ONNX (fourth engine)

**Production path:** [`AsrEnginePreferences.SHERPA`](../app/src/main/java/com/whispertflite/AsrEnginePreferences.kt) + [`SherpaModelCatalog`](../app/src/main/java/com/whispertflite/sherpa/SherpaModelCatalog.kt) (built-in `getModelConfig` types). [`WhisperRecognitionService`](../app/src/main/java/com/whispertflite/WhisperRecognitionService.java) routes **Moonshine → Parakeet → Sherpa → Whisper (TFLite)** when each “selected and ready” gate matches. Streaming hold uses [`SherpaStreamingRecorder`](../app/src/main/java/com/whispertflite/sherpa/SherpaStreamingRecorder.kt). **Punctuation:** optional offline final-text polish via [`SherpaPunctuationPostProcessor`](../app/src/main/java/com/whispertflite/sherpa/SherpaPunctuationPostProcessor.kt) (toggle `SherpaPreferences.KEY_PUNCT_ENHANCE`).

**ORT:** Sherpa JNI expects a matching `libonnxruntime.so`; the build uses **`overlaySherpaOnnxRuntime`** in [`app/build.gradle`](../app/build.gradle) (see [offline-asr-research.md](offline-asr-research.md)).

### Sherpa-onnx spike (dev-only)

Debug builds can still open [`SherpaOnnxSpikeActivity`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxSpikeActivity.kt) for a minimal Zipformer trial; production Sherpa uses the shared catalog + [`SherpaOnnxConfig.absolutizeModelConfig`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxConfig.kt).
