package com.whispertflite.parakeet

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Librosa-compatible log-mel (128 bins, n_fft=512, hop=160, win=400 padded to 512 Hann),
 * zero-pad 256 each side of PCM chunk, take first [MEL_TIME] frames (drop last STFT frame).
 */
class ParakeetMelExtractor(context: Context) {

    private val melBasis: Array<FloatArray> // [128][257]
    private val hann512: FloatArray

    init {
        val mbBytes = context.assets.open(ParakeetConstants.ASSETS_MEL_BASIS).use { it.readBytes() }
        val mb = ByteBuffer.wrap(mbBytes).order(ByteOrder.nativeOrder())
        val basisFlat = FloatArray(128 * 257)
        for (i in basisFlat.indices) basisFlat[i] = mb.float
        melBasis = Array(128) { r ->
            FloatArray(257) { c -> basisFlat[r * 257 + c] }
        }
        val hbBytes = context.assets.open(ParakeetConstants.ASSETS_HANN).use { it.readBytes() }
        val hb = ByteBuffer.wrap(hbBytes).order(ByteOrder.nativeOrder())
        hann512 = FloatArray(512) { hb.float }
    }

    fun pcm16ToMelLog(pcm: ShortArray): FloatArray {
        require(pcm.size == ParakeetConstants.CHUNK_PCM_SAMPLES) { "Expected ${ParakeetConstants.CHUNK_PCM_SAMPLES} samples" }
        val pad = ParakeetConstants.N_FFT / 2
        val paddedLen = pcm.size + 2 * pad
        val padded = FloatArray(paddedLen)
        for (i in pcm.indices) {
            padded[i + pad] = pcm[i] / 32768f
        }
        val tFrames = 1 + (paddedLen - ParakeetConstants.N_FFT) / ParakeetConstants.HOP_LENGTH
        val takeFrames = minOf(ParakeetConstants.MEL_TIME, tFrames)
        val mel = FloatArray(ParakeetConstants.MEL_BINS * ParakeetConstants.MEL_TIME)
        val re = FloatArray(512)
        val im = FloatArray(512)
        val power = FloatArray(257)
        for (t in 0 until takeFrames) {
            val start = t * ParakeetConstants.HOP_LENGTH
            for (i in 0 until 512) {
                re[i] = padded[start + i] * hann512[i]
                im[i] = 0f
            }
            fftRadix2(re, im)
            for (k in 0 until 257) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }
            for (m in 0 until ParakeetConstants.MEL_BINS) {
                var sum = 0f
                val row = melBasis[m]
                for (k in 0 until 257) {
                    sum += row[k] * power[k]
                }
                val logMel = ln(max(sum, 1e-10f).toDouble()).toFloat()
                mel[m * ParakeetConstants.MEL_TIME + t] = logMel
            }
        }
        if (takeFrames < ParakeetConstants.MEL_TIME) {
            for (m in 0 until ParakeetConstants.MEL_BINS) {
                for (t in takeFrames until ParakeetConstants.MEL_TIME) {
                    mel[m * ParakeetConstants.MEL_TIME + t] = ln(1e-10f)
                }
            }
        }
        return mel
    }

    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val ang = -2.0 * PI / len
            val wlenR = cos(ang).toFloat()
            val wlenI = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vIdx = i + k + halfLen
                    val vR = re[vIdx] * wR - im[vIdx] * wI
                    val vI = re[vIdx] * wI + im[vIdx] * wR
                    re[vIdx] = uR - vR
                    im[vIdx] = uI - vI
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    val nwR = wR * wlenR - wI * wlenI
                    val nwI = wR * wlenI + wI * wlenR
                    wR = nwR
                    wI = nwI
                }
                i += len
            }
            len *= 2
        }
    }
}
