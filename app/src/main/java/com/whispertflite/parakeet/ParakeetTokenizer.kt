package com.whispertflite.parakeet

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/** SentencePiece id → piece table (1024 rows), shipped as TSV from tokenizer.model. */
class ParakeetTokenizer(context: Context) {
    private val pieces: Array<String> = Array(ParakeetConstants.VOCAB_SIZE) { "" }

    init {
        BufferedReader(
            InputStreamReader(context.assets.open(ParakeetConstants.ASSETS_VOCAB), Charsets.UTF_8),
        ).useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val id = line.substring(0, tab).toIntOrNull() ?: return@forEach
                    if (id in pieces.indices) {
                        pieces[id] = line.substring(tab + 1)
                    }
                }
            }
        }
    }

    fun decodeTokenIds(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in 0 until ParakeetConstants.VOCAB_SIZE) {
                sb.append(pieces[id])
            }
        }
        return sb.toString().replace('\u2581', ' ').trim()
    }
}
