package com.whispertflite.asr

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Attaches optional OEM [NoiseSuppressor] / [AcousticEchoCanceler] to an [AudioRecord] session.
 * Stacking with [android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION] may be redundant on some devices.
 */
class AudioCaptureEffects private constructor(
    private val noiseSuppressor: NoiseSuppressor?,
    private val acousticEchoCanceler: AcousticEchoCanceler?,
) {
    fun release() {
        try {
            noiseSuppressor?.enabled = false
        } catch (_: Exception) {
        }
        try {
            acousticEchoCanceler?.enabled = false
        } catch (_: Exception) {
        }
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AudioCaptureEffects"

        @JvmStatic
        fun attachIfRequested(
            record: AudioRecord,
            wantNoiseSuppressor: Boolean,
            wantAec: Boolean,
        ): AudioCaptureEffects? {
            val sessionId = record.audioSessionId
            var ns: NoiseSuppressor? = null
            var aec: AcousticEchoCanceler? = null
            if (wantNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(sessionId)
                    ns.enabled = true
                } catch (e: Exception) {
                    Log.w(TAG, "NoiseSuppressor attach failed", e)
                    try {
                        ns?.release()
                    } catch (_: Exception) {
                    }
                    ns = null
                }
            }
            if (wantAec && AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)
                    aec.enabled = true
                } catch (e: Exception) {
                    Log.w(TAG, "AcousticEchoCanceler attach failed", e)
                    try {
                        aec?.release()
                    } catch (_: Exception) {
                    }
                    aec = null
                }
            }
            return if (ns == null && aec == null) null else AudioCaptureEffects(ns, aec)
        }
    }
}
