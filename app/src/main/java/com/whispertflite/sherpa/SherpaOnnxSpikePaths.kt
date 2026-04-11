package com.whispertflite.sherpa

import java.io.File

/**
 * Spike-only: on-disk layout for one English streaming Zipformer model (sherpa-onnx `getModelConfig(6)`).
 *
 * Place the Hugging Face repo contents under [modelsRoot]/[MODEL_DIR_NAME] (see docs/offline-asr-research.md).
 */
object SherpaOnnxSpikePaths {
    const val MODEL_DIR_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-26"

    /** Subfolder of [android.content.Context.getExternalFilesDir] */
    const val MODELS_SUBDIR = "sherpa-onnx-models"

    private val requiredFiles = listOf(
        "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
        "joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
        "tokens.txt",
    )

    @JvmStatic
    fun modelDirectory(externalFilesDir: File?): File? {
        if (externalFilesDir == null) return null
        return File(File(externalFilesDir, MODELS_SUBDIR), MODEL_DIR_NAME)
    }

    /** All ONNX/token files present for the spike model. */
    @JvmStatic
    fun isModelPresent(externalFilesDir: File?): Boolean {
        val dir = modelDirectory(externalFilesDir) ?: return false
        if (!dir.isDirectory) return false
        return requiredFiles.all { File(dir, it).isFile }
    }
}
