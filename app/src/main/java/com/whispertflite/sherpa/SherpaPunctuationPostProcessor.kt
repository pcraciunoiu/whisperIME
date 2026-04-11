package com.whispertflite.sherpa

import android.content.Context
import java.util.Locale

/**
 * Offline, lightweight polish for Sherpa streaming output: sentence-style casing and terminal punctuation
 * when the ASR string has none. Does **not** call the network; optional and user-toggleable.
 *
 * When models already emit commas / periods, this mostly leaves text unchanged (see [likelyHasPunctuation]).
 */
object SherpaPunctuationPostProcessor {

    @JvmStatic
    fun applyFinal(context: Context, raw: String?): String {
        if (raw == null) return ""
        var t = raw.trim()
        if (t.isEmpty()) return t
        if (!SherpaPreferences.isPunctuationEnhanceEnabled(context)) return t

        if (!likelyHasPunctuation(t) && t.length >= 24 && t.any { it.isLetter() }) {
            val last = t.last()
            if (!last.isLetterOrDigit() && last !in ")]}\"'") {
                // already ends with some symbol; skip
            } else if (last.isLetterOrDigit() || last == ')') {
                t = t + "."
            }
        }

        t = normalizeAllCapsWords(t)
        return t
    }

    private fun likelyHasPunctuation(s: String): Boolean {
        var n = 0
        for (c in s) {
            if (c == '.' || c == '?' || c == '!' || c == ',' || c == ':' || c == ';') n++
        }
        return n >= 1 && (n * 4 >= s.length / 8 || s.contains(','))
    }

    /** If the line is mostly ALL CAPS words, convert to sentence case for readability. */
    private fun normalizeAllCapsWords(s: String): String {
        val letters = s.count { it.isLetter() }
        if (letters < 8) return s
        val upperLetters = s.count { it.isLetter() && it.isUpperCase() }
        if (upperLetters * 10 < letters * 7) return s

        val lower = s.lowercase(Locale.getDefault())
        return lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
