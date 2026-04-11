package com.whispertflite

import android.content.Context
import androidx.preference.PreferenceManager
import com.whispertflite.moonshine.MoonshinePreferences
import com.whispertflite.parakeet.ParakeetPreferences
import com.whispertflite.sherpa.SherpaPreferences

/**
 * Single main-screen / IME / recognition ASR backend: Whisper (TFLite), Parakeet (ONNX), Moonshine Base, or Sherpa-ONNX.
 */
object AsrEnginePreferences {
    const val WHISPER = "whisper"
    const val PARAKEET = "parakeet"
    const val MOONSHINE = "moonshine"
    const val SHERPA = "sherpa"

    const val KEY_MAIN_ENGINE = "asrMainEngine"
    const val KEY_SETUP_WIZARD_ENGINE = "setupWizardAsrEngine"

    @JvmStatic
    fun mainEngine(context: Context): String {
        MoonshinePreferences.migrateFromParakeetKeys(context)
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val explicit = sp.getString(KEY_MAIN_ENGINE, null)
        if (explicit != null) return explicit
        return when {
            MoonshinePreferences.useMoonshineMain(context) -> MOONSHINE
            ParakeetPreferences.useParakeetMain(context) -> PARAKEET
            SherpaPreferences.useSherpaMain(context) -> SHERPA
            else -> WHISPER
        }
    }

    @JvmStatic
    fun setMainEngine(context: Context, engine: String) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit()
            .putString(KEY_MAIN_ENGINE, engine)
            .putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_MAIN, engine == MOONSHINE)
            .putBoolean(ParakeetPreferences.KEY_USE_PARAKEET_MAIN, engine == PARAKEET)
            .putBoolean(SherpaPreferences.KEY_USE_SHERPA_MAIN, engine == SHERPA)
            .apply()
    }

    @JvmStatic
    fun imeUsesEngine(context: Context, engine: String): Boolean = mainEngine(context) == engine

    @JvmStatic
    fun setupWizardEngine(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_SETUP_WIZARD_ENGINE, null)

    @JvmStatic
    fun setSetupWizardEngine(context: Context, engine: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_SETUP_WIZARD_ENGINE, engine)
            .apply()
    }
}
