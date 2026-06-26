package com.whispertflite.parakeet

import android.app.Activity
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLConnection

/**
 * Downloads the sherpa-onnx Parakeet model files individually from the Hugging Face mirror.
 *
 * Each file is streamed to a `.part` temp file, verified against its exact expected size, then
 * atomically renamed into place. Only after *all* files pass is the
 * [ParakeetConstants.READY_MARKER_FILE] marker written. [ParakeetModelFiles.allOnnxPresent] gates
 * model loading on that marker, so an interrupted/corrupt download can never reach sherpa-onnx's
 * native loader (which aborts the whole process on a bad .onnx).
 */
object ParakeetDownloader {
    private const val TAG = "ParakeetDownloader"

    @JvmStatic
    fun downloadParakeetModels(
        activity: Activity,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        onDone: Runnable,
    ) {
        val baseDir = activity.getExternalFilesDir(null) ?: return
        Thread {
            try {
                if (!ParakeetModelFiles.allOnnxPresent(baseDir)) {
                    downloadAll(activity, progressBar, sizeView, baseDir)
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

    private fun downloadAll(
        activity: Activity,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        baseDir: File,
    ) {
        val modelDir = File(baseDir, ParakeetConstants.MODEL_DIR)
        modelDir.mkdirs()

        // Stale/partial state from a previous interrupted run must not survive: drop the marker so a
        // crash mid-download leaves the model definitively "not present".
        ParakeetModelFiles.readyMarkerFile(baseDir).delete()

        val total = ParakeetConstants.MODEL_TOTAL_BYTES
        var completed = 0L

        for (spec in ParakeetConstants.MODEL_FILES) {
            val target = File(modelDir, spec.name)
            // Skip files already correct from a partially-finished prior run.
            if (target.isFile && target.length() == spec.sizeBytes) {
                completed += spec.sizeBytes
                continue
            }

            val part = File(modelDir, "${spec.name}.part")
            part.delete()
            downloadFile(activity, progressBar, sizeView, spec, part, completed, total)

            if (part.length() != spec.sizeBytes) {
                part.delete()
                throw IllegalStateException(
                    "Size mismatch for ${spec.name}: got ${part.length()}, expected ${spec.sizeBytes}",
                )
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw IllegalStateException("Failed to move ${spec.name} into place")
            }
            completed += spec.sizeBytes
        }

        // Everything verified: publish the marker so the model becomes loadable.
        ParakeetModelFiles.readyMarkerFile(baseDir).writeText("ok")
    }

    private fun downloadFile(
        activity: Activity,
        progressBar: ProgressBar?,
        sizeView: TextView?,
        spec: ParakeetConstants.ModelFile,
        part: File,
        priorCompleted: Long,
        total: Long,
    ) {
        val ucon: URLConnection = URL(spec.url).openConnection()
        ucon.connectTimeout = 15_000
        ucon.readTimeout = 60_000
        ucon.getInputStream().use { raw ->
            BufferedInputStream(raw, 1 shl 16).use { ins ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(1 shl 16)
                    var fileDone = 0L
                    while (true) {
                        val r = ins.read(buf)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        fileDone += r
                        val overall = priorCompleted + fileDone
                        val mb = overall / 1024 / 1024
                        val totalMb = total / 1024 / 1024
                        val pct = ((overall * 100) / total).toInt().coerceIn(0, 100)
                        activity.runOnUiThread {
                            sizeView?.text = "$mb / $totalMb MB"
                            progressBar?.progress = pct
                        }
                    }
                    fos.flush()
                }
            }
        }
    }
}
