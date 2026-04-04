package com.whispertflite.parakeet

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.whispertflite.R
import com.whispertflite.databinding.ActivityParakeetPocBinding
import com.whispertflite.utils.HapticFeedback
import com.whispertflite.utils.ThemeUtils

/**
 * Parakeet ONNX streaming POC: download models, toggle IME / voice-input / main prefs, hold-to-dictate.
 * Manual host verification: install APK, download ~650MB encoder, hold mic, watch partial text.
 */
class ParakeetPocActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParakeetPocBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: ParakeetStreamingRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.setStatusBarAppearance(this)
        binding = ActivityParakeetPocBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        binding.switchParakeetMain.isChecked = sp.getBoolean(ParakeetPreferences.KEY_USE_PARAKEET_MAIN, false)
        binding.switchParakeetIme.isChecked = sp.getBoolean(ParakeetPreferences.KEY_USE_PARAKEET_IME, false)
        binding.switchParakeetRecognition.isChecked = sp.getBoolean(ParakeetPreferences.KEY_USE_PARAKEET_RECOGNITION, false)

        binding.switchParakeetMain.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(ParakeetPreferences.KEY_USE_PARAKEET_MAIN, v).apply()
        }
        binding.switchParakeetIme.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(ParakeetPreferences.KEY_USE_PARAKEET_IME, v).apply()
        }
        binding.switchParakeetRecognition.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(ParakeetPreferences.KEY_USE_PARAKEET_RECOGNITION, v).apply()
        }

        binding.btnParakeetDownload.setOnClickListener {
            binding.parakeetDownloadProgress.visibility = View.VISIBLE
            binding.parakeetDownloadProgress.progress = 0
            ParakeetDownloader.downloadParakeetModels(
                this,
                binding.parakeetDownloadProgress,
                null,
                Runnable { runOnUiThread { refreshModelStatus() } },
            )
        }

        refreshModelStatus()
        setupMic()
    }

    private fun refreshModelStatus() {
        val dir = getExternalFilesDir(null)
        val ok = ParakeetModelFiles.allOnnxPresent(dir)
        binding.parakeetModelStatus.text = if (ok) {
            getString(R.string.parakeet_models_ready)
        } else {
            getString(R.string.parakeet_models_missing)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMic() {
        binding.btnParakeetMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!ensureAudioPermission()) return@setOnTouchListener true
                    if (!ParakeetModelFiles.allOnnxPresent(getExternalFilesDir(null))) {
                        Toast.makeText(this, R.string.parakeet_models_missing, Toast.LENGTH_LONG).show()
                        return@setOnTouchListener true
                    }
                    HapticFeedback.vibrate(this)
                    binding.parakeetTranscript.text = ""
                    val dir = getExternalFilesDir(null)!!
                    recorder = ParakeetStreamingRecorder(
                        this,
                        dir,
                        mainHandler,
                    ) { partial ->
                        binding.parakeetTranscript.text = partial
                    }
                    val started = recorder!!.start()
                    if (!started) {
                        Toast.makeText(this, R.string.parakeet_start_failed, Toast.LENGTH_SHORT).show()
                        recorder = null
                    } else {
                        binding.btnParakeetMic.setBackgroundResource(R.drawable.rounded_button_background_pressed)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.btnParakeetMic.setBackgroundResource(R.drawable.rounded_button_background)
                    recorder?.let { r ->
                        val finalText = r.stop()
                        binding.parakeetTranscript.text = finalText
                    }
                    recorder = null
                }
            }
            true
        }
    }

    private fun ensureAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        return false
    }

    override fun onDestroy() {
        recorder?.stop(true)
        recorder = null
        super.onDestroy()
    }
}
