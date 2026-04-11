package com.whispertflite.sherpa

import android.content.Context
import android.util.Log
import dalvik.system.BaseDexClassLoader
import java.io.File

/**
 * Loads Sherpa native libraries on the **same thread** that will construct [com.k2fsa.sherpa.onnx.OnlineRecognizer].
 *
 * The app ships Microsoft’s [libonnxruntime.so] (Moonshine/Parakeet) and Sherpa’s ORT as [libonnxruntime_sherpa.so].
 * Preloading on the UI thread then loading JNI on a worker thread can fail on some Android builds; we load both on the
 * worker.
 *
 * Loading order: prefer [BaseDexClassLoader.findLibrary] + [System.load] so paths match libraries embedded in the APK
 * (when [ApplicationInfo.nativeLibraryDir] has no extracted copies). Then [System.loadLibrary], then files under
 * [Context.getApplicationInfo].nativeLibraryDir.
 */
internal object SherpaOnnxNativeBootstrap {
    private const val TAG = "SherpaOnnxBootstrap"

    /**
     * Must be called from the Sherpa worker thread before [com.k2fsa.sherpa.onnx.OnlineRecognizer] is referenced.
     */
    @JvmStatic
    fun loadSherpaJniStack(context: Context) {
        val cl = context.classLoader as? BaseDexClassLoader
        val ortPath = cl?.findLibrary("onnxruntime_sherpa")
        val jniPath = cl?.findLibrary("sherpa-onnx-jni")
        if (ortPath != null && jniPath != null) {
            try {
                System.load(ortPath)
                System.load(jniPath)
                Log.d(TAG, "Sherpa native stack loaded (ClassLoader paths) thread=${Thread.currentThread().name}")
                return
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "System.load(ClassLoader paths) failed, trying loadLibrary", e)
            }
        } else {
            Log.w(TAG, "findLibrary incomplete: onnxruntime_sherpa=$ortPath sherpa-onnx-jni=$jniPath")
        }

        try {
            System.loadLibrary("onnxruntime_sherpa")
            System.loadLibrary("sherpa-onnx-jni")
            Log.d(TAG, "Sherpa native stack loaded (loadLibrary)")
            return
        } catch (e: UnsatisfiedLinkError) {
            val dir = context.applicationInfo.nativeLibraryDir
            Log.w(TAG, "loadLibrary failed, retrying with files under $dir", e)
            try {
                System.load(File(dir, "libonnxruntime_sherpa.so").absolutePath)
                System.load(File(dir, "libsherpa-onnx-jni.so").absolutePath)
                Log.d(TAG, "Sherpa native stack loaded (nativeLibraryDir)")
            } catch (e2: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load Sherpa native libraries", e2)
                throw e2
            }
        }
    }
}
