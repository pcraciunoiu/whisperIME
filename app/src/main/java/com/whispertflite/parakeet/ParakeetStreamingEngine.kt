package com.whispertflite.parakeet

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * Offline Parakeet recognizer backed by sherpa-onnx. Despite the legacy name, this is a non-streaming
 * (batch) recognizer: feed a full hold's PCM to [transcribe] and get the final text.
 *
 * Class/method names kept stable so [ParakeetEnginePool] and the call sites need no changes.
 */
class ParakeetStreamingEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    modelsDir: File,
) : AutoCloseable {

    private val recognizer: OfflineRecognizer
    private val lock = Any()

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = ParakeetConstants.SAMPLE_RATE,
                featureDim = ParakeetConstants.FEATURE_DIM,
                dither = 0f,
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = ParakeetModelFiles.encoderFile(modelsDir).absolutePath,
                    decoder = ParakeetModelFiles.decoderFile(modelsDir).absolutePath,
                    joiner = ParakeetModelFiles.joinerFile(modelsDir).absolutePath,
                ),
                tokens = ParakeetModelFiles.tokensFile(modelsDir).absolutePath,
                modelType = ParakeetConstants.MODEL_TYPE,
                numThreads = 2,
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(config = config)
    }

    /** Decode 16 kHz mono float PCM (range -1..1). Returns the recognized text (may be empty). */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        synchronized(lock) {
            val stream = recognizer.createStream()
            return try {
                stream.acceptWaveform(samples, ParakeetConstants.SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed", e)
                ""
            } finally {
                stream.release()
            }
        }
    }

    /** No streaming state to reset; kept for [ParakeetEnginePool] compatibility. */
    fun resetSession() {}

    /** Batch engine keeps no intermediate transcript. */
    fun snapshotTranscript(): String = ""

    override fun close() {
        synchronized(lock) {
            try {
                recognizer.release()
            } catch (e: Exception) {
                Log.w(TAG, "recognizer.release failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "ParakeetASR"
    }
}
