package com.whispertflite;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
import static android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.LiveTranscribePreferences;
import com.whispertflite.asr.OfflineAsrEngines;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.SpeechRecognizerBundles;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperLivePreviewLoop;
import com.whispertflite.asr.WhisperModelSelection;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.moonshine.MoonshineHoldRecorder;
import com.whispertflite.moonshine.MoonshinePreferences;
import com.whispertflite.parakeet.ParakeetStreamingRecorder;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;

import java.io.File;

public class WhisperRecognitionService extends RecognitionService {
    private static final String TAG = "WhisperRecognitionService";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private WhisperLivePreviewLoop whisperRsLiveLoop = null;
    private boolean recognitionWhisperLivePartials = false;
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private boolean recognitionCancelled = false;
    private SharedPreferences sp = null;
    private MoonshineHoldRecorder moonshineRecognitionRecorder = null;
    private ParakeetStreamingRecorder parakeetRecognitionRecorder = null;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        String targetLang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        MoonshinePreferences.migrateFromParakeetKeys(this);
        String langCode = sp.getString("recognitionServiceLanguage", "auto");
        int langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
        Log.d(TAG,"default langToken " + langToken);

        if (targetLang != null) {
            Log.d(TAG,"StartListening in " + targetLang);
            langCode = targetLang.split("[-_]")[0].toLowerCase();   //support both de_DE and de-DE
            langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
        } else {
            Log.d(TAG,"StartListening, no language specified");
        }

        checkRecordPermission(callback);

        sdcardDataFolder = this.getExternalFilesDir(null);
        selectedTfliteFile = WhisperModelSelection.tfliteFileForRecognitionService(sdcardDataFolder, sp, MULTI_LINGUAL_TOP_WORLD_SLOW);

