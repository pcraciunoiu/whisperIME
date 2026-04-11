package com.whispertflite.sherpa

import android.content.Context
import androidx.preference.PreferenceManager

object SherpaPreferences {
    const val KEY_USE_SHERPA_MAIN = "useSherpaMain"
    const val KEY_SHERPA_VARIANT = "sherpaCatalogVariant"
    /** Offline final-text punctuation / casing polish (rule-based; see docs). */
    const val KEY_PUNCT_ENHANCE = "sherpaPunctEnhanceFinal"

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
}
