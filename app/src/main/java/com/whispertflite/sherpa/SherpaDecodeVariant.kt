package com.whispertflite.sherpa

/**
 * Future hook when adding **non-transducer** Sherpa checkpoints (e.g. Paraformer, CTC-only) that need
 * a different mic/decode loop (tail padding, etc.). All current catalog entries use
 * [TRANSDUCER_STREAMING] with [SherpaStreamingRecorder].
 */
enum class SherpaDecodeVariant {
    TRANSDUCER_STREAMING,
    // PARAFORMER_STREAMING,
    // ZIPFORMER2_CTC,
}
