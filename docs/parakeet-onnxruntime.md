# Parakeet + ONNX Runtime (diagnosis)

## Symptom

Parakeet (ONNX) stopped working reliably after **Moonshine** was added. Whisper (TFLite) was unaffected.

## Root cause (verified in build)

Both **`com.microsoft.onnxruntime:onnxruntime-android`** and **`ai.moonshine:moonshine-voice`** ship `lib/arm64-v8a/libonnxruntime.so` (and `armeabi-v7a`). Packaging used `pickFirst` for duplicate paths, but **merge order did not prefer Microsoft’s build**. The merged APK could contain Moonshine’s `libonnxruntime.so` while **`libonnxruntime4j_jni.so` came from a different ORT release** — JNI and core runtime were **mismatched**, breaking Parakeet’s `OrtSession` usage.

**Moonshine linkage:** `libmoonshine.so` imports `OrtGetApiBase@VERS_1.23.0` (GNU symbol version), verified with `readelf -Ws libmoonshine.so`. Microsoft’s `libonnxruntime.so` **1.23.1** exports `OrtGetApiBase@@VERS_1.23.1`, so the dynamic linker **does not** satisfy Moonshine’s `VERS_1.23.0` dependency → `UnsatisfiedLinkError: cannot locate symbol OrtGetApiBase`. Pin **`onnxruntime-android:1.23.0`** for both `implementation` and `ortJniUnpack` (same patch line as Parakeet JNI). Bump **both** coordinates together when upgrading.

Verify after a build: merged `lib/arm64-v8a/libonnxruntime.so` should match the **same** resolved `onnxruntime-android` AAR as `libonnxruntime4j_jni.so`.

## Fix

[`app/build.gradle`](../app/build.gradle):

1. **`ortJniUnpack`** configuration resolving only `onnxruntime-android`.
2. **`unpackMicrosoftOrtJni`** copies `jni/**` from that AAR into `build/microsoftOrtJni/`.
3. **`sourceSets.main.jniLibs`** includes `build/microsoftOrtJni/jni` so Microsoft’s `libonnxruntime.so` is packaged with **app-controlled** precedence over the duplicate inside the Moonshine AAR.

After a debug build, merged `lib/arm64-v8a/libonnxruntime.so` must match the Microsoft AAR referenced in Gradle (same version as `implementation` / `ortJniUnpack`).

**Sherpa-ONNX:** `libsherpa-onnx-jni.so` from the k2-fsa AAR expects a **different** ONNX Runtime build than Moonshine’s `OrtGetApiBase@VERS_1.23.0` line. Overwriting the single `libonnxruntime.so` with Sherpa’s copy fixed Sherpa but broke Moonshine/Parakeet. The build now ships **Microsoft’s** `libonnxruntime.so` (unchanged) **plus** Sherpa’s runtime as `libonnxruntime_sherpa.so`, with **`prepareSherpaJniWithRenamedOrt`** patching the Sherpa JNI `.so` (`patchelf --replace-needed`) to load the renamed library.

## Secondary fix

[`ParakeetStreamingRecorder`](../app/src/main/java/com/whispertflite/parakeet/ParakeetStreamingRecorder.kt) sets `engine = eng` immediately after successful `ParakeetStreamingEngine` construction so `stop()` can snapshot/close if the user releases during `AudioRecord` setup (avoids leaked sessions and empty finals).

## Retest

- [ ] Parakeet: main screen + RecognitionService, short and long holds.
- [ ] Moonshine: still works end-to-end (same `libonnxruntime.so` as the packaged Microsoft ORT version, aligned with moonshine-voice).

## No text inserted (crash fixed, but nothing appears)

1. **Filter logcat** (examples):
   - `adb logcat -s ParakeetASR:I WhisperInputMethodService:I WhisperRecognitionService:I`
   - **ParakeetASR**: `start()`, mic on, first partial, `stop()` summary.
   - **WhisperRecognitionService**: chosen engine on `onStartListening`, `onStopListening` final length for Moonshine/Parakeet.
   - **WhisperInputMethodService**: `commitHoldTranscription` (committed length, composing finish, or empty).
2. **Main screen + “Transcribe live”:** If partials never updated the text box but the final transcript exists at finger-up, the UI now applies that final string when the field is still empty.
3. **IME:** `commitHoldTranscription` needs a non-null `InputConnection` and either a non-empty final string or an active composing span from live partials. If the host field loses focus before release, connection is null — keep focus in the same field.
4. **Very short holds:** Less than one full streaming chunk (~1.12s at 16 kHz) can yield weak or empty decoding; try holding longer.

## References

- Offline ASR overview: [offline-asr-research.md](offline-asr-research.md)
