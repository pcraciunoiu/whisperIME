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
| [`OfflineAsrEngines`](../app/src/main/java/com/whispertflite/asr/OfflineAsrEngines.kt) | “Engine X selected **and** models present” for routing (Moonshine/Parakeet vs Whisper). |
| [`LiveTranscribePreferences`](../app/src/main/java/com/whispertflite/asr/LiveTranscribePreferences.kt) | Single preference key for live partials across all surfaces. |
| [`WhisperModelSelection`](../app/src/main/java/com/whispertflite/asr/WhisperModelSelection.kt) | Whisper `.tflite` basename: main screen vs RecognitionService settings. |
| [`WhisperLivePreviewLoop`](../app/src/main/java/com/whispertflite/asr/WhisperLivePreviewLoop.java) | Throttled on-device Whisper previews from [`Recorder`](../app/src/main/java/com/whispertflite/asr/Recorder.java) PCM snapshots. |

## Engines (see also)

- [offline-asr-research.md](offline-asr-research.md) — shipped engines and research notes.
- [parakeet-onnxruntime.md](parakeet-onnxruntime.md) — ORT merge with Moonshine.

### Sherpa-onnx spike (dev-only)

Not part of the production engine list or `RecognitionService` routing. Debug builds can open [`SherpaOnnxSpikeActivity`](../app/src/main/java/com/whispertflite/sherpa/SherpaOnnxSpikeActivity.kt) from the main screen to trial k2-fsa sherpa-onnx streaming ASR after models are installed manually (see **Sherpa-onnx spike** in [offline-asr-research.md](offline-asr-research.md)). A future fourth engine would still go through RecognitionService-first integration above.
