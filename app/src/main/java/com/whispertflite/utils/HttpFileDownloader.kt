package com.whispertflite.utils

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Shared HTTP file fetch for model downloads (Parakeet, Sherpa HF resolve URLs, …). */
object HttpFileDownloader {
    private const val TAG = "HttpFileDownloader"

    /** Hugging Face Hub tolerates a normal browser-like UA better than a bare custom string. */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 whisperIME/1.0"

    @JvmStatic
    fun downloadFileToFile(
        urlStr: String,
        outFile: File,
        onBytes: (Long) -> Unit,
    ) {
        downloadFileToFile(urlStr, outFile, onBytes, USER_AGENT)
    }

    @JvmStatic
    fun downloadFileToFile(
        urlStr: String,
        outFile: File,
        onBytes: (Long) -> Unit,
        userAgent: String,
    ) {
        outFile.parentFile?.mkdirs()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "*/*")
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            Log.e(TAG, "HTTP $code for $urlStr — $err")
            throw IOException("HTTP $code (expected 2xx) for $urlStr")
        }
        conn.inputStream.use { raw ->
            BufferedInputStream(raw, 8192).use { ins ->
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = ins.read(buf)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        onBytes(r.toLong())
                    }
                    fos.flush()
                }
            }
        }
    }
}
