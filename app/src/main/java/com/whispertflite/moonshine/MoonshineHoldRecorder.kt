package com.whispertflite.moonshine

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
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
 * Hold-to-talk: 16 kHz mono PCM. Batch mode decodes on release; live mode uses Moonshine's
 * default stream ([Transcriber.start] / [Transcriber.addAudio] / [Transcriber.stop]) for partials.
 */
class MoonshineHoldRecorder(
    private val context: Context,
    private val mainHandler: Handler,
    private val onPartial: Consumer<String>,
    private val useLiveStreaming: Boolean = false,
) {
    @Volatile
    private var transcriber: Transcriber? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var pseudoChunks = 0

    /** Finished transcript lines in the current live hold (Moonshine reports each line separately). */
    private val liveLineLock = Any()
    private val liveCompletedLines = StringBuilder()

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
        synchronized(liveLineLock) { liveCompletedLines.clear() }
        lastTranscript = ""
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

    /** Emits [liveCompletedLines] + current in-progress line so pauses do not drop earlier sentences. */
    private fun emitLiveCumulative(currentLineText: String) {
        val full =
            synchronized(liveLineLock) {
                buildString {
                    append(liveCompletedLines.toString())
                    val cur = currentLineText.trim()
                    if (isNotEmpty() && cur.isNotEmpty()) append(' ')
                    append(cur)
                }
            }
        if (full.isNotEmpty()) {
            lastTranscript = full
            mainHandler.post { onPartial.accept(full) }
        }
    }

    private fun attachLiveListener(tr: Transcriber) {
        tr.removeAllListeners()
        tr.addListener { event ->
            event.accept(
                object : TranscriptEvent.Visitor {
                    override fun onLineStarted(e: TranscriptEvent.LineStarted) {}

                    override fun onLineUpdated(e: TranscriptEvent.LineUpdated) {
                        val t = e.line.text?.trim().orEmpty()
                        emitLiveCumulative(t)
                    }

                    override fun onLineTextChanged(e: TranscriptEvent.LineTextChanged) {
                        val t = e.line.text?.trim().orEmpty()
                        emitLiveCumulative(t)
                    }

                    override fun onLineCompleted(e: TranscriptEvent.LineCompleted) {
                        val t = e.line.text?.trim().orEmpty()
                        synchronized(liveLineLock) {
                            if (t.isNotEmpty()) {
                                if (liveCompletedLines.isNotEmpty()) liveCompletedLines.append(' ')
                                liveCompletedLines.append(t)
                            }
                        }
                        emitLiveCumulative("")
                    }

                    override fun onError(e: TranscriptEvent.Error) {
                        Log.e(TAG, "Moonshine stream error", e.cause)
                    }
                },
            )
        }
    }

    private fun flushShortsToAddAudio(tr: Transcriber, shorts: ShortArray, len: Int, sampleRate: Int): Int {
        var added = 0
        val chunkSamples = 1600
        var offset = 0
        while (offset < len) {
            val n = min(chunkSamples, len - offset)
            val floats = FloatArray(n) { i -> shorts[offset + i] / 32768f }
            tr.addAudio(floats, sampleRate)
            added += n
            offset += n
        }
        return added
    }

    private fun addAudioFromShortBuffer(
        tr: Transcriber,
        readBuf: ShortArray,
        n: Int,
        sampleRate: Int,
        streamSamplesTotal: Int,
        maxSamples: Int,
    ): Int {
        var total = streamSamplesTotal
        var remaining = n
        var idx = 0
        while (remaining > 0) {
            val room = maxSamples - total
            if (room <= 0) break
            val take = min(remaining, room)
            val floats = FloatArray(take) { i -> readBuf[idx + i] / 32768f }
            tr.addAudio(floats, sampleRate)
            total += take
            idx += take
            remaining -= take
        }
        return total
    }

    private fun recordLoop() {
        Log.i(TAG, "worker: thread started")
        val loadThread =
            Thread(
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
        val record =
            try {
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
        Log.i(TAG, "worker: mic on — buffering while model loads (max ${MoonshineConstants.MAX_RECORD_SECONDS}s)")
        val readBuf = ShortArray(2048)
        val maxSamples = MoonshineConstants.SAMPLE_RATE * MoonshineConstants.MAX_RECORD_SECONDS
        var accum = ShortArray(min(32_000, maxSamples))
        var len = 0
        var loggedFirst = false
        var liveStarted = false
        var liveStartFailed = false
        var streamSamplesTotal = 0
        try {
            while (running.get()) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n < 0) break
                if (n == 0) continue
                if (!loggedFirst) {
                    loggedFirst = true
                    Log.i(TAG, "worker: first samples read n=$n (modelReady=${transcriber != null})")
                }

                val trLocal = transcriber

                if (useLiveStreaming && trLocal != null && !liveStartFailed) {
                    if (!liveStarted) {
                        try {
                            attachLiveListener(trLocal)
                            trLocal.start()
                            liveStarted = true
                            if (len > 0) {
                                streamSamplesTotal += flushShortsToAddAudio(trLocal, accum, len, sampleRate)
                                len = 0
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Moonshine live stream start failed; falling back to batch", e)
                            liveStartFailed = true
                        }
                    }
                    if (liveStarted) {
                        streamSamplesTotal =
                            addAudioFromShortBuffer(
                                trLocal,
                                readBuf,
                                n,
                                sampleRate,
                                streamSamplesTotal,
                                maxSamples,
                            )
                        if (streamSamplesTotal >= maxSamples) {
                            running.set(false)
                        }
                        continue
                    }
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
                when {
                    useLiveStreaming && liveStarted && tr != null -> {
                        try {
                            tr.stop()
                        } catch (e: Exception) {
                            Log.e(TAG, "transcriber.stop failed", e)
                        }
                        tr.removeAllListeners()
                    }
                    tr != null && len > 0 -> {
                        val floats = FloatArray(len) { i -> accum[i] / 32768f }
                        pseudoChunks = (len + 17_919) / 17_920
                        val transcript = tr.transcribeWithoutStreaming(floats, sampleRate)
                        val text = transcript?.text()?.trim() ?: ""
                        lastTranscript = text
                        Log.i(TAG, "worker: transcribe samples=$len textLen=${text.length} \"${text.take(48)}\"")
                        if (text.isNotEmpty()) {
                            mainHandler.post { onPartial.accept(text) }
                        }
                    }
                    else -> {
                        if (tr == null) {
                            Log.w(TAG, "worker: no transcriber (load failed); dropped $len samples")
                            lastTranscript = ""
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed", e)
                if (!liveStarted) lastTranscript = ""
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
