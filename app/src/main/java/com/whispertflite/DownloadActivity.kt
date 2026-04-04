package com.whispertflite

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.whispertflite.databinding.ActivityDownloadBinding
import com.whispertflite.moonshine.MoonshineConstants
import com.whispertflite.moonshine.MoonshineDownloader
import com.whispertflite.moonshine.MoonshineModelFiles
import com.whispertflite.moonshine.MoonshinePreferences
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
        MoonshinePreferences.migrateFromParakeetKeys(this)
        val moonshineWizard = sp.getBoolean(MoonshinePreferences.KEY_SETUP_WIZARD_MOONSHINE, false) ||
            sp.getBoolean("setupWizardParakeet", false)
        if (moonshineWizard) {
            binding?.radioMoonshine?.isChecked = true
        } else {
            binding?.radioWhisper?.isChecked = true
        }
        applyWizardDescription(moonshineWizard)

        binding?.modelChoiceGroup?.setOnCheckedChangeListener { _, checkedId ->
            val useMoonshine = checkedId == R.id.radio_moonshine
            sp.edit().putBoolean(MoonshinePreferences.KEY_SETUP_WIZARD_MOONSHINE, useMoonshine).apply()
            if (!useMoonshine) {
                sp.edit().remove("setupWizardParakeet").apply()
            }
            applyWizardDescription(useMoonshine)
        }
    }

    private fun applyWizardDescription(moonshine: Boolean) {
        binding?.downloadModelDesc?.setText(
            if (moonshine) R.string.download_model_text_moonshine else R.string.download_model_text,
        )
    }

    override fun onResume() {
        super.onResume()
        Downloader.copyAssetsToSdcard(this)
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        MoonshinePreferences.migrateFromParakeetKeys(this)
        val moonshineWizard = sp.getBoolean(MoonshinePreferences.KEY_SETUP_WIZARD_MOONSHINE, false) ||
            sp.getBoolean("setupWizardParakeet", false)
        if (moonshineWizard) {
            if (MoonshineModelFiles.allModelFilesPresent(this)) {
                sp.edit()
                    .putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_MAIN, true)
                    .putString("modelName", MoonshineConstants.MAIN_SCREEN_SPINNER_SENTINEL)
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
        val useMoonshine = binding?.radioMoonshine?.isChecked == true
        binding?.downloadSize?.visibility = View.VISIBLE
        binding?.downloadProgress?.visibility = View.VISIBLE
        binding?.buttonStart?.visibility = View.INVISIBLE
        if (useMoonshine) {
            binding?.buttonUpdate?.visibility = View.GONE
            MoonshineDownloader.downloadMoonshineBaseModels(
                this,
                binding?.downloadProgress,
                binding?.downloadSize,
                Runnable {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_MAIN, true)
                        .putString("modelName", MoonshineConstants.MAIN_SCREEN_SPINNER_SENTINEL)
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
