package com.whispertflite.sherpa

import java.io.File

/**
 * Debug spike: on-disk layout for English streaming Zipformer (`getModelConfig(6)`).
 *
 * Place the Hugging Face repo contents under [modelsRoot]/[MODEL_DIR_NAME] (see docs/offline-asr-research.md).
 */
object SherpaOnnxSpikePaths {
    const val MODEL_DIR_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-26"

    /** Subfolder of [android.content.Context.getExternalFilesDir] */
    const val MODELS_SUBDIR = SherpaModelFiles.MODELS_SUBDIR

    @JvmStatic
    fun modelDirectory(externalFilesDir: File?): File? {
        if (externalFilesDir == null) return null
        return File(File(externalFilesDir, MODELS_SUBDIR), MODEL_DIR_NAME)
    }

    /** All ONNX/token files present for the spike model (same check as catalog type 6). */
    @JvmStatic
    fun isModelPresent(externalFilesDir: File?): Boolean {
        val root = SherpaModelFiles.modelsRoot(externalFilesDir) ?: return false
        val entry = SherpaCatalogEntry.ENTRIES.find { it.sherpaConfigType == 6 } ?: return false
        return SherpaModelFiles.allFilesPresentForEntry(root, entry)
    }
}
