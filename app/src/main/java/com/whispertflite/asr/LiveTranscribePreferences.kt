package com.whispertflite.asr

import android.content.SharedPreferences

/**
 * User toggle for on-device **live partials** while holding the mic (Whisper throttled snapshots,
 * Parakeet/Moonshine streaming). Same preference across main screen, IME, and [com.whispertflite.WhisperRecognitionService].
 */
object LiveTranscribePreferences {
    const val PREFS_KEY = "liveTranscribePartials"

    @JvmStatic
    fun isEnabled(sp: SharedPreferences): Boolean = sp.getBoolean(PREFS_KEY, false)
}
