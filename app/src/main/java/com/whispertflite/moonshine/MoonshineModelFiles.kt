package com.whispertflite.moonshine

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object MoonshineModelFiles {
    private const val TAG = "MoonshineModelFiles"

    /** True if this process can load arm64-v8a native code (hardware or ARM64 emulator image). */
    @JvmStatic
    fun hasArm64V8aAbi(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /**
     * Loads Moonshine JNI and its dependencies in a safe order so the single packaged
     * [libonnxruntime.so] is bound before [libmoonshine.so] resolves symbols.
     *
     * Call only when [hasArm64V8aAbi] is true (no-op otherwise).
     */
    @JvmStatic
    fun loadMoonshineNativeLibraries(): Boolean {
        if (!hasArm64V8aAbi()) {
            Log.w(TAG, "Skipping Moonshine native load: no arm64-v8a in ${Build.SUPPORTED_ABIS.contentToString()}")
            return false
        }
        return try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("moonshine")
            System.loadLibrary("moonshine-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Moonshine native load failed (onnxruntime → moonshine → moonshine-jni)", e)
            false
        }
    }

    /**
     * Moonshine ships [libmoonshine-jni.so] for **arm64-v8a** only (see moonshine-voice AAR).
     * Probes [loadMoonshineNativeLibraries] after confirming arm64 ABI.
     */
    @JvmStatic
    fun isDeviceSupported(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        if (!hasArm64V8aAbi()) return false
        return loadMoonshineNativeLibraries()
    }

    @JvmStatic
    fun baseEnDir(context: Context): File =
        File(context.filesDir, "moonshine/${MoonshineConstants.MODEL_ASSET_SUBDIR}")

    /**
     * True when encoder/decoder/tokenizer exist under [baseEnDir] with plausible sizes.
     * Does **not** call [isDeviceSupported]; use after download to verify extraction without
     * conflating “native library failed” with “download failed”.
     */
    @JvmStatic
    fun hasMoonshineBaseModelFilesOnDisk(context: Context): Boolean {
        val d = baseEnDir(context)
        val enc = File(d, "encoder_model.ort")
        val dec = File(d, "decoder_model_merged.ort")
        val tok = File(d, "tokenizer.bin")
        return enc.isFile && enc.length() > 1_000_000L &&
            dec.isFile && dec.length() > 1_000_000L &&
            tok.isFile && tok.length() > 100L
    }

    /** Device can load Moonshine JNI **and** model files are present. */
    @JvmStatic
    fun allModelFilesPresent(context: Context): Boolean {
        if (!isDeviceSupported(context)) return false
        return hasMoonshineBaseModelFilesOnDisk(context)
    }
}
