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

/**
 * Pushes [ParakeetConstants.CHUNK_PCM_SAMPLES] frames of 16 kHz mono PCM to the engine;
 * posts partial transcripts on [mainHandler].
 */
class ParakeetStreamingRecorder(
    private val context: Context,
    private val modelsDir: File,
    private val mainHandler: Handler,
    private val onPartial: Consumer<String>,
) {
    @Volatile
    private var engine: ParakeetStreamingEngine? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var audioChunksFed = 0
    @Volatile
    private var loggedFirstNonEmptyPartial = false

    /** Set in the worker [finally] after the last [ParakeetStreamingEngine.snapshotTranscript], before [releaseAfterHold]. */
    @Volatile
    private var workerFinalTranscript: String? = null

    fun start(): Boolean {
        if (!ParakeetModelFiles.allOnnxPresent(modelsDir)) {
            Log.w(TAG, "start() aborted: ONNX models missing under $modelsDir")
            return false
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start() aborted: RECORD_AUDIO not granted")
            return false
        }
        // Serialize with previous session so we never run two workers or leak an engine.
        stop(join = true)
        audioChunksFed = 0
        loggedFirstNonEmptyPartial = false
        workerFinalTranscript = null
        running.set(true)
        worker = Thread({ recordLoop() }, "ParakeetStream").also { it.start() }
        Log.d(TAG, "start() worker thread scheduled")
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
        val wf = workerFinalTranscript
        workerFinalTranscript = null
        val last =
            wf ?: try {
                engine?.snapshotTranscript() ?: ""
            } catch (_: Exception) {
                ""
            }
        engine = null
        Log.i(
            TAG,
            "stop() finalLen=${last.length} fullChunks=$audioChunksFed preview=\"${last.take(80)}\"",
        )
        return last
    }

    private fun recordLoop() {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "worker: ParakeetStream thread started")
        val t0 = android.os.SystemClock.elapsedRealtime()
        ParakeetEnginePool.lockSession()
        var eng: ParakeetStreamingEngine? = null
        try {
            eng = try {
                ParakeetEnginePool.borrowEngine(context.applicationContext, modelsDir).also { it.resetSession() }
            } catch (e: Exception) {
                Log.e(TAG, "ONNX load failed", e)
                running.set(false)
                return
            }
            engine = eng
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "worker: engine ready in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            }
            if (!running.get()) {
                Log.w(TAG, "worker: stop before mic — finger up during model load? (no audio processed)")
                return
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                running.set(false)
                return
            }

            val sampleRate = ParakeetConstants.SAMPLE_RATE
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBuf, 4096)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            val record = try {
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
                return
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized state=${record.state}")
                running.set(false)
                return
            }
            val captureEffects =
                AudioCaptureEffects.attachIfRequested(
                    record,
                    AudioCapturePreferences.platformNoiseSuppressorEnabled(context),
                    AudioCapturePreferences.platformAecEnabled(context),
                )
            val rnnoise = RnnoiseDenoiser.createIfEnabled(context)
            record.startRecording()
            Log.i(TAG, "mic recording started (16 kHz mono); chunkSamples=${ParakeetConstants.CHUNK_PCM_SAMPLES}")
            val pcmEngine = eng!!
            val readBuf = ShortArray(2048)
            val chunk = ShortArray(ParakeetConstants.CHUNK_PCM_SAMPLES)
            var filled = 0
            /** When the model emits a fresh segment after silence, merge with prior text for this hold. */
            var streamedStitch = ""
            var stitchLogBudget = 5
            try {
                while (running.get()) {
                    val n = record.read(readBuf, 0, readBuf.size)
                    if (n < 0) {
                        Log.w(TAG, "worker: AudioRecord.read error n=$n")
                        break
                    }
                    if (n == 0) continue
                    rnnoise?.processBuffer(readBuf, n)
                    var i = 0
                    while (i < n && running.get()) {
                        val need = ParakeetConstants.CHUNK_PCM_SAMPLES - filled
                        val take = minOf(need, n - i)
                        System.arraycopy(readBuf, i, chunk, filled, take)
                        filled += take
                        i += take
                        if (filled == ParakeetConstants.CHUNK_PCM_SAMPLES) {
                            audioChunksFed++
                            val text = try {
                                pcmEngine.processPcm16Chunk(chunk)
                            } catch (e: Exception) {
                                Log.e(TAG, "processPcm16Chunk failed", e)
                                ""
                            }
                            val prevStitch = streamedStitch
                            streamedStitch = stitchStreamingPartials(streamedStitch, text)
                            if (Log.isLoggable(TAG, Log.DEBUG) && stitchLogBudget > 0 &&
                                prevStitch.isNotBlank() && text.isNotBlank()
                            ) {
                                stitchLogBudget--
                                Log.d(
                                    TAG,
                                    "stitch chunk#$audioChunksFed raw=\"${text.trim().take(48)}\" " +
                                        "cumulative=\"${streamedStitch.take(64)}\"",
                                )
                            }
                            if (streamedStitch.isNotBlank() && !loggedFirstNonEmptyPartial) {
                                loggedFirstNonEmptyPartial = true
                                Log.i(
                                    TAG,
                                    "first non-empty partial chunk#$audioChunksFed len=${streamedStitch.length} " +
                                        "preview=\"${streamedStitch.take(48)}\"",
                                )
                            }
                            mainHandler.post { onPartial.accept(streamedStitch) }
                            filled = 0
                        }
                    }
                }
            } finally {
                if (filled > 0) {
                    chunk.fill(0.toShort(), filled, chunk.size)
                    try {
                        val text = pcmEngine.processPcm16Chunk(chunk)
                        streamedStitch = stitchStreamingPartials(streamedStitch, text)
                        mainHandler.post { onPartial.accept(streamedStitch) }
                    } catch (e: Exception) {
                        Log.e(TAG, "partial flush processPcm16Chunk failed", e)
                    }
                }
                rnnoise?.release()
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                captureEffects?.release()
                record.release()
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } finally {
            try {
                workerFinalTranscript = try {
                    eng?.snapshotTranscript() ?: ""
                } catch (_: Exception) {
                    ""
                }
            } finally {
                ParakeetEnginePool.releaseAfterHold(eng)
                engine = null
                ParakeetEnginePool.unlockSession()
            }
        }
    }

    companion object {
        private const val TAG = "ParakeetASR"

        /** Cumulative streaming text; if a chunk starts a disjoint hypothesis after a pause, append it. */
        private fun stitchStreamingPartials(prev: String, incoming: String): String {
            val inc = incoming.trim()
            if (inc.isEmpty()) return prev
            val p = prev.trim()
            if (p.isEmpty()) return inc
            if (inc.startsWith(p)) return inc
            if (p.startsWith(inc)) return inc
            return "$p $inc".trim()
        }
    }

    /** Last partial text from engine (call before [stop] closes engine). */
    fun snapshotTranscript(): String {
        val e = engine ?: return ""
        return try {
            e.snapshotTranscript()
        } catch (_: Exception) {
            ""
        }
    }
}
