package com.whispertflite.parakeet

/**
 * Offline NVIDIA Parakeet TDT 0.6B v2 (English) via sherpa-onnx (k2-fsa).
 * Model card: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2
 * sherpa-onnx package: sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
 *
 * Replaces the earlier hand-rolled streaming ONNX port: sherpa-onnx owns the (correct, maintained)
 * feature front-end, encoder/decoder/joiner, RNNT decoding and tokenization, fully offline.
 */
object ParakeetConstants {
    const val SAMPLE_RATE = 16_000
    const val FEATURE_DIM = 80
    const val MODEL_TYPE = "nemo_transducer"

    /** Hold-to-talk cap; mirrors the other engines. */
    const val MAX_RECORD_SECONDS = 60

    /** Directory (under the app's external files dir) that holds the sherpa-onnx model files. */
    const val MODEL_DIR = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"

    const val ENCODER_FILE = "$MODEL_DIR/encoder.int8.onnx"
    const val DECODER_FILE = "$MODEL_DIR/decoder.int8.onnx"
    const val JOINER_FILE = "$MODEL_DIR/joiner.int8.onnx"
    const val TOKENS_FILE = "$MODEL_DIR/tokens.txt"

    /**
     * Marker written only after all model files have been fully downloaded and size-verified.
     * Presence of this file is the single source of truth that the model is usable.
     *
     * Why this matters: sherpa-onnx's native `OfflineRecognizer` loader aborts the whole process
     * (uncatchable SIGABRT) when handed a truncated/corrupt .onnx ("Protobuf parsing failed").
     * Gating model load on this marker guarantees we never feed it a partial download.
     */
    const val READY_MARKER_FILE = "$MODEL_DIR/.download_complete"

    /**
     * Individual files are downloaded directly from the Hugging Face mirror (uncompressed) rather
     * than the GitHub .tar.bz2: this avoids minutes-long on-device bzip2 decompression of the
     * ~622 MB encoder and lets us verify each file's size atomically.
     */
    private const val MODEL_BASE_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"

    /** Files to download: relative path under [MODEL_DIR] -> (download URL, exact expected byte size). */
    val MODEL_FILES: List<ModelFile> = listOf(
        ModelFile("encoder.int8.onnx", "$MODEL_BASE_URL/encoder.int8.onnx", 652_184_296L),
        ModelFile("decoder.int8.onnx", "$MODEL_BASE_URL/decoder.int8.onnx", 7_257_753L),
        ModelFile("joiner.int8.onnx", "$MODEL_BASE_URL/joiner.int8.onnx", 1_739_080L),
        ModelFile("tokens.txt", "$MODEL_BASE_URL/tokens.txt", 9_384L),
    )

    /** Total download size across [MODEL_FILES], for progress reporting. */
    val MODEL_TOTAL_BYTES: Long = MODEL_FILES.sumOf { it.sizeBytes }

    data class ModelFile(val name: String, val url: String, val sizeBytes: Long)

    /** Stored under [com.whispertflite.asr.WhisperModelSelection.PREFS_KEY_MAIN_SCREEN] when the main
     *  screen uses Parakeet (no .tflite file). Must contain "parakeet.streaming" (MainActivity checks). */
    const val MAIN_SCREEN_SPINNER_SENTINEL = "parakeet.streaming.screen"
}
