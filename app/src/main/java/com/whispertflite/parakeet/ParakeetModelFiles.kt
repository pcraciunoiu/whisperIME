package com.whispertflite.parakeet

import java.io.File

/**
 * Locations of the sherpa-onnx Parakeet model files, all under [ParakeetConstants.MODEL_DIR] inside the
 * app's external files dir (where [ParakeetDownloader] extracts the archive).
 */
object ParakeetModelFiles {
    @JvmStatic
    fun encoderFile(dir: File): File = File(dir, ParakeetConstants.ENCODER_FILE)

    @JvmStatic
    fun decoderFile(dir: File): File = File(dir, ParakeetConstants.DECODER_FILE)

    @JvmStatic
    fun joinerFile(dir: File): File = File(dir, ParakeetConstants.JOINER_FILE)

    @JvmStatic
    fun tokensFile(dir: File): File = File(dir, ParakeetConstants.TOKENS_FILE)

    @JvmStatic
    fun readyMarkerFile(dir: File): File = File(dir, ParakeetConstants.READY_MARKER_FILE)

    /**
     * Name kept for call-site compatibility: true once the model is *fully* downloaded and verified.
     *
     * Requires the [ParakeetConstants.READY_MARKER_FILE] marker (written only after every file passed
     * size verification) and re-checks each file's exact byte size. This is the gate that prevents
     * feeding a truncated .onnx to sherpa-onnx, which would abort the process uncatchably.
     */
    @JvmStatic
    fun allOnnxPresent(dir: File?): Boolean {
        if (dir == null) return false
        if (!readyMarkerFile(dir).isFile) return false
        return ParakeetConstants.MODEL_FILES.all { spec ->
            val f = File(dir, "${ParakeetConstants.MODEL_DIR}/${spec.name}")
            f.isFile && f.length() == spec.sizeBytes
        }
    }
}
