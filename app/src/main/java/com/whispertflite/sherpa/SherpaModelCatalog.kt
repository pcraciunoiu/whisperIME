package com.whispertflite.sherpa

import androidx.annotation.StringRes
import com.whispertflite.R

/**
 * Built-in streaming transducers from [com.k2fsa.sherpa.onnx.getModelConfig] (sherpa-onnx Kotlin API).
 * Order: **English-only**, fast-first (small Zipformer / NeMo 80ms), then larger / higher-accuracy options.
 */
data class SherpaCatalogEntry(
    val id: String,
    @StringRes val labelRes: Int,
    /** Argument to [com.k2fsa.sherpa.onnx.getModelConfig]. */
    val sherpaConfigType: Int,
    val huggingFaceUrl: String,
    /** Rough download size for UI hints (MB). */
    val approxSizeMb: Int,
    /** 1 = fastest tier in this catalog. */
    val speedTier: Int,
    @StringRes val punctuationNotesRes: Int,
) {
    companion object {
        @JvmField
        val ENTRIES: List<SherpaCatalogEntry> = listOf(
            SherpaCatalogEntry(
                id = "zipformer-en-20m",
                labelRes = R.string.sherpa_model_zipformer_en_20m,
                sherpaConfigType = 10,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
                approxSizeMb = 180,
                speedTier = 1,
                punctuationNotesRes = R.string.sherpa_punct_notes_zipformer_en,
            ),
            SherpaCatalogEntry(
                id = "nemo-fast-conformer-en-80ms",
                labelRes = R.string.sherpa_model_nemo_fast_conformer_en_80ms,
                sherpaConfigType = 11,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-80ms",
                approxSizeMb = 90,
                speedTier = 1,
                punctuationNotesRes = R.string.sherpa_punct_notes_nemo_ctc_en,
            ),
            SherpaCatalogEntry(
                id = "zipformer-en-kroko-2025",
                labelRes = R.string.sherpa_model_zipformer_en_kroko_2025,
                sherpaConfigType = 21,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
                approxSizeMb = 380,
                speedTier = 2,
                punctuationNotesRes = R.string.sherpa_punct_notes_zipformer_en,
            ),
            SherpaCatalogEntry(
                id = "zipformer-en-202306",
                labelRes = R.string.sherpa_model_zipformer_en_202306,
                sherpaConfigType = 6,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
                approxSizeMb = 320,
                speedTier = 2,
                punctuationNotesRes = R.string.sherpa_punct_notes_zipformer_en,
            ),
            SherpaCatalogEntry(
                id = "nemo-fast-conformer-en-480ms",
                labelRes = R.string.sherpa_model_nemo_fast_conformer_en_480ms,
                sherpaConfigType = 12,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-480ms",
                approxSizeMb = 90,
                speedTier = 2,
                punctuationNotesRes = R.string.sherpa_punct_notes_nemo_ctc_en,
            ),
            SherpaCatalogEntry(
                id = "lstm-en",
                labelRes = R.string.sherpa_model_lstm_en,
                sherpaConfigType = 2,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-lstm-en-2023-02-17",
                approxSizeMb = 120,
                speedTier = 3,
                punctuationNotesRes = R.string.sherpa_punct_notes_lstm_en,
            ),
            SherpaCatalogEntry(
                id = "nemo-fast-conformer-en-1040ms",
                labelRes = R.string.sherpa_model_nemo_fast_conformer_en_1040ms,
                sherpaConfigType = 13,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-1040ms",
                approxSizeMb = 90,
                speedTier = 3,
                punctuationNotesRes = R.string.sherpa_punct_notes_nemo_ctc_en,
            ),
            SherpaCatalogEntry(
                id = "nemotron-speech-en-0.6b-int8",
                labelRes = R.string.sherpa_model_nemotron_speech_en_0_6b_int8,
                sherpaConfigType = 28,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14",
                approxSizeMb = 650,
                speedTier = 4,
                punctuationNotesRes = R.string.sherpa_punct_notes_nemotron_en,
            ),
        )

        @JvmStatic
        val defaultEntry: SherpaCatalogEntry
            get() = ENTRIES.first()

        @JvmStatic
        fun byId(id: String): SherpaCatalogEntry? = ENTRIES.find { it.id == id }

        @JvmStatic
        fun requireById(id: String): SherpaCatalogEntry =
            byId(id) ?: defaultEntry
    }
}
