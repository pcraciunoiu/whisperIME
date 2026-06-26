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
 * When [useLiveStreaming] is set, a separate decoder thread periodically re-decodes the audio captured so
 * far and emits it via [onPartial] for a live preview. sherpa-onnx's offline recognizer keeps no streaming
 * state, so each partial is a full re-decode of the growing buffer — done off the capture thread so mic
 * reads are never starved (the bug that previously dropped words). The decode on release stays authoritative.
 *
 * Class/method names kept stable so MainActivity / IME / RecognitionService need no changes.
 */
class ParakeetStreamingRecorder(
    private val context: Context,
    private val modelsDir: File,
    private val mainHandler: Handler,
    private val onPartial: Consumer<String>,
    private val useLiveStreaming: Boolean = false,
) {
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastTranscript: String = ""

    @Volatile
    private var engine: ParakeetStreamingEngine? = null

    /** Guards [accum]/[accumLen] shared between the capture thread and the partial-decoder thread. */
    private val bufLock = Any()
    private var accum = ShortArray(0)
    private var accumLen = 0

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
        engine = null
        synchronized(bufLock) {
            accum = ShortArray(0)
            accumLen = 0
        }
        running.set(true)
        worker = Thread({ recordLoop() }, "ParakeetBatch").also { it.start() }
        Log.d(TAG, "start() worker thread scheduled (live=$useLiveStreaming)")
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

    /** Latest transcript: a live partial while held, or the final decode once released. */
    fun snapshotTranscript(): String = lastTranscript

    private fun recordLoop() {
        ParakeetEnginePool.lockSession()
        engine = null
        // Load the recognizer in parallel with mic capture so a cold start doesn't drop the opening words.
        val loadThread = Thread({
            engine = try {
                ParakeetEnginePool.borrowEngine(context.applicationContext, modelsDir)
            } catch (e: Exception) {
                Log.e(TAG, "engine load failed", e)
                null
            }
        }, "ParakeetLoad").also { it.start() }

        val partialThread =
            if (useLiveStreaming) Thread({ partialLoop() }, "ParakeetPartial").also { it.start() } else null

        try {
            val finalLen = captureUtterance()
            // Stop emitting partials and make sure the engine finished loading before the final decode.
            joinQuietly(partialThread)
            joinQuietly(loadThread)
            val e = engine
            if (e == null) {
                Log.w(TAG, "no engine (load failed); dropped $finalLen samples")
                lastTranscript = ""
                return
            }
            if (finalLen <= 0) {
                Log.w(TAG, "no audio captured")
                lastTranscript = ""
                return
            }
            val floats = snapshotFloats()
            val t0 = android.os.SystemClock.elapsedRealtime()
            val text = e.transcribe(floats).trim()
            lastTranscript = text
            Log.i(
                TAG,
                "decoded samples=$finalLen (${finalLen / ParakeetConstants.SAMPLE_RATE}s) " +
                    "in ${android.os.SystemClock.elapsedRealtime() - t0}ms textLen=${text.length} " +
                    "preview=\"${text.take(64)}\"",
            )
            if (text.isNotEmpty()) {
                mainHandler.post { onPartial.accept(text) }
            }
        } finally {
            joinQuietly(partialThread)
            joinQuietly(loadThread)
            ParakeetEnginePool.releaseAfterHold(engine)
            ParakeetEnginePool.unlockSession()
        }
    }

    /**
     * Re-decodes the audio captured so far on a fixed cadence and emits it as a live partial. Runs only
     * while [running]; each pass is a full offline decode (no streaming state), throttled by both the sleep
     * interval and the engine's internal lock so it can never overrun the capture thread.
     */
    private fun partialLoop() {
        var lastDecodedLen = 0
        while (running.get()) {
            try {
                Thread.sleep(PARTIAL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!running.get()) break
            val e = engine ?: continue
            val floats = snapshotFloats()
            if (floats.size < MIN_PARTIAL_SAMPLES || floats.size == lastDecodedLen) continue
            lastDecodedLen = floats.size
            val text = try {
                e.transcribe(floats).trim()
            } catch (ex: Exception) {
                Log.w(TAG, "partial decode failed", ex)
                ""
            }
            if (text.isNotEmpty() && running.get()) {
                lastTranscript = text
                mainHandler.post { onPartial.accept(text) }
            }
        }
    }

    /** Snapshots the captured PCM (under [bufLock]) as 16 kHz mono float samples in -1..1. */
    private fun snapshotFloats(): FloatArray {
        synchronized(bufLock) {
            val n = accumLen
            val src = accum
            return FloatArray(n) { src[it] / 32768f }
        }
    }

    /** Records 16 kHz mono PCM (with optional denoise) into the shared buffer until [running] clears; returns the sample count. */
    private fun captureUtterance(): Int {
        val sampleRate = ParakeetConstants.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val maxSamples = sampleRate * ParakeetConstants.MAX_RECORD_SECONDS
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var record: AudioRecord? = null
        var captureEffects: AudioCaptureEffects? = null
        var rnnoise: RnnoiseDenoiser? = null
        var scoStarted = false
        synchronized(bufLock) {
            accum = ShortArray(min(sampleRate * 4, maxSamples))
            accumLen = 0
        }
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
                return 0
            }
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized state=${rec.state}")
                running.set(false)
                return 0
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
            while (running.get() && accumLen < maxSamples) {
                val n = rec.read(readBuf, 0, readBuf.size)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error n=$n")
                    break
                }
                if (n == 0) continue
                rnnoise?.processBuffer(readBuf, n)
                synchronized(bufLock) {
                    val room = maxSamples - accumLen
                    val take = min(n, room)
                    if (accumLen + take > accum.size) {
                        val newCap = min((accum.size * 2).coerceAtLeast(accumLen + take), maxSamples)
                        accum = accum.copyOf(newCap)
                    }
                    System.arraycopy(readBuf, 0, accum, accumLen, take)
                    accumLen += take
                }
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
        return synchronized(bufLock) { accumLen }
    }

    private fun joinQuietly(thread: Thread?) {
        if (thread == null) return
        try {
            thread.join(120_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "ParakeetASR"

        /** How often the live preview re-decodes the growing buffer. */
        private const val PARTIAL_INTERVAL_MS = 800L

        /** Don't decode until there's at least this much audio (~0.4 s) to avoid noisy early partials. */
        private const val MIN_PARTIAL_SAMPLES = ParakeetConstants.SAMPLE_RATE * 2 / 5
    }
}
