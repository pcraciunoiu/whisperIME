package com.whispertflite.sherpa

import com.k2fsa.sherpa.onnx.getModelConfig
import java.io.File

object SherpaModelFiles {
    /** Subfolder of [android.content.Context.getExternalFilesDir]: `…/sherpa-onnx-models/`. */
    const val MODELS_SUBDIR = "sherpa-onnx-models"

    @JvmStatic
    fun modelsRoot(externalFilesDir: File?): File? {
        if (externalFilesDir == null) return null
        return File(externalFilesDir, MODELS_SUBDIR)
    }

    /**
     * True when every non-empty path in [getModelConfig] for this catalog entry exists under [modelsRoot].
     */
    @JvmStatic
    fun allFilesPresentForEntry(modelsRoot: File?, entry: SherpaCatalogEntry): Boolean {
        if (modelsRoot == null || !modelsRoot.isDirectory) return false
        val rel = getModelConfig(entry.sherpaConfigType) ?: return false
        return collectRelativeModelPaths(rel).all { File(modelsRoot, it).isFile }
    }

    @JvmStatic
    fun allFilesPresentForSelectedVariant(externalFilesDir: File?, context: android.content.Context): Boolean {
        val root = modelsRoot(externalFilesDir) ?: return false
        val id = SherpaPreferences.selectedCatalogId(context)
        val entry = SherpaCatalogEntry.requireById(id)
        return allFilesPresentForEntry(root, entry)
    }
}
