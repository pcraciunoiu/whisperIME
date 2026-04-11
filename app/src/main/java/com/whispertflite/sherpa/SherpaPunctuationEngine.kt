package com.whispertflite.sherpa

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import java.io.File

/**
 * Lazy-loaded sherpa-onnx punctuation ONNX ([OnlinePunctuation] / [OfflinePunctuation]).
 * Requires [SherpaOnnxNativeBootstrap.loadSherpaJniStack] before first use.
 */
internal object SherpaPunctuationEngine {
    private const val TAG = "SherpaPunctEngine"

    private val lock = Any()
    private var loadedId: String? = null
    private var online: OnlinePunctuation? = null
    private var offline: OfflinePunctuation? = null

    @JvmStatic
    fun invalidate() {
        synchronized(lock) {
            try {
                online?.release()
            } catch (_: Exception) {
            }
            try {
                offline?.release()
            } catch (_: Exception) {
            }
            online = null
            offline = null
            loadedId = null
        }
    }

    @JvmStatic
    fun addPunctuation(context: Context, text: String, entry: SherpaPunctCatalogEntry): String {
        if (entry.kind == SherpaPunctKind.HEURISTIC) return text
        val ext = context.getExternalFilesDir(null)
        if (!SherpaPunctuationModelFiles.allFilesPresentForEntry(ext, entry)) {
            return text
        }
        synchronized(lock) {
            if (loadedId != entry.id) {
                invalidate()
                SherpaOnnxNativeBootstrap.loadSherpaJniStack(context.applicationContext)
                val dir = SherpaPunctuationModelFiles.modelDir(ext, entry)
                    ?: throw IllegalStateException("no model dir")
                when (entry.kind) {
                    SherpaPunctKind.HEURISTIC -> {
                        /* unreachable: early return above */
                    }
                    SherpaPunctKind.ONLINE_CNN_BILSTM -> {
                        val mc = OnlinePunctuationModelConfig(
                            File(dir, "model.int8.onnx").absolutePath,
                            File(dir, "bpe.vocab").absolutePath,
                            1,
                            false,
                            "cpu",
                        )
                        online = OnlinePunctuation(null, OnlinePunctuationConfig(mc))
                    }
                    SherpaPunctKind.OFFLINE_CT_ZH_EN -> {
                        val mc = OfflinePunctuationModelConfig(
                            File(dir, "model.int8.onnx").absolutePath,
                            1,
                            false,
                            "cpu",
                        )
                        offline = OfflinePunctuation(null, OfflinePunctuationConfig(mc))
                    }
                }
                loadedId = entry.id
            }
            return when (entry.kind) {
                SherpaPunctKind.HEURISTIC -> text
                SherpaPunctKind.ONLINE_CNN_BILSTM -> checkNotNull(online).addPunctuation(text)
                SherpaPunctKind.OFFLINE_CT_ZH_EN -> checkNotNull(offline).addPunctuation(text)
            }
        }
    }
}
