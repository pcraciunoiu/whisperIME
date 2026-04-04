package com.whispertflite.moonshine

import android.content.Context
import androidx.preference.PreferenceManager

object MoonshinePreferences {
    const val KEY_USE_MOONSHINE_IME = "useMoonshineIme"
    const val KEY_USE_MOONSHINE_RECOGNITION = "useMoonshineRecognition"
    const val KEY_USE_MOONSHINE_MAIN = "useMoonshineMain"
    const val KEY_SETUP_WIZARD_MOONSHINE = "setupWizardMoonshine"

    /** Legacy Parakeet keys (migrated once). */
    private const val LEGACY_IME = "useParakeetIme"
    private const val LEGACY_REC = "useParakeetRecognition"
    private const val LEGACY_MAIN = "useParakeetMain"
    private const val LEGACY_WIZARD = "setupWizardParakeet"

    @JvmStatic
    fun migrateFromParakeetKeys(context: Context) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        if (sp.contains(KEY_USE_MOONSHINE_MAIN)) return
        val e = sp.edit()
        if (sp.contains(LEGACY_MAIN)) e.putBoolean(KEY_USE_MOONSHINE_MAIN, sp.getBoolean(LEGACY_MAIN, false))
        if (sp.contains(LEGACY_IME)) e.putBoolean(KEY_USE_MOONSHINE_IME, sp.getBoolean(LEGACY_IME, false))
        if (sp.contains(LEGACY_REC)) e.putBoolean(KEY_USE_MOONSHINE_RECOGNITION, sp.getBoolean(LEGACY_REC, false))
        if (sp.contains(LEGACY_WIZARD)) e.putBoolean(KEY_SETUP_WIZARD_MOONSHINE, sp.getBoolean(LEGACY_WIZARD, false))
        e.apply()
    }

    @JvmStatic
    fun useMoonshineIme(context: Context): Boolean {
        migrateFromParakeetKeys(context)
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_MOONSHINE_IME, false)
    }

    @JvmStatic
    fun useMoonshineRecognition(context: Context): Boolean {
        migrateFromParakeetKeys(context)
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_MOONSHINE_RECOGNITION, false)
    }

    @JvmStatic
    fun useMoonshineMain(context: Context): Boolean {
        migrateFromParakeetKeys(context)
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_MOONSHINE_MAIN, false)
    }
}
