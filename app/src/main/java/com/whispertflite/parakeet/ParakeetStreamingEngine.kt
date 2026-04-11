package com.whispertflite.parakeet

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Streaming multitalker Parakeet int8: encoder cache + RNNT greedy decoder per encoder frame.
 */
class ParakeetStreamingEngine(
    context: Context,
    modelsDir: File,
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val encSession: OrtSession
    private val decSession: OrtSession
    private val melExtractor = ParakeetMelExtractor(context)
    private val tokenizer = ParakeetTokenizer(context)

    private val cacheLc = FloatArray(1 * 24 * 70 * 1024)
    private val cacheLt = FloatArray(1 * 24 * 1024 * 8)
    private var cacheChannelLen = 0L

    private val decState1 = FloatArray(2 * 1 * 640)
    private val decState2 = FloatArray(2 * 1 * 640)
    /** Last [PRE_ENCODE_MEL_FRAMES] columns of mel (128×9), prepended next chunk like parakeet-rs. */
    private val melPreCtx = FloatArray(ParakeetConstants.MEL_BINS * ParakeetConstants.PRE_ENCODE_MEL_FRAMES)
    /** Must match parakeet-rs / NeMo: first `targets` value is blank, not <unk> (0). */
    private var prevLabel = ParakeetConstants.BLANK_ID.toLong()
    private val emittedIds = mutableListOf<Int>()
    /** Reused for decoder `targets` to avoid a direct-buffer alloc every RNNT step. */
    private val decTargetLongBuf =
        ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer()

    private val lock = Any()
    private var debugChunkCount = 0
    private var debugLoggedDecoderMeta = false

    init {
        val so = OrtSession.SessionOptions()
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        encSession = env.createSession(ParakeetModelFiles.encoderFile(modelsDir).absolutePath, so)
        decSession = env.createSession(ParakeetModelFiles.decoderFile(modelsDir).absolutePath, so)
    }

    fun resetSession() {
        synchronized(lock) {
            cacheLc.fill(0f)
            cacheLt.fill(0f)
            cacheChannelLen = 0L
            decState1.fill(0f)
            decState2.fill(0f)
            melPreCtx.fill(0f)
            prevLabel = ParakeetConstants.BLANK_ID.toLong()
            emittedIds.clear()
            debugChunkCount = 0
            debugLoggedDecoderMeta = false
        }
    }

    fun snapshotTranscript(): String {
        synchronized(lock) {
            return tokenizer.decodeTokenIds(emittedIds)
        }
    }

    /**
     * Encoder expects `PRE_ENCODE_MEL_FRAMES` + `MEL_TIME` columns (parakeet-rs `build_mel_chunk`),
     * not raw 112-frame mel alone — otherwise streaming cache drifts and decoding stalls mid-utterance.
     */
    private fun stitchEncoderMel(chunkMel: FloatArray): FloatArray {
        val m = ParakeetConstants.MEL_BINS
        val tChunk = ParakeetConstants.MEL_TIME
        val p = ParakeetConstants.PRE_ENCODE_MEL_FRAMES
        val w = ParakeetConstants.MEL_ENCODER_TIME
        require(chunkMel.size == m * tChunk)
        val out = FloatArray(m * w)
        for (bin in 0 until m) {
            val rowOut = bin * w
            val rowChunk = bin * tChunk
            val rowCtx = bin * p
            for (t in 0 until p) {
                out[rowOut + t] = melPreCtx[rowCtx + t]
            }
            for (t in 0 until tChunk) {
                out[rowOut + p + t] = chunkMel[rowChunk + t]
            }
            for (t in 0 until p) {
                melPreCtx[rowCtx + t] = chunkMel[rowChunk + (tChunk - p + t)]
            }
        }
        return out
    }

    /** Returns full transcript so far after this chunk. */
    fun processPcm16Chunk(pcm: ShortArray): String {
        require(pcm.size == ParakeetConstants.CHUNK_PCM_SAMPLES)
        synchronized(lock) {
            debugChunkCount++
            val chunkTag = debugChunkCount
            val melChunk = melExtractor.pcm16ToMelLog(pcm)
            val melEnc = stitchEncoderMel(melChunk)
            if (Log.isLoggable(TAG, Log.DEBUG) && chunkTag == 1) {
                var mn = Float.POSITIVE_INFINITY
                var mx = Float.NEGATIVE_INFINITY
                for (x in melChunk) {
                    if (x < mn) mn = x
                    if (x > mx) mx = x
                }
                Log.d(TAG, "mel[1] min=$mn max=$mx (expect finite non-zero if mic signal present)")
            }
            val Tenc = ParakeetConstants.MEL_ENCODER_TIME
            val melFb = FloatBuffer.allocate(ParakeetConstants.MEL_BINS * Tenc)
            melFb.put(melEnc)
            melFb.rewind()
            val melTensor = OnnxTensor.createTensor(
                env,
                melFb,
                longArrayOf(1, ParakeetConstants.MEL_BINS.toLong(), Tenc.toLong()),
            )
            // HeapLongBuffer from LongBuffer.wrap() is rejected at runtime; use direct NIO buffer.
            val lenTensor = createLongTensor(longArrayOf(Tenc.toLong()), longArrayOf(1))
            val lcTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(cacheLc), longArrayOf(1, 24, 70, 1024))
            val ltTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(cacheLt), longArrayOf(1, 24, 1024, 8))
            val lcLenTensor = createLongTensor(longArrayOf(cacheChannelLen), longArrayOf(1))
            val spk = FloatArray(Tenc) { 1f }
            val bg = FloatArray(Tenc) { 0f }
            val spkTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(spk), longArrayOf(1, Tenc.toLong()))
            val bgTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(bg), longArrayOf(1, Tenc.toLong()))
            var encTeThisChunk = -1
            try {
                val encInputs = mapOf(
                    "processed_signal" to melTensor,
                    "processed_signal_length" to lenTensor,
                    "cache_last_channel" to lcTensor,
                    "cache_last_time" to ltTensor,
                    "cache_last_channel_len" to lcLenTensor,
                    "spk_targets" to spkTensor,
                    "bg_spk_targets" to bgTensor,
                )
                encSession.run(encInputs).use { encResult ->
                    val encodedTensor = encResult[0] as OnnxTensor
                    val encLenTensor = encResult[1] as OnnxTensor
                    val lcNext = encResult[2] as OnnxTensor
                    val ltNext = encResult[3] as OnnxTensor
                    val lcLenNext = encResult[4] as OnnxTensor
                    val Te = encLenTensor.longBuffer.get(0).toInt()
                    encTeThisChunk = Te
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        if (chunkTag == 1) {
                            try {
                                val inf = encodedTensor.info
                                Log.d(
                                    TAG,
                                    "enc[0] type=${inf.type} onnxType=${inf.onnxType} shape=${inf.shape.contentToString()}",
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "enc tensor meta", e)
                            }
                        }
                        if (Te == 0) {
                            Log.w(TAG, "encoder Te=0 (no frames) chunk#$chunkTag")
                        }
                    }
                    val encBuf = encodedTensor.floatBuffer.duplicate()
                    encBuf.clear()
                    val encBth = FloatArray(Te * 1024)
                    for (c in 0 until 1024) {
                        for (t in 0 until Te) {
                            encBth[t * 1024 + c] = encBuf.get()
                        }
                    }
                    lcNext.floatBuffer.duplicate().apply { clear(); get(cacheLc) }
                    ltNext.floatBuffer.duplicate().apply { clear(); get(cacheLt) }
                    cacheChannelLen = lcLenNext.longBuffer.get(0)
                    runRnntOnChunk(encBth, Te)
                }
            } finally {
                melTensor.close()
                lenTensor.close()
                lcTensor.close()
                ltTensor.close()
                lcLenTensor.close()
                spkTensor.close()
                bgTensor.close()
            }
            val decoded = tokenizer.decodeTokenIds(emittedIds)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "chunk#$debugChunkCount summary Te=$encTeThisChunk totalIds=${emittedIds.size} textLen=${decoded.length} " +
                        "preview=\"${decoded.take(96)}\"",
                )
            }
            return decoded
        }
    }

    private fun runRnntOnChunk(encBth: FloatArray, Te: Int) {
        val encOne = FloatArray(1024)
        val encSliceFb = FloatBuffer.allocate(1024)
        val logitsScratch = FloatArray(1025)
        var totalRnntSteps = 0
        for (t in 0 until Te) {
            System.arraycopy(encBth, t * 1024, encOne, 0, 1024)
            var frameRnntSteps = 0
            while (true) {
                if (++totalRnntSteps > MAX_RNNT_STEPS_PER_CHUNK) {
                    Log.w(TAG, "rnnt: abort chunk — exceeded total step cap $MAX_RNNT_STEPS_PER_CHUNK (t=$t)")
                    return
                }
                if (++frameRnntSteps > MAX_RNNT_STEPS_PER_FRAME) {
                    Log.w(
                        TAG,
                        "rnnt: advance frame — exceeded per-frame cap $MAX_RNNT_STEPS_PER_FRAME at t=$t " +
                            "(blank=${ParakeetConstants.BLANK_ID} never won argmax; check targets init / logits)",
                    )
                    break
                }
                encSliceFb.clear()
                encSliceFb.put(encOne)
                encSliceFb.rewind()
                val encT = OnnxTensor.createTensor(env, encSliceFb, longArrayOf(1, 1, 1024))
                decTargetLongBuf.clear()
                decTargetLongBuf.put(prevLabel)
                decTargetLongBuf.rewind()
                val targets = OnnxTensor.createTensor(env, decTargetLongBuf, longArrayOf(1, 1))
                val s1Tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(decState1), longArrayOf(2, 1, 640))
                val s2Tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(decState2), longArrayOf(2, 1, 640))
                try {
                    val decInputs = mapOf(
                        "encoder_outputs" to encT,
                        "targets" to targets,
                        "input_states_1" to s1Tensor,
                        "input_states_2" to s2Tensor,
                    )
                    val decResult = decSession.run(decInputs)
                    try {
                        val outLogits = decResult[0] as OnnxTensor
                        val lb = outLogits.floatBuffer.duplicate()
                        lb.clear()
                        lb.get(logitsScratch)
                        (decResult[2] as OnnxTensor).floatBuffer.duplicate().apply { clear(); get(decState1) }
                        (decResult[3] as OnnxTensor).floatBuffer.duplicate().apply { clear(); get(decState2) }
                        var best = 0
                        var bestV = logitsScratch[0]
                        for (i in 1 until 1025) {
                            if (logitsScratch[i] > bestV) {
                                bestV = logitsScratch[i]
                                best = i
                            }
                        }
                        if (Log.isLoggable(TAG, Log.DEBUG) && !debugLoggedDecoderMeta && t == 0) {
                            debugLoggedDecoderMeta = true
                            try {
                                val inf = outLogits.info
                                Log.d(
                                    TAG,
                                    "dec[0] type=${inf.type} onnxType=${inf.onnxType} shape=${inf.shape.contentToString()}",
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "dec tensor meta", e)
                            }
                            var encMaxAbs = 0f
                            for (v in encOne) {
                                val a = kotlin.math.abs(v)
                                if (a > encMaxAbs) encMaxAbs = a
                            }
                            Log.d(
                                TAG,
                                "decoder first step argmax=$best score=$bestV blank=${ParakeetConstants.BLANK_ID} encMaxAbs=$encMaxAbs",
                            )
                        }
                        if (best == ParakeetConstants.BLANK_ID) {
                            break
                        }
                        emittedIds.add(best)
                        prevLabel = best.toLong()
                    } finally {
                        decResult.close()
                    }
                } finally {
                    encT.close()
                    targets.close()
                    s1Tensor.close()
                    s2Tensor.close()
                }
            }
        }
    }

    override fun close() {
        encSession.close()
        decSession.close()
    }

    /** ORT accepts direct [LongBuffer] + shape; [LongBuffer.wrap] on long[] yields a heap buffer and fails. */
    private fun createLongTensor(values: LongArray, shape: LongArray): OnnxTensor {
        val count = shape.fold(1L) { acc, d -> acc * d }
        require(values.size.toLong() == count) { "long tensor shape ${shape.contentToString()} needs ${count} values, got ${values.size}" }
        val bb = ByteBuffer.allocateDirect(8 * values.size).order(ByteOrder.nativeOrder())
        val lb = bb.asLongBuffer()
        for (v in values) lb.put(v)
        lb.rewind()
        return OnnxTensor.createTensor(env, lb, shape)
    }

    companion object {
        private const val TAG = "ParakeetASR"
        /** Same cap as parakeet-rs `MAX_SYMBOLS_PER_STEP` (NeMo greedy per encoded frame). */
        private const val MAX_RNNT_STEPS_PER_FRAME = 10
        private const val MAX_RNNT_STEPS_PER_CHUNK = 48_000
    }
}
