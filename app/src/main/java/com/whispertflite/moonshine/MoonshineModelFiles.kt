package com.whispertflite.moonshine

import android.content.Context
import android.os.Build
import java.io.File

object MoonshineModelFiles {

    /**
     * Moonshine ships [libmoonshine-jni.so] for **arm64-v8a** only (see moonshine-voice AAR).
     *
     * With [android:extractNativeLibs] false (AGP default), `.so` files are often **not** present as
     * standalone files under [ApplicationInfo.nativeLibraryDir]; they are loaded from the APK. So we
     * probe with [System.loadLibrary] after confirming the process prefers arm64-v8a.
     */
    @JvmStatic
    fun isDeviceSupported(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return false
        return try {
            System.loadLibrary("moonshine-jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @JvmStatic
    fun baseEnDir(context: Context): File =
        File(context.filesDir, "moonshine/${MoonshineConstants.MODEL_ASSET_SUBDIR}")

    @JvmStatic
    fun allModelFilesPresent(context: Context): Boolean {
        if (!isDeviceSupported(context)) return false
        val d = baseEnDir(context)
        val enc = File(d, "encoder_model.ort")
        val dec = File(d, "decoder_model_merged.ort")
        val tok = File(d, "tokenizer.bin")
        return enc.isFile && enc.length() > 1_000_000L &&
            dec.isFile && dec.length() > 1_000_000L &&
            tok.isFile && tok.length() > 100L
    }
}
