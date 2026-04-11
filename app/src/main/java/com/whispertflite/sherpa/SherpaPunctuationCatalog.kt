package com.whispertflite.sherpa

import androidx.annotation.StringRes
import com.whispertflite.R

/** How k2-fsa punctuation is implemented in sherpa-onnx (see docs punctuation pretrained models). */
enum class SherpaPunctKind {
    /** No ONNX; rules in [SherpaPunctuationPostProcessor]. */
    HEURISTIC,

    /** [com.k2fsa.sherpa.onnx.OnlinePunctuation] CNN-BiLSTM + BPE (Edge-Punct style). */
    ONLINE_CNN_BILSTM,

    /** [com.k2fsa.sherpa.onnx.OfflinePunctuation] CT transformer (Chinese + English text). */
    OFFLINE_CT_ZH_EN,
}

data class SherpaPunctCatalogEntry(
    val id: String,
    @StringRes val labelRes: Int,
    val kind: SherpaPunctKind,
    /** GitHub release asset under `punctuation-models/` (null for heuristics). */
    val releaseTarBz2: String?,
    /** Top-level folder name inside the tarball (must match archive layout). */
    val extractFolderName: String?,
    @StringRes val notesRes: Int,
    /** Rough tarball size for download progress (0 = N/A). */
    val approxDownloadMb: Int,
) {
    companion object {
        @JvmField
        val ENTRIES: List<SherpaPunctCatalogEntry> = listOf(
            SherpaPunctCatalogEntry(
                id = "heuristics",
                labelRes = R.string.sherpa_punct_model_heuristics,
                kind = SherpaPunctKind.HEURISTIC,
                releaseTarBz2 = null,
                extractFolderName = null,
                notesRes = R.string.sherpa_punct_model_notes_heuristics,
                approxDownloadMb = 0,
            ),
            SherpaPunctCatalogEntry(
                id = "online-en-int8",
                labelRes = R.string.sherpa_punct_model_online_en_int8,
                kind = SherpaPunctKind.ONLINE_CNN_BILSTM,
                releaseTarBz2 = "sherpa-onnx-online-punct-en-2024-08-06.tar.bz2",
                extractFolderName = "sherpa-onnx-online-punct-en-2024-08-06",
                notesRes = R.string.sherpa_punct_model_notes_online_en,
                approxDownloadMb = 35,
            ),
            SherpaPunctCatalogEntry(
                id = "ct-zh-en-int8",
                labelRes = R.string.sherpa_punct_model_ct_zh_en_int8,
                kind = SherpaPunctKind.OFFLINE_CT_ZH_EN,
                releaseTarBz2 = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2",
                extractFolderName = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8",
                notesRes = R.string.sherpa_punct_model_notes_ct_zh_en,
                approxDownloadMb = 120,
            ),
        )

        @JvmStatic
        val defaultEntry: SherpaPunctCatalogEntry
            get() = ENTRIES.first()

        @JvmStatic
        fun byId(id: String): SherpaPunctCatalogEntry? = ENTRIES.find { it.id == id }

        @JvmStatic
        fun requireById(id: String): SherpaPunctCatalogEntry =
            byId(id) ?: defaultEntry
    }
}
