package com.whispertflite.utils

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Shared HTTP file fetch for model downloads (Parakeet, Sherpa HF resolve URLs, …). */
object HttpFileDownloader {
    const val USER_AGENT: String = "whisperIME/1.0 (Android)"

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
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
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
