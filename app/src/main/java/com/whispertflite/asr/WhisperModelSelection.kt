package com.whispertflite.asr

import android.content.SharedPreferences
import java.io.File

/**
 * Where the selected Whisper `.tflite` filename is stored. RecognitionService uses its own key so
 * the system voice-input screen can pick a model independently of the standalone app ([com.whispertflite.MainActivity]).
 */
object WhisperModelSelection {
    const val PREFS_KEY_MAIN_SCREEN = "modelName"
    const val PREFS_KEY_RECOGNITION_SERVICE = "recognitionServiceModelName"

    @JvmStatic
    fun tfliteFileForMainScreen(dataDir: File, sp: SharedPreferences, defaultFileName: String): File {
        return File(dataDir, sp.getString(PREFS_KEY_MAIN_SCREEN, defaultFileName)!!)
    }

    @JvmStatic
    fun tfliteFileForRecognitionService(dataDir: File, sp: SharedPreferences, defaultFileName: String): File {
        return File(dataDir, sp.getString(PREFS_KEY_RECOGNITION_SERVICE, defaultFileName)!!)
    }

    @JvmStatic
    fun mainScreenModelBasename(sp: SharedPreferences, defaultFileName: String): String {
        return sp.getString(PREFS_KEY_MAIN_SCREEN, defaultFileName)!!
    }
}
