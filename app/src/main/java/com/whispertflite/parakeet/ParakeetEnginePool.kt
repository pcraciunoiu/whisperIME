package com.whispertflite.parakeet

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Keeps a single [ParakeetStreamingEngine] (ONNX sessions) per models directory so each hold does not
 * pay full session creation cost. [ParakeetStreamingRecorder] holds [sessionLock] for the whole worker
 * run and calls [releaseAfterHold] before unlocking.
 */
object ParakeetEnginePool {
    private const val TAG = "ParakeetASR"

    private val sessionLock = ReentrantLock()

    @Volatile
    private var pooled: ParakeetStreamingEngine? = null

    @Volatile
    private var pooledDir: String? = null

    private val poolInitLock = Any()

    fun lockSession() {
        sessionLock.lock()
    }

    fun unlockSession() {
        sessionLock.unlock()
    }

    /**
     * Must be called with [sessionLock] held (same thread as [lockSession]).
     */
    fun borrowEngine(context: Context, modelsDir: File): ParakeetStreamingEngine {
        val path = modelsDir.absolutePath
        synchronized(poolInitLock) {
            if (pooled != null && pooledDir == path) {
                Log.i(TAG, "borrowEngine: reusing pooled ONNX sessions")
                return pooled!!
            }
            Log.i(TAG, "borrowEngine: loading ONNX sessions (cold start)")
            pooled?.close()
            pooled = null
            pooledDir = null
            val eng = ParakeetStreamingEngine(context.applicationContext, modelsDir)
            pooled = eng
            pooledDir = path
            return eng
        }
    }

    /**
     * Reset decoder state after a hold; keeps encoder/decoder sessions open.
     * Must be called with [sessionLock] held.
     */
    fun releaseAfterHold(engine: ParakeetStreamingEngine?) {
        if (engine == null) return
        try {
            engine.resetSession()
        } catch (e: Exception) {
            Log.w(TAG, "releaseAfterHold: resetSession failed", e)
        }
    }

    /**
     * Close pooled sessions (e.g. after re-downloading ONNX files on disk).
     */
    fun invalidate() {
        sessionLock.lock()
        try {
            synchronized(poolInitLock) {
                pooled?.close()
                pooled = null
                pooledDir = null
            }
        } finally {
            sessionLock.unlock()
        }
    }

    /**
     * Load ONNX in a background thread so the next hold avoids multi-second delay.
     * Skips if a session is already active or another thread holds the lock.
     */
    @JvmStatic
    fun warm(context: Context, modelsDir: File?) {
        if (modelsDir == null || !ParakeetModelFiles.allOnnxPresent(modelsDir)) return
        val appCtx = context.applicationContext
        Thread(
            {
                if (!sessionLock.tryLock()) {
                    Log.d(TAG, "preheat: skipped (Parakeet session active)")
                    return@Thread
                }
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    val e = borrowEngine(appCtx, modelsDir)
                    e.resetSession()
                    Log.i(TAG, "preheat: done in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "preheat failed", e)
                } finally {
                    sessionLock.unlock()
                }
            },
            "ParakeetPreheat",
        ).start()
    }
}
