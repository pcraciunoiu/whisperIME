package com.whispertflite.asr

import android.content.Context
import android.media.MediaRecorder
import androidx.preference.PreferenceManager

/**
 * Shared mic capture options for [Recorder], [com.whispertflite.moonshine.MoonshineHoldRecorder],
 * and [com.whispertflite.parakeet.ParakeetStreamingRecorder].
 */
object AudioCapturePreferences {
    const val KEY_AUDIO_SOURCE = "audioCaptureSource"
    const val KEY_PLATFORM_NOISE_SUPPRESSOR = "audioPlatformNoiseSuppressor"
    const val KEY_PLATFORM_AEC = "audioPlatformAec"
    const val KEY_RNNOISE_DENOISE = "audioRnnoiseDenoise"

    /** Stored value: [SOURCE_VOICE_RECOGNITION] or [SOURCE_VOICE_COMMUNICATION]. */
    const val SOURCE_VOICE_RECOGNITION = "voice_recognition"
    const val SOURCE_VOICE_COMMUNICATION = "voice_communication"

    @JvmStatic
    fun audioSource(context: Context): Int {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return when (sp.getString(KEY_AUDIO_SOURCE, SOURCE_VOICE_RECOGNITION)) {
            SOURCE_VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    @JvmStatic
    fun platformNoiseSuppressorEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_PLATFORM_NOISE_SUPPRESSOR, false)

    @JvmStatic
    fun platformAecEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_PLATFORM_AEC, false)

    @JvmStatic
    fun rnnoiseDenoiseEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_RNNOISE_DENOISE, false)

    // Future: optional streaming ONNX speech enhancement (DeepFilter-class) using the same
    // onnxruntime-android pin as Parakeet/Moonshine — evaluate after RNNoise + platform DSP.
}
