@file:JvmName("RnnoiseJni")

package com.whispertflite.asr

private var libraryLoaded = false

fun ensureRnnoiseLoaded() {
    synchronized(AudioCapturePreferences::class.java) {
        if (!libraryLoaded) {
            System.loadLibrary("rnnoise_jni")
            libraryLoaded = true
        }
    }
}

external fun rnnoiseCreate(): Long

external fun rnnoiseDestroy(handle: Long)

/** Denoise complete 10 ms frames (160 samples @ 16 kHz) in [merged] from index 0 up to [completeSamples]. */
external fun rnnoiseProcessFramesInPlace(handle: Long, merged: ShortArray, completeSamples: Int)
