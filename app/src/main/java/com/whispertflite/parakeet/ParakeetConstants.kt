package com.whispertflite.parakeet

/**
 * Multitalker Parakeet streaming int8 ONNX (English, single-speaker masks).
 * See https://huggingface.co/smcleod/multitalker-parakeet-streaming-0.6b-v1-onnx-int8
 */
object ParakeetConstants {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_PCM_SAMPLES = 17_920
    const val MEL_BINS = 128
    const val MEL_TIME = 112
    /** Matches parakeet-rs `PRE_ENCODE_CACHE`: prefix mel frames before each streaming chunk. */
    const val PRE_ENCODE_MEL_FRAMES = 9
    /** Encoder `processed_signal` width = context + chunk (121). */
    const val MEL_ENCODER_TIME = PRE_ENCODE_MEL_FRAMES + MEL_TIME
    const val N_FFT = 512
    const val HOP_LENGTH = 160
    const val BLANK_ID = 1024
    const val VOCAB_SIZE = 1024

    const val ENCODER_FILE = "parakeet_encoder.int8.onnx"
    const val DECODER_FILE = "parakeet_decoder_joint.int8.onnx"

    const val HF_BASE =
        "https://huggingface.co/smcleod/multitalker-parakeet-streaming-0.6b-v1-onnx-int8/resolve/main/"
    const val ENCODER_URL = HF_BASE + "encoder.int8.onnx"
    const val DECODER_URL = HF_BASE + "decoder_joint.int8.onnx"

    const val ASSETS_MEL_BASIS = "parakeet/mel_basis.bin"
    const val ASSETS_HANN = "parakeet/hann512.bin"
    const val ASSETS_VOCAB = "parakeet/parakeet_vocab.tsv"

    /** Stored in SharedPreferences "modelName" when the main screen uses Parakeet (no .tflite file). */
    const val MAIN_SCREEN_SPINNER_SENTINEL = "parakeet.streaming.screen"
}
