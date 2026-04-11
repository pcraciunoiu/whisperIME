package com.whispertflite.sherpa

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import java.io.File
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SherpaStreamingRecorder"
private const val SAMPLE_RATE = 16000

/**
 * Hold-to-talk streaming ASR via sherpa-onnx [OnlineRecognizer], mirroring [com.whispertflite.parakeet.ParakeetStreamingRecorder].
 * Partials are posted on [mainHandler]; [stop] returns **punctuation-polished** final text (see [SherpaPunctuationPostProcessor]).
 */
class SherpaStreamingRecorder(
    private val context: Context,
    private val externalFilesDir: File?,
    private val mainHandler: Handler,
    private val onPartial: Consumer<String>,
) {
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var workerFinalTranscript: String? = null

    fun start(): Boolean {
        val root = SherpaModelFiles.modelsRoot(externalFilesDir)
        val entry = SherpaCatalogEntry.requireById(SherpaPreferences.selectedCatalogId(context))
        if (!SherpaModelFiles.allFilesPresentForEntry(root, entry)) {
            Log.w(TAG, "start() aborted: model files missing for ${entry.id} under $root")
            return false
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start() aborted: RECORD_AUDIO not granted")
            return false
        }
        stop(join = true)
        workerFinalTranscript = null
        running.set(true)
        worker = Thread({ recordLoop(entry, root!!) }, "SherpaStream").also { it.start() }
        Log.d(TAG, "start() worker scheduled entry=${entry.id}")
        return true
    }

    @JvmOverloads
    fun stop(join: Boolean = true): String {
        running.set(false)
        if (join) {
            try {
                worker?.join(8000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        val raw = workerFinalTranscript ?: ""
        workerFinalTranscript = null
        val polished = SherpaPunctuationPostProcessor.applyFinal(context.applicationContext, raw)
        Log.i(
            TAG,
            "stop() finalLen=${polished.length} preview=\"${polished.take(80)}\"",
        )
        return polished
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(entry: SherpaCatalogEntry, modelsRoot: File) {
        var rec: OnlineRecognizer? = null
        var audioRecord: AudioRecord? = null
        try {
            val rel = getModelConfig(entry.sherpaConfigType)
                ?: run {
                    Log.e(TAG, "getModelConfig(${entry.sherpaConfigType}) null")
                    running.set(false)
                    return
                }
            val mcfg = absolutizeModelConfig(rel, modelsRoot)
            val config = OnlineRecognizerConfig(
                featConfig = getFeatureConfig(SAMPLE_RATE, 80),
                modelConfig = mcfg,
                endpointConfig = getEndpointConfig(),
                enableEndpoint = true,
            )
            rec = OnlineRecognizer(null, config)

            if (!running.get()) return

            val channel = AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.ENCODING_PCM_16BIT
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channel,
                format,
                min * 2,
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                running.set(false)
                return
            }
            audioRecord.startRecording()

            val stream = rec.createStream("")
            try {
                val bufferSize = (0.1 * SAMPLE_RATE).toInt()
                val buffer = ShortArray(bufferSize)
                var lastText = ""
                while (running.get()) {
                    val n = audioRecord.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val text = rec.getResult(stream).text
                    if (text != lastText) {
                        lastText = text
                        mainHandler.post { onPartial.accept(text) }
                    }
                    if (rec.isEndpoint(stream)) {
                        rec.reset(stream)
                    }
                }
                workerFinalTranscript = lastText
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordLoop failed", e)
            workerFinalTranscript = ""
        } finally {
            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }
            audioRecord?.release()
            rec?.release()
        }
    }
}
