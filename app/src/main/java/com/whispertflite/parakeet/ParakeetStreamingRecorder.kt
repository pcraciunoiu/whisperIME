package com.whispertflite.parakeet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.util.Log
import androidx.core.app.ActivityCompat
import com.whispertflite.asr.AudioCaptureEffects
import com.whispertflite.asr.AudioCapturePreferences
import com.whispertflite.asr.RnnoiseDenoiser
import java.io.File
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Hold-to-talk recorder for the offline sherpa-onnx Parakeet engine. Captures 16 kHz mono PCM while held
 * and decodes the whole utterance once on release (Parakeet TDT is an offline/batch model). The model is
 * loaded in parallel with capture so the first words aren't lost to a cold start.
 *
 * Class/method names kept stable so MainActivity / IME / RecognitionService need no changes.
 */
class ParakeetStreamingRecorder(
    private val context: Context,
    private val modelsDir: File,
    private val mainHandler: Handler,
    private val onPartial: Consumer<String>,
) {
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastTranscript: String = ""

    fun start(): Boolean {
        if (!ParakeetModelFiles.allOnnxPresent(modelsDir)) {
            Log.w(TAG, "start() aborted: model files missing under $modelsDir")
            return false
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start() aborted: RECORD_AUDIO not granted")
            return false
        }
        stop(join = true)
        lastTranscript = ""
        running.set(true)
        worker = Thread({ recordLoop() }, "ParakeetBatch").also { it.start() }
        Log.d(TAG, "start() worker thread scheduled")
        return true
    }

    @JvmOverloads
    fun stop(join: Boolean = true): String {
        running.set(false)
        if (join) {
            try {
                // Generous: the worker decodes the full utterance after capture ends before returning.
                worker?.join(120_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        val last = lastTranscript
        Log.i(TAG, "stop() finalLen=${last.length} preview=\"${last.take(80)}\"")
        return last
    }

    /** Final transcript only exists after decode; live snapshots aren't available for the batch engine. */
    fun snapshotTranscript(): String = lastTranscript

    private fun recordLoop() {
        ParakeetEnginePool.lockSession()
        var eng: ParakeetStreamingEngine? = null
        // Load the recognizer in parallel with mic capture so a cold start doesn't drop the opening words.
        val loadThread = Thread({
            eng = try {
                ParakeetEnginePool.borrowEngine(context.applicationContext, modelsDir)
            } catch (e: Exception) {
                Log.e(TAG, "engine load failed", e)
                null
            }
        }, "ParakeetLoad").also { it.start() }

        try {
            val captured = captureUtterance()
            try {
                loadThread.join(120_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val e = eng
            if (e == null) {
                Log.w(TAG, "no engine (load failed); dropped ${captured.length} samples")
                lastTranscript = ""
                return
            }
            if (captured.length <= 0) {
                Log.w(TAG, "no audio captured")
                lastTranscript = ""
                return
            }
            val floats = FloatArray(captured.length) { captured.buffer[it] / 32768f }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val text = e.transcribe(floats).trim()
            lastTranscript = text
            Log.i(
                TAG,
                "decoded samples=${captured.length} (${captured.length / ParakeetConstants.SAMPLE_RATE}s) " +
                    "in ${android.os.SystemClock.elapsedRealtime() - t0}ms textLen=${text.length} " +
                    "preview=\"${text.take(64)}\"",
            )
            if (text.isNotEmpty()) {
                mainHandler.post { onPartial.accept(text) }
            }
        } finally {
            try {
                loadThread.join(120_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            ParakeetEnginePool.releaseAfterHold(eng)
            ParakeetEnginePool.unlockSession()
        }
    }

    private class Captured(val buffer: ShortArray, val length: Int)

    /** Records 16 kHz mono PCM (with optional denoise) into a growing buffer until [running] clears. */
    private fun captureUtterance(): Captured {
        val sampleRate = ParakeetConstants.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val maxSamples = sampleRate * ParakeetConstants.MAX_RECORD_SECONDS
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var record: AudioRecord? = null
        var captureEffects: AudioCaptureEffects? = null
        var rnnoise: RnnoiseDenoiser? = null
        var scoStarted = false
        var accum = ShortArray(min(sampleRate * 4, maxSamples))
        var len = 0
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBuf, sampleRate * 2 * 2) // ~2 s headroom
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoStarted = true
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(AudioCapturePreferences.audioSource(context))
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
                return Captured(accum, 0)
            }
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized state=${rec.state}")
                running.set(false)
                return Captured(accum, 0)
            }
            captureEffects = AudioCaptureEffects.attachIfRequested(
                rec,
                AudioCapturePreferences.platformNoiseSuppressorEnabled(context),
                AudioCapturePreferences.platformAecEnabled(context),
            )
            rnnoise = RnnoiseDenoiser.createIfEnabled(context)
            rec.startRecording()
            Log.i(TAG, "mic recording started (16 kHz mono)")
            val readBuf = ShortArray(2048)
            while (running.get() && len < maxSamples) {
                val n = rec.read(readBuf, 0, readBuf.size)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error n=$n")
                    break
                }
                if (n == 0) continue
                rnnoise?.processBuffer(readBuf, n)
                val room = maxSamples - len
                val take = min(n, room)
                if (len + take > accum.size) {
                    val newCap = min((accum.size * 2).coerceAtLeast(len + take), maxSamples)
                    accum = accum.copyOf(newCap)
                }
                System.arraycopy(readBuf, 0, accum, len, take)
                len += take
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
        } finally {
            rnnoise?.release()
            if (record != null) {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                captureEffects?.release()
                record.release()
            }
            if (scoStarted) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
        return Captured(accum, len)
    }

    companion object {
        private const val TAG = "ParakeetASR"
    }
}
