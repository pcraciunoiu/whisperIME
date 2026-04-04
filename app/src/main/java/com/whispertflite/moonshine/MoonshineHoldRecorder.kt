package com.whispertflite.moonshine

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Hold-to-talk: buffer 16 kHz mono PCM while recording; load Moonshine Base on a side thread;
 * on release run [Transcriber.transcribeWithoutStreaming]. Matches prior Parakeet buffered UX.
 */
class MoonshineHoldRecorder(
    private val context: Context,
    private val mainHandler: Handler,
    private val onPartial: Consumer<String>,
) {
    @Volatile
    private var transcriber: Transcriber? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var pseudoChunks = 0

    fun start(): Boolean {
        if (!MoonshineModelFiles.isDeviceSupported(context)) return false
        if (!MoonshineModelFiles.allModelFilesPresent(context)) return false
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        stop(join = true)
        pseudoChunks = 0
        running.set(true)
        worker = Thread({ recordLoop() }, "MoonshineStream").also { it.start() }
        return true
    }

    @JvmOverloads
    fun stop(join: Boolean = true): String {
        running.set(false)
        if (join) {
            try {
                worker?.join(120_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        val last = lastTranscript
        transcriber = null
        Log.i(TAG, "stop() finalLen=${last.length} pseudoChunks=$pseudoChunks preview=\"${last.take(80)}\"")
        return last
    }

    @Volatile
    private var lastTranscript: String = ""

    private fun recordLoop() {
        Log.i(TAG, "worker: thread started")
        val loadThread = Thread(
            {
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    val tr = Transcriber()
                    tr.loadFromFiles(
                        MoonshineModelFiles.baseEnDir(context).absolutePath,
                        JNI.MOONSHINE_MODEL_ARCH_BASE,
                    )
                    transcriber = tr
                    Log.i(TAG, "worker: Moonshine Base ready in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Moonshine load failed", e)
                }
            },
            "MoonshineModelLoad",
        )
        loadThread.start()

        if (!running.get()) {
            loadThread.join(120_000)
            transcriber = null
            return
        }

        val sampleRate = MoonshineConstants.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, 4096)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build failed", e)
            running.set(false)
            loadThread.join(120_000)
            transcriber = null
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            running.set(false)
            loadThread.join(120_000)
            transcriber = null
            return
        }

        record.startRecording()
        Log.i(TAG, "worker: mic on — buffering while model loads (max 30s)")
        val readBuf = ShortArray(2048)
        val maxSamples = MoonshineConstants.SAMPLE_RATE * 30
        var accum = ShortArray(min(32_000, maxSamples))
        var len = 0
        var loggedFirst = false
        try {
            while (running.get()) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n < 0) break
                if (n == 0) continue
                if (!loggedFirst) {
                    loggedFirst = true
                    Log.i(TAG, "worker: first samples read n=$n (modelReady=${transcriber != null})")
                }
                val room = maxSamples - len
                if (room <= 0) continue
                val take = minOf(n, room)
                if (len + take > accum.size) {
                    val newCap = minOf((accum.size * 2).coerceAtLeast(len + take), maxSamples)
                    accum = accum.copyOf(newCap)
                }
                System.arraycopy(readBuf, 0, accum, len, take)
                len += take
            }
        } finally {
            loadThread.join(120_000)
            val tr = transcriber
            try {
                if (tr != null && len > 0) {
                    val floats = FloatArray(len) { i -> accum[i] / 32768f }
                    pseudoChunks = (len + 17_919) / 17_920
                    val transcript = tr.transcribeWithoutStreaming(floats, sampleRate)
                    val text = transcript?.text()?.trim() ?: ""
                    lastTranscript = text
                    Log.i(TAG, "worker: transcribe samples=$len textLen=${text.length} \"${text.take(48)}\"")
                    if (text.isNotEmpty()) {
                        mainHandler.post { onPartial.accept(text) }
                    }
                } else {
                    lastTranscript = ""
                    if (tr == null) {
                        Log.w(TAG, "worker: no transcriber (load failed); dropped $len samples")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed", e)
                lastTranscript = ""
            }
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    companion object {
        private const val TAG = "MoonshineASR"
    }
}
