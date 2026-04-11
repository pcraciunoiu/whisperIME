package com.whispertflite.asr

import java.io.File
import java.util.ArrayList

/**
 * whisper.cpp expects GGML checkpoints (typically `ggml-*.bin` from Hugging Face
 * [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp) or `.gguf` builds).
 */
object WhisperGgmlModels {
    private const val TFLITE_EN_SUFFIX = ".en.tflite"

    /** Quantized English tiny model from ggerganov/whisper.cpp (fast CPU inference). */
    const val GGML_TINY_EN_Q5_1 = "ggml-tiny.en-q5_1.bin"

    /**
     * ggerganov English checkpoints: `ggml-tiny.en.bin`, `ggml-tiny.en-q5_1.bin` (note `.en-`, not `.en.`).
     */
    private val GGML_ENGLISH_NAME = Regex("""\.(tiny|base|small|medium|large)\.en""")

    @JvmStatic
    fun isGgmlEnglishOnlyFilename(filename: String): Boolean =
        isGgmlModelPath(filename) && GGML_ENGLISH_NAME.containsMatchIn(filename.lowercase())

    @JvmStatic
    fun isGgmlModelPath(path: String): Boolean {
        val lower = path.lowercase()
        if (lower.endsWith(".gguf")) return true
        if (!lower.endsWith(".bin")) return false
        val base = path.substringAfterLast('/').lowercase()
        return base.startsWith("ggml-")
    }

    /**
     * Multilingual vs English-only: DocWolle `.en.tflite`; ggml English models use `tiny.en`, `base.en`, etc.
     * (including `tiny.en-q5_1` — must not require the substring `.en.`).
     */
    @JvmStatic
    fun isMultilingualModelFilename(filename: String): Boolean {
        val lower = filename.lowercase()
        if (lower.endsWith(".tflite")) {
            return !lower.endsWith(TFLITE_EN_SUFFIX)
        }
        if (isGgmlModelPath(filename)) {
            return !isGgmlEnglishOnlyFilename(filename)
        }
        return true
    }

    /** `.tflite` plus ggml `.bin` / `.gguf` models under [dir]. */
    @JvmStatic
    fun listDownloadedModelFiles(dir: File): ArrayList<File> {
        val out = ArrayList<File>()
        if (!dir.exists()) return out
        val files = dir.listFiles() ?: return out
        for (f in files) {
            if (!f.isFile) continue
            val n = f.name.lowercase()
            if (n.endsWith(".tflite") || isGgmlModelPath(f.absolutePath)) {
                out.add(f)
            }
        }
        out.sortWith(compareBy { it.name.lowercase() })
        return out
    }
}
