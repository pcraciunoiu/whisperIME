package com.whispertflite.moonshine

import android.app.Activity
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.whispertflite.R
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object MoonshineDownloader {
    private const val TAG = "MoonshineDownloader"

    private val FILES = arrayOf("encoder_model.ort", "decoder_model_merged.ort", "tokenizer.bin")

    @JvmStatic
    fun downloadMoonshineBaseModels(
        activity: Activity,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        onDone: Runnable,
    ) {
        val outDir = MoonshineModelFiles.baseEnDir(activity)
        Thread {
            try {
                outDir.mkdirs()
                val conn = URL(MoonshineConstants.ANDROID_EXAMPLES_TAR_URL).openConnection()
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                val total = conn.contentLengthLong.coerceAtLeast(1L)
                var readTotal = 0L
                TarArchiveInputStream(
                    GzipCompressorInputStream(BufferedInputStream(conn.getInputStream(), 65536)),
                ).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val name = entry.name
                        if (entry.isDirectory || !name.startsWith(MoonshineConstants.TAR_ENTRY_PREFIX)) {
                            entry = tar.nextTarEntry
                            continue
                        }
                        val simple = name.removePrefix(MoonshineConstants.TAR_ENTRY_PREFIX)
                        if (simple.startsWith("._") || simple !in FILES) {
                            entry = tar.nextTarEntry
                            continue
                        }
                        val outFile = File(outDir, simple)
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val r = tar.read(buf)
                                if (r <= 0) break
                                fos.write(buf, 0, r)
                                readTotal += r
                                activity.runOnUiThread {
                                    sizeView?.text = "${readTotal / 1024 / 1024} MB"
                                    progressBar?.progress = ((readTotal * 100) / total).toInt().coerceIn(0, 99)
                                }
                            }
                        }
                        Log.i(TAG, "extracted $simple (${outFile.length()} bytes)")
                        entry = tar.nextTarEntry
                    }
                }
                activity.runOnUiThread {
                    val ok = MoonshineModelFiles.allModelFilesPresent(activity)
                    progressBar?.progress = if (ok) 100 else 0
                    if (ok) {
                        onDone.run()
                    } else {
                        Toast.makeText(activity, R.string.error_download, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "download/extract failed", e)
                activity.runOnUiThread {
                    progressBar?.progress = 0
                    Toast.makeText(activity, R.string.error_download, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
