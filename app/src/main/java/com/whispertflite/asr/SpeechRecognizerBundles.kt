package com.whispertflite.asr

import android.os.Bundle
import android.speech.SpeechRecognizer

/** Builds [SpeechRecognizer.RESULTS_RECOGNITION] bundles for [android.speech.RecognitionService] callbacks. */
object SpeechRecognizerBundles {
    @JvmStatic
    fun resultsRecognitionSingle(text: String): Bundle {
        val list = arrayListOf(text)
        return Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list)
        }
    }
}
