package com.whispertflite

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.whispertflite.databinding.ActivityDownloadBinding
import android.content.SharedPreferences
import com.whispertflite.moonshine.MoonshineDownloader
import com.whispertflite.moonshine.MoonshineModelFiles
import com.whispertflite.moonshine.MoonshinePreferences
import com.whispertflite.parakeet.ParakeetDownloader
import com.whispertflite.parakeet.ParakeetEnginePool
import com.whispertflite.parakeet.ParakeetModelFiles
import com.whispertflite.parakeet.ParakeetPreferences
import com.whispertflite.sherpa.SherpaCatalogEntry
import com.whispertflite.sherpa.SherpaDownloader
import com.whispertflite.sherpa.SherpaModelFiles
import com.whispertflite.sherpa.SherpaPreferences
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

        val fromIntent = intent.getStringExtra(EXTRA_PREFERRED_ENGINE)
        val wizard = AsrEnginePreferences.setupWizardEngine(this)
            ?: legacyWizardEngine(sp)
        val initial = fromIntent ?: wizard ?: AsrEnginePreferences.WHISPER

        val engineAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.asr_engine_entries,
            android.R.layout.simple_spinner_item,
        )
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding?.spinnerWizardEngine?.adapter = engineAdapter
        setSpinnerSelectionForEngine(initial)
        AsrEnginePreferences.setSetupWizardEngine(this, initial)
        applyWizardDescription(initial)

        binding?.spinnerWizardEngine?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val values = resources.getStringArray(R.array.asr_engine_entry_values)
                val eng = values.getOrElse(position) { AsrEnginePreferences.WHISPER }
                AsrEnginePreferences.setSetupWizardEngine(this@DownloadActivity, eng)
                applyWizardDescription(eng)
                updateSherpaVariantUi(eng)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        bindSherpaVariantSpinner()
        updateSherpaVariantUi(initial)
    }

    private fun bindSherpaVariantSpinner() {
        val labels = SherpaCatalogEntry.ENTRIES.map { getString(it.labelRes) }
        val ad = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding?.spinnerSherpaVariant?.adapter = ad
        val cur = SherpaPreferences.selectedCatalogId(this)
        val idx = SherpaCatalogEntry.ENTRIES.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
        binding?.spinnerSherpaVariant?.setSelection(idx, false)
        binding?.spinnerSherpaVariant?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val idStr = SherpaCatalogEntry.ENTRIES.getOrNull(position)?.id ?: return
                SherpaPreferences.setSelectedCatalogId(this@DownloadActivity, idStr)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateSherpaVariantUi(engine: String) {
        val show = AsrEnginePreferences.SHERPA == engine
        val vis = if (show) View.VISIBLE else View.GONE
        binding?.labelSherpaVariant?.visibility = vis
        binding?.spinnerSherpaVariant?.visibility = vis
    }

    private fun legacyWizardEngine(sp: SharedPreferences): String? = when {
        sp.getBoolean(MoonshinePreferences.KEY_SETUP_WIZARD_MOONSHINE, false) -> AsrEnginePreferences.MOONSHINE
        sp.getBoolean(ParakeetPreferences.KEY_SETUP_WIZARD_PARAKEET, false) -> AsrEnginePreferences.PARAKEET
        sp.getBoolean("setupWizardParakeet", false) -> AsrEnginePreferences.PARAKEET
        else -> null
    }

    private fun setSpinnerSelectionForEngine(engine: String) {
        val values = resources.getStringArray(R.array.asr_engine_entry_values)
        val idx = values.indexOf(engine).takeIf { it >= 0 } ?: 0
        binding?.spinnerWizardEngine?.setSelection(idx, false)
    }

    private fun selectedEngine(): String {
        val pos = binding?.spinnerWizardEngine?.selectedItemPosition ?: 0
        val values = resources.getStringArray(R.array.asr_engine_entry_values)
        return values.getOrElse(pos) { AsrEnginePreferences.WHISPER }
    }

    private fun applyWizardDescription(engine: String) {
        binding?.downloadModelDesc?.setText(
            when (engine) {
                AsrEnginePreferences.PARAKEET -> R.string.download_model_text_parakeet
                AsrEnginePreferences.MOONSHINE -> R.string.download_model_text_moonshine
                AsrEnginePreferences.SHERPA -> R.string.download_model_text_sherpa
                else -> R.string.download_model_text
            },
        )
    }

    override fun onResume() {
        super.onResume()
        Downloader.copyAssetsToSdcard(this)
        MoonshinePreferences.migrateFromParakeetKeys(this)
        val wizard = AsrEnginePreferences.setupWizardEngine(this) ?: selectedEngine()

        when (wizard) {
            AsrEnginePreferences.MOONSHINE -> {
                if (MoonshineModelFiles.hasMoonshineBaseModelFilesOnDisk(this)) {
                    AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.MOONSHINE)
                    showReadyAndGoMain()
                }
            }
            AsrEnginePreferences.PARAKEET -> {
                val dir = getExternalFilesDir(null)
                if (dir != null && ParakeetModelFiles.allOnnxPresent(dir)) {
                    AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.PARAKEET)
                    showReadyAndGoMain()
                }
            }
            AsrEnginePreferences.SHERPA -> {
                val dir = getExternalFilesDir(null)
                if (dir != null && SherpaModelFiles.allFilesPresentForSelectedVariant(dir, this)) {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean(SherpaPreferences.KEY_USE_SHERPA_MAIN, true)
                        .apply()
                    AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.SHERPA)
                    showReadyAndGoMain()
                }
            }
            else -> {
                if (Downloader.checkModels(this)) {
                    AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.WHISPER)
                    binding?.downloadProgress?.progress = 100
                    binding?.downloadProgress?.visibility = View.VISIBLE
                    binding?.buttonStart?.visibility = View.VISIBLE
                    if (!Downloader.checkUpdate(this)) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    } else {
                        binding?.buttonUpdate?.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun showReadyAndGoMain() {
        binding?.downloadProgress?.progress = 100
        binding?.downloadProgress?.visibility = View.VISIBLE
        binding?.buttonStart?.visibility = View.VISIBLE
        binding?.buttonUpdate?.visibility = View.GONE
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Suppress("UNUSED_PARAMETER")
    fun download(view: View) {
        val engine = selectedEngine()
        binding?.downloadSize?.visibility = View.VISIBLE
        binding?.downloadProgress?.visibility = View.VISIBLE
        binding?.buttonStart?.visibility = View.INVISIBLE
        when (engine) {
            AsrEnginePreferences.MOONSHINE -> {
                binding?.buttonUpdate?.visibility = View.GONE
                MoonshineDownloader.downloadMoonshineBaseModels(
                    this,
                    binding?.downloadProgress,
                    binding?.downloadSize,
                    Runnable {
                        val sp = PreferenceManager.getDefaultSharedPreferences(this)
                        if (MoonshineModelFiles.hasMoonshineBaseModelFilesOnDisk(this)) {
                            AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.MOONSHINE)
                            sp.edit().putBoolean(MoonshinePreferences.KEY_USE_MOONSHINE_MAIN, true).apply()
                        }
                        binding?.downloadProgress?.progress = 100
                        binding?.buttonStart?.visibility = View.VISIBLE
                    },
                )
            }
            AsrEnginePreferences.SHERPA -> {
                binding?.buttonUpdate?.visibility = View.GONE
                SherpaDownloader.downloadSherpaModels(
                    this,
                    binding?.downloadProgress,
                    binding?.downloadSize,
                    Runnable {
                        val dir = getExternalFilesDir(null)
                        if (dir != null && SherpaModelFiles.allFilesPresentForSelectedVariant(dir, this)) {
                            PreferenceManager.getDefaultSharedPreferences(this).edit()
                                .putBoolean(SherpaPreferences.KEY_USE_SHERPA_MAIN, true)
                                .apply()
                            AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.SHERPA)
                        }
                        binding?.downloadProgress?.progress = 100
                        binding?.buttonStart?.visibility = View.VISIBLE
                    },
                )
            }
            AsrEnginePreferences.PARAKEET -> {
                binding?.buttonUpdate?.visibility = View.GONE
                ParakeetDownloader.downloadParakeetModels(
                    this,
                    binding?.downloadProgress,
                    binding?.downloadSize,
                    Runnable {
                        val dir = getExternalFilesDir(null)
                        if (dir != null && ParakeetModelFiles.allOnnxPresent(dir)) {
                            ParakeetEnginePool.invalidate()
                            AsrEnginePreferences.setMainEngine(this, AsrEnginePreferences.PARAKEET)
                            PreferenceManager.getDefaultSharedPreferences(this).edit()
                                .putBoolean(com.whispertflite.parakeet.ParakeetPreferences.KEY_USE_PARAKEET_MAIN, true)
                                .apply()
                            ParakeetEnginePool.warm(this, dir)
                        }
                        binding?.downloadProgress?.progress = 100
                        binding?.buttonStart?.visibility = View.VISIBLE
                    },
                )
            }
            else -> Downloader.downloadModels(this, binding!!)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun startMain(view: View) {
        AsrEnginePreferences.setMainEngine(this, selectedEngine())
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateModels(view: View) {
        binding?.downloadSize?.visibility = View.VISIBLE
        binding?.downloadProgress?.visibility = View.VISIBLE
        binding?.buttonStart?.visibility = View.INVISIBLE
        binding?.buttonUpdate?.visibility = View.GONE
        Downloader.deleteOldModels(this)
        Downloader.downloadModels(this, binding!!)
    }

    companion object {
        const val EXTRA_PREFERRED_ENGINE = "preferredEngine"
    }
}
