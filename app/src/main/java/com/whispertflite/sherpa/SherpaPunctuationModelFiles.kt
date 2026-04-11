package com.whispertflite.sherpa

import android.content.Context
import java.io.File

/** On-disk layout under [Context.getExternalFilesDir]: `sherpa-punct-models/&lt;extractFolderName&gt;/`. */
object SherpaPunctuationModelFiles {
    const val MODELS_SUBDIR = "sherpa-punct-models"

    @JvmStatic
    fun modelsRoot(externalFilesDir: File?): File? =
        externalFilesDir?.let { File(it, MODELS_SUBDIR) }

    @JvmStatic
    fun modelDir(root: File?, entry: SherpaPunctCatalogEntry): File? {
        val folder = entry.extractFolderName ?: return null
        return root?.let { File(it, folder) }
    }

    @JvmStatic
    fun allFilesPresentForEntry(root: File?, entry: SherpaPunctCatalogEntry): Boolean {
        if (entry.kind == SherpaPunctKind.HEURISTIC) return true
        val dir = modelDir(root, entry) ?: return false
        return when (entry.kind) {
            SherpaPunctKind.ONLINE_CNN_BILSTM -> {
                val onnx = File(dir, "model.int8.onnx")
                val vocab = File(dir, "bpe.vocab")
                onnx.isFile && onnx.length() > 512L && vocab.isFile && vocab.length() > 32L
            }
            SherpaPunctKind.OFFLINE_CT_ZH_EN -> {
                val onnx = File(dir, "model.int8.onnx")
                onnx.isFile && onnx.length() > 512L
            }
            SherpaPunctKind.HEURISTIC -> true
        }
    }
}
