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

        if (!likelyHasPunctuation(t) && t.length >= 8 && t.any { it.isLetter() }) {
            val last = t.last()
            if (!last.isLetterOrDigit() && last !in ")]}\"'") {
                // already ends with some symbol; skip
            } else if (last.isLetterOrDigit() || last == ')') {
                t = t + "."
            }
        }

        t = normalizeShoutedCasing(t)
        return t
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
        // Skip if already mostly lowercase / mixed (e.g. model fixed casing).
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
