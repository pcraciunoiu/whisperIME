package com.whispertflite.sherpa

import android.app.Activity
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import com.whispertflite.utils.HttpFileDownloader
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Downloads k2-fsa [punctuation-models](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html)
 * tar.bz2 releases and extracts under [SherpaPunctuationModelFiles.modelsRoot].
 */
object SherpaPunctuationDownloader {
    private const val TAG = "SherpaPunctDownload"
    private const val RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/"

    @JvmStatic
    fun downloadPunctModelIfNeeded(
        activity: Activity,
        entry: SherpaPunctCatalogEntry,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        onDone: Runnable,
    ) {
        val external = activity.getExternalFilesDir(null) ?: run {
            activity.runOnUiThread { onDone.run() }
            return
        }
        val root = SherpaPunctuationModelFiles.modelsRoot(external) ?: run {
            activity.runOnUiThread { onDone.run() }
            return
        }
        if (entry.kind == SherpaPunctKind.HEURISTIC ||
            SherpaPunctuationModelFiles.allFilesPresentForEntry(external, entry)
        ) {
            activity.runOnUiThread { onDone.run() }
            return
        }
        val tarName = entry.releaseTarBz2
        val folder = entry.extractFolderName
        if (tarName == null || folder == null) {
            activity.runOnUiThread { onDone.run() }
            return
        }
        val url = RELEASE_BASE + tarName
        val destDir = File(root, folder)
        val tmp = File(activity.cacheDir, "punct-$tarName")
        val totalEst = entry.approxDownloadMb.coerceAtLeast(1) * 1_000_000L
        Thread {
            try {
                var done = 0L
                HttpFileDownloader.downloadFileToFile(url, tmp) { n ->
                    done += n
                    activity.runOnUiThread {
                        val mb = done / (1024.0 * 1024.0)
                        sizeView?.text = if (mb < 0.01 && done > 0) {
                            "${done / 1024} KB"
                        } else {
                            String.format(java.util.Locale.US, "%.2f MB", mb)
                        }
                        progressBar?.progress = ((done * 100L) / totalEst).toInt().coerceIn(0, 99)
                    }
                }
                extractTarBz2ToModelsRoot(tmp, root, progressBar, activity)
                SherpaPunctuationEngine.invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "download/extract failed for $tarName", e)
                try {
                    if (destDir.exists()) destDir.deleteRecursively()
                } catch (_: Exception) {
                }
            } finally {
                try {
                    tmp.delete()
                } catch (_: Exception) {
                }
                activity.runOnUiThread {
                    progressBar?.progress = 100
                    onDone.run()
                }
            }
        }.start()
    }

    private fun extractTarBz2ToModelsRoot(
        tarBz2File: File,
        modelsRoot: File,
        progressBar: ProgressBar?,
        activity: Activity,
    ) {
        modelsRoot.mkdirs()
        val total = tarBz2File.length().coerceAtLeast(1L)
        var read = 0L
        FileInputStream(tarBz2File).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val outFile = File(modelsRoot, entry.name)
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buf = ByteArray(64 * 1024)
                                var n: Int
                                while (tar.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                    read += n
                                    val p = ((read * 100L) / total).toInt().coerceIn(0, 99)
                                    activity.runOnUiThread { progressBar?.progress = p }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
