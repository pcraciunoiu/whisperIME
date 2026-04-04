package com.whispertflite.moonshine

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
 * Moonshine Base (English) POC: download models, toggle on-device ASR prefs, hold-to-dictate.
 */
class MoonshinePocActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParakeetPocBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: MoonshineHoldRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.setStatusBarAppearance(this)
        MoonshinePreferences.migrateFromParakeetKeys(this)
        binding = ActivityParakeetPocBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        binding.switchParakeetMain.isChecked = sp.getBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_MAIN, false)
        binding.switchParakeetIme.isChecked = sp.getBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_IME, false)
        binding.switchParakeetRecognition.isChecked =
            sp.getBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_RECOGNITION, false)

        binding.switchParakeetMain.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_MAIN, v).apply()
        }
        binding.switchParakeetIme.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_IME, v).apply()
        }
        binding.switchParakeetRecognition.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_RECOGNITION, v).apply()
        }

        binding.btnParakeetDownload.setOnClickListener {
            binding.parakeetDownloadProgress.visibility = View.VISIBLE
            binding.parakeetDownloadProgress.progress = 0
            MoonshineDownloader.downloadMoonshineBaseModels(
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
        val ok = MoonshineModelFiles.allModelFilesPresent(this)
        val arm = MoonshineModelFiles.isDeviceSupported(this)
        binding.parakeetModelStatus.text = when {
            !arm -> getString(R.string.moonshine_arm64_only)
            ok -> getString(R.string.moonshine_models_ready)
            else -> getString(R.string.moonshine_models_missing)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMic() {
        binding.btnParakeetMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!ensureAudioPermission()) return@setOnTouchListener true
                    if (!MoonshineModelFiles.isDeviceSupported(this)) {
                        Toast.makeText(this, R.string.moonshine_arm64_only, Toast.LENGTH_LONG).show()
                        return@setOnTouchListener true
                    }
                    if (!MoonshineModelFiles.allModelFilesPresent(this)) {
                        Toast.makeText(this, R.string.moonshine_models_missing, Toast.LENGTH_LONG).show()
                        return@setOnTouchListener true
                    }
                    HapticFeedback.vibrate(this)
                    binding.parakeetTranscript.text = ""
                    recorder = MoonshineHoldRecorder(
                        this,
                        mainHandler,
                    ) { partial -> binding.parakeetTranscript.text = partial }
                    val started = recorder!!.start()
                    if (!started) {
                        Toast.makeText(this, R.string.moonshine_start_failed, Toast.LENGTH_SHORT).show()
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
