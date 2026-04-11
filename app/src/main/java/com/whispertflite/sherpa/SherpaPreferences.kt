package com.whispertflite.sherpa

import android.content.Context
import androidx.preference.PreferenceManager

object SherpaPreferences {
    const val KEY_USE_SHERPA_MAIN = "useSherpaMain"
    const val KEY_SHERPA_VARIANT = "sherpaCatalogVariant"
    /** Offline final-text punctuation / casing polish (neural or rule-based; see [SherpaPunctCatalogEntry]). */
    const val KEY_PUNCT_ENHANCE = "sherpaPunctEnhanceFinal"
    const val KEY_PUNCT_MODEL = "sherpaPunctModelId"

    @JvmStatic
    fun useSherpaMain(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_SHERPA_MAIN, false)

    @JvmStatic
    fun selectedCatalogId(context: Context): String {
        val def = SherpaCatalogEntry.defaultEntry.id
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_SHERPA_VARIANT, def)
            ?: def
    }

    @JvmStatic
    fun setSelectedCatalogId(context: Context, id: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_SHERPA_VARIANT, id)
            .apply()
    }

    @JvmStatic
    fun isPunctuationEnhanceEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_PUNCT_ENHANCE, true)

    @JvmStatic
    fun setPunctuationEnhanceEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_PUNCT_ENHANCE, enabled)
            .apply()
    }

    @JvmStatic
    fun selectedPunctModelId(context: Context): String {
        val def = SherpaPunctCatalogEntry.defaultEntry.id
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_PUNCT_MODEL, def)
            ?: def
    }

    @JvmStatic
    fun setSelectedPunctModelId(context: Context, id: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_PUNCT_MODEL, id)
            .apply()
    }
}
