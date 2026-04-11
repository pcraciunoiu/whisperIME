package com.whispertflite.sherpa

import android.app.Activity
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import com.k2fsa.sherpa.onnx.getModelConfig
import com.whispertflite.utils.HttpFileDownloader
import java.io.File
import java.net.URLEncoder

/**
 * Downloads ONNX/token files for the selected [SherpaCatalogEntry] from Hugging Face
 * (`/resolve/main/<path>`), mirroring [com.whispertflite.parakeet.ParakeetDownloader].
 */
object SherpaDownloader {
    private const val TAG = "SherpaDownloader"

    @JvmStatic
    fun downloadSherpaModels(
        activity: Activity,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        onDone: Runnable,
    ) {
        val external = activity.getExternalFilesDir(null) ?: run {
            activity.runOnUiThread { onDone.run() }
            return
        }
        val modelsRoot = SherpaModelFiles.modelsRoot(external) ?: run {
            activity.runOnUiThread { onDone.run() }
            return
        }
        modelsRoot.mkdirs()
        val entry = SherpaCatalogEntry.requireById(SherpaPreferences.selectedCatalogId(activity))
        val cfg = getModelConfig(entry.sherpaConfigType)
        if (cfg == null) {
            Log.e(TAG, "getModelConfig(${entry.sherpaConfigType}) null")
            activity.runOnUiThread { onDone.run() }
            return
        }
        val paths = collectRelativeModelPaths(cfg)
        if (paths.isEmpty()) {
            Log.e(TAG, "no paths in model config")
            activity.runOnUiThread { onDone.run() }
            return
        }
        val totalEst = entry.approxSizeMb.coerceAtLeast(1) * 1_000_000L
        Thread {
            try {
                var done = 0L
                val base = entry.huggingFaceUrl.trim().trimEnd('/')
                for (relPath in paths) {
                    val dest = File(modelsRoot, relPath)
                    if (dest.isFile && dest.length() > 512L) {
                        continue
                    }
                    if (dest.isFile) {
                        dest.delete()
                    }
                    val url = huggingFaceResolveMainUrl(base, relPath)
                    Log.i(TAG, "GET $url")
                    try {
                        HttpFileDownloader.downloadFileToFile(url, dest, { n ->
                            done += n
                            activity.runOnUiThread {
                                sizeView?.text = "${done / 1024 / 1024} MB"
                                progressBar?.progress = ((done * 100L) / totalEst).toInt().coerceIn(0, 99)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "failed $relPath", e)
                        if (dest.exists()) dest.delete()
                        throw e
                    }
                }
                activity.runOnUiThread {
                    progressBar?.progress = 100
                    onDone.run()
                }
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                activity.runOnUiThread { onDone.run() }
            }
        }.start()
    }

    /** `https://huggingface.co/org/repo/resolve/main/relative/path` with per-segment encoding. */
    @JvmStatic
    fun huggingFaceResolveMainUrl(repoBase: String, relativePath: String): String {
        val enc = relativePath.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "${repoBase.trim().trimEnd('/')}/resolve/main/$enc"
    }
}
