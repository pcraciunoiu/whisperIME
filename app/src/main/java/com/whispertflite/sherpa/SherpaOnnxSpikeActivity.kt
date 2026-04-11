package com.whispertflite.sherpa

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import com.whispertflite.R
import com.whispertflite.utils.ThemeUtils
import com.whispertflite.sherpa.absolutizeModelConfig
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "SherpaOnnxSpike"
private const val REQ_AUDIO = 301
private const val SAMPLE_RATE = 16000

/**
 * Debug spike: streaming English Zipformer via sherpa-onnx ([getModelConfig] type 6).
 * Models are read from disk under [SherpaOnnxSpikePaths.MODELS_SUBDIR]; see docs/offline-asr-research.md.
 */
class SherpaOnnxSpikeActivity : AppCompatActivity() {

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sherpa_spike)
        ThemeUtils.setStatusBarAppearance(this)

        val status = findViewById<TextView>(R.id.sherpa_spike_status)
        val record = findViewById<Button>(R.id.sherpa_spike_record)
        val transcript = findViewById<TextView>(R.id.sherpa_spike_transcript)

        refreshStatus(status)

        record.setOnClickListener {
            if (!SherpaOnnxSpikePaths.isModelPresent(getExternalFilesDir(null))) {
                refreshStatus(status)
                return@setOnClickListener
            }
            if (!haveMicPermission()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQ_AUDIO,
                )
                return@setOnClickListener
            }
            if (!isRecording) {
                startRecording(status, record, transcript)
            } else {
                stopRecording(record)
            }
        }

        try {
            if (SherpaOnnxSpikePaths.isModelPresent(getExternalFilesDir(null))) {
                initRecognizer()
                status.text = getString(R.string.sherpa_spike_ready)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "initRecognizer failed", e)
            if (e is UnsatisfiedLinkError || e.cause is UnsatisfiedLinkError) {
                Log.e(
                    TAG,
                    "Native link error: ensure libonnxruntime.so matches sherpa AAR (see overlaySherpaOnnxRuntime in app/build.gradle). " +
                        "If Parakeet/Moonshine break, ORT symbol mix may need a different packaging strategy.",
                )
            }
            status.text = getString(
                R.string.sherpa_spike_init_failed,
                e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun refreshStatus(status: TextView) {
        val ok = SherpaOnnxSpikePaths.isModelPresent(getExternalFilesDir(null))
        val dir = SherpaOnnxSpikePaths.modelDirectory(getExternalFilesDir(null))
        status.text = if (ok) {
            getString(R.string.sherpa_spike_model_ok, dir?.absolutePath ?: "")
        } else {
            getString(R.string.sherpa_spike_model_missing, dir?.absolutePath ?: "")
        }
    }

    private fun haveMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun initRecognizer() {
        logNativeLibraryDiagnostics()
        val external = getExternalFilesDir(null)
            ?: throw IllegalStateException("no external files dir")
        val modelsRoot = File(external, SherpaOnnxSpikePaths.MODELS_SUBDIR)
        val rel = getModelConfig(6)
            ?: throw IllegalStateException("getModelConfig(6)==null")
        val mcfg = absolutizeModelConfig(rel, modelsRoot)
        logModelFileDiagnostics(mcfg)
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(SAMPLE_RATE, 80),
            modelConfig = mcfg,
            endpointConfig = getEndpointConfig(),
            enableEndpoint = true,
        )
        recognizer = OnlineRecognizer(null, config)
        Log.i(TAG, "OnlineRecognizer ready (files under ${modelsRoot.absolutePath})")
    }

    /** Log packaged .so names and sizes — helps debug OrtGetApiBase / ORT mismatch. */
    private fun logNativeLibraryDiagnostics() {
        val dir = applicationInfo.nativeLibraryDir?.let { File(it) } ?: run {
            Log.w(TAG, "nativeLibraryDir is null")
            return
        }
        Log.i(TAG, "nativeLibraryDir=${dir.absolutePath}")
        val names = dir.list()?.filter { it.endsWith(".so") }?.sorted() ?: emptyList()
        Log.i(TAG, "native .so count=${names.size}: ${names.joinToString(", ")}")
        for (name in listOf("libonnxruntime.so", "libsherpa-onnx-jni.so", "libonnxruntime4j_jni.so")) {
            val f = File(dir, name)
            if (f.isFile) {
                Log.i(TAG, "  $name size=${f.length()} bytes")
            } else {
                Log.w(TAG, "  missing $name")
            }
        }
    }

    private fun logModelFileDiagnostics(mcfg: OnlineModelConfig) {
        val paths = listOf(
            mcfg.transducer.encoder,
            mcfg.transducer.decoder,
            mcfg.transducer.joiner,
            mcfg.tokens,
        )
        for (p in paths) {
            val f = File(p)
            Log.i(
                TAG,
                "model file ${f.name} exists=${f.isFile} size=${if (f.isFile) f.length() else -1} path=$p",
            )
        }
    }

    private fun startRecording(status: TextView, record: Button, transcript: TextView) {
        val rec = recognizer ?: run {
            status.text = getString(R.string.sherpa_spike_not_initialized)
            return
        }
        if (!initMicrophone()) {
            status.text = getString(R.string.sherpa_spike_mic_failed)
            return
        }
        audioRecord!!.startRecording()
        record.setText(R.string.sherpa_spike_record_stop)
        isRecording = true
        transcript.text = ""
        val startNs = System.nanoTime()
        recordingThread = thread {
            val stream = rec.createStream("")
            val bufferSize = (0.1 * SAMPLE_RATE).toInt()
            val buffer = ShortArray(bufferSize)
            try {
                while (isRecording) {
                    val n = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val text = rec.getResult(stream).text
                    if (rec.isEndpoint(stream)) {
                        rec.reset(stream)
                    }
                    runOnUiThread { transcript.text = text }
                    val elapsed = (System.nanoTime() - startNs) / 1_000_000_000.0
                    Log.d(TAG, "t=${"%.2f".format(elapsed)}s partial=\"$text\"")
                }
            } finally {
                stream.release()
                val total = (System.nanoTime() - startNs) / 1_000_000_000.0
                Log.i(TAG, "recording thread exit total=${"%.2f".format(total)}s")
            }
        }
    }

    private fun stopRecording(record: Button) {
        isRecording = false
        recordingThread?.join(5000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        record.setText(R.string.sherpa_spike_record_start)
    }

    private fun initMicrophone(): Boolean {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            channel,
            format,
            min * 2,
        )
        return audioRecord?.state == AudioRecord.STATE_INITIALIZED
    }

    override fun onDestroy() {
        isRecording = false
        recordingThread?.join(2000)
        audioRecord?.release()
        recognizer?.release()
        recognizer = null
        super.onDestroy()
    }
}
