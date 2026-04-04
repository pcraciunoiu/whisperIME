package com.whispertflite.parakeet

import android.content.Context
import androidx.preference.PreferenceManager

object ParakeetPreferences {
    const val KEY_USE_PARAKEET_IME = "useParakeetIme"
    const val KEY_USE_PARAKEET_RECOGNITION = "useParakeetRecognition"
    const val KEY_USE_PARAKEET_MAIN = "useParakeetMain"
    /** First-run download screen: user chose Parakeet path vs Whisper TFLite. */
    const val KEY_SETUP_WIZARD_PARAKEET = "setupWizardParakeet"

    @JvmStatic
    fun useParakeetIme(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_PARAKEET_IME, false)

    @JvmStatic
    fun useParakeetRecognition(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_PARAKEET_RECOGNITION, false)

    @JvmStatic
    fun useParakeetMain(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_USE_PARAKEET_MAIN, false)
}
