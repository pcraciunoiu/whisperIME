package com.whispertflite

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.whispertflite.databinding.ActivityDownloadBinding
import com.whispertflite.parakeet.ParakeetConstants
import com.whispertflite.parakeet.ParakeetDownloader
import com.whispertflite.parakeet.ParakeetModelFiles
import com.whispertflite.parakeet.ParakeetPreferences
import com.whispertflite.utils.Downloader
import com.whispertflite.utils.ThemeUtils

class DownloadActivity : AppCompatActivity() {
    private var binding: ActivityDownloadBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding!!.root)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        ThemeUtils.setStatusBarAppearance(this)

        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val parakeetWizard = sp.getBoolean(ParakeetPreferences.KEY_SETUP_WIZARD_PARAKEET, false)
        if (parakeetWizard) {
            binding?.radioParakeet?.isChecked = true
        } else {
            binding?.radioWhisper?.isChecked = true
        }
        applyWizardDescription(parakeetWizard)

        binding?.modelChoiceGroup?.setOnCheckedChangeListener { _, checkedId ->
            val useParakeet = checkedId == R.id.radio_parakeet
            sp.edit().putBoolean(ParakeetPreferences.KEY_SETUP_WIZARD_PARAKEET, useParakeet).apply()
            applyWizardDescription(useParakeet)
        }
    }

    private fun applyWizardDescription(parakeet: Boolean) {
        binding?.downloadModelDesc?.setText(
            if (parakeet) R.string.download_model_text_parakeet else R.string.download_model_text,
        )
    }

    override fun onResume() {
        super.onResume()
        Downloader.copyAssetsToSdcard(this)
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val parakeetWizard = sp.getBoolean(ParakeetPreferences.KEY_SETUP_WIZARD_PARAKEET, false)
        if (parakeetWizard) {
            val dir = getExternalFilesDir(null)
            if (ParakeetModelFiles.allOnnxPresent(dir)) {
                sp.edit()
                    .putBoolean(ParakeetPreferences.KEY_USE_PARAKEET_MAIN, true)
                    .putString("modelName", ParakeetConstants.MAIN_SCREEN_SPINNER_SENTINEL)
                    .apply()
                binding?.downloadProgress?.progress = 100
                binding?.downloadProgress?.visibility = View.VISIBLE
                binding?.buttonStart?.visibility = View.VISIBLE
                binding?.buttonUpdate?.visibility = View.GONE
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        } else {
            if (Downloader.checkModels(this)) {
                binding?.downloadProgress?.progress = 100
                binding?.downloadProgress?.visibility = View.VISIBLE
                binding?.buttonStart?.visibility = View.VISIBLE
                if (!Downloader.checkUpdate(this)) {
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    binding?.buttonUpdate?.visibility = View.VISIBLE
                }
            }
        }
    }

    fun download(view: View) {
        val useParakeet = binding?.radioParakeet?.isChecked == true
        binding?.downloadSize?.visibility = View.VISIBLE
        binding?.downloadProgress?.visibility = View.VISIBLE
        binding?.buttonStart?.visibility = View.INVISIBLE
        if (useParakeet) {
            binding?.buttonUpdate?.visibility = View.GONE
            ParakeetDownloader.downloadParakeetModels(
                this,
                binding?.downloadProgress,
                binding?.downloadSize,
                Runnable {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean(ParakeetPreferences.KEY_USE_PARAKEET_MAIN, true)
                        .putString("modelName", ParakeetConstants.MAIN_SCREEN_SPINNER_SENTINEL)
                        .apply()
                    binding?.downloadProgress?.progress = 100
                    binding?.buttonStart?.visibility = View.VISIBLE
                },
            )
        } else {
            Downloader.downloadModels(this, binding!!)
        }
    }

    fun startMain(view: View) {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    fun updateModels(view: View) {
        binding?.downloadSize?.visibility = View.VISIBLE
        binding?.downloadProgress?.visibility = View.VISIBLE
        binding?.buttonStart?.visibility = View.INVISIBLE
        binding?.buttonUpdate?.visibility = View.GONE
        Downloader.deleteOldModels(this)
        Downloader.downloadModels(this, binding!!)
    }
}
