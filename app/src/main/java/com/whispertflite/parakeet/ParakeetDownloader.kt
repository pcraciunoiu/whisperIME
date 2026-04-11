package com.whispertflite.parakeet

import android.app.Activity
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import com.whispertflite.utils.HttpFileDownloader
import java.io.File

object ParakeetDownloader {
    private const val TAG = "ParakeetDownloader"

    @JvmStatic
    fun downloadParakeetModels(
        activity: Activity,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        onDone: Runnable,
    ) {
        val dir = activity.getExternalFilesDir(null) ?: return
        Thread {
            try {
                val enc = ParakeetModelFiles.encoderFile(dir)
                val dec = ParakeetModelFiles.decoderFile(dir)
                val totalEst = 650_000_000L + 10_000_000L
                var done = 0L
                if (!enc.isFile || enc.length() < 10_000_000L) {
                    HttpFileDownloader.downloadFileToFile(ParakeetConstants.ENCODER_URL, enc) { n ->
                        done += n
                        activity.runOnUiThread {
                            sizeView?.text = "${done / 1024 / 1024} MB"
                            progressBar?.progress = ((done * 100) / totalEst).toInt().coerceIn(0, 99)
                        }
                    }
                }
                if (!dec.isFile || dec.length() < 1000L) {
                    HttpFileDownloader.downloadFileToFile(ParakeetConstants.DECODER_URL, dec) { n ->
                        done += n
                        activity.runOnUiThread {
                            sizeView?.text = "${done / 1024 / 1024} MB"
                            progressBar?.progress = ((done * 100) / totalEst).toInt().coerceIn(0, 99)
                        }
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
}