        if (OfflineAsrEngines.moonshineSelectedAndReady(this)) {
            boolean moonshineLivePartials = LiveTranscribePreferences.isEnabled(sp);
            Log.i(TAG, "onStartListening: engine=moonshine livePartials=" + moonshineLivePartials);
            Handler mainHandler = new Handler(Looper.getMainLooper());
            moonshineRecognitionRecorder = new MoonshineHoldRecorder(this, mainHandler,
                    partial -> {
                        if (!moonshineLivePartials) return;
                        try {
                            callback.partialResults(SpeechRecognizerBundles.resultsRecognitionSingle(partial));
                        } catch (RemoteException e) {
                            throw new RuntimeException(e);
                        }
                    }, moonshineLivePartials);
            if (moonshineRecognitionRecorder.start()) {
                Log.d(TAG, "moonshine recorder started");
                try {
                    callback.beginningOfSpeech();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Log.w(TAG, "moonshine recorder start() failed");
                moonshineRecognitionRecorder = null;
                try {
                    callback.error(ERROR_CLIENT);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            return;
        }

        if (OfflineAsrEngines.parakeetSelectedAndReady(this, sdcardDataFolder)) {
            boolean livePartials = LiveTranscribePreferences.isEnabled(sp);
            Log.i(TAG, "onStartListening: engine=parakeet livePartials=" + livePartials
                    + " modelsDir=" + (sdcardDataFolder != null));
            Handler mainHandler = new Handler(Looper.getMainLooper());
            parakeetRecognitionRecorder = new ParakeetStreamingRecorder(this, sdcardDataFolder, mainHandler,
                    partial -> {
                        if (!livePartials) return;
                        try {
                            callback.partialResults(SpeechRecognizerBundles.resultsRecognitionSingle(partial));
                        } catch (RemoteException e) {
                            throw new RuntimeException(e);
                        }
                    });
            if (parakeetRecognitionRecorder.start()) {
                Log.d(TAG, "parakeet recorder started");
                try {
                    callback.beginningOfSpeech();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Log.w(TAG, "parakeet recorder start() failed (missing models or RECORD_AUDIO?)");
                parakeetRecognitionRecorder = null;
                try {
                    callback.error(ERROR_CLIENT);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            return;
        }

        Log.i(TAG, "onStartListening: engine=whisper model=" + selectedTfliteFile.getName()
                + " exists=" + selectedTfliteFile.exists());
        if (!selectedTfliteFile.exists()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    callback.error(ERROR_LANGUAGE_UNAVAILABLE);
                } else {
                    callback.error(ERROR_CLIENT);
                }
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        } else {
            initModel(selectedTfliteFile, callback, langToken);
            recognitionWhisperLivePartials = LiveTranscribePreferences.isEnabled(sp);

            mRecorder = new Recorder(this);
            mRecorder.setListener(message -> {
                if (message.equals(Recorder.MSG_RECORDING)){
                    try {
                        callback.rmsChanged(10);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                    if (recognitionWhisperLivePartials && mWhisper != null) {
                        stopWhisperRsLiveLoop();
                        whisperRsLiveLoop = new WhisperLivePreviewLoop(mainHandler, mRecorder, mWhisper,
                                partial -> {
                                    if (partial == null) {
                                        return;
                                    }
                                    try {
                                        callback.partialResults(
                                                SpeechRecognizerBundles.resultsRecognitionSingle(partial));
                                    } catch (RemoteException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                        whisperRsLiveLoop.start();
                    }
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    stopWhisperRsLiveLoop();
                    HapticFeedback.vibrate(this);
                    try {
                        callback.rmsChanged(-20.0f);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    stopWhisperRsLiveLoop();
                    try {
                        callback.error(ERROR_CLIENT);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            if (!mWhisper.isInProgress()) {
                HapticFeedback.vibrate(this);
                startRecording();
                try {
                    callback.beginningOfSpeech();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    private void stopRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
    }

    private void stopWhisperRsLiveLoop() {
        if (whisperRsLiveLoop != null) {
            whisperRsLiveLoop.stop();
            whisperRsLiveLoop = null;
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        Log.d(TAG,"cancel");
        stopWhisperRsLiveLoop();
        if (moonshineRecognitionRecorder != null) {
            moonshineRecognitionRecorder.stop();
            moonshineRecognitionRecorder = null;
        }
        if (parakeetRecognitionRecorder != null) {
            parakeetRecognitionRecorder.stop();
            parakeetRecognitionRecorder = null;
        }
        stopRecording();
        deinitModel();
        recognitionCancelled = true;
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.d(TAG, "onStopListening");
        if (moonshineRecognitionRecorder != null) {
            String fin = moonshineRecognitionRecorder.stop();
            moonshineRecognitionRecorder = null;
            Log.i(TAG, "onStopListening: moonshine finalLen=" + fin.length());
            try {
                callback.endOfSpeech();
                callback.results(SpeechRecognizerBundles.resultsRecognitionSingle(fin.trim()));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        if (parakeetRecognitionRecorder != null) {
            String fin = parakeetRecognitionRecorder.stop();
            parakeetRecognitionRecorder = null;
            Log.i(TAG, "onStopListening: parakeet finalLen=" + fin.length()
                    + " preview=\"" + (fin.length() > 64 ? fin.substring(0, 64) + "…" : fin) + "\"");
            try {
                callback.endOfSpeech();
                callback.results(SpeechRecognizerBundles.resultsRecognitionSingle(fin.trim()));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        stopRecording();
    }

    // Model initialization
    private void initModel(File modelFile, Callback callback, int langToken) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setLanguage(langToken);
        mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
        Log.d(TAG, "Language token " + langToken);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                if (whisperResult.getResult().trim().length() > 0){
                    Log.d(TAG, whisperResult.getResult().trim());
                    try {
                        callback.endOfSpeech();
                        deinitModel();

                        String result = whisperResult.getResult();
                        if (whisperResult.getLanguage().equals("zh")){
                            boolean simpleChinese = sp.getBoolean("RecognitionServiceSimpleChinese",false);
                            result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                        }

                        callback.results(SpeechRecognizerBundles.resultsRecognitionSingle(result.trim()));
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }

    private void startRecording() {
        mRecorder.initVad();
        mRecorder.start();
        recognitionCancelled = false;
    }

    private void startTranscription() {
        if (!recognitionCancelled){
            mainHandler.post(()-> {
                Toast toast = new Toast(this);
                toast.setDuration(Toast.LENGTH_SHORT);
                toast.setText(R.string.processing);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    toast.addCallback(new Toast.Callback() {
                        @Override
                        public void onToastHidden() {
                            super.onToastHidden();
                            if (mWhisper!=null) toast.show();
                        }
                    });
                }
                toast.show();
            });
            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
            mWhisper.start();
            Log.d(TAG,"Start Transcription");
        }
    }

    @Override
    public void onDestroy (){
        deinitModel();
    }
    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void checkRecordPermission(Callback callback) {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED){
            Log.d(TAG,getString(R.string.need_record_audio_permission));
            try {
                callback.error(ERROR_INSUFFICIENT_PERMISSIONS);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
