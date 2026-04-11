package com.whispertflite.engine;

import com.whispertflite.BuildConfig;
import com.whispertflite.asr.RecordBuffer;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.InputLang;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Whisper via whisper.cpp (GGML) and JNI. Model file is typically {@code ggml-*.bin} from
 * ggerganov/whisper.cpp on Hugging Face — tokenizer is embedded; DocWolle {@code filters_vocab_*.bin}
 * is ignored.
 */
public final class WhisperEngineCpp implements WhisperEngine {

    static {
        System.loadLibrary("whisper_jni");
    }

    private volatile boolean mInitialized;
    private long mNativeHandle;
    private boolean mMultilingual;

    private native long nativeInit(String modelPath);

    private native void nativeAbort(long handle);

    private native void nativeFree(long handle);

    /** @return [0]=text [1]=language code [2]=TRANSCRIBE or TRANSLATE */
    private native String[] nativeTranscribe(
            long handle,
            byte[] pcm16MonoLe,
            boolean translate,
            String languageOrNull,
            int nThreads);

    @Override
    public boolean isInitialized() {
        return mInitialized;
    }

    @Override
    public void initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        deinitialize();
        // vocabPath unused — ggml model carries tokenizer data
        if (BuildConfig.DEBUG && vocabPath != null) {
            android.util.Log.d("WhisperEngineCpp", "Ignoring vocab path for ggml backend: " + vocabPath);
        }
        mMultilingual = multilingual;
        mNativeHandle = nativeInit(modelPath);
        if (mNativeHandle == 0) {
            throw new IOException("whisper.cpp failed to load model: " + modelPath);
        }
        mInitialized = true;
    }

    @Override
    public void deinitialize() {
        if (mNativeHandle != 0) {
            nativeAbort(mNativeHandle);
            nativeFree(mNativeHandle);
            mNativeHandle = 0;
        }
        mInitialized = false;
    }

    @Override
    public WhisperResult processRecordBuffer(Whisper.Action action, int langToken) {
        byte[] pcm = RecordBuffer.getOutputBuffer();
        if (pcm == null || pcm.length < 2) {
            return new WhisperResult("", "", action);
        }
        return processPcm(action, langToken, pcm);
    }

    @Override
    public WhisperResult processPcm(Whisper.Action action, int langToken, byte[] pcm16MonoLe) {
        if (!mInitialized || mNativeHandle == 0 || pcm16MonoLe == null || pcm16MonoLe.length < 2) {
            return new WhisperResult("", "", action);
        }
        boolean translate = action == Whisper.Action.TRANSLATE;
        String lang = languageIsoForCpp(langToken);
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        String[] parts = nativeTranscribe(mNativeHandle, pcm16MonoLe, translate, lang, threads);
        if (parts == null || parts.length < 3) {
            return new WhisperResult("", "", action);
        }
        String text = parts[0] != null ? parts[0] : "";
        String langOut = parts[1] != null ? parts[1] : "";
        Whisper.Action taskOut = "TRANSLATE".equals(parts[2]) ? Whisper.Action.TRANSLATE : Whisper.Action.TRANSCRIBE;
        return new WhisperResult(text, langOut, taskOut);
    }

    /**
     * {@code null} means auto-detect (multilingual) or let native use defaults; English-only models use
     * {@code en} when no token is set.
     */
    private String languageIsoForCpp(int langToken) {
        if (mMultilingual) {
            if (langToken < 0) {
                return null;
            }
            ArrayList<InputLang> list = InputLang.getLangList();
            String code = InputLang.getLanguageCodeById(list, langToken);
            return code.isEmpty() ? null : code;
        }
        if (langToken >= 0) {
            ArrayList<InputLang> list = InputLang.getLangList();
            String code = InputLang.getLanguageCodeById(list, langToken);
            if (!code.isEmpty()) {
                return code;
            }
        }
        return "en";
    }
}
