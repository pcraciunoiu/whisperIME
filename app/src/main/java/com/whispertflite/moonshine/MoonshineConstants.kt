package com.whispertflite.moonshine

/**
 * Useful Sensors **Moonshine Base** (~61M params) via [ai.moonshine:moonshine-voice].
 * Larger public tier is **base** (vs **tiny** ~27M). Models ship as merged ORT files inside the
 * upstream `android-examples` release tarball.
 *
 * @see <a href="https://github.com/moonshine-ai/moonshine">moonshine-ai/moonshine</a>
 */
object MoonshineConstants {
    const val SAMPLE_RATE = 16_000

    /** Mic capture cap for hold-to-talk (matches UI countdown). */
    const val MAX_RECORD_SECONDS = 60

    /** Must match the AAR / JNI build (see moonshine example MainActivity). */
    const val MODEL_ASSET_SUBDIR = "base-en"

    /**
     * Release tag aligned with [ai.moonshine:moonshine-voice] dependency version.
     * Tarball contains `Transcriber/.../assets/base-en/{encoder,decoder,tokenizer}`.
     */
    const val VOICE_LIB_VERSION = "0.0.51"
    const val ANDROID_EXAMPLES_TAR_URL =
        "https://github.com/moonshine-ai/moonshine/releases/download/v$VOICE_LIB_VERSION/android-examples.tar.gz"

    const val TAR_ENTRY_PREFIX = "Transcriber/app/src/main/assets/$MODEL_ASSET_SUBDIR/"

    /** Placeholder file in external storage spinner list (same legacy name as Parakeet path). */
    const val MAIN_SCREEN_SPINNER_SENTINEL = "parakeet.streaming.screen"
}
