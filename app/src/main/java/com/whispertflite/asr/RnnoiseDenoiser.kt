package com.whispertflite.asr

import android.content.Context

/**
 * Stateful RNNoise denoising for 16 kHz mono PCM (10 ms = 160 samples per internal frame).
 * Uses carry between [processBuffer] calls so arbitrary [AudioRecord.read] sizes work.
 */
class RnnoiseDenoiser private constructor(private val handle: Long) {
    private var carry = ShortArray(0)
    private var byteScratch = ShortArray(2048)

    fun processBuffer(buf: ShortArray, length: Int) {
        if (handle == 0L || length <= 0) return
        val co = carry.size
        val merged = ShortArray(co + length)
        if (co > 0) System.arraycopy(carry, 0, merged, 0, co)
        System.arraycopy(buf, 0, merged, co, length)
        val total = merged.size
        val complete = (total / 160) * 160
        if (complete > 0) {
            rnnoiseProcessFramesInPlace(handle, merged, complete)
        }
        for (i in 0 until length) {
            buf[i] = merged[co + i]
        }
        carry =
            if (complete < total) {
                merged.copyOfRange(complete, total)
            } else {
                ShortArray(0)
            }
    }

    /** Little-endian PCM16 in [audioData] for [bytesRead] bytes (must be even). */
    fun processPcm16Bytes(audioData: ByteArray, bytesRead: Int) {
        if (handle == 0L || bytesRead < 2) return
        val nShort = bytesRead / 2
        if (byteScratch.size < nShort) byteScratch = ShortArray(nShort)
        var j = 0
        for (i in 0 until nShort) {
            val lo = audioData[j++].toInt() and 0xff
            val hi = audioData[j++].toInt() and 0xff
            byteScratch[i] = ((hi shl 8) or lo).toShort()
        }
        processBuffer(byteScratch, nShort)
        var k = 0
        for (i in 0 until nShort) {
            val v = byteScratch[i].toInt()
            audioData[k++] = (v and 0xff).toByte()
            audioData[k++] = ((v shr 8) and 0xff).toByte()
        }
    }

    fun release() {
        if (handle != 0L) {
            rnnoiseDestroy(handle)
        }
        carry = ShortArray(0)
    }

    companion object {
        @JvmStatic
        fun createIfEnabled(context: Context): RnnoiseDenoiser? {
            if (!AudioCapturePreferences.rnnoiseDenoiseEnabled(context)) return null
            ensureRnnoiseLoaded()
            val h = rnnoiseCreate()
            if (h == 0L) return null
            return RnnoiseDenoiser(h)
        }
    }
}
