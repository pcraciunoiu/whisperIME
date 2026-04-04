package com.whispertflite.parakeet

import java.io.File

object ParakeetModelFiles {
    @JvmStatic
    fun encoderFile(dir: File): File = File(dir, ParakeetConstants.ENCODER_FILE)

    @JvmStatic
    fun decoderFile(dir: File): File = File(dir, ParakeetConstants.DECODER_FILE)

    @JvmStatic
    fun allOnnxPresent(dir: File?): Boolean {
        if (dir == null) return false
        return encoderFile(dir).isFile && decoderFile(dir).isFile &&
            encoderFile(dir).length() > 1_000_000L && decoderFile(dir).length() > 1000L
    }
}
