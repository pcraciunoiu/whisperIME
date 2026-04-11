package com.whispertflite.sherpa

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Final-text polish after Sherpa streaming: optional **neural** punctuation (k2-fsa ONNX via
 * [SherpaPunctuationEngine]) when a model is selected and on disk, otherwise **heuristics**
 * (terminal period + shouted-casing fold).
 */
object SherpaPunctuationPostProcessor {
    private const val TAG = "SherpaPunctPost"

    @JvmStatic
    fun applyFinal(context: Context, raw: String?): String {
        if (raw == null) return ""
        var t = raw.trim()
        if (t.isEmpty()) return t
        if (!SherpaPreferences.isPunctuationEnhanceEnabled(context)) return t

        val entry = SherpaPunctCatalogEntry.requireById(SherpaPreferences.selectedPunctModelId(context))
        val ext = context.getExternalFilesDir(null)
        if (entry.kind != SherpaPunctKind.HEURISTIC &&
            SherpaPunctuationModelFiles.allFilesPresentForEntry(ext, entry)
        ) {
            try {
                return SherpaPunctuationEngine.addPunctuation(context.applicationContext, t, entry)
            } catch (e: Exception) {
                Log.e(TAG, "neural punctuation failed, falling back to heuristics", e)
            }
        }
        return applyHeuristicsOnly(t)
    }

    private fun applyHeuristicsOnly(t: String): String {
        var x = t
        if (!likelyHasPunctuation(x) && x.length >= 8 && x.any { it.isLetter() }) {
            val last = x.last()
            if (!last.isLetterOrDigit() && last !in ")]}\"'") {
                // already ends with some symbol; skip
            } else if (last.isLetterOrDigit() || last == ')') {
                x = x + "."
            }
        }
        return normalizeShoutedCasing(x)
    }

    private fun likelyHasPunctuation(s: String): Boolean {
        var n = 0
        for (c in s) {
            if (c == '.' || c == '?' || c == '!' || c == ',' || c == ':' || c == ';') n++
        }
        return n >= 1 && (n * 4 >= s.length / 8 || s.contains(','))
    }

    /**
     * Many Sherpa English checkpoints emit **ALL CAPS** with sparse punctuation. If there is almost no
     * lowercase, fold to sentence-style casing (not title-case per word).
     */
    private fun normalizeShoutedCasing(s: String): String {
        val letters = s.count { it.isLetter() }
        if (letters < 3) return s
        val lowerLetters = s.count { it.isLetter() && it.isLowerCase() }
        if (lowerLetters * 10 > letters * 3) return s

        val folded = s.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        var capNext = true
        for (ch in folded) {
            if (capNext && ch.isLetter()) {
                sb.append(ch.titlecase(Locale.getDefault()))
                capNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?') {
                    capNext = true
                }
            }
        }
        return sb.toString()
    }
}
