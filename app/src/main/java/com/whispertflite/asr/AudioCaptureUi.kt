package com.whispertflite.asr

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.Spinner
import com.whispertflite.R

/** Binds mic source spinner and optional DSP toggles to [AudioCapturePreferences]. */
object AudioCaptureUi {
    @JvmStatic
    fun bindSpinnerAndToggles(
        context: Context,
        sp: SharedPreferences,
        spinner: Spinner,
        platformNs: CheckBox,
        platformAec: CheckBox,
        rnnoise: CheckBox,
    ) {
        val values = context.resources.getStringArray(R.array.audio_capture_source_values)
        val current =
            sp.getString(AudioCapturePreferences.KEY_AUDIO_SOURCE, AudioCapturePreferences.SOURCE_VOICE_RECOGNITION)
                ?: AudioCapturePreferences.SOURCE_VOICE_RECOGNITION
        var sel = 0
        for (i in values.indices) {
            if (values[i] == current) {
                sel = i
                break
            }
        }
        spinner.setSelection(sel, false)
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    sp.edit().putString(AudioCapturePreferences.KEY_AUDIO_SOURCE, values[position]).apply()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        platformNs.isChecked = AudioCapturePreferences.platformNoiseSuppressorEnabled(context)
        platformNs.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(AudioCapturePreferences.KEY_PLATFORM_NOISE_SUPPRESSOR, v).apply()
        }
        platformAec.isChecked = AudioCapturePreferences.platformAecEnabled(context)
        platformAec.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(AudioCapturePreferences.KEY_PLATFORM_AEC, v).apply()
        }
        rnnoise.isChecked = AudioCapturePreferences.rnnoiseDenoiseEnabled(context)
        rnnoise.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(AudioCapturePreferences.KEY_RNNOISE_DENOISE, v).apply()
        }
    }
}
