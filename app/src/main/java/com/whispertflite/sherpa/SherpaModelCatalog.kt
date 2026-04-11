package com.whispertflite.sherpa

import androidx.annotation.StringRes
import com.whispertflite.R

/**
 * Built-in English streaming transducers from [com.k2fsa.sherpa.onnx.getModelConfig] (sherpa-onnx Kotlin API).
 * Order: **fast-first** (smaller / fewer params first), then larger Zipformer, then LSTM baseline.
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
                id = "zipformer-en-202306",
                labelRes = R.string.sherpa_model_zipformer_en_202306,
                sherpaConfigType = 6,
                huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
                approxSizeMb = 320,
                speedTier = 2,
                punctuationNotesRes = R.string.sherpa_punct_notes_zipformer_en,
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
