package com.whispertflite.asr

import android.content.Context
import com.whispertflite.AsrEnginePreferences
import com.whispertflite.moonshine.MoonshineModelFiles
import com.whispertflite.parakeet.ParakeetModelFiles
import java.io.File

/**
 * "Preference **and** models present" — used where the app should pick an engine only when runnable
 * (e.g. [com.whispertflite.WhisperRecognitionService], overlay activity, IME routing).
 *
 * Surfaces that must honor **preference even when files are missing** (toast + download flow) should
 * branch on [com.whispertflite.AsrEnginePreferences.mainEngine] and validate separately
 * (e.g. MainActivity `ensureEngineModelsReady`).
 */
object OfflineAsrEngines {
    @JvmStatic
    fun moonshineSelectedAndReady(context: Context): Boolean {
        if (!AsrEnginePreferences.MOONSHINE.equals(AsrEnginePreferences.mainEngine(context))) {
            return false
        }
        return MoonshineModelFiles.allModelFilesPresent(context)
    }

    @JvmStatic
    fun parakeetSelectedAndReady(context: Context, modelsDir: File?): Boolean {
        if (modelsDir == null) return false
        if (!AsrEnginePreferences.PARAKEET.equals(AsrEnginePreferences.mainEngine(context))) {
            return false
        }
        return ParakeetModelFiles.allOnnxPresent(modelsDir)
    }
}
